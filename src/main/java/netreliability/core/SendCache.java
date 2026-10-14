package netreliability.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.networkInfo.NetworkInfo;
import netreliability.compat.GameInternals;
import netreliability.config.NetConfig;
import netreliability.stats.NetStats;

/**
 * Sending side of NACK recovery: keeps the datagrams of every split packet this process sends for a few seconds, and
 * resends the ones a peer's {@code PacketFragmentNack} asks for, byte for byte (same identifier, so the peer's game
 * merges them into the packet it is still assembling).
 *
 * <p>NACKs are untrusted input. A NACK is only answered for the peer the packet went to, each fragment at most once
 * per NACK, at most {@link #MAX_RESEND_ROUNDS} times per packet, within a per-peer byte budget (a request bigger than
 * the budget gets the first fragments that fit; the next NACK asks for the rest), with at most one resend of a packet
 * queued at a time and at most {@link #MAX_QUEUED_BYTES} queued in total. The resends run on
 * {@link NetThreads#worker()}, not on the receive thread, which holds the game's {@code PacketManager} lock.
 *
 * <p>Split packet identifiers come from one process-wide counter in the game, so the identifier alone finds the entry.
 */
public final class SendCache {

    /** Ignore a repeat NACK for the same packet within this long: the previous resend is still in flight. */
    private static final int MIN_RESEND_GAP_MS = 15;
    /** Hard cap on how often one packet is resent, whatever peers ask. */
    private static final int MAX_RESEND_ROUNDS = 20;
    /** Per-peer resend budget: refills at this many bytes per second, holds at most {@link #BUDGET_BURST}. */
    private static final long BUDGET_BYTES_PER_SECOND = 4L * 1024 * 1024;
    private static final long BUDGET_BURST = 1024L * 1024;
    private static final int MAX_BUDGET_PEERS = 4096;
    /** Budgets untouched for this long are dropped (a full bucket is the same as a new one). */
    private static final int BUDGET_IDLE_MS = 10000;
    /** Resend bytes waiting for the worker, all peers together. */
    private static final long MAX_QUEUED_BYTES = 4L * 1024 * 1024;
    private static final int MAX_QUEUED_JOBS = 256;
    /** Guards the shared admission state: {@link #queuedBytes}, {@link #queuedJobs} and the budgets' tokens. */
    private static final Object admission = new Object();
    private static long queuedBytes;
    private static int queuedJobs;

    private static final Object lock = new Object();
    /** Insertion order is send order, so the head is always the oldest. */
    private static final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    private static long bytes;
    /** Guarded by {@link #admission}. */
    private static final Map<NetworkInfo, Budget> budgets = new HashMap<>();
    private static volatile long lastSendError;

    private SendCache() {
    }

    /** After the game sent a packet ({@code NetworkPacket.sendPacket}). */
    public static void onSent(NetworkPacket packet) {
        if (packet.identifier == 0 || packet.networkInfo == null || !GameInternals.isAvailable()) return;
        put(packet.identifier, packet.networkInfo, GameInternals.fragmentDatagrams(packet), System.currentTimeMillis());
    }

    static void put(int identifier, NetworkInfo destination, byte[][] datagrams, long now) {
        int size = 0;
        for (byte[] d : datagrams) size += d == null ? 0 : d.length;
        NetStats.splitSent.incrementAndGet();
        NetStats.fragmentsSent.addAndGet(datagrams.length);
        NetConfig config = NetConfig.get();
        Entry entry = new Entry(destination, datagrams, size, now + config.sendCacheMs);
        synchronized (lock) {
            Entry old = entries.remove(identifier);
            if (old != null) bytes -= old.size;
            entries.put(identifier, entry);
            bytes += size;
            evict(now, config.sendCacheMaxKB * 1024L);
        }
    }

    /** Drops expired entries and idle budgets; called every frame. */
    public static void tick(long now) {
        synchronized (lock) {
            evict(now, NetConfig.get().sendCacheMaxKB * 1024L);
        }
        if (!budgets.isEmpty()) {
            synchronized (admission) {
                budgets.values().removeIf(b -> b.idleSince(now) > BUDGET_IDLE_MS);
            }
        }
    }

    public static String describe() {
        synchronized (lock) {
            return "Send cache: " + entries.size() + " packets, " + bytes / 1024 + " KB";
        }
    }

