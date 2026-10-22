package com.kartikey.p2pchat.remote;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Screen source fed by a native capture helper ({@code screenstream}, macOS ScreenCaptureKit)
 * that is much faster than {@link java.awt.Robot}. The helper writes frames to its stdout:
 *
 * <pre>
 * all values little-endian uint32
 * frame = magic "SSF1" | width | height | rectCount | rect*
 * rect  = x | y | w | h | w*h*4 bytes BGRA, rows top to bottom, no padding
 * </pre>
 *
 * Rects are the regions that changed; the first frame (and the first after a size change) covers
 * the whole screen. A reader thread applies them to a private buffer, and {@link #capture()}
 * returns a copy of it. When the stream fails, capture continues with the fallback source.
 */
public final class NativeScreen implements ScreenSource, AutoCloseable {

    static final int MAGIC = 0x31465353; // "SSF1" read little-endian
    private static final int MAX_SIDE = 16_384;
    private static final int MAX_RECTS = 65_536;

    private final Object lock = new Object();
    private final DataInputStream in;
    private final ScreenSource fallback;
    private final Process process;
    private final Thread reader;
    private int[] pixels;
    private int width;
    private int height;
    private long frames;
    private volatile String failure;

    /** Reads frames from {@code in}; uses {@code fallback} once the stream ends or is invalid. */
    NativeScreen(InputStream in, ScreenSource fallback, Process process) {
        this.in = new DataInputStream(new BufferedInputStream(in, 1 << 20));
        this.fallback = fallback;
        this.process = process;
        reader = new Thread(this::readLoop, "native-screen");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Starts the helper and waits for its first full frame.
     *
     * @throws IOException if the helper can't start or sends no valid frame within {@code timeoutMs}
     */
    public static NativeScreen start(Path helper, int fps, ScreenSource fallback, long timeoutMs) throws IOException {
        if (!Files.isExecutable(helper)) {
            throw new IOException("no capture helper at " + helper);
        }
        Process process = new ProcessBuilder(List.of(helper.toString(), "--fps", Integer.toString(fps)))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        NativeScreen screen = new NativeScreen(process.getInputStream(), fallback, process);
        try {
            screen.awaitFirstFrame(timeoutMs);
        } catch (IOException e) {
            screen.close();
            throw e;
        }
        return screen;
    }

    void awaitFirstFrame(long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (lock) {
            while (frames == 0 && failure == null) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw new IOException("capture helper sent no frame within " + timeoutMs + " ms");
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while starting the capture helper");
                }
            }
            if (frames == 0) {
                throw new IOException("capture helper failed: " + failure);
            }
        }
    }

    /** Frame size of the native stream, or null before the first frame. */
    public java.awt.Dimension size() {
        synchronized (lock) {
            return frames == 0 ? null : new java.awt.Dimension(width, height);
        }
    }

    /** Why the native stream stopped, or null while it works. */
    public String failure() {
        return failure;
    }

    long frames() {
        synchronized (lock) {
            return frames;
        }
    }

    @Override
    public BufferedImage capture() {
        synchronized (lock) {
            if (failure == null && frames > 0) {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                System.arraycopy(pixels, 0, target, 0, target.length);
                return image;
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("native capture stopped: " + failure);
        }
        return fallback.capture();
    }

    @Override
    public void close() {
        if (failure == null) {
            failure = "closed";
        }
        if (process != null) {
            process.destroy();
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private void readLoop() {
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        byte[] row = new byte[0];
        try {
            while (true) {
                header.clear();
                in.readFully(header.array(), 0, 16);
                int magic = header.getInt(0);
                int frameWidth = header.getInt(4), frameHeight = header.getInt(8), rectCount = header.getInt(12);
                if (magic != MAGIC) {
                    throw new IOException("bad frame magic " + Integer.toHexString(magic));
                }
                if (frameWidth <= 0 || frameHeight <= 0 || frameWidth > MAX_SIDE || frameHeight > MAX_SIDE
                        || rectCount < 0 || rectCount > MAX_RECTS) {
                    throw new IOException("bad frame header " + frameWidth + "x" + frameHeight + " rects=" + rectCount);
                }
                synchronized (lock) {
                    if (pixels == null || width != frameWidth || height != frameHeight) {
                        pixels = new int[frameWidth * frameHeight];
                        width = frameWidth;
                        height = frameHeight;
                    }
                }
                for (int r = 0; r < rectCount; r++) {
                    header.clear();
                    in.readFully(header.array(), 0, 16);
                    int x = header.getInt(0), y = header.getInt(4), w = header.getInt(8), h = header.getInt(12);
                    if (x < 0 || y < 0 || w < 0 || h < 0 || x > frameWidth - w || y > frameHeight - h) {
                        throw new IOException("rect outside the frame: " + x + "," + y + " " + w + "x" + h);
                    }
                    if (row.length < w * 4) {
                        row = new byte[w * 4];
                    }
                    for (int line = 0; line < h; line++) {
                        in.readFully(row, 0, w * 4);
                        // Rows are applied one at a time, so capture() never waits for a whole frame.
                        synchronized (lock) {
                            int offset = (y + line) * frameWidth + x;
                            for (int i = 0, p = 0; i < w; i++, p += 4) {
                                pixels[offset + i] = (row[p] & 0xff) | (row[p + 1] & 0xff) << 8 | (row[p + 2] & 0xff) << 16;
                            }
                        }
                    }
                }
                synchronized (lock) {
                    frames++;
                    lock.notifyAll();
                }
            }
        } catch (EOFException e) {
            fail("capture helper stopped");
        } catch (IOException | RuntimeException e) {
            fail(String.valueOf(e.getMessage()));
        }
    }

    private void fail(String reason) {
        synchronized (lock) {
            if (failure == null) {
                failure = reason;
                if (frames > 0) {
                    System.err.println("native screen capture stopped (" + reason + "), using Java capture");
                }
            }
            lock.notifyAll();
        }
        if (process != null) {
            process.destroy();
        }
    }
}
