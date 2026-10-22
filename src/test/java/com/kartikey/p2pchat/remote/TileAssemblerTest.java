package com.kartikey.p2pchat.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TileAssemblerTest {

    @Test
    void joinsFragmentsInIndexOrderAndIgnoresDuplicates() {
        TileAssembler assembler = new TileAssembler();
        assertNull(assembler.add(new RemoteProtocol.Tile(1, 2, 3, 1, 2, new byte[]{3, 4})));
        assertNull(assembler.add(new RemoteProtocol.Tile(1, 2, 3, 1, 2, new byte[]{9})));
        assertArrayEquals(new byte[]{1, 2, 3, 4},
                assembler.add(new RemoteProtocol.Tile(1, 2, 3, 0, 2, new byte[]{1, 2})));
    }

    @Test
    void newerVersionDiscardsOlderPartial() {
        TileAssembler assembler = new TileAssembler();
        assertNull(assembler.add(new RemoteProtocol.Tile(1, 0, 0, 0, 2, new byte[]{1})));
        assertArrayEquals(new byte[]{3},
                assembler.add(new RemoteProtocol.Tile(2, 0, 0, 0, 1, new byte[]{3})));
        assertNull(assembler.add(new RemoteProtocol.Tile(1, 0, 0, 1, 2, new byte[]{2})));
    }
}
