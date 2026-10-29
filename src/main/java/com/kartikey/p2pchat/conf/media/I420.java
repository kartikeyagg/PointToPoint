package com.kartikey.p2pchat.conf.media;

import java.awt.image.BufferedImage;

/** Conversions between {@link VideoFrame} and AWT images, plus scaling for simulcast layers. */
public final class I420 {

    private I420() {}

    /** BT.601 limited-range RGB to I420; odd sizes lose their last row/column. */
    public static VideoFrame fromImage(BufferedImage img, long timestampMicros) {
        int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] out = new byte[VideoFrame.size(w, h)];
        int uOff = w * h, vOff = uOff + (w / 2) * (h / 2);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = rgb[y * w + x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                out[y * w + x] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = rgb[(y + dy) * w + x + dx];
                        r += (p >> 16) & 0xff;
                        g += (p >> 8) & 0xff;
                        b += p & 0xff;
                    }
                }
                r >>= 2;
                g >>= 2;
                b >>= 2;
                int i = (y / 2) * (w / 2) + x / 2;
                out[uOff + i] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                out[vOff + i] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
        return new VideoFrame(w, h, out, timestampMicros);
    }

    public static BufferedImage toImage(VideoFrame f) {
        int w = f.width(), h = f.height(), uOff = f.uOffset(), vOff = f.vOffset();
        byte[] d = f.data();
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = (d[y * w + x] & 0xff) - 16;
                int i = (y / 2) * (w / 2) + x / 2;
                int u = (d[uOff + i] & 0xff) - 128, v = (d[vOff + i] & 0xff) - 128;
                int r = clamp((298 * c + 409 * v + 128) >> 8);
                int g = clamp((298 * c - 100 * u - 208 * v + 128) >> 8);
                int b = clamp((298 * c + 516 * u + 128) >> 8);
                rgb[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, rgb, 0, w);
        return img;
    }

    /** Nearest-neighbour scale; cheap enough for camera-sized frames, and sizes are rounded to even. */
    public static VideoFrame scale(VideoFrame f, int width, int height) {
        int w = width & ~1, h = height & ~1;
        if (w == f.width() && h == f.height()) {
            return f;
        }
        byte[] src = f.data(), out = new byte[VideoFrame.size(w, h)];
        scalePlane(src, 0, f.width(), f.height(), out, 0, w, h);
        scalePlane(src, f.uOffset(), f.width() / 2, f.height() / 2, out, w * h, w / 2, h / 2);
        scalePlane(src, f.vOffset(), f.width() / 2, f.height() / 2, out, w * h + (w / 2) * (h / 2), w / 2, h / 2);
        return new VideoFrame(w, h, out, f.timestampMicros());
    }

    private static void scalePlane(byte[] src, int so, int sw, int sh, byte[] dst, int dOff, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            for (int x = 0; x < dw; x++) {
                dst[dOff + y * dw + x] = src[so + sy * sw + x * sw / dw];
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
