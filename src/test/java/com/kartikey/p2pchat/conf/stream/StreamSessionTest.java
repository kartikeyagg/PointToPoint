package com.kartikey.p2pchat.conf.stream;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamSessionTest {
    final Identity id = Identity.generate();
    final long sid = StreamIds.of(id.id(), 0);
    final GroupCipher cipher = new GroupCipher(Crypto.random(32));

    byte[] pkt(long session, long seq, byte[] payload) {
        return cipher.seal(new MediaHeader(MediaHeader.Kind.AUDIO, sid, 1, seq, 0, 0, 5, 0, session), payload);
    }

    /** All wire packets (media and manifests) of one session carrying seqs 0..n-1. */
    List<byte[]> run(long session, int n) {
        StreamSigner s = new StreamSigner(id, sid, session, 4, 8);
        List<byte[]> all = new ArrayList<>();
        for (int i = 0; i < n; i++) all.addAll(s.onSend(pkt(session, i, new byte[] {(byte) i, 1, 2, 3}), 0));
        all.addAll(s.flush());
        return all;
    }

    @Test
    void restartedSessionUsesDifferentKeystream() {
        byte[] payload = new byte[64];
        byte[] a = pkt(10, 0, payload), b = pkt(11, 0, payload);
        byte[] ca = Arrays.copyOfRange(a, MediaHeader.LENGTH, a.length - 16);
        byte[] cb = Arrays.copyOfRange(b, MediaHeader.LENGTH, b.length - 16);
        assertFalse(Arrays.equals(ca, cb));
        assertThrows(IllegalArgumentException.class, () -> {
            byte[] forged = a.clone();
            System.arraycopy(MediaHeader.decode(b).encode(), 0, forged, 0, MediaHeader.LENGTH);
            cipher.open(forged);
        });
        assertTrue(StreamIds.newSession() < StreamIds.newSession());
    }

    @Test
    void receiverFollowsNewSessionAndRejectsOldReplay() {
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        List<byte[]> old = run(100, 12);
        int got = 0;
        for (byte[] p : old) got += v.onPacket(p, 0).size();
        assertEquals(12, got);

        List<byte[]> live = run(200, 12);
        got = 0;
        for (byte[] p : live) got += v.onPacket(p, 0).size();
        assertEquals(12, got);

        long replaysBefore = v.replays();
        for (byte[] p : old) assertTrue(v.onPacket(p, 0).isEmpty());
        assertEquals(replaysBefore + old.size(), v.replays());

        // live stream continues undisturbed
        StreamSigner s = new StreamSigner(id, sid, 200, 4, 8);
        for (int i = 0; i < 12; i++) s.onSend(pkt(200, i, new byte[] {(byte) i, 1, 2, 3}), 0);
        List<byte[]> more = new ArrayList<>();
        for (int i = 12; i < 16; i++) more.addAll(s.onSend(pkt(200, i, new byte[4]), 0));
        got = 0;
        for (byte[] p : more) got += v.onPacket(p, 0).size();
        assertEquals(4, got);
    }

    @Test
    void manifestSignatureIsBoundToSession() {
        List<byte[]> r = run(5, 4);
        byte[] man = r.get(r.size() - 1);
        byte[] changed = man.clone();
        changed[MediaHeader.LENGTH - 1] ^= 1; // flip a session bit
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        v.onPacket(changed, 0);
        assertEquals(1, v.forgedManifests());
    }

    @Test
    void oldSeqBelowFloorRejected() {
        StreamVerifier v = new StreamVerifier(id.id(), sid, 512, 2000);
        StreamSigner s = new StreamSigner(id, sid, 1, 4, 8);
        List<byte[]> first = new ArrayList<>();
        for (int i = 0; i < 4; i++) first.addAll(s.onSend(pkt(1, i, new byte[4]), 0));
        // jump far ahead
        long far = 20000;
        List<byte[]> later = new ArrayList<>();
        for (long i = far; i < far + 4; i++) later.addAll(s.onSend(pkt(1, i, new byte[4]), 0));
        for (byte[] p : first) v.onPacket(p, 0);
        int got = 0;
        for (byte[] p : later) got += v.onPacket(p, 0).size();
        assertEquals(4, got);
        long before = v.replays();
        for (byte[] p : first) assertTrue(v.onPacket(p, 0).isEmpty());
        assertEquals(before + first.size(), v.replays());
    }
}
