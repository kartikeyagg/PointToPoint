package com.kartikey.p2pchat.conf.node.tree;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-node relay table: (streamId, stripe) -> parent + children (with the layer each wants).
 * Media is routed by the clear {@link MediaHeader} only (never decrypted here). Packets are accepted
 * only from the current parent of that (stream, stripe), or locally if we are the source.
 *
 * <p>Repair: when a parent disconnects, leaves, or is silent for {@code silenceMillis} while the
 * stream is otherwise active, the node tries, in order, the backups the coordinator gave it, the
 * coordinator, then the grandparent. Cycles are prevented by an ancestor path (8-byte hashes): a
 * node only accepts a REPAIR from someone who is not among its own ancestors.
 *
 * <p>Single-threaded: all methods run on the network callback thread / the tick driver.
 */
public final class Forwarder implements PeerNetwork.Listener {

    public record Config(int maxStreams, int maxStripes, int maxChildren, int repairChildren,
                         long silenceMillis, long attemptTimeoutMillis, long retryMillis) {
        public static Config defaults() { return new Config(64, 16, 256, 8, 1500, 400, 1000); }
    }

    /** Receives media this node subscribed to (already filtered by our own max layer). */
    public interface Sink {
        void onPacket(MediaHeader header, byte[] packet);
    }

    /** Receives CROWD_UP media packets (see CrowdSession). */
    public interface CrowdHandler {
        void onCrowdUp(ParticipantId from, byte[] packet);
    }

    public record StreamStats(long received, long forwarded, long dropped, long delivered) {}

    /** A stream counts as active while any stripe delivered something within this window. */
    private static final long ACTIVE_WINDOW_MILLIS = 5000;

    private static final int SEEN_WINDOW = 1024, RING_MILLIS = 1000, STREAM_RING_BYTES = 2 << 20;
    private static final int NACK_MAX_SEQS = 32, MAX_MISSING = 64, RETX_BURST = 64;
    private static final long NACK_GRACE = 30, NACK_MAX_AGE = 400, RETX_PER_SECOND = 64;
    public static final int NACK_MAGIC = 0xC1;
    /** One NACK plus two retries, all within NACK_MAX_AGE (a retry is spaced about one RTT). */
    private static final int MAX_TRIES = 3;

    private record Buf(byte[] packet, long at) {}

    private static final class Stripe {
        ParticipantId parent, grandparent;
        long[] path; // ancestors source..parent; null until known
        List<ParticipantId> backups = List.of();
        long lastRx;
        final Map<ParticipantId, Integer> children = new LinkedHashMap<>();
        int sentLayer = -1;
        long hiSeq = -1;
        final java.util.BitSet seenBits = new java.util.BitSet(SEEN_WINDOW); // seq mod SEEN_WINDOW, within SEEN_WINDOW of hiSeq
        final LinkedHashMap<Long, Buf> ring = new LinkedHashMap<>(); // recent packets, for retransmission
        final LinkedHashMap<Long, long[]> missing = new LinkedHashMap<>(); // seq -> {detectedAt, nextNackAt, tries}
        final Map<ParticipantId, Double> childTokens = new HashMap<>();
        final Map<ParticipantId, Long> childTokenAt = new HashMap<>();
        // repair
        boolean repairing;
        ParticipantId lost, attempting;
        List<ParticipantId> queue = List.of();
        int qi;
        long deadline, repairStart, retryAt;
        boolean attemptSent, wasDisconnect;
        long detectedAt = -1; // for recovery measurement, until the first packet arrives
    }

    private static final class Stream {
        final long id;
        boolean source, subscribed;
        int ownMax;
        Sink sink;
        ParticipantId sourceId;
        byte[] subscribeMsg;
        ParticipantId coordinator; // null = the forwarder-wide default
        long lastSubscribeSent = -1;
        long lastRx, activeSince;
        long received, forwarded, dropped, delivered;
        long ringBytes;
        final Map<Integer, Stripe> stripes = new HashMap<>();
        Stream(long id) { this.id = id; }
    }

