package com.kartikey.p2pchat.remote;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopViewerTest {

    @Test
    void reassemblesShuffledJpegFragments() throws Exception {
        FakeTransport transport = new FakeTransport();
        DesktopViewer viewer = new DesktopViewer(transport);
        BufferedImage expected = gradient(80, 60);
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.ScreenInfo(80, 60, 80, true)));
        List<byte[]> messages = tileMessages(1, 0, 0, jpeg(expected), 37);
        Collections.shuffle(messages, new java.util.Random(7));
        for (byte[] message : messages) {
            viewer.onMessage(message);
        }
        assertEquals(1, viewer.tilesDrawn());
        assertTrue(meanDifference(expected, viewer.snapshot()) < 8.0);
    }

    @Test
    void missingOldAndInvalidTilesDoNotChangeCanvas() throws Exception {
        DesktopViewer viewer = new DesktopViewer(new FakeTransport());
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.Tile(1, 0, 0, 0, 1, new byte[]{1})));
        assertNull(viewer.snapshot());
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.ScreenInfo(20, 20, 20, false)));
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.Tile(1, 2, 0, 0, 1, new byte[]{1})));
        viewer.onMessage(new byte[]{127, 3, 4});
        BufferedImage red = solid(20, 20, Color.RED);
        for (byte[] message : tileMessages(2, 0, 0, jpeg(red), 10000)) {
            viewer.onMessage(message);
        }
        BufferedImage blue = solid(20, 20, Color.BLUE);
        List<byte[]> missing = tileMessages(3, 0, 0, jpeg(blue), 10);
        viewer.onMessage(missing.get(0));
        for (byte[] message : tileMessages(1, 0, 0, jpeg(blue), 10000)) {
            viewer.onMessage(message);
        }
        assertEquals(1, viewer.tilesDrawn());
        assertTrue(red(viewer.snapshot().getRGB(10, 10)) > 150);
    }

    @Test
    void sendsSessionRefreshAndInputOnlyWhenAllowed() {
        FakeTransport transport = new FakeTransport();
        DesktopViewer viewer = new DesktopViewer(transport);
        viewer.onPeerSession();
        viewer.mouseMove(1, 2);
        viewer.key(65, true);
        viewer.requestRefresh();
        assertEquals(2, transport.messages.size());
        assertTrue(transport.messages.get(0) instanceof RemoteProtocol.ViewRequest);
        assertTrue(transport.messages.get(1) instanceof RemoteProtocol.Refresh);
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.ScreenInfo(10, 10, 10, true)));
        viewer.mouseMove(1, 2);
        viewer.mouseButton(1, 2, RemoteProtocol.BUTTON_LEFT, true);
        viewer.mouseWheel(1, 2, -1);
        viewer.key(65, true);
        assertEquals(6, transport.messages.size());
        assertTrue(transport.data.get(0) instanceof RemoteProtocol.MouseMove);
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.ControlState(false)));
        assertFalse(viewer.controlAllowed());
        viewer.key(66, true);
        assertEquals(6, transport.messages.size());
    }

    @Test
    void renderThreadKeepsTheNetworkThreadFreeAndDrawsOnlyTheNewestTile() throws Exception {
        DesktopViewer viewer = DesktopViewer.withRenderThread(new FakeTransport());
        java.util.concurrent.CountDownLatch painting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        viewer.addListener(new DesktopViewer.Listener() {
            @Override
            public void tileDrawn(int x, int y, BufferedImage tile) {
                painting.countDown();
                try {
                    release.await(); // a slow paint
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.ScreenInfo(32, 32, 32, false)));
        viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.Tile(1, 0, 0, 0, 1, jpeg(solid(32, 32, Color.BLACK)))));
        assertTrue(painting.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            long start = System.nanoTime();
            Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};
            for (int v = 0; v < colors.length; v++) {
                viewer.onMessage(RemoteProtocol.encode(new RemoteProtocol.Tile(2 + v, 0, 0, 0, 1, jpeg(solid(32, 32, colors[v])))));
            }
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "network thread waited for the paint");
        } finally {
            release.countDown();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (viewer.tilesDrawn() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(100);
        assertEquals(2, viewer.tilesDrawn(), "the first tile, then only the newest of the queued ones");
        assertTrue(red(viewer.snapshot().getRGB(16, 16)) > 200 && ((viewer.snapshot().getRGB(16, 16) >> 8) & 0xff) > 200);
        viewer.close();
    }

    private static List<byte[]> tileMessages(int version, int x, int y, byte[] jpeg, int partSize) {
        List<byte[]> result = new ArrayList<>();
        int count = (jpeg.length + partSize - 1) / partSize;
        for (int index = 0; index < count; index++) {
            int start = index * partSize;
            int end = Math.min(jpeg.length, start + partSize);
            byte[] part = java.util.Arrays.copyOfRange(jpeg, start, end);
            result.add(RemoteProtocol.encode(new RemoteProtocol.Tile(version, x, y, index, count, part)));
        }
        return result;
    }

    private static byte[] jpeg(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(x * 255 / width, y * 255 / height, (x + y) * 255 / (width + height)).getRGB());
            }
        }
        return image;
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color.getRGB());
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static double meanDifference(BufferedImage expected, BufferedImage actual) {
        long difference = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int a = expected.getRGB(x, y), b = actual.getRGB(x, y);
                difference += Math.abs((a >>> 16 & 255) - (b >>> 16 & 255));
                difference += Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
                difference += Math.abs((a & 255) - (b & 255));
            }
        }
        return difference / (double) (expected.getWidth() * expected.getHeight() * 3);
    }

    private static int red(int rgb) {
        return rgb >>> 16 & 255;
    }

    private static final class FakeTransport implements Transport {
        private final List<RemoteProtocol.Message> messages = new ArrayList<>();
        private final List<RemoteProtocol.Message> data = new ArrayList<>();

        @Override
        public boolean sendData(byte[] bytes) {
            RemoteProtocol.Message message = RemoteProtocol.decode(bytes);
            messages.add(message);
            data.add(message);
            return true;
        }

        @Override
        public boolean sendReliable(byte[] bytes) {
            messages.add(RemoteProtocol.decode(bytes));
            return true;
        }
    }
}
