package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * What someone needs to join: which meeting, which admin to ask (their full public identity, so a
 * man in the middle can't pose as them) and where to reach that admin. Shared as a text code.
 *
 * <p>Layout: {@code "p2pc1:" base64url(meetingId(16) | admin(88) | titleLen(1) | title | n(1) |
 * n × (addrLen(1) | addr | port(2)))}.
 */
public record Invite(byte[] meetingId, ParticipantId admin, String title, List<InetSocketAddress> candidates) {

    private static final String PREFIX = "p2pc1:";
    private static final int MAX_CANDIDATES = 8;

    public Invite {
        if (meetingId.length != 16) {
            throw new IllegalArgumentException("meeting id must be 16 bytes");
        }
        byte[] t = title.getBytes(StandardCharsets.UTF_8);
        if (t.length > 255) {
            throw new IllegalArgumentException("title too long");
        }
        candidates = candidates.stream().filter(a -> !a.isUnresolved()).limit(MAX_CANDIDATES).toList();
        meetingId = meetingId.clone();
    }

    @Override
    public byte[] meetingId() {
        return meetingId.clone();
    }

    public String encode() {
        byte[] t = title.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(16 + ParticipantId.ENCODED_LEN + 2 + t.length + candidates.size() * 19);
        b.put(meetingId).put(admin.encoded()).put((byte) t.length).put(t).put((byte) candidates.size());
        for (InetSocketAddress a : candidates) {
            byte[] ip = a.getAddress().getAddress();
            b.put((byte) ip.length).put(ip).putShort((short) a.getPort());
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(b.array(), b.position()));
    }

    /** @throws IllegalArgumentException for anything that isn't a well-formed invite */
    public static Invite decode(String code) {
        String s = code.strip();
        if (!s.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not an invite code");
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(Base64.getUrlDecoder().decode(s.substring(PREFIX.length())));
            byte[] id = new byte[16];
            b.get(id);
            byte[] adminBytes = new byte[ParticipantId.ENCODED_LEN];
            b.get(adminBytes);
            ParticipantId admin = ParticipantId.decode(adminBytes, 0);
            byte[] t = new byte[b.get() & 0xff];
            b.get(t);
            int n = b.get() & 0xff;
            if (n > MAX_CANDIDATES) {
                throw new IllegalArgumentException("too many addresses");
            }
            List<InetSocketAddress> cands = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int len = b.get() & 0xff;
                if (len != 4 && len != 16) {
                    throw new IllegalArgumentException("bad address length");
                }
                byte[] ip = new byte[len];
                b.get(ip);
                cands.add(new InetSocketAddress(InetAddress.getByAddress(ip), b.getShort() & 0xffff));
            }
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new Invite(id, admin, new String(t, StandardCharsets.UTF_8), cands);
        } catch (BufferUnderflowException | UnknownHostException e) {
            throw new IllegalArgumentException("truncated invite");
        }
    }
}
