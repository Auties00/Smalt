package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.image.heic.HeicReference.Planes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Builds HEIC files box by box around x265-coded payloads (grids, alpha planes, color boxes,
 * thumbnails, derived images) and hostile variants of them, and checks the decoder against
 * ffmpeg's decode of the same payloads.
 */
@DisplayName("HEIC structures")
class HeicStructureTest {
    /** Generous for small grids, small enough to keep mutated files cheap. */
    private static final MediaLimits SMALL = MediaLimits.builder().maxPixels(1 << 20).maxBytes(16L << 20).build();

    /** A coded HEVC still: its hvcC record, its length-prefixed payload and its size. */
    record Coded(byte[] config, byte[] payload, int width, int height, String pixelFormat) {
        Planes reference() {
            var format = pixelFormat.startsWith("gray") ? ChromaFormat.MONOCHROME : ChromaFormat.YUV420;
            var depth = pixelFormat.contains("10") ? 10 : 8;
            return HeicReference.decode(HeicReference.annexB(config, List.of(payload)), 1, width, height, format, depth);
        }
    }

    /** Encodes one picture with x265 through ffmpeg and takes the Annex B stream apart. */
    static Coded encode(String filter, int width, int height, String pixelFormat, String x265) {
        var path = Fixtures.ffmpeg("hevc", List.of("-f", "lavfi", "-i", "testsrc2=size=256x256:rate=1"),
                List.of("-frames:v", "1", "-vf", filter + ",format=" + pixelFormat, "-c:v", "libx265", "-preset",
                        "ultrafast", "-x265-params", "log-level=error" + x265));
        var stream = Fixtures.read(path);
        var sets = new ArrayList<byte[]>();
        var payload = new ByteArrayOutputStream();
        for (var nal : nalUnits(stream)) {
            var type = (nal[0] >> 1) & 0x3F;
            if (type >= 32 && type <= 34) {
                sets.add(nal);
            } else {
                payload.write(nal.length >>> 24);
                payload.write(nal.length >>> 16);
                payload.write(nal.length >>> 8);
                payload.write(nal.length);
                payload.writeBytes(nal);
            }
        }
        return new Coded(hvcC(sets), payload.toByteArray(), width, height, pixelFormat);
    }

    static Coded tile(int x, int y, String pixelFormat) {
        return encode("crop=64:64:" + x + ":" + y, 64, 64, pixelFormat, "");
    }

