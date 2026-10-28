package com.kartikey.p2pchat.conf.policy;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A signed moderation action. Authority is not checked here (the issuer may no longer be an admin
 * by the time it is replayed); {@link PolicyState} decides that. The lamport clock gives a total
 * order that every node derives identically from the same set of commands.
 */
public record AdminCommand(byte[] meetingId, ParticipantId issuer, long lamport, long issuedAtMillis,
                           Kind kind, ParticipantId target, long untilMillis, byte[] signature) {

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
    private static final int BODY_FIXED = MeetingGenesis.ID_LEN + ParticipantId.ENCODED_LEN + 8 + 8 + 1 + 1 + 8;

    public AdminCommand {
        if (meetingId.length != MeetingGenesis.ID_LEN || signature.length != Crypto.SIGNATURE_LEN) {
            throw new IllegalArgumentException("bad command field length");
        }
        if (kind.targeted != (target != null)) {
            throw new IllegalArgumentException(kind + (kind.targeted ? " needs a target" : " takes no target"));
        }
        meetingId = meetingId.clone();
        signature = signature.clone();
    }

    public static AdminCommand sign(Identity issuer, byte[] meetingId, Kind kind, ParticipantId target,
                                    long untilMillis, long lamport, long now) {
        byte[] body = body(meetingId, issuer.id(), lamport, now, kind, target, untilMillis);
        return new AdminCommand(meetingId, issuer.id(), lamport, now, kind, target, untilMillis, issuer.sign(signed(body)));
    }

    private static byte[] body(byte[] meetingId, ParticipantId issuer, long lamport, long issued, Kind kind,
                               ParticipantId target, long until) {
        ByteBuffer b = ByteBuffer.allocate(BODY_FIXED + (target == null ? 0 : ParticipantId.ENCODED_LEN));
        b.put(meetingId).put(issuer.encoded()).putLong(lamport).putLong(issued).put((byte) kind.ordinal());
        if (target == null) {
            b.put((byte) 0);
        } else {
            b.put((byte) 1).put(target.encoded());
        }
        b.putLong(until);
        return b.array();
    }

    private static byte[] signed(byte[] body) {
        return ByteBuffer.allocate(DOMAIN.length + body.length).put(DOMAIN).put(body).array();
    }

    private byte[] body() {
        return body(meetingId, issuer, lamport, issuedAtMillis, kind, target, untilMillis);
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
            if (data.length != min + ParticipantId.ENCODED_LEN) {
                throw new IllegalArgumentException("bad admin command length");
            }
            target = ParticipantId.decode(data, b.position());
            b.position(b.position() + ParticipantId.ENCODED_LEN);
        } else if (flag != 0 || data.length != min) {
            throw new IllegalArgumentException("bad admin command length");
        }
        long until = b.getLong();
        byte[] sig = new byte[Crypto.SIGNATURE_LEN];
        b.get(sig);
        return new AdminCommand(meeting, issuer, lamport, issued, Kind.values()[k], target, until, sig);
    }

    /** SHA-256 of the encoding; identifies the command for dedup, ordering and gossip digests. */
    public byte[] id() {
        return Crypto.sha256(encode());
    }
}
