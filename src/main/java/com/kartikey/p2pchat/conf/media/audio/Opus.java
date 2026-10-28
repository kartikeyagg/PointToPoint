package com.kartikey.p2pchat.conf.media.audio;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacpp.Loader;

/** Probe for the FFmpeg/libopus natives, so callers and tests can skip instead of crash. */
public final class Opus {

    private static final boolean AVAILABLE = probe();

    private Opus() {}

    public static boolean available() {
        return AVAILABLE;
    }

    private static boolean probe() {
        try {
            Loader.load(avcodec.class);
            return avcodec.avcodec_find_encoder_by_name("libopus") != null
                    && avcodec.avcodec_find_decoder_by_name("libopus") != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
