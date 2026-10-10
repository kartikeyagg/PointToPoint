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
 * on the wire : plain frame                       (no passphrase)
 *               nonce(12) AES-GCM(plain frame)    (with passphrase)
 * </pre>
 *
 * Both peers must use the same passphrase (or both none); anything that fails to
 * decode is rejected with {@link IllegalArgumentException}.
 */
public final class PacketCodec {

    static final short MAGIC = 0x5032; // "P2"
    static final byte VERSION = 1;
    static final int HEADER_LEN = 2 + 1 + 1 + 8 + 8;
    /** Keeps datagrams below typical internet MTU so they are never fragmented. */
    public static final int MAX_PAYLOAD = 1100;

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

    public byte[] encode(Packet p) {
        if (p.payload().length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + p.payload().length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + p.payload().length);
        buf.putShort(MAGIC).put(VERSION).put((byte) p.type().ordinal())
                .putLong(p.session()).putLong(p.seq()).put(p.payload());
        byte[] plain = buf.array();
        return key == null ? plain : encrypt(plain);
    }

    public Packet decode(byte[] data, int length) {
        byte[] plain = key == null ? java.util.Arrays.copyOf(data, length) : decrypt(data, length);
        if (plain.length < HEADER_LEN) {
            throw new IllegalArgumentException("packet too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(plain);
        if (buf.getShort() != MAGIC || buf.get() != VERSION) {
            throw new IllegalArgumentException("not a p2p-chat packet (or passphrase mismatch)");
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
            return ByteBuffer.allocate(NONCE_LEN + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] data, int length) {
        if (length <= NONCE_LEN) {
            throw new IllegalArgumentException("packet too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, NONCE_LEN));
            return c.doFinal(data, NONCE_LEN, length - NONCE_LEN);
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
