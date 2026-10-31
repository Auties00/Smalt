package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Photoshop composite decoder: every color mode, depth and compression of documents built from known
 * planes, PSB's wider fields, the transparency rule of the layer count, extra channels, the image resources, ffmpeg
 * reading the same documents, ImageMagick's documents, and rejection of malformed and unsupported files.
 */
@DisplayName("Photoshop decoder")
class PsdDecoderTest {
    private static final int WIDTH = 23;
    private static final int HEIGHT = 9;

    private static byte[][] planes(int channels, int bytesPerSample, long seed) {
        var random = new Random(seed);
        var planes = new byte[channels][WIDTH * HEIGHT * bytesPerSample];
        for (var plane : planes) {
            for (var index = 0; index < plane.length; index++) {
                plane[index] = (byte) (index % 7 < 3 ? 40 : random.nextInt(256));
            }
        }
        return planes;
    }

    private static int sample(byte[] plane, int index, int bytes) {
        return bytes == 1 ? plane[index] & 0xFF : (plane[2 * index] & 0xFF) << 8 | (plane[2 * index + 1] & 0xFF);
    }

    @ParameterizedTest
    @CsvSource({"1,0", "1,1", "1,2", "1,3", "2,0", "2,1", "2,3"})
    @DisplayName("decodes 8 and 16-bit RGB with transparency in every compression and version")
    void rgb(int version, int compression) {
        for (var depth : new int[]{8, 16}) {
            var bytes = depth / 8;
            var planes = planes(4, bytes, depth + compression);
            var file = new PsdFiles(version, WIDTH, HEIGHT, depth, 3).layerCount(-1).build(compression, planes);
            assertEquals(ImageFormat.PSD, ImageFormat.detect(file, file.length).orElseThrow());
            var image = PsdDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().highBitDepth(true)
                    .build());
            assertTrue(image.picture().hasAlpha());
            for (var index = 0; index < WIDTH * HEIGHT; index++) {
                for (var channel = 0; channel < 4; channel++) {
                    var expected = sample(planes[channel], index, bytes);
                    var actual = depth == 8 ? RasterTesting.tight(image.picture())[4 * index + channel] & 0xFF
                            : ((Rgba16Picture) image.picture()).pixels()[4 * index + channel] & 0xFFFF;
                    assertEquals(expected, actual, "depth " + depth + " texel " + index + " channel " + channel);
                }
            }
        }
    }

    @Test
    @DisplayName("treats the extra channel as an alpha channel unless the layer count is negative")
    void extraChannels() {
        var planes = planes(5, 1, 3);
        var opaque = new PsdFiles(1, WIDTH, HEIGHT, 8, 3).build(1, planes);
        var image = PsdDecoder.decode(MediaSource.of(opaque));
        assertFalse(image.picture().hasAlpha());
        var names = ByteBuffer.allocate(4 + 2 * 3 + 4 + 2 * 4).putInt(3).putChar('A').putChar('b').putChar('\0')
                .putInt(4).putChar('S').putChar('p').putChar('t').putChar('\0').array();
        var display = new byte[4 + 2 * 13];
        display[4 + 13 + 12] = 2;
        var kept = assertInstanceOf(Channel8Picture.class, PsdDecoder.decode(MediaSource.of(new PsdFiles(1, WIDTH,
                HEIGHT, 8, 3).resource(1045, names).resource(1077, display).build(0, planes)),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture());
        assertEquals(5, kept.channelCount());
        assertEquals(ChannelType.SELECTION_MASK, kept.layout().channel(3).type());
        assertEquals("Ab", kept.layout().channel(3).name());
        assertEquals(ChannelType.SPOT, kept.layout().channel(4).type());
        assertEquals(planes[4][7], kept.pixels()[7 * 5 + 4]);
        var transparent = PsdDecoder.decode(MediaSource.of(new PsdFiles(1, WIDTH, HEIGHT, 8, 3).layerCount(-2)
                .build(0, planes)));
        assertTrue(transparent.picture().hasAlpha());
        assertEquals(planes[3][5], RasterTesting.tight(transparent.picture())[4 * 5 + 3]);
    }

    @ParameterizedTest
    @CsvSource({"1,16", "2,16", "1,8", "2,32"})
    @DisplayName("reads the layer count of the Lr16 block of deep documents, in the decoder and the core probe")
    void taggedLayerCount(int version, int depth) {
        var bytes = depth / 8;
        var planes = planes(4, bytes, version * depth);
        var transparent = new PsdFiles(version, WIDTH, HEIGHT, depth, 3).taggedLayerCount(-1).build(0, planes);
        var opaque = new PsdFiles(version, WIDTH, HEIGHT, depth, 3).taggedLayerCount(1).build(0, planes);
        assertTrue(PsdDecoder.decode(MediaSource.of(transparent)).picture().hasAlpha());
        assertTrue(PsdDecoder.probe(MediaSource.of(transparent)).hasAlpha());
        assertTrue(Media.probe(MediaSource.of(transparent)).image().orElseThrow().hasAlpha());
        assertFalse(PsdDecoder.decode(MediaSource.of(opaque)).picture().hasAlpha());
        assertFalse(PsdDecoder.probe(MediaSource.of(opaque)).hasAlpha());
        assertFalse(Media.probe(MediaSource.of(opaque)).image().orElseThrow().hasAlpha());
        if (depth == 16) {
            var picture = (Rgba16Picture) PsdDecoder.decode(MediaSource.of(transparent),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            assertEquals(sample(planes[3], 11, 2), picture.pixels()[4 * 11 + 3] & 0xFFFF);
        }
    }

    @Test
    @DisplayName("decodes gray, duotone, bitmap and indexed documents")
    void modes() {
        var gray = planes(1, 1, 5);
        var rgba = RasterTesting.tight(PsdDecoder.decode(MediaSource.of(new PsdFiles(1, WIDTH, HEIGHT, 8, 1)
                .build(1, gray))).picture());
        var duotone = RasterTesting.tight(PsdDecoder.decode(MediaSource.of(new PsdFiles(1, WIDTH, HEIGHT, 8, 8)
                .colorData(new byte[100]).build(1, gray))).picture());
        for (var index = 0; index < WIDTH * HEIGHT; index++) {
            assertEquals(gray[0][index], rgba[4 * index + 1]);
            assertEquals(gray[0][index], duotone[4 * index]);
        }
        var bits = new byte[][]{new byte[(WIDTH + 7) / 8 * HEIGHT]};
        bits[0][0] = (byte) 0x80;
        var bitmap = RasterTesting.tight(PsdDecoder.decode(MediaSource.of(new PsdFiles(1, WIDTH, HEIGHT, 1, 0)
                .build(0, bits))).picture());
        assertEquals(0, bitmap[0]);
        assertEquals(-1, bitmap[4]);
        var table = new byte[768];
        for (var entry = 0; entry < 256; entry++) {
            table[entry] = (byte) entry;
            table[256 + entry] = (byte) (255 - entry);
            table[512 + entry] = (byte) (entry * 3);
        }
        var indices = planes(1, 1, 9);
        var transparent = ByteBuffer.allocate(2).putShort((short) (indices[0][2] & 0xFF)).array();
        var indexed = PsdDecoder.decode(MediaSource.of(new PsdFiles(1, WIDTH, HEIGHT, 8, 2).colorData(table)
                .resource(1047, transparent).build(1, indices)));
        var pixels = RasterTesting.tight(indexed.picture());
        assertTrue(indexed.picture().hasAlpha());
        var indexedFile = new PsdFiles(1, WIDTH, HEIGHT, 8, 2).colorData(table).resource(1005, new byte[16])
                .resource(1047, transparent).build(1, indices);
        assertTrue(PsdDecoder.probe(MediaSource.of(indexedFile)).hasAlpha());
        assertTrue(Media.probe(MediaSource.of(indexedFile)).image().orElseThrow().hasAlpha());
        var outOfTable = new PsdFiles(1, WIDTH, HEIGHT, 8, 2).colorData(table)
                .resource(1047, new byte[]{1, 0}).build(1, indices);
        assertFalse(PsdDecoder.probe(MediaSource.of(outOfTable)).hasAlpha());
        assertFalse(PsdDecoder.decode(MediaSource.of(outOfTable)).picture().hasAlpha());
        assertFalse(Media.probe(MediaSource.of(outOfTable)).image().orElseThrow().hasAlpha());
        for (var index = 0; index < WIDTH * HEIGHT; index++) {
            var entry = indices[0][index] & 0xFF;
            assertEquals((byte) entry, pixels[4 * index]);
            assertEquals((byte) (255 - entry), pixels[4 * index + 1]);
            assertEquals((byte) (entry * 3), pixels[4 * index + 2]);
            assertEquals(entry == (indices[0][2] & 0xFF) ? 0 : -1, pixels[4 * index + 3]);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16})
    @DisplayName("decodes CMYK as inverted inks, kept with keepChannels")
    void cmyk(int depth) {
        var bytes = depth / 8;
        var planes = planes(4, bytes, 11);
        var file = new PsdFiles(1, WIDTH, HEIGHT, depth, 4).build(1, planes);
        var options = ImageDecoderOptions.builder().keepChannels(true).highBitDepth(true).build();
        var picture = PsdDecoder.decode(MediaSource.of(file), options).picture();
        for (var index = 0; index < WIDTH * HEIGHT; index++) {
            for (var channel = 0; channel < 4; channel++) {
                var expected = (depth == 8 ? 255 : 65535) - sample(planes[channel], index, bytes);
                var actual = depth == 8 ? ((Channel8Picture) picture).pixels()[4 * index + channel] & 0xFF
                        : ((Channel16Picture) picture).pixels()[4 * index + channel] & 0xFFFF;
                assertEquals(expected, actual);
            }
        }
        assertEquals(PixelLayout.CMYK, ((com.github.auties00.smalt.image.ChannelPicture) picture).layout());
        assertFalse(PsdDecoder.decode(MediaSource.of(file)).picture().hasAlpha());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    @DisplayName("decodes 32-bit floating-point documents as linear light")
    void floats(int compression) {
        var values = new float[WIDTH * HEIGHT];
        var random = new Random(compression);
        for (var index = 0; index < values.length; index++) {
            values[index] = random.nextFloat() * 2;
        }
        var plane = ByteBuffer.allocate(values.length * 4);
        for (var value : values) {
            plane.putFloat(value);
        }
        var file = new PsdFiles(1, WIDTH, HEIGHT, 32, 1).build(compression, plane.array());
        var image = PsdDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().keepChannels(true).build());
        assertArrayEquals(values, assertInstanceOf(ChannelFloatPicture.class, image.picture()).pixels());
        assertTrue(image.colorProfile().isPresent());
    }

    @Test
    @DisplayName("converts L*a*b* to sRGB")
    void lab() {
        // L 100, a 0, b 0 is white; L 0 is black
        var planes = new byte[][]{new byte[]{(byte) 255, 0}, new byte[]{(byte) 128, (byte) 128},
                new byte[]{(byte) 128, (byte) 128}};
        var rgba = RasterTesting.tight(PsdDecoder.decode(MediaSource.of(new PsdFiles(1, 2, 1, 8, 9).build(0, planes)))
                .picture());
        assertEquals(255, rgba[0] & 0xFF, 1);
        assertEquals(255, rgba[1] & 0xFF, 1);
        assertEquals(0, rgba[4] & 0xFF, 1);
    }

    @Test
    @DisplayName("returns the ICC profile, EXIF, XMP and resolution resources")
    void resources() {
        var icc = ColorProfile.displayP3().toByteArray();
        var xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var exif = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0};
        var resolution = ByteBuffer.allocate(16).putInt(300 << 16).putShort((short) 1).putShort((short) 1)
                .putInt(150 << 16).putShort((short) 1).putShort((short) 1).array();
        var file = new PsdFiles(1, WIDTH, HEIGHT, 8, 3).resource(1039, icc).resource(1058, exif).resource(1060, xmp)
                .resource(1005, resolution).build(0, planes(3, 1, 13));
        var image = PsdDecoder.decode(MediaSource.of(file));
        assertArrayEquals(icc, image.iccProfile().orElseThrow());
        assertArrayEquals(exif, image.exif().orElseThrow());
        assertArrayEquals(xmp, image.xmp().orElseThrow());
        assertTrue(image.colorProfile().isPresent());
        assertTrue(image.density().isPresent());
    }

    @Nested
    @DisplayName("oracles")
    class Oracles {
        @ParameterizedTest
        @CsvSource({"3,8,1", "3,16,1", "1,8,0", "1,16,1", "2,8,1", "0,1,1", "3,8,0"})
        @DisplayName("ffmpeg reads the same documents to the same pixels")
        void ffmpeg(int mode, int depth, int compression) {
            var channels = mode == 3 ? 4 : 1;
            var bytes = depth == 1 ? 1 : depth / 8;
            var builder = new PsdFiles(1, WIDTH, HEIGHT, depth, mode);
            byte[][] planes;
            if (depth == 1) {
                planes = new byte[][]{RasterFixtures.raw(RasterFixtures.rgba(builder.rowBytes(), HEIGHT, 3, false))};
                planes[0] = java.util.Arrays.copyOf(planes[0], builder.rowBytes() * HEIGHT);
            } else {
                planes = planes(channels, bytes, mode * 10L + depth);
            }
            if (mode == 2) {
                var table = new byte[768];
                new Random(1).nextBytes(table);
                builder.colorData(table);
            }
            if (mode == 3) {
                builder.layerCount(-1);
            }
            var file = builder.build(compression, planes);
            var wide = depth == 16;
            if (wide) {
                var ours = (Rgba16Picture) PsdDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder()
                        .highBitDepth(true).build()).picture();
                var theirs = RasterFixtures.shortsLe(RasterFixtures.ffmpegDecode(file, "psd", "rgba64le"));
                Tolerance.exact().assertWithin("mode " + mode, theirs, RasterFixtures.tight(ours), 16);
            } else {
                var ours = RasterTesting.tight(PsdDecoder.decode(MediaSource.of(file)).picture());
                var theirs = RasterFixtures.ffmpegDecode(file, "psd", "rgba");
                Tolerance.exact().assertWithin("mode " + mode, theirs, ours);
            }
        }

        @ParameterizedTest
        @CsvSource({"psd,8", "psb,8", "psd,16", "psb,16"})
        @DisplayName("reads ImageMagick's documents to the pixels it was given")
        void imageMagick(String format, int depth) throws Exception {
            var convert = Oracle.find("magick").or(() -> Oracle.find("convert"));
            Skip.unless(convert.isPresent(), "oracle:imagemagick", "ImageMagick is not on PATH");
            var version = Oracle.execute(convert.get(), Duration.ofMinutes(1), null, List.of("-version"));
            Skip.unless(version.exitCode() == 0 && new String(version.stdout()).contains("ImageMagick"),
                    "oracle:imagemagick", convert.get() + " is not ImageMagick");
            var picture = RasterFixtures.rgba(31, 17, 5, true);
            var png = RasterTesting.write("im-" + format + depth + ".png", PngEncoder.encode(picture));
            var out = png.resolveSibling("im-out" + depth + "." + format);
            var result = Oracle.execute(convert.get(), Duration.ofMinutes(1), null, List.of(png.toString(),
                    "-depth", String.valueOf(depth), format + ":" + out));
            assertEquals(0, result.exitCode(), result.stderr());
            var file = Files.readAllBytes(out);
            var image = Images.decode(MediaSource.of(file));
            assertEquals(ImageFormat.PSD, image.format());
            assertTrue(image.picture().hasAlpha(), "the transparency of a " + depth + "-bit document");
            assertTrue(Media.probe(MediaSource.of(file)).image().orElseThrow().hasAlpha());
            RasterTesting.assertSameVisible(RasterTesting.tight(picture), RasterTesting.tight(image.picture()), 31,
                    format);
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects invalid headers and unsupported documents")
        void rejects() {
            var good = new PsdFiles(1, 4, 4, 8, 3).build(0, new byte[3][16]);
            var badVersion = good.clone();
            badVersion[5] = 3;
            assertThrows(MediaException.Malformed.class, () -> PsdDecoder.decode(MediaSource.of(badVersion)));
            var badMode = good.clone();
            badMode[25] = 5;
            assertThrows(MediaException.Unsupported.class, () -> PsdDecoder.decode(MediaSource.of(badMode)));
            var badCompression = good.clone();
            badCompression[good.length - 48 - 1] = 9;
            assertThrows(MediaException.Unsupported.class, () -> PsdDecoder.decode(MediaSource.of(badCompression)));
            var noMerged = new PsdFiles(1, 4, 4, 8, 3).resource(1057, new byte[]{0, 0, 0, 1, 0})
                    .build(0, new byte[3][16]);
            assertThrows(MediaException.Unsupported.class, () -> PsdDecoder.decode(MediaSource.of(noMerged)));
            assertThrows(MediaException.Malformed.class, () -> PsdDecoder.decode(MediaSource.of(
                    java.util.Arrays.copyOf(good, 30))));
            assertThrows(MediaException.Malformed.class, () -> PsdDecoder.decode(MediaSource.of(
                    new PsdFiles(1, 4, 4, 8, 3).build(0, new byte[2][16]))));
        }

        @Test
        @DisplayName("survives mutations with only media exceptions")
        void mutations() {
            var seeds = List.of(new PsdFiles(1, 12, 6, 8, 3).layerCount(-1).build(1, planes(4, 1, 1)),
                    new PsdFiles(2, 12, 6, 16, 1).build(3, planes(1, 2, 2)),
                    new PsdFiles(1, 12, 6, 8, 4).build(2, planes(4, 1, 3)));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(300), 19,
                    source -> PsdDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }
}
