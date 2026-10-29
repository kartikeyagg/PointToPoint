package com.kartikey.p2pchat.conf.node;

import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.media.VideoFrame;

import java.util.List;

/**
 * Devices and codecs as the node sees them. The app plugs in mic, speaker, camera, Opus and VP8
 * ({@code DeviceMediaIO}); tests and simulations plug in {@link #silent()} so hundreds of nodes run
 * in one JVM. All methods are called from the node's thread and must not block.
 */
public interface MediaIO extends AutoCloseable {

    /** The next 20 ms mic frame, or null if none is ready (mic off, or not time yet). */
    short[] pollMic();

    /** Plays one 20 ms frame (already mixed). */
    void play(short[] pcm);

    /** Encoded camera (or screen) layers produced since the last call: one array per picture, index = layer. */
    List<EncodedFrame[]> pollVideo();

    void setMic(boolean on);

    void setCamera(boolean on);

    void setScreen(boolean on);

    /** Which simulcast layers anyone watches; unwatched layers aren't encoded. */
    void setLayersWanted(boolean[] wanted);

    void requestKeyframe(int layer);

    Codecs.AudioEncoder newAudioEncoder();

    Codecs.AudioDecoder newAudioDecoder();

    Codecs.VideoDecoder newVideoDecoder();

    @Override
    void close();

    /**
     * No devices; a trivial lossy "codec" (every 4th sample, 8-bit) small enough for crowd mixes.
     * Mic frames come from {@link Silent#feed}, played audio collects in {@link Silent#played}.
     */
    static Silent silent() {
        return new Silent();
    }

    final class Silent implements MediaIO {
        public final java.util.ArrayDeque<short[]> mic = new java.util.ArrayDeque<>();
        public final java.util.List<short[]> played = new java.util.ArrayList<>();
        public boolean micOn, cameraOn, screenOn;

        /** Queues mic frames for {@link #pollMic}. */
        public void feed(short[] frame) {
            mic.addLast(frame);
        }

        @Override
        public short[] pollMic() {
            return micOn ? mic.pollFirst() : null;
        }

        @Override
        public void play(short[] pcm) {
            if (played.size() < 100_000) {
                played.add(pcm);
            }
        }

        @Override
        public List<EncodedFrame[]> pollVideo() {
            return List.of();
        }

        @Override
        public void setMic(boolean on) {
            micOn = on;
        }

        @Override
        public void setCamera(boolean on) {
            cameraOn = on;
        }

        @Override
        public void setScreen(boolean on) {
            screenOn = on;
        }

        @Override
        public void setLayersWanted(boolean[] wanted) {}

        @Override
        public void requestKeyframe(int layer) {}

        @Override
        public Codecs.AudioEncoder newAudioEncoder() {
            return new Codecs.AudioEncoder() {
                @Override
                public byte[] encode(short[] pcm) {
                    byte[] out = new byte[pcm.length / 4];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = (byte) (pcm[i * 4] >> 8);
                    }
                    return out;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public Codecs.AudioDecoder newAudioDecoder() {
            return new Codecs.AudioDecoder() {
                @Override
                public short[] decode(byte[] packet) {
                    short[] out = new short[MediaFormat.FRAME_SAMPLES];
                    if (packet != null) {
                        for (int i = 0; i < out.length && i / 4 < packet.length; i++) {
                            out[i] = (short) (packet[i / 4] << 8);
                        }
                    }
                    return out;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public Codecs.VideoDecoder newVideoDecoder() {
            return new Codecs.VideoDecoder() {
                @Override
                public VideoFrame decode(EncodedFrame frame) {
                    return null;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public void close() {}
    }
}
