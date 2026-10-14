package netreliability.core;

/**
 * Which patches have run at least once in this process. After a game update a patch whose target moved simply never
 * runs (the mod loader only logs a warning), so {@code /netrel status} shows these to make that visible.
 */
public final class HookStatus {

    public static volatile boolean receive;
    public static volatile boolean send;
    /** Only fires when fragment 0 of a split packet arrives late, so false is normal on a clean link. */
    public static volatile boolean merge;
    public static volatile boolean serverFrame;
    public static volatile boolean clientFrame;
    public static volatile boolean steamSend;

    private HookStatus() {
    }

    public static String describe() {
        return "Hooks seen: receive=" + receive + " send=" + send + " serverFrame=" + serverFrame + " clientFrame=" + clientFrame
            + " steamSend=" + steamSend + " merge=" + merge + " (merge only after a late fragment 0)";
    }
}
