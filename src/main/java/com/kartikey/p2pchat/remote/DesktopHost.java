package com.kartikey.p2pchat.remote;

import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/** Captures, streams, and optionally accepts input for a shared desktop. */
public final class DesktopHost implements AutoCloseable {
    private final Transport transport;
    private final ScreenSource screen;
    private final InputSink input;
    private final HostSettings settings;
    private final Object lock = new Object();
    private final TileEncoder encoder;
    private final TreeSet<TileEncoder.Position> dirty = new TreeSet<>(
            Comparator.comparingInt(TileEncoder.Position::y).thenComparingInt(TileEncoder.Position::x));
    private final Set<String> reportedFailures = new HashSet<>();
    private BufferedImage frame;
    private boolean controlAllowed;
    private volatile boolean closed;
    private Thread captureThread;
    private int refreshCursor;
    private int version;
    private long tilesSent;
    private long bytesSent;

    public DesktopHost(Transport transport, ScreenSource screen, InputSink input, HostSettings settings) {
        if (transport == null || screen == null || input == null || settings == null) {
            throw new NullPointerException("host dependencies");
        }
        if (settings.fps() <= 0 || settings.maxBytesPerSecond() < 0 || settings.refreshSeconds() <= 0) {
            throw new IllegalArgumentException("bad host settings");
        }
        this.transport = transport;
        this.screen = screen;
        this.input = input;
        this.settings = settings;
        encoder = new TileEncoder(settings.tileSize(), settings.jpegQuality());
        controlAllowed = settings.controlAllowed();
    }

    /** Starts the periodic capture loop once. */
    public void start() {
        synchronized (lock) {
            if (closed || captureThread != null) {
                return;
            }
            captureThread = new Thread(this::captureLoop, "remote-capture");
            captureThread.setDaemon(true);
            captureThread.start();
        }
    }

    /** Announces the current screen and makes every tile available to a new viewer. */
    public void onPeerSession() {
        synchronized (lock) {
            ensureFrameLocked();
            sendScreenInfoLocked();
            markAllDirtyLocked();
        }
    }

