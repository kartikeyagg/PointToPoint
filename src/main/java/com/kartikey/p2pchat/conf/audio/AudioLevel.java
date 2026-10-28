package com.kartikey.p2pchat.conf.audio;

/**
 * Loudness in the RFC 6464 convention: 0 = loudest (full scale), 127 = silence. Relays
 * read this single byte to pick speakers without ever decoding audio.
 */
public final class AudioLevel {

    public static final int SILENCE = 127;

    private AudioLevel() {}

    /** RMS of the frame in dB below full scale (dBov), rounded and clamped to 0..127. */
    public static int of(short[] frame) {
        if (frame.length == 0) {
            return SILENCE;
        }
        double sum = 0;
        for (short s : frame) {
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / frame.length) / 32768.0;
        if (rms <= 0) {
            return SILENCE;
        }
        long db = Math.round(-20 * Math.log10(rms));
        return (int) Math.max(0, Math.min(SILENCE, db));
    }
}
