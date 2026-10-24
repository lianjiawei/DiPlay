#!/bin/sh
# Run as root after sync_diplay_updates.py has completed in STAGING/public.
set -eu
STAGING=${1:?Usage: install.sh STAGING NGINX_HTTPS_SITE}
SITE=${2:?Usage: install.sh STAGING NGINX_HTTPS_SITE}
test "$(id -u)" -eq 0
test -f "$STAGING/public/releases.json"
test -f "$SITE"
if test -d /var/www/diplay-updates; then
    test "$(cat /var/www/diplay-updates/.channel)" = 'lianjiawei/DiPlay'
fi
if ! id diplay-updates >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin diplay-updates
fi
install -d -m 755 /opt/diplay-updates /var/www/diplay-updates /etc/nginx/snippets
install -m 644 "$STAGING/sync_diplay_updates.py" /opt/diplay-updates/sync_diplay_updates.py
cp -a "$STAGING/public/assets" /var/www/diplay-updates/
install -m 644 "$STAGING/public/releases.json" /var/www/diplay-updates/.releases.json.new
mv /var/www/diplay-updates/.releases.json.new /var/www/diplay-updates/releases.json
printf 'lianjiawei/DiPlay\n' > /var/www/diplay-updates/.channel
chown -R diplay-updates:diplay-updates /var/www/diplay-updates
if command -v restorecon >/dev/null 2>&1; then restorecon -R /var/www/diplay-updates; fi
install -m 644 "$STAGING/deploy/nginx.conf" /etc/nginx/snippets/diplay-updates.conf
install -m 644 "$STAGING/deploy/diplay-updates.service" /etc/systemd/system/diplay-updates.service
install -m 644 "$STAGING/deploy/diplay-updates.timer" /etc/systemd/system/diplay-updates.timer
BACKUP="$SITE.diplay-backup-$(date +%Y%m%d%H%M%S)"
cp -p "$SITE" "$BACKUP"
python3 - "$SITE" <<'PY'
from pathlib import Path
import os
import sys
site = Path(sys.argv[1])
source = site.read_text()
include = 'include /etc/nginx/snippets/diplay-updates.conf;'
if include not in source:
    anchor = 'include /etc/nginx/snippets/evox-official.conf;'
    if source.count(anchor) != 1:
        raise ValueError('Expected one HTTPS site include anchor')
    temporary = site.with_name(site.name + '.diplay-new')
    temporary.write_text(source.replace(anchor, anchor + '\n    ' + include))
    temporary.chmod(site.stat().st_mode & 0o777)
    os.replace(temporary, site)
PY
if ! nginx -t; then
    cp -p "$BACKUP" "$SITE"
    exit 1
fi
systemctl daemon-reload
systemctl reload nginx
systemctl enable --now diplay-updates.timer
systemctl start --no-block diplay-updates.service
printf 'DiPlay update mirror enabled; Nginx backup: %s\n' "$BACKUP"
