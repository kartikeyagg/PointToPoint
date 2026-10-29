package com.kartikey.p2pchat.conf.media.video;

import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;

/**
 * VP8 decoder. Delta frames are useless without a reference, so until a keyframe arrives (at start
 * or after any error) frames are dropped rather than fed to libvpx, which would show garbage.
 * Not thread-safe.
 */
public final class Vp8Decoder implements Codecs.VideoDecoder {

    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket pkt;
    private boolean haveKey;
    private boolean closed;

    public Vp8Decoder() {
        frame = avutil.av_frame_alloc();
        pkt = avcodec.av_packet_alloc();
        try {
            open();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void open() {
        AVCodec codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_VP8);
        if (codec == null) {
            throw new IllegalStateException("FFmpeg build has no VP8 decoder");
        }
        ctx = avcodec.avcodec_alloc_context3(codec);
        ctx.thread_count(1); // frame threading would add latency
        if (avcodec.avcodec_open2(ctx, codec, (AVDictionary) null) < 0) {
            avcodec.avcodec_free_context(ctx);
            ctx = null;
            throw new IllegalStateException("avcodec_open2 failed");
        }
    }

    @Override
    public VideoFrame decode(EncodedFrame in) {
        if (closed || in == null || in.data().length == 0 || in.data().length > MediaFormat.MAX_ENCODED_FRAME
                || tooBig(in.width(), in.height())) {
            return null;
        }
        if (!headerOk(in.data())) {
            return null;
        }
        if (!haveKey) {
            if (!in.keyframe()) {
                return null;
            }
            haveKey = true;
        }
        if (avcodec.av_new_packet(pkt, in.data().length) < 0) {
            return null;
        }
        pkt.data().put(in.data());
        int r = avcodec.avcodec_send_packet(ctx, pkt);
        avcodec.av_packet_unref(pkt);
        if (r < 0 || avcodec.avcodec_receive_frame(ctx, frame) < 0) {
            if (r < 0) {
                haveKey = false;
                avcodec.avcodec_flush_buffers(ctx);
            }
            return null;
        }
        try {
            // a tiny hostile keyframe can declare 16383x16383; never allocate from untrusted dims unchecked
            if (tooBig(frame.width(), frame.height()) || frame.width() < 2 || frame.height() < 2) {
                haveKey = false;
                return null;
            }
            int w = frame.width() & ~1, h = frame.height() & ~1;
            byte[] out = new byte[VideoFrame.size(w, h)];
            copyPlane(0, out, 0, w, h);
            copyPlane(1, out, w * h, w / 2, h / 2);
            copyPlane(2, out, w * h + (w / 2) * (h / 2), w / 2, h / 2);
            return new VideoFrame(w, h, out, in.timestampMicros());
        } finally {
            avutil.av_frame_unref(frame);
        }
    }

    /**
     * Checks the VP8 frame tag (RFC 6386 9.1) so libvpx never sees a hostile size: a keyframe has
     * bit0 of byte0 clear, then start code 9d 01 2a and 14-bit little-endian width and height.
     */
    static boolean headerOk(byte[] d) {
        if (d.length < 3) {
            return false;
        }
        if ((d[0] & 1) != 0) {
            return true; // delta frame: no dimensions
        }
        if (d.length < 10 || (d[3] & 0xff) != 0x9d || d[4] != 0x01 || d[5] != 0x2a) {
            return false;
        }
        int w = ((d[6] & 0xff) | (d[7] & 0xff) << 8) & 0x3fff;
        int h = ((d[8] & 0xff) | (d[9] & 0xff) << 8) & 0x3fff;
        return w > 0 && h > 0 && !tooBig(w, h);
    }

    private static boolean tooBig(int w, int h) {
        return w > MediaFormat.MAX_VIDEO_WIDTH || h > MediaFormat.MAX_VIDEO_HEIGHT;
    }

    private void copyPlane(int plane, byte[] dst, int off, int w, int h) {
        BytePointer p = frame.data(plane);
        int ls = frame.linesize(plane);
        for (int y = 0; y < h; y++) {
            p.position((long) y * ls).get(dst, off + y * w, w);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ctx != null) {
            avcodec.avcodec_free_context(ctx);
            ctx = null;
        }
        if (frame != null) {
            avutil.av_frame_free(frame);
            frame = null;
        }
        if (pkt != null) {
            avcodec.av_packet_free(pkt);
            pkt = null;
        }
    }
}
