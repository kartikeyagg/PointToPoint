package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.net.MemoryFabric;
import com.kartikey.p2pchat.conf.ui.ConferenceController;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole meetings in one JVM over the in-memory network: real policy, keys, gossip, relay trees and
 * signed media, with fake devices. This is the end-to-end check that the pieces work together.
 */
class MeetingSimulationTest {

    private static final int TICK = 10;

    private static final class Sim {
        final MemoryFabric fabric = new MemoryFabric(42);
        final List<ConferenceNode> nodes = new ArrayList<>();
        final List<MediaIO.Silent> media = new ArrayList<>();
        final List<List<String>> notices = new ArrayList<>();
        long now;

        ConferenceNode host(String title) {
            Identity id = Identity.generate();
            MediaIO.Silent m = MediaIO.silent();
            ConferenceNode n = ConferenceNode.host(id, fabric.join(id.id()), m, ConferenceNode.Settings.of("Host"),
                    title, fabric::now);
            return add(n, m);
        }

        ConferenceNode join(String name, Invite invite) {
            Identity id = Identity.generate();
            MediaIO.Silent m = MediaIO.silent();
            ConferenceNode n = ConferenceNode.join(id, fabric.join(id.id()), m, ConferenceNode.Settings.of(name),
                    invite, fabric::now);
            return add(n, m);
        }

        private ConferenceNode add(ConferenceNode n, MediaIO.Silent m) {
            List<String> log = new ArrayList<>();
            n.setView(new ConferenceController.ConferenceView() {
                @Override public void onVideo(com.kartikey.p2pchat.conf.ParticipantId who, BufferedImage frame) {}
                @Override public void onParticipantsChanged() {}
                @Override public void onPolicyChanged() {}
                @Override public void onChat(com.kartikey.p2pchat.conf.ParticipantId from, String name, String text, long at) {
                    log.add("chat:" + text);
                }
                @Override public void onActiveSpeakers(List<com.kartikey.p2pchat.conf.ParticipantId> s) {}
                @Override public void onNotice(String message) { log.add(message); }
                @Override public void onEnded(String reason) { log.add("ended:" + reason); }
            });
            nodes.add(n);
            media.add(m);
            notices.add(log);
            return n;
        }

        /** Runs the meeting for {@code millis}, feeding a tone to every node whose mic is on. */
        void run(long millis) {
            long wall = System.nanoTime();
            try {
                runInner(millis);
            } finally {
                long ms = (System.nanoTime() - wall) / 1_000_000;
                if (ms > 2_000) {
                    System.out.println("slow phase: " + millis + " ms simulated took " + ms + " ms at t=" + now);
                }
            }
        }

        private void runInner(long millis) {
            long end = now + millis;
            short[] tone = tone();
            while (now < end) {
                now += TICK;
                fabric.advanceTo(now);
                for (int i = 0; i < nodes.size(); i++) {
                    if (now % MediaFormat.FRAME_MILLIS == 0 && media.get(i).micOn) {
                        media.get(i).feed(tone);
                    }
                    nodes.get(i).tick(now);
                }
            }
        }

