package netreliability.core;

import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;

/** After the game sent a packet. Must never throw into the game's send path. */
public final class Sending {

    private static volatile boolean errorLogged;

    private Sending() {
    }

    public static void onSent(NetworkPacket packet) {
        HookStatus.send = true;
        try {
            SendCache.onSent(packet);
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                GameLog.err.println("[NetReliability] error in send hook (further errors not logged): " + e);
                e.printStackTrace();
            }
        }
    }
}
