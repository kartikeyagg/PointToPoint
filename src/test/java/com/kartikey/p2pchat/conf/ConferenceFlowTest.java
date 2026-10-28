package com.kartikey.p2pchat.conf;

import com.kartikey.p2pchat.conf.keys.EpochBundle;
import com.kartikey.p2pchat.conf.keys.EpochSchedule;
import com.kartikey.p2pchat.conf.keys.KeyRing;
import com.kartikey.p2pchat.conf.policy.AdminCommand;
import com.kartikey.p2pchat.conf.policy.JoinTicket;
import com.kartikey.p2pchat.conf.policy.MeetingGenesis;
import com.kartikey.p2pchat.conf.policy.PolicyState;
import com.kartikey.p2pchat.conf.stream.GroupCipher;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import com.kartikey.p2pchat.conf.stream.StreamSigner;
import com.kartikey.p2pchat.conf.stream.StreamVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The phase-1 modules together: admit, hand out the epoch key, stream through a relay. */
class ConferenceFlowTest {

    @Test
    void admittedMemberReceivesSpeakerAndCatchesTamperingRelay() {
        long now = 1_000_000;
        Identity host = Identity.generate(), speaker = Identity.generate(), listener = Identity.generate();
        MeetingGenesis genesis = MeetingGenesis.create(host, true, now);
        PolicyState policy = new PolicyState(genesis);
        byte[] meetingId = genesis.meetingId();

        assertTrue(policy.admit(JoinTicket.issue(host, meetingId, speaker.id(), now), now));
        assertTrue(policy.admit(JoinTicket.issue(host, meetingId, listener.id(), now), now));
        policy.add(AdminCommand.sign(host, meetingId, AdminCommand.Kind.ALLOW_SPEAK, speaker.id(), 0,
                policy.nextLamport(), now));
        assertTrue(policy.canSpeak(speaker.id(), now));
        assertFalse(policy.canSpeak(listener.id(), now));

        byte[] epochKey = EpochSchedule.newKey();
        List<EpochBundle> bundles = EpochBundle.create(host, meetingId, 1, epochKey,
                List.of(speaker.id(), listener.id()), 1000, now);
        KeyRing listenerRing = new KeyRing(meetingId);
        for (EpochBundle b : bundles) {
            listenerRing.accept(EpochBundle.decode(b.encode()), listener, policy::isAdmin);
        }
        assertArrayEquals(epochKey, listenerRing.key(1).orElseThrow());

        long streamId = StreamIds.of(speaker.id(), 0);
        GroupCipher senderCipher = new GroupCipher(epochKey);
        StreamSigner signer = new StreamSigner(speaker, streamId, 8);
        List<byte[]> wire = new ArrayList<>();
        for (int seq = 0; seq < 16; seq++) {
            MediaHeader h = new MediaHeader(MediaHeader.Kind.AUDIO, streamId, 1, seq, 0, 0, 20, 0);
            wire.addAll(signer.onSend(senderCipher.seal(h, ("frame " + seq).getBytes(StandardCharsets.UTF_8))));
        }
        wire.addAll(signer.flush());

        // the relay flips one ciphertext byte of packet 3; everything else passes untouched
        byte[] victim = wire.get(3);
        victim[victim.length - 1] ^= 1;

        StreamVerifier verifier = new StreamVerifier(speaker.id(), streamId, 256, 5_000);
        GroupCipher listenerCipher = new GroupCipher(listenerRing.key(1).orElseThrow());
        List<String> heard = new ArrayList<>();
        for (byte[] p : wire) {
            for (byte[] released : verifier.onPacket(p, now)) {
                heard.add(new String(listenerCipher.open(released), StandardCharsets.UTF_8));
            }
        }
        assertEquals(15, heard.size());
        assertFalse(heard.contains("frame 3"));
        assertEquals(1, verifier.tampered());
    }

    @Test
    void kickedMemberIsLeftOutOfTheNextEpoch() {
        long now = 5_000_000;
        Identity host = Identity.generate(), stays = Identity.generate(), kicked = Identity.generate();
        MeetingGenesis genesis = MeetingGenesis.create(host, true, now);
        PolicyState policy = new PolicyState(genesis);
        policy.add(AdminCommand.sign(host, genesis.meetingId(), AdminCommand.Kind.KICK, kicked.id(), 0,
                policy.nextLamport(), now));
        assertTrue(policy.isKicked(kicked.id()));
        assertFalse(policy.admit(JoinTicket.issue(host, genesis.meetingId(), kicked.id(), now), now));
        assertTrue(EpochSchedule.due(now, now + 1, true));

        List<ParticipantId> members = List.of(stays.id(), kicked.id()).stream()
                .filter(p -> !policy.isKicked(p)).toList();
        List<EpochBundle> bundles = EpochBundle.create(host, genesis.meetingId(), 2, EpochSchedule.newKey(),
                members, 1000, now);
        assertTrue(bundles.get(0).unwrap(stays).isPresent());
        assertTrue(bundles.get(0).unwrap(kicked).isEmpty());
    }
}
