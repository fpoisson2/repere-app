#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "Mise à jour échouée ligne $LINENO" >&2' ERR
APP=/opt/alcohol-tracker; BACKUP=$($APP/scripts/proxmox/backup-app.sh); OLD=$(git -C "$APP" rev-parse HEAD)
rollback(){ git -C "$APP" reset --hard "$OLD"; systemctl restart alcohol-tracker; echo "Rollback vers $OLD; données sauvegardées dans $BACKUP" >&2; }
trap rollback ERR
git -C "$APP" fetch --tags origin
git -C "$APP" checkout "${APP_REF:-main}"
git -C "$APP" pull --ff-only
$APP/.venv/bin/pip install -r "$APP/backend/requirements.txt"
(cd "$APP/frontend" && npm install && npm run build)
if [[ -f $APP/backend/alembic.ini ]]; then (cd "$APP/backend" && ../.venv/bin/alembic upgrade head); fi
chown -R alcoholtracker:alcoholtracker "$APP"; systemctl restart alcohol-tracker
for _ in {1..30}; do curl -fsS http://127.0.0.1/api/health >/dev/null && { trap - ERR; echo "Mise à jour réussie"; exit 0; }; sleep 2; done
false

