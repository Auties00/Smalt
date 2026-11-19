package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.HeicReference.Planes;
import com.github.auties00.smalt.image.heic.HeicStructureTest.Coded;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import static com.github.auties00.smalt.image.heic.HeicStructureTest.alphaItem;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.alphaPlane;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.encode;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.grid;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.heic;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.still;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.stitched;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.tile;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.tiles;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers alpha planes of another size or depth than their color image, grids of RGBA inputs,
 * mixed luma and chroma depths, deep sequence frames and XMP items in HEIC files built box by box
 * around x265 payloads. Resampled alpha is checked against {@link ImageOps#resize} of ffmpeg's
 * decode of the alpha payload with the triangle filter; mixed-depth stills against
 * {@link HevcDecoder} decoding the same conformance stream.
 */
@DisplayName("HEIC alpha, depth and XMP")
class HeicAlphaDepthTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static final String RAW_BASE = "https://www.itu.int/wftp3/av-arch/jctvc-site/bitstream_exchange/draft_conformance/RExt/";

    /** The alpha samples an alpha payload of another size gives once resampled to a size. */
    private static int[] resampled(Planes alpha, int width, int height) {
        Picture mono;
        if (alpha.depth() == 8) {
            var bytes = new byte[alpha.planes()[0].length];
            for (var i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) alpha.planes()[0][i];
            }
            mono = YuvPicture.builder().size(alpha.width(), alpha.height()).chromaFormat(ChromaFormat.MONOCHROME)
                    .color(ColorInfo.JPEG).luma(bytes, alpha.width()).build();
        } else {
            var shorts = new short[alpha.planes()[0].length];
            for (var i = 0; i < shorts.length; i++) {
                shorts[i] = (short) alpha.planes()[0][i];
            }
            mono = Yuv16Picture.builder().size(alpha.width(), alpha.height()).chromaFormat(ChromaFormat.MONOCHROME)
                    .bitDepth(alpha.depth()).color(ColorInfo.JPEG).luma(shorts, alpha.width()).build();
        }
        return Planes.of(ImageOps.resize(mono, width, height, ResizeFilter.BILINEAR)).planes()[0];
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
        return Planes.of(picture).planes()[3];
    }

    private static Planes asPlanes(int[] samples, int width, int height, int depth) {
        return new Planes(width, height, ChromaFormat.MONOCHROME, depth, new int[][]{samples, null, null, null});
    }

    @Nested
    @DisplayName("alpha of another size")
    class Size {
        @Test
        @DisplayName("resamples a half-size coded alpha item to the color size")
        void coded() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alphaPlane(32, 32, "");
            var builder = heic();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()));
            assertArrayEquals(resampled(alpha.reference(), 64, 64), alphaOf(decoded.picture()));
            assertTrue(HeicDecoder.probe(MediaSource.of(builder.build())).hasAlpha());
        }

        @Test
        @DisplayName("resamples a larger alpha item down, and expands limited range after resampling")
        void largerLimited() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alphaPlane(100, 90, ":range=limited");
            var builder = heic();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()));
            var expected = resampled(alpha.reference(), 64, 64);
            for (var i = 0; i < expected.length; i++) {
                expected[i] = Math.clamp(((expected[i] - 16) * 255 + 109) / 219, 0, 255);
            }
            assertArrayEquals(expected, alphaOf(decoded.picture()));
        }

        @Test
        @DisplayName("resamples a derived alpha image (an identity of a smaller coded item)")
        void derived() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alphaPlane(48, 40, "");
            var builder = heic();
            var image = still(builder, color);
            var coded = still(builder, alpha);
            builder.hidden(coded);
            var iden = builder.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(48, 40)),
                    essential(HeifBuilder.auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha")));
            builder.reference("dimg", iden, coded).reference("auxl", iden, image).hidden(iden).primary(image);
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()));
            assertArrayEquals(resampled(alpha.reference(), 64, 64), alphaOf(decoded.picture()));
        }

        @Test
        @DisplayName("resamples the half-size alpha item of each grid tile to the tile")
        void perTile() {
            var colors = tiles("yuv420p");
            var alphas = List.of(alphaPlane(32, 32, ""), alphaPlane(32, 32, ":qp=30"), alphaPlane(32, 32, ":qp=20"),
                    alphaPlane(32, 32, ":qp=40"));
            var builder = heic();
            builder.primary(grid(builder, colors, 2, 120, 100));
            for (var i = 0; i < 4; i++) {
                alphaItem(builder, alphas.get(i), i + 1);
            }
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()));
            var tilesAlpha = new ArrayList<Planes>();
            for (var alpha : alphas) {
                tilesAlpha.add(asPlanes(resampled(alpha.reference(), 64, 64), 64, 64, 8));
            }
            var expected = stitched(tilesAlpha, 2, 64, 120, 100).planes()[0];
            assertArrayEquals(expected, alphaOf(decoded.picture()));
        }

        @Test
        @DisplayName("resamples an alpha grid whose output size differs from the color grid")
        void alphaGrid() {
            var colors = tiles("yuv420p");
            var alphas = List.of(alphaPlane(32, 32, ""), alphaPlane(32, 32, ":qp=30"), alphaPlane(32, 32, ":qp=20"),
                    alphaPlane(32, 32, ":qp=40"));
            var builder = heic();
            var colorGrid = grid(builder, colors, 2, 128, 128);
            var auxGrid = grid(builder, alphas, 2, 64, 64, essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
            builder.reference("auxl", auxGrid, colorGrid).primary(colorGrid);
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()));
            var assembled = stitched(alphas.stream().map(Coded::reference).toList(), 2, 32, 64, 64);
            assertArrayEquals(resampled(assembled, 128, 128), alphaOf(decoded.picture()));
        }
    }

    @Nested
    @DisplayName("alpha of another depth")
    class Depth {
        @Test
        @DisplayName("rescales 8-bit alpha to the depth of 10-bit color, and the 8-bit decode reduces it back")
        void eightUnderTen() {
            var color = tile(0, 0, "yuv420p10le");
            var alpha = alphaPlane(32, 32, "");
            var builder = heic();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var data = builder.build();
            var wide = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(data), WIDE).picture());
            var expected = rescale(resampled(alpha.reference(), 64, 64), 8, 10);
            assertArrayEquals(expected, alphaOf(wide));
            var narrow = HeicDecoder.decode(MediaSource.of(data)).picture();
            assertArrayEquals(alphaOf(ImageOps.toEightBit(wide)), alphaOf(narrow));
        }

        @Test
        @DisplayName("rescales 10-bit alpha to 8-bit color exactly")
        void tenUnderEight() {
            var color = tile(0, 0, "yuv420p");
            var alpha = encode("scale=64:64,format=gray10le,geq=lum='X*13+Y*3'", 64, 64, "gray10le", "");
            var builder = heic();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            for (var options : List.of(ImageDecoderOptions.defaults(), WIDE)) {
                var decoded = HeicDecoder.decode(MediaSource.of(builder.build()), options).picture();
                assertArrayEquals(rescale(alpha.reference().planes()[0], 10, 8), alphaOf(decoded));
            }
        }
    }

    @Nested
    @DisplayName("grids of RGBA inputs")
    class RgbaGrids {
        /** Builds an {@code iovl} payload with 16-bit offsets. */
        private static byte[] overlayPayload(int[] fill, int width, int height, int[]... offsets) {
            var out = new ByteArrayOutputStream();
            out.write(0);
            out.write(0);
            for (var value : fill) {
                out.write(value >> 8);
                out.write(value);
            }
            out.write(width >> 8);
            out.write(width);
            out.write(height >> 8);
            out.write(height);
            for (var offset : offsets) {
                for (var value : offset) {
                    out.write(value >> 8);
                    out.write(value);
                }
            }
            return out.toByteArray();
        }

        /** Adds a hidden 64x64 overlay of one coded tile at an offset over a translucent fill. */
        private static int overlay(HeifBuilder builder, Coded coded, int dx, int dy) {
            var input = still(builder, coded);
            builder.hidden(input);
            var id = builder.idatItem("iovl", overlayPayload(new int[]{0x2000, 0x4000, 0x6000, 0x8000}, 64, 64,
                    new int[]{dx, dy}), plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", id, input).hidden(id);
            return id;
        }

        private static Picture standaloneOverlay(Coded coded, int dx, int dy, ImageDecoderOptions options) {
            var builder = heic();
            builder.primary(overlay(builder, coded, dx, dy));
            return HeicDecoder.decode(MediaSource.of(builder.build()), options).picture();
        }

        private static Picture standaloneStill(Coded coded, ImageDecoderOptions options) {
            var builder = heic();
            builder.primary(still(builder, coded));
            return HeicDecoder.decode(MediaSource.of(builder.build()), options).picture();
        }

        private static byte[] gridOf(List<Integer> inputs, int width, int height, HeifBuilder builder) {
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, inputs.size(), width, height),
                    plain(HeifBuilder.ispe(width, height)));
            builder.reference("dimg", grid, inputs.stream().mapToInt(Integer::intValue).toArray()).primary(grid);
            return builder.build();
        }

        private static void paste(Picture tile, Picture canvas, int x, int width) {
            switch (canvas) {
                case RgbaPicture target -> {
                    var source = ImageOps.toRgba(tile);
                    for (var row = 0; row < source.height(); row++) {
                        var w = Math.min(source.width(), width - x);
                        System.arraycopy(source.pixels(), row * source.stride(), target.pixels(),
                                row * target.stride() + 4 * x, 4 * w);
                        if (!source.hasAlpha()) {
                            for (var col = 0; col < w; col++) {
                                target.pixels()[row * target.stride() + 4 * (x + col) + 3] = (byte) 255;
                            }
                        }
                    }
                }
                case Rgba16Picture target -> {
                    var source = tile instanceof Yuv16Picture || tile instanceof Rgba16Picture ? ImageOps.toRgba16(tile)
                            : ImageOps.toSixteenBit(ImageOps.toRgba(tile));
                    for (var row = 0; row < source.height(); row++) {
                        var w = Math.min(source.width(), width - x);
                        System.arraycopy(source.pixels(), row * source.stride(), target.pixels(),
                                row * target.stride() + 4 * x, 4 * w);
                        if (!source.hasAlpha()) {
                            for (var col = 0; col < w; col++) {
                                target.pixels()[row * target.stride() + 4 * (x + col) + 3] = (short) 0xFFFF;
                            }
                        }
                    }
                }
                default -> throw new AssertionError(canvas);
            }
        }

        private static byte[] tight(RgbaPicture picture) {
            var out = new byte[picture.width() * picture.height() * 4];
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), out, y * picture.width() * 4, picture.width() * 4);
            }
            return out;
        }

        private static short[] tight(Rgba16Picture picture) {
            var out = new short[picture.width() * picture.height() * 4];
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), out, y * picture.width() * 4, picture.width() * 4);
            }
            return out;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yuv420p", "yuv420p10le"})
        @DisplayName("stitch overlay inputs and a planar input before them into one packed picture")
        void overlays(String pixelFormat) {
            var codedTiles = tiles(pixelFormat);
            var builder = heic();
            var first = still(builder, codedTiles.get(0));
            builder.hidden(first);
            var second = overlay(builder, codedTiles.get(1), 8, 4);
            var third = overlay(builder, codedTiles.get(2), -10, 20);
            var data = gridOf(List.of(first, second, third), 180, 64, builder);
            var deep = pixelFormat.contains("10");
            for (var options : List.of(ImageDecoderOptions.defaults(), WIDE)) {
                var decoded = HeicDecoder.decode(MediaSource.of(data), options).picture();
                var wide = deep && options.highBitDepth();
                Picture expected = wide ? Rgba16Picture.allocate(180, 64, true) : RgbaPicture.allocate(180, 64, true);
                paste(standaloneStill(codedTiles.get(0), options), expected, 0, 180);
                paste(standaloneOverlay(codedTiles.get(1), 8, 4, options), expected, 64, 180);
                paste(standaloneOverlay(codedTiles.get(2), -10, 20, options), expected, 128, 180);
                if (wide) {
                    assertArrayEquals(tight((Rgba16Picture) expected), tight(assertInstanceOf(Rgba16Picture.class, decoded)));
                } else {
                    assertArrayEquals(tight((RgbaPicture) expected), tight(assertInstanceOf(RgbaPicture.class, decoded)));
                }
            }
        }

        @Test
        @DisplayName("stitch a premultiplied coded tile, divided back, with a planar tile")
        void premultipliedTile() {
            var codedTiles = tiles("yuv420p");
            var alpha = alphaPlane(64, 64, "");
            var builder = heic();
            var first = still(builder, codedTiles.get(0));
            var aux = alphaItem(builder, alpha, first);
            builder.reference("prem", first, aux).hidden(first);
            var second = still(builder, codedTiles.get(1));
            builder.hidden(second);
            var data = gridOf(List.of(first, second), 128, 64, builder);
            var decoded = assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(data)).picture());
            var premultiplied = heic();
            var image = still(premultiplied, codedTiles.get(0));
            var premAux = alphaItem(premultiplied, alpha, image);
            premultiplied.reference("prem", image, premAux).primary(image);
            var expected = RgbaPicture.allocate(128, 64, true);
            paste(HeicDecoder.decode(MediaSource.of(premultiplied.build())).picture(), expected, 0, 128);
            paste(standaloneStill(codedTiles.get(1), ImageDecoderOptions.defaults()), expected, 64, 128);
            assertArrayEquals(tight(expected), tight(decoded));
        }

        @Test
        @DisplayName("replace the alpha of a packed grid with the grid's alpha item")
        void gridAlpha() {
            var codedTiles = tiles("yuv420p");
            var builder = heic();
            var first = overlay(builder, codedTiles.get(0), 0, 0);
            var second = still(builder, codedTiles.get(1));
            builder.hidden(second);
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), plain(HeifBuilder.ispe(128, 64)));
            builder.reference("dimg", grid, first, second).primary(grid);
            var alpha = alphaPlane(64, 32, "");
            var aux = builder.item("hvc1", alpha.payload(), plain(HeifBuilder.ispe(64, 32)),
                    essential(HeifBuilder.box("hvcC", alpha.config())), essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
            builder.reference("auxl", aux, grid).hidden(aux);
            var decoded = assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            var expectedAlpha = resampled(alpha.reference(), 128, 64);
            var expected = RgbaPicture.allocate(128, 64, true);
            paste(standaloneOverlay(codedTiles.get(0), 0, 0, ImageDecoderOptions.defaults()), expected, 0, 128);
            paste(standaloneStill(codedTiles.get(1), ImageDecoderOptions.defaults()), expected, 64, 128);
            for (var i = 0; i < expectedAlpha.length; i++) {
                expected.pixels()[4 * i + 3] = (byte) expectedAlpha[i];
            }
            assertArrayEquals(tight(expected), tight(decoded));
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        private static final com.github.auties00.smalt.MediaLimits SMALL = com.github.auties00.smalt.MediaLimits.builder()
                .maxPixels(1 << 20).maxBytes(16L << 20).build();

        private static void attempt(byte[] data, ImageDecoderOptions options, int[] outcomes) {
            try {
                HeicDecoder.decode(MediaSource.of(data), options);
                try (var reader = HeicDecoder.openAnimation(MediaSource.of(data), options)) {
                    for (var frame = reader.read(); frame != null; frame = reader.read()) {
                        assertNotNull(frame.picture());
                    }
                }
                outcomes[0]++;
            } catch (com.github.auties00.smalt.MediaException expected) {
                outcomes[1]++;
            }
        }

        @Test
        @DisplayName("raise only MediaException for truncated and mutated files with resampled alpha and RGBA grids")
        void mutations() {
            var seeds = new ArrayList<byte[]>();
            var resampled = heic();
            var colorGrid = grid(resampled, tiles("yuv420p10le"), 2, 128, 128);
            var auxGrid = grid(resampled, List.of(alphaPlane(32, 32, ""), alphaPlane(32, 32, ""), alphaPlane(32, 32, ""),
                    alphaPlane(32, 32, "")), 2, 64, 64, essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
            resampled.reference("auxl", auxGrid, colorGrid).primary(colorGrid);
            seeds.add(resampled.build());
            var packed = heic();
            var first = still(packed, tile(0, 0, "yuv420p"));
            alphaItem(packed, alphaPlane(32, 32, ""), first);
            var input = still(packed, tile(64, 0, "yuv420p"));
            packed.hidden(input);
            var overlay = packed.idatItem("iovl", new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 64, 0, 64, 0, 4, 0, 4},
                    plain(HeifBuilder.ispe(64, 64)));
            packed.reference("dimg", overlay, input).hidden(overlay);
            var gridItem = packed.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), plain(HeifBuilder.ispe(128, 64)));
            packed.reference("dimg", gridItem, first, overlay).primary(gridItem);
            seeds.add(packed.build());
            for (var options : List.of(ImageDecoderOptions.builder().limits(SMALL).build(),
                    ImageDecoderOptions.builder().limits(SMALL).highBitDepth(true).build())) {
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofMinutes(4), () -> {
                    var random = new java.util.Random(31);
                    var outcomes = new int[2];
                    for (var seed : seeds) {
                        for (var length = 0; length < seed.length; length += Math.max(1, seed.length / 60)) {
                            attempt(Arrays.copyOf(seed, length), options, outcomes);
                        }
                        for (var round = 0; round < 80; round++) {
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
        }
    }

    @Nested
    @DisplayName("mixed luma and chroma depths")
    class MixedDepth {
        private static byte[] bitstream(String name, String sha256) throws IOException {
            var archive = Conformance.fetch("hevc-rext-" + name + ".zip", RAW_BASE + name + ".zip", sha256);
            try (var zip = new ZipFile(archive.toFile())) {
                return zip.getInputStream(zip.getEntry(name + "/" + name + ".bin")).readAllBytes();
            }
        }

        private static List<byte[]> nalUnits(byte[] data) {
            var starts = new ArrayList<Integer>();
            for (var i = 0; i + 2 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    starts.add(i + 3);
                    i += 2;
                }
            }
            var units = new ArrayList<byte[]>();
            for (var k = 0; k < starts.size(); k++) {
                var end = k + 1 < starts.size() ? starts.get(k + 1) - 3 : data.length;
                while (end > starts.get(k) && data[end - 1] == 0) {
                    end--;
                }
                units.add(Arrays.copyOfRange(data, starts.get(k), end));
            }
            return units;
        }

        private static byte[] hvcC(List<byte[]> sets) {
            var out = new ByteArrayOutputStream();
            out.write(1);
            out.writeBytes(new byte[20]);
            out.write(0xFC | 3);
            out.write(sets.size());
            for (var set : sets) {
                out.write(0x80 | ((set[0] >> 1) & 0x3F));
                out.write(0);
                out.write(1);
                out.write(set.length >> 8);
                out.write(set.length);
                out.writeBytes(set);
            }
            return out.toByteArray();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Bitdepth_A_RExt_Sony_1|34967e2047d59b0f3e96e7501712a056c01f369501d812b170c717ce1d417368",
                "Bitdepth_B_RExt_Sony_1|6f5f16377e9287ced1ad06a9493249ed1d61e393d779b06e777a92e14b6c6eb6"})
        @DisplayName("decode the first picture of a RExt stream wrapped in HEIF as HevcDecoder does at high bit depth")
        void conformance(String vector) throws IOException {
            var parts = vector.split("\\|");
            var stream = bitstream(parts[0], parts[1]);
            var sets = new ArrayList<byte[]>();
            var payload = new ByteArrayOutputStream();
            var annexB = new ByteArrayOutputStream();
            var slices = 0;
            for (var nal : nalUnits(stream)) {
                var type = (nal[0] >> 1) & 0x3F;
                var slice = type <= 21 && (type <= 9 || type >= 16);
                if (slice && (nal[2] & 0x80) != 0 && slices > 0) {
                    break;
                }
                if (type >= 32 && type <= 34) {
                    sets.add(nal);
                } else if (slice) {
                    slices++;
                    payload.write(nal.length >>> 24);
                    payload.write(nal.length >>> 16);
                    payload.write(nal.length >>> 8);
                    payload.write(nal.length);
                    payload.writeBytes(nal);
                } else {
                    continue;
                }
                annexB.writeBytes(new byte[]{0, 0, 0, 1});
                annexB.writeBytes(nal);
            }
            var reference = new Picture[1];
            var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).build();
            try (var decoder = new HevcDecoder(track, HevcDecoderOptions.builder().highBitDepth(true).build())) {
                java.util.function.Consumer<VideoFrame> sink = frame -> {
                    if (reference[0] == null) {
                        reference[0] = copy((Yuv16Picture) frame.picture());
                    }
                };
                decoder.decode(new Packet(1, annexB.toByteArray(), 0, 0, 0, true), sink);
                decoder.flush(sink);
            }
            var expected = assertInstanceOf(Yuv16Picture.class, reference[0]);
            assertEquals(12, expected.bitDepth());
            var builder = heic();
            var item = builder.item("hvc1", payload.toByteArray(),
                    plain(HeifBuilder.ispe(expected.width(), expected.height())),
                    essential(HeifBuilder.box("hvcC", hvcC(sets))));
            builder.primary(item);
            var data = builder.build();
            var deep = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(data), WIDE).picture());
            assertEquals(12, deep.bitDepth());
            HeicStructureTest.assertPlanes(Planes.of(expected), Planes.of(deep));
            var narrow = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(data)).picture());
            var chromaShallow = parts[0].startsWith("Bitdepth_A");
            var planes = Planes.of(narrow).planes();
            var wide = Planes.of(deep).planes();
            for (var plane = 0; plane < 3; plane++) {
                var shift = (plane > 0) == chromaShallow ? 4 : 0;
                for (var i = 0; i < planes[plane].length; i++) {
                    var source = wide[plane][i] >> shift;
                    var depth = 12 - shift;
                    var round = depth == 8 ? source : Math.min(255, (source + 8) >> 4);
                    assertEquals(round, planes[plane][i], "plane " + plane + " sample " + i);
                }
            }
        }

        private static Yuv16Picture copy(Yuv16Picture picture) {
            var out = Yuv16Picture.allocate(picture.width(), picture.height(), picture.chromaFormat(), picture.bitDepth(),
                    false, picture.color());
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.y(), y * picture.yStride(), out.y(), y * out.yStride(), picture.width());
            }
            for (var y = 0; y < picture.chromaHeight(); y++) {
                System.arraycopy(picture.u(), y * picture.uvStride(), out.u(), y * out.uvStride(), picture.chromaWidth());
                System.arraycopy(picture.v(), y * picture.uvStride(), out.v(), y * out.uvStride(), picture.chromaWidth());
            }
            return out;
        }
    }

    @Nested
    @DisplayName("sequences")
    class Sequences {
        private static final String ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha";

        private static List<Coded> frames(String pixelFormat, int width, int height, int count) {
            var out = new ArrayList<Coded>();
            for (var i = 0; i < count; i++) {
                out.add(encode("crop=" + width + ":" + height + ":" + (i * 16) + ":" + (i * 8), width, height,
                        pixelFormat, ""));
            }
            return out;
        }

        private static byte[] sequence(List<Coded> colors, List<Coded> alphas) {
            var builder = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8", "mif1");
            var color = builder.track("hvc1", colors.getFirst().width(), colors.getFirst().height(), 100,
                    colors.stream().map(Coded::payload).toList(), HeifBuilder.box("hvcC", colors.getFirst().config()));
            if (alphas != null) {
                var alpha = builder.track("hvc1", alphas.getFirst().width(), alphas.getFirst().height(), 100,
                        alphas.stream().map(Coded::payload).toList(), HeifBuilder.box("hvcC", alphas.getFirst().config()),
                        HeifSequenceBuilder.auxi(ALPHA_URN));
                builder.reference("auxl", alpha, color);
            }
            return builder.build();
        }

        @Test
        @DisplayName("resample half-size alpha frames to the color frames")
        void alpha() {
            var colors = frames("yuv420p", 64, 64, 3);
            var alphas = new ArrayList<Coded>();
            for (var i = 0; i < 3; i++) {
                alphas.add(encode("crop=32:32:" + (i * 8) + ":0,format=gray,geq=lum='X*7+Y*" + (i + 1) + "'", 32, 32,
                        "gray", ""));
            }
            var data = sequence(colors, alphas);
            var still = HeicDecoder.decode(MediaSource.of(data));
            assertArrayEquals(resampled(alphas.getFirst().reference(), 64, 64), alphaOf(still.picture()));
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(data))) {
                var index = 0;
                for (var frame = reader.read(); frame != null; frame = reader.read(), index++) {
                    var rgba = assertInstanceOf(RgbaPicture.class, frame.picture());
                    var expected = resampled(alphas.get(index).reference(), 64, 64);
                    for (var i = 0; i < expected.length; i++) {
                        assertEquals(expected[i], rgba.pixels()[(i / 64) * rgba.stride() + 4 * (i % 64) + 3] & 0xFF,
                                "frame " + index + " pixel " + i);
                    }
                }
                assertEquals(3, index);
            }
        }

        @Test
        @DisplayName("play 10-bit frames as 16-bit RGBA under highBitDepth and as 8-bit RGBA otherwise")
        void deepFrames() {
            var colors = frames("yuv420p10le", 64, 64, 3);
            var alphas = frames("gray10le", 64, 64, 3);
            var data = sequence(colors, alphas);
            var deep = new ArrayList<Rgba16Picture>();
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(data), WIDE)) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    deep.add(copy(assertInstanceOf(Rgba16Picture.class, frame.picture())));
                }
            }
            assertEquals(3, deep.size());
            var still = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(data), WIDE).picture());
            assertArrayEquals(tight(ImageOps.toRgba16(still)), tight(deep.getFirst()));
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(data))) {
                var index = 0;
                for (AnimationFrame frame = reader.read(); frame != null; frame = reader.read(), index++) {
                    var narrow = assertInstanceOf(RgbaPicture.class, frame.picture());
                    assertEquals(narrow.width(), deep.get(index).width());
                }
                assertEquals(3, index);
            }
            var plainColor = sequence(colors, null);
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(plainColor), WIDE)) {
                var frame = reader.read();
                assertNotNull(frame);
                var rgba = assertInstanceOf(Rgba16Picture.class, frame.picture());
                assertEquals(false, rgba.hasAlpha());
            }
            var eightBit = sequence(frames("yuv420p", 64, 64, 2), null);
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(eightBit), WIDE)) {
                assertInstanceOf(RgbaPicture.class, reader.read().picture());
            }
        }

        @Test
        @DisplayName("play a still image as one 16-bit frame under highBitDepth")
        void still() {
            var builder = heic();
            builder.primary(HeicStructureTest.still(builder, tile(0, 0, "yuv420p10le")));
            var data = builder.build();
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(data), WIDE)) {
                var frame = assertInstanceOf(Rgba16Picture.class, reader.read().picture());
                var still = HeicDecoder.decode(MediaSource.of(data), WIDE).picture();
                assertArrayEquals(tight(ImageOps.toRgba16(still)), tight(frame));
            }
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(data))) {
                assertInstanceOf(RgbaPicture.class, reader.read().picture());
            }
        }

        private static Rgba16Picture copy(Rgba16Picture picture) {
            var out = Rgba16Picture.allocate(picture.width(), picture.height(), picture.hasAlpha());
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), out.pixels(), y * out.stride(), 4 * picture.width());
            }
            return out;
        }

        private static short[] tight(Rgba16Picture picture) {
            var out = new short[picture.width() * picture.height() * 4];
            for (var y = 0; y < picture.height(); y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), out, y * picture.width() * 4, picture.width() * 4);
            }
            return out;
        }
    }

    @Nested
    @DisplayName("XMP")
    class Xmp {
        private static final byte[] PACKET = ("<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF"
                + " xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about=''"
                + " xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='Smalt test'/></rdf:RDF></x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("return the XMP item of the primary image, kept through the sRGB conversion")
        void primary() {
            var builder = heic();
            var image = still(builder, tile(0, 0, "yuv420p"));
            var xmp = builder.mimeItem("application/rdf+xml", PACKET);
            builder.reference("cdsc", xmp, image).primary(image);
            var data = builder.build();
            assertArrayEquals(PACKET, HeicDecoder.decode(MediaSource.of(data)).xmp().orElseThrow());
            var srgb = ImageDecoderOptions.builder().convertToSrgb(true).build();
            assertArrayEquals(PACKET, HeicDecoder.decode(MediaSource.of(data), srgb).xmp().orElseThrow());
            var none = heic();
            none.primary(still(none, tile(0, 0, "yuv420p")));
            assertTrue(HeicDecoder.decode(MediaSource.of(none.build())).xmp().isEmpty());
        }

        @Test
        @DisplayName("return a deflate-encoded XMP item of a grid inflated, and none for an unknown encoding")
        void encoded() {
            var deflater = new java.util.zip.Deflater();
            deflater.setInput(PACKET);
            deflater.finish();
            var compressed = new byte[PACKET.length + 64];
            var size = deflater.deflate(compressed);
            deflater.end();
            var builder = heic();
            var grid = grid(builder, tiles("yuv420p"), 2, 128, 128);
            var xmp = builder.mimeItem("application/rdf+xml", "deflate", Arrays.copyOf(compressed, size));
            builder.reference("cdsc", xmp, grid).primary(grid);
            assertArrayEquals(PACKET, HeicDecoder.decode(MediaSource.of(builder.build())).xmp().orElseThrow());

            var unknown = heic();
            var image = still(unknown, tile(0, 0, "yuv420p"));
            var item = unknown.mimeItem("application/rdf+xml", "br", PACKET);
            unknown.reference("cdsc", item, image).primary(image);
            assertTrue(HeicDecoder.decode(MediaSource.of(unknown.build()), ImageDecoderOptions.builder()
                    .limits(MediaLimits.DEFAULT).build()).xmp().isEmpty());
        }
    }
}
