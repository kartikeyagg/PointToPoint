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
import org.bytedeco.javacpp.ShortPointer;

/**
 * Opus via FFmpeg's libopus. Tuned for lossy P2P voice: FEC lets the next packet repair a lost one,
 * and DTX makes silence cost almost nothing, which matters when most of a crowd is muted.
 */
public final class OpusEncoder implements Codecs.AudioEncoder {

    private final AVCodecContext ctx;
    private final AVFrame frame;
    private final AVPacket packet;
    private long pts;
    private boolean closed;

    public OpusEncoder() {
        this(MediaFormat.AUDIO_BITRATE);
    }

    public OpusEncoder(int bitrate) {
        if (!Opus.available()) {
            throw new IllegalStateException("libopus not available");
        }
        AVCodec codec = avcodec_find_encoder_by_name("libopus");
        ctx = avcodec_alloc_context3(codec);
        frame = av_frame_alloc();
        packet = av_packet_alloc();
        try {
            ctx.sample_rate(MediaFormat.SAMPLE_RATE);
            ctx.sample_fmt(AV_SAMPLE_FMT_S16);
            ctx.bit_rate(bitrate);
            ctx.frame_size(MediaFormat.FRAME_SAMPLES);
            av_channel_layout_default(ctx.ch_layout(), MediaFormat.CHANNELS);
            ctx.time_base().num(1).den(MediaFormat.SAMPLE_RATE);
            av_opt_set(ctx.priv_data(), "application", "voip", 0);
            av_opt_set(ctx.priv_data(), "frame_duration", "20", 0);
            av_opt_set(ctx.priv_data(), "fec", "1", 0);
            av_opt_set(ctx.priv_data(), "packet_loss", "10", 0);
            av_opt_set(ctx.priv_data(), "dtx", "1", 0);
            check(avcodec_open2(ctx, codec, (AVDictionary) null), "open");

            frame.format(AV_SAMPLE_FMT_S16);
            frame.sample_rate(MediaFormat.SAMPLE_RATE);
            frame.nb_samples(MediaFormat.FRAME_SAMPLES);
            av_channel_layout_default(frame.ch_layout(), MediaFormat.CHANNELS);
            check(av_frame_get_buffer(frame, 0), "frame buffer");
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public byte[] encode(short[] pcm) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        if (pcm.length != MediaFormat.FRAME_SAMPLES) {
            throw new IllegalArgumentException("need " + MediaFormat.FRAME_SAMPLES + " samples");
        }
        check(av_frame_make_writable(frame), "writable");
        new ShortPointer(frame.data(0)).put(pcm);
        frame.pts(pts);
        pts += MediaFormat.FRAME_SAMPLES;
        check(avcodec_send_frame(ctx, frame), "send");
        byte[] out = new byte[0];
        while (true) {
            int r = avcodec_receive_packet(ctx, packet);
            if (r == AVERROR_EAGAIN() || r == AVERROR_EOF) {
                break;
            }
            check(r, "receive");
            out = new byte[packet.size()];
            packet.data().get(out);
            av_packet_unref(packet);
        }
        return out;
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

    private static void check(int r, String what) {
        if (r < 0) {
            throw new IllegalStateException("opus " + what + " failed: " + r);
        }
    }
}
