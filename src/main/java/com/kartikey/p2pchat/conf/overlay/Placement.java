package com.kartikey.p2pchat.conf.overlay;

/**
 * Where a joining node ended up. Entries of {@code parents}/{@code depths} are indexed by stripe;
 * a stripe that could not be placed has parent -1 and depth -1.
 *
 * @param interiorStripe the one stripe this node forwards in, or -1 if it is leaf-only
 */
public record Placement(long id, int interiorStripe, long[] parents, int[] depths) {
    public boolean fullyPlaced() {
        for (long p : parents) if (p < 0) return false;
        return true;
    }
}
