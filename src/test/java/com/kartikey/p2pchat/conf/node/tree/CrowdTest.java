package com.kartikey.p2pchat.conf.node.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.audio.AudioMode;
import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CrowdTest {

    private static final int FRAME = 160, K = TreeSim.K;

    /** Lossless fake codec: 16-bit PCM as bytes. */
    static final class Fake implements Codecs.AudioEncoder, Codecs.AudioDecoder {
        public byte[] encode(short[] pcm) {
            ByteBuffer b = ByteBuffer.allocate(pcm.length * 2);
            for (short v : pcm) b.putShort(v);
            return b.array();
        }
        public short[] decode(byte[] p) {
            short[] out = new short[FRAME];
            if (p != null) {
                ByteBuffer b = ByteBuffer.wrap(p);
                for (int i = 0; i < FRAME && b.remaining() >= 2; i++) out[i] = b.getShort();
            }
            return out;
        }
        public void close() {}
    }

    private static final class Crowd {
        final TreeSim sim;
        final CrowdSession[] sessions;
        final List<CrowdSession.MixFrame> rootMixes = new ArrayList<>();
        final Map<Long, Long> downSeenAt = new HashMap<>(); // deepest node: counter -> arrival time

        boolean allTalk;
        int traced = -2;

        Crowd(int n) {
            Forwarder.Sink[] sinks = new Forwarder.Sink[n];
            sessions = new CrowdSession[n];
            sim = new TreeSim(n, 21, i -> (h, p) -> sinks[i].onPacket(h, p));
            for (int i = 0; i < n; i++) {
                final int me = i;
                if (i == 1) continue; // admin/coordinator only coordinates
                Supplier<short[]> mic = () -> {
                    short[] f = new short[FRAME];
                    if (allTalk) {
                        if (me == 1 || me % 6 == 5) return null;
                        java.util.Arrays.fill(f, (short) 10);
                    } else if (me == traced) {
                        f[0] = (short) (sim.fabric.now() / 20 % 20000 + 1); // frame tag; only the traced node talks
                    } else {
                        return null;
                    }
                    return f;
                };
                sessions[i] = new CrowdSession(sim.ids[i], sim.nets[i], sim.fwd[i], sim.streamId, FRAME, K, new Fake(),
                        Fake::new, mic, () -> Long.MAX_VALUE, mix -> {
                            if (me == 0) rootMixes.add(mix);
                            if (me == traced && traced >= 0) downSeenAt.putIfAbsent((long) mix.pcm()[0], sim.fabric.now());
                        });
                sinks[i] = sessions[i];
            }
        }

        void run(long ms) {
            sim.run(ms, t -> { for (CrowdSession s : sessions) if (s != null) s.tick(t); });
        }
    }

    @Test
    void rootMixCountsEveryTalkerWithin20msFrames() {
        int n = 300;
        Crowd c = new Crowd(n);
        c.allTalk = true;
        c.sim.run(2500);
        int mics = 0;
        for (int i = 0; i < n; i++) if (i != 1 && i % 6 != 5) mics++;
        long start = c.sim.t;
        c.run(1200);
        // sessions only tick inside Crowd.run; the frames above were produced during this call
        List<Integer> counts = new ArrayList<>();
        for (CrowdSession.MixFrame f : c.rootMixes) if (f.atMillis() > start + 500) counts.add(f.participants());
        Collections.sort(counts);
        int median = counts.get(counts.size() / 2);
        System.out.printf("[crowd] 300 nodes, %d with mics: root mix participants median %d, min %d, max %d over %d frames%n",
                mics, median, counts.get(0), counts.get(counts.size() - 1), counts.size());
        assertTrue(median >= mics * 0.95 && median <= mics, "median " + median + " vs " + mics);
        assertTrue(counts.get(counts.size() - 1) <= mics, "never more voices than talkers");
        long rejected = 0;
        for (CrowdSession s : c.sessions) if (s != null) rejected += s.rejectedCount();
        assertEquals(0, rejected, "no honest frame should be rejected");
    }

    @Test
    void perFrameLatencyAndSpoofedUpFramesAreIgnored() {
        int n = 300;
        Crowd c = new Crowd(n);
        c.sim.run(2500);
        int traced = 2, d0 = 0;
        for (int i = 2; i < n; i++) {
            int d = c.sim.depth(i, 0);
            if (d > d0) { d0 = d; traced = i; }
        }
        c.traced = traced;
        long start = c.sim.t;
        // an attacker (a leaf-only node) sprays forged CROWD_UP frames at the traced node's parent
        int attacker = -1;
        for (int i = 2; i < n; i++) if (!c.sim.hasChildren(i) && i != traced) { attacker = i; break; }
        ParticipantId parent = c.sim.fwd[traced].parentOf(c.sim.streamId, 0);
        final int atk = attacker;
        c.sim.nets[atk].connect(parent, List.of(), null);
        c.sim.run(1200, t -> {
            if (t % 20 == 0) {
                byte[] junk = new byte[200];
                junk[0] = (byte) TreeMessages.CROWD_UP_MAGIC;
                ByteBuffer.wrap(junk, 1, 8).putLong(c.sim.streamId);
                c.sim.nets[atk].sendMedia(parent, junk);
            }
            for (CrowdSession s : c.sessions) if (s != null) s.tick(t);
        });
        // when the traced node speaks, the root should hear it; tag = frame counter at emission time
        List<Long> up = new ArrayList<>(), rt = new ArrayList<>();
        for (CrowdSession.MixFrame f : c.rootMixes) {
            if (f.atMillis() <= start + 500 || f.participants() != 1 || f.pcm()[0] == 0) continue;
            long emitted = tagTime(f.pcm()[0], f.atMillis());
            up.add(f.atMillis() - emitted);
            Long down = c.downSeenAt.get((long) f.pcm()[0]);
            if (down != null) rt.add(down - emitted);
        }
        Collections.sort(up);
        Collections.sort(rt);
        assertTrue(up.size() > 30, "root heard the traced talker (" + up.size() + " frames)");
        long upMed = up.get(up.size() / 2), rtMed = rt.isEmpty() ? -1 : rt.get(rt.size() / 2);
        System.out.printf("[crowd] depth %d (stripe 0) leaf mic -> root mix: median %d ms (max %d ms); mic -> final mix back at that leaf: "
                + "median %d ms (max %d ms); fabric hop 20-30 ms, frame 20 ms%n", d0, upMed, up.get(up.size() - 1),
                rtMed, rt.isEmpty() ? -1 : rt.get(rt.size() - 1));
        assertTrue(rt.size() > 20, "final mix reached the traced leaf");
        assertTrue(upMed <= (long) (d0 + 1) * 50 + 20, "latency " + upMed);
        long rejected = 0;
        for (CrowdSession s : c.sessions) if (s != null) rejected += s.rejectedCount();
        assertTrue(rejected >= 50, "forged frames must be counted and ignored: " + rejected);
    }

    /** The tag is (emission time / 20 % 20000 + 1); recover the emission time closest below {@code seenAt}. */
    private static long tagTime(long tag, long seenAt) {
        long frame = tag - 1;
        long period = 20000L * 20;
        long base = seenAt - seenAt % period + frame * 20;
        if (base > seenAt) base -= period;
        return base;
    }

    @Test
    void crowdModeSwitchesOffWhenDeadlinePasses() {
        assertEquals(AudioMode.CROWD, AudioMode.of(1000, 500));
        assertEquals(AudioMode.NORMAL, AudioMode.of(1000, 1000));
        PeerNetwork unused = null;
        assertTrue(unused == null);
    }
}
