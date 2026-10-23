package com.kartikey.p2pchat.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Linux capture helper (native/linux/screenstream.c, built by run.sh into
 * target/screenstream) against the live X display. Skipped where there is no helper or display.
 */
class LinuxScreenHelperTest {

    private static final Path HELPER = Path.of("target", "screenstream");

    @Test
    void helperPicturesMatchJavaCapture() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"), "Linux only");
        assumeTrue(System.getenv("DISPLAY") != null && !"wayland".equals(System.getenv("XDG_SESSION_TYPE")), "needs X11");
        assumeTrue(!GraphicsEnvironment.isHeadless() && Files.isExecutable(HELPER), "needs a display and ./run.sh's helper build");

        RobotScreen robot = new RobotScreen();
        try (NativeScreen screen = NativeScreen.start(HELPER, 30, null, 5000)) {
            Dimension size = screen.size();
            Rectangle bounds = robot.bounds();
            assertNotNull(size);
            assertEquals(bounds.width, size.width);
            assertEquals(bounds.height, size.height);

            BufferedImage fromHelper = screen.capture();
            BufferedImage fromRobot = robot.capture();
            // Same pixels in the same colour order, apart from whatever changed between the shots.
            int same = 0, samples = 0;
            for (int y = 0; y < size.height; y += 7) {
                for (int x = 0; x < size.width; x += 7) {
                    samples++;
                    if ((fromHelper.getRGB(x, y) & 0xffffff) == (fromRobot.getRGB(x, y) & 0xffffff)) {
                        same++;
                    }
                }
            }
            assertTrue(same > samples * 0.9, same + " of " + samples + " sampled pixels match");
        }
    }
}
