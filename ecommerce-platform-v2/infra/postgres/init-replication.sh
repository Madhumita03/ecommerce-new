#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" << 'SQL'
DO $$BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='replicator') THEN
    CREATE USER replicator WITH REPLICATION ENCRYPTED PASSWORD 'repl_secret';
  END IF;
END$$;
SQL
cat >> "$PGDATA/postgresql.conf" << 'EOF'
wal_level=replica
max_wal_senders=3
hot_standby=on
EOF
echo "host replication replicator 0.0.0.0/0 md5" >> "$PGDATA/pg_hba.conf"
