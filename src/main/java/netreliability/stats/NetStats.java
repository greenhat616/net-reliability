package netreliability.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters of this mod (a host's server and client share them). Receiving side counts split packets that
 * arrive here; sending side counts NACKs this process answers. {@link #COLUMNS} is the CSV header order.
 */
public final class NetStats {

    // Receiving
    public static final AtomicLong splitStarted = new AtomicLong();
    public static final AtomicLong splitCompleted = new AtomicLong();
    /** Completed with no NACK: every fragment arrived the first time. */
    public static final AtomicLong splitCleanCompleted = new AtomicLong();
    /**
     * Completed after at least one NACK. Usually thanks to the resend, but a delayed original can also arrive after the
     * NACK, so this is an upper bound on recoveries.
     */
    public static final AtomicLong splitRecovered = new AtomicLong();
    public static final AtomicLong splitGivenUp = new AtomicLong();
    public static final AtomicLong fragmentsReceived = new AtomicLong();
    /** Fragments of a packet that had already completed (late or duplicate resends); dropped before the game sees them. */
    public static final AtomicLong fragmentsLate = new AtomicLong();
    public static final AtomicLong nacksSent = new AtomicLong();
    public static final AtomicLong fragmentsRequested = new AtomicLong();
    public static final AtomicLong maxFragmentsPerPacket = new AtomicLong();
    /** Split packets not tracked because too many were already incomplete (flood protection); the game still gets them. */
    public static final AtomicLong trackingOverflow = new AtomicLong();
    // Sending
    public static final AtomicLong splitSent = new AtomicLong();
    public static final AtomicLong fragmentsSent = new AtomicLong();
    public static final AtomicLong nacksReceived = new AtomicLong();
    public static final AtomicLong fragmentsResent = new AtomicLong();
    public static final AtomicLong bytesResent = new AtomicLong();
    /** NACKs for a packet no longer (or never) in the send cache. */
    public static final AtomicLong nackCacheMisses = new AtomicLong();
    /** NACKs from an address the packet was not sent to; ignored. */
    public static final AtomicLong nackWrongPeer = new AtomicLong();
    public static final AtomicLong sendCacheEvictions = new AtomicLong();
    /** NACKs not answered because the peer used up its resend byte budget. */
    public static final AtomicLong resendBudgetDenied = new AtomicLong();
    // Testing
    public static final AtomicLong simulatedDrops = new AtomicLong();

    private static final AtomicLong[] ALL = {splitStarted, splitCompleted, splitCleanCompleted, splitRecovered, splitGivenUp,
        fragmentsReceived, fragmentsLate, nacksSent, fragmentsRequested, maxFragmentsPerPacket, trackingOverflow, splitSent, fragmentsSent,
        nacksReceived, fragmentsResent, bytesResent, nackCacheMisses, nackWrongPeer, sendCacheEvictions, resendBudgetDenied,
        simulatedDrops};

    public static final String COLUMNS = "splitStarted,splitCompleted,splitClean,splitRecovered,splitGivenUp,fragmentsReceived,"
        + "fragmentsLate,nacksSent,fragmentsRequested,maxFragments,trackingOverflow,splitSent,fragmentsSent,nacksReceived,"
        + "fragmentsResent,bytesResent,nackCacheMisses,nackWrongPeer,sendCacheEvictions,resendBudgetDenied,simulatedDrops";

    private NetStats() {
    }

    public static void reset() {
        for (AtomicLong v : ALL) v.set(0);
    }

    public static void recordFragmentCount(int count) {
        maxFragmentsPerPacket.accumulateAndGet(count, Math::max);
    }

    public static String csvRow() {
        StringBuilder s = new StringBuilder();
        for (AtomicLong v : ALL) {
            if (s.length() > 0) s.append(',');
            s.append(v.get());
        }
        return s.toString();
    }

    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        long started = splitStarted.get();
        out.add("Receiving split packets: " + started + " started, " + splitCompleted.get() + " completed ("
            + splitCleanCompleted.get() + " clean, " + splitRecovered.get() + " after NACK), " + splitGivenUp.get()
            + " given up; largest " + maxFragmentsPerPacket.get() + " fragments"
            + (trackingOverflow.get() > 0 ? "; " + trackingOverflow.get() + " untracked (too many incomplete)" : ""));
        out.add("  fragments " + fragmentsReceived.get() + " received, " + fragmentsLate.get() + " late/duplicate; NACKs sent "
            + nacksSent.get() + " asking for " + fragmentsRequested.get() + " fragments");
        out.add("Sending split packets: " + splitSent.get() + " (" + fragmentsSent.get() + " fragments); NACKs answered "
            + nacksReceived.get() + ", resent " + fragmentsResent.get() + " fragments (" + bytesResent.get() / 1024 + " KB), "
            + nackCacheMisses.get() + " cache misses, " + nackWrongPeer.get() + " wrong peer, " + resendBudgetDenied.get()
            + " over budget, " + sendCacheEvictions.get() + " evicted");
        if (simulatedDrops.get() > 0) out.add("Simulated drops: " + simulatedDrops.get());
        return out;
    }
}
