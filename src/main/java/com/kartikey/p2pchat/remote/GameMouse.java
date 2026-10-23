package com.kartikey.p2pchat.remote;

/**
 * Turns pointer positions on the viewer into relative motion, for games that lock the pointer
 * (Krunker, browser shooters, FPS games). Java can't lock the pointer itself, so whenever the
 * pointer strays more than {@code radius} from the centre of the viewer, it is warped back there.
 * Events that were queued before the warp still carry the old far position; they are skipped
 * until the pointer is seen near the centre again.
 */
final class GameMouse {

    /** Moves the viewer's own pointer; {@link java.awt.Robot} in the app. */
    interface Pointer {
        void moveTo(int x, int y);
    }

    /** Far events tolerated after a warp before concluding that warping doesn't work here. */
    static final int MAX_SKIPPED = 8;

    private final Pointer pointer;
    private boolean active;
    private int centreX, centreY, radius;
    private int lastX, lastY;
    private boolean awaitingWarp;
    private int skipped;
    private boolean warpWorks = true;

    GameMouse(Pointer pointer) {
        this.pointer = pointer;
    }

    boolean active() {
        return active;
    }

    /** Whether the last warp was seen to take effect (false: the OS refused to move the pointer). */
    boolean warpWorks() {
        return warpWorks;
    }

    /** Starts relative mode around the given centre (screen coordinates). */
    void start(int centreX, int centreY, int radius) {
        active = true;
        warpWorks = true;
        setCentre(centreX, centreY, radius);
        warp();
    }

    void stop() {
        active = false;
    }

    /** The viewer window moved or was resized. */
    void setCentre(int centreX, int centreY, int radius) {
        this.centreX = centreX;
        this.centreY = centreY;
        this.radius = Math.max(8, radius);
    }

    /**
     * The pointer was seen at ({@code x}, {@code y}) on the viewer's screen.
     *
     * @return the motion to send as {dx, dy}, or null for none
     */
    int[] moved(int x, int y) {
        if (!active) {
            return null;
        }
        int dx, dy;
        if (awaitingWarp) {
            if (distance(x, y) > radius / 2 && ++skipped <= MAX_SKIPPED) {
                return null; // queued before the warp
            }
            if (skipped > MAX_SKIPPED) {
                warpWorks = false; // carry on without warping; motion stops at the screen edge
                dx = 0;
                dy = 0;
            } else {
                dx = x - centreX;
                dy = y - centreY;
            }
            awaitingWarp = false;
        } else {
            dx = x - lastX;
            dy = y - lastY;
        }
        lastX = x;
        lastY = y;
        if (warpWorks && distance(x, y) > radius) {
            warp();
        }
        return dx == 0 && dy == 0 ? null : new int[] {dx, dy};
    }

    /** The pointer left the viewer anyway (a very fast flick): bring it back. */
    void exited() {
        if (active && warpWorks) {
            warp();
        }
    }

    private void warp() {
        pointer.moveTo(centreX, centreY);
        lastX = centreX;
        lastY = centreY;
        awaitingWarp = true;
        skipped = 0;
    }

    private double distance(int x, int y) {
        return Math.hypot(x - centreX, y - centreY);
    }
}
