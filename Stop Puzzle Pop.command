#!/bin/zsh
# Double-click this file in Finder to stop the local portal, PostgreSQL, and Docker Desktop.
# It never deletes the PostgreSQL volume or the saved episode files.

export BRAIN_BOOSTER_PROJECT_DIR="${0:A:h}"
source "$BRAIN_BOOSTER_PROJECT_DIR/scripts/puzzle-pop-local.zsh" || exit 1

bb_prepare_path
mkdir -p "$BB_RUNTIME_DIR"
bb_stop_portal || exit 1

cd "$BRAIN_BOOSTER_PROJECT_DIR" || exit 1
if bb_docker_ready; then
  print -- "Stopping the saved PostgreSQL container…"
  docker compose stop postgres || { bb_alert "The portal stopped, but PostgreSQL could not be stopped. Check Docker Desktop."; exit 1; }
else
  print -- "Docker Desktop's engine is already stopped or unavailable."
fi
print -- "Closing Docker Desktop…"
/usr/bin/osascript -e 'tell application "Docker Desktop" to quit' >/dev/null 2>&1 || /usr/bin/osascript -e 'tell application "Docker" to quit' >/dev/null 2>&1 || true

bb_alert "Puzzle Pop, PostgreSQL, and Docker Desktop have been stopped. Your saved episodes and database volume remain intact."
