package com.kartikey.p2pchat.conf;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Small crypto helpers shared by the conference packages. All JDK, no providers. */
public final class Crypto {

    public static final int KEY_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;
    public static final int SIGNATURE_LEN = 64;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    public static byte[] random(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869). */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            for (int i = 0, pos = 0; pos < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) (i + 1));
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** AES-256-GCM with a caller-chosen 12-byte nonce; returns ciphertext||tag. */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN * 8, nonce));
            if (aad != null) {
                c.updateAAD(aad);
            }
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    /**
     * Inverse of {@link #seal}.
     *
     * @throws IllegalArgumentException if the tag does not verify
     */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN * 8, nonce));
            if (aad != null) {
                c.updateAAD(aad);
            }
            return c.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("authentication failed");
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("decryption failed: " + e.getMessage());
        }
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        if (signature == null || signature.length != SIGNATURE_LEN) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static byte[] x25519(PrivateKey own, byte[] peerEncoded) {
        try {
            PublicKey pk = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(peerEncoded));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(own);
            ka.doPhase(pk, true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad X25519 key: " + e.getMessage());
        }
    }
}
