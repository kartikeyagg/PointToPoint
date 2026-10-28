package com.kartikey.p2pchat.conf.media.video;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.I420;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One VP8 encoder per simulcast layer, so each receiver can pick the resolution it can afford.
 * Layers nobody watches are disabled and cost no CPU; re-enabling one starts with a keyframe.
 */
public final class Simulcast implements AutoCloseable {

    private final int[][] layers;
    private final Vp8Encoder[] encoders;
    private final boolean[] enabled;
    private final boolean[] wantKey;

    public Simulcast(int fps) {
        this(MediaFormat.VIDEO_LAYERS, fps);
    }

    public Simulcast(int[][] layers, int fps) {
        this.layers = layers;
        encoders = new Vp8Encoder[layers.length];
        enabled = new boolean[layers.length];
        wantKey = new boolean[layers.length];
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
        List<EncodedFrame> out = new ArrayList<>(layers.length);
        for (int i = 0; i < layers.length; i++) {
            if (!enabled[i] || encoders[i] == null) {
                out.add(null);
                continue;
            }
            VideoFrame f = I420.scale(src, layers[i][0], layers[i][1]);
            boolean key = forceKeyframe || wantKey[i];
            wantKey[i] = false;
            out.add(encoders[i].encode(f, key));
        }
        return out;
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
        for (int i = 0; i < encoders.length; i++) {
            if (encoders[i] != null) {
                encoders[i].close();
                encoders[i] = null;
            }
        }
    }
}
