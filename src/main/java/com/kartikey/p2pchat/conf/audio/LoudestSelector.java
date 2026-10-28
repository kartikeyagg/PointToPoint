package com.kartikey.p2pchat.conf.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which streams a relay forwards, from their audio-level bytes alone.
 *
 * <p>A stream is a candidate while it was "loud" (level at most {@link #LOUD_LEVEL}) within the last
 * holdMillis; the hold is the hysteresis that stops the set flapping between syllables. Candidates are
 * ranked by their latest level, so a held-but-quiet stream only loses its slot to someone genuinely
 * louder. Streams silent for 2x holdMillis without any update are forgotten entirely.
 *
 * <p>Pinned streams (the active speaker) are always returned first and do NOT count toward n: the
 * speaker must never be bumped by a shouting crowd, and n then means "extra loud voices".
 * Not thread-safe; callers synchronise.
 */
public final class LoudestSelector {

    /** Levels at or below this (dBov) count as speech; quieter frames do not refresh the hold. */
    public static final int LOUD_LEVEL = 96;

    private static final class State {
        int level = AudioLevel.SILENCE;
        long lastUpdate;
        long lastLoud = Long.MIN_VALUE;
    }

    private final int n;
    private final long holdMillis;
    private final Map<Long, State> streams = new HashMap<>();
    private final Set<Long> pinned = new HashSet<>();

    public LoudestSelector(int n, long holdMillis) {
        if (n < 0 || holdMillis < 0) {
            throw new IllegalArgumentException("n and holdMillis must be >= 0");
        }
        this.n = n;
        this.holdMillis = holdMillis;
    }

    public void update(long streamId, int level, long now) {
        State s = streams.computeIfAbsent(streamId, k -> new State());
        s.level = Math.max(0, Math.min(AudioLevel.SILENCE, level));
        s.lastUpdate = now;
        if (s.level <= LOUD_LEVEL) {
            s.lastLoud = now;
        }
    }

    public void pin(long streamId) {
        pinned.add(streamId);
    }

    public void unpin(long streamId) {
        pinned.remove(streamId);
    }

    /** Pinned streams, then up to n loudest recent others (loudest first). */
    public List<Long> selected(long now) {
        streams.values().removeIf(s -> now - s.lastUpdate > 2 * holdMillis);
        List<Map.Entry<Long, State>> cands = new ArrayList<>();
        for (Map.Entry<Long, State> e : streams.entrySet()) {
            State s = e.getValue();
            if (!pinned.contains(e.getKey()) && s.lastLoud != Long.MIN_VALUE && now - s.lastLoud <= holdMillis) {
                cands.add(e);
            }
        }
        cands.sort((a, b) -> {
            int c = Integer.compare(a.getValue().level, b.getValue().level);
            return c != 0 ? c : Long.compare(a.getKey(), b.getKey());
        });
        List<Long> out = new ArrayList<>(pinned);
        for (int i = 0; i < Math.min(n, cands.size()); i++) {
            out.add(cands.get(i).getKey());
        }
        return out;
    }
}