    /** A peer ({@code from}) asks for fragments of split packet {@code identifier}. */
    public static void onNack(NetworkInfo from, int identifier, int[] ranges) {
        NetStats.nacksReceived.incrementAndGet();
        Entry entry;
        synchronized (lock) {
            entry = entries.get(identifier);
        }
        if (entry == null) {
            NetStats.nackCacheMisses.incrementAndGet();
            return;
        }
        // Clients too: a UDP client accepts datagrams from any address, so anyone could otherwise make it resend.
        if (!Objects.equals(entry.destination, from)) {
            NetStats.nackWrongPeer.incrementAndGet();
            return;
        }
        long now = System.currentTimeMillis();
        BitSet wanted = requestedIndexes(ranges, entry.datagrams.length);
        List<byte[]> resend = new ArrayList<>();
        long resendBytes = 0;
        // One atomic admission: selection, budget, queue room and the entry's single pending slot.
        synchronized (admission) {
            synchronized (entry) {
                if (entry.queued.get() || now - entry.lastResendAt < MIN_RESEND_GAP_MS || entry.rounds >= MAX_RESEND_ROUNDS) return;
            }
            if (queuedJobs >= MAX_QUEUED_JOBS) {
                NetStats.resendBudgetDenied.incrementAndGet();
                return;
            }
            Budget budget = budgets.get(entry.destination);
            if (budget == null) {
                if (budgets.size() >= MAX_BUDGET_PEERS) {
                    NetStats.resendBudgetDenied.incrementAndGet();
                    return;
                }
                budget = new Budget(now);
                budgets.put(entry.destination, budget);
            }
            // The requested fragments in order, while they fit the peer's budget and the global queue room.
            long room = Math.min(budget.available(now), MAX_QUEUED_BYTES - queuedBytes);
            for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
                byte[] datagram = entry.datagrams[i];
                if (datagram == null) continue;
                if (resendBytes + datagram.length > room) break;
                resend.add(datagram);
                resendBytes += datagram.length;
            }
            if (resend.isEmpty()) {
                if (!wanted.isEmpty()) NetStats.resendBudgetDenied.incrementAndGet();
                return;
            }
            budget.take(resendBytes, now);
            queuedBytes += resendBytes;
            queuedJobs++;
            synchronized (entry) {
                entry.queued.set(true);
                entry.lastResendAt = now;
                entry.rounds++;
            }
        }
        // The job holds only what it sends, not the cache entry, so eviction still frees the rest.
        NetworkInfo destination = entry.destination;
        AtomicBoolean pending = entry.queued;
        long expiresAt = entry.expiresAt;
        long jobBytes = resendBytes;
        Runnable job = () -> {
            try {
                if (System.currentTimeMillis() < expiresAt) send(destination, resend);
            } finally {
                release(jobBytes);
                pending.set(false);
            }
        };
        try {
            NetThreads.worker().execute(job);
        } catch (RejectedExecutionException e) {
            // JVM shutting down
            release(jobBytes);
            pending.set(false);
        }
    }

    private static void release(long jobBytes) {
        synchronized (admission) {
            queuedBytes -= jobBytes;
            queuedJobs--;
        }
    }

    /**
     * The distinct fragment indexes {@code ranges} (pairs of start, length) ask for, clipped to {@code [0, count)}.
     * Overlapping or repeated ranges ask for each fragment once.
     */
    static BitSet requestedIndexes(int[] ranges, int count) {
        BitSet out = new BitSet(count);
        for (int r = 0; r + 1 < ranges.length; r += 2) {
            int start = Math.max(0, ranges[r]);
            int end = Math.min(count, ranges[r] + Math.max(0, ranges[r + 1]));
            if (start < end) out.set(start, end);
        }
        return out;
    }

    private static void send(NetworkInfo destination, List<byte[]> datagrams) {
        for (byte[] datagram : datagrams) {
            try {
                destination.send(datagram);
                NetStats.fragmentsResent.incrementAndGet();
                NetStats.bytesResent.addAndGet(datagram.length);
            } catch (IOException | RuntimeException e) {
                long now = System.currentTimeMillis();
                if (now - lastSendError > 10000) {
                    lastSendError = now;
                    GameLog.warn.println("[NetReliability] could not resend a fragment to " + destination.getDisplayName() + ": " + e);
                }
                return;
            }
        }
    }

    private static void evict(long now, long maxBytes) {
        Iterator<Map.Entry<Integer, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            boolean expired = e.expiresAt <= now;
            if (!expired && bytes <= maxBytes) break;
            if (!expired) NetStats.sendCacheEvictions.incrementAndGet();
            bytes -= e.size;
            it.remove();
        }
    }

    private static final class Entry {
        final NetworkInfo destination;
        final byte[][] datagrams;
        final int size;
        final long expiresAt;
        long lastResendAt;
        int rounds;
        /** A resend of this packet is waiting for the worker: further NACKs are dropped until it went out. */
        final AtomicBoolean queued = new AtomicBoolean();

        Entry(NetworkInfo destination, byte[][] datagrams, int size, long expiresAt) {
            this.destination = destination;
            this.datagrams = datagrams;
            this.size = size;
            this.expiresAt = expiresAt;
        }
    }

    /** Token bucket of resend bytes for one peer. */
    private static final class Budget {
        private long tokens = BUDGET_BURST;
        private long updatedAt;

        Budget(long now) {
            updatedAt = now;
            touchedAt = now;
        }

        private long touchedAt;

        synchronized long available(long now) {
            refill(now);
            return tokens;
        }

        synchronized void take(long amount, long now) {
            refill(now);
            tokens = Math.max(0, tokens - amount);
            touchedAt = now;
        }

        synchronized long idleSince(long now) {
            return now - touchedAt;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - updatedAt);
            updatedAt = now;
            tokens = Math.min(BUDGET_BURST, tokens + elapsed * BUDGET_BYTES_PER_SECOND / 1000);
        }
    }
}
