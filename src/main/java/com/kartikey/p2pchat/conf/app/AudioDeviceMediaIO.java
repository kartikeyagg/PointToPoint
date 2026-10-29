package com.kartikey.p2pchat.conf.app;

import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.media.audio.Opus;
import com.kartikey.p2pchat.conf.media.audio.OpusDecoder;
import com.kartikey.p2pchat.conf.media.audio.OpusEncoder;
import com.kartikey.p2pchat.conf.media.device.MicCapture;
import com.kartikey.p2pchat.conf.media.device.SpeakerPlayback;
import com.kartikey.p2pchat.conf.media.video.Vp8;
import com.kartikey.p2pchat.conf.media.video.Vp8Decoder;
import com.kartikey.p2pchat.conf.node.MediaIO;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * Microphone, speaker and Opus for a real meeting. Camera and screen sending are not wired yet
 * (see REMAINING.md); received video is still decoded with VP8.
 */
public final class AudioDeviceMediaIO implements MediaIO {

    private static final int MIC_QUEUE = 10;

    private final ArrayBlockingQueue<short[]> mic = new ArrayBlockingQueue<>(MIC_QUEUE);
    private final Consumer<String> onProblem;
    private final MediaIO fallback = MediaIO.silent();
    private MicCapture capture;
    private SpeakerPlayback speaker;
    private volatile boolean micOn;

    public AudioDeviceMediaIO(Consumer<String> onProblem) {
        this.onProblem = onProblem;
    }

    /** Device media when the codecs load, otherwise silent media so the meeting still works for chat and control. */
    public static MediaIO create(Consumer<String> onProblem) {
        if (!Opus.available()) {
            onProblem.accept("Opus codec not available: audio disabled");
            return MediaIO.silent();
        }
        return new AudioDeviceMediaIO(onProblem);
    }

    @Override
    public short[] pollMic() {
        return micOn ? mic.poll() : null;
    }

    @Override
    public void play(short[] pcm) {
        try {
            if (speaker == null) {
                if (!SpeakerPlayback.available()) {
                    return;
                }
                speaker = new SpeakerPlayback();
            }
            speaker.play(pcm);
        } catch (RuntimeException e) {
            onProblem.accept("speaker: " + e.getMessage());
            speaker = null;
        }
    }

    @Override
    public List<EncodedFrame[]> pollVideo() {
        return List.of();
    }

    @Override
    public synchronized void setMic(boolean on) {
        micOn = on;
        if (!on) {
            mic.clear();
            return;
        }
        if (capture == null) {
            try {
                capture = new MicCapture(frame -> {
                    if (!mic.offer(frame)) { // keep the newest audio: latency must not build up
                        mic.poll();
                        mic.offer(frame);
                    }
                });
                capture.start();
            } catch (Exception e) {
                capture = null;
                micOn = false;
                onProblem.accept("microphone unavailable: " + e.getMessage());
            }
        }
    }

    @Override
    public void setCamera(boolean on) {
        if (on) {
            onProblem.accept("camera sending is not wired up yet");
        }
    }

    @Override
    public void setScreen(boolean on) {
        if (on) {
            onProblem.accept("screen sharing is not wired up yet");
        }
    }

    @Override
    public void setLayersWanted(boolean[] wanted) {}

    @Override
    public void requestKeyframe(int layer) {}

    @Override
    public Codecs.AudioEncoder newAudioEncoder() {
        return new OpusEncoder();
    }

    @Override
    public Codecs.AudioDecoder newAudioDecoder() {
        return new OpusDecoder();
    }

    @Override
    public Codecs.VideoDecoder newVideoDecoder() {
        return Vp8.available() ? new Vp8Decoder() : fallback.newVideoDecoder();
    }

    @Override
    public synchronized void close() {
        micOn = false;
        if (capture != null) {
            capture.close();
            capture = null;
        }
        if (speaker != null) {
            speaker.close();
            speaker = null;
        }
    }
}
