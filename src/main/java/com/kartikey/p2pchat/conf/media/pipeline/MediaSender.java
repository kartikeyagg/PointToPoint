package com.kartikey.p2pchat.conf.media.pipeline;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.audio.AudioLevel;
import com.kartikey.p2pchat.conf.media.EncodedFrame;
import com.kartikey.p2pchat.conf.stream.GroupCipher;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import com.kartikey.p2pchat.conf.stream.StreamIds;
import com.kartikey.p2pchat.conf.stream.StreamSigner;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Turns codec output into sealed, signed packets for one stream (one sender, one track).
 *
 * <p>The seq counter is shared by all layers and fragments of the stream rather than kept per
 * layer: the single {@link StreamSigner} batches consecutive seqs and the verifier tracks seqs per
 * stream, so interleaved per-layer counters would break every batch. Seqs are still strictly
 * monotonic and never reused within a session, which is what {@link GroupCipher}'s nonce needs; a
 * restarted sender gets a new session (and so a new key) because seq starts at 0 again. Frame ids are
 * per layer.
 */
public final class MediaSender {
    private static final int BATCH = 8;

    private final MediaHeader.Kind kind;
    private final long streamId;
    private final long session = StreamIds.newSession();
    private final LongSupplier epochSupplier;
    private final Function<Long, Optional<byte[]>> keyForEpoch;
    private final Consumer<byte[]> out;
    private final StreamSigner signer;
    private final Map<Long, GroupCipher> ciphers = new HashMap<>();
    private final int[] frameIds = new int[3];
    private long seq;
    private long dropped;

    public MediaSender(Identity me, int track, MediaHeader.Kind kind, LongSupplier epochSupplier,
                       Function<Long, Optional<byte[]>> keyForEpoch, Consumer<byte[]> out) {
        this.kind = kind;
        this.streamId = StreamIds.of(me.id(), track);
        this.epochSupplier = epochSupplier;
        this.keyForEpoch = keyForEpoch;
        this.out = out;
        this.signer = new StreamSigner(me, streamId, session, BATCH, 2 * BATCH);
    }

    public synchronized void sendAudio(byte[] opusPacket, short[] pcmForLevel) {
        send(0, 0, AudioLevel.of(pcmForLevel), opusPacket);
    }

    public synchronized void sendVideo(int layer, EncodedFrame f) {
        int id = frameIds[layer]++;
        for (byte[] payload : Fragmenter.split(id, f)) {
            send(layer, f.keyframe() ? MediaHeader.FLAG_KEYFRAME : 0, AudioLevel.SILENCE, payload);
        }
    }

    private void send(int layer, int flags, int level, byte[] payload) {
        long epoch = epochSupplier.getAsLong();
        GroupCipher cipher = ciphers.get(epoch);
        if (cipher == null) {
            Optional<byte[]> key = keyForEpoch.apply(epoch);
            if (key.isEmpty()) { // no key yet: sending in clear is never an option
                dropped++;
                return;
            }
            if (ciphers.size() > 2) ciphers.clear();
            cipher = new GroupCipher(key.get());
            ciphers.put(epoch, cipher);
        }
        MediaHeader h = new MediaHeader(kind, streamId, (int) epoch, seq++, layer, 0, level, flags, session);
        for (byte[] p : signer.onSend(cipher.seal(h, payload))) out.accept(p);
    }

    /** Flushes a partial manifest batch; call every ~50 ms so receivers can verify promptly. */
    public synchronized void tick() {
        for (byte[] p : signer.flush()) out.accept(p);
    }

    /** Payloads dropped because no key was known for the current epoch. */
    public synchronized long dropped() { return dropped; }

    public long streamId() { return streamId; }
}
