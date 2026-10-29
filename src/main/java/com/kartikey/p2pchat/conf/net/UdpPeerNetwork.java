package com.kartikey.p2pchat.conf.net;

import com.kartikey.p2pchat.StunClient;
import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * {@link PeerNetwork} over one UDP socket: mutually authenticated, forward-secret sessions to many
 * peers, NAT hole punching from both sides, relaying through a connected member as a fallback,
 * reliable ordered control messages and unreliable media.
 *
 * <h2>Wire format</h2>
 * Handshake messages are described in {@link Handshake}. Session packets are
 * {@code 0xD4 | sessionId(8) | counter(8) | AES-256-GCM(channel(1) | payload)} with the 17-byte
 * header as AAD, the counter as nonce (one key per direction) and a 2048-packet replay window.
 * Channels: MEDIA, CONTROL (segments of {@link ReliableStream}), ACK, PING/PONG (8-byte id), CLOSE,
 * RELAY {@code targetFp(16) | inner datagram} and RELAY_DELIVER {@code sourceFp(16) | inner}.
 * Relayed traffic is a whole inner datagram (handshake or session packet) carried inside the
 * relay's own encrypted sessions, so the relay sees only ciphertext of the end-to-end session.
 * STUN responses share the socket and are told apart by their first byte.
 *
 * <h2>Threads</h2>
 * One receive thread, one 5 ms timer thread and one dispatcher thread that alone calls the
 * {@link Listener} (never with a lock held). All state is guarded by one lock; send methods may be
 * called from any thread, including the listener.
 */
public final class UdpPeerNetwork implements PeerNetwork {

    public static final int DEFAULT_PORT = 7302;
    /** Largest media packet {@link #sendMedia} accepts. */
    public static final int MAX_MEDIA = 4096;
    /** Largest control message {@link #sendControl} accepts. */
    public static final int MAX_CONTROL = ReliableStream.MAX_MESSAGE;

    static final byte CH_MEDIA = 1;
    static final byte CH_CONTROL = 2;
    static final byte CH_ACK = 3;
    static final byte CH_PING = 4;
    static final byte CH_PONG = 5;
    static final byte CH_CLOSE = 6;
    static final byte CH_RELAY = 7;
    static final byte CH_RELAY_DELIVER = 8;

    static final int HDR = 17;
    static final int TAG = 16;
    static final int FP_LEN = 16;
    private static final int MAX_DATAGRAM = 8192;
    private static final int MAX_INBOUND = 256;
    private static final int MAX_OUTBOUND = 1024;
    private static final int MAX_PEERS = 1024;
    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_RATE_KEYS = 4096;
    private static final int MAX_MEDIA_BACKLOG = 8192;
    private static final long MAX_CONTROL_BACKLOG = 32L << 20;
    private static final long SUSPEND_MILLIS = 1_500;
    private static final byte[] EMPTY = new byte[0];
    private static final Object POISON = new Object();
    private static final System.Logger LOG = System.getLogger(UdpPeerNetwork.class.getName());

    // ---- tunables (package-private so tests can shorten them before start)
    long timeoutMillis = 10_000;
    long pingIntervalMillis = 2_000;
    long punchIntervalMillis = 250;
    long connectWindowMillis = 10_000;
    long relayAfterMillis = 3_000;
    long staleHelloGraceMillis = 2_000;
    long maxUnackedBytes = 1L << 20;
    double handshakesPerSourcePerSecond = 10;
    double handshakesPerSecond = 200;
    double relayPacketsPerSecond = 4_000;
    private volatile double dropRate;

    private final Identity me;
    private final ParticipantId self;
    private final byte[] selfEncoded;
    private final int requestedPort;
    private final Predicate<ParticipantId> acceptPeer;
    private final List<String> stunServers;

    private final Object lock = new Object();
    private final Map<ParticipantId, Peer> peers = new HashMap<>();
    private final Map<ByteBuffer, Peer> peersByFp = new HashMap<>();
    private final Map<Long, Session> sessions = new HashMap<>();
    private final Map<ParticipantId, Outbound> outbound = new HashMap<>();
    private final Map<ByteBuffer, Outbound> outboundByNonce = new HashMap<>();
    private final LinkedHashMap<ByteBuffer, Inbound> inbound = new LinkedHashMap<>();
    private final Map<ByteBuffer, Inbound> inboundByNonceR = new HashMap<>();
    private final Map<Object, TokenBucket> hsBuckets = lru();
    private final Map<Object, TokenBucket> pktBuckets = lru();
    private TokenBucket globalHs;
    private final List<InetSocketAddress> localCandidates = new ArrayList<>();
    private final Set<InetSocketAddress> publicCandidates = new LinkedHashSet<>();
    private final List<InetSocketAddress> stunTargets = new ArrayList<>();
    private final Map<ByteBuffer, InetSocketAddress> stunPending = new HashMap<>();
    private int stunRounds;
    private long stunNextAt;

    private final LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>();
    private final AtomicInteger mediaBacklog = new AtomicInteger();
    private final AtomicLong controlBacklog = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();
    /** Relayed inner datagrams to process after the lock is released (receive thread only). */
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();

