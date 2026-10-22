package com.kartikey.p2pchat.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameMouseTest {

    private final List<String> warps = new ArrayList<>();

    /** A pointer that really moves, like the OS does after Robot.mouseMove. */
    private GameMouse mouse() {
        return new GameMouse((x, y) -> warps.add(x + "," + y));
    }

    @Test
    void sendsMotionBetweenEventsAndNothingWhileOff() {
        GameMouse mouse = mouse();
        assertNull(mouse.moved(10, 10), "off: positions are not motion");
        mouse.start(500, 400, 100);
        assertEquals(List.of("500,400"), warps, "starts by centring the pointer");
        assertNull(mouse.moved(500, 400), "the warp itself is no motion");
        assertArrayEquals(new int[] {5, -2}, mouse.moved(505, 398));
        assertArrayEquals(new int[] {-10, 0}, mouse.moved(495, 398));
        mouse.stop();
        assertNull(mouse.moved(600, 400));
    }

    @Test
    void recentresWhenThePointerStraysAndSkipsEventsQueuedBeforeTheWarp() {
        GameMouse mouse = mouse();
        mouse.start(500, 400, 100);
        mouse.moved(500, 400);
        warps.clear();
        assertArrayEquals(new int[] {120, 0}, mouse.moved(620, 400), "the move that strays still counts");
        assertEquals(List.of("500,400"), warps, "and puts the pointer back");
        assertNull(mouse.moved(630, 400), "queued before the warp: skipped, not a jump back");
        assertArrayEquals(new int[] {3, 1}, mouse.moved(503, 401), "motion since the warp");
        assertArrayEquals(new int[] {1, 1}, mouse.moved(504, 402));
    }

    @Test
    void carriesOnWithoutWarpingWhenTheSystemRefusesToMoveThePointer() {
        GameMouse mouse = mouse(); // records warps but the "pointer" never actually moves
        mouse.start(500, 400, 100);
        int x = 900;
        for (int i = 0; i < GameMouse.MAX_SKIPPED; i++) {
            assertNull(mouse.moved(x++, 400));
        }
        assertNull(mouse.moved(x, 400), "gives up waiting for the warp; no jump");
        assertFalse(mouse.warpWorks());
        assertArrayEquals(new int[] {4, 0}, mouse.moved(x + 4, 400), "plain motion from here on");
        assertEquals(1, warps.size(), "no more warps");
    }

    @Test
    void leavingTheWindowBringsThePointerBack() {
        GameMouse mouse = mouse();
        mouse.start(500, 400, 100);
        mouse.moved(500, 400);
        mouse.exited();
        assertEquals(2, warps.size());
        assertTrue(mouse.active());
    }
}
