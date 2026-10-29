package com.kartikey.p2pchat.conf.keys;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * One gossip unit of an epoch key distribution: the group key wrapped separately for each recipient of
 * this shard, signed by the issuing admin. Signing the whole body lets any relay verify and forward a
 * bundle without being able to read the key, and sharding keeps each unit small at huge audience sizes.
 */
public final class EpochBundle {

    public static final int MEETING_ID_LEN = 16;
    public static final int FP_LEN = 16;
    public static final int WRAPPED_LEN = Crypto.KEY_LEN + Crypto.TAG_LEN;
    public static final int MAX_ENTRIES = 65535;
    private static final int EPH_LEN = 44;
    private static final byte[] DOMAIN = "p2p-conf/v1 epoch-bundle".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO = "p2p-conf/v1 epoch wrap".getBytes(StandardCharsets.UTF_8);
    /** Fixed nonce: safe because each wrap key is unique per recipient per bundle (fresh ephemeral key). */
    private static final byte[] ZERO_NONCE = new byte[Crypto.NONCE_LEN];

    /** A recipient's fingerprint (16 raw bytes) and the epoch key wrapped to them (48 bytes). */
    public record Entry(byte[] fingerprint, byte[] wrapped) {}

    private final byte[] meetingId;
    private final long epoch;
    private final ParticipantId issuer;
    private final long issuedAtMillis;
    private final int shardIndex;
    private final int shardCount;
    private final byte[] ephemeralPublic;
    private final List<Entry> entries;
    private final byte[] signature;

    public EpochBundle(byte[] meetingId, long epoch, ParticipantId issuer, long issuedAtMillis, int shardIndex,
                       int shardCount, byte[] ephemeralPublic, List<Entry> entries, byte[] signature) {
        if (meetingId.length != MEETING_ID_LEN || ephemeralPublic.length != EPH_LEN
                || signature.length != Crypto.SIGNATURE_LEN || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("bad bundle field size");
        }
        for (Entry e : entries) {
            if (e.fingerprint().length != FP_LEN || e.wrapped().length != WRAPPED_LEN) {
                throw new IllegalArgumentException("bad entry size");
            }
        }
        this.meetingId = meetingId.clone();
        this.epoch = epoch;
        this.issuer = issuer;
        this.issuedAtMillis = issuedAtMillis;
        this.shardIndex = shardIndex;
        this.shardCount = shardCount;
        this.ephemeralPublic = ephemeralPublic.clone();
        this.entries = List.copyOf(entries);
        this.signature = signature.clone();
    }

    public byte[] meetingId() { return meetingId.clone(); }
    public long epoch() { return epoch; }
    public ParticipantId issuer() { return issuer; }
    public long issuedAtMillis() { return issuedAtMillis; }
    public int shardIndex() { return shardIndex; }
    public int shardCount() { return shardCount; }
    public byte[] ephemeralPublic() { return ephemeralPublic.clone(); }
    public List<Entry> entries() { return entries; }

    /** Splits recipients into shards of at most maxPerShard, each a separately signed bundle. */
    public static List<EpochBundle> create(Identity admin, byte[] meetingId, long epoch, byte[] epochKey,
                                           Collection<ParticipantId> recipients, int maxPerShard, long now) {
        if (maxPerShard < 1 || maxPerShard > MAX_ENTRIES || meetingId.length != MEETING_ID_LEN
                || epochKey.length != Crypto.KEY_LEN) {
            throw new IllegalArgumentException("bad bundle parameters");
        }
        List<ParticipantId> all = new ArrayList<>(recipients);
        int shards = Math.max(1, (all.size() + maxPerShard - 1) / maxPerShard);
        List<EpochBundle> out = new ArrayList<>(shards);
        for (int s = 0; s < shards; s++) {
            KeyPair eph = ephemeralPair();
            byte[] ephPub = eph.getPublic().getEncoded();
            List<ParticipantId> slice = all.subList(s * maxPerShard, Math.min(all.size(), (s + 1) * maxPerShard));
            List<Entry> entries = new ArrayList<>(slice.size());
            for (ParticipantId r : slice) {
                byte[] shared = Crypto.x25519(eph.getPrivate(), r.agreeKey());
                byte[] k = wrapKey(meetingId, epoch, shared, r, ephPub);
                entries.add(new Entry(fingerprint(r), Crypto.seal(k, ZERO_NONCE, aad(meetingId, epoch), epochKey)));
            }
            byte[] body = body(meetingId, epoch, admin.id(), now, s, shards, ephPub, entries);
            out.add(new EpochBundle(meetingId, epoch, admin.id(), now, s, shards, ephPub, entries,
                    admin.sign(signed(body))));
        }
        return out;
    }

