package com.kartikey.p2pchat.remote;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Detects changed screen tiles and turns them into JPEG tile messages. */
final class TileEncoder {
    record Position(int x, int y) {}

    private final int tileSize;
    private final float quality;
    private BufferedImage previous;

    TileEncoder(int tileSize, float quality) {
        if (tileSize <= 0 || quality < 0 || quality > 1) {
            throw new IllegalArgumentException("bad tile settings");
        }
        this.tileSize = tileSize;
        this.quality = quality;
    }

    List<Position> changedTiles(BufferedImage frame) {
        int columns = columns(frame), rows = rows(frame);
        List<Position> changed = new ArrayList<>();
        boolean all = previous == null || previous.getWidth() != frame.getWidth()
                || previous.getHeight() != frame.getHeight();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                if (all || differs(frame, x, y)) {
                    changed.add(new Position(x, y));
                }
            }
        }
        previous = copy(frame);
        return changed;
    }

    List<Position> allTiles(BufferedImage frame) {
        List<Position> tiles = new ArrayList<>();
        for (int y = 0; y < rows(frame); y++) {
            for (int x = 0; x < columns(frame); x++) {
                tiles.add(new Position(x, y));
            }
        }
        return tiles;
    }

    byte[] encode(BufferedImage frame, Position position) {
        int x = position.x * tileSize, y = position.y * tileSize;
        int width = Math.min(tileSize, frame.getWidth() - x);
        int height = Math.min(tileSize, frame.getHeight() - y);
        BufferedImage tile = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = frame.getRGB(x, y, width, height, null, 0, width);
        tile.setRGB(0, 0, width, height, pixels, 0, width);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                writer.setOutput(output);
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
                writer.write(null, new IIOImage(tile, null, null), params);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode tile", e);
        }
    }

    List<RemoteProtocol.Tile> fragments(byte[] encoded, Position position, int version) {
        int count = Math.max(1, (encoded.length + RemoteProtocol.MAX_TILE_DATA - 1)
                / RemoteProtocol.MAX_TILE_DATA);
        List<RemoteProtocol.Tile> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * RemoteProtocol.MAX_TILE_DATA;
            int end = Math.min(encoded.length, start + RemoteProtocol.MAX_TILE_DATA);
            byte[] chunk = new byte[end - start];
            System.arraycopy(encoded, start, chunk, 0, chunk.length);
            result.add(new RemoteProtocol.Tile(version, position.x, position.y, index, count, chunk));
        }
        return result;
    }

    private boolean differs(BufferedImage frame, int tileX, int tileY) {
        int x = tileX * tileSize, y = tileY * tileSize;
        int width = Math.min(tileSize, frame.getWidth() - x);
        int height = Math.min(tileSize, frame.getHeight() - y);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if (frame.getRGB(x + column, y + row) != previous.getRGB(x + column, y + row)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int columns(BufferedImage image) {
        return (image.getWidth() + tileSize - 1) / tileSize;
    }

    private int rows(BufferedImage image) {
        return (image.getHeight() + tileSize - 1) / tileSize;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        int width = source.getWidth(), height = source.getHeight();
        result.setRGB(0, 0, width, height, source.getRGB(0, 0, width, height, null, 0, width), 0, width);
        return result;
    }
}
