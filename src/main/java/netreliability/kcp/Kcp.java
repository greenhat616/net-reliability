package netreliability.kcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

/**
 * A Java port of KCP ({@code ikcp.c}, https://github.com/skywind3000/kcp, MIT licence, Copyright (C) 2017 skywind3000):
 * an ARQ protocol over unreliable datagrams with selective ACK, fast resend and a non-doubling RTO. Kept close to the C
 * source so it can be checked against it line by line; names follow the C fields.
 *
 * <p>Ported from upstream commit {@code 7346e366f1928dd727ecbb53253454fdaecaebb7}, with {@code IKCP_FASTACK_CONSERVE}
 * defined as upstream does and without the pluggable congestion control ({@code ccops}, upstream's default path is
 * the one ported). Logging and the segment allocator hooks are left out.
 *
 * <p>Not thread-safe: the caller serialises every call ({@link KcpSession} locks around it). Sequence numbers and
 * timestamps are unsigned 32-bit in the C code; here they are {@code int}s compared with {@link #diff}, which wraps the
 * same way.
 */
public final class Kcp {

    public static final int OVERHEAD = 24;
    private static final int RTO_NDL = 30;
    private static final int RTO_MIN = 100;
    private static final int RTO_DEF = 200;
    private static final int RTO_MAX = 60000;
    private static final int CMD_PUSH = 81;
    private static final int CMD_ACK = 82;
    private static final int CMD_WASK = 83;
    private static final int CMD_WINS = 84;
    private static final int ASK_SEND = 1;
    private static final int ASK_TELL = 2;
    private static final int WND_SND = 32;
    private static final int WND_RCV = 128;
    private static final int MTU_DEF = 1400;
    private static final int INTERVAL = 100;
    private static final int DEADLINK = 20;
    private static final int THRESH_INIT = 2;
    private static final int THRESH_MIN = 2;
    private static final int PROBE_INIT = 7000;
    private static final int PROBE_LIMIT = 120000;
    private static final int FASTACK_LIMIT = 5;

    /** Sends one encoded datagram ({@code buffer[0..length)}); must copy if it keeps the bytes. */
    public interface Output {
        void output(byte[] buffer, int length);
    }

    private static final class Segment {
        int conv, cmd, frg, wnd, ts, sn, una;
        int resendts, rto, fastack, xmit;
        final byte[] data;

        Segment(int size) {
            data = new byte[size];
        }
    }

    private final int conv;
    private final Output output;
    private int mtu = MTU_DEF, mss = MTU_DEF - OVERHEAD;
    private int state;
    private int sndUna, sndNxt, rcvNxt;
    private int ssthresh = THRESH_INIT;
    private int rxRttval, rxSrtt, rxRto = RTO_DEF, rxMinrto = RTO_MIN;
    private int sndWnd = WND_SND, rcvWnd = WND_RCV, rmtWnd = WND_RCV, cwnd, probe;
    private int current, interval = INTERVAL, tsFlush = INTERVAL;
    private int xmit;
    private int nodelay;
    private boolean updated;
    private int tsProbe, probeWait;
    private int deadLink = DEADLINK, incr;
    private final LinkedList<Segment> sndQueue = new LinkedList<>();
    private final LinkedList<Segment> rcvQueue = new LinkedList<>();
    private final LinkedList<Segment> sndBuf = new LinkedList<>();
    private final LinkedList<Segment> rcvBuf = new LinkedList<>();
    /** Pairs of (sn, ts) to acknowledge on the next flush. */
    private final ArrayList<int[]> ackList = new ArrayList<>();
    private byte[] buffer = new byte[(MTU_DEF + OVERHEAD) * 3];
    private int fastresend;
    private int fastlimit = FASTACK_LIMIT;
    private boolean nocwnd;
    private boolean stream;

    public Kcp(int conv, Output output) {
        this.conv = conv;
        this.output = output;
    }

    static int diff(int later, int earlier) {
        return later - earlier;
    }

    /** {@code ikcp_getconv}: the conversation id of an encoded datagram, or 0 if it is too short. */
    public static int getConv(byte[] data, int offset, int length) {
        return length < 4 ? 0 : readInt(data, offset);
    }

