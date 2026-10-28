package com.kartikey.p2pchat.conf.policy;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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

    public enum AddResult { ADDED, DUPLICATE, BAD_SIGNATURE, WRONG_MEETING }

    private record Entry(AdminCommand cmd, byte[] id) { }

    private static final Comparator<Entry> REPLAY_ORDER = Comparator
            .<Entry>comparingLong(e -> e.cmd().lamport())
            .thenComparing(e -> e.cmd().issuer())
            .thenComparing(Entry::id, Arrays::compareUnsigned);

    private final MeetingGenesis genesis;
    private final Map<String, Entry> entries = new HashMap<>();
    private long maxLamport;
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
        byte[] id = c.id();
        String key = HexFormat.of().formatHex(id);
        if (entries.containsKey(key)) {
            return AddResult.DUPLICATE;
        }
        if (!c.verify()) {
            return AddResult.BAD_SIGNATURE;
        }
        entries.put(key, new Entry(c, id));
        maxLamport = Math.max(maxLamport, c.lamport());
        derived = null;
        return AddResult.ADDED;
    }

    private Derived d() {
        if (derived == null) {
            List<Entry> sorted = new ArrayList<>(entries.values());
            sorted.sort(REPLAY_ORDER);
            derived = replay(sorted);
        }
        return derived;
    }

    private Derived replay(List<Entry> sorted) {
        Derived s = new Derived(genesis.host(), genesis.cameraAllowedByDefault());
        for (Entry e : sorted) {
            s.apply(e.cmd());
        }
        return s;
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

    /** Clock value for the next command this node issues: above everything seen so far. */
    public synchronized long nextLamport() {
        return maxLamport + 1;
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
        final Set<ParticipantId> admins = new HashSet<>();
        final Set<ParticipantId> kicked = new HashSet<>();
        final Map<ParticipantId, Boolean> speak = new HashMap<>();
        final Map<ParticipantId, Boolean> camera = new HashMap<>();
        long openMicUntil;
        boolean ended;

        Derived(ParticipantId host, boolean cameraDefault) {
            this.host = host;
            this.cameraDefault = cameraDefault;
            admins.add(host);
        }

        void apply(AdminCommand c) {
            ParticipantId who = c.issuer(), t = c.target();
            if (ended || !admins.contains(who)) {
                return;
            }
            boolean isHost = who.equals(host);
            switch (c.kind()) {
                case GRANT_ADMIN -> {
                    if (!kicked.contains(t)) {
                        admins.add(t);
                    }
                }
                case REVOKE_ADMIN -> {
                    if (isHost && !t.equals(host)) {
                        admins.remove(t);
                    }
                }
                case TRANSFER_HOST -> {
                    if (isHost && !kicked.contains(t)) {
                        admins.add(t);
                        host = t;
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
                        kicked.add(t);
                        admins.remove(t);
                    }
                }
                case END_MEETING -> ended = true;
            }
        }
    }
}
