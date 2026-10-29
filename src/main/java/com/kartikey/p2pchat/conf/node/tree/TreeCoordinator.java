package com.kartikey.p2pchat.conf.node.tree;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.overlay.NodeInfo;
import com.kartikey.p2pchat.conf.overlay.Placement;
import com.kartikey.p2pchat.conf.overlay.Reattach;
import com.kartikey.p2pchat.conf.overlay.StripeForest;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs on one online admin. Keeps one {@link StripeForest} per stream and turns joins, leaves and
 * failure reports into PLACE (to the subscriber) and ADOPT (to the new parent) messages; the node
 * layer connects the two. Single-threaded: call {@link #handle}/{@link #onDisconnected} from the
 * network callbacks. Work per message is bounded by the forest fan-out and the configured caps.
 */
public final class TreeCoordinator {

    public record Config(int stripes, int stripeKbps, int maxFanout, int sourceUploadKbps, int maxStreams,
                         int maxMembersPerStream, int backups) {
        public Config {
            if (stripes < 1 || stripes > 16 || backups > TreeMessages.MAX_BACKUPS) throw new IllegalArgumentException("config");
        }
        public static Config defaults() { return new Config(4, 50, 4, 8000, 64, 4096, 3); }
    }

    private static final class Member {
        final ParticipantId id;
        final long idx;
        final long hash;
        int maxLayer;
        byte[] candidates;
        Member(ParticipantId id, long idx, int maxLayer, byte[] candidates) {
            this.id = id; this.idx = idx; this.hash = Ids.hash(id); this.maxLayer = maxLayer; this.candidates = candidates;
        }
    }

    private static final class Forest {
        final StripeForest forest;
        final Member source;
        final Map<ParticipantId, Member> byId = new HashMap<>();
        final Map<Long, Member> byIdx = new HashMap<>();
        long nextIdx = 1;
        Forest(StripeForest f, Member source) { this.forest = f; this.source = source; byIdx.put(0L, source); }
    }

    private final PeerNetwork net;
    private final Config cfg;
    private final Map<Long, Forest> forests = new LinkedHashMap<>();
    private long malformed;

    public TreeCoordinator(PeerNetwork net, Config cfg) {
        this.net = net;
        this.cfg = cfg;
    }

    public long malformedCount() { return malformed; }

    /** Read-only diagnostics (do not mutate). */
    public StripeForest forest(long streamId) {
        Forest f = forests.get(streamId);
        return f == null ? null : f.forest;
    }

    public int memberCount(long streamId) {
        Forest f = forests.get(streamId);
        return f == null ? 0 : f.byId.size();
    }

    /** @return true if the message belongs to the coordinator (handled or dropped as malformed). */
    public boolean handle(ParticipantId from, byte[] msg) {
        if (msg.length == 0 || !TreeMessages.forCoordinator(msg[0] & 0xff)) return false;
        try {
            switch (msg[0] & 0xff) {
                case TreeMessages.SUBSCRIBE -> subscribe(from, TreeMessages.subscribe(msg));
                case TreeMessages.UNSUBSCRIBE -> {
                    Forest f = forests.get(TreeMessages.streamId(msg));
                    if (f != null) remove(f, from);
                }
                case TreeMessages.LEAVE -> onDisconnected(from);
                case TreeMessages.PUBLISH -> {
                    Forest f = forests.get(TreeMessages.streamId(msg));
                    if (f != null && f.source.id.equals(from)) f.source.candidates = TreeMessages.publishCandidates(msg);
                }
                case TreeMessages.REPARENT -> reparent(from, TreeMessages.reparent(msg));
                default -> { }
            }
        } catch (RuntimeException e) {
            malformed++;
        }
        return true;
    }

    /** A participant is gone (link closed, left the conference): remove it from every forest. */
    public void onDisconnected(ParticipantId p) {
        for (Forest f : new ArrayList<>(forests.values())) remove(f, p);
    }

    // ---- handlers ------------------------------------------------------------------------------

    private void subscribe(ParticipantId from, TreeMessages.Subscribe m) {
        // Stream ids are derived from the sender's identity: reject claims that do not match.
        if ((StreamIds.of(m.source(), 0) >>> 8) != (m.streamId() >>> 8)) { malformed++; return; }
        if (m.source().equals(from)) return;
        Forest f = forests.get(m.streamId());
        if (f == null) {
            if (forests.size() >= cfg.maxStreams()) return;
            NodeInfo src = new NodeInfo(0, cfg.sourceUploadKbps(), true);
            f = new Forest(new StripeForest(src, cfg.stripes(), cfg.stripeKbps(), cfg.maxFanout()),
                    new Member(m.source(), 0, 2, new byte[0]));
            forests.put(m.streamId(), f);
        }
        Member mem = f.byId.get(from);
        if (mem != null) { // refresh: update layer and re-send the current placement
            mem.maxLayer = m.maxLayer();
            for (int s = 0; s < cfg.stripes(); s++) sendPlacement(m.streamId(), f, mem, s, true);
            return;
        }
        if (f.byId.size() >= cfg.maxMembersPerStream()) return;
        mem = new Member(from, f.nextIdx++, m.maxLayer(), m.candidates());
        f.byId.put(from, mem);
        f.byIdx.put(mem.idx, mem);
        Placement p = f.forest.join(new NodeInfo(mem.idx, Math.max(0, Math.min(m.uploadKbps(), 1_000_000)), m.canRelay()));
        for (int s = 0; s < cfg.stripes(); s++) {
            if (p.parents()[s] >= 0) sendPlacement(m.streamId(), f, mem, s, true);
        }
    }

    private void reparent(ParticipantId from, TreeMessages.Reparent m) {
        Forest f = forests.get(m.streamId());
        if (f == null || m.stripe() >= cfg.stripes()) return;
        Member reporter = f.byId.get(from);
        if (reporter == null) return;
        boolean replaced = false;
        Member failed = f.byId.get(m.failed());
        if (m.disconnected() && failed != null) {
            for (Reattach r : remove(f, failed.id)) {
                if (r.id() == reporter.idx && r.stripe() == m.stripe()) replaced = true;
            }
        }
        if (!replaced) sendPlacement(m.streamId(), f, reporter, m.stripe(), true);
    }

    private List<Reattach> remove(Forest f, ParticipantId p) {
        Member m = f.byId.get(p);
        if (m == null) return List.of();
        long streamId = streamOf(f);
        List<Reattach> events = f.forest.leave(m.idx);
        f.byId.remove(p);
        f.byIdx.remove(m.idx);
        for (Reattach r : events) {
            Member child = f.byIdx.get(r.id());
            if (child != null && r.newParent() >= 0) sendPlacement(streamId, f, child, r.stripe(), true);
        }
        return events;
    }

    private long streamOf(Forest f) {
        for (Map.Entry<Long, Forest> e : forests.entrySet()) if (e.getValue() == f) return e.getKey();
        throw new IllegalStateException();
    }

    // ---- message building -----------------------------------------------------------------------

    private void sendPlacement(long streamId, Forest f, Member child, int stripe, boolean adoptToo) {
        long pidx = f.forest.parent(child.idx, stripe);
        Member parent = f.byIdx.get(pidx);
        if (pidx < 0 || parent == null) return;
        // ancestors from the source down to the parent, as hashes
        List<Long> chain = new ArrayList<>();
        long cur = pidx;
        for (int guard = 0; cur >= 0 && guard < TreeMessages.MAX_PATH; guard++) {
            Member a = f.byIdx.get(cur);
            if (a == null) break;
            chain.add(a.hash);
            cur = f.forest.parent(cur, stripe);
        }
        long[] path = new long[chain.size()];
        for (int k = 0; k < path.length; k++) path[k] = chain.get(chain.size() - 1 - k);
        long gpidx = f.forest.parent(pidx, stripe);
        Member gp = gpidx >= 0 ? f.byIdx.get(gpidx) : null;

        List<ParticipantId> backups = new ArrayList<>();
        if (gp != null) {
            List<Long> sibs = new ArrayList<>(f.forest.children(gp.idx, stripe));
            sibs.remove(Long.valueOf(pidx));
            sibs.remove(Long.valueOf(child.idx));
            sibs.sort(Comparator.comparingInt((Long x) -> f.forest.children(x, stripe).size()).thenComparingLong(x -> x));
            for (Long x : sibs) {
                Member b = f.byIdx.get(x);
                if (b != null && backups.size() < cfg.backups() - 1) backups.add(b.id);
            }
            if (backups.size() < cfg.backups()) backups.add(gp.id);
        }
        net.sendControl(child.id, TreeMessages.place(new TreeMessages.Place(streamId, stripe, parent.id,
                parent.candidates, gp == null ? null : gp.id, backups, path)));
        if (adoptToo) {
            net.sendControl(parent.id, TreeMessages.adopt(new TreeMessages.Adopt(streamId, stripe, child.id,
                    child.maxLayer, child.candidates)));
        }
    }

    static final class Ids {
        private Ids() {}
        private static final Map<ParticipantId, Long> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        static long hash(ParticipantId id) {
            Long v = CACHE.get(id);
            if (v == null) {
                byte[] h = Crypto.sha256(id.encoded());
                long x = 0;
                for (int i = 0; i < 8; i++) x = (x << 8) | (h[i] & 0xff);
                v = x;
                if (CACHE.size() > 16384) CACHE.clear(); // bounded: ids come from remote peers
                CACHE.put(id, v);
            }
            return v;
        }
    }
}
