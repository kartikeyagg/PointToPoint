package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.PacketCodec;
import com.kartikey.p2pchat.PeerLink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Host and viewer over two real, encrypted PeerLinks on loopback; only the screen and input are fake. */
class RemoteDesktopIntegrationTest {

    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) {
            toClose.get(i).close();
        }
    }

    /** Smooth gradient so JPEG stays close to the original; {@code mark} paints one bright square. */
    private static BufferedImage screen(int w, int h, boolean mark) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / w, g = y * 255 / h, b = 128;
                if (mark && x >= 200 && x < 240 && y >= 100 && y < 140) {
                    r = g = b = 255;
                }
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static double meanDiff(BufferedImage a, BufferedImage b, int x0, int y0, int w, int h) {
        long sum = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    sum += Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF));
                }
            }
        }
        return sum / (3.0 * w * h);
    }

    private static boolean await(BooleanSupplier condition, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static final class RecordingInput implements InputSink {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        @Override
        public void mouseMove(int x, int y) {
            events.add("move " + x + "," + y);
        }

        @Override
        public void mouseButton(int x, int y, int button, boolean pressed) {
            events.add("button " + button + (pressed ? " down " : " up ") + x + "," + y);
        }

        @Override
        public void mouseWheel(int x, int y, int rotation) {
            events.add("wheel " + rotation);
        }

        @Override
        public void key(int keyCode, boolean pressed) {
            events.add("key " + keyCode + (pressed ? " down" : " up"));
        }
    }

    @Test
    void viewerSeesTheHostScreenAndControlsIt() throws Exception {
        int ph = freePort(), pv = freePort();
        BufferedImage[] current = {screen(320, 200, false)};
        ScreenSource fakeScreen = () -> current[0];
        RecordingInput input = new RecordingInput();

        LinkBridge hostBridge = new LinkBridge(), viewerBridge = new LinkBridge();
        PeerLink hostLink = new PeerLink(ph, new InetSocketAddress("127.0.0.1", pv), new PacketCodec("secret"), hostBridge);
        PeerLink viewerLink = new PeerLink(pv, new InetSocketAddress("127.0.0.1", ph), new PacketCodec("secret"), viewerBridge);
        toClose.add(hostLink);
        toClose.add(viewerLink);
        DesktopHost host = new DesktopHost(Transport.of(hostLink), fakeScreen, input,
                new HostSettings(20, 0.9f, 64, 5_000_000, 3, false));
        DesktopViewer viewer = new DesktopViewer(Transport.of(viewerLink));
        toClose.add(host);
        toClose.add(viewer);
        hostBridge.host = host;
        viewerBridge.viewer = viewer;
        host.start();
        hostLink.start();
        viewerLink.start();

        assertTrue(await(() -> viewer.screenSize() != null, 5000), "viewer learns the screen size");
        assertEquals(320, viewer.screenSize().width);
        assertEquals(200, viewer.screenSize().height);
        assertTrue(await(() -> {
            BufferedImage s = viewer.snapshot();
            return s != null && meanDiff(s, current[0], 0, 0, 320, 200) < 6;
        }, 8000), "the whole screen arrives through encrypted datagrams");

        // a change on the host shows up on the viewer
        current[0] = screen(320, 200, true);
        assertTrue(await(() -> meanDiff(viewer.snapshot(), current[0], 200, 100, 40, 40) < 12, 5000),
                "changed region is updated");

        // control is off: input is not replayed
        viewer.mouseButton(10, 10, RemoteProtocol.BUTTON_LEFT, true);
        assertNull(input.events.poll(500, TimeUnit.MILLISECONDS));

        host.setControlAllowed(true);
        assertTrue(await(viewer::controlAllowed, 3000), "viewer is told control is allowed");
        viewer.mouseButton(250, 120, RemoteProtocol.BUTTON_LEFT, true);
        viewer.mouseButton(250, 120, RemoteProtocol.BUTTON_LEFT, false);
        viewer.key(java.awt.event.KeyEvent.VK_A, true);
        viewer.key(java.awt.event.KeyEvent.VK_A, false);
        List<String> got = new ArrayList<>();
        while (got.size() < 4) {
            String e = input.events.poll(3, TimeUnit.SECONDS);
            if (e == null) {
                break;
            }
            if (!e.startsWith("move")) {
                got.add(e);
            }
        }
        assertEquals(List.of("button 1 down 250,120", "button 1 up 250,120",
                "key " + java.awt.event.KeyEvent.VK_A + " down", "key " + java.awt.event.KeyEvent.VK_A + " up"), got);
        assertTrue(host.tilesSent() > 0);
    }
}
