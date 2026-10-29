package com.kartikey.p2pchat.conf.media.video;

import org.bytedeco.ffmpeg.global.avcodec;

/** Probes whether the FFmpeg natives load and contain libvpx, so callers can degrade instead of crashing. */
public final class Vp8 {

    private static Boolean available;

    private Vp8() {}

    /** @return false (never throws) when natives are missing or built without libvpx */
    public static synchronized boolean available() {
        if (available == null) {
            try {
                available = avcodec.avcodec_find_encoder_by_name("libvpx") != null
                        && avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_VP8) != null;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }
}
