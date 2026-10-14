package netreliability.config;

import java.io.File;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/**
 * Every setting of the mod, as one immutable snapshot ({@link #get()}). Settings are per machine and are not synced:
 * each side only decides how it receives (NACKs, simulated loss) and how it sends over Steam; answering a peer's NACK
 * is always on, so mixed settings still work.
 *
 * <p>The file is the vanilla per-mod settings file, {@code cfg/mods/a632079.netreliability.cfg}: the game creates it
 * with the defaults on first launch ({@link NetSettings}); {@code /netrel reload} or {@code /netrelc reload} re-reads it.
 */
public final class NetConfig {

    public static final String MOD_ID = "a632079.netreliability";

    private static volatile NetConfig current = new NetConfig(new Builder());

    /** Repair the vanilla mergePackets bug (a split packet whose fragment 0 arrives late never completes). */
    public final boolean fixVanillaMergeBug;
    /** Ask the sender to resend the fragments of a split packet that did not arrive. */
    public final boolean nackEnabled;
    /** A split packet that got no fragment for this long is missing the rest: NACK it. */
    public final int nackDelayMs;
    /** Lower bound on the wait before NACKing the same packet again (the wait is otherwise 2 x the measured RTT). */
    public final int nackRetryMinMs;
    /** NACK rounds per split packet before giving up and leaving it to the game's own re-requests. */
    public final int nackMaxRounds;
    /** Give up on a split packet this long after its first fragment; must stay below the game's 5 s timeout. */
    public final int nackGiveUpMs;
    /** How long sent fragments are kept for answering NACKs. */
    public final int sendCacheMs;
    /** Upper bound on the fragments kept for NACKs; the oldest go first. */
    public final int sendCacheMaxKB;
    /** Receive and send buffer requested for the game's UDP sockets; 0 = leave the OS default. */
    public final int socketBufferKB;
    /** Steam P2P send routing: VANILLA, FRAGMENTS_RELIABLE or ALL_RELIABLE ({@link SteamSendMode}). */
    public final SteamSendMode steamSendMode;
    /** Testing: share of incoming datagrams this machine drops on purpose, 0 to 1. */
    public final float simulatedLoss;
    /** Testing: drop only fragments of split packets, so the rest of the game keeps working. */
    public final boolean simulatedLossFragmentsOnly;
    /** Append a stats row to netreliability-stats.csv every this many seconds; 0 = off. */
    public final int statsLogSeconds;

    private NetConfig(Builder b) {
        fixVanillaMergeBug = b.fixVanillaMergeBug;
        nackEnabled = b.nackEnabled;
        nackDelayMs = b.nackDelayMs;
        nackRetryMinMs = b.nackRetryMinMs;
        nackMaxRounds = b.nackMaxRounds;
        nackGiveUpMs = b.nackGiveUpMs;
        sendCacheMs = b.sendCacheMs;
        sendCacheMaxKB = b.sendCacheMaxKB;
        socketBufferKB = b.socketBufferKB;
        steamSendMode = b.steamSendMode;
        simulatedLoss = b.simulatedLoss;
        simulatedLossFragmentsOnly = b.simulatedLossFragmentsOnly;
        statsLogSeconds = b.statsLogSeconds;
    }

    public static NetConfig get() {
        return current;
    }

    public static void set(NetConfig config) {
        current = config;
    }

    public static NetConfig defaults() {
        return new NetConfig(new Builder());
    }

    public static File file() {
        return new File(GlobalData.cfgPath() + "mods/" + MOD_ID + ".cfg");
    }

    /** Re-reads the settings file. Returns an error message, or null on success; on error the settings stay. */
    public static String reloadFromFile() {
        File file = file();
        if (!file.exists()) {
            set(defaults());
            return null;
        }
        try {
            LoadData settings = new LoadData(file).getFirstLoadDataByName("SETTINGS");
            set(settings != null ? fromLoadData(settings) : defaults());
            return null;
        } catch (RuntimeException e) {
            GameLog.err.println("[NetReliability] could not read " + file + ": " + e);
            return e.toString();
        }
    }

