package com.kartikey.p2pchat.conf.keys;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Receiver-side epoch key store. Keeps the newest few epochs so late packets from the previous epoch
 * still decrypt, while older keys are dropped for forward secrecy against later compromise.
 * Epochs are bounded (32-bit, and a few ahead of the current one) so a rogue admin cannot pin the ring
 * to a huge epoch, and concurrent rotations of one epoch converge on the lowest-sorting issuer's key.
 */
public final class KeyRing {

    public enum Result { ACCEPTED, REPLACED, NOT_RECIPIENT, BAD_SIGNATURE, NOT_ADMIN, STALE, TOO_FAR, WRONG_MEETING,
        DUPLICATE }

    public static final long MAX_EPOCH = 0xFFFFFFFFL;
    private static final int RETAIN = 3;
    private static final int MAX_AHEAD = 4;
    private static final int MAX_CANDIDATES = 2;

    private record Held(ParticipantId issuer, byte[] key) {}

    private final byte[] meetingId;
    /** Per epoch, candidates sorted by issuer; index 0 is the deterministic winner. */
    private final TreeMap<Long, List<Held>> keys = new TreeMap<>();

    public KeyRing(byte[] meetingId) {
        this.meetingId = meetingId.clone();
    }

    public synchronized Result accept(EpochBundle bundle, Identity me, Predicate<ParticipantId> isAdmin) {
        if (!Arrays.equals(bundle.meetingId(), meetingId)) {
            return Result.WRONG_MEETING;
        }
        long epoch = bundle.epoch();
        long cur = currentEpoch();
        if (epoch < 0 || epoch > MAX_EPOCH || (cur >= 0 && epoch > cur + MAX_AHEAD)) {
            return Result.TOO_FAR;
        }
        if (epoch < cur - 2) {
            return Result.STALE;
        }
        List<Held> held = keys.get(epoch);
        if (held != null && held.stream().anyMatch(h -> h.issuer().equals(bundle.issuer()))) {
            return Result.DUPLICATE;
        }
        if (!bundle.verify()) {
            return Result.BAD_SIGNATURE;
        }
        if (!isAdmin.test(bundle.issuer())) {
            return Result.NOT_ADMIN;
        }
        Optional<byte[]> key = bundle.unwrap(me);
        if (key.isEmpty()) {
            return Result.NOT_RECIPIENT;
        }
        List<Held> list = held == null ? new ArrayList<>() : held;
        list.add(new Held(bundle.issuer(), key.get()));
        list.sort((a, b) -> a.issuer().compareTo(b.issuer()));
        boolean wins = list.get(0).issuer().equals(bundle.issuer());
        while (list.size() > MAX_CANDIDATES) {
            list.remove(list.size() - 1);
        }
        keys.put(epoch, list);
        while (keys.size() > RETAIN) {
            keys.pollFirstEntry();
        }
        if (list.size() == 1) {
            return Result.ACCEPTED;
        }
        return wins ? Result.REPLACED : Result.DUPLICATE;
    }

    public synchronized Optional<byte[]> key(long epoch) {
        List<Held> l = keys.get(epoch);
        return l == null ? Optional.empty() : Optional.of(l.get(0).key().clone());
    }

    /** Keys held for an epoch, winner first (at most two), for packets sent before a split resolved. */
    public synchronized List<byte[]> candidates(long epoch) {
        List<byte[]> out = new ArrayList<>();
        for (Held h : keys.getOrDefault(epoch, List.of())) {
            out.add(h.key().clone());
        }
        return out;
    }

    /** Newest epoch held, or -1 if none. */
    public synchronized long currentEpoch() {
        return keys.isEmpty() ? -1 : keys.lastKey();
    }

    public synchronized Optional<byte[]> currentKey() {
        return keys.isEmpty() ? Optional.empty() : key(keys.lastKey());
    }
}
