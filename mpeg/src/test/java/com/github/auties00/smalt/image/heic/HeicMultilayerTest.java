package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.internal.StillDecoder;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HevcAccessUnit;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static com.github.auties00.smalt.testkit.HevcAccessUnit.lengthPrefixed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes layered HEVC image items ({@code lhv1}): Nokia's multilayer conformance files, whose expected pictures the
 * SHM reference decoder of the scalable and multiview extensions gave
 * ({@code tools/testdata/hevc-multilayer/make-references.py}), and files built around the first access unit of the
 * multi-layer streams kept with the HEVC tests ({@code video/hevc/multilayer}), whose expected pictures are SHM's
 * decode of those streams. The built files cover an SNR or spatial enhancement preferred by an alternatives group, the
 * views of a multiview item chosen by a layer selector, an enhancement over an AVC-coded base item ({@code exbl}) and
 * the fallback to the primary item when the layered item cannot be reconstructed.
 */
class HeicMultilayerTest {
    private static final String VECTORS = "/com/github/auties00/smalt/video/hevc/multilayer/";

    @Nested
    @DisplayName("Nokia's multilayer conformance files")
    class Nokia {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "multilayer001.heic, 1024, 512, d088afd5231e03ba2ca19ed296196f40",
                "multilayer004.heic, 1024, 512, 85173ebd9fb422ca0be9f53faa8f845f"
        })
        @DisplayName("show the layered enhancement their alternatives group prefers, like SHM decodes it")
        void preferred(String name, int width, int height, String md5) {
            var source = MediaSource.of(HeicVectors.read(HeicVectors.nokia(name)));
            var picture = HeicDecoder.decode(source).picture();
            assertEquals(width, picture.width());
            assertEquals(height, picture.height());
            assertEquals(md5, md5(raw(picture)), name);
            var info = HeicDecoder.probe(source);
            assertEquals(width, info.width());
            assertEquals(height, info.height());
        }

        @Test
        @DisplayName("the right view of the stereo pair decodes from the layered item its layer selector names")
        void stereo() {
            var source = MediaSource.of(HeicVectors.read(HeicVectors.nokia("multilayer005.heic")));
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var picture = StillDecoder.decode(source, file, file.item(20004), MediaLimits.DEFAULT, false).picture();
            assertEquals(512, picture.width());
            assertEquals(256, picture.height());
            assertEquals("b0f6cbae9f5d402cd0ce332ef80463e2", md5(raw(picture)));
            var right = HeicDecoder.decodeStereoView(source, 1).orElseThrow().picture();
            assertEquals("b0f6cbae9f5d402cd0ce332ef80463e2", md5(raw(right)), "the public right view");
            var left = HeicDecoder.decodeStereoView(source, 0).orElseThrow().picture();
            assertEquals(md5(raw(HeicDecoder.decode(source).picture())), md5(raw(left)),
                    "the left view is the primary image");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "nokia, multilayer003.heic, 1002, 1004",
                "pillow, tests/images/heif_other/spatial_photo.heic, 26, 52"
        })
        @DisplayName("a stereo pair of two HEVC images, or of two grids as in a spatial photo, gives each view")
        void stereoItems(String origin, String name, int leftItem, int rightItem) {
            var path = origin.equals("nokia") ? HeicVectors.nokia(name) : HeicVectors.pillow(name);
            var source = MediaSource.of(HeicVectors.read(path));
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var views = new int[]{leftItem, rightItem};
            var md5s = new String[2];
            for (var view = 0; view < 2; view++) {
                var expected = StillDecoder.decode(source, file, file.item(views[view]), MediaLimits.DEFAULT, false)
                        .picture();
                var actual = HeicDecoder.decodeStereoView(source, view).orElseThrow().picture();
                assertEquals(expected.width(), actual.width(), "view " + view);
                assertEquals(expected.height(), actual.height(), "view " + view);
                md5s[view] = rgbaMd5(actual);
                assertEquals(rgbaMd5(expected), md5s[view], "view " + view);
            }
            assertTrue(!md5s[0].equals(md5s[1]), "the two views differ");
        }

        @Test
        @DisplayName("a file without a stereo pair has no stereo view, and only views 0 and 1 exist")
        void noStereoPair() {
            var source = MediaSource.of(HeicVectors.read(HeicVectors.nokia("multilayer001.heic")));
            assertTrue(HeicDecoder.decodeStereoView(source, 0).isEmpty());
            assertTrue(HeicDecoder.decodeStereoView(source, 1).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> HeicDecoder.decodeStereoView(source, 2));
            assertThrows(IllegalArgumentException.class, () -> HeicDecoder.decodeStereoView(source, -1));
        }

        @Test
        @DisplayName("the HEVC base item of the SNR file still decodes on its own, like ffmpeg decodes its data")
        void base() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = MediaSource.of(HeicVectors.read(HeicVectors.nokia("multilayer001.heic")));
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var item = file.item(20003);
            var picture = StillDecoder.decode(source, file, item, MediaLimits.DEFAULT, false).picture();
            var expected = HeicReference.item(source, file, item, 1024, 512, ChromaFormat.YUV420, 8);
            HeicStructureTest.assertPlanes(expected, HeicAvcTest.planes(picture));
        }
    }

    @Nested
    @DisplayName("files built around the SHM streams")
    class Built {
        @Test
        @DisplayName("a spatial enhancement preferred by the alternatives group shows at its own size")
        void spatial() {
            var unit = HevcAccessUnit.first(stream("shvc-2x-ld.hevc"));
            var builder = heic();
            var base = baseItem(builder, unit, 64, 48);
            var layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1);
            alternatives(builder, layered, base).primary(base);
            var source = MediaSource.of(builder.build());
            var picture = HeicDecoder.decode(source).picture();
            assertEquals(expectedMd5("shvc-2x-ld", 1), md5(raw(picture)));
            assertEquals(128, HeicDecoder.probe(source).width());
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var basePicture = StillDecoder.decode(source, file, file.item(base), MediaLimits.DEFAULT, false).picture();
            assertEquals(expectedMd5("shvc-2x-ld", 0), md5(raw(basePicture)));
        }

        @Test
        @DisplayName("a stereo pair of the base view and the layered second view gives each view like SHM")
        void stereoPair() {
            var unit = HevcAccessUnit.first(stream("mvhevc-ld.hevc"));
            var builder = heic();
            var left = baseItem(builder, unit, 128, 96);
            var right = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1,
                    essential(HeifBuilder.box("lsel", new byte[]{0, 1})));
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("ster", 100, left, right))).primary(left);
            var source = MediaSource.of(builder.build());
            assertEquals(expectedMd5("mvhevc-ld", 0), md5(raw(HeicDecoder.decode(source).picture())));
            assertEquals(expectedMd5("mvhevc-ld", 0), md5(raw(HeicDecoder.decodeStereoView(source, 0).orElseThrow()
                    .picture())));
            assertEquals(expectedMd5("mvhevc-ld", 1), md5(raw(HeicDecoder.decodeStereoView(source, 1).orElseThrow()
                    .picture())));
            // a pair that names an item the file lacks
            var broken = heic();
            var only = baseItem(broken, unit, 128, 96);
            broken.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("ster", 100, only, 999))).primary(only);
            var brokenSource = MediaSource.of(broken.build());
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decodeStereoView(brokenSource, 1));
            assertEquals(expectedMd5("mvhevc-ld", 0), md5(raw(HeicDecoder.decodeStereoView(brokenSource, 0)
                    .orElseThrow().picture())));
        }

        @Test
        @DisplayName("an enhancement of another bit depth keeps it when a deep decode is asked for")
        void bitDepth() {
            var unit = HevcAccessUnit.first(stream("shvc-snr-8-10.hevc"));
            var builder = heic();
            var base = baseItem(builder, unit, 128, 96);
            var layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1);
            alternatives(builder, layered, base).primary(base);
            var source = MediaSource.of(builder.build());
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var picture = StillDecoder.decode(source, file, file.item(layered), MediaLimits.DEFAULT, true).picture();
            assertEquals(10, picture.bitDepth());
            assertEquals(expectedMd5("shvc-snr-8-10", 1), md5(raw(picture)));
        }

        @ParameterizedTest(name = "layer selector {0}")
        @CsvSource({"0, 0", "1, 1", "-1, 1"})
        @DisplayName("the layer selector of a multiview item picks its view, the highest output layer without one")
        void views(int selector, int layer) {
            var unit = HevcAccessUnit.first(stream("mvhevc-ld.hevc"));
            var builder = heic();
            var base = baseItem(builder, unit, 128, 96);
            var extra = selector < 0 ? new HeifBuilder.Property[0]
                    : new HeifBuilder.Property[]{essential(HeifBuilder.box("lsel", new byte[]{0, (byte) selector}))};
            var layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1, extra);
            builder.hidden(layered).primary(base);
            var source = MediaSource.of(builder.build());
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var picture = StillDecoder.decode(source, file, file.item(layered), MediaLimits.DEFAULT, false).picture();
            assertEquals(expectedMd5("mvhevc-ld", layer), md5(raw(picture)));
        }

        @Test
        @DisplayName("an enhancement over an AVC-coded base item takes that item's picture as its base layer")
        void avcBase() {
            var unit = HevcAccessUnit.first(stream("shvc-avcbase-2x-ld.hevc"));
            var avc = AvcUnit.first(stream("shvc-avcbase-2x-ld.264"));
            var builder = heic();
            var base = avcItem(builder, avc);
            var layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1);
            builder.reference("exbl", layered, base);
            alternatives(builder, layered, base).primary(base);
            var source = MediaSource.of(builder.build());
            assertEquals(expectedMd5("shvc-avcbase-2x-ld", 1), md5(raw(HeicDecoder.decode(source).picture())));
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var basePicture = StillDecoder.decode(source, file, file.item(base), MediaLimits.DEFAULT, false).picture();
            assertEquals(expectedMd5("shvc-avcbase-2x-ld", 0), md5(raw(basePicture)));
        }

        @Test
        @DisplayName("a layered item whose base layer is coded outside it without an exbl reference falls back")
        void missingExternalBase() {
            var unit = HevcAccessUnit.first(stream("shvc-avcbase-2x-ld.hevc"));
            var avc = AvcUnit.first(stream("shvc-avcbase-2x-ld.264"));
            var builder = heic();
            var base = avcItem(builder, avc);
            var layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1);
            alternatives(builder, layered, base).primary(base);
            var source = MediaSource.of(builder.build());
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            assertThrows(MediaException.Unsupported.class,
                    () -> StillDecoder.decode(source, file, file.item(layered), MediaLimits.DEFAULT, false));
            assertEquals(expectedMd5("shvc-avcbase-2x-ld", 0), md5(raw(HeicDecoder.decode(source).picture())));
        }

        @Test
        @DisplayName("a layered item that cannot be reconstructed leaves the primary item shown")
        void fallback() {
            var unit = HevcAccessUnit.first(stream("shvc-2x-ld.hevc"));
            var cases = new ArrayList<byte[]>();
            // the item's data lacks the base layer its enhancement predicts from
            var builder = heic();
            var base = baseItem(builder, unit, 64, 48);
            var layered = layeredItem(builder, unit, unit.slices(layer -> layer == 1), 128, 96, 1);
            alternatives(builder, layered, base).primary(base);
            cases.add(builder.build());
            // the item has no target output layer set
            builder = heic();
            base = baseItem(builder, unit, 64, 48);
            var payload = lengthPrefixed(unit.slices(any()));
            layered = builder.item("lhv1", payload, plain(HeifBuilder.ispe(128, 96)),
                    essential(HeifBuilder.box("lhvC", unit.lhvC())));
            alternatives(builder, layered, base).primary(base);
            cases.add(builder.build());
            // the item's configuration record is cut short
            builder = heic();
            base = baseItem(builder, unit, 64, 48);
            layered = builder.item("lhv1", payload, plain(HeifBuilder.ispe(128, 96)),
                    essential(HeifBuilder.box("lhvC", Arrays.copyOf(unit.lhvC(), 9))), tols(1));
            alternatives(builder, layered, base).primary(base);
            cases.add(builder.build());
            // the layer selector names a layer the output layer set does not output
            builder = heic();
            base = baseItem(builder, unit, 64, 48);
            layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1,
                    essential(HeifBuilder.box("lsel", new byte[]{0, 5})));
            alternatives(builder, layered, base).primary(base);
            cases.add(builder.build());
            // the external base layer is not AVC-coded
            builder = heic();
            base = baseItem(builder, unit, 64, 48);
            layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1);
            builder.reference("exbl", layered, base);
            alternatives(builder, layered, base).primary(base);
            cases.add(builder.build());
            for (var i = 0; i < cases.size(); i++) {
                var source = MediaSource.of(cases.get(i));
                var file = HeifFile.parse(source, MediaLimits.DEFAULT);
                var item = file.item(layered);
                var index = i;
                assertThrows(MediaException.class,
                        () -> StillDecoder.decode(source, file, item, MediaLimits.DEFAULT, false), "case " + index);
                assertEquals(expectedMd5("shvc-2x-ld", 0), md5(raw(HeicDecoder.decode(source).picture())),
                        "case " + i + " shows the primary item");
            }
        }

        @Test
        @DisplayName("damaged files built around layered items fail only with media exceptions")
        void damaged() {
            var unit = HevcAccessUnit.first(stream("shvc-2x-ld.hevc"));
            var builder = heic();
            var base = baseItem(builder, unit, 64, 48);
            var layered = layeredItem(builder, unit, unit.slices(any()), 128, 96, 1);
            alternatives(builder, layered, base).primary(base);
            var seed = builder.build();
            var random = new Random(7);
            assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
                for (var length = 0; length < seed.length; length += Math.max(1, seed.length / 100)) {
                    attempt(Arrays.copyOf(seed, length));
                }
                for (var round = 0; round < 200; round++) {
                    var mutated = seed.clone();
                    for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                        mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                    }
                    attempt(mutated);
                }
            });
        }

        private static void attempt(byte[] data) {
            try {
                HeicDecoder.probe(MediaSource.of(data));
                var picture = HeicDecoder.decode(MediaSource.of(data)).picture();
                assertTrue(picture.width() > 0 && picture.height() > 0);
            } catch (MediaException exception) {
                // rejected, as a damaged file may be
            }
        }
    }

    private static HeifBuilder heic() {
        return new HeifBuilder().brands("heic", "heic", "mif1");
    }

    private static int baseItem(HeifBuilder builder, HevcAccessUnit unit, int width, int height) {
        var payload = lengthPrefixed(unit.slices(layer -> layer == 0));
        return builder.item("hvc1", payload, plain(HeifBuilder.ispe(width, height)),
                essential(HeifBuilder.box("hvcC", unit.hvcC())));
    }

    private static int avcItem(HeifBuilder builder, AvcUnit avc) {
        return builder.item("avc1", lengthPrefixed(avc.slices()), plain(HeifBuilder.ispe(64, 48)),
                essential(HeifBuilder.box("avcC", avc.avcC())));
    }

    private static int layeredItem(HeifBuilder builder, HevcAccessUnit unit, List<byte[]> data, int width, int height,
                                   int outputLayerSet, HeifBuilder.Property... extra) {
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(width, height)),
                essential(HeifBuilder.box("lhvC", unit.lhvC())), tols(outputLayerSet)));
        properties.addAll(List.of(extra));
        return builder.item("lhv1", lengthPrefixed(data), properties.toArray(new HeifBuilder.Property[0]));
    }

    private static HeifBuilder.Property tols(int outputLayerSet) {
        var index = new byte[]{(byte) (outputLayerSet >> 8), (byte) outputLayerSet};
        return essential(HeifBuilder.fullBox("tols", 0, 0, index));
    }

    private static HeifBuilder alternatives(HeifBuilder builder, int preferred, int other) {
        return builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 100, preferred, other)));
    }

    private static IntPredicate any() {
        return layer -> true;
    }

    /**
     * The NAL units of the first access unit of an Annex B AVC stream.
     */
    private record AvcUnit(List<byte[]> sequenceSets, List<byte[]> pictureSets, List<byte[]> slices) {
        static AvcUnit first(byte[] stream) {
            var sequenceSets = new ArrayList<byte[]>();
            var pictureSets = new ArrayList<byte[]>();
            var slices = new ArrayList<byte[]>();
            for (var unit : HevcAccessUnit.nalUnits(stream)) {
                var type = unit[0] & 0x1F;
                if (type == 7) {
                    sequenceSets.add(unit);
                } else if (type == 8) {
                    pictureSets.add(unit);
                } else if (type == 1 || type == 5) {
                    if (!slices.isEmpty() && (unit[1] & 0x80) != 0) {
                        break;
                    }
                    slices.add(unit);
                }
            }
            return new AvcUnit(sequenceSets, pictureSets, slices);
        }

        byte[] avcC() {
            var sps = sequenceSets.getFirst();
            var out = new ByteArrayOutputStream();
            out.write(1);
            out.write(sps[1]);
            out.write(sps[2]);
            out.write(sps[3]);
            out.write(0xFF);
            out.write(0xE0 | sequenceSets.size());
            for (var set : sequenceSets) {
                out.write(set.length >> 8);
                out.write(set.length);
                out.writeBytes(set);
            }
            out.write(pictureSets.size());
            for (var set : pictureSets) {
                out.write(set.length >> 8);
                out.write(set.length);
                out.writeBytes(set);
            }
            return out.toByteArray();
        }
    }

    private static byte[] stream(String name) {
        try (var input = HeicMultilayerTest.class.getResourceAsStream(VECTORS + name)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String expectedMd5(String name, int layer) {
        var table = new String(stream("multilayer.tsv"), StandardCharsets.US_ASCII);
        for (var line : table.split("\n")) {
            var columns = line.strip().split("\t");
            if (columns[0].equals(name) && columns[1].equals(Integer.toString(layer))) {
                return columns[6].split(",")[0];
            }
        }
        throw new IllegalArgumentException(name + " layer " + layer);
    }

    private static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case YuvPicture p -> {
                for (var y = 0; y < p.height(); y++) {
                    out.write(p.y(), y * p.yStride(), p.width());
                }
                for (var y = 0; y < p.chromaHeight(); y++) {
                    out.write(p.u(), y * p.uvStride(), p.chromaWidth());
                }
                for (var y = 0; y < p.chromaHeight(); y++) {
                    out.write(p.v(), y * p.uvStride(), p.chromaWidth());
                }
            }
            case com.github.auties00.smalt.image.Yuv16Picture p -> {
                shorts(out, p.y(), p.yStride(), p.width(), p.height());
                shorts(out, p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                shorts(out, p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
            }
            default -> throw new AssertionError("expected a planar picture, got " + picture);
        }
        return out.toByteArray();
    }

    private static void shorts(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var sample = plane[y * stride + x];
                out.write(sample & 0xFF);
                out.write((sample >> 8) & 0xFF);
            }
        }
    }

    /** Returns the MD5 of a picture of any kind converted to 8-bit RGBA, row after row. */
    private static String rgbaMd5(Picture picture) {
        var rgba = com.github.auties00.smalt.image.ops.ImageOps.toRgba(picture);
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < rgba.height(); y++) {
            out.write(rgba.pixels(), y * rgba.stride(), rgba.width() * 4);
        }
        return md5(out.toByteArray());
    }

    private static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
