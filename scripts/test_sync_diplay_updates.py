import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from sync_diplay_updates import sync, RELEASES_URL, CHECKSUMS


class SyncUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base = 'https://updates.example.com/diplay-updates'
        self.apk = b'published apk bytes'
        self.apk_url = 'https://github.com/lianjiawei/DiPlay/releases/download/v1.1.6/CarPlay-1.1.6.apk'
        self.sums_url = 'https://github.com/lianjiawei/DiPlay/releases/download/v1.1.6/' + CHECKSUMS
        self.responses = {
            RELEASES_URL: json.dumps([{'tag_name': 'v1.1.6', 'body': 'Release notes', 'assets': [
                {'name': 'CarPlay-1.1.6.apk', 'browser_download_url': self.apk_url},
                {'name': CHECKSUMS, 'browser_download_url': self.sums_url}]}]).encode(),
            self.apk_url: self.apk,
            self.sums_url: (hashlib.sha256(self.apk).hexdigest() + '  CarPlay-1.1.6.apk\n').encode(),
        }
        self.requests = []

    def fetch(self, url, path, limit):
        self.requests.append(url)
        body = self.responses[url]
        self.assertLessEqual(len(body), limit)
        path.write_bytes(body)

    def test_catalog_and_both_assets_are_served_by_the_mirror(self):
        self.assertEqual(1, sync(self.root, self.base, self.fetch))
        catalog = json.loads((self.root / 'releases.json').read_text())
        self.assertEqual('Release notes', catalog[0]['body'])
        self.assertTrue(all(a['browser_download_url'].startswith(self.base + '/assets/v1.1.6/')
                            for a in catalog[0]['assets']))
        self.assertEqual(self.apk, (self.root / 'assets/v1.1.6/CarPlay-1.1.6.apk').read_bytes())

    def test_bad_apk_preserves_the_previous_catalog(self):
        old = '[{"tag_name":"v1.1.3"}]'
        (self.root / 'releases.json').write_text(old)
        self.responses[self.apk_url] = b'tampered apk'
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            sync(self.root, self.base, self.fetch)
        self.assertEqual(old, (self.root / 'releases.json').read_text())
        self.assertFalse((self.root / 'assets/v1.1.6/CarPlay-1.1.6.apk').exists())

    def test_verified_cached_apk_is_not_downloaded_again(self):
        sync(self.root, self.base, self.fetch)
        self.requests.clear()
        sync(self.root, self.base, self.fetch)
        self.assertNotIn(self.apk_url, self.requests)

    def test_upstream_failure_keeps_the_published_catalog(self):
        sync(self.root, self.base, self.fetch)
        previous = (self.root / 'releases.json').read_bytes()
        def offline(*args):
            raise OSError('upstream offline')
        with self.assertRaises(OSError):
            sync(self.root, self.base, offline)
        self.assertEqual(previous, (self.root / 'releases.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
