# Déploiement Android en test interne

Chaque push sur la branche `dev` déclenche `.github/workflows/android-internal.yml`.
Le workflow exécute les tests Android, produit les APK et AAB signés des modules mobile et
Wear, conserve les artefacts pendant 30 jours, puis transmet les deux AAB à la piste
`internal` de l'application `ca.repere.app` dans Google Play Console.

## Versions automatiques

Une exécution utilise `GITHUB_RUN_NUMBER` et `GITHUB_RUN_ATTEMPT`. Une relance obtient donc
un nouveau code accepté par Play :

- mobile : `37 000 000 + run_number × 10 + run_attempt`;
- Wear : `36 000 000 + run_number × 10 + run_attempt`.

Le nom visible est identique pour mobile et Wear et prend la forme
`AAAA.MM.beta-<numéro>`, par exemple `2026.08.beta-123`. Le numéro correspond à
`run_number × 10 + run_attempt`, de sorte qu'une relance reçoit aussi un nom unique.
Les versions locales restent définies dans les fichiers Gradle; le CI les remplace uniquement
pour son build.

## Secrets GitHub requis

Créer l'environnement GitHub `play-internal`, puis y ajouter :

- `ANDROID_KEYSTORE_BASE64` : contenu Base64 du fichier JKS d'upload;
- `ANDROID_KEYSTORE_PASSWORD` : mot de passe du JKS;
- `ANDROID_KEY_ALIAS` : alias de la clé d'upload;
- `ANDROID_KEY_PASSWORD` : mot de passe de la clé;
- `PLAY_SERVICE_ACCOUNT_JSON` : JSON complet du compte de service Google Play.

Pour encoder le JKS sous PowerShell :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("android/repere-upload.jks"))
```

Le compte de service doit avoir accès à l'application dans Play Console et le projet Google
Cloud doit avoir l'API Google Play Android Developer activée. L'application et sa fiche Play
doivent avoir été initialisées manuellement au moins une fois avant le premier déploiement API.

## Flux de travail

1. Développer dans `dev` ou fusionner une branche de fonctionnalité vers `dev`.
2. Pousser `dev` sur GitHub.
3. Vérifier l'environnement `play-internal` dans l'onglet Actions.
4. Une fois les tests réussis, la version apparaît automatiquement pour les testeurs internes.

La concurrence est sérialisée : deux pushs rapprochés ne créent pas simultanément des éditions
Google Play incompatibles.
