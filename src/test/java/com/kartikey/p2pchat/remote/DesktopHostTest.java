package com.kartikey.p2pchat.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesktopHostTest {
    @Test
    void unchangedFrameOnlyGetsOnePeriodicRefreshTile() {
        BufferedImage frame = image(128, 64);
        RecordingTransport transport = new RecordingTransport();
        DesktopHost host = host(transport, new Source(frame), new RecordingInput(),
                new HostSettings(1, .7f, 64, 1_000_000, 99, true));
        host.step();
        assertEquals(2, transport.tiles().size());
        transport.clearData();
        host.step();
        assertEquals(1, transport.tiles().size());
    }

    @Test
    void changedTileHasOneVersionAndDecodes() throws Exception {
        BufferedImage frame = image(64, 64);
        RecordingTransport transport = new RecordingTransport();
        DesktopHost host = host(transport, new Source(frame), new RecordingInput(), HostSettings.defaults());
        host.step();
        transport.clearData();
        frame.setRGB(2, 3, 0x00ffff);
        host.step();
        List<RemoteProtocol.Tile> tiles = transport.tiles();
        assertFalse(tiles.isEmpty());
        assertTrue(tiles.stream().allMatch(t -> t.version() == tiles.get(0).version()));
        byte[] joined = tiles.stream().sorted(java.util.Comparator.comparingInt(RemoteProtocol.Tile::fragIndex))
                .collect(java.io.ByteArrayOutputStream::new, (out, tile) -> out.writeBytes(tile.data()), (a, b) -> a.writeBytes(b.toByteArray()))
                .toByteArray();
        BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(joined));
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
    }

    @Test
    void failedSendRemainsDirtyAndInputIsClamped() {
        RecordingTransport transport = new RecordingTransport();
        transport.acceptData = false;
        RecordingInput input = new RecordingInput();
        DesktopHost host = host(transport, new Source(image(10, 8)), input,
                new HostSettings(1, .7f, 64, 1_000_000, 99, true));
        host.step();
        assertEquals(0, host.tilesSent());
        transport.acceptData = true;
        host.step();
        assertEquals(1, host.tilesSent());
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseMove(99, -3)));
        assertEquals("9,0", input.moves.get(0));
        host.setControlAllowed(false);
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseMove(1, 1)));
        assertEquals(1, input.moves.size());
        assertTrue(transport.reliable.stream().anyMatch(m -> m instanceof RemoteProtocol.ControlState));
    }

    @Test
    void gameMouseMovesRelativelyAndClicksWithoutMoving() {
        RecordingTransport transport = new RecordingTransport();
        RecordingInput input = new RecordingInput();
        DesktopHost host = host(transport, new Source(image(10, 8)), input,
                new HostSettings(1, .7f, 64, 1_000_000, 99, true));
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseDelta(-3, 4)));
        assertTrue(input.moves.isEmpty(), "no input before the first frame");
        host.step();
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseDelta(-3, 4)));
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseDelta(1_000_000, -1_000_000)));
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseButton(
                RemoteProtocol.HERE, RemoteProtocol.HERE, RemoteProtocol.BUTTON_LEFT, true)));
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseWheel(RemoteProtocol.HERE, RemoteProtocol.HERE, 2)));
        assertEquals(List.of("by -3,4", "by " + DesktopHost.MAX_DELTA + "," + -DesktopHost.MAX_DELTA,
                "button 1 true", "wheel 2"), input.moves);
        host.setControlAllowed(false);
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseDelta(1, 1)));
        host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseButton(
                RemoteProtocol.HERE, RemoteProtocol.HERE, RemoteProtocol.BUTTON_LEFT, false)));
        assertEquals(4, input.moves.size(), "view only ignores game mouse input too");
    }

    @Test
    void inputIsNotHeldUpByASlowCapture() throws Exception {
        BufferedImage frame = image(64, 64);
        java.util.concurrent.CountDownLatch capturing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean block = new java.util.concurrent.atomic.AtomicBoolean();
        ScreenSource slow = () -> {
            if (block.get()) {
                capturing.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return frame;
        };
        RecordingInput input = new RecordingInput();
        DesktopHost host = host(new RecordingTransport(), slow, input, new HostSettings(1, .7f, 64, 1_000_000, 99, true));
        host.step();
        block.set(true);
        Thread stepper = new Thread(host::step);
        stepper.start();
        assertTrue(capturing.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            // The capture thread is stuck inside step(); input must still go through at once.
            Thread sender = new Thread(() -> host.onMessage(RemoteProtocol.encode(new RemoteProtocol.MouseMove(5, 6))));
            sender.start();
            sender.join(2000);
            assertFalse(sender.isAlive());
            assertEquals(List.of("5,6"), input.moves);
        } finally {
            release.countDown();
            stepper.join(5000);
            host.close();
        }
    }

    private static DesktopHost host(RecordingTransport transport, ScreenSource source, RecordingInput input, HostSettings settings) {
        return new DesktopHost(transport, source, input, settings);
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static final class Source implements ScreenSource {
        private final BufferedImage image;
        Source(BufferedImage image) { this.image = image; }
        @Override public BufferedImage capture() { return image; }
    }

    private static final class RecordingTransport implements Transport {
        boolean acceptData = true;
        final List<RemoteProtocol.Message> data = new ArrayList<>();
        final List<RemoteProtocol.Message> reliable = new ArrayList<>();
        @Override public boolean sendData(byte[] bytes) {
            if (!acceptData) return false;
            data.add(RemoteProtocol.decode(bytes));
            return true;
        }
        @Override public boolean sendReliable(byte[] bytes) {
            reliable.add(RemoteProtocol.decode(bytes));
            return true;
        }
        List<RemoteProtocol.Tile> tiles() { return data.stream().filter(RemoteProtocol.Tile.class::isInstance)
                .map(RemoteProtocol.Tile.class::cast).toList(); }
        void clearData() { data.clear(); }
    }

    private static final class RecordingInput implements InputSink {
        final List<String> moves = new ArrayList<>();
        @Override public void mouseMove(int x, int y) { moves.add(x + "," + y); }
        @Override public void mouseButton(int x, int y, int button, boolean pressed) { }
        @Override public void mouseWheel(int x, int y, int rotation) { }
        @Override public void key(int keyCode, boolean pressed) { }
        @Override public void mouseMoveBy(int dx, int dy) { moves.add("by " + dx + "," + dy); }
        @Override public void mouseButtonHere(int button, boolean pressed) { moves.add("button " + button + " " + pressed); }
        @Override public void mouseWheelHere(int rotation) { moves.add("wheel " + rotation); }
    }
}
