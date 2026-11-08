package com.github.auties00.smalt.image.jxr;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jxr.internal.JxrCodestream;
import com.github.auties00.smalt.image.jxr.internal.JxrContainer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the JPEG XR decoder's public surface over the vectors of tools/testdata/behavior/make-jxr-vectors.py:
 * recognition and probing, the pictures each output format decodes to (checked against the samples the codestream
 * decodes to, which JxrCodestreamTest checks against jxrlib), the alpha, premultiplication, orientation, color
 * profile and metadata the container carries, and rejection of malformed files.
 */
@DisplayName("JPEG XR decoder")
class JxrDecoderTest {
    private static final String ROOT = "/com/github/auties00/smalt/image/jxr/";

    private static byte[] vector(String name) {
        try (var input = JxrDecoderTest.class.getResourceAsStream(ROOT + name + ".jxr")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** {@return the output samples of a file's image codestream} */
    private static JxrCodestream.Samples samples(byte[] data) {
        var container = JxrContainer.read(data);
        return JxrCodestream.decode(data, container.imageOffset(), container.imageEnd(), MediaLimits.DEFAULT);
    }

    private static ImageDecoderOptions keep() {
        return ImageDecoderOptions.builder().keepChannels(true).highBitDepth(true).build();
    }

    /**
     * Returns a copy of a file whose directory gains entries, written past the end of the file with their data.
     *
     * @param entries tag, type, count and data of each entry; data of up to 4 bytes is stored in the entry
     */
    static byte[] withEntries(byte[] file, Object[]... entries) {
        var directory = (int) ByteBuffer.wrap(file, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        var count = ByteBuffer.wrap(file, directory, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        var list = new ArrayList<byte[]>();
        for (var index = 0; index < count; index++) {
            list.add(Arrays.copyOfRange(file, directory + 2 + 12 * index, directory + 14 + 12 * index));
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(file);
        for (var entry : entries) {
            var tag = (int) entry[0];
            list.removeIf(bytes -> ((bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8) == tag);
            var data = (byte[]) entry[3];
            var value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length <= 4) {
                value.put(data);
            } else {
                value.putInt(out.size());
                out.writeBytes(data);
            }
            list.add(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putShort((short) tag)
                    .putShort((short) (int) entry[1]).putInt((int) entry[2]).put(value.array()).array());
        }
        list.sort((a, b) -> Integer.compare((a[0] & 0xFF) | (a[1] & 0xFF) << 8, (b[0] & 0xFF) | (b[1] & 0xFF) << 8));
        if ((out.size() & 1) != 0) {
            out.write(0);
        }
        var start = out.size();
        out.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) list.size()).array());
        list.forEach(out::writeBytes);
        out.writeBytes(new byte[4]);
        var result = out.toByteArray();
        ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(start);
        return result;
    }

    @Nested
    @DisplayName("recognition")
    class Recognition {
        @Test
        @DisplayName("recognizes the container, registers the decoder and probes the size and alpha")
        void registered() {
            var file = vector("bgra8-q40-a2");
            assertEquals(ImageFormat.JXR, ImageFormat.detect(file, file.length).orElseThrow());
            assertTrue(Images.isDecodable(ImageFormat.JXR));
            var info = Images.probe(MediaSource.of(file));
            assertEquals(75, info.width());
            assertEquals(43, info.height());
            assertTrue(info.hasAlpha());
            assertFalse(JxrDecoder.probe(MediaSource.of(vector("rgb8-q40l0"))).hasAlpha());
            assertTrue(JxrDecoder.probe(MediaSource.of(vector("rgbaf-q40-a3"))).hasAlpha());
            var window = JxrDecoder.probe(MediaSource.of(vector("rgb8-q40-win")));
            assertEquals(List.of(50, 29), List.of(window.width(), window.height()));
        }

        @ParameterizedTest
        @MethodSource("com.github.auties00.smalt.image.jxr.JxrDecoderTest#vectors")
        @DisplayName("probes alpha and bits per sample from the pixel format as the codestreams carry them")
        void probedAlpha(String name) {
            var file = vector(name);
            var image = Media.probe(MediaSource.of(file)).image().orElseThrow();
            assertEquals(JxrDecoder.probe(MediaSource.of(file)).hasAlpha(), image.hasAlpha(), name);
            var bits = expectedBits(name);
            if (bits > 0) {
                assertEquals(bits, image.bitDepth().orElseThrow(), name);
            }
        }

        /** {@return the bits per sample the core probe reports for a vector's pixel format, or 0 for raw YUV} */
        private static int expectedBits(String name) {
            var format = name.substring(0, name.indexOf('-'));
            if (format.startsWith("yuv")) {
                return 0;
            }
            if (format.equals("bw")) {
                return 1;
            }
            if (format.endsWith("fx32") || format.endsWith("f") || format.equals("rgbe")) {
                return 32;
            }
            if (format.endsWith("16") || format.endsWith("h") || format.equals("bgr101010")) {
                return 16;
            }
            return 8;
        }
    }

    /** {@return the names of the vectors listed in jxr.tsv} */
    static List<String> vectors() {
        try (var input = JxrDecoderTest.class.getResourceAsStream(ROOT + "jxr.tsv")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t")[0])
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("pictures")
    class Pictures {
        @Test
        @DisplayName("decodes 8-bit RGB to packed RGBA of its samples")
        void rgb8() {
            var file = vector("rgb8-q40l2");
            var samples = samples(file);
            var picture = assertInstanceOf(RgbaPicture.class, Images.decode(MediaSource.of(file)).picture());
            for (var index = 0; index < samples.width() * samples.height(); index++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(samples.channels()[c][index], picture.pixels()[index * 4 + c] & 0xFF);
                }
                assertEquals(255, picture.pixels()[index * 4 + 3] & 0xFF);
            }
        }

        @ParameterizedTest
        @CsvSource({"gray16-q40, 1", "rgba16-q40-a3, 4", "rgb16-q40l2, 3"})
        @DisplayName("keeps 16-bit samples with highBitDepth")
        void sixteen(String name, int channels) {
            var file = vector(name);
            var samples = samples(file);
            var picture = assertInstanceOf(Rgba16Picture.class, JxrDecoder.decode(MediaSource.of(file), keep())
                    .picture());
            for (var index = 0; index < samples.width() * samples.height(); index++) {
                for (var c = 0; c < 3; c++) {
                    var expected = samples.channels()[channels == 1 ? 0 : c][index];
                    assertEquals(expected, picture.pixels()[index * 4 + c] & 0xFFFF);
                }
                if (channels == 4) {
                    assertEquals(samples.channels()[3][index], picture.pixels()[index * 4 + 3] & 0xFFFF);
                }
            }
        }

        @ParameterizedTest
        @CsvSource({"grayfx16-q40, 8192", "rgbfx16-q40, 8192", "grayfx32-q40, 16777216", "rgbafx32-q40-a2, 16777216"})
        @DisplayName("divides fixed-point samples into floats as jxrlib's pixel format conversion does")
        void fixedPoint(String name, float scale) {
            var file = vector(name);
            var container = JxrContainer.read(file);
            var samples = JxrCodestream.decode(file, container.imageOffset(), container.imageEnd(),
                    MediaLimits.DEFAULT);
            var picture = assertInstanceOf(ChannelFloatPicture.class, JxrDecoder.decode(MediaSource.of(file),
                    keep()).picture());
            var count = picture.channelCount();
            for (var index = 0; index < samples.width() * samples.height(); index++) {
                for (var c = 0; c < samples.channels().length; c++) {
                    assertEquals(samples.channels()[c][index] * (1 / scale), picture.pixels()[index * count + c]);
                }
            }
        }

        @Test
        @DisplayName("decodes half, float and RGBE samples to linear floats")
        void floating() {
            var half = vector("rgbh-q40");
            var halfSamples = samples(half);
            var halfPicture = assertInstanceOf(ChannelFloatPicture.class, JxrDecoder.decode(MediaSource.of(half),
                    keep()).picture());
            assertEquals(Float.float16ToFloat((short) halfSamples.channels()[1][100]), halfPicture.pixels()[301]);
            var single = vector("grayf-q40");
            var singleSamples = samples(single);
            var singlePicture = assertInstanceOf(ChannelFloatPicture.class,
                    JxrDecoder.decode(MediaSource.of(single), keep()).picture());
            assertEquals(Float.intBitsToFloat(singleSamples.channels()[0][77]), singlePicture.pixels()[77]);
            var decoded = JxrDecoder.decode(MediaSource.of(single), keep());
            assertTrue(decoded.colorProfile().isPresent(), "floating-point gray is linear light");
            var rgbe = vector("rgbe-q40");
            var rgbeSamples = samples(rgbe);
            var rgbePicture = assertInstanceOf(ChannelFloatPicture.class, JxrDecoder.decode(MediaSource.of(rgbe),
                    keep()).picture());
            assertEquals(PixelLayout.RGB, rgbePicture.layout());
            for (var index = 0; index < rgbeSamples.width() * rgbeSamples.height(); index++) {
                var exponent = rgbeSamples.channels()[3][index];
                for (var c = 0; c < 3; c++) {
                    var expected = exponent == 0 ? 0f
                            : rgbeSamples.channels()[c][index] * (float) Math.scalb(1.0, exponent - 136);
                    assertEquals(expected, rgbePicture.pixels()[index * 3 + c]);
                }
            }
        }

        @ParameterizedTest
        @CsvSource({"bgr555-q40, 5, 5", "bgr565-q40, 5, 6"})
        @DisplayName("widens packed 5 and 6-bit samples to 8 bits by replicating their high bits")
        void packed(String name, int redBits, int greenBits) {
            var file = vector(name);
            var samples = samples(file);
            var picture = assertInstanceOf(RgbaPicture.class, JxrDecoder.decode(MediaSource.of(file)).picture());
            for (var index = 0; index < samples.width() * samples.height(); index++) {
                var red = samples.channels()[0][index];
                var green = samples.channels()[1][index];
                assertEquals((red << (8 - redBits)) | (red >> (2 * redBits - 8)), picture.pixels()[index * 4] & 0xFF);
                assertEquals((green << (8 - greenBits)) | (green >> (2 * greenBits - 8)),
                        picture.pixels()[index * 4 + 1] & 0xFF);
            }
            var ten = vector("bgr101010-q40");
            var tenSamples = samples(ten);
            var wide = assertInstanceOf(Rgba16Picture.class, JxrDecoder.decode(MediaSource.of(ten), keep())
                    .picture());
            var blue = tenSamples.channels()[2][9];
            assertEquals((blue << 6) | (blue >> 4), wide.pixels()[9 * 4 + 2] & 0xFFFF);
        }

        @Test
        @DisplayName("keeps CMYK inks, independent channels and black and white with keepChannels")
        void channels() {
            var cmyk = assertInstanceOf(Channel8Picture.class, JxrDecoder.decode(MediaSource.of(
                    vector("cmyk8-ic-q80")), keep()).picture());
            assertEquals(PixelLayout.CMYK, cmyk.layout());
            var cmyka = assertInstanceOf(Channel8Picture.class, JxrDecoder.decode(MediaSource.of(
                    vector("cmyka8-q40-a3")), keep()).picture());
            assertEquals(PixelLayout.CMYKA, cmyka.layout());
            var five = assertInstanceOf(Channel8Picture.class, JxrDecoder.decode(MediaSource.of(vector("nch5-q1")),
                    keep()).picture());
            assertEquals(5, five.channelCount());
            assertEquals(ChannelType.INK, five.layout().channel(4).type());
            var bw = vector("bw-q1");
            var bwSamples = samples(bw);
            var gray = assertInstanceOf(RgbaPicture.class, JxrDecoder.decode(MediaSource.of(bw)).picture());
            for (var index = 0; index < bwSamples.width() * bwSamples.height(); index++) {
                assertEquals(bwSamples.channels()[0][index] * 255, gray.pixels()[index * 4] & 0xFF);
            }
        }

        @Test
        @DisplayName("appends planar and interleaved alpha, premultiplied when the pixel format is")
        void alpha() {
            var planar = assertInstanceOf(RgbaPicture.class, JxrDecoder.decode(MediaSource.of(
                    vector("bgra8-q40-a2"))).picture());
            assertTrue(planar.hasAlpha());
            var interleaved = JxrDecoder.decode(MediaSource.of(vector("bgra8-q40-a3")), keep()).picture();
            assertTrue(interleaved.hasAlpha());
            // 32bppBGRA (0x0F) relabeled 32bppPBGRA (0x10)
            var file = vector("bgra8-q40-a3");
            var at = indexOf(file, new byte[]{(byte) 0xB1, (byte) 0x85, 0x3D, 0x77, 0x76, (byte) 0x8D, (byte) 0xC9,
                    0x0F});
            file[at + 7] = 0x10;
            var premultiplied = assertInstanceOf(Channel8Picture.class, JxrDecoder.decode(MediaSource.of(file),
                    keep()).picture());
            assertEquals(ChannelType.PREMULTIPLIED_ALPHA, premultiplied.layout().channel(3).type());
        }

        private static int indexOf(byte[] data, byte[] pattern) {
            for (var at = 0; at + pattern.length <= data.length; at++) {
                if (Arrays.equals(data, at, at + pattern.length, pattern, 0, pattern.length)) {
                    return at;
                }
            }
            throw new AssertionError("pattern not found");
        }
    }

    @Nested
    @DisplayName("container")
    class Container {
        @ParameterizedTest
        @CsvSource({"1, FLIP_VERTICAL", "2, FLIP_HORIZONTAL", "3, ROTATE_180", "4, ROTATE_90", "5, TRANSVERSE",
                "6, TRANSPOSE", "7, ROTATE_270"})
        @DisplayName("reports the container's transformation as the orientation, without applying it")
        void orientation(int code, Orientation orientation) {
            var file = withEntries(vector("rgb8-q40l2"), new Object[]{0xBC02, 1, 1, new byte[]{(byte) code}});
            var decoded = JxrDecoder.decode(MediaSource.of(file));
            assertEquals(orientation, decoded.orientation());
            assertEquals(75, decoded.picture().width());
            assertEquals(orientation, JxrDecoder.probe(MediaSource.of(file)).orientation());
        }

        @Test
        @DisplayName("ignores the image header's orientation when a container is present")
        void headerOrientation() {
            var file = vector("rgb8-q40l2");
            var container = JxrContainer.read(file);
            file[container.imageOffset() + 9] |= 4 << 3;
            assertEquals(Orientation.NORMAL, JxrDecoder.decode(MediaSource.of(file)).orientation());
            var bare = Arrays.copyOfRange(file, container.imageOffset(), container.imageEnd());
            assertEquals(Orientation.ROTATE_90, JxrDecoder.decode(MediaSource.of(bare)).orientation());
        }

        @Test
        @DisplayName("reports the embedded XMP packet, EXIF directory and resolution")
        void metadata() {
            var xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"></x:xmpmeta>".getBytes();
            var exif = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1)
                    .putShort((short) 0x9003).putShort((short) 2).putInt(4).put("2026".getBytes()).putInt(0)
                    .array();
            var dpi = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(300f).array();
            var source = vector("rgb8-q40l2");
            var exifOffset = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(source.length).array();
            var withExif = new byte[source.length + exif.length];
            System.arraycopy(source, 0, withExif, 0, source.length);
            System.arraycopy(exif, 0, withExif, source.length, exif.length);
            var file = withEntries(withExif, new Object[]{0x02BC, 1, xmp.length, xmp},
                    new Object[]{0x8769, 4, 1, exifOffset}, new Object[]{0xBC82, 11, 1, dpi},
                    new Object[]{0xBC83, 11, 1, dpi});
            var decoded = JxrDecoder.decode(MediaSource.of(file));
            assertArrayEquals(xmp, decoded.xmp().orElseThrow());
            var tiff = decoded.exif().orElseThrow();
            assertEquals('I', tiff[0]);
            assertEquals(42, tiff[2]);
            assertTrue(tiff.length < 64, "a stand-alone EXIF block, not the file: " + tiff.length + " bytes");
            var parsed = ExifData.parse(tiff);
            var date = assertInstanceOf(ExifValue.Ascii.class, parsed.get(ExifData.Directory.EXIF, 0x9003)
                    .orElseThrow());
            assertTrue(date.value().startsWith("2026"), date.value());
            assertEquals(300.0, decoded.density().orElseThrow().horizontal(), 1e-9);
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects files that are not JPEG XR and truncated headers")
        void headers() {
            assertThrows(MediaException.Malformed.class, () -> JxrDecoder.decode(MediaSource.of(new byte[64])));
            var file = vector("rgb8-q40l2");
            assertThrows(MediaException.class, () -> JxrDecoder.decode(MediaSource.of(Arrays.copyOf(file, 40))));
        }

        @Test
        @DisplayName("survives mutated files")
        void mutations() {
            var seeds = new ArrayList<byte[]>();
            for (var name : List.of("rgb8-q20d1l2", "bgra8-q30tile-a3", "rgb8-qpsp2", "rgbaf-q40-a3-win",
                    "rgb8-q40l2-v0", "rgb8-hardd1l2", "cmyk8-ic-q80", "bgra8-q40-a2", "nch5-q1", "rgb8-q30dc")) {
                seeds.add(vector(name));
            }
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(60), 3,
                    source -> JxrDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }
}
