package com.kartikey.p2pchat.conf.node.tree;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.audio.AudioMode;
import com.kartikey.p2pchat.conf.audio.CrowdMixer;
import com.kartikey.p2pchat.conf.audio.SignedMix;
import com.kartikey.p2pchat.conf.media.Codecs;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.stream.MediaHeader;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Crowd mode runtime (everyone may talk). Audio flows UP the stripe-0 tree of the crowd stream: every
 * 20 ms each node mixes what its children sent since its last frame, adds its own mic frame, encodes,
 * signs ({@link SignedMix}) and sends one packet to its parent. The root (the stream's source) sends
 * the final mix DOWN as CROWD_MIX media through the normal {@link Forwarder}.
 *
 * <p><b>Up transport = lossy media, not control.</b> Control is reliable and ordered per peer, so a
 * lost datagram would stall every later frame behind a retransmit (head-of-line blocking) and
 * deliver stale audio; a 20 ms frame is worthless when late, so a dropped frame is simply skipped
 * (the decoder conceals it). The packet is {@code 0xC0 | streamId(8) | SignedMix}; 0xC0 can never
 * be a media header (version nibble must be 1), so the Forwarder routes it here.
 *
 * <p>No waiting: a node never holds a frame for a slow child. Each tick it mixes the newest frame
 * each child delivered since the previous tick (at most one per child per tick, strictly increasing
 * frameSeq per child); a late frame is used by the next tick, a missing one is skipped. Hop delay is
 * therefore hop latency plus up to one frame per level. Single-threaded; drive with {@link #tick}.
 */
public final class CrowdSession implements Forwarder.CrowdHandler, Forwarder.Sink {

    public static final int FRAME_MILLIS = 20;
    /** Frames queued per child before the oldest is dropped (hop jitter can bunch two frames in one tick). */
    static final int MAX_QUEUED = 3;

    /** One final mix as seen by a node (root: as produced; others: as received). */
    public record MixFrame(long seq, int participants, short[] pcm, long atMillis) {}

    private final Identity self;
    private final PeerNetwork net;
    private final Forwarder fwd;
    private final long streamId;
    private final int frameSamples, stripes;
    private final Codecs.AudioEncoder encoder;
    private final Codecs.AudioDecoder sinkDecoder;
    private final Supplier<Codecs.AudioDecoder> decoderFactory;
    private final Supplier<short[]> mic;
    private final LongSupplier openMicUntil;
    private final Consumer<MixFrame> onFinalMix;
    private final CrowdMixer mixer;
    private final Map<ParticipantId, ArrayDeque<SignedMix>> inbox = new HashMap<>();
    private final Map<ParticipantId, Long> lastSeq = new HashMap<>();
    private final Map<ParticipantId, Codecs.AudioDecoder> decoders = new HashMap<>();
    private long nextFrameAt, frameCounter, rejected, framesSent, framesSkipped, lateMixes;
    private long lastDownSeq = -1, lastTick;

    /**
     * @param stripes    stripe count of the stream (the final mix is spread over them by seq % stripes)
     * @param mic        next mic frame, or null when muted / no mic
     * @param onFinalMix called with every final mix (root: produced, others: received and verified)
     */
    public CrowdSession(Identity self, PeerNetwork net, Forwarder fwd, long streamId, int frameSamples, int stripes,
                        Codecs.AudioEncoder encoder, Supplier<Codecs.AudioDecoder> decoderFactory,
                        Supplier<short[]> mic, LongSupplier openMicUntil, Consumer<MixFrame> onFinalMix) {
        this.self = self;
        this.net = net;
        this.fwd = fwd;
        this.streamId = streamId;
        this.frameSamples = frameSamples;
        this.stripes = stripes;
        this.encoder = encoder;
        this.decoderFactory = decoderFactory;
        this.sinkDecoder = decoderFactory.get();
        this.mic = mic;
        this.openMicUntil = openMicUntil;
        this.onFinalMix = onFinalMix;
        this.mixer = new CrowdMixer(frameSamples);
        fwd.setCrowdHandler(this);
    }

    public long rejectedCount() { return rejected; }
    /** Final mixes that arrived after a newer one (different stripe paths reorder) and were not played. */
    public long lateMixes() { return lateMixes; }
    public long framesSent() { return framesSent; }
    public long framesSkipped() { return framesSkipped; }

    /** Child to us (stripe-0 tree), unverified here: the signature is checked once, when the frame is mixed. */
    @Override
    public void onCrowdUp(ParticipantId from, byte[] packet) {
        try {
            if (packet.length < 9 + 1 || ByteBuffer.wrap(packet, 1, 8).getLong() != streamId) return;
            if (!fwd.childrenOf(streamId, 0).contains(from)) { rejected++; return; }
            SignedMix m = SignedMix.decode(Arrays.copyOfRange(packet, 9, packet.length));
            if (!m.mixer().equals(from)) { rejected++; return; }
            Long last = lastSeq.get(from);
            if (last != null && m.frameSeq() <= last) { rejected++; return; }
            ArrayDeque<SignedMix> q = inbox.computeIfAbsent(from, x -> new ArrayDeque<>());
            if (!q.isEmpty() && q.peekLast().frameSeq() >= m.frameSeq()) { rejected++; return; } // strictly increasing
            q.addLast(m);
            if (q.size() > MAX_QUEUED) { q.pollFirst(); framesSkipped++; } // bounded: stale audio is dropped
        } catch (RuntimeException e) {
            rejected++;
        }
    }

    /** Final mix coming down the normal forwarder (non-root nodes subscribed to the stream). */
    @Override
    public void onPacket(MediaHeader h, byte[] packet) {
        if (h.kind() != MediaHeader.Kind.CROWD_MIX || packet.length <= MediaHeader.LENGTH) return;
        try {
            SignedMix m = SignedMix.decode(Arrays.copyOfRange(packet, MediaHeader.LENGTH, packet.length));
            ParticipantId src = fwd.sourceOf(streamId);
            if (src == null || !m.mixer().equals(src)) { rejected++; return; }
            if (m.frameSeq() <= lastDownSeq) { lateMixes++; return; } // reordered across stripes: too late to play
            if (!m.verify()) { rejected++; return; }
            lastDownSeq = m.frameSeq();
            short[] pcm = sinkDecoder.decode(m.payload());
            if (onFinalMix != null) onFinalMix.accept(new MixFrame(m.frameSeq(), m.participants(), pcm, lastTick));
        } catch (RuntimeException e) {
            rejected++;
        }
    }

    public void tick(long now) {
        lastTick = now;
        if (AudioMode.of(openMicUntil.getAsLong(), now) != AudioMode.CROWD) {
            inbox.clear();
            nextFrameAt = 0;
            return;
        }
        if (nextFrameAt == 0) nextFrameAt = now - (now % FRAME_MILLIS) + FRAME_MILLIS;
        int guard = 0;
        while (now >= nextFrameAt && guard++ < 5) {
            frame(now);
            nextFrameAt += FRAME_MILLIS;
        }
        if (now >= nextFrameAt) nextFrameAt = now - (now % FRAME_MILLIS) + FRAME_MILLIS; // far behind: resync, skip frames
    }

    private void frame(long now) {
        List<ParticipantId> kids = fwd.childrenOf(streamId, 0);
        int inputs = 0;
        for (Map.Entry<ParticipantId, ArrayDeque<SignedMix>> e : inbox.entrySet()) {
            ParticipantId child = e.getKey();
            SignedMix m = e.getValue().pollFirst(); // one frame per child per tick; a backlog drains one per tick
            if (m == null || !kids.contains(child)) continue;
            lastSeq.put(child, m.frameSeq());
            if (!m.verify()) { rejected++; continue; }
            Codecs.AudioDecoder d = decoders.computeIfAbsent(child, x -> decoderFactory.get());
            short[] pcm = d.decode(m.payload());
            if (pcm.length != frameSamples) { rejected++; continue; }
            mixer.add(pcm, m.participants());
            inputs++;
        }
        inbox.keySet().retainAll(kids);
        if (lastSeq.size() > 4 * Math.max(8, kids.size())) lastSeq.keySet().retainAll(kids); // bounded
        decoders.keySet().retainAll(kids);
        short[] own = mic.get();
        if (own != null && own.length == frameSamples) {
            mixer.add(own, 1);
            inputs++;
        }
        CrowdMixer.MixResult r = mixer.mix();
        if (inputs == 0) { framesSkipped++; return; }
        byte[] payload = encoder.encode(r.samples());
        if (payload.length > SignedMix.MAX_PAYLOAD) { framesSkipped++; return; }
        long seq = frameCounter++;
        SignedMix sm = SignedMix.sign(self, seq, r.participants(), inputs, payload);
        byte[] enc = sm.encode();
        if (fwd.isSource(streamId)) {
            MediaHeader h = new MediaHeader(MediaHeader.Kind.CROWD_MIX, streamId, 0, seq, 0, (int) (seq % stripes), 0, 0);
            byte[] pkt = ByteBuffer.allocate(MediaHeader.LENGTH + enc.length).put(h.encode()).put(enc).array();
            fwd.send(pkt);
            framesSent++;
            if (onFinalMix != null) onFinalMix.accept(new MixFrame(seq, r.participants(), r.samples(), now));
        } else {
            ParticipantId parent = fwd.parentOf(streamId, 0);
            if (parent == null) { framesSkipped++; return; }
            byte[] pkt = ByteBuffer.allocate(9 + enc.length).put((byte) TreeMessages.CROWD_UP_MAGIC).putLong(streamId).put(enc).array();
            if (net.sendMedia(parent, pkt)) framesSent++;
        }
    }
}
