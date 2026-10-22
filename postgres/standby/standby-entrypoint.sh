#!/bin/bash
# Wraps the stock postgres image entrypoint. On a completely empty data
# directory (first container start, or after `docker compose down -v`) it
# clones the primary with pg_basebackup -- a real base backup + WAL
# streaming setup, the same mechanism you'd use in production -- then hands
# off to the normal postgres startup, which detects standby.signal (written
# by pg_basebackup -R) and comes up in hot-standby/recovery mode instead of
# running initdb.
#
# On every later restart the data directory is already populated, so this
# block is skipped entirely and postgres just resumes streaming from where
# it left off (using the replication slot created on the primary, so no
# work is lost even after a restart).
set -euo pipefail

PRIMARY_HOST="${PRIMARY_HOST:?PRIMARY_HOST must be set}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
REPLICATION_USER="${REPLICATION_USER:-replicator}"
REPLICATION_PASSWORD="${REPLICATION_PASSWORD:-replicator_pw}"
REPLICATION_SLOT="${REPLICATION_SLOT:-db_eu_slot}"
APPLICATION_NAME="${APPLICATION_NAME:-db_eu}"

if [ -z "$(ls -A "$PGDATA" 2>/dev/null)" ]; then
    echo "[standby-entrypoint] empty PGDATA -- taking a base backup from ${PRIMARY_HOST}:${PRIMARY_PORT}"

    until PGPASSWORD="$REPLICATION_PASSWORD" pg_basebackup \
        --host="$PRIMARY_HOST" \
        --port="$PRIMARY_PORT" \
        --username="$REPLICATION_USER" \
        --pgdata="$PGDATA" \
        --format=plain \
        --wal-method=stream \
        --write-recovery-conf \
        --slot="$REPLICATION_SLOT" \
        --progress \
        --dbname="application_name=${APPLICATION_NAME}"
    do
        echo "[standby-entrypoint] primary not ready yet, retrying in 2s..."
        sleep 2
    done

    chmod 0700 "$PGDATA"
    echo "[standby-entrypoint] base backup complete -- standby.signal is in place, primary_slot_name=${REPLICATION_SLOT}"
else
    echo "[standby-entrypoint] PGDATA already populated, skipping base backup and resuming streaming"
fi

exec docker-entrypoint.sh "$@"
