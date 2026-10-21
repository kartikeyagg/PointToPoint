package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.PacketCodec;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Messages of the remote desktop service. Each one is a single {@link com.kartikey.p2pchat.PeerLink}
 * payload whose first byte is the {@link Kind}; numbers are big-endian.
 *
 * <pre>
 * host   -> viewer : ScreenInfo, ControlState (reliable)   Tile fragments (datagrams)
 * viewer -> host   : ViewRequest, Refresh, MouseButton, MouseWheel, Key (reliable)
 *                    MouseMove (datagram: only the latest position matters)
 * </pre>
 *
 * The screen is cut into square tiles of {@link ScreenInfo#tileSize} pixels. A changed tile is
 * JPEG-encoded and sent as {@code fragCount} {@link Tile} datagrams that share one
 * {@code version}; a tile is drawn once all its fragments for that version have arrived. Lost
 * fragments are healed by the host re-sending every tile periodically, and by {@link Refresh}.
 */
public final class RemoteProtocol {

    /** Bumped on incompatible changes; sent in {@link ViewRequest}. */
    public static final int VERSION = 1;
    /** kind(1) version(4) tileX(2) tileY(2) fragIndex(2) fragCount(2) */
    public static final int TILE_HEADER = 1 + 4 + 2 + 2 + 2 + 2;
    /** Largest {@link Tile#data} chunk that still fits one datagram. */
    public static final int MAX_TILE_DATA = PacketCodec.MAX_PAYLOAD - TILE_HEADER;
    /** Mouse buttons in {@link MouseButton#button}. */
    public static final int BUTTON_LEFT = 1, BUTTON_MIDDLE = 2, BUTTON_RIGHT = 3;

    private RemoteProtocol() {}

    public enum Kind { SCREEN_INFO, TILE, CONTROL_STATE, VIEW_REQUEST, REFRESH, MOUSE_MOVE, MOUSE_BUTTON, MOUSE_WHEEL, KEY }

    public sealed interface Message
            permits ScreenInfo, Tile, ControlState, ViewRequest, Refresh, MouseMove, MouseButton, MouseWheel, Key {}

    /** Host screen size in pixels, tile size, and whether the viewer may send input. */
    public record ScreenInfo(int width, int height, int tileSize, boolean controlAllowed) implements Message {}

    /**
     * One fragment of an encoded tile. {@code tileX}/{@code tileY} count tiles, not pixels;
     * {@code version} grows every time the host re-encodes any tile.
     */
    public record Tile(int version, int tileX, int tileY, int fragIndex, int fragCount, byte[] data) implements Message {}

    /** The host user switched remote control on or off. */
    public record ControlState(boolean allowed) implements Message {}

    /** A viewer (re)joined: the host answers with ScreenInfo and a full frame. */
    public record ViewRequest(int protocolVersion) implements Message {}

    /** Viewer asks for every tile again (e.g. after heavy loss). */
    public record Refresh() implements Message {}

    /** Pointer position in host screen pixels. */
    public record MouseMove(int x, int y) implements Message {}

    /** {@code button} is one of the BUTTON_* constants. */
    public record MouseButton(int x, int y, int button, boolean pressed) implements Message {}

    /** Wheel clicks; positive scrolls down, like {@link java.awt.event.MouseWheelEvent#getWheelRotation}. */
    public record MouseWheel(int x, int y, int rotation) implements Message {}

    /** A {@link java.awt.event.KeyEvent} VK_ code going down or up. */
    public record Key(int keyCode, boolean pressed) implements Message {}

    /** Tiles and pointer moves go as datagrams; everything else must arrive, in order. */
    public static boolean isReliable(Message m) {
        return !(m instanceof Tile || m instanceof MouseMove);
    }

    public static byte[] encode(Message m) {
        ByteBuffer b;
        if (m instanceof ScreenInfo s) {
            b = start(Kind.SCREEN_INFO, 4 + 4 + 2 + 1).putInt(s.width()).putInt(s.height())
                    .putShort(u16(s.tileSize())).put(bool(s.controlAllowed()));
        } else if (m instanceof Tile t) {
            if (t.data().length > MAX_TILE_DATA) {
                throw new IllegalArgumentException("tile fragment too large: " + t.data().length);
            }
            b = start(Kind.TILE, TILE_HEADER - 1 + t.data().length).putInt(t.version())
                    .putShort(u16(t.tileX())).putShort(u16(t.tileY()))
                    .putShort(u16(t.fragIndex())).putShort(u16(t.fragCount())).put(t.data());
        } else if (m instanceof ControlState c) {
            b = start(Kind.CONTROL_STATE, 1).put(bool(c.allowed()));
        } else if (m instanceof ViewRequest v) {
            b = start(Kind.VIEW_REQUEST, 4).putInt(v.protocolVersion());
        } else if (m instanceof Refresh) {
            b = start(Kind.REFRESH, 0);
        } else if (m instanceof MouseMove mm) {
            b = start(Kind.MOUSE_MOVE, 8).putInt(mm.x()).putInt(mm.y());
        } else if (m instanceof MouseButton mb) {
            b = start(Kind.MOUSE_BUTTON, 10).putInt(mb.x()).putInt(mb.y()).put((byte) mb.button()).put(bool(mb.pressed()));
        } else if (m instanceof MouseWheel w) {
            b = start(Kind.MOUSE_WHEEL, 12).putInt(w.x()).putInt(w.y()).putInt(w.rotation());
        } else if (m instanceof Key k) {
            b = start(Kind.KEY, 5).putInt(k.keyCode()).put(bool(k.pressed()));
        } else {
            throw new IllegalArgumentException("unknown message " + m);
        }
        return b.array();
    }

    /** @throws IllegalArgumentException for anything malformed */
    public static Message decode(byte[] data) {
        if (data.length == 0) {
            throw new IllegalArgumentException("empty message");
        }
        int k = data[0] & 0xFF;
        if (k >= Kind.values().length) {
            throw new IllegalArgumentException("unknown message kind " + k);
        }
        ByteBuffer b = ByteBuffer.wrap(data, 1, data.length - 1);
        try {
            Message m = switch (Kind.values()[k]) {
                case SCREEN_INFO -> new ScreenInfo(b.getInt(), b.getInt(), b.getShort() & 0xFFFF, b.get() != 0);
                case TILE -> {
                    int version = b.getInt(), tx = b.getShort() & 0xFFFF, ty = b.getShort() & 0xFFFF;
                    int index = b.getShort() & 0xFFFF, count = b.getShort() & 0xFFFF;
                    if (count == 0 || index >= count) {
                        throw new IllegalArgumentException("bad fragment " + index + "/" + count);
                    }
                    byte[] chunk = new byte[b.remaining()];
                    b.get(chunk);
                    yield new Tile(version, tx, ty, index, count, chunk);
                }
                case CONTROL_STATE -> new ControlState(b.get() != 0);
                case VIEW_REQUEST -> new ViewRequest(b.getInt());
                case REFRESH -> new Refresh();
                case MOUSE_MOVE -> new MouseMove(b.getInt(), b.getInt());
                case MOUSE_BUTTON -> new MouseButton(b.getInt(), b.getInt(), b.get(), b.get() != 0);
                case MOUSE_WHEEL -> new MouseWheel(b.getInt(), b.getInt(), b.getInt());
                case KEY -> new Key(b.getInt(), b.get() != 0);
            };
            if (b.hasRemaining()) {
                throw new IllegalArgumentException("trailing bytes after " + Kind.values()[k]);
            }
            return m;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated " + Kind.values()[k]);
        }
    }

    private static ByteBuffer start(Kind kind, int bodyLength) {
        return ByteBuffer.allocate(1 + bodyLength).put((byte) kind.ordinal());
    }

    private static short u16(int v) {
        if (v < 0 || v > 0xFFFF) {
            throw new IllegalArgumentException("value out of range: " + v);
        }
        return (short) v;
    }

    private static byte bool(boolean v) {
        return (byte) (v ? 1 : 0);
    }
}
