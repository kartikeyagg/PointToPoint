package com.kartikey.p2pchat.conf.policy;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The meeting's moderation state as a pure function of the set of verified commands. Commands
 * arrive over gossip in any order, so nothing is applied on arrival: the derived state is a replay
 * of everything stored, sorted by (lamport, issuer, id), and each command counts only if its issuer
 * was authorized at that point of the replay. Two nodes holding the same set therefore agree on
 * every query. With no admin online nothing new is issued, so the state simply stays as it is.
 */
public final class PolicyState {

    /** REJECTED: lamport not positive or implausibly far ahead of the effective history. */
    public enum AddResult { ADDED, DUPLICATE, BAD_SIGNATURE, WRONG_MEETING, REJECTED }

    /** A lamport this far beyond the highest effective one is treated as an attack, not a clock. */
    public static final long MAX_LAMPORT_JUMP = 1_000_000;
    public static final int MAX_JUNK_PER_ISSUER = 64;
    public static final int MAX_JUNK_TOTAL = 4096;

    private record Entry(String key, AdminCommand cmd, byte[] id, long seq) { }

    private static final Comparator<Entry> REPLAY_ORDER = Comparator
            .<Entry>comparingLong(e -> e.cmd().lamport())
            .thenComparing(e -> e.cmd().issuer())
            .thenComparing(Entry::id, Arrays::compareUnsigned);

    private final MeetingGenesis genesis;
    private final Map<String, Entry> entries = new HashMap<>();
    private long seq;
    private long effectiveMaxHint;
    private Derived derived;

    public PolicyState(MeetingGenesis genesis) {
        if (!genesis.verify()) {
            throw new IllegalArgumentException("bad genesis signature");
        }
        this.genesis = genesis;
        this.derived = replay(List.of());
    }

    public MeetingGenesis genesis() {
        return genesis;
    }

    public synchronized AddResult add(AdminCommand c) {
        if (!genesis.sameMeeting(c.meetingId())) {
            return AddResult.WRONG_MEETING;
        }
        if (c.lamport() <= 0) {
            return AddResult.REJECTED;
        }
        byte[] id = c.id();
        String key = HexFormat.of().formatHex(id);
        if (entries.containsKey(key)) {
            return AddResult.DUPLICATE;
        }
        if (!c.verify()) {
            return AddResult.BAD_SIGNATURE;
        }
        if (c.lamport() - effectiveMaxHint > MAX_LAMPORT_JUMP) {
            d();
            if (c.lamport() - effectiveMaxHint > MAX_LAMPORT_JUMP) {
                return AddResult.REJECTED;
            }
        }
        entries.put(key, new Entry(key, c, id, seq++));
        derived = null;
        return AddResult.ADDED;
    }

    private Derived d() {
        if (derived == null) {
            List<Entry> sorted = new ArrayList<>(entries.values());
            sorted.sort(REPLAY_ORDER);
            derived = replay(sorted);
            effectiveMaxHint = derived.maxEffective;
            evictJunk(sorted, derived);
        }
        return derived;
    }

    /**
     * Replays in order; when an effective revocation voids commands its target signed below the
     * revocation's lamport (and outside the frozen set), the replay restarts without them. The void
     * set only grows, so this ends, and it depends only on the set of commands.
     */
    private Derived replay(List<Entry> sorted) {
        Map<ParticipantId, List<Entry>> byIssuer = new HashMap<>();
        for (Entry e : sorted) {
            byIssuer.computeIfAbsent(e.cmd().issuer(), k -> new ArrayList<>()).add(e);
        }
        Set<Entry> voided = Collections.newSetFromMap(new IdentityHashMap<>());
        while (true) {
            Derived s = new Derived(genesis.host(), genesis.cameraAllowedByDefault(), byIssuer, voided);
            for (Entry e : sorted) {
                if (!voided.contains(e)) {
                    s.apply(e);
                }
            }
            if (s.newVoids.isEmpty()) {
                return s;
            }
            voided.addAll(s.newVoids);
        }
    }

