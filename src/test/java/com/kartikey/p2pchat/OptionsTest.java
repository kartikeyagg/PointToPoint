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
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--peer"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--nope"}));
    }
}
