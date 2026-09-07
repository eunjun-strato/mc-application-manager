"""Execute notebook cells against stubbed storage; never contact a CSP."""
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

import matplotlib
matplotlib.use('Agg')
import pandas as pd
import requests

NOTEBOOK = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
CELLS = {c['id']: ''.join(c['source']) for c in NOTEBOOK['cells'] if c['cell_type'] == 'code'}
CSV = b'region,usage\nseoul,2\ntokyo,3\nseoul,4\n'


class NotebookTests(unittest.TestCase):
    def setUp(self):
        self.ns = {}
        with patch.dict(os.environ, {'MCMP_OBJECT_STORAGE_GATEWAY_URL': 'http://example.invalid',
                                    'MCMP_OBJECT_STORAGE_TOKEN': 'unit-test-only'}):
            self.run_cell('mcmp-access')
            self.run_cell('mcmp-analysis-helpers')
        self.ns['display'] = lambda *args: None
        self.ns['storages'] = lambda: [dict(alias='bucket-a', accessMode='READ_ONLY', prefix='data/')]
        self.ns['list_objects'] = lambda alias, prefix: pd.DataFrame([
            dict(key='data/test.csv', size=len(CSV)), dict(key='data/ignored.txt', size=4)])
        self.ns['_preview_bytes'] = Mock(return_value=CSV)
        self.ns['upload'] = Mock(side_effect=AssertionError('No automatic writes allowed'))
        self.run_cell('mcmp-storages')
        self.run_cell('mcmp-settings')

    def run_cell(self, cell_id):
        exec(compile(CELLS[cell_id], cell_id, 'exec'), self.ns)

    def test_every_cell_has_valid_syntax_and_no_saved_outputs(self):
        for cell_id, source in CELLS.items():
            compile(source, cell_id, 'exec')
        for cell in NOTEBOOK['cells']:
            if cell['cell_type'] == 'code':
                self.assertEqual(cell['outputs'], [])
                self.assertIsNone(cell['execution_count'])

    def test_default_flow_reads_csv_and_aggregates_without_upload(self):
        self.run_cell('mcmp-objects')
        self.run_cell('mcmp-preview')
        with patch('matplotlib.pyplot.show') as show:
            self.run_cell('mcmp-chart')
            show.assert_called_once()
        self.assertEqual(self.ns['selected_key'], 'data/test.csv')
        self.assertEqual(len(self.ns['df']), 3)
        self.assertEqual(dict(zip(self.ns['summary'].group_key, self.ns['summary'].value)),
                         {'seoul': 6, 'tokyo': 3})
        self.run_cell('mcmp-export')
        self.ns['upload'].assert_not_called()

    def test_default_flow_loads_every_granted_storage(self):
        self.ns['storages'] = lambda: [
            dict(alias='bucket-a', provider='aws', accessMode='READ_ONLY', prefix='data/'),
            dict(alias='bucket-b', provider='ncp', accessMode='READ_ONLY', prefix='data/'),
        ]
        listing = Mock(return_value=pd.DataFrame([dict(key='data/test.csv', size=len(CSV))]))
        self.ns['list_objects'] = listing
        self.run_cell('mcmp-storages')
        self.run_cell('mcmp-settings')
        self.run_cell('mcmp-objects')
        self.run_cell('mcmp-preview')

        self.assertEqual(set(self.ns['loaded_data']), {'bucket-a', 'bucket-b'})
        self.assertEqual(set(self.ns['selected_keys']), {'bucket-a', 'bucket-b'})
        self.assertEqual(listing.call_count, 2)
        self.assertEqual(self.ns['_preview_bytes'].call_count, 2)

    def test_empty_bucket_clears_stale_data_and_skips_chart(self):
        self.ns['df'] = pd.DataFrame({'old': [9]})
        self.ns['list_objects'] = lambda *args: pd.DataFrame()
        self.run_cell('mcmp-objects')
        self.run_cell('mcmp-preview')
        with patch('matplotlib.pyplot.show') as show:
            self.run_cell('mcmp-chart')
            show.assert_not_called()
        self.assertIsNone(self.ns['df'])
        self.assertIsNone(self.ns['summary'])

    def test_no_storage_or_invalid_alias(self):
        self.ns['available_storages'] = []
        self.run_cell('mcmp-objects')
        self.run_cell('mcmp-preview')
        self.assertIsNone(self.ns['df'])
        self.run_cell('mcmp-storages')
        self.ns['STORAGE_ALIAS'] = 'not-granted'
        with self.assertRaisesRegex(ValueError, 'STORAGE_ALIAS'):
            self.run_cell('mcmp-objects')

    def test_prefix_is_forwarded_without_modification(self):
        self.ns['OBJECT_PREFIX'] = 'data/team-a/'
        listing = Mock(return_value=pd.DataFrame())
        self.ns['list_objects'] = listing
        self.run_cell('mcmp-objects')
        listing.assert_called_once_with('bucket-a', 'data/team-a/')

    def test_selection_rejects_outside_prefix_and_oversized_files(self):
        choose = self.ns['_choose_preview_key']
        objects = [dict(key='data/big.csv', size=100), dict(key='data/a.parquet', size=2),
                   dict(key='data/z.csv', size=2), dict(key='data/.hidden.csv', size=1)]
        self.assertEqual(choose(objects, None, 5), 'data/z.csv')
        with self.assertRaisesRegex(ValueError, 'OBJECT_KEY'):
            choose(objects, 'other/outside.csv', 5)
        with self.assertRaisesRegex(ValueError, 'MAX_DOWNLOAD_MB'):
            choose(objects, 'data/big.csv', 5)

    def test_explicit_parquet_file(self):
        self.ns['list_objects'] = lambda *args: pd.DataFrame([dict(key='data/a.parquet', size=10)])
        self.ns['OBJECT_KEY'] = 'data/a.parquet'
        self.run_cell('mcmp-objects')
        expected = pd.DataFrame({'region': ['seoul'], 'usage': [2]})
        with patch.object(pd, 'read_parquet', return_value=expected) as read:
            self.run_cell('mcmp-preview')
            read.assert_called_once()
        pd.testing.assert_frame_equal(self.ns['df'], expected)

    def test_missing_parquet_engine_does_not_keep_old_data(self):
        self.ns['list_objects'] = lambda *args: pd.DataFrame([dict(key='data/a.parquet', size=10)])
        self.run_cell('mcmp-objects')
        self.ns['df'] = pd.DataFrame({'stale': [1]})
        with patch.object(pd, 'read_parquet', side_effect=ImportError):
            self.run_cell('mcmp-preview')
        self.assertIsNone(self.ns['df'])

    def test_numeric_column_validation_empty_and_nonfinite_values(self):
        summarize = self.ns['_summarize']
        self.assertIsNone(summarize(pd.DataFrame({'region': ['seoul'], 'label': ['x']}))[0])
        frame = pd.DataFrame({'region': ['s', 's'], 'usage': [float('nan'), float('inf')]})
        self.assertIsNone(summarize(frame)[0])
        with self.assertRaisesRegex(ValueError, 'AGGREGATION'):
            summarize(pd.read_csv(io.BytesIO(CSV)), aggregation='arbitrary')
        with self.assertRaisesRegex(ValueError, 'numeric'):
            summarize(pd.read_csv(io.BytesIO(CSV)), value_col='region')
        summary, _, _ = summarize(pd.read_csv(io.BytesIO(CSV)), aggregation='mean')
        self.assertEqual(dict(zip(summary.group_key, summary.value)), {'seoul': 3, 'tokyo': 3})

    def test_actual_stream_limit_and_url_redaction(self):
        self.run_cell('mcmp-analysis-helpers')
        self.ns['_presigned'] = lambda *args: dict(method='GET', presignedURL='https://example.invalid/SECRET')
        response = Mock(ok=True)
        response.iter_content.return_value = [b'1234', b'5678']
        manager = Mock()
        manager.__enter__ = Mock(return_value=response)
        manager.__exit__ = Mock(return_value=False)
        with patch.object(requests, 'request', return_value=manager):
            with self.assertRaisesRegex(ValueError, 'MAX_DOWNLOAD_MB'):
                self.ns['_preview_bytes']('bucket-a', 'data/test.csv', 5)
        with patch.object(requests, 'request', side_effect=requests.ConnectionError('SECRET')):
            with self.assertRaises(RuntimeError) as error:
                self.ns['_preview_bytes']('bucket-a', 'data/test.csv', 5)
            self.assertNotIn('SECRET', str(error.exception))


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]], verbosity=2)
