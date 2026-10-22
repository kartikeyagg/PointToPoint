package com.kartikey.p2pchat.remote;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Maintains a remote desktop canvas from messages received over a {@link Transport}. */
public final class DesktopViewer implements AutoCloseable {

    public interface Listener {
        default void frameUpdated() {}

        /**
         * A decoded tile was drawn at ({@code x}, {@code y}) in host-screen pixels. Called on the
         * render thread; {@code tile} must not be modified.
         */
        default void tileDrawn(int x, int y, BufferedImage tile) {
            frameUpdated();
        }

        default void stateChanged() {}
    }

    private final Transport transport;
    /** Decodes and draws tiles; inline by default, a dedicated thread in the app (see {@link #withRenderThread}). */
    private final Executor renderer;
    private final ExecutorService ownedRenderer;
    private final Object lock = new Object();
    /** Complete tiles waiting for the renderer; a newer version of a tile replaces an older one. */
    private final Map<TileCoordinate, PendingTile> pending = new LinkedHashMap<>();
    private boolean renderScheduled;
    private final TileAssembler assembler = new TileAssembler();
    private final Map<TileCoordinate, Integer> drawnVersions = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Reused: finding a JPEG reader for every tile costs several times the decoding itself. */
    private final ImageReader jpegReader = ImageIO.getImageReadersByFormatName("jpeg").next();
    private BufferedImage canvas;
    private int screenWidth;
    private int screenHeight;
    private int tileSize;
    private boolean controlAllowed;
    private boolean closed;
    private long tilesDrawn;
    private long tileMessages;
    private long tileBytes;
    private long decodeNanos;

    /** Running totals since the viewer started; compare two snapshots for rates. */
    public record Stats(long fragments, long bytes, long tilesDrawn, long tilesIncomplete, long decodeNanos) {}

    public Stats stats() {
        synchronized (lock) {
            return new Stats(tileMessages, tileBytes, tilesDrawn, assembler.incomplete(), decodeNanos);
        }
    }

    /** Decodes and draws each tile on the thread that delivers it. */
    public DesktopViewer(Transport transport) {
        this(transport, Runnable::run, null);
    }

    private DesktopViewer(Transport transport, Executor renderer, ExecutorService owned) {
        this.transport = transport;
        this.renderer = renderer;
        this.ownedRenderer = owned;
    }

