package com.kartikey.p2pchat.conf.media.device;

import static org.junit.jupiter.api.Assertions.*;

import com.kartikey.p2pchat.conf.media.MediaFormat;
import com.kartikey.p2pchat.conf.media.VideoFrame;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DeviceTest {

    @Test
    void chunkerAssemblesLittleEndianFramesFromOddReads() {
        List<short[]> frames = new ArrayList<>();
        Chunker c = new Chunker(frames::add);
        int n = MediaFormat.FRAME_SAMPLES * 2 + 5;
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            pcm[2 * i] = (byte) i;
            pcm[2 * i + 1] = (byte) (i >> 8);
        }
        for (int off = 0; off < pcm.length; ) {
            int len = Math.min(777, pcm.length - off);
            c.accept(pcm, off, len);
            off += len;
        }
        assertEquals(2, frames.size());
        assertEquals(MediaFormat.FRAME_SAMPLES, frames.get(1).length);
        assertEquals((short) 900, frames.get(0)[900]);
        assertEquals((short) (MediaFormat.FRAME_SAMPLES + 3), frames.get(1)[3]);
    }

    @Test
    void chunkerIsLittleEndian() {
        short[][] got = new short[1][];
        Chunker c = new Chunker(f -> got[0] = f);
        byte[] b = new byte[MediaFormat.FRAME_SAMPLES * 2];
        b[0] = 0x02;
        b[1] = 0x01;
        c.accept(b, 0, b.length);
        assertEquals(0x0102, got[0][0]);
    }

    @Test
    void copyPlanesHonoursStride() {
        int w = 4, h = 4, sy = 8, sc = 6;
        ByteBuffer y = ByteBuffer.allocate(sy * h), u = ByteBuffer.allocate(sc * 2), v = ByteBuffer.allocate(sc * 2);
        Arrays.fill(y.array(), (byte) 99);
        Arrays.fill(u.array(), (byte) 99);
        Arrays.fill(v.array(), (byte) 99);
        for (int r = 0; r < h; r++) for (int x = 0; x < w; x++) y.put(r * sy + x, (byte) (r * 10 + x));
        for (int r = 0; r < 2; r++) for (int x = 0; x < 2; x++) {
            u.put(r * sc + x, (byte) (100 + r * 2 + x));
            v.put(r * sc + x, (byte) (120 + r * 2 + x));
        }
        byte[] out = CameraCapture.copyPlanes(y, sy, u, sc, v, sc, w, h);
        assertEquals(24, out.length);
        for (int r = 0; r < h; r++) for (int x = 0; x < w; x++) assertEquals((byte) (r * 10 + x), out[r * w + x]);
        for (int i = 0; i < 4; i++) {
            assertEquals((byte) (100 + i), out[16 + i]);
            assertEquals((byte) (120 + i), out[20 + i]);
        }
    }

    @Test
    void screenCaptureRateSizeAndClose() throws Exception {
        List<VideoFrame> got = new CopyOnWriteArrayList<>();
        ScreenCapture cap = new ScreenCapture(() -> new BufferedImage(1001, 563, BufferedImage.TYPE_INT_RGB), 20, 320, got::add);
        cap.start();
        Thread.sleep(1000);
        cap.close();
        cap.close();
        int n = got.size();
        assertTrue(n >= 8 && n <= 25, "frames: " + n);
        for (VideoFrame f : got) {
            assertTrue(f.width() <= 320 && f.width() % 2 == 0 && f.height() % 2 == 0);
        }
        Thread.sleep(200);
        assertEquals(n, got.size());
    }

    @Test
    void osDefaultsAreSet() {
        assertFalse(CameraCapture.inputFormat().isEmpty());
        assertFalse(CameraCapture.defaultDevice().isEmpty());
        assertNotNull(Devices.listAudio());
    }

    @Test
    void speakerBytesAreLittleEndian() {
        assertArrayEquals(new byte[] {0x02, 0x01, (byte) 0xff, (byte) 0xff},
                SpeakerPlayback.toBytes(new short[] {0x0102, -1}));
    }
}
