package com.kartikey.p2pchat.conf.media;

/**
 * One raw picture in I420 (YUV 4:2:0 planar): the Y plane ({@code width*height} bytes), then U and
 * V ({@code (width/2)*(height/2)} bytes each). Width and height are even. This is what cameras
 * deliver and VP8 consumes, so frames move between capture, encoder and decoder without converting.
 */
public record VideoFrame(int width, int height, byte[] data, long timestampMicros) {

    public VideoFrame {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("frame size must be positive and even: " + width + "x" + height);
        }
        if (data.length != size(width, height)) {
            throw new IllegalArgumentException("I420 buffer is " + data.length + " bytes, expected " + size(width, height));
        }
    }

    public static int size(int width, int height) {
        return width * height * 3 / 2;
    }

    public int uOffset() {
        return width * height;
    }

    public int vOffset() {
        return width * height + (width / 2) * (height / 2);
    }
}
