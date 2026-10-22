package com.kartikey.p2pchat.remote;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.image.VolatileImage;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/** Swing window that displays and controls a {@link DesktopViewer}. */
public final class ViewerWindow {

    private final JFrame frame;
    private final JLabel status;
    private final AtomicBoolean closeNotified = new AtomicBoolean();
    private final Runnable onClose;
    private final DesktopViewer viewer;
    private final CanvasPanel canvas;
    private final GameMouse game;
    private final JButton gameButton;
    private final java.awt.Cursor blankCursor;

    private ViewerWindow(DesktopViewer viewer, String title, Runnable onClose) {
        this.onClose = onClose;
        this.viewer = viewer;
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        canvas = new CanvasPanel(viewer);
        canvas.setFocusable(true);
        canvas.setFocusTraversalKeysEnabled(false);
        game = new GameMouse(viewerPointer());
        blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new java.awt.Point(0, 0), "none");
        gameButton = new JButton(GAME_OFF);
        gameButton.setFocusable(false);
        gameButton.setToolTipText("Sends mouse motion instead of positions, for games that lock the pointer. "
                + "Ctrl+Alt+G turns it on and off.");
        gameButton.addActionListener(e -> setGameMouse(!game.active()));
        installInput();
        viewer.addListener(new DesktopViewer.Listener() {
            @Override
            public void tileDrawn(int x, int y, java.awt.image.BufferedImage tile) {
                canvas.tileDrawn(x, y, tile);
            }

            @Override
            public void stateChanged() {
                canvas.remoteChanged(); // the screen size may have changed
            }
        });

        status = new JLabel();
        JLabel control = new JLabel("", SwingConstants.RIGHT);
        JButton refresh = new JButton("Refresh");
        refresh.setFocusable(false);
        refresh.addActionListener(e -> viewer.requestRefresh());
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        buttons.add(refresh);
        buttons.add(gameButton);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(control, BorderLayout.EAST);
        bottom.add(buttons, BorderLayout.WEST);
        viewer.addListener(new DesktopViewer.Listener() {
            @Override
            public void stateChanged() {
                SwingUtilities.invokeLater(() -> {
                    control.setText(viewer.controlAllowed() ? "" : "View only");
                    if (!viewer.controlAllowed()) {
                        setGameMouse(false);
                    }
                });
            }
        });
        control.setText(viewer.controlAllowed() ? "" : "View only");

        frame.add(canvas, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(initialSize());
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                notifyClosed();
            }

