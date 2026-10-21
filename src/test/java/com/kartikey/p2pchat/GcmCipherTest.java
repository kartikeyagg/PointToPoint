package com.kartikey.p2pchat;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GcmCipherTest {

    private static final int MAX = PacketCodec.MAX_FRAME;

    private static byte[] jce(byte[] key, long counter, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, GcmCipher.nonce(counter)));
        return c.doFinal(plain);
    }

    @Test
    void matchesJdkAesGcmForEveryLength() throws Exception {
        Random rnd = new Random(1);
        byte[] key = new byte[32];
        rnd.nextBytes(key);
        GcmCipher gcm = new GcmCipher(key, MAX);
        for (int len = 0; len <= MAX; len += (len < 70 ? 1 : 37)) {
            byte[] plain = new byte[len];
            rnd.nextBytes(plain);
            long counter = rnd.nextLong() & Long.MAX_VALUE;
            byte[] out = new byte[len + GcmCipher.TAG_LEN];
            gcm.seal(counter, null, plain, 0, len, out, 0);
            assertArrayEquals(jce(key, counter, plain), out, "length " + len);
            assertArrayEquals(plain, gcm.open(counter, null, out, 0, out.length));
        }
    }

    @Test
    void precomputedKeystreamGivesSameResult() throws Exception {
        byte[] key = new byte[32];
        key[0] = 7;
        GcmCipher gcm = new GcmCipher(key, MAX);
        byte[] ks = gcm.newKeystream();
        gcm.keystream(42, ks);
        byte[] plain = "precomputed xor".getBytes();
        byte[] out = new byte[plain.length + 16];
        gcm.seal(42, ks, plain, 0, plain.length, out, 0);
        assertArrayEquals(jce(key, 42, plain), out);
    }

    @Test
    void tamperingAndWrongCounterAreRejected() throws Exception {
        GcmCipher gcm = new GcmCipher(new byte[32], MAX);
        for (int size : new int[]{100, MAX}) { // precomputed path and JDK path
            byte[] plain = new byte[size];
            byte[] out = new byte[size + 16];
            gcm.seal(5 + size, null, plain, 0, size, out, 0);
            assertThrows(AEADBadTagException.class, () -> gcm.open(6 + size, null, out, 0, out.length));
            byte[] bad = out.clone();
            bad[50] ^= 1;
            assertThrows(AEADBadTagException.class, () -> gcm.open(5 + size, null, bad, 0, bad.length));
            byte[] badTag = out.clone();
            badTag[size + 15] ^= 1;
            assertThrows(AEADBadTagException.class, () -> gcm.open(5 + size, null, badTag, 0, badTag.length));
            assertArrayEquals(plain, gcm.open(5 + size, null, out, 0, out.length));
        }
    }

    @Test
    void poolServesUpcomingCountersAndRefills() throws Exception {
        GcmCipher gcm = new GcmCipher(new byte[32], MAX);
        try (KeystreamPool pool = new KeystreamPool(gcm, 0, 8, "test-pool")) {
            for (long c = 0; c < 40; c++) {
                byte[] ks = null;
                for (int i = 0; i < 200 && ks == null; i++) {
                    ks = pool.take(c);
                    if (ks == null) {
                        Thread.sleep(5);
                    }
                }
                assertNotNull(ks, "counter " + c + " never precomputed");
                byte[] expected = gcm.newKeystream();
                gcm.keystream(c, expected);
                assertArrayEquals(expected, ks);
                pool.release(c);
            }
            assertNull(pool.take(10), "released counters are not served again");
            assertNull(pool.take(40 + 8), "beyond the window");
        }
    }

    @Test
    void sizeLimitIsEnforced() {
        GcmCipher gcm = new GcmCipher(new byte[32], 64);
        byte[] big = new byte[65];
        assertThrows(IllegalArgumentException.class,
                () -> gcm.seal(0, null, big, 0, big.length, new byte[100], 0));
        assertEquals(16 + 64, gcm.keystreamLen);
        assertEquals(16 + GcmCipher.PRECOMPUTE_MAX, new GcmCipher(new byte[32], MAX).keystreamLen);
        assertArrayEquals(new byte[12], Arrays.copyOf(GcmCipher.nonce(0), 12));
    }
}
