package com.kartikey.p2pchat.conf.audio;

import java.util.Arrays;

/**
 * Sums one 20 ms frame per input and soft-limits the result. A relay with thousands of voices below
 * it cannot normalise by input count (that would make a lone voice inaudible), so instead the sum is
 * passed through a limiter that is exactly linear up to a knee and then compresses smoothly toward
 * full scale, which never hard-clips however many voices pile up. Not thread-safe.
 */
public final class CrowdMixer {

    /** Result of one mixing round. */
    public record MixResult(short[] samples, int participants) {}

    private static final double MAX = 32767.0;
    private static final double KNEE = 24000.0;

    private final int frameSamples;
    private final long[] sum;
    private int participants;

    public CrowdMixer(int frameSamples) {
        if (frameSamples <= 0) {
            throw new IllegalArgumentException("frameSamples must be positive");
        }
        this.frameSamples = frameSamples;
        this.sum = new long[frameSamples];
    }

    /** Adds an input that already contains participants voices (a child's mix may hold thousands). */
    public void add(short[] frame, int participants) {
        if (frame.length != frameSamples) {
            throw new IllegalArgumentException("frame must have " + frameSamples + " samples");
        }
        if (participants < 0) {
            throw new IllegalArgumentException("negative participants");
        }
        for (int i = 0; i < frameSamples; i++) {
            sum[i] += frame[i];
        }
        this.participants += participants;
    }

    /** Returns the limited mix and total participant count, then resets for the next frame. */
    public MixResult mix() {
        short[] out = new short[frameSamples];
        for (int i = 0; i < frameSamples; i++) {
            out[i] = limit(sum[i]);
        }
        MixResult r = new MixResult(out, participants);
        Arrays.fill(sum, 0);
        participants = 0;
        return r;
    }

    static short limit(long v) {
        long a = Math.abs(v);
        if (a <= KNEE) {
            return (short) v;
        }
        double head = MAX - KNEE;
        double y = KNEE + head * Math.tanh((a - KNEE) / head);
        return (short) Math.round(v < 0 ? -y : y);
    }
}
