#!/usr/bin/env python3
"""Atomically publish a manually uploaded test APK and retain five test builds."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

SAFE = re.compile(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,159}\Z')
BASE = 'https://weclaw-agent.cloud/diplay-updates/test-channel'
MAX_APK_BYTES = 256 * 1024 * 1024


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def publish(root, incoming, build_id, apk_name, expected, notes=''):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    with (root / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return _publish_locked(root, incoming, build_id, apk_name, expected, notes)


def _publish_locked(root, incoming, build_id, apk_name, expected, notes):
    root = Path(root)
    incoming = Path(incoming)
    if not SAFE.fullmatch(build_id) or not SAFE.fullmatch(apk_name) or not apk_name.endswith('.apk'):
        raise ValueError('Invalid build identifier or APK name')
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Invalid checksum')
    if incoming.is_symlink() or not incoming.is_file() or not 0 < incoming.stat().st_size <= MAX_APK_BYTES:
        raise ValueError('Invalid uploaded APK')
    if digest(incoming) != expected:
        raise ValueError('Uploaded APK checksum mismatch')
    root.mkdir(parents=True, exist_ok=True)
    builds = root / 'builds'
    builds.mkdir(exist_ok=True)
    catalog_path = root / 'releases.json'
    previous = json.loads(catalog_path.read_text()) if catalog_path.exists() else []
    if not isinstance(previous, list):
        raise ValueError('Existing test catalog is invalid')
    if any(not isinstance(item, dict) or not isinstance(item.get('tag_name'), str) or
           not SAFE.fullmatch(item['tag_name']) for item in previous):
        raise ValueError('Existing test catalog contains an invalid build')
    if any(item.get('tag_name') == build_id for item in previous):
        raise ValueError('Build identifier already exists')
    target = builds / build_id
    if target.exists() or target.is_symlink():
        raise ValueError('Build directory already exists')
    stage = Path(tempfile.mkdtemp(prefix='.publishing-', dir=builds))
    catalog_published = False
    try:
        staged_apk = stage / apk_name
        shutil.copyfile(incoming, staged_apk)
        if digest(staged_apk) != expected:
            raise ValueError('Staged APK checksum mismatch')
        (stage / 'SHA256SUMS.txt').write_text(f'{expected}  {apk_name}\n')
        for file in stage.iterdir():
            os.chmod(file, 0o644)
        os.chmod(stage, 0o755)
        os.replace(stage, target)
        entry = {'tag_name': build_id, 'name': build_id, 'body': notes[:4000], 'prerelease': True,
                 'assets': [{'name': apk_name, 'browser_download_url': f'{BASE}/builds/{build_id}/{apk_name}'},
                            {'name': 'SHA256SUMS.txt', 'browser_download_url': f'{BASE}/builds/{build_id}/SHA256SUMS.txt'}]}
        catalog = [entry] + previous[:4]
        with tempfile.NamedTemporaryFile('w', dir=root, prefix='.catalog-', delete=False) as out:
            temporary = Path(out.name)
            json.dump(catalog, out, ensure_ascii=False, indent=2)
            out.write('\n')
        try:
            os.chmod(temporary, 0o644)
            os.replace(temporary, catalog_path)
            catalog_published = True
        finally:
            temporary.unlink(missing_ok=True)
    except Exception:
        shutil.rmtree(stage, ignore_errors=True)
        if not catalog_published and target.exists():
            shutil.rmtree(target, ignore_errors=True)
        raise
    kept = {item.get('tag_name') for item in catalog}
    for item in previous[4:]:
        folder = builds / item['tag_name']
        if folder.is_dir() and not folder.is_symlink() and folder.name not in kept:
            shutil.rmtree(folder)
    return catalog


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--incoming', required=True)
    parser.add_argument('--build-id', required=True)
    parser.add_argument('--apk-name', required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--notes', default='')
    args = parser.parse_args()
    print('Published test builds:', len(publish(args.root, args.incoming, args.build_id, args.apk_name, args.sha256, args.notes)))
