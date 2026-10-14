package netreliability.kcp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;
import netreliability.compat.GameInternals;
import netreliability.compat.WireFormat;
import netreliability.config.NetConfig;
import netreliability.stats.NetStats;

/**
 * One KCP conversation with one peer over one UDP socket. The KCP stream carries the game's datagrams framed as
 * {@code u16 length + bytes}.
 *
 * <p>Locking: every method locks the session and takes no other lock while holding it (delivery to the game happens
 * after {@link #input} returns), so sessions cannot deadlock with the game or each other.
 *
 * <p>Lifecycle: ACTIVE until {@link #fail} (dead link, no progress, queue overflow, malformed stream, idle). A failed
 * session releases its buffers but stays registered as a tombstone ({@link KcpTransport}): sends to the peer are
 * dropped rather than going out as plain UDP the peer no longer expects, so the game's own timeout ends the
 * connection.
 */
final class KcpSession {

    /** Unacknowledged plus queued segments before the session gives up (about 2 MB). */
    static final int MAX_WAIT_SEGMENTS = 2048;
    /** Unacknowledged data with no ACK progress for this long: the peer is gone or stuck. */
    static final int NO_PROGRESS_MS = 20000;
    /** Fragments of split packets held back until the packet is complete: bytes, packets, and age. */
    static final int MAX_HELD_BYTES = 4 * 1024 * 1024;
    static final int MAX_HELD_PACKETS = 512;
    static final int HELD_MAX_AGE_MS = 60000;
    /** Largest split packet held (8 MB of content); more fragments than this is not a game packet. */
    static final int MAX_FRAGMENTS = 8192;
    /** After the game connection ended: keep flushing queued data (the disconnect packet) at most this long. */
    static final int CLOSE_LINGER_MS = 3000;
    /** Accounting per held fragment on top of its bytes (map entry, array header). */
    private static final int HELD_OVERHEAD = 64;

    final DatagramSocket socket;
    final InetAddress address;
    final int port;
    final boolean initiator;
    private final Kcp kcp;
    private final byte[] carrierHeader;
    private byte[] pending = new byte[256];
    private int pendingLength;
    private boolean failed;
    private boolean closing;
    private long closingSince;
    private String failReason;
    private long failedAt;
    private long lastValidInputAt;
    private int lastUna;
    private long lastProgressAt;
    private String lastSendError;
    private List<byte[]> admissionOutput;
    /** Split packets being held: identifier -> raw fragment datagrams so far. Insertion order = age order. */
    private final LinkedHashMap<Integer, Held> held = new LinkedHashMap<>();
    private int heldBytes;

    KcpSession(DatagramSocket socket, InetAddress address, int port, int conv, boolean initiator, byte[] carrierHeader, long now) {
        this.socket = socket;
        this.address = address;
        this.port = port;
        this.initiator = initiator;
        this.carrierHeader = carrierHeader;
        this.lastValidInputAt = now;
        this.lastProgressAt = now;
        NetConfig c = NetConfig.get();
        this.kcp = new Kcp(conv, this::output);
        kcp.setStream(true);
        kcp.setMtu(WireFormat.MAX_DATAGRAM - carrierHeader.length);
        kcp.setWindow(c.kcpWindow, c.kcpWindow);
        kcp.setNoDelay(1, c.kcpIntervalMs, c.kcpFastResend, true);
    }

    int conv() {
        return kcp.conv();
    }

    synchronized boolean isFailed() {
        return failed;
    }

    synchronized long failedAt() {
        return failedAt;
    }

    synchronized long lastValidInputAt() {
        return lastValidInputAt;
    }

    /** Queues one game datagram; dropped if the session failed. */
    synchronized void send(byte[] datagram) {
        if (failed) {
            NetStats.kcpDroppedSends.incrementAndGet();
            return;
        }
        if (datagram.length > WireFormat.MAX_DATAGRAM) {
            // The game never sends more (its receivers would cut it); refuse rather than carry it.
            NetStats.kcpDroppedSends.incrementAndGet();
            return;
        }
        if (kcp.waitSnd() >= MAX_WAIT_SEGMENTS) {
            fail("send queue full (" + kcp.waitSnd() + " segments)", System.currentTimeMillis());
            NetStats.kcpDroppedSends.incrementAndGet();
            return;
        }
        byte[] frame = new byte[datagram.length + 2];
        frame[0] = (byte) (datagram.length >>> 8);
        frame[1] = (byte) datagram.length;
        System.arraycopy(datagram, 0, frame, 2, datagram.length);
        kcp.send(frame, 0, frame.length);
    }

