#!/usr/bin/env bash
# One region-streaming benchmark run: a dedicated server (Server.jar) and a Steam client on this machine, connected
# over 127.0.0.1 (direct UDP, not Steam P2P). The server teleports the client's player around (netreliability.test.NetTest)
# and the client reports how long each teleport took to load.
#
#   tools/net-test.sh <label> [--server key=value ...] [--client key=value ...]
#
# key=value pairs go into that side's cfg/mods/a632079.netreliability.cfg, e.g.
#   tools/net-test.sh vanilla-10 --client simulatedLoss=0.1 nackEnabled=false --server nackEnabled=false
# Loss is simulated on the receiving side, so --client simulatedLoss=X drops X of what the server sends.
#
# The world comes from build/nettest/_world (made once by make_world_template, see common.sh), so every run starts
# on the same terrain and spawn point; delete that directory to regenerate it.
#
# Exit code: 0 only if the server finished the whole script ("test DONE").
#
# Env: NETTEST_LOSS = simulated loss the client switches on after joining (see NetTest.LOSS_AFTER_JOIN),
# NETTEST_POSITIONS (5), NETTEST_ROUNDS (2), NETTEST_PORT (14180), NETTEST_TIMEOUT seconds (900),
# NETTEST_NO_BUILD=1 to skip the build. Output in build/nettest/<label>/: server.log, client.log and each side's
# netreliability-stats.csv; the summary lines are printed at the end.
set -euo pipefail
source "$(dirname "$0")/common.sh"

LABEL="${1:?usage: net-test.sh <label> [--server k=v ...] [--client k=v ...]}"
shift
# The label names a directory that gets deleted: one plain path component only.
if ! [[ "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "label must match [A-Za-z0-9][A-Za-z0-9._-]*: $LABEL"
    exit 2
fi
SERVER_KV=(statsLogSeconds=5)
CLIENT_KV=(statsLogSeconds=5)
side=""
for a in "$@"; do
    case "$a" in
        --server) side=server ;;
        --client) side=client ;;
        *=*) if [ "$side" = server ]; then SERVER_KV+=("$a"); elif [ "$side" = client ]; then CLIENT_KV+=("$a"); else echo "key=value before --server/--client: $a"; exit 2; fi ;;
        *) echo "unknown argument: $a"; exit 2 ;;
    esac
done

PORT="${NETTEST_PORT:-14180}"
TIMEOUT="${NETTEST_TIMEOUT:-900}"
PROPS=(-Dnetreliability.test=1 -Dnetreliability.test.positions="${NETTEST_POSITIONS:-5}" -Dnetreliability.test.rounds="${NETTEST_ROUNDS:-2}"
    -Dnetreliability.test.loss="${NETTEST_LOSS:--1}")
OUT="$ROOT/build/nettest/$LABEL"
SERVER_DATA="$OUT/server/data"
CLIENT_DATA="$OUT/client/data"

[ "${NETTEST_NO_BUILD:-0}" = 1 ] || build_mod
make_world_template
case "$(cd "$ROOT/build/nettest" && pwd)/$LABEL" in
    "$ROOT/build/nettest/"?*) rm -rf "$OUT" ;;
    *) echo "refusing to delete $OUT"; exit 2 ;;
esac
mkdir -p "$SERVER_DATA/saves/worlds"
cp "$WORLD_TEMPLATE" "$SERVER_DATA/saves/worlds/nettest.zip"
write_modlist "$SERVER_DATA"
write_modlist "$CLIENT_DATA"
write_mod_config "$SERVER_DATA" "${SERVER_KV[@]}"
write_mod_config "$CLIENT_DATA" "${CLIENT_KV[@]}"
write_client_settings "$CLIENT_DATA"
prepare_client_run_dir "$OUT/client/run"

cleanup() {
    kill_matching java.exe "nettest/$LABEL/"
    kill_matching sleep.exe "100001"
}
trap cleanup EXIT

# Server.jar expects to run from the game directory. Its console reads stdin, so give it a pipe that stays open.
(
    cd "$GAME_DIR"
    sleep 100001 | "$JAVA" -Xmx4G "${PROPS[@]}" \
        -jar Server.jar -nogui -datadir "$SERVER_DATA" -world nettest -port "$PORT" -mod "$ROOT/build/jar" \
        > "$OUT/server.log" 2>&1
) &

for _ in $(seq 1 120); do
    grep -aq "Started server" "$OUT/server.log" 2>/dev/null && break
    sleep 1
done
grep -aq "Started server" "$OUT/server.log" || { echo "server did not start; see $OUT/server.log"; exit 1; }

(
    cd "$OUT/client/run"
    "$JAVA" -Xmx4G "${PROPS[@]}" -cp "$GAME_DIR/Necesse.jar" StartSteamClient \
        -datadir "$CLIENT_DATA" -connect "127.0.0.1:$PORT" -character NetTester -mod "$ROOT/build/jar" \
        > "$OUT/client.log" 2>&1
) &

start=$(date +%s)
STATUS=0
while ! grep -aq "\[NetReliability\] test DONE" "$OUT/server.log" 2>/dev/null; do
    sleep 2
    if [ $(( $(date +%s) - start )) -gt "$TIMEOUT" ]; then echo "FAILED: timed out after $TIMEOUT s"; STATUS=1; break; fi
done
sleep 6 # one more stats row on each side

echo "== $LABEL  server: ${SERVER_KV[*]}  client: ${CLIENT_KV[*]}"
grep -a "\[NetReliability\] test \(client joined\|round\|server\)" "$OUT/server.log" | sed 's/\x1b\[[0-9;]*m//g; s/^.*\[NetReliability\] test //'
grep -a "\[NetReliability\] test client " "$OUT/client.log" | sed 's/\x1b\[[0-9;]*m//g; s/^.*\[NetReliability\] test client //' | tail -4
tail -1 "$CLIENT_DATA/netreliability-stats.csv" 2>/dev/null | sed 's/^/client csv: /' || true
exit $STATUS
