package com.kartikey.p2pchat.conf.node.gossip;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.overlay.GossipFilter;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Eager-push gossip of signed items over the active view, with lazy repair (IHAVE / IWANT).
 * Items failing the validator are neither delivered nor forwarded, so forged data dies at the
 * first honest hop. Message types 0x10 ITEM, 0x11 IHAVE, 0x12 IWANT. Single-threaded.
 *
 * <p>Known limit: an id that failed validation stays "seen", so a later-valid copy of the same
 * item is only recovered through policy/key anti-entropy, not through gossip.
 */
public final class Gossip {

    public static final int T_ITEM = 0x10, T_IHAVE = 0x11, T_IWANT = 0x12;
    public static final int MAX_ITEM_BYTES = 64 * 1024;

    /** A gossiped payload. Authenticity lives inside {@code body} and is checked by the validator. */
    public record Item(int kind, byte[] body) {
        public static final int GENESIS = 1, ADMIN_COMMAND = 2, KEY_BUNDLE = 3, JOIN_TICKET = 4, PRESENCE = 5, CHAT = 6;

        public Item {
            if (kind < GENESIS || kind > CHAT) throw new IllegalArgumentException("bad item kind");
            if (body.length > MAX_ITEM_BYTES) throw new IllegalArgumentException("item too large");
            body = body.clone();
        }

        /** SHA-256 over kind and body. */
        public byte[] id() {
            return Crypto.sha256(new byte[]{(byte) kind}, body);
        }
    }

