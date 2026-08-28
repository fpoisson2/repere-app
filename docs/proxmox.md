# Déploiement Proxmox VE / LXC

## Prérequis et réseau

Exécuter sur un hôte Proxmox VE en root. Le bridge par défaut est `vmbr0`; il doit joindre le VLAN `192.168.1.0/24`. En cas de VLAN-aware bridge, affectez le tag sur le bridge ou ajoutez `tag=<VID>` à `net0` selon votre architecture. Le script accepte DHCP ou une adresse statique `192.168.1.x/24`, avec passerelle `192.168.1.1` par défaut. Réservez l’adresse dans DHCP pour un service stable.

## Création automatique

```bash
git clone <repository>
cd repere-app/scripts/proxmox
less create-lxc.sh
sudo ./create-lxc.sh
```

Mode non interactif :

```bash
CTID=220 HOSTNAME=alcohol STORAGE=local-lvm BRIDGE=vmbr0 \
IP=192.168.1.220/24 GATEWAY=192.168.1.1 MEMORY=2048 CORES=2 DISK=8 \
APP_REPO=https://github.com/fpoisson2/repere-app.git bash create-lxc.sh
```

Avec `IP=dhcp`, Proxmox transmet DHCP. Le conteneur Debian 12 est non privilégié, démarre au boot, et n’active ni nesting, keyctl ni FUSE. L’installation native est donc recommandée. `INSTALL_MODE=docker` existe, mais Docker-in-LXC demande généralement `features: nesting=1,keyctl=1`; configurez ces options explicitement sur l’hôte si votre version Proxmox l’exige. Cela augmente la surface et la complexité de dépannage.

## Création manuelle

Créez un Debian 12 non privilégié (2 cœurs, 2 Go RAM, 8 Go), configurez `net0` sur `vmbr0`, démarrez-le, puis copiez et lancez `install-app.sh` avec `APP_REPO` défini. N’exécutez jamais le service applicatif en root.

## Exploitation

```bash
systemctl status alcohol-tracker
systemctl restart alcohol-tracker
journalctl -u alcohol-tracker
./update-app.sh
./backup-app.sh
./restore-app.sh /chemin/archive.tar.gz
```

La sauvegarde applicative utilise `.backup` de SQLite, donc reste cohérente pendant les écritures. En complément, `vzdump 220 --mode snapshot --compress zstd --storage <backup>` protège le conteneur complet. Utilisez les snapshots avant une opération courte et `vzdump` pour la rétention externe. Un restore Proxmox restaure le CT entier; `restore-app.sh` ne restaure que données/configuration et crée d’abord un point de retour.

Pour Nginx Proxy Manager, Traefik, Caddy externe ou Cloudflare Tunnel, ciblez `http://IP_LXC:80`. Terminez TLS à l’extérieur et transmettez Host, X-Forwarded-For et X-Forwarded-Proto. Ne forcez pas HTTPS dans le LXC.

## Dépannage

- CTID occupé : choisissez `pvesh get /cluster/nextid`.
- Template absent : contrôlez `pveam update` et le stockage `local`.
- Pas de DHCP : vérifiez VLAN/tag, bridge, pare-feu et serveur DHCP.
- Santé : `pct exec 220 -- curl -v http://127.0.0.1/api/health`.
- Base : `/var/lib/alcohol-tracker/database.sqlite`; configuration : `/etc/alcohol-tracker/app.env`.
- Après reboot Proxmox, vérifiez `onboot: 1`, puis systemd et Caddy dans le CT.

Les validations statiques (`scripts/proxmox/tests/static-validation.sh`) couvrent syntaxe, options sûres, téléchargement template, DHCP/statique, backup/restore et healthcheck. Les scénarios réels de reboot, persistance, vzdump et réseau nécessitent un nœud Proxmox de test; utilisez un CTID et une IP réservés avant production.

