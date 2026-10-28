package com.kartikey.p2pchat.conf.media.video;

import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.I420;
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
 * Real-time VP8 encoder (libvpx via FFmpeg) tuned for latency over compression: no lookahead, no
 * alt-ref frames, error-resilient so a lost delta frame doesn't poison later ones, and a small
 * rate-control buffer so bitrate spikes don't turn into delay. Not thread-safe.
 */
public final class Vp8Encoder implements Codecs.VideoEncoder {

    private final int width, height, fps;
    private int bitrate;
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket pkt;
    private long pts;
    private boolean needKey;
    private boolean closed;

    static {
        // libvpx prints a version banner per context at INFO level
        try {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        } catch (Throwable ignored) {
            // natives missing: the constructor will fail with a clearer error
        }
    }

    public Vp8Encoder(int width, int height, int bitrate, int fps) {
        this.width = width & ~1;
        this.height = height & ~1;
        this.bitrate = bitrate;
        this.fps = fps;
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
        AVCodec codec = avcodec.avcodec_find_encoder_by_name("libvpx");
        if (codec == null) {
            throw new IllegalStateException("FFmpeg build has no libvpx encoder");
        }
        ctx = avcodec.avcodec_alloc_context3(codec);
        ctx.width(width);
        ctx.height(height);
        ctx.pix_fmt(avutil.AV_PIX_FMT_YUV420P);
        ctx.time_base().num(1).den(fps);
        ctx.framerate().num(fps).den(1);
        ctx.gop_size(MediaFormat.KEYFRAME_INTERVAL_FRAMES);
        ctx.max_b_frames(0);
        ctx.bit_rate(bitrate);
        ctx.rc_max_rate(bitrate);
        ctx.rc_buffer_size(Math.max(bitrate / 4, 10_000)); // ~250 ms of video
        // threads scale with resolution: more only adds sync overhead on small pictures
        int px = width * height;
        int want = px >= 900_000 ? 4 : px >= 200_000 ? 2 : 1;
        ctx.thread_count(Math.min(want, Runtime.getRuntime().availableProcessors()));
        BytePointer priv = new BytePointer(ctx.priv_data());
        avutil.av_opt_set(priv, "deadline", "realtime", 0);
        avutil.av_opt_set(priv, "cpu-used", "16", 0);
        avutil.av_opt_set(priv, "lag-in-frames", "0", 0);
        avutil.av_opt_set(priv, "error-resilient", "1", 0);
        avutil.av_opt_set(priv, "auto-alt-ref", "0", 0);
        avutil.av_opt_set(priv, "noise-sensitivity", "0", 0);
        avutil.av_opt_set(priv, "static-thresh", "100", 0);
        int r = avcodec.avcodec_open2(ctx, codec, (AVDictionary) null);
        if (r < 0) {
            avcodec.avcodec_free_context(ctx);
            ctx = null;
            throw new IllegalStateException("avcodec_open2 failed: " + r);
        }
        frame.format(avutil.AV_PIX_FMT_YUV420P);
        frame.width(width);
        frame.height(height);
        if (avutil.av_frame_get_buffer(frame, 0) < 0) {
            throw new IllegalStateException("av_frame_get_buffer failed");
        }
    }

    @Override
    public EncodedFrame encode(VideoFrame src, boolean forceKeyframe) {
        if (closed) {
            return null;
        }
        VideoFrame f = (src.width() == width && src.height() == height) ? src : I420.scale(src, width, height);
        if (avutil.av_frame_make_writable(frame) < 0) {
            return null;
        }
        byte[] d = f.data();
        copyPlane(d, 0, width, height, 0);
        copyPlane(d, f.uOffset(), width / 2, height / 2, 1);
        copyPlane(d, f.vOffset(), width / 2, height / 2, 2);
        frame.pts(pts++);
        boolean key = forceKeyframe || needKey;
        needKey = false;
        frame.pict_type(key ? avutil.AV_PICTURE_TYPE_I : avutil.AV_PICTURE_TYPE_NONE);
        if (avcodec.avcodec_send_frame(ctx, frame) < 0) {
            return null;
        }
        if (avcodec.avcodec_receive_packet(ctx, pkt) < 0) {
            return null;
        }
        byte[] out = new byte[pkt.size()];
        pkt.data().get(out);
        boolean isKey = (pkt.flags() & avcodec.AV_PKT_FLAG_KEY) != 0;
        avcodec.av_packet_unref(pkt);
        return new EncodedFrame(out, isKey, f.timestampMicros(), width, height);
    }

    private void copyPlane(byte[] src, int off, int w, int h, int plane) {
        BytePointer p = frame.data(plane);
        int ls = frame.linesize(plane);
        for (int y = 0; y < h; y++) {
            p.position((long) y * ls).put(src, off + y * w, w);
        }
    }

    /**
     * libavcodec's libvpx wrapper can't change the rate of an open context, so this recreates it and
     * the next frame is a keyframe (the new context has no reference to predict from).
     */
    @Override
    public void setBitrate(int bitsPerSecond) {
        if (closed || bitsPerSecond == bitrate) {
            return;
        }
        bitrate = bitsPerSecond;
        avcodec.avcodec_free_context(ctx);
        ctx = null;
        open();
        needKey = true;
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
