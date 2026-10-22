package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.remote.RemoteProtocol.*;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Host and viewer wired back to back through in-memory transports, checked from the outside. */
class RemoteDesktopBehaviourTest {

    /** Records every message; optionally forwards it to a peer and can refuse datagrams. */
    static final class Pipe implements Transport {
        final List<Message> sent = new ArrayList<>();
        volatile boolean up = true;
        java.util.function.Consumer<byte[]> peer = b -> {};

        @Override
        public synchronized boolean sendData(byte[] data) {
            if (!up) {
                return false;
            }
            sent.add(RemoteProtocol.decode(data));
            peer.accept(data);
            return true;
        }

        @Override
        public synchronized boolean sendReliable(byte[] data) {
            sent.add(RemoteProtocol.decode(data));
            peer.accept(data);
            return true;
        }

        synchronized <T extends Message> List<T> of(Class<T> type) {
            List<T> out = new ArrayList<>();
            for (Message m : sent) {
                if (type.isInstance(m)) {
                    out.add(type.cast(m));
                }
            }
            return out;
        }

        synchronized void clear() {
            sent.clear();
        }

        /** Tiles (x,y) whose every fragment of some version was sent. */
        Set<String> completeTiles() {
            java.util.Map<String, Set<Integer>> frags = new java.util.HashMap<>();
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (Tile t : of(Tile.class)) {
                String key = t.tileX() + "," + t.tileY() + "@" + t.version();
                frags.computeIfAbsent(key, k -> new HashSet<>()).add(t.fragIndex());
                counts.put(key, t.fragCount());
            }
            Set<String> done = new HashSet<>();
            frags.forEach((k, s) -> {
                if (s.size() == counts.get(k)) {
                    done.add(k.substring(0, k.indexOf('@')));
                }
            });
            return done;
        }
    }

    static final class Inputs implements InputSink {
        final List<String> events = new ArrayList<>();

        @Override
        public synchronized void mouseMove(int x, int y) {
            events.add("move " + x + "," + y);
        }

        @Override
        public synchronized void mouseButton(int x, int y, int button, boolean pressed) {
            events.add("button " + button + " " + pressed + " " + x + "," + y);
        }

        @Override
        public synchronized void mouseWheel(int x, int y, int rotation) {
            events.add("wheel " + rotation + " " + x + "," + y);
        }

        @Override
        public synchronized void key(int keyCode, boolean pressed) {
            events.add("key " + keyCode + " " + pressed);
        }
    }

