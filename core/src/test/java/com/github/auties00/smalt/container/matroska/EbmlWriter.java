package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.internal.tags.EbmlIds;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds EBML documents by hand for tests: elements are nested lists of children or leaf values.
 */
final class EbmlWriter {
    private EbmlWriter() {
        throw new AssertionError();
    }

    /** An element tree node. */
    sealed interface Node permits Master, Leaf, Raw {
        byte[] encode();
    }

    /** A master element; {@code unknownSize} writes the one-byte unknown size marker. */
    record Master(long id, boolean unknownSize, List<Node> children) implements Node {
        @Override
        public byte[] encode() {
            var body = new ByteArrayOutputStream();
            for (var child : children) {
                body.writeBytes(child.encode());
            }
            var out = new ByteArrayOutputStream();
            out.writeBytes(encodeId(id));
            out.writeBytes(unknownSize ? new byte[]{(byte) 0xFF} : size(body.size()));
            out.writeBytes(body.toByteArray());
            return out.toByteArray();
        }
    }

    /** A leaf element holding raw bytes. */
    record Leaf(long id, byte[] value) implements Node {
        @Override
        public byte[] encode() {
            var out = new ByteArrayOutputStream();
            out.writeBytes(encodeId(id));
            out.writeBytes(size(value.length));
            out.writeBytes(value);
            return out.toByteArray();
        }
    }

    /** Bytes copied verbatim. */
    record Raw(byte[] bytes) implements Node {
        @Override
        public byte[] encode() {
            return bytes;
        }
    }

    static Master master(long id, Node... children) {
        return new Master(id, false, List.of(children));
    }

    static Master unknown(long id, Node... children) {
        return new Master(id, true, List.of(children));
    }

    static Master master(long id, List<Node> children) {
        return new Master(id, false, children);
    }

    static Leaf uint(long id, long value) {
        var bytes = new ArrayList<Byte>();
        do {
            bytes.addFirst((byte) value);
            value >>>= 8;
        } while (value != 0);
        var array = new byte[bytes.size()];
        for (var index = 0; index < array.length; index++) {
            array[index] = bytes.get(index);
        }
        return new Leaf(id, array);
    }

    static Leaf string(long id, String value) {
        return new Leaf(id, value.getBytes(StandardCharsets.UTF_8));
    }

    static Leaf floating(long id, double value) {
        var bits = Double.doubleToLongBits(value);
        var array = new byte[8];
        for (var index = 0; index < 8; index++) {
            array[index] = (byte) (bits >>> (56 - 8 * index));
        }
        return new Leaf(id, array);
    }

    static Leaf binary(long id, byte[] value) {
        return new Leaf(id, value);
    }

    static byte[] encodeId(long id) {
        var length = id >= 0x1000000 ? 4 : id >= 0x10000 ? 3 : id >= 0x100 ? 2 : 1;
        var out = new byte[length];
        for (var index = 0; index < length; index++) {
            out[index] = (byte) (id >>> (8 * (length - 1 - index)));
        }
        return out;
    }

    /** Encodes a data size in the shortest valid form. */
    static byte[] size(long value) {
        for (var length = 1; length <= 8; length++) {
            if (value < (1L << (7 * length)) - 1) {
                var out = new byte[length];
                var marked = value | (1L << (7 * length));
                for (var index = 0; index < length; index++) {
                    out[index] = (byte) (marked >>> (8 * (length - 1 - index)));
                }
                return out;
            }
        }
        throw new IllegalArgumentException("size too large");
    }

    /** Encodes a signed EBML lace size difference of the given width. */
    static byte[] signedSize(long value, int width) {
        var biased = value + (1L << (7 * width - 1)) - 1;
        var marked = biased | (1L << (7 * width));
        var out = new byte[width];
        for (var index = 0; index < width; index++) {
            out[index] = (byte) (marked >>> (8 * (width - 1 - index)));
        }
        return out;
    }

    /** Builds a block body: track number, relative timestamp, flags, then the given payload. */
    static byte[] block(int track, int relative, int flags, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(size(track));
        out.write(relative >> 8);
        out.write(relative);
        out.write(flags);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** Builds Xiph lacing for frames. */
    static byte[] xiphLace(List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        out.write(frames.size() - 1);
        for (var frame : frames.subList(0, frames.size() - 1)) {
            var size = frame.length;
            while (size >= 255) {
                out.write(255);
                size -= 255;
            }
            out.write(size);
        }
        frames.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Builds EBML lacing for frames. */
    static byte[] ebmlLace(List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        out.write(frames.size() - 1);
        out.writeBytes(size(frames.getFirst().length));
        for (var index = 1; index < frames.size() - 1; index++) {
            var difference = (long) frames.get(index).length - frames.get(index - 1).length;
            var width = 1;
            while (Math.abs(difference) >= (1L << (7 * width - 1)) - 1) {
                width++;
            }
            out.writeBytes(signedSize(difference, width));
        }
        frames.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Builds fixed-size lacing for equally sized frames. */
    static byte[] fixedLace(List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        out.write(frames.size() - 1);
        frames.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Builds a complete file: EBML header plus the given segment children. */
    static byte[] file(String docType, boolean unknownSegment, Node... segmentChildren) {
        var header = master(EbmlIds.EBML, uint(EbmlIds.EBML_VERSION, 1), uint(EbmlIds.EBML_READ_VERSION, 1),
                uint(EbmlIds.EBML_MAX_ID_LENGTH, 4), uint(EbmlIds.EBML_MAX_SIZE_LENGTH, 8), string(EbmlIds.DOC_TYPE, docType),
                uint(EbmlIds.DOC_TYPE_VERSION, 4), uint(EbmlIds.DOC_TYPE_READ_VERSION, 2));
        var segment = new Master(EbmlIds.SEGMENT, unknownSegment, List.of(segmentChildren));
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.encode());
        out.writeBytes(segment.encode());
        return out.toByteArray();
    }
}
