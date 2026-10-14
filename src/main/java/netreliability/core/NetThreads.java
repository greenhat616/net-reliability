package netreliability.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** The mod's own background thread (daemon, started on first use). */
public final class NetThreads {

    private static final AtomicReference<ScheduledExecutorService> worker = new AtomicReference<>();

    private NetThreads() {
    }

    /** Single thread: resends, and (when used) the KCP update loop. Tasks must catch their own exceptions. */
    public static ScheduledExecutorService worker() {
        ScheduledExecutorService w = worker.get();
        if (w != null) return w;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetReliability worker");
            t.setDaemon(true);
            return t;
        });
        if (worker.compareAndSet(null, created)) return created;
        created.shutdown();
        return worker.get();
    }
}
