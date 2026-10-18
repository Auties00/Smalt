package com.github.auties00.smalt.image.jp2.internal;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Collects what the tile-parts of one tile hold: their header parameters, the location of their
 * bodies, and their packed packet headers.
 */
final class TileData {
    /**
     * The parameters of the tile-part headers.
     */
    final HeaderScope scope;

    /**
     * The start and length of each tile-part body, two entries per body, in codestream order.
     */
    private int[] bodies = new int[8];

    /**
     * The number of entries used in {@link #bodies}.
     */
    private int bodyEntries;

    /**
     * The {@code PPT} segments, by {@code Zppt}.
     */
    private TreeMap<Integer, byte[]> ppt;

    /**
     * The packed packet headers taken from the {@code PPM} segments, or {@code null} when the
     * codestream has none.
     */
    private ByteArrayOutputStream ppm;

    /**
     * Constructs an empty tile.
     *
     * @param components the number of image components
     */
    TileData(int components) {
        this.scope = new HeaderScope(components);
    }

    /**
     * Records the body of a tile-part.
     *
     * @param start  the index of the first byte after {@code SOD}
     * @param length the body length
     */
    void addBody(int start, int length) {
        if (bodyEntries == bodies.length) {
            bodies = Arrays.copyOf(bodies, bodies.length * 2);
        }
        bodies[bodyEntries++] = start;
        bodies[bodyEntries++] = length;
    }

    /**
     * Records a {@code PPT} segment.
     *
     * @param index the {@code Zppt} index
     * @param data  the packed packet headers
     */
    void addPpt(int index, byte[] data) {
        if (ppt == null) {
            ppt = new TreeMap<>();
        }
        ppt.merge(index, data, (previous, next) -> {
            var joined = Arrays.copyOf(previous, previous.length + next.length);
            System.arraycopy(next, 0, joined, previous.length, next.length);
            return joined;
        });
    }

    /**
     * Records the {@code PPM} packet headers of a tile-part.
     *
     * @param data the packed packet headers
     */
    void addPpm(byte[] data) {
        if (ppm == null) {
            ppm = new ByteArrayOutputStream();
        }
        ppm.writeBytes(data);
    }

    /**
     * Joins the tile-part bodies into one stream.
     *
     * @param codestream the codestream bytes
     * @return the concatenated bodies
     */
    byte[] body(byte[] codestream) {
        var total = 0L;
        for (var i = 1; i < bodyEntries; i += 2) {
            total += bodies[i];
        }
        var joined = new byte[(int) total];
        var position = 0;
        for (var i = 0; i < bodyEntries; i += 2) {
            System.arraycopy(codestream, bodies[i], joined, position, bodies[i + 1]);
            position += bodies[i + 1];
        }
        return joined;
    }

    /**
     * Returns the total length of the tile-part bodies.
     *
     * @return the length in bytes
     */
    long bodyLength() {
        var total = 0L;
        for (var i = 1; i < bodyEntries; i += 2) {
            total += bodies[i];
        }
        return total;
    }

    /**
     * Returns the packed packet headers of the tile.
     *
     * @return the headers from the {@code PPM} or {@code PPT} segments, or {@code null} when the
     *         packet headers are in the bodies
     */
    byte[] packedHeaders() {
        if (ppm != null) {
            return ppm.toByteArray();
        }
        if (ppt != null) {
            var joined = new ByteArrayOutputStream();
            for (var segment : ppt.values()) {
                joined.writeBytes(segment);
            }
            return joined.toByteArray();
        }
        return null;
    }
}
