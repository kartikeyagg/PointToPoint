package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.PacketCodec;
import com.kartikey.p2pchat.remote.RemoteProtocol.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteProtocolTest {

    @Test
    void everyMessageRoundTrips() {
        List<Message> all = List.of(new ScreenInfo(2560, 1440, 64, true), new ControlState(false),
                new ViewRequest(RemoteProtocol.VERSION), new Refresh(), new MouseMove(-5, 1079),
                new MouseButton(10, 20, RemoteProtocol.BUTTON_RIGHT, true), new MouseWheel(1, 2, -3),
                new Key(java.awt.event.KeyEvent.VK_ENTER, false), new RemoteProtocol.MouseDelta(-7, 300),
                new MouseButton(RemoteProtocol.HERE, RemoteProtocol.HERE, RemoteProtocol.BUTTON_LEFT, false));
        for (Message m : all) {
            assertEquals(m, RemoteProtocol.decode(RemoteProtocol.encode(m)));
        }
        byte[] chunk = new byte[RemoteProtocol.MAX_TILE_DATA];
        chunk[chunk.length - 1] = 9;
        byte[] wire = RemoteProtocol.encode(new Tile(123456, 39, 22, 2, 3, chunk));
        assertEquals(PacketCodec.MAX_PAYLOAD, wire.length, "a full fragment fills one datagram exactly");
        Tile t = (Tile) RemoteProtocol.decode(wire);
        assertEquals(123456, t.version());
        assertEquals(39, t.tileX());
        assertEquals(22, t.tileY());
        assertEquals(2, t.fragIndex());
        assertEquals(3, t.fragCount());
        assertArrayEquals(chunk, t.data());
    }

    @Test
    void channelsAreChosenByKind() {
        assertFalse(RemoteProtocol.isReliable(new MouseMove(0, 0)));
        assertFalse(RemoteProtocol.isReliable(new RemoteProtocol.MouseDelta(1, 0)));
        assertFalse(RemoteProtocol.isReliable(new Tile(0, 0, 0, 0, 1, new byte[0])));
        assertTrue(RemoteProtocol.isReliable(new Key(65, true)));
        assertTrue(RemoteProtocol.isReliable(new ScreenInfo(1, 1, 64, false)));
    }

    @Test
    void malformedInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.decode(new byte[]{(byte) 200}));
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.decode(new byte[]{(byte) Kind.KEY.ordinal(), 0, 0}));
        byte[] key = RemoteProtocol.encode(new Key(1, true));
        byte[] longer = java.util.Arrays.copyOf(key, key.length + 1);
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.decode(longer));
        byte[] badFrag = RemoteProtocol.encode(new Tile(1, 0, 0, 0, 1, new byte[0]));
        badFrag[badFrag.length - 1] = 0; // fragCount 0
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.decode(badFrag));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteProtocol.encode(new Tile(0, 0, 0, 0, 1, new byte[RemoteProtocol.MAX_TILE_DATA + 1])));
    }
}
