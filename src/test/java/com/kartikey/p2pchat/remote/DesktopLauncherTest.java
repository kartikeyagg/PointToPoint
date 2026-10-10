package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLauncherTest {

    @Test
    void buildsHostAndViewerModes() {
        Options host = DesktopLauncher.buildOptions(true, "example.test", "", "", "secret!", true);
        Options viewer = DesktopLauncher.buildOptions(false, "example.test", "", "", "secret!", false);

        assertEquals("host", host.desktop);
        assertFalse(host.viewOnly);
        assertEquals("view", viewer.desktop);
        assertFalse(viewer.viewOnly);
    }

    @Test
    void mapsDisabledControlToViewOnlyHost() {
        Options options = DesktopLauncher.buildOptions(true, "example.test", "", "", "secret!", false);

        assertTrue(options.viewOnly);
    }

    @Test
    void embeddedPeerPortWinsOverExplicitField() {
        Options options = DesktopLauncher.buildOptions(true, "1.2.3.4:9010", "9020", "9030", "secret!", true);

        assertEquals("1.2.3.4", options.peerHost);
        assertEquals(9010, options.peerPort);
        assertEquals(9030, options.localPort);
    }

    @Test
    void supportsBareAndBracketedIpv6() {
        Options bare = DesktopLauncher.buildOptions(false, "2001:db8::1", "9010", "", "secret!", false);
        Options bracketed = DesktopLauncher.buildOptions(false, "[2001:db8::2]:9020", "9010", "", "secret!", false);

        assertEquals("2001:db8::1", bare.peerHost);
        assertEquals(9010, bare.peerPort);
        assertEquals("2001:db8::2", bracketed.peerHost);
        assertEquals(9020, bracketed.peerPort);
    }

    @Test
    void blankPortsUseDesktopPort() {
        Options options = DesktopLauncher.buildOptions(true, "example.test", "", "", "secret!", true);

        assertEquals(Options.DESKTOP_PORT, options.peerPort);
        assertEquals(Options.DESKTOP_PORT, options.localPort);
    }

    @Test
    void rejectsMissingPeerShortPassphraseAndBadPort() {
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLauncher.buildOptions(true, " ", "", "", "secret!", true));
        IllegalArgumentException passphrase = assertThrows(IllegalArgumentException.class,
                () -> DesktopLauncher.buildOptions(true, "peer", "", "", "short", true));
        assertEquals("Passphrase must be at least 6 characters", passphrase.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLauncher.buildOptions(true, "peer", "no", "", "secret!", true));
    }
}
