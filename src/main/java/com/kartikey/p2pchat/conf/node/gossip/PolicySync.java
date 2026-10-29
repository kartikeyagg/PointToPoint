package com.kartikey.p2pchat.conf.node.gossip;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.keys.EpochBundle;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.policy.AdminCommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Anti-entropy for admin commands and epoch keys. Every ~5 s a DIGEST {policy digest, key epoch}
 * goes to one random active peer. A different digest triggers a full exchange of command lists
 * (pages of at most 100); a higher epoch at the peer makes us ask for the bundle shard that
 * contains our fingerprint. Message types 0x18 DIGEST, 0x19 SYNC_REQ, 0x1A COMMANDS, 0x1B KEY_REQ,
 * 0x1C KEY_BUNDLE. Single-threaded. Commands are verified by whoever {@code add} delegates to.
 */
public final class PolicySync {

    public static final int T_DIGEST = 0x18, T_SYNC_REQ = 0x19, T_COMMANDS = 0x1A, T_KEY_REQ = 0x1B, T_KEY_BUNDLE = 0x1C;
    public static final int PAGE = 100, MAX_COMMAND_BYTES = 4096, MAX_BUNDLE_BYTES = 70_000;

    private static final long PERIOD = 5_000, COOLDOWN = 1_500;

    private final PeerNetwork net;
    private final Supplier<? extends Collection<ParticipantId>> peers;
    private final Supplier<byte[]> digest;
    private final Supplier<? extends Collection<AdminCommand>> commands;
    private final Consumer<AdminCommand> add;
    private final LongSupplier keyEpoch;
    private final BiFunction<Long, byte[], Optional<EpochBundle>> bundleFor;
    private final Supplier<byte[]> myFingerprint;
    private final Consumer<EpochBundle> acceptBundle;
    private final Random rnd;
    private final Map<String, Long> cooldown = new HashMap<>();
    private long now, next = -1;
    private long syncsStarted, commandsReceived, bundlesRequested;

    /**
     * @param keyEpoch      newest key epoch we hold (-1 if none)
     * @param bundleFor     (epoch, fingerprint) to the shard containing that fingerprint, if we have it
     * @param myFingerprint our 16-byte recipient fingerprint
     * @param acceptBundle  receives bundles we asked for; verification and unwrapping are its job
     */
    public PolicySync(PeerNetwork net, Supplier<? extends Collection<ParticipantId>> activePeers,
                      Supplier<byte[]> digest, Supplier<? extends Collection<AdminCommand>> commands,
                      Consumer<AdminCommand> add, LongSupplier keyEpoch,
                      BiFunction<Long, byte[], Optional<EpochBundle>> bundleFor, Supplier<byte[]> myFingerprint,
                      Consumer<EpochBundle> acceptBundle, Random rnd) {
        this.net = net;
        this.peers = activePeers;
        this.digest = digest;
        this.commands = commands;
        this.add = add;
        this.keyEpoch = keyEpoch;
        this.bundleFor = bundleFor;
        this.myFingerprint = myFingerprint;
        this.acceptBundle = acceptBundle;
        this.rnd = rnd;
    }

    public void onDisconnected(ParticipantId peer) {
        cooldown.keySet().removeIf(k -> k.startsWith(peer.shortId() + ":"));
    }

    public long syncsStarted() { return syncsStarted; }
    public long commandsReceived() { return commandsReceived; }
    public long bundlesRequested() { return bundlesRequested; }

    public void tick(long nowMillis) {
        now = nowMillis;
        if (next < 0) next = now + rnd.nextInt((int) PERIOD);
        if (now < next) return;
        next = now + PERIOD;
        List<ParticipantId> ps = new ArrayList<>(peers.get());
        if (ps.isEmpty()) return;
        net.sendControl(ps.get(rnd.nextInt(ps.size())), digestMsg());
        if (cooldown.size() > 256) cooldown.values().removeIf(t -> now - t > 10 * COOLDOWN);
    }

