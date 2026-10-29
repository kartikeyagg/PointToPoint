package com.kartikey.p2pchat.conf.media.audio;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.media.MediaFormat;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.ShortPointer;

/**
 * Opus decoder over FFmpeg. FFmpeg's API cannot ask libopus for concealment, so a lost frame is
 * covered by repeating the last output with a fade, which sounds better than a click to silence.
 */
public final class OpusDecoder implements Codecs.AudioDecoder {

    private static final int N = MediaFormat.FRAME_SAMPLES;
    private static final float FADE = 0.7f;

    private final AVCodecContext ctx;
    private final AVFrame frame;
    private final AVPacket packet;
    private short[] last = new short[N];
    private boolean closed;

    public OpusDecoder() {
        if (!Opus.available()) {
            throw new IllegalStateException("libopus not available");
        }
        AVCodec codec = avcodec_find_decoder_by_name("libopus");
        ctx = avcodec_alloc_context3(codec);
        frame = av_frame_alloc();
        packet = av_packet_alloc();
        try {
            ctx.sample_rate(MediaFormat.SAMPLE_RATE);
            av_channel_layout_default(ctx.ch_layout(), MediaFormat.CHANNELS);
            ctx.request_sample_fmt(AV_SAMPLE_FMT_S16);
            int r = avcodec_open2(ctx, codec, (AVDictionary) null);
            if (r < 0) {
                throw new IllegalStateException("opus decoder open failed: " + r);
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public short[] decode(byte[] data) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        short[] out = data == null || data.length == 0 ? null : decodePacket(data);
        if (out == null) {
            out = new short[N];
            for (int i = 0; i < N; i++) {
                out[i] = (short) (last[i] * FADE);
            }
        }
        last = out;
        return out.clone();
    }

    private short[] decodePacket(byte[] data) {
        if (av_new_packet(packet, data.length) < 0) {
            return null;
        }
        packet.data().put(data);
        int r = avcodec_send_packet(ctx, packet);
        av_packet_unref(packet);
        if (r < 0) {
            return null;
        }
        short[] out = new short[N];
        int filled = 0;
        while (avcodec_receive_frame(ctx, frame) >= 0) {
            int n = Math.min(frame.nb_samples(), N - filled);
            copy(frame, out, filled, n);
            filled += n;
            av_frame_unref(frame);
        }
        return filled == 0 ? null : out;
    }

    /** Takes whichever sample format the decoder produced: S16, S16P, FLT or FLTP (mono: planar equals packed). */
    private static void copy(AVFrame f, short[] out, int at, int n) {
        int fmt = f.format();
        if (fmt == AV_SAMPLE_FMT_S16 || fmt == AV_SAMPLE_FMT_S16P) {
            new ShortPointer(f.data(0)).limit(n).get(out, at, n);
        } else if (fmt == AV_SAMPLE_FMT_FLT || fmt == AV_SAMPLE_FMT_FLTP) {
            float[] in = new float[n];
            new FloatPointer(f.data(0)).limit(n).get(in);
            for (int i = 0; i < n; i++) {
                out[at + i] = (short) Math.max(-32768, Math.min(32767, Math.round(in[i] * 32768f)));
            }
        } else {
            throw new IllegalStateException("unsupported sample format " + fmt);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        av_packet_free(packet);
        av_frame_free(frame);
        avcodec_free_context(ctx);
    }
}
