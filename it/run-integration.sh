#!/usr/bin/env bash
# Real-server integration test for NetworkLock.jar.
#
# Downloads official, checksum-verified Paper / Velocity / Waterfall builds, installs the
# same NetworkLock.jar on all of them and asserts the outcome of direct attacks and proxy
# joins. Needs: bash, curl, sha256sum, openssl, Java 21 on PATH, ports 35566 and 35600-35601
# free. Run from the repository root after `./gradlew shadowJar`.
#
#   it/run-integration.sh            # Paper 1.21.11 + Velocity 3.5.1 + Waterfall 1.21
#   PAPER=1.21.11 VELOCITY=3.5.1 WATERFALL=1.21 it/run-integration.sh
#   BACKEND=folia VELOCITY_MODE=bungeeguard it/run-integration.sh   # Folia backend, Velocity bungeeguard forwarding
set -uo pipefail

PAPER=${PAPER:-1.21.11}
BACKEND=${BACKEND:-paper}              # paper or folia (both use the PAPER version)
VELOCITY_MODE=${VELOCITY_MODE:-legacy}  # legacy or bungeeguard (both work with BungeeCord-mode backends)
VELOCITY=${VELOCITY:-3.5.1}
WATERFALL=${WATERFALL:-1.21}
ROOT=$(pwd)
WORK="$ROOT/it/work/auto"
JAR="$ROOT/build/libs/NetworkLock.jar"
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
FAILED=0
PIDS=()

[ -f "$JAR" ] || { echo "build first: ./gradlew shadowJar"; exit 2; }
rm -rf "$WORK/srv" && mkdir -p "$WORK/jars" "$WORK/srv" "$WORK/probe"

