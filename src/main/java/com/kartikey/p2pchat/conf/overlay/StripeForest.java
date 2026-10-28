package com.kartikey.p2pchat.conf.overlay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * SplitStream-style forest: one push tree per stripe, every node forwards in at most one stripe
 * and is a leaf in the rest, so upload is spread and losing a relay costs only 1/k of the stream.
 *
 * <p>Each stripe keeps a lazily-cleaned min-heap of (depth, id) for nodes with a free slot, so a
 * join is O(k log N) and always picks the shallowest parent, which keeps trees about log_fanout N
 * deep (live latency). Stale heap entries (node left, moved, or full) are discarded when seen.
 * Not thread-safe; the caller serialises membership changes.
 */
public final class StripeForest {

    private record Slot(int depth, long id) implements Comparable<Slot> {
        @Override public int compareTo(Slot o) {
            int c = Integer.compare(depth, o.depth);
            return c != 0 ? c : Long.compare(id, o.id);
        }
    }

    private static final class Node {
        final NodeInfo info;
        final boolean source;
        int interior = -1;
        int fanout;
        final Node[] parent;
        final int[] depth;
        final List<Node>[] children;

        @SuppressWarnings("unchecked")
        Node(NodeInfo info, boolean source, int stripes) {
            this.info = info;
            this.source = source;
            this.parent = new Node[stripes];
            this.depth = new int[stripes];
            Arrays.fill(depth, -1);
            this.children = new List[stripes];
        }

        int fanoutIn(int s) { return source || s == interior ? fanout : 0; }

        boolean hasFree(int s) { return children[s] != null && children[s].size() < fanoutIn(s); }
    }

    private final int stripes;
    private final int stripeKbps;
    private final int maxFanout;
    private final Node source;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final List<PriorityQueue<Slot>> heaps = new ArrayList<>();
    private final long[] freeSlots; // approximate; only used to balance interior-stripe choice
    private final List<PriorityQueue<Slot>> leafHeaps = new ArrayList<>();
    private final List<Set<Node>> unplaced = new ArrayList<>();

    public StripeForest(NodeInfo source, int stripes, int stripeKbps, int maxFanout) {
        if (stripes < 1 || stripeKbps < 1 || maxFanout < 1) throw new IllegalArgumentException("bad forest parameters");
        this.stripes = stripes;
        this.stripeKbps = stripeKbps;
        this.maxFanout = maxFanout;
        this.freeSlots = new long[stripes];
        this.source = new Node(source, true, stripes);
        // The source serves the stripe roots out of its own upload, split evenly across stripes.
        this.source.fanout = Math.max(1, source.uploadKbps() / stripeKbps / stripes);
        nodes.put(source.id(), this.source);
        for (int s = 0; s < stripes; s++) {
            heaps.add(new PriorityQueue<>());
            leafHeaps.add(new PriorityQueue<>());
            unplaced.add(new LinkedHashSet<>());
            this.source.children[s] = new ArrayList<>();
            this.source.depth[s] = 0;
            freeSlots[s] = this.source.fanout;
            heaps.get(s).add(new Slot(0, source.id()));
        }
    }

    // ---- membership -------------------------------------------------------------------------

    public Placement join(NodeInfo info) {
        if (nodes.containsKey(info.id())) throw new IllegalArgumentException("duplicate id " + info.id());
        Node n = new Node(info, false, stripes);
        int fan = info.canRelay() ? Math.min(maxFanout, info.uploadKbps() / stripeKbps) : 0;
        if (fan > 0) {
            int best = 0;
            for (int s = 1; s < stripes; s++) if (freeSlots[s] < freeSlots[best]) best = s;
            n.interior = best;
            n.fanout = fan;
            n.children[best] = new ArrayList<>();
            freeSlots[best] += fan;
        }
        nodes.put(info.id(), n);
        long[] parents = new long[stripes];
        int[] depths = new int[stripes];
        for (int s = 0; s < stripes; s++) {
            if (!place(n, s, null, -1)) unplaced.get(s).add(n);
            parents[s] = n.parent[s] == null ? -1 : n.parent[s].info.id();
            depths[s] = n.depth[s];
        }
        return new Placement(info.id(), n.interior, parents, depths);
    }

