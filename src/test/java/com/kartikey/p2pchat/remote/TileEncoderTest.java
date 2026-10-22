package com.kartikey.p2pchat.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TileEncoderTest {
    @Test
    void detectsPixelChangesAndCropsEdgeTiles() throws Exception {
        TileEncoder encoder = new TileEncoder(64, .7f);
        BufferedImage image = image(100, 70);
        assertEquals(4, encoder.changedTiles(image).size());
        assertTrue(encoder.changedTiles(image).isEmpty());
        image.setRGB(99, 69, 0x00ff00);
        assertEquals(List.of(new TileEncoder.Position(1, 1)), encoder.changedTiles(image));
        assertEquals(36, ImageIO.read(new java.io.ByteArrayInputStream(
                encoder.encode(image, new TileEncoder.Position(1, 1)))).getWidth());
        assertEquals(6, ImageIO.read(new java.io.ByteArrayInputStream(
                encoder.encode(image, new TileEncoder.Position(1, 1)))).getHeight());
    }

    @Test
    void parallelEncodingMatchesOneByOneInOrder() {
        TileEncoder encoder = new TileEncoder(64, .7f);
        BufferedImage noisy = image(300, 200);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 300; x++) {
                noisy.setRGB(x, y, (x * 7919 + y * 104729) & 0x00ffffff);
            }
        }
        List<TileEncoder.Position> all = encoder.allTiles(noisy);
        List<byte[]> together = encoder.encodeAll(noisy, all);
        assertEquals(all.size(), together.size());
        for (int i = 0; i < all.size(); i++) {
            assertTrue(java.util.Arrays.equals(encoder.encode(noisy, all.get(i)), together.get(i)), "tile " + i);
        }
        encoder.close();
    }

    @Test
    void detectsChangesInImagesThatAreNotIntRgb() {
        TileEncoder encoder = new TileEncoder(128, .7f);
        BufferedImage bgr = new BufferedImage(300, 130, BufferedImage.TYPE_3BYTE_BGR);
        assertEquals(6, encoder.changedTiles(bgr).size());
        assertTrue(encoder.changedTiles(bgr).isEmpty());
        bgr.setRGB(260, 129, 0x123456);
        assertEquals(List.of(new TileEncoder.Position(2, 1)), encoder.changedTiles(bgr));
    }

    @Test
    void splitsLargeJpegIntoDatagramSizedFragments() {
        TileEncoder encoder = new TileEncoder(256, 1f);
        BufferedImage noisy = image(256, 256);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                noisy.setRGB(x, y, (x * 1103515245 + y * 12345) & 0x00ffffff);
            }
        }
        List<RemoteProtocol.Tile> pieces = encoder.fragments(encoder.encode(noisy, new TileEncoder.Position(0, 0)),
                new TileEncoder.Position(0, 0), 8);
        assertTrue(pieces.size() > 1);
        assertTrue(pieces.stream().allMatch(tile -> tile.data().length <= RemoteProtocol.MAX_TILE_DATA));
        assertTrue(pieces.stream().allMatch(tile -> tile.version() == 8 && tile.fragCount() == pieces.size()));
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