    /**
     * Feeds one KCP datagram from the peer. Returns the game datagrams that became complete, in order, or null if the
     * datagram was not valid KCP for this conversation (the session is left as it was) or the session failed.
     */
    synchronized List<byte[]> input(byte[] data, long now) {
        if (failed) return null;
        kcp.setCurrent((int) now);
        if (kcp.input(data, 0, data.length) < 0) return null;
        lastValidInputAt = now;
        if (closing) {
            // The game connection is over: keep acknowledging and resending, but hand nothing more to the game
            // (a new game connection needs a new conversation).
            while (kcp.recv() != null) {
                // discard
            }
            kcp.flush();
            return Collections.emptyList();
        }
        List<byte[]> out = Collections.emptyList();
        for (byte[] chunk = kcp.recv(); chunk != null; chunk = kcp.recv()) {
            append(chunk);
            int offset = 0;
            while (pendingLength - offset >= 2) {
                int length = (pending[offset] & 0xff) << 8 | pending[offset + 1] & 0xff;
                if (length > WireFormat.MAX_DATAGRAM) {
                    // The stream is corrupt (or hostile): nothing after this point can be framed.
                    fail("bad frame length " + length, now);
                    return out;
                }
                if (pendingLength - offset - 2 < length) break;
                byte[] datagram = new byte[length];
                System.arraycopy(pending, offset + 2, datagram, 0, length);
                if (out.isEmpty()) out = new ArrayList<>();
                out.add(datagram);
                offset += 2 + length;
            }
            if (offset > 0) {
                System.arraycopy(pending, offset, pending, 0, pendingLength - offset);
                pendingLength -= offset;
            }
        }
        // Send the ACKs now instead of on the next update: the sender's RTT and fast resend depend on them.
        kcp.flush();
        return out;
    }

    /**
     * The first datagram of a server session, before it is published: true if it was valid KCP and did not fail the
     * session.
     */
    synchronized boolean admissionInput(byte[] data, long now) {
        List<byte[]> out = input(data, now);
        if (out == null || failed) return false;
        admissionOutput = out;
        return true;
    }

    /** What {@link #admissionInput} produced, once. */
    synchronized List<byte[]> takeAdmissionInput() {
        List<byte[]> out = admissionOutput;
        admissionOutput = null;
        return out == null ? Collections.<byte[]>emptyList() : out;
    }

    /**
     * Holds back fragments of split packets until all of them are here, then returns their raw datagrams together:
     * the game times out a split packet 5 s after its last fragment, and KCP can stall longer than that behind a lost
     * segment after already acknowledging the first fragments (which no one could resend). Returns null for a packet
     * that is not split: deliver it as it is.
     *
     * <p>Fragments are kept and returned as raw datagrams: a parsed {@code NetworkPacket} holds an array sized by the
     * fragment count its header claims, so the caller parses them one at a time while submitting.
     */
    synchronized List<byte[]> assemble(NetworkPacket packet, byte[] datagram, long now) {
        // Split or not by the header's fragment count, not the identifier: a split header with identifier 0 parses
        // fine but can never merge, and would sit in the game's incomplete list.
        if (packet.isComplete()) return null;
        if (failed || !GameInternals.isAvailable()) return Collections.emptyList();
        if (packet.identifier == 0) {
            NetStats.kcpBadInput.incrementAndGet();
            return Collections.emptyList();
        }
        int total = GameInternals.fragmentCount(packet);
        int index = GameInternals.fragmentIndex(packet);
        if (total > MAX_FRAGMENTS) {
            fail("split packet with " + total + " fragments", now);
            return Collections.emptyList();
        }
        Held h = held.get(packet.identifier);
        if (h == null) {
            if (held.size() >= MAX_HELD_PACKETS) {
                fail("too many incomplete split packets (" + held.size() + ")", now);
                return Collections.emptyList();
            }
            h = new Held(total, now);
            held.put(packet.identifier, h);
        }
        if (total != h.total || index < 0 || index >= total) {
            fail("inconsistent fragments of split packet " + packet.identifier + " (" + index + "/" + total + " vs " + h.total + ")", now);
            return Collections.emptyList();
        }
        if (h.fragments.containsKey(index)) return Collections.emptyList();
        int cost = datagram.length + HELD_OVERHEAD;
        if (heldBytes + cost > MAX_HELD_BYTES) {
            fail("too many held fragments (" + heldBytes / 1024 + " KB)", now);
            return Collections.emptyList();
        }
        h.fragments.put(index, datagram);
        h.bytes += cost;
        heldBytes += cost;
        if (h.fragments.size() < h.total) return Collections.emptyList();
        held.remove(packet.identifier);
        heldBytes -= h.bytes;
        return new ArrayList<>(h.fragments.values()); // index order: fragment 0 first
    }

