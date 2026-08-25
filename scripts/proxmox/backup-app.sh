#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "Sauvegarde échouée ligne $LINENO" >&2' ERR
DEST=${1:-/var/lib/alcohol-tracker/backups}; DATA=/var/lib/alcohol-tracker
install -d -o alcoholtracker -g alcoholtracker "$DEST"
STAMP=$(date +%F-%H%M); WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
sqlite3 "$DATA/database.sqlite" ".backup '$WORK/database.sqlite'"
cp -a /etc/alcohol-tracker "$WORK/config"
for dir in uploads imports; do [[ -e $DATA/$dir ]] && cp -a "$DATA/$dir" "$WORK/$dir"; done
tar -C "$WORK" -czf "$DEST/alcohol-tracker-backup-$STAMP.tar.gz" .
chown alcoholtracker:alcoholtracker "$DEST/alcohol-tracker-backup-$STAMP.tar.gz"
echo "$DEST/alcohol-tracker-backup-$STAMP.tar.gz"

