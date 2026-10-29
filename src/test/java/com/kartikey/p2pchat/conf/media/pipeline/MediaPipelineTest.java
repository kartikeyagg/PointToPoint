package com.kartikey.p2pchat.conf.media.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MediaPipelineTest {
    private final Identity me = Identity.generate();
    private final Map<Long, byte[]> keys = new HashMap<>();
    private final AtomicLong epoch = new AtomicLong(1);
    private final List<byte[]> wire = new ArrayList<>();
    private final List<Long> audioSeqs = new ArrayList<>();
    private final List<byte[]> audio = new ArrayList<>();
    private final List<int[]> levels = new ArrayList<>();
    private final Map<Integer, List<EncodedFrame>> video = new HashMap<>();

    private final MediaReceiver.Listener listener = new MediaReceiver.Listener() {
        @Override public void onAudio(long seq, byte[] p, int level) {
            audioSeqs.add(seq);
            audio.add(p);
            levels.add(new int[] {level});
        }
        @Override public void onVideo(int layer, EncodedFrame f) {
            video.computeIfAbsent(layer, l -> new ArrayList<>()).add(f);
        }
    };

    MediaPipelineTest() {
        SecureRandom r = new SecureRandom();
        for (long e : new long[] {1, 2}) {
            byte[] k = new byte[32];
            r.nextBytes(k);
            keys.put(e, k);
        }
    }

    private MediaSender sender(MediaHeader.Kind kind) {
        return new MediaSender(me, 3, kind, epoch::get, e -> Optional.ofNullable(keys.get(e)), wire::add);
    }

    private MediaReceiver receiver() {
        return new MediaReceiver(me.id(), 3, e -> Optional.ofNullable(keys.get(e)), listener);
    }

    private void deliver(MediaReceiver rx) {
        long t = 0;
        for (byte[] p : wire) rx.onPacket(p, t += 5);
    }

    private static boolean isMedia(byte[] p) {
        return MediaHeader.decode(p).kind() != MediaHeader.Kind.MANIFEST;
    }

    private static EncodedFrame frame(int size, boolean key, int seed) {
        byte[] d = new byte[size];
        new Random(seed).nextBytes(d);
        return new EncodedFrame(d, key, seed * 1000L, 640, 360);
    }

    @Test
    void audioWithTamperedPacketAndEpochSwitch() {
        MediaSender tx = sender(MediaHeader.Kind.AUDIO);
        List<byte[]> sent = new ArrayList<>();
        short[] loud = new short[960];
        java.util.Arrays.fill(loud, (short) 16000);
        for (int i = 0; i < 12; i++) {
            if (i == 6) epoch.set(2);
            byte[] opus = {(byte) i, 1, 2, 3};
            sent.add(opus);
            tx.sendAudio(opus, loud);
            if (i % 3 == 0) tx.tick();
        }
        tx.tick();
        // flip one byte of the 4th media packet
        int seen = 0;
        for (byte[] p : wire) {
            if (isMedia(p) && seen++ == 3) {
                p[p.length - 1] ^= 1;
                break;
            }
        }
        MediaReceiver rx = receiver();
        deliver(rx);
        assertEquals(11, audio.size());
        assertFalse(audioSeqs.contains(3L));
        for (int i = 0; i < audio.size(); i++) {
            assertArrayEquals(sent.get(audioSeqs.get(i).intValue()), audio.get(i));
            assertTrue(levels.get(i)[0] < 10);
        }
        assertEquals(1, rx.verifier().tampered());
    }

    @Test
    void threeLayerVideoWithTamperedFragment() {
        MediaSender tx = sender(MediaHeader.Kind.VIDEO);
        Map<Integer, List<EncodedFrame>> sent = new HashMap<>();
        for (int n = 0; n < 5; n++) {
            if (n == 3) epoch.set(2);
            for (int layer = 0; layer < 3; layer++) {
                EncodedFrame f = frame(2500 * (layer + 1), n == 0, n * 10 + layer);
                sent.computeIfAbsent(layer, l -> new ArrayList<>()).add(f);
                tx.sendVideo(layer, f);
            }
            tx.tick();
        }
        // corrupt one fragment of frame 2 on layer 1
        for (byte[] p : wire) {
            if (!isMedia(p)) continue;
            MediaHeader h = MediaHeader.decode(p);
            if (h.layer() == 1 && h.seq() > 0 && !h.keyframe() && corruptNext(h)) {
                p[p.length - 2] ^= 0x55;
                break;
            }
        }
        MediaReceiver rx = receiver();
        deliver(rx);

        assertEquals(5, video.get(0).size());
        assertEquals(5, video.get(2).size());
        assertEquals(4, video.get(1).size());
        for (int layer = 0; layer < 3; layer++) {
            for (EncodedFrame got : video.get(layer)) {
                boolean found = sent.get(layer).stream().anyMatch(s -> java.util.Arrays.equals(s.data(), got.data())
                        && s.keyframe() == got.keyframe() && s.timestampMicros() == got.timestampMicros());
                assertTrue(found);
            }
        }
        assertTrue(rx.needsKeyframe(1));
        assertFalse(rx.needsKeyframe(0));
        assertFalse(rx.needsKeyframe(2));
    }

    private int corrupted;

    /** Picks the first non-keyframe layer-1 fragment only. */
    private boolean corruptNext(MediaHeader h) {
        return corrupted++ == 0;
    }
}
