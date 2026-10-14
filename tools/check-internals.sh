#!/usr/bin/env bash
# Checks every game member this mod depends on (docs/GAME-INTERNALS.md) against the installed Necesse.jar and
# Server.jar, using javap. Run it first after a game update; any MISSING line is something to fix before testing.
#
#   tools/check-internals.sh          exit code 0 = all present
set -uo pipefail
source "$(dirname "$0")/common.sh"
JAVAP="${JAVAP:-$JAVA_HOME_BUILD/bin/javap}"
FAIL=0

# expect <jar> <class> <grep -F pattern in "javap -p -c" output> <what it is for>
expect() {
    local jar="$1" cls="$2" pattern="$3" why="$4"
    local dump
    dump=$("$JAVAP" -p -c -cp "$GAME_DIR/$jar" "$cls" 2>/dev/null)
    if [ -z "$dump" ]; then
        echo "MISSING  $jar $cls (class)  -- $why"
        FAIL=1
    elif grep -qF -- "$pattern" <<<"$dump"; then
        echo "ok       $jar $cls: $pattern"
    else
        echo "MISSING  $jar $cls: $pattern  -- $why"
        FAIL=1
    fi
}

for JAR in Necesse.jar Server.jar; do
    NP=necesse.engine.network.NetworkPacket
    expect $JAR $NP "private final int packetIndex;" "GameInternals.fragmentIndex"
    expect $JAR $NP "private final necesse.engine.network.NetworkPacket\$PacketData[] packets;" "GameInternals.fragmentCount/fragmentDatagrams"
    expect $JAR "$NP\$PacketData" "private final byte[] packet;" "GameInternals.fragmentDatagrams"
    expect $JAR $NP "private int complete;" "GameInternals.recountFragments (vanilla merge bug fix)"
    expect $JAR $NP "public necesse.engine.network.NetworkPacket mergePackets(necesse.engine.network.NetworkPacket);" "NetworkPacketMergePatch"
    expect $JAR $NP "public boolean isComplete();" "split packets reach the tracker incomplete"
    expect $JAR $NP "public final int identifier;" "split packet id (0 = not split)"
    expect $JAR $NP "public final necesse.engine.network.networkInfo.NetworkInfo networkInfo;" "peer of a packet"
    expect $JAR $NP "public void sendPacket() throws java.io.IOException;" "NetworkPacketSendPatch"
    expect $JAR $NP "public necesse.engine.network.NetworkPacket(necesse.engine.network.Packet, necesse.engine.network.networkInfo.NetworkInfo);" "sending NACKs"
    expect $JAR $NP "sipush        992" "fragment content size (WireFormat docs)"
    PM=necesse.engine.network.PacketManager
    expect $JAR $PM "public void submitInPacket(necesse.engine.network.NetworkPacket);" "PacketManagerSubmitInPatch"
    expect $JAR $PM "public void submitOutPacket(necesse.engine.network.NetworkPacket);" "NACKs are counted like game packets"
    expect $JAR $PM "sipush        5000" "the game's incomplete-packet timeout; nackGiveUpMs must stay below it"
    expect $JAR $PM "public long getTotalIncompleteDropped();" "stats"
    expect $JAR necesse.engine.network.server.Server "public void frameTick(necesse.engine.gameLoop.tickManager.TickManager);" "ServerFrameTickPatch"
    expect $JAR necesse.engine.network.server.Server "public final necesse.engine.network.PacketManager packetManager;" "FrameTick"
    expect $JAR necesse.engine.network.client.Client "public void frameTick(necesse.engine.gameLoop.tickManager.TickManager);" "ClientFrameTickPatch"
    expect $JAR necesse.engine.network.client.Client "public final necesse.engine.network.PacketManager packetManager;" "FrameTick"
    expect $JAR necesse.engine.network.networkInfo.DatagramNetworkInfo "public final java.net.DatagramSocket socket;" "socket buffer tuning"
    expect $JAR necesse.engine.network.networkInfo.NetworkInfo "public abstract void send(byte[]) throws java.io.IOException;" "resending fragments"
    expect $JAR necesse.engine.registries.PacketRegistry "public static int registerPacket(boolean, boolean, boolean, java.lang.Class<? extends necesse.engine.network.Packet>);" "registering PacketFragmentNack"
    expect $JAR necesse.engine.registries.PacketRegistry "public static int registerPacket(java.lang.Class<? extends necesse.engine.network.Packet>);" "registering PacketNetTestReady"
    expect $JAR necesse.engine.network.Packet "public void processServer(necesse.engine.network.NetworkPacket, necesse.engine.network.server.Server, necesse.engine.network.server.ServerClient);" "packet handlers"
    expect $JAR necesse.engine.network.Packet "public void processClient(necesse.engine.network.NetworkPacket, necesse.engine.network.client.Client);" "packet handlers"
    expect $JAR necesse.engine.network.client.Client "public necesse.engine.network.server.Server getLocalServer();" "FrameTick: hosts have no own traffic"
    expect $JAR necesse.engine.network.server.ServerClient "public necesse.engine.network.networkInfo.NetworkInfo networkInfo;" "forget peer on disconnect"
    expect $JAR necesse.engine.events.ServerClientDisconnectEvent "public final necesse.engine.network.server.ServerClient client;" "forget peer on disconnect"
    expect $JAR necesse.engine.modLoader.ModSettings "public abstract void applyLoadData(necesse.engine.save.LoadData);" "NetSettings"
    expect $JAR necesse.engine.network.client.ClientLevelLoading "public boolean isLoadingDone();" "NetTest (benchmark only)"
    expect $JAR necesse.engine.network.server.ServerClient "public void changeLevel(necesse.engine.util.LevelIdentifier, java.util.function.Function<necesse.level.maps.Level, java.awt.Point>, boolean);" "NetTest teleports (benchmark only)"
done

# Steam exists in the client jar only.
SI=necesse.engine.platforms.steam.network.networkInfo.SteamNetworkMessagesInfo
expect Necesse.jar $SI "public void send(byte[]) throws java.io.IOException;" "SteamSendPatch"
expect Necesse.jar $SI "public static com.codedisaster.steamworks.SteamNetworkingMessages\$SendFlag sendFlag;" "SteamRouting ALL_RELIABLE"
expect Necesse.jar $SI "public final com.codedisaster.steamworks.SteamNetworkingMessages networking;" "SteamRouting.trySend"
expect Necesse.jar $SI "public final com.codedisaster.steamworks.SteamID remoteID;" "SteamRouting.trySend"

[ $FAIL = 0 ] && echo "All game internals present." || echo "Some internals changed: see docs/UPGRADING.md."
exit $FAIL