    private volatile Listener listener;
    private volatile boolean closed;
    private volatile DatagramSocket socket;
    private Thread rxThread, timerThread, dispatchThread;

    public UdpPeerNetwork(Identity me, int port, Predicate<ParticipantId> acceptPeer, List<String> stunServers) {
        this.me = Objects.requireNonNull(me);
        this.self = me.id();
        this.selfEncoded = self.encoded();
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("bad port " + port);
        }
        this.requestedPort = port;
        this.acceptPeer = acceptPeer == null ? p -> true : acceptPeer;
        this.stunServers = stunServers == null ? List.of() : List.copyOf(stunServers);
    }

    /** Binds the socket and starts the threads. */
    public synchronized void start() throws IOException {
        if (socket != null || closed) {
            throw new IllegalStateException("already started or closed");
        }
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReceiveBufferSize(4 << 20);
            s.setSendBufferSize(1 << 20);
            s.bind(new InetSocketAddress(requestedPort));
        } catch (IOException e) {
            s.close();
            throw e;
        }
        long now = now();
        synchronized (lock) {
            socket = s;
            globalHs = new TokenBucket(handshakesPerSecond, handshakesPerSecond, now);
            localCandidates.addAll(findLocalAddresses(s));
        }
        rxThread = new Thread(this::receiveLoop, "p2pconf-net-rx");
        timerThread = new Thread(this::timerLoop, "p2pconf-net-timer");
        dispatchThread = new Thread(this::dispatchLoop, "p2pconf-net-dispatch");
        for (Thread t : List.of(rxThread, timerThread, dispatchThread)) {
            t.setDaemon(true);
            t.start();
        }
    }

    /** Local UDP port (after {@link #start}). */
    public int port() {
        DatagramSocket s = socket;
        return s == null ? -1 : s.getLocalPort();
    }

    // ============================================================== PeerNetwork

    @Override
    public ParticipantId self() {
        return self;
    }

    @Override
    public List<InetSocketAddress> candidates() {
        synchronized (lock) {
            List<InetSocketAddress> out = new ArrayList<>(publicCandidates);
            for (InetSocketAddress a : localCandidates) {
                if (!out.contains(a)) {
                    out.add(a);
                }
            }
            return out;
        }
    }

    @Override
    public void connect(ParticipantId peer, List<InetSocketAddress> candidates, ParticipantId via) {
        Objects.requireNonNull(peer);
        if (peer.equals(self) || closed || socket == null) {
            return;
        }
        List<InetSocketAddress> cands = new ArrayList<>();
        if (candidates != null) {
            for (InetSocketAddress a : candidates) {
                if (a != null && !a.isUnresolved() && a.getPort() > 0 && !cands.contains(a)
                        && cands.size() < MAX_CANDIDATES) {
                    cands.add(a);
                }
            }
        }
        ParticipantId relay = via != null && !via.equals(peer) && !via.equals(self) ? via : null;
        long now = now();
        synchronized (lock) {
            if (closed || peers.containsKey(peer)) {
                return;
            }
            Outbound o = outbound.get(peer);
            if (o != null) {
                for (InetSocketAddress a : cands) {
                    if (!o.cands.contains(a) && o.cands.size() < MAX_CANDIDATES) {
                        o.cands.add(a);
                    }
                }
                if (relay != null) {
                    o.via = relay;
                }
                return;
            }
            if (outbound.size() >= MAX_OUTBOUND) {
                return;
            }
            o = new Outbound(peer, cands, relay, now);
            outbound.put(peer, o);
            outboundByNonce.put(ByteBuffer.wrap(o.nonce), o);
            punch(o, now);
        }
    }

    @Override
    public void disconnect(ParticipantId peer) {
        synchronized (lock) {
            removeOutbound(peer);
            Peer p = peers.get(peer);
            if (p == null) {
                return;
            }
            if (p.session.confirmed) {
                sendOn(p, CH_CLOSE, EMPTY, null);
                sendOn(p, CH_CLOSE, EMPTY, null);
            }
            dropPeer(p);
        }
    }

    @Override
    public Set<ParticipantId> connected() {
        synchronized (lock) {
            Set<ParticipantId> out = new LinkedHashSet<>();
            for (Peer p : peers.values()) {
                if (p.announced) {
                    out.add(p.id);
                }
            }
            return Collections.unmodifiableSet(out);
        }
    }

    @Override
    public boolean sendMedia(ParticipantId peer, byte[] packet) {
        if (packet == null || packet.length > MAX_MEDIA) {
            return false;
        }
        synchronized (lock) {
            Peer p = peers.get(peer);
            return p != null && p.announced && sendOn(p, CH_MEDIA, packet, null);
        }
    }

    @Override
    public boolean sendControl(ParticipantId peer, byte[] message) {
        if (message == null || message.length > MAX_CONTROL) {
            return false;
        }
        long now = now();
        synchronized (lock) {
            Peer p = peers.get(peer);
            if (p == null || !p.announced || !p.rel.offer(message)) {
                return false;
            }
            pump(p, now);
            return true;
        }
    }

    @Override
    public LinkStats stats(ParticipantId peer) {
        synchronized (lock) {
            Peer p = peers.get(peer);
            if (p == null || !p.announced) {
                return null;
            }
            double rtt = p.rttMs >= 0 ? p.rttMs : Math.max(0, p.rel.srtt());
            return new LinkStats((int) Math.round(rtt), Math.min(1, p.loss), p.bytesSent, p.bytesRecv,
                    p.session.path.via != null);
        }
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Peer p : peers.values()) {
                if (p.session.confirmed) {
                    sendOn(p, CH_CLOSE, EMPTY, null);
                }
            }
            peers.clear();
            peersByFp.clear();
            sessions.clear();
            outbound.clear();
            outboundByNonce.clear();
            inbound.clear();
            inboundByNonceR.clear();
        }
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
        events.add(POISON);
        for (Thread t : new Thread[] {timerThread, rxThread, dispatchThread}) {
            if (t != null && t != Thread.currentThread()) {
                t.interrupt();
                try {
                    t.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ============================================================== test hooks

    /** Drops this fraction of outgoing datagrams (loss tests). */
    void setDropRate(double rate) {
        this.dropRate = rate;
    }

    /** Current session id with {@code peer}, or null. */
    Long sessionId(ParticipantId peer) {
        synchronized (lock) {
            Peer p = peers.get(peer);
            return p == null ? null : p.session.sid;
        }
    }

    long malformedCount() {
        return malformed.get();
    }

    int halfOpenCount() {
        synchronized (lock) {
            return inbound.size();
        }
    }

    // ============================================================== state

    /** Where a peer's packets go: straight to an address, or through a connected relay. */
    private record Path(InetSocketAddress direct, ParticipantId via) {
        static Path direct(InetSocketAddress a) {
            return new Path(a, null);
        }

        static Path relay(ParticipantId via) {
            return new Path(null, via);
        }

        Object rateKey() {
            return direct != null ? direct.getAddress() : via;
        }
    }

    private record Deferred(byte[] data, Path path, byte[] claimedFp) {}

    private record Connected(ParticipantId peer) {}

    private record Disconnected(ParticipantId peer) {}

    private record Media(ParticipantId from, byte[] data) {}

    private record Control(ParticipantId from, byte[] data) {}

    private static final class Session {
        final long sid;
        final Peer peer;
        final boolean initiator;
        final byte[] nonceI;
        final long createdAt;
        final SecretKeySpec sendKey;
        final SecretKeySpec recvKey;
        final Cipher enc;
        final Cipher dec;
        final ReplayWindow replay = new ReplayWindow();
        long sendCtr;
        Path path;
        boolean confirmed;

        Session(long sid, Peer peer, boolean initiator, byte[] nonceI, byte[] sendKey, byte[] recvKey, Path path,
                long now) {
            this.sid = sid;
            this.peer = peer;
            this.initiator = initiator;
            this.nonceI = nonceI;
            this.sendKey = new SecretKeySpec(sendKey, "AES");
            this.recvKey = new SecretKeySpec(recvKey, "AES");
            this.path = path;
            this.createdAt = now;
            try {
                this.enc = Cipher.getInstance("AES/GCM/NoPadding");
                this.dec = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM unavailable", e);
            }
        }
    }

    private final class Peer {
        final ParticipantId id;
        final byte[] fp;
        final TokenBucket relayBucket;
        Session session;
        ReliableStream rel;
        boolean announced;
        long lastRecv;
        byte[] confirmMsg;
        long confirmNextAt;
        long pingId;
        long pingSentAt;
        boolean pingOutstanding;
        long nextPingAt;
        double rttMs = -1;
        double loss;
        long bytesSent;
        long bytesRecv;
        long lastTx;
        long lastRetx;

        Peer(ParticipantId id, long now) {
            this.id = id;
            this.fp = fingerprint(id);
            this.relayBucket = new TokenBucket(relayPacketsPerSecond, relayPacketsPerSecond / 4, now);
        }
    }

    private final class Outbound {
        final ParticipantId peer;
        final byte[] peerEncoded;
        final List<InetSocketAddress> cands;
        final long startedAt;
        final KeyPair eph;
        final byte[] ephPub;
        final byte[] nonce = Crypto.random(Handshake.NONCE_LEN);
        final byte[] hello;
        ParticipantId via;
        long nextSendAt;
        long lastHelloAt;
        long suspendedUntil;
        int ackAttempts;

        Outbound(ParticipantId peer, List<InetSocketAddress> cands, ParticipantId via, long now) {
            this.peer = peer;
            this.peerEncoded = peer.encoded();
            this.cands = cands;
            this.via = via;
            this.startedAt = now;
            this.eph = Handshake.newEphemeral();
            this.ephPub = eph.getPublic().getEncoded();
            this.hello = Handshake.encodeHello(ephPub, self, nonce);
        }
    }

    private static final class Inbound {
        final ByteBuffer key;
        final ParticipantId idI;
        final byte[] nonceI;
        final ByteBuffer nonceR;
        final byte[] th;
        final Handshake.Keys keys;
        final byte[] ack;
        final long createdAt;
        long ackSentAt;
        int confirmAttempts;
        boolean done;
        long sid;

        Inbound(ByteBuffer key, ParticipantId idI, byte[] nonceI, byte[] nonceR, byte[] th, Handshake.Keys keys,
                byte[] ack, long now) {
            this.key = key;
            this.idI = idI;
            this.nonceI = nonceI;
            this.nonceR = ByteBuffer.wrap(nonceR);
            this.th = th;
            this.keys = keys;
            this.ack = ack;
            this.createdAt = now;
        }
    }

    // ============================================================== receive path

    private void receiveLoop() {
        byte[] buf = new byte[MAX_DATAGRAM + 1];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        DatagramSocket s = socket;
        while (!closed) {
            try {
                pkt.setLength(buf.length);
                s.receive(pkt);
            } catch (IOException e) {
                if (closed || s.isClosed()) {
                    break;
                }
                LockSupport.parkNanos(1_000_000);
                continue;
            }
            int len = pkt.getLength();
            if (len == 0 || len > MAX_DATAGRAM) {
                malformed.incrementAndGet();
                continue;
            }
            try {
                onDatagram(buf, len, Path.direct((InetSocketAddress) pkt.getSocketAddress()), null);
            } catch (RuntimeException e) {
                malformed.incrementAndGet();
            }
            Deferred d;
            while ((d = deferred.poll()) != null) {
                try {
                    onDatagram(d.data, d.data.length, d.path, d.claimedFp);
                } catch (RuntimeException e) {
                    malformed.incrementAndGet();
                }
            }
        }
    }

    /** Handles one datagram (direct, or the inner datagram of a RELAY_DELIVER when claimedFp != null). */
    private void onDatagram(byte[] d, int len, Path path, byte[] claimedFp) {
        byte type = d[0];
        if (type == Handshake.DATA) {
            onData(d, len, path, claimedFp);
        } else if (type == Handshake.HELLO) {
            onHello(d, len, path, claimedFp);
        } else if (type == Handshake.HELLO_ACK) {
            onHelloAck(d, len, path, claimedFp);
        } else if (type == Handshake.CONFIRM) {
            onConfirm(d, len, path, claimedFp);
        } else if (claimedFp == null && StunClient.isStunMessage(d, len)) {
            onStun(d, len, path.direct());
        } else {
            malformed.incrementAndGet();
        }
    }

    private void onHello(byte[] d, int len, Path path, byte[] claimedFp) {
        if (!Handshake.looksLikeHello(d, len)) {
            malformed.incrementAndGet();
            return;
        }
        long now = now();
        ByteBuffer key = ByteBuffer.wrap(Crypto.sha256(Arrays.copyOf(d, Handshake.HELLO_BODY)));
        Object rateKey = path.rateKey();
        synchronized (lock) {
            Inbound existing = inbound.get(key);
            if (existing != null) {
                // a retransmitted (or re-routed) HELLO: resend the same answer, no new crypto
                if (!existing.done && bucket(pktBuckets, rateKey, 50, now).take(now)) {
                    sendHandshake(path, existing.idI, existing.ack);
                }
                return;
            }
            if (!bucket(hsBuckets, rateKey, handshakesPerSourcePerSecond, now).take(now) || !globalHs.take(now)) {
                return;
            }
        }
        Handshake.Hello h = Handshake.parseHello(d, len);
        ParticipantId id = h.id();
        if (id.equals(self) || (claimedFp != null && !Arrays.equals(claimedFp, fingerprint(id))) || !accept(id)) {
            return;
        }
        synchronized (lock) {
            if (!shouldRespond(id, h.nonce(), path, now)) {
                return;
            }
        }
        KeyPair eph = Handshake.newEphemeral();
        byte[] ephR = eph.getPublic().getEncoded();
        byte[] nonceR = Crypto.random(Handshake.NONCE_LEN);
        byte[] th = Handshake.transcriptHash(h.eph(), id.encoded(), h.nonce(), ephR, selfEncoded, nonceR);
        Handshake.Keys keys = Handshake.deriveKeys(th, Crypto.x25519(eph.getPrivate(), h.eph()));
        byte[] sig = me.sign(Handshake.responderSigInput(th));
        byte[] ack = Handshake.encodeAck(h.nonce(), ephR, self, nonceR, sig, keys.confR());
        synchronized (lock) {
            if (closed || inbound.containsKey(key) || !shouldRespond(id, h.nonce(), path, now)) {
                return;
            }
            Outbound o = outbound.get(id);
            if (o != null) {
                // we have the higher id: answer theirs and ignore answers to ours for a while
                o.suspendedUntil = now + SUSPEND_MILLIS;
            }
            Inbound in = new Inbound(key, id, h.nonce(), nonceR, th, keys, ack, now);
            while (inbound.size() >= MAX_INBOUND) {
                removeInbound(inbound.values().iterator().next());
            }
            inbound.put(key, in);
            inboundByNonceR.put(in.nonceR, in);
            in.ackSentAt = now;
            sendHandshake(path, id, ack);
        }
    }

    /**
     * Simultaneous-open and replay rules, with the lock held. The lower id is the designated
     * initiator: while it is trying to reach the peer it ignores the peer's HELLOs (but learns the
     * address they came from), and the higher id answers them.
     */
    private boolean shouldRespond(ParticipantId id, byte[] nonceI, Path path, long now) {
        Outbound o = outbound.get(id);
        if (o != null && self.compareTo(id) < 0) {
            if (path.direct() != null) {
                if (!o.cands.contains(path.direct()) && o.cands.size() < MAX_CANDIDATES) {
                    o.cands.add(path.direct());
                }
            } else if (o.via == null) {
                o.via = path.via();
            }
            return false;
        }
        Peer p = peers.get(id);
        if (p != null) {
            Session s = p.session;
            return !(s.initiator && !s.confirmed) && !Arrays.equals(s.nonceI, nonceI)
                    && now - s.createdAt >= staleHelloGraceMillis;
        }
        return true;
    }

    private void onHelloAck(byte[] d, int len, Path path, byte[] claimedFp) {
        Handshake.Ack a = Handshake.parseAck(d, len);
        long now = now();
        Outbound o;
        synchronized (lock) {
            o = outboundByNonce.get(ByteBuffer.wrap(a.nonceI()));
            if (o == null || now < o.suspendedUntil || o.ackAttempts >= 16) {
                return;
            }
            o.ackAttempts++;
        }
        if (!Arrays.equals(a.idR(), o.peerEncoded)
                || (claimedFp != null && !Arrays.equals(claimedFp, fingerprint(o.peer)))) {
            return;
        }
        byte[] th = Handshake.transcriptHash(o.ephPub, selfEncoded, o.nonce, a.ephR(), a.idR(), a.nonceR());
        Handshake.Keys keys = Handshake.deriveKeys(th, Crypto.x25519(o.eph.getPrivate(), a.ephR()));
        if (!Handshake.constantTimeEquals(keys.confR(), a.conf())
                || !o.peer.verify(Handshake.responderSigInput(th), a.sig())) {
            return;
        }
        byte[] confirm = Handshake.encodeConfirm(o.nonce, a.nonceR(), me.sign(Handshake.initiatorSigInput(th)),
                keys.confI());
        synchronized (lock) {
            if (closed || outbound.get(o.peer) != o) {
                return;
            }
            Peer p = installSession(o.peer, keys, true, o.nonce, path, now, now - o.lastHelloAt);
            if (p != null) {
                p.confirmMsg = confirm;
                p.confirmNextAt = now + punchIntervalMillis;
                sendHandshake(path, o.peer, confirm);
            }
        }
    }

    private void onConfirm(byte[] d, int len, Path path, byte[] claimedFp) {
        Handshake.Confirm c = Handshake.parseConfirm(d, len);
        long now = now();
        Inbound in;
        synchronized (lock) {
            in = inboundByNonceR.get(ByteBuffer.wrap(c.nonceR()));
            if (in == null || !Arrays.equals(in.nonceI, c.nonceI())) {
                return;
            }
            if (in.done) {
                // our first packet was lost: poke the initiator again
                Peer p = peers.get(in.idI);
                if (p != null && p.session.sid == in.sid && bucket(pktBuckets, path.rateKey(), 50, now).take(now)) {
                    sendPing(p, now);
                }
                return;
            }
            if (in.confirmAttempts++ >= 8) {
                return;
            }
        }
        if ((claimedFp != null && !Arrays.equals(claimedFp, fingerprint(in.idI)))
                || !Handshake.constantTimeEquals(in.keys.confI(), c.conf())
                || !in.idI.verify(Handshake.initiatorSigInput(in.th), c.sig())) {
            return;
        }
        synchronized (lock) {
            if (closed || in.done || inboundByNonceR.get(in.nonceR) != in) {
                return;
            }
            Peer p = installSession(in.idI, in.keys, false, in.nonceI, path, now, now - in.ackSentAt);
            if (p == null) {
                return;
            }
            in.done = true;
            in.sid = p.session.sid;
            p.session.confirmed = true;
            announce(p);
            sendPing(p, now);
        }
    }

    private void onData(byte[] d, int len, Path path, byte[] claimedFp) {
        if (len < HDR + 1 + TAG) {
            malformed.incrementAndGet();
            return;
        }
        ByteBuffer hdr = ByteBuffer.wrap(d, 1, 16);
        long sid = hdr.getLong(), ctr = hdr.getLong();
        long now = now();
        synchronized (lock) {
            Session s = sessions.get(sid);
            if (s == null || s.replay.isReplay(ctr)) {
                return;
            }
            Peer p = s.peer;
            if (claimedFp != null && !Arrays.equals(claimedFp, p.fp)) {
                return;
            }
            byte[] pt = open(s, d, len, ctr);
            if (pt == null || pt.length == 0) {
                malformed.incrementAndGet();
                return;
            }
            boolean newest = s.replay.mark(ctr);
            p.lastRecv = now;
            p.bytesRecv += len;
            if (newest && !path.equals(s.path)) {
                s.path = path; // roaming, or moving between direct and relayed
            }
            if (!s.confirmed) {
                s.confirmed = true;
                p.confirmMsg = null;
                announce(p);
            }
            onChannel(p, pt, claimedFp != null, now);
        }
    }

    private void onChannel(Peer p, byte[] pt, boolean relayed, long now) {
        int n = pt.length - 1;
        switch (pt[0]) {
            case CH_MEDIA -> {
                if (mediaBacklog.get() < MAX_MEDIA_BACKLOG) {
                    mediaBacklog.incrementAndGet();
                    events.add(new Media(p.id, Arrays.copyOfRange(pt, 1, pt.length)));
                }
            }
            case CH_CONTROL -> {
                if (controlBacklog.get() > MAX_CONTROL_BACKLOG) {
                    return; // the app is not keeping up: drop unacked, the sender retransmits later
                }
                try {
                    p.rel.onSegment(pt, 1, n, now, m -> {
                        controlBacklog.addAndGet(m.length);
                        events.add(new Control(p.id, m));
                    });
                } catch (ReliableStream.ProtocolException e) {
                    LOG.log(System.Logger.Level.DEBUG, "dropping " + p.id + ": " + e.getMessage());
                    sendOn(p, CH_CLOSE, EMPTY, null);
                    dropPeer(p);
                    return;
                }
                if (p.rel.ackDue(now)) {
                    sendOn(p, CH_ACK, p.rel.buildAck(), null);
                }
            }
            case CH_ACK -> {
                p.rel.onAck(pt, 1, n, now);
                pump(p, now);
            }
            case CH_PING -> {
                if (n == 8) {
                    sendOn(p, CH_PONG, Arrays.copyOfRange(pt, 1, 9), null);
                }
            }
            case CH_PONG -> {
                if (n == 8 && p.pingOutstanding && ByteBuffer.wrap(pt, 1, 8).getLong() == p.pingId) {
                    p.pingOutstanding = false;
                    double rtt = Math.max(0, now - p.pingSentAt);
                    p.rttMs = p.rttMs < 0 ? rtt : 0.875 * p.rttMs + 0.125 * rtt;
                    p.loss = 0.875 * p.loss;
                }
            }
            case CH_CLOSE -> dropPeer(p);
            case CH_RELAY -> {
                if (!relayed && n > FP_LEN) {
                    forwardRelay(p, pt, now);
                }
            }
            case CH_RELAY_DELIVER -> {
                if (!relayed && n > FP_LEN && p.announced && p.session.path.direct() != null) {
                    deferred.add(new Deferred(Arrays.copyOfRange(pt, 1 + FP_LEN, pt.length), Path.relay(p.id),
                            Arrays.copyOfRange(pt, 1, 1 + FP_LEN)));
                }
            }
            default -> malformed.incrementAndGet();
        }
    }

    /** Forwards {@code from}'s RELAY to another of our directly connected peers, rate-limited. */
    private void forwardRelay(Peer from, byte[] pt, long now) {
        if (!from.announced || from.session.path.direct() == null) {
            return;
        }
        Peer to = peersByFp.get(ByteBuffer.wrap(pt, 1, FP_LEN).slice());
        if (to == null || to == from || !to.announced || to.session.path.direct() == null
                || !from.relayBucket.take(now)) {
            return;
        }
        sendOn(to, CH_RELAY_DELIVER, from.fp, Arrays.copyOfRange(pt, 1 + FP_LEN, pt.length));
    }

    private void onStun(byte[] d, int len, InetSocketAddress from) {
        byte[] tx = Arrays.copyOfRange(d, 8, 20);
        synchronized (lock) {
            InetSocketAddress server = stunPending.get(ByteBuffer.wrap(tx));
            if (server == null || from == null || !server.getAddress().equals(from.getAddress())) {
                return;
            }
            InetSocketAddress mapped = StunClient.parseResponse(d, len, tx);
            if (mapped != null && publicCandidates.size() < 4) {
                stunPending.remove(ByteBuffer.wrap(tx));
                publicCandidates.add(mapped);
            }
        }
    }

    // ============================================================== sessions

    /** Installs a fresh session with {@code id}, replacing (and reporting the loss of) any old one. */
    private Peer installSession(ParticipantId id, Handshake.Keys keys, boolean initiator, byte[] nonceI, Path path,
                                long now, long rttSample) {
        if (sessions.containsKey(keys.sessionId())) {
            return null;
        }
        Peer p = peers.get(id);
        if (p != null) {
            sessions.remove(p.session.sid);
            if (p.announced) {
                events.add(new Disconnected(id));
            }
        } else {
            if (peers.size() >= MAX_PEERS) {
                return null;
            }
            p = new Peer(id, now);
            peers.put(id, p);
            peersByFp.put(ByteBuffer.wrap(p.fp), p);
        }
        Session s = new Session(keys.sessionId(), p, initiator, nonceI, initiator ? keys.i2r() : keys.r2i(),
                initiator ? keys.r2i() : keys.i2r(), path, now);
        long rtt = Math.max(1, rttSample);
        p.session = s;
        p.rel = new ReliableStream(maxUnackedBytes, Math.max(200, 3 * rtt));
        p.rel.seedRtt(rtt);
        p.rttMs = rtt;
        p.announced = false;
        p.lastRecv = now;
        p.confirmMsg = null;
        p.pingOutstanding = false;
        p.nextPingAt = now + pingIntervalMillis;
        p.lastTx = 0;
        p.lastRetx = 0;
        sessions.put(s.sid, s);
        removeOutbound(id);
        return p;
    }

    private void announce(Peer p) {
        if (!p.announced) {
            p.announced = true;
            events.add(new Connected(p.id));
        }
    }

    private void dropPeer(Peer p) {
        if (peers.get(p.id) != p) {
            return;
        }
        peers.remove(p.id);
        peersByFp.remove(ByteBuffer.wrap(p.fp));
        sessions.remove(p.session.sid);
        if (p.announced) {
            events.add(new Disconnected(p.id));
        }
    }

    private void removeOutbound(ParticipantId id) {
        Outbound o = outbound.remove(id);
        if (o != null) {
            outboundByNonce.remove(ByteBuffer.wrap(o.nonce));
        }
    }

    private void removeInbound(Inbound in) {
        inbound.remove(in.key);
        inboundByNonceR.remove(in.nonceR);
    }

    private void pump(Peer p, long now) {
        p.rel.poll(now, seg -> sendOn(p, CH_CONTROL, seg, null));
        if (p.rel.ackDue(now)) {
            sendOn(p, CH_ACK, p.rel.buildAck(), null);
        }
    }

    private void sendPing(Peer p, long now) {
        if (p.pingOutstanding) {
            p.loss = 0.875 * p.loss + 0.125;
        }
        p.pingId = ThreadLocalRandom.current().nextLong();
        p.pingSentAt = now;
        p.pingOutstanding = true;
        p.nextPingAt = now + pingIntervalMillis;
        sendOn(p, CH_PING, ByteBuffer.allocate(8).putLong(p.pingId).array(), null);
    }

    /** Encrypts {@code ch | a | b} on p's session and routes it. */
    private boolean sendOn(Peer p, byte ch, byte[] a, byte[] b) {
        Session s = p.session;
        byte[] pkt = seal(s, ch, a, b);
        p.bytesSent += pkt.length;
        if (s.path.direct() != null) {
            return rawSend(pkt, s.path.direct());
        }
        return relayVia(s.path.via(), p.fp, pkt);
    }

    private boolean relayVia(ParticipantId via, byte[] targetFp, byte[] datagram) {
        Peer v = peers.get(via);
        if (v == null || !v.announced || v.session.path.direct() == null) {
            return false;
        }
        return sendOn(v, CH_RELAY, targetFp, datagram);
    }

    private void sendHandshake(Path path, ParticipantId target, byte[] msg) {
        if (path.direct() != null) {
            rawSend(msg, path.direct());
        } else {
            relayVia(path.via(), fingerprint(target), msg);
        }
    }

    private static byte[] seal(Session s, byte ch, byte[] a, byte[] b) {
        int plen = 1 + a.length + (b == null ? 0 : b.length);
        byte[] pt = new byte[plen];
        pt[0] = ch;
        System.arraycopy(a, 0, pt, 1, a.length);
        if (b != null) {
            System.arraycopy(b, 0, pt, 1 + a.length, b.length);
        }
        byte[] out = new byte[HDR + plen + TAG];
        long ctr = s.sendCtr++;
        ByteBuffer.wrap(out).put(Handshake.DATA).putLong(s.sid).putLong(ctr);
        try {
            s.enc.init(Cipher.ENCRYPT_MODE, s.sendKey, new GCMParameterSpec(TAG * 8, nonce(ctr)));
            s.enc.updateAAD(out, 0, HDR);
            s.enc.doFinal(pt, 0, plen, out, HDR);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
        return out;
    }

    private static byte[] open(Session s, byte[] d, int len, long ctr) {
        try {
            s.dec.init(Cipher.DECRYPT_MODE, s.recvKey, new GCMParameterSpec(TAG * 8, nonce(ctr)));
            s.dec.updateAAD(d, 0, HDR);
            return s.dec.doFinal(d, HDR, len - HDR);
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] nonce(long ctr) {
        return ByteBuffer.allocate(Crypto.NONCE_LEN).putInt(0).putLong(ctr).array();
    }

    private boolean rawSend(byte[] data, InetSocketAddress to) {
        DatagramSocket s = socket;
        if (s == null) {
            return false;
        }
        double drop = dropRate;
        if (drop > 0 && ThreadLocalRandom.current().nextDouble() < drop) {
            return true;
        }
        try {
            s.send(new DatagramPacket(data, data.length, to));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ============================================================== timer

    private void timerLoop() {
        resolveStun();
        while (!closed) {
            try {
                tick(now());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "network timer failed", e);
            }
            LockSupport.parkNanos(5_000_000);
        }
    }

    private void tick(long now) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            for (Outbound o : new ArrayList<>(outbound.values())) {
                if (now - o.startedAt >= connectWindowMillis) {
                    removeOutbound(o.peer);
                } else if (now >= o.nextSendAt) {
                    punch(o, now);
                }
            }
            for (Iterator<Inbound> it = inbound.values().iterator(); it.hasNext(); ) {
                Inbound in = it.next();
                if (now - in.createdAt < connectWindowMillis) {
                    break;
                }
                it.remove();
                inboundByNonceR.remove(in.nonceR);
            }
            for (Peer p : new ArrayList<>(peers.values())) {
                tickPeer(p, now);
            }
            tickStun(now);
        }
    }

    private void punch(Outbound o, long now) {
        o.nextSendAt = now + punchIntervalMillis;
        o.lastHelloAt = now;
        for (InetSocketAddress a : o.cands) {
            rawSend(o.hello, a);
        }
        if (o.via != null && now - o.startedAt >= relayAfterMillis) {
            relayVia(o.via, fingerprint(o.peer), o.hello);
        }
    }

    private void tickPeer(Peer p, long now) {
        Session s = p.session;
        if (!s.confirmed) {
            if (now - s.createdAt >= connectWindowMillis) {
                dropPeer(p);
            } else if (p.confirmMsg != null && now >= p.confirmNextAt) {
                p.confirmNextAt = now + punchIntervalMillis;
                sendHandshake(s.path, p.id, p.confirmMsg);
            }
            return;
        }
        if (now - p.lastRecv > timeoutMillis) {
            dropPeer(p);
            return;
        }
        long tx = p.rel.transmissions(), retx = p.rel.retransmissions();
        if (tx - p.lastTx >= 50 || (now >= p.nextPingAt && tx - p.lastTx >= 10)) {
            // control retransmission ratio as a loss sample (pings cover idle links)
            double ratio = (double) (retx - p.lastRetx) / (tx - p.lastTx);
            p.loss = 0.75 * p.loss + 0.25 * ratio;
            p.lastTx = tx;
            p.lastRetx = retx;
        }
        if (now >= p.nextPingAt) {
            sendPing(p, now);
        }
        pump(p, now);
    }

    private void resolveStun() {
        List<InetSocketAddress> targets = new ArrayList<>();
        for (String server : stunServers) {
            if (closed) {
                return;
            }
            try {
                int colon = server.lastIndexOf(':');
                String host = colon > 0 ? server.substring(0, colon) : server;
                int port = colon > 0 ? Integer.parseInt(server.substring(colon + 1)) : 3478;
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                InetSocketAddress a = new InetSocketAddress(InetAddress.getByName(host), port);
                if (!a.isUnresolved()) {
                    targets.add(a);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot resolve STUN server " + server);
            }
        }
        synchronized (lock) {
            stunTargets.addAll(targets);
        }
    }

    private void tickStun(long now) {
        if (stunTargets.isEmpty() || !publicCandidates.isEmpty() || stunRounds >= 3 || now < stunNextAt) {
            return;
        }
        stunRounds++;
        stunNextAt = now + 700;
        stunPending.clear();
        for (InetSocketAddress server : stunTargets) {
            byte[] tx = StunClient.newTransactionId();
            stunPending.put(ByteBuffer.wrap(tx), server);
            rawSend(StunClient.bindingRequest(tx), server);
        }
    }

    // ============================================================== dispatcher

    private void dispatchLoop() {
        while (true) {
            Object e;
            try {
                e = events.take();
            } catch (InterruptedException ie) {
                if (closed) {
                    return;
                }
                continue;
            }
            if (e == POISON) {
                return;
            }
            if (e instanceof Media m) {
                mediaBacklog.decrementAndGet();
            } else if (e instanceof Control c) {
                controlBacklog.addAndGet(-c.data.length);
            }
            Listener l = listener;
            if (l == null || closed) {
                continue;
            }
            try {
                if (e instanceof Media m) {
                    l.onMedia(m.from, m.data);
                } else if (e instanceof Control c) {
                    l.onControl(c.from, c.data);
                } else if (e instanceof Connected c) {
                    l.onConnected(c.peer);
                } else if (e instanceof Disconnected d) {
                    l.onDisconnected(d.peer);
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "listener failed", ex);
            }
        }
    }

    // ============================================================== helpers

    private boolean accept(ParticipantId id) {
        try {
            return acceptPeer.test(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TokenBucket bucket(Map<Object, TokenBucket> map, Object key, double perSecond, long now) {
        return map.computeIfAbsent(key, k -> new TokenBucket(perSecond, Math.max(1, perSecond * 2), now));
    }

    private static <K, V> LinkedHashMap<K, V> lru() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_RATE_KEYS;
            }
        };
    }

    static byte[] fingerprint(ParticipantId id) {
        return Arrays.copyOf(Crypto.sha256(id.encoded()), FP_LEN);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static List<InetSocketAddress> findLocalAddresses(DatagramSocket s) {
        int port = s.getLocalPort();
        boolean v6 = s.getLocalAddress() instanceof Inet6Address;
        List<InetSocketAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                            || a.isMulticastAddress() || (a instanceof Inet6Address && !v6)) {
                        continue;
                    }
                    InetSocketAddress c = new InetSocketAddress(InetAddress.getByAddress(a.getAddress()), port);
                    if (!out.contains(c) && out.size() < 8) {
                        out.add(c);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot list interfaces: " + e);
        }
        if (out.isEmpty()) {
            out.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        }
        return out;
    }
}