    /** The game connection ended: keep flushing what is queued (the disconnect packet) for a moment, then go. */
    synchronized void beginClose(long now) {
        if (closing) return;
        closing = true;
        closingSince = now;
    }

    /** True once a closing session has delivered its queue or lingered long enough. */
    synchronized boolean isClosed(long now) {
        return closing && (failed || kcp.waitSnd() == 0 || now - closingSince > CLOSE_LINGER_MS);
    }

    /** Waits up to {@code maxMs} for queued data to be acknowledged (client disconnect, before its socket closes). */
    void drain(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (true) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (failed || kcp.waitSnd() == 0 || now >= end) return;
                kcp.update((int) now);
                kcp.flush();
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The update tick: KCP timers, then every failure check, atomically. {@code idleTimeoutMs} > 0 fails the session
     * after that long without valid input (server side).
     */
    synchronized void update(long now, int idleTimeoutMs) {
        if (failed) return;
        if (idleTimeoutMs > 0 && now - lastValidInputAt > idleTimeoutMs) {
            fail("no data from the peer for " + idleTimeoutMs / 1000 + " s", now);
            return;
        }
        expireHeld(now);
        kcp.update((int) now);
        if (kcp.state() < 0) {
            fail("dead link (a segment was resent too often)", now);
            return;
        }
        if (kcp.waitSnd() == 0 || kcp.sndUna() != lastUna) {
            lastUna = kcp.sndUna();
            lastProgressAt = now;
        } else if (now - lastProgressAt > NO_PROGRESS_MS) {
            fail("no ACK progress for " + (now - lastProgressAt) / 1000 + " s", now);
        }
    }

    synchronized void fail(String reason, long now) {
        if (failed) return;
        failed = true;
        failReason = reason;
        failedAt = now;
        held.clear();
        heldBytes = 0;
        kcp.release();
        pending = new byte[0];
        pendingLength = 0;
        admissionOutput = null;
        NetStats.kcpFailures.incrementAndGet();
        GameLog.warn.println("[NetReliability] KCP session " + endpoint() + " failed: " + reason
            + "; the game connection will time out");
    }

    synchronized int heldPackets() {
        return held.size();
    }

    synchronized String describe() {
        return endpoint() + (failed ? " FAILED (" + failReason + ")" : "") + ", srtt " + kcp.srtt() + " ms, unacked "
            + kcp.waitSnd() + ", resends " + kcp.totalResends() + ", held " + held.size() + " packets / " + heldBytes / 1024 + " KB"
            + (lastSendError == null ? "" : ", last send error " + lastSendError);
    }

    private String endpoint() {
        return (initiator ? "to " : "from ") + address.getHostAddress() + ":" + port + " conv " + Integer.toHexString(kcp.conv());
    }

    private void expireHeld(long now) {
        Iterator<Map.Entry<Integer, Held>> it = held.entrySet().iterator();
        while (it.hasNext()) {
            Held h = it.next().getValue();
            if (now - h.firstAt <= HELD_MAX_AGE_MS) break;
            heldBytes -= h.bytes;
            it.remove();
        }
    }

    private void append(byte[] chunk) {
        if (pending.length < pendingLength + chunk.length) {
            byte[] bigger = new byte[Math.max(pending.length * 2, pendingLength + chunk.length)];
            System.arraycopy(pending, 0, bigger, 0, pendingLength);
            pending = bigger;
        }
        System.arraycopy(chunk, 0, pending, pendingLength, chunk.length);
        pendingLength += chunk.length;
    }

    /** KCP output (called with the session locked): wrap in the carrier packet header and send on the game's socket. */
    private void output(byte[] buffer, int length) {
        byte[] datagram = new byte[carrierHeader.length + length];
        System.arraycopy(carrierHeader, 0, datagram, 0, carrierHeader.length);
        System.arraycopy(buffer, 0, datagram, carrierHeader.length, length);
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, address, port));
            NetStats.kcpDatagramsOut.incrementAndGet();
        } catch (IOException e) {
            lastSendError = e.toString();
        }
    }

    private static final class Held {
        final int total;
        final long firstAt;
        /** Fragment index -> raw datagram, in index order (the game merges in any order; this keeps it tidy). */
        final TreeMap<Integer, byte[]> fragments = new TreeMap<>();
        int bytes;

        Held(int total, long now) {
            this.total = total;
            this.firstAt = now;
        }
    }
}