    private final PeerNetwork net;
    private final LongSupplier clock;
    private final Config cfg;
    private final long selfHash;
    private final Map<Long, Stream> streams = new LinkedHashMap<>();
    private ParticipantId coordinator;
    private byte[] myCandidates = new byte[0];
    private CrowdHandler crowd;
    private long unknownDropped, malformed, spoofDropped;
    private final Deque<Long> recoveries = new ArrayDeque<>();
    private int repairsStarted;
    private long mediaBytesSent, nackBytesSent, retransBytesSent, nacksSent, retransSent, nacksIgnored;

    public Forwarder(PeerNetwork net, LongSupplier clock, Config cfg) {
        this.net = net;
        this.clock = clock;
        this.cfg = cfg;
        this.selfHash = TreeCoordinator.Ids.hash(net.self());
    }

    // ---- configuration / local API ------------------------------------------------------------

    public void setCoordinator(ParticipantId coordinator) { this.coordinator = coordinator; }
    public void setCandidates(byte[] candidates) { this.myCandidates = candidates.clone(); }
    public void setCrowdHandler(CrowdHandler h) { this.crowd = h; }

    /** We are the source of {@code streamId}: we inject packets with {@link #send}. */
    public void publish(long streamId) { publish(streamId, null); }

    /** As {@link #publish(long)}; {@code coordinator} runs this stream's tree (often ourselves). */
    public void publish(long streamId, ParticipantId coordinator) {
        Stream s = stream(streamId, true);
        if (s == null) return;
        s.source = true;
        s.coordinator = coordinator;
        ParticipantId c = coordOf(s);
        if (c != null && !c.equals(net.self())) net.sendControl(c, TreeMessages.publish(streamId, myCandidates));
    }

    /** Subscribe to a stream; {@code uploadKbps}/{@code canRelay} say how much we can forward for others. */
    public void subscribe(long streamId, ParticipantId source, int maxLayer, int uploadKbps, boolean canRelay, Sink sink) {
        subscribe(streamId, source, null, maxLayer, uploadKbps, canRelay, sink);
    }

    /** Subscribe with a per-stream coordinator (null = the default from {@link #setCoordinator}). */
    public void subscribe(long streamId, ParticipantId source, ParticipantId coordinator, int maxLayer, int uploadKbps,
                          boolean canRelay, Sink sink) {
        Stream s = stream(streamId, true);
        if (s == null) return;
        s.coordinator = coordinator;
        s.subscribed = true;
        s.ownMax = maxLayer;
        s.sink = sink;
        s.sourceId = source;
        s.subscribeMsg = TreeMessages.subscribe(new TreeMessages.Subscribe(streamId, source, maxLayer, uploadKbps,
                canRelay, myCandidates));
        sendSubscribe(s);
    }

    /** Change the layer we want (gallery tile 0, active speaker 2); propagates up the tree. */
    public void setMaxLayer(long streamId, int maxLayer) {
        Stream s = streams.get(streamId);
        if (s == null || !s.subscribed) return;
        s.ownMax = maxLayer;
        TreeMessages.Subscribe old = TreeMessages.subscribe(s.subscribeMsg);
        s.subscribeMsg = TreeMessages.subscribe(new TreeMessages.Subscribe(streamId, old.source(), maxLayer,
                old.uploadKbps(), old.canRelay(), old.candidates()));
        sendSubscribe(s);
        // tick() pushes the new effective layer to each parent
    }

    public void unsubscribe(long streamId) {
        Stream s = streams.remove(streamId);
        if (s == null) return;
        goodbye(s);
        ParticipantId c = coordOf(s);
        if (c != null) net.sendControl(c, TreeMessages.unsubscribe(streamId));
    }

    /** Leave everything gracefully: parents drop us, children repair immediately. */
    public void leave() {
        java.util.Set<ParticipantId> coords = new java.util.LinkedHashSet<>();
        for (Stream s : streams.values()) {
            goodbye(s);
            if (coordOf(s) != null) coords.add(coordOf(s));
        }
        streams.clear();
        for (ParticipantId c : coords) net.sendControl(c, TreeMessages.leave());
    }

