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
        return new HostSettings(20, 0.7f, 128, DEFAULT_MAX_BYTES_PER_SECOND, 3, true);
    }

    /** 8 MB/s: a whole changed 1080p screen fits in one frame, so moving windows don't arrive in bands. */
    public static final int DEFAULT_MAX_BYTES_PER_SECOND = 8_000_000;

    public HostSettings withFps(int framesPerSecond) {
        return new HostSettings(framesPerSecond, jpegQuality, tileSize, maxBytesPerSecond, refreshSeconds, controlAllowed);
    }

    public HostSettings withMaxBytesPerSecond(int bytesPerSecond) {
        return new HostSettings(fps, jpegQuality, tileSize, bytesPerSecond, refreshSeconds, controlAllowed);
    }

    public HostSettings withControlAllowed(boolean allowed) {
        return new HostSettings(fps, jpegQuality, tileSize, maxBytesPerSecond, refreshSeconds, allowed);
    }
}
