package com.kartikey.p2pchat.conf.node.tree;

import com.kartikey.p2pchat.conf.ParticipantId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire codecs for the tree control messages (type bytes 0x20..0x2D). All decoders throw
 * {@link IllegalArgumentException} (or a runtime buffer exception) on malformed input; callers drop
 * those. Ids travel as {@link ParticipantId#encoded()}; ancestor paths as 8-byte hashes.
 *
 * <p>Crowd-up audio is deliberately <b>not</b> a control message: it is a lossy media packet that
 * starts with {@link #CROWD_UP_MAGIC} (never a valid media header version nibble), see CrowdSession.
 */
public final class TreeMessages {
    private TreeMessages() {}

    public static final int SUBSCRIBE = 0x20, UNSUBSCRIBE = 0x21, LEAVE = 0x22, PLACE = 0x23, ADOPT = 0x24,
            REPAIR = 0x25, ACCEPT = 0x26, REJECT = 0x27, LAYER = 0x28, DETACH = 0x29, PARENT_LEAVING = 0x2A,
            PATH = 0x2B, REPARENT = 0x2C, PUBLISH = 0x2D;
    public static final int CROWD_UP_MAGIC = 0xC0;
    public static final int MAX_CANDIDATES = 512, MAX_PATH = 64, MAX_BACKUPS = 4;

    /** Messages the coordinator handles; everything else in the range is for the Forwarder. */
    public static boolean forCoordinator(int type) {
        return type == SUBSCRIBE || type == UNSUBSCRIBE || type == LEAVE || type == REPARENT || type == PUBLISH;
    }

    public record Subscribe(long streamId, ParticipantId source, int maxLayer, int uploadKbps, boolean canRelay,
                            byte[] candidates) {}
    public record Place(long streamId, int stripe, ParticipantId parent, byte[] candidates,
                        ParticipantId grandparent, List<ParticipantId> backups, long[] path) {}
    public record Adopt(long streamId, int stripe, ParticipantId child, int maxLayer, byte[] candidates) {}
    public record Accept(long streamId, int stripe, ParticipantId grandparent, long[] path) {}
    public record Reparent(long streamId, int stripe, ParticipantId failed, boolean disconnected) {}

    // ---- encoders ----------------------------------------------------------------------------

    private static W w(int type) { W w = new W(); w.b(type); return w; }

    public static byte[] subscribe(Subscribe m) {
        W w = w(SUBSCRIBE);
        w.l(m.streamId).id(m.source).b(m.maxLayer).i(m.uploadKbps).b(m.canRelay ? 1 : 0).bytes(m.candidates);
        return w.done();
    }
    public static byte[] unsubscribe(long streamId) { return w(UNSUBSCRIBE).l(streamId).done(); }
    public static byte[] leave() { return w(LEAVE).done(); }
    public static byte[] publish(long streamId, byte[] candidates) { return w(PUBLISH).l(streamId).bytes(candidates).done(); }
    public static byte[] place(Place m) {
        W w = w(PLACE);
        w.l(m.streamId).b(m.stripe).id(m.parent).bytes(m.candidates);
        w.b(m.grandparent == null ? 0 : 1);
        if (m.grandparent != null) w.id(m.grandparent);
        w.b(m.backups.size());
        for (ParticipantId p : m.backups) w.id(p);
        return w.path(m.path).done();
    }
    public static byte[] adopt(Adopt m) {
        return w(ADOPT).l(m.streamId).b(m.stripe).id(m.child).b(m.maxLayer).bytes(m.candidates).done();
    }
    public static byte[] repair(long streamId, int stripe, int maxLayer) {
        return w(REPAIR).l(streamId).b(stripe).b(maxLayer).done();
    }
    public static byte[] accept(Accept m) {
        W w = w(ACCEPT).l(m.streamId).b(m.stripe);
        w.b(m.grandparent == null ? 0 : 1);
        if (m.grandparent != null) w.id(m.grandparent);
        return w.path(m.path).done();
    }
    public static byte[] reject(long streamId, int stripe) { return w(REJECT).l(streamId).b(stripe).done(); }
    public static byte[] layer(long streamId, int stripe, int maxLayer) { return w(LAYER).l(streamId).b(stripe).b(maxLayer).done(); }
    public static byte[] detach(long streamId, int stripe) { return w(DETACH).l(streamId).b(stripe).done(); }
    public static byte[] parentLeaving(long streamId, int stripe) { return w(PARENT_LEAVING).l(streamId).b(stripe).done(); }
    public static byte[] path(long streamId, int stripe, long[] path) { return w(PATH).l(streamId).b(stripe).path(path).done(); }
    public static byte[] reparent(Reparent m) {
        return w(REPARENT).l(m.streamId).b(m.stripe).id(m.failed).b(m.disconnected ? 1 : 0).done();
    }

    // ---- decoders (message includes the leading type byte) ------------------------------------

    public static Subscribe subscribe(byte[] msg) {
        R r = r(msg, SUBSCRIBE);
        return new Subscribe(r.l(), r.id(), r.layer(), r.i(), r.b() != 0, r.bytes());
    }
    /** Stream id: first field of every message except LEAVE. */
    public static long streamId(byte[] msg) { return r(msg, -1).l(); }
    /** Stripe: second field of the stripe-addressed messages. */
    public static int stripe(byte[] msg) { R r = r(msg, -1); r.l(); return r.b(); }
    public static Place place(byte[] msg) {
        R r = r(msg, PLACE);
        long sid = r.l();
        int stripe = r.b();
        ParticipantId parent = r.id();
        byte[] cand = r.bytes();
        ParticipantId gp = r.b() != 0 ? r.id() : null;
        int n = r.b();
        if (n > MAX_BACKUPS) throw new IllegalArgumentException("backups");
        List<ParticipantId> backups = new ArrayList<>();
        for (int k = 0; k < n; k++) backups.add(r.id());
        return new Place(sid, stripe, parent, cand, gp, backups, r.path());
    }
    public static Adopt adopt(byte[] msg) {
        R r = r(msg, ADOPT);
        return new Adopt(r.l(), r.b(), r.id(), r.layer(), r.bytes());
    }
    public static int repairLayer(byte[] msg) { R r = r(msg, REPAIR); r.l(); r.b(); return r.layer(); }
    public static int layerOf(byte[] msg) { R r = r(msg, LAYER); r.l(); r.b(); return r.layer(); }
    public static Accept accept(byte[] msg) {
        R r = r(msg, ACCEPT);
        long sid = r.l();
        int stripe = r.b();
        ParticipantId gp = r.b() != 0 ? r.id() : null;
        return new Accept(sid, stripe, gp, r.path());
    }
    public static long[] pathOf(byte[] msg) { R r = r(msg, PATH); r.l(); r.b(); return r.path(); }
    public static Reparent reparent(byte[] msg) {
        R r = r(msg, REPARENT);
        return new Reparent(r.l(), r.b(), r.id(), r.b() != 0);
    }
    public static byte[] publishCandidates(byte[] msg) { R r = r(msg, PUBLISH); r.l(); return r.bytes(); }

    private static R r(byte[] msg, int type) {
        if (msg.length < 1 || (type >= 0 && (msg[0] & 0xff) != type)) throw new IllegalArgumentException("type");
        return new R(ByteBuffer.wrap(msg, 1, msg.length - 1));
    }

    private static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        W b(int v) { o.write(v); return this; }
        W i(int v) { o.writeBytes(ByteBuffer.allocate(4).putInt(v).array()); return this; }
        W l(long v) { o.writeBytes(ByteBuffer.allocate(8).putLong(v).array()); return this; }
        W id(ParticipantId p) { o.writeBytes(p.encoded()); return this; }
        W bytes(byte[] d) {
            if (d.length > MAX_CANDIDATES) throw new IllegalArgumentException("too long");
            o.write(d.length >>> 8); o.write(d.length); o.writeBytes(d); return this;
        }
        W path(long[] p) {
            if (p.length > MAX_PATH) throw new IllegalArgumentException("path");
            o.write(p.length);
            for (long h : p) l(h);
            return this;
        }
        byte[] done() { return o.toByteArray(); }
    }

    private static final class R {
        final ByteBuffer b;
        R(ByteBuffer b) { this.b = b; }
        int b() { return b.get() & 0xff; }
        int layer() { int v = b(); if (v > 2) throw new IllegalArgumentException("layer"); return v; }
        int i() { return b.getInt(); }
        long l() { return b.getLong(); }
        ParticipantId id() {
            if (b.remaining() < ParticipantId.ENCODED_LEN) throw new IllegalArgumentException("short id");
            ParticipantId p = ParticipantId.decode(b.array(), b.arrayOffset() + b.position());
            b.position(b.position() + ParticipantId.ENCODED_LEN);
            return p;
        }
        byte[] bytes() {
            int n = b.getShort() & 0xffff;
            if (n > MAX_CANDIDATES || n > b.remaining()) throw new IllegalArgumentException("len");
            byte[] d = new byte[n];
            b.get(d);
            return d;
        }
        long[] path() {
            int n = b();
            if (n > MAX_PATH || n * 8 > b.remaining()) throw new IllegalArgumentException("path");
            long[] p = new long[n];
            for (int k = 0; k < n; k++) p[k] = b.getLong();
            return p;
        }
    }
}
