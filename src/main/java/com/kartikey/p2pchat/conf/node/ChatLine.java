package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A chat message signed by its author, so relays and other members can't put words in anyone's mouth. */
public record ChatLine(ParticipantId author, long atMillis, String text, byte[] signature) {

    public static final int MAX_TEXT_BYTES = 2000;
    private static final byte[] DOMAIN = "p2p-conf/v1 chat".getBytes(StandardCharsets.US_ASCII);

    public static ChatLine sign(Identity me, String text, long now) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        String cut = t.length <= MAX_TEXT_BYTES ? text : new String(t, 0, MAX_TEXT_BYTES, StandardCharsets.UTF_8);
        return new ChatLine(me.id(), now, cut, me.sign(signed(me.id(), now, cut)));
    }

    public boolean verify() {
        return author.verify(signed(author, atMillis, text), signature);
    }

    public byte[] encode() {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(ParticipantId.ENCODED_LEN + 8 + 2 + t.length + Crypto.SIGNATURE_LEN)
                .put(author.encoded()).putLong(atMillis).putShort((short) t.length).put(t).put(signature).array();
    }

    public static ChatLine decode(byte[] data) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            byte[] id = new byte[ParticipantId.ENCODED_LEN];
            b.get(id);
            long at = b.getLong();
            int len = b.getShort() & 0xffff;
            if (len > MAX_TEXT_BYTES || len + Crypto.SIGNATURE_LEN != b.remaining()) {
                throw new IllegalArgumentException("bad chat length");
            }
            byte[] t = new byte[len];
            b.get(t);
            byte[] sig = new byte[Crypto.SIGNATURE_LEN];
            b.get(sig);
            return new ChatLine(ParticipantId.decode(id, 0), at, new String(t, StandardCharsets.UTF_8), sig);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated chat");
        }
    }

    private static byte[] signed(ParticipantId author, long at, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] body = ByteBuffer.allocate(ParticipantId.ENCODED_LEN + 8 + t.length)
                .put(author.encoded()).putLong(at).put(t).array();
        byte[] m = Arrays.copyOf(DOMAIN, DOMAIN.length + body.length);
        System.arraycopy(body, 0, m, DOMAIN.length, body.length);
        return m;
    }
}
