package netreliability.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.PacketManager;
import necesse.engine.network.networkInfo.NetworkInfo;
import netreliability.compat.GameInternals;
import netreliability.config.NetConfig;
import netreliability.packets.PacketFragmentNack;
import netreliability.stats.NetStats;

/**
 * Receiving side of NACK recovery. Watches the fragments of split packets as they reach the game's
 * {@code PacketManager}; when a packet stops receiving fragments before it is complete, asks the sender for the
 * missing ones ({@link PacketFragmentNack}). The game still assembles the packet itself: this only tracks which
 * fragments it has, so the two never disagree as long as every datagram passes through {@link #onFragment}.
 *
 * <p>Tracking runs even with NACKs off, so the stats show how many split packets vanilla loses.
 *
 * <p>Entries are keyed by the receiving {@code PacketManager} too: a new connection gets a new manager, so stale
 * entries from an old connection (whose sender may restart its identifiers at 1) never match. Every map is capped,
 * because anyone can send datagrams that look like fragments.
 */
public final class ReceiveTracker {

    /** Finished packets are remembered this long, to drop resends that arrive after completion. */
    private static final int FINISHED_MEMORY_MS = 10000;
    /** Retry wait while no RTT has been measured yet. */
    private static final int DEFAULT_RETRY_MS = 250;
    private static final int PEER_IDLE_FORGET_MS = 60000;
    /** Incomplete packets dropped by {@link #prune} even if their manager stopped ticking (connection gone). */
    private static final int ABANDONED_EXTRA_MS = 2000;
    private static final int MAX_PARTIALS = 4096;
    private static final int MAX_FINISHED = 65536;
    private static final int MAX_PEERS = 4096;

    private static final ConcurrentHashMap<Key, Partial> partials = new ConcurrentHashMap<>();
    /** Recently finished packets: completed (later copies are dropped) or given up (later copies go to the game). */
    private static final ConcurrentHashMap<Key, Finished> finished = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<NetworkInfo, Peer> peers = new ConcurrentHashMap<>();
    private static volatile long lastSendError;

    private ReceiveTracker() {
    }

    /**
     * One fragment of a split packet, before the game's {@code PacketManager} sees it.
     *
     * @return true if the game should not see it (a copy of a fragment of a packet that already completed)
     */
    public static boolean onFragment(PacketManager manager, NetworkPacket packet, long now) {
        int total = GameInternals.fragmentCount(packet);
        int index = GameInternals.fragmentIndex(packet);
        NetStats.fragmentsReceived.incrementAndGet();
        Key key = new Key(manager, packet.networkInfo, packet.identifier);
        Finished done = finished.get(key);
        if (done != null) {
            if (done.completed) {
                NetStats.fragmentsLate.incrementAndGet();
                return true;
            }
            return false;
        }
        Partial partial = partials.get(key);
        if (partial == null) {
            if (partials.size() >= MAX_PARTIALS) {
                NetStats.trackingOverflow.incrementAndGet();
                return false;
            }
            Partial created = new Partial(manager, total, now);
            partial = partials.putIfAbsent(key, created);
            if (partial == null) {
                partial = created;
                NetStats.splitStarted.incrementAndGet();
                NetStats.recordFragmentCount(total);
            }
        }
        Peer peer = peer(packet.networkInfo, now);
        synchronized (partial) {
            if (partial.closed || index < 0 || index >= partial.total || partial.received.get(index)) return false;
            // Give up here too, not only in tick: the game's own copy of this packet can expire (5 s after its last
            // fragment) while ticks stall, and a "completed" verdict for it would then drop copies the game still needs.
            // Anything younger than nackGiveUpMs (< 5 s) is still held by the game.
            if (now - partial.firstFragmentAt > NetConfig.get().nackGiveUpMs) {
                giveUp(key, partial, now);
                return false;
            }
            partial.received.set(index);
            partial.count++;
            partial.lastFragmentAt = now;
            partial.replyTo = packet.networkInfo;
            if (partial.awaitingResend) {
                partial.awaitingResend = false;
                peer.sampleRtt(now - partial.lastNackAt);
            }
            // Known gap: this verdict is reached in the hook, before the game merges the fragment. If the receive thread
            // stalled > ~0.5 s right here (nackGiveUpMs vs the game's 5 s), the game could expire its copy first; we
            // would then drop later copies it needs. The outcome equals vanilla (the packet is lost and the game asks
            // again): the game's fresh copy needs every fragment, and no NACK resend would supply them all anyway.
            if (partial.count == partial.total) {
                partial.closed = true;
                partials.remove(key);
                remember(key, true, now);
                NetStats.splitCompleted.incrementAndGet();
                if (partial.rounds == 0) NetStats.splitCleanCompleted.incrementAndGet();
                else NetStats.splitRecovered.incrementAndGet();
            }
        }
        return false;
    }

    /** Sends due NACKs for packets that {@code manager} receives, and gives up on stale ones. Called every frame. */
    public static void tick(PacketManager manager, long now) {
        NetConfig config = NetConfig.get();
        List<Nack> due = null;
        for (Map.Entry<Key, Partial> e : partials.entrySet()) {
            Partial p = e.getValue();
            if (p.manager != manager) continue;
            Nack nack;
            synchronized (p) {
                if (p.closed) continue;
                if (now - p.firstFragmentAt > config.nackGiveUpMs) {
                    giveUp(e.getKey(), p, now);
                    continue;
                }
                if (!config.nackEnabled || p.rounds >= config.nackMaxRounds) continue;
                if (now - p.lastFragmentAt < config.nackDelayMs) continue;
                if (p.rounds > 0 && now - p.lastNackAt < retryWait(p.replyTo, config)) continue;
                int[] ranges = missingRanges(p);
                if (ranges.length == 0) continue;
                p.rounds++;
                p.lastNackAt = now;
                p.awaitingResend = true;
                nack = new Nack(p.replyTo, e.getKey().identifier, ranges);
            }
            if (due == null) due = new ArrayList<>();
            due.add(nack);
        }
        if (due != null) {
            for (Nack nack : due) send(manager, nack, now);
        }
    }

