package com.kartikey.p2pchat.remote;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Detects changed screen tiles and turns them into JPEG tile messages. */
final class TileEncoder implements AutoCloseable {
    record Position(int x, int y) {}

    private static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));

    private final int tileSize;
    private final float quality;
    /** Looking up a JPEG writer costs more than encoding a small tile, so each thread keeps one. */
    private final ThreadLocal<ImageWriter> writers = ThreadLocal.withInitial(
            () -> ImageIO.getImageWritersByFormatName("jpeg").next());
    private ExecutorService pool;
    private int[] previous;
    private int previousWidth;
    private int previousHeight;

    TileEncoder(int tileSize, float quality) {
        if (tileSize <= 0 || quality < 0 || quality > 1) {
            throw new IllegalArgumentException("bad tile settings");
        }
        this.tileSize = tileSize;
        this.quality = quality;
    }

    List<Position> changedTiles(BufferedImage frame) {
        int width = frame.getWidth(), height = frame.getHeight();
        int[] pixels = pixels(frame);
        int columns = columns(frame), rows = rows(frame);
        List<Position> changed = new ArrayList<>();
        boolean all = previous == null || previousWidth != width || previousHeight != height;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                if (all || differs(pixels, width, height, x, y)) {
                    changed.add(new Position(x, y));
                }
            }
        }
        if (previous == null || previous.length != pixels.length) {
            previous = new int[pixels.length];
        }
        System.arraycopy(pixels, 0, previous, 0, pixels.length);
        previousWidth = width;
        previousHeight = height;
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
        return encode(frame, pixels(frame), position);
    }

    /** Encodes several tiles on all spare cores; results are in the order of {@code positions}. */
    List<byte[]> encodeAll(BufferedImage frame, List<Position> positions) {
        int[] pixels = pixels(frame);
        if (positions.size() <= 1 || THREADS == 1) {
            List<byte[]> result = new ArrayList<>(positions.size());
            for (Position position : positions) {
                result.add(encode(frame, pixels, position));
            }
            return result;
        }
        List<Future<byte[]>> futures = new ArrayList<>(positions.size());
        ExecutorService executor = pool();
        for (Position position : positions) {
            futures.add(executor.submit(() -> encode(frame, pixels, position)));
        }
        List<byte[]> result = new ArrayList<>(positions.size());
        try {
            for (Future<byte[]> future : futures) {
                result.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tile encoding interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("cannot encode tile", e.getCause());
        }
        return result;
    }

    @Override
    public synchronized void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(THREADS, task -> {
                Thread thread = new Thread(task, "remote-encode");
                thread.setDaemon(true);
                return thread;
            });
        }
        return pool;
    }

    private byte[] encode(BufferedImage frame, int[] pixels, Position position) {
        int frameWidth = frame.getWidth();
        int x = position.x * tileSize, y = position.y * tileSize;
        int width = Math.min(tileSize, frameWidth - x);
        int height = Math.min(tileSize, frame.getHeight() - y);
        BufferedImage tile = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] target = ((DataBufferInt) tile.getRaster().getDataBuffer()).getData();
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, (y + row) * frameWidth + x, target, row * width, width);
        }
        ImageWriter writer = writers.get();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(tile, null, null), params);
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode tile", e);
        } finally {
            writer.setOutput(null);
        }
        return bytes.toByteArray();
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

    /** The frame's RGB pixels, row by row: the backing array when possible, otherwise a copy. */
    static int[] pixels(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB
                && image.getRaster().getDataBuffer() instanceof DataBufferInt buffer
                && buffer.getNumBanks() == 1
                && image.getRaster().getParent() == null
                && buffer.getData().length == image.getWidth() * image.getHeight()) {
            return buffer.getData();
        }
        int width = image.getWidth(), height = image.getHeight();
        int[] copy = image.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0; i < copy.length; i++) {
            copy[i] &= 0xffffff;
        }
        return copy;
    }

    private boolean differs(int[] pixels, int width, int height, int tileX, int tileY) {
        int x = tileX * tileSize, y = tileY * tileSize;
        int tileWidth = Math.min(tileSize, width - x);
        int tileHeight = Math.min(tileSize, height - y);
        for (int row = 0; row < tileHeight; row++) {
            int start = (y + row) * width + x;
            if (!java.util.Arrays.equals(pixels, start, start + tileWidth, previous, start, start + tileWidth)) {
                return true;
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
}
