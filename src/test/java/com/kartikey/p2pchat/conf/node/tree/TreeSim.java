package com.kartikey.p2pchat.conf.node.tree;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.MemoryFabric;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;

/** Test harness: node 0 = source, node 1 = coordinator/admin, nodes 2.. = subscribers, all over a MemoryFabric. */
final class TreeSim {
    static final int K = 4;
    static final long SPOOF_SEQ = 5_000_000L;

    final int n;
    final MemoryFabric fabric;
    final Identity[] ids;
    final PeerNetwork[] nets;
    final Forwarder[] fwd;
    final TreeCoordinator coord;
    final long streamId;
    final boolean[] crashed;
    final Map<ParticipantId, Integer> index = new HashMap<>();
    final Random rnd = new Random(7);

    // delivery bookkeeping (default sink)
    final BitSet[] seen;
    final int[] maxLayerSeen;
    final long[][] lastT;
    final long[][] maxGapAfter;
    long gapFrom = Long.MAX_VALUE;
    boolean spoofDelivered;

    // source
    long seq, manifestSeq, firstSeq = -1, lastSeq = -1;
    boolean sending;
    boolean layered;
    long t;

    TreeSim(int n, long seed, IntFunction<Forwarder.Sink> customSink) {
        this.n = n;
        fabric = new MemoryFabric(seed);
        ids = new Identity[n];
        nets = new PeerNetwork[n];
        fwd = new Forwarder[n];
        crashed = new boolean[n];
        seen = new BitSet[n];
        maxLayerSeen = new int[n];
        lastT = new long[n][K];
        maxGapAfter = new long[n][K];
        for (int i = 0; i < n; i++) {
            ids[i] = Identity.generate();
            index.put(ids[i].id(), i);
            nets[i] = fabric.join(ids[i].id());
            fwd[i] = new Forwarder(nets[i], fabric::now, Forwarder.Config.defaults());
            seen[i] = new BitSet();
        }
        coord = new TreeCoordinator(nets[1], new TreeCoordinator.Config(K, 20, 8, 8000, 64, 4096, 3));
        streamId = StreamIds.of(ids[0].id(), 0);
        for (int i = 0; i < n; i++) {
            final int me = i;
            nets[i].setListener(new PeerNetwork.Listener() {
                public void onConnected(ParticipantId p) { fwd[me].onConnected(p); }
                public void onDisconnected(ParticipantId p) {
                    fwd[me].onDisconnected(p);
                    if (me == 1) coord.onDisconnected(p);
                }
                public void onMedia(ParticipantId from, byte[] pk) { fwd[me].onMedia(from, pk); }
                public void onControl(ParticipantId from, byte[] m) {
                    if (me == 1 && coord.handle(from, m)) return;
                    fwd[me].onControl(from, m);
                }
            });
            fwd[i].setCoordinator(ids[1].id());
            if (i != 1) nets[i].connect(ids[1].id(), List.of(), null);
        }
        fwd[0].publish(streamId);
        for (int i = 2; i < n; i++) {
            boolean relay = i % 10 >= 3; // 70% can relay, 30% leaf-only
            Forwarder.Sink sink = customSink != null ? customSink.apply(i) : defaultSink(i);
            fwd[i].subscribe(streamId, ids[0].id(), 0, relay ? 400 : 100, relay, sink);
        }
    }

    Forwarder.Sink defaultSink(int i) {
        return (h, pk) -> {
            if (h.seq() == SPOOF_SEQ) { spoofDelivered = true; return; }
            if (h.kind() == MediaHeader.Kind.MANIFEST) return;
            seen[i].set((int) h.seq());
            maxLayerSeen[i] = Math.max(maxLayerSeen[i], h.layer());
            long now = fabric.now();
            if (now >= gapFrom && lastT[i][h.stripe()] > 0) {
                maxGapAfter[i][h.stripe()] = Math.max(maxGapAfter[i][h.stripe()], now - lastT[i][h.stripe()]);
            }
            lastT[i][h.stripe()] = now;
        };
    }

    /** Advances the simulation by ms in 10 ms steps: network, ticks, source packets (every 20 ms). */
    void run(long ms, java.util.function.LongConsumer perStep) {
        long end = t + ms;
        while (t < end) {
            t += 10;
            fabric.advanceTo(t);
            for (int i = 0; i < n; i++) if (!crashed[i]) fwd[i].tick(t);
            if (perStep != null) perStep.accept(t);
            if (sending && t % 20 == 0) emit();
            if (t % 10 == 0) {
                // extra per-node hooks run through perStep
            }
        }
    }

    void run(long ms) { run(ms, null); }

    private void emit() {
        long s = seq++;
        int layer = layered ? (int) (s % 3) : 0;
        MediaHeader h = new MediaHeader(MediaHeader.Kind.AUDIO, streamId, 0, s, layer, (int) (s % K), 0, 0);
        byte[] pk = new byte[MediaHeader.LENGTH + 120];
        System.arraycopy(h.encode(), 0, pk, 0, MediaHeader.LENGTH);
        if (firstSeq < 0) firstSeq = s;
        lastSeq = s;
        fwd[0].send(pk);
        if (t % 1000 == 0) {
            MediaHeader m = new MediaHeader(MediaHeader.Kind.MANIFEST, streamId, 0, manifestSeq++, 0, 0, 0, 0);
            byte[] mp = new byte[MediaHeader.LENGTH + 40];
            System.arraycopy(m.encode(), 0, mp, 0, MediaHeader.LENGTH);
            fwd[0].sendManifest(mp, K);
        }
    }

    void crash(int i) {
        crashed[i] = true;
        fabric.crash(ids[i].id());
    }

    /** Fraction of packets with seq in [from, to] (and stripe if >=0, layer if >=0 by seq%3) that node i got. */
    double fraction(int i, long from, long to, int stripe) {
        int got = 0, total = 0;
        for (long s = from; s <= to; s++) {
            if (stripe >= 0 && s % K != stripe) continue;
            total++;
            if (seen[i].get((int) s)) got++;
        }
        return total == 0 ? 1.0 : (double) got / total;
    }

    List<Integer> subscribers() {
        List<Integer> l = new ArrayList<>();
        for (int i = 2; i < n; i++) if (!crashed[i]) l.add(i);
        return l;
    }

    boolean hasChildren(int i) {
        for (int s = 0; s < K; s++) if (!fwd[i].childrenOf(streamId, s).isEmpty()) return true;
        return false;
    }

    int depth(int i, int stripe) {
        int d = 0;
        int cur = i;
        while (cur != 0 && d < 100) {
            ParticipantId p = fwd[cur].parentOf(streamId, stripe);
            if (p == null) return -1;
            cur = index.get(p);
            d++;
        }
        return d;
    }
}
