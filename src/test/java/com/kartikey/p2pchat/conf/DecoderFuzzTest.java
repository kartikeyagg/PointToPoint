package com.kartikey.p2pchat.conf;

import com.kartikey.p2pchat.conf.audio.SignedMix;
import com.kartikey.p2pchat.conf.keys.EpochBundle;
import com.kartikey.p2pchat.conf.policy.AdminCommand;
import com.kartikey.p2pchat.conf.policy.JoinTicket;
import com.kartikey.p2pchat.conf.policy.MeetingGenesis;
import com.kartikey.p2pchat.conf.stream.GroupCipher;
import com.kartikey.p2pchat.conf.stream.Manifest;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamVerifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every decoder sees bytes from strangers (relays, gossip), so for random and mutated input it must
 * either decode or throw {@link IllegalArgumentException} — never another exception, never hang.
 * A decoded value that was mutated must also fail signature checks.
 */
class DecoderFuzzTest {

    private static final int ROUNDS = 3_000;

    private record Target(String name, byte[] valid, Function<byte[], Object> decode) {}

    @Test
    void decodersRejectGarbageCleanly() {
        long now = 1_000_000;
        Identity host = Identity.generate(), other = Identity.generate();
        MeetingGenesis g = MeetingGenesis.create(host, true, now);
        long streamId = 42;
        List<Target> targets = List.of(
                new Target("genesis", g.encode(), MeetingGenesis::decode),
                new Target("command", AdminCommand.sign(host, g.meetingId(), AdminCommand.Kind.KICK, other.id(), 0, 1, now)
                        .encode(), AdminCommand::decode),
                new Target("ticket", JoinTicket.issue(host, g.meetingId(), other.id(), now).encode(), JoinTicket::decode),
                new Target("bundle", EpochBundle.create(host, g.meetingId(), 1, Crypto.random(32),
                        List.of(other.id(), host.id()), 1000, now).get(0).encode(), EpochBundle::decode),
                new Target("mix", SignedMix.sign(host, 7, 3, 2, new byte[40]).encode(), SignedMix::decode),
                new Target("header", new MediaHeader(MediaHeader.Kind.AUDIO, streamId, 1, 9, 1, 2, 30, 1).encode(),
                        MediaHeader::decode),
                new Target("manifest", Manifest.sign(host, streamId, 0, List.of(new byte[16], new byte[16])).encode(),
                        Manifest::decode),
                new Target("participant", host.id().encoded(), b -> ParticipantId.decode(b, 0)));

        Random rnd = new Random(7);
        for (Target t : targets) {
            for (int i = 0; i < ROUNDS; i++) {
                byte[] input = i % 3 == 0 ? randomBytes(rnd, t.valid.length * 2) : mutate(rnd, t.valid);
                try {
                    t.decode.apply(input);
                } catch (IllegalArgumentException expected) {
                    // rejected cleanly
                } catch (RuntimeException e) {
                    fail(t.name + " threw " + e + " for input of " + input.length + " bytes", e);
                }
            }
        }
    }

    @Test
    void mutatedSignedObjectsNeverVerify() {
        long now = 2_000_000;
        Identity host = Identity.generate(), other = Identity.generate();
        MeetingGenesis g = MeetingGenesis.create(host, true, now);
        byte[] cmd = AdminCommand.sign(host, g.meetingId(), AdminCommand.Kind.GRANT_ADMIN, other.id(), 0, 1, now).encode();
        byte[] ticket = JoinTicket.issue(host, g.meetingId(), other.id(), now).encode();
        Random rnd = new Random(11);
        for (int i = 0; i < ROUNDS; i++) {
            assertNotVerified(() -> AdminCommand.decode(flipOne(rnd, cmd)).verify());
            assertNotVerified(() -> JoinTicket.decode(flipOne(rnd, ticket)).verify());
        }
    }

    @Test
    void verifierSurvivesPacketGarbage() {
        Identity sender = Identity.generate();
        StreamVerifier v = new StreamVerifier(sender.id(), 42, 64, 1_000);
        GroupCipher cipher = new GroupCipher(Crypto.random(32));
        byte[] valid = cipher.seal(new MediaHeader(MediaHeader.Kind.VIDEO, 42, 1, 3, 0, 0, 127, 0), new byte[200]);
        Random rnd = new Random(3);
        List<byte[]> released = new ArrayList<>();
        for (int i = 0; i < ROUNDS * 3; i++) {
            byte[] p = i % 2 == 0 ? randomBytes(rnd, 1200) : mutate(rnd, valid);
            released.addAll(assertDoesNotThrow(() -> v.onPacket(p, 0)));
            assertThrowsOnlyIae(() -> cipher.open(p));
        }
        assertTrue(released.isEmpty(), "nothing unsigned may be released");
    }

    private static void assertNotVerified(java.util.function.BooleanSupplier check) {
        try {
            assertFalse(check.getAsBoolean(), "a mutated object verified");
        } catch (IllegalArgumentException rejected) {
            // the mutation broke the encoding itself; also fine
        }
    }

    private static void assertThrowsOnlyIae(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    private static byte[] randomBytes(Random rnd, int maxLen) {
        byte[] b = new byte[rnd.nextInt(maxLen + 1)];
        rnd.nextBytes(b);
        return b;
    }

    private static byte[] flipOne(Random rnd, byte[] valid) {
        byte[] b = valid.clone();
        b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8));
        return b;
    }

    /** Bit flips, truncation, extension or a stomped length-looking field. */
    private static byte[] mutate(Random rnd, byte[] valid) {
        byte[] b = valid.clone();
        switch (rnd.nextInt(4)) {
            case 0 -> {
                for (int k = 1 + rnd.nextInt(4); k > 0; k--) {
                    b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8));
                }
            }
            case 1 -> b = java.util.Arrays.copyOf(b, rnd.nextInt(b.length));
            case 2 -> {
                byte[] longer = java.util.Arrays.copyOf(b, b.length + 1 + rnd.nextInt(64));
                rnd.nextBytes(new byte[0]);
                b = longer;
            }
            default -> {
                int at = rnd.nextInt(Math.max(1, b.length - 4));
                b[at] = (byte) 0xff;
                if (at + 1 < b.length) {
                    b[at + 1] = (byte) 0xff;
                }
            }
        }
        return b;
    }
}
