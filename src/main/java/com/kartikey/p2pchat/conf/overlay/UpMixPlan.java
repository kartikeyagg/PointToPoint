package com.kartikey.p2pchat.conf.overlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Crowd mode (everyone talks): audio flows UP one stripe's tree, each node mixing its children's
 * audio before sending a single stream to its parent, so no node ever handles more than fanout inputs.
 * Levels are ordered deepest first and end with the source, so every node appears after its children.
 */
public final class UpMixPlan {

    private final List<List<Long>> levels;

    private UpMixPlan(List<List<Long>> levels) { this.levels = levels; }

    public static UpMixPlan from(StripeForest f, int stripe) {
        TreeMap<Integer, List<Long>> byDepth = new TreeMap<>(Comparator.reverseOrder());
        for (long id : f.memberIds()) {
            int d = f.depth(id, stripe);
            if (d >= 0) byDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(id);
        }
        return new UpMixPlan(new ArrayList<>(byDepth.values()));
    }

    /** Levels from the deepest leaves up to the source (last level). */
    public List<List<Long>> levelsBottomUp() { return levels; }

    /** Tree depth (hops from the deepest node to the source). */
    public int depth() { return Math.max(0, levels.size() - 1); }

    /** depth x (hop + mix) going up, plus depth x hop coming back down as the mixed result. */
    public long estimatedLatencyMillis(int hopMillis, int mixFrameMillis) {
        long d = depth();
        return d * (hopMillis + mixFrameMillis) + d * hopMillis;
    }
}
