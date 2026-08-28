# AGENTS.md

Guidance for automated agents and contributors working in this repo. Read this before
touching more than one file.

## North star: the Android app mirrors the web app

The **web app and the Android app are two front-ends over the same backend**. They must
stay aligned: same menus, same feature set, same wording, same backend endpoints. When you
add or change a user-facing feature in one client, do the equivalent in the other (or open a
follow-up note saying why not). The Wear OS app is a small companion — it does not need
parity, only the quick-add / active-drink / today / BAC surface.

"Alignment" concretely means:

| Web menu (`frontend/src/main.tsx` `View`) | Android tab (`android/mobile` `Destination`) | Primary endpoints |
|---|---|---|
| Maintenant (`now`) / Dashboard | `NOW` — `MaintenantScreen` | `/api/sync`, `/api/bac`, `/api/check-ins`, `/api/days`, `/api/days/sober` |
| Stats (`stats`) | `STATS` — `StatsScreen` | `/api/stats`, `/api/stats/trends`, `/api/stats/health` |
| Repères (`insights`) | `INSIGHTS` — `InsightsScreen` | `/api/analytics/personal` |
| Succès (`success`) | `SUCCESS` — `SuccessScreen` | `/api/success` |
| Objectifs (`goals`) | `GOALS` — `GoalsScreen` | `/api/goals` (+ `POST`/`DELETE`) |
| Réglages (`settings`) | `SETTINGS` — `SettingsScreen` | `/api/auth/me`, `/api/settings`, `/api/oauth/*` |
| Historique (from settings) | `HISTORY` (not in bar, from Réglages) | `/api/sync` snapshot |
| — | `HEALTH` (from Réglages) — Health Connect | `/api/health-connect/*` |

`docs/android-wear.md` predates this alignment work and is **stale** (mentions a 4-tab app and
a 6-digit pairing code). Trust the code; update that doc when you get the chance.

## Layout

- `backend/` — FastAPI + SQLAlchemy + Alembic. `app/main.py` is the bulk; `app/longitudinal.py`
  is the `/api/...` router for check-ins / health / ML; `app/oauth.py` is the OAuth2 server;
  `app/services.py` holds the math (`alcohol`, `bac_at`, `body_r`, `daily_series`, …).
- `frontend/` — single-file React/TS SPA (`src/main.tsx`, ~3900 lines), built by Vite into
  `dist/` and served statically by FastAPI at `/`. It uses `public/sw.js` for caching and update
  notifications, but it is deliberately a **browser client, not an installable Chrome PWA**.
  Do not restore a manifest link or add a browser-install prompt. Direct Android installation to
  `https://play.google.com/store/apps/details?id=ca.repere.app` instead. The bilingual public
  presentation page is a separate Vite entry in `src/presentation.tsx`, served at `/about`;
  preserve its local-first message and keep its product links aligned with the app.
- `android/` — Gradle multi-module: `:core` (pure models + `CredentialStore`), `:data` (Room +
  `SyncRepository` + WorkManager), `:mobile`, `:wear`. Both apps share `applicationId ca.repere.app`
  (wear `versionCode` must stay **below** mobile's for Play bundled delivery).

## How the pieces talk

- **Android must remain fully usable offline.** The Android app must not depend on an active
  Internet connection for its user-facing screens. Reads use locally persisted data; writes are
  recorded locally first and synchronized with the backend when connectivity returns. Derived
  values needed by Android are computed from persisted local inputs, using the same canonical
  formulas as the backend. Local Android state is the source of truth;
  server synchronization is always secondary, opportunistic, and must never block a feature or
  offline use. Do not introduce a UI flow whose normal use requires a live request.

- **Auth.** The phone authenticates with **OAuth2 Authorization Code + PKCE** against the
  user's self-hosted server (`client_id=repere-android`, redirect `ca.repere.app://oauth2redirect`,
  no secret). The resulting bearer token works on the **whole API** — `current_user` and
  `wear_user` both accept it. The watch still uses the 6-digit `/api/wear/pair` flow, or the
  phone hands it credentials over the Data Layer.
- **Android data is offline-first.** Create/edit/delete drinks go through Room + the `/api/sync`
  mutation journal (`create`/`update`/`delete`). Goals, check-ins, sober days, settings, presets,
  profile inputs, and the data needed for stats/insights/success are also persisted locally.
  Network operations are queued or synchronized opportunistically and must not gate normal use.
