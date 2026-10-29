package com.kartikey.p2pchat.conf.media.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioJitterBufferTest {
    private static byte[] pkt(int seq) {
        return new byte[] {(byte) seq};
    }

    @Test
    void inOrderPlaysEverything() {
        AudioJitterBuffer b = new AudioJitterBuffer();
        for (int i = 0; i < 5; i++) b.put(i, pkt(i), i * 20L);
        assertNull(b.poll(20)); // still buffering
        for (int i = 0; i < 5; i++) assertArrayEquals(pkt(i), b.poll(100 + i * 20L));
        assertEquals(5, b.played());
        assertEquals(0, b.concealed());
    }

    @Test
    void reorderedPacketsPlayInOrder() {
        AudioJitterBuffer b = new AudioJitterBuffer();
        b.put(0, pkt(0), 0);
        b.put(2, pkt(2), 5);
        b.put(1, pkt(1), 10);
        for (int i = 0; i < 3; i++) assertArrayEquals(pkt(i), b.poll(60 + i * 20L));
    }

    @Test
    void lostPacketIsNullThenPlaybackContinues() {
        AudioJitterBuffer b = new AudioJitterBuffer();
        for (int i : new int[] {0, 1, 3, 4}) b.put(i, pkt(i), i * 20L);
        assertArrayEquals(pkt(0), b.poll(100));
        assertArrayEquals(pkt(1), b.poll(120));
        assertNull(b.poll(140));
        assertArrayEquals(pkt(3), b.poll(160));
        assertArrayEquals(pkt(4), b.poll(180));
        assertEquals(1, b.concealed());
        assertEquals(4, b.played());
    }

    @Test
    void latePacketIsDropped() {
        AudioJitterBuffer b = new AudioJitterBuffer();
        b.put(0, pkt(0), 0);
        b.put(2, pkt(2), 20);
        b.poll(100);
        assertNull(b.poll(120)); // seq 1 missing
        b.put(1, pkt(1), 125);
        assertEquals(1, b.late());
        assertArrayEquals(pkt(2), b.poll(140));
    }

    @Test
    void senderRestartResyncs() {
        AudioJitterBuffer b = new AudioJitterBuffer();
        for (int i = 0; i < 3; i++) b.put(1000 + i, pkt(i), i * 20L);
        b.poll(100);
        b.put(0, pkt(9), 200); // seq jumped far back
        assertEquals(0, b.late());
        assertNull(b.poll(210)); // re-buffering
        assertArrayEquals(pkt(9), b.poll(260));
    }

    @Test
    void targetDelayGrowsWithJitter() {
        AudioJitterBuffer calm = new AudioJitterBuffer();
        AudioJitterBuffer jittery = new AudioJitterBuffer();
        for (int i = 0; i < 100; i++) {
            calm.put(i, pkt(i), i * 20L);
            jittery.put(i, pkt(i), i * 20L + (i % 2 == 0 ? 0 : 70));
        }
        assertEquals(40, calm.targetDelayMillis());
        assertTrue(jittery.targetDelayMillis() > 60, "got " + jittery.targetDelayMillis());
        assertTrue(jittery.targetDelayMillis() <= 200);
    }
}
