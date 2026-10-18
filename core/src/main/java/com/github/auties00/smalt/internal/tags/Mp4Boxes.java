package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks and builds ISO base media file format boxes held in memory.
 */
final class Mp4Boxes {
    /**
     * The largest number of children listed for one box.
     */
    private static final int MAX_CHILDREN = 1 << 16;
    private Mp4Boxes() {
        throw new AssertionError();
    }

    /**
     * Lists the boxes in a byte range.
     *
     * <p>Listing stops at the first box whose size is invalid or runs past the range; the boxes
     * before it are returned.
     *
     * @param data the bytes
     * @param start the index of the first box
     * @param end the end of the range
     * @return the boxes in order
     */
    static List<Box> children(byte[] data, int start, int end) {
        var result = new ArrayList<Box>();
        var position = start;
        while (end - position >= 8 && result.size() < MAX_CHILDREN) {
            long size = ByteArrays.u32be(data, position);
            var header = 8;
            if (size == 1) {
                if (end - position < 16) {
                    break;
                }
                size = ByteArrays.i64be(data, position + 8);
                header = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < header || size > end - position) {
                break;
            }
            result.add(new Box(type(data, position + 4), position, header, (int) size));
            position += (int) size;
        }
        return result;
    }

    /**
     * Returns the four-character type at an offset.
     *
     * @param data the bytes
     * @param offset the index of the type
     * @return the type, ISO-8859-1 decoded so {@code 0xA9} reads as the copyright sign
     */
    static String type(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.ISO_8859_1);
    }

    /**
     * Finds the first child of a type.
     *
     * @param boxes the children to search
     * @param type the four-character box type
     * @return the box, or {@code null}
     */
    static Box find(List<Box> boxes, String type) {
        for (var box : boxes) {
            if (box.type().equals(type)) {
                return box;
            }
        }
        return null;
    }

    /**
     * Writes a box header and payload.
     *
     * @param out the destination
     * @param type the four-character type
     * @param payload the box payload
     * @throws MediaException.LimitExceeded if the box exceeds 4 GiB
     */
    static void box(ByteWriter out, String type, byte[] payload) {
        var size = 8L + payload.length;
        if (size > 0xFFFFFFFFL) {
            throw new MediaException.LimitExceeded(type + " box of " + size + " bytes is too large");
        }
        out.u32be(size);
        out.bytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.bytes(payload);
    }

    /**
     * Returns a box as bytes.
     *
     * @param type the four-character type
     * @param payload the box payload
     * @return the box
     */
    static byte[] box(String type, byte[] payload) {
        var out = new ByteWriter(8 + payload.length);
        box(out, type, payload);
        return out.toByteArray();
    }

    /**
     * Returns an empty {@code free} box of a total size.
     *
     * @param size the total size, at least 8
     * @return the box
     */
    static byte[] free(long size) {
        if (size < 8 || size > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("invalid free box size " + size);
        }
        var box = new byte[(int) size];
        ByteArrays.put32be(box, 0, size);
        box[4] = 'f';
        box[5] = 'r';
        box[6] = 'e';
        box[7] = 'e';
        return box;
    }

    /**
     * Locates one box inside a byte array.
     *
     * @param type the four-character type
     * @param offset the index of the box header
     * @param header the size of the header, 8 or 16
     * @param size the total size of the box
     */
    record Box(String type, int offset, int header, int size) {
        /**
         * {@return the index of the payload}
         */
        int payload() {
            return offset + header;
        }

        /**
         * {@return the index of the first byte after the box}
         */
        int end() {
            return offset + size;
        }
    }
}
