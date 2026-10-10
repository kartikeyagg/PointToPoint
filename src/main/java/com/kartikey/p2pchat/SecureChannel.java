package com.kartikey.p2pchat;

import javax.crypto.AEADBadTagException;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Per-connection keys on top of the shared passphrase.
 *
 * <p>Each side makes a fresh X25519 key pair at startup and sends the public half inside its
 * HELLO, which is sealed with the passphrase key (so only someone who knows the passphrase can
 * take part). Both sides then derive two session keys, one per direction, from the X25519 secret
 * with the passphrase key as HKDF salt. This gives every connection its own keys (old traffic
 * cannot be replayed into a new connection, and recorded traffic stays secret even if the
 * passphrase leaks later) and lets packets use a plain counter as the GCM nonce, which is what
 * makes precomputing the keystream possible.
 *
 * <p>Once a session is running, a HELLO with a different key (the peer restarted, or someone
 * replayed an old HELLO) only creates <em>pending</em> keys. They replace the current ones when
 * a packet sealed with them arrives, which proves the sender holds the new private key; a
 * replayed HELLO can therefore never knock out a working session.
 *
 * <p>Wire format of a session packet: {@code 0xE2 | counter(8) | AES-256-GCM(frame)}.
 */
final class SecureChannel implements AutoCloseable {

    static final byte PREFIX = (byte) 0xE2;
    static final int OVERHEAD = 1 + 8 + GcmCipher.TAG_LEN;
    /** X.509 encoding of an X25519 public key. */
    static final int PUBLIC_KEY_LEN = 44;
    private static final int POOL_DEPTH = 128;
    private static final int REPLAY_WINDOW = 1024;
    private static final byte[] INFO = "p2p-chat/v2 session keys".getBytes(StandardCharsets.UTF_8);

    private final byte[] staticKey;
    private final KeyPair ephemeral;
    private final byte[] localPublic;
    private final int maxFrame;
    private volatile Keys keys;
    private volatile Keys pending;

    enum Handshake { ADOPTED, PENDING, UNCHANGED }

    record Opened(byte[] frame, boolean rekeyed) {}

    /** Keys and counters for one handshake; replaced as a whole when the peer restarts. */
    private static final class Keys {
        final byte[] peerPublic;
        final GcmCipher send, recv;
        KeystreamPool sendPool, recvPool;       // started when the keys go live
        long nextCounter;                       // guarded by send
        long maxSeen = -1;                      // receiver thread only
        final long[] window = new long[REPLAY_WINDOW / 64];

        Keys(byte[] peerPublic, byte[] sendKey, byte[] recvKey, int maxFrame) {
            this.peerPublic = peerPublic;
            this.send = new GcmCipher(sendKey, maxFrame);
            this.recv = new GcmCipher(recvKey, maxFrame);
        }

        Keys live() {
            sendPool = new KeystreamPool(send, 0, POOL_DEPTH, "p2p-keystream-send");
            recvPool = new KeystreamPool(recv, 0, POOL_DEPTH, "p2p-keystream-recv");
            return this;
        }

        byte[] sendKeystream(long counter) {
            return sendPool == null ? null : sendPool.take(counter);
        }

        byte[] recvKeystream(long counter) {
            return recvPool == null ? null : recvPool.take(counter);
        }

        void used(KeystreamPool pool, long counter) {
            if (pool != null) {
                pool.release(counter);
            }
        }

        void close() {
            if (sendPool != null) {
                sendPool.close();
                recvPool.close();
            }
        }
    }

