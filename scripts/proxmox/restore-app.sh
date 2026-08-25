#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "Restauration échouée ligne $LINENO" >&2' ERR
ARCHIVE=${1:?Usage: restore-app.sh archive.tar.gz}; [[ -f $ARCHIVE ]] || { echo "Archive absente" >&2; exit 2; }
tar -tzf "$ARCHIVE" >/dev/null
"$(dirname "$0")/backup-app.sh" /var/lib/alcohol-tracker/backups/pre-restore
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT; tar -xzf "$ARCHIVE" -C "$WORK"
systemctl stop alcohol-tracker
install -m 0640 -o alcoholtracker -g alcoholtracker "$WORK/database.sqlite" /var/lib/alcohol-tracker/database.sqlite
[[ -d $WORK/uploads ]] && cp -a "$WORK/uploads/." /var/lib/alcohol-tracker/uploads/
[[ -d $WORK/config ]] && cp -a "$WORK/config/." /etc/alcohol-tracker/
chown -R alcoholtracker:alcoholtracker /var/lib/alcohol-tracker
systemctl start alcohol-tracker
for _ in {1..30}; do curl -fsS http://127.0.0.1/api/health >/dev/null && { echo "Restauration réussie"; exit 0; }; sleep 2; done
exit 5

