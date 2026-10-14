#!/usr/bin/env bash
# The benchmark matrix behind docs/RESULTS.md, at several simulated loss rates (applied on the client after it has
# joined, i.e. to what the server streams to it). Each run is tools/net-test.sh on the same world; the summaries are
# collected in build/nettest/bench-summary.txt.
#
#   tools/net-bench.sh [loss ...]        default: 0 0.1 0.2
#
# Groups (NETBENCH_GROUPS, default "vanilla mod"):
#   vanilla  every feature of this mod off (no NACK, no merge-bug fix, OS socket buffers): the game's own behaviour
#   mod      default settings
#   kcp      default settings + the client joins over KCP (experimental; checked: the client log must show the session)
#
# Exit code: non-zero if the build or any run failed.
set -uo pipefail
source "$(dirname "$0")/common.sh"

LOSSES=("$@")
[ $# -gt 0 ] || LOSSES=(0 0.1 0.2)
read -r -a GROUPS_TO_RUN <<< "${NETBENCH_GROUPS:-vanilla mod}"
SUMMARY="$ROOT/build/nettest/bench-summary.txt"
mkdir -p "$(dirname "$SUMMARY")"
build_mod || { echo "build failed"; exit 1; }
make_world_template || exit 1
: > "$SUMMARY"
VANILLA=(nackEnabled=false fixVanillaMergeBug=false socketBufferKB=0)
FAILED=0

run() {
    local label="$1" loss="$2"
    shift 2
    NETTEST_NO_BUILD=1 NETTEST_LOSS="$loss" "$ROOT/tools/net-test.sh" "$label" "$@" | tee -a "$SUMMARY"
    if [ "${PIPESTATUS[0]}" != 0 ]; then
        echo "== $label FAILED" | tee -a "$SUMMARY"
        FAILED=1
    fi
}

for loss in "${LOSSES[@]}"; do
    for group in "${GROUPS_TO_RUN[@]}"; do
        case "$group" in
            vanilla) run "vanilla-$loss" "$loss" --server "${VANILLA[@]}" --client "${VANILLA[@]}" ;;
            mod) run "mod-$loss" "$loss" ;;
            kcp)
                run "kcp-$loss" "$loss" --client kcpEnabled=true
                if ! grep -aq "KCP (experimental) on for" "$ROOT/build/nettest/kcp-$loss/client.log" 2>/dev/null; then
                    echo "== kcp-$loss FAILED: the client did not use KCP" | tee -a "$SUMMARY"
                    FAILED=1
                fi
                ;;
            *) echo "unknown group: $group"; FAILED=1 ;;
        esac
    done
done
echo "summary: $SUMMARY"
exit $FAILED
