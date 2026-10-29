package com.kartikey.p2pchat.conf.node.gossip;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.keys.EpochBundle;
import com.kartikey.p2pchat.conf.net.MemoryFabric;
import com.kartikey.p2pchat.conf.policy.AdminCommand;
import com.kartikey.p2pchat.conf.policy.MeetingGenesis;
import com.kartikey.p2pchat.conf.policy.PolicyState;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncAndCodecTest {

    @Test
    void contactRoundTrip() throws Exception {
        ParticipantId id = Sim.identity(0).id();
        List<InetSocketAddress> addrs = new ArrayList<>();
        addrs.add(new InetSocketAddress(java.net.InetAddress.getByAddress(new byte[]{10, 1, 2, 3}), 7000));
        addrs.add(new InetSocketAddress(java.net.InetAddress.getByAddress(new byte[16]), 65535));
        addrs.add(InetSocketAddress.createUnresolved("abcd1234", 7302));
        for (int i = 0; i < 10; i++) addrs.add(InetSocketAddress.createUnresolved("h" + i, 1));
        Contact c = new Contact(id, addrs, 5000, true);
        assertEquals(8, c.candidates().size());
        Contact d = Contact.decode(c.encode());
        assertEquals(c.id(), d.id());
        assertEquals(c.candidates(), d.candidates());
        assertEquals(5000, d.uploadKbps());
        assertTrue(d.canRelay());
        assertTrue(d.candidates().get(2).isUnresolved());
    }

    @Test
    void decodersAreFuzzSafe() {
        Random r = new Random(1);
        Contact seed = new Contact(Sim.identity(1).id(), List.of(InetSocketAddress.createUnresolved("x", 1)), 1, false);
        byte[] good = seed.encode();
        MemoryFabric fabric = new MemoryFabric(1);
        Sim.SimNode node = new Sim.SimNode(2, fabric, r, it -> true);
        PolicySync sync = new PolicySync(node.net, node.membership::activePeers, () -> new byte[32], List::of, c -> {},
                () -> -1, (e, f) -> Optional.empty(), () -> new byte[16], b -> {}, r);
        ParticipantId from = Sim.identity(3).id();
        for (int i = 0; i < 20000; i++) {
            byte[] m;
            if (i % 2 == 0) {
                m = new byte[r.nextInt(300)];
                r.nextBytes(m);
            } else { // mutate a plausible message
                byte[] body = Arrays.copyOf(good, good.length + 1);
                System.arraycopy(good, 0, body, 1, good.length);
                m = body;
                for (int k = 0; k < 3; k++) m[r.nextInt(m.length)] = (byte) r.nextInt(256);
            }
            if (m.length > 0) m[0] = (byte) r.nextInt(0x20);
            int[] n = {i};
            assertDoesNotThrow(() -> {
                node.membership.onControl(from, m);
                node.gossip.onControl(from, m);
                sync.onControl(from, m);
            }, "message " + n[0]);
            if (i % 3 == 0) assertThrowsOrOk(() -> Contact.decode(Arrays.copyOf(m, m.length)));
        }
    }

    private static void assertThrowsOrOk(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException ok) {
            // expected
        }
    }

    @Test
    void policySyncConvergesAfterPartition() {
        int n = 40;
        MemoryFabric fabric = new MemoryFabric(3);
        Identity host = Sim.identity(100);
        MeetingGenesis genesis = MeetingGenesis.create(host, true, 0);
        Random rnd = new Random(3);
        List<Sim.SimNode> nodes = new ArrayList<>();
        List<PolicyState> states = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Sim.SimNode node = new Sim.SimNode(i, fabric, new Random(i), it -> true);
            PolicyState ps = new PolicyState(genesis);
            node.policy = ps;
            node.sync = new PolicySync(node.net, node.membership::activePeers, ps::digest, ps::commands,
                    c -> ps.add(c), () -> -1, (e, f) -> Optional.empty(), () -> new byte[16], b -> {}, rnd);
            nodes.add(node);
            states.add(ps);
        }
        // fixed overlay: ring plus chords; links by hand, views by the membership protocol
        for (int i = 0; i < n; i++) {
            Sim.SimNode a = nodes.get(i);
            for (int off : new int[]{1, 2, 7}) {
                Sim.SimNode b = nodes.get((i + off) % n);
                a.membership.join(b.contact());
            }
        }
        long now = run(fabric, nodes, 0, 5_000);
        // partition halves A=[0,20) B=[20,40)
        for (int i = 0; i < 20; i++) for (int j = 20; j < 40; j++) {
            fabric.partition(nodes.get(i).identity.id(), nodes.get(j).identity.id());
        }
        ParticipantId target = Sim.identity(101).id();
        AdminCommand ca = AdminCommand.sign(host, genesis.meetingId(), AdminCommand.Kind.ALLOW_SPEAK, target, 0, 1, now);
        AdminCommand cb = AdminCommand.sign(host, genesis.meetingId(), AdminCommand.Kind.DENY_CAMERA, target, 0, 2, now);
        for (int i = 0; i < 20; i++) states.get(i).add(ca);
        for (int i = 20; i < 40; i++) states.get(i).add(cb);
        now = run(fabric, nodes, now, 20_000);
        assertFalse(Arrays.equals(states.get(0).digest(), states.get(39).digest()), "diverged while partitioned");
        for (int i = 0; i < 20; i++) for (int j = 20; j < 40; j++) {
            fabric.heal(nodes.get(i).identity.id(), nodes.get(j).identity.id());
        }
        // views on both sides were never cut by connection loss (only traffic), so sync flows across old links
        now = run(fabric, nodes, now, 60_000);
        byte[] d0 = states.get(0).digest();
        int agree = 0;
        for (PolicyState s : states) if (Arrays.equals(s.digest(), d0)) agree++;
        System.out.println("policy sync: " + agree + "/" + n + " converged after heal");
        assertEquals(n, agree);
        assertEquals(2, states.get(0).commands().size());
    }

    private static long run(MemoryFabric fabric, List<Sim.SimNode> nodes, long now, long millis) {
        long end = now + millis;
        for (; now < end; now += 200) {
            fabric.advanceTo(now);
            for (Sim.SimNode x : nodes) x.tick(now);
        }
        return now;
    }

    @Test
    void missingKeyIsFetchedFromPeer() {
        Identity admin = Sim.identity(200), me = Sim.identity(201);
        byte[] meeting = Crypto.random(16);
        List<EpochBundle> bundles = EpochBundle.create(admin, meeting, 5, Crypto.random(32),
                List.of(admin.id(), me.id()), 10, 0);
        byte[] fp = Arrays.copyOf(Crypto.sha256(me.id().encoded()), 16);
        MemoryFabric fabric = new MemoryFabric(9);
        Sim.SimNode a = new Sim.SimNode(0, fabric, new Random(1), it -> true);
        Sim.SimNode b = new Sim.SimNode(1, fabric, new Random(2), it -> true);
        List<EpochBundle> received = new ArrayList<>();
        a.sync = new PolicySync(a.net, a.membership::activePeers, () -> new byte[32], List::of, c -> {},
                () -> 5, (e, f) -> e == 5 ? Optional.of(bundles.get(0)) : Optional.empty(), () -> new byte[16], x -> {},
                new Random(1));
        b.sync = new PolicySync(b.net, b.membership::activePeers, () -> new byte[32], List::of, c -> {},
                () -> -1, (e, f) -> Optional.empty(), () -> fp, received::add, new Random(2));
        b.membership.join(a.contact());
        long now = 0;
        List<Sim.SimNode> both = List.of(a, b);
        now = run(fabric, both, now, 20_000);
        assertFalse(received.isEmpty()); // repeats until the receiver's epoch advances
        assertEquals(5, received.get(0).epoch());
        assertTrue(received.get(0).unwrap(me).isPresent());
    }
}
