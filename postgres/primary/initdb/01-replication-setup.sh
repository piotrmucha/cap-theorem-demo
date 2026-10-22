#!/bin/bash
# Runs once, on first startup of the primary, right after 00-schema.sql.
#
# Two things happen here, both of which are the "real" production
# way to set up streaming replication -- nothing here is emulated:
#
# 1. Create a dedicated replication role. Postgres requires a role with the
#    REPLICATION attribute for a standby to open a WAL streaming connection.
# 2. Pre-create a *physical replication slot* for the standby, and open up
#    pg_hba.conf so that role is actually allowed to connect.
#
# Why a replication slot: without one, if the standby is disconnected (e.g.
# during our simulated network partition) for long enough, the primary is
# free to recycle/delete WAL segments the standby still needs, forcing a
# fresh base backup to resync. A slot tells the primary "hold onto WAL until
# this specific standby has consumed it", which is what makes the
# disconnect -> reconnect -> catch up demo reliably repeatable.
set -euo pipefail

REPLICATION_USER="${REPLICATION_USER:-replicator}"
REPLICATION_PASSWORD="${REPLICATION_PASSWORD:-replicator_pw}"
REPLICATION_SLOT="${REPLICATION_SLOT:-db_eu_slot}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE ${REPLICATION_USER} WITH REPLICATION LOGIN PASSWORD '${REPLICATION_PASSWORD}';
    SELECT pg_create_physical_replication_slot('${REPLICATION_SLOT}');
EOSQL

{
    echo "# --- added by 01-replication-setup.sh ---"
    echo "host replication ${REPLICATION_USER} 0.0.0.0/0 scram-sha-256"
    echo "host replication ${REPLICATION_USER} ::0/0      scram-sha-256"
    echo "host all         all                0.0.0.0/0 scram-sha-256"
    echo "host all         all                ::0/0      scram-sha-256"
} >> "$PGDATA/pg_hba.conf"