    private void goodbye(Stream s) {
        for (Map.Entry<Integer, Stripe> e : s.stripes.entrySet()) {
            Stripe st = e.getValue();
            if (st.parent != null) net.sendControl(st.parent, TreeMessages.detach(s.id, e.getKey()));
            for (ParticipantId c : st.children.keySet()) net.sendControl(c, TreeMessages.parentLeaving(s.id, e.getKey()));
        }
    }

    /** Source side: inject a packet (header decides the stripe) and forward it to that stripe's children. */
    public boolean send(byte[] packet) {
        MediaHeader h;
        try { h = MediaHeader.decode(packet); } catch (RuntimeException e) { return false; }
        Stream s = streams.get(h.streamId());
        if (s == null || !s.source) return false;
        Stripe st = s.stripes.get(h.stripe());
        if (st == null) st = s.stripes.computeIfAbsent(h.stripe(), x -> new Stripe());
        if (h.kind() != MediaHeader.Kind.MANIFEST) remember(s, st, h.seq(), packet, clock.getAsLong());
        forwardToChildren(s, st, h, packet);
        return true;
    }

    /** Source side: manifests go on every stripe (copy per stripe, stripe byte patched). */
    public void sendManifest(byte[] packet, int stripes) {
        for (int k = 0; k < stripes; k++) {
            byte[] c = packet.clone();
            c[22] = (byte) k;
            send(c);
        }
    }

    // ---- queries ---------------------------------------------------------------------------------

    public ParticipantId parentOf(long streamId, int stripe) {
        Stripe st = stripeOf(streamId, stripe);
        return st == null ? null : st.parent;
    }

    public List<ParticipantId> childrenOf(long streamId, int stripe) {
        Stripe st = stripeOf(streamId, stripe);
        return st == null ? List.of() : new ArrayList<>(st.children.keySet());
    }

    public boolean isSource(long streamId) {
        Stream s = streams.get(streamId);
        return s != null && s.source;
    }

    public ParticipantId sourceOf(long streamId) {
        Stream s = streams.get(streamId);
        return s == null ? null : s.sourceId;
    }

    public StreamStats stats(long streamId) {
        Stream s = streams.get(streamId);
        return s == null ? new StreamStats(0, 0, 0, 0) : new StreamStats(s.received, s.forwarded, s.dropped, s.delivered);
    }

    /** NACK traffic and retransmissions relative to normally forwarded media bytes. */
    public record NackStats(long nacksSent, long nackBytes, long retransmits, long retransBytes, long mediaBytes, long ignored) {}
    public NackStats nackStats() { return new NackStats(nacksSent, nackBytesSent, retransSent, retransBytesSent, mediaBytesSent, nacksIgnored); }

    public long unknownStreamDrops() { return unknownDropped; }
    public long malformedCount() { return malformed; }
    public int repairsStarted() { return repairsStarted; }

    /** Detection-to-first-packet repair times (ms) of the last repairs, oldest first. */
    public List<Long> recoveryTimesMillis() { return new ArrayList<>(recoveries); }

    /** True when every stripe of the stream has a live parent (or we are the source). */
    public boolean fullyAttached(long streamId, int stripes) {
        Stream s = streams.get(streamId);
        if (s == null) return false;
        if (s.source) return true;
        for (int k = 0; k < stripes; k++) {
            Stripe st = s.stripes.get(k);
            if (st == null || st.parent == null) return false;
        }
        return true;
    }

    // ---- network callbacks ------------------------------------------------------------------------

    @Override
    public void onConnected(ParticipantId peer) {
        long now = clock.getAsLong();
        for (Stream s : streams.values()) {
            for (Stripe st : s.stripes.values()) {
                if (st.repairing && !st.attemptSent && peer.equals(st.attempting)) sendAttempt(s, stripeIndex(s, st), st, now);
            }
        }
    }

