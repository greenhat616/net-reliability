package netreliability.kcp;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.PacketManager;
import necesse.engine.network.UnknownPacketException;
import necesse.engine.network.networkInfo.DatagramNetworkInfo;
import necesse.engine.network.networkInfo.NetworkInfo;
import necesse.engine.registries.PacketRegistry;
import netreliability.compat.GameInternals;
import netreliability.config.NetConfig;
import netreliability.core.NetThreads;
import netreliability.stats.NetStats;

/**
 * EXPERIMENTAL: carries a direct-IP connection's datagrams over KCP ({@link Kcp}) instead of bare UDP, so nothing is
 * lost: KCP resends with selective ACKs and fast resend. See {@code docs/KCP.md}.
 *
 * <p>The client decides: with {@code kcpEnabled} it opens a KCP session to the server when its socket opens
 * ({@link #startClient}) and from then on sends everything through it. A server with this mod answers in KCP to any
 * client that talks KCP to it ({@code kcpAcceptFromClients}). On the wire KCP datagrams are game packets of type
 * {@link PacketKcpSegment}; {@link #onCarrier} takes them out of the game's receive path, runs them through KCP and
 * submits the game datagrams that come out to the same {@code PacketManager}, as if they had arrived directly.
 *
 * <p>Admission is untrusted input: a server session is only published once its first datagram parsed as valid KCP,
 * at most {@link #MAX_SESSIONS_PER_ADDRESS} per IP and {@link #MAX_SESSIONS} in total. An established session is
 * never replaced by a different conversation from the same address unless it has been quiet for
 * {@link #REPLACE_QUIET_MS}; replaced conversations are retired and their late datagrams ignored.
 *
 * <p>A failed session stays as a tombstone (sends dropped, carriers ignored) until its socket closes or the game
 * connection to that peer is gone ({@link #forget}, {@link #pruneTombstones}); it never silently turns back into
 * plain UDP while the game still talks to the peer.
 *
 * <p>Steam P2P connections are not affected (Steam has its own reliable mode, see {@code steamSendMode}).
 */
public final class KcpTransport {

    /** A server session with no valid input for this long fails (the client is gone). */
    private static final int IDLE_TIMEOUT_MS = 30000;
    /** Retired conversations are ignored this long; also the minimum age of a tombstone before pruning. */
    private static final int TOMBSTONE_MS = 60000;
    private static final int MAX_RETIRED_PER_KEY = 8;
    private static final int REPLACE_QUIET_MS = 10000;
    private static final int MAX_SESSIONS = 1024;
    private static final int MAX_SESSIONS_PER_ADDRESS = 4;

    private static final ConcurrentHashMap<Key, KcpSession> sessions = new ConcurrentHashMap<>();
    /** Conversations replaced on a key, with the time they stop being ignored. Guarded by {@link #admission}. */
    private static final Map<Key, List<Retired>> retired = new HashMap<>();
    /** Guards publishing, replacing and removing sessions. */
    private static final Object admission = new Object();
    /** Sockets of this process's own client connections: they never accept new KCP peers. */
    private static final Set<DatagramSocket> clientSockets = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final ThreadLocal<Boolean> delivering = ThreadLocal.withInitial(() -> false);
    private static volatile int carrierType = -1;
    private static volatile byte[] carrierHeader;
    private static ScheduledFuture<?> updateTask;
    private static volatile boolean errorLogged;

    private KcpTransport() {
    }

    /** After packet registration: learns the carrier packet's type and wire header. */
    public static void init() {
        carrierType = PacketRegistry.getPacketID(PacketKcpSegment.class);
        if (!GameInternals.isAvailable()) return;
        // The header of a single-datagram packet does not depend on its content: take it from an empty one.
        byte[][] datagrams = GameInternals.fragmentDatagrams(new NetworkPacket(new PacketKcpSegment(new byte[0]), null));
        carrierHeader = datagrams.length == 1 ? datagrams[0] : null;
    }

