package com.kartikey.p2pchat.conf.node.gossip;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OverlayGossipTest {

    static final java.util.function.Predicate<Gossip.Item> VALID = it -> it.body().length > 0 && it.body()[0] == 1;

    static Gossip.Item valid(int n) {
        return new Gossip.Item(Gossip.Item.CHAT, new byte[]{1, (byte) n, (byte) (n >> 8), (byte) (n >> 16)});
    }

    Sim build(int n) {
        Sim sim = new Sim(42, n, VALID);
        sim.joinAll(0, 30, 1, 5); // a small seed overlay first; the contacts are ordinary members of it
        sim.run(10_000);
        sim.joinAll(30, n, 3, 20);
        sim.run(100_000);
        return sim;
    }

    @Test
    void thousandNodesFormConnectedOverlayThenSurviveCrashes() {
        Sim sim = build(1000);
        int minA = 99, maxA = 0, minP = 99;
        long sumA = 0, sumP = 0;
        for (Sim.SimNode n : sim.nodes) {
            int a = n.membership.activePeers().size(), p = n.membership.passivePeers().size();
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minP = Math.min(minP, p);
            sumA += a;
            sumP += p;
        }
        System.out.printf("overlay: active min=%d max=%d avg=%.2f, passive min=%d avg=%.1f%n", minA, maxA,
                sumA / 1000.0, minP, sumP / 1000.0);
        assertTrue(minA >= 1 && maxA <= 6, "active view bounds");
        assertEquals(1000, sim.largestComponent(), "overlay connected");
        assertTrue(sumP / 1000.0 > 30, "passive views mostly filled");

        // crash 20%
        Random r = new Random(7);
        List<Sim.SimNode> victims = new ArrayList<>(sim.nodes);
        java.util.Collections.shuffle(victims, r);
        for (Sim.SimNode v : victims.subList(0, 200)) sim.crash(v);
        sim.run(40_000);
        int alive = sim.alive().size();
        assertEquals(800, alive);
        int comp = sim.largestComponent();
        System.out.println("after 20% crash: largest component " + comp + "/800");
        assertEquals(800, comp, "overlay reconnected after crashes");
        for (Sim.SimNode n : sim.alive()) assertTrue(n.membership.activePeers().size() >= 1);
    }

    @Test
    void gossipReachesAlmostEveryNode() {
        Sim sim = build(1000);
        long sentBefore = 0;
        for (Sim.SimNode n : sim.nodes) sentBefore += n.gossip.itemsSent();
        long startMillis = sim.now;
        Gossip.Item item = valid(1);
        sim.nodes.get(500).gossip.publish(item);
        long t99 = -1;
        int reached = 0;
        for (int step = 0; step < 100; step++) {
            sim.run(200);
            reached = 0;
            for (Sim.SimNode n : sim.nodes) if (n.index == 500 || !n.delivered.isEmpty()) reached++;
            if (t99 < 0 && reached >= 999) t99 = sim.now - startMillis;
        }
        long sent = 0;
        for (Sim.SimNode n : sim.nodes) sent += n.gossip.itemsSent();
        System.out.printf("gossip: reached %d/1000, 99.9%% after %d ms, item msgs/node %.2f (incl IHAVE repair)%n",
                reached, t99, (sent - sentBefore) / 1000.0);
        assertTrue(reached >= 999);
        for (Sim.SimNode n : sim.nodes) assertTrue(n.delivered.size() <= 1, "delivered at most once");
    }

    @Test
    void invalidItemsDieAtFirstHop() {
        Sim sim = build(200);
        // a malicious node that accepts everything and publishes a forged item
        Sim.SimNode evil = sim.nodes.get(10);
        Gossip liar = new Gossip(evil.net, evil.membership::activePeers, it -> true, it -> {}, new Random(1));
        long before = 0;
        for (Sim.SimNode n : sim.nodes) before += n.gossip.itemsSent();
        int neighbours = evil.membership.activePeers().size();
        liar.publish(new Gossip.Item(Gossip.Item.CHAT, new byte[]{9, 9, 9}));
        sim.run(5_000);
        long after = 0;
        for (Sim.SimNode n : sim.nodes) {
            after += n.gossip.itemsSent();
            assertTrue(n.delivered.isEmpty(), "forged item must not be delivered");
        }
        assertEquals(before, after, "no honest node forwards it");
        long rejected = 0;
        for (Sim.SimNode n : sim.nodes) rejected += n.gossip.rejected();
        assertEquals(neighbours, rejected, "rejected exactly at the first hop");
    }

    @Test
    void rateLimitStopsFlooder() {
        Sim sim = new Sim(5, 2, VALID);
        sim.nodes.get(1).membership.join(sim.nodes.get(0).contact());
        sim.run(2_000);
        Gossip flooder = new Gossip(sim.nodes.get(1).net, sim.nodes.get(1).membership::activePeers, it -> true,
                it -> {}, new Random(2));
        for (int i = 0; i < 5000; i++) flooder.publish(valid(i));
        sim.fabric.advanceTo(sim.now + 500); // no ticks: the bucket does not refill
        int got = sim.nodes.get(0).delivered.size();
        System.out.println("flood: 5000 sent, " + got + " delivered, rateLimited=" + sim.nodes.get(0).gossip.rateLimited());
        assertTrue(got <= 201 && got > 0, "got " + got);
        assertTrue(sim.nodes.get(0).gossip.rateLimited() > 4000);
        // after a while it may send again, at the sustained rate
        sim.run(1_000);
        flooder.publish(valid(99999));
        sim.run(500);
        assertTrue(sim.nodes.get(0).delivered.size() > got);
    }

    @Test
    void oversizeItemRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Gossip.Item(Gossip.Item.CHAT, new byte[65537]));
        assertDoesNotThrow(() -> new Gossip.Item(Gossip.Item.CHAT, new byte[65536]));
    }

    @Test
    void ihaveRepairsLostItem() {
        Sim sim = new Sim(8, 3, VALID);
        sim.nodes.get(1).membership.join(sim.nodes.get(0).contact());
        sim.nodes.get(2).membership.join(sim.nodes.get(0).contact());
        sim.run(5_000);
        // node 2 is briefly unreachable by pushes: publish before it is linked, via direct store only
        Sim.SimNode a = sim.nodes.get(0), c = sim.nodes.get(2);
        sim.fabric.partition(a.identity.id(), c.identity.id());
        a.gossip.publish(valid(7));
        sim.run(1_000);
        sim.fabric.heal(a.identity.id(), c.identity.id());
        sim.run(30_000);
        assertEquals(1, c.delivered.size(), "recovered through the other peer's IHAVE");
        ParticipantId unused = c.identity.id();
        assertNotNull(unused);
        PeerNetwork n = c.net;
        assertNotNull(n);
    }
}