fetch() { # project version -> prints local jar path; verifies the published SHA-256
    local meta url sum name
    meta=$(curl -fsS "https://fill.papermc.io/v3/projects/$1/versions/$2/builds/latest") || return 1
    meta=$(printf '%s' "$meta" | tr -d '\n' | sed 's/.*"server:default"//') # skip module jars listed before it
    url=$(printf '%s' "$meta" | grep -o '"url": *"[^"]*"' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/')
    sum=$(printf '%s' "$meta" | grep -o '"sha256": *"[0-9a-f]*"' | head -1 | sed 's/.*"\([0-9a-f]*\)"/\1/')
    name=$(basename "$url")
    [ -f "$WORK/jars/$name" ] || curl -fsSL -o "$WORK/jars/$name" "$url" || return 1
    (cd "$WORK/jars" && echo "$sum  $name" | sha256sum -c --quiet -) || return 1
    echo "$WORK/jars/$name"
}

newkey() { openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'; }

start() { # dir jar ready-pattern [server args]
    : > "$1/cmd.txt"
    # all fds redirected, so the background pipeline never holds the caller's stdout open
    (cd "$1" && tail -f cmd.txt | java -Xmx1G -jar "$2" ${4:-} > console.log 2>&1) < /dev/null > /dev/null 2>&1 &
    PIDS+=($!)
    for _ in $(seq 1 120); do grep -q "$3" "$1/console.log" 2>/dev/null && return 0; sleep 1; done
    echo "server in $1 did not start"; tail -20 "$1/console.log"; return 1
}

send() { echo "$2" >> "$1/cmd.txt"; }

cleanup() {
    for d in "$WORK"/srv/*/; do [ -f "$d/cmd.txt" ] && { echo stop >> "$d/cmd.txt"; echo end >> "$d/cmd.txt"; echo shutdown >> "$d/cmd.txt"; }; done
    sleep 8
    # a write after the server exited makes each `tail -f` die of SIGPIPE
    for d in "$WORK"/srv/*/; do [ -f "$d/cmd.txt" ] && echo >> "$d/cmd.txt"; done
    sleep 1
    for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
}
trap cleanup EXIT

check() { # description, expected-prefix, actual
    if [[ "$3" == "$2"* ]]; then echo "PASS  $1"; else echo "FAIL  $1: expected '$2', got '$3'"; FAILED=1; fi
}

logged() { # description, file, pattern
    if grep -q "$3" "$2"; then echo "PASS  $1"; else echo "FAIL  $1: '$3' not in $(basename "$2")"; FAILED=1; fi
}

echo "== fetching servers"
PAPER_JAR=$(fetch "$BACKEND" "$PAPER") && VEL_JAR=$(fetch velocity "$VELOCITY") && WF_JAR=$(fetch waterfall "$WATERFALL") \
    || { echo "download or checksum failed"; exit 2; }
javac -d "$WORK/probe" -cp "$ROOT/networklock-core/build/classes/java/main" "$ROOT/it/Probe.java" || exit 2

KV=$(newkey); KW=$(newkey)
B="$WORK/srv/backend"; V="$WORK/srv/velocity"; W="$WORK/srv/waterfall"
mkdir -p "$B/plugins/NetworkLock" "$B/config" "$V/plugins/networklock" "$W/plugins/NetworkLock"
for d in "$B" "$V" "$W"; do cp "$JAR" "$d/plugins/"; done

echo "eula=true" > "$B/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=35566\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nwhite-list=false\n' > "$B/server.properties"
printf 'settings:\n  bungeecord: true\n' > "$B/spigot.yml"
cat > "$B/plugins/NetworkLock/config.yml" <<EOF
network:
  server-id: "lobby"
authentication:
  trusted-proxies:
    - id: "velocity-1"
      keys: ["$KV"]
    - id: "waterfall-1"
      keys: ["$KW"]
logging:
  debug: true
EOF

cat > "$V/plugins/networklock/config.yml" <<EOF
network:
  id: "velocity-1"
security:
  key: "$KV"
EOF
cat > "$V/velocity.toml" <<EOF
config-version = "2.7"
bind = "127.0.0.1:35600"
online-mode = false
player-info-forwarding-mode = "$VELOCITY_MODE"
login-ratelimit = 0
[servers]
lobby = "127.0.0.1:35566"
try = ["lobby"]
[forced-hosts]
EOF

cat > "$W/plugins/NetworkLock/config.yml" <<'EOF'
network:
  id: "waterfall-1"
security:
  key: "env:NL_IT_KEY"
EOF
cat > "$W/config.yml" <<'EOF'
online_mode: false
ip_forward: true
connection_throttle: -1
servers:
  lobby:
    address: 127.0.0.1:35566
    restricted: false
listeners:
- host: 127.0.0.1:35601
  priorities:
  - lobby
  query_enabled: false
EOF

echo "== starting servers"
start "$B" "$PAPER_JAR" "Done (" --nogui || exit 1
start "$V" "$VEL_JAR" "Done" || exit 1
export NL_IT_KEY="$KW" # read by Waterfall's NetworkLock through key: "env:NL_IT_KEY"
start "$W" "$WF_JAR" "Listening on" || exit 1

CP="$(native "$WORK/probe")$SEP$(native "$ROOT/networklock-core/build/classes/java/main")"
PROTO=$(java -cp "$CP" Probe 127.0.0.1 35566 x protocol)
probe() { java -Dproto="$PROTO" -cp "$CP" Probe "$@" | tr '\n' ' ' | sed 's/ *$//'; }
U=069a79f4-44e9-4726-a5be-fca90e38aaf5

echo "== direct attacks on the backend (protocol $PROTO)"
check "forged UUID without token"        "REJECTED"  "$(probe 127.0.0.1 35566 Notch forged $U)"
check "junk token"                       "REJECTED"  "$(probe 127.0.0.1 35566 Notch garbage $U)"
check "token for another server"         "REJECTED"  "$(probe 127.0.0.1 35566 Notch token $U velocity-1 "$KV" survival 127.0.0.1)"
check "token claiming the other proxy"   "REJECTED"  "$(probe 127.0.0.1 35566 Notch token $U waterfall-1 "$KV" lobby 127.0.0.1)"
check "unknown proxy id"                 "REJECTED"  "$(probe 127.0.0.1 35566 Notch token $U proxy-9 "$KV" lobby 127.0.0.1)"
R=$(probe 127.0.0.1 35566 Notch token $U velocity-1 "$KV" lobby 127.0.0.1 replay)
check "valid token accepted"             "ACCEPTED"  "$R"
[[ "$R" == *"replay: REJECTED"*"NL-04"* ]] && echo "PASS  replayed token rejected (NL-04)" || { echo "FAIL  replay: $R"; FAILED=1; }

echo "== joins through proxies"
check "join via Velocity (legacy)"       "ACCEPTED"  "$(probe 127.0.0.1 35600 ViaVelocity plain)"
logged "backend verified Velocity join"  "$B/console.log" "Accepted ViaVelocity"
check "join via Waterfall (env key)"     "ACCEPTED"  "$(probe 127.0.0.1 35601 ViaWaterfall plain)"
logged "backend verified Waterfall join" "$B/console.log" "Accepted ViaWaterfall"

echo "== no credential for a connect that will not happen (NL-10)"
java -Djoin=true -Djoinms=15000 -Dproto="$PROTO" -cp "$CP" Probe 127.0.0.1 35601 Stayer plain > "$WORK/stayer.txt" 2>&1 &
STAYER=$!
sleep 6
send "$W" "nlock stats"; sleep 2
S1=$(grep -o "signed connections: [0-9]*" "$W/console.log" | tail -1)
send "$W" "send Stayer lobby"; sleep 3   # Stayer is already on lobby: BungeeCord fires ServerConnectEvent, then connects nowhere
send "$W" "nlock stats"; sleep 2
S2=$(grep -o "signed connections: [0-9]*" "$W/console.log" | tail -1)
wait "$STAYER"
if grep -qi "unknown command\|command not found" <(tail -30 "$W/console.log"); then
    echo "SKIP  Waterfall can no longer download its send module; NL-10 is covered by it/bungee-check.sh on BungeeCord"
elif [ -n "$S1" ] && [ "$S1" = "$S2" ]; then
    echo "PASS  connect to the server the player is on was not signed ($S2)"
else
    echo "FAIL  connect to the current server was signed ($S1 -> $S2)"; FAILED=1
fi

echo "== proxy translates backend rejections"
sed -i 's/server-id: "lobby"/server-id: "renamed"/' "$B/plugins/NetworkLock/config.yml"; send "$B" "nlock reload"; sleep 3
R=$(probe 127.0.0.1 35600 Mismatch plain)
check "mismatched server-id rejected via proxy" "REJECTED (configuration)" "$R"
[[ "$R" == *"color red"*"Connection authentication failed"* && "$R" != *"&c"* ]] \
    && echo "PASS  player saw the proxy's messages.yml text, &c rendered as red" \
    || { echo "FAIL  expected red messages.yml text, got: $R"; FAILED=1; }

echo "== reload safety"
printf 'network: [broken\n' > "$B/plugins/NetworkLock/config.yml"; send "$B" "nlock reload"; sleep 3
logged "invalid reload rejected"         "$B/console.log" "Reload failed"
check "still enforcing after bad reload" "REJECTED"  "$(probe 127.0.0.1 35566 Notch forged $U)"

if [ "$FAILED" = 0 ]; then echo "== ALL PASSED"; else echo "== FAILURES (logs in $WORK/srv)"; fi
exit "$FAILED"
