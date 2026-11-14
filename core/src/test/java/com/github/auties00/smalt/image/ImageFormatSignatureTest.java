package com.github.auties00.smalt.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that every byte of each format's signature takes part in its recognition: the minimal header of each
 * format is recognized, and changing any one of its signature bytes, or cutting it below its minimum length, makes
 * it another format or none. The Truevision TGA test, which has no magic number, is checked field by field.
 */
@DisplayName("image format signatures")
class ImageFormatSignatureTest {
    /**
     * One format's minimal header.
     *
     * @param format the format the header is
     * @param header the header, zero-filled after its fields
     * @param signature the indices of the bytes the recognition depends on
     * @param minimum the shortest length recognized
     */
    record Signature(ImageFormat format, byte[] header, int[] signature, int minimum) {
        @Override
        public String toString() {
            return format + " " + new String(header, 0, Math.min(header.length, 12), StandardCharsets.ISO_8859_1)
                    .replaceAll("[^ -~]", ".");
        }
    }

    static byte[] header(int length, Object... parts) {
        var out = new byte[length];
        var at = 0;
        for (var part : parts) {
            switch (part) {
                case String text -> {
                    var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
                    System.arraycopy(bytes, 0, out, at, bytes.length);
                    at += bytes.length;
                }
                case Integer value -> out[at++] = (byte) (int) value;
                case int[] skip -> at = skip[0];
                default -> throw new IllegalArgumentException(part.toString());
            }
        }
        return out;
    }

    static int[] range(int from, int to) {
        return java.util.stream.IntStream.range(from, to).toArray();
    }

    static int[] indices(int... values) {
        return values;
    }

    static Stream<Signature> signatures() {
        var box = new int[]{0, 0, 0, 0x0C};
        return Stream.of(
                new Signature(ImageFormat.JPEG, header(32, 0xFF, 0xD8, 0xFF), range(0, 3), 3),
                new Signature(ImageFormat.PNG, header(32, 0x89, "PNG", 0x0D, 0x0A, 0x1A, 0x0A), range(0, 8), 8),
                new Signature(ImageFormat.GIF, header(32, "GIF87a"), range(0, 6), 6),
                new Signature(ImageFormat.GIF, header(32, "GIF89a"), range(0, 6), 6),
                new Signature(ImageFormat.WEBP, header(32, "RIFF", 1, 2, 3, 4, "WEBP"), indices(0, 1, 2, 3, 8, 9, 10, 11), 12),
                new Signature(ImageFormat.ANI, header(32, "RIFF", 1, 2, 3, 4, "ACON"), indices(0, 1, 2, 3, 8, 9, 10, 11), 12),
                new Signature(ImageFormat.BMP, header(32, "BM"), range(0, 2), 14),
                new Signature(ImageFormat.BMP, header(32, "BA", new int[]{14}, "BM"), indices(0, 1, 14, 15), 28),
                new Signature(ImageFormat.JXL, header(32, 0xFF, 0x0A), range(0, 2), 2),
                new Signature(ImageFormat.JXL, header(32, box[0], box[1], box[2], box[3], "JXL ", 0x0D, 0x0A, 0x87, 0x0A),
                        range(0, 12), 12),
                new Signature(ImageFormat.ICO, header(32, 0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0, 40, 0, 0, 0, 22),
                        indices(0, 1, 2, 3, 4, 9), 22),
                new Signature(ImageFormat.ICO, header(32, 0, 0, 2, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0, 40, 0, 0, 0, 22),
                        indices(0, 1, 2, 3, 4, 9), 22),
                new Signature(ImageFormat.JP2, header(32, 0xFF, 0x4F, 0xFF, 0x51), range(0, 4), 4),
                new Signature(ImageFormat.JP2, header(32, box[0], box[1], box[2], box[3], "jP  ", 0x0D, 0x0A, 0x87, 0x0A),
                        range(0, 12), 12),
                new Signature(ImageFormat.JPEG_XS, header(32, 0xFF, 0x10, 0xFF, 0x50), range(0, 4), 4),
                new Signature(ImageFormat.JPEG_XS, header(32, box[0], box[1], box[2], box[3], "JXS ", 0x0D, 0x0A, 0x87,
                        0x0A), range(0, 12), 12),
                new Signature(ImageFormat.PNM, header(32, "P6\n"), range(0, 3), 3),
                new Signature(ImageFormat.PNM, header(32, "Pf "), range(0, 3), 3),
                new Signature(ImageFormat.QOI, header(32, "qoif"), range(0, 4), 14),
                new Signature(ImageFormat.HDR, header(32, "#?RADIANCE"), range(0, 3), 3),
                new Signature(ImageFormat.ICNS, header(32, "icns", 0, 0, 0, 8), range(0, 4), 8),
                new Signature(ImageFormat.PSD, header(32, "8BPS", 0, 1), range(0, 6), 6),
                new Signature(ImageFormat.PSD, header(32, "8BPS", 0, 2), range(0, 6), 6),
                new Signature(ImageFormat.EXR, header(32, 0x76, 0x2F, 0x31, 0x01), range(0, 4), 4),
                new Signature(ImageFormat.JXR, header(32, "II", 0xBC, 0), range(0, 3), 4),
                new Signature(ImageFormat.JXR, header(32, "II", 0xBC, 1), range(0, 3), 4),
                new Signature(ImageFormat.DDS, header(32, "DDS ", 124), range(0, 5), 8),
                new Signature(ImageFormat.HEIC, header(32, 0, 0, 0, 24, "ftypheic", 0, 0, 0, 0, "mif1"), range(4, 8), 16),
                new Signature(ImageFormat.AVIF, header(32, 0, 0, 0, 24, "ftypmif1", 0, 0, 0, 0, "avif"), indices(4, 5, 6,
                        7, 16), 20),
                new Signature(ImageFormat.RAW, header(32, 0, 0, 0, 24, "ftypcrx "), range(4, 12), 16),
                new Signature(ImageFormat.TGA, tga(0, 2, 0, 0, 24, 0), indices(1, 2, 16, 17), 18));
    }

