package com.kartikey.p2pchat.conf.media.pipeline;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cuts encoded frames into {@link Fragment} payloads that fit one packet. */
public final class Fragmenter {
    private Fragmenter() {}

    /** @return encoded fragment payloads in order; an empty frame still yields one fragment */
    public static List<byte[]> split(int frameId, EncodedFrame f) {
        int n = Math.max(1, (f.data().length + Fragment.MAX_DATA - 1) / Fragment.MAX_DATA);
        if (n > 0xFFFF) throw new IllegalArgumentException("frame too large");
        List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int from = i * Fragment.MAX_DATA;
            byte[] part = Arrays.copyOfRange(f.data(), from, Math.min(f.data().length, from + Fragment.MAX_DATA));
            out.add(new Fragment(frameId, i, n, f.keyframe(), f.width(), f.height(),
                    f.timestampMicros(), part).encode());
        }
        return out;
    }
}
