package com.kartikey.p2pchat.conf;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * A participant's long-term key pairs: Ed25519 to sign (admin commands, stream manifests, mixes)
 * and X25519 to receive wrapped keys. The private halves never leave this object except through
 * {@link #exportPrivate()} for saving to disk.
 */
public final class Identity {

    private final KeyPair sign;
    private final KeyPair agree;
    private final ParticipantId id;

    private Identity(KeyPair sign, KeyPair agree) {
        this.sign = sign;
        this.agree = agree;
        this.id = new ParticipantId(sign.getPublic().getEncoded(), agree.getPublic().getEncoded());
    }

    public static Identity generate() {
        try {
            return new Identity(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
                    KeyPairGenerator.getInstance("X25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519/X25519 unavailable", e);
        }
    }

    public ParticipantId id() {
        return id;
    }

    /** Ed25519 signature (64 bytes) over {@code message}. */
    public byte[] sign(byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(sign.getPrivate());
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** X25519 shared secret with another participant's agreement key. */
    public byte[] agree(ParticipantId peer) {
        return Crypto.x25519(agree.getPrivate(), peer.agreeKey());
    }

    /** X25519 shared secret with any X.509-encoded X25519 public key (e.g. an ephemeral one). */
    public byte[] agree(byte[] x25519Public) {
        return Crypto.x25519(agree.getPrivate(), x25519Public);
    }

    /** {@code len(2) | ed25519 PKCS#8 | len(2) | x25519 PKCS#8}; keep it secret. */
    public byte[] exportPrivate() {
        byte[] s = sign.getPrivate().getEncoded(), a = agree.getPrivate().getEncoded();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + s.length + a.length);
        b.putShort((short) s.length).put(s).putShort((short) a.length).put(a);
        return b.array();
    }

    /** Inverse of {@link #exportPrivate()}; also needs the public keys from the id. */
    public static Identity importPrivate(byte[] data, ParticipantId id) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(data);
            byte[] s = new byte[b.getShort()];
            b.get(s);
            byte[] a = new byte[b.getShort()];
            b.get(a);
            PrivateKey sp = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(s));
            PrivateKey ap = KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(a));
            KeyFactory ed = KeyFactory.getInstance("Ed25519"), x = KeyFactory.getInstance("X25519");
            Identity out = new Identity(
                    new KeyPair(ed.generatePublic(new java.security.spec.X509EncodedKeySpec(id.signKey())), sp),
                    new KeyPair(x.generatePublic(new java.security.spec.X509EncodedKeySpec(id.agreeKey())), ap));
            byte[] probe = "identity-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!id.verify(probe, out.sign(probe))) {
                throw new IllegalArgumentException("private key does not match id");
            }
            return out;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("bad identity file: " + e.getMessage());
        }
    }
}
