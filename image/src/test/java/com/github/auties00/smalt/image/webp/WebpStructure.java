package com.github.auties00.smalt.image.webp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the RIFF layout of a WebP file against the container specification (RFC 9649),
 * independently of the decoder: chunk sizes and padding, the VP8X flags and canvas, the chunk
 * order, the ANIM and ANMF fields, frame bounds, and the agreement of every frame header with
 * its bitstream.
 */
final class WebpStructure {
    private WebpStructure() {
        throw new AssertionError();
    }

    record RawChunk(String tag, int offset, int size) {
        int payload() {
            return offset + 8;
        }
    }

    static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    static int le24(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16;
    }

    static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    static List<RawChunk> chunks(byte[] file, int start, int end) {
        var chunks = new ArrayList<RawChunk>();
        var pos = start;
        while (pos < end) {
            assertTrue(end - pos >= 8, "chunk header fits at " + pos);
            var tag = new String(file, pos, 4, StandardCharsets.ISO_8859_1);
            var size = (int) le32(file, pos + 4);
            assertTrue(pos + 8L + size <= end, tag + " payload fits");
            if ((size & 1) != 0) {
                assertTrue(pos + 8 + size < end, tag + " is padded");
                assertEquals(0, file[pos + 8 + size], tag + " padding byte is zero");
            }
            chunks.add(new RawChunk(tag, pos, size));
            pos += 8 + size + (size & 1);
        }
        assertEquals(end, pos, "chunks end exactly at the container end");
        return chunks;
    }

    /** Returns {width, height, alpha} of a VP8 or VP8L payload. */
    static int[] bitstream(byte[] file, RawChunk chunk) {
        var p = chunk.payload();
        if (chunk.tag().equals("VP8L")) {
            assertEquals(0x2F, file[p] & 0xFF, "VP8L signature");
            var bits = le32(file, p + 1);
            assertEquals(0, bits >>> 29, "VP8L version");
            return new int[]{(int) (bits & 0x3FFF) + 1, (int) ((bits >>> 14) & 0x3FFF) + 1, (int) ((bits >>> 28) & 1)};
        }
        assertEquals("VP8 ", chunk.tag());
        assertEquals(0, file[p] & 1, "VP8 key frame");
        assertEquals(0x9D, file[p + 3] & 0xFF);
        assertEquals(0x01, file[p + 4] & 0xFF);
        assertEquals(0x2A, file[p + 5] & 0xFF);
        return new int[]{le16(file, p + 6) & 0x3FFF, le16(file, p + 8) & 0x3FFF, 0};
    }

    /** Validates a file, returning its top-level chunks. */
    static List<RawChunk> validate(byte[] file) {
        assertTrue(file.length >= 20, "file holds a RIFF header and a chunk");
        assertEquals("RIFF", new String(file, 0, 4, StandardCharsets.ISO_8859_1));
        assertEquals("WEBP", new String(file, 8, 4, StandardCharsets.ISO_8859_1));
        assertEquals(file.length - 8, le32(file, 4), "RIFF size covers the file");
        var chunks = chunks(file, 12, file.length);
        var first = chunks.getFirst();
        if (!first.tag().equals("VP8X")) {
            assertEquals(1, chunks.size(), "a simple file holds one chunk");
            assertTrue(first.tag().equals("VP8 ") || first.tag().equals("VP8L"));
            bitstream(file, first);
            return chunks;
        }
        assertEquals(10, first.size(), "VP8X size");
        var flags = file[first.payload()] & 0xFF;
        assertEquals(0, flags & ~0x3E, "reserved VP8X flag bits are zero");
        assertEquals(0, le24(file, first.payload() + 1), "reserved VP8X bytes are zero");
        var canvasW = le24(file, first.payload() + 4) + 1;
        var canvasH = le24(file, first.payload() + 7) + 1;
        var animated = (flags & 0x02) != 0;
        var i = 1;
        var hasIcc = false;
        var hasExif = false;
        var hasXmp = false;
        var anyAlpha = false;
        if (i < chunks.size() && chunks.get(i).tag().equals("ICCP")) {
            hasIcc = true;
            i++;
        }
        if (animated) {
            assertEquals("ANIM", chunks.get(i).tag(), "ANIM follows VP8X");
            assertEquals(6, chunks.get(i).size(), "ANIM size");
            i++;
            var frames = 0;
            for (; i < chunks.size() && chunks.get(i).tag().equals("ANMF"); i++, frames++) {
                var anmf = chunks.get(i);
                var p = anmf.payload();
                assertTrue(anmf.size() >= 16, "ANMF header fits");
                var x = 2 * le24(file, p);
                var y = 2 * le24(file, p + 3);
                var w = le24(file, p + 6) + 1;
                var h = le24(file, p + 9) + 1;
                assertEquals(0, (file[p + 15] & 0xFF) & ~3, "reserved ANMF flag bits are zero");
                assertTrue(x + w <= canvasW && y + h <= canvasH, "frame " + frames + " fits the canvas");
                var sub = chunks(file, p + 16, anmf.payload() + anmf.size());
                var s = 0;
                if (sub.get(s).tag().equals("ALPH")) {
                    anyAlpha = true;
                    s++;
                    assertEquals("VP8 ", sub.get(s).tag(), "ALPH precedes a VP8 bitstream");
                }
                var dims = bitstream(file, sub.get(s));
                anyAlpha |= dims[2] != 0;
                assertEquals(w, dims[0], "frame width matches its bitstream");
                assertEquals(h, dims[1], "frame height matches its bitstream");
                assertEquals(s + 1, sub.size(), "a frame holds only its image chunks");
            }
            assertTrue(frames > 0, "an animation holds frames");
        } else {
            if (chunks.get(i).tag().equals("ALPH")) {
                anyAlpha = true;
                i++;
                assertEquals("VP8 ", chunks.get(i).tag());
            }
            var dims = bitstream(file, chunks.get(i));
            anyAlpha |= dims[2] != 0;
            assertEquals(canvasW, dims[0], "canvas width matches the image");
            assertEquals(canvasH, dims[1], "canvas height matches the image");
            i++;
        }
        if (i < chunks.size() && chunks.get(i).tag().equals("EXIF")) {
            hasExif = true;
            i++;
        }
        if (i < chunks.size() && chunks.get(i).tag().equals("XMP ")) {
            hasXmp = true;
            i++;
        }
        for (; i < chunks.size(); i++) {
            var tag = chunks.get(i).tag();
            assertFalse(List.of("VP8X", "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L", "EXIF", "XMP ").contains(tag),
                    tag + " is out of order");
        }
        assertEquals(hasIcc, (flags & 0x20) != 0, "ICC flag");
        assertEquals(hasExif, (flags & 0x08) != 0, "EXIF flag");
        assertEquals(hasXmp, (flags & 0x04) != 0, "XMP flag");
        if (anyAlpha) {
            assertTrue((flags & 0x10) != 0, "alpha flag set for transparent frames");
        }
        return chunks;
    }
}