    /** Token bucket limits per peer: {@code burst} items at once, {@code perSecond} sustained. */
    public record RateLimit(double burst, double perSecond) {}

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }

    private static final int STORE_MAX = 1024, IHAVE_IDS = 32, IWANT_MAX = 16, SEEN_MAX = 4096, INVALID_MAX = 512;
    private static final long IHAVE_PERIOD = 2_000, REQUEST_HOLDOFF = 5_000;

    private final PeerNetwork net;
    private final Supplier<? extends Collection<ParticipantId>> peers;
    private final Predicate<Item> validator;
    private final Consumer<Item> delivery;
    private final Random rnd;
    private final RateLimit limit;
    private final GossipFilter seen = new GossipFilter(SEEN_MAX);
    private final Map<ByteBuffer, Item> store = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<ByteBuffer, Item> e) {
            return size() > STORE_MAX;
        }
    };
    private final Deque<ByteBuffer> recent = new ArrayDeque<>();
    private final Set<ByteBuffer> invalid = new LinkedHashSet<>();
    private final Map<ByteBuffer, Long> requested = new HashMap<>();
    private final Map<ParticipantId, Bucket> buckets = new HashMap<>();
    private long now, nextIhave = -1;
    private long itemsSent, itemsReceived, duplicates, rejected, rateLimited, ihaveSent, iwantSent;

    public Gossip(PeerNetwork net, Supplier<? extends Collection<ParticipantId>> activePeers,
                  Predicate<Item> validator, Consumer<Item> delivery, Random rnd) {
        this(net, activePeers, validator, delivery, rnd, new RateLimit(200, 100));
    }

    public Gossip(PeerNetwork net, Supplier<? extends Collection<ParticipantId>> activePeers,
                  Predicate<Item> validator, Consumer<Item> delivery, Random rnd, RateLimit limit) {
        this.net = net;
        this.peers = activePeers;
        this.validator = validator;
        this.delivery = delivery;
        this.rnd = rnd;
        this.limit = limit;
    }

    /** Originates an item: remembered locally and pushed to every active peer. Not delivered to our own callback. */
    public void publish(Item item) {
        byte[] id = item.id();
        if (!seen.firstTime(id)) return;
        remember(id, item);
        pushToAll(encode(item), null);
    }

    public void onConnected(ParticipantId peer) {}

    public void onDisconnected(ParticipantId peer) {
        buckets.remove(peer);
    }

    /** @return true if the type belongs to gossip. */
    public boolean onControl(ParticipantId from, byte[] msg) {
        if (msg.length == 0 || (msg[0] & 0xff) < T_ITEM || (msg[0] & 0xff) > T_IWANT) return false;
        if (!allow(from)) {
            rateLimited++;
            return true;
        }
        try {
            switch (msg[0] & 0xff) {
                case T_ITEM -> handleItem(from, decodeItem(msg));
                case T_IHAVE -> handleIhave(from, readIds(msg));
                default -> handleIwant(from, readIds(msg));
            }
        } catch (IllegalArgumentException ignored) {
            rejected++;
        }
        return true;
    }

    public void tick(long nowMillis) {
        now = nowMillis;
        if (nextIhave < 0) nextIhave = now + rnd.nextInt((int) IHAVE_PERIOD);
        if (now >= nextIhave) {
            nextIhave = now + IHAVE_PERIOD;
            if (!recent.isEmpty()) {
                List<ParticipantId> ps = new ArrayList<>(peers.get());
                if (!ps.isEmpty()) {
                    Wire.Writer w = new Wire.Writer().u8(T_IHAVE).u8(recent.size());
                    recent.forEach(id -> w.bytes(id.array()));
                    net.sendControl(ps.get(rnd.nextInt(ps.size())), w.done());
                    ihaveSent++;
                }
            }
            requested.values().removeIf(t -> now - t > REQUEST_HOLDOFF);
        }
    }

    // ---------------------------------------------------------------- stats

    public long itemsSent() { return itemsSent; }
    public long itemsReceived() { return itemsReceived; }
    public long duplicates() { return duplicates; }
    public long rejected() { return rejected; }
    public long rateLimited() { return rateLimited; }
    public long ihaveSent() { return ihaveSent; }
    public long iwantSent() { return iwantSent; }

    // ---------------------------------------------------------------- handlers

    private void handleItem(ParticipantId from, Item item) {
        itemsReceived++;
        byte[] id = item.id();
        if (!seen.firstTime(id)) {
            duplicates++;
            return;
        }
        ByteBuffer key = ByteBuffer.wrap(id);
        requested.remove(key);
        boolean ok;
        try {
            ok = validator.test(item);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            rejected++;
            invalid.add(key);
            if (invalid.size() > INVALID_MAX) invalid.remove(invalid.iterator().next());
            return;
        }
        remember(id, item);
        delivery.accept(item);
        pushToAll(encode(item), from);
    }

    private void handleIhave(ParticipantId from, List<byte[]> ids) {
        Wire.Writer w = new Wire.Writer().u8(T_IWANT);
        int n = 0;
        ByteArrayList want = new ByteArrayList();
        for (byte[] id : ids) {
            ByteBuffer k = ByteBuffer.wrap(id);
            if (store.containsKey(k) || invalid.contains(k) || requested.containsKey(k) || n >= IWANT_MAX) continue;
            requested.put(k, now);
            want.add(id);
            n++;
        }
        if (n == 0) return;
        w.u8(n);
        want.forEach(w::bytes);
        net.sendControl(from, w.done());
        iwantSent++;
    }

    private void handleIwant(ParticipantId from, List<byte[]> ids) {
        int n = 0;
        for (byte[] id : ids) {
            Item it = store.get(ByteBuffer.wrap(id));
            if (it != null && n++ < IWANT_MAX) {
                net.sendControl(from, encode(it));
                itemsSent++;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean allow(ParticipantId from) {
        Bucket b = buckets.computeIfAbsent(from, k -> new Bucket(limit.burst(), now));
        b.tokens = Math.min(limit.burst(), b.tokens + (now - b.last) / 1000.0 * limit.perSecond());
        b.last = now;
        if (b.tokens < 1) return false;
        b.tokens -= 1;
        return true;
    }

    private void remember(byte[] id, Item item) {
        ByteBuffer k = ByteBuffer.wrap(id);
        store.put(k, item);
        recent.addLast(k);
        if (recent.size() > IHAVE_IDS) recent.removeFirst();
    }

    private void pushToAll(byte[] wire, ParticipantId except) {
        for (ParticipantId p : peers.get()) {
            if (!p.equals(except) && net.sendControl(p, wire)) itemsSent++;
        }
    }

    static byte[] encode(Item item) {
        return ByteBuffer.allocate(2 + item.body().length).put((byte) T_ITEM).put((byte) item.kind()).put(item.body()).array();
    }

    static Item decodeItem(byte[] msg) {
        if (msg.length < 2 || msg.length > 2 + MAX_ITEM_BYTES) throw new IllegalArgumentException("bad item size");
        return new Item(msg[1] & 0xff, Arrays.copyOfRange(msg, 2, msg.length));
    }

    private static List<byte[]> readIds(byte[] msg) {
        Wire.Reader r = new Wire.Reader(msg, 1);
        int n = r.u8();
        if (n > IHAVE_IDS) throw new IllegalArgumentException("too many ids");
        List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(r.bytes(32));
        r.end();
        return out;
    }

    private static final class ByteArrayList extends ArrayList<byte[]> {}
}