    @Override
    public void onDisconnected(ParticipantId peer) {
        long now = clock.getAsLong();
        for (Stream s : streams.values()) {
            for (Map.Entry<Integer, Stripe> e : new ArrayList<>(s.stripes.entrySet())) {
                Stripe st = e.getValue();
                st.children.remove(peer);
                if (peer.equals(st.parent)) {
                    startRepair(s, e.getKey(), st, now, true);
                } else if (st.repairing && peer.equals(st.attempting)) {
                    st.qi++;
                    advance(s, e.getKey(), st, now);
                }
            }
        }
    }

    @Override
    public void onMedia(ParticipantId from, byte[] packet) {
        if (packet.length > 0 && (packet[0] & 0xff) == TreeMessages.CROWD_UP_MAGIC) {
            if (crowd != null) crowd.onCrowdUp(from, packet);
            return;
        }
        if (packet.length > 11 && (packet[0] & 0xff) == NACK_MAGIC) {
            onNack(from, packet);
            return;
        }
        MediaHeader h;
        try { h = MediaHeader.decode(packet); } catch (RuntimeException e) { malformed++; return; }
        Stream s = streams.get(h.streamId());
        if (s == null) { unknownDropped++; return; }
        Stripe st = s.stripes.get(h.stripe());
        if (st == null || st.parent == null || !from.equals(st.parent)) {
            s.dropped++;
            spoofDropped++;
            return;
        }
        boolean media = h.kind() != MediaHeader.Kind.MANIFEST;
        long prevHi = st.hiSeq;
        if (media && duplicate(st, h.seq())) { s.dropped++; return; }
        long now = clock.getAsLong();
        if (media) track(s, st, prevHi, h.seq(), packet, now);
        st.lastRx = now;
        if (s.lastRx == 0 || now - s.lastRx >= ACTIVE_WINDOW_MILLIS) s.activeSince = now; // stream (re)started
        s.lastRx = now;
        if (st.detectedAt >= 0) {
            recoveries.addLast(now - st.detectedAt);
            if (recoveries.size() > 64) recoveries.removeFirst();
            st.detectedAt = -1;
        }
        s.received++;
        if (s.subscribed && s.sink != null && (h.kind() == MediaHeader.Kind.MANIFEST || h.layer() <= s.ownMax)) {
            s.delivered++;
            s.sink.onPacket(h, packet);
        }
        forwardToChildren(s, st, h, packet);
    }

    @Override
    public void onControl(ParticipantId from, byte[] msg) {
        if (msg.length == 0) return;
        try {
            switch (msg[0] & 0xff) {
                case TreeMessages.PLACE -> onPlace(from, TreeMessages.place(msg));
                case TreeMessages.ADOPT -> onAdopt(from, TreeMessages.adopt(msg));
                case TreeMessages.REPAIR -> onRepair(from, msg);
                case TreeMessages.ACCEPT -> onAccept(from, TreeMessages.accept(msg));
                case TreeMessages.REJECT -> onReject(from, msg);
                case TreeMessages.LAYER -> onLayer(from, msg);
                case TreeMessages.DETACH -> {
                    Stripe st = stripeOf(TreeMessages.streamId(msg), TreeMessages.stripe(msg));
                    if (st != null) st.children.remove(from);
                }
                case TreeMessages.PARENT_LEAVING -> {
                    long sid = TreeMessages.streamId(msg);
                    int k = TreeMessages.stripe(msg);
                    Stream s = streams.get(sid);
                    Stripe st = stripeOf(sid, k);
                    if (st != null && from.equals(st.parent)) startRepair(s, k, st, clock.getAsLong(), true);
                }
                case TreeMessages.PATH -> onPath(from, msg);
                default -> { }
            }
        } catch (RuntimeException e) {
            malformed++;
        }
    }

    // ---- tick: silence detection, repair timeouts, layer propagation --------------------------------

