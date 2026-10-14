package netreliability.stats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.network.PacketManager;
import netreliability.config.NetConfig;

/**
 * Appends one CSV row per role (server / client) every {@code statsLogSeconds} to
 * {@code <data dir>/netreliability-stats.csv}: the game's own packet counters followed by {@link NetStats}.
 * Counters are cumulative; diff consecutive rows for rates.
 */
public final class StatsLog {

    private static final String HEADER = "timeMs,role,inPackets,outPackets,inBytes,outBytes,incompleteDropped,lostIn,lostOut,"
        + NetStats.COLUMNS;

    private static long lastServerRow;
    private static long lastClientRow;
    private static boolean headerChecked;
    private static boolean disabled;
    private static boolean errorLogged;

    private StatsLog() {
    }

    public static File file() {
        return new File(GlobalData.appDataPath() + "netreliability-stats.csv");
    }

    public static synchronized void tick(String role, PacketManager manager, long now) {
        int seconds = NetConfig.get().statsLogSeconds;
        if (seconds <= 0) return;
        boolean server = "server".equals(role);
        long last = server ? lastServerRow : lastClientRow;
        if (now - last < seconds * 1000L) return;
        if (server) lastServerRow = now;
        else lastClientRow = now;
        write(now + "," + role + "," + gameCounters(manager) + "," + NetStats.csvRow());
    }

    /** The game's own counters, in the CSV's order. */
    public static String gameCounters(PacketManager m) {
        return m.getTotalInPackets() + "," + m.getTotalOutPackets() + "," + m.getTotalInBytes() + "," + m.getTotalOutBytes() + ","
            + m.getTotalIncompleteDropped() + "," + m.getLostInPackets() + "," + m.getLostOutPackets();
    }

    public static List<String> describeGame(String role, PacketManager m) {
        List<String> out = new ArrayList<>();
        out.add("Game counters (" + role + "): in " + m.getTotalInPackets() + " packets / " + m.getTotalIn() + ", out "
            + m.getTotalOutPackets() + " / " + m.getTotalOut() + "; split packets dropped incomplete " + m.getTotalIncompleteDropped()
            + "; estimated lost in " + m.getLostInPackets() + ", out " + m.getLostOutPackets());
        out.add("  last " + PacketManager.networkTrackingTime + " s: in " + m.getAverageIn() + "/s (" + m.getAverageInPackets()
            + " packets), out " + m.getAverageOut() + "/s (" + m.getAverageOutPackets() + " packets)");
        return out;
    }

    private static String firstLine(File file) throws IOException {
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            return in.readLine();
        }
    }

    private static void write(String row) {
        File file = file();
        try {
            if (disabled) return;
            boolean needHeader = false;
            if (!headerChecked) {
                needHeader = !file.exists() || file.length() == 0 || !HEADER.equals(firstLine(file));
                if (needHeader && file.exists() && file.length() > 0) {
                    // Written with other columns: move it aside rather than mix two schemas in one file.
                    File old = new File(file.getPath() + ".old-" + System.currentTimeMillis() + ".csv");
                    if (!file.renameTo(old)) {
                        disabled = true;
                        GameLog.warn.println("[NetReliability] " + file + " has other columns and could not be moved aside; stats CSV off");
                        return;
                    }
                }
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                if (needHeader) out.println(HEADER);
                out.println(row);
                if (out.checkError()) throw new IOException("write failed");
            }
            // Only now: a failed first write must write the header again next time.
            headerChecked = true;
        } catch (IOException e) {
            if (!errorLogged) {
                errorLogged = true;
                GameLog.warn.println("[NetReliability] could not write " + file + ": " + e);
            }
        }
    }
}
