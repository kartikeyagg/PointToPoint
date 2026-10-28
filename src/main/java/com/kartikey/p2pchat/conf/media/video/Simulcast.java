package com.kartikey.p2pchat.conf.media.video;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One VP8 encoder per simulcast layer, so each receiver can pick the resolution it can afford.
 * Layers nobody watches are disabled and cost no CPU; re-enabling one starts with a keyframe.
 * Layers are independent, so they encode in parallel (the caller's thread does layer 0).
 */
public final class Simulcast implements AutoCloseable {

    private final int[][] layers;
    private final Vp8Encoder[] encoders;
    private final boolean[] enabled;
    private final boolean[] wantKey;
    /** Reused per-layer scaled pictures: the encoder copies them synchronously, so no per-frame allocation. */
    private final VideoFrame[] scaled;
    private final ExecutorService pool;

    public Simulcast(int fps) {
        this(MediaFormat.VIDEO_LAYERS, fps);
    }

    public Simulcast(int[][] layers, int fps) {
        this.layers = layers;
        encoders = new Vp8Encoder[layers.length];
        enabled = new boolean[layers.length];
        wantKey = new boolean[layers.length];
        scaled = new VideoFrame[layers.length];
        pool = Executors.newFixedThreadPool(Math.max(1, layers.length - 1), r -> {
            Thread t = new Thread(r, "simulcast-enc");
            t.setDaemon(true);
            return t;
        });
        Arrays.fill(enabled, true);
        Arrays.fill(wantKey, true);
        try {
            for (int i = 0; i < layers.length; i++) {
                encoders[i] = new Vp8Encoder(layers[i][0], layers[i][1], layers[i][2], fps);
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** @return one entry per layer (index = layer); null for disabled layers or when nothing came out */
    public synchronized List<EncodedFrame> encode(VideoFrame src, boolean forceKeyframe) {
        EncodedFrame[] out = new EncodedFrame[layers.length];
        List<Future<?>> pending = new ArrayList<>();
        for (int i = layers.length - 1; i >= 0; i--) {
            if (!enabled[i] || encoders[i] == null) {
                continue;
            }
            int layer = i;
            boolean key = forceKeyframe || wantKey[i];
            wantKey[i] = false;
            Runnable job = () -> out[layer] = encoders[layer].encode(scaleTo(src, layer), key);
            if (i == 0) {
                job.run();
            } else {
                pending.add(pool.submit(job));
            }
        }
        for (Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        return Arrays.asList(out);
    }

    private VideoFrame scaleTo(VideoFrame src, int layer) {
        int w = layers[layer][0] & ~1, h = layers[layer][1] & ~1;
        if (src.width() == w && src.height() == h) {
            return src;
        }
        VideoFrame dst = scaled[layer];
        if (dst == null) {
            dst = scaled[layer] = new VideoFrame(w, h, new byte[VideoFrame.size(w, h)], 0);
        }
        byte[] s = src.data(), d = dst.data();
        scalePlane(s, 0, src.width(), src.height(), d, 0, w, h);
        scalePlane(s, src.uOffset(), src.width() / 2, src.height() / 2, d, w * h, w / 2, h / 2);
        scalePlane(s, src.vOffset(), src.width() / 2, src.height() / 2, d, w * h + (w / 2) * (h / 2), w / 2, h / 2);
        return new VideoFrame(w, h, d, src.timestampMicros());
    }

    /** Nearest-neighbour like {@code I420.scale}, with the per-column source index hoisted out of the row loop. */
    private static void scalePlane(byte[] src, int so, int sw, int sh, byte[] dst, int dOff, int dw, int dh) {
        int[] xs = new int[dw];
        for (int x = 0; x < dw; x++) {
            xs[x] = x * sw / dw;
        }
        for (int y = 0; y < dh; y++) {
            int row = so + (y * sh / dh) * sw, o = dOff + y * dw;
            for (int x = 0; x < dw; x++) {
                dst[o + x] = src[row + xs[x]];
            }
        }
    }

    public synchronized void setLayerEnabled(int layer, boolean on) {
        if (on && !enabled[layer]) {
            wantKey[layer] = true;
        }
        enabled[layer] = on;
    }

    public synchronized void requestKeyframe(int layer) {
        wantKey[layer] = true;
    }

    public synchronized void setLayerBitrate(int layer, int bitsPerSecond) {
        encoders[layer].setBitrate(bitsPerSecond);
    }

    @Override
    public synchronized void close() {
        pool.shutdown();
        for (int i = 0; i < encoders.length; i++) {
            if (encoders[i] != null) {
                encoders[i].close();
                encoders[i] = null;
            }
        }
    }
}
