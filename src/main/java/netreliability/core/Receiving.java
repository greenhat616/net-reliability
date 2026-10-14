package netreliability.core;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.PacketManager;
import necesse.engine.network.networkInfo.DatagramNetworkInfo;
import netreliability.compat.GameInternals;
import netreliability.config.NetConfig;
import netreliability.kcp.KcpTransport;
import netreliability.stats.NetStats;

/**
 * Everything that happens to a datagram from the network before the game's {@code PacketManager.submitInPacket}
 * handles it: socket buffer tuning, simulated loss, fragment tracking. Must never throw into the game's receive loop.
 */
public final class Receiving {

    private static final Set<DatagramSocket> tunedSockets = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile DatagramSocket lastTuned;
    private static volatile String socketBufferReport = "no UDP socket seen yet";
    private static volatile boolean errorLogged;

    private Receiving() {
    }

    /** @return true if the game should not see this datagram */
    public static boolean onDatagram(PacketManager manager, NetworkPacket packet) {
        if (packet.networkInfo == null) return false; // same-process (host or singleplayer) traffic
        try {
            HookStatus.receive = true;
            // Datagrams KCP hands to the game already survived the network: no tuning, no simulated loss.
            boolean fromKcp = KcpTransport.isDelivering();
            if (!fromKcp && packet.networkInfo instanceof DatagramNetworkInfo) tune(((DatagramNetworkInfo) packet.networkInfo).socket);
            boolean fragment = packet.identifier != 0;
            NetConfig config = NetConfig.get();
            if (!fromKcp && config.simulatedLoss > 0 && (fragment || !config.simulatedLossFragmentsOnly)
                && ThreadLocalRandom.current().nextFloat() < config.simulatedLoss) {
                NetStats.simulatedDrops.incrementAndGet();
                return true;
            }
            if (!fromKcp && KcpTransport.onCarrier(manager, packet)) return true;
            if (fragment && GameInternals.isAvailable()) {
                return ReceiveTracker.onFragment(manager, packet, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                GameLog.err.println("[NetReliability] error in receive hook (further errors not logged): " + e);
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * After the game's {@code NetworkPacket.mergePackets} returned another object than the one it merged into: give it
     * the merged count ({@code NetworkPacketMergePatch}).
     */
    public static void onMergedIntoOther(NetworkPacket merged, int count) {
        HookStatus.merge = true;
        if (!NetConfig.get().fixVanillaMergeBug) return;
        try {
            GameInternals.setFragmentCount(merged, count);
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                GameLog.err.println("[NetReliability] error in merge hook (further errors not logged): " + e);
                e.printStackTrace();
            }
        }
    }

    public static String socketBufferReport() {
        return socketBufferReport;
    }

    private static void tune(DatagramSocket socket) {
        if (socket == null || socket == lastTuned) return;
        if (!tunedSockets.add(socket)) {
            lastTuned = socket;
            return;
        }
        lastTuned = socket;
        int wanted = NetConfig.get().socketBufferKB * 1024;
        try {
            int before = socket.getReceiveBufferSize();
            if (wanted > 0) {
                socket.setReceiveBufferSize(wanted);
                socket.setSendBufferSize(wanted);
            }
            socketBufferReport = "UDP socket port " + socket.getLocalPort() + ": receive buffer " + before / 1024 + " KB -> "
                + socket.getReceiveBufferSize() / 1024 + " KB, send buffer " + socket.getSendBufferSize() / 1024 + " KB";
            GameLog.out.println("[NetReliability] " + socketBufferReport);
        } catch (SocketException e) {
            socketBufferReport = "could not set UDP socket buffers: " + e;
            GameLog.warn.println("[NetReliability] " + socketBufferReport);
        }
    }
}
