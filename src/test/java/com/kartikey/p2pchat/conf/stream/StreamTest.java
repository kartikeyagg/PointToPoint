package com.kartikey.p2pchat.conf.stream;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamTest {
    Identity id;
    long sid;
    GroupCipher cipher;

    @BeforeEach
    void setUp() {
        id = Identity.generate();
        sid = StreamIds.of(id.id(), 3);
        cipher = new GroupCipher(Crypto.random(32));
    }

    MediaHeader hdr(long seq) {
        return new MediaHeader(MediaHeader.Kind.VIDEO, sid, 7, seq, 1, 2, 0, 1);
    }

    byte[] pkt(long seq) {
        return cipher.seal(hdr(seq), ("payload-" + seq).getBytes());
    }

    @Test
    void headerRoundTripAndStreamId() {
        MediaHeader h = new MediaHeader(MediaHeader.Kind.AUDIO, sid, -5, 1L << 40, 2, 255, 127, 1);
        assertEquals(h, MediaHeader.decode(h.encode()));
        assertEquals(MediaHeader.LENGTH, h.encode().length);
        assertEquals(3, sid & 0xFF);
    }

    @Test
    void sealOpenRoundTrip() {
        assertArrayEquals("payload-9".getBytes(), cipher.open(pkt(9)));
    }

    @Test
    void wrongKeyFails() {
        byte[] p = pkt(1);
        assertThrows(IllegalArgumentException.class, () -> new GroupCipher(Crypto.random(32)).open(p));
    }

    @Test
    void headerChangeDetected() {
        byte[] p = pkt(1);
        MediaHeader h = MediaHeader.decode(p);
        byte[] t = p.clone();
        byte[] nh = new MediaHeader(h.kind(), h.streamId(), h.epoch(), h.seq(), 0, h.stripe(),
                h.audioLevel(), h.flags()).encode();
        System.arraycopy(nh, 0, t, 0, nh.length);
        assertThrows(IllegalArgumentException.class, () -> cipher.open(t));

        List<byte[]> sent = new StreamSigner(id, sid, 1).onSend(p);
        StreamVerifier v = new StreamVerifier(id.id(), sid, 100, 1000);
        assertTrue(v.onPacket(t, 0).isEmpty());
        v.onPacket(sent.get(1), 0);
        assertEquals(1, v.tampered());
        assertEquals(0, v.verified());
    }

    @Test
    void bitFlipTampered() {
        StreamSigner s = new StreamSigner(id, sid, 2);
        byte[] a = pkt(0), b = pkt(1);
        s.onSend(a);
        List<byte[]> out = s.onSend(b);
        byte[] bad = a.clone();
        bad[MediaHeader.LENGTH + 2] ^= 1;
        StreamVerifier v = new StreamVerifier(id.id(), sid, 100, 1000);
        v.onPacket(out.get(1), 0);
        assertTrue(v.onPacket(bad, 0).isEmpty());
        assertEquals(1, v.tampered());
        assertEquals(1, v.onPacket(b, 0).size());
    }

    @Test
    void forgedManifest() {
        List<byte[]> out = new StreamSigner(Identity.generate(), sid, 1).onSend(pkt(0));
        StreamVerifier v = new StreamVerifier(id.id(), sid, 100, 1000);
        assertTrue(v.onPacket(out.get(0), 0).isEmpty());
        assertTrue(v.onPacket(out.get(1), 0).isEmpty());
        assertEquals(1, v.forgedManifests());
        assertEquals(0, v.verified());
    }

    @Test
    void manifestBeforeAndAfterAndReordered() {
        StreamSigner s = new StreamSigner(id, sid, 4);
        List<byte[]> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) all.addAll(s.onSend(pkt(i)));
        byte[] man = all.get(4);
        StreamVerifier after = new StreamVerifier(id.id(), sid, 100, 1000);
        int n = 0;
        for (int i : new int[] {2, 0, 3, 1}) n += after.onPacket(all.get(i), 0).size();
        assertEquals(0, n);
        assertEquals(4, after.onPacket(man, 1).size());

        StreamVerifier before = new StreamVerifier(id.id(), sid, 100, 1000);
        before.onPacket(man, 0);
        n = 0;
        for (int i : new int[] {3, 1, 0, 2}) n += before.onPacket(all.get(i), 0).size();
        assertEquals(4, n);
        assertEquals(4, before.verified());
    }

    @Test
    void replayDropped() {
        List<byte[]> out = new StreamSigner(id, sid, 1).onSend(pkt(0));
        StreamVerifier v = new StreamVerifier(id.id(), sid, 100, 1000);
        v.onPacket(out.get(1), 0);
        assertEquals(1, v.onPacket(out.get(0), 0).size());
        assertTrue(v.onPacket(out.get(0), 0).isEmpty());
        assertEquals(1, v.replays());
    }

    @Test
    void maxAgeEviction() {
        List<byte[]> out = new StreamSigner(id, sid, 1).onSend(pkt(0));
        StreamVerifier v = new StreamVerifier(id.id(), sid, 100, 100);
        v.onPacket(out.get(0), 0);
        assertTrue(v.onPacket(out.get(1), 500).isEmpty());
        assertEquals(1, v.unverifiedDropped());
    }

    @Test
    void flushPartialBatch() {
        StreamSigner s = new StreamSigner(id, sid, 10);
        byte[] a = pkt(0), b = pkt(1);
        assertEquals(1, s.onSend(a).size());
        s.onSend(b);
        List<byte[]> f = s.flush();
        assertEquals(1, f.size());
        assertTrue(s.flush().isEmpty());
        StreamVerifier v = new StreamVerifier(id.id(), sid, 100, 1000);
        v.onPacket(a, 0);
        v.onPacket(b, 0);
        assertEquals(2, v.onPacket(f.get(0), 0).size());
    }

    @Test
    void nonceUnique() {
        Set<String> seen = new HashSet<>();
        for (MediaHeader.Kind k : MediaHeader.Kind.values()) {
            if (k == MediaHeader.Kind.MANIFEST) continue;
            for (int layer = 0; layer < 3; layer++)
                for (long seq = 0; seq < 3; seq++)
                    assertTrue(seen.add(HexFormat.of().formatHex(
                            GroupCipher.nonce(new MediaHeader(k, sid, 0, seq, layer, 0, 0, 0)))));
        }
    }

    @Test
    void throughput() {
        long t0 = System.nanoTime();
        StreamSigner s = new StreamSigner(id, sid, 32);
        StreamVerifier v = new StreamVerifier(id.id(), sid, 4096, 10_000);
        byte[] payload = new byte[1000];
        int released = 0;
        for (int i = 0; i < 2000; i++)
            for (byte[] p : s.onSend(cipher.seal(hdr(i), payload))) released += v.onPacket(p, 0).size();
        for (byte[] p : s.flush()) released += v.onPacket(p, 0).size();
        assertEquals(2000, released);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000);
    }
}
