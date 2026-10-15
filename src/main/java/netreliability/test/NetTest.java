package netreliability.test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import necesse.engine.GameLog;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelLoading;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.level.maps.Level;
import netreliability.config.NetConfig;
import netreliability.stats.NetStats;

/**
 * Scripted region streaming benchmark ({@code tools/net-test.sh}), active only with {@code -Dnetreliability.test=1}.
 *
 * <p>The server teleports the first player through {@link #POSITIONS} far-apart spots, {@link #ROUNDS} times over. The
 * client measures how long each teleport takes until its level has every region it asked for, and reports it back
 * ({@link PacketNetTestReady}); the server then teleports again. Round 1 includes world generation on the server;
 * later rounds revisit generated ground, so they measure the network.
 */
public final class NetTest {

    public static final boolean ENABLED = "1".equals(System.getProperty("netreliability.test"));
    static final int POSITIONS = Integer.getInteger("netreliability.test.positions", 5);
    static final int ROUNDS = Integer.getInteger("netreliability.test.rounds", 2);
    private static final long SEED = Long.getLong("netreliability.test.seed", 1234L);
    /**
     * Simulated incoming loss the client switches on once it has joined (connecting under heavy simulated loss can
     * stall the game's handshake for minutes, which is not what this measures). Negative = leave the setting alone.
     */
    private static final float LOSS_AFTER_JOIN = Float.parseFloat(System.getProperty("netreliability.test.loss", "-1"));
    private static final int TELEPORT_PAUSE_MS = 1000;
    /** No report this long after a teleport: send the teleport again (the client measures from when it moved). */
    private static final int TELEPORT_RETRY_MS = 30000;
    /** A jump of this many pixels in one frame is a teleport (positions are 250+ tiles apart). */
    private static final float TELEPORT_DISTANCE = 100 * 32;
    private static final int LOAD_TIMEOUT_MS = 60000;
    /** If loading never starts after a teleport, every region was still loaded. */
    private static final int NO_LOAD_MS = 3000;
    /** The report travels over the lossy link too: resend it until the server reacts (teleports or finishes). */
    private static final int REPORT_RESEND_MS = 2000;

    // Server state (server thread)
    private static ServerClient target;
    private static Point origin;
    private static int teleportsDone;
    private static long nextTeleportAt;
    private static boolean waitingForClient;
    private static long teleportSentAt;
    private static int teleportRetries;
    private static final List<Long> durations = Collections.synchronizedList(new ArrayList<>());
    private static boolean serverDone;

    // Client state (client thread)
    private static long clientStart;
    private static boolean initialReported;
    private static int clientSeq;
    private static float lastX = Float.NaN, lastY = Float.NaN;
    private static long teleportAt;
    private static boolean sawLoading;
    private static PacketNetTestReady lastReport;
    private static long lastReportAt;

    private NetTest() {
    }

    public static void serverTick(Server server, long now) {
        if (!ENABLED || serverDone) return;
        if (target == null) return; // set by the client's first report
        if (waitingForClient) {
            // The teleport itself can be lost (vanilla does not resend it): the client then never moves or reports.
            if (now - teleportSentAt > TELEPORT_RETRY_MS) {
                teleportRetries++;
                GameLog.out.println("[NetReliability] test teleport " + teleportsDone + " retried (no report)");
                teleport(position((teleportsDone - 1) % POSITIONS, target.getLevel()), now);
            }
            return;
        }
        if (now < nextTeleportAt) return;
        if (teleportsDone >= POSITIONS * ROUNDS) {
            serverDone = true;
            summary();
            return;
        }
        Point p = position(teleportsDone % POSITIONS, target.getLevel());
        teleportsDone++;
        waitingForClient = true;
        GameLog.out.println("[NetReliability] test teleport " + teleportsDone + " to tile " + p.x + "," + p.y);
        teleport(p, now);
    }

    private static void teleport(Point p, long now) {
        teleportSentAt = now;
        target.changeLevel(target.getLevelIdentifier(), level -> new Point(p.x * 32 + 16, p.y * 32 + 16), true);
    }

