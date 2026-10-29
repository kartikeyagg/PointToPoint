package com.kartikey.p2pchat.conf.media.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FragmentTest {
    private static EncodedFrame frame(int size, boolean key, long seed) {
        byte[] d = new byte[size];
        new Random(seed).nextBytes(d);
        return new EncodedFrame(d, key, 123_456L * seed, 1280, 720);
    }

    @Test
    void bigFrameShuffledRoundTrips() {
        EncodedFrame f = frame(50_000, true, 1);
        List<byte[]> payloads = Fragmenter.split(7, f);
        assertTrue(payloads.size() > 50);
        for (byte[] p : payloads) assertTrue(p.length <= MediaFormat.MAX_FRAGMENT);
        Collections.shuffle(payloads, new Random(5));
        FrameAssembler a = new FrameAssembler(1000);
        EncodedFrame got = null;
        for (byte[] p : payloads) {
            assertNull(got);
            got = a.accept(Fragment.decode(p), 0);
        }
        assertNotNull(got);
        assertArrayEquals(f.data(), got.data());
        assertTrue(got.keyframe());
        assertEquals(f.timestampMicros(), got.timestampMicros());
        assertEquals(1280, got.width());
        assertEquals(720, got.height());
        assertFalse(a.needsKeyframe());
    }

    @Test
    void missingFragmentDropsFrameAndRequestsKeyframe() {
        FrameAssembler a = new FrameAssembler(1000);
        List<byte[]> f0 = Fragmenter.split(0, frame(3000, true, 1));
        List<byte[]> f1 = Fragmenter.split(1, frame(3000, false, 2));
        List<byte[]> f2 = Fragmenter.split(2, frame(3000, false, 3));
        for (byte[] p : f0) a.accept(Fragment.decode(p), 0);
        for (int i = 0; i < f1.size() - 1; i++) assertNull(a.accept(Fragment.decode(f1.get(i)), 1));
        assertFalse(a.needsKeyframe());
        EncodedFrame got = null;
        for (byte[] p : f2) got = a.accept(Fragment.decode(p), 2);
        assertNotNull(got);
        assertEquals(1, a.framesDropped());
        assertTrue(a.needsKeyframe());
        // the straggler of the dropped frame is ignored, and a keyframe clears the request
        assertNull(a.accept(Fragment.decode(f1.get(f1.size() - 1)), 3));
        for (byte[] p : Fragmenter.split(3, frame(2000, true, 4))) a.accept(Fragment.decode(p), 4);
        assertFalse(a.needsKeyframe());
    }

    @Test
    void staleIncompleteFrameExpires() {
        FrameAssembler a = new FrameAssembler(100);
        a.accept(Fragment.decode(Fragmenter.split(0, frame(3000, true, 1)).get(0)), 0);
        a.expire(500);
        assertEquals(1, a.framesDropped());
        assertTrue(a.needsKeyframe());
    }

    @Test
    void emptyFrameAndMalformedInput() {
        List<byte[]> p = Fragmenter.split(0, frame(0, true, 1));
        assertEquals(1, p.size());
        assertNotNull(new FrameAssembler(100).accept(Fragment.decode(p.get(0)), 0));
        assertThrows(IllegalArgumentException.class, () -> Fragment.decode(new byte[5]));
        byte[] bad = new ArrayList<>(p).get(0).clone();
        bad[7] = 0; // count = 0
        bad[6] = 0;
        assertThrows(IllegalArgumentException.class, () -> Fragment.decode(bad));
    }

    @Test
    void hostileFragmentsAreRejectedOrBounded() {
        byte[] ok = Fragmenter.split(0, frame(10, true, 1)).get(0);
        byte[] bigCount = ok.clone();
        bigCount[6] = (byte) 0xFF;
        bigCount[7] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> Fragment.decode(bigCount));
        for (int[] wh : new int[][] {{0, 720}, {1281, 720}, {9000, 720}, {1280, 5000}}) {
            byte[] b = ok.clone();
            b[9] = (byte) (wh[0] >> 8); b[10] = (byte) wh[0];
            b[11] = (byte) (wh[1] >> 8); b[12] = (byte) wh[1];
            assertThrows(IllegalArgumentException.class, () -> Fragment.decode(b));
        }
        // the attack: many frames, each announcing the largest legal count, one tiny fragment each
        FrameAssembler a = new FrameAssembler(1000);
        long max = 0;
        for (int id = 0; id < 5000; id++) {
            a.accept(new Fragment(id, 0, Fragment.MAX_COUNT, false, 640, 360, 0, new byte[Fragment.MAX_DATA]), 0);
            max = Math.max(max, a.retainedBytes());
        }
        assertTrue(max <= 2L * MediaFormat.MAX_ENCODED_FRAME, "retained " + max);
        assertTrue(a.needsKeyframe());
    }

    @Test
    void fuzzedFragmentsNeverThrowOrOverflowBudget() {
        Random r = new Random(9);
        FrameAssembler a = new FrameAssembler(1000);
        for (int i = 0; i < 20000; i++) {
            byte[] b = new byte[r.nextInt(MediaFormat.MAX_FRAGMENT + 20)];
            r.nextBytes(b);
            if (b.length > 8 && r.nextBoolean()) { b[4] = 0; b[5] = (byte) r.nextInt(3); b[6] = 0; b[7] = (byte) (1 + r.nextInt(3)); }
            try {
                a.accept(Fragment.decode(b), i);
            } catch (IllegalArgumentException expected) {
                // malformed input is rejected, never anything else
            }
            assertTrue(a.retainedBytes() <= 2L * MediaFormat.MAX_ENCODED_FRAME);
        }
    }
}
