package com.kartikey.p2pchat.conf.media.video;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Perf budget: targets are 15 ms/frame for all three layers and 8 ms for 720p alone; the test only
 * fails at 2x so slower CI machines don't flake. Numbers are printed for tracking.
 */
class Vp8PerfTest {

    private static final int WARMUP = 20, MEASURE = 60;

    @BeforeAll
    static void needNatives() {
        Assumptions.assumeTrue(Vp8.available(), "FFmpeg/libvpx natives unavailable");
    }

    /** Textured, noisy, moving content: a camera-like worst case rather than a flat box. */
    static VideoFrame noisy(int w, int h, int n) {
        byte[] d = new byte[VideoFrame.size(w, h)];
        int seed = n * 7919;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = x + n * 3, sy = y + n * 2;
                int tex = ((sx * 13) ^ (sy * 7)) & 0x1f;
                seed = seed * 1664525 + 1013904223;
                int noise = (seed >>> 28) & 7;
                d[y * w + x] = (byte) (60 + ((sx / 4 + sy / 6) & 0x7f) + tex + noise);
            }
        }
        for (int i = w * h; i < d.length; i++) {
            seed = seed * 1664525 + 1013904223;
            d[i] = (byte) (120 + ((seed >>> 29) & 7));
        }
        return new VideoFrame(w, h, d, n * 41_666L);
    }

    private static VideoFrame[] frames(int w, int h, int count) {
        VideoFrame[] f = new VideoFrame[count];
        for (int i = 0; i < count; i++) f[i] = noisy(w, h, i);
        return f;
    }

    @Test
    void simulcastAndSingleLayerBudget() {
        VideoFrame[] src = frames(1280, 720, WARMUP + MEASURE);
        double all;
        try (Simulcast sc = new Simulcast(24)) {
            long t = 0;
            for (int i = 0; i < src.length; i++) {
                long t0 = System.nanoTime();
                sc.encode(src[i], false);
                if (i >= WARMUP) t += System.nanoTime() - t0;
            }
            all = t / (double) MEASURE / 1e6;
        }
        double one;
        List<EncodedFrame> encoded = new ArrayList<>();
        try (Vp8Encoder enc = new Vp8Encoder(1280, 720, 1_500_000, 24)) {
            long t = 0;
            for (int i = 0; i < src.length; i++) {
                long t0 = System.nanoTime();
                EncodedFrame e = enc.encode(src[i], false);
                if (i >= WARMUP) t += System.nanoTime() - t0;
                encoded.add(e);
            }
            one = t / (double) MEASURE / 1e6;
        }
        double dec;
        try (Vp8Decoder d = new Vp8Decoder()) {
            long t = 0;
            for (int i = 0; i < encoded.size(); i++) {
                long t0 = System.nanoTime();
                assertNotNull(d.decode(encoded.get(i)));
                if (i >= WARMUP) t += System.nanoTime() - t0;
            }
            dec = t / (double) MEASURE / 1e6;
        }
        System.out.printf("PERF 3-layer %.1f ms/frame (target 15), 720p alone %.1f ms (target 8), 720p decode %.1f ms%n",
                all, one, dec);
        assertTrue(all <= 30, "3-layer " + all);
        assertTrue(one <= 16, "720p " + one);
    }
}
