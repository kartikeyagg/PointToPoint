package com.kartikey.p2pchat.conf.media.video;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Vp8Test {

    @BeforeAll
    static void needNatives() {
        Assumptions.assumeTrue(Vp8.available(), "FFmpeg/libvpx natives unavailable");
    }

    static VideoFrame synth(int w, int h, int n) {
        byte[] d = new byte[VideoFrame.size(w, h)];
        int bx = (n * 7) % (w - 60), by = (n * 3) % (h - 60);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (x * 100 / w) + (y * 60 / h) + 40;
                if (x >= bx && x < bx + 60 && y >= by && y < by + 60) v = 220;
                d[y * w + x] = (byte) v;
            }
        }
        Arrays.fill(d, w * h, d.length, (byte) 128);
        return new VideoFrame(w, h, d, n * 41_666L);
    }

    static double psnrY(VideoFrame a, VideoFrame b) {
        long se = 0;
        int n = a.width() * a.height();
        for (int i = 0; i < n; i++) {
            int e = (a.data()[i] & 0xff) - (b.data()[i] & 0xff);
            se += (long) e * e;
        }
        return se == 0 ? 99 : 10 * Math.log10(255.0 * 255 * n / se);
    }

    @Test
    void roundTripQualityAndKeyframes() {
        try (Vp8Encoder enc = new Vp8Encoder(640, 360, 500_000, 24); Vp8Decoder dec = new Vp8Decoder()) {
            int keys = 0;
            double minPsnr = 99;
            long bytes = 0;
            for (int i = 0; i < 30; i++) {
                VideoFrame in = synth(640, 360, i);
                EncodedFrame e = enc.encode(in, i == 15);
                assertNotNull(e);
                if (i == 0) assertTrue(e.keyframe());
                if (i == 15) assertTrue(e.keyframe(), "forced keyframe");
                if (e.keyframe()) keys++;
                bytes += e.data().length;
                VideoFrame out = dec.decode(e);
                assertNotNull(out);
                assertEquals(640, out.width());
                if (i >= 5) minPsnr = Math.min(minPsnr, psnrY(in, out));
            }
            System.out.println("360p: avg bytes/frame " + bytes / 30 + ", min PSNR " + minPsnr);
            assertTrue(keys <= 4, "keyframes: " + keys);
            assertTrue(minPsnr > 28, "psnr " + minPsnr);
        }
    }

    @Test
    void deltaBeforeKeyframeIsDropped() {
        try (Vp8Encoder enc = new Vp8Encoder(320, 180, 200_000, 24); Vp8Decoder dec = new Vp8Decoder()) {
            enc.encode(synth(320, 180, 0), false);
            EncodedFrame delta = enc.encode(synth(320, 180, 1), false);
            assertFalse(delta.keyframe());
            assertNull(dec.decode(delta));
            assertNotNull(dec.decode(enc.encode(synth(320, 180, 2), true)));
        }
    }

    @Test
    void setBitrateRestartsWithKeyframe() {
        try (Vp8Encoder enc = new Vp8Encoder(320, 180, 200_000, 24)) {
            enc.encode(synth(320, 180, 0), false);
            enc.setBitrate(100_000);
            assertTrue(enc.encode(synth(320, 180, 1), false).keyframe());
        }
    }

    @Test
    void simulcastLayers() {
        try (Simulcast sc = new Simulcast(24)) {
            long[] bytes = new long[3];
            VideoFrame src = null;
            List<EncodedFrame> last = null;
            long total = 0;
            for (int i = 0; i < 24; i++) {
                src = synth(1280, 720, i);
                long t0 = System.nanoTime();
                last = sc.encode(src, false);
                if (i > 0) total += System.nanoTime() - t0;
                assertEquals(3, last.size());
                for (int l = 0; l < 3; l++) bytes[l] += last.get(l).data().length;
            }
            System.out.println("simulcast avg ms/frame: " + total / 23 / 1e6);
            assertEquals(320, last.get(0).width());
            assertEquals(640, last.get(1).width());
            assertEquals(1280, last.get(2).width());
            assertEquals(720, last.get(2).height());
            System.out.println("avg bytes/frame per layer: " + bytes[0] / 24 + " " + bytes[1] / 24 + " " + bytes[2] / 24);
            assertTrue(bytes[0] < bytes[1] && bytes[1] < bytes[2]);

            sc.setLayerEnabled(1, false);
            List<EncodedFrame> r = sc.encode(src, false);
            assertNull(r.get(1));
            assertNotNull(r.get(0));
            sc.setLayerEnabled(1, true);
            assertTrue(sc.encode(src, false).get(1).keyframe());
        }
    }

    @Test
    void oversizedFramesRejected() {
        try (Vp8Decoder dec = new Vp8Decoder()) {
            assertNull(dec.decode(new EncodedFrame(new byte[100], true, 0, 16383, 16383)));
            assertNull(dec.decode(new EncodedFrame(new byte[3 * 1024 * 1024], true, 0, 320, 180)));
        }
    }

    @Test
    void interruptDuringEncodeThenCloseIsSafe() {
        VideoFrame src = synth(1280, 720, 1);
        for (int i = 0; i < 5; i++) {
            Simulcast sc = new Simulcast(24);
            Thread.currentThread().interrupt();
            List<EncodedFrame> r = sc.encode(src, false);
            assertTrue(Thread.interrupted(), "interrupt flag restored");
            assertEquals(3, r.size());
            assertNotNull(r.get(2));
            Thread.currentThread().interrupt();
            sc.close();
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void closeTwiceIsSafe() {
        Vp8Encoder enc = new Vp8Encoder(320, 180, 100_000, 24);
        Vp8Decoder dec = new Vp8Decoder();
        Simulcast sc = new Simulcast(24);
        enc.close();
        enc.close();
        dec.close();
        dec.close();
        sc.close();
        sc.close();
        assertNull(enc.encode(synth(320, 180, 0), false));
    }
}
