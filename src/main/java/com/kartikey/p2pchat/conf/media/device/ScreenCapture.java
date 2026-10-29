package com.kartikey.p2pchat.conf.media.device;

import com.kartikey.p2pchat.conf.media.I420;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import com.kartikey.p2pchat.remote.DesktopApp;
import com.kartikey.p2pchat.remote.RobotScreen;
import com.kartikey.p2pchat.remote.ScreenSource;
import java.awt.AWTException;
import java.util.function.Consumer;

/** Screen share as I420 frames at a fixed rate, downscaled so the encoder never sees more than {@code maxWidth}. */
public final class ScreenCapture implements AutoCloseable {

    private final ScreenSource source;
    private final long periodNanos;
    private final int maxWidth;
    private final Consumer<VideoFrame> onFrame;
    private volatile boolean closed;
    private Thread thread;

    public ScreenCapture(ScreenSource source, int fps, int maxWidth, Consumer<VideoFrame> onFrame) {
        this.source = source;
        this.periodNanos = 1_000_000_000L / Math.max(1, fps);
        this.maxWidth = Math.max(2, maxWidth);
        this.onFrame = onFrame;
    }

    /**
     * Same choice as the remote-desktop host: the native helper named by {@code $P2P_SCREENSTREAM}
     * when present (much faster), otherwise {@link RobotScreen}.
     */
    public static ScreenSource defaultSource() throws AWTException {
        return DesktopApp.openScreen(false, new RobotScreen());
    }

    public synchronized void start() {
        if (thread != null || closed) {
            return;
        }
        thread = new Thread(this::run, "screen-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        long next = System.nanoTime();
        while (!closed) {
            try {
                VideoFrame f = I420.fromImage(source.capture(), System.nanoTime() / 1000);
                if (f.width() > maxWidth) {
                    int w = maxWidth & ~1;
                    int h = Math.max(2, (int) ((long) f.height() * w / f.width()) & ~1);
                    f = I420.scale(f, w, h);
                }
                if (!closed) {
                    onFrame.accept(f);
                }
            } catch (RuntimeException e) {
                // a failed grab (e.g. resolution change mid-capture) just skips this tick
            }
            next += periodNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                } catch (InterruptedException e) {
                    return;
                }
            } else if (sleep < -periodNanos * 5) {
                next = System.nanoTime();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
