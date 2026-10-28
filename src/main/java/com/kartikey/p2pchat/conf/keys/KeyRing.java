package com.kartikey.p2pchat.conf.keys;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;

import java.util.Arrays;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Receiver-side epoch key store. Keeps the newest few epochs so late packets from the previous epoch
 * still decrypt, while older keys are dropped for forward secrecy against later compromise.
 */
public final class KeyRing {

    public enum Result { ACCEPTED, NOT_RECIPIENT, BAD_SIGNATURE, NOT_ADMIN, STALE, WRONG_MEETING, DUPLICATE }

    private static final int RETAIN = 3;

    private final byte[] meetingId;
    private final TreeMap<Long, byte[]> keys = new TreeMap<>();

    public KeyRing(byte[] meetingId) {
        this.meetingId = meetingId.clone();
    }

    public synchronized Result accept(EpochBundle bundle, Identity me, Predicate<ParticipantId> isAdmin) {
        if (!Arrays.equals(bundle.meetingId(), meetingId)) {
            return Result.WRONG_MEETING;
        }
        if (bundle.epoch() < currentEpoch() - 2) {
            return Result.STALE;
        }
        if (keys.containsKey(bundle.epoch())) {
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
        keys.put(bundle.epoch(), key.get());
        while (keys.size() > RETAIN) {
            keys.pollFirstEntry();
        }
        return Result.ACCEPTED;
    }

    public synchronized Optional<byte[]> key(long epoch) {
        byte[] k = keys.get(epoch);
        return k == null ? Optional.empty() : Optional.of(k.clone());
    }

    /** Newest epoch held, or -1 if none. */
    public synchronized long currentEpoch() {
        return keys.isEmpty() ? -1 : keys.lastKey();
    }

    public synchronized Optional<byte[]> currentKey() {
        return keys.isEmpty() ? Optional.empty() : key(keys.lastKey());
    }
}
