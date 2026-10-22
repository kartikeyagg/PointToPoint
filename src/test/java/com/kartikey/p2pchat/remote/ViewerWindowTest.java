package com.kartikey.p2pchat.remote;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewerWindowTest {

    @Test
    void mapsWideRemoteIntoLetterboxedPanel() {
        assertEquals(new Rectangle(0, 75, 200, 50), ViewerWindow.imageArea(200, 200, 400, 100));
        assertEquals(new Point(0, 0), ViewerWindow.toRemote(0, 75, 200, 200, 400, 100));
        assertEquals(new Point(399, 99), ViewerWindow.toRemote(199, 124, 200, 200, 400, 100));
        assertNull(ViewerWindow.toRemote(0, 74, 200, 200, 400, 100));
    }

    @Test
    void mapsTallRemoteIntoLetterboxedPanel() {
        assertEquals(new Rectangle(75, 0, 50, 200), ViewerWindow.imageArea(200, 200, 100, 400));
        assertEquals(new Point(0, 0), ViewerWindow.toRemote(75, 0, 200, 200, 100, 400));
        assertEquals(new Point(99, 399), ViewerWindow.toRemote(124, 199, 200, 200, 100, 400));
        assertNull(ViewerWindow.toRemote(74, 0, 200, 200, 100, 400));
    }
}
