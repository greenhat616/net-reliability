package netreliability.core;

import necesse.engine.GameLog;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import netreliability.kcp.KcpTransport;
import netreliability.stats.StatsLog;
import netreliability.test.NetTest;

/** Per-frame work, driven by the server's and the client's {@code frameTick}. Must never throw into the game loop. */
public final class FrameTick {

    private static volatile boolean errorLogged;
    private static long lastTombstonePrune;

    private FrameTick() {
    }

    public static void server(Server server) {
        HookStatus.serverFrame = true;
        try {
            long now = System.currentTimeMillis();
            ReceiveTracker.tick(server.packetManager, now);
            common(now);
            StatsLog.tick("server", server.packetManager, now);
            if (now - lastTombstonePrune > 1000) {
                lastTombstonePrune = now;
                KcpTransport.pruneTombstones(info -> server.streamClients().anyMatch(c -> info.equals(c.networkInfo)), now);
            }
            NetTest.serverTick(server, now);
        } catch (RuntimeException e) {
            logOnce(e);
        }
    }

    public static void client(Client client) {
        HookStatus.clientFrame = true;
        try {
            long now = System.currentTimeMillis();
            ReceiveTracker.tick(client.packetManager, now);
            common(now);
            // A host's or singleplayer client has no network traffic of its own.
            if (client.getLocalServer() == null) StatsLog.tick("client", client.packetManager, now);
            NetTest.clientTick(client, now);
        } catch (RuntimeException e) {
            logOnce(e);
        }
    }

    private static void common(long now) {
        ReceiveTracker.prune(now);
        SendCache.tick(now);
    }

    private static void logOnce(RuntimeException e) {
        if (errorLogged) return;
        errorLogged = true;
        GameLog.err.println("[NetReliability] error in frame tick (further errors not logged): " + e);
        e.printStackTrace();
    }
}
