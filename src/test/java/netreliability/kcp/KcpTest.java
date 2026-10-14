package netreliability.kcp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Two {@link Kcp} ends over a simulated link that loses, delays and reorders datagrams. */
public class KcpTest {

    /** One direction of the link: datagrams become deliverable after a delay, unless lost. */
    private static final class Link {
        final Random random;
        final double loss;
        final List<long[]> times = new ArrayList<>();
        final List<byte[]> datagrams = new ArrayList<>();

        Link(long seed, double loss) {
            this.random = new Random(seed);
            this.loss = loss;
        }

        void put(byte[] buffer, int length, long now) {
            if (random.nextDouble() < loss) return;
            times.add(new long[] {now + 20 + random.nextInt(30)}); // 20-50 ms: reorders too
            datagrams.add(Arrays.copyOf(buffer, length));
        }

        void deliver(Kcp to, long now) {
            Iterator<long[]> t = times.iterator();
            Iterator<byte[]> d = datagrams.iterator();
            while (t.hasNext()) {
                long at = t.next()[0];
                byte[] datagram = d.next();
                if (at <= now) {
                    to.setCurrent((int) now);
                    assertEquals(0, to.input(datagram, 0, datagram.length));
                    t.remove();
                    d.remove();
                }
            }
        }
    }

    private static void transfer(double loss, boolean stream) {
        long[] clock = {0};
        Link ab = new Link(1, loss);
        Link ba = new Link(2, loss);
        Kcp a = new Kcp(0x1234, (buf, len) -> ab.put(buf, len, clock[0]));
        Kcp b = new Kcp(0x1234, (buf, len) -> ba.put(buf, len, clock[0]));
        for (Kcp k : new Kcp[] {a, b}) {
            k.setStream(stream);
            k.setMtu(1021);
            k.setWindow(256, 256);
            k.setNoDelay(1, 10, 2, true);
        }
        Random random = new Random(3);
        List<byte[]> sent = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            byte[] m = new byte[1 + random.nextInt(1000)];
            random.nextBytes(m);
            sent.add(m);
            assertEquals(0, a.send(m, 0, m.length));
        }
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (byte[] m : sent) expected.write(m, 0, m.length);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        List<byte[]> messages = new ArrayList<>();
        for (clock[0] = 0; clock[0] < 120000 && received.size() < expected.size(); clock[0] += 5) {
            a.update((int) clock[0]);
            b.update((int) clock[0]);
            ab.deliver(b, clock[0]);
            ba.deliver(a, clock[0]);
            for (byte[] m = b.recv(); m != null; m = b.recv()) {
                messages.add(m);
                received.write(m, 0, m.length);
            }
        }
        assertArrayEquals("all bytes, in order", expected.toByteArray(), received.toByteArray());
        if (!stream) {
            assertEquals(sent.size(), messages.size());
            for (int i = 0; i < sent.size(); i++) assertArrayEquals(sent.get(i), messages.get(i));
        }
        assertTrue("finished in time: " + clock[0], clock[0] < 120000);
        assertEquals(0, a.state());
    }

    @Test
    public void streamModeNoLoss() {
        transfer(0, true);
    }

    @Test
    public void streamModeTwentyPercentLoss() {
        transfer(0.2, true);
    }

    @Test
    public void messageModeTenPercentLoss() {
        transfer(0.1, false);
    }

    @Test
    public void wrongConversationIsRejected() {
        Kcp a = new Kcp(1, (buf, len) -> { });
        byte[] datagram = new byte[Kcp.OVERHEAD];
        datagram[0] = 2; // conv 2
        assertEquals(-1, a.input(datagram, 0, datagram.length));
        assertEquals(-1, a.input(datagram, 0, 10)); // too short
    }

    @Test
    public void sequenceNumbersWrap() {
        assertTrue(Kcp.diff(Integer.MIN_VALUE, Integer.MAX_VALUE) > 0);
        assertTrue(Kcp.diff(5, -5) > 0);
        assertTrue(Kcp.diff(-5, 5) < 0);
    }
}
