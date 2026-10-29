package com.kartikey.p2pchat.conf.net;

/**
 * Sliding anti-replay window over 64-bit packet counters (as in IPsec/DTLS): counters more than
 * {@link #SIZE} behind the newest, or already seen, are rejected. Fixed memory.
 */
final class ReplayWindow {

    static final int SIZE = 2048;

    private final long[] bits = new long[SIZE / 64];
    private long maxSeen = -1;

    boolean isReplay(long counter) {
        if (counter < 0) {
            return true;
        }
        if (counter > maxSeen) {
            return false;
        }
        if (maxSeen - counter >= SIZE) {
            return true;
        }
        int bit = (int) (counter % SIZE);
        return (bits[bit >>> 6] & (1L << (bit & 63))) != 0;
    }

    /** Records {@code counter} (call only after {@link #isReplay} said no); true if it is the newest. */
    boolean mark(long counter) {
        boolean newest = counter > maxSeen;
        if (newest) {
            long from = Math.max(maxSeen + 1, counter - SIZE + 1);
            for (long c = from; c <= counter; c++) {
                int bit = (int) (c % SIZE);
                bits[bit >>> 6] &= ~(1L << (bit & 63));
            }
            maxSeen = counter;
        }
        int bit = (int) (counter % SIZE);
        bits[bit >>> 6] |= 1L << (bit & 63);
        return newest;
    }
}
