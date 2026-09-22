#!/usr/bin/env bash
# MoXu: one Java backend and one React/Vite development server.
# Reads exported environment variables; does not automatically load .env files.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${MOXU_BACKEND_PORT:-${WENSHAPE_BACKEND_PORT:-8080}}"
FRONTEND_PORT="${MOXU_FRONTEND_PORT:-${WENSHAPE_FRONTEND_PORT:-3000}}"
DATA_DIR="${MOXU_DATA_DIR:-${WENSHAPE_DATA_DIR:-$ROOT_DIR/data}}"
BACKEND_PID=""
FRONTEND_PID=""

fail() { echo "[MoXu] $*" >&2; exit 1; }
for tool in java mvn node npm curl; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required tool missing: $tool (JDK 21+, Maven 3.9+, Node.js 18+)."
done
JAVA_BIN="$(command -v java)"
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
fi
[[ -x "$JAVA_BIN" ]] || fail "JAVA_HOME does not contain an executable bin/java."
DATA_DIR="$(node -e 'console.log(require("path").resolve(process.argv[1], process.argv[2]))' "$ROOT_DIR" "$DATA_DIR")"

check_ports() {
    node - "$BACKEND_PORT" "$FRONTEND_PORT" <<'JS'
const net = require('net');
const ports = process.argv.slice(2).map(Number);
if (ports.some(p => !Number.isInteger(p) || p < 1 || p > 65535) || ports[0] === ports[1]) {
    console.error('[MoXu] Set two distinct ports between 1 and 65535.');
    process.exit(1);
}
const check = port => new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', () => reject(new Error('Port ' + port + ' is unavailable. Existing services were left running.')));
    server.listen({host: '127.0.0.1', port, exclusive: true}, () => server.close(resolve));
});
(async () => { for (const port of ports) await check(port); })().catch(e => {
    console.error('[MoXu] ' + e.message);
    process.exitCode = 1;
});
JS
}

# Never stop a process by port, executable name, or a saved PID file.
is_owned() {
    [[ -n "$1" ]] && [[ "$(ps -o ppid= -p "$1" 2>/dev/null | tr -d ' ')" == "$$" ]]
}
cleanup() {
    local pid attempt
    for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
        if is_owned "$pid"; then kill -TERM "$pid" 2>/dev/null || true; fi
    done
    for ((attempt = 0; attempt < 10; attempt++)); do
        if ! is_owned "$FRONTEND_PID" && ! is_owned "$BACKEND_PID"; then break; fi
        sleep 0.5
    done
    for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
        if is_owned "$pid"; then kill -KILL "$pid" 2>/dev/null || true; fi
        if [[ -n "$pid" ]]; then wait "$pid" 2>/dev/null || true; fi
    done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

check_ports
mkdir -p "$ROOT_DIR/.moxu-runtime"
RUN_DIR="$(mktemp -d "$ROOT_DIR/.moxu-runtime/run.XXXXXX")"
echo "[MoXu] Build and service logs: $RUN_DIR"
echo "[MoXu] Building Java backend..."
if ! mvn -B -f "$ROOT_DIR/backend-java/pom.xml" "-Dmoxu.build.directory=$RUN_DIR/backend" -DskipTests package >"$RUN_DIR/build.log" 2>&1; then
    fail "Java build failed. Inspect $RUN_DIR/build.log"
fi
if [[ ! -f "$ROOT_DIR/web/node_modules/vite/bin/vite.js" ]]; then
    echo "[MoXu] Installing frontend dependencies..."
    if ! (cd "$ROOT_DIR/web" && npm ci) >"$RUN_DIR/npm.log" 2>&1; then
        fail "Frontend dependency install failed. Inspect $RUN_DIR/npm.log"
    fi
fi
check_ports
mkdir -p "$DATA_DIR"
(
    cd "$ROOT_DIR"
    exec "$JAVA_BIN" -jar "$RUN_DIR/backend/moxu-backend.jar" \
        --server.address=127.0.0.1 "--server.port=$BACKEND_PORT" "--wenshape.data-dir=$DATA_DIR"
) >"$RUN_DIR/backend.log" 2>&1 &
BACKEND_PID=$!

wait_ready() {
    local pid="$1" url="$2" kind="$3" deadline=$((SECONDS + 120))
    while ((SECONDS < deadline)); do
        is_owned "$pid" || fail "$kind exited. Inspect logs in $RUN_DIR"
        if [[ "$kind" == backend ]]; then
            if curl --noproxy '*' --fail --silent --max-time 2 "$url" | node -e '
let body = ""; process.stdin.on("data", x => body += x);
process.stdin.on("end", () => { try { const h = JSON.parse(body); process.exit(h.status === "ok" && h.application === "moxu-backend" ? 0 : 1); } catch { process.exit(1); } });'; then
                is_owned "$pid" && return 0
            fi
        elif curl --noproxy '*' --fail --silent --max-time 2 "$url" >/dev/null; then
            is_owned "$pid" && return 0
        fi
        sleep 1
    done
    fail "$kind readiness check timed out. Inspect logs in $RUN_DIR"
}

wait_ready "$BACKEND_PID" "http://127.0.0.1:$BACKEND_PORT/health" backend
(
    cd "$ROOT_DIR/web"
    export VITE_DEV_PORT="$FRONTEND_PORT" VITE_BACKEND_PORT="$BACKEND_PORT"
    export VITE_BACKEND_URL="http://127.0.0.1:$BACKEND_PORT" VITE_BACKEND_WS_URL="ws://127.0.0.1:$BACKEND_PORT"
    exec node "$ROOT_DIR/web/node_modules/vite/bin/vite.js" --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort
) >"$RUN_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
wait_ready "$FRONTEND_PID" "http://127.0.0.1:$FRONTEND_PORT/" frontend

echo "[MoXu] Ready: http://127.0.0.1:$FRONTEND_PORT"
echo "[MoXu] Health: http://127.0.0.1:$BACKEND_PORT/health"
echo "[MoXu] Data: $DATA_DIR"
echo "[MoXu] Press Ctrl+C to stop only the services started by this launch."
while is_owned "$BACKEND_PID" && is_owned "$FRONTEND_PID"; do sleep 1; done
fail "A service exited. Inspect logs in $RUN_DIR"
