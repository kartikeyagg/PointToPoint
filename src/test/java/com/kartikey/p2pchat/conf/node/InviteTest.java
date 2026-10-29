package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InviteTest {

    @Test
    void roundTripsWithIpv4AndIpv6() throws Exception {
        Identity admin = Identity.generate();
        byte[] id = Crypto.random(16);
        Invite in = new Invite(id, admin.id(), "Team sync ✓", List.of(
                new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 7302),
                new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 7302)));
        Invite out = Invite.decode(in.encode());
        assertArrayEquals(id, out.meetingId());
        assertEquals(admin.id(), out.admin());
        assertEquals("Team sync ✓", out.title());
        assertEquals(in.candidates(), out.candidates());
        assertTrue(in.encode().length() < 300, "invite should stay pasteable");
    }

    @Test
    void rejectsGarbageCleanly() {
        Identity admin = Identity.generate();
        String valid = new Invite(Crypto.random(16), admin.id(), "x", List.of()).encode();
        byte[] raw = Base64.getUrlDecoder().decode(valid.substring(6));
        Random rnd = new Random(1);
        for (int i = 0; i < 2_000; i++) {
            byte[] b = raw.clone();
            if (i % 2 == 0) {
                b = java.util.Arrays.copyOf(b, rnd.nextInt(b.length + 20));
            } else {
                b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8));
            }
            String code = "p2pc1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            try {
                Invite.decode(code);
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Invite.decode("hello"));
        assertThrows(IllegalArgumentException.class, () -> Invite.decode("p2pc1:!!!"));
    }
}
