package com.github.auties00.smalt.image.jp2.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes JPX files (ISO/IEC 15444-2 annex M) box by box for tests: a box is its type and contents, and a superbox is
 * the concatenation of its boxes.
 */
final class JpxBuilder {
    private JpxBuilder() {
        throw new AssertionError();
    }

    static byte[] box(String type, byte[]... contents) {
        var body = new ByteArrayOutputStream();
        for (var content : contents) {
            body.writeBytes(content);
        }
        var out = new ByteArrayOutputStream();
        Part2Forward.u32(out, 8L + body.size());
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] signature() {
        return Jp2Boxes.SIGNATURE.clone();
    }

    static byte[] ftyp() {
        var out = new ByteArrayOutputStream();
        out.writeBytes("jpx ".getBytes(StandardCharsets.ISO_8859_1));
        Part2Forward.u32(out, 0);
        out.writeBytes("jpx jp2 ".getBytes(StandardCharsets.ISO_8859_1));
        return box("ftyp", out.toByteArray());
    }

    static byte[] ihdr(int width, int height, int components, int depth) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u32(out, height);
        Part2Forward.u32(out, width);
        Part2Forward.u16(out, components);
        out.write(depth - 1);
        out.write(7);
        out.write(0);
        out.write(0);
        return box("ihdr", out.toByteArray());
    }

    /** An enumerated color specification box with a precedence and optional enumerated parameters. */
    static byte[] colr(int space, int precedence, byte[] parameters) {
        var out = new ByteArrayOutputStream();
        out.write(1);
        out.write(precedence);
        out.write(1);
        Part2Forward.u32(out, space);
        if (parameters != null) {
            out.writeBytes(parameters);
        }
        return box("colr", out.toByteArray());
    }

    static byte[] colr(int space) {
        return colr(space, 0, null);
    }

    /** A color specification box of an unknown method, which readers ignore. */
    static byte[] colrUnknown(int precedence) {
        return box("colr", new byte[]{9, (byte) precedence, 1, 1, 2, 3});
    }

    static byte[] cdef(int[]... definitions) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, definitions.length);
        for (var definition : definitions) {
            Part2Forward.u16(out, definition[0]);
            Part2Forward.u16(out, definition[1]);
            Part2Forward.u16(out, definition[2]);
        }
        return box("cdef", out.toByteArray());
    }

    static byte[] opct(int type) {
        return box("opct", new byte[]{(byte) type});
    }

    static byte[] opctKey(byte[] key, int channels) {
        var out = new ByteArrayOutputStream();
        out.write(2);
        out.write(channels);
        out.writeBytes(key);
        return box("opct", out.toByteArray());
    }

    /** A codestream registration box; each entry is {CDN, XR, YR, XO, YO}. */
    static byte[] creg(int gridX, int gridY, int[]... entries) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, gridX);
        Part2Forward.u16(out, gridY);
        for (var entry : entries) {
            Part2Forward.u16(out, entry[0]);
            out.write(entry[1]);
            out.write(entry[2]);
            out.write(entry[3]);
            out.write(entry[4]);
        }
        return box("creg", out.toByteArray());
    }

    /** A palette box of 8-bit unsigned columns, entries row-major. */
    static byte[] pclr(int columns, int... entries) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, entries.length / columns);
        out.write(columns);
        for (var c = 0; c < columns; c++) {
            out.write(7);
        }
        for (var entry : entries) {
            out.write(entry);
        }
        return box("pclr", out.toByteArray());
    }

    /** A component mapping box; each entry is {component, type, column}. */
    static byte[] cmap(int[]... entries) {
        var out = new ByteArrayOutputStream();
        for (var entry : entries) {
            Part2Forward.u16(out, entry[0]);
            out.write(entry[1]);
            out.write(entry[2]);
        }
        return box("cmap", out.toByteArray());
    }

    /** A fragment list box; each fragment is {offset, length, data reference}. */
    static byte[] flst(long[]... fragments) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, fragments.length);
        for (var fragment : fragments) {
            Part2Forward.u64(out, fragment[0]);
            Part2Forward.u32(out, fragment[1]);
            Part2Forward.u16(out, (int) fragment[2]);
        }
        return box("flst", out.toByteArray());
    }

    static byte[] cref(String type, byte[] fragmentList) {
        return box("cref", type.getBytes(StandardCharsets.ISO_8859_1), fragmentList);
    }

    static byte[] copt(long width, long height, int repeat) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u32(out, height);
        Part2Forward.u32(out, width);
        out.write(repeat);
        return box("copt", out.toByteArray());
    }

    /** One instruction of an instruction set: the fields its {@code Ityp} selects, in order. */
    record Instruction(long x, long y, long width, long height, boolean persist, long life, long nextUse, long cropX,
                       long cropY, long cropWidth, long cropHeight, int rotation) {
        static Instruction at(long x, long y, boolean persist, long life, long nextUse) {
            return new Instruction(x, y, 0, 0, persist, life, nextUse, 0, 0, 0, 0, 0);
        }
    }

    static byte[] inst(int type, int repeat, long tick, Instruction... instructions) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, type);
        Part2Forward.u16(out, repeat);
        Part2Forward.u32(out, tick);
        for (var instruction : instructions) {
            if ((type & 1) != 0) {
                Part2Forward.u32(out, instruction.x());
                Part2Forward.u32(out, instruction.y());
            }
            if ((type & 2) != 0) {
                Part2Forward.u32(out, instruction.width());
                Part2Forward.u32(out, instruction.height());
            }
            if ((type & 8) != 0) {
                Part2Forward.u32(out, (instruction.persist() ? 0x80000000L : 0) | instruction.life());
                Part2Forward.u32(out, instruction.nextUse());
            }
            if ((type & 0x20) != 0) {
                Part2Forward.u32(out, instruction.cropX());
                Part2Forward.u32(out, instruction.cropY());
                Part2Forward.u32(out, instruction.cropWidth());
                Part2Forward.u32(out, instruction.cropHeight());
            }
            if ((type & 0x40) != 0) {
                Part2Forward.u32(out, instruction.rotation());
            }
        }
        return box("inst", out.toByteArray());
    }

    /**
     * Lays out boxes and places fragments: each {@code null} entry of {@code boxes} is replaced by a media data box
     * holding the matching chunk of {@code chunks}, and the offsets of the chunks are returned so that fragment lists
     * written afterwards can point at them. The first pass sizes the file with placeholder fragment lists of the same
     * length.
     */
    static final class Layout {
        final List<byte[]> boxes = new ArrayList<>();
        final List<byte[]> chunks = new ArrayList<>();

        Layout add(byte[] box) {
            boxes.add(box);
            return this;
        }

        /** Adds a media data box holding a chunk; returns the chunk index. */
        int media(byte[] chunk) {
            boxes.add(null);
            chunks.add(chunk);
            return chunks.size() - 1;
        }

        /** Returns the offset of the first content byte of each media data box. */
        long[] offsets() {
            var offsets = new long[chunks.size()];
            var position = 0L;
            var chunk = 0;
            for (var box : boxes) {
                if (box == null) {
                    offsets[chunk] = position + 8;
                    position += 8 + chunks.get(chunk++).length;
                } else {
                    position += box.length;
                }
            }
            return offsets;
        }

        byte[] build() {
            var out = new ByteArrayOutputStream();
            var chunk = 0;
            for (var box : boxes) {
                out.writeBytes(box == null ? box("mdat", chunks.get(chunk++)) : box);
            }
            return out.toByteArray();
        }
    }

    /** Returns the codestream of a JP2 or raw file: the contents of its first {@code jp2c} box. */
    static byte[] codestream(byte[] file) {
        if ((file[0] & 0xFF) == 0xFF) {
            return file;
        }
        var position = 12;
        while (position + 8 <= file.length) {
            var length = (int) com.github.auties00.smalt.internal.io.ByteArrays.u32be(file, position);
            var type = new String(file, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("jp2c")) {
                return java.util.Arrays.copyOfRange(file, position + 8, length == 0 ? file.length : position + length);
            }
            position += length;
        }
        throw new AssertionError("no codestream");
    }
}
