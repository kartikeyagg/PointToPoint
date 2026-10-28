package com.kartikey.p2pchat.conf.media.audio;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpusTest {

    static final int N = 960;

    @BeforeAll
    static void needOpus() {
        assumeTrue(Opus.available(), "libopus natives not available");
    }

    static short[] sine(int frame) {
        double peak = Math.pow(10, -12 / 20.0) * 32768;
        short[] f = new short[N];
        for (int i = 0; i < N; i++) {
            f[i] = (short) Math.round(peak * Math.sin(2 * Math.PI * 440 * (frame * N + i) / 48000.0));
        }
        return f;
    }

    @Test
    void roundTripCorrelatesAndHitsBitrate() {
        try (OpusEncoder enc = new OpusEncoder(); OpusDecoder dec = new OpusDecoder()) {
            List<short[]> in = new ArrayList<>();
            List<short[]> out = new ArrayList<>();
            long bytes = 0;
            int frames = 100;
            for (int k = 0; k < frames; k++) {
                short[] pcm = sine(k);
                byte[] p = enc.encode(pcm);
                if (k >= 10) {
                    bytes += p.length;
                }
                short[] o = dec.decode(p);
                assertEquals(N, o.length);
                in.add(pcm);
                out.add(o);
            }
            double avg = bytes / (double) (frames - 10);
            System.out.printf("opus avg packet %.1f bytes (%.1f kbps)%n", avg, avg * 8 / 0.02 / 1000);
            assertTrue(avg >= 30 && avg <= 90, "avg " + avg);

            int from = 20 * N;
            int len = 60 * N;
            double best = 0;
            for (int lag = 0; lag <= 400; lag++) {
                best = Math.max(best, corr(in, out, from, len, lag));
            }
            System.out.printf("opus correlation %.4f%n", best);
            assertTrue(best > 0.9, "corr " + best);
        }
    }

    private static double corr(List<short[]> in, List<short[]> out, int from, int len, int lag) {
        double xy = 0, xx = 0, yy = 0;
        for (int i = from; i < from + len; i++) {
            double x = in.get(i / N)[i % N];
            int j = i + lag;
            double y = out.get(j / N)[j % N];
            xy += x * y;
            xx += x * x;
            yy += y * y;
        }
        return xy / Math.sqrt(xx * yy + 1e-9);
    }

    @Test
    void silenceIsSmall() {
        try (OpusEncoder enc = new OpusEncoder()) {
            byte[] last = null;
            for (int k = 0; k < 50; k++) {
                last = enc.encode(new short[N]);
            }
            assertTrue(last.length <= 12, "silence packet " + last.length);
        }
    }

    @Test
    void lossConcealmentReturnsFullFrame() {
        try (OpusEncoder enc = new OpusEncoder(); OpusDecoder dec = new OpusDecoder()) {
            for (int k = 0; k < 10; k++) {
                dec.decode(enc.encode(sine(k)));
            }
            assertEquals(N, dec.decode(null).length);
            assertEquals(N, dec.decode(null).length);
            assertEquals(N, dec.decode(enc.encode(sine(10))).length);
        }
    }

    @Test
    void fastEnough() {
        try (OpusEncoder enc = new OpusEncoder(); OpusDecoder dec = new OpusDecoder()) {
            short[] pcm = sine(0);
            long t0 = System.nanoTime();
            for (int k = 0; k < 500; k++) {
                dec.decode(enc.encode(pcm));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("opus 500 frames enc+dec %d ms (%d us/frame)%n", ms, ms * 1000 / 500);
            assertTrue(ms < 2000, ms + " ms");
        }
    }

    @Test
    void closeTwiceIsSafe() {
        OpusEncoder enc = new OpusEncoder();
        OpusDecoder dec = new OpusDecoder();
        enc.close();
        enc.close();
        dec.close();
        dec.close();
    }
}