    /** From the client: it finished loading after teleport {@code seq} (0 = after joining). */
    static void onClientReady(Server server, ServerClient client, int seq, long durationMs) {
        if (!ENABLED || client == null || serverDone) return;
        if (seq == 0) {
            if (target != null) return; // a resent report
            target = client;
            origin = new Point(client.playerMob.getTileX(), client.playerMob.getTileY());
            GameLog.out.println("[NetReliability] test client joined, initial load " + durationMs + " ms, origin " + origin.x + "," + origin.y);
        } else {
            // Resent reports repeat a seq the server already handled: only the first copy counts.
            if (client != target || seq != teleportsDone || !waitingForClient) return;
            durations.add(durationMs);
            GameLog.out.println("[NetReliability] test teleport " + seq + " loaded in " + durationMs + " ms");
        }
        waitingForClient = false;
        nextTeleportAt = System.currentTimeMillis() + TELEPORT_PAUSE_MS;
    }

    public static void clientTick(Client client, long now) {
        if (!ENABLED || client.getLocalServer() != null) return;
        if (clientStart == 0) clientStart = now;
        PlayerMob player = client.getPlayer();
        ClientLevelLoading loading = client.levelManager == null ? null : client.levelManager.loading();
        if (player == null || loading == null || !client.loading.isDone()) return;
        if (!initialReported) {
            if (!loading.isLoadingDone()) return;
            initialReported = true;
            remember(player);
            if (LOSS_AFTER_JOIN >= 0) {
                NetConfig.Builder b = NetConfig.get().toBuilder();
                b.simulatedLoss = LOSS_AFTER_JOIN;
                NetConfig.setRuntime(b.build());
                GameLog.out.println("[NetReliability] test client simulated loss now " + LOSS_AFTER_JOIN);
            }
            report(client, 0, now - clientStart);
            return;
        }
        float dx = player.x - lastX, dy = player.y - lastY;
        if (teleportAt == 0 && dx * dx + dy * dy > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            teleportAt = now;
            sawLoading = false;
        }
        remember(player);
        if (teleportAt == 0) {
            if (lastReport != null && now - lastReportAt > REPORT_RESEND_MS) {
                lastReportAt = now;
                client.network.sendPacket(new PacketNetTestReady(lastReport.seq, lastReport.durationMs));
            }
            return;
        }
        if (!loading.isLoadingDone()) sawLoading = true;
        long elapsed = now - teleportAt;
        long result;
        if (sawLoading && loading.isLoadingDone()) result = elapsed;
        else if (!sawLoading && elapsed > NO_LOAD_MS) result = 0;
        else if (elapsed > LOAD_TIMEOUT_MS) result = -1;
        else return;
        teleportAt = 0;
        report(client, ++clientSeq, result);
    }

    private static void remember(PlayerMob player) {
        lastX = player.x;
        lastY = player.y;
    }

    private static void report(Client client, int seq, long durationMs) {
        GameLog.out.println("[NetReliability] test client report " + seq + ": " + durationMs + " ms");
        for (String line : NetStats.describe()) GameLog.out.println("[NetReliability] test client " + line);
        lastReport = new PacketNetTestReady(seq, durationMs);
        lastReportAt = System.currentTimeMillis();
        client.network.sendPacket(new PacketNetTestReady(seq, durationMs));
    }

    private static Point position(int i, Level level) {
        Random random = new Random(SEED + i * 7919L);
        double angle = random.nextDouble() * Math.PI * 2;
        int distance = 250 + random.nextInt(250);
        int x = origin.x + (int) (Math.cos(angle) * distance);
        int y = origin.y + (int) (Math.sin(angle) * distance);
        if (level != null && level.tileWidth > 0) x = Math.max(50, Math.min(level.tileWidth - 50, x));
        if (level != null && level.tileHeight > 0) y = Math.max(50, Math.min(level.tileHeight - 50, y));
        return new Point(x, y);
    }

    private static void summary() {
        for (int round = 0; round < ROUNDS; round++) {
            List<Long> list = new ArrayList<>();
            int timeouts = 0;
            synchronized (durations) {
                for (int i = round * POSITIONS; i < Math.min(durations.size(), (round + 1) * POSITIONS); i++) {
                    long d = durations.get(i);
                    if (d < 0) timeouts++;
                    else list.add(d);
                }
            }
            Collections.sort(list);
            long sum = 0;
            for (long d : list) sum += d;
            String stats = list.isEmpty() ? "no data" : "mean " + sum / list.size() + " ms, median " + list.get(list.size() / 2)
                + " ms, max " + list.get(list.size() - 1) + " ms";
            GameLog.out.println("[NetReliability] test round " + (round + 1) + ": " + stats + ", timeouts " + timeouts);
        }
        GameLog.out.println("[NetReliability] test round teleport retries: " + teleportRetries);
        for (String line : NetStats.describe()) GameLog.out.println("[NetReliability] test server " + line);
        GameLog.out.println("[NetReliability] test DONE");
    }
}
