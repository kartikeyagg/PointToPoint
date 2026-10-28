package com.kartikey.p2pchat.conf.policy;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * An admin's short-lived permission for one participant to join. Tickets live only
 * {@value #LIFETIME_MILLIS} ms (and {@link PolicyState#admit} refuses anything longer than
 * {@value #MAX_LIFETIME_MILLIS} ms), so a ticket cannot be hoarded: once every admin is offline no
 * fresh ticket can be minted and the meeting is closed to newcomers, while existing members keep
 * running under the frozen policy.
 */
public record JoinTicket(byte[] meetingId, ParticipantId participant, ParticipantId issuer,
                         long issuedAtMillis, long expiresAtMillis, byte[] signature) {

    public static final long LIFETIME_MILLIS = 60_000;
    public static final long MAX_LIFETIME_MILLIS = 120_000;
    public static final long SKEW_MILLIS = 5_000;
    public static final int ENCODED_LEN =
            MeetingGenesis.ID_LEN + 2 * ParticipantId.ENCODED_LEN + 16 + Crypto.SIGNATURE_LEN;
    private static final byte[] DOMAIN = "p2p-conf-ticket-v1".getBytes(StandardCharsets.UTF_8);

    public JoinTicket {
        if (meetingId.length != MeetingGenesis.ID_LEN || signature.length != Crypto.SIGNATURE_LEN) {
            throw new IllegalArgumentException("bad ticket field length");
        }
        meetingId = meetingId.clone();
        signature = signature.clone();
    }

    public static JoinTicket issue(Identity admin, byte[] meetingId, ParticipantId who, long now) {
        return issue(admin, meetingId, who, now, now + LIFETIME_MILLIS);
    }

    static JoinTicket issue(Identity admin, byte[] meetingId, ParticipantId who, long now, long expires) {
        return new JoinTicket(meetingId, who, admin.id(), now, expires,
                admin.sign(signed(meetingId, who, admin.id(), now, expires)));
    }

    private static byte[] signed(byte[] meetingId, ParticipantId who, ParticipantId issuer, long iat, long exp) {
        return ByteBuffer.allocate(DOMAIN.length + ENCODED_LEN - Crypto.SIGNATURE_LEN).put(DOMAIN).put(meetingId)
                .put(who.encoded()).put(issuer.encoded()).putLong(iat).putLong(exp).array();
    }

    public boolean verify() {
        return issuer.verify(signed(meetingId, participant, issuer, issuedAtMillis, expiresAtMillis), signature);
    }

    public byte[] encode() {
        return ByteBuffer.allocate(ENCODED_LEN).put(meetingId).put(participant.encoded()).put(issuer.encoded())
                .putLong(issuedAtMillis).putLong(expiresAtMillis).put(signature).array();
    }

    public static JoinTicket decode(byte[] data) {
        if (data.length != ENCODED_LEN) {
            throw new IllegalArgumentException("bad ticket length");
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        byte[] id = new byte[MeetingGenesis.ID_LEN];
        b.get(id);
        ParticipantId who = ParticipantId.decode(data, b.position());
        b.position(b.position() + ParticipantId.ENCODED_LEN);
        ParticipantId issuer = ParticipantId.decode(data, b.position());
        b.position(b.position() + ParticipantId.ENCODED_LEN);
        long iat = b.getLong(), exp = b.getLong();
        byte[] sig = new byte[Crypto.SIGNATURE_LEN];
        b.get(sig);
        return new JoinTicket(id, who, issuer, iat, exp, sig);
    }
}
