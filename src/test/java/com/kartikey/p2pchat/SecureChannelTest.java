package com.kartikey.p2pchat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureChannelTest {

    private static final byte[] STATIC = new byte[32];
    private static final int MAX = PacketCodec.MAX_FRAME;

    private static SecureChannel[] pair() {
        SecureChannel a = new SecureChannel(STATIC, MAX), b = new SecureChannel(STATIC, MAX);
        assertEquals(SecureChannel.Handshake.ADOPTED, a.establish(b.localPublicKey()));
        assertEquals(SecureChannel.Handshake.ADOPTED, b.establish(a.localPublicKey()));
        return new SecureChannel[]{a, b};
    }

    private static byte[] open(SecureChannel c, byte[] wire) {
        return c.open(wire, wire.length).frame();
    }

    @Test
    void bothDirectionsRoundTripWithDifferentKeys() {
        SecureChannel[] p = pair();
        byte[] msg = "hello over the session".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 300; i++) { // past the keystream pool depth
            assertArrayEquals(msg, open(p[1], p[0].seal(msg)));
            assertArrayEquals(msg, open(p[0], p[1].seal(msg)));
        }
        byte[] wire = p[0].seal(msg);
        // a packet reflected back to its sender must not open (directional keys)
        assertThrows(IllegalArgumentException.class, () -> open(p[0], wire));
        assertFalse(new String(wire, StandardCharsets.ISO_8859_1).contains("hello"));
    }

    @Test
    void replaysAreRejectedButReorderingIsFine() {
        SecureChannel[] p = pair();
        byte[] m1 = p[0].seal(new byte[]{1}), m2 = p[0].seal(new byte[]{2}), m3 = p[0].seal(new byte[]{3});
        assertArrayEquals(new byte[]{3}, open(p[1], m3));
        assertArrayEquals(new byte[]{1}, open(p[1], m1));
        assertThrows(IllegalArgumentException.class, () -> open(p[1], m1));
        assertArrayEquals(new byte[]{2}, open(p[1], m2));
        assertThrows(IllegalArgumentException.class, () -> open(p[1], m3));
    }

    @Test
    void wrongPassphraseCannotTalk() {
        SecureChannel a = new SecureChannel(STATIC, MAX);
        byte[] other = new byte[32];
        other[0] = 1;
        SecureChannel b = new SecureChannel(other, MAX);
        a.establish(b.localPublicKey());
        b.establish(a.localPublicKey());
        byte[] wire = a.seal(new byte[]{9});
        assertThrows(IllegalArgumentException.class, () -> open(b, wire));
    }

    @Test
    void ownKeyReflectedIsRefused() {
        SecureChannel a = new SecureChannel(STATIC, MAX);
        assertThrows(IllegalArgumentException.class, () -> a.establish(a.localPublicKey()));
    }

    @Test
    void newPeerKeyStaysPendingUntilTrafficProvesIt() {
        SecureChannel[] p = pair();
        SecureChannel a = p[0], b = p[1];
        SecureChannel attackerReplay = new SecureChannel(STATIC, MAX);
        // a HELLO with some other key (e.g. replayed) must not break the running session
        assertEquals(SecureChannel.Handshake.PENDING, a.establish(attackerReplay.localPublicKey()));
        assertArrayEquals(new byte[]{1}, open(a, b.seal(new byte[]{1})));
        assertArrayEquals(new byte[]{2}, open(b, a.seal(new byte[]{2})));

        // the peer really restarted: its first sealed packet switches us over
        SecureChannel restarted = new SecureChannel(STATIC, MAX);
        assertEquals(SecureChannel.Handshake.PENDING, a.establish(restarted.localPublicKey()));
        assertEquals(SecureChannel.Handshake.ADOPTED, restarted.establish(a.localPublicKey()));
        byte[] wire = restarted.seal(new byte[]{3});
        SecureChannel.Opened opened = a.open(wire, wire.length);
        assertTrue(opened.rekeyed());
        assertArrayEquals(new byte[]{3}, opened.frame());
        assertArrayEquals(new byte[]{4}, open(restarted, a.seal(new byte[]{4})));
        assertEquals(SecureChannel.Handshake.UNCHANGED, a.establish(restarted.localPublicKey()));
    }

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865"), SecureChannel.hkdf(salt, ikm, info, 42));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
