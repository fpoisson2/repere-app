# Modèle d’alcoolémie

Le moteur est une estimation Widmark simplifiée. Chaque boisson apporte `volume × ABV/100 × 0,789` grammes. L’absorption est linéaire entre le début et `durée + 30 minutes` (minimum 30 minutes). Après absorption, une élimination configurable en % BAC/heure est retranchée. La concentration estimée est `alcool restant / (poids × 1000 × ratio de distribution) × 100`, bornée à zéro.

Les valeurs par défaut sont 75 kg, ratio 0,68 et élimination 0,015 %/h. Elles varient fortement entre personnes et situations; nourriture, médicaments et santé ne sont pas modélisés.

> Cette valeur est une estimation mathématique. Elle ne constitue pas une mesure réelle de l’alcoolémie et ne doit jamais être utilisée pour déterminer s’il est sécuritaire ou légal de conduire.

