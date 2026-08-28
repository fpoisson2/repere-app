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

Use `stringResource(R.string.key)` in Compose. Add every new key to both language files. Android
13 and later can also expose Repère's per-app language setting through the operating system.

## Translation scope

The application shell, navigation, update messaging, and support surfaces establish the migration
path. Older screens still contain French source strings and can be moved to these dictionaries
incrementally without changing application logic.
