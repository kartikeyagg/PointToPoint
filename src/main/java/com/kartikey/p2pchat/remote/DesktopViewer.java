package com.kartikey.p2pchat.remote;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Maintains a remote desktop canvas from messages received over a {@link Transport}. */
public final class DesktopViewer implements AutoCloseable {

    public interface Listener {
        default void frameUpdated() {}
        default void stateChanged() {}
    }

    private final Transport transport;
    private final Object lock = new Object();
    private final TileAssembler assembler = new TileAssembler();
    private final Map<TileCoordinate, Integer> drawnVersions = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private BufferedImage canvas;
    private int screenWidth;
    private int screenHeight;
    private int tileSize;
    private boolean controlAllowed;
    private boolean closed;
    private long tilesDrawn;

    public DesktopViewer(Transport transport) {
        this.transport = transport;
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
        }
        listeners.clear();
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
            jpeg = assembler.add(tile);
        }
        if (jpeg == null) {
            return;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException ignored) {
            return;
        }
        if (image == null) {
            return;
        }
        synchronized (lock) {
            if (closed || canvas == null || !insideScreen(tile.tileX(), tile.tileY())) {
                return;
            }
            TileCoordinate coordinate = new TileCoordinate(tile.tileX(), tile.tileY());
            Integer drawnVersion = drawnVersions.get(coordinate);
            if (drawnVersion != null && tile.version() <= drawnVersion) {
                return;
            }
            Graphics2D graphics = canvas.createGraphics();
            graphics.drawImage(image, tile.tileX() * tileSize, tile.tileY() * tileSize, null);
            graphics.dispose();
            drawnVersions.put(coordinate, tile.version());
            tilesDrawn++;
        }
        fireFrameUpdated();
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

    private void fireFrameUpdated() {
        for (Listener listener : listeners) {
            listener.frameUpdated();
        }
    }

    private void fireStateChanged() {
        for (Listener listener : listeners) {
            listener.stateChanged();
        }
    }

    private record TileCoordinate(int x, int y) {}
}
