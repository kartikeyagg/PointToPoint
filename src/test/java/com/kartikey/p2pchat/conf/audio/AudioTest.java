package com.kartikey.p2pchat.conf.audio;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.Identity;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioTest {

    static short[] sine(double rmsDb, int n) {
        double peak = Math.pow(10, rmsDb / 20) * Math.sqrt(2) * 32768;
        short[] f = new short[n];
        for (int i = 0; i < n; i++) {
            f[i] = (short) Math.round(peak * Math.sin(2 * Math.PI * 440 * i / 48000.0));
        }
        return f;
    }

    @Test
    void levels() {
        assertEquals(127, AudioLevel.of(new short[960]));
        short[] sq = new short[960];
        for (int i = 0; i < sq.length; i++) {
            sq[i] = (i / 24) % 2 == 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
        }
        assertTrue(AudioLevel.of(sq) <= 1);
        assertEquals(20, AudioLevel.of(sine(-20, 960)), 2);
    }

    @Test
    void picksLoudestN() {
        LoudestSelector s = new LoudestSelector(2, 1000);
        s.update(1, 50, 0);
        s.update(2, 20, 0);
        s.update(3, 30, 0);
        s.update(4, 127, 0);
        assertEquals(List.of(2L, 3L), s.selected(0));
    }

    @Test
    void hysteresisAndForget() {
        LoudestSelector s = new LoudestSelector(1, 1000);
        s.update(1, 10, 0);
        s.update(1, 127, 100);
        assertEquals(List.of(1L), s.selected(900));
        assertEquals(List.of(), s.selected(1100));
        s.update(2, 10, 5000);
        s.update(2, 127, 5001);
        s.selected(5002);
        assertEquals(List.of(), s.selected(7100));
        s.update(2, 127, 7200);
        assertEquals(List.of(), s.selected(7200));
    }

    @Test
    void pinnedAlwaysPresentAndFree() {
        LoudestSelector s = new LoudestSelector(1, 1000);
        s.pin(9);
        s.update(1, 10, 0);
        s.update(2, 20, 0);
        assertEquals(List.of(9L, 1L), s.selected(0));
        s.unpin(9);
        assertEquals(List.of(1L), s.selected(0));
    }

    @Test
    void mixerSingleVoiceUnchanged() {
        CrowdMixer m = new CrowdMixer(960);
        short[] v = sine(-12, 960);
        m.add(v, 1);
        CrowdMixer.MixResult r = m.mix();
        assertEquals(1, r.participants());
        for (int i = 0; i < v.length; i++) {
            assertEquals(v[i], r.samples()[i], Math.abs(v[i]) * 0.03 + 2);
        }
    }

    @Test
    void mixerNeverClips() {
        CrowdMixer m = new CrowdMixer(960);
        short[] loud = new short[960];
        Arrays.fill(loud, Short.MAX_VALUE);
        for (int i = 0; i < 10_000; i++) {
            m.add(loud, 1);
        }
        CrowdMixer.MixResult r = m.mix();
        assertEquals(10_000, r.participants());
        assertTrue(r.samples()[0] > 30000);
        assertEquals(0, m.mix().participants());
        m.add(sine(-3, 960), 5000);
        m.add(sine(-3, 960), 7);
        assertEquals(5007, m.mix().participants());
    }

    @Test
    void signedMix() {
        Identity id = Identity.generate();
        SignedMix sm = SignedMix.sign(id, 42, 1000, 3, new byte[] {1, 2, 3});
        assertTrue(sm.verify());
        SignedMix back = SignedMix.decode(sm.encode());
        assertEquals(sm, back);
        assertTrue(back.verify());
        assertFalse(new SignedMix(sm.mixer(), 43, 1000, 3, sm.payload(), sm.signature()).verify());
        assertFalse(new SignedMix(sm.mixer(), 42, 1001, 3, sm.payload(), sm.signature()).verify());
        assertFalse(new SignedMix(sm.mixer(), 42, 1000, 4, sm.payload(), sm.signature()).verify());
        assertFalse(new SignedMix(sm.mixer(), 42, 1000, 3, new byte[] {1, 2, 4}, sm.signature()).verify());
        assertFalse(new SignedMix(Identity.generate().id(), 42, 1000, 3, sm.payload(), sm.signature()).verify());
        byte[] enc = sm.encode();
        assertThrows(IllegalArgumentException.class, () -> SignedMix.decode(Arrays.copyOf(enc, enc.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> SignedMix.decode(new byte[10]));
    }

    @Test
    void audioMode() {
        assertEquals(AudioMode.CROWD, AudioMode.of(1000, 999));
        assertEquals(AudioMode.NORMAL, AudioMode.of(1000, 1000));
        assertEquals(AudioMode.NORMAL, AudioMode.of(0, 5));
    }

    @Test
    void mixerBounds() {
        CrowdMixer m = new CrowdMixer(4);
        assertThrows(IllegalArgumentException.class, () -> m.add(new short[3], 1));
        assertThrows(IllegalArgumentException.class, () -> m.add(new short[4], 0));
        m.add(new short[4], Integer.MAX_VALUE);
        m.add(new short[4], 5);
        assertEquals(Integer.MAX_VALUE, m.mix().participants());
    }

    @Test
    void signedMixIsDomainSeparated() {
        Identity id = Identity.generate();
        SignedMix sm = SignedMix.sign(id, 1, 2, 3, new byte[] {9});
        byte[] raw = Arrays.copyOf(sm.encode(), sm.encode().length - 64);
        assertFalse(id.id().verify(raw, sm.signature()));
    }
}
