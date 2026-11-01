#!/bin/sh
set -eu

PGBIN="$(ls -d /usr/lib/postgresql/*/bin | head -n 1)"
PGDATA_DIR=/var/lib/postgresql/keel

if [ ! -s "$PGDATA_DIR/PG_VERSION" ]; then
  mkdir -p "$PGDATA_DIR"
  chown postgres:postgres "$PGDATA_DIR"
  su postgres -c "$PGBIN/initdb -D $PGDATA_DIR -U postgres --auth=trust" > /dev/null
fi
su postgres -c "$PGBIN/pg_ctl -D $PGDATA_DIR -l /tmp/postgres.log -w start -o '-c listen_addresses=127.0.0.1 -c shared_buffers=16MB -c max_connections=20 -c work_mem=2MB -c io_method=sync -c max_worker_processes=2 -c max_parallel_workers=0'"
if ! su postgres -c "psql -tAc \"select 1 from pg_roles where rolname = 'keel'\"" | grep -q 1; then
  su postgres -c "psql -q -c \"create role keel login password 'keel'\" -c \"create database keel owner keel\""
fi

export PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE=keel PGUSER=keel PGPASSWORD=keel
export KEEL_ROLE=all KEEL_RECEIVER_URL=http://127.0.0.1:8090

(
  while true; do
    PORT=8090 java $RECEIVER_JAVA_OPTS -jar /app/keel-receiver.jar || true
    echo "receiver exited, restarting"
    sleep 1
  done
) &

while true; do
  java $KEEL_JAVA_OPTS -jar /app/keel-runtime.jar || echo "keel exited with $?, restarting"
  sleep 1
done
