package com.github.auties00.smalt.image.internal.xz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Rewrites one field of a valid XZ file at a time and recomputes the CRC32 that covers it, so that the decoder
 * reaches the check of the field itself: the reserved stream and block flags, a zero compressed size, filter
 * properties past the block header, the LZMA2, delta and branch filter properties, header padding, and the footer's
 * backward size and flags.
 */
@DisplayName("XZ header fields")
class XzFieldsTest {
    private static byte[] file(String name) throws Exception {
        return Files.readAllBytes(XzDecoderTest.DIRECTORY.resolve(name));
    }

    private static void putCrc(byte[] data, int from, int length, int at) {
        var crc = new CRC32();
        crc.update(data, from, length);
        var value = (int) crc.getValue();
        for (var index = 0; index < 4; index++) {
            data[at + index] = (byte) (value >>> 8 * index);
        }
    }

    /**
     * Replaces the first block header of a file with one holding the given flags and fields, padded and checked.
     *
     * @param fields the bytes after the flags byte: the optional sizes and the filter flags
     */
    private static byte[] withBlockHeader(byte[] data, int flags, byte[] fields) {
        var oldSize = ((data[12] & 0xFF) + 1) * 4;
        var body = new ByteArrayOutputStream();
        body.write(0);
        body.write(flags);
        body.writeBytes(fields);
        while ((body.size() + 4) % 4 != 0) {
            body.write(0);
        }
        var header = Arrays.copyOf(body.toByteArray(), body.size() + 4);
        header[0] = (byte) (header.length / 4 - 1);
        putCrc(header, 0, header.length - 4, header.length - 4);
        var out = new ByteArrayOutputStream();
        out.write(data, 0, 12);
        out.writeBytes(header);
        out.write(data, 12 + oldSize, data.length - 12 - oldSize);
        return out.toByteArray();
    }

    /**
     * {@return the size fields of the first block header of a file, the compressed then the uncompressed size}
     */
    private static byte[] sizes(byte[] data) {
        var at = 14;
        var start = at;
        for (var field = 0; field < 2; field++) {
            while ((data[at] & 0x80) != 0) {
                at++;
            }
            at++;
        }
        return Arrays.copyOfRange(data, start, at);
    }

    /**
     * {@return the dictionary byte of the LZMA2 filter of the first block header of a file}
     */
    private static int dictionary(byte[] data) {
        var end = 12 + ((data[12] & 0xFF) + 1) * 4 - 4;
        for (var at = 14; at + 2 < end; at++) {
            if (data[at] == 0x21 && data[at + 1] == 1) {
                return data[at + 2] & 0xFF;
            }
        }
        throw new AssertionError("no LZMA2 filter");
    }

    /**
     * {@return the size fields of a file followed by filter flags}
     */
    private static byte[] withSizes(byte[] data, byte... filters) {
        var sizes = sizes(data);
        var out = Arrays.copyOf(sizes, sizes.length + filters.length);
        System.arraycopy(filters, 0, out, sizes.length, filters.length);
        return out;
    }

    private static byte[] lzma2(int dictionary) {
        return new byte[]{0x21, 1, (byte) dictionary};
    }

    private static void rejects(byte[] data) {
        assertThrows(DataFormatException.class, () -> XzDecoderTest.decode(data, 1 << 20));
    }

    @Test
    @DisplayName("the unmodified file decodes, as does one rewritten with the same block header")
    void control() throws Exception {
        var data = file("text-0.xz");
        var expected = XzDecoderTest.decode(data, 1 << 20);
        var dictionary = dictionary(data);
        var same = withBlockHeader(data, 0xC0, withSizes(data, lzma2(dictionary)));
        assertArrayEquals(expected, XzDecoderTest.decode(same, 1 << 20));
        assertArrayEquals(expected, XzDecoderTest.decode(withBlockHeader(data, 0xC0, withSizes(data, lzma2(40))),
                1 << 20), "the largest dictionary");
    }

    @Test
    @DisplayName("refuses reserved stream flags")
    void streamFlags() throws Exception {
        for (var flags : new int[][]{{1, 1}, {0, 0x11}}) {
            var data = file("text-0.xz");
            data[6] = (byte) flags[0];
            data[7] = (byte) flags[1];
            putCrc(data, 6, 2, 8);
            rejects(data);
        }
    }

    @Test
    @DisplayName("refuses reserved block flags, a zero compressed size and filter properties past the header")
    void blockFields() throws Exception {
        var data = file("text-0.xz");
        var dictionary = dictionary(data);
        rejects(withBlockHeader(data, 0x04, lzma2(dictionary)));
        rejects(withBlockHeader(data, 0x40, new byte[]{0, 0x21, 1, (byte) dictionary}));
        rejects(withBlockHeader(data, 0, new byte[]{0x21, 9, (byte) dictionary}));
        rejects(withBlockHeader(data, 0, new byte[]{0x21, 2, (byte) dictionary, 0}));
        rejects(withBlockHeader(data, 0, lzma2(41)));
        rejects(withBlockHeader(data, 0, new byte[]{0x21, 1, (byte) dictionary, 0, 0, 7}));
    }

    @Test
    @DisplayName("refuses filter chains with misplaced or malformed filters")
    void filters() throws Exception {
        var data = file("text-0.xz");
        var dictionary = dictionary(data);
        rejects(withBlockHeader(data, 1, new byte[]{0x21, 1, (byte) dictionary, 0x21, 1, (byte) dictionary}));
        rejects(withBlockHeader(data, 0, new byte[]{0x03, 1, 0}));
        rejects(withBlockHeader(data, 1, new byte[]{0x03, 2, 0, 0, 0x21, 1, (byte) dictionary}));
        rejects(withBlockHeader(data, 1, new byte[]{0x04, 2, 0, 0, 0x21, 1, (byte) dictionary}));
        rejects(withBlockHeader(data, 1, new byte[]{0x0C, 0, 0x21, 1, (byte) dictionary}));
    }

    @Test
    @DisplayName("refuses a footer whose backward size or flags disagree with the stream")
    void footer() throws Exception {
        var original = file("text-0.xz");
        var backward = original.clone();
        var at = backward.length - 12;
        backward[at + 4]++;
        putCrc(backward, at + 4, 6, at);
        rejects(backward);
        for (var change : new int[][]{{8, 1}, {9, 0x0F}, {9, 0x20}}) {
            var footer = original.clone();
            footer[at + change[0]] ^= (byte) change[1];
            putCrc(footer, at + 4, 6, at);
            rejects(footer);
        }
    }
}
