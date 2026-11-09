package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.avif.AvifStructureTest.Coded;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.image.avif.AvifStructureTest.encode;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.still;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.tile;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AVIF alpha planes of another size or depth than their color image, in still images,
 * grids and sequences, deep sequence frames and the XMP item, on files built box by box around
 * libaom-coded payloads and written by {@link AvifEncoder}. Resampled alpha is checked against
 * {@link ImageOps#resize} of the alpha payload decoded on its own, with the triangle filter.
 */
@DisplayName("AVIF alpha, depth and XMP")
class AvifAlphaDepthTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    /** An alpha payload: luma made of a gradient, gray at 8 or 10 bits. */
    private static Coded alpha(int width, int height, String pixelFormat) {
        return encode("scale=" + width + ":" + height + ",format=gray,geq=lum='X*5+Y*2'", width, height, pixelFormat);
    }

    /** The luma of an alpha payload decoded on its own, at its depth, and its range. */
    private static Picture standalone(Coded coded) {
        var builder = new HeifBuilder();
        builder.primary(still(builder, coded));
        return AvifDecoder.decode(MediaSource.of(builder.build()), WIDE).picture();
    }

    /** The full-range alpha samples an alpha payload gives once resampled to a size, at its depth. */
    private static int[] resampled(Coded coded, int width, int height) {
        var picture = standalone(coded);
        var mono = switch (picture) {
            case YuvPicture yuv -> YuvPicture.builder().size(yuv.width(), yuv.height())
                    .chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG).luma(yuv.y(), yuv.yStride()).build();
            case Yuv16Picture yuv -> Yuv16Picture.builder().size(yuv.width(), yuv.height())
                    .chromaFormat(ChromaFormat.MONOCHROME).bitDepth(yuv.bitDepth()).color(ColorInfo.JPEG)
                    .luma(yuv.y(), yuv.yStride()).build();
            default -> throw new AssertionError(picture);
        };
        var limited = switch (picture) {
            case YuvPicture yuv -> yuv.color().range() == ColorRange.LIMITED;
            case Yuv16Picture yuv -> yuv.color().range() == ColorRange.LIMITED;
            default -> false;
        };
        var planes = AvifReference.Planes.of(ImageOps.resize(mono, width, height, ResizeFilter.BILINEAR));
        var out = planes.planes()[0].clone();
        if (limited) {
            for (var i = 0; i < out.length; i++) {
                out[i] = AvifReference.limitedToFull(out[i], picture.bitDepth());
            }
        }
        return out;
    }

    private static int[] rescale(int[] samples, int from, int to) {
        var fromMax = (1 << from) - 1;
        var toMax = (1 << to) - 1;
        var out = new int[samples.length];
        for (var i = 0; i < out.length; i++) {
            out[i] = (int) (((long) samples[i] * toMax + fromMax / 2) / fromMax);
        }
        return out;
    }

    private static int[] alphaOf(Picture picture) {
        return AvifReference.Planes.of(picture).planes()[3];
    }

    private static int alphaItem(HeifBuilder builder, Coded coded, int master) {
        var id = builder.item("av01", coded.payload(), plain(HeifBuilder.ispe(coded.width(), coded.height())),
                essential(HeifBuilder.av1C(coded.config())), essential(HeifBuilder.alpha()));
        builder.reference("auxl", id, master).hidden(id);
        return id;
    }

    @Nested
    @DisplayName("still images")
    class Stills {
        @Test
        @DisplayName("resample a half-size alpha item to the color size")
        void halfSize() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alpha(32, 32, "gray");
            var builder = new HeifBuilder();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var decoded = AvifDecoder.decode(MediaSource.of(builder.build())).picture();
            assertArrayEquals(resampled(alpha, 64, 64), alphaOf(decoded));
        }

        @Test
        @DisplayName("rescale 8-bit alpha to 10-bit color, resampled, and reduce it back in the 8-bit decode")
        void eightUnderTen() {
            var color = tile(0, 0, "yuv420p10le");
            var alpha = alpha(40, 24, "gray");
            var builder = new HeifBuilder();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var data = builder.build();
            var wide = assertInstanceOf(Yuv16Picture.class, AvifDecoder.decode(MediaSource.of(data), WIDE).picture());
            assertEquals(10, wide.bitDepth());
            assertArrayEquals(rescale(resampled(alpha, 64, 64), 8, 10), alphaOf(wide));
            var narrow = AvifDecoder.decode(MediaSource.of(data)).picture();
            assertArrayEquals(alphaOf(ImageOps.toEightBit(wide)), alphaOf(narrow));
        }

        @Test
        @DisplayName("rescale 10-bit alpha of the color size to 8-bit color")
        void tenUnderEight() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alpha(64, 64, "gray10le");
            var builder = new HeifBuilder();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var decoded = AvifDecoder.decode(MediaSource.of(builder.build()), WIDE).picture();
            assertArrayEquals(rescale(resampled(alpha, 64, 64), 10, 8), alphaOf(decoded));
        }

        @Test
        @DisplayName("resample the half-size alpha item of each grid tile to the tile")
        void perTile() {
            var colors = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"));
            var alphas = List.of(alpha(32, 32, "gray"), encode("scale=32:32,format=gray,geq=lum='255-X*6'", 32, 32,
                    "gray"));
            var builder = new HeifBuilder();
            var ids = new int[2];
            for (var i = 0; i < 2; i++) {
                ids[i] = still(builder, colors.get(i));
                builder.hidden(ids[i]);
                alphaItem(builder, alphas.get(i), ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 120, 64), plain(HeifBuilder.ispe(120, 64)));
            builder.reference("dimg", grid, ids).primary(grid);
            var decoded = alphaOf(AvifDecoder.decode(MediaSource.of(builder.build())).picture());
            var left = resampled(alphas.get(0), 64, 64);
            var right = resampled(alphas.get(1), 64, 64);
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 120; x++) {
                    var expected = x < 64 ? left[y * 64 + x] : right[y * 64 + x - 64];
                    assertEquals(expected, decoded[y * 120 + x], "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("resample an alpha grid whose output size differs from the color grid")
        void alphaGrid() {
            var colors = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"), tile(0, 64, "yuv420p"),
                    tile(64, 64, "yuv420p"));
            var alphas = List.of(alpha(64, 64, "gray"), encode("scale=64:64,format=gray,geq=lum='255-X*3'", 64, 64,
                    "gray"));
            var builder = new HeifBuilder();
            var colorIds = new int[4];
            for (var i = 0; i < 4; i++) {
                colorIds[i] = still(builder, colors.get(i));
                builder.hidden(colorIds[i]);
            }
            var alphaIds = new int[2];
            for (var i = 0; i < 2; i++) {
                alphaIds[i] = builder.item("av01", alphas.get(i).payload(), plain(HeifBuilder.ispe(64, 64)),
                        essential(HeifBuilder.av1C(alphas.get(i).config())));
                builder.hidden(alphaIds[i]);
            }
            var colorGrid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 128, 128), plain(HeifBuilder.ispe(128, 128)));
            builder.reference("dimg", colorGrid, colorIds);
            var auxGrid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 120, 64), plain(HeifBuilder.ispe(120, 64)),
                    essential(HeifBuilder.alpha()));
            builder.reference("dimg", auxGrid, alphaIds).reference("auxl", auxGrid, colorGrid).hidden(auxGrid)
                    .primary(colorGrid);
            var decoded = AvifDecoder.decode(MediaSource.of(builder.build())).picture();
            var assembled = new byte[120 * 64];
            var limited = false;
            for (var i = 0; i < 2; i++) {
                var tilePicture = (YuvPicture) standalone(alphas.get(i));
                limited = tilePicture.color().range() == ColorRange.LIMITED;
                for (var y = 0; y < 64; y++) {
                    for (var x = 0; x < 64 && i * 64 + x < 120; x++) {
                        assembled[y * 120 + i * 64 + x] = tilePicture.y()[y * tilePicture.yStride() + x];
                    }
                }
            }
            var mono = YuvPicture.builder().size(120, 64).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                    .luma(assembled, 120).build();
            var expected = AvifReference.Planes.of(ImageOps.resize(mono, 128, 128, ResizeFilter.BILINEAR)).planes()[0];
            if (limited) {
                for (var i = 0; i < expected.length; i++) {
                    expected[i] = AvifReference.limitedToFull(expected[i], 8);
                }
            }
            assertArrayEquals(expected, alphaOf(decoded));
        }
    }

    @Nested
    @DisplayName("sequences")
    class Sequences {
        private static byte[] sequence(List<Coded> colors, List<Coded> alphas) {
            var builder = new HeifSequenceBuilder("avis", 1000, "avis", "msf1", "iso8", "mif1", "miaf");
            var color = builder.track("av01", colors.getFirst().width(), colors.getFirst().height(), 100,
                    colors.stream().map(Coded::payload).toList(), HeifBuilder.av1C(colors.getFirst().config()));
            var alpha = builder.track("av01", alphas.getFirst().width(), alphas.getFirst().height(), 100,
                    alphas.stream().map(Coded::payload).toList(), HeifBuilder.av1C(alphas.getFirst().config()),
                    HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
            builder.reference("auxl", alpha, color);
            return builder.build();
        }

        @Test
        @DisplayName("resample half-size alpha frames of another depth to the color frames")
        void alphaFrames() {
            var colors = new ArrayList<Coded>();
            var alphas = new ArrayList<Coded>();
            for (var i = 0; i < 3; i++) {
                colors.add(encode("crop=64:64:" + (i * 16) + ":" + (i * 8), 64, 64, "yuv420p10le"));
                alphas.add(encode("scale=32:32,format=gray,geq=lum='X*7+Y*" + (i + 1) + "'", 32, 32, "gray"));
            }
            var data = sequence(colors, alphas);
            var first = assertInstanceOf(Yuv16Picture.class, AvifDecoder.decode(MediaSource.of(data), WIDE).picture());
            assertArrayEquals(rescale(resampled(alphas.getFirst(), 64, 64), 8, 10), alphaOf(first));
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data), WIDE)) {
                var index = 0;
                for (var frame = reader.read(); frame != null; frame = reader.read(), index++) {
                    var rgba = assertInstanceOf(Rgba16Picture.class, frame.picture());
                    var expected = rescale(rescale(resampled(alphas.get(index), 64, 64), 8, 10), 10, 16);
                    for (var i = 0; i < expected.length; i++) {
                        assertEquals(expected[i], rgba.pixels()[(i / 64) * rgba.stride() + 4 * (i % 64) + 3] & 0xFFFF,
                                "frame " + index + " pixel " + i);
                    }
                }
                assertEquals(3, index);
            }
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data))) {
                var frame = assertInstanceOf(RgbaPicture.class, reader.read().picture());
                var expected = resampled(alphas.getFirst(), 64, 64);
                for (var i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], frame.pixels()[(i / 64) * frame.stride() + 4 * (i % 64) + 3] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("play the 10-bit animations AvifEncoder writes as 16-bit RGBA under highBitDepth")
        void encoded() {
            var options = AvifEncoderOptions.builder().bitDepth(10).quality(80).speed(9).build();
            var sink = new java.io.ByteArrayOutputStream();
            var frames = new ArrayList<RgbaPicture>();
            for (var i = 0; i < 3; i++) {
                var picture = RgbaPicture.allocate(48, 32, true);
                for (var p = 0; p < 48 * 32; p++) {
                    picture.pixels()[4 * p] = (byte) (p * 3 + i * 40);
                    picture.pixels()[4 * p + 1] = (byte) (p / 48 * 7);
                    picture.pixels()[4 * p + 2] = (byte) (255 - p % 48 * 5);
                    picture.pixels()[4 * p + 3] = (byte) (p % 48 * 5 + i);
                }
                frames.add(picture);
            }
            try (var writer = AvifEncoder.openAnimationWriter(48, 32, options,
                    com.github.auties00.smalt.io.MediaSink.of(sink))) {
                for (var frame : frames) {
                    writer.write(frame, 100_000);
                }
                writer.finish();
            }
            var data = sink.toByteArray();
            var still = AvifDecoder.decode(MediaSource.of(data), WIDE).picture();
            assertInstanceOf(Yuv16Picture.class, still);
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data), WIDE)) {
                var first = assertInstanceOf(Rgba16Picture.class, reader.read().picture());
                assertTrue(first.hasAlpha());
                var expected = ImageOps.toRgba16(still);
                for (var y = 0; y < 32; y++) {
                    for (var x = 0; x < 4 * 48; x++) {
                        assertEquals(expected.pixels()[y * expected.stride() + x], first.pixels()[y * first.stride() + x]);
                    }
                }
                var count = 1;
                while (reader.read() != null) {
                    count++;
                }
                assertEquals(3, count);
            }
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data))) {
                var narrow = assertInstanceOf(RgbaPicture.class, reader.read().picture());
                assertArrayEquals(tight(ImageOps.toRgba(AvifDecoder.decode(MediaSource.of(data)).picture())), tight(narrow));
            }
        }

        private static byte[] tight(RgbaPicture picture) {
            var out = new byte[picture.width() * picture.height() * 4];
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), out, y * picture.width() * 4, picture.width() * 4);
            }
            return out;
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        @Test
        @DisplayName("raise only MediaException for truncated and mutated files with resampled alpha")
        void mutations() {
            var builder = new HeifBuilder();
            var ids = new int[2];
            for (var i = 0; i < 2; i++) {
                ids[i] = still(builder, tile(i * 64, 0, "yuv420p10le"));
                builder.hidden(ids[i]);
                alphaItem(builder, alpha(32, 32, "gray"), ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), plain(HeifBuilder.ispe(128, 64)));
            builder.reference("dimg", grid, ids).primary(grid);
            var seed = builder.build();
            var limits = com.github.auties00.smalt.MediaLimits.builder().maxPixels(1 << 20).maxBytes(16L << 20).build();
            var options = ImageDecoderOptions.builder().limits(limits).highBitDepth(true).build();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofMinutes(4), () -> {
                var random = new java.util.Random(37);
                var outcomes = new int[2];
                for (var round = 0; round < 200; round++) {
                    var mutated = round < 60 ? java.util.Arrays.copyOf(seed, seed.length * round / 60) : seed.clone();
                    if (round >= 60) {
                        for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                            mutated[random.nextInt(Math.min(mutated.length, round % 2 == 0 ? 1200 : mutated.length))] =
                                    (byte) random.nextInt(256);
                        }
                    }
                    try {
                        AvifDecoder.decode(MediaSource.of(mutated), options);
                        outcomes[0]++;
                    } catch (com.github.auties00.smalt.MediaException expected) {
                        outcomes[1]++;
                    }
                }
                assertTrue(outcomes[0] > 0 && outcomes[1] > 0, "decoded " + outcomes[0] + ", rejected " + outcomes[1]);
            });
        }
    }

    @Nested
    @DisplayName("XMP")
    class Xmp {
        @Test
        @DisplayName("round-trip the XMP packet AvifEncoder embeds, and report none without one")
        void roundTrip() {
            var packet = ("<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                    + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>caf" + (char) 0xE9
                    + "</dc:title></rdf:Description></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
            var picture = RgbaPicture.allocate(16, 16, false);
            var encoded = AvifEncoder.encode(picture, AvifEncoderOptions.builder().xmp(packet).speed(9).build());
            var decoded = AvifDecoder.decode(MediaSource.of(encoded));
            assertArrayEquals(packet, decoded.xmp().orElseThrow());
            var srgb = AvifDecoder.decode(MediaSource.of(encoded), ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertArrayEquals(packet, srgb.xmp().orElseThrow());
            assertFalse(AvifDecoder.decode(MediaSource.of(AvifEncoder.encode(picture))).xmp().isPresent());
        }
    }
}
