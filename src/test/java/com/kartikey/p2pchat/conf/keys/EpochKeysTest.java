package com.kartikey.p2pchat.conf.keys;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EpochKeysTest {

    private final byte[] meeting = Crypto.random(16);

    @Test
    void threeRecipientsUnwrapSameKeyOutsiderCannot() {
        Identity admin = Identity.generate(), a = Identity.generate(), b = Identity.generate(), c = Identity.generate();
        Identity outsider = Identity.generate();
        byte[] key = EpochSchedule.newKey();
        List<EpochBundle> bs = EpochBundle.create(admin, meeting, 1, key,
                List.of(a.id(), b.id(), c.id()), 1000, 5L);
        assertEquals(1, bs.size());
        EpochBundle bundle = bs.get(0);
        assertTrue(bundle.verify());
        for (Identity i : List.of(a, b, c)) {
            assertArrayEquals(key, bundle.unwrap(i).orElseThrow());
        }
        assertTrue(bundle.unwrap(outsider).isEmpty());
        assertEquals(KeyRing.Result.NOT_RECIPIENT,
                new KeyRing(meeting).accept(bundle, outsider, p -> true));
    }

    @Test
    void tamperAnywhereBreaksVerify() {
        Identity admin = Identity.generate(), a = Identity.generate();
        byte[] enc = EpochBundle.create(admin, meeting, 1, EpochSchedule.newKey(), List.of(a.id()), 10, 5L)
                .get(0).encode();
        for (int i = 0; i < enc.length; i++) {
            byte[] t = enc.clone();
            t[i] ^= 1;
            boolean ok;
            try {
                ok = EpochBundle.decode(t).verify();
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            assertFalse(ok, "byte " + i);
        }
    }

    @Test
    void nonAdminRejected() {
        Identity admin = Identity.generate(), rogue = Identity.generate(), me = Identity.generate();
        EpochBundle bundle = EpochBundle.create(rogue, meeting, 1, EpochSchedule.newKey(), List.of(me.id()), 10, 5L)
                .get(0);
        KeyRing ring = new KeyRing(meeting);
        assertEquals(KeyRing.Result.NOT_ADMIN, ring.accept(bundle, me, p -> p.equals(admin.id())));
        assertEquals(KeyRing.Result.ACCEPTED, ring.accept(bundle, me, p -> p.equals(rogue.id())));
        assertEquals(KeyRing.Result.DUPLICATE, ring.accept(bundle, me, p -> true));
        assertEquals(KeyRing.Result.WRONG_MEETING,
                new KeyRing(Crypto.random(16)).accept(bundle, me, p -> true));
    }

    @Test
    void badSignatureRejected() {
        Identity admin = Identity.generate(), me = Identity.generate();
        byte[] enc = EpochBundle.create(admin, meeting, 1, EpochSchedule.newKey(), List.of(me.id()), 10, 5L)
                .get(0).encode();
        enc[enc.length - 1] ^= 1;
        assertEquals(KeyRing.Result.BAD_SIGNATURE,
                new KeyRing(meeting).accept(EpochBundle.decode(enc), me, p -> true));
    }

    @Test
    void shardsCoverAllRecipients() {
        Identity admin = Identity.generate();
        List<Identity> ids = new ArrayList<>();
        List<ParticipantId> pids = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            Identity x = Identity.generate();
            ids.add(x);
            pids.add(x.id());
        }
        byte[] key = EpochSchedule.newKey();
        List<EpochBundle> bs = EpochBundle.create(admin, meeting, 7, key, pids, 1000, 5L);
        assertEquals(3, bs.size());
        assertEquals(1000, bs.get(0).entries().size());
        assertEquals(500, bs.get(2).entries().size());
        for (EpochBundle b : bs) {
            assertTrue(b.verify());
        }
        for (int i = 0; i < ids.size(); i++) {
            assertArrayEquals(key, bs.get(i / 1000).unwrap(ids.get(i)).orElseThrow(), "recipient " + i);
        }
    }

    @Test
    void retentionAndStale() {
        Identity admin = Identity.generate(), me = Identity.generate();
        KeyRing ring = new KeyRing(meeting);
        assertEquals(-1, ring.currentEpoch());
        assertTrue(ring.currentKey().isEmpty());
        byte[][] keys = new byte[6][];
        for (int e = 1; e <= 4; e++) {
            keys[e] = EpochSchedule.newKey();
            assertEquals(KeyRing.Result.ACCEPTED, ring.accept(
                    EpochBundle.create(admin, meeting, e, keys[e], List.of(me.id()), 10, 5L).get(0), me, p -> true));
        }
        assertEquals(4, ring.currentEpoch());
        assertTrue(ring.key(1).isEmpty());
        assertArrayEquals(keys[2], ring.key(2).orElseThrow());
        assertArrayEquals(keys[4], ring.currentKey().orElseThrow());
        // epoch 1 < 4 - 2 is stale; epoch 2 would be a duplicate
        assertEquals(KeyRing.Result.STALE, ring.accept(
                EpochBundle.create(admin, meeting, 1, keys[1], List.of(me.id()), 10, 5L).get(0), me, p -> true));
        assertEquals(KeyRing.Result.DUPLICATE, ring.accept(
                EpochBundle.create(admin, meeting, 2, keys[2], List.of(me.id()), 10, 5L).get(0), me, p -> true));
    }

    @Test
    void encodeDecodeRoundTrip() {
        Identity admin = Identity.generate(), me = Identity.generate();
        byte[] key = EpochSchedule.newKey();
        EpochBundle b = EpochBundle.create(admin, meeting, 9, key, List.of(me.id()), 10, 1234L).get(0);
        EpochBundle d = EpochBundle.decode(b.encode());
        assertArrayEquals(b.encode(), d.encode());
        assertTrue(d.verify());
        assertEquals(9, d.epoch());
        assertEquals(1234L, d.issuedAtMillis());
        assertEquals(admin.id(), d.issuer());
        assertArrayEquals(key, d.unwrap(me).orElseThrow());
    }

    @Test
    void malformedDecodeRejected() {
        Identity admin = Identity.generate(), me = Identity.generate();
        byte[] enc = EpochBundle.create(admin, meeting, 9, EpochSchedule.newKey(), List.of(me.id()), 10, 1L)
                .get(0).encode();
        for (int len : new int[]{0, 10, 100, enc.length - 1}) {
            byte[] t = java.util.Arrays.copyOf(enc, len);
            assertThrows(IllegalArgumentException.class, () -> EpochBundle.decode(t));
        }
        assertThrows(IllegalArgumentException.class, () -> EpochBundle.decode(java.util.Arrays.copyOf(enc, enc.length + 1)));
        // claim 65535 entries with a short body
        byte[] lie = enc.clone();
        int countOff = 16 + 8 + ParticipantId.ENCODED_LEN + 8 + 4 + 4 + 44;
        lie[countOff] = (byte) 0xFF;
        lie[countOff + 1] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> EpochBundle.decode(lie));
    }

    @Test
    void scheduleDue() {
        assertFalse(EpochSchedule.due(1000, 1000 + 59_999, false));
        assertTrue(EpochSchedule.due(1000, 1000 + 60_000, false));
        assertTrue(EpochSchedule.due(1000, 1001, true));
        assertEquals(32, EpochSchedule.newKey().length);
    }

    @Test
    void epochPinningRejected() {
        Identity admin = Identity.generate(), me = Identity.generate();
        KeyRing ring = new KeyRing(meeting);
        byte[] k = EpochSchedule.newKey();
        assertEquals(KeyRing.Result.TOO_FAR, ring.accept(
                EpochBundle.create(admin, meeting, Long.MAX_VALUE, k, List.of(me.id()), 10, 5L).get(0), me, p -> true));
        assertEquals(KeyRing.Result.TOO_FAR, ring.accept(
                EpochBundle.create(admin, meeting, 0x100000000L, k, List.of(me.id()), 10, 5L).get(0), me, p -> true));
        assertEquals(KeyRing.Result.ACCEPTED, ring.accept(
                EpochBundle.create(admin, meeting, 10, k, List.of(me.id()), 10, 5L).get(0), me, p -> true));
        assertEquals(KeyRing.Result.TOO_FAR, ring.accept(
                EpochBundle.create(admin, meeting, 15, k, List.of(me.id()), 10, 5L).get(0), me, p -> true));
        assertEquals(KeyRing.Result.ACCEPTED, ring.accept(
                EpochBundle.create(admin, meeting, 14, k, List.of(me.id()), 10, 5L).get(0), me, p -> true));
    }

    @Test
    void sameEpochSplitConvergesOnLowestIssuer() {
        Identity a1 = Identity.generate(), a2 = Identity.generate(), me = Identity.generate();
        Identity low = a1.id().compareTo(a2.id()) < 0 ? a1 : a2;
        Identity high = low == a1 ? a2 : a1;
        byte[] kl = EpochSchedule.newKey(), kh = EpochSchedule.newKey();
        EpochBundle bl = EpochBundle.create(low, meeting, 3, kl, List.of(me.id()), 10, 5L).get(0);
        EpochBundle bh = EpochBundle.create(high, meeting, 3, kh, List.of(me.id()), 10, 5L).get(0);
        // high first, then low replaces
        KeyRing r1 = new KeyRing(meeting);
        assertEquals(KeyRing.Result.ACCEPTED, r1.accept(bh, me, p -> true));
        assertEquals(KeyRing.Result.REPLACED, r1.accept(bl, me, p -> true));
        assertArrayEquals(kl, r1.key(3).orElseThrow());
        assertEquals(2, r1.candidates(3).size());
        assertArrayEquals(kh, r1.candidates(3).get(1));
        assertEquals(KeyRing.Result.DUPLICATE, r1.accept(bl, me, p -> true));
        // low first, high does not replace
        KeyRing r2 = new KeyRing(meeting);
        assertEquals(KeyRing.Result.ACCEPTED, r2.accept(bl, me, p -> true));
        assertEquals(KeyRing.Result.DUPLICATE, r2.accept(bh, me, p -> true));
        assertArrayEquals(kl, r2.key(3).orElseThrow());
        assertArrayEquals(kl, r2.candidates(3).get(0));
        assertArrayEquals(kh, r2.candidates(3).get(1));
    }

    @Test
    void signatureIsDomainSeparated() {
        Identity admin = Identity.generate(), me = Identity.generate();
        byte[] enc = EpochBundle.create(admin, meeting, 1, EpochSchedule.newKey(), List.of(me.id()), 10, 5L)
                .get(0).encode();
        byte[] body = java.util.Arrays.copyOf(enc, enc.length - 64);
        byte[] sig = admin.sign(body);
        byte[] forged = java.util.Arrays.copyOf(body, enc.length);
        System.arraycopy(sig, 0, forged, body.length, 64);
        assertFalse(EpochBundle.decode(forged).verify());
    }
}
