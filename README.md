# Repère

**Understand your drinking without giving up your data.**

Repère is a free and open-source alcohol tracking platform for people who want to keep control
of their infrastructure and personal data. It turns habits into useful perspective: the goal is
to make patterns visible and leave the decisions to the person who knows the context best — you.

It combines a self-hosted web server with an autonomous Android application. The phone remains
fully usable without an account, Internet connection, or running server. When synchronization is
enabled, local changes are sent opportunistically to the user's own Repère server and reconciled
when connectivity returns.

A bilingual public project presentation is served at `/about` on every deployment; the private
application stays at `/`.

> Blood alcohol concentration values are mathematical estimates, never authorization to drive.

## Why Repère exists

This started as a personal itch: I wanted a Wear OS app to log a drink from my wrist in a couple
of taps. The apps I tried either didn't fit how I wanted to track, locked useful features behind
a subscription, or shipped ads and telemetry around personal health data.

I like self-hosted, open-source software, so I went further than a watch tile. Repère is the
result: a local-first Android app with a Wear OS companion, backed by a server you run yourself.
No mandatory cloud, no advertising, no analytics phoning home — just your data, on hardware you
control, in formats you can export.

## Principles

- **Self-hosted:** run the backend and web interface on hardware you control.
- **Local-first Android:** record, edit, and review data without network access; the phone is
  the source of truth.
- **Optional synchronization:** the server is secondary and never blocks normal phone use.
- **Private by design:** no mandatory cloud service, advertising, or telemetry.
- **Perspective, not judgment:** surface trends and estimates; leave the conclusions to you.
- **Open source:** inspect, modify, and redistribute Repère under the MIT License.
- **Client parity:** web and Android expose the same primary features and wording.

## Components

| Component | Purpose |
|---|---|
| `backend/` | FastAPI, SQLAlchemy, Alembic, OAuth 2.0, synchronization, and analytics |
| `frontend/` | React/TypeScript progressive web application served by the backend |
| `android/mobile` | Autonomous Android application |
| `android/wear` | Wear OS companion and complications |
| `android/core` | Shared alcohol, time, and BAC calculations |
| `android/data` | Room persistence and durable synchronization |

## Capabilities

- **Quick tracking:** presets and custom drink entries, offline create/edit/delete, sober days,
  and daily check-ins;
- **Personal trends:** history, statistics, goals, achievements, and change over time;
- **BAC estimate:** progressive estimation from a locally stored body profile, using one
  consistent model across server and mobile, always paired with a driving warning;
- **Canadian standard drink** and pure alcohol calculations;
- **Health and watch:** Health Connect daily aggregates and a Wear OS companion with quick entry
  and complications;
- **Optional sync:** OAuth 2.0 Authorization Code with PKCE for Android synchronization to your
  own server;
- **Your data out:** JSON/CSV export and a documented REST API.

## Quick start with Docker Compose

```bash
git clone https://github.com/fpoisson2/repere-app.git
cd repere-app
cp .env.example .env
# Replace SECRET_KEY with a long random value.
docker compose up -d --build
docker compose ps
```

Open `http://localhost:8080` and create the first account. Data is stored in the Docker volume
`alcohol_data`.

Do not forward port 8080 from your router. Use the private Tailscale configuration below for
remote access.

## Recommended private access with Tailscale

Repère does not need to be public on the Internet. The recommended deployment is to keep the
service private and connect the server and authorized devices through a Tailscale tailnet. This
avoids public DNS, router port forwarding, and a publicly reachable reverse proxy.

### 1. Bind Repère to localhost

Set the following value in `.env`:

```dotenv
APP_PORT=127.0.0.1:8080
SECURE_COOKIES=true
```

Then recreate the container:

```bash
docker compose up -d --build
```

### 2. Join the server to the tailnet

Install Tailscale using the method appropriate for the host, then authenticate it:

```bash
sudo tailscale up
```

Review device ownership, tags, access grants, and key expiry in the Tailscale admin console.

### 3. Expose Repère privately over HTTPS

Use Tailscale Serve as an HTTPS reverse proxy to the loopback-only service:

```bash
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
sudo tailscale serve status
```

Tailscale displays a private URL similar to:

