#!/bin/bash
set -e
for db in $(echo $POSTGRES_MULTIPLE_DATABASES | tr ',' ' '); do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -c "CREATE DATABASE $db;" 2>/dev/null || echo "DB $db already exists"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -c "GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;" || true
done
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -c "CREATE DATABASE keycloak;" 2>/dev/null || true
