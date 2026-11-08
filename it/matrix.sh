#!/usr/bin/env bash
# Runs it/backend-check.sh against every server jar listed below, each on its own Java, and prints
# one result line per server. Jar and JDK locations default to it/work/ and can be overridden with the variables below.
#
#   it/matrix.sh [filter]     # optional substring to run only matching entries
set -uo pipefail

J8=${J8:-it/work/jdk8/bin/java}    # set J8/J17/J25 to your JDKs
J17=${J17:-it/work/jdk17/bin/java}
J21=${J21:-java}
J25=${J25:-it/work/jdk25/bin/java}
JARS=${JARS:-it/work/jars}
BT=${BT:-it/work/buildtools} # Spigot/CraftBukkit jars from BuildTools
FILTER=${1:-}

# name | server jar | java [| expect: enforce (default) or deny-all]
ENTRIES="
Paper 1.8.8|$JARS/paper-1.8.8-445.jar|$J8
Spigot 1.8.8|$BT/spigot-1.8.8.jar|$J8
PandaSpigot 1.8.8|$JARS/pandaspigot-226.jar|$J8
Paper 1.12.2|$JARS/paper-1.12.2-1620.jar|$J8
Paper 1.16.5|$JARS/paper-1.16.5-794.jar|$J8
Paper 1.17.1|$JARS/paper-1.17.1-411.jar|$J17
Paper 1.18.2|$JARS/paper-1.18.2-388.jar|$J17
Paper 1.19.4|$JARS/paper-1.19.4-550.jar|$J17
Paper 1.20.4|$JARS/paper-1.20.4-499.jar|$J17
Kaiiju 1.20.1 (Folia fork)|$JARS/kaiiju-1.20.1-240.jar|$J17
Paper 1.20.6|$JARS/paper-1.20.6-151.jar|$J21
Paper 1.21.11|$JARS/paper-1.21.11-132.jar|$J21
Folia 1.21.11|$JARS/folia-1.21.11-14.jar|$J21
Purpur 1.21.11|$JARS/purpur-1.21.11-2568.jar|$J21
Purpur 1.16.5|$JARS/purpur-1.16.5-1171.jar|$J8
Gale 26.2|$JARS/gale-26.2-R2.3.0.jar|$J25
Pufferfish 1.21.10|$JARS/pufferfish-1.21.10-39.jar|$J21
Spigot 1.21.11|$BT/spigot-1.21.11.jar|$J21
CraftBukkit 1.21.11|$BT/craftbukkit-1.21.11.jar|$J21|deny-all
SpongeVanilla 1.21.11|$JARS/spongevanilla-1.21.11-18.0.0-RC2761-universal.jar|$J21
Paper 26.3|$JARS/paper-26.3-236.jar|$J25
Leaf 26.3|$JARS/leaf-26.3-22.jar|$J25
SpongeVanilla 26.3|$JARS/spongevanilla-26.3-21.0.0-RC2764-universal.jar|$J25
"

mkdir -p it/work/matrix
echo "$ENTRIES" | while IFS='|' read -r name jar java expect; do
    [ -z "$name" ] && continue
    [ -n "$FILTER" ] && [[ "$name" != *"$FILTER"* ]] && continue
    log="it/work/matrix/$(echo "$name" | tr -c 'A-Za-z0-9.' '_').log"
    if [ ! -f "$jar" ]; then echo "SKIP  $name (jar not found: $jar)"; continue; fi
    SERVER_JAVA="$java" timeout 600 bash it/backend-check.sh "$jar" "${expect:-enforce}" > "$log" 2>&1
    code=$?
    pass=$(grep -c '^PASS' "$log"); fail=$(grep -c '^FAIL' "$log")
    if [ "$code" = 0 ]; then echo "PASS  $name ($pass checks)"; else echo "FAIL  $name ($pass passed, $fail failed, exit $code) - $log"; fi
done
