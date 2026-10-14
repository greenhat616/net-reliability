package netreliability.core;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class ReceiveTrackerTest {

    private static ReceiveTracker.Partial partial(int total, int... received) {
        ReceiveTracker.Partial p = new ReceiveTracker.Partial(null, total, 0);
        for (int i : received) p.received.set(i);
        return p;
    }

    @Test
    public void missingRangesCoverEveryGap() {
        assertArrayEquals(new int[] {0, 1, 2, 2, 5, 1}, ReceiveTracker.missingRanges(partial(6, 1, 4)));
    }

    @Test
    public void missingTailUpToTotal() {
        assertArrayEquals(new int[] {2, 3}, ReceiveTracker.missingRanges(partial(5, 0, 1)));
    }

    @Test
    public void nothingMissing() {
        assertArrayEquals(new int[0], ReceiveTracker.missingRanges(partial(3, 0, 1, 2)));
    }

    @Test
    public void rangesAreCapped() {
        int total = 1000;
        int[] even = new int[total / 2];
        for (int i = 0; i < even.length; i++) even[i] = i * 2;
        int[] ranges = ReceiveTracker.missingRanges(partial(total, even));
        assertArrayEquals(new int[] {1, 1}, new int[] {ranges[0], ranges[1]});
        org.junit.Assert.assertEquals(netreliability.packets.PacketFragmentNack.MAX_RANGES * 2, ranges.length);
    }
}