    /**
     * Commands that never took effect (issuer not an admin yet, unauthorized, or voided) are kept
     * only in a bounded pool, newest first, so junk cannot grow memory or replay cost; a pending
     * command whose issuer is granted later is re-evaluated by the next replay while it is in the pool.
     */
    private void evictJunk(List<Entry> sorted, Derived s) {
        List<Entry> junk = new ArrayList<>();
        for (Entry e : sorted) {
            if (!s.effective.contains(e)) {
                junk.add(e);
            }
        }
        if (junk.size() <= MAX_JUNK_PER_ISSUER) {
            return;
        }
        junk.sort(Comparator.comparingLong(Entry::seq).reversed());
        Map<ParticipantId, Integer> perIssuer = new HashMap<>();
        int kept = 0;
        for (Entry e : junk) {
            int n = perIssuer.merge(e.cmd().issuer(), 1, Integer::sum);
            if (n > MAX_JUNK_PER_ISSUER || kept >= MAX_JUNK_TOTAL) {
                entries.remove(e.key());
            } else {
                kept++;
            }
        }
    }

    /**
     * Ids of up to {@value AdminCommand#MAX_FROZEN} effective commands by {@code target} (newest
     * first): what a revoker passes to {@link AdminCommand#sign} so those survive the revocation.
     * Trade-off: a legitimate command the revoker has not yet seen is dropped (safety over liveness).
     */
    public synchronized List<byte[]> frozenFor(ParticipantId target) {
        Derived s = d();
        List<Entry> mine = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.cmd().issuer().equals(target) && s.effective.contains(e)) {
                mine.add(e);
            }
        }
        mine.sort(Comparator.comparingLong((Entry e) -> e.cmd().lamport()).reversed());
        List<byte[]> out = new ArrayList<>();
        for (Entry e : mine.subList(0, Math.min(mine.size(), AdminCommand.MAX_FROZEN))) {
            out.add(e.id());
        }
        return out;
    }

    public synchronized ParticipantId host() {
        return d().host;
    }

    public synchronized Set<ParticipantId> admins() {
        return new TreeSet<>(d().admins);
    }

    public synchronized boolean isAdmin(ParticipantId p) {
        return d().admins.contains(p);
    }

    public synchronized boolean isKicked(ParticipantId p) {
        return d().kicked.contains(p);
    }

    /** Admins always; otherwise an explicit allow, or an open mic that has not expired, unless denied. */
    public synchronized boolean canSpeak(ParticipantId p, long now) {
        Derived s = d();
        if (s.kicked.contains(p)) {
            return false;
        }
        if (s.admins.contains(p)) {
            return true;
        }
        Boolean explicit = s.speak.get(p);
        return explicit != null ? explicit : now < s.openMicUntil;
    }

    public synchronized boolean canShowCamera(ParticipantId p) {
        Derived s = d();
        if (s.kicked.contains(p)) {
            return false;
        }
        if (s.admins.contains(p)) {
            return true;
        }
        return s.camera.getOrDefault(p, s.cameraDefault);
    }

    public synchronized long openMicUntil() {
        return d().openMicUntil;
    }

    public synchronized boolean ended() {
        return d().ended;
    }

    /** Clock value for the next command this node issues: above every effective command (junk cannot inflate it). */
    public synchronized long nextLamport() {
        return d().maxEffective + 1;
    }

    /** SHA-256 over the sorted command ids; peers compare it to learn whether they need to sync. */
    public synchronized byte[] digest() {
        List<byte[]> sorted = new ArrayList<>();
        entries.values().forEach(e -> sorted.add(e.id()));
        sorted.sort(Arrays::compareUnsigned);
        ByteBuffer b = ByteBuffer.allocate(sorted.size() * 32);
        sorted.forEach(b::put);
        return Crypto.sha256(b.array());
    }

    /** Snapshot of every stored command, for handing to a syncing peer. */
    public synchronized Collection<AdminCommand> commands() {
        List<AdminCommand> out = new ArrayList<>(entries.size());
        entries.values().forEach(e -> out.add(e.cmd()));
        return out;
    }

    /**
     * Accepts a ticket only if it is genuine, for this meeting, from a current admin, inside its
     * validity window (with {@value JoinTicket#SKEW_MILLIS} ms of clock skew allowed before it
     * starts), short-lived, for a participant who is not kicked, in a meeting that has not ended.
     * The short lifetime is what keeps joins closed while no admin is online: nobody can mint a
     * fresh ticket and old ones lapse within a couple of minutes.
     */
    public synchronized boolean admit(JoinTicket t, long now) {
        if (!genesis.sameMeeting(t.meetingId()) || !t.verify()) {
            return false;
        }
        Derived s = d();
        return s.admins.contains(t.issuer())
                && now >= t.issuedAtMillis() - JoinTicket.SKEW_MILLIS
                && now <= t.expiresAtMillis()
                && t.expiresAtMillis() - t.issuedAtMillis() <= JoinTicket.MAX_LIFETIME_MILLIS
                && !s.kicked.contains(t.participant())
                && !s.ended;
    }

    private static final class Derived {
        ParticipantId host;
        final boolean cameraDefault;
        final Map<ParticipantId, List<Entry>> byIssuer;
        final Set<Entry> voided;
        final Set<Entry> newVoids = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Entry> effective = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ParticipantId> admins = new HashSet<>();
        final Map<ParticipantId, Long> adminSince = new HashMap<>();
        final Set<ParticipantId> kicked = new HashSet<>();
        final Map<ParticipantId, Boolean> speak = new HashMap<>();
        final Map<ParticipantId, Boolean> camera = new HashMap<>();
        long openMicUntil;
        long maxEffective;
        boolean ended;

        Derived(ParticipantId host, boolean cameraDefault, Map<ParticipantId, List<Entry>> byIssuer,
                Set<Entry> voided) {
            this.host = host;
            this.cameraDefault = cameraDefault;
            this.byIssuer = byIssuer;
            this.voided = voided;
            admins.add(host);
        }

        void apply(Entry e) {
            AdminCommand c = e.cmd();
            ParticipantId who = c.issuer(), t = c.target();
            if (ended || !admins.contains(who)) {
                return;
            }
            boolean isHost = who.equals(host);
            if (!isHost && c.lamport() <= adminSince.getOrDefault(who, 0L)) {
                return;
            }
            boolean ok = true;
            switch (c.kind()) {
                case GRANT_ADMIN -> {
                    if (kicked.contains(t)) {
                        ok = false;
                    } else if (admins.add(t)) {
                        adminSince.put(t, c.lamport());
                    }
                }
                case REVOKE_ADMIN -> {
                    if (isHost && !t.equals(host)) {
                        revoke(t, c);
                    } else {
                        ok = false;
                    }
                }
                case TRANSFER_HOST -> {
                    if (isHost && !kicked.contains(t)) {
                        admins.add(t);
                        adminSince.put(host, c.lamport());
                        adminSince.remove(t);
                        host = t;
                    } else {
                        ok = false;
                    }
                }
                case ALLOW_SPEAK -> speak.put(t, true);
                case DENY_SPEAK -> speak.put(t, false);
                case OPEN_MIC_ALL -> openMicUntil = c.untilMillis();
                case CLOSE_MIC_ALL -> openMicUntil = 0;
                case ALLOW_CAMERA -> camera.put(t, true);
                case DENY_CAMERA -> camera.put(t, false);
                case KICK -> {
                    if (!t.equals(host) && (isHost || !admins.contains(t))) {
                        revoke(t, c);
                        kicked.add(t);
                    } else {
                        ok = false;
                    }
                }
                case END_MEETING -> ended = true;
            }
            if (ok) {
                effective.add(e);
                maxEffective = Math.max(maxEffective, c.lamport());
            }
        }

        /** Removes {@code t}'s admin rights and voids what it signed since its grant that the revoker did not accept. */
        private void revoke(ParticipantId t, AdminCommand c) {
            if (admins.contains(t)) {
                Set<String> frozen = new HashSet<>();
                c.frozen().forEach(id -> frozen.add(HexFormat.of().formatHex(id)));
                long since = adminSince.getOrDefault(t, 0L);
                for (Entry y : byIssuer.getOrDefault(t, List.of())) {
                    long l = y.cmd().lamport();
                    if (l > since && l < c.lamport() && !frozen.contains(y.key()) && !voided.contains(y)) {
                        newVoids.add(y);
                    }
                }
            }
            admins.remove(t);
            adminSince.remove(t);
        }
    }
}
