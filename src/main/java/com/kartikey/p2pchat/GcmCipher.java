package com.kartikey.p2pchat;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * AES-GCM split into its two halves so the expensive one can run ahead of time.
 *
 * <p>GCM encrypts by XOR-ing the data with an AES-CTR keystream and authenticates with a GHASH
 * tag. The keystream (and the tag mask E(K, J0)) depend only on the key and the nonce, never on
 * the data. Nonces here are a 64-bit packet counter, so the keystream for upcoming packets can be
 * computed before the packets exist ({@link KeystreamPool}); sealing a packet is then just XOR
 * plus GHASH. The output is byte-for-byte what {@code AES/GCM/NoPadding} produces for the same
 * key and nonce {@code 0x00000000 || counter}, 128-bit tag, no AAD.
 *
 * <p>Only frames up to {@link #PRECOMPUTE_MAX} bytes take that path. GHASH in plain Java costs
 * about 4 ns per byte, while the JDK's own AES-GCM uses the CPU's AES-NI and carry-less multiply
 * instructions; measured on an i5-9300H the precomputed path wins clearly for small frames
 * (40 B: 0.2 us vs 0.7 us) and loses for full ones (1120 B: 4.7 us vs 1.4 us). Larger frames are
 * therefore handed to the JDK. Both give identical bytes, so the receiver may pick either way.
 */
final class GcmCipher {

    static final int TAG_LEN = 16;
    static final int NONCE_LEN = 12;
    /** Frames up to this size use the precomputed keystream; larger ones the JDK's AES-GCM. */
    static final int PRECOMPUTE_MAX = 256;

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final long R = 0xE100000000000000L;

    /** Keystream layout: 16-byte tag mask, then blocks for up to {@link #PRECOMPUTE_MAX} bytes. */
    final int keystreamLen;
    final int maxPlain;

    private final SecretKeySpec key;
    /** GHASH multiplication by H as 16 byte-position tables of 256 entries (64 KiB). */
    private final long[] tHi = new long[16 * 256];
    private final long[] tLo = new long[16 * 256];
    private final ThreadLocal<Cipher> ecb;
    private final ThreadLocal<Cipher> gcmEncrypt = ThreadLocal.withInitial(GcmCipher::newGcm);
    private final ThreadLocal<Cipher> gcmDecrypt = ThreadLocal.withInitial(GcmCipher::newGcm);

    GcmCipher(byte[] key, int maxPlain) {
        this.key = new SecretKeySpec(key, "AES");
        this.maxPlain = maxPlain;
        this.keystreamLen = 16 + (Math.min(maxPlain, PRECOMPUTE_MAX) + 15) / 16 * 16;
        this.ecb = ThreadLocal.withInitial(this::newEcb);
        byte[] h;
        try {
            h = ecb.get().doFinal(new byte[16]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
        buildTables((long) LONGS.get(h, 0), (long) LONGS.get(h, 8));
    }

    private Cipher newEcb() {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    private static Cipher newGcm() {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** The 12-byte GCM nonce used for {@code counter}. */
    static byte[] nonce(long counter) {
        byte[] n = new byte[NONCE_LEN];
        LONGS.set(n, 4, counter);
        return n;
    }

    byte[] newKeystream() {
        return new byte[keystreamLen];
    }

    /** Fills {@code out} ({@link #keystreamLen} bytes) with E(K, J0), E(K, J0+1), ... for {@code counter}. */
    void keystream(long counter, byte[] out) {
        // counter blocks: nonce(12) || 32-bit block index starting at 1 (J0)
        for (int off = 0, block = 1; off < keystreamLen; off += 16, block++) {
            out[off] = 0;
            out[off + 1] = 0;
            out[off + 2] = 0;
            out[off + 3] = 0;
            LONGS.set(out, off + 4, counter);
            out[off + 12] = (byte) (block >>> 24);
            out[off + 13] = (byte) (block >>> 16);
            out[off + 14] = (byte) (block >>> 8);
            out[off + 15] = (byte) block;
        }
        try {
            ecb.get().doFinal(out, 0, keystreamLen, out, 0);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES failed", e);
        }
    }

    /**
     * Encrypts {@code len} bytes and appends the tag.
     *
     * @param ks keystream for {@code counter} from {@link #keystream}, or null to compute it now;
     *           ignored above {@link #PRECOMPUTE_MAX} bytes
     * @return bytes written ({@code len + TAG_LEN})
     */
    int seal(long counter, byte[] ks, byte[] in, int off, int len, byte[] out, int outOff) {
        checkLen(len);
        if (len > PRECOMPUTE_MAX) {
            try {
                Cipher c = gcmEncrypt.get();
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce(counter)));
                return c.doFinal(in, off, len, out, outOff);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM failed", e);
            }
        }
        if (ks == null) {
            ks = newKeystream();
            keystream(counter, ks);
        }
        xor(in, off, ks, out, outOff, len);
        long[] y = ghash(out, outOff, len);
        LONGS.set(out, outOff + len, y[0] ^ (long) LONGS.get(ks, 0));
        LONGS.set(out, outOff + len + 8, y[1] ^ (long) LONGS.get(ks, 8));
        return len + TAG_LEN;
    }

    /**
     * Verifies the tag and decrypts.
     *
     * @param ks keystream for {@code counter}, or null to compute it now
     * @throws AEADBadTagException if the data or tag was altered, or the key/counter is wrong
     */
    byte[] open(long counter, byte[] ks, byte[] in, int off, int len) throws AEADBadTagException {
        int ctLen = len - TAG_LEN;
        if (ctLen < 0 || ctLen > maxPlain) {
            throw new AEADBadTagException("bad length " + len);
        }
        if (ctLen > PRECOMPUTE_MAX) {
            try {
                Cipher c = gcmDecrypt.get();
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce(counter)));
                return c.doFinal(in, off, len);
            } catch (AEADBadTagException e) {
                throw e;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM failed", e);
            }
        }
        if (ks == null) {
            ks = newKeystream();
            keystream(counter, ks);
        }
        long[] y = ghash(in, off, ctLen);
        byte[] tag = new byte[TAG_LEN];
        LONGS.set(tag, 0, y[0] ^ (long) LONGS.get(ks, 0));
        LONGS.set(tag, 8, y[1] ^ (long) LONGS.get(ks, 8));
        byte[] given = new byte[TAG_LEN];
        System.arraycopy(in, off + ctLen, given, 0, TAG_LEN);
        if (!MessageDigest.isEqual(tag, given)) {
            throw new AEADBadTagException("tag mismatch");
        }
        byte[] plain = new byte[ctLen];
        xor(in, off, ks, plain, 0, ctLen);
        return plain;
    }

    private void checkLen(int len) {
        if (len < 0 || len > maxPlain) {
            throw new IllegalArgumentException("length " + len + " exceeds " + maxPlain);
        }
    }

    /** out = in XOR keystream (data part, after the 16-byte tag mask). */
    private static void xor(byte[] in, int off, byte[] ks, byte[] out, int outOff, int len) {
        int i = 0;
        for (; i + 8 <= len; i += 8) {
            LONGS.set(out, outOff + i, (long) LONGS.get(in, off + i) ^ (long) LONGS.get(ks, 16 + i));
        }
        for (; i < len; i++) {
            out[outOff + i] = (byte) (in[off + i] ^ ks[16 + i]);
        }
    }

    /** GHASH_H(ciphertext || len block), no AAD. */
    private long[] ghash(byte[] c, int off, int len) {
        long yh = 0, yl = 0;
        int i = 0;
        for (; i + 16 <= len; i += 16) {
            yh ^= (long) LONGS.get(c, off + i);
            yl ^= (long) LONGS.get(c, off + i + 8);
            long zh = 0, zl = 0;
            for (int b = 0; b < 8; b++) {
                int idx = (b << 8) | (int) ((yh >>> (56 - 8 * b)) & 0xFF);
                zh ^= tHi[idx];
                zl ^= tLo[idx];
            }
            for (int b = 0; b < 8; b++) {
                int idx = ((b + 8) << 8) | (int) ((yl >>> (56 - 8 * b)) & 0xFF);
                zh ^= tHi[idx];
                zl ^= tLo[idx];
            }
            yh = zh;
            yl = zl;
        }
        if (i < len) {
            byte[] last = new byte[16];
            System.arraycopy(c, off + i, last, 0, len - i);
            long[] y = mul(yh ^ (long) LONGS.get(last, 0), yl ^ (long) LONGS.get(last, 8));
            yh = y[0];
            yl = y[1];
        }
        return mul(yh, yl ^ ((long) len * 8));
    }

    private long[] mul(long yh, long yl) {
        long zh = 0, zl = 0;
        for (int b = 0; b < 8; b++) {
            int idx = (b << 8) | (int) ((yh >>> (56 - 8 * b)) & 0xFF);
            zh ^= tHi[idx];
            zl ^= tLo[idx];
        }
        for (int b = 0; b < 8; b++) {
            int idx = ((b + 8) << 8) | (int) ((yl >>> (56 - 8 * b)) & 0xFF);
            zh ^= tHi[idx];
            zl ^= tLo[idx];
        }
        return new long[]{zh, zl};
    }

    /**
     * T[i][j] = (byte j at position i) * H. GCM numbers bits from the most significant bit of
     * byte 0, and multiplying by x is a right shift with reduction by R.
     */
    private void buildTables(long hHi, long hLo) {
        long[] vHi = new long[128], vLo = new long[128];
        long vh = hHi, vl = hLo;
        for (int k = 0; k < 128; k++) {
            vHi[k] = vh;
            vLo[k] = vl;
            boolean carry = (vl & 1) != 0;
            vl = (vl >>> 1) | (vh << 63);
            vh >>>= 1;
            if (carry) {
                vh ^= R;
            }
        }
        for (int i = 0; i < 16; i++) {
            int base = i << 8;
            for (int j = 1; j < 256; j++) {
                int top = Integer.highestOneBit(j);
                int k = 8 * i + Integer.numberOfLeadingZeros(top) - 24;
                tHi[base + j] = vHi[k] ^ tHi[base + (j ^ top)];
                tLo[base + j] = vLo[k] ^ tLo[base + (j ^ top)];
            }
        }
    }
}
