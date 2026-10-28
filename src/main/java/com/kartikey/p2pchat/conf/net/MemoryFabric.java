package com.kartikey.p2pchat.conf.net;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * An in-memory network for tests and simulations, driven by a manual clock so runs are
 * deterministic. It can drop, delay and reorder media, partition nodes and crash them, which is
 * how the multi-node tests check that meetings survive bad networks.
 *
 * <p>Control messages are reliable (as the real network promises) but still delayed; media is
 * lossy. Nothing is delivered until {@link #advanceTo} is called, and then callbacks run on the
 * caller's thread in timestamp order.
 */
public final class MemoryFabric {

    private record InFlight(long at, long order, Node to, ParticipantId from, byte[] data, boolean control) {}

    private final Random rnd;
    private final Map<ParticipantId, Node> nodes = new HashMap<>();
    private final PriorityQueue<InFlight> queue = new PriorityQueue<>((a, b) ->
            a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));
    private final Set<ParticipantId> crashed = new HashSet<>();
    private final Set<Set<ParticipantId>> cut = new HashSet<>();
    private long now, order;
    private double mediaLoss;
    private int baseDelayMillis = 20, jitterMillis = 10;
    private long delivered, dropped;

    public MemoryFabric(long seed) {
        this.rnd = new Random(seed);
    }

    public PeerNetwork join(ParticipantId id) {
        Node n = new Node(id);
        nodes.put(id, n);
        return n;
    }

    public void setMediaLoss(double loss) {
        this.mediaLoss = loss;
    }

    public void setDelay(int baseMillis, int jitterMillis) {
        this.baseDelayMillis = baseMillis;
        this.jitterMillis = jitterMillis;
    }

    /** The node stops sending and receiving; its links drop as if the machine died. */
    public void crash(ParticipantId id) {
        crashed.add(id);
        for (Node n : nodes.values()) {
            if (n.links.remove(id) && n.listener != null && !crashed.contains(n.id)) {
                n.listener.onDisconnected(id);
            }
        }
        Node dead = nodes.get(id);
        if (dead != null) {
            dead.links.clear();
        }
    }

    /** No traffic between a and b (both directions) until {@link #heal}. */
    public void partition(ParticipantId a, ParticipantId b) {
        cut.add(Set.of(a, b));
    }

    public void heal(ParticipantId a, ParticipantId b) {
        cut.remove(Set.of(a, b));
    }

    public long now() {
        return now;
    }

    public long delivered() {
        return delivered;
    }

    public long dropped() {
        return dropped;
    }

    /** Delivers everything due up to {@code t}, in order, then sets the clock to {@code t}. */
    public void advanceTo(long t) {
        while (!queue.isEmpty() && queue.peek().at <= t) {
            InFlight m = queue.poll();
            now = m.at;
            Node to = m.to;
            if (crashed.contains(to.id) || crashed.contains(m.from) || !to.links.contains(m.from)
                    || to.listener == null) {
                dropped++;
                continue;
            }
            delivered++;
            if (m.control) {
                to.listener.onControl(m.from, m.data);
            } else {
                to.listener.onMedia(m.from, m.data);
            }
        }
        now = Math.max(now, t);
    }

    private boolean blocked(ParticipantId a, ParticipantId b) {
        return crashed.contains(a) || crashed.contains(b) || cut.contains(Set.of(a, b));
    }

    private long arrival() {
        return now + baseDelayMillis + (jitterMillis > 0 ? rnd.nextInt(jitterMillis + 1) : 0);
    }

    private final class Node implements PeerNetwork {
        final ParticipantId id;
        final Set<ParticipantId> links = new LinkedHashSet<>();
        final Map<ParticipantId, long[]> bytes = new HashMap<>();
        Listener listener;
        /** Control messages must stay in order per link even with jitter. */
        final Map<ParticipantId, Long> lastControlAt = new HashMap<>();

        Node(ParticipantId id) {
            this.id = id;
        }

        @Override
        public ParticipantId self() {
            return id;
        }

        @Override
        public List<InetSocketAddress> candidates() {
            return List.of(InetSocketAddress.createUnresolved(id.shortId(), 7302));
        }

        @Override
        public void connect(ParticipantId peer, List<InetSocketAddress> candidates, ParticipantId via) {
            Node other = nodes.get(peer);
            if (other == null || peer.equals(id) || blocked(id, peer) || links.contains(peer)) {
                return;
            }
            links.add(peer);
            other.links.add(id);
            if (listener != null) {
                listener.onConnected(peer);
            }
            if (other.listener != null) {
                other.listener.onConnected(id);
            }
        }

        @Override
        public void disconnect(ParticipantId peer) {
            Node other = nodes.get(peer);
            if (links.remove(peer) && listener != null) {
                listener.onDisconnected(peer);
            }
            if (other != null && other.links.remove(id) && other.listener != null) {
                other.listener.onDisconnected(id);
            }
        }

        @Override
        public Set<ParticipantId> connected() {
            return Set.copyOf(links);
        }

        @Override
        public boolean sendMedia(ParticipantId peer, byte[] packet) {
            if (!links.contains(peer)) {
                return false;
            }
            count(peer, packet.length);
            if (blocked(id, peer) || rnd.nextDouble() < mediaLoss) {
                dropped++;
                return true;
            }
            queue.add(new InFlight(arrival(), order++, nodes.get(peer), id, packet.clone(), false));
            return true;
        }

        @Override
        public boolean sendControl(ParticipantId peer, byte[] message) {
            if (!links.contains(peer)) {
                return false;
            }
            count(peer, message.length);
            if (blocked(id, peer)) {
                dropped++;
                return true;
            }
            long at = Math.max(arrival(), lastControlAt.getOrDefault(peer, 0L));
            lastControlAt.put(peer, at);
            queue.add(new InFlight(at, order++, nodes.get(peer), id, message.clone(), true));
            return true;
        }

        private void count(ParticipantId peer, int n) {
            bytes.computeIfAbsent(peer, k -> new long[1])[0] += n;
        }

        @Override
        public LinkStats stats(ParticipantId peer) {
            if (!links.contains(peer)) {
                return null;
            }
            return new LinkStats(2 * baseDelayMillis + jitterMillis, mediaLoss,
                    bytes.getOrDefault(peer, new long[1])[0], 0, false);
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            for (ParticipantId p : new ArrayList<>(links)) {
                disconnect(p);
            }
        }
    }
}