        static short[] tone() {
            short[] t = new short[MediaFormat.FRAME_SAMPLES];
            for (int i = 0; i < t.length; i++) {
                t[i] = (short) (6000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0));
            }
            return t;
        }
    }

    /** Connected components of the gossip overlay (active views), largest first. */
    static List<Integer> components(List<ConferenceNode> nodes) {
        java.util.Map<com.kartikey.p2pchat.conf.ParticipantId, ConferenceNode> byId = new java.util.HashMap<>();
        nodes.stream().filter(ConferenceNode::inMeeting).forEach(n -> byId.put(n.self(), n));
        java.util.Set<com.kartikey.p2pchat.conf.ParticipantId> seen = new java.util.HashSet<>();
        List<Integer> sizes = new ArrayList<>();
        for (var start : byId.keySet()) {
            if (!seen.add(start)) continue;
            int size = 0;
            java.util.ArrayDeque<com.kartikey.p2pchat.conf.ParticipantId> q = new java.util.ArrayDeque<>(List.of(start));
            while (!q.isEmpty()) {
                var cur = q.poll();
                size++;
                for (var nb : byId.get(cur).membership().activePeers()) {
                    if (byId.containsKey(nb) && seen.add(nb)) q.add(nb);
                }
            }
            sizes.add(size);
        }
        sizes.sort(java.util.Comparator.reverseOrder());
        return sizes;
    }

    private static long audibleFrames(MediaIO.Silent m) {
        return m.played.stream().filter(f -> {
            long e = 0;
            for (short s : f) {
                e += Math.abs(s);
            }
            return e > 100_000;
        }).count();
    }

    @Test
    void fortyPeopleJoinHearTheSpeakerAndFollowTheAdmin() {
        Sim sim = new Sim();
        ConferenceNode host = sim.host("All hands");
        Invite invite = Invite.decode(fakeInvite(host));
        for (int i = 0; i < 40; i++) {
            sim.join("P" + i, invite);
            sim.run(50);
        }
        sim.run(25_000); // one roster anti-entropy round
        for (ConferenceNode n : sim.nodes) {
            assertTrue(n.inMeeting(), "everyone is in");
        }
        System.out.println("overlay components after joins: " + components(sim.nodes));
        System.out.println("rosters: " + sim.nodes.stream().map(n -> String.valueOf(n.rosterSize())).toList());
        int minRoster = sim.nodes.stream().mapToInt(ConferenceNode::rosterSize).min().orElse(0);
        assertTrue(minRoster >= 38, "everyone sees (almost) everyone, min " + minRoster);

        // host lets P0 speak; everyone else should hear it, with 3% loss on media
        sim.fabric.setMediaLoss(0.03);
        ConferenceNode speaker = sim.nodes.get(1);
        host.allowSpeak(speaker.self(), true);
        speaker.setMicOn(true);
        sim.run(3_000);
        sim.media.forEach(m -> m.played.clear());
        sim.run(10_000);  // 500 frames
        long worst = Long.MAX_VALUE;
        for (int i = 2; i < sim.nodes.size(); i++) {
            worst = Math.min(worst, audibleFrames(sim.media.get(i)));
        }
        System.out.println("speaker audio, 40 listeners, 3% loss: worst listener heard " + worst + "/500 frames");
        assertTrue(worst >= 450, "worst listener heard only " + worst + " of 500 frames");

        // a non-speaker can't be heard even with the mic on
        ConferenceNode quiet = sim.nodes.get(2);
        quiet.setMicOn(true);
        sim.run(2_000);
        assertFalse(quiet.canSpeak());

        // chat reaches everyone
        quiet.sendChat("hello all");
        sim.run(2_000);
        long gotChat = sim.notices.stream().filter(l -> l.contains("chat:hello all")).count();
        assertEquals(sim.nodes.size(), gotChat);
        quiet.setMicOn(false);
        speaker.setMicOn(false);
        sim.fabric.setMediaLoss(0);

        // crowd mode: everyone's mic for 10 s; listeners hear the crowd mix
        for (int i = 3; i < 23; i++) {
            sim.nodes.get(i).setMicOn(true);
        }
        host.openMicForAll(10);
        sim.run(2_000);
        sim.media.forEach(m -> m.played.clear());
        sim.run(5_000);
        long heardCrowd = 0;
        for (int i = 23; i < sim.nodes.size(); i++) {
            heardCrowd += audibleFrames(sim.media.get(i)) > 100 ? 1 : 0;
        }
        for (int i = 0; i < sim.nodes.size(); i += 4) {
            System.out.println("crowd node " + i + ": " + sim.nodes.get(i).debugCrowd());
        }
        System.out.println("crowd mode: " + heardCrowd + "/" + (sim.nodes.size() - 23) + " silent listeners heard the crowd");
        assertTrue(heardCrowd >= sim.nodes.size() - 23 - 2, "crowd mix reached " + heardCrowd);
        sim.run(5_000);
        assertEquals(0, host.openMicUntil(), "open mic ends on its own");

        // kick: the kicked node leaves and is left out of the next key epoch
        ConferenceNode victim = sim.nodes.get(5);
        host.kick(victim.self());
        sim.run(3_000);
        assertTrue(victim.ended());
        assertTrue(sim.notices.get(5).stream().anyMatch(s -> s.startsWith("ended:You were removed")));
        long epoch = host.keyEpoch();
        assertTrue(epoch > victim.keyEpoch(), "a new epoch was made after the kick");
        for (int i = 1; i < sim.nodes.size(); i++) {
            if (i != 5) {
                assertEquals(epoch, sim.nodes.get(i).keyEpoch(), "node " + i + " has the new key");
            }
        }
    }

    @Test
    void hostOfflineFreezesJoinsButTheMeetingGoesOn() {
        Sim sim = new Sim();
        ConferenceNode host = sim.host("Standup");
        Invite invite = Invite.decode(fakeInvite(host));
        for (int i = 0; i < 12; i++) {
            sim.join("P" + i, invite);
            sim.run(50);
        }
        sim.run(4_000);
        ConferenceNode speaker = sim.nodes.get(1);
        host.allowSpeak(speaker.self(), true);
        speaker.setMicOn(true);
        sim.run(3_000);

        sim.media.forEach(m -> m.played.clear());
        sim.run(3_000);
        for (int i = 0; i < sim.nodes.size(); i++) {
            System.out.println("node " + i + " " + sim.nodes.get(i).self().shortId() + ": " + sim.nodes.get(i).debugStreams());
        }
        System.out.println("before crash heard: " + sim.media.stream().map(m -> String.valueOf(audibleFrames(m))).toList());
        sim.fabric.crash(host.self());
        sim.run(50_000); // past the admin-online window
        sim.media.forEach(m -> m.played.clear());
        sim.run(5_000);
        System.out.println("after crash heard: " + sim.media.stream().map(m -> String.valueOf(audibleFrames(m))).toList());
        for (int i = 2; i < sim.nodes.size(); i++) {
            assertTrue(audibleFrames(sim.media.get(i)) > 200, "speaker still heard by node " + i + " with the host gone");
            assertFalse(sim.nodes.get(i).health().adminOnline());
        }

        ConferenceNode late = sim.join("Late", invite);
        sim.run(35_000);
        assertTrue(late.ended(), "nobody can join while no admin is online");
    }

    /** MemoryFabric addresses aren't real IPs, so the invite carries no addresses (the fabric doesn't need them). */
    private static String fakeInvite(ConferenceNode host) {
        String code = host.inviteCode();
        assertFalse(code.isEmpty());
        return code;
    }
}
