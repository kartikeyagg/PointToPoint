package com.kartikey.p2pchat.conf.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableStreamTest {

    /** Two streams over a simulated lossy, delayed link on a manual clock. */
    @Test
    void deliversExactlyOnceInOrderUnderLossAndReordering() throws Exception {
        Random rnd = new Random(3);
        ReliableStream tx = new ReliableStream(1 << 20, 200), rx = new ReliableStream(1 << 20, 200);
        record Pkt(long at, boolean data, byte[] bytes) {}
        List<Pkt> wire = new ArrayList<>();
        List<byte[]> sent = new ArrayList<>(), got = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            byte[] m = new byte[rnd.nextInt(i % 50 == 0 ? 20_000 : 300)];
            rnd.nextBytes(m);
            sent.add(m);
        }
        ArrayDeque<byte[]> queue = new ArrayDeque<>(sent);
        long now = 0;
        while (got.size() < sent.size()) {
            assertTrue(now < 600_000, "stalled at " + got.size());
            while (!queue.isEmpty() && tx.offer(queue.peek())) {
                queue.poll();
            }
            final long t = now;
            tx.poll(now, seg -> {
                if (rnd.nextDouble() >= 0.3) {
                    wire.add(new Pkt(t + 10 + rnd.nextInt(20), true, seg));
                }
            });
            if (rx.ackDue(now)) {
                byte[] ack = rx.buildAck();
                if (rnd.nextDouble() >= 0.3) {
                    wire.add(new Pkt(t + 10 + rnd.nextInt(20), false, ack));
                }
            }
            for (var it = wire.iterator(); it.hasNext(); ) {
                Pkt p = it.next();
                if (p.at <= now) {
                    it.remove();
                    if (p.data) {
                        rx.onSegment(p.bytes, 0, p.bytes.length, now, got::add);
                    } else {
                        tx.onAck(p.bytes, 0, p.bytes.length, now);
                    }
                }
            }
            now += 5;
        }
        assertEquals(sent.size(), got.size());
        for (int i = 0; i < sent.size(); i++) {
            assertArrayEquals(sent.get(i), got.get(i), "message " + i);
        }
        assertTrue(tx.retransmissions() > 0);
    }

    @Test
    void capsUnackedAndRejectsOversize() {
        ReliableStream s = new ReliableStream(10_000, 200);
        assertFalse(s.offer(new byte[ReliableStream.MAX_MESSAGE + 1]));
        assertTrue(s.offer(new byte[9_000]));
        assertFalse(s.offer(new byte[2_000]));
        assertTrue(s.offer(new byte[1_000]));
    }

    @Test
    void framingViolationsAreProtocolErrors() {
        ReliableStream rx = new ReliableStream(1 << 20, 200);
        byte[] continuation = {0, 0, 0, 0, 0, 1, 2};
        assertThrows(ReliableStream.ProtocolException.class, () -> rx.onSegment(continuation, 0, 7, 0, m -> {}));
        ReliableStream rx2 = new ReliableStream(1 << 20, 200);
        assertThrows(ReliableStream.ProtocolException.class, () -> rx2.onSegment(new byte[3], 0, 3, 0, m -> {}));
        // an endless message (FIRST then continuations) is cut at MAX_MESSAGE
        ReliableStream rx3 = new ReliableStream(1 << 20, 200);
        assertThrows(ReliableStream.ProtocolException.class, () -> {
            for (int seq = 0; seq < 1000; seq++) {
                byte[] seg = new byte[ReliableStream.HEADER + ReliableStream.SEGMENT_DATA];
                java.nio.ByteBuffer.wrap(seg).putInt(seq).put(seq == 0 ? ReliableStream.FIRST : 0);
                rx3.onSegment(seg, 0, seg.length, 0, m -> {});
            }
        });
    }

    @Test
    void segmentsBeyondTheWindowAreIgnored() throws Exception {
        ReliableStream rx = new ReliableStream(1 << 20, 200);
        List<byte[]> got = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            byte[] seg = new byte[ReliableStream.HEADER + 100];
            java.nio.ByteBuffer.wrap(seg).putInt(5 + i * 7).put((byte) 3);
            rx.onSegment(seg, 0, seg.length, 0, got::add);
        }
        assertTrue(rx.bufferedBytes() <= ReliableStream.WINDOW * 100L);
        assertTrue(got.isEmpty());
    }

    @Test
    void replayWindow() {
        ReplayWindow w = new ReplayWindow();
        assertFalse(w.isReplay(0));
        w.mark(0);
        assertTrue(w.isReplay(0));
        w.mark(5000);
        assertTrue(w.isReplay(1));
        assertFalse(w.isReplay(4999));
        w.mark(4999);
        assertTrue(w.isReplay(4999));
        assertTrue(w.isReplay(-1));
    }
}
