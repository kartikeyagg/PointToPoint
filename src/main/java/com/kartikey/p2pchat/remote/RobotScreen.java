package com.kartikey.p2pchat.remote;

import java.awt.AWTException;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/** Screen source backed by the default desktop device. */
public final class RobotScreen implements ScreenSource {
    private final Rectangle bounds;
    private final Robot robot;

    public RobotScreen() throws AWTException {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        GraphicsConfiguration configuration = device.getDefaultConfiguration();
        bounds = new Rectangle(configuration.getBounds());
        robot = new Robot(device);
    }

    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    @Override
    public BufferedImage capture() {
        BufferedImage captured = robot.createScreenCapture(bounds);
        if (captured.getType() == BufferedImage.TYPE_INT_RGB) {
            return captured; // already the format the host works on; skip a full-screen copy
        }
        BufferedImage result = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics graphics = result.getGraphics();
        try {
            graphics.drawImage(captured, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
