package com.kartikey.p2pchat.conf.media.device;

import com.kartikey.p2pchat.conf.media.MediaFormat;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * Microphone as 20 ms frames of 48 kHz mono PCM on its own daemon thread. While muted, the line
 * keeps running and <b>silence frames are still delivered</b>, so downstream timing (jitter
 * buffers, Opus DTX, send pacing) is unaffected by muting.
 */
public final class MicCapture implements AutoCloseable {

    static final AudioFormat FORMAT = new AudioFormat(MediaFormat.SAMPLE_RATE, 16, MediaFormat.CHANNELS, true, false);

    private final Consumer<short[]> onFrame;
    private volatile boolean muted;
    private volatile boolean closed;
    private TargetDataLine line;
    private Thread thread;

    public MicCapture(Consumer<short[]> onFrame) {
        this.onFrame = onFrame;
    }

    public static boolean available() {
        try {
            return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Opens the default input (may trigger the OS microphone permission prompt). */
    public synchronized void start() throws LineUnavailableException {
        if (thread != null || closed) {
            return;
        }
        TargetDataLine l = AudioSystem.getTargetDataLine(FORMAT);
        l.open(FORMAT, FORMAT.getFrameSize() * MediaFormat.FRAME_SAMPLES * 4);
        l.start();
        line = l;
        thread = new Thread(this::run, "mic-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        Chunker chunker = new Chunker(f -> onFrame.accept(muted ? new short[f.length] : f));
        byte[] buf = new byte[FORMAT.getFrameSize() * MediaFormat.FRAME_SAMPLES];
        while (!closed) {
            int n = line.read(buf, 0, buf.length);
            if (n <= 0) {
                break;
            }
            chunker.accept(buf, 0, n);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (line != null) {
            line.stop();
            line.close();
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