    /**
     * Decodes and draws tiles on a thread of its own, so the network thread only reassembles
     * fragments and never waits for decoding or painting. Without this, a busy screen fills the
     * socket buffer and the system drops datagrams.
     */
    public static DesktopViewer withRenderThread(Transport transport) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "viewer-render");
            thread.setDaemon(true);
            return thread;
        });
        return new DesktopViewer(transport, executor, executor);
    }

    public void addListener(Listener l) {
        if (!closed && l != null) {
            listeners.add(l);
        }
    }

    public void onPeerSession() {
        send(new RemoteProtocol.ViewRequest(RemoteProtocol.VERSION));
    }

    public void onMessage(byte[] payload) {
        if (payload == null || closed) {
            return;
        }
        try {
            RemoteProtocol.Message message = RemoteProtocol.decode(payload);
            if (message instanceof RemoteProtocol.ScreenInfo info) {
                receiveScreenInfo(info);
            } else if (message instanceof RemoteProtocol.ControlState state) {
                receiveControlState(state);
            } else if (message instanceof RemoteProtocol.Tile tile) {
                receiveTile(tile);
            }
        } catch (IllegalArgumentException ignored) {
            // Network input is untrusted.
        }
    }

    public Dimension screenSize() {
        synchronized (lock) {
            return canvas == null ? null : new Dimension(screenWidth, screenHeight);
        }
    }

    public boolean controlAllowed() {
        synchronized (lock) {
            return !closed && controlAllowed;
        }
    }

    /** Draws the current canvas scaled into the given rectangle. */
    public void drawScaled(Graphics2D g, int x, int y, int width, int height) {
        synchronized (lock) {
            if (!closed && canvas != null && width > 0 && height > 0) {
                g.drawImage(canvas, x, y, width, height, null);
            }
        }
    }

    /** Returns an RGB copy of the current canvas. */
    public BufferedImage snapshot() {
        synchronized (lock) {
            if (canvas == null) {
                return null;
            }
            BufferedImage copy = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            copy.setRGB(0, 0, copy.getWidth(), copy.getHeight(),
                    canvas.getRGB(0, 0, canvas.getWidth(), canvas.getHeight(), null, 0, canvas.getWidth()),
                    0, canvas.getWidth());
            return copy;
        }
    }

    public long tilesDrawn() {
        synchronized (lock) {
            return tilesDrawn;
        }
    }

    public void mouseMove(int x, int y) {
        if (canControl()) {
            send(new RemoteProtocol.MouseMove(x, y));
        }
    }

    public void mouseButton(int x, int y, int button, boolean pressed) {
        if (canControl()) {
            send(new RemoteProtocol.MouseButton(x, y, button, pressed));
        }
    }

    public void mouseWheel(int x, int y, int rotation) {
        if (canControl()) {
            send(new RemoteProtocol.MouseWheel(x, y, rotation));
        }
    }

    public void key(int keyCode, boolean pressed) {
        if (canControl()) {
            send(new RemoteProtocol.Key(keyCode, pressed));
        }
    }

    public void requestRefresh() {
        send(new RemoteProtocol.Refresh());
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            canvas = null;
            drawnVersions.clear();
            assembler.clear();
            pending.clear();
        }
        listeners.clear();
        if (ownedRenderer != null) {
            ownedRenderer.shutdownNow();
        }
    }

    private void receiveScreenInfo(RemoteProtocol.ScreenInfo info) {
        if (info.width() <= 0 || info.height() <= 0 || info.tileSize() <= 0 || closed) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (canvas == null || screenWidth != info.width() || screenHeight != info.height()) {
                canvas = new BufferedImage(info.width(), info.height(), BufferedImage.TYPE_INT_RGB);
                screenWidth = info.width();
                screenHeight = info.height();
                drawnVersions.clear();
                assembler.clear();
                pending.clear();
            }
            tileSize = info.tileSize();
            controlAllowed = info.controlAllowed();
        }
        fireStateChanged();
    }

    private void receiveControlState(RemoteProtocol.ControlState state) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            controlAllowed = state.allowed();
        }
        fireStateChanged();
    }

    private void receiveTile(RemoteProtocol.Tile tile) {
        byte[] jpeg;
        synchronized (lock) {
            if (closed || canvas == null || !insideScreen(tile.tileX(), tile.tileY())) {
                return;
            }
            tileMessages++;
            tileBytes += tile.data().length;
            jpeg = assembler.add(tile);
        }
        if (jpeg == null) {
            return;
        }
        boolean schedule;
        synchronized (lock) {
            TileCoordinate coordinate = new TileCoordinate(tile.tileX(), tile.tileY());
            PendingTile previous = pending.get(coordinate);
            if (previous == null || previous.version() < tile.version()) {
                pending.put(coordinate, new PendingTile(coordinate, tile.version(), jpeg));
            }
            schedule = !renderScheduled;
            renderScheduled = true;
        }
        if (schedule) {
            renderer.execute(this::renderPending);
        }
    }

    /** Decodes and draws every pending tile, until none are left. */
    private void renderPending() {
        while (true) {
            java.util.List<PendingTile> batch;
            synchronized (lock) {
                if (pending.isEmpty() || closed) {
                    pending.clear();
                    renderScheduled = false;
                    return;
                }
                batch = new java.util.ArrayList<>(pending.values());
                pending.clear();
            }
            for (PendingTile tile : batch) {
                render(tile);
            }
        }
    }

    private void render(PendingTile tile) {
        long decodeStart = System.nanoTime();
        BufferedImage image = decode(tile.jpeg());
        long decodeTime = System.nanoTime() - decodeStart;
        if (image == null) {
            return;
        }
        int x, y;
        synchronized (lock) {
            TileCoordinate coordinate = tile.coordinate();
            if (closed || canvas == null || !insideScreen(coordinate.x(), coordinate.y())) {
                return;
            }
            Integer drawnVersion = drawnVersions.get(coordinate);
            if (drawnVersion != null && tile.version() <= drawnVersion) {
                return;
            }
            decodeNanos += decodeTime;
            x = coordinate.x() * tileSize;
            y = coordinate.y() * tileSize;
            Graphics2D graphics = canvas.createGraphics();
            graphics.drawImage(image, x, y, null);
            graphics.dispose();
            drawnVersions.put(coordinate, tile.version());
            tilesDrawn++;
        }
        for (Listener listener : listeners) {
            listener.tileDrawn(x, y, image);
        }
    }

    private BufferedImage decode(byte[] jpeg) {
        synchronized (jpegReader) {
            try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
                jpegReader.setInput(in, true, true);
                return jpegReader.read(0);
            } catch (IOException | RuntimeException ignored) {
                return null; // network input is untrusted
            } finally {
                jpegReader.setInput(null);
            }
        }
    }

    private boolean insideScreen(int x, int y) {
        return x >= 0 && y >= 0 && x < (screenWidth + tileSize - 1) / tileSize
                && y < (screenHeight + tileSize - 1) / tileSize;
    }

    private boolean canControl() {
        synchronized (lock) {
            return !closed && canvas != null && controlAllowed;
        }
    }

    private void send(RemoteProtocol.Message message) {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        transport.send(message);
    }

    private void fireStateChanged() {
        for (Listener listener : listeners) {
            listener.stateChanged();
        }
    }

    private record TileCoordinate(int x, int y) {}

    private record PendingTile(TileCoordinate coordinate, int version, byte[] jpeg) {}
}
