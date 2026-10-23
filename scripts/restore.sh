#!/usr/bin/env sh
# Restore a dump made by scripts/backup.sh into DATABASE_URL. Destructive: existing objects are
# dropped first. Stop the API before running it.
#   ./scripts/restore.sh ~/Documents/JurisTrack/backups/juristrack-20261022T021500Z.dump
set -eu

FILE=${1:?usage: scripts/restore.sh <dump file>}
# DATABASE_URL (postgresql://user:password@host:port/database) comes from the environment, e.g. for dev:
#   DATABASE_URL=postgresql://juristrack:juristrack_dev_pw@localhost:5437/juristrack
: "${DATABASE_URL:?DATABASE_URL is not set}"
URL=$(printf '%s' "$DATABASE_URL" | sed 's/[?&]currentSchema=[^&]*//')

printf 'Restore %s into %s? This replaces its data. Type "restore" to continue: ' "$FILE" "$(printf '%s' "$URL" | sed -E 's#//[^@]*@#//***@#')"
read -r answer
[ "$answer" = "restore" ] || { echo "aborted"; exit 1; }

if [ -n "${PG_DOCKER:-}" ]; then
  CONTAINER_URL=$(printf '%s' "$URL" | sed -E 's#@[^/]+/#@localhost:5432/#')
  docker exec -i "$PG_DOCKER" pg_restore --clean --if-exists --no-owner --dbname="$CONTAINER_URL" < "$FILE"
else
  pg_restore --clean --if-exists --no-owner --dbname="$URL" "$FILE"
fi
echo "restored $FILE"