    public void tick(long now) {
        for (Stream s : new ArrayList<>(streams.values())) {
            if (s.subscribed && s.lastSubscribeSent >= 0 && now - s.lastSubscribeSent > 2000 && !allPlaced(s)) sendSubscribe(s);
            boolean active = !s.source && now - s.lastRx < ACTIVE_WINDOW_MILLIS && s.lastRx > 0;
            for (Map.Entry<Integer, Stripe> e : new ArrayList<>(s.stripes.entrySet())) {
                int k = e.getKey();
                Stripe st = e.getValue();
                if (st.repairing) {
                    if (st.attempting == null && now >= st.retryAt) { st.qi = 0; advance(s, k, st, now); }
                    else if (st.attempting != null && now >= st.deadline) { st.qi++; advance(s, k, st, now); }
                } else if (st.parent != null) {
                    if (active && now - Math.max(st.lastRx, s.activeSince) > cfg.silenceMillis()) startRepair(s, k, st, now, false);
                    if (!st.missing.isEmpty()) sendNacks(s, k, st, now);
                    int eff = effectiveLayer(s, st);
                    if (st.sentLayer != eff && net.sendControl(st.parent, TreeMessages.layer(s.id, k, eff))) st.sentLayer = eff;
                }
            }
        }
    }

    // ---- control handlers ------------------------------------------------------------------------

    private ParticipantId coordOf(Stream s) { return s.coordinator != null ? s.coordinator : coordinator; }

    private boolean fromCoordinator(ParticipantId from, Stream s) {
        ParticipantId c = s != null ? coordOf(s) : coordinator;
        return c != null && c.equals(from);
    }

    private void onPlace(ParticipantId from, TreeMessages.Place m) {
        Stream s = streams.get(m.streamId());
        if (s == null || !s.subscribed || !fromCoordinator(from, s) || m.stripe() >= cfg.maxStripes()) return;
        if (m.parent().equals(net.self())) return;
        net.connect(m.parent(), List.of(), null);
        Stripe st = s.stripes.computeIfAbsent(m.stripe(), x -> new Stripe());
        setParent(s, m.stripe(), st, m.parent(), m.grandparent(), m.path(), clock.getAsLong());
        st.backups = List.copyOf(m.backups());
    }

    private void onAdopt(ParticipantId from, TreeMessages.Adopt m) {
        if (m.stripe() >= cfg.maxStripes() || m.child().equals(net.self())) return;
        Stream s = streams.get(m.streamId());
        // our own coordinator may place children in a stream before we publish it: keep them
        boolean local = from.equals(net.self());
        if (!local && !fromCoordinator(from, s)) return;
        if (s == null) s = stream(m.streamId(), true);
        if (s == null) return;
        Stripe st = s.stripes.computeIfAbsent(m.stripe(), x -> new Stripe());
        if (!st.children.containsKey(m.child()) && st.children.size() >= cfg.maxChildren()) return;
        st.children.put(m.child(), m.maxLayer());
        net.connect(m.child(), List.of(), null);
    }

    private void onRepair(ParticipantId from, byte[] msg) {
        long sid = TreeMessages.streamId(msg);
        int k = TreeMessages.stripe(msg);
        int layer = TreeMessages.repairLayer(msg);
        Stream s = streams.get(sid);
        Stripe st = s == null ? null : s.stripes.get(k);
        boolean ok = st != null && !st.repairing && (s.source || (st.parent != null && st.path != null && st.path.length < TreeMessages.MAX_PATH - 1))
                && !from.equals(net.self());
        if (ok) {
            long h = TreeCoordinator.Ids.hash(from);
            long[] p = s.source ? new long[0] : st.path;
            for (long a : p) if (a == h) ok = false; // requester is our ancestor: would form a cycle
            if (!s.source && st.parent != null && st.parent.equals(from)) ok = false;
        }
        if (ok && !st.children.containsKey(from) && st.children.size() >= cfg.repairChildren()) ok = false;
        if (!ok) {
            net.sendControl(from, TreeMessages.reject(sid, k));
            return;
        }
        st.children.put(from, layer);
        long[] mine = Arrays.copyOf(s.source ? new long[0] : st.path, (s.source ? 0 : st.path.length) + 1);
        mine[mine.length - 1] = selfHash;
        net.sendControl(from, TreeMessages.accept(new TreeMessages.Accept(sid, k, s.source ? null : st.parent, mine)));
    }

