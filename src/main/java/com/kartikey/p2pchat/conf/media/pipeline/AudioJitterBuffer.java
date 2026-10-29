package com.kartikey.p2pchat.conf.media.pipeline;

import com.kartikey.p2pchat.conf.media.MediaFormat;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Smooths out network jitter for one audio stream. The player calls {@link #poll} every 20 ms;
 * a null result means "this frame is lost, conceal it". Playout starts once the first packet has
 * waited the target delay, which adapts to the observed jitter (95th percentile of inter-arrival
 * deviation plus one frame, clamped). After an underrun the buffer re-buffers with the
 * then-current target, so a stream that turned jittery gets more headroom. Not thread-safe.
 */
public final class AudioJitterBuffer {
    private static final int WINDOW = 100;
    /** A seq jump beyond this (1 s of audio) is a sender restart or long outage: resync. */
    private static final int RESYNC_DISTANCE = 50;

    private final int minDelay;
    private final int maxDelay;
    private final TreeMap<Long, byte[]> packets = new TreeMap<>();
    private final long[] deviations = new long[WINDOW];
    private int devCount;
    private int devPos;

    private boolean initialised;
    private boolean buffering = true;
    private boolean everPlayed;
    private long bufferingSince;
    private long nextSeq;
    private long highestSeq;
    private long lastArrival;
    private int target;

    private long played, concealed, late;

    public AudioJitterBuffer() {
        this(40, 200);
    }

    public AudioJitterBuffer(int minDelayMillis, int maxDelayMillis) {
        if (minDelayMillis < 0 || maxDelayMillis < minDelayMillis) throw new IllegalArgumentException("delays");
        this.minDelay = minDelayMillis;
        this.maxDelay = maxDelayMillis;
        this.target = minDelayMillis;
    }

    public void put(long seq, byte[] packet, long arrivalMillis) {
        if (!initialised) {
            initialised = true;
            resync(seq, arrivalMillis);
        } else if (Math.abs(seq - nextSeq) > RESYNC_DISTANCE) {
            resync(seq, arrivalMillis);
        } else if (seq < nextSeq) {
            late++;
            return;
        } else {
            observe(seq, arrivalMillis);
        }
        packets.putIfAbsent(seq, packet);
    }

    private void resync(long seq, long now) {
        packets.clear();
        devCount = 0;
        devPos = 0;
        buffering = true;
        bufferingSince = now;
        nextSeq = seq;
        highestSeq = seq;
        lastArrival = now;
        target = minDelay;
    }

    private void observe(long seq, long now) {
        if (seq <= highestSeq) return;
        long expected = (seq - highestSeq) * MediaFormat.FRAME_MILLIS;
        deviations[devPos] = Math.abs((now - lastArrival) - expected);
        devPos = (devPos + 1) % WINDOW;
        devCount = Math.min(devCount + 1, WINDOW);
        highestSeq = seq;
        lastArrival = now;
        long[] sorted = Arrays.copyOf(deviations, devCount);
        Arrays.sort(sorted);
        long p95 = sorted[Math.min(devCount - 1, (int) Math.ceil(devCount * 0.95) - 1)];
        target = (int) Math.max(minDelay, Math.min(maxDelay, p95 + MediaFormat.FRAME_MILLIS));
    }

    /** @return the next frame's packet, or null if it was lost (or playout is still buffering) */
    public byte[] poll(long nowMillis) {
        if (buffering) {
            if (packets.isEmpty() || nowMillis - bufferingSince < target) {
                if (everPlayed) concealed++;
                return null;
            }
            buffering = false;
            nextSeq = packets.firstKey();
        }
        if (packets.isEmpty()) { // underrun: wait for more, with the current target
            buffering = true;
            bufferingSince = nowMillis;
            concealed++;
            return null;
        }
        byte[] p = packets.remove(nextSeq);
        nextSeq++;
        everPlayed = true;
        if (p == null) {
            concealed++;
            return null;
        }
        played++;
        return p;
    }

    /** True while waiting for the initial (or post-underrun) delay to elapse. */
    public boolean isBuffering() { return buffering; }

    public int targetDelayMillis() { return target; }

    public long played() { return played; }
    public long concealed() { return concealed; }
    public long late() { return late; }
}
