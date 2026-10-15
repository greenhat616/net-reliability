# Shared setup for the test scripts in tools/; source it, don't run it.

GAME_DIR="${NECESSE_GAME_DIR:-D:/Program Files (x86)/Steam/steamapps/common/Necesse}"
WORKSHOP_DIR="${NECESSE_WORKSHOP_DIR:-$(dirname "$(dirname "$GAME_DIR")")/workshop/content/1169040}"
JAVA_HOME_BUILD="${JAVA_HOME_BUILD:-/g/scoop/apps/temurin17-jdk/current}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="$GAME_DIR/jre/bin/java"
MOD_ID="a632079.netreliability"
# Required dependency, loaded from the Steam Workshop subscription (3558238376).
SETTINGS_LIB_ID="aizsave.modsettingslib"
SETTINGS_LIB_JAR="$(ls "$WORKSHOP_DIR"/3558238376/*.jar 2>/dev/null | head -1)"

build_mod() {
    (cd "$ROOT" && JAVA_HOME="$JAVA_HOME_BUILD" ./gradlew buildModJar -q)
}

# write_modlist <datadir> [server]: only this mod and Mod Settings Lib enabled; every other subscribed workshop mod listed as
# disabled, because unlisted mods would default to enabled.
write_modlist() {
    mkdir -p "$1/mods"
    {
        echo "{"
        if [ "${2:-}" = server ]; then
            # A dedicated server has no Steam Workshop: it loads the lib from <datadir>/mods/ instead.
            cp "$SETTINGS_LIB_JAR" "$1/mods/"
            echo "	{ id = $SETTINGS_LIB_ID, name = Mod Settings Lib, type = FILE_MOD, enabled = true },"
        else
            echo "	{ id = $SETTINGS_LIB_ID, name = Mod Settings Lib, type = STEAM_MOD, enabled = true },"
        fi
        # After the lib: the list order is the load order, and this mod's classes need the lib's.
        echo "	{ id = $MOD_ID, name = Net Reliability, type = DEV_MOD, enabled = true },"
        if [ -d "$WORKSHOP_DIR" ]; then
            for jar in "$WORKSHOP_DIR"/*/*.jar; do
                id=$(unzip -p "$jar" mod.info 2>/dev/null | sed -n 's/^[[:space:]]*id = \(.*\),[[:space:]]*$/\1/p' | head -1)
                [ -n "$id" ] && [ "$id" != "$MOD_ID" ] && [ "$id" != "$SETTINGS_LIB_ID" ] && echo "	{ id = $id, name = $id, type = STEAM_MOD, enabled = false },"
            done
        fi
        echo "}"
    } > "$1/mods/modlist.data"
}

# write_client_settings <datadir>: small muted window that keeps running without focus.
write_client_settings() {
    mkdir -p "$1/cfg"
    cat > "$1/cfg/settings.cfg" <<'EOT'
{
	GENERAL = {
		pauseOnFocusLoss = false,
		savePerformanceOnFocusLoss = false
	},
	SOUND = {
		masterVolume = 0
	},
	GRAPHICS = {
		displayMode = Windowed,
		displaySize = [1280, 720]
	}
}
EOT
}

# write_mod_config <datadir> [key=value ...]: just the overrides; the game fills in the rest on start-up.
write_mod_config() {
    local data="$1"
    shift
    local file="$data/cfg/mods/$MOD_ID.cfg"
    mkdir -p "$(dirname "$file")"
    {
        echo "{"
        echo "	SETTINGS = {"
        local first=1 kv
        for kv in "$@"; do
            [ $first = 1 ] || echo ","
            printf '		%s = %s' "${kv%%=*}" "${kv#*=}"
            first=0
        done
        echo
        echo "	}"
        echo "}"
    } > "$file"
}

# The Steam client needs steam_appid.txt in its working directory.
prepare_client_run_dir() {
    mkdir -p "$1"
    echo 1169040 > "$1/steam_appid.txt"
}

# kill_matching <exe name> <pattern>: kills every <exe name> process whose command line contains <pattern>.
kill_matching() {
    powershell -NoProfile -Command \
        "Get-CimInstance Win32_Process -Filter \"Name='$1'\" | Where-Object { \$_.CommandLine -like '*$2*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" \
        || true
}

WORLD_TEMPLATE="$ROOT/build/nettest/_world/data/saves/worlds/nettest.zip"

# make_world_template: generates the benchmark world once (a dedicated server started and stopped cleanly, so it
# saves), unless it already exists.
make_world_template() {
    [ -s "$WORLD_TEMPLATE" ] && [ "$(wc -c < "$WORLD_TEMPLATE")" -gt 1000 ] && return 0
    local dir="$ROOT/build/nettest/_world"
    rm -rf "$dir"
    mkdir -p "$dir/data"
    write_modlist "$dir/data" server
    echo "generating benchmark world in $dir"
    (
        cd "$GAME_DIR"
        { for _ in $(seq 1 180); do grep -aq "Started server" "$dir/server.log" 2>/dev/null && break; sleep 1; done
          echo stop; sleep 60; }             | "$JAVA" -Xmx4G -jar Server.jar -nogui -datadir "$dir/data" -world nettest -port 14179 -mod "$ROOT/build/jar"             > "$dir/server.log" 2>&1
    ) || true
    if ! [ -s "$WORLD_TEMPLATE" ] || [ "$(wc -c < "$WORLD_TEMPLATE")" -le 1000 ]; then
        echo "could not generate the benchmark world; see $dir/server.log"
        return 1
    fi
}
