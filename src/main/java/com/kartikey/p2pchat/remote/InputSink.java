package com.kartikey.p2pchat.remote;

/**
 * Replays the viewer's mouse and keyboard on the host; {@link RobotInput} in the app, a fake in
 * tests. Coordinates are screen pixels as captured by {@link ScreenSource}, already clamped to
 * the screen.
 */
public interface InputSink {

    void mouseMove(int x, int y);

    /** {@code button} is one of {@link RemoteProtocol#BUTTON_LEFT}, MIDDLE or RIGHT. */
    void mouseButton(int x, int y, int button, boolean pressed);

    void mouseWheel(int x, int y, int rotation);

    /** {@code keyCode} is a {@link java.awt.event.KeyEvent} VK_ code. */
    void key(int keyCode, boolean pressed);
}