    /**
     * Forgets old finished packets, idle peers, and incomplete packets nobody ticks any more (their connection is
     * gone, so {@link #tick} for their manager stopped). Process-wide, called every frame.
     */
    public static void prune(long now) {
        finished.values().removeIf(f -> f.expiresAt <= now);
        peers.values().removeIf(p -> now - p.lastSeen > PEER_IDLE_FORGET_MS);
        long abandonedBefore = now - NetConfig.get().nackGiveUpMs - ABANDONED_EXTRA_MS;
        partials.values().removeIf(p -> p.firstFragmentAt < abandonedBefore);
    }

    /** Drops everything about one peer, e.g. when it disconnects. */
    public static void forget(NetworkInfo info) {
        if (info == null) return;
        partials.keySet().removeIf(k -> info.equals(k.info));
        finished.keySet().removeIf(k -> info.equals(k.info));
        peers.remove(info);
    }

    /** Caller holds {@code partial}'s lock. */
    private static void giveUp(Key key, Partial partial, long now) {
        partial.closed = true;
        partials.remove(key, partial);
        remember(key, false, now);
        NetStats.splitGivenUp.incrementAndGet();
    }

    private static void remember(Key key, boolean completed, long now) {
        if (finished.size() < MAX_FINISHED) finished.put(key, new Finished(completed, now + FINISHED_MEMORY_MS));
    }

    public static String describe() {
        StringBuilder s = new StringBuilder("Receive tracker: " + partials.size() + " incomplete, " + finished.size()
            + " recently finished");
        for (Map.Entry<NetworkInfo, Peer> e : peers.entrySet()) {
            long rtt = e.getValue().rttMs;
            if (rtt >= 0) s.append("; NACK RTT ").append(e.getKey().getDisplayName()).append(' ').append(rtt).append(" ms");
        }
        return s.toString();
    }

    private static long retryWait(NetworkInfo info, NetConfig config) {
        Peer peer = info == null ? null : peers.get(info);
        long rtt = peer == null ? -1 : peer.rttMs;
        return Math.max(config.nackRetryMinMs, rtt < 0 ? DEFAULT_RETRY_MS : 2 * rtt);
    }

    /** Missing fragment indexes as (start, length) pairs, at most {@link PacketFragmentNack#MAX_RANGES} of them. */
    static int[] missingRanges(Partial p) {
        int[] out = new int[PacketFragmentNack.MAX_RANGES * 2];
        int n = 0;
        int i = p.received.nextClearBit(0);
        while (i < p.total && n < out.length) {
            int end = p.received.nextSetBit(i);
            if (end < 0 || end > p.total) end = p.total;
            out[n++] = i;
            out[n++] = end - i;
            NetStats.fragmentsRequested.addAndGet(end - i);
            i = p.received.nextClearBit(end);
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static void send(PacketManager manager, Nack nack, long now) {
        try {
            NetworkPacket packet = new NetworkPacket(new PacketFragmentNack(nack.identifier, nack.ranges), nack.to);
            manager.submitOutPacket(packet);
            packet.sendPacket();
            NetStats.nacksSent.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            if (now - lastSendError > 10000) {
                lastSendError = now;
                GameLog.warn.println("[NetReliability] could not send a NACK to " + nack.to.getDisplayName() + ": " + e);
            }
        }
    }

    private static Peer peer(NetworkInfo info, long now) {
        Peer peer = peers.get(info);
        if (peer == null) {
            // Over the cap the peer is not stored: RTT samples are lost and retries use the default wait.
            if (peers.size() >= MAX_PEERS) return new Peer();
            peer = peers.computeIfAbsent(info, i -> new Peer());
        }
        peer.lastSeen = now;
        return peer;
    }

    private static final class Key {
        final PacketManager manager;
        final NetworkInfo info;
        final int identifier;

        Key(PacketManager manager, NetworkInfo info, int identifier) {
            this.manager = manager;
            this.info = info;
            this.identifier = identifier;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return identifier == k.identifier && manager == k.manager && Objects.equals(info, k.info);
        }

        @Override
        public int hashCode() {
            return (31 * System.identityHashCode(manager) + Objects.hashCode(info)) * 31 + identifier;
        }
    }

    static final class Partial {
        final PacketManager manager;
        final int total;
        final BitSet received;
        final long firstFragmentAt;
        int count;
        long lastFragmentAt;
        NetworkInfo replyTo;
        int rounds;
        long lastNackAt;
        boolean awaitingResend;
        boolean closed;

        Partial(PacketManager manager, int total, long now) {
            this.manager = manager;
            this.total = total;
            this.received = new BitSet(total);
            this.firstFragmentAt = now;
            this.lastFragmentAt = now;
        }
    }

    private static final class Finished {
        final boolean completed;
        final long expiresAt;

        Finished(boolean completed, long expiresAt) {
            this.completed = completed;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Peer {
        volatile long rttMs = -1;
        volatile long lastSeen;

        void sampleRtt(long sample) {
            long old = rttMs;
            rttMs = old < 0 ? sample : (old * 7 + sample) / 8;
        }
    }

    private static final class Nack {
        final NetworkInfo to;
        final int identifier;
        final int[] ranges;

        Nack(NetworkInfo to, int identifier, int[] ranges) {
            this.to = to;
            this.identifier = identifier;
            this.ranges = ranges;
        }
    }
}
