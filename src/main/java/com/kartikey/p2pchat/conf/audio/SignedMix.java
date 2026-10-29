package com.kartikey.p2pchat.conf.audio;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * One mixed frame sent up (or down) the relay tree, signed by the mixer so injected sound can be
 * traced to the relay that produced it. The signature covers every other field.
 *
 * <p>Wire: mixer(88) | frameSeq(8) | participants(4) | inputs(4) | payloadLen(2) | payload | sig(64).
 */
public record SignedMix(ParticipantId mixer, long frameSeq, int participants, int inputs,
                        byte[] payload, byte[] signature) {

    public static final int MAX_PAYLOAD = 1500;
    /** Domain separation: the same identity key signs other message kinds. */
    private static final byte[] DOMAIN = "p2p-conf/v1 signed-mix".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int HEADER = ParticipantId.ENCODED_LEN + 8 + 4 + 4 + 2;

    public SignedMix {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large");
        }
        payload = payload.clone();
        signature = signature.clone();
    }

    public static SignedMix sign(Identity id, long frameSeq, int participants, int inputs, byte[] payload) {
        byte[] body = body(id.id(), frameSeq, participants, inputs, payload);
        return new SignedMix(id.id(), frameSeq, participants, inputs, payload, id.sign(signedBytes(body)));
    }

    public boolean verify() {
        return mixer.verify(signedBytes(body(mixer, frameSeq, participants, inputs, payload)), signature);
    }

    public byte[] encode() {
        byte[] body = body(mixer, frameSeq, participants, inputs, payload);
        return ByteBuffer.allocate(body.length + Crypto.SIGNATURE_LEN).put(body).put(signature).array();
    }

    public static SignedMix decode(byte[] data) {
        if (data.length < HEADER + Crypto.SIGNATURE_LEN) {
            throw new IllegalArgumentException("truncated signed mix");
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        ParticipantId mixer = ParticipantId.decode(data, 0);
        b.position(ParticipantId.ENCODED_LEN);
        long seq = b.getLong();
        int participants = b.getInt();
        int inputs = b.getInt();
        int len = b.getShort() & 0xFFFF;
        if (len > MAX_PAYLOAD || b.remaining() != len + Crypto.SIGNATURE_LEN) {
            throw new IllegalArgumentException("bad signed mix length");
        }
        byte[] payload = new byte[len];
        b.get(payload);
        byte[] sig = new byte[Crypto.SIGNATURE_LEN];
        b.get(sig);
        return new SignedMix(mixer, seq, participants, inputs, payload, sig);
    }

    private static byte[] signedBytes(byte[] body) {
        return ByteBuffer.allocate(DOMAIN.length + body.length).put(DOMAIN).put(body).array();
    }

    private static byte[] body(ParticipantId mixer, long seq, int participants, int inputs, byte[] payload) {
        return ByteBuffer.allocate(HEADER + payload.length).put(mixer.encoded()).putLong(seq)
                .putInt(participants).putInt(inputs).putShort((short) payload.length).put(payload).array();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignedMix m && mixer.equals(m.mixer) && frameSeq == m.frameSeq
                && participants == m.participants && inputs == m.inputs
                && Arrays.equals(payload, m.payload) && Arrays.equals(signature, m.signature);
    }

    @Override
    public int hashCode() {
        return mixer.hashCode() * 31 + Long.hashCode(frameSeq);
    }
}
