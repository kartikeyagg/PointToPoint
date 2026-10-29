package com.kartikey.p2pchat.conf.node.gossip;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.MemoryFabric;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.policy.PolicyState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** Test harness: N nodes (membership + gossip, optionally policy sync) on a MemoryFabric with a manual clock. */
final class Sim {

    private static final List<Identity> IDS = new ArrayList<>();

    static synchronized Identity identity(int i) {
        while (IDS.size() <= i) IDS.add(Identity.generate());
        return IDS.get(i);
    }

    static final class SimNode implements PeerNetwork.Listener {
        final int index;
        final Identity identity;
        final PeerNetwork net;
        final Membership membership;
        final Gossip gossip;
        PolicySync sync;
        PolicyState policy;
        final List<Gossip.Item> delivered = new ArrayList<>();
        boolean crashed;

        SimNode(int index, MemoryFabric fabric, Random rnd, Predicate<Gossip.Item> validator) {
            this.index = index;
            this.identity = identity(index);
            this.net = fabric.join(identity.id());
            Contact self = new Contact(identity.id(), net.candidates(), 1000, true);
            this.membership = new Membership(net, self, rnd);
            this.gossip = new Gossip(net, membership::activePeers, validator, delivered::add, rnd);
            net.setListener(this);
        }

        Contact contact() {
            return new Contact(identity.id(), net.candidates(), 1000, true);
        }

        @Override public void onConnected(ParticipantId peer) {
            membership.onConnected(peer);
            gossip.onConnected(peer);
        }

        @Override public void onDisconnected(ParticipantId peer) {
            membership.onDisconnected(peer);
            gossip.onDisconnected(peer);
            if (sync != null) sync.onDisconnected(peer);
        }

        @Override public void onMedia(ParticipantId from, byte[] packet) {}

        @Override public void onControl(ParticipantId from, byte[] msg) {
            if (membership.onControl(from, msg)) return;
            if (gossip.onControl(from, msg)) return;
            if (sync != null) sync.onControl(from, msg);
        }

        void tick(long now) {
            membership.tick(now);
            gossip.tick(now);
            if (sync != null) sync.tick(now);
        }
    }

    final MemoryFabric fabric;
    final List<SimNode> nodes = new ArrayList<>();
    final Random rnd;
    long now;

    Sim(long seed, int n, Predicate<Gossip.Item> validator) {
        this.fabric = new MemoryFabric(seed);
        this.rnd = new Random(seed);
        for (int i = 0; i < n; i++) nodes.add(new SimNode(i, fabric, new Random(seed * 1000 + i), validator));
    }

    void run(long millis) {
        long end = now + millis;
        while (now < end) {
            now += 200;
            fabric.advanceTo(now);
            for (SimNode n : nodes) if (!n.crashed) n.tick(now);
        }
    }

    /** Joins nodes [from, to) through random members of the first {@code contacts} nodes, perBatch every 200 ms. */
    void joinAll(int from, int to, int contacts, int perBatch) {
        for (int i = from; i < to; i += perBatch) {
            for (int j = i; j < Math.min(to, i + perBatch); j++) {
                if (j < contacts) {
                    if (j > 0) nodes.get(j).membership.join(nodes.get(0).contact());
                } else {
                    nodes.get(j).membership.join(nodes.get(rnd.nextInt(contacts)).contact());
                }
            }
            run(200);
        }
    }

    void crash(SimNode n) {
        n.crashed = true;
        fabric.crash(n.identity.id());
    }

    List<SimNode> alive() {
        List<SimNode> out = new ArrayList<>();
        for (SimNode n : nodes) if (!n.crashed) out.add(n);
        return out;
    }

    /** Size of the largest connected component over the live nodes' active views (edges are links we hold). */
    int largestComponent() {
        Map<ParticipantId, SimNode> by = new HashMap<>();
        for (SimNode n : alive()) by.put(n.identity.id(), n);
        Map<ParticipantId, Set<ParticipantId>> adj = new HashMap<>();
        for (SimNode n : alive()) {
            for (ParticipantId p : n.membership.activePeers()) {
                adj.computeIfAbsent(n.identity.id(), k -> new HashSet<>()).add(p);
                adj.computeIfAbsent(p, k -> new HashSet<>()).add(n.identity.id());
            }
        }
        Set<ParticipantId> seen = new HashSet<>();
        int best = 0;
        for (SimNode s : alive()) {
            if (seen.contains(s.identity.id())) continue;
            Queue<SimNode> q = new ArrayDeque<>();
            q.add(s);
            seen.add(s.identity.id());
            int size = 0;
            while (!q.isEmpty()) {
                SimNode c = q.poll();
                size++;
                for (ParticipantId p : adj.getOrDefault(c.identity.id(), Set.of())) {
                    SimNode o = by.get(p);
                    if (o != null && seen.add(p)) q.add(o);
                }
            }
            best = Math.max(best, size);
        }
        return best;
    }

    static <T> Optional<T> none() {
        return Optional.empty();
    }
}
