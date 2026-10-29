package com.kartikey.p2pchat.conf.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Paints the latest frame scaled with aspect kept. Frames may arrive on any thread. */
public final class VideoTile extends JComponent {
    private volatile BufferedImage frame;
    private volatile String name = "";
    private volatile boolean speaking;
    private final AtomicBoolean repaintPending = new AtomicBoolean();

    public VideoTile() {
        setOpaque(true);
        setBackground(new Color(24, 24, 28));
    }

    /** Replaces the frame; repaints are coalesced, so a slow EDT just skips frames. */
    public void setFrame(BufferedImage image) {
        frame = image;
        if (repaintPending.compareAndSet(false, true)) {
            Edt.run(this::repaint);
        }
    }

    public BufferedImage frame() {
        return frame;
    }

    public void setName(String n) {
        if (!n.equals(name)) {
            name = n;
            repaint();
        }
    }

    public void setSpeaking(boolean s) {
        if (s != speaking) {
            speaking = s;
            repaint();
        }
    }

    public boolean isSpeaking() {
        return speaking;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        repaintPending.set(false);
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth();
        int h = getHeight();
        g.setColor(getBackground());
        g.fillRect(0, 0, w, h);
        BufferedImage img = frame;
        if (img != null) {
            double s = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
            int dw = Math.max(1, (int) (img.getWidth() * s));
            int dh = Math.max(1, (int) (img.getHeight() * s));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        }
        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        String n = name;
        int tw = fm.stringWidth(n) + 10;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(4, h - fm.getHeight() - 8, tw, fm.getHeight() + 4);
        g.setColor(Color.WHITE);
        g.drawString(n, 9, h - 8 - fm.getDescent() + 2);
        if (speaking) {
            g.setColor(new Color(60, 200, 90));
            g.drawRect(0, 0, w - 1, h - 1);
            g.drawRect(1, 1, w - 3, h - 3);
            g.drawRect(2, 2, w - 5, h - 5);
        }
    }
}
