import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from sync_diplay_updates import sync, download, RELEASES_URL, CHECKSUMS


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

    def add_api_asset_urls(self):
        releases = json.loads(self.responses[RELEASES_URL])
        apk_api = 'https://api.github.com/repos/lianjiawei/DiPlay/releases/assets/101'
        sums_api = 'https://api.github.com/repos/lianjiawei/DiPlay/releases/assets/102'
        releases[0]['assets'][0]['url'] = apk_api
        releases[0]['assets'][1]['url'] = sums_api
        self.responses[RELEASES_URL] = json.dumps(releases).encode()
        self.responses[apk_api] = self.responses[self.apk_url]
        self.responses[sums_api] = self.responses[self.sums_url]
        return apk_api, sums_api

    def test_downloads_public_assets_via_api_when_github_site_is_unreachable(self):
        apk_api, sums_api = self.add_api_asset_urls()
        self.assertEqual(1, sync(self.root, self.base, self.fetch))
        self.assertIn(apk_api, self.requests)
        self.assertIn(sums_api, self.requests)
        self.assertNotIn(self.apk_url, self.requests)
        self.assertNotIn(self.sums_url, self.requests)
        self.assertEqual(self.apk, (self.root / 'assets/v1.1.6/CarPlay-1.1.6.apk').read_bytes())

    def test_falls_back_to_browser_url_if_api_asset_is_unavailable(self):
        apk_api, sums_api = self.add_api_asset_urls()
        def offline_api(url, path, limit):
            if url in (apk_api, sums_api):
                self.requests.append(url)
                raise OSError('API unavailable')
            return self.fetch(url, path, limit)
        self.assertEqual(1, sync(self.root, self.base, offline_api))
        self.assertIn(self.apk_url, self.requests)
        self.assertIn(self.sums_url, self.requests)

    def test_untrusted_api_url_is_never_requested(self):
        releases = json.loads(self.responses[RELEASES_URL])
        releases[0]['assets'][0]['url'] = 'https://attacker.example/releases/assets/101'
        self.responses[RELEASES_URL] = json.dumps(releases).encode()
        self.assertEqual(1, sync(self.root, self.base, self.fetch))
        self.assertIn(self.apk_url, self.requests)
        self.assertTrue(all(not url.startswith('https://attacker.example/') for url in self.requests))

    def test_api_checksum_mismatch_preserves_the_existing_catalog(self):
        sync(self.root, self.base, self.fetch)
        previous = (self.root / 'releases.json').read_bytes()
        (self.root / 'assets/v1.1.6/CarPlay-1.1.6.apk').unlink()
        apk_api, _ = self.add_api_asset_urls()
        self.responses[apk_api] = b'tampered apk'
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            sync(self.root, self.base, self.fetch)
        self.assertEqual(previous, (self.root / 'releases.json').read_bytes())

    def test_api_request_asks_for_binary_and_rejects_metadata(self):
        api_url = 'https://api.github.com/repos/lianjiawei/DiPlay/releases/assets/102'
        class Response(io.BytesIO):
            headers = type('Headers', (), {'get_content_type': lambda self: 'application/json'})()
        def metadata(request, timeout):
            self.assertEqual('application/octet-stream', request.get_header('Accept'))
            return Response(b'{"name":"SHA256SUMS.txt"}')
        with patch('sync_diplay_updates.urllib.request.urlopen', metadata), patch('sync_diplay_updates.time.sleep'):
            with self.assertRaisesRegex(OSError, 'metadata instead of the file'):
                download(api_url, self.root / 'result', 1024)

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

    def test_retains_latest_three_verified_stable_releases_and_prunes_old_assets(self):
        releases = []
        for tag, prerelease in [('v1.2.1-rc1', True), ('v1.2.0', False),
                                ('v1.1.9', False), ('v1.1.8', False), ('v1.1.7', False)]:
            name = f'CarPlay-{tag}.apk'
            url = f'https://github.com/lianjiawei/DiPlay/releases/download/{tag}/'
            apk_url, sums_url = url + name, url + CHECKSUMS
            data = tag.encode()
            self.responses[apk_url] = data
            self.responses[sums_url] = (hashlib.sha256(data).hexdigest() + f'  {name}\n').encode()
            releases.append({'tag_name': tag, 'prerelease': prerelease, 'assets': [
                {'name': name, 'browser_download_url': apk_url},
                {'name': CHECKSUMS, 'browser_download_url': sums_url}]})
        self.responses[RELEASES_URL] = json.dumps(releases).encode()
        old = self.root / 'assets' / 'v1.1.7'
        old.mkdir(parents=True)
        (old / 'old.apk').write_bytes(b'old')
        unrelated = self.root / 'assets' / 'other-content'
        unrelated.mkdir()
        self.assertEqual(3, sync(self.root, self.base, self.fetch))
        self.assertEqual(['v1.2.0', 'v1.1.9', 'v1.1.8'], [
            r['tag_name'] for r in json.loads((self.root / 'releases.json').read_text())])
        self.assertFalse(old.exists())
        self.assertTrue(unrelated.exists())
        self.assertFalse((self.root / 'assets' / 'v1.2.1-rc1').exists())
        self.assertNotIn(releases[-1]['assets'][0]['browser_download_url'], self.requests)

    def test_failure_before_publication_preserves_old_assets_and_catalog(self):
        old = self.root / 'assets' / 'v1.1.5'
        old.mkdir(parents=True)
        (old / 'old.apk').write_bytes(b'old')
        previous = '[{"tag_name":"v1.1.5"}]'
        (self.root / 'releases.json').write_text(previous)
        self.responses[self.apk_url] = b'bad checksum'
        with self.assertRaises(ValueError):
            sync(self.root, self.base, self.fetch)
        self.assertTrue((old / 'old.apk').exists())
        self.assertEqual(previous, (self.root / 'releases.json').read_text())

    def test_incomplete_upstream_does_not_shrink_a_three_release_catalog(self):
        old = self.root / 'assets' / 'v1.1.5'
        old.mkdir(parents=True)
        (old / 'old.apk').write_bytes(b'old')
        previous = json.dumps([{'tag_name': f'v1.1.{v}'} for v in (5, 4, 3)])
        (self.root / 'releases.json').write_text(previous)
        with self.assertRaisesRegex(ValueError, 'Too few verified releases'):
            sync(self.root, self.base, self.fetch)
        self.assertTrue((old / 'old.apk').exists())
        self.assertEqual(previous, (self.root / 'releases.json').read_text())


if __name__ == '__main__':
    unittest.main()
