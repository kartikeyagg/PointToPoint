package com.kartikey.p2pchat.remote;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HostWindowTest {
    @Test
    void sharingNoticeNeverTakesKeyboardFocus() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AtomicBoolean stopped = new AtomicBoolean();
        HostWindow window = HostWindow.open("peer", true, allowed -> {}, () -> stopped.set(true));
        try {
            // Remote key presses go to the focused window; this one must never receive them.
            assertFalse(window.canTakeKeyboardFocus());
            assertFalse(stopped.get());
        } finally {
            window.close();
        }
    }
}
