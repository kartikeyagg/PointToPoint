package com.kartikey.p2pchat;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Serialises packets to bytes and back.
 *
 * <pre>
 * plain frame : magic(2) version(1) type(1) session(8) seq(8) payload(n)
 * on the wire : plain frame                                  (no passphrase)
 *               0xE1 nonce(12) AES-GCM(plain frame)          (passphrase key: handshake only)
 *               0xE2 counter(8) AES-GCM(plain frame)         (session key, see SecureChannel)
 * </pre>
 *
 * This class handles the first two; {@link PeerLink} sends everything except HELLO through a
 * {@link SecureChannel} once the handshake is done. Both peers must use the same passphrase
 * (or both none); anything that fails to decode is rejected with {@link IllegalArgumentException}.
 */
public final class PacketCodec {

    static final short MAGIC = 0x5032; // "P2"
    static final byte VERSION = 2;
    static final int HEADER_LEN = 2 + 1 + 1 + 8 + 8;
    /** Keeps datagrams below typical internet MTU so they are never fragmented. */
    public static final int MAX_PAYLOAD = 1100;
    static final int MAX_FRAME = HEADER_LEN + MAX_PAYLOAD;
    static final byte HANDSHAKE_PREFIX = (byte) 0xE1;

    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] KDF_SALT = "p2p-chat/v1".getBytes(StandardCharsets.UTF_8);
    private static final int KDF_ITERATIONS = 200_000;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /** @param passphrase shared secret, or null/blank for unencrypted traffic */
    public PacketCodec(String passphrase) {
        this.key = passphrase == null || passphrase.isBlank() ? null : deriveKey(passphrase);
    }

    public boolean isEncrypted() {
        return key != null;
    }

    /** The passphrase key (salt for the session keys); only valid when encrypted. */
    byte[] keyBytes() {
        return key.getEncoded();
    }

    /** Plain frame, or (with a passphrase) a frame sealed with the passphrase key. */
    public byte[] encode(Packet p) {
        byte[] plain = frame(p);
        return key == null ? plain : encrypt(plain);
    }

    public Packet decode(byte[] data, int length) {
        return parse(key == null ? java.util.Arrays.copyOf(data, length) : decrypt(data, length));
    }

    static byte[] frame(Packet p) {
        if (p.payload().length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + p.payload().length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + p.payload().length);
        buf.putShort(MAGIC).put(VERSION).put((byte) p.type().ordinal())
                .putLong(p.session()).putLong(p.seq()).put(p.payload());
        return buf.array();
    }

    static Packet parse(byte[] plain) {
        if (plain.length < HEADER_LEN) {
            throw new IllegalArgumentException("packet too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(plain);
        if (buf.getShort() != MAGIC || buf.get() != VERSION) {
            throw new IllegalArgumentException("not a p2p-chat v" + VERSION
                    + " packet (passphrase mismatch or different app version?)");
        }
        Packet.Type type = Packet.Type.fromByte(buf.get());
        long session = buf.getLong();
        long seq = buf.getLong();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new Packet(type, session, seq, payload);
    }

    private byte[] encrypt(byte[] plain) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(1 + NONCE_LEN + ct.length).put(HANDSHAKE_PREFIX).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] data, int length) {
        if (length <= 1 + NONCE_LEN || data[0] != HANDSHAKE_PREFIX) {
            throw new IllegalArgumentException("not an encrypted p2p-chat packet (passphrase mismatch?)");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, NONCE_LEN));
            return c.doFinal(data, 1 + NONCE_LEN, length - 1 - NONCE_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot decrypt packet (passphrase mismatch?)", e);
        }
    }

    private static SecretKey deriveKey(String passphrase) {
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), KDF_SALT, KDF_ITERATIONS, 256);
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key derivation failed", e);
        }
    }
}
