package com.kartikey.p2pchat.conf.policy;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The root of a meeting's trust: created and signed once by the original host. Every later
 * {@link AdminCommand} is judged against the host named here, so a meeting id is only meaningful
 * together with its genesis.
 */
public record MeetingGenesis(byte[] meetingId, ParticipantId host, long createdAtMillis,
                             boolean cameraAllowedByDefault, byte[] signature) {

    public static final int ID_LEN = 16;
    public static final int ENCODED_LEN = ID_LEN + ParticipantId.ENCODED_LEN + 8 + 1 + Crypto.SIGNATURE_LEN;
    private static final byte[] DOMAIN = "p2p-conf-genesis-v1".getBytes(StandardCharsets.UTF_8);

    public MeetingGenesis {
        if (meetingId.length != ID_LEN || signature.length != Crypto.SIGNATURE_LEN) {
            throw new IllegalArgumentException("bad genesis field length");
        }
        meetingId = meetingId.clone();
        signature = signature.clone();
    }

    public static MeetingGenesis create(Identity host, boolean cameraDefault, long now) {
        byte[] id = Crypto.random(ID_LEN);
        byte[] body = body(id, host.id(), now, cameraDefault);
        return new MeetingGenesis(id, host.id(), now, cameraDefault, host.sign(body));
    }

    private static byte[] body(byte[] id, ParticipantId host, long created, boolean camera) {
        return ByteBuffer.allocate(DOMAIN.length + ID_LEN + ParticipantId.ENCODED_LEN + 9)
                .put(DOMAIN).put(id).put(host.encoded()).putLong(created).put((byte) (camera ? 1 : 0)).array();
    }

    public boolean verify() {
        return host.verify(body(meetingId, host, createdAtMillis, cameraAllowedByDefault), signature);
    }

    public byte[] encode() {
        return ByteBuffer.allocate(ENCODED_LEN).put(meetingId).put(host.encoded()).putLong(createdAtMillis)
                .put((byte) (cameraAllowedByDefault ? 1 : 0)).put(signature).array();
    }

    public static MeetingGenesis decode(byte[] data) {
        if (data.length != ENCODED_LEN) {
            throw new IllegalArgumentException("bad genesis length");
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        byte[] id = new byte[ID_LEN];
        b.get(id);
        ParticipantId host = ParticipantId.decode(data, ID_LEN);
        b.position(ID_LEN + ParticipantId.ENCODED_LEN);
        long created = b.getLong();
        byte cam = b.get();
        if (cam != 0 && cam != 1) {
            throw new IllegalArgumentException("bad genesis flag");
        }
        byte[] sig = new byte[Crypto.SIGNATURE_LEN];
        b.get(sig);
        return new MeetingGenesis(id, host, created, cam == 1, sig);
    }

    boolean sameMeeting(byte[] other) {
        return Arrays.equals(meetingId, other);
    }
}
