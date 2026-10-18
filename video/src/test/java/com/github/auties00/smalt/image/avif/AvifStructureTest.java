package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds AVIF files box by box around AV1 stills encoded by ffmpeg (libaom) and checks the
 * reconstruction rules: grid stitching, alpha items of every shape, premultiplied alpha, the
 * transformative properties in any order, and the failures hostile structures must raise.
 */
@DisplayName("AVIF structures")
class AvifStructureTest {
    /** Generous for a 2x2 grid of small tiles, small enough to keep mutated files cheap. */
    private static final MediaLimits SMALL = MediaLimits.builder().maxPixels(1 << 22).maxBytes(64L << 20).build();

    /** A coded AV1 still: its payload and av1C record. */
    record Coded(byte[] payload, byte[] config, int width, int height) {
    }

    /** Encodes a still with libaom through ffmpeg and takes its item apart. */
    static Coded encode(String filter, int width, int height, String pixelFormat) {
        var path = Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i", "testsrc2=size=128x128:rate=1"),
                List.of("-frames:v", "1", "-vf", filter + ",format=" + pixelFormat, "-c:v", "libaom-av1",
                        "-still-picture", "1", "-cpu-used", "8", "-crf", "20"));
        var data = Fixtures.read(path);
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var primary = file.primaryItem();
        var config = primary.property(HeifProperty.Av1Configuration.class).record();
        return new Coded(file.readItem(source, primary, MediaLimits.DEFAULT), config, width, height);
    }

    static Coded tile(int x, int y, String pixelFormat) {
        return encode("crop=64:64:" + x + ":" + y, 64, 64, pixelFormat);
    }

    static Coded alphaPlane(int width, int height) {
        return encode("scale=" + width + ":" + height + ",format=gray,geq=lum='X*4+Y'", width, height, "gray");
    }

    static int still(HeifBuilder builder, Coded coded, HeifBuilder.Property... extra) {
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(coded.width(), coded.height())),
                essential(HeifBuilder.av1C(coded.config())), plain(HeifBuilder.pixi(8, 8, 8))));
        properties.addAll(List.of(extra));
        return builder.item("av01", coded.payload(), properties.toArray(HeifBuilder.Property[]::new));
    }

    static YuvPicture decode(byte[] data) {
        return assertInstanceOf(YuvPicture.class, AvifDecoder.decode(MediaSource.of(data)).picture());
    }

    static YuvPicture decode(Coded coded) {
        var builder = new HeifBuilder();
        builder.primary(still(builder, coded));
        return decode(builder.build());
    }

    @Nested
    @DisplayName("grids")
    class Grids {
        @ParameterizedTest(name = "{0}")
        @DisplayName("stitch the decoded tiles and crop them to the output size")
        @ValueSource(strings = {"yuv420p", "yuv444p", "yuv420p10le"})
        void stitch(String pixelFormat) {
            var tiles = List.of(tile(0, 0, pixelFormat), tile(64, 0, pixelFormat), tile(0, 64, pixelFormat),
                    tile(64, 64, pixelFormat));
            var builder = new HeifBuilder();
            var ids = new int[4];
            for (var i = 0; i < 4; i++) {
                ids[i] = still(builder, tiles.get(i));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 120, 100), plain(HeifBuilder.ispe(120, 100)));
            builder.reference("dimg", grid, ids).primary(grid);
            var decoded = AvifDecoder.decode(MediaSource.of(builder.build()), ImageDecoderOptions.builder()
                    .highBitDepth(true).build());
            var actual = AvifReference.Planes.of(decoded.picture());
            assertEquals(120, actual.width());
            assertEquals(100, actual.height());
            for (var i = 0; i < 4; i++) {
                var single = new HeifBuilder();
                single.primary(still(single, tiles.get(i)));
                var tilePlanes = AvifReference.Planes.of(AvifDecoder.decode(MediaSource.of(single.build()),
                        ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
                var x0 = (i % 2) * 64;
                var y0 = (i / 2) * 64;
                var expected = AvifReference.crop(tilePlanes, 0, 0, Math.min(64, 120 - x0), Math.min(64, 100 - y0));
                var region = AvifReference.crop(actual, x0, y0, expected.width(), expected.height());
                AvifConformanceTest.assertPlanesEqual(expected, region, "tile " + i);
            }
        }

        @Test
        @DisplayName("assemble an alpha plane from one auxiliary item per tile")
        void perTileAlpha() {
            var colors = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"));
            var alpha = alphaPlane(64, 64);
            var builder = new HeifBuilder();
            var ids = new int[2];
            for (var i = 0; i < 2; i++) {
                ids[i] = still(builder, colors.get(i));
                var aux = builder.item("av01", alpha.payload(), plain(HeifBuilder.ispe(64, 64)),
                        essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
                builder.reference("auxl", aux, ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64));
            builder.reference("dimg", grid, ids).primary(grid);
            var picture = decode(builder.build());
            assertTrue(picture.hasAlpha());
            var tileAlpha = alphaOf(alpha);
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 128; x++) {
                    assertEquals(tileAlpha[y * 64 + (x % 64)], picture.alpha()[y * picture.alphaStride() + x] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("reject wrong input counts, non-coded inputs, small tiles and oversized outputs")
        void hostile() {
            var coded = tile(0, 0, "yuv420p");
            var wrongCount = new HeifBuilder();
            var t = still(wrongCount, coded);
            var g = wrongCount.idatItem("grid", HeifBuilder.grid(2, 2, 128, 128));
            wrongCount.reference("dimg", g, t).primary(g);
            var countMismatch = wrongCount.build();
            assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decode(MediaSource.of(countMismatch)));
            var nested = new HeifBuilder();
            var inner = nested.idatItem("grid", HeifBuilder.grid(1, 1, 64, 64));
            var outer = nested.idatItem("grid", HeifBuilder.grid(1, 1, 64, 64));
            nested.reference("dimg", inner, outer).reference("dimg", outer, inner).primary(outer);
            var cyclic = nested.build();
            assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decode(MediaSource.of(cyclic)));
            var huge = new HeifBuilder();
            var tiles = new int[4];
            for (var i = 0; i < 4; i++) {
                tiles[i] = still(huge, coded);
            }
            var big = huge.idatItem("grid", HeifBuilder.grid(2, 2, 100_000, 100_000));
            huge.reference("dimg", big, tiles).primary(big);
            var oversized = huge.build();
            assertThrows(MediaException.LimitExceeded.class, () -> AvifDecoder.decode(MediaSource.of(oversized)));
            assertThrows(MediaException.Malformed.class, () -> {
                var small = new HeifBuilder();
                var a = still(small, coded);
                var b = still(small, coded);
                var grid = small.idatItem("grid", HeifBuilder.grid(1, 2, 300, 64));
                small.reference("dimg", grid, a, b).primary(grid);
                AvifDecoder.decode(MediaSource.of(small.build()));
            });
        }
    }

    private static int[] alphaOf(Coded alpha) {
        var builder = new HeifBuilder();
        builder.primary(still(builder, alpha));
        var picture = decode(builder.build());
        var out = new int[alpha.width() * alpha.height()];
        var limited = picture.color().range() != com.github.auties00.smalt.image.ColorRange.FULL;
        for (var y = 0; y < alpha.height(); y++) {
            for (var x = 0; x < alpha.width(); x++) {
                var value = picture.y()[y * picture.yStride() + x] & 0xFF;
                out[y * alpha.width() + x] = limited ? AvifReference.limitedToFull(value, 8) : value;
            }
        }
        return out;
    }

    @Nested
    @DisplayName("alpha")
    class Alpha {
        @Test
        @DisplayName("attach the alpha item as the alpha plane, expanded to full range")
        void straight() {
            var color = encode("scale=96:64", 96, 64, "yuv420p");
            var alpha = alphaPlane(96, 64);
            var builder = new HeifBuilder();
            var image = still(builder, color);
            var aux = builder.item("av01", alpha.payload(), plain(HeifBuilder.ispe(96, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
            builder.reference("auxl", aux, image).primary(image);
            var data = builder.build();
            var picture = decode(data);
            assertTrue(Images.probe(MediaSource.of(data)).hasAlpha());
            var expected = alphaOf(alpha);
            for (var i = 0; i < expected.length; i++) {
                assertEquals(expected[i], picture.alpha()[(i / 96) * picture.alphaStride() + i % 96] & 0xFF);
            }
            assertArrayEquals(AvifReference.Planes.of(decode(color)).planes()[0], AvifReference.Planes.of(picture).planes()[0]);
        }

        @Test
        @DisplayName("divide premultiplied color by its alpha into straight RGBA")
        void premultiplied() {
            var color = encode("scale=96:64", 96, 64, "yuv420p");
            var alpha = alphaPlane(96, 64);
            var builder = new HeifBuilder();
            var image = still(builder, color);
            var aux = builder.item("av01", alpha.payload(), plain(HeifBuilder.ispe(96, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
            builder.reference("auxl", aux, image).reference("prem", image, aux).primary(image);
            var picture = assertInstanceOf(RgbaPicture.class, AvifDecoder.decode(MediaSource.of(builder.build())).picture());
            var plain = decode(color);
            var straight = ImageOps.toRgba(plain).pixels();
            var expectedAlpha = alphaOf(alpha);
            for (var i = 0; i < 96 * 64; i++) {
                var a = expectedAlpha[i];
                assertEquals(a, picture.pixels()[i * 4 + 3] & 0xFF);
                for (var c = 0; c < 3; c++) {
                    var value = straight[i * 4 + c] & 0xFF;
                    var expected = a == 0 ? 0 : a == 255 ? value : Math.min(255, (value * 510 + a) / (2 * a));
                    assertEquals(expected, picture.pixels()[i * 4 + c] & 0xFF, "pixel " + i + " component " + c);
                }
            }
        }

        @Test
        @DisplayName("reject an alpha item of another size and ignore one with an unknown URN")
        void mismatched() {
            var color = encode("scale=96:64", 96, 64, "yuv420p");
            var alpha = alphaPlane(64, 64);
            var builder = new HeifBuilder();
            var image = still(builder, color);
            var aux = builder.item("av01", alpha.payload(), plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
            builder.reference("auxl", aux, image).primary(image);
            var data = builder.build();
            assertThrows(MediaException.Unsupported.class, () -> AvifDecoder.decode(MediaSource.of(data)));
            var depth = new HeifBuilder();
            var main = still(depth, color);
            var other = depth.item("av01", alpha.payload(), plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.auxC("urn:example:depth")));
            depth.reference("auxl", other, main).primary(main);
            assertFalse(decode(depth.build()).hasAlpha());
        }
    }

    @Nested
    @DisplayName("transformative properties")
    class Transforms {
        @Test
        @DisplayName("crop on chroma boundaries exactly and outside them into 4:4:4")
        void crop() {
            var coded = encode("scale=96:64", 96, 64, "yuv420p");
            var full = AvifReference.Planes.of(decode(coded));
            // A 40x30 aperture centered (offset + 2) samples right and 3 samples down of the image
            // center starts at column 30 + offset and row 20.
            for (var offset : new int[]{0, 1}) {
                var builder = new HeifBuilder();
                builder.primary(still(builder, coded, essential(HeifBuilder.clap(40, 1, 30, 1, 2 * offset + 4, 2, 6, 2))));
                var picture = decode(builder.build());
                var left = 30 + offset;
                var top = 20;
                var expected = AvifReference.crop(full, left, top, 40, 30);
                AvifConformanceTest.assertPlanesEqual(expected, AvifReference.Planes.of(picture), "offset " + offset);
                assertEquals(offset == 0 ? ChromaFormat.YUV420 : ChromaFormat.YUV444, picture.chromaFormat());
            }
        }

        @Test
        @DisplayName("report rotation and mirroring as the orientation in association order")
        void orientation() {
            var coded = encode("scale=96:64", 96, 64, "yuv420p");
            var builder = new HeifBuilder();
            builder.primary(still(builder, coded, essential(HeifBuilder.irot(3)), essential(HeifBuilder.imir(1))));
            var data = builder.build();
            var image = AvifDecoder.decode(MediaSource.of(data));
            // A quarter turn clockwise (irot 3), then a left-right swap (imir 1).
            assertEquals(Orientation.TRANSPOSE, image.orientation());
            assertEquals(Orientation.TRANSPOSE, Images.probe(MediaSource.of(data)).orientation());
            assertEquals(96, image.picture().width());
        }

        @Test
        @DisplayName("return the ICC profile and the EXIF block of the primary item")
        void metadata() {
            var coded = encode("scale=96:64", 96, 64, "yuv420p");
            var builder = new HeifBuilder();
            var image = still(builder, coded, plain(HeifBuilder.icc(new byte[]{1, 2, 3})),
                    plain(HeifBuilder.nclx(9, 16, 9, false)), plain(HeifBuilder.clli(1000, 400)));
            var exif = builder.item("Exif", new byte[]{0, 0, 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0});
            builder.reference("cdsc", exif, image).primary(image);
            var decoded = AvifDecoder.decode(MediaSource.of(builder.build()));
            assertArrayEquals(new byte[]{1, 2, 3}, decoded.iccProfile().orElseThrow());
            assertArrayEquals(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0}, decoded.exif().orElseThrow());
            var color = ((YuvPicture) decoded.picture()).color();
            assertEquals(com.github.auties00.smalt.image.MatrixCoefficients.BT2020_NCL, color.matrix());
            assertEquals(com.github.auties00.smalt.image.TransferCharacteristics.PQ, color.transfer());
            assertEquals(1000, color.contentLightLevel().orElseThrow().maxContentLightLevel());
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        @Test
        @DisplayName("reject missing primary items, other codecs and unknown essential properties")
        void unsupported() {
            var coded = encode("scale=96:64", 96, 64, "yuv420p");
            var none = new HeifBuilder();
            still(none, coded);
            var noPrimary = none.build();
            assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decode(MediaSource.of(noPrimary)));
            var hevc = new HeifBuilder();
            hevc.primary(hevc.item("hvc1", new byte[16], plain(HeifBuilder.ispe(1, 1))));
            var hevcFile = hevc.build();
            assertThrows(MediaException.Unsupported.class, () -> AvifDecoder.decode(MediaSource.of(hevcFile)));
            var unknown = new HeifBuilder();
            unknown.primary(still(unknown, coded, essential(HeifBuilder.box("zzzz", new byte[4]))));
            var unknownFile = unknown.build();
            assertThrows(MediaException.Unsupported.class, () -> AvifDecoder.decode(MediaSource.of(unknownFile)));
        }

        @Test
        @DisplayName("ignore cyclic auxiliary references and thumbnails")
        void cyclicReferences() {
            var coded = encode("scale=96:64", 96, 64, "yuv420p");
            var builder = new HeifBuilder();
            var image = still(builder, coded);
            var other = still(builder, coded);
            builder.reference("auxl", image, other).reference("auxl", other, image)
                    .reference("thmb", other, image).primary(image);
            assertNotNull(decode(builder.build()));
        }

        @Test
        @DisplayName("raise only MediaException for truncated and mutated files, in bounded time")
        void mutations() {
            var colors = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"));
            var alpha = alphaPlane(64, 64);
            var builder = new HeifBuilder();
            var ids = new int[2];
            for (var i = 0; i < 2; i++) {
                ids[i] = still(builder, colors.get(i), essential(HeifBuilder.irot(1)));
            }
            var aux = builder.item("av01", alpha.payload(), plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), essential(HeifBuilder.clap(100, 1, 60, 1, 0, 1, 0, 1)));
            builder.reference("dimg", grid, ids).reference("auxl", aux, ids[0]).primary(grid);
            var seeds = List.of(builder.build(), Fixtures.read(Fixtures.ffmpeg("avif",
                    List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10"),
                    List.of("-frames:v", "4", "-c:v", "libaom-av1", "-cpu-used", "8"))));
            var options = ImageDecoderOptions.builder().limits(SMALL).build();
            assertTimeoutPreemptively(Duration.ofMinutes(4), () -> {
                var random = new Random(11);
                var outcomes = new int[2];
                for (var seed : seeds) {
                    for (var length = 0; length < seed.length; length += Math.max(1, seed.length / 300)) {
                        attempt(Arrays.copyOf(seed, length), options, outcomes);
                    }
                    for (var round = 0; round < 300; round++) {
                        var mutated = seed.clone();
                        for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                            mutated[random.nextInt(Math.min(mutated.length, round % 2 == 0 ? 1200 : mutated.length))] =
                                    (byte) random.nextInt(256);
                        }
                        attempt(mutated, options, outcomes);
                    }
                }
                assertTrue(outcomes[0] > 0 && outcomes[1] > 0, "decoded " + outcomes[0] + ", rejected " + outcomes[1]);
            });
        }

        private static void attempt(byte[] data, ImageDecoderOptions options, int[] outcomes) {
            try {
                AvifDecoder.probe(MediaSource.of(data));
                AvifDecoder.decode(MediaSource.of(data), options);
                try (var reader = AvifDecoder.openAnimation(MediaSource.of(data), options)) {
                    for (var frame = reader.read(); frame != null; frame = reader.read()) {
                        assertNotNull(frame.picture());
                    }
                }
                outcomes[0]++;
            } catch (MediaException expected) {
                outcomes[1]++;
            }
        }
    }

    @Test
    @DisplayName("register with Images and open stills and sequences as animations")
    void registry() {
        assertTrue(Images.isDecodable(ImageFormat.AVIF));
        var coded = encode("scale=96:64", 96, 64, "yuv420p");
        var builder = new HeifBuilder();
        builder.primary(still(builder, coded));
        var data = builder.build();
        assertEquals(Optional.of(ImageFormat.AVIF), Images.detect(MediaSource.of(data)));
        try (var reader = Images.openAnimation(MediaSource.of(data))) {
            assertEquals(1, reader.frameCount());
            assertNotNull(reader.read());
            assertEquals(null, reader.read());
        }
        var sequence = Fixtures.read(Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10"),
                List.of("-frames:v", "5", "-c:v", "libaom-av1", "-cpu-used", "8")));
        var info = Images.probe(MediaSource.of(sequence));
        assertTrue(info.animated());
        assertEquals(5, info.frameCount());
        try (var reader = Images.openAnimation(MediaSource.of(sequence))) {
            var frames = 0;
            var time = 0L;
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                assertEquals(time, frame.timestampMicros());
                assertEquals(100_000, frame.durationMicros());
                time += frame.durationMicros();
                frames++;
            }
            assertEquals(5, frames);
        }
        var deep = AvifDecoder.decode(MediaSource.of(Fixtures.read(Fixtures.ffmpeg("avif",
                List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10"),
                List.of("-frames:v", "1", "-pix_fmt", "yuv420p10le", "-c:v", "libaom-av1", "-cpu-used", "8",
                        "-still-picture", "1")))), ImageDecoderOptions.builder().highBitDepth(true).build());
        assertEquals(10, assertInstanceOf(Yuv16Picture.class, deep.picture()).bitDepth());
    }
}
