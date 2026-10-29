#!/usr/bin/env python3
"""Upload a signed local test APK to the public opt-in test channel on evox1."""
import argparse
import datetime as dt
import hashlib
from pathlib import Path
import re
import shlex
import subprocess
import uuid

EXPECTED_PACKAGE = 'cn.manstep.phonemirrorBox'
EXPECTED_CERT = 'f91fee2f5331241e265fbbfeca6c60ef27201b9e19c55b91ec4b137c3af5eae0'


def run(*command):
    return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--notes', default='')
    parser.add_argument('--build-tools', type=Path, default=Path.home() / 'Library/Android/sdk/build-tools/36.0.0')
    args = parser.parse_args()
    apk = args.apk.resolve(strict=True)
    badging = run(str(args.build_tools / 'aapt'), 'dump', 'badging', str(apk))
    if not re.search(r"^package: name='" + re.escape(EXPECTED_PACKAGE) + r"'", badging, re.MULTILINE):
        raise SystemExit('Unexpected APK package')
    certificate = run(str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk))
    if not re.search(r'^Signer #1 certificate SHA-256 digest: ' + EXPECTED_CERT + r'$', certificate, re.MULTILINE):
        raise SystemExit('Unexpected APK signing certificate')
    with apk.open('rb') as stream:
        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')
    build_id = f'test-{stamp}-{sha[:8]}'
    name = f'CarPlay-{build_id}.apk'
    incoming = '/tmp/diplay-test-' + uuid.uuid4().hex + '.apk'
    ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', 'evox1']
    subprocess.run(['scp', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', str(apk), 'evox1:' + incoming], check=True)
    try:
        # Only the upload staging file is exposed; the publisher validates its exact checksum.
        run(*ssh, 'chmod 644 ' + shlex.quote(incoming))
        args_remote = ['sudo', '-u', 'diplay-updates', 'python3', '/opt/diplay-updates/publish_test_catalog.py',
                       '--root', '/var/www/diplay-updates/test-channel', '--incoming', incoming,
                       '--build-id', build_id, '--apk-name', name, '--sha256', sha, '--notes', args.notes]
        print(run(*ssh, shlex.join(args_remote)).strip())
    finally:
        subprocess.run(ssh + ['rm -f -- ' + shlex.quote(incoming)], check=False)
    print(f'https://weclaw-agent.cloud/diplay-updates/test-channel/builds/{build_id}/{name}')
    print('SHA-256:', sha)


if __name__ == '__main__':
    main()
