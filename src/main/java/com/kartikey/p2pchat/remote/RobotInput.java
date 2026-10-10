package com.kartikey.p2pchat.remote;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;

/** Input sink backed by {@link Robot}. */
public final class RobotInput implements InputSink {
    private final Robot robot;
    private final Rectangle screenBounds;

    public RobotInput(Rectangle screenBounds) throws AWTException {
        this.screenBounds = new Rectangle(screenBounds);
        robot = new Robot();
    }

    @Override
    public void mouseMove(int x, int y) {
        robot.mouseMove(screenBounds.x + x, screenBounds.y + y);
    }

    @Override
    public void mouseButton(int x, int y, int button, boolean pressed) {
        mouseMove(x, y);
        int mask = InputEvent.getMaskForButton(button);
        if (pressed) {
            robot.mousePress(mask);
        } else {
            robot.mouseRelease(mask);
        }
    }

    @Override
    public void mouseWheel(int x, int y, int rotation) {
        mouseMove(x, y);
        robot.mouseWheel(rotation);
    }

    @Override
    public void key(int keyCode, boolean pressed) {
        try {
            if (pressed) {
                robot.keyPress(keyCode);
            } else {
                robot.keyRelease(keyCode);
            }
        } catch (IllegalArgumentException ignored) {
            // Some platform-specific virtual-key codes are not accepted by Robot.
        }
    }
}