    public List<Reattach> leave(long id) {
        Node x = nodes.get(id);
        if (x == null) return List.of();
        if (x.source) throw new IllegalArgumentException("the source cannot leave");
        List<Reattach> events = new ArrayList<>();
        List<List<Node>> orphans = new ArrayList<>();
        for (int s = 0; s < stripes; s++) {
            unplaced.get(s).remove(x);
            Node p = x.parent[s];
            if (p != null) {
                p.children[s].remove(x);
                freeSlots[s]++;
                pushIfFree(p, s);
            }
            List<Node> kids = new ArrayList<>();
            if (x.children[s] != null) {
                kids.addAll(x.children[s]);
                // Detach every orphan subtree first so no orphan can be re-attached under its own descendant.
                for (Node c : kids) { c.parent[s] = null; setDepth(c, s, -1); }
                freeSlots[s] -= x.fanoutIn(s) - kids.size();
            }
            orphans.add(kids);
        }
        nodes.remove(id);
        for (int s = 0; s < stripes; s++) {
            for (Node c : orphans.get(s)) {
                if (!place(c, s, events, id)) unplaced.get(s).add(c);
            }
        }
        retryUnplaced(events);
        return events;
    }

    private void retryUnplaced(List<Reattach> events) {
        for (int s = 0; s < stripes; s++) {
            if (unplaced.get(s).isEmpty()) continue;
            for (Node n : new ArrayList<>(unplaced.get(s))) {
                if (place(n, s, events, -1)) unplaced.get(s).remove(n);
            }
        }
    }

    // ---- placement internals ----------------------------------------------------------------

    /**
     * Attach detached {@code n} in stripe {@code s}. Normally under the shallowest free slot; but a node that
     * will forward here (room for at least 2 children, or 1 when no slot exists) first tries to take the
     * place of the shallowest leaf and adopt it, which pulls forwarders towards the root (shallower trees)
     * and, when the stripe is full, nets (fanout - 1) new slots.
     */
    private boolean place(Node n, int s, List<Reattach> events, long oldParent) {
        Node p = pickSlot(s);
        if (n.hasFree(s)) {
            int room = n.fanoutIn(s) - n.children[s].size();
            Node c = (p == null || room >= 2) ? pickLeaf(s) : null;
            if (c != null && (p == null || c.depth[s] <= p.depth[s])) {
                Node cp = c.parent[s];
                cp.children[s].remove(c);
                freeSlots[s]++;
                c.parent[s] = null;
                c.depth[s] = -1;
                attach(n, cp, s);
                attach(c, n, s);
                if (events != null) {
                    events.add(new Reattach(n.info.id(), s, oldParent, cp.info.id()));
                    events.add(new Reattach(c.info.id(), s, cp.info.id(), n.info.id()));
                }
                return true;
            }
        }
        if (p == null) return false;
        attach(n, p, s);
        if (events != null) events.add(new Reattach(n.info.id(), s, oldParent, p.info.id()));
        return true;
    }

    private Node pickLeaf(int s) {
        PriorityQueue<Slot> h = leafHeaps.get(s);
        while (!h.isEmpty()) {
            Slot e = h.peek();
            Node n = nodes.get(e.id());
            if (n != null && !n.source && n.depth[s] == e.depth() && n.parent[s] != null
                    && (n.children[s] == null || n.children[s].isEmpty())) return n;
            h.poll();
        }
        return null;
    }

    private Node pickSlot(int s) {
        PriorityQueue<Slot> h = heaps.get(s);
        while (!h.isEmpty()) {
            Slot e = h.peek();
            Node n = nodes.get(e.id());
            if (n != null && n.depth[s] == e.depth() && n.hasFree(s)) return n;
            h.poll();
        }
        return null;
    }

    private void pushIfFree(Node n, int s) {
        if (n.depth[s] > 0 && (n.children[s] == null || n.children[s].isEmpty())) {
            leafHeaps.get(s).add(new Slot(n.depth[s], n.info.id()));
        }
        if (n.depth[s] >= 0 && n.hasFree(s)) heaps.get(s).add(new Slot(n.depth[s], n.info.id()));
    }

    private void attach(Node child, Node parent, int s) {
        parent.children[s].add(child);
        child.parent[s] = parent;
        freeSlots[s]--;
        setDepth(child, s, parent.depth[s] + 1);
    }

    /** Sets depth of the subtree under {@code root} in stripe s; d = -1 marks it detached. */
    private void setDepth(Node root, int s, int d) {
        ArrayDeque<Node> st = new ArrayDeque<>();
        root.depth[s] = d;
        st.push(root);
        while (!st.isEmpty()) {
            Node cur = st.pop();
            if (d >= 0) pushIfFree(cur, s);
            if (cur.children[s] == null) continue;
            for (Node c : cur.children[s]) {
                c.depth[s] = d < 0 ? -1 : cur.depth[s] + 1;
                st.push(c);
            }
        }
    }

