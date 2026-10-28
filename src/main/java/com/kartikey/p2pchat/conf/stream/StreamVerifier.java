package com.kartikey.p2pchat.conf.stream;

import com.kartikey.p2pchat.conf.ParticipantId;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Receiver-side gate: a media packet is released only if it matches a hash in a manifest whose
 * signature verifies against the sender. Packets that arrive before their manifest are buffered
 * (bounded by count and age). Methods are synchronised.
 */
public final class StreamVerifier {
    private static final int MAX_MANIFESTS = 256;
    private static final int MAX_RELEASED = 8192;

    private record Pending(byte[] packet, long seq, long arrived) {}

    private final ParticipantId sender;
    private final long streamId;
    private final int maxPending;
    private final long maxAgeMillis;

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final TreeMap<Long, Manifest> manifests = new TreeMap<>();
    /** Recently released seqs, for replay detection; bounded so a long stream cannot grow it. */
    private final Map<Long, byte[]> released = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
            return size() > MAX_RELEASED;
        }
    };

    private long verified, tampered, forgedManifests, unverifiedDropped, replays;

    public StreamVerifier(ParticipantId sender, long streamId, int maxPending, long maxAgeMillis) {
        this.sender = sender;
        this.streamId = streamId;
        this.maxPending = maxPending;
        this.maxAgeMillis = maxAgeMillis;
    }

    /** Returns the packets (possibly none, possibly several) that are now verified and may be opened. */
    public synchronized List<byte[]> onPacket(byte[] packet, long now) {
        List<byte[]> out = new ArrayList<>();
        evict(now);
        MediaHeader h;
        try {
            h = MediaHeader.decode(packet);
        } catch (IllegalArgumentException e) {
            tampered++;
            return out;
        }
        if (h.streamId() != streamId) {
            tampered++;
            return out;
        }
        if (h.kind() == MediaHeader.Kind.MANIFEST) {
            onManifest(packet, out);
            return out;
        }
        if (released.containsKey(h.seq())) {
            replays++;
            return out;
        }
        Manifest m = covering(h.seq());
        if (m != null) {
            if (matches(m, h.seq(), packet)) release(h.seq(), packet, out);
            else tampered++;
            return out;
        }
        pending.addLast(new Pending(packet, h.seq(), now));
        evict(now);
        return out;
    }

    private void onManifest(byte[] packet, List<byte[]> out) {
        Manifest m;
        try {
            m = Manifest.decode(packet);
        } catch (IllegalArgumentException e) {
            forgedManifests++;
            return;
        }
        if (m.streamId() != streamId || !m.verify(sender)) {
            forgedManifests++;
            return;
        }
        // Overlapping manifests must agree with what we already trust; the sender never signs two
        // different hashes for one seq, so a disagreement means forgery (or a compromised key).
        for (int i = 0; i < m.hashes().size(); i++) {
            long seq = m.firstSeq() + i;
            byte[] known = released.get(seq);
            if (known == null) {
                Manifest c = covering(seq);
                if (c != null) known = c.hashes().get((int) (seq - c.firstSeq()));
            }
            if (known != null && !MessageDigest.isEqual(known, m.hashes().get(i))) {
                forgedManifests++;
                return;
            }
        }
        manifests.put(m.firstSeq(), m);
        while (manifests.size() > MAX_MANIFESTS) manifests.pollFirstEntry();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.seq < m.firstSeq() || p.seq >= m.firstSeq() + m.hashes().size()) continue;
            it.remove();
            if (released.containsKey(p.seq)) replays++;
            else if (matches(m, p.seq, p.packet)) release(p.seq, p.packet, out);
            else tampered++;
        }
    }

    private Manifest covering(long seq) {
        for (Manifest m : manifests.headMap(seq, true).descendingMap().values()) {
            if (seq < m.firstSeq() + m.hashes().size()) return m;
            if (m.firstSeq() < seq - Manifest.MAX_HASHES) break;
        }
        return null;
    }

    private static boolean matches(Manifest m, long seq, byte[] packet) {
        return MessageDigest.isEqual(m.hashes().get((int) (seq - m.firstSeq())), Manifest.hash(packet));
    }

    private void release(long seq, byte[] packet, List<byte[]> out) {
        released.put(seq, Manifest.hash(packet));
        verified++;
        out.add(packet);
    }

    private void evict(long now) {
        while (!pending.isEmpty()
                && (pending.size() > maxPending || now - pending.peekFirst().arrived > maxAgeMillis)) {
            pending.pollFirst();
            unverifiedDropped++;
        }
    }

    public synchronized long verified() { return verified; }
    public synchronized long tampered() { return tampered; }
    public synchronized long forgedManifests() { return forgedManifests; }
    public synchronized long unverifiedDropped() { return unverifiedDropped; }
    public synchronized long replays() { return replays; }
}
