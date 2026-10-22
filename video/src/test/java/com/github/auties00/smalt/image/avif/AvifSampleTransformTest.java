package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.image.avif.AvifStructureTest.still;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sample transform derived images of AVIF 1.2 ({@code sato}): 16-bit images split into
 * the 8 most and 8 least significant bits of each sample, coded losslessly by libaom through ffmpeg,
 * must decode to the original samples; libavif's own 12-bit plus 8-bit test file must decode to the
 * expression applied to its two inputs decoded on their own, and close to the 16-bit PNG it was
 * encoded from; and the alternatives group must fall back to the primary item when the transform
 * uses a feature the decoder lacks.
 */
@DisplayName("AVIF sample transforms")
class AvifSampleTransformTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    /** The 16-bit sample the lossless fixtures split, for a plane index: luma 0, chroma 1 and 2. */
    private static int sample(int plane, int x, int y) {
        return switch (plane) {
            case 0 -> (2 * x + y) * 256 + (7 * x + 13 * y) % 256;
            case 1 -> (x + 3 * y) * 256 + (5 * x + 11 * y + 40) % 256;
            default -> (200 - x - y) * 256 + (3 * x * y) % 256;
        };
    }

    /** Encodes one half of the split samples losslessly; {@code msb} selects the most significant bits. */
    private static AvifStructureTest.Coded half(boolean msb, boolean color) {
        var luma = msb ? "2*X+Y" : "mod(7*X+13*Y,256)";
        var cb = msb ? "X+3*Y" : "mod(5*X+11*Y+40,256)";
        var cr = msb ? "200-X-Y" : "mod(3*X*Y,256)";
        var format = color ? "yuv444p" : "gray";
        var filter = "format=" + format + ",geq=lum='" + luma + "'" + (color ? ":cb='" + cb + "':cr='" + cr + "'" : "");
        var path = Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i", "color=black:size=64x48:rate=1"),
                List.of("-frames:v", "1", "-vf", filter, "-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8",
                        "-aom-params", "lossless=1", "-pix_fmt", format));
        var data = Fixtures.read(path);
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var primary = file.primaryItem();
        var config = primary.property(HeifProperty.Av1Configuration.class).record();
        return new AvifStructureTest.Coded(file.readItem(source, primary, MediaLimits.DEFAULT), config, 64, 48);
    }

    /**
     * Builds a file whose primary item is the most significant half, with a hidden least significant
     * half and a {@code sato} item of the given payload; {@code altr} lists the transform before the
     * primary item.
     */
    private static byte[] split(boolean color, byte[] payload, boolean altr, boolean primaryTransform,
                                HeifBuilder.Property... satoProperties) {
        var builder = new HeifBuilder();
        var msb = still(builder, half(true, color));
        var lsb = still(builder, half(false, color));
        builder.hidden(lsb);
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(64, 48)),
                plain(color ? HeifBuilder.pixi(16, 16, 16) : HeifBuilder.pixi(16))));
        properties.addAll(List.of(satoProperties));
        var sato = builder.idatItem("sato", payload, properties.toArray(HeifBuilder.Property[]::new));
        builder.reference("dimg", sato, msb, lsb);
        builder.primary(primaryTransform ? sato : msb);
        if (altr) {
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, sato, msb)));
        } else if (!primaryTransform) {
            builder.hidden(sato);
        }
        return builder.build();
    }

    /** {@code (msb << 8) | lsb}, libavif's 8-bit plus 8-bit bit depth extension. */
    private static final byte[] EIGHT_EIGHT = HeifBuilder.sato(2, 0, 256, 1, 130, 2, 133);

    @Nested
    @DisplayName("lossless 16-bit images")
    class Lossless {
        @Test
        @DisplayName("reconstruct every 16-bit luma sample of a monochrome image shown through its alternatives group")
        void monochrome() {
            var data = split(false, EIGHT_EIGHT, true, false);
            var picture = assertInstanceOf(Yuv16Picture.class, AvifDecoder.decode(MediaSource.of(data), WIDE).picture());
            assertEquals(16, picture.bitDepth());
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
            assertEquals(64, picture.width());
            assertEquals(48, picture.height());
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    assertEquals(sample(0, x, y), picture.y()[y * picture.yStride() + x] & 0xFFFF, x + "," + y);
                }
            }
            var narrow = assertInstanceOf(YuvPicture.class, AvifDecoder.decode(MediaSource.of(data)).picture());
            assertArrayEquals(((YuvPicture) ImageOps.toEightBit(picture)).y(), narrow.y());
            var info = AvifDecoder.probe(MediaSource.of(data));
            assertEquals(64, info.width());
            assertFalse(info.hasAlpha());
        }

        @Test
        @DisplayName("reconstruct the luma and chroma planes of a 4:4:4 image whose primary item is the transform")
        void color() {
            var picture = assertInstanceOf(Yuv16Picture.class,
                    AvifDecoder.decode(MediaSource.of(split(true, EIGHT_EIGHT, false, true)), WIDE).picture());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            short[][] planes = {picture.y(), picture.u(), picture.v()};
            int[] strides = {picture.yStride(), picture.uvStride(), picture.uvStride()};
            for (var plane = 0; plane < 3; plane++) {
                for (var y = 0; y < 48; y++) {
                    for (var x = 0; x < 64; x++) {
                        assertEquals(sample(plane, x, y), planes[plane][y * strides[plane] + x] & 0xFFFF,
                                "plane " + plane + " at " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("clamp the luma to the limited range when the transform's nclx property says so")
        void limited() {
            var data = split(false, HeifBuilder.sato(2, 0, 256, 1, 130, 0, 60000, 136), false, true,
                    plain(HeifBuilder.nclx(1, 13, 6, false)));
            var picture = assertInstanceOf(Yuv16Picture.class, AvifDecoder.decode(MediaSource.of(data), WIDE).picture());
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    var expected = Math.clamp(Math.min(60000, (2 * x + y) * 256), 16 * 256, 235 * 256);
                    assertEquals(expected, picture.y()[y * picture.yStride() + x] & 0xFFFF);
                }
            }
        }

        @Test
        @DisplayName("decode to the depth of the pixi property, here 10 bits")
        void tenBits() {
            var builder = new HeifBuilder();
            var msb = still(builder, half(true, false));
            var sato = builder.idatItem("sato", HeifBuilder.sato(2, 1, 0, 4, 130), plain(HeifBuilder.ispe(64, 48)),
                    plain(HeifBuilder.pixi(10)));
            builder.reference("dimg", sato, msb).primary(sato);
            var picture = assertInstanceOf(Yuv16Picture.class,
                    AvifDecoder.decode(MediaSource.of(builder.build()), WIDE).picture());
            assertEquals(10, picture.bitDepth());
            assertEquals(Math.min(1023, (2 * 5 + 7) * 4), picture.y()[7 * picture.yStride() + 5]);
        }
    }

    @Nested
    @DisplayName("alternatives")
    class Alternatives {
        private YuvPicture primaryAlone() {
            var builder = new HeifBuilder();
            builder.primary(still(builder, half(true, false)));
            return assertInstanceOf(YuvPicture.class, AvifDecoder.decode(MediaSource.of(builder.build()), WIDE)
                    .picture());
        }

        @Test
        @DisplayName("show the primary item when the transform has a reserved token or an unknown version")
        void unsupported() {
            var expected = primaryAlone();
            var reserved = HeifBuilder.sato(2, 0, 256, 1, 130, 2, 200);
            // a future nullary operator: the operator after it would underflow the stack without it
            var nullary = HeifBuilder.sato(2, 1, 200, 133);
            var version = EIGHT_EIGHT.clone();
            version[0] |= 0x40;
            for (var payload : List.of(reserved, nullary, version)) {
                var data = split(false, payload, true, false);
                var shown = assertInstanceOf(YuvPicture.class, AvifDecoder.decode(MediaSource.of(data), WIDE).picture());
                assertArrayEquals(expected.y(), shown.y());
                assertEquals(64, AvifDecoder.probe(MediaSource.of(data)).width());
            }
            var primaryReserved = split(false, reserved, false, true);
            assertThrows(MediaException.Unsupported.class, () -> AvifDecoder.decode(MediaSource.of(primaryReserved)));
        }

        @Test
        @DisplayName("show the primary item when no alternatives group prefers the transform, or it is a thumbnail")
        void notPreferred() {
            var expected = primaryAlone();
            var ungrouped = split(false, EIGHT_EIGHT, false, false);
            assertArrayEquals(expected.y(), assertInstanceOf(YuvPicture.class,
                    AvifDecoder.decode(MediaSource.of(ungrouped), WIDE).picture()).y());
            // a transform item without data is no candidate
            var empty = split(false, new byte[0], true, false);
            assertArrayEquals(expected.y(), assertInstanceOf(YuvPicture.class,
                    AvifDecoder.decode(MediaSource.of(empty), WIDE).picture()).y());
            var builder = new HeifBuilder();
            var msb = still(builder, half(true, false));
            var lsb = still(builder, half(false, false));
            builder.hidden(lsb);
            var sato = builder.idatItem("sato", EIGHT_EIGHT, plain(HeifBuilder.ispe(64, 48)),
                    plain(HeifBuilder.pixi(16)));
            builder.reference("dimg", sato, msb, lsb).reference("thmb", sato, msb).primary(msb);
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, sato, msb)));
            assertArrayEquals(expected.y(), assertInstanceOf(YuvPicture.class,
                    AvifDecoder.decode(MediaSource.of(builder.build()), WIDE).picture()).y());
        }

        @Test
        @DisplayName("reject a malformed transform the group prefers")
        void malformed() {
            var leftover = HeifBuilder.sato(2, 1, 2);
            assertThrows(MediaException.Malformed.class,
                    () -> AvifDecoder.decode(MediaSource.of(split(false, leftover, true, false))));
            var noPixi = new HeifBuilder();
            var msb = still(noPixi, half(true, false));
            var sato = noPixi.idatItem("sato", HeifBuilder.sato(2, 1), plain(HeifBuilder.ispe(64, 48)));
            noPixi.reference("dimg", sato, msb).primary(sato);
            assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decode(MediaSource.of(noPixi.build())));
            var wrongSize = new HeifBuilder();
            var input = still(wrongSize, half(true, false));
            var wrong = wrongSize.idatItem("sato", HeifBuilder.sato(2, 1), plain(HeifBuilder.ispe(32, 48)),
                    plain(HeifBuilder.pixi(16)));
            wrongSize.reference("dimg", wrong, input).primary(wrong);
            assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decode(MediaSource.of(wrongSize.build())));
        }
    }

    @Nested
    @DisplayName("libavif's weld_sato_12B_8B_q0.avif")
    class Weld {
        private static final String VECTOR = "avif-libavif-weld_sato_12B_8B_q0.avif";
        private static final String REFERENCE = "avif-libavif-weld_16bit.png";

        /** Re-wraps one coded item of the file as the primary item of a file of its own. */
        private static AvifReference.Planes alone(MediaSource source, HeifFile file, HeifItem item) {
            var builder = new HeifBuilder();
            var properties = new ArrayList<HeifBuilder.Property>();
            var extent = item.property(HeifProperty.SpatialExtent.class);
            properties.add(plain(HeifBuilder.ispe((int) extent.width(), (int) extent.height())));
            properties.add(essential(HeifBuilder.av1C(item.property(HeifProperty.Av1Configuration.class).record())));
            builder.primary(builder.item("av01", file.readItem(source, item, MediaLimits.DEFAULT),
                    properties.toArray(HeifBuilder.Property[]::new)));
            return AvifReference.Planes.of(AvifDecoder.decode(MediaSource.of(builder.build()), WIDE).picture());
        }

        @Test
        @DisplayName("decode to 16 bits as 16 * base + hidden - 128 of its two inputs decoded on their own")
        void handComposition() throws IOException {
            var data = Files.readAllBytes(Conformance.fetchListed(VECTOR));
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var sato = file.items().stream().filter(item -> item.type().equals("sato")).findFirst().orElseThrow();
            var inputs = sato.references("dimg");
            assertEquals(2, inputs.length);
            var base = alone(source, file, file.item(inputs[0]));
            var hidden = alone(source, file, file.item(inputs[1]));
            assertEquals(12, base.depth());
            assertEquals(8, hidden.depth());
            var decoded = assertInstanceOf(Yuv16Picture.class, AvifDecoder.decode(source, WIDE).picture());
            var actual = AvifReference.Planes.of(decoded);
            assertEquals(16, actual.depth());
            assertEquals(base.width(), actual.width());
            for (var plane = 0; plane < 3; plane++) {
                var a = base.planes()[plane];
                var b = hidden.planes()[plane];
                var c = actual.planes()[plane];
                for (var i = 0; i < c.length; i++) {
                    assertEquals(Math.clamp(16L * a[i] + b[i] - 128, 0, 65535), c[i], "plane " + plane + " sample " + i);
                }
            }
            var narrow = AvifDecoder.decode(source).picture();
            AvifConformanceTest.assertPlanesEqual(AvifReference.Planes.of(ImageOps.toEightBit(decoded)),
                    AvifReference.Planes.of(narrow), "8-bit output");
        }

        @Test
        @DisplayName("decode closer to the 16-bit PNG it was encoded from than its 12-bit base image alone")
        void againstSource() throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = Files.readAllBytes(Conformance.fetchListed(VECTOR));
            var source = MediaSource.of(data);
            var decoded = assertInstanceOf(Yuv16Picture.class, AvifDecoder.decode(source, WIDE).picture());
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var sato = file.items().stream().filter(item -> item.type().equals("sato")).findFirst().orElseThrow();
            var base = alone(source, file, file.item(sato.references("dimg")[0]));
            var baseOnly = Yuv16Picture.allocate(decoded.width(), decoded.height(), decoded.chromaFormat(), 16, false,
                    decoded.color());
            short[][] planes = {baseOnly.y(), baseOnly.u(), baseOnly.v()};
            for (var plane = 0; plane < 3; plane++) {
                for (var i = 0; i < planes[plane].length; i++) {
                    planes[plane][i] = (short) (16 * base.planes()[plane][i]);
                }
            }
            var raw = Files.createTempFile("weld", ".rgb48");
            try {
                Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-i",
                        Conformance.fetchListed(REFERENCE).toString(), "-f", "rawvideo", "-pix_fmt", "rgb48le",
                        raw.toString()));
                var reference = Files.readAllBytes(raw);
                var transformed = psnr(ImageOps.toRgba16(decoded), reference);
                var baseline = psnr(ImageOps.toRgba16(baseOnly), reference);
                // libavif wrote the file at quality 0, so both are far from the source; the hidden image refines
                assertTrue(transformed > baseline && transformed > 20, "PSNR " + transformed
                        + " dB against the source PNG, " + baseline + " dB for the base image alone");
            } finally {
                Files.deleteIfExists(raw);
            }
        }

        private static double psnr(Rgba16Picture rgb, byte[] reference) {
            assertEquals(6L * rgb.width() * rgb.height(), reference.length);
            double error = 0;
            for (var y = 0; y < rgb.height(); y++) {
                for (var x = 0; x < rgb.width(); x++) {
                    for (var c = 0; c < 3; c++) {
                        var offset = 6 * (y * rgb.width() + x) + 2 * c;
                        var expected = (reference[offset] & 0xFF) | (reference[offset + 1] & 0xFF) << 8;
                        var difference = (rgb.pixels()[y * rgb.stride() + 4 * x + c] & 0xFFFF) - expected;
                        error += (double) difference * difference;
                    }
                }
            }
            return 10 * Math.log10(65535.0 * 65535.0 / (error / (3.0 * rgb.width() * rgb.height())));
        }
    }
}