    /** @return true if the type belongs to policy sync. */
    public boolean onControl(ParticipantId from, byte[] msg) {
        if (msg.length == 0 || (msg[0] & 0xff) < T_DIGEST || (msg[0] & 0xff) > T_KEY_BUNDLE) return false;
        try {
            Wire.Reader r = new Wire.Reader(msg, 1);
            switch (msg[0] & 0xff) {
                case T_DIGEST -> {
                    byte[] d = r.bytes(32);
                    long epoch = r.i64();
                    r.end();
                    handleDigest(from, d, epoch);
                }
                case T_SYNC_REQ -> {
                    r.end();
                    if (ready(from, "serve")) sendCommands(from);
                }
                case T_COMMANDS -> handleCommands(r);
                case T_KEY_REQ -> {
                    long epoch = r.i64();
                    byte[] fp = r.bytes(EpochBundle.FP_LEN);
                    r.end();
                    if (ready(from, "key")) {
                        bundleFor.apply(epoch, fp).ifPresent(b -> {
                            byte[] enc = b.encode();
                            if (enc.length + 1 <= MAX_BUNDLE_BYTES) {
                                net.sendControl(from, new Wire.Writer().u8(T_KEY_BUNDLE).bytes(enc).done());
                            }
                        });
                    }
                }
                default -> {
                    if (msg.length > MAX_BUNDLE_BYTES) throw new IllegalArgumentException("bundle too large");
                    acceptBundle.accept(EpochBundle.decode(Arrays.copyOfRange(msg, 1, msg.length)));
                }
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException | java.nio.BufferUnderflowException ignored) {
            // malformed remote input: drop
        }
        return true;
    }

    // ---------------------------------------------------------------- handlers

    private void handleDigest(ParticipantId from, byte[] theirs, long theirEpoch) {
        if (theirEpoch > keyEpoch.getAsLong() && ready(from, "keyreq")) {
            bundlesRequested++;
            net.sendControl(from, new Wire.Writer().u8(T_KEY_REQ).i64(theirEpoch).bytes(myFingerprint.get()).done());
        }
        if (!Arrays.equals(theirs, digest.get()) && ready(from, "sync")) {
            syncsStarted++;
            net.sendControl(from, new byte[]{T_SYNC_REQ});
            sendCommands(from);
        }
    }

    private void handleCommands(Wire.Reader r) {
        int n = r.u16();
        if (n > PAGE) throw new IllegalArgumentException("page too large");
        for (int i = 0; i < n; i++) {
            int len = r.u16();
            if (len > MAX_COMMAND_BYTES) throw new IllegalArgumentException("command too large");
            byte[] enc = r.bytes(len);
            try {
                add.accept(AdminCommand.decode(enc));
                commandsReceived++;
            } catch (RuntimeException e) {
                // one bad command does not poison the rest of the page
            }
        }
        r.end();
    }

    private void sendCommands(ParticipantId to) {
        List<AdminCommand> all = new ArrayList<>(commands.get());
        for (int off = 0; off < all.size() || off == 0; off += PAGE) {
            List<AdminCommand> page = all.subList(Math.min(off, all.size()), Math.min(all.size(), off + PAGE));
            Wire.Writer w = new Wire.Writer().u8(T_COMMANDS).u16(page.size());
            for (AdminCommand c : page) {
                byte[] enc = c.encode();
                w.u16(enc.length).bytes(enc);
            }
            if (!net.sendControl(to, w.done())) return;
            if (all.isEmpty()) return;
        }
    }

    private byte[] digestMsg() {
        return new Wire.Writer().u8(T_DIGEST).bytes(digest.get()).i64(keyEpoch.getAsLong()).done();
    }

    /** Per-peer, per-purpose rate gate so a chatty or hostile peer cannot make us resend lists constantly. */
    private boolean ready(ParticipantId peer, String what) {
        String k = peer.shortId() + ":" + what;
        Long t = cooldown.get(k);
        if (t != null && now - t < COOLDOWN) return false;
        cooldown.put(k, now);
        return true;
    }
}
