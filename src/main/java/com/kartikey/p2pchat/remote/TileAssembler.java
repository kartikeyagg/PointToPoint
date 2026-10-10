package com.kartikey.p2pchat.remote;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reassembles bounded, short-lived tile fragment sets. */
final class TileAssembler {

    private static final int MAX_ASSEMBLIES = 2048;
    private static final long MAX_AGE_NANOS = 2_000_000_000L;

    private final Map<Key, Assembly> assemblies = new LinkedHashMap<>();
    private final Map<Coordinate, Integer> newestPartial = new LinkedHashMap<>();

    /** Adds a fragment, returning its joined bytes when the set is complete. */
    byte[] add(RemoteProtocol.Tile tile) {
        long now = System.nanoTime();
        discardExpired(now);
        Coordinate coordinate = new Coordinate(tile.tileX(), tile.tileY());
        Integer newest = newestPartial.get(coordinate);
        if (newest != null && tile.version() < newest) {
            return null;
        }
        if (newest == null || tile.version() > newest) {
            removeCoordinate(coordinate);
            newestPartial.put(coordinate, tile.version());
        }

        Key key = new Key(tile.tileX(), tile.tileY(), tile.version());
        Assembly assembly = assemblies.get(key);
        if (assembly == null) {
            trimToCapacity();
            assembly = new Assembly(tile.fragCount(), now);
            assemblies.put(key, assembly);
        } else if (assembly.fragmentCount != tile.fragCount()) {
            return null;
        }
        if (assembly.fragments[tile.fragIndex()] != null) {
            return null;
        }
        assembly.fragments[tile.fragIndex()] = Arrays.copyOf(tile.data(), tile.data().length);
        assembly.received++;
        if (assembly.received != assembly.fragmentCount) {
            return null;
        }
        assemblies.remove(key);
        newestPartial.remove(coordinate);
        return assembly.join();
    }

    void clear() {
        assemblies.clear();
        newestPartial.clear();
    }

    private void discardExpired(long now) {
        Iterator<Map.Entry<Key, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Assembly> entry = iterator.next();
            if (now - entry.getValue().createdNanos > MAX_AGE_NANOS) {
                iterator.remove();
                newestPartial.remove(new Coordinate(entry.getKey().tileX, entry.getKey().tileY));
            }
        }
    }

    private void trimToCapacity() {
        while (assemblies.size() >= MAX_ASSEMBLIES) {
            Iterator<Map.Entry<Key, Assembly>> iterator = assemblies.entrySet().iterator();
            Map.Entry<Key, Assembly> oldest = iterator.next();
            iterator.remove();
            newestPartial.remove(new Coordinate(oldest.getKey().tileX, oldest.getKey().tileY));
        }
    }

    private void removeCoordinate(Coordinate coordinate) {
        Iterator<Map.Entry<Key, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Assembly> entry = iterator.next();
            if (entry.getKey().tileX == coordinate.tileX && entry.getKey().tileY == coordinate.tileY) {
                iterator.remove();
            }
        }
    }

    private record Key(int tileX, int tileY, int version) {}

    private record Coordinate(int tileX, int tileY) {}

    private static final class Assembly {
        private final int fragmentCount;
        private final long createdNanos;
        private final byte[][] fragments;
        private int received;

        private Assembly(int fragmentCount, long createdNanos) {
            this.fragmentCount = fragmentCount;
            this.createdNanos = createdNanos;
            this.fragments = new byte[fragmentCount][];
        }

        private byte[] join() {
            int length = 0;
            for (byte[] fragment : fragments) {
                length += fragment.length;
            }
            byte[] joined = new byte[length];
            int offset = 0;
            for (byte[] fragment : fragments) {
                System.arraycopy(fragment, 0, joined, offset, fragment.length);
                offset += fragment.length;
            }
            return joined;
        }
    }
}
