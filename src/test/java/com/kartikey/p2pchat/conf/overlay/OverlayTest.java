package com.kartikey.p2pchat.conf.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OverlayTest {

    private static final NodeInfo SRC = new NodeInfo(0, 400, true);

    @Test
    void smallTreeHandChecked() {
        // 2 stripes x 100 kbps, source upload 400 => 2 root slots per stripe.
        StripeForest f = new StripeForest(SRC, 2, 100, 2);
        Placement a = f.join(new NodeInfo(1, 200, true));
        Placement b = f.join(new NodeInfo(2, 200, true));
        assertEquals(0, a.interiorStripe());
        assertEquals(1, b.interiorStripe()); // balancing, not id mod k
        assertEquals(0, f.parent(1, 0));
        assertEquals(0, f.parent(2, 1));
        Placement c = f.join(new NodeInfo(3, 50, false));
        assertEquals(1, f.parent(3, 0)); // stripe 0: source full, A is the only free slot
        assertEquals(2, f.parent(3, 1));
        assertEquals(2, c.depths()[0]);
        assertEquals(List.of(3L), f.children(1, 0));
        assertEquals(2, f.maxDepth());
        assertEquals(0, f.unplacedCount());
        assertTrue(f.validate().isEmpty());
    }

    @Test
    void sourceOnlyTinyNetwork() {
        StripeForest f = new StripeForest(SRC, 2, 100, 4);
        assertEquals(1, f.size());
        assertEquals(0, f.maxDepth());
        f.join(new NodeInfo(1, 10, false));
        f.join(new NodeInfo(2, 10, false));
        assertTrue(f.join(new NodeInfo(3, 10, false)).fullyPlaced() == false);
        assertEquals(1, f.unplacedCount());
        assertEquals(400, f.uploadKbpsUsed(0));
        assertTrue(f.validate().isEmpty());
    }

    private static StripeForest bigForest(int n, Random rnd, List<NodeInfo> out) {
        StripeForest f = new StripeForest(new NodeInfo(0, 20_000, true), 4, 400, 16);
        for (int i = 1; i <= n; i++) {
            boolean relay = rnd.nextDouble() >= 0.15;
            double u = rnd.nextDouble();
            NodeInfo ni = new NodeInfo(i, relay ? (int) (2000 + 48000 * u * u * u) : 1000, relay);
            out.add(ni);
            f.join(ni);
        }
        return f;
    }

    @Test
    void twentyThousandJoins() {
        StripeForest f = bigForest(20_000, new Random(7), new ArrayList<>());
        assertEquals(List.of(), f.validate());
        assertEquals(0, f.unplacedCount());
        assertTrue(f.maxDepth() <= 8, "depth " + f.maxDepth());
        for (long id : f.memberIds()) {
            if (id == 0) continue;
            int active = 0;
            for (int s = 0; s < 4; s++) if (!f.children(id, s).isEmpty()) active++;
            assertTrue(active <= 1);
        }
    }

    @Test
    void leavesAreRepaired() {
        Random rnd = new Random(11);
        StripeForest f = bigForest(20_000, rnd, new ArrayList<>());
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 20_000; i++) ids.add(i);
        Collections.shuffle(ids, rnd);
        int reattached = 0;
        for (int i = 0; i < 2000; i++) reattached += f.leave(ids.get(i)).size();
        assertTrue(reattached > 0);
        assertEquals(List.of(), f.validate());
        assertEquals(0, f.unplacedCount());
        assertEquals(18_001, f.size());
    }

    @Test
    void gossipFilterDedupAndEviction() {
        GossipFilter g = new GossipFilter(2);
        byte[] a = new byte[32], b = new byte[32], c = new byte[32];
        a[0] = 1; b[0] = 2; c[0] = 3;
        assertTrue(g.firstTime(a));
        assertFalse(g.firstTime(a.clone()));
        assertTrue(g.firstTime(b));
        assertFalse(g.firstTime(a)); // refresh a; b is now eldest
        assertTrue(g.firstTime(c));  // evicts b
        assertEquals(2, g.size());
        assertTrue(g.firstTime(b));  // b was forgotten
        List<Long> t = GossipFilter.pickTargets(List.of(1L, 2L, 3L, 4L), 2, new Random(1));
        assertEquals(2, new HashSet<>(t).size());
        assertEquals(3, GossipFilter.pickTargets(List.of(1L, 2L, 3L), 9, new Random(1)).size());
    }

    @Test
    void upMixLevelsCoverAllOnceChildrenFirst() {
        StripeForest f = bigForest(3000, new Random(3), new ArrayList<>());
        for (int s = 0; s < 4; s++) {
            UpMixPlan plan = UpMixPlan.from(f, s);
            Set<Long> seen = new HashSet<>();
            for (List<Long> level : plan.levelsBottomUp()) {
                for (long id : level) {
                    assertTrue(seen.add(id), "duplicate " + id);
                    for (long child : f.children(id, s)) assertTrue(seen.contains(child), "child after parent");
                }
            }
            assertEquals(f.size(), seen.size());
            assertEquals(f.maxDepth(s), plan.depth());
            assertEquals(plan.depth() * (40 + 20) + plan.depth() * 40L, plan.estimatedLatencyMillis(40, 20));
        }
    }
}
