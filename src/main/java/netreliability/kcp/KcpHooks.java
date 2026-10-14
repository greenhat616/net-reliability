package netreliability.kcp;

import java.net.DatagramSocket;
import java.net.InetAddress;
import necesse.engine.GameLog;
import necesse.engine.network.networkInfo.DatagramNetworkInfo;

/** Entry points for the KCP patches. Must never throw into the game; any failure falls back to plain UDP. */
public final class KcpHooks {

    private static volatile boolean errorLogged;

    private KcpHooks() {
    }

    /** @return true if the datagram went into a KCP session and the game must not send it itself */
    public static boolean onDatagramSend(DatagramNetworkInfo info, byte[] data) {
        try {
            return KcpTransport.trySend(info, data);
        } catch (RuntimeException | LinkageError e) {
            logOnce(e);
            return false;
        }
    }

    public static void onClientOpened(boolean opened, DatagramSocket socket, InetAddress address, int port) {
        if (!opened) return;
        try {
            KcpTransport.startClient(socket, address, port);
        } catch (RuntimeException | LinkageError e) {
            logOnce(e);
        }
    }

    public static void onClientClosing(DatagramSocket socket) {
        try {
            KcpTransport.stopClient(socket);
        } catch (RuntimeException | LinkageError e) {
            logOnce(e);
        }
    }

    private static void logOnce(Throwable e) {
        if (errorLogged) return;
        errorLogged = true;
        GameLog.err.println("[NetReliability] error in KCP hook (further errors not logged): " + e);
        e.printStackTrace();
    }
}
