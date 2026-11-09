#!/usr/bin/env bash
# Multi-hour stability test on a small local network:
#   proxy-1 Velocity  127.0.0.1:35720 -> lobby    (Paper  127.0.0.1:35710)
#   proxy-2 Waterfall 127.0.0.1:35721 -> survival (Folia  127.0.0.1:35711)
# Both backends trust both proxies, each with its own key. Running the whole time:
#   valid logins 5/s through each proxy, forged direct logins 2/s to each backend (kept below what
#   Windows can sustain in TIME_WAIT ports for hours),
#   a replay and a wrong-server attempt against each backend every minute,
#   a reconnect storm (300 logins, 10 names, 100/s) every 10 minutes,
#   /nlock reload on all four every 5 minutes, a live key rotation of proxy-1 at 1/3 of the run,
#   a restart of the survival backend every 45 minutes.
# Every 30 s: CPU, heap, threads and handles of each JVM; every 10 min a forced GC and the heap after it,
# plus each backend's replay memory. Results in it/work/soak/.
#
#   HOURS=3 it/soak.sh      # needs ports 35710-35721, the jars below, Java 21 on PATH
set -uo pipefail

HOURS=${HOURS:-3}
ROOT=$(pwd)
S="$ROOT/it/work/soak"
JARS="$ROOT/it/work/jars"
PAPER_JAR="$JARS/paper-1.21.11-132.jar"; FOLIA_JAR="$JARS/folia-1.21.11-14.jar"
VEL_JAR="$JARS/velocity-3.5.1-615.jar"; WF_JAR="$JARS/waterfall-1.21-615.jar"
NL="$ROOT/build/libs/NetworkLock.jar"
JAVA=$(command -v java); JD=$(dirname "$JAVA")
SEP=":"; native() { echo "$1"; }
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; native() { cygpath -w "$1"; };; esac
CP="$(native "$S/cls")$SEP$(native "$ROOT/networklock-core/build/classes/java/main")"
DURATION=${DURATION:-$((HOURS * 3600))}   # seconds; overrides HOURS (short smoke runs)
RESTART_EVERY=${RESTART_EVERY:-2700}
END=$(( $(date +%s) + DURATION ))
key() { openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'; }
K1=$(key); K1B=$(key); K2=$(key)
ev() { echo "$(date +%s) $*" | tee -a "$S/events.log"; }

rm -rf "$S" && mkdir -p "$S/cls" "$S/raw"
echo $$ > "$S/soak.pid"   # to stop the whole run: kill this process tree
javac -d "$S/cls" -cp "$ROOT/networklock-core/build/classes/java/main" "$ROOT/it/Load.java" "$ROOT/it/Probe.java" || exit 2

backend() { # dir port id
    local d="$S/$1"; mkdir -p "$d/plugins/NetworkLock"; cp "$NL" "$d/plugins/"
    echo "eula=true" > "$d/eula.txt"
    printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=%s\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-players=5000\nspawn-protection=0\n' "$2" > "$d/server.properties"
    printf 'settings:\n  bungeecord: true\n' > "$d/spigot.yml"
    printf 'settings:\n  connection-throttle: -1\n' > "$d/bukkit.yml"
    mkdir -p "$d/config"; printf 'proxies:\n  bungee-cord:\n    online-mode: false\n' > "$d/config/paper-global.yml"
    backend_config "$1" "$3" "[\"$K1\"]"
}
backend_config() { # dir id proxy-1-keys
    printf 'network:\n  server-id: "%s"\nauthentication:\n  trusted-proxies:\n    - id: "proxy-1"\n      keys: %s\n    - id: "proxy-2"\n      keys: ["%s"]\nupdates:\n  check: false\nmetrics:\n  enabled: false\n' \
        "$2" "$3" "$K1B" > "$S/$1/plugins/NetworkLock/config.yml"
}
proxy_config() { # file id key
    printf 'network:\n  id: "%s"\nsecurity:\n  key: "%s"\nprotection:\n  connection-limit:\n    enabled: true\n    max-attempts: 10000\n    window-seconds: 1\nupdates:\n  check: false\nmetrics:\n  enabled: false\n' "$2" "$3" > "$1"
}
backend lobby 35710 lobby
backend survival 35711 survival
mkdir -p "$S/velocity/plugins/networklock" "$S/waterfall/plugins/NetworkLock"
cp "$NL" "$S/velocity/plugins/"; cp "$NL" "$S/waterfall/plugins/"
proxy_config "$S/velocity/plugins/networklock/config.yml" proxy-1 "$K1"
proxy_config "$S/waterfall/plugins/NetworkLock/config.yml" proxy-2 "$K1B"
printf 'config-version = "2.7"\nbind = "127.0.0.1:35720"\nonline-mode = false\nplayer-info-forwarding-mode = "legacy"\n[servers]\nlobby = "127.0.0.1:35710"\nsurvival = "127.0.0.1:35711"\ntry = ["lobby"]\n[forced-hosts]\n[advanced]\nlogin-ratelimit = 0\n' > "$S/velocity/velocity.toml"
printf 'online_mode: false\nip_forward: true\nconnection_throttle: -1\nservers:\n  survival:\n    address: 127.0.0.1:35711\n    restricted: false\n  lobby:\n    address: 127.0.0.1:35710\n    restricted: false\nlisteners:\n- host: 127.0.0.1:35721\n  priorities:\n  - survival\n  query_enabled: false\n' > "$S/waterfall/config.yml"

start() { # name jar xmx ready
    : > "$S/$1/cmd.txt"
    local seen; seen=$(grep -c "$4" "$S/$1/console.log" 2>/dev/null)
    # -Dnlsoak tags the process so monitoring never picks up another server running the same jar
    (cd "$S/$1" && tail -f cmd.txt | "$JAVA" -Dnlsoak="$1" -Xms$3 -Xmx$3 -jar "$2" nogui >> console.log 2>&1) < /dev/null > /dev/null 2>&1 &
    # wait for a NEW ready line: the log is appended across restarts
    for _ in $(seq 1 240); do [ "$(grep -c "$4" "$S/$1/console.log" 2>/dev/null)" -gt "${seen:-0}" ] && return 0; sleep 1; done
    ev "START FAILED $1"; return 1
}
send() { echo "$2" >> "$S/$1/cmd.txt"; }
pid_of() { "$JD/jps" -v | grep -F -- "-Dnlsoak=$1 " | awk '{print $1}' | head -1; }
stop_backend() { send "$1" stop; for _ in $(seq 1 60); do [ -z "$(pid_of "$1")" ] && break; sleep 1; done; send "$1" ""; sleep 1; }

start lobby "$PAPER_JAR" 1G "Done (" || exit 1
start survival "$FOLIA_JAR" 1G "Done (" || exit 1
start velocity "$VEL_JAR" 512M "Done" || exit 1
start waterfall "$WF_JAR" 512M "Listening on" || exit 1
PROTO=$(java -cp "$CP" Probe 127.0.0.1 35710 x protocol)
# pre-flight: one valid login through each proxy must work, or hours of traffic would test nothing
for x in "35720 velocity" "35721 waterfall"; do
    set -- $x
    r=$(java -Dproto=$PROTO -cp "$CP" Probe 127.0.0.1 "$1" Preflight$1 plain | tr '\n' ' ')
    if [[ "$r" != ACCEPTED* ]]; then
        ev "PRE-FLIGHT FAILED via $2: $r"; grep -iE "error|lockdown|problem" "$S/$2/console.log" | tail -5
        for n in lobby survival; do send $n stop; done; send velocity shutdown; send waterfall end; sleep 20; exit 1
    fi
done
ev "started, protocol $PROTO, pre-flight ok, until $(date -d @$END 2>/dev/null || echo $END)"

running() { [ "$(date +%s)" -lt "$END" ]; }
loop_load() { # tag host port mode count rate [names]
    local i=0
    while running; do
        i=$((i + 1))
        local t0; t0=$(date +%s)
        local line; line=$(java -Dproto=$PROTO -cp "$CP" Load "$2" "$3" "$4" "$5" "$6" "$1$i" "$(native "$S/raw/$1-$i.csv")" ${7:-} 2>&1 | tr '\n' ' ')
        echo "$t0 $(date +%s) $line" >> "$S/$1.summary"   # start, end, Load summary
    done
}
loop_load v 127.0.0.1 35720 rate 300 5 &
loop_load w 127.0.0.1 35721 rate 300 5 &
loop_load fl 127.0.0.1 35710 forged 120 2 &
loop_load fs 127.0.0.1 35711 forged 120 2 &

probes() { # every minute: replay and wrong-server against both backends (the right proxy-1 key at that moment)
    while running; do
        local k; k=$(cat "$S/k1.current" 2>/dev/null || echo "$K1")
        for b in "lobby 35710" "survival 35711"; do
            set -- $b
            local u; u=$(printf '%04x%04x-%04x-4%03x-a%03x-%04x%04x%04x' $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((RANDOM % 4096)) $RANDOM $RANDOM $RANDOM)
            local r w
            r=$(java -Dproto=$PROTO -cp "$CP" Probe 127.0.0.1 "$2" P$RANDOM token "$u" proxy-1 "$k" "$1" 127.0.0.1 replay | tr '\n' ' ')
            w=$(java -Dproto=$PROTO -cp "$CP" Probe 127.0.0.1 "$2" Q$RANDOM token "$u" proxy-1 "$k" wrongserver 127.0.0.1 | tr '\n' ' ')
            echo "$(date +%s) $1 replay=[$r] wrongserver=[$w]" >> "$S/probes.log"
        done
        sleep 60
    done
}
probes &

monitor() {
    echo "t,name,pid,cpu_ms,heap_kb,threads,handles" > "$S/monitor.csv"
    echo "t,name,heap_after_gc_kb" > "$S/heap-after-gc.csv"
    local n=0
    while running; do
        for x in "lobby paper-1.21.11-132.jar" "survival folia-1.21.11-14.jar" "velocity velocity-3.5.1-615.jar" "waterfall waterfall-1.21-615.jar"; do
            set -- $x
            local p; p=$(pid_of "$1"); [ -z "$p" ] && continue
            local cpu h th hd
            cpu=$(powershell -NoProfile -Command "\$p=Get-Process -Id $p; '{0},{1}' -f [int]\$p.TotalProcessorTime.TotalMilliseconds,\$p.HandleCount" 2>/dev/null | tr -d '\r')
            h=$("$JD/jstat" -gc "$p" 2>/dev/null | tail -1 | tr ',' '.' | awk '{printf "%d", $3+$4+$6+$8}')
            th=$("$JD/jcmd" "$p" Thread.print 2>/dev/null | grep -c '^"')
            echo "$(date +%s),$1,$p,${cpu%%,*},$h,$th,${cpu##*,}" >> "$S/monitor.csv"
            if [ $((n % 20)) -eq 0 ]; then
                "$JD/jcmd" "$p" GC.run > /dev/null 2>&1; sleep 2
                echo "$(date +%s),$1,$("$JD/jstat" -gc "$p" 2>/dev/null | tail -1 | tr ',' '.' | awk '{printf "%d", $3+$4+$6+$8}')" >> "$S/heap-after-gc.csv"
            fi
        done
        [ $((n % 20)) -eq 0 ] && { send lobby "nlock stats"; send survival "nlock stats"; }
        n=$((n + 1)); sleep 30
    done
}
monitor &

T0=$(date +%s); ROTATED=0; next_reload=$((T0 + 300)); next_storm=$((T0 + 600)); next_restart=$((T0 + RESTART_EVERY))
echo "$K1" > "$S/k1.current"
while running; do
    now=$(date +%s)
    if [ "$now" -ge "$next_reload" ]; then
        for x in lobby survival velocity waterfall; do send $x "nlock reload"; done
        ev "reload all"; next_reload=$((now + 300))
    fi
    if [ "$now" -ge "$next_storm" ]; then
        java -Dproto=$PROTO -cp "$CP" Load 127.0.0.1 35720 rate 300 100 storm$now "$(native "$S/raw/storm-$now.csv")" 10 >> "$S/storm.summary" 2>&1
        ev "reconnect storm"; next_storm=$((now + 600))
    fi
    if [ "$ROTATED" = 0 ] && [ "$now" -ge $((T0 + DURATION / 3)) ]; then
        # rotation without downtime: backends accept old+new, proxy switches, then old is removed
        backend_config lobby lobby "[\"$K1\", \"$K2\"]"; backend_config survival survival "[\"$K1\", \"$K2\"]"
        send lobby "nlock reload"; send survival "nlock reload"; sleep 5
        proxy_config "$S/velocity/plugins/networklock/config.yml" proxy-1 "$K2"; send velocity "nlock reload"; echo "$K2" > "$S/k1.current"; sleep 40
        backend_config lobby lobby "[\"$K2\"]"; backend_config survival survival "[\"$K2\"]"
        before=$(grep -c "Configuration reloaded" "$S/lobby/console.log")
        send lobby "nlock reload"; send survival "nlock reload"
        for _ in $(seq 1 30); do [ "$(grep -c "Configuration reloaded" "$S/lobby/console.log")" -gt "$before" ] && break; sleep 1; done
        ev "key rotation proxy-1 done (reload confirmed)"; ROTATED=1
        # the old key must now be refused
        echo "$(date +%s) oldkey reload-confirmed=[$(java -Dproto=$PROTO -cp "$CP" Probe 127.0.0.1 35710 OldKey token 3fa85f64-5717-4562-b3fc-2c963f66afa6 proxy-1 "$K1" lobby 127.0.0.1 | tr '\n' ' ')]" >> "$S/probes.log"
    fi
    if [ "$now" -ge "$next_restart" ]; then
        ev "restart survival: stopping"; stop_backend survival
        start survival "$FOLIA_JAR" 1G "Done (" && ev "restart survival: up"; next_restart=$(( $(date +%s) + RESTART_EVERY ))
    fi
    sleep 5
done

ev "time up, stopping"
sleep 70
for x in lobby survival; do send $x stop; done; send velocity shutdown; send waterfall end
sleep 25; for x in lobby survival velocity waterfall; do send $x ""; done
ev "done"
