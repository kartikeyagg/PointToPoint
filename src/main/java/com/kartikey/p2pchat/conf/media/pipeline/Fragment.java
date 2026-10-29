package com.kartikey.p2pchat.conf.media.pipeline;

import com.kartikey.p2pchat.conf.media.MediaFormat;
import java.nio.ByteBuffer;

/**
 * One packet-sized slice of an encoded video frame. The header travels inside the encrypted
 * payload (relays only see the clear {@code MediaHeader}); it repeats the picture metadata in
 * every fragment so any fragment can start a frame whichever arrives first.
 *
 * <pre>
 *  0..3   frameId (4, per layer, wraps)
 *  4..5   fragIndex (2)
 *  6..7   fragCount (2, >= 1)
 *  8      flags (1; bit0 = keyframe)
 *  9..10  width (2)
 * 11..12  height (2)
 * 13..20  timestampMicros (8)
 * 21..    codec data
 * </pre>
 */
public record Fragment(int frameId, int index, int count, boolean keyframe, int width, int height,
                       long timestampMicros, byte[] data) {

    public static final int HEADER_LENGTH = 21;
    /** Codec bytes per fragment, so header + data stays within {@link MediaFormat#MAX_FRAGMENT}. */
    public static final int MAX_DATA = MediaFormat.MAX_FRAGMENT - HEADER_LENGTH;
    /** Most fragments a legitimate frame can need; bounds what a hostile count can make us allocate. */
    public static final int MAX_COUNT = (MediaFormat.MAX_ENCODED_FRAME + MAX_DATA - 1) / MAX_DATA;

    public byte[] encode() {
        return ByteBuffer.allocate(HEADER_LENGTH + data.length)
                .putInt(frameId).putShort((short) index).putShort((short) count)
                .put((byte) (keyframe ? 1 : 0)).putShort((short) width).putShort((short) height)
                .putLong(timestampMicros).put(data).array();
    }

    /** @throws IllegalArgumentException if malformed */
    public static Fragment decode(byte[] payload) {
        if (payload == null || payload.length < HEADER_LENGTH || payload.length > MediaFormat.MAX_FRAGMENT)
            throw new IllegalArgumentException("bad fragment length");
        ByteBuffer b = ByteBuffer.wrap(payload);
        int frameId = b.getInt();
        int index = b.getShort() & 0xFFFF;
        int count = b.getShort() & 0xFFFF;
        int flags = b.get() & 0xFF;
        int width = b.getShort() & 0xFFFF;
        int height = b.getShort() & 0xFFFF;
        long ts = b.getLong();
        if (count < 1 || count > MAX_COUNT || index >= count) throw new IllegalArgumentException("bad fragment index");
        if (width < 2 || height < 2 || width > MediaFormat.MAX_VIDEO_WIDTH || height > MediaFormat.MAX_VIDEO_HEIGHT
                || (width & 1) != 0 || (height & 1) != 0)
            throw new IllegalArgumentException("bad picture size");
        if ((flags & ~1) != 0) throw new IllegalArgumentException("unknown fragment flags");
        byte[] data = new byte[b.remaining()];
        b.get(data);
        return new Fragment(frameId, index, count, (flags & 1) != 0, width, height, ts, data);
    }
}
