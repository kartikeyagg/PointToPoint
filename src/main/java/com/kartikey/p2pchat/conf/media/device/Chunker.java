package com.kartikey.p2pchat.conf.media.device;

import com.kartikey.p2pchat.conf.media.MediaFormat;
import java.util.function.Consumer;

/**
 * Turns arbitrary-sized reads of 16-bit little-endian PCM into fixed {@link MediaFormat#FRAME_SAMPLES}
 * frames. Sound lines return whatever is buffered, which rarely lines up with 20 ms, and the codec
 * needs exact frames; a leftover odd byte is also kept for the next read.
 */
public final class Chunker {

    private final Consumer<short[]> out;
    private short[] frame = new short[MediaFormat.FRAME_SAMPLES];
    private int filled;
    private int pendingLow = -1;

    public Chunker(Consumer<short[]> out) {
        this.out = out;
    }

    public void accept(byte[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int b = buf[i] & 0xff;
            if (pendingLow < 0) {
                pendingLow = b;
                continue;
            }
            frame[filled++] = (short) (pendingLow | (b << 8));
            pendingLow = -1;
            if (filled == frame.length) {
                short[] done = frame;
                frame = new short[MediaFormat.FRAME_SAMPLES];
                filled = 0;
                out.accept(done);
            }
        }
    }
}