    public static boolean isAvailable() {
        return carrierType >= 0 && carrierHeader != null;
    }

    /** True while KCP hands a datagram to the game: such datagrams already survived the network. */
    public static boolean isDelivering() {
        return delivering.get();
    }

    /** The connection {@code info} runs over KCP (or did, and failed): it needs no NACKs and no send cache. */
    public static boolean isReliable(NetworkInfo info) {
        if (sessions.isEmpty() || !(info instanceof DatagramNetworkInfo)) return false;
        DatagramNetworkInfo d = (DatagramNetworkInfo) info;
        return sessions.containsKey(new Key(d.socket, d.address, d.port));
    }

    /** This process's client opened its socket to a server ({@code ClientAddressNetwork.openConnection}). */
    public static void startClient(DatagramSocket socket, InetAddress address, int port) {
        if (socket == null || address == null) return;
        clientSockets.add(socket);
        if (!NetConfig.get().kcpEnabled) return;
        if (!isAvailable()) {
            GameLog.warn.println("[NetReliability] KCP requested but unavailable (game internals changed); using plain UDP");
            return;
        }
        int conv;
        do {
            conv = ThreadLocalRandom.current().nextInt();
        } while (conv == 0);
        long now = System.currentTimeMillis();
        KcpSession session = new KcpSession(socket, address, port, conv, true, carrierHeader, now);
        synchronized (admission) {
            sessions.put(new Key(socket, address, port), session);
        }
        NetStats.kcpSessions.incrementAndGet();
        ensureUpdating();
        GameLog.out.println("[NetReliability] KCP (experimental) on for " + address.getHostAddress() + ":" + port);
    }

    /** Longest the client's {@code close()} waits for its last packets (the disconnect) to be acknowledged. */
    private static final int CLIENT_DRAIN_MS = 500;

    /** The client is about to close its socket: let its last packets out, then drop the session. */
    public static void stopClient(DatagramSocket socket) {
        if (socket == null || sessions.isEmpty()) return;
        for (Map.Entry<Key, KcpSession> e : sessions.entrySet()) {
            if (e.getKey().socket == socket) e.getValue().drain(CLIENT_DRAIN_MS);
        }
        synchronized (admission) {
            sessions.keySet().removeIf(k -> k.socket == socket);
        }
    }

    /**
     * The game is about to send {@code datagram} to {@code info}. Returns true if KCP took it (queued it, or dropped
     * it because the session failed); the game must then not send it itself.
     */
    public static boolean trySend(DatagramNetworkInfo info, byte[] datagram) {
        if (sessions.isEmpty() || info.socket == null) return false;
        KcpSession session = sessions.get(new Key(info.socket, info.address, info.port));
        if (session == null) return false;
        session.send(datagram);
        return true;
    }

    /**
     * A datagram from the network, before the game handles it. Returns true if it was a KCP carrier (consumed here,
     * whatever came of it).
     */
    public static boolean onCarrier(PacketManager manager, NetworkPacket packet) {
        if (carrierType < 0 || packet.type != carrierType || !(packet.networkInfo instanceof DatagramNetworkInfo)) return false;
        NetStats.kcpDatagramsIn.incrementAndGet();
        DatagramNetworkInfo info = (DatagramNetworkInfo) packet.networkInfo;
        byte[] data = ((PacketKcpSegment) packet.getTypePacket()).raw;
        int conv = Kcp.getConv(data, 0, data.length);
        Key key = new Key(info.socket, info.address, info.port);
        long now = System.currentTimeMillis();
        KcpSession session = sessions.get(key);
        List<byte[]> datagrams;
        if (session != null && session.conv() == conv) {
            datagrams = session.input(data, now); // null for a failed session or invalid KCP
        } else {
            session = admit(key, info, conv, data, now);
            if (session == null) return true;
            datagrams = session.takeAdmissionInput();
        }
        if (datagrams == null) {
            NetStats.kcpBadInput.incrementAndGet();
            return true;
        }
        // Outside the session lock: submitting can make the game send, which takes other sessions' locks.
        for (byte[] datagram : datagrams) deliver(manager, session, info, datagram, now);
        return true;
    }

