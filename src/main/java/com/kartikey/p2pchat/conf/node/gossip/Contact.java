package com.kartikey.p2pchat.conf.node.gossip;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** How to reach a participant and what it can offer: candidate addresses, upload budget, relay willingness. */
public record Contact(ParticipantId id, List<InetSocketAddress> candidates, int uploadKbps, boolean canRelay) {

    public static final int MAX_CANDIDATES = 8;

    public Contact {
        if (id == null || candidates == null || uploadKbps < 0) throw new IllegalArgumentException("bad contact");
        candidates = List.copyOf(candidates.size() > MAX_CANDIDATES ? candidates.subList(0, MAX_CANDIDATES) : candidates);
    }

    /** {@code id(88) | uploadKbps(4) | relay(1) | n(1) | n * (kind(1) [ip 4|16 | hostLen(1) host] port(2))}. */
    public byte[] encode() {
        Wire.Writer w = new Wire.Writer();
        write(w);
        return w.done();
    }

    void write(Wire.Writer w) {
        w.bytes(id.encoded()).i32(uploadKbps).u8(canRelay ? 1 : 0).u8(candidates.size());
        for (InetSocketAddress a : candidates) {
            if (a.isUnresolved()) {
                byte[] host = a.getHostString().getBytes(StandardCharsets.UTF_8);
                if (host.length == 0 || host.length > 255) throw new IllegalArgumentException("bad host name");
                w.u8(0).u8(host.length).bytes(host);
            } else {
                byte[] ip = a.getAddress().getAddress();
                w.u8(ip.length).bytes(ip);
            }
            w.u16(a.getPort());
        }
    }

    public static Contact decode(byte[] data) {
        Wire.Reader r = new Wire.Reader(data, 0);
        Contact c = read(r);
        r.end();
        return c;
    }

    static Contact read(Wire.Reader r) {
        ParticipantId id = ParticipantId.decode(r.bytes(ParticipantId.ENCODED_LEN), 0);
        int kbps = r.i32();
        if (kbps < 0) throw new IllegalArgumentException("bad upload");
        int relay = r.u8();
        if (relay > 1) throw new IllegalArgumentException("bad relay flag");
        int n = r.u8();
        if (n > MAX_CANDIDATES) throw new IllegalArgumentException("too many candidates");
        List<InetSocketAddress> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int kind = r.u8();
            if (kind == 0) {
                int len = r.u8();
                if (len == 0) throw new IllegalArgumentException("empty host");
                String host = r.string(len);
                list.add(InetSocketAddress.createUnresolved(host, r.u16()));
            } else if (kind == 4 || kind == 16) {
                byte[] ip = r.bytes(kind);
                try {
                    list.add(new InetSocketAddress(InetAddress.getByAddress(ip), r.u16()));
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("bad address");
                }
            } else {
                throw new IllegalArgumentException("bad address kind");
            }
        }
        return new Contact(id, list, kbps, relay == 1);
    }
}
