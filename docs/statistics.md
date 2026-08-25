# Statistiques et période observée

La borne `tracking_start_date` est une contrainte de domaine, appliquée avant toute agrégation. Sa valeur automatique est la date de la première consommation ou du premier import; un réglage manuel devient prioritaire. Une plage est ramenée à `[max(début demandé, tracking_start_date), min(fin demandée, aujourd’hui)]`.

Seuls les jours de cet intervalle sont matérialisés. Un jour sans ligne vaut alors réellement 0 et peut être compté « sans alcool ». Avant cette borne, rien n’est créé, moyenné, comparé ou interprété. Les moyennes mobiles utilisent seulement le préfixe observable disponible et indiquent ainsi une fenêtre partielle au début du suivi.

Les quartiles et P90 utilisent une interpolation linéaire; l’écart-type est celui de la population observée. Le coefficient de variation n’est pas défini lorsque la moyenne vaut zéro. Les corrélations du journal sont exploratoires : **corrélation statistique ≠ causalité**.

