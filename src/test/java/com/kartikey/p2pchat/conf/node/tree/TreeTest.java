package com.kartikey.p2pchat.conf.node.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TreeTest {

    private static final int K = TreeSim.K;

    private static TreeSim settled(int n, long seed) {
        TreeSim sim = new TreeSim(n, seed, null);
        sim.run(2500);
        for (int i : sim.subscribers()) assertTrue(sim.fwd[i].fullyAttached(sim.streamId, K), "node " + i + " not attached");
        return sim;
    }

    @Test
    void messagesRoundTripAndMalformedInputIsDropped() {
        Identity a = Identity.generate(), b = Identity.generate();
        byte[] p = TreeMessages.place(new TreeMessages.Place(9, 2, a.id(), new byte[] {1, 2}, b.id(),
                List.of(a.id(), b.id()), new long[] {1, 2, 3}));
        TreeMessages.Place d = TreeMessages.place(p);
        assertEquals(a.id(), d.parent());
        assertEquals(2, d.backups().size());
        assertEquals(3, d.path().length);
        TreeMessages.Subscribe s = TreeMessages.subscribe(TreeMessages.subscribe(
                new TreeMessages.Subscribe(5, a.id(), 2, 300, true, new byte[] {7})));
        assertEquals(2, s.maxLayer());
        assertTrue(s.canRelay());

        TreeSim sim = new TreeSim(5, 1, null);
        Random r = new Random(3);
        for (int i = 0; i < 3000; i++) {
            byte[] junk = new byte[r.nextInt(200)];
            r.nextBytes(junk);
            if (junk.length > 0 && i % 2 == 0) junk[0] = (byte) (0x20 + r.nextInt(14));
            sim.fwd[3].onControl(sim.ids[2].id(), junk);
            sim.coord.handle(sim.ids[2].id(), junk);
            sim.fwd[3].onMedia(sim.ids[2].id(), junk);
        }
    }

    @Test
    void lossFreeDeliveryOnAllStripes() {
        TreeSim sim = settled(400, 11);
        sim.sending = true;
        sim.run(10_000);
        sim.sending = false;
        sim.run(500);
        long from = sim.firstSeq, to = sim.lastSeq;
        double worst = 1;
        int maxDepth = 0;
        for (int i : sim.subscribers()) {
            for (int s = 0; s < K; s++) {
                worst = Math.min(worst, sim.fraction(i, from, to, s));
                maxDepth = Math.max(maxDepth, sim.depth(i, s));
            }
        }
        System.out.printf("[tree] 400 nodes, 0%% loss: worst per-stripe delivery %.4f, max depth %d, packets %d%n",
                worst, maxDepth, to - from + 1);
        assertTrue(worst >= 0.99, "worst " + worst);
        assertEquals(0, sim.fwd[5].unknownStreamDrops());
    }

    private void lossRun(double loss, long seed, double minWorst) {
        TreeSim sim = settled(400, seed);
        sim.fabric.setMediaLoss(loss);
        sim.sending = true;
        sim.run(10_000);
        sim.sending = false;
        sim.run(800);
        double sum = 0, worst = 1;
        int cnt = 0;
        for (int i : sim.subscribers()) {
            for (int st = 0; st < K; st++) {
                // a stream's very first packet per stripe has no predecessor to reveal the gap, so start after it
                double f = sim.fraction(i, sim.firstSeq + K, sim.lastSeq - 8, st);
                sum += f;
                cnt++;
                worst = Math.min(worst, f);
            }
        }
        long nack = 0, retx = 0, media = 0, nacks = 0, retxN = 0;
        for (int i = 0; i < sim.n; i++) {
            Forwarder.NackStats ns = sim.fwd[i].nackStats();
            nack += ns.nackBytes(); retx += ns.retransBytes(); media += ns.mediaBytes(); nacks += ns.nacksSent(); retxN += ns.retransmits();
        }
        System.out.printf("[tree] 400 nodes, %.0f%% loss: mean %.4f, worst node-stripe %.4f; NACKs %d (%.2f%% of media bytes), "
                + "retransmits %d (%.2f%%), total overhead %.2f%%%n", loss * 100, sum / cnt, worst, nacks,
                100.0 * nack / media, retxN, 100.0 * retx / media, 100.0 * (nack + retx) / media);
        assertTrue(worst >= minWorst, "worst " + worst);
    }

    @Test
    void twoPercentLossWorstNodeAtLeast99Percent() { lossRun(0.02, 12, 0.99); }

    @Test
    void fivePercentLossWorstNodeAtLeast97Percent() { lossRun(0.05, 18, 0.97); }

    @Test
    void packetsFromNonParentsAreNeverAcceptedOrForwarded() {
        TreeSim sim = settled(200, 13);
        sim.sending = true;
        sim.run(1000);
        sim.sending = false;
        sim.run(500);
        int attacker = -1;
        for (int i : sim.subscribers()) if (!sim.hasChildren(i)) { attacker = i; break; }
        assertTrue(attacker > 0);
        long fwdBefore = 0, recvBefore = 0, droppedBefore = 0;
        for (int i = 0; i < sim.n; i++) {
            Forwarder.StreamStats st = sim.fwd[i].stats(sim.streamId);
            fwdBefore += st.forwarded();
            recvBefore += st.received();
            droppedBefore += st.dropped();
        }
        int sent = 0;
        for (int v = 0; v < sim.n; v++) {
            if (v == attacker) continue;
            sim.nets[attacker].connect(sim.ids[v].id(), List.of(), null);
            for (int s = 0; s < K; s++) {
                byte[] pk = new byte[MediaHeader.LENGTH + 50];
                System.arraycopy(new MediaHeader(MediaHeader.Kind.AUDIO, sim.streamId, 0, TreeSim.SPOOF_SEQ, 0, s, 0, 0).encode(),
                        0, pk, 0, MediaHeader.LENGTH);
                sim.nets[attacker].sendMedia(sim.ids[v].id(), pk);
                sent++;
            }
            // and one for a stream nobody knows
            byte[] other = new byte[MediaHeader.LENGTH];
            System.arraycopy(new MediaHeader(MediaHeader.Kind.AUDIO, 12345L, 0, 1, 0, 0, 0, 0).encode(), 0, other, 0, MediaHeader.LENGTH);
            sim.nets[attacker].sendMedia(sim.ids[v].id(), other);
        }
        sim.run(500);
        long fwdAfter = 0, recvAfter = 0, droppedAfter = 0;
        for (int i = 0; i < sim.n; i++) {
            Forwarder.StreamStats st = sim.fwd[i].stats(sim.streamId);
            fwdAfter += st.forwarded();
            recvAfter += st.received();
            droppedAfter += st.dropped();
        }
        System.out.printf("[tree] spoof: %d spoofed packets, dropped delta %d, forwarded delta %d%n",
                sent, droppedAfter - droppedBefore, fwdAfter - fwdBefore);
        assertFalse(sim.spoofDelivered, "a spoofed packet reached a sink");
        assertEquals(fwdBefore, fwdAfter);
        assertEquals(recvBefore, recvAfter);
        assertTrue(droppedAfter - droppedBefore >= sent - K, "spoofed packets must be counted as dropped");
        assertTrue(sim.fwd[7].unknownStreamDrops() >= 1);
    }

    @Test
    void crashingTenPercentOfRelaysRecoversEverySurvivor() {
        TreeSim sim = settled(400, 14);
        sim.sending = true;
        sim.run(3000);
        List<Integer> relays = new ArrayList<>();
        for (int i : sim.subscribers()) if (sim.hasChildren(i)) relays.add(i);
        Collections.shuffle(relays, new Random(5));
        int toCrash = Math.max(1, relays.size() / 10);
        long crashAt = sim.t;
        sim.gapFrom = crashAt;
        for (int k = 0; k < toCrash; k++) sim.crash(relays.get(k));
        sim.run(8000);
        long tailFrom = sim.lastSeq - 100; // last 2 s worth of packets
        List<Long> gaps = new ArrayList<>();
        long maxGap = 0;
        for (int i : sim.subscribers()) {
            for (int s = 0; s < K; s++) {
                assertTrue(sim.fraction(i, tailFrom, sim.lastSeq - 5, s) >= 0.99, "node " + i + " stripe " + s + " did not recover");
                long g = sim.maxGapAfter[i][s];
                maxGap = Math.max(maxGap, g);
                if (g > 120) gaps.add(g); // above the normal inter-packet gap (80 ms + jitter)
            }
        }
        Collections.sort(gaps);
        long median = gaps.isEmpty() ? 0 : gaps.get(gaps.size() / 2);
        List<Long> detect = new ArrayList<>();
        for (int i : sim.subscribers()) detect.addAll(sim.fwd[i].recoveryTimesMillis());
        Collections.sort(detect);
        System.out.printf("[tree] crashed %d/%d relays of 400: %d affected node-stripes, delivery gap median %d ms, max %d ms; "
                        + "detect-to-first-packet median %d ms, max %d ms%n",
                toCrash, relays.size(), gaps.size(), median, maxGap,
                detect.isEmpty() ? 0 : detect.get(detect.size() / 2), detect.isEmpty() ? 0 : detect.get(detect.size() - 1));
        assertTrue(maxGap < 2000, "max gap " + maxGap);
    }

    @Test
    void silentParentIsDetectedByTimeoutAndRepaired() {
        TreeSim sim = settled(400, 15);
        sim.sending = true;
        sim.run(2000);
        int relay = -1, child = -1, stripe = -1;
        outer:
        for (int i : sim.subscribers()) {
            for (int s = 0; s < K; s++) {
                for (ParticipantId c : sim.fwd[i].childrenOf(sim.streamId, s)) {
                    relay = i; child = sim.index.get(c); stripe = s;
                    break outer;
                }
            }
        }
        assertTrue(relay > 0);
        sim.gapFrom = sim.t;
        sim.fabric.partition(sim.ids[relay].id(), sim.ids[child].id()); // no disconnect event: pure silence
        sim.run(6000);
        long gap = sim.maxGapAfter[child][stripe];
        System.out.printf("[tree] silent parent: child stripe gap %d ms (silence threshold 1500 ms)%n", gap);
        assertTrue(sim.fraction(child, sim.lastSeq - 80, sim.lastSeq - 5, stripe) >= 0.99);
        assertTrue(gap >= 1400 && gap < 2600, "gap " + gap);
    }

    @Test
    void layerFilteringKeepsGalleryTilesOnLayerZero() {
        TreeSim sim = settled(120, 16);
        List<Integer> speaker = new ArrayList<>(), mid = new ArrayList<>(), gallery = new ArrayList<>();
        for (int i : sim.subscribers()) {
            int kind = i % 3;
            if (kind == 0) { speaker.add(i); sim.fwd[i].setMaxLayer(sim.streamId, 2); }
            else if (kind == 1) { mid.add(i); sim.fwd[i].setMaxLayer(sim.streamId, 1); }
            else gallery.add(i);
        }
        sim.run(1500);
        sim.layered = true;
        sim.sending = true;
        sim.run(6000);
        sim.sending = false;
        sim.run(500);
        for (int i : gallery) assertEquals(0, sim.maxLayerSeen[i], "gallery node " + i + " saw a higher layer");
        for (int i : mid) assertEquals(1, sim.maxLayerSeen[i]);
        long from = sim.firstSeq + 25;
        double worst = 1;
        for (int i : speaker) {
            assertEquals(2, sim.maxLayerSeen[i]);
            int got = 0, total = 0;
            for (long s = from; s <= sim.lastSeq; s++) {
                if (s % 3 != 2) continue;
                total++;
                if (sim.seen[i].get((int) s)) got++;
            }
            worst = Math.min(worst, (double) got / total);
        }
        for (int i : gallery) {
            int got = 0, total = 0;
            for (long s = from; s <= sim.lastSeq; s++) {
                if (s % 3 != 0) continue;
                total++;
                if (sim.seen[i].get((int) s)) got++;
            }
            worst = Math.min(worst, (double) got / total);
        }
        System.out.printf("[tree] layers: speaker/gallery worst delivery of wanted layer %.4f%n", worst);
        assertTrue(worst >= 0.99, "worst " + worst);
    }

    @Test
    void gracefulLeaveHandsChildrenToNewParents() {
        TreeSim sim = settled(150, 17);
        sim.sending = true;
        sim.run(1500);
        int leaver = -1;
        for (int i : sim.subscribers()) if (sim.hasChildren(i)) { leaver = i; break; }
        sim.gapFrom = sim.t;
        sim.fwd[leaver].leave();
        sim.nets[leaver].close();
        sim.crashed[leaver] = true;
        sim.run(4000);
        for (int i : sim.subscribers()) {
            for (int s = 0; s < K; s++) {
                assertTrue(sim.fraction(i, sim.lastSeq - 80, sim.lastSeq - 5, s) >= 0.99, "node " + i + " stripe " + s);
            }
        }
        PeerNetwork unused = sim.nets[0];
        assertNotNull(unused);
    }
}
