package com.kartikey.p2pchat.conf;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * A participant's public identity: the X.509 encodings of their Ed25519 signing key and X25519
 * agreement key. Two ids are equal when both keys are equal. {@link #fingerprint()} is what people
 * compare out of band (and what invite links carry for the host).
 */
public final class ParticipantId implements Comparable<ParticipantId> {

    /** X.509 encoding length of an Ed25519 or X25519 public key. */
    public static final int KEY_LEN = 44;
    public static final int ENCODED_LEN = 2 * KEY_LEN;

    private final byte[] signKey;
    private final byte[] agreeKey;
    private final PublicKey signPublic;

    public ParticipantId(byte[] signKey, byte[] agreeKey) {
        if (signKey.length != KEY_LEN || agreeKey.length != KEY_LEN) {
            throw new IllegalArgumentException("bad participant key length");
        }
        this.signKey = signKey.clone();
        this.agreeKey = agreeKey.clone();
        try {
            this.signPublic = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(this.signKey));
            KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(this.agreeKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad participant key: " + e.getMessage());
        }
    }

    /** Parses the {@link #encoded()} form. */
    public static ParticipantId decode(byte[] data, int offset) {
        if (data.length - offset < ENCODED_LEN) {
            throw new IllegalArgumentException("truncated participant id");
        }
        return new ParticipantId(Arrays.copyOfRange(data, offset, offset + KEY_LEN),
                Arrays.copyOfRange(data, offset + KEY_LEN, offset + ENCODED_LEN));
    }

    /** {@code signKey(44) | agreeKey(44)}. */
    public byte[] encoded() {
        byte[] out = new byte[ENCODED_LEN];
        System.arraycopy(signKey, 0, out, 0, KEY_LEN);
        System.arraycopy(agreeKey, 0, out, KEY_LEN, KEY_LEN);
        return out;
    }

    public byte[] signKey() {
        return signKey.clone();
    }

    public byte[] agreeKey() {
        return agreeKey.clone();
    }

    /** Checks an Ed25519 signature made by {@link Identity#sign}. */
    public boolean verify(byte[] message, byte[] signature) {
        return Crypto.verify(signPublic, message, signature);
    }

    /** First 16 bytes of SHA-256 over {@link #encoded()}, as hex. */
    public String fingerprint() {
        return HexFormat.of().formatHex(Crypto.sha256(encoded()), 0, 16);
    }

    /** Short form for logs and the UI. */
    public String shortId() {
        return fingerprint().substring(0, 8);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ParticipantId p && Arrays.equals(signKey, p.signKey) && Arrays.equals(agreeKey, p.agreeKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(signKey) * 31 + Arrays.hashCode(agreeKey);
    }

    @Override
    public int compareTo(ParticipantId o) {
        return Arrays.compareUnsigned(encoded(), o.encoded());
    }

    @Override
    public String toString() {
        return "Participant[" + shortId() + "]";
    }
}
