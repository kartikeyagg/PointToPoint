package com.kartikey.p2pchat.conf.policy;

import static com.kartikey.p2pchat.conf.policy.AdminCommand.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.policy.AdminCommand.Kind;
import com.kartikey.p2pchat.conf.policy.PolicyState.AddResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyStateTest {

    private static final long NOW = 1_000_000;
    private Identity host, a, b, u;
    private MeetingGenesis genesis;
    private PolicyState st;

    @BeforeEach
    void setUp() {
        host = Identity.generate();
        a = Identity.generate();
        b = Identity.generate();
        u = Identity.generate();
        genesis = MeetingGenesis.create(host, true, NOW);
        st = new PolicyState(genesis);
    }

    private AdminCommand cmd(Identity who, Kind k, Identity target, long until, long lamport) {
        return AdminCommand.sign(who, genesis.meetingId(), k, target == null ? null : target.id(), until, lamport, NOW);
    }

    private void add(Identity who, Kind k, Identity target, long lamport) {
        assertEquals(AddResult.ADDED, st.add(cmd(who, k, target, 0, lamport)));
    }

    @Test
    void genesisRoundTripAndTamper() {
        assertTrue(genesis.verify());
        MeetingGenesis back = MeetingGenesis.decode(genesis.encode());
        assertTrue(back.verify());
        assertEquals(host.id(), back.host());
        byte[] bad = genesis.encode();
        bad[ParticipantId.ENCODED_LEN + 16 + 3] ^= 1;
        assertFalse(MeetingGenesis.decode(bad).verify());
        assertThrows(IllegalArgumentException.class, () -> new PolicyState(MeetingGenesis.decode(bad)));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingGenesis.decode(Arrays.copyOf(genesis.encode(), 50)));
    }

    @Test
    void hostTransferThenOldHostCannotRevoke() {
        add(host, GRANT_ADMIN, b, 1);
        add(host, TRANSFER_HOST, a, 2);
        assertEquals(a.id(), st.host());
        assertTrue(st.isAdmin(host.id()));
        add(host, REVOKE_ADMIN, b, 3);
        assertTrue(st.isAdmin(b.id()));
        add(a, REVOKE_ADMIN, b, 4);
        assertFalse(st.isAdmin(b.id()));
        add(a, REVOKE_ADMIN, host, 5);
        assertFalse(st.isAdmin(host.id()));
    }

    @Test
    void multiAdminGrants() {
        add(host, GRANT_ADMIN, a, 1);
        add(a, GRANT_ADMIN, b, 2);
        assertEquals(3, st.admins().size());
        add(b, KICK, u, 3);
        assertTrue(st.isKicked(u.id()));
        add(a, GRANT_ADMIN, u, 4);
        assertFalse(st.isAdmin(u.id()));
    }

    @Test
    void nonAdminCommandIgnored() {
        add(u, GRANT_ADMIN, u, 1);
        add(u, KICK, a, 2);
        add(u, END_MEETING, null, 3);
        assertFalse(st.isAdmin(u.id()));
        assertFalse(st.isKicked(a.id()));
        assertFalse(st.ended());
    }

    @Test
    void adminRevokedLaterCommandsIgnored() {
        add(host, GRANT_ADMIN, a, 1);
        add(host, REVOKE_ADMIN, a, 2);
        add(a, KICK, u, 3);
        assertFalse(st.isKicked(u.id()));
        add(a, GRANT_ADMIN, b, 4);
        assertFalse(st.isAdmin(b.id()));
    }

    @Test
    void adminCannotKickAdmin() {
        add(host, GRANT_ADMIN, a, 1);
        add(host, GRANT_ADMIN, b, 2);
        add(a, KICK, b, 3);
        assertFalse(st.isKicked(b.id()));
        add(host, KICK, b, 4);
        assertTrue(st.isKicked(b.id()));
        assertFalse(st.isAdmin(b.id()));
    }

    @Test
    void openMicExpiresByTime() {
        assertFalse(st.canSpeak(u.id(), NOW));
        assertEquals(AddResult.ADDED, st.add(cmd(host, OPEN_MIC_ALL, null, NOW + 1000, 1)));
        assertTrue(st.canSpeak(u.id(), NOW + 999));
        assertFalse(st.canSpeak(u.id(), NOW + 1000));
        assertEquals(NOW + 1000, st.openMicUntil());
        add(host, CLOSE_MIC_ALL, null, 2);
        assertFalse(st.canSpeak(u.id(), NOW));
    }

    @Test
    void denyOverridesOpenMicAndAllowWorksWithout() {
        add(host, OPEN_MIC_ALL, null, 1);
        st.add(cmd(host, OPEN_MIC_ALL, null, NOW + 5000, 2));
        add(host, DENY_SPEAK, u, 3);
        assertFalse(st.canSpeak(u.id(), NOW));
        assertTrue(st.canSpeak(b.id(), NOW));
        add(host, ALLOW_SPEAK, u, 4);
        assertTrue(st.canSpeak(u.id(), NOW + 10_000));
        assertTrue(st.canSpeak(host.id(), NOW));
    }

    @Test
    void cameraPolicy() {
        assertTrue(st.canShowCamera(u.id()));
        add(host, DENY_CAMERA, u, 1);
        assertFalse(st.canShowCamera(u.id()));
        add(host, ALLOW_CAMERA, u, 2);
        assertTrue(st.canShowCamera(u.id()));
    }

    @Test
    void kickBlocksAdmitAndSpeaking() {
        add(host, ALLOW_SPEAK, u, 1);
        assertTrue(st.admit(JoinTicket.issue(host, genesis.meetingId(), u.id(), NOW), NOW));
        add(host, KICK, u, 2);
        assertTrue(st.isKicked(u.id()));
        assertFalse(st.admit(JoinTicket.issue(host, genesis.meetingId(), u.id(), NOW), NOW));
        assertFalse(st.canSpeak(u.id(), NOW));
        assertFalse(st.canShowCamera(u.id()));
        add(host, ALLOW_SPEAK, u, 3);
        assertFalse(st.canSpeak(u.id(), NOW));
    }

    @Test
    void hostCannotBeKickedOrRevoked() {
        add(host, GRANT_ADMIN, a, 1);
        add(a, KICK, host, 2);
        add(host, KICK, host, 3);
        add(host, REVOKE_ADMIN, host, 4);
        assertFalse(st.isKicked(host.id()));
        assertTrue(st.isAdmin(host.id()));
        assertEquals(host.id(), st.host());
    }

    @Test
    void endedMeetingBlocksAdmit() {
        add(host, END_MEETING, null, 1);
        assertTrue(st.ended());
        assertFalse(st.admit(JoinTicket.issue(host, genesis.meetingId(), u.id(), NOW), NOW));
    }

    @Test
    void tamperedEncodingIsBadSignature() {
        byte[] enc = cmd(host, ALLOW_SPEAK, u, 0, 1).encode();
        enc[16 + ParticipantId.ENCODED_LEN + 7] ^= 1;
        assertEquals(AddResult.BAD_SIGNATURE, st.add(AdminCommand.decode(enc)));
    }

    @Test
    void duplicateAndWrongMeeting() {
        AdminCommand c = cmd(host, ALLOW_SPEAK, u, 0, 1);
        assertEquals(AddResult.ADDED, st.add(c));
        assertEquals(AddResult.DUPLICATE, st.add(AdminCommand.decode(c.encode())));
        byte[] other = new byte[16];
        assertEquals(AddResult.WRONG_MEETING,
                st.add(AdminCommand.sign(host, other, END_MEETING, null, 0, 1, NOW)));
        assertEquals(2, st.nextLamport());
    }

    @Test
    void shuffledOrdersConverge() {
        Identity[] ids = {host, a, b, u, Identity.generate(), Identity.generate()};
        Kind[] kinds = Kind.values();
        Random rnd = new Random(42);
        List<AdminCommand> all = new ArrayList<>();
        while (all.size() < 50) {
            Kind k = kinds[rnd.nextInt(kinds.length)];
            if (k == END_MEETING && rnd.nextInt(4) != 0) {
                continue;
            }
            Identity issuer = ids[rnd.nextInt(ids.length)];
            Identity target = ids[rnd.nextInt(ids.length)];
            long until = NOW + rnd.nextInt(5000);
            all.add(cmd(issuer, k, k.name().matches("OPEN_MIC_ALL|CLOSE_MIC_ALL|END_MEETING") ? null : target,
                    until, rnd.nextInt(12)));
        }
        PolicyState ref = new PolicyState(genesis);
        all.forEach(ref::add);
        for (int i = 0; i < 8; i++) {
            List<AdminCommand> copy = new ArrayList<>(all);
            Collections.shuffle(copy, rnd);
            PolicyState s = new PolicyState(genesis);
            copy.forEach(s::add);
            assertArrayEquals(ref.digest(), s.digest());
            assertEquals(ref.host(), s.host());
            assertEquals(ref.admins(), s.admins());
            assertEquals(ref.ended(), s.ended());
            assertEquals(ref.openMicUntil(), s.openMicUntil());
            assertEquals(ref.nextLamport(), s.nextLamport());
            for (Identity p : ids) {
                assertEquals(ref.isKicked(p.id()), s.isKicked(p.id()));
                assertEquals(ref.canSpeak(p.id(), NOW + 100), s.canSpeak(p.id(), NOW + 100));
                assertEquals(ref.canShowCamera(p.id()), s.canShowCamera(p.id()));
            }
        }
    }

    @Test
    void syncViaCommandsConverges() {
        add(host, GRANT_ADMIN, a, 1);
        add(a, KICK, u, 2);
        PolicyState peer = new PolicyState(genesis);
        assertFalse(Arrays.equals(st.digest(), peer.digest()));
        st.commands().forEach(peer::add);
        assertArrayEquals(st.digest(), peer.digest());
        assertTrue(peer.isKicked(u.id()));
    }

    @Test
    void ticketRules() {
        byte[] m = genesis.meetingId();
        JoinTicket ok = JoinTicket.issue(host, m, u.id(), NOW);
        assertTrue(st.admit(ok, NOW));
        assertTrue(st.admit(ok, NOW - 5000));
        assertFalse(st.admit(ok, NOW - 5001));
        assertTrue(st.admit(ok, NOW + 60_000));
        assertFalse(st.admit(ok, NOW + 60_001));
        assertFalse(st.admit(JoinTicket.issue(a, m, u.id(), NOW), NOW));
        assertFalse(st.admit(JoinTicket.issue(host, new byte[16], u.id(), NOW), NOW));
        assertFalse(st.admit(JoinTicket.issue(host, m, u.id(), NOW, NOW + 121_000), NOW));
        assertTrue(st.admit(JoinTicket.issue(host, m, u.id(), NOW, NOW + 120_000), NOW));
        byte[] enc = ok.encode();
        enc[enc.length - 1] ^= 1;
        assertFalse(st.admit(JoinTicket.decode(enc), NOW));
        add(host, GRANT_ADMIN, a, 1);
        assertTrue(st.admit(JoinTicket.issue(a, m, u.id(), NOW), NOW));
        add(host, REVOKE_ADMIN, a, 2);
        assertFalse(st.admit(JoinTicket.issue(a, m, u.id(), NOW), NOW));
    }

    @Test
    void roundTripsAndTruncation() {
        AdminCommand targeted = cmd(host, KICK, u, 0, 7);
        AdminCommand untargeted = cmd(host, OPEN_MIC_ALL, null, 99, 8);
        for (AdminCommand c : List.of(targeted, untargeted)) {
            AdminCommand back = AdminCommand.decode(c.encode());
            assertTrue(back.verify());
            assertArrayEquals(c.id(), back.id());
            assertEquals(c.kind(), back.kind());
            assertEquals(c.target(), back.target());
            byte[] enc = c.encode();
            assertThrows(IllegalArgumentException.class, () -> AdminCommand.decode(Arrays.copyOf(enc, enc.length - 1)));
            assertThrows(IllegalArgumentException.class, () -> AdminCommand.decode(Arrays.copyOf(enc, enc.length + 1)));
        }
        byte[] kind = targeted.encode();
        kind[16 + ParticipantId.ENCODED_LEN + 16] = 99;
        assertThrows(IllegalArgumentException.class, () -> AdminCommand.decode(kind));
        assertThrows(IllegalArgumentException.class,
                () -> AdminCommand.sign(host, genesis.meetingId(), KICK, null, 0, 1, NOW));
        JoinTicket t = JoinTicket.issue(host, genesis.meetingId(), u.id(), NOW);
        assertTrue(JoinTicket.decode(t.encode()).verify());
        byte[] te = t.encode();
        assertThrows(IllegalArgumentException.class, () -> JoinTicket.decode(Arrays.copyOf(te, te.length - 1)));
    }
}
