package com.kartikey.p2pchat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A direct, serverless UDP link to one peer.
 *
 * <p>Both sides send HELLO probes to each other's address at the same time. The outgoing
 * probe opens a mapping in the sender's own NAT/firewall, so the peer's probes are let in
 * ("UDP hole punching"). Once anything arrives the link is CONNECTED and kept alive with
 * PINGs. Chat messages are acknowledged and retransmitted until delivered.
 */
public final class PeerLink implements AutoCloseable {

    public enum Status { CONNECTING, CONNECTED, DISCONNECTED, CLOSED }

    /** Callbacks run on the link's internal threads; hop to your UI thread if needed. */
    public interface Listener {
        default void onStatus(Status status, String detail) {}

        default void onMessage(String text) {}

        default void onDelivered(long messageId) {}

        default void onFailed(long messageId, String text) {}

        default void onWarning(String warning) {}

        /** Result of the optional STUN lookup of our own public address. */
        default void onNatReport(StunClient.Report report) {}
    }

    static final long PUNCH_INTERVAL_MS = 500;
    static final long PING_INTERVAL_MS = 5_000;
    static final long PEER_TIMEOUT_MS = 20_000;
    static final long RETRANSMIT_INTERVAL_MS = 1_000;
    static final int MAX_ATTEMPTS = 30;
    private static final int SEEN_WINDOW = 2048;