    static Coded alphaPlane(int width, int height, String x265) {
        return encode("scale=" + width + ":" + height + ",format=gray,geq=lum='X*3+Y'", width, height, "gray", x265);
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

    static HeifBuilder heic() {
        return new HeifBuilder().brands("heic", "heic", "mif1");
    }

    static int still(HeifBuilder builder, Coded coded, HeifBuilder.Property... extra) {
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(coded.width(), coded.height())),
                essential(HeifBuilder.box("hvcC", coded.config()))));
        properties.addAll(List.of(extra));
        return builder.item("hvc1", coded.payload(), properties.toArray(HeifBuilder.Property[]::new));
    }

    static int alphaItem(HeifBuilder builder, Coded coded, int master) {
        var id = builder.item("hvc1", coded.payload(), plain(HeifBuilder.ispe(coded.width(), coded.height())),
                essential(HeifBuilder.box("hvcC", coded.config())), essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
        builder.reference("auxl", id, master).hidden(id);
        return id;
    }

    static int grid(HeifBuilder builder, List<Coded> tiles, int columns, int width, int height,
                    HeifBuilder.Property... extra) {
        var ids = new int[tiles.size()];
        for (var i = 0; i < ids.length; i++) {
            ids[i] = still(builder, tiles.get(i));
            builder.hidden(ids[i]);
        }
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(width, height))));
        properties.addAll(List.of(extra));
        var grid = builder.idatItem("grid", HeifBuilder.grid(tiles.size() / columns, columns, width, height),
                properties.toArray(HeifBuilder.Property[]::new));
        builder.reference("dimg", grid, ids);
        return grid;
    }

    static List<Coded> tiles(String pixelFormat) {
        return List.of(tile(0, 0, pixelFormat), tile(64, 0, pixelFormat), tile(0, 64, pixelFormat),
                tile(64, 64, pixelFormat));
    }

    static Planes stitched(List<Planes> tiles, int columns, int tileSize, int width, int height) {
        var format = tiles.getFirst().format();
        var out = new int[4][];
        out[0] = new int[width * height];
        if (format.hasChroma()) {
            out[1] = new int[format.chromaWidth(width) * format.chromaHeight(height)];
            out[2] = new int[out[1].length];
        }
        for (var i = 0; i < tiles.size(); i++) {
            var x0 = (i % columns) * tileSize;
            var y0 = (i / columns) * tileSize;
            var tile = tiles.get(i);
            for (var y = y0; y < Math.min(height, y0 + tileSize); y++) {
                for (var x = x0; x < Math.min(width, x0 + tileSize); x++) {
                    out[0][y * width + x] = tile.planes()[0][(y - y0) * tileSize + (x - x0)];
                    if (format.hasChroma() && ((x | y) & 1) == 0) {
                        var cw = format.chromaWidth(width);
                        var tw = tile.chromaWidth();
                        out[1][(y / 2) * cw + x / 2] = tile.planes()[1][((y - y0) / 2) * tw + (x - x0) / 2];
                        out[2][(y / 2) * cw + x / 2] = tile.planes()[2][((y - y0) / 2) * tw + (x - x0) / 2];
                    }
                }
            }
        }
        return new Planes(width, height, format, tiles.getFirst().depth(), out);
    }

    static void assertPlanes(Planes expected, Planes actual) {
        assertEquals(expected.width(), actual.width(), "width");
        assertEquals(expected.height(), actual.height(), "height");
        assertEquals(expected.format(), actual.format(), "format");
        assertEquals(expected.depth(), actual.depth(), "depth");
        for (var plane = 0; plane < 4; plane++) {
            assertArrayEquals(expected.planes()[plane], actual.planes()[plane], "plane " + plane);
        }
    }

    @Nested
    @DisplayName("grids")
    class Grids {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yuv420p", "yuv420p10le", "gray"})
        @DisplayName("stitch x265 tiles decoded in parallel and crop them to the output size")
        void stitch(String pixelFormat) {
            var tiles = tiles(pixelFormat);
            var builder = heic();
            builder.primary(grid(builder, tiles, 2, 120, 100));
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()),
                    ImageDecoderOptions.builder().highBitDepth(true).build());
            var expected = stitched(tiles.stream().map(Coded::reference).toList(), 2, 64, 120, 100);
            assertPlanes(expected, Planes.of(decoded.picture()));
            if (pixelFormat.contains("10")) {
                var narrow = HeicDecoder.decode(MediaSource.of(builder.build()));
                assertPlanes(Planes.of(ImageOps.toEightBit((Yuv16Picture) decoded.picture())), Planes.of(narrow.picture()));
            }
        }

        @Test
        @DisplayName("assemble an alpha plane from one auxiliary item per tile, and from an alpha grid")
        void alpha() {
            var tiles = tiles("yuv420p");
            var alphas = List.of(alphaPlane(64, 64, ""), alphaPlane(64, 64, ""), alphaPlane(64, 64, ""),
                    alphaPlane(64, 64, ""));
            var expectedAlpha = stitched(alphas.stream().map(Coded::reference).toList(), 2, 64, 128, 128).planes()[0];
            var perTile = heic();
            perTile.primary(grid(perTile, tiles, 2, 128, 128));
            for (var i = 0; i < 4; i++) {
                alphaItem(perTile, alphas.get(i), i + 1);
            }
            var decoded = Planes.of(HeicDecoder.decode(MediaSource.of(perTile.build())).picture());
            assertArrayEquals(expectedAlpha, decoded.planes()[3]);

            var alphaGrid = heic();
            var colorGrid = grid(alphaGrid, tiles, 2, 128, 128);
            var auxGrid = grid(alphaGrid, alphas, 2, 128, 128, essential(HeifBuilder.auxC("urn:mpeg:hevc:2015:auxid:1")));
            alphaGrid.reference("auxl", auxGrid, colorGrid).primary(colorGrid);
            var gridDecoded = Planes.of(HeicDecoder.decode(MediaSource.of(alphaGrid.build())).picture());
            assertTrue(HeicDecoder.probe(MediaSource.of(perTile.build())).hasAlpha());
            assertArrayEquals(expectedAlpha, gridDecoded.planes()[3]);
        }

        @Test
        @DisplayName("reject wrong input counts, uncovered outputs and oversized outputs")
        void hostile() {
            var tiles = tiles("yuv420p");
            var wrongCount = heic();
            var a = still(wrongCount, tiles.get(0));
            var g = wrongCount.idatItem("grid", HeifBuilder.grid(2, 2, 128, 128), plain(HeifBuilder.ispe(128, 128)));
            wrongCount.reference("dimg", g, a).primary(g);
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(wrongCount.build())));

            var uncovered = heic();
            uncovered.primary(grid(uncovered, tiles, 2, 200, 100));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(uncovered.build())));

            var huge = heic();
            huge.primary(grid(huge, tiles, 2, 100_000, 100_000));
            assertThrows(MediaException.class, () -> HeicDecoder.decode(MediaSource.of(huge.build())));

            var self = heic();
            var s = self.idatItem("grid", HeifBuilder.grid(1, 1, 64, 64), plain(HeifBuilder.ispe(64, 64)));
            self.reference("dimg", s, s).primary(s);
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(self.build())));
        }
    }

    @Nested
    @DisplayName("alpha")
    class Alpha {
        @Test
        @DisplayName("expand a limited-range alpha plane to full range")
        void limited() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alphaPlane(64, 64, ":range=limited");
            var builder = heic();
            var image = still(builder, color);
            alphaItem(builder, alpha, image);
            builder.primary(image);
            var decoded = (YuvPicture) HeicDecoder.decode(MediaSource.of(builder.build())).picture();
            var luma = alpha.reference().planes()[0];
            for (var i = 0; i < luma.length; i++) {
                var expected = Math.clamp(((luma[i] - 16) * 255 + 109) / 219, 0, 255);
                assertEquals(expected, decoded.alpha()[i] & 0xFF, "sample " + i);
            }
        }

        @Test
        @DisplayName("divide premultiplied color by its alpha into straight RGBA")
        void premultiplied() {
            var color = tile(0, 0, "yuv420p");
            var alpha = alphaPlane(64, 64, "");
            var builder = heic();
            var image = still(builder, color);
            var aux = alphaItem(builder, alpha, image);
            builder.reference("prem", image, aux).primary(image);
            var decoded = assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            var luma = alpha.reference().planes()[0];
            for (var i = 0; i < luma.length; i++) {
                assertEquals(luma[i], decoded.pixels()[(i / 64) * decoded.stride() + (i % 64) * 4 + 3] & 0xFF);
            }
        }

        @Test
        @DisplayName("resample an alpha plane of another size to the color size")
        void mismatched() {
            var builder = heic();
            var image = still(builder, tile(0, 0, "yuv420p"));
            alphaItem(builder, alphaPlane(32, 32, ""), image);
            builder.primary(image);
            var decoded = (YuvPicture) HeicDecoder.decode(MediaSource.of(builder.build())).picture();
            assertTrue(decoded.hasAlpha());
            assertEquals(64, decoded.width());
        }
    }

    @Nested
    @DisplayName("color")
    class Color {
        @Test
        @DisplayName("take the MIAF defaults for what neither the stream nor the file describes")
        void defaults() {
            var builder = heic();
            builder.primary(still(builder, tile(0, 0, "yuv420p")));
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build()));
            var color = ((YuvPicture) decoded.picture()).color();
            assertEquals(MatrixCoefficients.BT601, color.matrix());
            // ffmpeg passes the limited range of its yuv420p frames to x265, which signals it
            assertEquals(ColorRange.LIMITED, color.range());
            assertEquals(ColorPrimaries.BT709, color.primaries());
            assertEquals(TransferCharacteristics.SRGB, color.transfer());
            assertTrue(decoded.colorProfile().isEmpty());
        }

        @Test
        @DisplayName("take the stream's color description, and let an nclx box and clli replace it")
        void signaled() {
            var coded = encode("crop=64:64:0:0", 64, 64, "yuv420p10le",
                    ":colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited");
            var vui = heic();
            vui.primary(still(vui, coded));
            var fromStream = ((YuvPicture) HeicDecoder.decode(MediaSource.of(vui.build())).picture()).color();
            assertEquals(ColorPrimaries.BT2020, fromStream.primaries());
            assertEquals(TransferCharacteristics.PQ, fromStream.transfer());
            assertEquals(ColorRange.LIMITED, fromStream.range());

            var boxed = heic();
            boxed.primary(still(boxed, coded, essential(HeifBuilder.nclx(12, 13, 1, true)), plain(HeifBuilder.clli(1000, 400))));
            var decoded = HeicDecoder.decode(MediaSource.of(boxed.build()));
            var color = ((YuvPicture) decoded.picture()).color();
            assertEquals(ColorPrimaries.of(12), color.primaries());
            assertEquals(TransferCharacteristics.SRGB, color.transfer());
            assertEquals(MatrixCoefficients.BT709, color.matrix());
            assertEquals(ColorRange.FULL, color.range());
            assertEquals(1000, color.contentLightLevel().orElseThrow().maxContentLightLevel());
            assertFalse(decoded.colorProfile().orElseThrow().isSrgb());
        }
    }

    @Nested
    @DisplayName("high bit depth")
    class HighDepth {
        private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

        /** Builds an {@code iovl} payload with 16-bit offsets. */
        private static byte[] overlayPayload(int[] fill, int width, int height, int[][] offsets) {
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

        /** Decodes one coded item on its own, at its depth. */
        private static com.github.auties00.smalt.image.Picture standalone(Coded coded) {
            var builder = heic();
            builder.primary(still(builder, coded));
            return HeicDecoder.decode(MediaSource.of(builder.build()), WIDE).picture();
        }

        @Test
        @DisplayName("compose an overlay of 10-bit inputs at 16 bits, with the exact 16-bit fill")
        void overlay() {
            var tiles = List.of(tile(0, 0, "yuv420p10le"), tile(64, 64, "yuv420p10le"));
            var fill = new int[]{0x1234, 0x5678, 0x9ABC, 0xFFFF};
            int[][] offsets = {{0, 0}, {50, 30}};
            var builder = heic();
            var inputs = new int[tiles.size()];
            for (var i = 0; i < inputs.length; i++) {
                inputs[i] = still(builder, tiles.get(i));
                builder.hidden(inputs[i]);
            }
            var overlay = builder.idatItem("iovl", overlayPayload(fill, 100, 80, offsets), plain(HeifBuilder.ispe(100, 80)));
            builder.reference("dimg", overlay, inputs).primary(overlay);
            var data = builder.build();
            var deep = assertInstanceOf(com.github.auties00.smalt.image.Rgba16Picture.class,
                    HeicDecoder.decode(MediaSource.of(data), WIDE).picture());
            var expected = new short[4 * 100 * 80];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (short) fill[i & 3];
            }
            for (var t = 0; t < tiles.size(); t++) {
                var input = standalone(tiles.get(t));
                assertInstanceOf(Yuv16Picture.class, input);
                var rgba = ImageOps.toRgba16(input);
                for (var y = 0; y < 64; y++) {
                    for (var x = 0; x < 64; x++) {
                        var cx = offsets[t][0] + x;
                        var cy = offsets[t][1] + y;
                        if (cx < 100 && cy < 80) {
                            System.arraycopy(rgba.pixels(), y * rgba.stride() + 4 * x, expected, 4 * (cy * 100 + cx), 4);
                        }
                    }
                }
            }
            var actual = new short[expected.length];
            for (var y = 0; y < 80; y++) {
                System.arraycopy(deep.pixels(), y * deep.stride(), actual, y * 400, 400);
            }
            assertArrayEquals(expected, actual);
            var narrow = assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(data)).picture());
            assertEquals((0x1234 * 255 + 32767) / 65535, narrow.pixels()[4 * 99] & 0xFF);
        }

        @Test
        @DisplayName("compose an overlay of 8-bit inputs at 8 bits even when the depth is kept")
        void overlayOfEightBitInputs() {
            var builder = heic();
            var input = still(builder, tile(0, 0, "yuv420p"));
            builder.hidden(input);
            var overlay = builder.idatItem("iovl", overlayPayload(new int[]{0, 0, 0, 0xFFFF}, 64, 64, new int[][]{{0, 0}}),
                    plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", overlay, input).primary(overlay);
            var wide = HeicDecoder.decode(MediaSource.of(builder.build()), WIDE).picture();
            var narrow = HeicDecoder.decode(MediaSource.of(builder.build())).picture();
            assertArrayEquals(assertInstanceOf(RgbaPicture.class, narrow).pixels(),
                    assertInstanceOf(RgbaPicture.class, wide).pixels());
        }

        @Test
        @DisplayName("divide premultiplied 10-bit color by its alpha at 16 bits")
        void premultiplied() {
            var color = tile(0, 0, "yuv420p10le");
            var alpha = alphaPlane(64, 64, "");
            var straight = heic();
            var plainImage = still(straight, color);
            alphaItem(straight, alpha, plainImage);
            straight.primary(plainImage);
            var source = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(straight.build()), WIDE)
                    .picture());
            var builder = heic();
            var image = still(builder, color);
            var aux = alphaItem(builder, alpha, image);
            builder.reference("prem", image, aux).primary(image);
            var decoded = assertInstanceOf(com.github.auties00.smalt.image.Rgba16Picture.class,
                    HeicDecoder.decode(MediaSource.of(builder.build()), WIDE).picture());
            var premultiplied = ImageOps.toRgba16(source);
            for (var i = 0; i < 64 * 64; i++) {
                var p = (i / 64) * premultiplied.stride() + 4 * (i % 64);
                var q = (i / 64) * decoded.stride() + 4 * (i % 64);
                long a = premultiplied.pixels()[p + 3] & 0xFFFF;
                assertEquals(a, decoded.pixels()[q + 3] & 0xFFFF);
                for (var c = 0; c < 3; c++) {
                    long value = premultiplied.pixels()[p + c] & 0xFFFF;
                    var expected = a == 0 ? 0 : Math.min(65535, (value * 131070 + a) / (2 * a));
                    assertEquals(expected, decoded.pixels()[q + c] & 0xFFFF, "pixel " + i + " channel " + c);
                }
            }
            assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
        }
    }

    @Nested
    @DisplayName("derived images and thumbnails")
    class Derived {
        @Test
        @DisplayName("decode the smallest matching thumbnail under a reduced-size hint")
        void thumbnails() {
            var builder = heic();
            var primary = still(builder, encode("scale=256:256", 256, 256, "yuv420p", ""));
            var thumbnail = still(builder, encode("scale=64:64", 64, 64, "yuv420p", ""));
            var wide = still(builder, encode("scale=128:64", 128, 64, "yuv420p", ""));
            builder.reference("thmb", thumbnail, primary).reference("thmb", wide, primary).primary(primary);
            var data = builder.build();
            var small = HeicDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(48, 48));
            assertEquals(64, small.picture().width());
            assertEquals(256, small.sourceWidth());
            var full = HeicDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(100, 100));
            assertEquals(256, full.picture().width());
            assertEquals(256, HeicDecoder.decode(MediaSource.of(data)).picture().width());
        }

        @Test
        @DisplayName("reject derivation cycles, chains deeper than eight and prediction cycles")
        void hostile() {
            var coded = tile(0, 0, "yuv420p");
            var cycle = heic();
            var first = cycle.item("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
            var second = cycle.item("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
            cycle.reference("dimg", first, second).reference("dimg", second, first).primary(first);
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(cycle.build())));

            var deep = heic();
            var base = still(deep, coded);
            var previous = base;
            for (var i = 0; i < 12; i++) {
                var next = deep.item("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
                deep.reference("dimg", next, previous);
                previous = next;
            }
            deep.primary(previous);
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(deep.build())));

            var predicted = heic();
            var a = still(predicted, coded);
            var b = still(predicted, coded);
            predicted.reference("pred", a, b).reference("pred", b, a).primary(a);
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(predicted.build())));
        }

        @Test
        @DisplayName("stop an overlay that decodes more than four times the picture limit")
        void budget() {
            var coded = encode("scale=256:256", 256, 256, "yuv420p", "");
            var builder = heic();
            var inputs = new int[20];
            for (var i = 0; i < inputs.length; i++) {
                inputs[i] = still(builder, coded);
                builder.hidden(inputs[i]);
            }
            var payload = new ByteArrayOutputStream();
            payload.writeBytes(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 1, 0, 1, 0});
            for (var i = 0; i < inputs.length; i++) {
                payload.writeBytes(new byte[4]);
            }
            var overlay = builder.idatItem("iovl", payload.toByteArray(), plain(HeifBuilder.ispe(256, 256)));
            builder.reference("dimg", overlay, inputs).primary(overlay);
            var limits = MediaLimits.builder().maxPixels(256 * 256 * 2).build();
            assertThrows(MediaException.LimitExceeded.class, () -> HeicDecoder.decode(MediaSource.of(builder.build()),
                    ImageDecoderOptions.builder().limits(limits).build()));
            var composed = HeicDecoder.decode(MediaSource.of(builder.build()));
            assertInstanceOf(RgbaPicture.class, composed.picture());
        }

        @Test
        @DisplayName("report rotation and mirroring as the orientation, crop exactly")
        void transforms() {
            var builder = heic();
            builder.primary(still(builder, tile(0, 0, "yuv420p"), essential(HeifBuilder.clap(33, 1, 40, 1, -1, 2, 0, 1)),
                    essential(HeifBuilder.irot(3)), essential(HeifBuilder.imir(1))));
            var data = builder.build();
            var decoded = HeicDecoder.decode(MediaSource.of(data));
            assertEquals(33, decoded.picture().width());
            assertEquals(ChromaFormat.YUV444, ((YuvPicture) decoded.picture()).chromaFormat());
            var info = HeicDecoder.probe(MediaSource.of(data));
            assertEquals(33, info.width());
            assertEquals(40, info.height());
            assertEquals(decoded.orientation(), info.orientation());
            // a quarter turn clockwise, then a left-right mirror, is a transpose
            assertEquals(Orientation.TRANSPOSE, decoded.orientation());
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        @Test
        @DisplayName("reject other codecs and unknown essential properties")
        void unsupported() {
            var coded = tile(0, 0, "yuv420p");
            var av1 = heic();
            av1.primary(av1.item("av01", new byte[16], plain(HeifBuilder.ispe(64, 64))));
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.decode(MediaSource.of(av1.build())));
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.probe(MediaSource.of(av1.build())));
            var unknown = heic();
            unknown.primary(still(unknown, coded, essential(HeifBuilder.box("zzzz", new byte[4]))));
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.decode(MediaSource.of(unknown.build())));
            var none = heic();
            still(none, coded);
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(none.build())));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(new byte[0])));
        }

        @Test
        @DisplayName("raise only MediaException for truncated and mutated files, in bounded time")
        void mutations() {
            var tiles = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"));
            var builder = heic();
            var ids = new int[2];
            for (var i = 0; i < 2; i++) {
                ids[i] = still(builder, tiles.get(i), essential(HeifBuilder.irot(1)));
            }
            alphaItem(builder, alphaPlane(64, 64, ""), ids[0]);
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64),
                    essential(HeifBuilder.clap(100, 1, 60, 1, 0, 1, 0, 1)));
            builder.reference("dimg", grid, ids).primary(grid);
            var seeds = new ArrayList<byte[]>();
            seeds.add(builder.build());
            for (var path : List.of("tests/images/heif/RGBA_10__29x100.heif", "tests/images/heif/zPug_3.heic",
                    "tests/images/heif/L_8__29x100.heif")) {
                seeds.add(HeicVectors.read(HeicVectors.pillow(path)));
            }
            for (var name : List.of("C025.heic", "multilayer005.heic", "C016.heic")) {
                seeds.add(HeicVectors.read(HeicVectors.nokia(name)));
            }
            var options = ImageDecoderOptions.builder().limits(SMALL).build();
            assertTimeoutPreemptively(Duration.ofMinutes(6), () -> {
                var random = new Random(23);
                var outcomes = new int[2];
                for (var seed : seeds) {
                    for (var length = 0; length < seed.length; length += Math.max(1, seed.length / 150)) {
                        attempt(Arrays.copyOf(seed, length), options, outcomes);
                    }
                    for (var round = 0; round < 150; round++) {
                        var mutated = seed.clone();
                        for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                            mutated[random.nextInt(Math.min(mutated.length, round % 2 == 0 ? 1500 : mutated.length))] =
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
                HeicDecoder.probe(MediaSource.of(data));
                HeicDecoder.decode(MediaSource.of(data), options);
                try (var reader = HeicDecoder.openAnimation(MediaSource.of(data), options)) {
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pillow:tests/images/heif_other/pug.heic", "pillow:tests/images/heif_other/arrow.heic",
            "pillow:tests/images/heif/RGBA_8__128x128.heif", "pillow:tests/images/heif/L_8__29x100.heif",
            "nokia:MIAF007.heic", "nokia:C042.heic", "nokia:C044.heic"})
    @DisplayName("Media.probe reports what the decoder decodes")
    void probe(String vector) {
        var path = vector.startsWith("nokia:") ? HeicVectors.nokia(vector.substring(6))
                : HeicVectors.pillow(vector.substring(7));
        var data = HeicVectors.read(path);
        var media = Media.probe(MediaSource.of(data));
        assertEquals(ImageFormat.HEIC, media.imageFormat().orElseThrow());
        var image = media.image().orElseThrow();
        var expected = HeicDecoder.probe(MediaSource.of(data));
        assertEquals(ImageFormat.HEIC, image.format());
        assertEquals(expected.width(), image.width(), "width");
        assertEquals(expected.height(), image.height(), "height");
        assertEquals(expected.orientation(), image.orientation(), "orientation");
        assertEquals(expected.hasAlpha(), image.hasAlpha(), "alpha");
        assertFalse(image.isAnimated());
    }
}
