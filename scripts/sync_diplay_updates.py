#!/usr/bin/env python3
"""Mirror this fork's public APK releases and atomically publish a verified static catalog."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import urllib.parse
import urllib.request

RELEASES_URL = 'https://api.github.com/repos/lianjiawei/DiPlay/releases?per_page=30'
CHECKSUMS = 'SHA256SUMS.txt'
MAX_APK_BYTES = 256 * 1024 * 1024
MAX_TEXT_BYTES = 4 * 1024 * 1024
RETAIN_RELEASES = 3
SAFE_NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,159}\Z')
VERSION_FOLDER = re.compile(r'v[0-9][A-Za-z0-9._+-]*\Z')


def download(url, destination, limit):
    request = urllib.request.Request(url, headers={'User-Agent': 'DiPlay-update-mirror/1',
                                                  'Accept': 'application/json, */*'})
    last_error = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response, destination.open('wb') as output:
                size = 0
                while chunk := response.read(65536):
                    size += len(chunk)
                    if size > limit:
                        raise ValueError(f'Response exceeds size limit: {url}')
                    output.write(chunk)
            return
        except (OSError, TimeoutError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(2 ** attempt)
    raise last_error


def asset_url(asset):
    url = asset['browser_download_url']
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.netloc != 'github.com' or not parsed.path.startswith(
            '/lianjiawei/DiPlay/releases/download/'):
        raise ValueError('Unexpected release asset origin')
    return url


def sha256(path):
    with path.open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def sync(destination, public_base, fetch=download):
    parsed = urllib.parse.urlsplit(public_base)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.query or parsed.fragment:
        raise ValueError('public_base must be an HTTPS directory URL')
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    catalog = []
    retained = set()
    with tempfile.TemporaryDirectory(prefix='.sync-', dir=destination) as temporary:
        temporary = Path(temporary)
        listing = temporary / 'releases.json'
        fetch(RELEASES_URL, listing, MAX_TEXT_BYTES)
        releases = json.loads(listing.read_text())
        if not isinstance(releases, list):
            raise ValueError('Release listing is not an array')
        for release in releases[:30]:
            if release.get('draft') or release.get('prerelease'):
                continue
            tag = release.get('tag_name', '')
            assets = release.get('assets', [])
            apks = [a for a in assets if a.get('name', '').endswith('.apk')]
            checksums = next((a for a in assets if a.get('name') == CHECKSUMS), None)
            if not apks or not checksums:
                continue
            if not SAFE_NAME.fullmatch(tag) or any(not SAFE_NAME.fullmatch(a['name']) for a in apks):
                raise ValueError('Unsafe release tag or filename')
            folder = destination / 'assets' / tag
            folder.mkdir(parents=True, exist_ok=True)
            checksum_file = temporary / CHECKSUMS
            fetch(asset_url(checksums), checksum_file, MAX_TEXT_BYTES)
            expected = {}
            for line in checksum_file.read_text().splitlines():
                match = re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?(.+)', line)
                if match:
                    expected[match[2]] = match[1].lower()
            mirrored = []
            for asset in apks:
                name = asset['name']
                digest = expected.get(name)
                if digest is None:
                    raise ValueError(f'Missing checksum: {tag}/{name}')
                target = folder / name
                if not target.exists() or sha256(target) != digest:
                    if shutil.disk_usage(destination).free < MAX_APK_BYTES + 128 * 1024 * 1024:
                        raise OSError('Insufficient free space for update staging')
                    staged = temporary / name
                    fetch(asset_url(asset), staged, MAX_APK_BYTES)
                    if sha256(staged) != digest:
                        raise ValueError(f'Checksum mismatch: {tag}/{name}')
                    os.chmod(staged, 0o644)
                    os.replace(staged, target)
                mirrored.append({'name': name, 'browser_download_url':
                    public_base.rstrip('/') + '/assets/' + urllib.parse.quote(tag) + '/' + urllib.parse.quote(name)})
            os.chmod(checksum_file, 0o644)
            os.replace(checksum_file, folder / CHECKSUMS)
            mirrored.append({'name': CHECKSUMS, 'browser_download_url':
                public_base.rstrip('/') + '/assets/' + urllib.parse.quote(tag) + '/' + CHECKSUMS})
            catalog.append({key: release[key] for key in
                            ('tag_name', 'name', 'body', 'published_at', 'prerelease', 'draft') if key in release})
            catalog[-1]['assets'] = mirrored
            retained.add(tag)
            if len(catalog) == RETAIN_RELEASES:
                break
        if not catalog:
            raise ValueError('No verified releases; preserving the existing catalog')
        previous_catalog = destination / 'releases.json'
        if previous_catalog.exists() and len(catalog) < RETAIN_RELEASES:
            previous = json.loads(previous_catalog.read_text())
            if isinstance(previous, list) and len(previous) >= RETAIN_RELEASES:
                raise ValueError('Too few verified releases; preserving the existing catalog')
        listing.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
        os.chmod(listing, 0o644)
        os.replace(listing, destination / 'releases.json')
    # Publish the verified catalog first, then remove obsolete release directories.
    for folder in (destination / 'assets').iterdir():
        if (folder.is_dir() and not folder.is_symlink() and VERSION_FOLDER.fullmatch(folder.name)
                and folder.name not in retained):
            shutil.rmtree(folder)
    return len(catalog)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--public-base', required=True)
    args = parser.parse_args()
    print(f'Published {sync(args.destination, args.public_base)} verified releases', flush=True)
