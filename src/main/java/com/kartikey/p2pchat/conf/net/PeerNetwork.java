package com.kartikey.p2pchat.conf.net;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

/**
 * Authenticated links to other participants, all over one local endpoint. {@code UdpPeerNetwork}
 * in the app, {@link MemoryFabric} in tests and simulations.
 *
 * <p>Threading contract: implementations call the {@link Listener} from one thread at a time, and
 * the conference logic is written as single-threaded event handlers driven by those callbacks plus
 * {@code tick(now)}. Send methods may be called from any thread.
 */
public interface PeerNetwork extends AutoCloseable {

    ParticipantId self();

    /** Addresses others can try to reach us on (local, and public ones learned by STUN). */
    List<InetSocketAddress> candidates();

    /**
     * Starts (or keeps) trying to reach {@code peer} on its candidate addresses, punching through
     * NAT from both sides; {@code via}, when non-null, is a connected member willing to relay if
     * no direct path works.
     */
    void connect(ParticipantId peer, List<InetSocketAddress> candidates, ParticipantId via);

    /** Closes the link to {@code peer}; the other side sees {@link Listener#onDisconnected}. */
    void disconnect(ParticipantId peer);

    Set<ParticipantId> connected();

    /** Fire-and-forget; false if there is no link (or the send queue is full). */
    boolean sendMedia(ParticipantId peer, byte[] packet);

    /** Reliable and in order per peer; false if there is no link or too much is unacknowledged. */
    boolean sendControl(ParticipantId peer, byte[] message);

    /** Round-trip time, loss and throughput of a link, or null if not connected. */
    LinkStats stats(ParticipantId peer);

    void setListener(Listener listener);

    @Override
    void close();

    interface Listener {
        void onConnected(ParticipantId peer);

        void onDisconnected(ParticipantId peer);

        void onMedia(ParticipantId from, byte[] packet);

        void onControl(ParticipantId from, byte[] message);
    }

    /** Smoothed link measurements; {@code relayed} when traffic goes through another member. */
    record LinkStats(int rttMillis, double lossRate, long bytesSent, long bytesReceived, boolean relayed) {}
}
