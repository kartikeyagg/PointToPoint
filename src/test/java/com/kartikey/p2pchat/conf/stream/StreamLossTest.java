package com.kartikey.p2pchat.conf.stream;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreamLossTest {
    final Identity id = Identity.generate();
    final long sid = StreamIds.of(id.id(), 0);
    final GroupCipher cipher = new GroupCipher(Crypto.random(32));

    byte[] pkt(long seq) {
        return cipher.seal(new MediaHeader(MediaHeader.Kind.AUDIO, sid, 1, seq, 0, 0, 10, 0), new byte[100]);
    }

    static boolean isManifest(byte[] p) {
        return MediaHeader.decode(p).kind() == MediaHeader.Kind.MANIFEST;
    }

    @Test
    void randomLossDeliversNearlyAllSurvivors() {
        StreamSigner s = new StreamSigner(id, sid, 8);
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        Random rnd = new Random(42);
        int mediaSent = 0, delivered = 0;
        long now = 0;
        for (int i = 0; i < 2000; i++) {
            for (byte[] p : s.onSend(pkt(i), now)) {
                boolean man = isManifest(p);
                if (!man) mediaSent++;
                if (rnd.nextDouble() < 0.10) continue;
                delivered += v.onPacket(p, now).size();
            }
            now += 20;
        }
        for (byte[] p : s.flush()) delivered += v.onPacket(p, now).size();
        double ratio = delivered / (double) mediaSent;
        System.out.printf("10%% loss: released %d of %d (%.3f)%n", delivered, mediaSent, ratio);
        assertTrue(ratio >= 0.88, "ratio " + ratio);
        assertEquals(0, v.tampered());
    }

    @Test
    void droppingEveryOtherManifestCostsNothing() {
        StreamSigner s = new StreamSigner(id, sid, 8);
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        int manifests = 0, delivered = 0;
        for (int i = 0; i < 160; i++)
            for (byte[] p : s.onSend(pkt(i), 0)) {
                if (isManifest(p) && manifests++ % 2 == 1) continue;
                delivered += v.onPacket(p, 0).size();
            }
        // the final batch's only covering manifest is the lost one or the not-yet-sent next one
        assertTrue(delivered >= 160 - 8, "delivered " + delivered);
        assertTrue(delivered > 0);
    }

    @Test
    void overlapIsConsistentAndSurvivesDuplicates() {
        StreamSigner s = new StreamSigner(id, sid, 4);
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        List<byte[]> mans = new ArrayList<>();
        int got = 0;
        for (int i = 0; i < 12; i++)
            for (byte[] p : s.onSend(pkt(i), 0)) {
                if (isManifest(p)) mans.add(p);
                got += v.onPacket(p, 0).size();
            }
        assertEquals(12, got);
        for (byte[] m : mans) assertTrue(v.onPacket(m, 0).isEmpty());
        assertEquals(0, v.forgedManifests());
        assertEquals(0, v.replays());
    }

    @Test
    void inconsistentOverlappingManifestIsForgery() {
        StreamSigner s = new StreamSigner(id, sid, 2);
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        for (int i = 0; i < 2; i++) for (byte[] p : s.onSend(pkt(i), 0)) v.onPacket(p, 0);
        assertEquals(2, v.verified());
        // validly signed, but claims a different hash for released seq 1 and also covers seq 2
        List<byte[]> hs = new ArrayList<>();
        hs.add(Manifest.hash(pkt(0)));
        hs.add(Manifest.hash(new byte[] {1, 2, 3}));
        hs.add(Manifest.hash(pkt(2)));
        byte[] bad = Manifest.sign(id, sid, 0, hs).encode();
        assertTrue(v.onPacket(bad, 0).isEmpty());
        assertEquals(1, v.forgedManifests());
        assertTrue(v.onPacket(pkt(2), 0).isEmpty());
        assertEquals(2, v.verified());
    }

    @Test
    void flushIfOlderThanBoundsLatency() {
        StreamSigner s = new StreamSigner(id, sid, 8);
        s.onSend(pkt(0), 1000);
        assertTrue(s.flushIfOlderThan(1020, 40).isEmpty());
        assertEquals(1, s.flushIfOlderThan(1040, 40).size());
        assertTrue(s.flushIfOlderThan(5000, 40).isEmpty());
    }
}
