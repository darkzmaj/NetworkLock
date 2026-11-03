#!/usr/bin/env bash
# Runs the direct-attack checks against a game server jar (Paper, Purpur, Pufferfish, Folia,
# Spigot, CraftBukkit, SpongeVanilla) with NetworkLock.jar installed. No proxy needed: tokens are
# minted locally. Jars with "sponge" in the name get the Sponge layout (mods/, config/networklock/).
#
#   it/backend-check.sh path/to/server.jar [expect]
#
# expect = "enforce" (default): forged logins rejected, valid token accepted once.
# expect = "deny-all": every login rejected (servers without the Paper profile API, e.g. Spigot).
# Needs bash, openssl, Java on PATH and port 35570 free. Run from the repository root.
set -uo pipefail

SERVER_JAR=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
EXPECT=${2:-enforce}
ROOT=$(pwd)
WORK="$ROOT/it/work/backend-check"
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
FAILED=0

S="$WORK/srv"
SPONGE=0; case "$(basename "$SERVER_JAR")" in *sponge*) SPONGE=1;; esac
if [ "$SPONGE" = 1 ]; then PLUGINS="$S/mods"; DATA="$S/config/networklock"; else PLUGINS="$S/plugins"; DATA="$S/plugins/NetworkLock"; fi
rm -rf "$WORK" && mkdir -p "$PLUGINS" "$DATA" "$WORK/probe"
javac -d "$WORK/probe" -cp "$ROOT/networklock-core/build/classes/java/main" "$ROOT/it/Probe.java" || exit 2
KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')
cp "$ROOT/build/libs/NetworkLock.jar" "$PLUGINS/"
echo "eula=true" > "$S/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=35570\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nwhite-list=false\n' > "$S/server.properties"
if [ "$SPONGE" = 1 ]; then
    mkdir -p "$S/config/sponge"
    printf 'modules {\n    ip-forwarding=true\n}\nip-forwarding {\n    mode=LEGACY\n}\n' > "$S/config/sponge/sponge.conf"
else
    printf 'settings:\n  bungeecord: true\n' > "$S/spigot.yml"
fi
printf 'network:\n  server-id: "lobby"\nauthentication:\n  trusted-proxies:\n    - id: "proxy-1"\n      keys: ["%s"]\nlogging:\n  debug: true\n' "$KEY" > "$DATA/config.yml"

: > "$S/cmd.txt"
(cd "$S" && tail -f cmd.txt | java -Xmx1G -jar "$SERVER_JAR" --nogui > console.log 2>&1) < /dev/null > /dev/null 2>&1 &
PID=$!
cleanup() { echo stop >> "$S/cmd.txt"; sleep 10; echo >> "$S/cmd.txt"; sleep 1; kill "$PID" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 180); do grep -q "Done (" "$S/console.log" 2>/dev/null && break; sleep 1; done
grep -q "Done (" "$S/console.log" || { echo "server did not start"; tail -20 "$S/console.log"; exit 1; }

CP="$(native "$WORK/probe")$SEP$(native "$ROOT/networklock-core/build/classes/java/main")"
PROTO=$(java -cp "$CP" Probe 127.0.0.1 35570 x protocol)
probe() { java -Dproto="$PROTO" -cp "$CP" Probe 127.0.0.1 35570 "$@" | tr '\n' ' ' | sed 's/ *$//'; }
check() { if [[ "$3" == "$2"* ]]; then echo "PASS  $1"; else echo "FAIL  $1: expected '$2', got '$3'"; FAILED=1; fi; }
U=069a79f4-44e9-4726-a5be-fca90e38aaf5

echo "== $(grep -m1 -oE 'This server is running [^(]*|Starting minecraft server version [^ ]*' "$S/console.log") (protocol $PROTO), expect $EXPECT"
check "forged UUID without token" "REJECTED" "$(probe Notch forged $U)"
check "junk token"                "REJECTED" "$(probe Notch garbage $U)"
check "token for another server"  "REJECTED" "$(probe Notch token $U proxy-1 "$KEY" survival 127.0.0.1)"
R=$(probe Notch token $U proxy-1 "$KEY" lobby 127.0.0.1 replay)
if [ "$EXPECT" = "deny-all" ]; then
    check "valid token still refused (no Paper API)" "REJECTED" "$R"
    grep -q "lacks the Paper profile API" "$S/console.log" && echo "PASS  lockdown reason logged" \
        || { echo "FAIL  lockdown reason not logged"; FAILED=1; }
else
    check "valid token accepted" "ACCEPTED" "$R"
    [[ "$R" == *"replay: REJECTED"*"NL-04"* ]] && echo "PASS  replay rejected (NL-04)" || { echo "FAIL  replay: $R"; FAILED=1; }
    if [ "$SPONGE" = 1 ]; then
        echo "SKIP  credential strip (Sponge profiles are read-only; a used credential cannot be replayed)"
    else
        grep -q "still present" "$S/console.log" && { echo "FAIL  credential reached the live profile"; FAILED=1; } \
            || echo "PASS  credential stripped from profile"
    fi
fi
echo "nlock doctor" >> "$S/cmd.txt"; sleep 3
grep -q "OK   proxy forwarding" "$S/console.log" && echo "PASS  doctor ran" || { echo "FAIL  doctor output missing"; FAILED=1; }
grep -qE "Exception|Could not load" <(grep -i networklock "$S/console.log") && { echo "FAIL  NetworkLock errors in console"; FAILED=1; }

[ "$FAILED" = 0 ] && echo "== ALL PASSED" || echo "== FAILURES (log: $S/console.log)"
exit "$FAILED"
