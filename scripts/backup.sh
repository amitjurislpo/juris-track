#!/usr/bin/env sh
# Daily Postgres backup. Schedule from cron, e.g.
#   15 2 * * *  DATABASE_URL=postgresql://user:password@host:5467/juristrack_prd /srv/juristrack/scripts/backup.sh >> /var/backups/juristrack/backup.log 2>&1
#
# Writes a compressed custom-format dump to $BACKUP_DIR (default $HOME/Documents/JurisTrack/backups) and keeps
# $BACKUP_KEEP_DAYS days (default 14). Restore with scripts/restore.sh <file>.
# Needs pg_dump 16+ on PATH, or set PG_DOCKER=juristrack-backend-db to run it inside the compose container.
# Copy the dumps off the server too.
set -eu

# DATABASE_URL (postgresql://user:password@host:port/database) comes from the environment, e.g. for dev:
#   DATABASE_URL=postgresql://juristrack:juristrack_dev_pw@localhost:5437/juristrack
: "${DATABASE_URL:?DATABASE_URL is not set}"
URL=$(printf '%s' "$DATABASE_URL" | sed 's/[?&]currentSchema=[^&]*//')
DIRECTORY=${BACKUP_DIR:-$HOME/Documents/JurisTrack/backups}
KEEP_DAYS=${BACKUP_KEEP_DAYS:-14}
mkdir -p "$DIRECTORY"
FILE="$DIRECTORY/juristrack-$(date -u +%Y%m%dT%H%M%SZ).dump"

if [ -n "${PG_DOCKER:-}" ]; then
  # inside the container the database listens on 5432
  CONTAINER_URL=$(printf '%s' "$URL" | sed -E 's#@[^/]+/#@localhost:5432/#')
  docker exec "$PG_DOCKER" pg_dump --format=custom --no-owner "$CONTAINER_URL" > "$FILE"
else
  pg_dump --format=custom --no-owner --file="$FILE" "$URL"
fi

[ -s "$FILE" ] || { echo "backup failed: $FILE is empty" >&2; rm -f "$FILE"; exit 1; }
echo "$(date -u +%FT%TZ) wrote $FILE ($(wc -c < "$FILE") bytes)"
find "$DIRECTORY" -name 'juristrack-*.dump' -type f -mtime +"$KEEP_DAYS" -print -delete
