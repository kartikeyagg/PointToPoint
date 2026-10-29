package com.kartikey.p2pchat.conf.net;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.ParticipantId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Wire encoding and key schedule of the SIGMA-style handshake used by {@link UdpPeerNetwork}.
 *
 * <pre>
 * HELLO     I→R  0xD1 | ver | eph_i(44) | id_i(88) | nonce_i(16) | zero padding to 320 bytes
 * HELLO_ACK R→I  0xD2 | ver | nonce_i(16) | eph_r(44) | id_r(88) | nonce_r(16) | sig_r(64) | conf_r(16)
 * CONFIRM   I→R  0xD3 | ver | nonce_i(16) | nonce_r(16) | sig_i(64) | conf_i(16)
 *
 * th      = SHA-256("p2pconf-hs-v1" | eph_i | id_i | nonce_i | eph_r | id_r | nonce_r)
 * okm     = HKDF(salt = th, ikm = X25519(eph_i, eph_r), info = "p2pconf-keys-v1", 112)
 *         = k_i2r(32) | k_r2i(32) | sessionId(8) | conf_r(16) | conf_i(16) | unused(8)
 * sig_r   = Ed25519_r("p2pconf-sig-R" | th),  sig_i = Ed25519_i("p2pconf-sig-I" | th)
 * </pre>
 *
 * Both signatures cover both identities, both ephemeral keys and both nonces, so neither side can
 * be talked into a session with someone else, and the conf tags prove each side derived the same
 * keys. HELLO is padded to at least the size of HELLO_ACK so a spoofed source gains no
 * amplification.
 */
final class Handshake {

    static final byte HELLO = (byte) 0xD1;
    static final byte HELLO_ACK = (byte) 0xD2;
    static final byte CONFIRM = (byte) 0xD3;
    static final byte DATA = (byte) 0xD4;
    static final byte VERSION = 1;

    static final int EPH_LEN = 44;
    static final int NONCE_LEN = 16;
    static final int CONF_LEN = 16;
    static final int SIG_LEN = Crypto.SIGNATURE_LEN;
    static final int HELLO_BODY = 2 + EPH_LEN + ParticipantId.ENCODED_LEN + NONCE_LEN;
    static final int HELLO_LEN = 320;
    static final int ACK_LEN = 2 + NONCE_LEN + EPH_LEN + ParticipantId.ENCODED_LEN + NONCE_LEN + SIG_LEN + CONF_LEN;
    static final int CONFIRM_LEN = 2 + NONCE_LEN + NONCE_LEN + SIG_LEN + CONF_LEN;

    private static final byte[] TH_LABEL = "p2pconf-hs-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_INFO = "p2pconf-keys-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIG_R = "p2pconf-sig-R".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIG_I = "p2pconf-sig-I".getBytes(StandardCharsets.US_ASCII);

    private Handshake() {}

    record Hello(byte[] eph, ParticipantId id, byte[] nonce) {}

    record Ack(byte[] nonceI, byte[] ephR, byte[] idR, byte[] nonceR, byte[] sig, byte[] conf) {}

    record Confirm(byte[] nonceI, byte[] nonceR, byte[] sig, byte[] conf) {}

    /** Session secrets derived from one handshake. */
    record Keys(byte[] i2r, byte[] r2i, long sessionId, byte[] confR, byte[] confI) {}

    static KeyPair newEphemeral() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    static byte[] encodeHello(byte[] eph, ParticipantId id, byte[] nonce) {
        return ByteBuffer.allocate(HELLO_LEN).put(HELLO).put(VERSION).put(eph).put(id.encoded()).put(nonce).array();
    }

    /** Cheap structural check only; the id is decoded (and validated) by {@link #parseHello}. */
    static boolean looksLikeHello(byte[] d, int len) {
        return len >= HELLO_LEN && d[0] == HELLO && d[1] == VERSION;
    }

    /** @throws IllegalArgumentException if malformed */
    static Hello parseHello(byte[] d, int len) {
        if (!looksLikeHello(d, len)) {
            throw new IllegalArgumentException("bad hello");
        }
        byte[] eph = Arrays.copyOfRange(d, 2, 2 + EPH_LEN);
        ParticipantId id = ParticipantId.decode(d, 2 + EPH_LEN);
        byte[] nonce = Arrays.copyOfRange(d, 2 + EPH_LEN + ParticipantId.ENCODED_LEN, HELLO_BODY);
        return new Hello(eph, id, nonce);
    }

    static byte[] encodeAck(byte[] nonceI, byte[] ephR, ParticipantId idR, byte[] nonceR, byte[] sig, byte[] conf) {
        return ByteBuffer.allocate(ACK_LEN).put(HELLO_ACK).put(VERSION).put(nonceI).put(ephR).put(idR.encoded())
                .put(nonceR).put(sig).put(conf).array();
    }

    static Ack parseAck(byte[] d, int len) {
        if (len != ACK_LEN || d[0] != HELLO_ACK || d[1] != VERSION) {
            throw new IllegalArgumentException("bad ack");
        }
        ByteBuffer b = ByteBuffer.wrap(d, 2, len - 2);
        return new Ack(take(b, NONCE_LEN), take(b, EPH_LEN), take(b, ParticipantId.ENCODED_LEN),
                take(b, NONCE_LEN), take(b, SIG_LEN), take(b, CONF_LEN));
    }

    static byte[] encodeConfirm(byte[] nonceI, byte[] nonceR, byte[] sig, byte[] conf) {
        return ByteBuffer.allocate(CONFIRM_LEN).put(CONFIRM).put(VERSION).put(nonceI).put(nonceR).put(sig).put(conf)
                .array();
    }

    static Confirm parseConfirm(byte[] d, int len) {
        if (len != CONFIRM_LEN || d[0] != CONFIRM || d[1] != VERSION) {
            throw new IllegalArgumentException("bad confirm");
        }
        ByteBuffer b = ByteBuffer.wrap(d, 2, len - 2);
        return new Confirm(take(b, NONCE_LEN), take(b, NONCE_LEN), take(b, SIG_LEN), take(b, CONF_LEN));
    }

    static byte[] transcriptHash(byte[] ephI, byte[] idI, byte[] nonceI, byte[] ephR, byte[] idR, byte[] nonceR) {
        return Crypto.sha256(TH_LABEL, ephI, idI, nonceI, ephR, idR, nonceR);
    }

    /**
     * @throws IllegalArgumentException if the peer's ephemeral key is invalid or of small order
     */
    static Keys deriveKeys(byte[] th, byte[] dh) {
        boolean zero = true;
        for (byte x : dh) {
            zero &= x == 0;
        }
        if (zero) {
            throw new IllegalArgumentException("degenerate X25519 share");
        }
        byte[] okm = Crypto.hkdf(th, dh, KEY_INFO, 112);
        Arrays.fill(dh, (byte) 0);
        return new Keys(Arrays.copyOfRange(okm, 0, 32), Arrays.copyOfRange(okm, 32, 64),
                ByteBuffer.wrap(okm, 64, 8).getLong(), Arrays.copyOfRange(okm, 72, 88), Arrays.copyOfRange(okm, 88, 104));
    }

    static byte[] responderSigInput(byte[] th) {
        return concat(SIG_R, th);
    }

    static byte[] initiatorSigInput(byte[] th) {
        return concat(SIG_I, th);
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] take(ByteBuffer b, int n) {
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
