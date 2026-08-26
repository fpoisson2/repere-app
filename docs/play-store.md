# Test Android et Wear OS dans Google Play

Les modules `mobile` et `wear` utilisent le même package `ca.repere.app`. Ils doivent également être signés avec la même clé d’envoi. Google Play distribue les deux facteurs de forme depuis la même fiche, mais les bundles sont téléversés et versionnés séparément.

## 1. Déployer le backend compatible

Avant d’associer l’application, déployer le code actuel sur `https://repere.ve2fpd.com` et appliquer les migrations Alembic. `/api/health` doit ensuite annoncer `database_version: 0013` ou une version ultérieure.

## 2. Créer et sauvegarder une clé d’envoi

Dans Android Studio : **Build → Generate Signed Bundle / APK → Android App Bundle → Create new**. Créer `android/repere-upload.jks`, avec l’alias `repere-upload`, puis conserver une sauvegarde chiffrée du fichier et de ses mots de passe hors du dépôt.

Copier `android/keystore.properties.example` vers `android/keystore.properties` et y inscrire les mots de passe. Ces deux fichiers sensibles sont ignorés par Git.

Google Play App Signing conservera la clé de signature finale. La clé locale est la clé d’envoi et peut être réinitialisée auprès de Google si elle est perdue, mais elle doit tout de même être sauvegardée soigneusement.

## 3. Produire les bundles signés

```powershell
cd android
.\gradlew.bat clean playBundles
```

Fichiers attendus :

```text
mobile/build/outputs/bundle/release/mobile-release.aab
wear/build/outputs/bundle/release/wear-release.aab
```

Vérifier chaque signature :

```powershell
jarsigner -verify -verbose -certs mobile/build/outputs/bundle/release/mobile-release.aab
jarsigner -verify -verbose -certs wear/build/outputs/bundle/release/wear-release.aab
```

## 4. Configurer Play Console

1. Créer une application avec le package `ca.repere.app` et activer Play App Signing.
2. Créer une piste de test interne ou fermé pour téléphone et téléverser `mobile-release.aab`.
3. Dans **Paramètres avancés → Facteurs de forme**, ajouter Wear OS.
4. Créer la piste Wear OS correspondante et téléverser `wear-release.aab`.
5. Ajouter au moins une capture Wear OS réelle et mentionner la complication dans la fiche.
6. Ajouter une politique de confidentialité publique accessible en HTTPS.
7. Compléter Data Safety et la déclaration Health Apps pour sommeil, pas, exercice, fréquence cardiaque, fréquence au repos, HRV et lecture facultative en arrière-plan.

## 5. Vérifier l’installation réelle

- accepter le test avec le même compte Google sur le téléphone et la montre;
- installer Repère depuis la fiche Play du téléphone;
- depuis la section Wear OS de la fiche ou le Play Store de la montre, installer le compagnon;
- confirmer que les deux applications portent la même version fonctionnelle mais des `versionCode` différents;
- tester une saisie téléphone et montre sans réseau, puis réactiver le réseau;
- activer ensuite la synchronisation facultative vers `https://repere.ve2fpd.com` et vérifier l’absence de doublons;
- révoquer une permission Health Connect et confirmer que les autres types continuent de fonctionner.
