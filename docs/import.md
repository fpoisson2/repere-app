# Import CSV

L’API accepte UTF-8 avec BOM, `;` ou `,`, et les heures 24 h ou AM/PM. Colonnes attendues : `id,name,start_date,start_time,duration_min,volume_ml,abv_pct,cost,glass_icon`.

L’identifiant stable est `source:id`; sans ID, un SHA-256 est calculé sur date, heure, nom, volume, ABV et durée. Une contrainte unique par utilisateur rend un réimport idempotent. `cost < 0` devient `NULL`; l’icône source reste conservée. Chaque ligne appartient à un `import_batch`, supprimable avec ses consommations. Le rapport fournit importées, ignorées et échouées.