    private static BufferedImage noise(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, r.nextInt(0x1000000));
            }
        }
        return img;
    }

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static HostSettings settings(int maxBytesPerSecond, boolean control) {
        // refresh every 1000 s: periodic resends stay out of the way of these assertions
        return new HostSettings(10, 0.7f, 64, maxBytesPerSecond, 1000, control);
    }

    @Test
    void byteBudgetSpreadsAFullFrameOverSeveralSteps() {
        BufferedImage screen = noise(256, 256, 1); // 16 noisy tiles, each several KB as JPEG
        Pipe pipe = new Pipe();
        try (DesktopHost host = new DesktopHost(pipe, () -> screen, new Inputs(), settings(60_000, false))) {
            host.onPeerSession();
            host.step();
            int afterOne = pipe.completeTiles().size();
            assertTrue(afterOne >= 1 && afterOne < 16, "one step sends only what fits 6 KB: " + afterOne);
            for (int i = 0; i < 40 && pipe.completeTiles().size() < 16; i++) {
                host.step();
            }
            assertEquals(16, pipe.completeTiles().size(), "the rest follows in later steps");
            assertTrue(host.tilesSent() >= 16);
        }
    }

    @Test
    void tilesWaitWhileTheLinkIsDownAndGoOutAfterwards() {
        BufferedImage screen = solid(128, 128, Color.BLUE);
        Pipe pipe = new Pipe();
        try (DesktopHost host = new DesktopHost(pipe, () -> screen, new Inputs(), settings(10_000_000, false))) {
            host.onPeerSession();
            pipe.up = false;
            host.step();
            host.step();
            assertTrue(pipe.of(Tile.class).isEmpty());
            pipe.up = true;
            host.step();
            assertEquals(4, pipe.completeTiles().size());
        }
    }

    @Test
    void inputIsClampedAndOnlyReplayedWhileControlIsAllowed() {
        BufferedImage screen = solid(200, 100, Color.GRAY);
        Pipe pipe = new Pipe();
        Inputs inputs = new Inputs();
        try (DesktopHost host = new DesktopHost(pipe, () -> screen, inputs, settings(10_000_000, false))) {
            host.onPeerSession();
            host.step();
            host.onMessage(RemoteProtocol.encode(new MouseButton(5, 5, RemoteProtocol.BUTTON_LEFT, true)));
            host.onMessage(RemoteProtocol.encode(new Key(65, true)));
            assertTrue(inputs.events.isEmpty(), "control is off");

            host.setControlAllowed(true);
            assertEquals(new ControlState(true), pipe.of(ControlState.class).get(pipe.of(ControlState.class).size() - 1));
            host.onMessage(RemoteProtocol.encode(new MouseButton(-50, 9999, RemoteProtocol.BUTTON_RIGHT, true)));
            host.onMessage(RemoteProtocol.encode(new MouseButton(5, 5, 7, true))); // no such button
            host.onMessage(RemoteProtocol.encode(new Key(0, true)));               // no such key
            host.onMessage(RemoteProtocol.encode(new MouseWheel(10, 20, 3)));
            host.onMessage(new byte[]{1, 2, 3});                                    // garbage
            List<String> notMoves = inputs.events.stream().filter(e -> !e.startsWith("move")).toList();
            assertEquals(List.of("button 3 true 0,99", "wheel 3 10,20"), notMoves);

            host.setControlAllowed(false);
            host.onMessage(RemoteProtocol.encode(new Key(65, true)));
            assertEquals(notMoves, inputs.events.stream().filter(e -> !e.startsWith("move")).toList());
        }
    }

    @Test
    void viewerFollowsTheHostIncludingResizeAndControlChanges() {
        BufferedImage[] screen = {solid(128, 128, Color.RED)};
        Pipe hostOut = new Pipe(), viewerOut = new Pipe();
        Inputs inputs = new Inputs();
        try (DesktopHost host = new DesktopHost(hostOut, () -> screen[0], inputs, settings(10_000_000, false));
             DesktopViewer viewer = new DesktopViewer(viewerOut)) {
            hostOut.peer = viewer::onMessage;
            viewerOut.peer = host::onMessage;

            viewer.onPeerSession(); // ViewRequest -> ScreenInfo + full frame
            host.step();
            assertEquals(new java.awt.Dimension(128, 128), viewer.screenSize());
            assertTrue(Math.abs(((viewer.snapshot().getRGB(100, 100) >> 16) & 0xFF) - 255) < 10);

            // viewer drops input while the host has not allowed control
            viewerOut.clear();
            viewer.key(65, true);
            viewer.mouseMove(1, 1);
            assertTrue(viewerOut.sent.isEmpty());

            host.setControlAllowed(true);
            assertTrue(viewer.controlAllowed());
            viewer.key(65, true);
            assertTrue(inputs.events.contains("key 65 true"));

            screen[0] = solid(192, 128, Color.GREEN);
            host.step();
            assertEquals(new java.awt.Dimension(192, 128), viewer.screenSize());
            int g = (viewer.snapshot().getRGB(150, 64) >> 8) & 0xFF;
            assertTrue(g > 200, "new size is drawn with the new content: green=" + g);

            viewerOut.clear();
            viewer.requestRefresh();
            assertEquals(List.of(new Refresh()), viewerOut.sent);
        }
    }

    @Test
    void anOlderTileVersionArrivingLateDoesNotOverwriteANewerOne() throws Exception {
        Pipe out = new Pipe();
        try (DesktopViewer viewer = new DesktopViewer(out)) {
            viewer.onMessage(RemoteProtocol.encode(new ScreenInfo(64, 64, 64, false)));
            viewer.onMessage(RemoteProtocol.encode(new Tile(5, 0, 0, 0, 1, jpeg(solid(64, 64, Color.WHITE)))));
            viewer.onMessage(RemoteProtocol.encode(new Tile(4, 0, 0, 0, 1, jpeg(solid(64, 64, Color.BLACK)))));
            int r = (viewer.snapshot().getRGB(32, 32) >> 16) & 0xFF;
            assertTrue(r > 240, "still white: " + r);
            assertEquals(1, viewer.tilesDrawn());
        }
    }

    private static byte[] jpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        assertTrue(out.size() <= RemoteProtocol.MAX_TILE_DATA);
        return out.toByteArray();
    }
}