    private final DatagramSocket socket;
    private final PacketCodec codec;
    private final Listener listener;
    private final long session = new SecureRandom().nextLong();
    private final AtomicLong nextSeq = new AtomicLong(1);
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "p2p-timer");
        t.setDaemon(true);
        return t;
    });
    private final Thread receiver;
    private final List<String> stunServers;

    private volatile InetSocketAddress peer;
    private volatile Status status = Status.CONNECTING;
    private volatile long lastHeardAt;
    private volatile long lastSentAt;
    private long remoteSession;
    private final Set<Long> seen = new LinkedHashSet<>();
    private boolean warnedBadPacket;

    private static final class Pending {
        final Packet packet;
        final String text;
        volatile int attempts;
        volatile long lastSentAt;

        Pending(Packet packet, String text) {
            this.packet = packet;
            this.text = text;
        }
    }

    /**
     * Binds {@code localPort} (0 = any free port) and starts punching towards {@code peer}.
     */
    public PeerLink(int localPort, InetSocketAddress peer, PacketCodec codec, Listener listener)
            throws SocketException {
        this(localPort, peer, codec, listener, List.of());
    }

    /**
     * Like {@link #PeerLink(int, InetSocketAddress, PacketCodec, Listener)}, but first asks the
     * given STUN servers (e.g. {@link StunClient#DEFAULT_SERVERS}) for our public address and
     * reports it via {@link Listener#onNatReport}. An empty list skips the lookup.
     */
    public PeerLink(int localPort, InetSocketAddress peer, PacketCodec codec, Listener listener,
                    List<String> stunServers) throws SocketException {
        if (peer.isUnresolved()) {
            throw new IllegalArgumentException("cannot resolve peer host " + peer.getHostString());
        }
        this.socket = new DatagramSocket(localPort);
        this.peer = peer;
        this.codec = codec;
        this.listener = listener;
        this.stunServers = List.copyOf(stunServers);
        this.receiver = new Thread(this::receiveLoop, "p2p-receiver");
        this.receiver.setDaemon(true);
    }

    public PeerLink start() {
        listener.onStatus(Status.CONNECTING, "punching towards " + describe(peer));
        receiver.start();
        timer.scheduleWithFixedDelay(this::tick, 0, PUNCH_INTERVAL_MS / 2, TimeUnit.MILLISECONDS);
        return this;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public InetSocketAddress peerAddress() {
        return peer;
    }

    public Status status() {
        return status;
    }

    public boolean isEncrypted() {
        return codec.isEncrypted();
    }

    /**
     * Queues a message for reliable delivery.
     *
     * @return id later reported to {@link Listener#onDelivered} or {@link Listener#onFailed}
     */
    public long send(String text) {
        if (status == Status.CLOSED) {
            throw new IllegalStateException("link is closed");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PacketCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("message too long (" + bytes.length
                    + " bytes, max " + PacketCodec.MAX_PAYLOAD + ")");
        }
        long seq = nextSeq.getAndIncrement();
        Pending p = new Pending(new Packet(Packet.Type.MSG, session, seq, bytes), text);
        pending.put(seq, p);
        transmit(p);
        return seq;
    }

    @Override
    public void close() {
        if (status == Status.CLOSED) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            sendRaw(Packet.control(Packet.Type.BYE, session));
        }
        setStatus(Status.CLOSED, "closed");
        timer.shutdownNow();
        socket.close();
    }

    // ---- timers -------------------------------------------------------------------------

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (status == Status.CONNECTED && now - lastHeardAt > PEER_TIMEOUT_MS) {
                setStatus(Status.DISCONNECTED, "no reply for " + PEER_TIMEOUT_MS / 1000 + "s, retrying");
            }
            if (status != Status.CONNECTED) {
                if (now - lastSentAt >= PUNCH_INTERVAL_MS) {
                    sendRaw(Packet.control(Packet.Type.HELLO, session));
                }
            } else if (now - lastSentAt >= PING_INTERVAL_MS) {
                sendRaw(Packet.control(Packet.Type.PING, session));
            }
            for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (now - p.lastSentAt < RETRANSMIT_INTERVAL_MS) {
                    continue;
                }
                if (p.attempts >= MAX_ATTEMPTS) {
                    it.remove();
                    listener.onFailed(p.packet.seq(), p.text);
                } else {
                    transmit(p);
                }
            }
        } catch (RuntimeException e) {
            // never let the scheduler die silently
            listener.onWarning("timer error: " + e);
        }
    }

    private void transmit(Pending p) {
        p.attempts++;
        p.lastSentAt = System.currentTimeMillis();
        sendRaw(p.packet);
    }

    private void sendRaw(Packet packet) {
        if (socket.isClosed()) {
            return;
        }
        try {
            byte[] data = codec.encode(packet);
            socket.send(new DatagramPacket(data, data.length, peer));
            lastSentAt = System.currentTimeMillis();
        } catch (IOException e) {
            // network may be temporarily down; retries happen on the next tick
            if (!socket.isClosed()) {
                listener.onWarning("send failed: " + e.getMessage());
            }
        }
    }

    // ---- receiving ----------------------------------------------------------------------

    private void receiveLoop() {
        if (!stunServers.isEmpty()) {
            // Probe from this very socket so the reported mapping is the one the peer must use.
            // Chat packets arriving meanwhile are dropped; HELLO/MSG are retransmitted anyway.
            try {
                boolean ipv6 = peer.getAddress() instanceof Inet6Address;
                listener.onNatReport(StunClient.probe(socket, stunServers, ipv6, 1500));
            } catch (IOException | RuntimeException e) {
                listener.onWarning("public address lookup failed: " + e.getMessage());
            }
        }
        byte[] buf = new byte[2048];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (!socket.isClosed()) {
            try {
                dp.setLength(buf.length);
                socket.receive(dp);
                if (!StunClient.isStunMessage(buf, dp.getLength())) {
                    handle((InetSocketAddress) dp.getSocketAddress(), buf, dp.getLength());
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    listener.onWarning("receive failed: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                listener.onWarning("error handling packet: " + e);
            }
        }
    }

    private void handle(InetSocketAddress from, byte[] data, int length) {
        // Only the configured peer's IP is accepted; its port may differ if its NAT remapped it.
        if (!samePeer(from.getAddress(), peer.getAddress())) {
            return;
        }
        Packet p;
        try {
            p = codec.decode(data, length);
        } catch (IllegalArgumentException e) {
            if (!warnedBadPacket) {
                warnedBadPacket = true;
                listener.onWarning("dropping unreadable packets from peer: " + e.getMessage());
            }
            return;
        }
        if (from.getPort() != peer.getPort()) {
            listener.onWarning("peer is reachable on port " + from.getPort() + ", switching to it");
            peer = from;
        }
        lastHeardAt = System.currentTimeMillis();

        if (p.type() == Packet.Type.BYE) {
            setStatus(Status.DISCONNECTED, "peer left, waiting for it to come back");
            return;
        }
        if (status != Status.CONNECTED) {
            setStatus(Status.CONNECTED, "connected to " + describe(peer)
                    + (codec.isEncrypted() ? " (encrypted)" : " (NOT encrypted)"));
            // answer right away so the peer's side of the hole opens quickly
            sendRaw(Packet.control(Packet.Type.PING, session));
        }
        if (p.session() != remoteSession) {
            remoteSession = p.session();
            seen.clear();
        }
        switch (p.type()) {
            case MSG -> {
                sendRaw(new Packet(Packet.Type.ACK, session, p.seq(), null));
                if (seen.add(p.seq())) {
                    if (seen.size() > SEEN_WINDOW) {
                        Iterator<Long> it = seen.iterator();
                        it.next();
                        it.remove();
                    }
                    listener.onMessage(new String(p.payload(), StandardCharsets.UTF_8));
                }
            }
            case ACK -> {
                if (pending.remove(p.seq()) != null) {
                    listener.onDelivered(p.seq());
                }
            }
            default -> {
                // HELLO / PING only refresh liveness
            }
        }
    }

    private synchronized void setStatus(Status next, String detail) {
        if (status == next || status == Status.CLOSED) {
            return;
        }
        status = next;
        listener.onStatus(next, detail);
    }

    /**
     * Same host, or for IPv6 the same /64 network: hosts with privacy extensions send from
     * rotating temporary addresses, so the peer may answer from a sibling of the address we dialled.
     */
    static boolean samePeer(InetAddress a, InetAddress b) {
        if (a.equals(b)) {
            return true;
        }
        if (a instanceof Inet6Address && b instanceof Inet6Address && !a.isLinkLocalAddress()) {
            return Arrays.equals(Arrays.copyOf(a.getAddress(), 8), Arrays.copyOf(b.getAddress(), 8));
        }
        return false;
    }

    private static String describe(InetSocketAddress a) {
        return StunClient.format(a);
    }
}
