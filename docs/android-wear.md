# Android et Wear OS

Le dossier `android/` contient deux applications signées avec le même identifiant et deux modules partagés :

- `mobile` : application Android compagnon;
- `wear` : application Wear OS compagnon et complication de cadran;
- `core` : modèles métier communs au téléphone et à la montre;
- `data` : base Room et synchronisation durable par WorkManager.

Le téléphone est la source de vérité locale et fonctionne sans compte ni serveur. Il crée des favoris intégrés au premier démarrage et conserve les consommations ainsi que les résumés Health Connect dans Room. Si l’utilisateur active la synchronisation facultative, les changements locaux sont envoyés de manière idempotente à `/api/sync`, puis les changements serveur sont récupérés avec un curseur. Les suppressions font partie du journal de synchronisation et ne sont donc pas perdues hors ligne.

L’application Android propose quatre espaces :

- **Aujourd’hui** : total quotidien, favoris et saisie personnalisée utilisables hors ligne;
- **Historique** : copie Room et suppression synchronisée;
- **Santé** : choix granulaire des autorisations et import de résumés Health Connect sur 14 jours;
- **Réglages** : association au serveur et transmission de la configuration à la montre.

Health Connect n’envoie que des agrégats quotidiens. Une journée sans enregistrement reste absente plutôt que d’être transformée en valeur zéro. L’historique étendu et la lecture en arrière-plan demeurent des consentements séparés et ne sont pas demandés par le flux standard.

## Fonctionnement

La base Repère reste la source de vérité. Une consommation démarrée depuis la montre est immédiatement créée avec `is_active=true`. Lorsque l’utilisateur la termine, l’API enregistre l’heure de fin et calcule la durée réelle. Le volume, le taux d’alcool et la quantité sont fixés dès le démarrage et peuvent ensuite être corrigés dans l’interface web.

La montre ne parle jamais directement à l’API web : elle relaie chaque commande (démarrer/terminer une consommation) au téléphone par le Wear OS Data Layer (`MessageClient`), et c’est le téléphone qui l’exécute contre le serveur ou la stocke localement s’il est hors ligne. Le téléphone repousse ensuite l’état à jour (consommation active, alcoolémie estimée, total du jour) à la montre par un `DataItem` du même Data Layer, aussi bien après une action de la montre qu’après ses propres synchronisations périodiques.

## Association

1. Dans Repère, ouvrir **Réglages → Android et Wear OS**.
2. Appuyer sur **Générer un code**.
3. Dans l’application Android, saisir l’adresse, par exemple `http://192.168.1.151`, et le code à six chiffres.
4. Appuyer sur **Associer**, puis **Synchroniser la montre** si nécessaire.

Le code expire après dix minutes et ne peut servir qu’une fois. Le jeton peut être révoqué depuis les réglages web sans changer le mot de passe du compte.

## Compilation

Ouvrir le dossier `android/` dans une version récente d’Android Studio avec JDK 17 et le SDK Android 36, puis synchroniser Gradle. Le Gradle Wrapper inclus permet aussi de construire sans installation globale de Gradle :

```powershell
cd android
.\gradlew.bat :mobile:assembleDebug :wear:assembleDebug
```

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

HTTP en clair est désactivé dans tous les builds. Le serveur par défaut est `https://repere.ve2fpd.com`; une autre adresse peut être configurée, mais elle doit utiliser HTTPS.

La préparation de la piste de test Play Console est détaillée dans [play-store.md](play-store.md).