    /**
     * A carrier with a conversation no session on {@code key} has. Returns the new, published session, or null if it
     * is not admitted (the datagram is then dropped).
     */
    private static KcpSession admit(Key key, DatagramNetworkInfo info, int conv, byte[] data, long now) {
        if (conv == 0 || carrierHeader == null || !accepts(info.socket)) return null;
        synchronized (admission) {
            List<Retired> gone = retired.get(key);
            if (gone != null) {
                gone.removeIf(r -> now >= r.until);
                for (Retired r : gone) {
                    if (r.conv == conv) return null; // late datagram of a replaced conversation
                }
                if (gone.size() >= MAX_RETIRED_PER_KEY) {
                    // Too many conversations on this address within TOMBSTONE_MS: wait until one expires.
                    NetStats.kcpRejected.incrementAndGet();
                    return null;
                }
            }
            KcpSession existing = sessions.get(key);
            if (existing != null && !existing.isFailed() && now - existing.lastValidInputAt() < REPLACE_QUIET_MS) {
                NetStats.kcpRejected.incrementAndGet();
                return null; // an established conversation is not replaced by an unexpected one
            }
            if (existing != null && existing.isFailed()) {
                return null; // a tombstone: the game's timeout must end that connection first
            }
            if (existing == null && (sessions.size() >= MAX_SESSIONS || countFor(info.address) >= MAX_SESSIONS_PER_ADDRESS)) {
                NetStats.kcpRejected.incrementAndGet();
                return null;
            }
            // Validate before publishing: only valid KCP for this conversation creates or replaces routing state.
            KcpSession candidate = new KcpSession(info.socket, info.address, info.port, conv, false, carrierHeader, now);
            if (!candidate.admissionInput(data, now)) {
                NetStats.kcpRejected.incrementAndGet();
                return null;
            }
            if (existing != null) retire(key, existing.conv(), now);
            sessions.put(key, candidate);
            NetStats.kcpSessions.incrementAndGet();
            ensureUpdating();
            return candidate;
        }
    }

    public static List<String> describe() {
        NetConfig c = NetConfig.get();
        List<String> out = new ArrayList<>();
        out.add("KCP (experimental): " + (c.kcpEnabled ? "on" : "off") + " when joining by IP, "
            + (c.kcpAcceptFromClients ? "accepted" : "refused") + " from clients; " + sessions.size() + " sessions"
            + (isAvailable() ? "" : " (UNAVAILABLE: game internals changed)"));
        for (KcpSession s : sessions.values()) out.add("  " + s.describe());
        return out;
    }

    private static int countFor(InetAddress address) {
        int n = 0;
        for (Key k : sessions.keySet()) {
            if (Objects.equals(k.address, address)) n++;
        }
        return n;
    }

    private static boolean accepts(DatagramSocket socket) {
        return NetConfig.get().kcpAcceptFromClients && !clientSockets.contains(socket);
    }

    private static void deliver(PacketManager manager, KcpSession session, DatagramNetworkInfo info, byte[] datagram, long now) {
        NetworkPacket packet;
        try {
            packet = new NetworkPacket(info, datagram);
        } catch (UnknownPacketException e) {
            NetStats.kcpBadInput.incrementAndGet();
            return;
        }
        List<byte[]> fragments = session.assemble(packet, datagram, now);
        if (fragments == null) {
            submit(manager, packet);
            return;
        }
        // One at a time: each parsed fragment holds an array sized by the fragment count, so never keep them all.
        for (byte[] fragment : fragments) {
            try {
                submit(manager, new NetworkPacket(info, fragment));
            } catch (UnknownPacketException e) {
                NetStats.kcpBadInput.incrementAndGet();
            }
        }
    }

