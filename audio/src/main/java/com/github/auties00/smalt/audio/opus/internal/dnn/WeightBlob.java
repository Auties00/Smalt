/* Ports libopus 1.5.2 (dnn/parse_lpcnet_weights.c, dnn/write_lpcnet_weights.c). Copyright Amazon;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A parsed weight blob: the named arrays of one or more models, in the record format of
 * {@code write_lpcnet_weights.c}.
 *
 * {@snippet :
 * struct WeightHead {        // 64 bytes, little-endian
 *     char head[4];          // "DNNw"
 *     int version;           // 0
 *     int type;              // 0 float, 1 int, 3 int8
 *     int size;              // payload size in bytes
 *     int block_size;        // size rounded up to a multiple of 64
 *     char name[44];         // zero terminated
 * };                         // followed by block_size bytes, the payload first
 * }
 *
 * <p>Instances are immutable and thread-safe.
 */
final class WeightBlob {
    /**
     * The array type of 32-bit floats ({@code WEIGHT_TYPE_float}).
     */
    static final int TYPE_FLOAT = 0;

    /**
     * The array type of 32-bit integers ({@code WEIGHT_TYPE_int}).
     */
    static final int TYPE_INT = 1;

    /**
     * The array type of signed bytes ({@code WEIGHT_TYPE_int8}).
     */
    static final int TYPE_INT8 = 3;

    /**
     * The size of a record header and the alignment of record payloads ({@code WEIGHT_BLOCK_SIZE}).
     */
    private static final int BLOCK_SIZE = 64;

    /**
     * The blob bytes.
     */
    private final ByteBuffer data;

    /**
     * The payload offset and size in bytes of each array, by name.
     */
    private final Map<String, int[]> arrays = new HashMap<>();

    /**
     * Parses a blob ({@code parse_weights}).
     *
     * @param bytes the blob
     * @throws IllegalStateException if a record is truncated or malformed
     */
    WeightBlob(byte[] bytes) {
        this.data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var pos = 0;
        while (pos < bytes.length) {
            if (bytes.length - pos < BLOCK_SIZE || bytes[pos] != 'D' || bytes[pos + 1] != 'N' || bytes[pos + 2] != 'N'
                    || bytes[pos + 3] != 'w') {
                throw new IllegalStateException("malformed DNN weight record at " + pos);
            }
            var type = data.getInt(pos + 8);
            var size = data.getInt(pos + 12);
            var blockSize = data.getInt(pos + 16);
            if (size < 0 || blockSize < size || blockSize > bytes.length - pos - BLOCK_SIZE || bytes[pos + 63] != 0) {
                throw new IllegalStateException("malformed DNN weight record at " + pos);
            }
            var end = pos + 20;
            while (bytes[end] != 0) {
                end++;
            }
            var name = new String(bytes, pos + 20, end - pos - 20, StandardCharsets.US_ASCII);
            arrays.putIfAbsent(name, new int[]{pos + BLOCK_SIZE, size, type});
            pos += BLOCK_SIZE + blockSize;
        }
    }

    /**
     * Reads a gzipped blob from a resource of this package.
     *
     * @param resource the resource name
     * @return the parsed blob
     * @throws IllegalStateException if the resource is missing or malformed
     */
    static WeightBlob load(String resource) {
        try (InputStream raw = WeightBlob.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("missing Opus DNN model " + resource);
            }
            try (var in = new GZIPInputStream(raw, 1 << 16)) {
                return new WeightBlob(in.readAllBytes());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read Opus DNN model " + resource, exception);
        }
    }

    /**
     * Returns the record of an array whose payload has the given size, as {@code find_array_check} does.
     *
     * @param name the array name
     * @param bytes the expected payload size in bytes
     * @return the payload offset, size and type, or {@code null} if the array is missing or has another size
     */
    private int[] find(String name, int bytes) {
        var record = arrays.get(name);
        return record != null && record[1] == bytes ? record : null;
    }

    /**
     * {@return whether the blob holds an array of this name}
     *
     * @param name the array name
     */
    boolean contains(String name) {
        return arrays.containsKey(name);
    }

    /**
     * Returns a required float array.
     *
     * @param name the array name
     * @param count the number of elements
     * @return the elements
     * @throws IllegalStateException if the array is missing or has another size
     */
    float[] floats(String name, int count) {
        var record = find(name, 4 * count);
        if (record == null) {
            throw new IllegalStateException("missing or mis-sized DNN weight array " + name);
        }
        return toFloats(record, count);
    }

    /**
     * Returns an optional float array ({@code opt_array_check}).
     *
     * @param name the array name
     * @param count the number of elements
     * @return the elements, or {@code null} if the blob has no such array
     * @throws IllegalStateException if the array has another size
     */
    float[] optionalFloats(String name, int count) {
        var record = arrays.get(name);
        if (record == null) {
            return null;
        }
        if (record[1] != 4 * count) {
            throw new IllegalStateException("mis-sized DNN weight array " + name);
        }
        return toFloats(record, count);
    }

    /**
     * Returns a required signed byte array.
     *
     * @param name the array name
     * @param count the number of elements
     * @return the elements
     * @throws IllegalStateException if the array is missing or has another size
     */
    byte[] bytes(String name, int count) {
        var record = find(name, count);
        if (record == null) {
            throw new IllegalStateException("missing or mis-sized DNN weight array " + name);
        }
        var out = new byte[count];
        data.get(record[0], out);
        return out;
    }

    /**
     * Returns an integer array whose size is not known in advance.
     *
     * @param name the array name
     * @return the elements, or {@code null} if the blob has no such array
     */
    int[] ints(String name) {
        var record = arrays.get(name);
        if (record == null) {
            return null;
        }
        var out = new int[record[1] / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = data.getInt(record[0] + 4 * i);
        }
        return out;
    }

    /**
     * Converts the payload of a float record.
     */
    private float[] toFloats(int[] record, int count) {
        var out = new float[count];
        for (var i = 0; i < count; i++) {
            out[i] = data.getFloat(record[0] + 4 * i);
        }
        return out;
    }
}