    private void onAccept(ParticipantId from, TreeMessages.Accept m) {
        Stream s = streams.get(m.streamId());
        Stripe st = s == null ? null : s.stripes.get(m.stripe());
        if (st != null && from.equals(st.parent)) return; // duplicate accept from our current parent
        if (st == null || !st.repairing || !from.equals(st.attempting)) {
            net.sendControl(from, TreeMessages.detach(m.streamId(), m.stripe())); // stale: release the slot
            return;
        }
        setParent(s, m.stripe(), st, from, m.grandparent(), m.path(), clock.getAsLong());
    }

    private void onReject(ParticipantId from, byte[] msg) {
        Stream s = streams.get(TreeMessages.streamId(msg));
        int k = TreeMessages.stripe(msg);
        Stripe st = s == null ? null : s.stripes.get(k);
        if (st != null && st.repairing && from.equals(st.attempting)) {
            st.qi++;
            advance(s, k, st, clock.getAsLong());
        }
    }

    private void onLayer(ParticipantId from, byte[] msg) {
        Stream s = streams.get(TreeMessages.streamId(msg));
        int k = TreeMessages.stripe(msg);
        Stripe st = s == null ? null : s.stripes.get(k);
        if (st != null && st.children.containsKey(from)) st.children.put(from, TreeMessages.layerOf(msg));
    }

    private void onPath(ParticipantId from, byte[] msg) {
        Stream s = streams.get(TreeMessages.streamId(msg));
        int k = TreeMessages.stripe(msg);
        Stripe st = s == null ? null : s.stripes.get(k);
        if (st == null || !from.equals(st.parent)) return;
        long[] p = TreeMessages.pathOf(msg);
        for (long a : p) if (a == selfHash) { startRepair(s, k, st, clock.getAsLong(), true); return; }
        applyPath(s, k, st, p);
    }

    // ---- repair machinery ----------------------------------------------------------------------------

    private void setParent(Stream s, int k, Stripe st, ParticipantId parent, ParticipantId gp, long[] path, long now) {
        if (st.parent != null && !st.parent.equals(parent)) net.sendControl(st.parent, TreeMessages.detach(s.id, k));
        boolean changed = !parent.equals(st.parent);
        st.parent = parent;
        st.grandparent = gp;
        st.repairing = false;
        st.attempting = null;
        st.lastRx = now;
        if (changed) st.sentLayer = -1;
        applyPath(s, k, st, path);
    }

    private void applyPath(Stream s, int k, Stripe st, long[] parentPath) {
        boolean differs = st.path == null || !Arrays.equals(st.path, parentPath);
        st.path = parentPath;
        if (differs) {
            long[] mine = Arrays.copyOf(parentPath, Math.min(parentPath.length + 1, TreeMessages.MAX_PATH));
            mine[mine.length - 1] = selfHash;
            byte[] m = TreeMessages.path(s.id, k, mine);
            for (ParticipantId c : st.children.keySet()) net.sendControl(c, m);
        }
    }

    private void startRepair(Stream s, int k, Stripe st, long now, boolean disconnected) {
        if (st.repairing) return;
        repairsStarted++;
        ParticipantId lost = st.parent;
        st.lost = lost;
        st.parent = null;
        st.path = null;
        st.repairing = true;
        st.repairStart = now;
        st.detectedAt = now;
        st.wasDisconnect = disconnected;
        if (lost != null && !disconnected) net.sendControl(lost, TreeMessages.detach(s.id, k));
        List<ParticipantId> q = new ArrayList<>();
        for (ParticipantId b : st.backups) if (!b.equals(lost) && !q.contains(b)) q.add(b);
        ParticipantId coord = coordOf(s);
        if (coord != null && !coord.equals(net.self())) q.add(coord);
        ParticipantId gp = st.grandparent;
        if (gp != null && !gp.equals(lost) && !q.contains(gp)) q.add(gp);
        st.queue = q;
        st.qi = 0;
        advance(s, k, st, now);
    }