    public static NetConfig fromLoadData(LoadData data) {
        Builder d = new Builder();
        Builder b = new Builder();
        b.fixVanillaMergeBug = data.getBoolean("fixVanillaMergeBug", d.fixVanillaMergeBug, false);
        b.nackEnabled = data.getBoolean("nackEnabled", d.nackEnabled, false);
        b.nackDelayMs = data.getInt("nackDelayMs", d.nackDelayMs, 1, 2000, false);
        b.nackRetryMinMs = data.getInt("nackRetryMinMs", d.nackRetryMinMs, 1, 2000, false);
        b.nackMaxRounds = data.getInt("nackMaxRounds", d.nackMaxRounds, 1, 50, false);
        b.nackGiveUpMs = data.getInt("nackGiveUpMs", d.nackGiveUpMs, 100, 4900, false);
        b.sendCacheMs = data.getInt("sendCacheMs", d.sendCacheMs, 100, 30000, false);
        b.sendCacheMaxKB = data.getInt("sendCacheMaxKB", d.sendCacheMaxKB, 64, 1024 * 1024, false);
        b.socketBufferKB = data.getInt("socketBufferKB", d.socketBufferKB, 0, 64 * 1024, false);
        b.steamSendMode = data.getEnum(SteamSendMode.class, "steamSendMode", d.steamSendMode, false);
        b.simulatedLoss = data.getFloat("simulatedLoss", d.simulatedLoss, 0.0F, 1.0F, false);
        b.simulatedLossFragmentsOnly = data.getBoolean("simulatedLossFragmentsOnly", d.simulatedLossFragmentsOnly, false);
        b.statsLogSeconds = data.getInt("statsLogSeconds", d.statsLogSeconds, 0, 3600, false);
        return new NetConfig(b);
    }

    public void addSaveData(SaveData save) {
        save.addBoolean("fixVanillaMergeBug", fixVanillaMergeBug, "Repair the game bug that drops split packets whose first fragment arrives late (needed by NACK)");
        save.addBoolean("nackEnabled", nackEnabled, "Ask the sender to resend lost fragments of split packets (both sides need the mod)");
        save.addInt("nackDelayMs", nackDelayMs, "A split packet with no new fragment for this long gets a NACK");
        save.addInt("nackRetryMinMs", nackRetryMinMs, "Minimum wait before NACKing the same packet again (otherwise 2 x RTT)");
        save.addInt("nackMaxRounds", nackMaxRounds, "NACK rounds per split packet before giving up");
        save.addInt("nackGiveUpMs", nackGiveUpMs, "Give up on a split packet this long after its first fragment (< 5000, the game's timeout)");
        save.addInt("sendCacheMs", sendCacheMs, "How long sent fragments are kept for answering NACKs");
        save.addInt("sendCacheMaxKB", sendCacheMaxKB, "Memory cap for kept fragments, in KB");
        save.addInt("socketBufferKB", socketBufferKB, "UDP socket receive/send buffer to request, in KB; 0 = OS default");
        save.addEnum("steamSendMode", steamSendMode, "Steam P2P sends: VANILLA | FRAGMENTS_RELIABLE | ALL_RELIABLE");
        save.addFloat("simulatedLoss", simulatedLoss, "TESTING: share of incoming datagrams to drop on purpose (0-1)");
        save.addBoolean("simulatedLossFragmentsOnly", simulatedLossFragmentsOnly, "TESTING: drop only fragments of split packets");
        save.addInt("statsLogSeconds", statsLogSeconds, "Write a row to netreliability-stats.csv every N seconds; 0 = off");
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.fixVanillaMergeBug = fixVanillaMergeBug;
        b.nackEnabled = nackEnabled;
        b.nackDelayMs = nackDelayMs;
        b.nackRetryMinMs = nackRetryMinMs;
        b.nackMaxRounds = nackMaxRounds;
        b.nackGiveUpMs = nackGiveUpMs;
        b.sendCacheMs = sendCacheMs;
        b.sendCacheMaxKB = sendCacheMaxKB;
        b.socketBufferKB = socketBufferKB;
        b.steamSendMode = steamSendMode;
        b.simulatedLoss = simulatedLoss;
        b.simulatedLossFragmentsOnly = simulatedLossFragmentsOnly;
        b.statsLogSeconds = statsLogSeconds;
        return b;
    }

    public String describe() {
        return "mergeFix=" + fixVanillaMergeBug + ", nack=" + nackEnabled + " (delay " + nackDelayMs + " ms, retry >= " + nackRetryMinMs + " ms, " + nackMaxRounds
            + " rounds, give up " + nackGiveUpMs + " ms), sendCache=" + sendCacheMs + " ms/" + sendCacheMaxKB + " KB, socketBuffer="
            + socketBufferKB + " KB, steam=" + steamSendMode + ", simulatedLoss=" + simulatedLoss
            + (simulatedLossFragmentsOnly ? " (fragments only)" : "") + ", statsLog=" + statsLogSeconds + " s";
    }

    /** Defaults live here and nowhere else. */
    public static final class Builder {
        public boolean fixVanillaMergeBug = true;
        public boolean nackEnabled = true;
        public int nackDelayMs = 40;
        public int nackRetryMinMs = 120;
        public int nackMaxRounds = 8;
        public int nackGiveUpMs = 4500;
        public int sendCacheMs = 6000;
        public int sendCacheMaxKB = 64 * 1024;
        public int socketBufferKB = 4096;
        public SteamSendMode steamSendMode = SteamSendMode.VANILLA;
        public float simulatedLoss = 0.0F;
        public boolean simulatedLossFragmentsOnly = false;
        public int statsLogSeconds = 0;

        public NetConfig build() {
            return new NetConfig(this);
        }
    }
}
