#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
for script in "$ROOT"/scripts/proxmox/*.sh; do
  bash -n "$script"
  grep -q 'set -Eeuo pipefail' "$script"
done
command -v shellcheck >/dev/null && shellcheck "$ROOT"/scripts/proxmox/*.sh
grep -q 'pct status' "$ROOT/scripts/proxmox/create-lxc.sh"
grep -q 'pveam download' "$ROOT/scripts/proxmox/create-lxc.sh"
grep -q 'ip=\$IP' "$ROOT/scripts/proxmox/create-lxc.sh"
grep -q 'sqlite3.*\.backup' "$ROOT/scripts/proxmox/backup-app.sh"
grep -q 'health' "$ROOT/scripts/proxmox/restore-app.sh"
echo "Validations statiques Proxmox réussies"
