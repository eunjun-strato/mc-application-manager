"""Run with Python 3 on Linux: python3 src/test/python/test_object_storage_tunnel.py."""
import importlib.util
import json
import pathlib
import socket
import subprocess
import tempfile
import unittest
from unittest.mock import call, patch

ROOT = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/tunnel'
spec = importlib.util.spec_from_file_location('tunnel_remote', ROOT / 'remote.py')
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)
TID = 'a' * 32
CID = 'b' * 64


def data(action):
    return {'id': TID, 'container': CID, 'deployment': 1, 'action': action}


def container():
    return {'Id': CID, 'Image': 'sha256:test', 'State': {'Running': True},
            'Config': {'Labels': {'mcmp.managed': 'true', 'mcmp.deployment-id': '1'},
                       'Env': ['MCMP_OBJECT_STORAGE_GATEWAY_URL=http://127.0.0.1:18084/applications/object-storage-gateway']}}


class RemoteSafetyTest(unittest.TestCase):
    def test_inspect_returns_existing_container_without_daemon_probe(self):
        result = subprocess.CompletedProcess([], 0, json.dumps([container()]), '')
        with patch.object(remote.subprocess, 'run', return_value=result) as execute:
            self.assertEqual(remote.inspect(CID), container())
            execute.assert_called_once_with(
                remote.DOCKER + ['inspect', CID], capture_output=True, text=True, timeout=15)

    def test_inspect_accepts_old_and_new_missing_container_messages(self):
        name = 'mcmp-os-' + TID
        for message in (
                'Error: No such object: ' + name,
                'error: no such object: ' + name,
                'ERROR: NO SUCH OBJECT: ' + name,
                'Error response from daemon: No such container: ' + name,
                'error response from daemon: no such container: ' + name):
            with self.subTest(message=message), patch.object(remote.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 1, '[]\n', message + '\n'),
                    subprocess.CompletedProcess([], 0, 'daemon-id\n', '')]) as execute:
                self.assertIsNone(remote.inspect(name))
                self.assertEqual(execute.call_args_list, [
                    call(remote.DOCKER + ['inspect', name], capture_output=True, text=True, timeout=15),
                    call(remote.DOCKER + ['info', '--format', '{{.ID}}'],
                         capture_output=True, text=True, timeout=60)])

    def test_inspect_does_not_treat_unavailable_daemon_as_missing_container(self):
        name = 'mcmp-os-' + TID
        with patch.object(remote.subprocess, 'run', side_effect=[
                subprocess.CompletedProcess([], 1, '[]\n', 'error: no such object: ' + name),
                subprocess.CompletedProcess([], 1, '', 'Cannot connect to the Docker daemon')]):
            with self.assertRaisesRegex(RuntimeError, 'Remote tunnel operation failed'):
                remote.inspect(name)

    def test_inspect_does_not_ignore_other_errors_or_missing_objects(self):
        name = 'mcmp-os-' + TID
        for message in (
                'permission denied',
                'dial unix /var/run/docker.sock: No such file or directory',
                'error: no such object: unrelated-container',
                'Error: No such object: unrelated-container',
                'error: no such object: ' + name + '-other'):
            with self.subTest(message=message), patch.object(remote.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 1, '', message),
                    subprocess.CompletedProcess([], 0, 'daemon-id\n', '')]):
                with self.assertRaisesRegex(RuntimeError, 'Cannot inspect tunnel container'):
                    remote.inspect(name)

    def test_attach_continues_when_new_bridge_does_not_exist(self):
        name = 'mcmp-os-' + TID
        with patch.object(remote, 'target', return_value=container()), \
                patch.object(remote, 'owned_directory'), patch.object(remote, 'remove_socket'), \
                patch.object(remote.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 1, '[]\n', 'error: no such object: ' + name),
                    subprocess.CompletedProcess([], 0, 'daemon-id\n', ''),
                    subprocess.CompletedProcess([], 0, 'bridge-id\n', '')]) as execute:
            self.assertTrue(remote.main(data('attach'), 'test bridge')['attached'])
            arguments = execute.call_args.args[0]
            self.assertEqual(arguments[:len(remote.DOCKER) + 2], remote.DOCKER + ['run', '-d'])
            self.assertIn(name, arguments)
            self.assertEqual(execute.call_count, 3)

    def test_cleanup_continues_when_bridge_is_already_absent(self):
        name = 'mcmp-os-' + TID
        with patch.object(remote, 'owned_directory', return_value=False), \
                patch.object(remote, 'target') as parent, \
                patch.object(remote.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 1, '[]\n', 'error: no such object: ' + name),
                    subprocess.CompletedProcess([], 0, 'daemon-id\n', '')]) as execute:
            self.assertTrue(remote.main(data('remove'), '')['removed'])
            self.assertEqual(execute.call_count, 2)
            parent.assert_not_called()

    def test_rejects_untrusted_identifier(self):
        invalid = data('remove')
        invalid['id'] = '../somewhere'
        with self.assertRaises(ValueError):
            remote.validate(invalid)

    def test_creates_private_directory_with_ownership(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'owned'
            self.assertTrue(remote.owned_directory(directory, TID, create=True))
            self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
            self.assertEqual((directory / 'owner').read_text(), TID)

    def test_refuses_symlink_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'symlink'
            directory.symlink_to(temporary, target_is_directory=True)
            with self.assertRaises(RuntimeError):
                remote.owned_directory(directory, TID)
            self.assertTrue(directory.is_symlink())

    def test_refuses_wrong_ownership_marker(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'owned'
            remote.owned_directory(directory, TID, create=True)
            (directory / 'owner').write_text('wrong')
            with self.assertRaises(RuntimeError):
                remote.owned_directory(directory, TID)

    def test_preserves_non_socket_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            path = directory / 'gateway.sock'
            path.write_text('user data')
            with self.assertRaises(RuntimeError):
                remote.remove_socket(directory)
            self.assertEqual(path.read_text(), 'user data')

    def test_removes_only_owned_socket(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            with socket.socket(socket.AF_UNIX) as listener:
                listener.bind(str(directory / 'gateway.sock'))
                remote.remove_socket(directory)
                self.assertFalse((directory / 'gateway.sock').exists())

    def test_refuses_socket_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            (directory / 'gateway.sock').symlink_to(directory / 'missing')
            with self.assertRaises(RuntimeError):
                remote.remove_socket(directory)

    def test_never_removes_another_apps_bridge(self):
        with patch.object(remote, 'inspect', return_value={'Config': {'Labels': {}}}), patch.object(remote, 'run') as run:
            with self.assertRaises(RuntimeError):
                remote.remove_bridge('bridge', TID, CID)
            run.assert_not_called()

    def test_removes_bridge_with_both_matching_labels(self):
        bridge = {'Id': 'owned-bridge', 'Config': {'Labels': {'mcmp.tunnel-id': TID, 'mcmp.tunnel-target': CID}}}
        with patch.object(remote, 'inspect', return_value=bridge), patch.object(remote, 'run') as run:
            remote.remove_bridge('bridge', TID, CID)
            self.assertEqual(run.call_args.args[0][-3:], ['rm', '-f', 'owned-bridge'])

    def test_requires_parent_container_deployment_label(self):
        wrong = container()
        wrong['Config']['Labels']['mcmp.deployment-id'] = '2'
        with patch.object(remote, 'inspect', return_value=wrong):
            with self.assertRaises(RuntimeError):
                remote.target(data('probe'))

    def test_refuses_to_adopt_legacy_direct_gateway(self):
        wrong = container()
        wrong['Config']['Env'] = []
        with patch.object(remote, 'inspect', return_value=wrong):
            with self.assertRaises(RuntimeError):
                remote.target(data('probe'))

    def test_attach_does_not_publish_host_ports_or_mount_docker_socket(self):
        with patch.object(remote, 'target', return_value=container()), patch.object(remote, 'owned_directory'), \
             patch.object(remote, 'remove_bridge'), patch.object(remote, 'remove_socket'), patch.object(remote, 'run') as run:
            remote.main(data('attach'), 'test bridge')
            arguments = run.call_args.args[0]
            self.assertIn('container:' + CID, arguments)
            self.assertIn('--read-only', arguments)
            self.assertIn('--cap-drop', arguments)
            self.assertIn('no-new-privileges', arguments)
            self.assertNotIn('-p', arguments)
            self.assertNotIn('--privileged', arguments)
            self.assertNotIn('/var/run/docker.sock', ' '.join(arguments))

    def test_cleanup_without_parent_container_is_idempotent(self):
        with patch.object(remote, 'remove_bridge'), patch.object(remote, 'owned_directory', return_value=False), \
             patch.object(remote, 'target') as parent:
            self.assertTrue(remote.main(data('remove'), '')['removed'])
            parent.assert_not_called()


if __name__ == '__main__':
    unittest.main()
