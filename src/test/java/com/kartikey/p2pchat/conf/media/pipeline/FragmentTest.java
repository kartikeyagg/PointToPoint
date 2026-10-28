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
}
