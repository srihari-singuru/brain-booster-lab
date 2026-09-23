#!/bin/zsh
# Double-click this file in Finder to rebuild and start the local Puzzle Pop portal.

export BRAIN_BOOSTER_PROJECT_DIR="${0:A:h}"
source "$BRAIN_BOOSTER_PROJECT_DIR/scripts/puzzle-pop-local.zsh" || exit 1

bb_prepare_path
bb_select_java_25 || exit 1
mkdir -p "$BB_RUNTIME_DIR"

# Always replace only this portal process, so the launched app reflects the latest code.
bb_stop_portal || exit 1
bb_start_docker || exit 1
bb_start_database || exit 1
bb_load_environment || exit 1

cd "$BRAIN_BOOSTER_PROJECT_DIR" || exit 1
print -- "Building the latest Puzzle Pop code…"
if ! mvn -DskipTests clean package; then
  bb_alert "The build failed, so the portal was not started. Fix the errors shown in this Terminal window, then run Start Puzzle Pop again."
  exit 1
fi

print -- "Starting the local portal…"
rm -f "$BB_PID_FILE"
nohup java -jar "$BB_JAR" --server.port="$BB_PORT" >"$BB_LOG_FILE" 2>&1 &
print -- $! >"$BB_PID_FILE"

if ! bb_wait_for_portal; then
  bb_stop_portal || true
  exit 1
fi

open "http://localhost:$BB_PORT/"
print -- "Puzzle Pop is ready at http://localhost:$BB_PORT/"