    /** True if the issuer's signature covers exactly this content. Says nothing about admin status. */
    public boolean verify() {
        return issuer.verify(signed(body(meetingId, epoch, issuer, issuedAtMillis, shardIndex, shardCount,
                ephemeralPublic, entries)), signature);
    }

    /** The epoch key, or empty if there is no entry for me or it fails authentication. */
    public Optional<byte[]> unwrap(Identity me) {
        byte[] fp = fingerprint(me.id());
        for (Entry e : entries) {
            if (Arrays.equals(e.fingerprint(), fp)) {
                try {
                    byte[] k = wrapKey(meetingId, epoch, me.agree(ephemeralPublic), me.id(), ephemeralPublic);
                    return Optional.of(Crypto.open(k, ZERO_NONCE, aad(meetingId, epoch), e.wrapped()));
                } catch (IllegalArgumentException ex) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    public byte[] encode() {
        byte[] body = body(meetingId, epoch, issuer, issuedAtMillis, shardIndex, shardCount, ephemeralPublic, entries);
        return ByteBuffer.allocate(body.length + signature.length).put(body).put(signature).array();
    }

    public static EpochBundle decode(byte[] data) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            byte[] meeting = new byte[MEETING_ID_LEN];
            b.get(meeting);
            long epoch = b.getLong();
            byte[] idBytes = new byte[ParticipantId.ENCODED_LEN];
            b.get(idBytes);
            ParticipantId issuer = ParticipantId.decode(idBytes, 0);
            long issuedAt = b.getLong();
            int idx = b.getInt(), cnt = b.getInt();
            byte[] eph = new byte[EPH_LEN];
            b.get(eph);
            int n = b.getShort() & 0xFFFF;
            if (b.remaining() != (long) n * (FP_LEN + WRAPPED_LEN) + Crypto.SIGNATURE_LEN) {
                throw new IllegalArgumentException("bundle length mismatch");
            }
            List<Entry> entries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                byte[] fp = new byte[FP_LEN], w = new byte[WRAPPED_LEN];
                b.get(fp);
                b.get(w);
                entries.add(new Entry(fp, w));
            }
            byte[] sig = new byte[Crypto.SIGNATURE_LEN];
            b.get(sig);
            return new EpochBundle(meeting, epoch, issuer, issuedAt, idx, cnt, eph, entries, sig);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated bundle");
        }
    }

    private static byte[] body(byte[] meetingId, long epoch, ParticipantId issuer, long issuedAt, int idx, int cnt,
                               byte[] eph, List<Entry> entries) {
        ByteBuffer b = ByteBuffer.allocate(MEETING_ID_LEN + 8 + ParticipantId.ENCODED_LEN + 8 + 4 + 4 + EPH_LEN + 2
                + entries.size() * (FP_LEN + WRAPPED_LEN));
        b.put(meetingId).putLong(epoch).put(issuer.encoded()).putLong(issuedAt).putInt(idx).putInt(cnt).put(eph)
                .putShort((short) entries.size());
        for (Entry e : entries) {
            b.put(e.fingerprint()).put(e.wrapped());
        }
        return b.array();
    }

    /** Domain-separates the signature so it cannot be replayed as any other signed message. */
    private static byte[] signed(byte[] body) {
        return ByteBuffer.allocate(DOMAIN.length + body.length).put(DOMAIN).put(body).array();
    }

    private static byte[] aad(byte[] meetingId, long epoch) {
        return ByteBuffer.allocate(MEETING_ID_LEN + 8).put(meetingId).putLong(epoch).array();
    }

    private static byte[] wrapKey(byte[] meetingId, long epoch, byte[] shared, ParticipantId r, byte[] ephPub) {
        byte[] info = ByteBuffer.allocate(INFO.length + ParticipantId.ENCODED_LEN + ephPub.length)
                .put(INFO).put(r.encoded()).put(ephPub).array();
        return Crypto.hkdf(aad(meetingId, epoch), shared, info, Crypto.KEY_LEN);
    }

    private static byte[] fingerprint(ParticipantId id) {
        return Arrays.copyOf(Crypto.sha256(id.encoded()), FP_LEN);
    }

    private static KeyPair ephemeralPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }
}
