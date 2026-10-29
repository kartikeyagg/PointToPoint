package com.kartikey.p2pchat.conf.media.pipeline;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.stream.GroupCipher;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import com.kartikey.p2pchat.conf.stream.StreamVerifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Receives one remote stream: verify (signed manifests), then decrypt, then reassemble. Bad
 * packets are counted, never thrown, since the network is untrusted. Synchronised.
 */
public final class MediaReceiver {

    public interface Listener {
        void onAudio(long seq, byte[] opusPacket, int audioLevel);

        void onVideo(int layer, EncodedFrame frame);
    }

    private final Function<Long, Optional<byte[]>> keyForEpoch;
    private final Listener listener;
    private final StreamVerifier verifier;
    private final Map<Long, GroupCipher> ciphers = new HashMap<>();
    private final FrameAssembler[] assemblers = new FrameAssembler[3];
    private long rejected;

    public MediaReceiver(ParticipantId sender, int track, Function<Long, Optional<byte[]>> keyForEpoch,
                         Listener listener) {
        this.keyForEpoch = keyForEpoch;
        this.listener = listener;
        this.verifier = new StreamVerifier(sender, StreamIds.of(sender, track), 512, 2000);
        for (int i = 0; i < assemblers.length; i++) assemblers[i] = new FrameAssembler(1000);
    }

    public void onPacket(byte[] packet) {
        onPacket(packet, System.currentTimeMillis());
    }

    /** @param packet a media packet or manifest exactly as received from the network */
    public synchronized void onPacket(byte[] packet, long nowMillis) {
        for (byte[] verified : verifier.onPacket(packet, nowMillis)) {
            try {
                handle(verified, nowMillis);
            } catch (RuntimeException e) {
                rejected++;
            }
        }
        for (FrameAssembler a : assemblers) a.expire(nowMillis);
    }

    private void handle(byte[] packet, long now) {
        MediaHeader h = MediaHeader.decode(packet);
        long epoch = h.epoch() & 0xFFFFFFFFL;
        GroupCipher cipher = ciphers.get(epoch);
        if (cipher == null) {
            Optional<byte[]> key = keyForEpoch.apply(epoch);
            if (key.isEmpty()) throw new IllegalArgumentException("unknown epoch");
            if (ciphers.size() > 2) ciphers.clear();
            cipher = new GroupCipher(key.get());
            ciphers.put(epoch, cipher);
        }
        byte[] payload = cipher.open(packet);
        if (h.kind() == MediaHeader.Kind.AUDIO) {
            listener.onAudio(h.seq(), payload, h.audioLevel());
            return;
        }
        EncodedFrame f = assemblers[h.layer()].accept(Fragment.decode(payload), now);
        if (f != null) listener.onVideo(h.layer(), f);
    }

    /** True if a frame on this layer was lost and no keyframe has completed since. */
    public synchronized boolean needsKeyframe(int layer) {
        return assemblers[layer].needsKeyframe();
    }

    /** Packets that passed verification but failed decryption or parsing. */
    public synchronized long rejected() { return rejected; }

    public StreamVerifier verifier() { return verifier; }
}
