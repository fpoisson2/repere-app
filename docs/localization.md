# Localization

Repère uses French (Canada) as its source language and supports incremental English translation.

## Web

Translations and locale persistence are defined in `frontend/src/i18n.ts`. Add a key to the
`Messages` type, then provide it in every dictionary. In React components, read strings through
`useI18n()` instead of adding new hard-coded user-facing text.

The selected locale is stored in the browser under `repere-locale`. The language selector is in
Settings → About and support.

## Android

Android uses standard string resources:

- `android/mobile/src/main/res/values/strings.xml` — French (Canada), the default.
- `android/mobile/src/main/res/values-en/strings.xml` — English.
- `android/mobile/src/main/res/xml/locales_config.xml` — supported locales.

The Wear OS app and the `:data` module carry their own pair of files:

- `android/wear/src/main/res/values{,-en}/strings.xml` — watch UI, tile and complication labels.
- `android/data/src/main/res/values{,-en}/strings.xml` — the favourites seeded on first launch.
  These are editable *data*, written once in whatever language is active at that moment, not
  labels resolved on every read.

Use `stringResource(R.string.key)` in Compose, `pluralStringResource` wherever a count decides the
wording, and `context.getString(...)` outside composition. Add every new key to both language
files. Android 13 and later can also expose Repère's per-app language setting through the
operating system.

Two rules are easy to miss:

- **Dates and numbers follow the locale too.** Format them with `Locale.getDefault()` — never a
  hard-coded `Locale.CANADA_FRENCH` — so an English UI does not print `samedi 30 août` and `1,50`.
- **A displayed string must never double as state.** Sync status used to be a French string that
  `startsWith` was matched against; it is now the `SyncStatus` enum, each entry carrying its own
  label resource. Keep control flow on typed values and leave the wording in `strings.xml`.

## Translation scope

Every user-facing string in the Android phone and watch apps now comes from these files: both
apps are fully translated to English. The web app is still on the incremental path described
above.
