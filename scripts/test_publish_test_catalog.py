import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from publish_test_catalog import publish


class TestCatalogPublishTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'channel'
        self.apk = Path(self.tmp.name) / 'upload.apk'
        self.apk.write_bytes(b'test apk')
        self.sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()

    def test_six_uploads_keep_only_five_with_individual_checksums(self):
        for n in range(6):
            publish(self.root, self.apk, f'test-{n}', f'app-{n}.apk', self.sha, 'notes')
        catalog = json.loads((self.root / 'releases.json').read_text())
        self.assertEqual([f'test-{n}' for n in (5, 4, 3, 2, 1)], [x['tag_name'] for x in catalog])
        self.assertFalse((self.root / 'builds/test-0').exists())
        self.assertEqual(5, len(list((self.root / 'builds').iterdir())))
        for entry in catalog:
            folder = self.root / 'builds' / entry['tag_name']
            name = entry['assets'][0]['name']
            self.assertEqual(self.apk.read_bytes(), (folder / name).read_bytes())
            self.assertIn(self.sha, (folder / 'SHA256SUMS.txt').read_text())

    def test_bad_upload_and_duplicate_keep_catalog_and_assets(self):
        publish(self.root, self.apk, 'test-1', 'app.apk', self.sha)
        old = (self.root / 'releases.json').read_bytes()
        with self.assertRaises(ValueError):
            publish(self.root, self.apk, 'test-2', 'app.apk', '0' * 64)
        with self.assertRaises(ValueError):
            publish(self.root, self.apk, 'test-1', 'app.apk', self.sha)
        self.assertEqual(old, (self.root / 'releases.json').read_bytes())
        self.assertTrue((self.root / 'builds/test-1/app.apk').exists())

    def test_rejects_unsafe_names_and_does_not_delete_unrelated_folder(self):
        with self.assertRaises(ValueError):
            publish(self.root, self.apk, '../bad', 'app.apk', self.sha)
        unrelated = self.root / 'builds/other'
        unrelated.mkdir(parents=True)
        for n in range(6):
            publish(self.root, self.apk, f'test-{n}', 'app.apk', self.sha)
        self.assertTrue(unrelated.is_dir())


if __name__ == '__main__':
    unittest.main()
