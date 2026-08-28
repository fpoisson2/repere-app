# Politique de sécurité

## Signaler une vulnérabilité

Merci de **ne pas** ouvrir d'issue publique pour une faille de sécurité.

Utilise la fonction **Private vulnerability reporting** de GitHub :
<https://github.com/fpoisson2/repere-app/security/advisories/new>

Réponse initiale visée : sous 7 jours. Merci d'inclure une description,
les étapes de reproduction et l'impact estimé.

## Portée

- Applications Android (`android/`)
- Backend FastAPI (`backend/`)
- Frontend web (`frontend/`)

Le serveur est toujours auto-hébergé ; l'authentification utilise OAuth 2.0
avec PKCE. Les rapports concernant la configuration d'un déploiement tiers
non maintenu ici sont hors portée.

## Versions supportées

Seule la dernière version publiée sur la piste de test interne reçoit des
correctifs de sécurité.