```text
https://repere-server.example-tailnet.ts.net
```

Only devices authorized in the tailnet can reach it. Use **Tailscale Serve**, not **Tailscale
Funnel**: Funnel is intended for public Internet exposure.

### 4. Connect Android

1. Install Tailscale on the Android phone and join the same tailnet.
2. Confirm the private Repère URL opens in the phone browser.
3. Enter the complete `https://...ts.net` address in Repère Android settings.
4. Complete OAuth authorization and enable synchronization if desired.

The Android app continues to work if Tailscale or the server becomes unavailable. Pending changes
remain on the phone and synchronize later.

## Proxmox LXC

Repère includes installation and maintenance scripts for an unprivileged Debian 12 LXC:

```bash
git clone https://github.com/fpoisson2/repere-app.git
cd repere-app/scripts/proxmox
less create-lxc.sh
sudo APP_REPO=https://github.com/fpoisson2/repere-app.git ./create-lxc.sh
```

See [Proxmox deployment](docs/proxmox.md) for networking, backup, restore, and operations.
Tailscale can be installed inside the LXC so Repère remains reachable only through the tailnet.

## Local development

```bash
cp .env.example .env
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
cd frontend && npm install && npm run build && cd ..
DATABASE_URL=sqlite:///./dev.sqlite DATA_DIR=. .venv/bin/uvicorn backend.app.main:app --reload
```

Open `http://localhost:8000`. Interactive OpenAPI documentation is available at `/docs`.

## Android and Wear OS

Open `android/` in Android Studio or use the included Gradle Wrapper:

```bash
cd android
./gradlew :core:test :data:test :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

Mobile and Wear releases must be signed with the same key. Internal Google Play deployment from
the `dev` branch is documented in [Play internal deployment](docs/play-internal-deployment.md).

Self-hosted web installations direct phone users to the official Google Play listing for the
native Android app. The website remains a browser client and is not promoted as a Chrome-installed
PWA.

Every deployment also serves a bilingual public project presentation at `/about`. The private
application remains at `/`.

## Configuration

| Variable | Purpose |
|---|---|
| `SECRET_KEY` | Required secret protecting authentication state |
| `DATABASE_URL` | SQLite by default; PostgreSQL is supported |
| `DATA_DIR` | Persistent application data directory |
| `SECURE_COOKIES` | Enable for HTTPS deployments |
| `TRUSTED_HOSTS` | Accepted HTTP hostnames |
| `APP_PORT` | Docker host binding and port |
| `APP_VERSION` | Version displayed by the web client (defaults to `local`) |
| `OPENAI_API_KEY` | Optional server-side AI analysis integration |

For Tailscale Serve, restrict `TRUSTED_HOSTS` to the generated private `*.ts.net` hostname after
confirming the address.

## Tests

```bash
cd backend && python -m pytest -q
cd ../frontend && npx tsc --noEmit && npm run build
cd ../android && ./gradlew :core:test :data:test :mobile:testDebugUnitTest
```

## Privacy and security

- The Android database is the local source of truth.
- Server synchronization is optional.
- Health Connect stores daily aggregates instead of raw health records.
- Tailscale controls network reachability; Repère authentication still protects application data.
- Operators remain responsible for database and data-volume backups.

## Documentation

- [Architecture](docs/architecture.md)
- [Statistics](docs/statistics.md)
- [BAC model](docs/bac-model.md)
- [Data imports](docs/import.md)
- [Android and Wear OS](docs/android-wear.md)
- [Play Store preparation](docs/play-store.md)
- [Internal Play deployment](docs/play-internal-deployment.md)
- [Design system](docs/design-system.md)
- [Localization](docs/localization.md)

## Contributing

Web and Android are two front ends over the same backend and should remain aligned. Read
[AGENTS.md](AGENTS.md) before broad changes. Development and internal testing use `dev`; stable
changes can be merged into `main` after validation.

## Support and license

If Repère is useful to you, you can [support its development on Buy Me a Coffee](https://buymeacoffee.com/fpoisson).

Repère is available under the [MIT License](LICENSE). Bugs and feature requests can be reported in
[GitHub Issues](https://github.com/fpoisson2/repere-app/issues).
