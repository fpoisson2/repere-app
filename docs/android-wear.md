# Android et Wear OS

Le dossier `android/` contient deux applications signées avec le même identifiant :

- `mobile` : application Android compagnon;
- `wear` : application Wear OS autonome et complication de cadran.

## Fonctionnement

La base Repère reste la source de vérité. Une consommation démarrée depuis la montre est immédiatement créée avec `is_active=true`. Lorsque l’utilisateur la termine, l’API enregistre l’heure de fin et calcule la durée réelle. Le volume, le taux d’alcool et la quantité sont fixés dès le démarrage et peuvent ensuite être corrigés dans l’interface web.

L’application téléphone transmet l’adresse du serveur et le jeton à la montre par le Wear OS Data Layer. La montre communique ensuite directement avec l’API, par le réseau relayé du téléphone, le Wi-Fi ou sa connexion cellulaire.

## Association

1. Dans Repère, ouvrir **Réglages → Android et Wear OS**.
2. Appuyer sur **Générer un code**.
3. Dans l’application Android, saisir l’adresse, par exemple `http://192.168.1.151`, et le code à six chiffres.
4. Appuyer sur **Associer**, puis **Synchroniser la montre** si nécessaire.

Le code expire après dix minutes et ne peut servir qu’une fois. Le jeton peut être révoqué depuis les réglages web sans changer le mot de passe du compte.

## Compilation

Ouvrir le dossier `android/` dans une version récente d’Android Studio avec JDK 17 et le SDK Android 35, puis synchroniser Gradle.

- sélectionner `mobile` pour produire l’APK du téléphone;
- sélectionner `wear` pour produire l’APK de la montre;
- signer les deux APK avec la même clé, condition nécessaire au Data Layer;
- installer avec Android Studio ou `adb install`.

Les APK de développement se trouvent normalement dans :

```text
android/mobile/build/outputs/apk/debug/mobile-debug.apk
android/wear/build/outputs/apk/debug/wear-debug.apk
```

## Complication du cadran

Dans le sélecteur de complications du cadran, choisir **Repère**. Elle affiche **Démarrer** ou **Terminer**. Un toucher lance directement l’action avec les derniers volume, taux et quantité choisis. Ouvrir l’application montre normalement pour ajuster ces trois valeurs.

## Réseau et sécurité

L’accès HTTP en clair est activé pour permettre un serveur de homelab sur le VLAN local. Ne publiez pas ce port HTTP sur Internet. Pour un accès distant, utilisez un nom DNS avec HTTPS via Caddy, Nginx Proxy Manager, Traefik ou un tunnel sécurisé, puis configurez cette URL dans l’application.