    private static void submit(PacketManager manager, NetworkPacket packet) {
        NetStats.kcpDelivered.incrementAndGet();
        delivering.set(true);
        try {
            manager.submitInPacket(packet);
        } finally {
            delivering.set(false);
        }
    }

    private static synchronized void ensureUpdating() {
        if (updateTask != null) return;
        int interval = Math.max(10, NetConfig.get().kcpIntervalMs);
        updateTask = NetThreads.worker().scheduleAtFixedRate(KcpTransport::updateAll, interval, interval, TimeUnit.MILLISECONDS);
    }

    private static void updateAll() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Key, KcpSession> e : sessions.entrySet()) {
                KcpSession s = e.getValue();
                if (s.socket.isClosed() || s.isClosed(now)) {
                    remove(e.getKey(), s);
                    continue;
                }
                s.update(now, s.initiator ? 0 : IDLE_TIMEOUT_MS);
            }
            synchronized (admission) {
                for (List<Retired> list : retired.values()) list.removeIf(r -> now >= r.until);
                retired.values().removeIf(List::isEmpty);
            }
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                GameLog.err.println("[NetReliability] error in KCP update (further errors not logged): " + e);
                e.printStackTrace();
            }
        }
    }

    /**
     * The game connection to {@code info} is gone (a server client disconnected). The session lingers until the
     * queued data (the game's disconnect packet, sent just before) is acknowledged, then the update loop drops it.
     */
    public static void forget(NetworkInfo info) {
        if (!(info instanceof DatagramNetworkInfo) || sessions.isEmpty()) return;
        DatagramNetworkInfo d = (DatagramNetworkInfo) info;
        KcpSession s = sessions.get(new Key(d.socket, d.address, d.port));
        if (s != null) s.beginClose(System.currentTimeMillis());
    }

    /**
     * Drops server tombstones older than {@link #TOMBSTONE_MS} whose peer is not a connected game client (sessions
     * that failed before or without ever joining the game). Called by the server tick.
     */
    public static void pruneTombstones(java.util.function.Predicate<NetworkInfo> connected, long now) {
        if (sessions.isEmpty()) return;
        synchronized (admission) {
            sessions.entrySet().removeIf(e -> {
                KcpSession s = e.getValue();
                if (s.initiator || !s.isFailed() || now - s.failedAt() < TOMBSTONE_MS) return false;
                Key k = e.getKey();
                if (connected.test(new DatagramNetworkInfo(k.socket, k.address, k.port))) return false;
                retire(k, s.conv(), now);
                return true;
            });
        }
    }

    private static void remove(Key key, KcpSession session) {
        synchronized (admission) {
            if (!sessions.remove(key, session) || session.initiator) return;
            // Late datagrams of this conversation must not recreate it with reset sequence numbers.
            retire(key, session.conv(), System.currentTimeMillis());
        }
    }

    /** Caller holds {@link #admission}. */
    private static void retire(Key key, int conv, long now) {
        List<Retired> list = retired.computeIfAbsent(key, k -> new ArrayList<>());
        // Only expired entries go: admit() refuses new sessions while the list is full, so this stays bounded
        // (at most MAX_RETIRED_PER_KEY plus the one session that list allowed).
        list.removeIf(r -> now >= r.until);
        list.add(new Retired(conv, now + TOMBSTONE_MS));
    }

    private static final class Retired {
        final int conv;
        final long until;

        Retired(int conv, long until) {
            this.conv = conv;
            this.until = until;
        }
    }

    private static final class Key {
        final DatagramSocket socket;
        final InetAddress address;
        final int port;

        Key(DatagramSocket socket, InetAddress address, int port) {
            this.socket = socket;
            this.address = address;
            this.port = port;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return socket == k.socket && port == k.port && Objects.equals(address, k.address);
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(socket) * 31 + Objects.hashCode(address)) * 31 + port;
        }
    }
}
