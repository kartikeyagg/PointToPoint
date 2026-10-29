package com.kartikey.p2pchat.conf.stream;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Signed list of truncated packet hashes for consecutive seqs {@code firstSeq ..}. Relays hold the
 * group key and could forge AEAD packets, but cannot forge the sender's signature, so a packet is
 * trusted only if its hash is listed here.
 *
 * <p>Packet layout: MediaHeader(kind MANIFEST, seq = firstSeq, epoch 0, session = sender session) ||
 * count(1) || count * hash(16) || signature(64). The signature covers "p2p-conf/v1 manifest" (domain separator) || everything before it.
 * Sent in clear; it carries nothing secret.
 */
public record Manifest(long streamId, long session, long firstSeq, List<byte[]> hashes, byte[] signature) {
    public static final int MAX_HASHES = 64;
    public static final int HASH_LEN = 16;
    private static final byte[] DOMAIN = "p2p-conf/v1 manifest".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public Manifest {
        if (hashes.isEmpty() || hashes.size() > MAX_HASHES) throw new IllegalArgumentException("hash count");
        for (byte[] h : hashes) if (h.length != HASH_LEN) throw new IllegalArgumentException("hash length");
        if (signature.length != Crypto.SIGNATURE_LEN) throw new IllegalArgumentException("signature length");
        hashes = List.copyOf(hashes);
    }

    /** 16-byte truncated sha256 of a whole packet (header included). */
    public static byte[] hash(byte[] packet) {
        return Arrays.copyOf(Crypto.sha256(packet), HASH_LEN);
    }

    private static byte[] body(long streamId, long session, long firstSeq, List<byte[]> hashes) {
        MediaHeader h = new MediaHeader(MediaHeader.Kind.MANIFEST, streamId, 0, firstSeq, 0, 0, 0, 0, session);
        ByteBuffer b = ByteBuffer.allocate(MediaHeader.LENGTH + 1 + hashes.size() * HASH_LEN);
        b.put(h.encode()).put((byte) hashes.size());
        for (byte[] x : hashes) b.put(x);
        return b.array();
    }

    private static byte[] signed(byte[] body) {
        byte[] m = Arrays.copyOf(DOMAIN, DOMAIN.length + body.length);
        System.arraycopy(body, 0, m, DOMAIN.length, body.length);
        return m;
    }

    public static Manifest sign(Identity sender, long streamId, long session, long firstSeq, List<byte[]> hashes) {
        return new Manifest(streamId, session, firstSeq, hashes, sender.sign(signed(body(streamId, session, firstSeq, hashes))));
    }

    /** Session 0 form. */
    public static Manifest sign(Identity sender, long streamId, long firstSeq, List<byte[]> hashes) {
        return sign(sender, streamId, 0, firstSeq, hashes);
    }

    public byte[] encode() {
        byte[] body = body(streamId, session, firstSeq, hashes);
        byte[] out = Arrays.copyOf(body, body.length + signature.length);
        System.arraycopy(signature, 0, out, body.length, signature.length);
        return out;
    }

    public static Manifest decode(byte[] packet) {
        if (packet == null || packet.length < MediaHeader.LENGTH + 1 + Crypto.SIGNATURE_LEN)
            throw new IllegalArgumentException("short manifest");
        MediaHeader h = MediaHeader.decode(packet);
        if (h.kind() != MediaHeader.Kind.MANIFEST) throw new IllegalArgumentException("not a manifest");
        int count = packet[MediaHeader.LENGTH] & 0xFF;
        if (count == 0 || count > MAX_HASHES) throw new IllegalArgumentException("hash count");
        if (packet.length != MediaHeader.LENGTH + 1 + count * HASH_LEN + Crypto.SIGNATURE_LEN)
            throw new IllegalArgumentException("bad manifest length");
        List<byte[]> hs = new ArrayList<>(count);
        int off = MediaHeader.LENGTH + 1;
        for (int i = 0; i < count; i++, off += HASH_LEN) hs.add(Arrays.copyOfRange(packet, off, off + HASH_LEN));
        return new Manifest(h.streamId(), h.session(), h.seq(), hs, Arrays.copyOfRange(packet, off, off + Crypto.SIGNATURE_LEN));
    }

    public boolean verify(ParticipantId sender) {
        return sender.verify(signed(body(streamId, session, firstSeq, hashes)), signature);
    }
}
