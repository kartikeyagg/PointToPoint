package com.kartikey.p2pchat.conf.media;

/** Fixed media parameters every participant agrees on, so nothing has to be negotiated. */
public final class MediaFormat {

    /** Opus' native rate; capture, mixing and playback all run at it. */
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 1;
    public static final int FRAME_MILLIS = 20;
    /** Samples per 20 ms mono frame. */
    public static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MILLIS / 1000;
    public static final int AUDIO_BITRATE = 24_000;

    /** Simulcast layers, smallest first: index = {@code MediaHeader.layer}. */
    public static final int[][] VIDEO_LAYERS = {
            {320, 180, 150_000},
            {640, 360, 500_000},
            {1280, 720, 1_500_000},
    };
    public static final int VIDEO_FPS = 24;
    /** Keyframe at least this often, so a receiver that joins or loses packets recovers quickly. */
    public static final int KEYFRAME_INTERVAL_FRAMES = VIDEO_FPS * 2;

    /** Encoded media payload per packet, leaving room for headers, tags and the fragment header. */
    public static final int MAX_FRAGMENT = 1000;

    private MediaFormat() {}
}
