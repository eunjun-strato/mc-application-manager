"""Fixed AM-managed VM helper. DATA and BRIDGE_CODE are supplied by the Java runtime."""
import json
import os
import pathlib
import pwd
import re
import stat
import subprocess
import tempfile

DOCKER = ([] if os.geteuid() == 0 else ['sudo', '-n']) + ['docker']


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, timeout=60, **kwargs)
    if result.returncode:
        # Docker/requests errors may contain commands, environment or signed URLs.
        raise RuntimeError('Remote tunnel operation failed')
    return result.stdout


def inspect(name):
    result = subprocess.run(DOCKER + ['inspect', name], capture_output=True, text=True, timeout=15)
    if result.returncode:
        # Only absence is idempotent. An unavailable daemon is NOT successful deletion.
        run(DOCKER + ['info', '--format', '{{.ID}}'])
        # Docker versions vary the capitalization and use either object or container.
        # Match this exact target, not unrelated errors such as "No such file or directory".
        missing = re.search(r'\bno such (?:object|container):\s*' + re.escape(name) + r'\s*\Z',
                            result.stderr, re.IGNORECASE)
        if missing:
            return None
        raise RuntimeError('Cannot inspect tunnel container')
    return json.loads(result.stdout)[0]


def validate(data):
    if not re.fullmatch(r'[a-f0-9]{32}', data['id']):
        raise ValueError('Invalid tunnel ID')
    if not re.fullmatch(r'[a-f0-9]{64}', data['container']):
        raise ValueError('Invalid container ID')
    if not isinstance(data['deployment'], int) or data['deployment'] <= 0:
        raise ValueError('Invalid deployment ID')


def target(data):
    container = inspect(data['container'])
    if not container:
        raise RuntimeError('Jupyter container is missing')
    labels = container['Config'].get('Labels') or {}
    if labels.get('mcmp.managed') != 'true' or labels.get('mcmp.deployment-id') != str(data['deployment']):
        raise RuntimeError('Jupyter container ownership mismatch')
    env = dict(x.split('=', 1) for x in container['Config'].get('Env', []) if '=' in x)
    if env.get('MCMP_OBJECT_STORAGE_GATEWAY_URL') != 'http://127.0.0.1:18084/applications/object-storage-gateway':
        raise RuntimeError('Jupyter was not installed with managed SSH transport')
    return container


def owned_directory(directory, tunnel_id, create=False):
    if not directory.exists() and not directory.is_symlink():
        if not create:
            return False
        directory.mkdir(mode=0o700)
        try:
            marker = directory / 'owner'
            with marker.open('x') as stream:
                stream.write(tunnel_id)
            marker.chmod(0o600)
        except Exception:
            # Only an empty directory created by this invocation may be removed.
            if not any(directory.iterdir()):
                directory.rmdir()
            raise
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise RuntimeError('Refusing unowned tunnel directory')
    descriptor = os.open(directory / 'owner', os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as stream:
        marker_info = os.fstat(stream.fileno())
        if not stat.S_ISREG(marker_info.st_mode) or marker_info.st_uid != os.getuid() or stream.read(33) != tunnel_id:
            raise RuntimeError('Tunnel ownership marker mismatch')
    return True


def remove_socket(directory):
    path = directory / 'gateway.sock'
    try:
        info = path.lstat()
    except FileNotFoundError:
        return
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
        raise RuntimeError('Refusing to remove a non-socket or unowned path')
    path.unlink()


def remove_bridge(name, tunnel_id, container_id):
    bridge = inspect(name)
    if bridge:
        labels = bridge['Config'].get('Labels') or {}
        if labels.get('mcmp.tunnel-id') != tunnel_id or labels.get('mcmp.tunnel-target') != container_id:
            raise RuntimeError('Refusing to remove an unrelated container')
        run(DOCKER + ['rm', '-f', bridge['Id']])


def main(data, bridge_code):
    validate(data)
    directory = pathlib.Path('/tmp/mcmp-os-' + data['id'])
    name = 'mcmp-os-' + data['id']
    action = data['action']
    if action == 'remove':
        # Do not depend on the parent container still existing.
        remove_bridge(name, data['id'], data['container'])
        if owned_directory(directory, data['id']):
            remove_socket(directory)
            # Never recursively delete; unknown files cause failure and remain intact.
            if {p.name for p in directory.iterdir()} != {'owner'}:
                raise RuntimeError('Unexpected file in tunnel directory')
            (directory / 'owner').unlink()
            directory.rmdir()
        return {'removed': True}
    container = target(data)
    if action == 'probe':
        key = pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().strip().split()
        if len(key) < 2 or key[0] != 'ssh-ed25519':
            raise RuntimeError('SSH host public key unavailable')
        return {'user': pwd.getpwuid(os.getuid()).pw_name, 'uid': os.getuid(),
                'hostKey': ' '.join(key[:2]), 'running': container['State']['Running']}
    if not container['State']['Running']:
        return {'running': False, 'ready': False}
    if action == 'attach':
        owned_directory(directory, data['id'], create=True)
        remove_bridge(name, data['id'], data['container'])
        remove_socket(directory)
        run(DOCKER + ['run', '-d', '--name', name,
             '--label', 'mcmp.tunnel-id=' + data['id'],
             '--label', 'mcmp.tunnel-target=' + data['container'],
             '--restart', 'unless-stopped', '--network', 'container:' + data['container'],
             '--user', str(os.getuid()), '--read-only', '--cap-drop', 'ALL',
             '--security-opt', 'no-new-privileges', '--memory', '96m', '--pids-limit', '64',
             '--mount', 'type=bind,source=' + str(directory) + ',target=/run/mcmp-tunnel,readonly',
             '--entrypoint', 'python', container['Image'], '-u', '-c', bridge_code])
        return {'attached': True}
    if action not in {'check', 'verify'}:
        raise ValueError('Unknown tunnel action')
    # Execute in the SAME network namespace and with the existing deployment token.
    check = """
import json,os,time,urllib.request
def request(path, authorized=False):
 headers={'Authorization':'Bearer '+os.environ['MCMP_OBJECT_STORAGE_TOKEN']} if authorized else {}
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18084'+path,headers=headers),timeout=10) as r:
  return json.load(r)
try:
 request('/healthz')
 if VERIFY:
  prefix='/applications/object-storage-gateway'
  data=request(prefix+'/storages',True)
  assert data.get('code')==200 and data.get('data'), 'No grants'
  import urllib.parse
  alias=data['data'][0]['alias']
  listed=request(prefix+'/objects?'+urllib.parse.urlencode({'storage':alias}),True)
  assert listed.get('code')==200, 'Object listing failed'
 print(json.dumps({'running':True,'ready':True}))
except Exception:
 print(json.dumps({'running':True,'ready':False}))
"""
    output = run(DOCKER + ['exec', '-i', data['container'], 'python', '-'],
                 input=check.replace('VERIFY', repr(action == 'verify')))
    return json.loads(output)


if __name__ == '__main__':
    try:
        print(json.dumps(main(DATA, BRIDGE_CODE)))
    except Exception:
        # No tracebacks: inputs and requests may include credentials.
        print('Managed Object Storage tunnel operation failed', file=__import__('sys').stderr)
        raise SystemExit(1)
