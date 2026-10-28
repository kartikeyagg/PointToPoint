package com.kartikey.p2pchat.conf.media.device;

import com.kartikey.p2pchat.conf.media.MediaFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Speaker output. The line buffer is small (~60 ms) and a frame is dropped when it is full, so a
 * stalled device or clock drift cannot make latency grow without bound (and play() never blocks).
 */
public final class SpeakerPlayback implements AutoCloseable {

    private static final int BYTES_PER_MS = MediaFormat.SAMPLE_RATE / 1000 * 2;
    private static final int BUFFER_MS = 60;

    private SourceDataLine line;
    private boolean closed;

    public static boolean available() {
        try {
            return AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, MicCapture.FORMAT));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void open() throws LineUnavailableException {
        SourceDataLine l = AudioSystem.getSourceDataLine(MicCapture.FORMAT);
        l.open(MicCapture.FORMAT, BUFFER_MS * BYTES_PER_MS);
        l.start();
        line = l;
    }

    /** Queues one frame; drops it when the device buffer cannot take it right now. */
    public synchronized void play(short[] frame) {
        if (closed) {
            return;
        }
        try {
            if (line == null) {
                open();
            }
        } catch (LineUnavailableException e) {
            return;
        }
        byte[] b = toBytes(frame);
        if (line.available() < b.length) {
            return;
        }
        line.write(b, 0, b.length);
    }

    static byte[] toBytes(short[] frame) {
        byte[] b = new byte[frame.length * 2];
        for (int i = 0; i < frame.length; i++) {
            b[2 * i] = (byte) frame[i];
            b[2 * i + 1] = (byte) (frame[i] >> 8);
        }
        return b;
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
    }
}
