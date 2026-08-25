#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "Erreur ligne $LINENO: création interrompue" >&2' ERR

[[ -x /usr/sbin/pct && -r /etc/pve/.version ]] || { echo "Ce script doit être exécuté en root sur Proxmox VE." >&2; exit 1; }
[[ ${EUID} -eq 0 ]] || { echo "Privilèges root requis." >&2; exit 1; }
for cmd in pct pveam awk curl; do command -v "$cmd" >/dev/null || { echo "Dépendance absente: $cmd" >&2; exit 1; }; done

ask() { local var=$1 prompt=$2 default=$3; if [[ -z ${!var:-} ]]; then if [[ -t 0 ]]; then read -r -p "$prompt [$default]: " value; printf -v "$var" '%s' "${value:-$default}"; else printf -v "$var" '%s' "$default"; fi; fi; }
ask CTID "CTID" "$(pvesh get /cluster/nextid)"
ask HOSTNAME "Hostname" "alcohol"
ask STORAGE "Stockage" "local-lvm"
ask BRIDGE "Bridge" "vmbr0"
ask IP "Réseau (dhcp ou 192.168.1.x/24)" "dhcp"
ask GATEWAY "Passerelle (statique)" "192.168.1.1"
ask CORES "CPU" "2"; ask MEMORY "RAM MB" "2048"; ask SWAP "Swap MB" "512"; ask DISK "Disque GB" "8"
INSTALL_MODE=${INSTALL_MODE:-native}; APP_REPO=${APP_REPO:-}; APP_REF=${APP_REF:-main}

pct status "$CTID" >/dev/null 2>&1 && { echo "Le CTID $CTID est déjà utilisé; aucune modification effectuée." >&2; exit 2; }
pvesm status -storage "$STORAGE" >/dev/null || { echo "Stockage invalide: $STORAGE" >&2; exit 2; }
ip link show "$BRIDGE" >/dev/null || { echo "Bridge absent: $BRIDGE" >&2; exit 2; }
if [[ $IP != dhcp && ! $IP =~ ^192\.168\.1\.[0-9]{1,3}/24$ ]]; then echo "IP attendue dans le VLAN 192.168.1.0/24 (ex. 192.168.1.220/24)." >&2; exit 2; fi

TEMPLATE_STORAGE=${TEMPLATE_STORAGE:-local}
pveam update
HOST_ARCH=$(dpkg --print-architecture)
TEMPLATE=${TEMPLATE:-$(pveam available --section system | awk -v arch="$HOST_ARCH" '$2 ~ /debian-[0-9]+-standard/ && $2 ~ ("_" arch "\\.tar") {print $2}' | sort -V | tail -1)}
[[ -n $TEMPLATE ]] || { echo "Template Debian stable introuvable." >&2; exit 3; }
TEMPLATE_PATH="$TEMPLATE_STORAGE:vztmpl/$TEMPLATE"
TEMPLATE_FILE=$(pvesm path "$TEMPLATE_PATH")
if [[ ! -f $TEMPLATE_FILE ]]; then pveam download "$TEMPLATE_STORAGE" "$TEMPLATE"; fi
NET="name=eth0,bridge=$BRIDGE,ip=$IP"
[[ $IP == dhcp ]] || NET+=",gw=$GATEWAY"
pct create "$CTID" "$TEMPLATE_PATH" --hostname "$HOSTNAME" --cores "$CORES" --memory "$MEMORY" --swap "$SWAP" --rootfs "$STORAGE:$DISK" --net0 "$NET" --unprivileged 1 --onboot 1 --start 1

for _ in {1..60}; do pct exec "$CTID" -- getent hosts deb.debian.org >/dev/null 2>&1 && break; sleep 2; done
pct exec "$CTID" -- getent hosts deb.debian.org >/dev/null || { echo "Le réseau du LXC n'est pas prêt." >&2; exit 4; }
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
pct push "$CTID" "$SCRIPT_DIR/install-app.sh" /root/install-app.sh -perms 0755
if [[ -z $APP_REPO ]]; then
  PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
  BUNDLE=$(mktemp --suffix=.tar.gz)
  trap 'rm -f "$BUNDLE"' EXIT
  tar -C "$PROJECT_ROOT" --exclude=.git --exclude=node_modules --exclude=.venv --exclude='*.sqlite' -czf "$BUNDLE" .
  pct push "$CTID" "$BUNDLE" /root/alcohol-tracker-source.tar.gz -perms 0600
fi
pct exec "$CTID" -- env INSTALL_MODE="$INSTALL_MODE" APP_REPO="$APP_REPO" APP_REF="$APP_REF" /root/install-app.sh
LXC_IP=$(pct exec "$CTID" -- hostname -I | awk '{print $1}')
STATUS=$(pct exec "$CTID" -- curl -fsS http://127.0.0.1/api/health | grep -q healthy && echo Healthy || echo Degraded)
printf '\nInstallation terminée\n\nLXC: %s\nHostname: %s\nIP: %s\n\nApplication:\nhttp://%s/\n\nÉtat:\n%s\n' "$CTID" "$HOSTNAME" "$LXC_IP" "$LXC_IP" "$STATUS"
