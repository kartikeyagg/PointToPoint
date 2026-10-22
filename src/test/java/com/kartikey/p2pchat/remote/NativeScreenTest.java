package com.kartikey.p2pchat.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class NativeScreenTest {

    /** Builds frames in the helper's wire format. */
    private static final class Frames {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Frames frame(int width, int height, int... rects) {
            ints(NativeScreen.MAGIC, width, height, rects.length / 5);
            for (int i = 0; i < rects.length; i += 5) {
                int x = rects[i], y = rects[i + 1], w = rects[i + 2], h = rects[i + 3], rgb = rects[i + 4];
                ints(x, y, w, h);
                for (int p = 0; p < w * h; p++) {
                    out.write(rgb & 0xff);          // B
                    out.write((rgb >> 8) & 0xff);   // G
                    out.write((rgb >> 16) & 0xff);  // R
                    out.write(0xff);                // A
                }
            }
            return this;
        }

        Frames ints(int... values) {
            ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                b.putInt(v);
            }
            out.writeBytes(b.array());
            return this;
        }

        InputStream stream() {
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    void appliesFullFrameThenDirtyRects() throws Exception {
        PipedOutputStream pipe = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(pipe, 1 << 16);
        NativeScreen screen = new NativeScreen(in, null, null);
        pipe.write(new Frames().frame(4, 3, 0, 0, 4, 3, 0x102030).out.toByteArray());
        pipe.flush();
        screen.awaitFirstFrame(2000);
        assertEquals(new java.awt.Dimension(4, 3), screen.size());
        BufferedImage first = screen.capture();
        assertEquals(BufferedImage.TYPE_INT_RGB, first.getType());
        assertEquals(0x102030, first.getRGB(3, 2) & 0xffffff);

        pipe.write(new Frames().frame(4, 3, 1, 1, 2, 1, 0xabcdef).out.toByteArray());
        pipe.flush();
        long deadline = System.currentTimeMillis() + 2000;
        while (screen.frames() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        BufferedImage second = screen.capture();
        assertEquals(0xabcdef, second.getRGB(1, 1) & 0xffffff);
        assertEquals(0xabcdef, second.getRGB(2, 1) & 0xffffff);
        assertEquals(0x102030, second.getRGB(3, 1) & 0xffffff);
        assertEquals(0x102030, first.getRGB(1, 1) & 0xffffff, "earlier captures are copies");
        screen.close();
    }

    @Test
    void fallsBackWhenTheHelperStops() throws Exception {
        BufferedImage robotFrame = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        NativeScreen screen = new NativeScreen(new Frames().frame(4, 3, 0, 0, 4, 3, 0x00ff00).stream(),
                () -> robotFrame, null);
        long deadline = System.currentTimeMillis() + 2000;
        while (screen.failure() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertNotNull(screen.failure());
        assertSame(robotFrame, screen.capture());
    }

    @Test
    void rejectsGarbageAndRectsOutsideTheFrame() {
        NativeScreen garbage = new NativeScreen(new Frames().ints(0x12345678, 4, 3, 0).stream(), null, null);
        IOException e = assertThrows(IOException.class, () -> garbage.awaitFirstFrame(2000));
        assertTrue(e.getMessage().contains("magic"), e.getMessage());

        NativeScreen outside = new NativeScreen(new Frames().frame(4, 3, 2, 0, 3, 1, 0).stream(), null, null);
        e = assertThrows(IOException.class, () -> outside.awaitFirstFrame(2000));
        assertTrue(e.getMessage().contains("outside"), e.getMessage());
        assertThrows(IllegalStateException.class, outside::capture);
    }

    @Test
    void timesOutWhenNoFrameArrives() throws Exception {
        PipedInputStream silent = new PipedInputStream(new PipedOutputStream());
        NativeScreen screen = new NativeScreen(silent, null, null);
        assertThrows(IOException.class, () -> screen.awaitFirstFrame(100));
        assertNull(screen.size());
        screen.close();
    }

    @Test
    void startRunsAHelperProcess() throws Exception {
        // A shell script stands in for the Swift helper: one 2x1 frame, then it stays alive.
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("helper");
        java.nio.file.Path data = dir.resolve("frame.bin");
        java.nio.file.Files.write(data, new Frames().frame(2, 1, 0, 0, 2, 1, 0x0000ff).out.toByteArray());
        java.nio.file.Path helper = dir.resolve("screenstream");
        java.nio.file.Files.writeString(helper, "#!/bin/sh\n[ \"$1\" = --fps ] || exit 3\ncat '" + data + "'\nexec sleep 30\n");
        assertTrue(helper.toFile().setExecutable(true));
        try (NativeScreen screen = NativeScreen.start(helper, 30, null, 5000)) {
            assertEquals(0x0000ff, screen.capture().getRGB(1, 0) & 0xffffff);
        }
        assertThrows(IOException.class, () -> NativeScreen.start(dir.resolve("missing"), 30, null, 1000));
    }
}