    /**
     * Returns a TGA header.
     */
    static byte[] tga(int mapType, int imageType, int mapLength, int entryBits, int depth, int descriptor) {
        var header = new byte[32];
        header[1] = (byte) mapType;
        header[2] = (byte) imageType;
        header[5] = (byte) mapLength;
        header[7] = (byte) entryBits;
        header[12] = 4;
        header[14] = 4;
        header[16] = (byte) depth;
        header[17] = (byte) descriptor;
        return header;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signatures")
    @DisplayName("recognizes the header, and not one with a signature byte changed or cut short")
    void signature(Signature signature) {
        var header = signature.header();
        assertEquals(Optional.of(signature.format()), ImageFormat.detect(header, header.length));
        assertEquals(Optional.of(signature.format()), ImageFormat.detect(header, signature.minimum()));
        assertNotEquals(Optional.of(signature.format()), ImageFormat.detect(header, signature.minimum() - 1),
                "cut below the minimum length");
        for (var index : signature.signature()) {
            for (var replacement : new int[]{0x7E, 0x00, 0xFF}) {
                var changed = header.clone();
                if ((changed[index] & 0xFF) == replacement) {
                    continue;
                }
                changed[index] = (byte) replacement;
                assertNotEquals(Optional.of(signature.format()), ImageFormat.detect(changed, changed.length),
                        "byte " + index + " set to " + replacement);
            }
        }
    }

    @ParameterizedTest(name = "map {0}, type {1}, map length {2}, entry {3}, depth {4}, descriptor {5}")
    @CsvSource({"1,1,16,24,8,0", "1,9,16,15,16,0", "1,1,16,16,8,0", "1,1,16,32,8,0", "0,2,0,0,15,0", "0,10,0,0,32,8",
            "0,2,0,24,16,0", "0,3,0,0,8,0", "0,11,0,0,16,0", "0,2,0,15,24,0", "0,2,0,16,24,0", "0,2,0,32,24,0"})
    @DisplayName("recognizes every consistent TGA header")
    void tgaValid(int mapType, int imageType, int mapLength, int entryBits, int depth, int descriptor) {
        var header = tga(mapType, imageType, mapLength, entryBits, depth, descriptor);
        assertEquals(Optional.of(ImageFormat.TGA), ImageFormat.detect(header, header.length));
    }

    @ParameterizedTest(name = "map {0}, type {1}, map length {2}, entry {3}, depth {4}, descriptor {5}")
    @CsvSource({"2,2,0,0,24,0", "0,2,4,0,24,0", "0,2,0,8,24,0", "1,1,0,24,8,0", "1,1,16,8,8,0",
            "0,1,0,0,8,0", "1,1,16,24,24,0", "0,2,0,0,8,0", "1,3,16,24,8,0", "0,3,0,0,24,0", "0,4,0,0,24,0",
            "0,2,0,0,24,192", "0,2,0,0,24,9", "0,2,0,0,8,8"})
    @DisplayName("refuses TGA headers whose fields disagree")
    void tgaInvalid(int mapType, int imageType, int mapLength, int entryBits, int depth, int descriptor) {
        var header = tga(mapType, imageType, mapLength, entryBits, depth, descriptor);
        assertNotEquals(Optional.of(ImageFormat.TGA), ImageFormat.detect(header, header.length));
    }

    @Test
    @DisplayName("refuses TGA headers without dimensions or a zero color map origin")
    void tgaDimensions() {
        var header = tga(0, 2, 0, 0, 24, 0);
        header[3] = 1;
        assertNotEquals(Optional.of(ImageFormat.TGA), ImageFormat.detect(header, header.length));
        var noWidth = tga(0, 2, 0, 0, 24, 0);
        noWidth[12] = 0;
        assertNotEquals(Optional.of(ImageFormat.TGA), ImageFormat.detect(noWidth, noWidth.length));
        var noHeight = tga(0, 2, 0, 0, 24, 0);
        noHeight[14] = 0;
        assertNotEquals(Optional.of(ImageFormat.TGA), ImageFormat.detect(noHeight, noHeight.length));
    }

    @Test
    @DisplayName("needs an icon's data size and offset past the directory, and an ICNS size of at least eight")
    void sizes() {
        var icon = header(32, 0, 0, 1, 0, 2, 0, 16, 16, 0, 0, 1, 0, 32, 0, 40, 0, 0, 0, 38);
        assertEquals(Optional.of(ImageFormat.ICO), ImageFormat.detect(icon, icon.length));
        var noSize = icon.clone();
        noSize[14] = 0;
        assertNotEquals(Optional.of(ImageFormat.ICO), ImageFormat.detect(noSize, noSize.length));
        var inside = icon.clone();
        inside[18] = 37;
        assertNotEquals(Optional.of(ImageFormat.ICO), ImageFormat.detect(inside, inside.length));
        var icns = header(32, "icns", 0, 0, 0, 7);
        assertNotEquals(Optional.of(ImageFormat.ICNS), ImageFormat.detect(icns, icns.length));
    }

    @Test
    @DisplayName("tells Motion JPEG 2000 from JPEG 2000, and HEIF brands from a minor version")
    void containers() {
        var motion = header(32, 0, 0, 0, 0x0C, "jP  ", 0x0D, 0x0A, 0x87, 0x0A, new int[]{16}, "ftypmjp2");
        assertNotEquals(Optional.of(ImageFormat.JP2), ImageFormat.detect(motion, motion.length));
        var motionSimple = header(32, 0, 0, 0, 0x0C, "jP  ", 0x0D, 0x0A, 0x87, 0x0A, new int[]{16}, "ftypmj2s");
        assertNotEquals(Optional.of(ImageFormat.JP2), ImageFormat.detect(motionSimple, motionSimple.length));
        var otherBrand = header(32, 0, 0, 0, 0x0C, "jP  ", 0x0D, 0x0A, 0x87, 0x0A, new int[]{16}, "ftypmjxx");
        assertEquals(Optional.of(ImageFormat.JP2), ImageFormat.detect(otherBrand, otherBrand.length));
        var minor = header(32, 0, 0, 0, 16, "ftypisom", "avif");
        assertEquals(Optional.empty(), ImageFormat.detect(minor, minor.length));
        var smallBox = header(32, 0, 0, 0, 4, "ftypisom", 0, 0, 0, 0, "heix");
        assertEquals(Optional.of(ImageFormat.HEIC), ImageFormat.detect(smallBox, smallBox.length));
        for (var brand : new String[]{"heim", "heis", "hevc", "hevx", "mif2", "msf1", "miaf", "avci", "avcs", "jpeg",
                "jpgs", "j2ki", "j2is"}) {
            var compatible = header(32, 0, 0, 0, 20, "ftypisom", 0, 0, 0, 0, brand);
            assertEquals(Optional.of(ImageFormat.HEIC), ImageFormat.detect(compatible, compatible.length), brand);
        }
        assertEquals(Optional.of(ImageFormat.AVIF), ImageFormat.detect(header(32, 0, 0, 0, 20, "ftypavis"), 32));
    }

    @Test
    @DisplayName("names every Netpbm variant and refuses other digits")
    void pnm() {
        for (var kind : "1234567FfHh".toCharArray()) {
            var header = header(32, "P" + kind + "\t");
            assertEquals(Optional.of(ImageFormat.PNM), ImageFormat.detect(header, header.length), "P" + kind);
        }
        var eight = header(32, "P8 ");
        assertEquals(Optional.empty(), ImageFormat.detect(eight, eight.length));
        var noSpace = header(32, "P6x");
        assertEquals(Optional.empty(), ImageFormat.detect(noSpace, noSpace.length));
        var jxr = header(32, "II", 0xBC, 2);
        assertEquals(Optional.empty(), ImageFormat.detect(jxr, jxr.length), "JPEG XR version 2");
        var lower = header(32, "#?r");
        assertEquals(Optional.empty(), ImageFormat.detect(lower, lower.length));
    }

    @Test
    @DisplayName("checks the length it is given")
    void bounds() {
        assertThrows(IndexOutOfBoundsException.class, () -> ImageFormat.detect(new byte[4], 5));
        assertThrows(IndexOutOfBoundsException.class, () -> ImageFormat.detect(new byte[4], -1));
        assertThrows(NullPointerException.class, () -> ImageFormat.detect(null, 0));
        assertEquals(Optional.empty(), ImageFormat.detect(new byte[0], 0));
    }
}
