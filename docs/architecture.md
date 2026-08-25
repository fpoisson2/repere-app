# Architecture

React/TypeScript est compilé en fichiers statiques servis par FastAPI. L’API utilise SQLAlchemy et Alembic; SQLite WAL est le défaut, PostgreSQL fonctionne en remplaçant `DATABASE_URL`. Toutes les lignes métier portent un `user_id`. Les mots de passe sont Argon2, les sessions sont signées et les cookies peuvent être marqués Secure derrière TLS.

Le code (`/opt/alcohol-tracker`), la configuration (`/etc/alcohol-tracker`) et les données (`/var/lib/alcohol-tracker`) sont séparés. Caddy termine HTTP dans le LXC et transmet les en-têtes `X-Forwarded-*`. Aucun service cloud, Redis ou télémétrie n’est requis.

