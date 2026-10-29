package com.kartikey.p2pchat.conf.media.device;

import com.kartikey.p2pchat.conf.media.VideoFrame;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Webcam via FFmpeg's platform input (avfoundation / v4l2 / dshow) on its own daemon thread. YUV420P
 * is requested so frames go straight to the VP8 encoder; if the device refuses it, the grabber is
 * reopened with BGR24 and converted here.
 */
public final class CameraCapture implements AutoCloseable {

    private final String device;
    private final int width, height, fps;
    private final Consumer<VideoFrame> onFrame;
    private volatile boolean closed;
    private Thread thread;
    private FFmpegFrameGrabber grabber;

    public CameraCapture(String device, int width, int height, int fps, Consumer<VideoFrame> onFrame) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.onFrame = onFrame;
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    }

    public static String inputFormat() {
        String os = os();
        return os.contains("mac") ? "avfoundation" : os.contains("win") ? "dshow" : "v4l2";
    }

    public static String defaultDevice() {
        String os = os();
        return os.contains("mac") ? "0" : os.contains("win") ? "video=Integrated Camera" : "/dev/video0";
    }

    private FFmpegFrameGrabber open(int pixelFormat) throws Exception {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(device);
        g.setFormat(inputFormat());
        g.setImageWidth(width);
        g.setImageHeight(height);
        g.setFrameRate(fps);
        g.setPixelFormat(pixelFormat);
        g.setOption("framerate", Integer.toString(fps));
        g.setOption("video_size", width + "x" + height);
        g.start();
        return g;
    }

    /** Opens the device (may trigger the OS camera permission prompt) and starts delivering frames. */
    public synchronized void start() throws Exception {
        if (thread != null || closed) {
            return;
        }
        boolean yuv = true;
        try {
            grabber = open(avutil.AV_PIX_FMT_YUV420P);
        } catch (Exception e) {
            yuv = false;
            grabber = open(avutil.AV_PIX_FMT_BGR24);
        }
        boolean planar = yuv;
        thread = new Thread(() -> run(planar), "camera-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(boolean planar) {
        FFmpegFrameGrabber g = grabber;
        try {
            while (!closed) {
                Frame f = g.grab();
                if (f == null) {
                    break;
                }
                if (f.image == null || f.image.length == 0 || !(f.image[0] instanceof ByteBuffer b0)) {
                    continue;
                }
                int w = f.imageWidth & ~1, h = f.imageHeight & ~1;
                byte[] data;
                if (planar) {
                    int sy = f.imageStride;
                    if (f.image.length >= 3 && f.image[1] instanceof ByteBuffer b1 && f.image[2] instanceof ByteBuffer b2) {
                        data = copyPlanes(b0, sy, b1, (sy + 1) / 2, b2, (sy + 1) / 2, w, h);
                    } else {
                        data = copyContiguous(b0, sy, w, h);
                    }
                } else {
                    data = bgrToI420(b0, f.imageStride, w, h);
                }
                if (!closed) {
                    onFrame.accept(new VideoFrame(w, h, data, System.nanoTime() / 1000));
                }
            }
        } catch (Exception | Error e) {
            // device unplugged or closed underneath us: stop delivering
        }
    }

    /** Copies three strided planes into a tightly packed I420 array. */
    public static byte[] copyPlanes(ByteBuffer y, int strideY, ByteBuffer u, int strideU,
                                    ByteBuffer v, int strideV, int w, int h) {
        byte[] out = new byte[VideoFrame.size(w, h)];
        int cw = w / 2, ch = h / 2;
        copyPlane(y, strideY, out, 0, w, h);
        copyPlane(u, strideU, out, w * h, cw, ch);
        copyPlane(v, strideV, out, w * h + cw * ch, cw, ch);
        return out;
    }

    private static void copyPlane(ByteBuffer src, int stride, byte[] dst, int dstOff, int w, int h) {
        ByteBuffer s = src.duplicate();
        for (int row = 0; row < h; row++) {
            s.position(row * stride);
            s.get(dst, dstOff + row * w, w);
        }
    }

    /** One buffer holding Y, U, V back to back (what javacv produces for planar targets). */
    static byte[] copyContiguous(ByteBuffer b, int strideY, int w, int h) {
        int strideC = (strideY + 1) / 2;
        ByteBuffer u = b.duplicate().position(strideY * h).slice();
        ByteBuffer v = b.duplicate().position(strideY * h + strideC * (h / 2)).slice();
        return copyPlanes(b, strideY, u, strideC, v, strideC, w, h);
    }

    /** Fallback path for devices that only deliver packed BGR; BT.601 limited range like {@code I420.fromImage}. */
    static byte[] bgrToI420(ByteBuffer b, int stride, int w, int h) {
        byte[] out = new byte[VideoFrame.size(w, h)];
        int uOff = w * h, vOff = uOff + (w / 2) * (h / 2);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = y * stride + x * 3;
                int bl = b.get(p) & 0xff, g = b.get(p + 1) & 0xff, r = b.get(p + 2) & 0xff;
                out[y * w + x] = (byte) (((66 * r + 129 * g + 25 * bl + 128) >> 8) + 16);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int i = (y / 2) * (w / 2) + x / 2;
                    out[uOff + i] = (byte) (((-38 * r - 74 * g + 112 * bl + 128) >> 8) + 128);
                    out[vOff + i] = (byte) (((112 * r - 94 * g - 18 * bl + 128) >> 8) + 128);
                }
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception e) {
                // already stopped
            }
        }
    }
}
