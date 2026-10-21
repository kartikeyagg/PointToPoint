package com.kartikey.p2pchat.remote;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Always-visible notice that a host screen is being shared. */
public final class HostWindow {

    private final JFrame frame;
    private final JLabel status;
    private final JLabel stats;
    private final Runnable onStop;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private HostWindow(String peerDescription, boolean controlAllowed, Consumer<Boolean> onControlToggle,
                       Runnable onStop) {
        this.onStop = onStop;
        frame = new JFrame("Remote desktop - sharing");
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stop();
            }
        });

        JLabel notice = new JLabel("\u25cf Your screen is visible to " + peerDescription);
        notice.setForeground(new Color(180, 0, 0));
        notice.setFont(notice.getFont().deriveFont(Font.BOLD));
        status = new JLabel("Sharing screen");
        stats = new JLabel("0 tiles, 0 bytes sent");
        JCheckBox control = new JCheckBox("Allow remote control", controlAllowed);
        control.addActionListener(event -> onControlToggle.accept(control.isSelected()));
        JButton stop = new JButton("Stop sharing");
        stop.addActionListener(event -> stop());

        JPanel details = new JPanel(new GridLayout(0, 1, 0, 4));
        details.add(notice);
        details.add(status);
        details.add(stats);
        details.add(control);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(details, BorderLayout.CENTER);
        content.add(stop, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        placeTopRight();
        frame.setVisible(true);
    }

    /** Opens the sharing notice on the Swing event dispatch thread. */
    public static HostWindow open(String peerDescription, boolean controlAllowed,
                                  Consumer<Boolean> onControlToggle, Runnable onStop) {
        if (SwingUtilities.isEventDispatchThread()) {
            return new HostWindow(peerDescription, controlAllowed, onControlToggle, onStop);
        }
        final HostWindow[] result = new HostWindow[1];
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = new HostWindow(peerDescription, controlAllowed,
                    onControlToggle, onStop));
        } catch (Exception e) {
            throw new IllegalStateException("could not open sharing window", e);
        }
        return result[0];
    }

    /** Updates the displayed sharing status. */
    public void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    /** Updates the sent-tile counters. */
    public void setStats(long tilesSent, long bytesSent) {
        SwingUtilities.invokeLater(() -> stats.setText(tilesSent + " tiles, " + formatBytes(bytesSent) + " sent"));
    }

    /** Disposes this window without stopping the sharing service. */
    public void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    private void stop() {
        if (stopped.compareAndSet(false, true)) {
            frame.dispose();
            onStop.run();
        }
    }

    private void placeTopRight() {
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension size = frame.getSize();
        frame.setLocation(bounds.x + bounds.width - size.width - 16, bounds.y + 16);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_000_000) {
            return bytes + " bytes";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }
}
