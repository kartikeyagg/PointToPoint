package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.PeerLink;

/** Where remote desktop messages go; a {@link PeerLink} in the app, a fake in tests. */
public interface Transport {

    /** Fire-and-forget datagram; false if nothing was sent. */
    boolean sendData(byte[] data);

    /** Reliable, in-order bytes; false if not accepted (no peer yet, or too much unacknowledged). */
    boolean sendReliable(byte[] data);

    /** Encodes {@code m} and sends it on the channel {@link RemoteProtocol#isReliable} picks. */
    default boolean send(RemoteProtocol.Message m) {
        byte[] bytes = RemoteProtocol.encode(m);
        return RemoteProtocol.isReliable(m) ? sendReliable(bytes) : sendData(bytes);
    }

    static Transport of(PeerLink link) {
        return new Transport() {
            @Override
            public boolean sendData(byte[] data) {
                return link.sendData(data);
            }

            @Override
            public boolean sendReliable(byte[] data) {
                return link.sendReliable(data);
            }
        };
    }
}