    // ---- queries ----------------------------------------------------------------------------

    /** Parent id in the stripe, or -1 for the source / an unplaced node. */
    public long parent(long id, int stripe) {
        Node n = nodes.get(id);
        if (n == null || n.parent[stripe] == null) return -1;
        return n.parent[stripe].info.id();
    }

    public List<Long> children(long id, int stripe) {
        Node n = nodes.get(id);
        List<Long> out = new ArrayList<>();
        if (n != null && n.children[stripe] != null) for (Node c : n.children[stripe]) out.add(c.info.id());
        return out;
    }

    /** Depth (source = 0) or -1 if unplaced / unknown. */
    public int depth(long id, int stripe) {
        Node n = nodes.get(id);
        return n == null ? -1 : n.depth[stripe];
    }

    public int maxDepth() {
        int m = 0;
        for (int s = 0; s < stripes; s++) m = Math.max(m, maxDepth(s));
        return m;
    }

    public int maxDepth(int stripe) {
        int m = 0;
        for (Node n : nodes.values()) m = Math.max(m, n.depth[stripe]);
        return m;
    }

    public double averageDepth() {
        long sum = 0, cnt = 0;
        for (Node n : nodes.values()) {
            if (n.source) continue;
            for (int s = 0; s < stripes; s++) if (n.depth[s] >= 0) { sum += n.depth[s]; cnt++; }
        }
        return cnt == 0 ? 0 : (double) sum / cnt;
    }

    /** Upload in kbps this node spends on forwarding (children x stripe rate). */
    public int uploadKbpsUsed(long id) {
        Node n = nodes.get(id);
        if (n == null) return 0;
        int c = 0;
        for (int s = 0; s < stripes; s++) if (n.children[s] != null) c += n.children[s].size();
        return c * stripeKbps;
    }

    /** Stripe this node forwards in, or -1 (leaf everywhere, or the source). */
    public int interiorStripe(long id) {
        Node n = nodes.get(id);
        return n == null || n.source ? -1 : n.interior;
    }

    /** Number of distinct nodes missing from at least one stripe. */
    public int unplacedCount() {
        Set<Long> ids = new HashSet<>();
        for (Set<Node> set : unplaced) for (Node n : set) ids.add(n.info.id());
        return ids.size();
    }

    /** Members including the source. */
    public int size() { return nodes.size(); }

    public int stripes() { return stripes; }

    public NodeInfo source() { return source.info; }

    public List<Long> memberIds() { return new ArrayList<>(nodes.keySet()); }

    /** All problems found; empty means the forest is consistent. Nodes in unplaced subtrees are exempt. */
    public List<String> validate() {
        List<String> bad = new ArrayList<>();
        for (Node n : nodes.values()) {
            int active = 0;
            for (int s = 0; s < stripes; s++) {
                List<Node> kids = n.children[s];
                if (kids != null && !kids.isEmpty()) active++;
                if (kids != null && kids.size() > n.fanoutIn(s)) bad.add("node " + n.info.id() + " over fanout in stripe " + s);
                if (kids != null) for (Node c : kids) if (c.parent[s] != n) bad.add("child link mismatch " + c.info.id());
                if (n.source) continue;
                if (n.depth[s] < 0) {
                    if (!unplacedOrUnder(n, s)) bad.add("node " + n.info.id() + " detached in stripe " + s + " but not tracked");
                    continue;
                }
                Node cur = n;
                int steps = 0;
                while (cur.parent[s] != null && steps <= nodes.size()) { cur = cur.parent[s]; steps++; }
                if (cur != source) bad.add("node " + n.info.id() + " does not reach source in stripe " + s);
                else if (steps != n.depth[s]) bad.add("depth mismatch node " + n.info.id() + " stripe " + s);
            }
            if (!n.source && active > 1) bad.add("node " + n.info.id() + " interior in " + active + " stripes");
        }
        return bad;
    }

    private boolean unplacedOrUnder(Node n, int s) {
        Node cur = n;
        int steps = 0;
        while (cur != null && steps++ <= nodes.size()) {
            if (unplaced.get(s).contains(cur)) return true;
            cur = cur.parent[s];
        }
        return false;
    }
}
