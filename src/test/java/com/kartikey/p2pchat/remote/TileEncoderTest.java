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
