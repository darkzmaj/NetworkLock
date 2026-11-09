#!/usr/bin/env bash
# BungeeCord checks for NL-10: a connect that never happens must not leave a signature behind.
#   1. "send <player> <server they are on>": BungeeCord fires ServerConnectEvent, then connects nowhere;
#      NetworkLock must not sign it (the signed-connections counter must not move).
#   2. "send <player> dead": a server entry where nothing listens; the connect fails without any event,
#      so NetworkLock's safety net must remove the unused signature shortly after the connect timeout.
# Uses it/work/jars/BungeeCord.jar (bundles its command modules) and Paper 1.21.11; ports 35580-35589.
#
#   it/bungee-check.sh
set -uo pipefail

ROOT=$(pwd)
W="$ROOT/it/work/bungee-check"
PAPER_JAR="$ROOT/it/work/jars/paper-1.21.11-132.jar"
BUNGEE_JAR="$ROOT/it/work/jars/BungeeCord.jar"
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
CP="$(native "$W/cls")$SEP$(native "$ROOT/networklock-core/build/classes/java/main")"
FAILED=0
KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')

rm -rf "$W" && mkdir -p "$W/cls" "$W/paper/plugins/NetworkLock" "$W/bungee/plugins/NetworkLock"
javac -d "$W/cls" -cp "$ROOT/networklock-core/build/classes/java/main" "$ROOT/it/Probe.java" || exit 2
cp "$ROOT/build/libs/NetworkLock.jar" "$W/paper/plugins/"; cp "$ROOT/build/libs/NetworkLock.jar" "$W/bungee/plugins/"
echo "eula=true" > "$W/paper/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=35580\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n' > "$W/paper/server.properties"
printf 'settings:\n  bungeecord: true\n' > "$W/paper/spigot.yml"
mkdir -p "$W/paper/config"; printf 'proxies:\n  bungee-cord:\n    online-mode: false\n' > "$W/paper/config/paper-global.yml"
printf 'network:\n  server-id: "lobby"\nauthentication:\n  trusted-proxies:\n    - id: "proxy-1"\n      keys: ["%s"]\nupdates:\n  check: false\nmetrics:\n  enabled: false\n' "$KEY" > "$W/paper/plugins/NetworkLock/config.yml"
printf 'network:\n  id: "proxy-1"\nsecurity:\n  key: "%s"\nlogging:\n  debug: true\nupdates:\n  check: false\nmetrics:\n  enabled: false\n' "$KEY" > "$W/bungee/plugins/NetworkLock/config.yml"
printf 'online_mode: false\nip_forward: true\nconnection_throttle: -1\nservers:\n  lobby:\n    address: 127.0.0.1:35580\n    restricted: false\n  dead:\n    address: 127.0.0.1:35589\n    restricted: false\nlisteners:\n- host: 127.0.0.1:35581\n  priorities:\n  - lobby\n  query_enabled: false\n' > "$W/bungee/config.yml"

PIDS=()
start() { # dir jar ready
    : > "$1/cmd.txt"
    (cd "$1" && tail -f cmd.txt | java -Xmx1G -jar "$2" nogui > console.log 2>&1) < /dev/null > /dev/null 2>&1 &
    PIDS+=($!)
    for _ in $(seq 1 180); do grep -q "$3" "$1/console.log" 2>/dev/null && return 0; sleep 1; done
    echo "start failed in $1"; tail -5 "$1/console.log"; return 1
}
send() { echo "$2" >> "$1/cmd.txt"; }
cleanup() { send "$W/paper" stop; send "$W/bungee" end; sleep 10; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; }
trap cleanup EXIT
signed() { send "$W/bungee" "nlock stats"; sleep 2; grep -o "signed connections: [0-9]*" "$W/bungee/console.log" | tail -1 | grep -o "[0-9]*$"; }

start "$W/paper" "$PAPER_JAR" "Done (" || exit 1
start "$W/bungee" "$BUNGEE_JAR" "Listening on" || exit 1
PROTO=$(java -cp "$CP" Probe 127.0.0.1 35580 x protocol)

java -Djoin=true -Djoinms=40000 -Dproto="$PROTO" -cp "$CP" Probe 127.0.0.1 35581 Stayer plain > "$W/stayer.txt" 2>&1 &
STAYER=$!
sleep 8
grep -q "Stayer joined the game" "$W/paper/console.log" && echo "PASS  Stayer joined lobby through BungeeCord" || { echo "FAIL  Stayer did not join"; FAILED=1; }

S1=$(signed)
send "$W/bungee" "send Stayer lobby"; sleep 4
S2=$(signed)
if grep -qi "unknown command" "$W/bungee/console.log"; then
    echo "FAIL  BungeeCord's send command is missing"; FAILED=1
elif [ -n "$S1" ] && [ "$S1" = "$S2" ]; then
    echo "PASS  connect to the server the player is already on was not signed (signed connections stayed $S2)"
else
    echo "FAIL  connect to the current server was signed ($S1 -> $S2)"; FAILED=1
fi

send "$W/bungee" "send Stayer dead"; sleep 2
S3=$(signed)
[ -n "$S3" ] && [ "$S3" -gt "$S2" ] && echo "PASS  connect to the dead server was signed ($S2 -> $S3)" || { echo "FAIL  dead-server connect not signed ($S2 -> $S3)"; FAILED=1; }
for _ in $(seq 1 15); do grep -q "Removed an unused signature from Stayer" "$W/bungee/console.log" && break; sleep 1; done
grep -q "Removed an unused signature from Stayer" "$W/bungee/console.log" \
    && echo "PASS  unused signature removed by the safety net after the failed connect" \
    || { echo "FAIL  unused signature was not removed"; FAILED=1; }

wait "$STAYER"
grep -qE "Exception|SEVERE" <(grep -i networklock "$W/bungee/console.log") && { echo "FAIL  NetworkLock errors on BungeeCord"; FAILED=1; }
[ "$FAILED" = 0 ] && echo "== ALL PASSED" || echo "== FAILURES (logs in $W)"
exit "$FAILED"
