package com.kartikey.p2pchat.conf.node.gossip;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * HyParView-style partial views. The active view (default 6) is the set of connected links the
 * overlay runs on; the passive view (default 40) is a pool of contacts used to replace lost links.
 * Single-threaded: call {@link #onConnected}, {@link #onDisconnected}, {@link #onControl} and
 * {@link #tick} from one thread.
 *
 * <p>Message types 0x01..0x07. Malformed or oversized input is dropped.
 */
public final class Membership {

    public static final int T_JOIN = 0x01, T_FORWARD_JOIN = 0x02, T_NEIGHBOR = 0x03, T_NEIGHBOR_REPLY = 0x04,
            T_DISCONNECT = 0x05, T_SHUFFLE = 0x06, T_SHUFFLE_REPLY = 0x07;

    public interface Listener {
        void activeAdded(Contact peer);

        void activeRemoved(ParticipantId peer);
    }

    private static final int ACTIVE_WALK = 4, PASSIVE_WALK = 2, MAX_TTL = 8, MAX_SAMPLE = 8;
    private static final long PENDING_TIMEOUT = 5_000, UNCLAIMED_TIMEOUT = 5_000, SHUFFLE_PERIOD = 10_000,
            JOIN_RETRY = 5_000, FILL_PERIOD = 1_000, REJOIN_MIN = 60_000, REJOIN_SPREAD = 60_000;
    private static final double JOIN_BURST = 100, JOIN_PER_SECOND = 50;

    private static final class Pending {
        final Contact contact;
        final boolean join, high;
        final long since;
        boolean sent;

        Pending(Contact contact, boolean join, boolean high, long since) {
            this.contact = contact;
            this.join = join;
            this.high = high;
            this.since = since;
        }
    }

    private final PeerNetwork net;
    private final Contact self;
    private final Random rnd;
    private final int activeMax, passiveMax;
    private final Map<ParticipantId, Contact> active = new LinkedHashMap<>();
    private final Map<ParticipantId, Contact> passive = new LinkedHashMap<>();
    private final Map<ParticipantId, Pending> pending = new HashMap<>();
    private final Map<ParticipantId, Long> unclaimed = new HashMap<>();
    private final Map<ParticipantId, byte[]> replies = new HashMap<>();
    private Contact joinContact;
    private Listener listener = new Listener() {
        @Override public void activeAdded(Contact peer) {}
        @Override public void activeRemoved(ParticipantId peer) {}
    };
    private long now, nextShuffle = -1, nextJoin, nextFill, nextRejoin = -1;
    private double joinTokens = JOIN_BURST;
    private long joinTokensAt;

    public Membership(PeerNetwork net, Contact self, Random rnd) {
        this(net, self, rnd, 6, 40);
    }

    public Membership(PeerNetwork net, Contact self, Random rnd, int activeMax, int passiveMax) {
        if (activeMax < 1 || passiveMax < 1) throw new IllegalArgumentException("view sizes");
        this.net = net;
        this.self = self;
        this.rnd = rnd;
        this.activeMax = activeMax;
        this.passiveMax = passiveMax;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public List<ParticipantId> activePeers() {
        return new ArrayList<>(active.keySet());
    }

    public List<Contact> activeContacts() {
        return new ArrayList<>(active.values());
    }

    public List<ParticipantId> passivePeers() {
        return new ArrayList<>(passive.keySet());
    }

    public List<Contact> passiveContacts() {
        return new ArrayList<>(passive.values());
    }

    public Contact contactOf(ParticipantId id) {
        Contact c = active.get(id);
        return c != null ? c : passive.get(id);
    }

    /** Enters the overlay through a known member. Retried from {@link #tick} while we have no links. */
    public void join(Contact contactNode) {
        if (contactNode.id().equals(self.id())) return;
        joinContact = contactNode;
        startConnect(contactNode, true, true);
    }

    // ---------------------------------------------------------------- events

    public void onConnected(ParticipantId peer) {
        if (pending.containsKey(peer)) {
            linkReady(peer);
        } else if (replies.containsKey(peer)) {
            net.sendControl(peer, replies.remove(peer));
            unclaimed.put(peer, now);
        } else if (!active.containsKey(peer)) {
            unclaimed.put(peer, now);
        }
    }

    public void onDisconnected(ParticipantId peer) {
        pending.remove(peer);
        unclaimed.remove(peer);
        replies.remove(peer);
        Contact c = active.remove(peer);
        if (c != null) {
            listener.activeRemoved(peer);
            fill();
        }
    }

    /** @return true if the message type belongs to membership (even if it was malformed and dropped). */
    public boolean onControl(ParticipantId from, byte[] msg) {
        if (msg.length == 0 || (msg[0] & 0xff) < T_JOIN || (msg[0] & 0xff) > T_SHUFFLE_REPLY) return false;
        try {
            Wire.Reader r = new Wire.Reader(msg, 1);
            switch (msg[0] & 0xff) {
                case T_JOIN -> {
                    Contact c = Contact.read(r);
                    r.end();
                    handleJoin(from, c);
                }
                case T_FORWARD_JOIN -> {
                    int ttl = r.u8();
                    Contact c = Contact.read(r);
                    r.end();
                    if (ttl > MAX_TTL) throw new IllegalArgumentException("ttl");
                    handleForwardJoin(from, ttl, c);
                }
                case T_NEIGHBOR -> {
                    int prio = r.u8();
                    Contact c = Contact.read(r);
                    r.end();
                    if (prio > 1) throw new IllegalArgumentException("prio");
                    handleNeighbor(from, prio == 1, c);
                }
                case T_NEIGHBOR_REPLY -> {
                    int ok = r.u8();
                    r.end();
                    if (ok > 1) throw new IllegalArgumentException("flag");
                    handleReply(from, ok == 1);
                }
                case T_DISCONNECT -> {
                    r.end();
                    handleDisconnect(from);
                }
                case T_SHUFFLE -> {
                    int ttl = r.u8();
                    Contact origin = Contact.read(r);
                    List<Contact> sample = readSample(r);
                    r.end();
                    if (ttl > MAX_TTL) throw new IllegalArgumentException("ttl");
                    handleShuffle(from, ttl, origin, sample);
                }
                default -> {
                    List<Contact> sample = readSample(r);
                    r.end();
                    handleShuffleReply(from, sample);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // malformed remote input: drop
        }
        return true;
    }

    public void tick(long nowMillis) {
        now = nowMillis;
        if (nextShuffle < 0) {
            nextShuffle = now + rnd.nextInt((int) SHUFFLE_PERIOD);
            nextRejoin = now + REJOIN_MIN + rnd.nextInt((int) REJOIN_SPREAD);
        }
        if (joinContact != null && now >= nextRejoin) {
            // Heals fragments: flash crowds can leave clusters that only ever talk to each other, and a
            // fresh JOIN through a well-known member is the one link that bridges them again.
            nextRejoin = now + REJOIN_MIN + rnd.nextInt((int) REJOIN_SPREAD);
            if (active.containsKey(joinContact.id())) {
                net.sendControl(joinContact.id(), withContact(T_JOIN, -1, self));
            } else {
                startConnect(joinContact, true, true);
            }
        }
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue().since > PENDING_TIMEOUT) {
                it.remove(); // dead or unreachable contact: forget it
                if (!active.containsKey(e.getKey())) net.disconnect(e.getKey());
            }
        }
        for (var it = unclaimed.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue() > UNCLAIMED_TIMEOUT) {
                it.remove();
                if (!active.containsKey(e.getKey()) && !pending.containsKey(e.getKey())) net.disconnect(e.getKey());
            }
        }
        if (active.isEmpty() && pending.isEmpty() && joinContact != null && now >= nextJoin && passive.isEmpty()) {
            startConnect(joinContact, true, true);
        }
        if (now >= nextShuffle) {
            nextShuffle = now + SHUFFLE_PERIOD;
            shuffle();
        }
        if (active.size() < activeMax && (now >= nextFill || active.isEmpty())) {
            nextFill = now + FILL_PERIOD;
            fill();
        }
    }

    // ---------------------------------------------------------------- handlers

    private void handleJoin(ParticipantId from, Contact c) {
        if (!c.id().equals(from)) return;
        pending.remove(from);
        unclaimed.remove(from);
        joinTokens = Math.min(JOIN_BURST, joinTokens + (now - joinTokensAt) / 1000.0 * JOIN_PER_SECOND);
        joinTokensAt = now;
        if (joinTokens < 1) {
            addPassive(c); // overloaded contact: keep the contact info but do not churn the view
            unclaimed.put(from, now);
            return;
        }
        joinTokens -= 1;
        addActive(c);
        byte[] fwd = withContact(T_FORWARD_JOIN, ACTIVE_WALK, c);
        for (ParticipantId q : new ArrayList<>(active.keySet())) {
            if (!q.equals(from)) net.sendControl(q, fwd);
        }
    }

    private void handleForwardJoin(ParticipantId from, int ttl, Contact c) {
        if (c.id().equals(self.id()) || active.containsKey(c.id())) return;
        ParticipantId next = ttl == 0 || active.size() <= 1 ? null : randomActiveExcept(from);
        if (next == null) {
            startConnect(c, false, true);
            return;
        }
        if (ttl == PASSIVE_WALK) addPassive(c);
        net.sendControl(next, withContact(T_FORWARD_JOIN, ttl - 1, c));
    }

    private void handleNeighbor(ParticipantId from, boolean high, Contact c) {
        if (!c.id().equals(from)) return;
        pending.remove(from);
        unclaimed.remove(from);
        boolean accept = high || active.size() < activeMax || active.containsKey(from);
        if (accept) {
            addActive(c);
        } else {
            addPassive(c);
            unclaimed.put(from, now);
        }
        net.sendControl(from, new byte[]{T_NEIGHBOR_REPLY, (byte) (accept ? 1 : 0)});
    }

    private void handleReply(ParticipantId from, boolean accept) {
        Pending p = pending.remove(from);
        if (p == null || active.containsKey(from)) return;
        if (accept) {
            addActive(p.contact);
        } else {
            net.disconnect(from);
            addPassive(p.contact);
        }
    }

    private void handleDisconnect(ParticipantId from) {
        Contact c = active.remove(from);
        if (c != null) {
            listener.activeRemoved(from);
            addPassive(c);
            net.disconnect(from);
            fill();
        }
    }

    private void handleShuffle(ParticipantId from, int ttl, Contact origin, List<Contact> sample) {
        if (!active.containsKey(from) || origin.id().equals(self.id())) return;
        ParticipantId next = ttl > 0 && active.size() > 1 ? randomActiveExcept(from) : null;
        if (next != null) {
            net.sendControl(next, shuffleMsg(T_SHUFFLE, ttl - 1, origin, sample));
            return;
        }
        List<Contact> mine = new ArrayList<>(passive.values());
        Collections.shuffle(mine, rnd);
        byte[] reply = sampleMsg(T_SHUFFLE_REPLY, mine.subList(0, Math.min(MAX_SAMPLE, mine.size())));
        integrate(sample);
        addPassive(origin);
        if (net.connected().contains(origin.id())) {
            net.sendControl(origin.id(), reply);
        } else if (replies.size() < 8) {
            replies.put(origin.id(), reply);
            net.connect(origin.id(), origin.candidates(), null);
            if (replies.containsKey(origin.id()) && net.connected().contains(origin.id())) {
                net.sendControl(origin.id(), replies.remove(origin.id()));
                unclaimed.put(origin.id(), now);
            }
        }
    }

    private void handleShuffleReply(ParticipantId from, List<Contact> sample) {
        integrate(sample);
        if (!active.containsKey(from) && !pending.containsKey(from)) net.disconnect(from);
    }

    // ---------------------------------------------------------------- actions

    private void shuffle() {
        ParticipantId target = randomActiveExcept(null);
        if (target == null) return;
        List<Contact> sample = new ArrayList<>();
        sample.add(self);
        List<Contact> a = new ArrayList<>(active.values());
        a.removeIf(c -> c.id().equals(target));
        Collections.shuffle(a, rnd);
        sample.addAll(a.subList(0, Math.min(3, a.size())));
        List<Contact> p = new ArrayList<>(passive.values());
        Collections.shuffle(p, rnd);
        sample.addAll(p.subList(0, Math.min(MAX_SAMPLE - sample.size(), p.size())));
        net.sendControl(target, shuffleMsg(T_SHUFFLE, 3, self, sample));
    }

    private void fill() {
        int budget = active.isEmpty() ? 3 : 2;
        int pend = 0;
        for (Pending p : pending.values()) if (!p.join) pend++;
        int need = Math.min(budget, activeMax - active.size() - pend);
        if (need <= 0 || passive.isEmpty()) return;
        List<Contact> candidates = new ArrayList<>(passive.values());
        Collections.shuffle(candidates, rnd);
        for (Contact c : candidates) {
            if (need <= 0) break;
            if (pending.containsKey(c.id())) continue;
            passive.remove(c.id());
            startConnect(c, false, active.isEmpty());
            need--;
        }
    }

    private void startConnect(Contact c, boolean join, boolean high) {
        ParticipantId id = c.id();
        if (id.equals(self.id()) || active.containsKey(id) || pending.containsKey(id)) return;
        passive.remove(id);
        pending.put(id, new Pending(c, join, high, now));
        net.connect(id, c.candidates(), null);
        if (pending.containsKey(id) && net.connected().contains(id)) linkReady(id);
    }

    private void linkReady(ParticipantId peer) {
        Pending p = pending.get(peer);
        if (p == null || p.sent) return;
        p.sent = true;
        unclaimed.remove(peer);
        if (p.join) {
            pending.remove(peer);
            addActive(p.contact);
            net.sendControl(peer, withContact(T_JOIN, -1, self));
        } else {
            net.sendControl(peer, withContact(T_NEIGHBOR, p.high ? 1 : 0, self));
        }
        nextJoin = now + JOIN_RETRY;
    }

    private void addActive(Contact c) {
        ParticipantId id = c.id();
        if (id.equals(self.id())) return;
        if (active.containsKey(id)) {
            active.put(id, c);
            return;
        }
        passive.remove(id);
        if (active.size() >= activeMax) {
            List<ParticipantId> ids = new ArrayList<>(active.keySet());
            ParticipantId victim = ids.get(rnd.nextInt(ids.size()));
            Contact vc = active.remove(victim);
            net.sendControl(victim, new byte[]{T_DISCONNECT});
            net.disconnect(victim);
            listener.activeRemoved(victim);
            addPassive(vc);
        }
        active.put(id, c);
        listener.activeAdded(c);
    }

    private void addPassive(Contact c) {
        ParticipantId id = c.id();
        if (id.equals(self.id()) || active.containsKey(id)) return;
        if (passive.containsKey(id)) return;
        if (passive.size() >= passiveMax) {
            List<ParticipantId> ids = new ArrayList<>(passive.keySet());
            passive.remove(ids.get(rnd.nextInt(ids.size())));
        }
        passive.put(id, c);
    }

    private void integrate(List<Contact> sample) {
        for (Contact c : sample) addPassive(c);
    }

    private ParticipantId randomActiveExcept(ParticipantId except) {
        List<ParticipantId> ids = new ArrayList<>(active.keySet());
        ids.remove(except);
        return ids.isEmpty() ? null : ids.get(rnd.nextInt(ids.size()));
    }

    // ---------------------------------------------------------------- wire

    private static byte[] withContact(int type, int extra, Contact c) {
        Wire.Writer w = new Wire.Writer().u8(type);
        if (extra >= 0) w.u8(extra);
        c.write(w);
        return w.done();
    }

    private static byte[] shuffleMsg(int type, int ttl, Contact origin, List<Contact> sample) {
        Wire.Writer w = new Wire.Writer().u8(type).u8(ttl);
        origin.write(w);
        w.u8(sample.size());
        sample.forEach(c -> c.write(w));
        return w.done();
    }

    private static byte[] sampleMsg(int type, List<Contact> sample) {
        Wire.Writer w = new Wire.Writer().u8(type).u8(sample.size());
        sample.forEach(c -> c.write(w));
        return w.done();
    }

    private static List<Contact> readSample(Wire.Reader r) {
        int n = r.u8();
        if (n > MAX_SAMPLE) throw new IllegalArgumentException("sample too large");
        List<Contact> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Contact.read(r));
        return out;
    }
}
