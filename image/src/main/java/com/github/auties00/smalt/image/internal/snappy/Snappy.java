package com.github.auties00.smalt.image.internal.snappy;

import com.github.auties00.smalt.MediaException;

/**
 * Decompresses and compresses the raw Snappy block format (the varint uncompressed length followed by literal and
 * copy elements), as Google's {@code format_description.txt} defines it.
 *
 * <p>Instances hold the hash table of the compressor and are not thread-safe; decompression is static.
 */
public final class Snappy {
    /**
     * The number of bits of the compressor's hash.
     */
    private static final int HASH_BITS = 14;

    /**
     * The largest number of bytes one compressed block covers, so that every match offset fits two bytes.
     */
    private static final int BLOCK = 1 << 16;

    /**
     * The shortest match the compressor codes.
     */
    private static final int MIN_MATCH = 4;

    /**
     * The positions of the last 4-byte sequences seen by hash, relative to the current block, plus one.
     */
    private final int[] table = new int[1 << HASH_BITS];

    /**
     * Constructs a compressor.
     */
    public Snappy() {
    }

    /**
     * Returns the uncompressed length a compressed block declares.
     *
     * @param data the array holding the block
     * @param offset the index of its first byte
     * @param length the number of bytes of the block
     * @return the length
     * @throws MediaException.Malformed if the length varint is truncated or exceeds 2^31 - 1
     */
    public static int uncompressedLength(byte[] data, int offset, int length) {
        var result = 0L;
        for (var index = 0; index < 5; index++) {
            if (index >= length) {
                throw new MediaException.Malformed("the Snappy length is truncated");
            }
            var value = data[offset + index] & 0xFF;
            result |= (long) (value & 0x7F) << (7 * index);
            if ((value & 0x80) == 0) {
                if (result > Integer.MAX_VALUE) {
                    break;
                }
                return (int) result;
            }
        }
        throw new MediaException.Malformed("the Snappy length is out of range");
    }

    /**
     * Decompresses a block into part of an array.
     *
     * @param data the array holding the block
     * @param offset the index of its first byte
     * @param length the number of bytes of the block
     * @param out the destination
     * @param outOffset the index of the first byte written
     * @param outLength the number of bytes the destination has room for, at least the declared length
     * @return the number of bytes written, the declared uncompressed length
     * @throws MediaException.Malformed if the block is truncated, a copy reaches before the start of the output, or
     * the elements do not produce exactly the declared length within {@code outLength}
     */
    public static int decompress(byte[] data, int offset, int length, byte[] out, int outOffset, int outLength) {
        var expected = uncompressedLength(data, offset, length);
        if (expected > outLength) {
            throw new MediaException.Malformed("the Snappy block expands to " + expected + " bytes, more than the "
                    + outLength + " expected");
        }
        var in = offset;
        while ((data[in] & 0x80) != 0) {
            in++;
        }
        in++;
        var end = offset + length;
        var at = outOffset;
        var outEnd = outOffset + expected;
        while (in < end) {
            var tag = data[in++] & 0xFF;
            switch (tag & 3) {
                case 0 -> {
                    var size = tag >>> 2;
                    if (size >= 60) {
                        var bytes = size - 59;
                        if (end - in < bytes) {
                            throw new MediaException.Malformed("a Snappy literal length is truncated");
                        }
                        var value = 0L;
                        for (var index = 0; index < bytes; index++) {
                            value |= (long) (data[in++] & 0xFF) << (8 * index);
                        }
                        if (value >= Integer.MAX_VALUE) {
                            throw new MediaException.Malformed("a Snappy literal is too long");
                        }
                        size = (int) value;
                    }
                    size++;
                    if (end - in < size || outEnd - at < size) {
                        throw new MediaException.Malformed("a Snappy literal overruns its block");
                    }
                    System.arraycopy(data, in, out, at, size);
                    in += size;
                    at += size;
                }
                case 1 -> {
                    if (in >= end) {
                        throw new MediaException.Malformed("a Snappy copy is truncated");
                    }
                    var size = 4 + ((tag >>> 2) & 7);
                    var distance = (tag >>> 5) << 8 | data[in++] & 0xFF;
                    at = copy(out, outOffset, at, outEnd, distance, size);
                }
                case 2 -> {
                    if (end - in < 2) {
                        throw new MediaException.Malformed("a Snappy copy is truncated");
                    }
                    var distance = data[in] & 0xFF | (data[in + 1] & 0xFF) << 8;
                    in += 2;
                    at = copy(out, outOffset, at, outEnd, distance, (tag >>> 2) + 1);
                }
                default -> {
                    if (end - in < 4) {
                        throw new MediaException.Malformed("a Snappy copy is truncated");
                    }
                    var distance = (long) (data[in] & 0xFF) | (data[in + 1] & 0xFF) << 8
                            | (data[in + 2] & 0xFF) << 16 | (long) (data[in + 3] & 0xFF) << 24;
                    in += 4;
                    if (distance > Integer.MAX_VALUE) {
                        throw new MediaException.Malformed("a Snappy copy reaches before the output");
                    }
                    at = copy(out, outOffset, at, outEnd, (int) distance, (tag >>> 2) + 1);
                }
            }
        }
        if (at != outEnd) {
            throw new MediaException.Malformed("the Snappy block holds " + (at - outOffset) + " bytes, not the "
                    + expected + " it declares");
        }
        return expected;
    }

