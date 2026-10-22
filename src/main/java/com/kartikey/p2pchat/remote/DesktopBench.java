package com.kartikey.p2pchat.remote;

import java.awt.Graphics;
import java.awt.image.BufferedImage;

/**
 * {@code --desktop bench}: measures how fast this computer can share its screen, without
 * connecting to anyone. Screen capture and tile encoding are the host's two costs per frame.
 */
public final class DesktopBench {

    private static final int RUNS = 10;

    private DesktopBench() {}

    public static int run(com.kartikey.p2pchat.Options o) {
        ScreenSource screen = null;
        try {
            RobotScreen robot = new RobotScreen();
            screen = DesktopApp.openScreen(o, robot);
            String kind = screen instanceof NativeScreen ? "native helper" : "Java Robot";
            if (screen instanceof NativeScreen nativeScreen) {
                long before = nativeScreen.frames();
                Thread.sleep(2000);
                System.out.printf("native helper:     %d frames in 2 s (it only sends frames when the screen changes)%n",
                        nativeScreen.frames() - before);
            }
            BufferedImage image = null;
            for (int i = 0; i < 3; i++) {
                image = screen.capture();
            }
            long start = System.nanoTime();
            for (int i = 0; i < RUNS; i++) {
                image = screen.capture();
            }
            double captureMs = (System.nanoTime() - start) / 1e6 / RUNS;
            System.out.printf("capture source:    %s%n", kind);

            // Alternate between the screen and a shifted copy, so every frame is a full-screen change.
            BufferedImage shifted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics g = shifted.getGraphics();
            g.drawImage(image, 40, 25, null);
            g.dispose();
            BufferedImage first = image;
            int[] frame = {0};
            long[] bytes = {0};
            ScreenSource alternating = () -> frame[0]++ % 2 == 0 ? first : shifted;
            Transport counting = new Transport() {
                @Override
                public boolean sendData(byte[] data) {
                    bytes[0] += data.length;
                    return true;
                }

                @Override
                public boolean sendReliable(byte[] data) {
                    return true;
                }
            };
            InputSink none = new InputSink() {
                @Override public void mouseMove(int x, int y) {}
                @Override public void mouseButton(int x, int y, int button, boolean pressed) {}
                @Override public void mouseWheel(int x, int y, int rotation) {}
                @Override public void key(int keyCode, boolean pressed) {}
            };
            HostSettings settings = DesktopApp.hostSettings(o, screen instanceof NativeScreen);
            double encodeMs;
            long tiles;
            try (DesktopHost host = new DesktopHost(counting, alternating, none, settings)) {
                for (int i = 0; i < 4; i++) {
                    host.step();
                }
                long tiles0 = host.tilesSent();
                bytes[0] = 0;
                start = System.nanoTime();
                for (int i = 0; i < RUNS; i++) {
                    host.step();
                }
                encodeMs = (System.nanoTime() - start) / 1e6 / RUNS;
                tiles = (host.tilesSent() - tiles0) / RUNS;
            }
            double frameMs = captureMs + encodeMs;
            System.out.printf("screen:            %d x %d%n", image.getWidth(), image.getHeight());
            System.out.printf("capture:           %.1f ms per frame%n", captureMs);
            System.out.printf("full-screen change: %.1f ms to find, encode and send %d tiles (%d KB)%n",
                    encodeMs, tiles, bytes[0] / RUNS / 1024);
            System.out.printf("=> about %.0f frames/s while the whole screen changes (limit %d)%n",
                    Math.min(settings.fps(), 1000 / frameMs), settings.fps());
            return 0;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } finally {
            if (screen instanceof NativeScreen nativeScreen) {
                nativeScreen.close();
            }
        }
    }
}
