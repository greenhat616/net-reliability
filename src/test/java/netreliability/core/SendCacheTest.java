package netreliability.core;

import static org.junit.Assert.assertEquals;

import java.util.BitSet;
import org.junit.Test;

public class SendCacheTest {

    private static BitSet bits(int... indexes) {
        BitSet b = new BitSet();
        for (int i : indexes) b.set(i);
        return b;
    }

    @Test
    public void overlappingRangesAskForEachFragmentOnce() {
        int[] ranges = new int[400];
        for (int i = 0; i < ranges.length; i += 2) {
            ranges[i] = 0;
            ranges[i + 1] = 2;
        }
        assertEquals(bits(0, 1), SendCache.requestedIndexes(ranges, 2));
    }

    @Test
    public void rangesAreClippedToThePacket() {
        assertEquals(bits(3, 4), SendCache.requestedIndexes(new int[] {3, 65535}, 5));
        assertEquals(bits(0, 1), SendCache.requestedIndexes(new int[] {-5, 7}, 5));
        assertEquals(bits(), SendCache.requestedIndexes(new int[] {9, 2}, 5));
        assertEquals(bits(), SendCache.requestedIndexes(new int[] {1, -3}, 5));
    }

    @Test
    public void oddLengthIgnoresTheDanglingValue() {
        assertEquals(bits(1), SendCache.requestedIndexes(new int[] {1, 1, 4}, 5));
    }
}
