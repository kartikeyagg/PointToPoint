package com.kartikey.p2pchat.conf.node.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.MemoryFabric;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Three sources, each coordinating its own stream at the same time, with no admin involved. */
class MultiSourceTest {
    private static final int N = 200, S = 3, K = 4;

    @Test
    void eachSourceCoordinatesItsOwnStream() {
        MemoryFabric fabric = new MemoryFabric(31);
        Identity[] ids = new Identity[N];
        PeerNetwork[] nets = new PeerNetwork[N];
        Forwarder[] fwd = new Forwarder[N];
        TreeCoordinator[] coord = new TreeCoordinator[S];
        long[] streams = new long[S];
        BitSet[][] seen = new BitSet[N][S];
        for (int i = 0; i < N; i++) {
            ids[i] = Identity.generate();
            nets[i] = fabric.join(ids[i].id());
            fwd[i] = new Forwarder(nets[i], fabric::now, Forwarder.Config.defaults());
        }
        for (int i = 0; i < S; i++) {
            final int me = i;
            streams[i] = StreamIds.of(ids[i].id(), 0);
            coord[i] = new TreeCoordinator(nets[i], new TreeCoordinator.Config(K, 20, 8, 3000, 64, 4096, 3),
                    src -> src.equals(ids[me].id()));
            coord[i].setLocalHandler(fwd[i]::onControl);
        }
        for (int i = 0; i < N; i++) {
            final int me = i;
            nets[i].setListener(new PeerNetwork.Listener() {
                public void onConnected(ParticipantId p) { fwd[me].onConnected(p); }
                public void onDisconnected(ParticipantId p) {
                    fwd[me].onDisconnected(p);
                    if (me < S) coord[me].onDisconnected(p);
                }
                public void onMedia(ParticipantId f, byte[] pk) { fwd[me].onMedia(f, pk); }
                public void onControl(ParticipantId f, byte[] m) {
                    if (me < S && coord[me].handle(f, m)) return;
                    fwd[me].onControl(f, m);
                }
            });
        }
        for (int i = 0; i < S; i++) fwd[i].publish(streams[i], ids[i].id());
        for (int i = S; i < N; i++) {
            for (int s = 0; s < S; s++) {
                nets[i].connect(ids[s].id(), List.of(), null);
                final int node = i, st = s;
                seen[i][s] = new BitSet();
                boolean relay = i % 10 >= 3;
                fwd[i].subscribe(streams[s], ids[s].id(), ids[s].id(), 0, relay ? 400 : 100, relay,
                        (h, p) -> { if (h.kind() != MediaHeader.Kind.MANIFEST) seen[node][st].set((int) h.seq()); });
            }
        }
        // a coordinator refuses other sources' streams
        nets[S].connect(ids[0].id(), List.of(), null);
        nets[S].sendControl(ids[0].id(), TreeMessages.subscribe(new TreeMessages.Subscribe(streams[1], ids[1].id(), 0, 100, true, new byte[0])));

        long t = 0, seq = 0, first = -1;
        boolean sending = false;
        for (; t < 9000; t += 10) {
            fabric.advanceTo(t);
            for (int i = 0; i < N; i++) fwd[i].tick(t);
            if (t == 2500) sending = true;
            if (sending && t % 20 == 0) {
                if (first < 0) first = seq;
                for (int s = 0; s < S; s++) {
                    MediaHeader h = new MediaHeader(MediaHeader.Kind.AUDIO, streams[s], 0, seq, 0, (int) (seq % K), 0, 0);
                    byte[] pk = new byte[MediaHeader.LENGTH + 100];
                    System.arraycopy(h.encode(), 0, pk, 0, MediaHeader.LENGTH);
                    fwd[s].send(pk);
                }
                seq++;
            }
        }
        double worst = 1;
        for (int i = S; i < N; i++) {
            for (int s = 0; s < S; s++) {
                int got = 0, total = 0;
                for (long q = first; q < seq - 10; q++) { total++; if (seen[i][s].get((int) q)) got++; }
                worst = Math.min(worst, (double) got / total);
            }
        }
        System.out.printf("[tree] 3 source-run coordinators, 200 nodes: worst per-stream delivery %.4f, members %d/%d/%d%n",
                worst, coord[0].memberCount(streams[0]), coord[1].memberCount(streams[1]), coord[2].memberCount(streams[2]));
        assertTrue(worst >= 0.99, "worst " + worst);
        assertEquals(0, coord[0].memberCount(streams[1]), "coordinator of source 0 must not serve source 1");
        assertEquals(N - S, coord[1].memberCount(streams[1]));
    }
}
