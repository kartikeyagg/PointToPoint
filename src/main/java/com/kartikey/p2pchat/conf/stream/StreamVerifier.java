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
 *
 * <p>Replay defences: the current sender session is learned only from a validly signed manifest;
 * traffic from an older session is dropped and a newer session resets all state. Within a session,
 * a high-water mark rejects seqs more than {@code MAX_RELEASED} behind the newest verified one, so
 * the bounded released-set cannot be bypassed by replaying very old packets.
 */
public final class StreamVerifier {
    private static final int MAX_MANIFESTS = 256;
    /** Size of the replay window: released seqs remembered, and how far behind the high-water mark is accepted. */
    private static final int MAX_RELEASED = 8192;

    private record Pending(byte[] packet, long seq, long session, long arrived) {}

    private final ParticipantId sender;
    private final long streamId;
    private final int maxPending;
    private final long maxAgeMillis;

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final TreeMap<Long, Manifest> manifests = new TreeMap<>();
    private final Map<Long, byte[]> released = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
            return size() > MAX_RELEASED;
        }
    };
    private boolean haveSession;
    private long session;
    private boolean haveHigh;
    private long highSeq;

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
        if (haveSession && h.session() < session) {
            replays++;
            return out;
        }
        if (haveSession && h.session() == session) {
            if (belowFloor(h.seq()) || released.containsKey(h.seq())) {
                replays++;
                return out;
            }
            Manifest m = covering(h.seq());
            if (m != null) {
                if (matches(m, h.seq(), packet)) release(h.seq(), packet, out);
                else tampered++;
                return out;
            }
        }
        // Unknown session, or a newer one not yet proven by a manifest: wait for a signed manifest.
        pending.addLast(new Pending(packet, h.seq(), h.session(), now));
        evict(now);
        return out;
    }

    private boolean belowFloor(long seq) {
        return haveHigh && seq < highSeq - MAX_RELEASED;
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
        if (haveSession && m.session() < session) { // genuine but stale: a relay replaying an old run
            replays++;
            return;
        }
        if (!haveSession || m.session() > session) {
            // A newer session, proven by the sender's signature: forget the old run entirely.
            session = m.session();
            haveSession = true;
            haveHigh = false;
            manifests.clear();
            released.clear();
            pending.removeIf(p -> p.session < session);
        }
        if (belowFloor(m.firstSeq())) {
            replays++;
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
        raiseHigh(m.firstSeq() + m.hashes().size() - 1);
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.session != session || p.seq < m.firstSeq() || p.seq >= m.firstSeq() + m.hashes().size()) continue;
            it.remove();
            if (released.containsKey(p.seq)) replays++;
            else if (matches(m, p.seq, p.packet)) release(p.seq, p.packet, out);
            else tampered++;
        }
    }

    private void raiseHigh(long seq) {
        if (!haveHigh || seq > highSeq) {
            highSeq = seq;
            haveHigh = true;
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
