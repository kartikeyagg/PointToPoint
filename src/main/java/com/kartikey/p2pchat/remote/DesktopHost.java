package com.kartikey.p2pchat.remote;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
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
    /*
     * Input arrives on the network thread and must never wait for a capture or encode step,
     * so the fields it reads are volatile instead of guarded by the lock.
     */
    private volatile boolean controlAllowed;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile boolean closed;
    private Thread captureThread;
    private int refreshCursor;
    private int version;
    private final AtomicLong tilesSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();

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
        } else if (message instanceof RemoteProtocol.MouseDelta delta) {
            if (controlAllowed && frameWidth > 0) {
                input.mouseMoveBy(clampDelta(delta.dx()), clampDelta(delta.dy()));
            }
        } else if (message instanceof RemoteProtocol.MouseButton button) {
            if (button.button() >= RemoteProtocol.BUTTON_LEFT && button.button() <= RemoteProtocol.BUTTON_RIGHT) {
                if (isHere(button.x(), button.y())) {
                    replayHere(() -> input.mouseButtonHere(button.button(), button.pressed()));
                } else {
                    replay(button.x(), button.y(), (x, y) -> input.mouseButton(x, y, button.button(), button.pressed()));
                }
            }
        } else if (message instanceof RemoteProtocol.MouseWheel wheel) {
            if (isHere(wheel.x(), wheel.y())) {
                replayHere(() -> input.mouseWheelHere(wheel.rotation()));
            } else {
                replay(wheel.x(), wheel.y(), (x, y) -> input.mouseWheel(x, y, wheel.rotation()));
            }
        } else if (message instanceof RemoteProtocol.Key key && key.keyCode() > 0) {
            if (controlAllowed && frameWidth > 0) {
                input.key(key.keyCode(), key.pressed());
            }
        }
    }

    /** Enables or disables remote input and informs the connected viewer. */
    public void setControlAllowed(boolean allowed) {
        controlAllowed = allowed;
        transport.send(new RemoteProtocol.ControlState(allowed));
    }

    public boolean isControlAllowed() {
        return controlAllowed;
    }

    public long tilesSent() {
        return tilesSent.get();
    }

    public long bytesSent() {
        return bytesSent.get();
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
            // Encode in parallel batches; tiles left over when the budget runs out stay dirty.
            while (!dirty.isEmpty() && used < budget) {
                List<TileEncoder.Position> batch = new ArrayList<>(BATCH);
                for (TileEncoder.Position position : dirty) {
                    batch.add(position);
                    if (batch.size() == BATCH) {
                        break;
                    }
                }
                List<byte[]> jpegs = encoder.encodeAll(frame, batch);
                for (int i = 0; i < batch.size(); i++) {
                    TileEncoder.Position position = batch.get(i);
                    List<RemoteProtocol.Tile> fragments = encoder.fragments(jpegs.get(i), position, version + 1);
                    List<byte[]> packets = new ArrayList<>(fragments.size());
                    long updateSize = 0;
                    for (RemoteProtocol.Tile tile : fragments) {
                        byte[] bytes = RemoteProtocol.encode(tile);
                        packets.add(bytes);
                        updateSize += bytes.length;
                    }
                    if (used > 0 && used + updateSize > budget) {
                        return;
                    }
                    version++;
                    for (byte[] bytes : packets) {
                        if (!transport.sendData(bytes)) {
                            return;
                        }
                        used += bytes.length;
                        bytesSent.addAndGet(bytes.length);
                    }
                    dirty.remove(position);
                    tilesSent.incrementAndGet();
                    if (used >= budget) {
                        return;
                    }
                }
            }
        }
    }

    /** Tiles encoded together; enough to keep every encoder thread busy. */
    private static final int BATCH = 32;

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
        encoder.close();
    }

    /** Largest relative move applied at once; a fast flick is a few hundred pixels. */
    static final int MAX_DELTA = 4096;

    private static int clampDelta(int d) {
        return Math.max(-MAX_DELTA, Math.min(MAX_DELTA, d));
    }

    private static boolean isHere(int x, int y) {
        return x == RemoteProtocol.HERE && y == RemoteProtocol.HERE;
    }

    private void replayHere(Runnable action) {
        if (controlAllowed && frameWidth > 0) {
            action.run();
        }
    }

    private void replay(int x, int y, BiConsumer<Integer, Integer> action) {
        int width = frameWidth, height = frameHeight;
        if (!controlAllowed || width <= 0 || height <= 0) {
            return;
        }
        action.accept(Math.max(0, Math.min(width - 1, x)), Math.max(0, Math.min(height - 1, y)));
    }

    private boolean ensureFrameLocked() {
        BufferedImage captured = screen.capture();
        if (captured == null || captured.getWidth() <= 0 || captured.getHeight() <= 0) {
            throw new IllegalStateException("screen source returned no image");
        }
        boolean sizeChanged = frame == null || frame.getWidth() != captured.getWidth()
                || frame.getHeight() != captured.getHeight();
        frame = copy(captured);
        frameWidth = frame.getWidth();
        frameHeight = frame.getHeight();
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

    /** Private RGB copy of the capture, so later changes to the source image can't race the encoders. */
    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] from = TileEncoder.pixels(source);
        int[] to = ((java.awt.image.DataBufferInt) result.getRaster().getDataBuffer()).getData();
        System.arraycopy(from, 0, to, 0, to.length);
        return result;
    }
}
