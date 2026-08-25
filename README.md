# AlcoTrack Homelab

Application self-hosted mobile-first de suivi d’alcool : saisie par presets, statistiques strictement bornées à la première date suivie, imports idempotents, sessions et estimation BAC progressive. Aucune télémétrie, aucun cloud obligatoire.

> L’alcoolémie affichée est une estimation mathématique, jamais une autorisation de conduire.

## Démarrage rapide

### Développement local

```bash
cp .env.example .env
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
cd frontend && npm install && npm run build && cd ..
DATABASE_URL=sqlite:///./dev.sqlite DATA_DIR=. .venv/bin/uvicorn backend.app.main:app --reload
```

Ouvrez `http://localhost:8000`, créez le premier compte, puis choisissez un preset et « Ajouter maintenant ».

### Docker Compose

```bash
cp .env.example .env
# Remplacez SECRET_KEY dans .env
docker compose up -d
docker compose ps
```

Application : `http://localhost:8080`. Les données vivent dans le volume `alcohol_data`.

### Proxmox LXC officiel

Méthode vérifiable recommandée :

```bash
git clone <repository>
cd alcohol-tracker/scripts/proxmox
less create-lxc.sh
sudo APP_REPO=<repository> ./create-lxc.sh
```

Pour le VLAN demandé :

```bash
CTID=220 HOSTNAME=alcohol STORAGE=local-lvm BRIDGE=vmbr0 \
IP=192.168.1.220/24 GATEWAY=192.168.1.1 MEMORY=2048 CORES=2 DISK=8 \
APP_REPO=<repository> sudo -E bash create-lxc.sh
```

DHCP : remplacez `IP=...` par `IP=dhcp`. Une installation distante en une commande peut être publiée après remplacement de l’URL et revue du script :

```bash
bash -c "$(curl -fsSL https://example.invalid/alcohol-tracker/create-lxc.sh)"
```

La commande git + `less` reste plus sûre. Voir [docs/proxmox.md](docs/proxmox.md).

## Android et Wear OS

Une application Android compagnon et une application Wear OS avec complication de cadran sont disponibles dans `android/`. Consultez [docs/android-wear.md](docs/android-wear.md) pour l’association, la compilation et l’installation.

L’analyse comportementale optionnelle via l’API OpenAI est documentée dans [docs/ai-insights.md](docs/ai-insights.md). Elle utilise le modèle `gpt-5.6-sol` et nécessite une clé configurée côté serveur.

## API et fonctionnalités

Les routes REST incluent `/api/drinks`, `/api/presets`, `/api/import`, `/api/import/history`, `/api/days`, `/api/sessions`, `/api/stats`, `/api/stats/trends`, `/api/stats/distribution`, `/api/bac`, `/api/goals`, `/api/journal`, `/api/export` et `/api/health`. OpenAPI est disponible sous `/docs`.

`tracking_start_date` est la date minimale réelle par défaut et peut être modifiée dans `/api/settings`. Aucun jour antérieur n’est matérialisé comme zéro. Les détails sont dans [statistics.md](docs/statistics.md), [bac-model.md](docs/bac-model.md), [import.md](docs/import.md) et [architecture.md](docs/architecture.md).

PostgreSQL : utilisez `DATABASE_URL=postgresql+psycopg://user:password@host/database`. En production TLS, passez `SECURE_COOKIES=true`.

## Tests

```bash
cd backend && pytest
cd .. && scripts/proxmox/tests/static-validation.sh
cd frontend && npm run build
```

Les tests couvrent les exemples grammes/standards, AM/PM, durée, déduplication, coût négatif, première date, bornage statistique, sessions, absorption, élimination et BAC non négatif.