    private void advance(Stream s, int k, Stripe st, long now) {
        if (st.qi >= st.queue.size()) {
            st.attempting = null;
            st.retryAt = now + cfg.retryMillis();
            return;
        }
        st.attempting = st.queue.get(st.qi);
        st.attemptSent = false;
        st.deadline = now + cfg.attemptTimeoutMillis();
        sendAttempt(s, k, st, now);
    }

    private void sendAttempt(Stream s, int k, Stripe st, long now) {
        ParticipantId c = st.attempting;
        if (c == null) return;
        boolean ok;
        if (c.equals(coordOf(s))) {
            ok = net.sendControl(c, TreeMessages.reparent(new TreeMessages.Reparent(s.id, k,
                    st.lost == null ? c : st.lost, st.wasDisconnect)));
        } else {
            st.attemptSent = true; // guards against the nested onConnected triggered by connect()
            net.connect(c, List.of(), null);
            ok = net.sendControl(c, TreeMessages.repair(s.id, k, effectiveLayer(s, st)));
        }
        st.attemptSent = ok;
    }

    private int stripeIndex(Stream s, Stripe st) {
        for (Map.Entry<Integer, Stripe> e : s.stripes.entrySet()) if (e.getValue() == st) return e.getKey();
        return -1;
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private int effectiveLayer(Stream s, Stripe st) {
        int m = s.subscribed ? s.ownMax : 0;
        for (int l : st.children.values()) m = Math.max(m, l);
        return m;
    }

    private boolean allPlaced(Stream s) {
        if (s.source || s.stripes.isEmpty()) return s.source;
        for (Stripe st : s.stripes.values()) if (st.parent == null && !st.repairing) return false;
        return true;
    }

    private void sendSubscribe(Stream s) {
        s.lastSubscribeSent = clock.getAsLong();
        ParticipantId c = coordOf(s);
        if (c != null && s.subscribeMsg != null) net.sendControl(c, s.subscribeMsg);
    }

    private Stream stream(long id, boolean create) {
        Stream s = streams.get(id);
        if (s == null) {
            if (streams.size() >= cfg.maxStreams()) return null;
            s = new Stream(id);
            streams.put(id, s);
        }
        return s;
    }

    private Stripe stripeOf(long streamId, int stripe) {
        Stream s = streams.get(streamId);
        return s == null ? null : s.stripes.get(stripe);
    }

    private boolean duplicate(Stripe st, long seq) {
        if (seq < 0) return true;
        if (st.hiSeq >= 0 && seq <= st.hiSeq && st.hiSeq - seq >= SEEN_WINDOW) return true;
        if (st.hiSeq < 0 || seq - st.hiSeq >= SEEN_WINDOW) {
            st.seenBits.clear();
        } else if (seq > st.hiSeq) {
            for (long q = st.hiSeq + 1; q <= seq; q++) st.seenBits.clear((int) (q % SEEN_WINDOW));
        }
        int bit = (int) (seq % SEEN_WINDOW);
        if (seq <= st.hiSeq && st.seenBits.get(bit)) return true;
        st.seenBits.set(bit);
        if (seq > st.hiSeq) st.hiSeq = seq;
        return false;
    }

    /** Called for a fresh (non-duplicate) media packet: record gaps, forget recovered seqs, buffer it. */
    private void track(Stream s, Stripe st, long prevHi, long seq, byte[] packet, long now) {
        st.missing.remove(seq);
        if (prevHi >= 0 && seq > prevHi) {
            long step = s.stripes.isEmpty() ? 1 : Collections.max(s.stripes.keySet()) + 1;
            if (seq - prevHi <= step * (long) MAX_MISSING) {
                for (long q = prevHi + step; q < seq; q += step) {
                    if (st.missing.size() >= MAX_MISSING) break;
                    st.missing.putIfAbsent(q, new long[] {now, now + NACK_GRACE, 0});
                }
            }
        }
        remember(s, st, seq, packet, now);
    }

    private void remember(Stream s, Stripe st, long seq, byte[] packet, long now) {
        Buf old = st.ring.put(seq, new Buf(packet, now));
        if (old != null) s.ringBytes -= old.packet().length;
        s.ringBytes += packet.length;
        var it = st.ring.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Buf> e = it.next();
            if (now - e.getValue().at() > RING_MILLIS || s.ringBytes > STREAM_RING_BYTES) {
                s.ringBytes -= e.getValue().packet().length;
                it.remove();
            } else break;
        }
    }

