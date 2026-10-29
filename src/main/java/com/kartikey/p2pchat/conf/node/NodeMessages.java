package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.keys.EpochBundle;
import com.kartikey.p2pchat.conf.node.gossip.Contact;
import com.kartikey.p2pchat.conf.policy.JoinTicket;
import com.kartikey.p2pchat.conf.policy.MeetingGenesis;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Node-level control messages (range {@link ControlTypes#NODE_FIRST}..): asking an admin to join,
 * the admin's answer, and proving membership on a new link.
 */
final class NodeMessages {

    static final int JOIN_REQ = 0x40, JOIN_OK = 0x41, JOIN_DENIED = 0x42, HELLO = 0x43, ROSTER_REQ = 0x44, ROSTER = 0x45, REMOVED = 0x46;
    static final int MAX_CONTACTS = 16;
    /** A newcomer learns at most this many earlier participants from one peer (admins and publishers first). */
    static final int MAX_ROSTER_SYNC = 2_000, MAX_ROSTER_PAGE_BYTES = 60_000, MAX_ROSTER_PAGE = 400;

    private NodeMessages() {}

    /** Newcomer to admin: who I am and how to reach me. */
    record JoinReq(Contact contact, String name) {}

    /** Admin to newcomer: everything needed to take part (commands follow through policy sync). */
    record JoinOk(MeetingGenesis genesis, JoinTicket ticket, EpochBundle key, List<Contact> contacts) {}

    static byte[] joinReq(JoinReq r) {
        byte[] c = r.contact().encode(), n = Presence.trimName(r.name()).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 2 + c.length + 1 + n.length).put((byte) JOIN_REQ)
                .putShort((short) c.length).put(c).put((byte) n.length).put(n).array();
    }

    static JoinReq joinReq(byte[] m) {
        ByteBuffer b = start(m, JOIN_REQ);
        try {
            Contact c = Contact.decode(take(b, b.getShort() & 0xffff));
            String name = new String(take(b, b.get() & 0xff), StandardCharsets.UTF_8);
            end(b);
            return new JoinReq(c, name);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated join request");
        }
    }

    static byte[] joinOk(JoinOk ok) {
        byte[] g = ok.genesis().encode(), t = ok.ticket().encode();
        byte[] k = ok.key() == null ? new byte[0] : ok.key().encode();
        List<byte[]> cs = new ArrayList<>();
        int size = 1 + 2 + g.length + 2 + t.length + 4 + k.length + 1;
        for (Contact c : ok.contacts().subList(0, Math.min(MAX_CONTACTS, ok.contacts().size()))) {
            byte[] e = c.encode();
            cs.add(e);
            size += 2 + e.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size).put((byte) JOIN_OK);
        b.putShort((short) g.length).put(g).putShort((short) t.length).put(t).putInt(k.length).put(k);
        b.put((byte) cs.size());
        for (byte[] e : cs) {
            b.putShort((short) e.length).put(e);
        }
        return b.array();
    }

    static JoinOk joinOk(byte[] m) {
        ByteBuffer b = start(m, JOIN_OK);
        try {
            MeetingGenesis g = MeetingGenesis.decode(take(b, b.getShort() & 0xffff));
            JoinTicket t = JoinTicket.decode(take(b, b.getShort() & 0xffff));
            int kl = b.getInt();
            EpochBundle k = kl == 0 ? null : EpochBundle.decode(take(b, kl));
            int n = b.get() & 0xff;
            if (n > MAX_CONTACTS) {
                throw new IllegalArgumentException("too many contacts");
            }
            List<Contact> cs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                cs.add(Contact.decode(take(b, b.getShort() & 0xffff)));
            }
            end(b);
            return new JoinOk(g, t, k, cs);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated join answer");
        }
    }

    static byte[] joinDenied(String reason) {
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(200, r.length);
        return ByteBuffer.allocate(2 + n).put((byte) JOIN_DENIED).put((byte) n).put(r, 0, n).array();
    }

    static String joinDenied(byte[] m) {
        ByteBuffer b = start(m, JOIN_DENIED);
        try {
            String s = new String(take(b, b.get() & 0xff), StandardCharsets.UTF_8);
            end(b);
            return s;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated denial");
        }
    }

    /** First message on every member-to-member link: my signed presence (with my ticket). */
    static byte[] hello(Presence p) {
        byte[] e = p.encode();
        return ByteBuffer.allocate(1 + e.length).put((byte) HELLO).put(e).array();
    }

    static Presence hello(byte[] m) {
        ByteBuffer b = start(m, HELLO);
        return Presence.decode(take(b, b.remaining()));
    }

    /** Asks for the sender's roster unless it matches ours ({@code count} entries, XOR of id hashes). */
    static byte[] rosterReq(int count, long digest) {
        return ByteBuffer.allocate(1 + 4 + 8).put((byte) ROSTER_REQ).putInt(count).putLong(digest).array();
    }

    record RosterDigest(int count, long digest) {}

    static RosterDigest rosterReq(byte[] m) {
        ByteBuffer b = start(m, ROSTER_REQ);
        try {
            RosterDigest d = new RosterDigest(b.getInt(), b.getLong());
            end(b);
            return d;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated roster request");
        }
    }

    static byte[] roster(List<Presence> page) {
        List<byte[]> enc = new ArrayList<>(page.size());
        int size = 1 + 2;
        for (Presence p : page) {
            byte[] e = p.encode();
            enc.add(e);
            size += 2 + e.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size).put((byte) ROSTER).putShort((short) enc.size());
        for (byte[] e : enc) {
            b.putShort((short) e.length).put(e);
        }
        return b.array();
    }

    static List<Presence> roster(byte[] m) {
        ByteBuffer b = start(m, ROSTER);
        try {
            int n = b.getShort() & 0xffff;
            if (n > MAX_ROSTER_PAGE) {
                throw new IllegalArgumentException("roster page too large");
            }
            List<Presence> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(Presence.decode(take(b, b.getShort() & 0xffff)));
            }
            end(b);
            return out;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated roster");
        }
    }

    /** The signed admin command that removed (or ended) us, sent just before our links close. */
    static byte[] removed(byte[] command) {
        return ByteBuffer.allocate(1 + command.length).put((byte) REMOVED).put(command).array();
    }

    static byte[] removedCommand(byte[] m) {
        ByteBuffer b = start(m, REMOVED);
        return take(b, b.remaining());
    }

    private static ByteBuffer start(byte[] m, int type) {
        if (m.length == 0 || (m[0] & 0xff) != type) {
            throw new IllegalArgumentException("wrong message type");
        }
        ByteBuffer b = ByteBuffer.wrap(m);
        b.get();
        return b;
    }

    private static byte[] take(ByteBuffer b, int n) {
        if (n < 0 || n > b.remaining()) {
            throw new IllegalArgumentException("truncated message");
        }
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }

    private static void end(ByteBuffer b) {
        if (b.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes");
        }
    }
}
