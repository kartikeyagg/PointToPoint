package com.kartikey.p2pchat;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Precomputes GCM keystreams ("the XOR arrays") for the next {@code depth} packet counters on a
 * background thread, so the send and receive paths only XOR and GHASH (for frames small enough
 * to take that path, see {@link GcmCipher#PRECOMPUTE_MAX}).
 *
 * <p>Single consumer: one thread at a time calls {@link #take} and {@link #release}. Counters
 * are expected to rise roughly one by one; anything outside the window is simply not cached
 * and the caller computes it inline.
 */
final class KeystreamPool implements AutoCloseable {

    private final GcmCipher gcm;
    private final int depth;
    private final byte[][] slots;
    /** Counter whose keystream is in each slot, or -1 while empty/being written. */
    private final AtomicLongArray ready;
    private final Thread producer;

    /** Counters below this are no longer needed; the producer works on [base, base + depth). */
    private volatile long base;
    private volatile boolean closed;
    /** Set while the producer sleeps on a full window, so {@link #release} only wakes it then. */
    private volatile boolean idle;

    KeystreamPool(GcmCipher gcm, long firstCounter, int depth, String name) {
        this.gcm = gcm;
        this.depth = depth;
        this.slots = new byte[depth][];
        this.ready = new AtomicLongArray(depth);
        for (int i = 0; i < depth; i++) {
            ready.set(i, -1);
        }
        this.base = firstCounter;
        this.producer = new Thread(this::fill, name);
        producer.setDaemon(true);
        producer.start();
    }

    /** The precomputed keystream for {@code counter}, or null if it is not ready. */
    byte[] take(long counter) {
        long b = base;
        if (counter < b || counter >= b + depth) {
            return null;
        }
        int s = slot(counter);
        return ready.get(s) == counter ? slots[s] : null;
    }

    /** Marks every counter up to and including {@code counter} as used. */
    void release(long counter) {
        if (counter >= base) {
            base = counter + 1;
            if (idle) {
                LockSupport.unpark(producer); // unpark costs ~1 us; skip it while the producer is busy
            }
        }
    }

    private int slot(long counter) {
        return (int) Math.floorMod(counter, (long) depth);
    }

    private void fill() {
        long next = base;
        while (!closed) {
            long b = base;
            if (next < b) {
                next = b;
            }
            if (next >= b + depth) {
                idle = true;
                if (next >= base + depth) { // re-check so a release just before idle=true is not missed
                    LockSupport.parkNanos(this, 50_000_000L);
                }
                idle = false;
                continue;
            }
            // The slot's previous counter (next - depth) is below base, so nobody reads it now.
            int s = slot(next);
            ready.set(s, -1);
            byte[] ks = slots[s];
            if (ks == null) {
                ks = slots[s] = gcm.newKeystream();
            }
            gcm.keystream(next, ks);
            ready.set(s, next);
            next++;
        }
    }

    @Override
    public void close() {
        closed = true;
        LockSupport.unpark(producer);
    }
}
