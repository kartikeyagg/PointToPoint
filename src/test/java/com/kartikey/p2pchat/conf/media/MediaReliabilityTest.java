package com.kartikey.p2pchat.conf.media;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.media.audio.Opus;
import com.kartikey.p2pchat.conf.media.audio.OpusDecoder;
import com.kartikey.p2pchat.conf.media.audio.OpusEncoder;
import com.kartikey.p2pchat.conf.media.pipeline.AudioJitterBuffer;
import com.kartikey.p2pchat.conf.media.pipeline.MediaReceiver;
import com.kartikey.p2pchat.conf.media.pipeline.MediaSender;
import com.kartikey.p2pchat.conf.media.video.Simulcast;
import com.kartikey.p2pchat.conf.media.video.Vp8;
import com.kartikey.p2pchat.conf.media.video.Vp8Decoder;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real codecs through the real pipeline over a bad network: random loss, jitter and reordering.
 * These are the numbers that decide whether a call is usable, so they are asserted, not just printed.
 */
class MediaReliabilityTest {

    /** Delivers packets after a random delay and drops some, like a congested path. */
    private static final class LossyLink {
        record InFlight(long at, long order, byte[] data) {}

        final PriorityQueue<InFlight> queue = new PriorityQueue<>((a, b) ->
                a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));
        final Random rnd;
        final double loss;
        final int baseMillis, jitterMillis;
        long order, sent, lost;

        LossyLink(long seed, double loss, int baseMillis, int jitterMillis) {
            this.rnd = new Random(seed);
            this.loss = loss;
            this.baseMillis = baseMillis;
            this.jitterMillis = jitterMillis;
        }

        void send(byte[] p, long now) {
            sent++;
            if (rnd.nextDouble() < loss) {
                lost++;
                return;
            }
            queue.add(new InFlight(now + baseMillis + rnd.nextInt(jitterMillis + 1), order++, p));
        }

        List<byte[]> due(long now) {
            List<byte[]> out = new ArrayList<>();
            while (!queue.isEmpty() && queue.peek().at <= now) {
                out.add(queue.poll().data);
            }
            return out;
        }
    }

    @Test
    void audioStaysContinuousAtTenPercentLoss() {
        assumeTrue(Opus.available(), "Opus natives not available");
        Identity speaker = Identity.generate();
        byte[] key = Crypto.random(32);
        Function<Long, Optional<byte[]>> keys = e -> Optional.of(key);
        LossyLink link = new LossyLink(1, 0.10, 30, 60);
        long[] now = {0};
        MediaSender sender = new MediaSender(speaker, 0, MediaHeader.Kind.AUDIO, () -> 1, keys, p -> link.send(p, now[0]));
        AudioJitterBuffer jitter = new AudioJitterBuffer(40, 200);
        MediaReceiver receiver = new MediaReceiver(speaker.id(), 0, keys, new MediaReceiver.Listener() {
            @Override
            public void onAudio(long seq, byte[] opus, int level) {
                jitter.put(seq, opus, now[0]);
            }

            @Override
            public void onVideo(int layer, EncodedFrame frame) {}
        });

        int frames = 1500;  // 30 s
        int decoded = 0;
        try (OpusEncoder enc = new OpusEncoder(); OpusDecoder dec = new OpusDecoder()) {
            short[] pcm = new short[MediaFormat.FRAME_SAMPLES];
            for (int f = 0; f < frames + 20; f++, now[0] += MediaFormat.FRAME_MILLIS) {
                if (f < frames) {
                    for (int i = 0; i < pcm.length; i++) {
                        pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 300 * (f * pcm.length + i) / 48_000.0));
                    }
                    sender.sendAudio(enc.encode(pcm), pcm);
                    if (f % 3 == 2) {
                        sender.tick();
                    }
                }
                for (byte[] p : link.due(now[0])) {
                    receiver.onPacket(p, now[0]);
                }
                byte[] packet = jitter.poll(now[0]);
                if (!jitter.isBuffering()) {
                    assertEquals(MediaFormat.FRAME_SAMPLES, dec.decode(packet).length);
                    if (packet != null) {
                        decoded++;
                    }
                }
            }
        }
        double delivered = decoded / (double) frames;
        System.out.printf("audio @10%% loss: delivered %.1f%%, concealed %d, late %d, target delay %d ms, rejected %d%n",
                100 * delivered, jitter.concealed(), jitter.late(), jitter.targetDelayMillis(), receiver.rejected());
        assertEquals(0, receiver.verifier().tampered());
        // network loss alone is 10%; signature batching must not turn that into much more
        assertTrue(delivered > 0.85, "only " + delivered + " of audio frames played");
    }

    @Test
    void videoRecoversFromLossWithKeyframeRequests() {
        assumeTrue(Vp8.available(), "VP8 natives not available");
        Identity speaker = Identity.generate();
        byte[] key = Crypto.random(32);
        Function<Long, Optional<byte[]>> keys = e -> Optional.of(key);
        LossyLink link = new LossyLink(2, 0.03, 30, 40);
        long[] now = {0};
        MediaSender sender = new MediaSender(speaker, 1, MediaHeader.Kind.VIDEO, () -> 1, keys, p -> link.send(p, now[0]));
        List<EncodedFrame> got = new ArrayList<>();
        MediaReceiver receiver = new MediaReceiver(speaker.id(), 1, keys, new MediaReceiver.Listener() {
            @Override
            public void onAudio(long seq, byte[] opus, int level) {}

            @Override
            public void onVideo(int layer, EncodedFrame frame) {
                if (layer == 1) {
                    got.add(frame);
                }
            }
        });

        int frames = 240, shown = 0;  // 10 s at 24 fps
        Random noise = new Random(5);
        try (Simulcast sim = new Simulcast(MediaFormat.VIDEO_FPS); Vp8Decoder dec = new Vp8Decoder()) {
            sim.setLayerEnabled(2, false);
            for (int f = 0; f < frames + 10; f++, now[0] += 1000 / MediaFormat.VIDEO_FPS) {
                if (f < frames) {
                    if (receiver.needsKeyframe(1)) {
                        sim.requestKeyframe(1);  // in the app this travels back as a control message
                    }
                    List<EncodedFrame> layers = sim.encode(movingPicture(f, noise), f == 0);
                    for (int l = 0; l < layers.size(); l++) {
                        if (layers.get(l) != null) {
                            sender.sendVideo(l, layers.get(l));
                        }
                    }
                    sender.tick();
                }
                for (byte[] p : link.due(now[0])) {
                    receiver.onPacket(p, now[0]);
                }
                for (EncodedFrame ef : got) {
                    if (dec.decode(ef) != null) {
                        shown++;
                    }
                }
                got.clear();
            }
        }
        double ratio = shown / (double) frames;
        System.out.printf("video 360p @3%% loss: shown %.1f%% of frames, rejected %d%n", 100 * ratio, receiver.rejected());
        assertEquals(0, receiver.verifier().tampered());
        assertTrue(ratio > 0.6, "only " + ratio + " of video frames shown");
    }

    private static VideoFrame movingPicture(int f, Random noise) {
        int w = 1280, h = 720;
        byte[] d = new byte[VideoFrame.size(w, h)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                d[y * w + x] = (byte) (((x + f * 6) ^ (y + f * 3)) & 0xff);
            }
        }
        for (int i = w * h; i < d.length; i++) {
            d[i] = (byte) (128 + noise.nextInt(8));
        }
        return new VideoFrame(w, h, d, f * 1_000_000L / MediaFormat.VIDEO_FPS);
    }
}
