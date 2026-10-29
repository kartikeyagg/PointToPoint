package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.audio.AudioMode;
import com.kartikey.p2pchat.conf.audio.LoudestSelector;
import com.kartikey.p2pchat.conf.keys.EpochBundle;
import com.kartikey.p2pchat.conf.keys.EpochSchedule;
import com.kartikey.p2pchat.conf.keys.KeyRing;
import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.I420;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import com.kartikey.p2pchat.conf.media.pipeline.AudioJitterBuffer;
import com.kartikey.p2pchat.conf.media.pipeline.MediaReceiver;
import com.kartikey.p2pchat.conf.media.pipeline.MediaSender;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.node.gossip.Contact;
import com.kartikey.p2pchat.conf.node.gossip.Gossip;
import com.kartikey.p2pchat.conf.node.gossip.Membership;
import com.kartikey.p2pchat.conf.node.gossip.PolicySync;
import com.kartikey.p2pchat.conf.node.tree.CrowdSession;
import com.kartikey.p2pchat.conf.node.tree.Forwarder;
import com.kartikey.p2pchat.conf.node.tree.TreeCoordinator;
import com.kartikey.p2pchat.conf.policy.AdminCommand;
import com.kartikey.p2pchat.conf.policy.JoinTicket;
import com.kartikey.p2pchat.conf.policy.MeetingGenesis;
import com.kartikey.p2pchat.conf.policy.PolicyState;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import com.kartikey.p2pchat.conf.ui.ConferenceController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * One participant in a meeting: ties the network, admin policy, keys, gossip, relay trees and
 * media together, and implements what the window needs.
 *
 * <p>Single-threaded by design: every method, network callback and {@link #tick} must run on the
 * same thread. {@link NodeRunner} provides that thread in the app; tests drive it directly over a
 * {@code MemoryFabric}, which makes multi-node runs deterministic.
 */
public final class ConferenceNode implements ConferenceController, PeerNetwork.Listener {

    /** What this machine offers the meeting. */
    public record Settings(String name, int uploadKbps, boolean canRelay, int stripes) {
        public static Settings of(String name) {
            return new Settings(name, 8_000, true, 4);
        }
    }

    public static final int AUDIO_TRACK = 0, CAMERA_TRACK = 1, SCREEN_TRACK = 2, CROWD_TRACK = 9;
    static final long ADMIN_PRESENCE_MILLIS = 15_000, PUBLISHER_PRESENCE_MILLIS = 30_000;
    static final long ADMIN_ONLINE_WINDOW = 45_000, JOIN_RETRY_MILLIS = 2_000, JOIN_GIVE_UP_MILLIS = 30_000;
    static final long UNVERIFIED_LINK_MILLIS = 10_000, SUBSCRIPTION_CHECK_MILLIS = 1_000;
    static final int MAX_ROSTER = 200_000, MAX_UNVERIFIED = 128, KEY_SHARD = 1_000;

    private enum State { JOINING, IN, ENDED }

    /** One stream we receive. */
    private final class Incoming {
        final ParticipantId source;
        final int track;
        final MediaReceiver rx;
        final AudioJitterBuffer jitter = new AudioJitterBuffer();
        Codecs.AudioDecoder audio;
        Codecs.VideoDecoder video;
        int layer;

        Incoming(ParticipantId source, int track) {
            this.source = source;
            this.track = track;
            this.rx = new MediaReceiver(source, track, e -> ring == null ? Optional.empty() : ring.key(e),
                    new MediaReceiver.Listener() {
                        @Override
                        public void onAudio(long seq, byte[] opus, int level) {
                            jitter.put(seq, opus, clock.getAsLong());
                            loudest.update(StreamIds.of(source, track), level, clock.getAsLong());
                        }

                        @Override
                        public void onVideo(int layer, EncodedFrame frame) {
                            showVideo(Incoming.this, frame);
                        }
                    });
        }
    }

    private final Identity me;
    private final PeerNetwork net;
    private final MediaIO media;
    private final Settings settings;
    private final LongSupplier clock;
    private final Random rnd;

    private State state;
    private Invite invite;
    private long joinStartedAt, lastJoinAttempt;
    private String title;
    private MeetingGenesis genesis;
    private byte[] meetingId;
    private PolicyState policy;
    private KeyRing ring;
    private JoinTicket myTicket;
    private Membership membership;
    private Gossip gossip;
    private PolicySync sync;
    private Forwarder forwarder;
    private TreeCoordinator coordinator;

    private final Map<ParticipantId, Presence> roster = new LinkedHashMap<>();
    private final Set<ParticipantId> admitted = new HashSet<>();
    private final Set<ParticipantId> verified = new HashSet<>();
    private final Map<ParticipantId, Long> unverified = new LinkedHashMap<>();
    private final Map<ParticipantId, Presence> waitingHellos = new LinkedHashMap<>();
    private final Map<Long, List<EpochBundle>> bundles = new LinkedHashMap<>();
    private final Map<Long, Incoming> incoming = new HashMap<>();
    private final LoudestSelector loudest = new LoudestSelector(3, 1_500);
    /** Last tree message or media per peer: links in active tree use survive membership churn. */
    private final Map<ParticipantId, Long> treeActivity = new HashMap<>();
    /** Kicked peers whose links close at the given time, after they were told. */
    private final Map<ParticipantId, Long> closing = new HashMap<>();
    static final long CLOSE_AFTER_REMOVAL_MILLIS = 2_000;
    static final long TREE_LINK_IDLE_MILLIS = 30_000;

    private long lastRotation = -1, lastPresence = -1, lastSubscriptionCheck, lastSenderTick, lastAudioTick;
    private boolean kickPending, announcedToPeers;
    /** XOR of the roster's id hashes: two equal rosters have equal digests, so syncs are cheap when nothing changed. */
    private long rosterDigest, lastRosterSync;
    static final long ROSTER_SYNC_MILLIS = 10_000, ROSTER_SYNC_LARGE_MILLIS = 300_000, ROSTER_RESYNC_MILLIS = 2_000;
    private int lastFlags = -1;

    private boolean micOn, cameraOn, screenOn;
    private List<ParticipantId> gallery = List.of();
    private MediaSender audioOut, videoOut;
    private Codecs.AudioEncoder audioEncoder;
    private CrowdSession crowd;
    private ParticipantId crowdRoot;
    private short[] crowdFinal;
    private short[] lastMicFrame;
    private ConferenceView view;

    private ConferenceNode(Identity me, PeerNetwork net, MediaIO media, Settings settings, LongSupplier clock, long seed) {
        this.me = me;
        this.net = net;
        this.media = media;
        this.settings = settings;
        this.clock = clock;
        this.rnd = new Random(seed);
        net.setListener(this);
    }

    /** Starts a new meeting with us as host. */
    public static ConferenceNode host(Identity me, PeerNetwork net, MediaIO media, Settings settings, String title,
                                      LongSupplier clock) {
        ConferenceNode n = new ConferenceNode(me, net, media, settings, clock, Arrays.hashCode(me.id().encoded()));
        long now = clock.getAsLong();
        n.title = title == null || title.isBlank() ? "Meeting" : title.strip();
        n.start(MeetingGenesis.create(me, true, now));
        n.myTicket = JoinTicket.issue(me, n.meetingId, me.id(), now);
        n.admitted.add(me.id());
        n.rotateKey(now);
        n.announce(now);
        return n;
    }

    /** Asks the invite's admin to let us in; {@link ConferenceView#onNotice} reports progress. */
    public static ConferenceNode join(Identity me, PeerNetwork net, MediaIO media, Settings settings, Invite invite,
                                      LongSupplier clock) {
        ConferenceNode n = new ConferenceNode(me, net, media, settings, clock, Arrays.hashCode(me.id().encoded()));
        n.state = State.JOINING;
        n.invite = invite;
        n.title = invite.title();
        n.joinStartedAt = clock.getAsLong();
        n.lastJoinAttempt = n.joinStartedAt;
        net.connect(invite.admin(), invite.candidates(), null);
        return n;
    }

    private void start(MeetingGenesis g) {
        genesis = g;
        meetingId = g.meetingId();
        policy = new PolicyState(g);
        ring = new KeyRing(meetingId);
        Contact self = selfContact();
        membership = new Membership(new SharedLinks(), self, rnd);
        gossip = new Gossip(net, membership::activePeers, this::validItem, this::onItem, rnd);
        sync = new PolicySync(net, membership::activePeers, policy::digest, policy::commands, this::addCommand,
                ring::currentEpoch, this::bundleFor, () -> fingerprint(me.id()), this::acceptBundle, rnd);
        forwarder = new Forwarder(net, clock, Forwarder.Config.defaults());
        forwarder.setCandidates(self.encode());
        coordinator = new TreeCoordinator(net, coordinatorConfig(), me.id()::equals);
        coordinator.setLocalHandler(forwarder::onControl);
        coordinator.setLocalCandidates(self.encode());
        audioEncoder = media.newAudioEncoder();
        state = State.IN;
    }

    private TreeCoordinator.Config coordinatorConfig() {
        TreeCoordinator.Config d = TreeCoordinator.Config.defaults();
        return new TreeCoordinator.Config(settings.stripes(), d.stripeKbps(), d.maxFanout(), settings.uploadKbps(),
                d.maxStreams(), d.maxMembersPerStream(), d.backups());
    }

    // ---------------------------------------------------------------- network callbacks

    @Override
    public void onConnected(ParticipantId peer) {
        long now = clock.getAsLong();
        if (state == State.JOINING) {
            if (peer.equals(invite.admin())) {
                net.sendControl(peer, NodeMessages.joinReq(new NodeMessages.JoinReq(selfContact(), settings.name())));
            }
            return;
        }
        if (state != State.IN) {
            return;
        }
        if (policy.isKicked(peer)) {
            net.disconnect(peer);
            return;
        }
        if (!verified.contains(peer)) {
            if (unverified.size() >= MAX_UNVERIFIED) {
                net.disconnect(peer);
                return;
            }
            unverified.put(peer, now);
        }
        // HELLO goes first: control is ordered, so the peer verifies us before any other message.
        net.sendControl(peer, NodeMessages.hello(currentPresence(now, true)));
        membership.onConnected(peer);
        gossip.onConnected(peer);
        forwarder.onConnected(peer);
    }

    @Override
    public void onDisconnected(ParticipantId peer) {
        unverified.remove(peer);
        verified.remove(peer);
        if (state != State.IN) {
            return;
        }
        membership.onDisconnected(peer);
        gossip.onDisconnected(peer);
        sync.onDisconnected(peer);
        forwarder.onDisconnected(peer);
        coordinator.onDisconnected(peer);
    }

    @Override
    public void onControl(ParticipantId from, byte[] msg) {
        try {
            if (ControlTypes.inRange(msg, ControlTypes.NODE_FIRST, ControlTypes.NODE_LAST)) {
                onNodeMessage(from, msg);
                return;
            }
            if (state != State.IN || !verified.contains(from)) {
                return; // nothing but HELLO / JOIN_REQ from someone we haven't admitted
            }
            if (ControlTypes.inRange(msg, ControlTypes.GOSSIP_FIRST, ControlTypes.GOSSIP_LAST)) {
                if (!membership.onControl(from, msg) && !gossip.onControl(from, msg)) {
                    sync.onControl(from, msg);
                }
            } else if (ControlTypes.inRange(msg, ControlTypes.TREE_FIRST, ControlTypes.TREE_LAST)) {
                treeActivity.put(from, clock.getAsLong());
                if (!coordinator.handle(from, msg)) {
                    forwarder.onControl(from, msg);
                }
            }
        } catch (IllegalArgumentException malformed) {
            // remote input: ignore
        }
    }

    @Override
    public void onMedia(ParticipantId from, byte[] packet) {
        if (state == State.IN && verified.contains(from)) {
            treeActivity.put(from, clock.getAsLong());
            forwarder.onMedia(from, packet);
        }
    }

    private void onNodeMessage(ParticipantId from, byte[] msg) {
        long now = clock.getAsLong();
        switch (msg[0] & 0xff) {
            case NodeMessages.JOIN_REQ -> {
                if (state == State.IN) {
                    answerJoin(from, NodeMessages.joinReq(msg), now);
                }
            }
            case NodeMessages.JOIN_OK -> {
                if (state == State.JOINING && from.equals(invite.admin())) {
                    joined(NodeMessages.joinOk(msg), now);
                }
            }
            case NodeMessages.JOIN_DENIED -> {
                if (state == State.JOINING && from.equals(invite.admin())) {
                    end("Join refused: " + NodeMessages.joinDenied(msg));
                }
            }
            case NodeMessages.HELLO -> {
                if (state == State.IN) {
                    onHello(from, NodeMessages.hello(msg), now);
                }
            }
            case NodeMessages.REMOVED -> {
                if (state == State.IN) {
                    addCommand(AdminCommand.decode(NodeMessages.removedCommand(msg))); // verified by the policy
                }
            }
            case NodeMessages.ROSTER_REQ -> {
                NodeMessages.RosterDigest d = NodeMessages.rosterReq(msg);
                if (state == State.IN && verified.contains(from)
                        && (d.count() != roster.size() || d.digest() != rosterDigest)) {
                    sendRoster(from);
                    // push-pull: they may know people we don't
                    net.sendControl(from, NodeMessages.rosterReq(roster.size(), rosterDigest));
                }
            }
            case NodeMessages.ROSTER -> {
                if (state == State.IN && verified.contains(from)) {
                    int before = roster.size();
                    for (Presence p : NodeMessages.roster(msg)) {
                        if (!roster.containsKey(p.id()) && p.verify() && isMember(p, now)) {
                            remember(p);
                        }
                    }
                    if (roster.size() > before) {
                        lastRosterSync = Math.min(lastRosterSync, now - ROSTER_SYNC_MILLIS + ROSTER_RESYNC_MILLIS);
                    }
                }
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- joining

    private void answerJoin(ParticipantId from, NodeMessages.JoinReq req, long now) {
        if (!policy.isAdmin(me.id()) || !req.contact().id().equals(from)) {
            return;
        }
        if (policy.ended() || policy.isKicked(from)) {
            net.sendControl(from, NodeMessages.joinDenied(policy.ended() ? "the meeting has ended" : "you were removed"));
            return;
        }
        JoinTicket ticket = JoinTicket.issue(me, meetingId, from, now);
        admitted.add(from);
        long epoch = ring.currentEpoch();
        EpochBundle key = epoch < 0 ? null : EpochBundle.create(me, meetingId, epoch, ring.key(epoch).orElseThrow(),
                List.of(from), 1, now).get(0);
        List<Contact> contacts = new ArrayList<>(membership.activeContacts());
        contacts.addAll(membership.passiveContacts());
        Collections.shuffle(contacts, rnd);
        contacts.add(0, selfContact());
        // spreading newcomers over many entry points keeps a meeting-start rush from splitting the overlay
        net.sendControl(from, NodeMessages.joinOk(new NodeMessages.JoinOk(genesis, ticket, key,
                contacts.subList(0, Math.min(NodeMessages.MAX_CONTACTS, contacts.size())))));
    }

    private void joined(NodeMessages.JoinOk ok, long now) {
        if (!ok.genesis().verify() || !ok.ticket().verify() || !ok.ticket().participant().equals(me.id())
                || !ok.ticket().issuer().equals(invite.admin()) || !Arrays.equals(ok.genesis().meetingId(), invite.meetingId())) {
            end("The admin's answer did not check out");
            return;
        }
        start(ok.genesis());
        myTicket = ok.ticket();
        admitted.add(me.id());
        admitted.add(invite.admin());
        verified.add(invite.admin());
        if (ok.key() != null) {
            acceptBundle(ok.key());
        }
        List<Contact> contacts = new ArrayList<>(ok.contacts());
        Contact entry = contacts.size() > 1 ? contacts.get(1 + rnd.nextInt(contacts.size() - 1)) : contacts.get(0);
        net.sendControl(invite.admin(), NodeMessages.hello(currentPresence(now, true)));
        membership.join(entry);
        announce(now);
        notice("Joined " + title);
    }

    private void onHello(ParticipantId from, Presence p, long now) {
        if (!p.id().equals(from) || !p.verify()) {
            net.disconnect(from);
            return;
        }
        if (!isMember(p, now)) {
            if (waitingHellos.size() < MAX_UNVERIFIED) {
                waitingHellos.put(from, p); // the grant of the admin who let them in may still be on its way
            }
            return;
        }
        unverified.remove(from);
        verified.add(from);
        remember(p);
    }

    /**
     * Admitted earlier, or carrying a ticket signed by a current admin. A ticket's short lifetime
     * only governs getting in (an online admin must answer the join request); afterwards it is
     * proof of membership for as long as the meeting runs, so later announcements and late joiners
     * can still check it.
     */
    private boolean isMember(Presence p, long now) {
        if (policy.isKicked(p.id())) {
            return false;
        }
        if (admitted.contains(p.id()) || policy.isAdmin(p.id())) {
            return true;
        }
        JoinTicket t = p.ticket();
        if (t != null && t.participant().equals(p.id()) && Arrays.equals(t.meetingId(), meetingId) && t.verify()
                && policy.isAdmin(t.issuer()) && t.issuedAtMillis() <= now + 5_000 && !policy.ended()) {
            admitted.add(p.id());
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- gossip items

    private boolean validItem(Gossip.Item item) {
        long now = clock.getAsLong();
        try {
            return switch (item.kind()) {
                case Gossip.Item.ADMIN_COMMAND -> AdminCommand.decode(item.body()).verify();
                case Gossip.Item.KEY_BUNDLE -> {
                    EpochBundle b = EpochBundle.decode(item.body());
                    yield b.verify() && policy.isAdmin(b.issuer());
                }
                case Gossip.Item.PRESENCE -> {
                    Presence p = Presence.decode(item.body());
                    yield p.verify() && Math.abs(p.atMillis() - now) < 300_000 && isMember(p, now);
                }
                case Gossip.Item.CHAT -> {
                    ChatLine c = ChatLine.decode(item.body());
                    yield c.verify() && admitted.contains(c.author()) && !policy.isKicked(c.author());
                }
                default -> false;
            };
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void onItem(Gossip.Item item) {
        switch (item.kind()) {
            case Gossip.Item.ADMIN_COMMAND -> addCommand(AdminCommand.decode(item.body()));
            case Gossip.Item.KEY_BUNDLE -> acceptBundle(EpochBundle.decode(item.body()));
            case Gossip.Item.PRESENCE -> remember(Presence.decode(item.body()));
            case Gossip.Item.CHAT -> {
                ChatLine c = ChatLine.decode(item.body());
                if (view != null) {
                    view.onChat(c.author(), nameOf(c.author()), c.text(), c.atMillis());
                }
            }
            default -> { }
        }
    }

    private void addCommand(AdminCommand c) {
        if (policy.add(c) != PolicyState.AddResult.ADDED) {
            return;
        }
        if (c.kind() == AdminCommand.Kind.KICK) {
            kickPending = true;
        }
        if (policy.isKicked(me.id())) {
            end("You were removed from the meeting");
            return;
        }
        if (policy.ended()) {
            end("The meeting has ended");
            return;
        }
        for (ParticipantId p : net.connected()) {
            if (policy.isKicked(p) && !closing.containsKey(p)) {
                // tell them first (they check the signature themselves), then close the link
                net.sendControl(p, NodeMessages.removed(c.encode()));
                closing.put(p, clock.getAsLong() + CLOSE_AFTER_REMOVAL_MILLIS);
            }
        }
        long now = clock.getAsLong();
        for (Map.Entry<ParticipantId, Presence> e : new ArrayList<>(waitingHellos.entrySet())) {
            if (isMember(e.getValue(), now)) {
                waitingHellos.remove(e.getKey());
                onHello(e.getKey(), e.getValue(), now);
            }
        }
        lastSubscriptionCheck = 0; // speakers / crowd mode may have changed: act on the next tick
        if (view != null) {
            view.onPolicyChanged();
        }
    }

    private void acceptBundle(EpochBundle b) {
        KeyRing.Result r = ring.accept(b, me, policy::isAdmin);
        if (r == KeyRing.Result.ACCEPTED || r == KeyRing.Result.REPLACED || r == KeyRing.Result.NOT_RECIPIENT
                || r == KeyRing.Result.DUPLICATE) {
            List<EpochBundle> list = bundles.computeIfAbsent(b.epoch(), e -> new ArrayList<>());
            if (list.size() < 256) {
                list.add(b);
            }
            while (bundles.size() > 3) {
                bundles.remove(bundles.keySet().iterator().next());
            }
        }
    }

    private Optional<EpochBundle> bundleFor(Long epoch, byte[] fingerprint) {
        for (EpochBundle b : bundles.getOrDefault(epoch, List.of())) {
            for (EpochBundle.Entry e : b.entries()) {
                if (Arrays.equals(e.fingerprint(), fingerprint)) {
                    return Optional.of(b);
                }
            }
        }
        return Optional.empty();
    }

    private void remember(Presence p) {
        if (p.has(Presence.LEAVING)) {
            if (roster.remove(p.id()) != null) {
                rosterDigest ^= idHash(p.id());
            }
        } else {
            Presence old = roster.get(p.id());
            if (old == null || old.atMillis() <= p.atMillis()) {
                if (old == null && roster.size() >= MAX_ROSTER) {
                    return;
                }
                if (old == null) {
                    rosterDigest ^= idHash(p.id());
                }
                roster.put(p.id(), p);
            }
        }
        if (view != null) {
            view.onParticipantsChanged();
        }
    }

    /** The newest presences we know (admins and publishers first), in pages that fit a control message. */
    private void sendRoster(ParticipantId to) {
        List<Presence> all = new ArrayList<>(roster.values());
        all.sort(Comparator.comparing((Presence p) -> !(policy.isAdmin(p.id()) || p.flags() != 0))
                .thenComparing(Presence::atMillis, Comparator.reverseOrder()));
        List<Presence> page = new ArrayList<>();
        int bytes = 0;
        for (Presence p : all.subList(0, Math.min(NodeMessages.MAX_ROSTER_SYNC, all.size()))) {
            int len = p.encode().length + 2;
            if (bytes + len > NodeMessages.MAX_ROSTER_PAGE_BYTES) {
                net.sendControl(to, NodeMessages.roster(page));
                page = new ArrayList<>();
                bytes = 0;
            }
            page.add(p);
            bytes += len;
        }
        if (!page.isEmpty()) {
            net.sendControl(to, NodeMessages.roster(page));
        }
    }

    // ---------------------------------------------------------------- timer

    /** Call every ~10–20 ms on the node's thread. */
    public void tick(long now) {
        if (state == State.JOINING) {
            if (now - joinStartedAt > JOIN_GIVE_UP_MILLIS) {
                end("Could not reach the meeting's admin");
            } else if (now - lastJoinAttempt > JOIN_RETRY_MILLIS) {
                lastJoinAttempt = now;
                net.connect(invite.admin(), invite.candidates(), null);
                if (net.connected().contains(invite.admin())) {
                    onConnected(invite.admin());
                }
            }
            return;
        }
        if (state != State.IN) {
            return;
        }
        closing.entrySet().removeIf(e -> {
            boolean due = now >= e.getValue();
            if (due) {
                net.disconnect(e.getKey());
            }
            return due;
        });
        membership.tick(now);
        gossip.tick(now);
        sync.tick(now);
        forwarder.tick(now);
        unverified.entrySet().removeIf(e -> {
            boolean stale = now - e.getValue() > UNVERIFIED_LINK_MILLIS;
            if (stale) {
                net.disconnect(e.getKey());
            }
            return stale;
        });
        if (isKeyMaster(now) && (lastRotation < 0 || now - lastRotation >= EpochSchedule.ROTATE_MILLIS || kickPending)) {
            rotateKey(now);
        }
        kickPending = false;
        if (!announcedToPeers && !membership.activePeers().isEmpty()) {
            // the first announcement may have gone out before we had anyone to gossip it to
            announcedToPeers = true;
            announce(now);
            lastRosterSync = Long.MIN_VALUE / 2; // sync right away
        }
        long syncEvery = roster.size() < NodeMessages.MAX_ROSTER_SYNC ? ROSTER_SYNC_MILLIS : ROSTER_SYNC_LARGE_MILLIS;
        if (announcedToPeers && now - lastRosterSync >= syncEvery) {
            // anti-entropy: gossip repair only covers recent items, so compare rosters with a random neighbour
            lastRosterSync = now;
            List<ParticipantId> peers = membership.activePeers();
            if (!peers.isEmpty()) {
                net.sendControl(peers.get(rnd.nextInt(peers.size())), NodeMessages.rosterReq(roster.size(), rosterDigest));
            }
        }
        maybeAnnounce(now);
        if (now - lastSubscriptionCheck >= SUBSCRIPTION_CHECK_MILLIS) {
            lastSubscriptionCheck = now;
            updateSubscriptions(now);
            ensureSourceLinks();
            treeActivity.values().removeIf(t -> now - t >= TREE_LINK_IDLE_MILLIS);
        }
        sendMedia(now);
        if (crowd != null) {
            crowd.tick(now);
        }
        if (now - lastAudioTick >= MediaFormat.FRAME_MILLIS) {
            lastAudioTick = now;
            playAudio(now);
        }
    }

    // ---------------------------------------------------------------- keys

    /** The host if online, otherwise the online admin whose id sorts lowest: one rotator, no fights. */
    private boolean isKeyMaster(long now) {
        if (!policy.isAdmin(me.id())) {
            return false;
        }
        if (policy.host().equals(me.id())) {
            return true;
        }
        if (online(policy.host(), now)) {
            return false;
        }
        for (ParticipantId a : policy.admins()) {
            if (online(a, now) && a.compareTo(me.id()) < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean online(ParticipantId p, long now) {
        if (p.equals(me.id())) {
            return true;
        }
        Presence pr = roster.get(p);
        return pr != null && now - pr.atMillis() < ADMIN_ONLINE_WINDOW;
    }

    private void rotateKey(long now) {
        long epoch = Math.max(ring.currentEpoch(), 0) + 1;
        List<ParticipantId> recipients = new ArrayList<>();
        for (ParticipantId p : admitted) {
            if (!policy.isKicked(p)) {
                recipients.add(p);
            }
        }
        for (EpochBundle b : EpochBundle.create(me, meetingId, epoch, EpochSchedule.newKey(), recipients, KEY_SHARD, now)) {
            acceptBundle(b);
            gossip.publish(new Gossip.Item(Gossip.Item.KEY_BUNDLE, b.encode()));
        }
        lastRotation = now;
    }

    // ---------------------------------------------------------------- presence

    private int flags() {
        int f = 0;
        if (micOn && audioOut != null) {
            f |= Presence.PUB_AUDIO;
        }
        if (cameraOn) {
            f |= Presence.PUB_CAMERA;
        }
        if (screenOn) {
            f |= Presence.PUB_SCREEN;
        }
        return f;
    }

    private Presence currentPresence(long now, boolean withTicket) {
        return Presence.sign(me, settings.name(), flags(), selfContact(), now, withTicket ? myTicket : null);
    }

    private void announce(long now) {
        Presence p = currentPresence(now, true);
        remember(p);
        gossip.publish(new Gossip.Item(Gossip.Item.PRESENCE, p.encode()));
        lastPresence = now;
        lastFlags = p.flags();
    }

    private void maybeAnnounce(long now) {
        long period = policy.isAdmin(me.id()) ? ADMIN_PRESENCE_MILLIS
                : flags() != 0 ? PUBLISHER_PRESENCE_MILLIS : Long.MAX_VALUE;
        if (flags() != lastFlags || (period != Long.MAX_VALUE && now - lastPresence >= period)) {
            announce(now);
        }
    }

    // ---------------------------------------------------------------- media out

    private void sendMedia(long now) {
        boolean crowdMode = AudioMode.of(policy.openMicUntil(), now) == AudioMode.CROWD;
        short[] pcm = media.pollMic();
        lastMicFrame = pcm;
        boolean talk = micOn && pcm != null && !crowdMode && policy.canSpeak(me.id(), now);
        if (talk) {
            if (audioOut == null) {
                audioOut = newSender(AUDIO_TRACK, MediaHeader.Kind.AUDIO);
            }
            audioOut.sendAudio(audioEncoder.encode(pcm), pcm);
        }
        if (cameraOn && policy.canShowCamera(me.id())) {
            for (EncodedFrame[] layers : media.pollVideo()) {
                if (videoOut == null) {
                    videoOut = newSender(CAMERA_TRACK, MediaHeader.Kind.VIDEO);
                }
                for (int l = 0; l < layers.length; l++) {
                    if (layers[l] != null) {
                        videoOut.sendVideo(l, layers[l]);
                    }
                }
            }
        } else {
            media.pollVideo();
        }
        if (now - lastSenderTick >= 40) {
            lastSenderTick = now;
            if (audioOut != null) {
                audioOut.tick();
            }
            if (videoOut != null) {
                videoOut.tick();
            }
        }
    }

    private MediaSender newSender(int track, MediaHeader.Kind kind) {
        long streamId = StreamIds.of(me.id(), track);
        forwarder.publish(streamId, me.id());
        return new MediaSender(me, track, kind, settings.stripes(), ring::currentEpoch, ring::key, packet -> {
            if (MediaHeader.decode(packet).kind() == MediaHeader.Kind.MANIFEST) {
                forwarder.sendManifest(packet, settings.stripes());
            } else {
                forwarder.send(packet);
            }
        });
    }

    // ---------------------------------------------------------------- media in

    private void updateSubscriptions(long now) {
        Map<Long, Integer> want = new HashMap<>();   // stream -> layer
        Map<Long, ParticipantId> sources = new HashMap<>();
        boolean crowdMode = AudioMode.of(policy.openMicUntil(), now) == AudioMode.CROWD;
        for (Presence p : roster.values()) {
            if (p.id().equals(me.id())) {
                continue;
            }
            if (!crowdMode && p.has(Presence.PUB_AUDIO) && policy.canSpeak(p.id(), now)) {
                long s = StreamIds.of(p.id(), AUDIO_TRACK);
                want.put(s, 0);
                sources.put(s, p.id());
            }
        }
        ParticipantId speaker = activeSpeaker();
        for (ParticipantId g : gallery) {
            Presence p = roster.get(g);
            if (p != null && p.has(Presence.PUB_CAMERA) && !g.equals(me.id())) {
                long s = StreamIds.of(g, CAMERA_TRACK);
                want.put(s, g.equals(speaker) ? 2 : 0);
                sources.put(s, g);
            }
        }
        for (Long s : new ArrayList<>(incoming.keySet())) {
            if (!want.containsKey(s)) {
                forwarder.unsubscribe(s);
                closeIncoming(incoming.remove(s));
            }
        }
        for (Map.Entry<Long, Integer> e : want.entrySet()) {
            Incoming in = incoming.get(e.getKey());
            if (in == null) {
                ParticipantId src = sources.get(e.getKey());
                Contact c = roster.get(src).contact();
                net.connect(src, c.candidates(), null);
                int track = (int) (e.getKey() & 0xff);
                in = new Incoming(src, track);
                in.layer = e.getValue();
                if (track == AUDIO_TRACK) {
                    in.audio = media.newAudioDecoder();
                } else {
                    in.video = media.newVideoDecoder();
                }
                Incoming target = in;
                incoming.put(e.getKey(), in);
                forwarder.subscribe(e.getKey(), src, src, in.layer, settings.uploadKbps(), settings.canRelay(),
                        (h, packet) -> target.rx.onPacket(packet, clock.getAsLong()));
            } else if (in.layer != e.getValue()) {
                in.layer = e.getValue();
                forwarder.setMaxLayer(e.getKey(), in.layer);
            }
        }
        updateCrowd(crowdMode);
    }

    /** Crowd mode: everyone's mic flows up the tree of the admin who opened the mics. */
    private void updateCrowd(boolean crowdMode) {
        ParticipantId root = crowdMode ? policy.host() : null;
        if (crowdMode && !online(root, clock.getAsLong())) {
            root = policy.admins().stream().filter(a -> online(a, clock.getAsLong()))
                    .min(Comparator.naturalOrder()).orElse(null);
        }
        if (root == null ? crowd == null : root.equals(crowdRoot)) {
            return;
        }
        if (crowd != null) {
            forwarder.unsubscribe(StreamIds.of(crowdRoot, CROWD_TRACK));
            crowd = null;
        }
        crowdRoot = root;
        if (root == null) {
            return;
        }
        long streamId = StreamIds.of(root, CROWD_TRACK);
        crowd = new CrowdSession(me, net, forwarder, streamId, MediaFormat.FRAME_SAMPLES, settings.stripes(),
                media.newAudioEncoder(), media::newAudioDecoder,
                () -> micOn ? lastMicFrame : null, policy::openMicUntil, f -> crowdFinal = f.pcm());
        if (root.equals(me.id())) {
            forwarder.publish(streamId, me.id());
        } else {
            Presence p = roster.get(root);
            if (p != null) {
                net.connect(root, p.contact().candidates(), null);
            }
            forwarder.subscribe(streamId, root, root, 0, settings.uploadKbps(), settings.canRelay(), crowd);
        }
    }

    private void playAudio(long now) {
        int[] sum = null;
        for (Incoming in : incoming.values()) {
            if (in.audio == null) {
                continue;
            }
            byte[] packet = in.jitter.poll(now);
            if (in.jitter.isBuffering()) {
                continue;
            }
            short[] pcm = in.audio.decode(packet);
            sum = add(sum, pcm);
        }
        if (crowdFinal != null) {
            sum = add(sum, crowdFinal);
            crowdFinal = null;
        }
        if (sum != null) {
            short[] out = new short[sum.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
            }
            media.play(out);
        }
    }

    private static int[] add(int[] sum, short[] pcm) {
        int[] s = sum == null ? new int[pcm.length] : sum;
        for (int i = 0; i < Math.min(s.length, pcm.length); i++) {
            s[i] += pcm[i];
        }
        return s;
    }

    private void showVideo(Incoming in, EncodedFrame frame) {
        if (in.video == null) {
            return;
        }
        VideoFrame f = in.video.decode(frame);
        if (f == null) {
            if (in.rx.needsKeyframe(in.layer)) {
                // keyframes also come every 2 s on their own; nothing to send upstream yet
                return;
            }
            return;
        }
        if (view != null) {
            view.onVideo(in.source, I420.toImage(f));
        }
    }

    private ParticipantId activeSpeaker() {
        List<Long> loud = loudest.selected(clock.getAsLong());
        for (Long s : loud) {
            Incoming in = incoming.get(s);
            if (in != null) {
                return in.source;
            }
        }
        return null;
    }

    private void closeIncoming(Incoming in) {
        if (in == null) {
            return;
        }
        if (in.audio != null) {
            in.audio.close();
        }
        if (in.video != null) {
            in.video.close();
        }
    }

    // ---------------------------------------------------------------- shared links

    /**
     * The membership layer's view of the network. Gossip neighbours and relay-tree links share the
     * same physical links, so when membership drops a neighbour the link only closes if no tree role
     * (parent, child, stream source) still needs it.
     */
    private final class SharedLinks implements PeerNetwork {
        @Override public ParticipantId self() { return net.self(); }
        @Override public List<java.net.InetSocketAddress> candidates() { return net.candidates(); }
        @Override public void connect(ParticipantId p, List<java.net.InetSocketAddress> c, ParticipantId via) { net.connect(p, c, via); }
        @Override public Set<ParticipantId> connected() { return net.connected(); }
        @Override public boolean sendMedia(ParticipantId p, byte[] packet) { return net.sendMedia(p, packet); }
        @Override public boolean sendControl(ParticipantId p, byte[] message) { return net.sendControl(p, message); }
        @Override public LinkStats stats(ParticipantId p) { return net.stats(p); }
        @Override public void setListener(Listener l) { throw new UnsupportedOperationException(); }
        @Override public void close() { }

        @Override
        public void disconnect(ParticipantId p) {
            if (!treeNeeds(p)) {
                net.disconnect(p);
            }
        }
    }

    private boolean treeNeeds(ParticipantId p) {
        Long last = treeActivity.get(p);
        if (last != null && clock.getAsLong() - last < TREE_LINK_IDLE_MILLIS) {
            return true;
        }
        List<Long> streams = new ArrayList<>(incoming.keySet());
        if (audioOut != null) {
            streams.add(audioOut.streamId());
        }
        if (videoOut != null) {
            streams.add(videoOut.streamId());
        }
        if (crowdRoot != null) {
            streams.add(StreamIds.of(crowdRoot, CROWD_TRACK));
            if (p.equals(crowdRoot)) {
                return true;
            }
        }
        for (Incoming in : incoming.values()) {
            if (in.source.equals(p)) {
                return true;
            }
        }
        for (long s : streams) {
            for (int k = 0; k < settings.stripes(); k++) {
                if (p.equals(forwarder.parentOf(s, k)) || forwarder.childrenOf(s, k).contains(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Keeps links to the sources we listen to: they coordinate their trees, and repairs go through them. */
    private void ensureSourceLinks() {
        Set<ParticipantId> up = net.connected();
        List<ParticipantId> sources = new ArrayList<>();
        for (Incoming in : incoming.values()) {
            sources.add(in.source);
        }
        if (crowdRoot != null && !crowdRoot.equals(me.id())) {
            sources.add(crowdRoot);
        }
        for (ParticipantId src : sources) {
            Presence p = roster.get(src);
            if (!up.contains(src) && p != null && !policy.isKicked(src)) {
                net.connect(src, p.contact().candidates(), null);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private Contact selfContact() {
        return new Contact(me.id(), net.candidates(), settings.uploadKbps(), settings.canRelay());
    }

    private static long idHash(ParticipantId p) {
        return java.nio.ByteBuffer.wrap(fingerprint(p)).getLong();
    }

    private static byte[] fingerprint(ParticipantId p) {
        return java.util.HexFormat.of().parseHex(p.fingerprint());
    }

    private String nameOf(ParticipantId p) {
        Presence pr = roster.get(p);
        return pr == null ? p.shortId() : pr.name();
    }

    private void notice(String s) {
        if (view != null) {
            view.onNotice(s);
        }
    }

    private void end(String reason) {
        if (state == State.ENDED) {
            return;
        }
        boolean wasIn = state == State.IN;
        state = State.ENDED;
        if (wasIn) {
            forwarder.leave();
            gossip.publish(new Gossip.Item(Gossip.Item.PRESENCE,
                    Presence.sign(me, settings.name(), Presence.LEAVING, selfContact(), clock.getAsLong(), null).encode()));
        }
        for (Incoming in : incoming.values()) {
            closeIncoming(in);
        }
        incoming.clear();
        if (audioEncoder != null) {
            audioEncoder.close();
        }
        if (view != null) {
            view.onEnded(reason);
        }
    }

    // ---------------------------------------------------------------- ConferenceController

    @Override
    public ParticipantId self() {
        return me.id();
    }

    @Override
    public String meetingTitle() {
        return title;
    }

    @Override
    public String inviteCode() {
        if (state != State.IN || !policy.isAdmin(me.id())) {
            return "";
        }
        List<java.net.InetSocketAddress> addrs = net.candidates();
        return new Invite(meetingId, me.id(), title, addrs).encode();
    }

    @Override
    public List<ParticipantInfo> participants() {
        if (state != State.IN) {
            return List.of();
        }
        long now = clock.getAsLong();
        Set<Long> loud = new HashSet<>(loudest.selected(now));
        List<ParticipantInfo> out = new ArrayList<>(roster.size());
        for (Presence p : roster.values()) {
            out.add(new ParticipantInfo(p.id(), p.name(), policy.isAdmin(p.id()), policy.host().equals(p.id()),
                    policy.canSpeak(p.id(), now), p.has(Presence.PUB_CAMERA),
                    loud.contains(StreamIds.of(p.id(), AUDIO_TRACK))));
        }
        out.sort(Comparator.comparing((ParticipantInfo i) -> !i.host()).thenComparing(i -> !i.admin())
                .thenComparing(ParticipantInfo::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Override
    public boolean isAdmin() {
        return state == State.IN && policy.isAdmin(me.id());
    }

    @Override
    public boolean isHost() {
        return state == State.IN && policy.host().equals(me.id());
    }

    @Override
    public boolean canSpeak() {
        return state == State.IN && policy.canSpeak(me.id(), clock.getAsLong());
    }

    @Override
    public long openMicUntil() {
        return state == State.IN && policy.openMicUntil() > clock.getAsLong() ? policy.openMicUntil() : 0;
    }

    @Override
    public Health health() {
        long now = clock.getAsLong();
        int peers = 0, rtt = 0;
        double loss = 0;
        long up = 0, down = 0;
        for (ParticipantId p : net.connected()) {
            PeerNetwork.LinkStats s = net.stats(p);
            if (s == null) {
                continue;
            }
            peers++;
            rtt += s.rttMillis();
            loss += s.lossRate();
            up += s.bytesSent();
            down += s.bytesReceived();
        }
        long tampered = 0;
        int delay = 0;
        for (Incoming in : incoming.values()) {
            tampered += in.rx.verifier().tampered() + in.rx.verifier().forgedManifests();
            delay = Math.max(delay, in.jitter.targetDelayMillis());
        }
        boolean adminOnline = state == State.IN && policy.admins().stream().anyMatch(a -> online(a, now));
        return new Health(peers, peers == 0 ? 0 : rtt / peers, peers == 0 ? 0 : loss / peers, 0,
                up * 8 / 1000, down * 8 / 1000, tampered, delay, adminOnline);
    }

    @Override
    public void setMicOn(boolean on) {
        micOn = on;
        media.setMic(on);
    }

    @Override
    public void setCameraOn(boolean on) {
        cameraOn = on;
        media.setCamera(on);
    }

    @Override
    public void setScreenShare(boolean on) {
        screenOn = on;
        media.setScreen(on);
    }

    @Override
    public void setGallery(List<ParticipantId> tiles) {
        gallery = List.copyOf(tiles.subList(0, Math.min(MAX_TILES, tiles.size())));
        lastSubscriptionCheck = 0;
    }

    @Override
    public void sendChat(String text) {
        if (state != State.IN || text == null || text.isBlank()) {
            return;
        }
        ChatLine c = ChatLine.sign(me, text.strip(), clock.getAsLong());
        gossip.publish(new Gossip.Item(Gossip.Item.CHAT, c.encode()));
        if (view != null) {
            view.onChat(me.id(), settings.name(), c.text(), c.atMillis());
        }
    }

    private void command(AdminCommand.Kind kind, ParticipantId target, long until, List<byte[]> frozen) {
        if (state != State.IN || !policy.isAdmin(me.id())) {
            return;
        }
        long now = clock.getAsLong();
        AdminCommand c = AdminCommand.sign(me, meetingId, kind, target, until, policy.nextLamport(), now, frozen);
        addCommand(c);
        gossip.publish(new Gossip.Item(Gossip.Item.ADMIN_COMMAND, c.encode()));
    }

    @Override
    public void allowSpeak(ParticipantId p, boolean allow) {
        command(allow ? AdminCommand.Kind.ALLOW_SPEAK : AdminCommand.Kind.DENY_SPEAK, p, 0, List.of());
    }

    @Override
    public void openMicForAll(int seconds) {
        int s = Math.max(5, Math.min(600, seconds));
        command(AdminCommand.Kind.OPEN_MIC_ALL, null, clock.getAsLong() + s * 1000L, List.of());
    }

    @Override
    public void closeMicForAll() {
        command(AdminCommand.Kind.CLOSE_MIC_ALL, null, 0, List.of());
    }

    @Override
    public void allowCamera(ParticipantId p, boolean allow) {
        command(allow ? AdminCommand.Kind.ALLOW_CAMERA : AdminCommand.Kind.DENY_CAMERA, p, 0, List.of());
    }

    @Override
    public void grantAdmin(ParticipantId p) {
        command(AdminCommand.Kind.GRANT_ADMIN, p, 0, List.of());
    }

    @Override
    public void revokeAdmin(ParticipantId p) {
        if (state == State.IN) {
            command(AdminCommand.Kind.REVOKE_ADMIN, p, 0, policy.frozenFor(p));
        }
    }

    @Override
    public void transferHost(ParticipantId p) {
        command(AdminCommand.Kind.TRANSFER_HOST, p, 0, List.of());
    }

    @Override
    public void kick(ParticipantId p) {
        if (state == State.IN) {
            command(AdminCommand.Kind.KICK, p, 0, policy.frozenFor(p));
        }
    }

    @Override
    public void endMeeting() {
        command(AdminCommand.Kind.END_MEETING, null, 0, List.of());
    }

    @Override
    public void leave() {
        end("You left the meeting");
    }

    @Override
    public void setView(ConferenceView view) {
        this.view = view;
    }

    // ---------------------------------------------------------------- for tests and diagnostics

    boolean inMeeting() {
        return state == State.IN;
    }

    boolean ended() {
        return state == State.ENDED;
    }

    long keyEpoch() {
        return ring == null ? -1 : ring.currentEpoch();
    }

    PolicyState policy() {
        return policy;
    }

    int rosterSize() {
        return roster.size();
    }

    Membership membership() {
        return membership;
    }

    String debugCrowd() {
        if (crowd == null) {
            return "no crowd session (root=" + (crowdRoot == null ? "-" : crowdRoot.shortId()) + ")";
        }
        long sid = StreamIds.of(crowdRoot, CROWD_TRACK);
        Forwarder.StreamStats st = forwarder.stats(sid);
        String parents = java.util.stream.IntStream.range(0, settings.stripes()).mapToObj(k -> {
            ParticipantId p = forwarder.parentOf(sid, k);
            return p == null ? "-" : p.shortId() + (net.connected().contains(p) ? "" : "(nolink)");
        }).toList().toString();
        return parents + " " + String.format("root=%s sent=%d skipped=%d rejected=%d late=%d | rx=%d deliv=%d drop=%d attached=%s kids0=%d",
                crowdRoot.shortId(), crowd.framesSent(), crowd.framesSkipped(), crowd.rejectedCount(), crowd.lateMixes(),
                st.received(), st.delivered(), st.dropped(), forwarder.fullyAttached(sid, settings.stripes()),
                forwarder.childrenOf(sid, 0).size()) + " members=" + coordinator.memberCount(sid)
                + " coordMalformed=" + coordinator.malformedCount() + " fwdMalformed=" + forwarder.malformedCount()
                + " unknownDrops=" + forwarder.unknownStreamDrops();
    }

    /** One line per received stream: tree stats, attachment and verifier counters. */
    String debugStreams() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Long, Incoming> e : incoming.entrySet()) {
            Forwarder.StreamStats st = forwarder.stats(e.getKey());
            var v = e.getValue().rx.verifier();
            b.append(String.format("rx=%d fwd=%d drop=%d deliv=%d attached=%s parents=%s | verified=%d tampered=%d forged=%d unverDrop=%d replays=%d rejected=%d jitter played=%d concealed=%d late=%d",
                    st.received(), st.forwarded(), st.dropped(), st.delivered(),
                    forwarder.fullyAttached(e.getKey(), settings.stripes()),
                    java.util.stream.IntStream.range(0, settings.stripes()).mapToObj(k -> {
                        ParticipantId p = forwarder.parentOf(e.getKey(), k);
                        return p == null ? "-" : p.shortId();
                    }).toList(),
                    v.verified(), v.tampered(), v.forgedManifests(), v.unverifiedDropped(), v.replays(),
                    e.getValue().rx.rejected(), e.getValue().jitter.played(), e.getValue().jitter.concealed(),
                    e.getValue().jitter.late()));
        }
        return b.toString();
    }
}
