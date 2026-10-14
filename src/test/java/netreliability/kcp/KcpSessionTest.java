package netreliability.kcp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** {@link KcpSession} framing and limits, fed by a plain {@link Kcp} peer. */
public class KcpSessionTest {

    private static final int CONV = 0x5151;
    private DatagramSocket socket;
    private KcpSession session;
    private final List<byte[]> peerOutput = new ArrayList<>();
    private Kcp peer;

    @Before
    public void setUp() throws Exception {
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        // Outgoing KCP from the session goes to the discard port; nobody answers.
        session = new KcpSession(socket, InetAddress.getLoopbackAddress(), 9, CONV, false, new byte[] {1, 2, 3}, 0);
        peer = new Kcp(CONV, (buf, len) -> peerOutput.add(Arrays.copyOf(buf, len)));
        peer.setStream(true);
        peer.setMtu(1021);
        peer.setNoDelay(1, 10, 2, true);
    }

    @After
    public void tearDown() {
        socket.close();
    }

    private static byte[] frame(int length, byte fill) {
        byte[] f = new byte[length + 2];
        f[0] = (byte) (length >>> 8);
        f[1] = (byte) length;
        Arrays.fill(f, 2, f.length, fill);
        return f;
    }

    /** Sends the peer's queued data and feeds it to the session; returns what the session delivered. */
    private List<byte[]> pump(long now) {
        peer.update((int) now);
        List<byte[]> delivered = new ArrayList<>();
        for (byte[] datagram : peerOutput) {
            List<byte[]> out = session.input(datagram, now);
            if (out != null) delivered.addAll(out);
        }
        peerOutput.clear();
        return delivered;
    }

    @Test
    public void framesComeOutWhole() {
        byte[] a = frame(10, (byte) 1), b = frame(1024, (byte) 2), c = frame(0, (byte) 3);
        for (byte[] f : new byte[][] {a, b, c}) peer.send(f, 0, f.length);
        List<byte[]> out = pump(0);
        assertEquals(3, out.size());
        assertArrayEquals(Arrays.copyOfRange(a, 2, a.length), out.get(0));
        assertArrayEquals(Arrays.copyOfRange(b, 2, b.length), out.get(1));
        assertEquals(0, out.get(2).length);
        assertFalse(session.isFailed());
    }

    @Test
    public void oversizedFrameFailsTheSession() {
        byte[] bad = frame(2000, (byte) 7);
        peer.send(bad, 0, bad.length);
        pump(0);
        assertTrue(session.isFailed());
        assertNull("a failed session takes no more input", session.input(new byte[Kcp.OVERHEAD], 1));
    }

    @Test
    public void invalidKcpLeavesTheSessionAlone() {
        assertNull(session.input(new byte[] {1, 2, 3}, 0));
        byte[] otherConv = new byte[Kcp.OVERHEAD];
        otherConv[0] = 9;
        assertNull(session.input(otherConv, 0));
        assertFalse(session.isFailed());
    }

    @Test
    public void unacknowledgedQueueIsBounded() {
        byte[] datagram = new byte[1000];
        for (int i = 0; i < KcpSession.MAX_WAIT_SEGMENTS + 10 && !session.isFailed(); i++) session.send(datagram);
        assertTrue(session.isFailed());
        session.send(datagram); // swallowed, no exception
    }

    @Test
    public void noAckProgressFailsTheSession() {
        session.send(new byte[100]);
        session.update(0, 0);
        session.update(KcpSession.NO_PROGRESS_MS / 2, 0);
        assertFalse(session.isFailed());
        session.update(KcpSession.NO_PROGRESS_MS + 1000, 0);
        assertTrue(session.isFailed());
    }

    @Test
    public void idleTimeoutIsCheckedWithTheUpdate() {
        byte[] f = frame(5, (byte) 1);
        peer.send(f, 0, f.length);
        pump(1000);
        session.update(1500, 1000);
        assertFalse("input 0.5 s ago", session.isFailed());
        session.update(2500, 1000);
        assertTrue(session.isFailed());
    }

    @Test
    public void admissionRejectsInputThatFailsTheSession() {
        byte[] bad = frame(4000, (byte) 7);
        peer.send(bad, 0, bad.length);
        peer.update(0);
        assertFalse(session.admissionInput(peerOutput.get(0), 0));
    }

    @Test
    public void admissionAcceptsValidKcpAndKeepsItsOutput() {
        byte[] f = frame(3, (byte) 4);
        peer.send(f, 0, f.length);
        peer.update(0);
        assertTrue(session.admissionInput(peerOutput.get(0), 0));
        List<byte[]> out = session.takeAdmissionInput();
        assertEquals(1, out.size());
        assertArrayEquals(new byte[] {4, 4, 4}, out.get(0));
        assertTrue("taken once", session.takeAdmissionInput().isEmpty());
    }

    @Test
    public void closingLingersUntilDeliveredOrTimeout() {
        assertFalse("not closing", session.isClosed(0));
        session.send(new byte[50]); // never acknowledged: nobody listens on the discard port
        session.update(0, 0);
        session.beginClose(0);
        assertFalse("still has unacknowledged data", session.isClosed(KcpSession.CLOSE_LINGER_MS - 1));
        assertTrue(session.isClosed(KcpSession.CLOSE_LINGER_MS + 1));
    }

    @Test
    public void closingWithNothingQueuedEndsAtOnce() {
        session.beginClose(0);
        assertTrue(session.isClosed(0));
    }

    @Test
    public void closingSessionStillAcknowledgesButDeliversNothing() {
        session.beginClose(0);
        byte[] f = frame(8, (byte) 2);
        peer.send(f, 0, f.length);
        assertTrue("game data is discarded while closing", pump(0).isEmpty());
        assertFalse(session.isFailed());
    }

    @Test
    public void failingReleasesBuffers() {
        for (int i = 0; i < 100; i++) session.send(new byte[1000]);
        session.fail("test", 0);
        session.send(new byte[10]);
        assertTrue(session.describe().contains("unacked 0"));
    }
}
