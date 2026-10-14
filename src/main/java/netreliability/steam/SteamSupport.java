package netreliability.steam;

import necesse.engine.GameLog;
import netreliability.config.SteamSendMode;

/**
 * Steam-free front door to {@link SteamRouting}. The dedicated server ({@code Server.jar}) ships no Steam classes, so
 * nothing outside the {@code steam} package may touch a Steam type; call this instead.
 */
public final class SteamSupport {

    private static final boolean PRESENT = detect();

    private SteamSupport() {
    }

    public static boolean isPresent() {
        return PRESENT;
    }

    public static void applyMode(SteamSendMode mode) {
        if (!PRESENT) return;
        try {
            SteamRouting.applyMode(mode);
        } catch (LinkageError | RuntimeException e) {
            GameLog.warn.println("[NetReliability] could not apply Steam send mode " + mode + ": " + e);
        }
    }

    public static String describe() {
        if (!PRESENT) return "Steam: not available in this build";
        try {
            return SteamRouting.describe();
        } catch (LinkageError | RuntimeException e) {
            return "Steam: " + e;
        }
    }

    private static boolean detect() {
        try {
            Class.forName("necesse.engine.platforms.steam.network.networkInfo.SteamNetworkMessagesInfo", false,
                SteamSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
