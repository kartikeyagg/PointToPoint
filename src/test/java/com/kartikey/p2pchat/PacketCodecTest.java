package com.kartikey.p2pchat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketCodecTest {

    private static final Packet MSG = new Packet(Packet.Type.MSG, 42L, 7L,
            "héllo 👋".getBytes(StandardCharsets.UTF_8));

    @Test
    void plainRoundTrip() {
        PacketCodec c = new PacketCodec(null);
        byte[] wire = c.encode(MSG);
        assertPacketEquals(MSG, c.decode(wire, wire.length));
    }

    @Test
    void encryptedRoundTripAndCiphertextHidesText() {
        PacketCodec c = new PacketCodec("secret");
        byte[] wire = c.encode(MSG);
        assertPacketEquals(MSG, c.decode(wire, wire.length));
        assertFalse(new String(wire, StandardCharsets.ISO_8859_1).contains("llo"));
    }

    @Test
    void wrongPassphraseIsRejected() {
        byte[] wire = new PacketCodec("secret").encode(MSG);
        assertThrows(IllegalArgumentException.class, () -> new PacketCodec("other").decode(wire, wire.length));
    }

    @Test
    void encryptionMismatchIsRejected() {
        byte[] plain = new PacketCodec(null).encode(MSG);
        assertThrows(IllegalArgumentException.class, () -> new PacketCodec("secret").decode(plain, plain.length));
        byte[] enc = new PacketCodec("secret").encode(MSG);
        assertThrows(IllegalArgumentException.class, () -> new PacketCodec(null).decode(enc, enc.length));
    }

    @Test
    void garbageAndOversizeAreRejected() {
        PacketCodec c = new PacketCodec(null);
        assertThrows(IllegalArgumentException.class, () -> c.decode(new byte[]{1, 2, 3}, 3));
        Packet big = new Packet(Packet.Type.MSG, 1, 1, new byte[PacketCodec.MAX_PAYLOAD + 1]);
        assertThrows(IllegalArgumentException.class, () -> c.encode(big));
    }

    private static void assertPacketEquals(Packet expected, Packet actual) {
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.session(), actual.session());
        assertEquals(expected.seq(), actual.seq());
        assertArrayEquals(expected.payload(), actual.payload());
    }
}
