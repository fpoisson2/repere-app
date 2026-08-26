# Analyse longitudinale personnelle

Repère conserve les données sur l'instance auto-hébergée. Aucun agrégat de santé,
check-in ou historique d'alcool n'est envoyé à un tiers par défaut. Les résultats
sont des associations personnelles et ne constituent jamais une preuve causale.

## Temps et prévention des fuites

Toutes les features sont calculées avec un `cutoff_at_utc`. Une source n'est
admissible que si elle existait avant ce cutoff. Les check-ins marqués
`post_onset=true` sont exclus des prédicteurs de la consommation en cours. Une
valeur Health Connect manquante reste `null`; elle ne devient jamais zéro.

Les définitions sont versionnées (`personal-daily-v1`, `personal-mad-v1`). Un
holdout prospectif porte des bornes persistées et `holdout_frozen=true`.

## Health Connect

Le navigateur ne peut pas lire Health Connect. Le compagnon Android lit seulement
les types autorisés, agrège sur l'appareil, puis envoie ces agrégats à l'instance
Repère associée via `/api/health-connect/aggregates`. Chaque ligne conserve origine,
appareil, méthode d'agrégation, fenêtre UTC et qualité de couverture.

Permissions granulaires supportées : sommeil, HRV RMSSD, fréquence au repos,
fréquence cardiaque, pas et exercice. Historique supérieur à 30 jours et lecture
en arrière-plan sont des consentements supplémentaires et ne doivent être demandés
que lorsque l'utilisateur active la fonction correspondante.

## Calibration

- descriptif : 7 jours observés;
- associations : 20 jours et 5 événements;
- modèle régularisé : 42 jours et 10 événements;
- modèle temporel : jamais avant 90 jours.

La validation est chronologique. Les rapports comparent taux de base, historique
avec jour de semaine, modèle complet et ablations Health Connect/check-in. Ils
conservent AUROC, AUPRC, Brier, calibration, sensibilité, spécificité et faux
positifs par semaine.

## Confidentialité opérationnelle

Les journaux de production ne doivent contenir ni corps de requête ni valeur de
santé, d'alcool ou de craving. Le chiffrement du volume `/var/lib/alcohol-tracker`
doit être assuré par le stockage hôte (LUKS/ZFS natif chiffré). Les secrets du
service appartiennent à un fichier root-only, jamais à une unité systemd lisible.