    /**
     * Copies earlier output, byte by byte when the source overlaps the destination.
     *
     * @param out the output
     * @param start the index of the first output byte
     * @param at the index of the next output byte
     * @param end the end of the output (exclusive)
     * @param distance how far back the copy starts
     * @param size the number of bytes copied
     * @return the index after the copy
     */
    private static int copy(byte[] out, int start, int at, int end, int distance, int size) {
        if (distance == 0 || distance > at - start) {
            throw new MediaException.Malformed("a Snappy copy reaches before the output");
        }
        if (end - at < size) {
            throw new MediaException.Malformed("a Snappy copy overruns its block");
        }
        if (distance >= size) {
            System.arraycopy(out, at - distance, out, at, size);
        } else {
            for (var index = 0; index < size; index++) {
                out[at + index] = out[at + index - distance];
            }
        }
        return at + size;
    }

    /**
     * Returns the largest size a compressed block of an input length can have.
     *
     * @param length the input length
     * @return the bound
     */
    public static long maxCompressedLength(int length) {
        return 32L + length + length / 6;
    }

    /**
     * Compresses part of an array into a block.
     *
     * <p>The output is a valid Snappy block of the input; its exact bytes depend only on the input.
     *
     * @param data the array holding the input
     * @param offset the index of its first byte
     * @param length the number of input bytes
     * @param out the destination, with room for {@link #maxCompressedLength(int)} bytes from {@code outOffset}
     * @param outOffset the index of the first byte written
     * @return the number of bytes written
     */
    public int compress(byte[] data, int offset, int length, byte[] out, int outOffset) {
        var at = outOffset;
        var remaining = length;
        do {
            out[at++] = (byte) (remaining & 0x7F | (remaining > 0x7F ? 0x80 : 0));
            remaining >>>= 7;
        } while (remaining != 0);
        for (var block = 0; block < length; block += BLOCK) {
            at = compressBlock(data, offset + block, Math.min(BLOCK, length - block), out, at);
        }
        return at - outOffset;
    }

    /**
     * Compresses up to 64 KiB of input with a greedy hash match search.
     *
     * @param data the array holding the input
     * @param offset the index of the block's first byte
     * @param length the number of input bytes, at most {@value #BLOCK}
     * @param out the destination
     * @param at the index of the next byte written
     * @return the index after the block's elements
     */
    private int compressBlock(byte[] data, int offset, int length, byte[] out, int at) {
        java.util.Arrays.fill(table, 0);
        var literal = 0;
        var index = 0;
        var limit = length - MIN_MATCH;
        while (index <= limit) {
            var sequence = u32(data, offset + index);
            var hash = (sequence * 0x1E35A7BD) >>> (32 - HASH_BITS);
            var candidate = table[hash] - 1;
            table[hash] = index + 1;
            if (candidate < 0 || u32(data, offset + candidate) != sequence) {
                index++;
                continue;
            }
            var size = MIN_MATCH;
            while (index + size < length && data[offset + candidate + size] == data[offset + index + size]) {
                size++;
            }
            at = literal(data, offset + literal, index - literal, out, at);
            at = copies(index - candidate, size, out, at);
            index += size;
            literal = index;
        }
        return literal(data, offset + literal, length - literal, out, at);
    }

    /**
     * Writes a literal element.
     *
     * @param data the array holding the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes, possibly zero
     * @param out the destination
     * @param at the index of the next byte written
     * @return the index after the element
     */
    private static int literal(byte[] data, int offset, int length, byte[] out, int at) {
        if (length == 0) {
            return at;
        }
        var stored = length - 1;
        if (stored < 60) {
            out[at++] = (byte) (stored << 2);
        } else if (stored < 1 << 8) {
            out[at++] = (byte) (60 << 2);
            out[at++] = (byte) stored;
        } else {
            out[at++] = (byte) (61 << 2);
            out[at++] = (byte) stored;
            out[at++] = (byte) (stored >>> 8);
        }
        System.arraycopy(data, offset, out, at, length);
        return at + length;
    }

    /**
     * Writes the copy elements of a match, at most 64 bytes each, as 1-byte offset copies where they fit.
     *
     * @param distance the match distance, from 1 to 65535
     * @param size the match length
     * @param out the destination
     * @param at the index of the next byte written
     * @return the index after the elements
     */
    private static int copies(int distance, int size, byte[] out, int at) {
        while (size > 0) {
            var part = size > 64 ? (size - 64 < 4 ? 60 : 64) : size;
            if (part >= 4 && part <= 11 && distance < 2048) {
                out[at++] = (byte) (1 | (part - 4) << 2 | (distance >>> 8) << 5);
                out[at++] = (byte) distance;
            } else {
                out[at++] = (byte) (2 | (part - 1) << 2);
                out[at++] = (byte) distance;
                out[at++] = (byte) (distance >>> 8);
            }
            size -= part;
        }
        return at;
    }

    /**
     * {@return four bytes read little-endian}
     *
     * @param data the array
     * @param offset the index of the first byte
     */
    private static int u32(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }
}
