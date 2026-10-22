package com.kartikey.p2pchat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    @Test
    void defaults() {
        Options o = Options.parse(new String[0]);
        assertNull(o.peerHost);
        assertEquals(Options.DEFAULT_PORT, o.peerPort);
        assertEquals(Options.DEFAULT_PORT, o.localPort);
        assertFalse(o.cli);
    }

    @Test
    void parsesAllFlags() {
        Options o = Options.parse(new String[]{"--cli", "--peer", "1.2.3.4", "--peer-port", "9000",
                "--port", "9001", "--key", "s3cret"});
        assertTrue(o.cli);
        assertEquals("1.2.3.4", o.peerHost);
        assertEquals(9000, o.peerPort);
        assertEquals(9001, o.localPort);
        assertEquals("s3cret", o.passphrase);
    }

    @Test
    void desktopBenchMode() {
        assertEquals("bench", Options.parse(new String[]{"--desktop", "bench"}).desktop);
    }

    @Test
    void maxRateIsInMegabytesPerSecond() {
        assertEquals(0, Options.parse(new String[0]).maxBytesPerSecond);
        assertEquals(2_500_000, Options.parse(new String[]{"--max-rate", "2.5"}).maxBytesPerSecond);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[]{"--max-rate", "0"}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[]{"--max-rate", "fast"}));
    }

    @Test
    void peerAcceptsHostPortAndIpv6Forms() {
        Options o = Options.parse(new String[]{"--peer", "203.0.113.10:48720"});
        assertEquals("203.0.113.10", o.peerHost);
        assertEquals(48720, o.peerPort);

        o = Options.parse(new String[]{"--peer", "[2001:db8::1]:9000"});
        assertEquals("2001:db8::1", o.peerHost);
        assertEquals(9000, o.peerPort);

        o = Options.parse(new String[]{"--peer", "2001:db8::1"});
        assertEquals("2001:db8::1", o.peerHost);
        assertEquals(Options.DEFAULT_PORT, o.peerPort);

        o = Options.parse(new String[]{"--whoami", "--no-stun"});
        assertTrue(o.whoami);
        assertFalse(o.stun);
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--peer"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--nope"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--peer", "[::1"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--peer", "1.2.3.4:x"}));
    }

    @Test
    void desktopModesUseTheirOwnDefaultPort() {
        Options o = Options.parse(new String[]{"--desktop", "host", "--peer", "203.0.113.7", "--key", "k", "--view-only"});
        assertEquals("host", o.desktop);
        assertTrue(o.viewOnly);
        assertEquals(Options.DESKTOP_PORT, o.peerPort);
        assertEquals(Options.DESKTOP_PORT, o.localPort);

        o = Options.parse(new String[]{"--desktop", "view", "--peer", "203.0.113.7:9000", "--port", "9001"});
        assertEquals("view", o.desktop);
        assertEquals(9000, o.peerPort);
        assertEquals(9001, o.localPort);

        o = Options.parse(new String[]{"--desktop", "--peer-port", "9002"});
        assertEquals("", o.desktop, "bare --desktop opens the launcher");
        assertEquals(9002, o.peerPort);
        assertEquals(Options.DESKTOP_PORT, o.localPort);
        assertNull(Options.parse(new String[0]).desktop);
    }
}
