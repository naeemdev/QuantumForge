#!/usr/bin/env bash
# Starts or stops the full stack for the browser tests in tests/e2e:
# API (dev profile), a dev-process worker with the TypeScript adapter, and the
# production build of the web console.
#
#   scripts/e2e-stack.sh start     # builds what is needed, starts, waits until ready
#   scripts/e2e-stack.sh stop
#
# Database: E2E_DB_URL / E2E_DB_USER / E2E_DB_PASSWORD
# (default jdbc:postgresql://localhost:5432/quantumforge_e2e, quantumforge/quantumforge).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
state="$root/build/e2e"
mkdir -p "$state"

wait_for() {
  local url=$1 name=$2
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null "$url"; then return 0; fi
    sleep 2
  done
  echo "$name did not become ready ($url); last log lines:" >&2
  tail -n 40 "$state/$name.log" >&2 || true
  return 1
}

start() {
  # --no-daemon: a background daemon would inherit this script's output and keep callers waiting.
  (cd "$root" && ./gradlew -q --no-daemon --console=plain :services:api:bootJar :services:worker:installDist)
  if [[ ! -f "$root/apps/web/.next/BUILD_ID" ]]; then
    (cd "$root" && NEXT_PUBLIC_API_URL=http://localhost:8080 pnpm web:build)
  fi

  local token="e2e-worker-token-$(date +%s)"
  local blobs="$state/blobs"
  rm -rf "$blobs" && mkdir -p "$blobs"

  SPRING_PROFILES_ACTIVE=dev \
  QF_DB_URL="${E2E_DB_URL:-jdbc:postgresql://localhost:5432/quantumforge_e2e}" \
  QF_DB_USER="${E2E_DB_USER:-quantumforge}" \
  QF_DB_PASSWORD="${E2E_DB_PASSWORD:-quantumforge}" \
  QF_STORAGE_ROOT="$blobs" \
  QF_DEV_WORKER_TOKEN="$token" \
    setsid nohup java -jar "$root"/services/api/build/libs/api-*.jar > "$state/api.log" 2>&1 < /dev/null &
  echo $! > "$state/api.pid"
  wait_for http://localhost:8080/health/ready api

  QF_CONTROL_PLANE_URL=http://localhost:8081 \
  QF_WORKER_TOKEN="$token" \
  QF_ISOLATION_MODE=dev-process \
  QF_WORK_ROOT="$state/work" \
  QF_TS_ANALYZER="$root/language-packs/typescript" \
    setsid nohup "$root/services/worker/build/install/quantumforge-worker/bin/quantumforge-worker" > "$state/worker.log" 2>&1 < /dev/null &
  echo $! > "$state/worker.pid"

  # Started directly (not in a subshell) so $! is the process-group leader that stop() signals.
  setsid nohup pnpm --dir "$root/apps/web" start > "$state/web.log" 2>&1 < /dev/null &
  echo $! > "$state/web.pid"
  wait_for http://localhost:3000/login web
  echo "stack ready: web http://localhost:3000, api http://localhost:8080 (logs in $state)"
}

stop() {
  for name in web worker api; do
    if [[ -f "$state/$name.pid" ]]; then
      local pid
      pid="$(cat "$state/$name.pid")"
      # Each service runs in its own process group (setsid), so this also stops
      # grandchildren such as the next-server that pnpm spawns.
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
      rm -f "$state/$name.pid"
    fi
  done
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