    private void sendNacks(Stream s, int k, Stripe st, long now) {
        List<Long> due = new ArrayList<>();
        var it = st.missing.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, long[]> e = it.next();
            long[] m = e.getValue();
            if (now - m[0] > NACK_MAX_AGE || m[2] >= MAX_TRIES) { it.remove(); continue; } // would miss the playout
            if (now >= m[1] && due.size() < NACK_MAX_SEQS) {
                due.add(e.getKey());
                m[2]++;
                m[1] = now + retryAfter(st.parent);
            }
        }
        if (due.isEmpty()) return;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(11 + 8 * due.size());
        b.put((byte) NACK_MAGIC).putLong(s.id).put((byte) k).put((byte) due.size());
        for (long q : due) b.putLong(q);
        if (net.sendMedia(st.parent, b.array())) { nacksSent++; nackBytesSent += b.capacity(); }
    }

    private long retryAfter(ParticipantId parent) {
        PeerNetwork.LinkStats ls = net.stats(parent);
        return Math.max(40, Math.min(150, ls == null ? 100 : ls.rttMillis() + 20));
    }

    private void onNack(ParticipantId from, byte[] p) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(p, 1, p.length - 1);
            long sid = b.getLong();
            int k = b.get() & 0xff;
            int n = b.get() & 0xff;
            Stream s = streams.get(sid);
            Stripe st = s == null ? null : s.stripes.get(k);
            Integer childLayer = st == null ? null : st.children.get(from);
            if (childLayer == null || n > NACK_MAX_SEQS || b.remaining() < 8 * n) { nacksIgnored++; return; }
            long now = clock.getAsLong();
            // per-child token bucket: a hostile child cannot make us resend more than RETX_PER_SECOND
            Long last = st.childTokenAt.get(from);
            double tok = st.childTokens.getOrDefault(from, (double) RETX_BURST);
            if (last != null) tok = Math.min(RETX_BURST, tok + (now - last) * RETX_PER_SECOND / 1000.0);
            for (int i = 0; i < n; i++) {
                long q = b.getLong();
                Buf buf = st.ring.get(q);
                if (buf == null || tok < 1) continue;
                MediaHeader h = MediaHeader.decode(buf.packet());
                if (h.layer() > childLayer) continue;
                if (net.sendMedia(from, buf.packet())) {
                    tok -= 1;
                    retransSent++;
                    retransBytesSent += buf.packet().length;
                }
            }
            st.childTokens.put(from, tok);
            st.childTokenAt.put(from, now);
        } catch (RuntimeException e) {
            malformed++;
        }
    }

    private void forwardToChildren(Stream s, Stripe st, MediaHeader h, byte[] packet) {
        boolean manifest = h.kind() == MediaHeader.Kind.MANIFEST;
        for (Map.Entry<ParticipantId, Integer> c : st.children.entrySet()) {
            if (manifest || c.getValue() >= h.layer()) {
                if (net.sendMedia(c.getKey(), packet)) { s.forwarded++; mediaBytesSent += packet.length; }
            }
        }
    }
}
