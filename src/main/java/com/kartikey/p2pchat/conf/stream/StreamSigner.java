package com.kartikey.p2pchat.conf.stream;

import com.kartikey.p2pchat.conf.Identity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Sender-side batching: collects hashes of sealed packets and emits a signed {@link Manifest} at
 * each batch boundary. A manifest covers the last {@code window} packets (default 2 x batch), so
 * every packet is covered by two manifests and one lost manifest costs nothing. Manifests always
 * cover consecutive seqs; a gap or out-of-order seq closes the current batch and restarts the window.
 */
public final class StreamSigner {
    private final Identity sender;
    private final long streamId;
    private final int batchSize;
    private final int window;
    /** Hashes of the most recent consecutive run (at most {@code window}); first one has seq histFirst. */
    private final ArrayDeque<byte[]> hist = new ArrayDeque<>();
    private long histFirst;
    private int uncovered;
    private long oldestUncoveredAt;

    public StreamSigner(Identity sender, long streamId, int batchSize) {
        this(sender, streamId, batchSize, Math.min(2 * batchSize, Manifest.MAX_HASHES));
    }

    public StreamSigner(Identity sender, long streamId, int batchSize, int window) {
        if (batchSize < 1 || batchSize > Manifest.MAX_HASHES) throw new IllegalArgumentException("batchSize");
        if (window < batchSize || window > Manifest.MAX_HASHES) throw new IllegalArgumentException("window");
        this.sender = sender;
        this.streamId = streamId;
        this.batchSize = batchSize;
        this.window = window;
    }

    public List<byte[]> onSend(byte[] sealedPacket) {
        return onSend(sealedPacket, System.currentTimeMillis());
    }

    /**
     * Returns the packet, preceded by a manifest of the old run if seqs were not consecutive, and
     * followed by a manifest if the batch is full. {@code nowMillis} feeds {@link #flushIfOlderThan}.
     */
    public synchronized List<byte[]> onSend(byte[] sealedPacket, long nowMillis) {
        MediaHeader h = MediaHeader.decode(sealedPacket);
        if (h.streamId() != streamId || h.kind() == MediaHeader.Kind.MANIFEST)
            throw new IllegalArgumentException("packet does not belong to this stream");
        List<byte[]> out = new ArrayList<>(3);
        if (!hist.isEmpty() && h.seq() != histFirst + hist.size()) {
            out.addAll(flush());
            hist.clear();
        }
        if (hist.isEmpty()) histFirst = h.seq();
        if (uncovered == 0) oldestUncoveredAt = nowMillis;
        hist.addLast(Manifest.hash(sealedPacket));
        uncovered++;
        while (hist.size() > window) {
            hist.pollFirst();
            histFirst++;
        }
        out.add(sealedPacket);
        if (uncovered >= batchSize) out.addAll(flush());
        return out;
    }

    /** Emits a manifest if any packet is not yet covered (call every ~50 ms); empty otherwise. */
    public synchronized List<byte[]> flush() {
        List<byte[]> out = new ArrayList<>(1);
        if (uncovered == 0) return out;
        out.add(Manifest.sign(sender, streamId, histFirst, new ArrayList<>(hist)).encode());
        uncovered = 0;
        return out;
    }

    /** Bounds verification latency: flushes only if the oldest uncovered packet has waited maxWaitMillis. */
    public synchronized List<byte[]> flushIfOlderThan(long nowMillis, int maxWaitMillis) {
        if (uncovered > 0 && nowMillis - oldestUncoveredAt >= maxWaitMillis) return flush();
        return new ArrayList<>(0);
    }
}
