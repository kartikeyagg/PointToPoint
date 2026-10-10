package com.kartikey.p2pchat.remote;

/**
 * Tuning for {@link DesktopHost}.
 *
 * @param fps               screen captures per second
 * @param jpegQuality       0..1 for {@link javax.imageio.ImageIO} JPEG tiles
 * @param tileSize          tile edge in pixels
 * @param maxBytesPerSecond upload budget for tiles; changed tiles over budget wait for the next frame
 * @param refreshSeconds    every tile is re-sent at least this often, healing lost datagrams
 * @param controlAllowed    whether the viewer may move the mouse and type when sharing starts
 */
public record HostSettings(int fps, float jpegQuality, int tileSize, int maxBytesPerSecond,
                           int refreshSeconds, boolean controlAllowed) {

    public static HostSettings defaults() {
        return new HostSettings(10, 0.7f, 64, 2_000_000, 3, true);
    }

    public HostSettings withControlAllowed(boolean allowed) {
        return new HostSettings(fps, jpegQuality, tileSize, maxBytesPerSecond, refreshSeconds, allowed);
    }
}
