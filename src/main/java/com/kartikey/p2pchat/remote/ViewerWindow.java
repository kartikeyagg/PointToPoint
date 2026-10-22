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

    private ViewerWindow(DesktopViewer viewer, String title, Runnable onClose) {
        this.onClose = onClose;
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        JPanel canvas = new CanvasPanel(viewer);
        canvas.setFocusable(true);
        canvas.setFocusTraversalKeysEnabled(false);
        installInput(canvas, viewer);
        viewer.addListener(new DesktopViewer.Listener() {
            @Override
            public void frameUpdated() {
                canvas.repaint();
            }

            @Override
            public void stateChanged() {
                canvas.repaint();
            }
        });

        status = new JLabel();
        JLabel control = new JLabel("", SwingConstants.RIGHT);
        JButton refresh = new JButton("Refresh");
        refresh.setFocusable(false);
        refresh.addActionListener(e -> viewer.requestRefresh());
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(control, BorderLayout.EAST);
        bottom.add(refresh, BorderLayout.WEST);
        viewer.addListener(new DesktopViewer.Listener() {
            @Override
            public void stateChanged() {
                SwingUtilities.invokeLater(() -> control.setText(viewer.controlAllowed() ? "" : "View only"));
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

    private static void installInput(JPanel panel, DesktopViewer viewer) {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                panel.requestFocusInWindow();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                panel.requestFocusInWindow();
                sendButton(event, viewer, panel);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                sendButton(event, viewer, panel);
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                sendMove(event, viewer, panel);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                sendMove(event, viewer, panel);
            }
        });
        MouseWheelListener wheel = event -> {
            java.awt.Point point = remotePoint(event, viewer, panel);
            if (point != null) {
                viewer.mouseWheel(point.x, point.y, event.getWheelRotation());
            }
        };
        panel.addMouseWheelListener(wheel);
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                sendKey(event, viewer, true);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                sendKey(event, viewer, false);
            }
        });
    }

    private static void sendMove(MouseEvent event, DesktopViewer viewer, JPanel panel) {
        java.awt.Point point = remotePoint(event, viewer, panel);
        if (point != null) {
            viewer.mouseMove(point.x, point.y);
        }
    }

    private static void sendButton(MouseEvent event, DesktopViewer viewer, JPanel panel) {
        int button = SwingUtilities.isLeftMouseButton(event) ? RemoteProtocol.BUTTON_LEFT
                : SwingUtilities.isMiddleMouseButton(event) ? RemoteProtocol.BUTTON_MIDDLE
                : SwingUtilities.isRightMouseButton(event) ? RemoteProtocol.BUTTON_RIGHT : 0;
        java.awt.Point point = remotePoint(event, viewer, panel);
        if (button != 0 && point != null) {
            viewer.mouseButton(point.x, point.y, button, event.getID() == MouseEvent.MOUSE_PRESSED);
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

    private static final class CanvasPanel extends JPanel {
        private final DesktopViewer viewer;

        private CanvasPanel(DesktopViewer viewer) {
            this.viewer = viewer;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Dimension size = viewer.screenSize();
            if (size != null) {
                Rectangle area = imageArea(getWidth(), getHeight(), size.width, size.height);
                viewer.drawScaled(g, area.x, area.y, area.width, area.height);
            }
            g.dispose();
        }
    }
}
