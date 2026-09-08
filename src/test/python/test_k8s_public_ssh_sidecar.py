"""Real public-image smoke test; Docker required, no host ports or registry login.

The official Python image supplies only the Python HTTP fixture; the SSH server
and client, account mounts, startup and readiness run in the unmodified public image.
"""
import json
from pathlib import Path
import subprocess
import time
import uuid

IMAGE = 'lscr.io/linuxserver/openssh-server@sha256:39ba37d50fdd6be1bf70644c871e5dcb9234ee79ac56424ea03ca08cadf1e7b0'
RESOURCES = Path(__file__).resolve().parents[2] / 'main' / 'resources' / 'k8s-ssh'
NAME = 'mcmp-public-ssh-test-' + uuid.uuid4().hex[:10]
FIXTURE = NAME + '-http'

def docker(*args, check=True, timeout=30):
    return subprocess.run(['docker', *args], text=True, encoding='utf-8', errors='replace', capture_output=True, check=check, timeout=timeout)

def execute(*args, check=True):
    return docker('exec', NAME, *args, check=check)

def ready():
    return execute('/bin/sh', '/opt/mcmp-ssh/health.sh', check=False).returncode == 0

def await_health(expected):
    for _ in range(20):
        if ready() == expected:
            return
        time.sleep(.3)
    logs = execute('/bin/sh', '-c', 'cat /run/mcmp-ssh/server.log /run/mcmp-ssh/client.log', check=False).stdout
    raise AssertionError('readiness did not become ' + str(expected) + '\n' + logs)

base = ['ssh', '-F', '/dev/null', '-T', '-n', '-i', '/run/mcmp-ssh/client',
        '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
        '-o', 'HostKeyAlias=mcmp-test', '-o', 'UserKnownHostsFile=/run/mcmp-ssh/known_hosts',
        '-o', 'ExitOnForwardFailure=yes', '-o', 'ConnectTimeout=2', '-p', '2222', '-l', 'mcmp']

def connect():
    command = ' '.join(base + ['-N', '-R', '127.0.0.1:18084:127.0.0.1:19084', '127.0.0.1'])
    execute('/bin/sh', '-c', command + ' >/run/mcmp-ssh/client.log 2>&1 & echo $! >/run/mcmp-ssh/client.pid')

try:
    mounts = ['--mount', f'type=bind,source={RESOURCES},target=/opt/mcmp-ssh,readonly']
    for file in ('passwd', 'shadow', 'group'):
        mounts += ['--mount', f'type=bind,source={RESOURCES / file},target=/etc/{file},readonly']
    docker('run', '-d', '--name', NAME, '--read-only', '--tmpfs', '/run/mcmp-ssh',
           '--tmpfs', '/etc/mcmp-ssh', '--cap-drop', 'ALL', '--cap-add', 'SETUID',
           '--cap-add', 'SETGID', '--cap-add', 'SYS_CHROOT', '--cap-add', 'CHOWN',
           '--security-opt', 'no-new-privileges', *mounts, '--entrypoint', '/bin/sh',
           IMAGE, '-c', 'exec sleep 300')
    execute('/bin/sh', '-c', '''set -eu
ssh-keygen -q -t ed25519 -N '' -f /run/mcmp-ssh/client
ssh-keygen -q -t ed25519 -N '' -f /etc/mcmp-ssh/host-key
printf 'restrict,port-forwarding,permitlisten="127.0.0.1:18084" ' > /etc/mcmp-ssh/authorized_keys
cat /run/mcmp-ssh/client.pub >> /etc/mcmp-ssh/authorized_keys
printf 'mcmp-test ' > /run/mcmp-ssh/known_hosts
cat /etc/mcmp-ssh/host-key.pub >> /run/mcmp-ssh/known_hosts
''')
    docker('exec', '-d', NAME, '/bin/sh', '-c', '/bin/sh /opt/mcmp-ssh/start.sh >/run/mcmp-ssh/server.log 2>&1')
    docker('run', '-d', '--name', FIXTURE, '--network', 'container:' + NAME,
           '--entrypoint', 'python3', 'python:3.12-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254', '-c', '''
import http.server
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Length', '7')
        self.end_headers()
        self.wfile.write(b'fixture')
    def log_message(self, *args): pass
http.server.ThreadingHTTPServer(('127.0.0.1',19084),Handler).serve_forever()
''')
    time.sleep(1)
    assert not ready(), 'readiness succeeded before tunnel'
    connect()
    await_health(True)
    docker('exec', FIXTURE, 'python3', '-c', '''
import http.client,time
c=http.client.HTTPConnection('127.0.0.1',18084,timeout=3)
c.connect(); original=c.sock
for _ in range(6):
    c.request('GET','/applications/object-storage-gateway/storages')
    r=c.getresponse()
    assert r.status == 200 and r.read() == b'fixture'
    assert c.sock is original
    time.sleep(2)
c.close()
''')
    assert execute(*base, '127.0.0.1', 'echo forbidden', check=False).returncode != 0
    for binding in ('127.0.0.1:18085', '0.0.0.0:18084'):
        assert execute(*base, '-N', '-R', binding + ':127.0.0.1:19084', '127.0.0.1', check=False).returncode != 0
    networks = json.loads(docker('inspect', NAME).stdout)[0]['NetworkSettings']['Networks']
    ip = next(iter(networks.values()))['IPAddress']
    assert execute('nc', '-z', '-w', '2', ip, '2222', check=False).returncode != 0
    execute('/bin/sh', '-c', 'kill "$(cat /run/mcmp-ssh/client.pid)"')
    await_health(False)
    connect()
    await_health(True)
    print('PASS: public image, read-only account mounts, reverse HTTP, persistent TCP, shell/port/public-bind denial, loopback-only SSH, readiness loss and reconnect')
finally:
    docker('rm', '-f', FIXTURE, NAME, check=False)
