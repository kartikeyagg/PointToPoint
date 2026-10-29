package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.node.gossip.Contact;
import com.kartikey.p2pchat.conf.policy.JoinTicket;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A participant's signed "I'm here": display name, what they publish and how to reach them. The
 * first one after joining carries the admin's {@link JoinTicket}, which is how every member learns
 * that the newcomer was admitted.
 *
 * <p>At 100k participants nobody can hear from everyone regularly, so only admins (to show they are
 * online) and people publishing audio or video repeat it; everyone else announces once.
 */
public record Presence(ParticipantId id, String name, int flags, Contact contact, long atMillis, JoinTicket ticket,
                       byte[] signature) {

    public static final int PUB_AUDIO = 1, PUB_CAMERA = 2, PUB_SCREEN = 4, LEAVING = 8;
    public static final int MAX_NAME_BYTES = 64;
    private static final byte[] DOMAIN = "p2p-conf/v1 presence".getBytes(StandardCharsets.US_ASCII);

    public static Presence sign(Identity me, String name, int flags, Contact contact, long now, JoinTicket ticket) {
        String n = trimName(name);
        byte[] body = body(me.id(), n, flags, contact, now, ticket);
        return new Presence(me.id(), n, flags, contact, now, ticket, me.sign(withDomain(body)));
    }

    public boolean verify() {
        return contact.id().equals(id) && id.verify(withDomain(body(id, name, flags, contact, atMillis, ticket)), signature);
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public byte[] encode() {
        byte[] body = body(id, name, flags, contact, atMillis, ticket);
        return ByteBuffer.allocate(body.length + signature.length).put(body).put(signature).array();
    }

    public static Presence decode(byte[] data) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            ParticipantId id = ParticipantId.decode(take(b, ParticipantId.ENCODED_LEN), 0);
            byte[] n = take(b, b.get() & 0xff);
            if (n.length > MAX_NAME_BYTES) {
                throw new IllegalArgumentException("name too long");
            }
            int flags = b.get() & 0xff;
            Contact contact = Contact.decode(take(b, b.getShort() & 0xffff));
            long at = b.getLong();
            int ticketLen = b.getShort() & 0xffff;
            JoinTicket ticket = ticketLen == 0 ? null : JoinTicket.decode(take(b, ticketLen));
            byte[] sig = take(b, Crypto.SIGNATURE_LEN);
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new Presence(id, new String(n, StandardCharsets.UTF_8), flags, contact, at, ticket, sig);
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("truncated presence");
        }
    }

    private static byte[] body(ParticipantId id, String name, int flags, Contact contact, long at, JoinTicket ticket) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8), c = contact.encode();
        byte[] t = ticket == null ? new byte[0] : ticket.encode();
        return ByteBuffer.allocate(ParticipantId.ENCODED_LEN + 1 + n.length + 1 + 2 + c.length + 8 + 2 + t.length)
                .put(id.encoded()).put((byte) n.length).put(n).put((byte) flags)
                .putShort((short) c.length).put(c).putLong(at).putShort((short) t.length).put(t).array();
    }

    private static byte[] withDomain(byte[] body) {
        byte[] m = Arrays.copyOf(DOMAIN, DOMAIN.length + body.length);
        System.arraycopy(body, 0, m, DOMAIN.length, body.length);
        return m;
    }

    private static byte[] take(ByteBuffer b, int n) {
        if (n < 0 || n > b.remaining()) {
            throw new IllegalArgumentException("truncated presence");
        }
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }

    /** Names are for display only; cut to {@link #MAX_NAME_BYTES} without splitting a character. */
    static String trimName(String name) {
        String s = name == null || name.isBlank() ? "Guest" : name.strip();
        while (s.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            s = s.substring(0, s.offsetByCodePoints(0, s.codePointCount(0, s.length()) - 1));
        }
        return s;
    }
}
