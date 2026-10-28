package com.kartikey.p2pchat.conf.overlay;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Bounded LRU of recently seen message ids so gossip floods die out instead of looping. */
public final class GossipFilter {

    private final Map<ByteBuffer, Boolean> seen;

    public GossipFilter(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<ByteBuffer, Boolean> e) {
                return size() > capacity;
            }
        };
    }

    /** True the first time this id is offered (and remembers it); false for repeats, which refresh recency. */
    public synchronized boolean firstTime(byte[] id) {
        ByteBuffer key = ByteBuffer.wrap(id.clone()); // clone: caller may reuse its buffer
        if (seen.get(key) != null) return false;
        seen.put(key, Boolean.TRUE);
        return true;
    }

    public synchronized int size() { return seen.size(); }

    /** Random subset of up to {@code fanout} neighbours to forward a message to. */
    public static List<Long> pickTargets(List<Long> neighbours, int fanout, Random rnd) {
        List<Long> copy = new ArrayList<>(neighbours);
        Collections.shuffle(copy, rnd);
        return new ArrayList<>(copy.subList(0, Math.min(Math.max(0, fanout), copy.size())));
    }
}
