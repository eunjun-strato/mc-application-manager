"""Run the real notebook transfer helpers with a stubbed HTTP client (stdlib only)."""
import ast
import json
from pathlib import Path
import types
import unittest
from unittest.mock import MagicMock, Mock


NOTEBOOK = Path(__file__).resolve().parents[2] / 'main/resources/notebooks/object-storage.ipynb'


class NotebookHeaderTests(unittest.TestCase):
    def setUp(self):
        self.alias = 'ncp'
        self.http = types.SimpleNamespace(request=Mock(), RequestException=OSError)
        self.ticket = dict(method='GET', presignedURL='https://KR.object.ncloudstorage.com/bucket/a.csv',
                           requiredHeaders={'Host': 'KR.object.ncloudstorage.com', 'x-required': 'value'})
        self.ns = {'requests': self.http, '_mcmp': Mock(return_value=self.ticket), 'Path': Mock()}
        notebook = json.loads(NOTEBOOK.read_text(encoding='utf-8'))
        for cell in notebook['cells']:
            if cell.get('id') not in ('mcmp-access', 'mcmp-analysis-helpers'):
                continue
            tree = ast.parse(''.join(cell['source']))
            functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                         and node.name in ('_presigned', 'download', 'upload', '_preview_bytes')]
            exec(compile(ast.Module(body=functions, type_ignores=[]), cell['id'], 'exec'), self.ns)

    def assert_required_headers_forwarded(self):
        args, kwargs = self.http.request.call_args
        self.assertEqual(args[:2], (self.ticket['method'], self.ticket['presignedURL']))
        self.assertEqual(kwargs['headers'], self.ticket['requiredHeaders'])

    def test_download_preserves_signed_host(self):
        self.http.request.return_value = Mock(content=b'csv-data')
        self.assertEqual(self.ns['download'](self.alias, 'a.csv'), b'csv-data')
        self.assert_required_headers_forwarded()

    def test_preview_preserves_signed_host(self):
        response = MagicMock(ok=True)
        response.__enter__.return_value = response
        response.iter_content.return_value = [b'csv-data']
        self.http.request.return_value = response
        self.assertEqual(self.ns['_preview_bytes'](self.alias, 'a.csv', 1024), b'csv-data')
        self.assert_required_headers_forwarded()

    def test_upload_preserves_signed_host(self):
        self.ticket['method'] = 'PUT'
        self.ns['Path'].return_value.open.return_value = MagicMock()
        self.assertEqual(self.ns['upload'](self.alias, 'local.csv', 'a.csv'), 'a.csv')
        self.assert_required_headers_forwarded()

    def test_provider_without_required_headers_still_works(self):
        self.ticket.pop('requiredHeaders')
        self.ns['download']('other', 'a.csv')
        self.assertEqual(self.http.request.call_args.kwargs['headers'], {})


class NhnNotebookHeaderTests(NotebookHeaderTests):
    def setUp(self):
        super().setUp()
        self.alias = 'nhn'
        self.ticket['presignedURL'] = 'https://KR1-api-object-storage.nhncloudservice.com/bucket/a.csv'
        self.ticket['requiredHeaders']['Host'] = 'KR1-api-object-storage.nhncloudservice.com'


if __name__ == '__main__':
    unittest.main(verbosity=2)
