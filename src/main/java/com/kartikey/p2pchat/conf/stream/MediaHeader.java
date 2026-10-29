package com.kartikey.p2pchat.conf.stream;

import java.nio.ByteBuffer;

/**
 * Fixed 32-byte clear header that relays may read to route (layer, stripe, loudness). It is the
 * AEAD associated data, so any change by a relay is detected on open, and its bytes are covered by
 * the packet hash in the signed manifest.
 *
 * <pre>
 *  0      version(4 bits, =1) | kind(4 bits)
 *  1      layer(2 bits, simulcast 0..2) | flags(6 bits, bit0 = keyframe)
 *  2..9   streamId (8)
 * 10..13  epoch, low 32 bits (4)
 * 14..21  seq, per-stream monotonic (8)
 * 22      stripe (1)
 * 23      audioLevel (1; 0 = loudest .. 127 = silence, RFC 6464 style)
 * 24..31  session (8): sender start time in millis, strictly increasing per process
 * </pre>
 * Version and kind share a byte, and layer and flags share another, to keep the header small. The
 * session makes a restarted sender (seq back at 0, same streamId) a different key and nonce space,
 * and lets receivers tell live traffic from replays of an older run.
 */
public record MediaHeader(Kind kind, long streamId, int epoch, long seq, int layer, int stripe,
                          int audioLevel, int flags, long session) {

    public static final int LENGTH = 32;
    public static final int VERSION = 1;
    public static final int FLAG_KEYFRAME = 1;

    public enum Kind { AUDIO, VIDEO, SCREEN, CHAT, CROWD_MIX, MANIFEST }

    public MediaHeader {
        if (kind == null) throw new IllegalArgumentException("kind");
        if (layer < 0 || layer > 2) throw new IllegalArgumentException("layer out of range");
        if (stripe < 0 || stripe > 255) throw new IllegalArgumentException("stripe out of range");
        if (audioLevel < 0 || audioLevel > 127) throw new IllegalArgumentException("audioLevel out of range");
        if (flags < 0 || flags > 63) throw new IllegalArgumentException("flags out of range");
    }

    /** Session 0 form, for callers that do not restart streams. */
    public MediaHeader(Kind kind, long streamId, int epoch, long seq, int layer, int stripe,
                       int audioLevel, int flags) {
        this(kind, streamId, epoch, seq, layer, stripe, audioLevel, flags, 0);
    }

    public boolean keyframe() {
        return (flags & FLAG_KEYFRAME) != 0;
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(LENGTH);
        b.put((byte) ((VERSION << 4) | kind.ordinal()));
        b.put((byte) ((layer << 6) | flags));
        b.putLong(streamId).putInt(epoch).putLong(seq);
        b.put((byte) stripe).put((byte) audioLevel).putLong(session);
        return b.array();
    }

    /** Decodes the first {@link #LENGTH} bytes of {@code data}. */
    public static MediaHeader decode(byte[] data) {
        if (data == null || data.length < LENGTH) throw new IllegalArgumentException("short header");
        ByteBuffer b = ByteBuffer.wrap(data, 0, LENGTH);
        int vk = b.get() & 0xFF;
        if ((vk >>> 4) != VERSION) throw new IllegalArgumentException("unsupported version");
        Kind[] kinds = Kind.values();
        if ((vk & 0x0F) >= kinds.length) throw new IllegalArgumentException("unknown kind");
        int lf = b.get() & 0xFF;
        long streamId = b.getLong();
        int epoch = b.getInt();
        long seq = b.getLong();
        int stripe = b.get() & 0xFF;
        int level = b.get() & 0xFF;
        long session = b.getLong();
        return new MediaHeader(kinds[vk & 0x0F], streamId, epoch, seq, lf >>> 6, stripe, level, lf & 0x3F, session);
    }
}
