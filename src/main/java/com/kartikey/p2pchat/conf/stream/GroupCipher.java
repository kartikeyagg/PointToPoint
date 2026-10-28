package com.kartikey.p2pchat.conf.stream;

import com.kartikey.p2pchat.conf.Crypto;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encrypts media for the whole meeting under one epoch key. Every member (including relays) holds
 * that key, so this gives confidentiality against outsiders only; origin authenticity comes from
 * {@link StreamSigner}/{@link StreamVerifier}.
 *
 * <p>A per-stream key is derived so a (key, nonce) pair can never repeat across senders even though
 * all of them use the same epoch key. Within a stream the nonce is
 * {@code kind(1) | layer(1) | stripe(1) | 0(1) | seq(8)}: seq is monotonic per stream and the
 * leading fields separate any sub-sequences that reuse a seq, so each (streamId, kind, layer,
 * stripe, seq) gets a distinct nonce. The sender must never seal two different payloads with the
 * same tuple.
 */
public final class GroupCipher {
    private static final byte[] INFO = "p2p-conf/v1 stream".getBytes(StandardCharsets.US_ASCII);

    private final byte[] epochKey;
    private final ConcurrentHashMap<Long, byte[]> keys = new ConcurrentHashMap<>();

    public GroupCipher(byte[] epochKey) {
        if (epochKey == null || epochKey.length == 0) throw new IllegalArgumentException("epochKey");
        this.epochKey = epochKey.clone();
    }

    private byte[] streamKey(long streamId) {
        return keys.computeIfAbsent(streamId, id ->
                Crypto.hkdf(epochKey, ByteBuffer.allocate(8).putLong(id).array(), INFO, Crypto.KEY_LEN));
    }

    static byte[] nonce(MediaHeader h) {
        return ByteBuffer.allocate(Crypto.NONCE_LEN)
                .put((byte) h.kind().ordinal()).put((byte) h.layer()).put((byte) h.stripe()).put((byte) 0)
                .putLong(h.seq()).array();
    }

    /** Returns header || ciphertext || tag. */
    public byte[] seal(MediaHeader h, byte[] payload) {
        if (h.kind() == MediaHeader.Kind.MANIFEST) throw new IllegalArgumentException("manifests are not sealed");
        byte[] hdr = h.encode();
        byte[] ct = Crypto.seal(streamKey(h.streamId()), nonce(h), hdr, payload);
        byte[] out = new byte[hdr.length + ct.length];
        System.arraycopy(hdr, 0, out, 0, hdr.length);
        System.arraycopy(ct, 0, out, hdr.length, ct.length);
        return out;
    }

    /** @throws IllegalArgumentException if malformed, header-tampered, or wrong key */
    public byte[] open(byte[] packet) {
        if (packet == null || packet.length < MediaHeader.LENGTH + Crypto.TAG_LEN)
            throw new IllegalArgumentException("short packet");
        MediaHeader h = MediaHeader.decode(packet);
        if (h.kind() == MediaHeader.Kind.MANIFEST) throw new IllegalArgumentException("not a media packet");
        byte[] hdr = Arrays.copyOfRange(packet, 0, MediaHeader.LENGTH);
        byte[] ct = Arrays.copyOfRange(packet, MediaHeader.LENGTH, packet.length);
        return Crypto.open(streamKey(h.streamId()), nonce(h), hdr, ct);
    }
}