    /** Handles bytes received from either transport channel. */
    public void onMessage(byte[] payload) {
        final RemoteProtocol.Message message;
        try {
            message = RemoteProtocol.decode(payload);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (message instanceof RemoteProtocol.ViewRequest || message instanceof RemoteProtocol.Refresh) {
            synchronized (lock) {
                if (message instanceof RemoteProtocol.ViewRequest) {
                    ensureFrameLocked();
                    sendScreenInfoLocked();
                }
                markAllDirtyLocked();
            }
            return;
        }
        if (message instanceof RemoteProtocol.MouseMove move) {
            replay(move.x(), move.y(), input::mouseMove);
        } else if (message instanceof RemoteProtocol.MouseButton button) {
            if (button.button() >= RemoteProtocol.BUTTON_LEFT && button.button() <= RemoteProtocol.BUTTON_RIGHT) {
                replay(button.x(), button.y(), (x, y) -> input.mouseButton(x, y, button.button(), button.pressed()));
            }
        } else if (message instanceof RemoteProtocol.MouseWheel wheel) {
            replay(wheel.x(), wheel.y(), (x, y) -> input.mouseWheel(x, y, wheel.rotation()));
        } else if (message instanceof RemoteProtocol.Key key && key.keyCode() > 0) {
            synchronized (lock) {
                if (controlAllowed && frame != null) {
                    // Deliberately outside the lock: input can block on the desktop system.
                } else {
                    return;
                }
            }
            input.key(key.keyCode(), key.pressed());
        }
    }

    /** Enables or disables remote input and informs the connected viewer. */
    public void setControlAllowed(boolean allowed) {
        synchronized (lock) {
            controlAllowed = allowed;
            transport.send(new RemoteProtocol.ControlState(allowed));
        }
    }

    public boolean isControlAllowed() {
        synchronized (lock) {
            return controlAllowed;
        }
    }

    public long tilesSent() {
        synchronized (lock) {
            return tilesSent;
        }
    }

    public long bytesSent() {
        synchronized (lock) {
            return bytesSent;
        }
    }

    /** Captures one frame and sends as many pending tile updates as the budget permits. */
    public void step() {
        synchronized (lock) {
            boolean sizeChanged = ensureFrameLocked();
            if (sizeChanged) {
                sendScreenInfoLocked();
            }
            addRefreshSliceLocked();
            long budget = settings.maxBytesPerSecond() / settings.fps();
            long used = 0;
            while (!dirty.isEmpty()) {
                TileEncoder.Position position = dirty.first();
                byte[] jpeg = encoder.encode(frame, position);
                List<RemoteProtocol.Tile> fragments = encoder.fragments(jpeg, position, version + 1);
                long updateSize = encodedSize(fragments);
                if (used > 0 && used + updateSize > budget) {
                    break;
                }
                version++;
                boolean complete = true;
                for (RemoteProtocol.Tile tile : fragments) {
                    byte[] bytes = RemoteProtocol.encode(tile);
                    if (!transport.sendData(bytes)) {
                        complete = false;
                        break;
                    }
                    used += bytes.length;
                    bytesSent += bytes.length;
                }
                if (!complete) {
                    break;
                }
                dirty.remove(position);
                tilesSent++;
                if (used >= budget) {
                    break;
                }
            }
        }
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            thread = captureThread;
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void replay(int x, int y, BiConsumer<Integer, Integer> action) {
        int clampedX;
        int clampedY;
        synchronized (lock) {
            if (!controlAllowed || frame == null) {
                return;
            }
            clampedX = Math.max(0, Math.min(frame.getWidth() - 1, x));
            clampedY = Math.max(0, Math.min(frame.getHeight() - 1, y));
        }
        action.accept(clampedX, clampedY);
    }

    private boolean ensureFrameLocked() {
        BufferedImage captured = screen.capture();
        if (captured == null || captured.getWidth() <= 0 || captured.getHeight() <= 0) {
            throw new IllegalStateException("screen source returned no image");
        }
        boolean sizeChanged = frame == null || frame.getWidth() != captured.getWidth()
                || frame.getHeight() != captured.getHeight();
        frame = copy(captured);
        dirty.addAll(encoder.changedTiles(frame));
        if (sizeChanged) {
            refreshCursor = 0;
        }
        return sizeChanged;
    }

    private void markAllDirtyLocked() {
        if (frame != null) {
            dirty.addAll(encoder.allTiles(frame));
        }
    }

    private void addRefreshSliceLocked() {
        if (frame == null) {
            return;
        }
        List<TileEncoder.Position> all = encoder.allTiles(frame);
        int count = Math.max(1, (all.size() + settings.refreshSeconds() * settings.fps() - 1)
                / (settings.refreshSeconds() * settings.fps()));
        for (int i = 0; i < count; i++) {
            dirty.add(all.get(refreshCursor));
            refreshCursor = (refreshCursor + 1) % all.size();
        }
    }

    private void sendScreenInfoLocked() {
        transport.send(new RemoteProtocol.ScreenInfo(frame.getWidth(), frame.getHeight(), settings.tileSize(), controlAllowed));
    }

    private long encodedSize(List<RemoteProtocol.Tile> fragments) {
        long total = 0;
        for (RemoteProtocol.Tile tile : fragments) {
            total += RemoteProtocol.encode(tile).length;
        }
        return total;
    }

    private void captureLoop() {
        long period = Math.max(1, 1000L / settings.fps());
        long next = System.currentTimeMillis();
        while (!closed) {
            try {
                step();
            } catch (RuntimeException e) {
                String message = String.valueOf(e.getMessage());
                synchronized (reportedFailures) {
                    if (reportedFailures.add(message)) {
                        System.err.println("remote capture: " + message);
                    }
                }
            }
            next += period;
            long wait = next - System.currentTimeMillis();
            if (wait <= 0) {
                next = System.currentTimeMillis();
                continue;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
                // close() interrupts this sleep; check the flag at the top of the loop.
            }
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        int width = source.getWidth(), height = source.getHeight();
        result.setRGB(0, 0, width, height, source.getRGB(0, 0, width, height, null, 0, width), 0, width);
        return result;
    }
}
