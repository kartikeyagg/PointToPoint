package com.kartikey.p2pchat.conf.stream;

import com.kartikey.p2pchat.conf.Identity;
import java.util.ArrayList;
import java.util.List;

/**
 * Sender-side batching: collects hashes of sealed packets and emits a signed {@link Manifest} per
 * batch, amortising one signature over many packets. Batches always cover consecutive seqs; a
 * gap or out-of-order seq closes the current batch first.
 */
public final class StreamSigner {
    private final Identity sender;
    private final long streamId;
    private final int batchSize;
    private final List<byte[]> hashes = new ArrayList<>();
    private long firstSeq;

    public StreamSigner(Identity sender, long streamId, int batchSize) {
        if (batchSize < 1 || batchSize > Manifest.MAX_HASHES) throw new IllegalArgumentException("batchSize");
        this.sender = sender;
        this.streamId = streamId;
        this.batchSize = batchSize;
    }

    /** Returns the packet, preceded by a manifest of the old batch if it was broken, and followed by one if full. */
    public synchronized List<byte[]> onSend(byte[] sealedPacket) {
        MediaHeader h = MediaHeader.decode(sealedPacket);
        if (h.streamId() != streamId || h.kind() == MediaHeader.Kind.MANIFEST)
            throw new IllegalArgumentException("packet does not belong to this stream");
        List<byte[]> out = new ArrayList<>(3);
        if (!hashes.isEmpty() && h.seq() != firstSeq + hashes.size()) out.addAll(flush());
        if (hashes.isEmpty()) firstSeq = h.seq();
        hashes.add(Manifest.hash(sealedPacket));
        out.add(sealedPacket);
        if (hashes.size() >= batchSize) out.addAll(flush());
        return out;
    }

    /** Emits a manifest for a partial batch (call every ~50 ms); empty if nothing is pending. */
    public synchronized List<byte[]> flush() {
        List<byte[]> out = new ArrayList<>(1);
        if (hashes.isEmpty()) return out;
        out.add(Manifest.sign(sender, streamId, firstSeq, hashes).encode());
        hashes.clear();
        return out;
    }
}
