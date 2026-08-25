#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "Erreur ligne $LINENO: installation interrompue" >&2' ERR
[[ ${EUID} -eq 0 ]] || { echo "Root requis pour provisionner le LXC." >&2; exit 1; }
INSTALL_MODE=${INSTALL_MODE:-native}; APP_REPO=${APP_REPO:-}; APP_REF=${APP_REF:-main}
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl git sqlite3 caddy
if [[ $INSTALL_MODE == docker ]]; then
  apt-get install -y docker.io docker-compose-plugin
  systemctl enable --now docker
  install -d /opt/alcohol-tracker /var/lib/alcohol-tracker
  if [[ -n $APP_REPO ]]; then git clone --branch "$APP_REF" --depth 1 "$APP_REPO" /opt/alcohol-tracker; else mkdir -p /opt/alcohol-tracker; tar -xzf /root/alcohol-tracker-source.tar.gz -C /opt/alcohol-tracker; fi
  cp /opt/alcohol-tracker/.env.example /opt/alcohol-tracker/.env
  sed -i 's|/data/database.sqlite|/data/database.sqlite|' /opt/alcohol-tracker/.env
  (cd /opt/alcohol-tracker && docker compose up -d --build)
  UPSTREAM=127.0.0.1:8080
else
  apt-get install -y --no-install-recommends python3 python3-venv python3-pip nodejs npm
  id alcoholtracker >/dev/null 2>&1 || useradd --system --home /var/lib/alcohol-tracker --shell /usr/sbin/nologin alcoholtracker
  install -d -o alcoholtracker -g alcoholtracker /var/lib/alcohol-tracker/{uploads,backups} /var/log/alcohol-tracker
  install -d -m 0750 -o root -g alcoholtracker /etc/alcohol-tracker
  if [[ -n $APP_REPO ]]; then git clone --branch "$APP_REF" --depth 1 "$APP_REPO" /opt/alcohol-tracker; else mkdir -p /opt/alcohol-tracker; tar -xzf /root/alcohol-tracker-source.tar.gz -C /opt/alcohol-tracker; fi
  python3 -m venv /opt/alcohol-tracker/.venv
  /opt/alcohol-tracker/.venv/bin/pip install -r /opt/alcohol-tracker/backend/requirements.txt
  (cd /opt/alcohol-tracker/frontend && npm install && npm run build)
  SECRET=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')
  printf 'SECRET_KEY=%s\nDATABASE_URL=sqlite:////var/lib/alcohol-tracker/database.sqlite\nDATA_DIR=/var/lib/alcohol-tracker\nTRUSTED_HOSTS=*\nSECURE_COOKIES=false\n' "$SECRET" > /etc/alcohol-tracker/app.env
  chown root:alcoholtracker /etc/alcohol-tracker/app.env; chmod 0640 /etc/alcohol-tracker/app.env
  chown -R alcoholtracker:alcoholtracker /opt/alcohol-tracker /var/lib/alcohol-tracker
  install -m 0644 /opt/alcohol-tracker/scripts/proxmox/alcohol-tracker.service /etc/systemd/system/alcohol-tracker.service
  systemctl daemon-reload; systemctl enable --now alcohol-tracker
  UPSTREAM=127.0.0.1:8000
fi
printf ':80 {\n reverse_proxy %s {\n  header_up X-Forwarded-For {remote_host}\n  header_up X-Forwarded-Proto {scheme}\n  header_up X-Forwarded-Host {host}\n }\n}\n' "$UPSTREAM" > /etc/caddy/Caddyfile
systemctl enable --now caddy; systemctl reload caddy
for _ in {1..30}; do curl -fsS http://127.0.0.1/api/health >/dev/null && exit 0; sleep 2; done
echo "Healthcheck en échec" >&2; exit 5
