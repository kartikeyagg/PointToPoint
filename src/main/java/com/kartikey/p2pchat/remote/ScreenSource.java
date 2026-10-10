package com.kartikey.p2pchat.remote;

import java.awt.image.BufferedImage;

/** Supplies screenshots of the shared screen; {@link RobotScreen} in the app, a fake in tests. */
public interface ScreenSource {

    /**
     * The whole shared screen as a {@link BufferedImage#TYPE_INT_RGB} image. Its size may change
     * between calls (resolution change); coordinates in input events use the same pixels.
     */
    BufferedImage capture();
}
