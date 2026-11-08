package com.github.auties00.smalt.internal.font;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the TrueType reader and the rasterizer on the synthetic font of
 * {@code tools/testdata/fonts/make-test-font.py}, whose glyphs have known outlines and areas: cmap lookups,
 * metrics, simple, implied-point and composite outlines (offsets, scales and point matching), exact coverage of
 * axis-aligned edges, the areas of curved and holed glyphs, collections, and the rejection of damaged fonts with
 * {@link MediaException}s only.
 */
@DisplayName("TrueType fonts")
class TrueTypeFontTest {
    /** The SHA-256 of the committed font, so that a regenerated font that changed is noticed. */
    static final String SHA256 = "4556687c0e7985390b3826a04b8b109aa7266675bad31df3503cf236a08fe5fa";

    static byte[] fontBytes() {
        try (var stream = TrueTypeFontTest.class.getResourceAsStream("/com/github/auties00/smalt/subtitle/smalt-test.ttf")) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static TrueTypeFont font() {
        return TrueTypeFont.read(fontBytes());
    }

    /** The coverage of an outline at a scale, in pixels of full coverage. */
    static double area(GlyphOutline outline, double scale, int size, double originX, double baseline) {
        var raster = new Rasterizer(size, size);
        outline.flatten(scale, 0, originX, baseline, 0.05, raster);
        var mask = raster.fill();
        var sum = 0L;
        for (var value : mask) {
            sum += value & 0xFF;
        }
        return sum / 255.0;
    }

    @Test
    @DisplayName("reads the committed font, which the generator pins")
    void pinned() throws NoSuchAlgorithmException {
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fontBytes()));
        assertEquals(SHA256, digest);
    }

    @Test
    @DisplayName("reads the metrics of the font")
    void metrics() {
        var font = font();
        assertEquals(1000, font.unitsPerEm());
        assertEquals(800, font.ascender());
        assertEquals(-200, font.descender());
        assertEquals(0, font.lineGap());
        assertEquals(-100, font.underlinePosition());
        assertEquals(50, font.underlineThickness());
        assertEquals(9, font.glyphCount());
        assertEquals(1000, font.advance(2));
        assertEquals(1250, font.advance(7));
        assertEquals(200, font.advance(8));
    }

    @Test
    @DisplayName("maps characters through the format 12 subtable, supplementary planes included")
    void cmap() {
        var font = font();
        assertEquals(1, font.glyph(' '));
        assertEquals(2, font.glyph('#'));
        assertEquals(3, font.glyph('T'));
        assertEquals(4, font.glyph('O'));
        assertEquals(5, font.glyph('o'));
        assertEquals(6, font.glyph('B'));
        assertEquals(7, font.glyph('C'));
        assertEquals(8, font.glyph('H'));
        assertEquals(8, font.glyph('z'));
        assertEquals(2, font.glyph(0x1F600));
        assertEquals(0, font.glyph(0xE9));
        assertEquals(0, font.glyph(0x10FFFF));
    }

    @Test
    @DisplayName("maps characters through the format 4 subtable when it is the only one")
    void format4() {
        var data = fontBytes();
        // drop the format 12 record: the cmap table then lists only its format 4 subtable
        var cmap = tableOffset(data, "cmap");
        ByteBuffer.wrap(data).putShort(cmap + 2, (short) 1);
        var font = TrueTypeFont.read(data);
        assertEquals(2, font.glyph('#'));
        assertEquals(8, font.glyph('q'));
        assertEquals(0, font.glyph(0x1F600));
    }

    static int tableOffset(byte[] data, String tag) {
        var buffer = ByteBuffer.wrap(data);
        var tables = buffer.getShort(4) & 0xFFFF;
        for (var index = 0; index < tables; index++) {
            var record = 12 + 16 * index;
            var name = new String(data, record, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (name.equals(tag)) {
                return buffer.getInt(record + 8);
            }
        }
        throw new AssertionError(tag);
    }

    @Nested
    @DisplayName("outlines")
    class Outlines {
        @Test
        @DisplayName("reads simple glyphs and resolves composites by offsets, scales and matched points")
        void composites() {
            var font = font();
            var square = font.outline(2);
            assertArrayEquals(new double[]{0, 0, 1000, 1000}, square.xs());
            assertArrayEquals(new double[]{0, 1000, 1000, 0}, square.ys());
            assertArrayEquals(new int[]{4}, square.ends());
            var pair = font.outline(6);
            assertArrayEquals(new double[]{0, 0, 500, 500, 500, 500, 1000, 1000}, pair.xs());
            assertArrayEquals(new double[]{0, 500, 500, 0, 500, 1000, 1000, 500}, pair.ys());
            assertArrayEquals(new int[]{4, 8}, pair.ends());
            var matched = font.outline(7);
            // the triangle, then the quarter square whose point 0 lands on the triangle's point 2 (1000, 0)
            assertArrayEquals(new double[]{0, 0, 1000, 1000, 1000, 1250, 1250}, matched.xs());
            assertArrayEquals(new double[]{0, 1000, 0, 0, 250, 250, 0}, matched.ys());
            assertTrue(font.outline(1).isEmpty());
        }

        @Test
        @DisplayName("covers an aligned square exactly, and its edge pixels by the covered fraction")
        void squareCoverage() {
            var outline = font().outline(2);
            var raster = new Rasterizer(12, 12);
            outline.flatten(0.01, 0, 1, 11, 0.05, raster);
            var mask = raster.fill();
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 12; x++) {
                    var inside = x >= 1 && x < 11 && y >= 1 && y < 11;
                    assertEquals(inside ? 255 : 0, mask[y * 12 + x] & 0xFF, "at " + x + "," + y);
                }
            }
            var shifted = new Rasterizer(12, 12);
            outline.flatten(0.01, 0, 1.5, 11, 0.05, shifted);
            var half = shifted.fill();
            assertEquals(128, half[5 * 12 + 1] & 0xFF);
            assertEquals(255, half[5 * 12 + 5] & 0xFF);
            assertEquals(128, half[5 * 12 + 11] & 0xFF);
        }

        @Test
        @DisplayName("covers the areas of a triangle, a disc made of off-curve points only and a ring")
        void areas() {
            var font = font();
            assertEquals(0.5 * 50 * 50, area(font.outline(3), 0.05, 60, 2, 55), 0.5 * 50 * 50 * 0.01);
            // eight quadratic arcs through the octagon of radius 400 / cos(22.5 degrees) enclose about pi r^2
            var radius = 40.0;
            var disc = area(font.outline(4), 0.1, 100, 0, 90);
            assertEquals(Math.PI * radius * radius, disc, Math.PI * radius * radius * 0.01);
            var ring = area(font.outline(5), 0.1, 100, 0, 90);
            assertEquals(Math.PI * (radius * radius - 20 * 20), ring, Math.PI * (radius * radius - 400) * 0.015);
            var notdef = area(font.outline(0), 0.1, 100, 0, 90);
            assertEquals(60 * 70 - 40 * 50, notdef, 30);
        }

        @Test
        @DisplayName("slants an outline by the shear")
        void shear() {
            var outline = font().outline(2);
            var upright = new Rasterizer(40, 24);
            outline.flatten(0.02, 0, 2, 22, 0.05, upright);
            var slanted = new Rasterizer(40, 24);
            outline.flatten(0.02, 0.5, 2, 22, 0.05, slanted);
            var a = upright.fill();
            var b = slanted.fill();
            // the bottom row starts at the origin in both, the top row 10 pixels further right when slanted
            assertEquals(255, a[21 * 40 + 3] & 0xFF);
            assertEquals(255, b[21 * 40 + 3] & 0xFF);
            assertEquals(255, a[3 * 40 + 3] & 0xFF);
            assertEquals(0, b[3 * 40 + 3] & 0xFF);
            assertEquals(255, b[3 * 40 + 13] & 0xFF);
        }
    }

    @Nested
    @DisplayName("damaged and unusual files")
    class Damaged {
        @Test
        @DisplayName("reads the first font of a collection")
        void collection() {
            var font = fontBytes();
            var shift = 16;
            var collection = ByteBuffer.allocate(shift + font.length);
            collection.putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(shift).put(font);
            var data = collection.array();
            var tables = ByteBuffer.wrap(data).getShort(shift + 4) & 0xFFFF;
            for (var index = 0; index < tables; index++) {
                var at = shift + 12 + 16 * index + 8;
                ByteBuffer.wrap(data).putInt(at, ByteBuffer.wrap(data).getInt(at) + shift);
            }
            var read = TrueTypeFont.read(data);
            assertEquals(2, read.glyph('#'));
            assertArrayEquals(font().outline(6).xs(), read.outline(6).xs());
        }

        @Test
        @DisplayName("rejects PostScript outlines as unsupported and other files as malformed")
        void kinds() {
            var data = fontBytes();
            data[0] = 'O';
            data[1] = 'T';
            data[2] = 'T';
            data[3] = 'O';
            assertThrows(MediaException.Unsupported.class, () -> TrueTypeFont.read(data));
            assertThrows(MediaException.Malformed.class, () -> TrueTypeFont.read(new byte[]{1, 2, 3}));
            assertThrows(MediaException.Malformed.class, () -> TrueTypeFont.read("not a font at all".getBytes()));
        }

        @Test
        @DisplayName("fails every truncation and corruption with a media exception only")
        void corruption() {
            var data = fontBytes();
            for (var length = 0; length < data.length; length++) {
                exercise(java.util.Arrays.copyOf(data, length));
            }
            var random = new SplittableRandom(20261024);
            for (var round = 0; round < 3000; round++) {
                var copy = data.clone();
                for (var flips = 1 + random.nextInt(6); flips > 0; flips--) {
                    copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
                }
                exercise(copy);
            }
        }

        static void exercise(byte[] data) {
            TrueTypeFont font;
            try {
                font = TrueTypeFont.read(data);
            } catch (MediaException expected) {
                return;
            }
            for (var codePoint : new int[]{' ', '#', 'T', 'O', 'o', 'B', 'C', 'H', 0x1F600, 0xFFFF}) {
                var glyph = font.glyph(codePoint);
                assertTrue(glyph >= 0 && glyph < font.glyphCount());
                font.advance(glyph);
            }
            for (var glyph = 0; glyph < font.glyphCount(); glyph++) {
                try {
                    var outline = font.outline(glyph);
                    var raster = new Rasterizer(16, 16);
                    outline.flatten(0.01, 0.2, 2, 12, 0.1, raster);
                    raster.fill();
                } catch (MediaException expected) {
                    // a damaged glyph fails alone
                }
            }
        }
    }
}
