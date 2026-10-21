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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A direct, serverless UDP link to one peer.
 *
 * <p>Both sides send HELLO probes to each other's address at the same time. The outgoing
 * probe opens a mapping in the sender's own NAT/firewall, so the peer's probes are let in
 * ("UDP hole punching"). Once anything arrives the link is CONNECTED and kept alive with
 * PINGs. Chat messages are acknowledged and retransmitted until delivered.
 *
 * <p>Besides chat text the link carries application bytes for other services (remote desktop):
 * {@link #sendData} is fire-and-forget, {@link #sendReliable} is delivered once and in order.
 * With a passphrase every packet after the HELLO handshake goes through a {@link SecureChannel}.
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

        /** A datagram from {@link #sendData}. */
        default void onData(byte[] data) {}

        /** Bytes from {@link #sendReliable}, in the order they were sent. */
        default void onReliable(byte[] data) {}

        /**
         * A (new) instance of the peer app is talking to us: the first contact, or the peer
         * restarted. Reliable streams start over, so resend any state the peer needs.
         * Called before the matching CONNECTED status.
         */
        default void onPeerSession() {}
    }

    static final long PUNCH_INTERVAL_MS = 500;
    static final long PING_INTERVAL_MS = 5_000;
    static final long PEER_TIMEOUT_MS = 20_000;
    static final long RETRANSMIT_INTERVAL_MS = 1_000;
    /** Retransmissions allowed once connected (about 30 s). */
    static final int MAX_ATTEMPTS = 30;
    /** Messages typed before the peer is reachable wait this long for a connection. */
    static final long MAX_QUEUE_MS = 5 * 60_000;
    private static final int SEEN_WINDOW = 2048;
    static final long RELIABLE_TICK_MS = 50;
    static final long RELIABLE_RTO_MS = 250;
    static final int MAX_UNACKED = 4096;
    /** Largest {@link #sendReliable} payload (8 bytes go to the target session). */
    public static final int MAX_RELIABLE = PacketCodec.MAX_PAYLOAD - 8;

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
    /** Null without a passphrase. */
    private final SecureChannel secure;

    private volatile InetSocketAddress peer;
    private volatile Status status = Status.CONNECTING;
    private volatile long lastHeardAt;
    private volatile long lastSentAt;
    private volatile long remoteSession;
    private final Set<Long> seen = new LinkedHashSet<>();
    private boolean warnedBadPacket;

    // reliable in-order stream (RDATA), reset whenever the peer's session changes
    private final Object streamLock = new Object();
    private long outSeq = 1;
    private final TreeMap<Long, Pending> outPending = new TreeMap<>();
    private long inNext = 1;
    private final TreeMap<Long, byte[]> inBuffer = new TreeMap<>();

    /** Test hook: outgoing packets matching this are silently dropped. */
    volatile Predicate<Packet> dropOutgoing = p -> false;

    private static final class Pending {
        final Packet packet;
        final String text;
        volatile int attempts;
        volatile long lastSentAt;

        final long createdAt = System.currentTimeMillis();

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
        try {
            // room for bursts of screen tiles; the OS may cap these silently
            socket.setReceiveBufferSize(4 << 20);
            socket.setSendBufferSize(1 << 20);
        } catch (SocketException ignored) {
            // keep the defaults
        }
        this.peer = peer;
        this.codec = codec;
        this.listener = listener;
        this.stunServers = List.copyOf(stunServers);
        this.secure = codec.isEncrypted() ? new SecureChannel(codec.keyBytes(), PacketCodec.MAX_FRAME) : null;
        this.receiver = new Thread(this::receiveLoop, "p2p-receiver");
        this.receiver.setDaemon(true);
    }

    public PeerLink start() {
        listener.onStatus(Status.CONNECTING, "punching towards " + describe(peer));
        receiver.start();
        timer.scheduleWithFixedDelay(this::tick, 0, PUNCH_INTERVAL_MS / 2, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::retransmitReliable, RELIABLE_TICK_MS, RELIABLE_TICK_MS,
                TimeUnit.MILLISECONDS);
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

    /**
     * Sends a datagram that may be lost, duplicated never, reordered rarely.
     *
     * @return false if not connected (nothing sent)
     */
    public boolean sendData(byte[] data) {
        checkSize(data.length, PacketCodec.MAX_PAYLOAD);
        if (status != Status.CONNECTED) {
            return false;
        }
        sendRaw(new Packet(Packet.Type.DATA, session, 0, data));
        return true;
    }

    /**
     * Queues bytes for reliable, in-order delivery to the current peer instance. Retransmitted
     * until acknowledged; dropped if the peer restarts (see {@link Listener#onPeerSession}).
     *
     * @return false if no peer has been heard yet or too much is unacknowledged
     */
    public boolean sendReliable(byte[] data) {
        checkSize(data.length, MAX_RELIABLE);
        Pending p;
        synchronized (streamLock) {
            long target = remoteSession;
            if (target == 0 || status == Status.CLOSED || outPending.size() >= MAX_UNACKED) {
                return false;
            }
            byte[] payload = new byte[8 + data.length];
            java.nio.ByteBuffer.wrap(payload).putLong(target).put(data);
            p = new Pending(new Packet(Packet.Type.RDATA, session, outSeq++, payload), null);
            outPending.put(p.packet.seq(), p);
        }
        transmit(p);
        return true;
    }

    private static void checkSize(int length, int max) {
        if (length > max) {
            throw new IllegalArgumentException("payload too large: " + length + " bytes, max " + max);
        }
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
        if (secure != null) {
            secure.close();
        }
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
                    sendRaw(hello());
                    if (secure != null && secure.isEstablished()) {
                        // HELLO alone does not prove we hold the session keys; this does
                        sendRaw(Packet.control(Packet.Type.PING, session));
                    }
                }
            } else if (now - lastSentAt >= PING_INTERVAL_MS) {
                sendRaw(Packet.control(Packet.Type.PING, session));
            }
            for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (now - p.lastSentAt < RETRANSMIT_INTERVAL_MS) {
                    continue;
                }
                if (p.attempts >= MAX_ATTEMPTS || now - p.createdAt > MAX_QUEUE_MS) {
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

    private void retransmitReliable() {
        try {
            if (status != Status.CONNECTED) {
                return;
            }
            long now = System.currentTimeMillis();
            List<Pending> due = new ArrayList<>();
            synchronized (streamLock) {
                for (Pending p : outPending.values()) {
                    if (now - p.lastSentAt >= RELIABLE_RTO_MS) {
                        due.add(p);
                    }
                }
            }
            for (Pending p : due) {
                transmit(p);
            }
        } catch (RuntimeException e) {
            listener.onWarning("timer error: " + e);
        }
    }

    /** Our HELLO; with a passphrase it carries our X25519 key and whether we have the peer's. */
    private Packet hello() {
        if (secure == null) {
            return Packet.control(Packet.Type.HELLO, session);
        }
        byte[] pub = secure.localPublicKey();
        byte[] payload = Arrays.copyOf(pub, pub.length + 1);
        payload[pub.length] = (byte) (secure.isEstablished() ? 1 : 0);
        return new Packet(Packet.Type.HELLO, session, 0, payload);
    }

    private void transmit(Pending p) {
        // Only count tries while connected: messages typed during hole punching must not
        // use up their retry budget before the path even exists.
        if (status == Status.CONNECTED) {
            p.attempts++;
        }
        p.lastSentAt = System.currentTimeMillis();
        sendRaw(p.packet);
    }

    private void sendRaw(Packet packet) {
        if (socket.isClosed()) {
            return;
        }
        if (dropOutgoing.test(packet)) {
            lastSentAt = System.currentTimeMillis();
            return;
        }
        try {
            byte[] data;
            if (secure == null || packet.type() == Packet.Type.HELLO) {
                data = codec.encode(packet);
            } else if (secure.isEstablished()) {
                data = secure.seal(PacketCodec.frame(packet));
            } else {
                return; // no session keys yet; reliable traffic is retried later
            }
            socket.send(new DatagramPacket(data, data.length, peer));
            lastSentAt = System.currentTimeMillis();
        } catch (IOException e) {
            // network may be temporarily down; retries happen on the next tick
            if (!socket.isClosed()) {
                listener.onWarning("send failed: " + e.getMessage());
            }
        } catch (IllegalStateException e) {
            // keys dropped by a concurrent close()
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
        boolean rekeyed = false;
        try {
            if (secure != null && length > 0 && data[0] == SecureChannel.PREFIX) {
                SecureChannel.Opened opened = secure.open(data, length);
                p = PacketCodec.parse(opened.frame());
                rekeyed = opened.rekeyed();
            } else {
                p = codec.decode(data, length);
            }
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

        if (secure != null && p.type() == Packet.Type.HELLO) {
            // A HELLO only proves knowledge of the passphrase (it may be a replay), so it never
            // marks the peer as live; the session packets that follow do.
            handshake(p);
            return;
        }
        lastHeardAt = System.currentTimeMillis();

        if (p.type() == Packet.Type.BYE) {
            setStatus(Status.DISCONNECTED, "peer left, waiting for it to come back");
            return;
        }
        if (p.session() != remoteSession) {
            newPeerSession(p.session());
        }
        if (status != Status.CONNECTED || rekeyed) {
            setStatus(Status.CONNECTED, "connected to " + describe(peer)
                    + (codec.isEncrypted() ? " (encrypted)" : " (NOT encrypted)"));
            // answer right away so the peer's side of the hole opens quickly
            sendRaw(Packet.control(Packet.Type.PING, session));
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
            case DATA -> listener.onData(p.payload());
            case RDATA -> receiveReliable(p);
            case RACK -> {
                synchronized (streamLock) {
                    outPending.remove(p.seq());
                }
            }
            default -> {
                // HELLO / PING only refresh liveness
            }
        }
    }

    private void handshake(Packet p) {
        byte[] payload = p.payload();
        if (payload.length != SecureChannel.PUBLIC_KEY_LEN + 1) {
            return;
        }
        SecureChannel.Handshake result;
        try {
            result = secure.establish(Arrays.copyOf(payload, SecureChannel.PUBLIC_KEY_LEN));
        } catch (IllegalArgumentException e) {
            listener.onWarning("ignoring bad handshake: " + e.getMessage());
            return;
        }
        if (payload[SecureChannel.PUBLIC_KEY_LEN] == 0) {
            sendRaw(hello()); // the peer lacks our key
        }
        if (result == SecureChannel.Handshake.ADOPTED) {
            sendRaw(Packet.control(Packet.Type.PING, session)); // proves we now hold the keys
        }
    }

    private void receiveReliable(Packet p) {
        if (p.payload().length < 8) {
            return;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(p.payload());
        if (buf.getLong() != session) {
            return; // meant for an earlier run of this app
        }
        List<byte[]> ready = new ArrayList<>();
        boolean duplicate;
        synchronized (streamLock) {
            duplicate = p.seq() < inNext || inBuffer.containsKey(p.seq());
            if (!duplicate && p.seq() > inNext && inBuffer.size() >= MAX_UNACKED) {
                return; // far ahead and buffer full: no ack, so the sender retries later
            }
        }
        // duplicates are acked too, since the first ack may have been lost
        sendRaw(new Packet(Packet.Type.RACK, session, p.seq(), null));
        if (duplicate) {
            return;
        }
        synchronized (streamLock) {
            if (p.seq() < inNext || inBuffer.containsKey(p.seq())) {
                return;
            }
            inBuffer.put(p.seq(), Arrays.copyOfRange(p.payload(), 8, p.payload().length));
            byte[] next;
            while ((next = inBuffer.remove(inNext)) != null) {
                ready.add(next);
                inNext++;
            }
        }
        for (byte[] d : ready) {
            listener.onReliable(d);
        }
    }

    private void newPeerSession(long s) {
        synchronized (streamLock) {
            remoteSession = s;
            outPending.clear();
            outSeq = 1;
            inBuffer.clear();
            inNext = 1;
        }
        seen.clear();
        listener.onPeerSession();
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