    public int conv() {
        return conv;
    }

    /** -1 once a segment was resent {@code deadLink} times without an ACK. */
    public int state() {
        return state;
    }

    public int srtt() {
        return rxSrtt;
    }

    public int totalResends() {
        return xmit;
    }

    /** Segments queued or in flight, not yet acknowledged. */
    /** Not in ikcp.c: drops every queued segment, for a session that will never be used again. */
    public void release() {
        sndQueue.clear();
        sndBuf.clear();
        rcvQueue.clear();
        rcvBuf.clear();
        ackList.clear();
    }

    /** Oldest unacknowledged sequence number; advances as the peer acknowledges. */
    public int sndUna() {
        return sndUna;
    }

    public int waitSnd() {
        return sndBuf.size() + sndQueue.size();
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    /** {@code ikcp_peeksize}: size of the next message, or -1 if none is complete. */
    public int peekSize() {
        if (rcvQueue.isEmpty()) return -1;
        Segment seg = rcvQueue.getFirst();
        if (seg.frg == 0) return seg.data.length;
        if (rcvQueue.size() < seg.frg + 1) return -1;
        int length = 0;
        for (Segment s : rcvQueue) {
            length += s.data.length;
            if (s.frg == 0) break;
        }
        return length;
    }

    /** {@code ikcp_recv}: the next message, or null if none is complete. */
    public byte[] recv() {
        int peek = peekSize();
        if (peek < 0) return null;
        boolean recover = rcvQueue.size() >= rcvWnd;
        byte[] out = new byte[peek];
        int n = 0;
        Iterator<Segment> it = rcvQueue.iterator();
        while (it.hasNext()) {
            Segment seg = it.next();
            System.arraycopy(seg.data, 0, out, n, seg.data.length);
            n += seg.data.length;
            int fragment = seg.frg;
            it.remove();
            if (fragment == 0) break;
        }
        moveRcvBufToQueue();
        if (rcvQueue.size() < rcvWnd && recover) probe |= ASK_TELL;
        return out;
    }

    /** {@code ikcp_send}: queues {@code data[offset..offset+length)}. Returns 0, or a negative error. */
    public int send(byte[] data, int offset, int length) {
        if (length < 0) return -1;
        if (stream && !sndQueue.isEmpty()) {
            Segment old = sndQueue.getLast();
            if (old.data.length < mss) {
                int extend = Math.min(length, mss - old.data.length);
                Segment seg = new Segment(old.data.length + extend);
                System.arraycopy(old.data, 0, seg.data, 0, old.data.length);
                System.arraycopy(data, offset, seg.data, old.data.length, extend);
                sndQueue.removeLast();
                sndQueue.addLast(seg);
                offset += extend;
                length -= extend;
            }
            if (length <= 0) return 0;
        }
        int count = length <= mss ? 1 : (length + mss - 1) / mss;
        if (count >= WND_RCV) return -2;
        if (count == 0) count = 1;
        for (int i = 0; i < count; i++) {
            int size = Math.min(length, mss);
            Segment seg = new Segment(size);
            System.arraycopy(data, offset, seg.data, 0, size);
            seg.frg = stream ? 0 : count - i - 1;
            sndQueue.addLast(seg);
            offset += size;
            length -= size;
        }
        return 0;
    }

    private void updateAck(int rtt) {
        if (rxSrtt == 0) {
            rxSrtt = rtt;
            rxRttval = rtt / 2;
        } else {
            int delta = Math.abs(rtt - rxSrtt);
            rxRttval = (3 * rxRttval + delta) / 4;
            rxSrtt = (7 * rxSrtt + rtt) / 8;
            if (rxSrtt < 1) rxSrtt = 1;
        }
        int rto = rxSrtt + Math.max(interval, 4 * rxRttval);
        rxRto = Math.max(rxMinrto, Math.min(rto, RTO_MAX));
    }

    private void shrinkBuf() {
        sndUna = sndBuf.isEmpty() ? sndNxt : sndBuf.getFirst().sn;
    }

    private void parseAck(int sn) {
        if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return;
        Iterator<Segment> it = sndBuf.iterator();
        while (it.hasNext()) {
            Segment seg = it.next();
            if (sn == seg.sn) {
                it.remove();
                break;
            }
            if (diff(sn, seg.sn) < 0) break;
        }
    }

    private void parseUna(int una) {
        Iterator<Segment> it = sndBuf.iterator();
        while (it.hasNext()) {
            Segment seg = it.next();
            if (diff(una, seg.sn) > 0) it.remove();
            else break;
        }
    }

    /** {@code ikcp_parse_fastack} with {@code IKCP_FASTACK_CONSERVE}: an ACK older than a segment's last send does not count. */
    private void parseFastack(int sn, int ts) {
        if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return;
        for (Segment seg : sndBuf) {
            if (diff(sn, seg.sn) < 0) break;
            if (sn != seg.sn && diff(ts, seg.ts) >= 0) seg.fastack++;
        }
    }

    private void parseData(Segment newseg) {
        int sn = newseg.sn;
        if (diff(sn, rcvNxt + rcvWnd) >= 0 || diff(sn, rcvNxt) < 0) return;
        boolean repeat = false;
        ListIterator<Segment> it = rcvBuf.listIterator(rcvBuf.size());
        while (it.hasPrevious()) {
            Segment seg = it.previous();
            if (seg.sn == sn) {
                repeat = true;
                break;
            }
            if (diff(sn, seg.sn) > 0) {
                it.next();
                break;
            }
        }
        if (!repeat) it.add(newseg);
        moveRcvBufToQueue();
    }

    private void moveRcvBufToQueue() {
        while (!rcvBuf.isEmpty()) {
            Segment seg = rcvBuf.getFirst();
            if (seg.sn == rcvNxt && rcvQueue.size() < rcvWnd) {
                rcvBuf.removeFirst();
                rcvQueue.addLast(seg);
                rcvNxt++;
            } else {
                break;
            }
        }
    }

    /** {@code ikcp_input}: one datagram from the peer. Returns 0, or a negative error (wrong conv, malformed). */
    public int input(byte[] data, int offset, int size) {
        int prevUna = sndUna;
        int maxack = 0, latestTs = 0;
        boolean flag = false;
        if (size < OVERHEAD) return -1;
        while (size >= OVERHEAD) {
            int segConv = readInt(data, offset);
            int cmd = data[offset + 4] & 0xff;
            int frg = data[offset + 5] & 0xff;
            int wnd = readShort(data, offset + 6);
            int ts = readInt(data, offset + 8);
            int sn = readInt(data, offset + 12);
            int una = readInt(data, offset + 16);
            int len = readInt(data, offset + 20);
            offset += OVERHEAD;
            size -= OVERHEAD;
            if (segConv != conv) return -1;
            if (len < 0 || size < len) return -2;
            if (cmd != CMD_PUSH && cmd != CMD_ACK && cmd != CMD_WASK && cmd != CMD_WINS) return -3;
            rmtWnd = wnd;
            parseUna(una);
            shrinkBuf();
            if (cmd == CMD_ACK) {
                if (diff(current, ts) >= 0) updateAck(diff(current, ts));
                parseAck(sn);
                shrinkBuf();
                if (!flag) {
                    flag = true;
                    maxack = sn;
                    latestTs = ts;
                } else if (diff(sn, maxack) > 0 && diff(ts, latestTs) > 0) {
                    // IKCP_FASTACK_CONSERVE
                    maxack = sn;
                    latestTs = ts;
                }
            } else if (cmd == CMD_PUSH) {
                if (diff(sn, rcvNxt + rcvWnd) < 0) {
                    ackList.add(new int[] {sn, ts});
                    if (diff(sn, rcvNxt) >= 0) {
                        Segment seg = new Segment(len);
                        seg.conv = segConv;
                        seg.cmd = cmd;
                        seg.frg = frg;
                        seg.wnd = wnd;
                        seg.ts = ts;
                        seg.sn = sn;
                        seg.una = una;
                        System.arraycopy(data, offset, seg.data, 0, len);
                        parseData(seg);
                    }
                }
            } else if (cmd == CMD_WASK) {
                probe |= ASK_TELL;
            }
            offset += len;
            size -= len;
        }
        if (flag) parseFastack(maxack, latestTs);
        if (diff(sndUna, prevUna) > 0 && cwnd < rmtWnd) {
            if (cwnd < ssthresh) {
                cwnd++;
                incr += mss;
            } else {
                if (incr < mss) incr = mss;
                incr += (mss * mss) / incr + (mss / 16);
                if ((cwnd + 1) * mss <= incr) cwnd = (incr + mss - 1) / (mss > 0 ? mss : 1);
            }
            if (cwnd > rmtWnd) {
                cwnd = rmtWnd;
                incr = rmtWnd * mss;
            }
        }
        return 0;
    }

    private int wndUnused() {
        return rcvQueue.size() < rcvWnd ? rcvWnd - rcvQueue.size() : 0;
    }

    private int encode(int ptr, Segment seg, int cmd, int sn, int ts, int wnd, int una, int len) {
        writeInt(buffer, ptr, conv);
        buffer[ptr + 4] = (byte) cmd;
        buffer[ptr + 5] = (byte) seg.frg;
        writeShort(buffer, ptr + 6, wnd);
        writeInt(buffer, ptr + 8, ts);
        writeInt(buffer, ptr + 12, sn);
        writeInt(buffer, ptr + 16, una);
        writeInt(buffer, ptr + 20, len);
        return ptr + OVERHEAD;
    }

    private static final Segment NO_FRAGMENT = new Segment(0);

    /** {@code ikcp_flush}: sends pending ACKs, window probes and due segments. */
    public void flush() {
        if (!updated) return;
        int wnd = wndUnused();
        int ptr = 0;
        for (int[] ack : ackList) {
            if (ptr + OVERHEAD > mtu) {
                output.output(buffer, ptr);
                ptr = 0;
            }
            ptr = encode(ptr, NO_FRAGMENT, CMD_ACK, ack[0], ack[1], wnd, rcvNxt, 0);
        }
        ackList.clear();

        if (rmtWnd == 0) {
            if (probeWait == 0) {
                probeWait = PROBE_INIT;
                tsProbe = current + probeWait;
            } else if (diff(current, tsProbe) >= 0) {
                if (probeWait < PROBE_INIT) probeWait = PROBE_INIT;
                probeWait += probeWait / 2;
                if (probeWait > PROBE_LIMIT) probeWait = PROBE_LIMIT;
                tsProbe = current + probeWait;
                probe |= ASK_SEND;
            }
        } else {
            tsProbe = 0;
            probeWait = 0;
        }
        if ((probe & ASK_SEND) != 0) {
            if (ptr + OVERHEAD > mtu) {
                output.output(buffer, ptr);
                ptr = 0;
            }
            ptr = encode(ptr, NO_FRAGMENT, CMD_WASK, 0, 0, wnd, rcvNxt, 0);
        }
        if ((probe & ASK_TELL) != 0) {
            if (ptr + OVERHEAD > mtu) {
                output.output(buffer, ptr);
                ptr = 0;
            }
            ptr = encode(ptr, NO_FRAGMENT, CMD_WINS, 0, 0, wnd, rcvNxt, 0);
        }
        probe = 0;

        int priorCwnd = cwnd;
        int window = Math.min(sndWnd, rmtWnd);
        if (!nocwnd) window = Math.min(cwnd, window);
        while (diff(sndNxt, sndUna + window) < 0 && !sndQueue.isEmpty()) {
            Segment seg = sndQueue.removeFirst();
            seg.conv = conv;
            seg.cmd = CMD_PUSH;
            seg.wnd = wnd;
            seg.ts = current;
            seg.sn = sndNxt++;
            seg.una = rcvNxt;
            seg.resendts = current;
            seg.rto = rxRto;
            seg.fastack = 0;
            seg.xmit = 0;
            sndBuf.addLast(seg);
        }

        int resent = fastresend > 0 ? fastresend : Integer.MAX_VALUE;
        int rtomin = nodelay == 0 ? rxRto >> 3 : 0;
        boolean change = false, lost = false;
        for (Segment seg : sndBuf) {
            boolean needsend = false;
            if (seg.xmit == 0) {
                needsend = true;
                seg.xmit++;
                seg.rto = rxRto;
                seg.resendts = current + seg.rto + rtomin;
            } else if (diff(current, seg.resendts) >= 0) {
                needsend = true;
                seg.xmit++;
                xmit++;
                if (nodelay == 0) {
                    seg.rto += Math.max(seg.rto, rxRto);
                } else {
                    int step = nodelay < 2 ? seg.rto : rxRto;
                    seg.rto += step / 2;
                }
                seg.resendts = current + seg.rto;
                lost = true;
            } else if (seg.fastack >= resent) {
                if (seg.xmit <= fastlimit || fastlimit <= 0) {
                    needsend = true;
                    seg.xmit++;
                    seg.fastack = 0;
                    seg.resendts = current + seg.rto;
                    change = true;
                }
            }
            if (needsend) {
                seg.ts = current;
                seg.wnd = wnd;
                seg.una = rcvNxt;
                int need = OVERHEAD + seg.data.length;
                if (ptr + need > mtu) {
                    output.output(buffer, ptr);
                    ptr = 0;
                }
                ptr = encode(ptr, seg, CMD_PUSH, seg.sn, seg.ts, seg.wnd, seg.una, seg.data.length);
                System.arraycopy(seg.data, 0, buffer, ptr, seg.data.length);
                ptr += seg.data.length;
                if (seg.xmit >= deadLink) state = -1;
            }
        }
        if (ptr > 0) output.output(buffer, ptr);

        if (change) {
            int inflight = sndNxt - sndUna;
            ssthresh = Math.max(inflight / 2, THRESH_MIN);
            cwnd = ssthresh + resent;
            incr = cwnd * mss;
        }
        if (lost) {
            ssthresh = Math.max(priorCwnd / 2, THRESH_MIN);
            cwnd = 1;
            incr = mss;
        }
        if (cwnd < 1) {
            cwnd = 1;
            incr = mss;
        }
    }

    /**
     * Sets the clock used by the next {@link #input} (RTT samples) and {@link #flush}. {@link #update} sets it too;
     * calling this first lets the caller react to input at once instead of on the next update tick.
     */
    public void setCurrent(int now) {
        current = now;
    }

    /** {@code ikcp_update}: call every {@code interval} ms or so with the current time in ms. */
    public void update(int now) {
        current = now;
        if (!updated) {
            updated = true;
            tsFlush = current;
        }
        int slap = diff(current, tsFlush);
        if (slap >= 10000 || slap < -10000) {
            tsFlush = current;
            slap = 0;
        }
        if (slap >= 0) {
            tsFlush += interval;
            if (diff(current, tsFlush) >= 0) tsFlush = current + interval;
            flush();
        }
    }

    /** {@code ikcp_setmtu}. */
    public void setMtu(int mtu) {
        if (mtu < 50) throw new IllegalArgumentException("mtu " + mtu);
        this.buffer = new byte[(mtu + OVERHEAD) * 3];
        this.mtu = mtu;
        this.mss = mtu - OVERHEAD;
    }

    /** {@code ikcp_wndsize}. */
    public void setWindow(int snd, int rcv) {
        if (snd > 0) sndWnd = snd;
        if (rcv > 0) rcvWnd = Math.max(rcv, WND_RCV);
    }

    /** {@code ikcp_nodelay}: (1, 10, 2, true) is the usual "fast" mode. */
    public void setNoDelay(int nodelay, int interval, int resend, boolean nocwnd) {
        if (nodelay >= 0) {
            this.nodelay = nodelay;
            rxMinrto = nodelay != 0 ? RTO_NDL : RTO_MIN;
        }
        if (interval >= 0) this.interval = Math.max(10, Math.min(5000, interval));
        if (resend >= 0) fastresend = resend;
        this.nocwnd = nocwnd;
    }

    private static int readInt(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static int readShort(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static void writeInt(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void writeShort(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }
}
