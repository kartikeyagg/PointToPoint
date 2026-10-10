package com.kartikey.p2pchat;

/**
 * One datagram on the wire.
 *
 * @param type    what kind of packet this is
 * @param session random id chosen by the sender at startup (lets the receiver notice restarts)
 * @param seq     sequence number for MSG/RDATA; for ACK/RACK it is the seq being acknowledged
 * @param payload UTF-8 text for MSG, bytes for DATA/RDATA, our X25519 key for encrypted HELLO
 */
public record Packet(Type type, long session, long seq, byte[] payload) {

    public enum Type {
        /** Hole-punching probe; sent repeatedly until the peer answers. */
        HELLO,
        /** Keep-alive so NAT mappings do not expire. */
        PING,
        /** A chat message that must be acknowledged. */
        MSG,
        /** Acknowledges a MSG with the same seq. */
        ACK,
        /** Sender is shutting down. */
        BYE,
        /** Application datagram: delivered at most once, may be lost or reordered. */
        DATA,
        /** Application data delivered reliably and in order; payload starts with the target session. */
        RDATA,
        /** Acknowledges an RDATA with the same seq. */
        RACK;

        static Type fromByte(byte b) {
            Type[] all = values();
            if (b < 0 || b >= all.length) {
                throw new IllegalArgumentException("unknown packet type " + b);
            }
            return all[b];
        }
    }

    public Packet {
        payload = payload == null ? new byte[0] : payload;
    }

    public static Packet control(Type type, long session) {
        return new Packet(type, session, 0, null);
    }
}