    SecureChannel(byte[] staticKey, int maxFrame) {
        this.staticKey = staticKey.clone();
        this.maxFrame = maxFrame;
        try {
            this.ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
        this.localPublic = ephemeral.getPublic().getEncoded();
        if (localPublic.length != PUBLIC_KEY_LEN) {
            throw new IllegalStateException("unexpected X25519 key encoding");
        }
    }

    byte[] localPublicKey() {
        return localPublic.clone();
    }

    boolean isEstablished() {
        return keys != null;
    }

    /**
     * Derives session keys for the peer's public key: used right away for the first handshake,
     * otherwise kept pending until a packet proves the peer holds them.
     *
     * @throws IllegalArgumentException for a malformed key or our own key reflected back
     */
    synchronized Handshake establish(byte[] peerPublic) {
        Keys current = keys, waiting = pending;
        if (current != null && Arrays.equals(current.peerPublic, peerPublic)) {
            return Handshake.UNCHANGED;
        }
        if (waiting != null && Arrays.equals(waiting.peerPublic, peerPublic)) {
            return Handshake.PENDING;
        }
        Keys derived = derive(peerPublic);
        if (current == null) {
            keys = derived.live();
            return Handshake.ADOPTED;
        }
        pending = derived;
        return Handshake.PENDING;
    }

    private Keys derive(byte[] peerPublic) {
        if (peerPublic.length != PUBLIC_KEY_LEN || Arrays.equals(peerPublic, localPublic)) {
            throw new IllegalArgumentException("bad handshake key");
        }
        byte[] shared;
        try {
            PublicKey pk = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(peerPublic));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(ephemeral.getPrivate());
            ka.doPhase(pk, true);
            shared = ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad handshake key: " + e.getMessage());
        }
        boolean localFirst = Arrays.compareUnsigned(localPublic, peerPublic) < 0;
        byte[] info = ByteBuffer.allocate(INFO.length + 2 * PUBLIC_KEY_LEN).put(INFO)
                .put(localFirst ? localPublic : peerPublic).put(localFirst ? peerPublic : localPublic).array();
        byte[] okm = hkdf(staticKey, shared, info, 64);
        byte[] first = Arrays.copyOfRange(okm, 0, 32), second = Arrays.copyOfRange(okm, 32, 64);
        return localFirst ? new Keys(peerPublic.clone(), first, second, maxFrame)
                : new Keys(peerPublic.clone(), second, first, maxFrame);
    }

    private synchronized boolean promote(Keys k) {
        if (pending != k) {
            return false;
        }
        Keys old = keys;
        keys = k.live();
        pending = null;
        old.close();
        return true;
    }

    /** Encrypts a frame for the peer. */
    byte[] seal(byte[] frame) {
        Keys k = keys;
        if (k == null) {
            throw new IllegalStateException("no session yet");
        }
        byte[] out = new byte[1 + 8 + frame.length + GcmCipher.TAG_LEN];
        out[0] = PREFIX;
        synchronized (k.send) {
            long counter = k.nextCounter++;
            ByteBuffer.wrap(out, 1, 8).putLong(counter);
            k.send.seal(counter, k.sendKeystream(counter), frame, 0, frame.length, out, 9);
            k.used(k.sendPool, counter);
        }
        return out;
    }

    /**
     * Authenticates and decrypts a session packet. Call from one thread only.
     *
     * @return the frame, and whether it switched us to the pending keys
     * @throws IllegalArgumentException if it is forged, damaged, replayed or from another session
     */
    Opened open(byte[] data, int length) {
        Keys k = keys;
        if (k == null) {
            throw new IllegalArgumentException("session packet before handshake");
        }
        if (length < OVERHEAD || data[0] != PREFIX) {
            throw new IllegalArgumentException("not a session packet");
        }
        long counter = ByteBuffer.wrap(data, 1, 8).getLong();
        byte[] frame = tryOpen(k, counter, data, length);
        if (frame != null) {
            return new Opened(frame, false);
        }
        Keys next = pending;
        if (next != null && (frame = tryOpen(next, counter, data, length)) != null) {
            return new Opened(frame, promote(next));
        }
        throw new IllegalArgumentException("cannot decrypt packet (replayed, or passphrase mismatch?)");
    }

    private static byte[] tryOpen(Keys k, long counter, byte[] data, int length) {
        if (counter < 0 || isReplay(k, counter)) {
            return null;
        }
        byte[] frame;
        try {
            frame = k.recv.open(counter, k.recvKeystream(counter), data, 9, length - 9);
        } catch (AEADBadTagException e) {
            return null;
        }
        markSeen(k, counter);
        k.used(k.recvPool, counter);
        return frame;
    }

    private static boolean isReplay(Keys k, long counter) {
        if (counter > k.maxSeen) {
            return false;
        }
        long age = k.maxSeen - counter;
        if (age >= REPLAY_WINDOW) {
            return true;
        }
        int bit = (int) (counter % REPLAY_WINDOW);
        return (k.window[bit >>> 6] & (1L << (bit & 63))) != 0;
    }

    private static void markSeen(Keys k, long counter) {
        if (counter > k.maxSeen) {
            // clear the bits of counters that slide out of the window
            long from = Math.max(k.maxSeen + 1, counter - REPLAY_WINDOW + 1);
            for (long c = from; c <= counter; c++) {
                int bit = (int) (c % REPLAY_WINDOW);
                k.window[bit >>> 6] &= ~(1L << (bit & 63));
            }
            k.maxSeen = counter;
        }
        int bit = (int) (counter % REPLAY_WINDOW);
        k.window[bit >>> 6] |= 1L << (bit & 63);
    }

    /** HKDF-SHA256 (RFC 5869). */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
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

    @Override
    public synchronized void close() {
        Keys k = keys;
        if (k != null) {
            k.close();
        }
        keys = null;
        pending = null;
    }
}
