#!/bin/zsh
# Shared local-control helpers for the two double-clickable Brain Booster Lab scripts.
# This file is sourced by the .command launchers; do not run it directly.

if [[ -z "${BRAIN_BOOSTER_PROJECT_DIR:-}" ]]; then
  print -u2 -- "BRAIN_BOOSTER_PROJECT_DIR is required. Start with one of the .command files in the project folder."
  return 1
fi

typeset -gr BB_PORT=8090
typeset -gr BB_RUNTIME_DIR="$BRAIN_BOOSTER_PROJECT_DIR/outputs/local-runtime"
typeset -gr BB_PID_FILE="$BB_RUNTIME_DIR/portal.pid"
typeset -gr BB_LOG_FILE="$BB_RUNTIME_DIR/portal.log"
typeset -gr BB_JAR="$BRAIN_BOOSTER_PROJECT_DIR/target/brain-booster-lab-0.0.1-SNAPSHOT.jar"

bb_alert() {
  local message="$1"
  print -- "$message"
  /usr/bin/osascript -e 'on run argv' -e 'display alert "Brain Booster Lab" message (item 1 of argv)' -e 'end run' -- "$message" >/dev/null 2>&1 || true
}

bb_prepare_path() {
  export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
  if [[ -x /Applications/Docker.app/Contents/Resources/bin/docker ]]; then
    export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
  fi
}

bb_select_java_25() {
  # macOS's java_home can return an older Microsoft JDK even when the active
  # Homebrew Java 25 runtime is correctly installed, so verify the runtime.
  local homebrew_java="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
  if [[ -x "$homebrew_java/bin/java" ]] && "$homebrew_java/bin/java" -version 2>&1 | /usr/bin/grep -Eq 'version "25([."]|[0-9])'; then
    export JAVA_HOME="$homebrew_java"
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    unset JAVA_HOME
  fi
  if ! java -version 2>&1 | /usr/bin/grep -Eq 'version "25([."]|[0-9])'; then
    bb_alert "Java 25 is required to build Brain Booster Lab, but this launcher could not find it. Install Java 25, then run Start Brain Booster Lab again."
    return 1
  fi
}

bb_is_brain_booster_process() {
  local pid="$1" command
  command=$(ps -p "$pid" -o command= 2>/dev/null) || return 1
  [[ "$command" == *"brain-booster-lab-0.0.1-SNAPSHOT.jar"* ]]
}

bb_wait_for_exit() {
  local pid="$1"
  for _ in {1..20}; do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  return 1
}

bb_stop_portal() {
  local pid="" port_pid=""
  if [[ -f "$BB_PID_FILE" ]]; then
    pid=$(<"$BB_PID_FILE")
    if [[ "$pid" == <-> ]] && bb_is_brain_booster_process "$pid"; then
      print -- "Stopping Brain Booster Lab portal (PID $pid)…"
      kill -TERM "$pid" 2>/dev/null || true
      bb_wait_for_exit "$pid" || { print -- "Portal did not stop gracefully; ending its process."; kill -KILL "$pid" 2>/dev/null || true; }
    fi
    rm -f "$BB_PID_FILE"
  fi

  port_pid=$(lsof -t -nP -iTCP:"$BB_PORT" -sTCP:LISTEN 2>/dev/null | head -1) || true
  if [[ -n "$port_pid" ]]; then
    if bb_is_brain_booster_process "$port_pid"; then
      print -- "Stopping remaining Brain Booster Lab process on port $BB_PORT…"
      kill -TERM "$port_pid" 2>/dev/null || true
      bb_wait_for_exit "$port_pid" || kill -KILL "$port_pid" 2>/dev/null || true
    else
      bb_alert "Port $BB_PORT is in use by another application. It was not stopped. Please close that application, then try again."
      return 1
    fi
  fi
}

bb_docker_ready() {
  command -v docker >/dev/null 2>&1 || return 1
  # Docker's CLI can hang while Desktop is closing or its VM is restarting.
  # Bound this probe so a double-click launcher never waits indefinitely.
  docker info >/dev/null 2>&1 &
  local probe=$!
  for _ in {1..5}; do
    if ! kill -0 "$probe" 2>/dev/null; then
      wait "$probe"
      return $?
    fi
    sleep 1
  done
  kill -TERM "$probe" 2>/dev/null || true
  wait "$probe" 2>/dev/null || true
  return 1
}

bb_start_docker() {
  if bb_docker_ready; then return 0; fi
  if ! open -Ra Docker >/dev/null 2>&1; then
    bb_alert "Docker Desktop is not installed. Install and open Docker Desktop, then run Start Brain Booster Lab again."
    return 1
  fi
  print -- "Starting Docker Desktop…"
  open -gja Docker
  for _ in {1..90}; do
    bb_docker_ready && return 0
    sleep 2
  done
  bb_alert "Docker Desktop did not become ready within three minutes. Open Docker Desktop, wait for it to finish starting, then run Start Brain Booster Lab again."
  return 1
}

bb_start_database() {
  cd "$BRAIN_BOOSTER_PROJECT_DIR" || return 1
  print -- "Starting the saved PostgreSQL database…"
  docker compose up -d postgres || return 1
  for _ in {1..60}; do
    docker compose exec -T postgres pg_isready -U brain_booster -d brain_booster_lab >/dev/null 2>&1 && return 0
    sleep 1
  done
  bb_alert "PostgreSQL did not become ready. Open Docker Desktop to check the brain-booster-lab-postgres container, then try again."
  return 1
}

bb_load_environment() {
  cd "$BRAIN_BOOSTER_PROJECT_DIR" || return 1
  if [[ -f .env ]]; then
    set -a
    source .env
    set +a
  fi
  # Compose receives POSTGRES_PASSWORD, while Spring can also use DATABASE_PASSWORD.
  if [[ -z "${DATABASE_PASSWORD:-}" && -n "${POSTGRES_PASSWORD:-}" ]]; then
    export DATABASE_PASSWORD="$POSTGRES_PASSWORD"
  fi
}

bb_wait_for_portal() {
  for _ in {1..90}; do
    curl --fail --silent --show-error "http://localhost:$BB_PORT/actuator/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  print -u2 -- "The portal did not become ready. Recent server log:"
  tail -40 "$BB_LOG_FILE" 2>/dev/null || true
  bb_alert "Brain Booster Lab did not start. The technical details are in outputs/local-runtime/portal.log."
  return 1
}
