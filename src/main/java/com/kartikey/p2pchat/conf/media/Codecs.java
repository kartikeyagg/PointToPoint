package com.kartikey.p2pchat.conf.media;

/** Codec interfaces, so pipelines and tests don't depend on FFmpeg. */
public final class Codecs {

    private Codecs() {}

    /** Encodes one {@link MediaFormat#FRAME_SAMPLES}-sample mono frame per call. */
    public interface AudioEncoder extends AutoCloseable {
        /** @return the packet; may be very small (or empty) for silence when DTX is on */
        byte[] encode(short[] pcm);

        @Override
        void close();
    }

    public interface AudioDecoder extends AutoCloseable {
        /**
         * @param packet the encoded frame, or {@code null} when it was lost: the decoder then
         *               conceals the gap
         * @return exactly {@link MediaFormat#FRAME_SAMPLES} samples
         */
        short[] decode(byte[] packet);

        @Override
        void close();
    }

    /** One encoder per simulcast layer; input frames are already at the layer's size. */
    public interface VideoEncoder extends AutoCloseable {
        /** @return the encoded frame, or {@code null} if the encoder produced nothing this time */
        EncodedFrame encode(VideoFrame frame, boolean forceKeyframe);

        void setBitrate(int bitsPerSecond);

        @Override
        void close();
    }

    public interface VideoDecoder extends AutoCloseable {
        /** @return the picture, or {@code null} if this frame can't be shown (e.g. waiting for a keyframe) */
        VideoFrame decode(EncodedFrame frame);

        @Override
        void close();
    }
}