            @Override
            public void windowDeactivated(WindowEvent event) {
                setGameMouse(false); // never keep hold of the pointer behind another window
            }
        });
    }

    /** Opens a viewer window on the event dispatch thread. */
    public static ViewerWindow open(DesktopViewer viewer, String title, Runnable onClose) {
        if (SwingUtilities.isEventDispatchThread()) {
            ViewerWindow window = new ViewerWindow(viewer, title, onClose);
            window.frame.setVisible(true);
            return window;
        }
        final ViewerWindow[] result = new ViewerWindow[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                result[0] = new ViewerWindow(viewer, title, onClose);
                result[0].frame.setVisible(true);
            });
        } catch (Exception e) {
            throw new IllegalStateException("unable to open viewer window", e);
        }
        return result[0];
    }

    public void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    public void close() {
        SwingUtilities.invokeLater(() -> {
            closeNotified.set(true);
            frame.dispose();
        });
    }

    static java.awt.Point toRemote(int px, int py, int panelW, int panelH, int remoteW, int remoteH) {
        Rectangle area = imageArea(panelW, panelH, remoteW, remoteH);
        if (area.width == 0 || area.height == 0 || px < area.x || py < area.y
                || px >= area.x + area.width || py >= area.y + area.height) {
            return null;
        }
        int x = area.width == 1 ? 0 : (int) ((long) (px - area.x) * (remoteW - 1) / (area.width - 1));
        int y = area.height == 1 ? 0 : (int) ((long) (py - area.y) * (remoteH - 1) / (area.height - 1));
        return new java.awt.Point(Math.min(remoteW - 1, x), Math.min(remoteH - 1, y));
    }

    static Rectangle imageArea(int panelW, int panelH, int remoteW, int remoteH) {
        if (panelW <= 0 || panelH <= 0 || remoteW <= 0 || remoteH <= 0) {
            return new Rectangle();
        }
        double scale = Math.min((double) panelW / remoteW, (double) panelH / remoteH);
        int width = Math.max(1, (int) Math.round(remoteW * scale));
        int height = Math.max(1, (int) Math.round(remoteH * scale));
        return new Rectangle((panelW - width) / 2, (panelH - height) / 2, width, height);
    }

    private static Dimension initialSize() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Dimension(Math.min(1280, screen.width), Math.min(800, screen.height));
    }

    private void notifyClosed() {
        if (closeNotified.compareAndSet(false, true) && onClose != null) {
            onClose.run();
        }
    }

    static final String GAME_OFF = "Game mouse", GAME_ON = "Release mouse (Ctrl+Alt+G)";

    private static GameMouse.Pointer viewerPointer() {
        try {
            java.awt.Robot robot = new java.awt.Robot();
            return robot::mouseMove;
        } catch (java.awt.AWTException | SecurityException e) {
            return (x, y) -> {}; // GameMouse notices that warps don't happen
        }
    }

    /** Game mouse mode: hide the pointer, keep it in the window, and send motion instead of positions. */
    private void setGameMouse(boolean on) {
        if (on == game.active() || (on && !viewer.controlAllowed()) || (on && !canvas.isShowing())) {
            return;
        }
        if (on) {
            canvas.requestFocusInWindow();
            canvas.setCursor(blankCursor);
            game.start(centreX(), centreY(), radius());
            setStatus("Game mouse on: Ctrl+Alt+G releases the mouse");
        } else {
            game.stop();
            canvas.setCursor(java.awt.Cursor.getDefaultCursor());
            setStatus("Game mouse off");
        }
        gameButton.setText(on ? GAME_ON : GAME_OFF);
    }

    private int centreX() {
        return canvas.getLocationOnScreen().x + canvas.getWidth() / 2;
    }

    private int centreY() {
        return canvas.getLocationOnScreen().y + canvas.getHeight() / 2;
    }

    /** How far the pointer may stray before it is put back: well inside the window. */
    private int radius() {
        return Math.min(canvas.getWidth(), canvas.getHeight()) / 8;
    }

    private void installInput() {
        JPanel panel = canvas;
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                panel.requestFocusInWindow();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                panel.requestFocusInWindow();
                sendButton(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                sendButton(event);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                game.exited();
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                sendMove(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                sendMove(event);
            }
        });
        MouseWheelListener wheel = event -> {
            if (game.active()) {
                viewer.mouseWheelHere(event.getWheelRotation());
                return;
            }
            java.awt.Point point = remotePoint(event, viewer, panel);
            if (point != null) {
                viewer.mouseWheel(point.x, point.y, event.getWheelRotation());
            }
        };
        panel.addMouseWheelListener(wheel);
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (isGameToggle(event)) {
                    setGameMouse(!game.active());
                    return;
                }
                sendKey(event, viewer, true);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                if (!isGameToggle(event)) {
                    sendKey(event, viewer, false);
                }
            }
        });
    }

    static boolean isGameToggle(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.VK_G && event.isControlDown() && event.isAltDown();
    }

    private void sendMove(MouseEvent event) {
        if (game.active()) {
            game.setCentre(centreX(), centreY(), radius());
            int[] delta = game.moved(event.getXOnScreen(), event.getYOnScreen());
            if (delta != null) {
                viewer.mouseDelta(delta[0], delta[1]);
            }
            return;
        }
        java.awt.Point point = remotePoint(event, viewer, canvas);
        if (point != null) {
            viewer.mouseMove(point.x, point.y);
        }
    }

    private void sendButton(MouseEvent event) {
        int button = SwingUtilities.isLeftMouseButton(event) ? RemoteProtocol.BUTTON_LEFT
                : SwingUtilities.isMiddleMouseButton(event) ? RemoteProtocol.BUTTON_MIDDLE
                : SwingUtilities.isRightMouseButton(event) ? RemoteProtocol.BUTTON_RIGHT : 0;
        boolean pressed = event.getID() == MouseEvent.MOUSE_PRESSED;
        if (button != 0 && game.active()) {
            viewer.mouseButtonHere(button, pressed);
            return;
        }
        java.awt.Point point = remotePoint(event, viewer, canvas);
        if (button != 0 && point != null) {
            viewer.mouseButton(point.x, point.y, button, pressed);
        }
    }

    private static void sendKey(KeyEvent event, DesktopViewer viewer, boolean pressed) {
        if (event.getKeyCode() != KeyEvent.VK_UNDEFINED) {
            viewer.key(event.getKeyCode(), pressed);
        }
    }

    private static java.awt.Point remotePoint(MouseEvent event, DesktopViewer viewer, JPanel panel) {
        Dimension size = viewer.screenSize();
        return size == null ? null : toRemote(event.getX(), event.getY(), panel.getWidth(), panel.getHeight(),
                size.width, size.height);
    }

    /**
     * Paints the remote screen. A scaled copy is kept in video memory ({@link VolatileImage}):
     * each new tile is scaled into it once, and a repaint is a plain copy of the changed area.
     * Scaling the whole screen on every repaint cost ~10 ms of CPU and starved the network thread.
     */
    private static final class CanvasPanel extends JPanel {
        private final DesktopViewer viewer;
        private final Object scaledLock = new Object();
        private VolatileImage scaled;
        /** Image area (in panel pixels) the scaled copy was made for. */
        private Rectangle scaledArea;
        private boolean redrawAll = true;

        private CanvasPanel(DesktopViewer viewer) {
            this.viewer = viewer;
        }

        /** Render thread: scale one tile into the copy, then repaint just that area. */
        void tileDrawn(int x, int y, java.awt.image.BufferedImage tile) {
            Rectangle area;
            Rectangle target;
            synchronized (scaledLock) {
                area = scaledArea;
                if (area == null || scaled == null || redrawAll) {
                    repaint();
                    return;
                }
                Dimension size = viewer.screenSize();
                if (size == null || size.width <= 0) {
                    return;
                }
                target = scaledRect(x, y, tile.getWidth(), tile.getHeight(), size, area);
                if (scaled.validate(getGraphicsConfiguration()) != VolatileImage.IMAGE_OK) {
                    redrawAll = true;
                } else {
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(tile, target.x, target.y, target.width, target.height, null);
                    g.dispose();
                }
            }
            repaint(area.x + target.x, area.y + target.y, target.width, target.height);
        }

        /** Remote screen size or state changed: rebuild the scaled copy on the next paint. */
        void remoteChanged() {
            synchronized (scaledLock) {
                redrawAll = true;
            }
            repaint();
        }

        /** Host rectangle mapped into the scaled copy; neighbouring tiles share edges exactly. */
        private static Rectangle scaledRect(int x, int y, int w, int h, Dimension screen, Rectangle area) {
            int x1 = (int) Math.round((double) x * area.width / screen.width);
            int y1 = (int) Math.round((double) y * area.height / screen.height);
            int x2 = (int) Math.round((double) (x + w) * area.width / screen.width);
            int y2 = (int) Math.round((double) (y + h) * area.height / screen.height);
            return new Rectangle(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            Dimension size = viewer.screenSize();
            if (size != null) {
                Rectangle area = imageArea(getWidth(), getHeight(), size.width, size.height);
                if (area.width > 0 && area.height > 0 && !paintCopy(g, area)) {
                    // No video memory copy available: scale directly (slower).
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    viewer.drawScaled(g, area.x, area.y, area.width, area.height);
                }
            }
            g.dispose();
        }

        private boolean paintCopy(Graphics2D g, Rectangle area) {
            java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc == null) {
                return false;
            }
            synchronized (scaledLock) {
                boolean resized = scaled == null || scaledArea == null
                        || scaled.getWidth() != area.width || scaled.getHeight() != area.height;
                if (resized || scaled.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
                    if (scaled != null) {
                        scaled.flush();
                    }
                    scaled = createVolatileImage(area.width, area.height);
                    if (scaled == null) {
                        return false;
                    }
                    redrawAll = true;
                } else if (scaled.validate(gc) == VolatileImage.IMAGE_RESTORED) {
                    redrawAll = true;
                }
                scaledArea = new Rectangle(area);
                if (redrawAll) {
                    Graphics2D vg = scaled.createGraphics();
                    vg.setColor(java.awt.Color.BLACK);
                    vg.fillRect(0, 0, area.width, area.height);
                    vg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    viewer.drawScaled(vg, 0, 0, area.width, area.height);
                    vg.dispose();
                    redrawAll = false;
                }
                g.drawImage(scaled, area.x, area.y, null);
                if (scaled.contentsLost()) {
                    redrawAll = true;
                    repaint();
                }
            }
            return true;
        }
    }
}
