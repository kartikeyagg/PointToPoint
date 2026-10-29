package com.kartikey.p2pchat.conf.policy;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A signed moderation action. Authority is not checked here (the issuer may no longer be an admin
 * by the time it is replayed); {@link PolicyState} decides that. The lamport clock gives a total
 * order that every node derives identically from the same set of commands.
 *
 * <p>{@code frozen} (REVOKE_ADMIN and KICK only) lists the ids of the target's commands the issuer
 * had accepted. Lamport values are chosen by the issuer, so without it a revoked admin could sign
 * a command with a lamport below the revocation and have it replayed as if issued earlier.
 */
public record AdminCommand(byte[] meetingId, ParticipantId issuer, long lamport, long issuedAtMillis,
                           Kind kind, ParticipantId target, long untilMillis, List<byte[]> frozen, byte[] signature) {

    public static final int MAX_FROZEN = 1024;
    private static final int ID_LEN = 32;

    public enum Kind {
        GRANT_ADMIN(true), REVOKE_ADMIN(true), TRANSFER_HOST(true), ALLOW_SPEAK(true), DENY_SPEAK(true),
        OPEN_MIC_ALL(false), CLOSE_MIC_ALL(false), ALLOW_CAMERA(true), DENY_CAMERA(true), KICK(true),
        END_MEETING(false);

        final boolean targeted;

        Kind(boolean targeted) {
            this.targeted = targeted;
        }
    }

    private static final byte[] DOMAIN = "p2p-conf-admincmd-v1".getBytes(StandardCharsets.UTF_8);
    private static final int BODY_FIXED = MeetingGenesis.ID_LEN + ParticipantId.ENCODED_LEN + 8 + 8 + 1 + 1 + 8 + 2;

    public AdminCommand {
        if (meetingId.length != MeetingGenesis.ID_LEN || signature.length != Crypto.SIGNATURE_LEN) {
            throw new IllegalArgumentException("bad command field length");
        }
        if (kind.targeted != (target != null)) {
            throw new IllegalArgumentException(kind + (kind.targeted ? " needs a target" : " takes no target"));
        }
        if (frozen == null) {
            frozen = List.of();
        }
        if (frozen.size() > MAX_FROZEN || (!frozen.isEmpty() && kind != Kind.KICK && kind != Kind.REVOKE_ADMIN)) {
            throw new IllegalArgumentException("bad frozen set");
        }
        List<byte[]> copy = new ArrayList<>(frozen.size());
        for (byte[] id : frozen) {
            if (id.length != ID_LEN) {
                throw new IllegalArgumentException("bad frozen id");
            }
            copy.add(id.clone());
        }
        frozen = List.copyOf(copy);
        meetingId = meetingId.clone();
        signature = signature.clone();
    }

    public static AdminCommand sign(Identity issuer, byte[] meetingId, Kind kind, ParticipantId target,
                                    long untilMillis, long lamport, long now) {
        return sign(issuer, meetingId, kind, target, untilMillis, lamport, now, List.of());
    }

    /** As above, with the frozen set for a REVOKE_ADMIN or KICK; see {@link PolicyState#frozenFor}. */
    public static AdminCommand sign(Identity issuer, byte[] meetingId, Kind kind, ParticipantId target,
                                    long untilMillis, long lamport, long now, List<byte[]> frozen) {
        AdminCommand unsigned = new AdminCommand(meetingId, issuer.id(), lamport, now, kind, target, untilMillis,
                frozen, new byte[Crypto.SIGNATURE_LEN]);
        return new AdminCommand(meetingId, issuer.id(), lamport, now, kind, target, untilMillis, frozen,
                issuer.sign(signed(unsigned.body())));
    }

    private static byte[] body(byte[] meetingId, ParticipantId issuer, long lamport, long issued, Kind kind,
                               ParticipantId target, long until, List<byte[]> frozen) {
        ByteBuffer b = ByteBuffer.allocate(BODY_FIXED + (target == null ? 0 : ParticipantId.ENCODED_LEN)
                + frozen.size() * ID_LEN);
        b.put(meetingId).put(issuer.encoded()).putLong(lamport).putLong(issued).put((byte) kind.ordinal());
        if (target == null) {
            b.put((byte) 0);
        } else {
            b.put((byte) 1).put(target.encoded());
        }
        b.putLong(until).putShort((short) frozen.size());
        frozen.forEach(b::put);
        return b.array();
    }

    private static byte[] signed(byte[] body) {
        return ByteBuffer.allocate(DOMAIN.length + body.length).put(DOMAIN).put(body).array();
    }

    private byte[] body() {
        return body(meetingId, issuer, lamport, issuedAtMillis, kind, target, untilMillis, frozen);
    }

    public boolean verify() {
        return issuer.verify(signed(body()), signature);
    }

    public byte[] encode() {
        byte[] body = body();
        return ByteBuffer.allocate(body.length + signature.length).put(body).put(signature).array();
    }

    public static AdminCommand decode(byte[] data) {
        int min = BODY_FIXED + Crypto.SIGNATURE_LEN;
        if (data.length < min) {
            throw new IllegalArgumentException("truncated admin command");
        }
        int fixedTail = 0;
        ByteBuffer b = ByteBuffer.wrap(data);
        byte[] meeting = new byte[MeetingGenesis.ID_LEN];
        b.get(meeting);
        ParticipantId issuer = ParticipantId.decode(data, b.position());
        b.position(b.position() + ParticipantId.ENCODED_LEN);
        long lamport = b.getLong(), issued = b.getLong();
        int k = b.get() & 0xff;
        if (k >= Kind.values().length) {
            throw new IllegalArgumentException("unknown command kind");
        }
        int flag = b.get();
        ParticipantId target = null;
        if (flag == 1) {
            if (data.length < min + ParticipantId.ENCODED_LEN) {
                throw new IllegalArgumentException("truncated admin command");
            }
            fixedTail = ParticipantId.ENCODED_LEN;
            target = ParticipantId.decode(data, b.position());
            b.position(b.position() + ParticipantId.ENCODED_LEN);
        } else if (flag != 0) {
            throw new IllegalArgumentException("bad admin command flag");
        }
        long until = b.getLong();
        int n = b.getShort() & 0xffff;
        if (n > MAX_FROZEN || data.length != min + fixedTail + n * ID_LEN) {
            throw new IllegalArgumentException("bad admin command length");
        }
        List<byte[]> frozen = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] id = new byte[ID_LEN];
            b.get(id);
            frozen.add(id);
        }
        byte[] sig = new byte[Crypto.SIGNATURE_LEN];
        b.get(sig);
        return new AdminCommand(meeting, issuer, lamport, issued, Kind.values()[k], target, until, frozen, sig);
    }

    /** SHA-256 of the encoding; identifies the command for dedup, ordering and gossip digests. */
    public byte[] id() {
        return Crypto.sha256(encode());
    }
}
