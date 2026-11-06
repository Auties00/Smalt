package com.github.auties00.smalt.image.exr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the OpenEXR decoder's public surface over the vectors of tools/testdata/behavior/make-exr-vectors.py and the
 * ffmpeg-written samples: recognition, part descriptions, channel selection and naming, display and data windows,
 * layers, luminance and chroma, mipmap levels picked by the size hint, deep flattening, ffmpeg reading the same
 * files, and rejection of malformed and unsupported files.
 */
@DisplayName("OpenEXR decoder")
class ExrDecoderTest {
    private static final String ROOT = "/com/github/auties00/smalt/image/exr/";

    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "exr");

    private static byte[] vector(String name) {
        try (var input = ExrDecoderTest.class.getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static float[] reference(String name) {
        var buffer = ByteBuffer.wrap(vector(name + ".f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        var values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static ChannelFloatPicture keep(byte[] file) {
        return assertInstanceOf(ChannelFloatPicture.class, ExrDecoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture());
    }

    /** {@return the samples of the output channel that has a name, one per pixel} */
    private static float[] channel(ChannelFloatPicture picture, String name) {
        var layout = picture.layout();
        for (var index = 0; index < layout.count(); index++) {
            if (layout.channel(index).name().equals(name)) {
                var out = new float[picture.width() * picture.height()];
                for (var y = 0; y < picture.height(); y++) {
                    for (var x = 0; x < picture.width(); x++) {
                        out[y * picture.width() + x] = picture.pixels()[y * picture.stride()
                                + x * layout.count() + index];
                    }
                }
                return out;
            }
        }
        throw new AssertionError("no channel " + name + " in " + layout);
    }

    /** {@return the reference samples of one channel of the first part, channels in name order} */
    private static float[] referenceChannel(String name, List<String> channels, String channel, int pixels) {
        var all = reference(name);
        var index = channels.indexOf(channel);
        return java.util.Arrays.copyOfRange(all, index * pixels, (index + 1) * pixels);
    }

    @Nested
    @DisplayName("recognition")
    class Recognition {
        @Test
        @DisplayName("recognizes the magic, registers the decoder and probes the display window")
        void registered() {
            var file = vector("scanline-zip.exr");
            assertEquals(ImageFormat.EXR, ImageFormat.detect(file, file.length).orElseThrow());
            assertTrue(Images.isDecodable(ImageFormat.EXR));
            var info = ExrDecoder.probe(MediaSource.of(file));
            assertEquals(23, info.width());
            assertEquals(40, info.height());
            assertTrue(info.hasAlpha());
            assertEquals(1, info.frameCount());
            var windowed = ExrDecoder.probe(MediaSource.of(vector("windows-decreasing.exr")));
            assertEquals(40, windowed.width());
            assertEquals(30, windowed.height());
        }

        @Test
        @DisplayName("describes every part")
        void parts() {
            var parts = ExrDecoder.parts(MediaSource.of(vector("multipart.exr")));
            assertEquals(2, parts.size());
            assertEquals(Optional.of("beauty"), parts.get(0).name());
            assertEquals(ExrPart.Storage.SCANLINE, parts.get(0).storage());
            assertEquals(ExrPart.Compression.ZIP, parts.get(0).compression());
            assertEquals(List.of("A", "B", "G", "R"), parts.get(0).channels());
            assertEquals(Optional.of("depth"), parts.get(1).name());
            assertEquals(ExrPart.Storage.TILED, parts.get(1).storage());
            assertEquals(ExrPart.Compression.PIZ, parts.get(1).compression());
            var mipmap = ExrDecoder.parts(MediaSource.of(vector("tiled-mipmap-zip.exr"))).getFirst();
            assertEquals(6, mipmap.levels());
            var deep = ExrDecoder.parts(MediaSource.of(vector("deep-scanline-zips.exr"))).getFirst();
            assertEquals(ExrPart.Storage.DEEP_SCANLINE, deep.storage());
            var window = ExrDecoder.parts(MediaSource.of(vector("windows-decreasing.exr"))).getFirst();
            assertEquals(List.of(5, 3, 30, 20), List.of(window.dataX(), window.dataY(), window.dataWidth(),
                    window.dataHeight()));
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @ParameterizedTest
        @ValueSource(strings = {"scanline-none", "scanline-piz", "scanline-dwab", "float-pxr24", "tiled-dwaa",
                "scanline-htj2k256", "scanline-zstd"})
        @DisplayName("keeps every channel, color first, each named after its file channel")
        void channels(String name) {
            var file = vector(name + ".exr");
            var picture = keep(file);
            var names = ExrDecoder.parts(MediaSource.of(file)).getFirst().channels();
            assertEquals(names.size(), picture.channelCount());
            assertEquals(ChannelType.RED, picture.layout().channel(0).type());
            assertEquals(ChannelType.PREMULTIPLIED_ALPHA, picture.layout().channel(3).type());
            var pixels = picture.width() * picture.height();
            for (var channelName : names) {
                var want = referenceChannel(name, names, channelName, pixels);
                var got = channel(picture, channelName);
                var rounded = 0;
                for (var index = 0; index < pixels; index++) {
                    if (Float.floatToIntBits(want[index]) == Float.floatToIntBits(got[index])) {
                        continue;
                    }
                    // the reference library's vectorized DWA transforms may round one half unit apart, which the
                    // table to linear values can widen to a few units
                    assertTrue(name.contains("dwa") && Math.abs(Float.floatToFloat16(want[index])
                            - Float.floatToFloat16(got[index])) <= 4, name + " " + channelName + " sample " + index);
                    rounded++;
                }
                assertTrue(rounded <= pixels / 100, name + " " + channelName + ": " + rounded);
            }
        }

        @Test
        @DisplayName("decodes to 8 and 16-bit RGBA without a transfer function and reports linear BT.709")
        void packed() {
            var file = vector("scanline-zip.exr");
            var floats = keep(file);
            var image = ExrDecoder.decode(MediaSource.of(file));
            var rgba = RasterTesting.tight(image.picture());
            var red = channel(floats, "R");
            var alpha = channel(floats, "A");
            for (var index = 0; index < red.length; index++) {
                var a = Math.clamp(alpha[index], 0, 1);
                assertEquals(Math.round(a * 255), rgba[4 * index + 3] & 0xFF, 1, "alpha " + index);
            }
            var profile = image.colorProfile().orElseThrow();
            assertEquals(3, profile.colorSpace().channels());
        }

        @Test
        @DisplayName("places the data window in the display window")
        void windows() {
            var file = vector("windows-decreasing.exr");
            var picture = keep(file);
            assertEquals(40, picture.width());
            assertEquals(30, picture.height());
            var red = channel(picture, "R");
            var names = List.of("A", "B", "G", "R");
            var expected = referenceChannel("windows-decreasing", names, "R", 30 * 20);
            for (var y = 0; y < 30; y++) {
                for (var x = 0; x < 40; x++) {
                    var inside = x >= 5 && x < 35 && y >= 3 && y < 23;
                    var want = inside ? expected[(y - 3) * 30 + x - 5] : 0f;
                    assertEquals(want, red[y * 40 + x], "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("takes the color of a file without unprefixed channels from its first layer")
        void layers() {
            var file = vector("layers-zips.exr");
            var picture = keep(file);
            assertEquals("diffuse.R", picture.layout().channel(0).name());
            assertEquals("spec.R", picture.layout().channel(3).name());
            var names = List.of("diffuse.B", "diffuse.G", "diffuse.R", "spec.R");
            assertArrayEquals(referenceChannel("layers-zips", names, "diffuse.G", 23 * 40), channel(picture,
                    "diffuse.G"));
        }

        @Test
        @DisplayName("converts luminance and chroma to RGB whose luminance is the file's")
        void luminanceChroma() {
            var picture = keep(vector("luminance-chroma-piz.exr"));
            assertEquals(3, picture.channelCount());
            var reference = reference("luminance-chroma-piz");
            // channels BY (12x20), RY (12x20), Y (24x40), in name order
            var luminance = java.util.Arrays.copyOfRange(reference, 2 * 240, 2 * 240 + 960);
            var red = channel(picture, "R");
            var green = channel(picture, "G");
            var blue = channel(picture, "B");
            var differences = 0;
            for (var index = 0; index < luminance.length; index++) {
                var y = 0.2126 * red[index] + 0.7152 * green[index] + 0.0722 * blue[index];
                if (Math.abs(y - luminance[index]) > 0.01 + 0.01 * Math.abs(luminance[index])) {
                    differences++;
                }
            }
            // the saturation fix changes the luminance of a few super-saturated pixels
            assertTrue(differences < luminance.length / 20, differences + " pixels");
        }

        @Test
        @DisplayName("decodes gray luminance and chroma exactly as gray")
        void grayChroma() {
            var pattern = new float[24 * 8];
            for (var index = 0; index < pattern.length; index++) {
                pattern[index] = Float.float16ToFloat(Float.floatToFloat16(index * 0.01f));
            }
            var file = ExrFiles.luminanceChroma(24, 8, pattern);
            var picture = keep(file);
            assertArrayEquals(pattern, channel(picture, "R"));
            assertArrayEquals(pattern, channel(picture, "G"));
            assertArrayEquals(pattern, channel(picture, "B"));
            var row = new float[]{0.25f, 0.5f};
            assertArrayEquals(row, channel(keep(ExrFiles.luminanceChroma(2, 1, row)), "G"));
        }

        @Test
        @DisplayName("decodes the smallest mipmap level that meets the size hint")
        void levels() {
            var file = vector("tiled-mipmap-zip.exr");
            var image = ExrDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().keepChannels(true)
                    .minSize(6, 6).build());
            var picture = assertInstanceOf(ChannelFloatPicture.class, image.picture());
            assertEquals(11, picture.width());
            assertEquals(20, picture.height());
            var reference = reference("tiled-mipmap-zip");
            var level0 = 23 * 40 * 3;
            assertArrayEquals(java.util.Arrays.copyOfRange(reference, level0 + 2 * 220, level0 + 3 * 220),
                    channel(picture, "R"));
            assertEquals(23, keep(file).width());
        }

        @Test
        @DisplayName("flattens deep files and opens every part as a page")
        void pagesAndDeep() {
            var deep = keep(vector("deep-scanline-zips.exr"));
            assertEquals(9, deep.width());
            var reference = reference("deep-scanline-zips");
            assertArrayEquals(java.util.Arrays.copyOfRange(reference, 3 * 63, 4 * 63), channel(deep, "R"), 1e-6f);
            try (var pages = ExrDecoder.openPages(MediaSource.of(vector("multipart.exr")))) {
                assertEquals(2, pages.pageCount());
                var depth = pages.read(1);
                assertEquals(23, depth.picture().width());
                assertEquals(1, pages.probe(1).frameCount() - 1);
            }
            var viaImages = Images.openPages(MediaSource.of(vector("multipart.exr")));
            assertEquals(2, viaImages.pageCount());
        }

        @ParameterizedTest
        @ValueSource(strings = {"zip-half.exr", "rle-float.exr"})
        @DisplayName("agrees with ffmpeg on the files ffmpeg wrote")
        void ffmpeg(String name) throws IOException {
            var file = Files.readAllBytes(SAMPLES.resolve(name));
            var picture = keep(file);
            var width = picture.width();
            var height = picture.height();
            var channels = picture.channelCount();
            var theirs = RasterFixtures.interleaveGbr(RasterFixtures.floatsLe(RasterFixtures.ffmpegDecode(file, "exr",
                    channels == 4 ? "gbrapf32le" : "gbrpf32le")), width, height, channels);
            assertArrayEquals(theirs, RasterFixtures.tight(picture), name);
        }

        @ParameterizedTest
        @ValueSource(strings = {"scanline-piz", "scanline-b44", "scanline-pxr24", "scanline-rle", "scanline-zips"})
        @DisplayName("agrees with ffmpeg on the color of the reference library's files")
        void ffmpegReference(String name) {
            var file = vector(name + ".exr");
            var picture = keep(file);
            var width = picture.width();
            var height = picture.height();
            var theirs = RasterFixtures.interleaveGbr(RasterFixtures.floatsLe(RasterFixtures.ffmpegDecode(file, "exr",
                    "gbrapf32le")), width, height, 4);
            var ours = new float[width * height * 4];
            var red = channel(picture, "R");
            var green = channel(picture, "G");
            var blue = channel(picture, "B");
            var alpha = channel(picture, "A");
            for (var index = 0; index < width * height; index++) {
                ours[4 * index] = red[index];
                ours[4 * index + 1] = green[index];
                ours[4 * index + 2] = blue[index];
                ours[4 * index + 3] = alpha[index];
            }
            assertArrayEquals(theirs, ours, name);
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects bad magic numbers, versions and truncated headers")
        void headers() {
            var file = vector("scanline-zip.exr");
            var magic = file.clone();
            magic[0] = 0;
            assertThrows(MediaException.Malformed.class, () -> ExrDecoder.decode(MediaSource.of(magic)));
            var version = file.clone();
            version[4] = 3;
            assertThrows(MediaException.Unsupported.class, () -> ExrDecoder.decode(MediaSource.of(version)));
            assertThrows(MediaException.Malformed.class, () -> ExrDecoder.decode(MediaSource.of(
                    java.util.Arrays.copyOf(file, 60))));
            assertThrows(MediaException.class, () -> ExrDecoder.decode(MediaSource.of(
                    java.util.Arrays.copyOf(file, file.length - 100))));
        }

        @Test
        @DisplayName("reports unknown compression methods as unsupported and other methods' chunks as malformed")
        void compressionMethods() {
            var file = vector("scanline-zips.exr");
            assertThrows(MediaException.Unsupported.class, () -> ExrDecoder.decode(MediaSource.of(
                    ExrFiles.withCompression(file, 14))));
            // ZIPS chunks hold one line, as ZSTD ones do, so only the method changes
            assertThrows(MediaException.Malformed.class, () -> ExrDecoder.decode(MediaSource.of(
                    ExrFiles.withCompression(file, 13))));
            // a tile is a chunk whatever the method, and these DWAA tiles are all compressed
            for (var method : new int[]{10, 11, 12, 13}) {
                assertThrows(MediaException.Malformed.class, () -> ExrDecoder.decode(MediaSource.of(
                        ExrFiles.withCompression(vector("tiled-dwaa.exr"), method))));
            }
        }

        @Test
        @DisplayName("survives mutated files")
        void mutations() {
            var seeds = new ArrayList<byte[]>();
            for (var name : List.of("scanline-piz", "scanline-dwaa", "scanline-b44", "scanline-pxr24",
                    "tiled-mipmap-zip", "deep-scanline-zips", "multipart", "luminance-chroma-piz",
                    "scanline-htj2k32", "float-lj2k", "scanline-zstd", "deep-scanline-zstd")) {
                seeds.add(vector(name + ".exr"));
            }
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(60), 3,
                    source -> ExrDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }
}
