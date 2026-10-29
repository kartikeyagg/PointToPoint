package com.kartikey.p2pchat.conf.media.pipeline;

import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebuilds frames from fragments arriving in any order. Frames are delivered in completion order
 * and never go backwards: once frame N is out, older incomplete frames are useless to a decoder
 * and are dropped. Any drop (or a gap in frame ids) raises {@link #needsKeyframe()} until a
 * keyframe completes, so the receiver knows to ask the sender for one. Not thread-safe.
 */
public final class FrameAssembler {
    private static final int MAX_PARTIALS = 16;
    /** Retained bytes across all partial frames (data plus slot overhead). */
    private static final long BYTE_BUDGET = 2L * MediaFormat.MAX_ENCODED_FRAME;

    private static final class Partial {
        final byte[][] parts;
        final long firstSeen;
        int received;
        long bytes;

        Partial(int count, long now) {
            parts = new byte[count][];
            firstSeen = now;
            bytes = 8L * count;
        }
    }

    private final long maxAgeMillis;
    private final Map<Integer, Partial> partials = new LinkedHashMap<>();
    private boolean delivered;
    private int newest;
    private boolean needsKeyframe;
    private long framesDropped;
    private long retainedBytes;

    public FrameAssembler(long maxAgeMillis) {
        this.maxAgeMillis = maxAgeMillis;
    }

    /** @return the completed frame, or null if more fragments are needed (or it was stale) */
    public EncodedFrame accept(Fragment f, long nowMillis) {
        expire(nowMillis);
        if (delivered && f.frameId() - newest <= 0) return null; // older than what was already shown
        Partial p = partials.get(f.frameId());
        if (p == null) {
            if (partials.size() >= MAX_PARTIALS) {
                Iterator<Partial> oldest = partials.values().iterator();
                drop(oldest, oldest.next());
            }
            p = new Partial(f.count(), nowMillis);
            partials.put(f.frameId(), p);
            retainedBytes += p.bytes;
        }
        if (p.parts.length != f.count() || p.parts[f.index()] != null) return null; // inconsistent or duplicate
        p.parts[f.index()] = f.data();
        p.received++;
        p.bytes += f.data().length;
        retainedBytes += f.data().length;
        while (retainedBytes > BYTE_BUDGET && partials.size() > 1) evictOldest(p);
        if (p.received < p.parts.length) return null;

        partials.remove(f.frameId());
        retainedBytes -= p.bytes;
        for (Iterator<Map.Entry<Integer, Partial>> it = partials.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Partial> e = it.next();
            if (e.getKey() - f.frameId() < 0) {
                retainedBytes -= e.getValue().bytes;
                it.remove();
                framesDropped++;
                needsKeyframe = true;
            }
        }
        if (delivered && f.frameId() != newest + 1) needsKeyframe = true;
        delivered = true;
        newest = f.frameId();
        if (f.keyframe()) needsKeyframe = false;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : p.parts) out.writeBytes(part);
        return new EncodedFrame(out.toByteArray(), f.keyframe(), f.timestampMicros(), f.width(), f.height());
    }

    /** Drops partial frames that waited too long for their remaining fragments. */
    public void expire(long nowMillis) {
        for (Iterator<Partial> it = partials.values().iterator(); it.hasNext(); ) {
            Partial p = it.next();
            if (nowMillis - p.firstSeen > maxAgeMillis) drop(it, p);
        }
    }

    private void evictOldest(Partial keep) {
        Iterator<Partial> it = partials.values().iterator();
        Partial p = it.next();
        if (p == keep) { p = null; }
        if (p == null) { // oldest is the one being filled: evict the next oldest instead
            p = it.next();
        }
        drop(it, p);
    }

    private void drop(Iterator<Partial> it, Partial p) {
        retainedBytes -= p.bytes;
        it.remove();
        framesDropped++;
        needsKeyframe = true;
    }

    /** True after a frame was lost, until the next keyframe completes. */
    public boolean needsKeyframe() { return needsKeyframe; }

    public long retainedBytes() { return retainedBytes; }

    public long framesDropped() { return framesDropped; }
}