- **BAC uses the same model everywhere.** The canonical implementation is `services.bac_at` /
  `bac_projection`, using `services.body_r(user)` (Watson TBW from `sex` + `height_cm` +
  `weight_kg`). Android must also carry an equivalent, tested implementation and persist the
  required profile parameters locally so BAC remains available offline. Keep both implementations
  mathematically aligned whenever the model changes. Always display the driving disclaimer.
- **Day bucketing** uses the user's `day_start_hour` (default 8). Server endpoints that need
  "today" in the user's timezone accept the client's local time (see `/api/wear/state?now=`).
- **Watch refresh.** After a phone sync, `pokeWatch()` bumps `/repere/config`; the watch's
  `ConfigListenerService` + `StateCache.refresh()` re-pull `/api/wear/state` so complications
  track deletions/edits without opening the watch app.

## Conventions

- **Localization.** French (Canada) is the source and fallback language. New web strings belong in
  `frontend/src/i18n.ts` and must be supplied in both `fr-CA` and `en`; new Android strings belong
  in `res/values/strings.xml` and `res/values-en/strings.xml`. Avoid adding new hard-coded
  user-facing strings. See `docs/localization.md`. Keep the BAC disclaimer ("estimation
  mathématique, jamais une autorisation de conduire") anywhere BAC is shown.
- **Web design system.** Reuse the semantic tokens and shared components in
  `frontend/src/design-system.css`; do not introduce isolated colours or ad-hoc component styles
  when an existing token or pattern fits. See `docs/design-system.md`.
- **Product links.** Buy Me a Coffee is `https://buymeacoffee.com/fpoisson`. Issues are reported
  at `https://github.com/fpoisson2/alcohol-tracker/issues`. The Android install destination is the
  Google Play URL above. Keep these links aligned between web, Android, and README documentation.
- **Version and updates.** Web displays `VITE_APP_VERSION` (Docker receives it through
  `APP_VERSION`) and detects deployed versions through the service worker. Android displays
  `BuildConfig.VERSION_NAME` and uses Google Play flexible in-app updates; update checks must fail
  silently when Play or connectivity is unavailable so offline use remains unaffected.
- **Code style is compact.** `backend` and `android` both favour dense one-liners and minimal
  ceremony — match the file you're editing, don't reformat.
- **Backend schema changes need an Alembic migration** (`backend/alembic/versions/NNNN_*.py`,
  `down_revision` = current head). Fresh installs also rely on `Base.metadata.create_all` in
  `startup()`, so new models work there too. (Known issue: `alembic upgrade` from an *empty* DB
  fails at `0002` — existing deploys past `0013` are unaffected.)
- **Android release = APK *and* signed AAB, for both modules.** Bump the relevant module's
  `versionCode` (+ `versionName` for a notable release) before building. Signing is wired via
  `android/keystore.properties`.
- **Internal release automation.** Pushes to `dev` run `.github/workflows/android-internal.yml`,
  build both mobile and Wear bundles, and publish them to the Play internal track. CI version names
  follow `YYYY.MM.beta-N`; mobile `versionCode` must remain above Wear's. Do not commit signing
  material or Play service-account credentials. See `docs/play-internal-deployment.md`.

## Build & test

```bash
# backend
cd backend && python -m pytest -q            # 44+ tests; keep green
python -m alembic history                    # verify your migration chains to head

# frontend
cd frontend && npx tsc --noEmit && npm run build

# android (from android/)
./gradlew :mobile:assembleRelease :mobile:bundleRelease :wear:assembleRelease :wear:bundleRelease
# outputs: {mobile,wear}/build/outputs/{apk,bundle}/release/
```

A feature change usually touches: `backend` (endpoint + test) → `frontend/src/main.tsx` →
`android/mobile` → rebuild both apps. Deploy the backend (`alembic upgrade head`) and the
frontend (`dist/`) together.

## When adding a user-facing feature — checklist

1. Backend endpoint + pytest.
2. Web: wire it into the matching `View` in `frontend/src/main.tsx`.
3. Android: wire it into the matching `Destination` screen; reuse `Net` for reads/writes,
   `SyncRepository` only for drink mutations.
4. Keep wording identical between web and Android and add every new string to both locale sets.
5. Bump Android `versionCode`, rebuild APK + AAB for `:mobile` (and `:wear` if `:core`/`:data`
   or the watch changed).
6. Use the design-system tokens for new web UI; verify the version, support, issue, update, and
   Google Play links remain correct when touching settings or onboarding.
7. Note anything you deliberately left out of one client.
