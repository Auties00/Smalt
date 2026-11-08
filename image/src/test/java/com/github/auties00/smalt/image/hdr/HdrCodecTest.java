package com.github.auties00.smalt.image.hdr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Radiance RGBE codec: the shared-exponent coding, flat, adaptive run-length and old run-length scanlines,
 * the eight orientations, the header lines, ffmpeg reading Smalt's files and Smalt reading ffmpeg's bit for bit, and
 * rejection of malformed files.
 */
@DisplayName("Radiance RGBE codec")
class HdrCodecTest {
    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static ChannelFloatPicture keep(byte[] file) {
        return assertInstanceOf(ChannelFloatPicture.class, HdrDecoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture());
    }

    /** The value a component decodes to after the shared-exponent coding of its pixel. */
    static float quantized(float component, float max) {
        if (!(max >= 1e-32f) || component <= 0) {
            return 0;
        }
        var exponent = Math.getExponent(max) + 1;
        var scale = Math.scalb((double) max, -exponent) * 256.0 / max;
        var mantissa = Math.min(255, (int) (component * scale));
        return mantissa * Math.scalb(1f, exponent + 128 - 136);
    }

    @Test
    @DisplayName("recognizes the magic and registers the codec")
    void recognition() {
        var file = HdrEncoder.encode(RasterFixtures.floats(9, 3, PixelLayout.RGB, 1));
        assertEquals(ImageFormat.HDR, ImageFormat.detect(file, file.length).orElseThrow());
        assertTrue(Images.isDecodable(ImageFormat.HDR));
        assertTrue(Images.isEncodable(ImageFormat.HDR));
        var info = HdrDecoder.probe(MediaSource.of(file));
        assertEquals(9, info.width());
        assertEquals(3, info.height());
    }

    @ParameterizedTest
    @CsvSource({"9,4,true", "9,4,false", "3,5,true", "16000,1,true", "120,7,true"})
    @DisplayName("round trips through the shared-exponent coding in every scanline form")
    void roundTrip(int width, int height, boolean runLength) {
        var picture = RasterFixtures.floats(width, height, PixelLayout.RGB, width + height);
        var file = HdrEncoder.encode(picture, HdrEncoderOptions.builder().runLength(runLength).build());
        var decoded = keep(file).pixels();
        var original = picture.pixels();
        for (var index = 0; index < original.length; index += 3) {
            var max = Math.max(Math.max(original[index], original[index + 1]), original[index + 2]);
            for (var channel = 0; channel < 3; channel++) {
                assertEquals(quantized(original[index + channel], max), decoded[index + channel],
                        "sample " + (index + channel));
            }
        }
        var flat = HdrEncoder.encode(picture, HdrEncoderOptions.builder().runLength(false).build());
        if (runLength && width >= 8 && width <= 32767) {
            assertNotEquals(flat.length, file.length);
        }
        assertArrayEquals(decoded, keep(flat).pixels());
    }

    @Test
    @DisplayName("decodes old run-length repeats, including chained shifts")
    void oldRunLength() {
        var pixels = new byte[]{(byte) 128, 64, 32, (byte) 129, 1, 1, 1, 2, 1, 1, 1, 1};
        var file = RasterTesting.concat(ascii("#?RADIANCE\n\n-Y 1 +X 259\n"), pixels);
        var floats = keep(file).pixels();
        assertEquals(259 * 3, floats.length);
        for (var index = 0; index < 259; index++) {
            assertEquals(1f, floats[3 * index], "pixel " + index);
            assertEquals(0.5f, floats[3 * index + 1]);
        }
    }

    @ParameterizedTest
    @CsvSource({"-Y 2 +X 3,0,0", "+Y 2 +X 3,1,0", "-Y 2 -X 3,0,2", "+Y 2 -X 3,1,2", "+X 3 -Y 2,0,0",
            "+X 3 +Y 2,1,0", "-X 3 -Y 2,0,2", "-X 3 +Y 2,1,2"})
    @DisplayName("places the first stored pixel by the resolution string")
    void orientations(String resolution, int y, int x) {
        var pixels = new byte[24];
        pixels[0] = (byte) 128;
        pixels[3] = (byte) 129;
        for (var index = 1; index < 6; index++) {
            pixels[4 * index + 3] = (byte) 129;
            pixels[4 * index + 1] = (byte) 128;
        }
        var file = RasterTesting.concat(ascii("#?RGBE\nFORMAT=32-bit_rle_rgbe\n\n" + resolution + "\n"), pixels);
        var image = HdrDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().keepChannels(true).build());
        assertEquals(3, image.picture().width());
        assertEquals(2, image.picture().height());
        var floats = ((ChannelFloatPicture) image.picture()).pixels();
        assertEquals(1f, floats[(y * 3 + x) * 3], resolution);
    }

    @Test
    @DisplayName("reports the declared or default primaries and XYZ")
    void profiles() {
        var file = RasterTesting.concat(ascii("#?RADIANCE\nFORMAT=32-bit_rle_xyze\n\n-Y 1 +X 1\n"),
                new byte[]{(byte) 128, (byte) 128, (byte) 128, (byte) 129});
        assertEquals(ColorProfile.xyz().colorSpace(), HdrDecoder.decode(MediaSource.of(file)).colorProfile()
                .orElseThrow().colorSpace());
        var plain = RasterTesting.concat(ascii("#?RADIANCE\n\n-Y 1 +X 1\n"), new byte[]{0, 0, 0, 0});
        assertTrue(HdrDecoder.decode(MediaSource.of(plain)).colorProfile().isPresent());
    }

    @Test
    @DisplayName("converts to sRGB from linear light")
    void srgb() {
        var picture = ChannelFloatPicture.allocate(1, 1, PixelLayout.RGB);
        picture.pixels()[0] = picture.pixels()[1] = picture.pixels()[2] = 0.2140f;
        var file = HdrEncoder.encode(picture);
        var image = HdrDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(true).build());
        var rgba = RasterTesting.tight(image.picture());
        // linear 0.214 is about sRGB 0.5
        assertEquals(128, rgba[1] & 0xFF, 3);
    }

    @Test
    @DisplayName("encodes through Images from sRGB and refuses lossless coding")
    void generic() {
        // equal channels keep every channel at full mantissa precision under the shared exponent
        var picture = RgbaPicture.allocate(10, 6, false);
        for (var index = 0; index < 60; index++) {
            var value = (byte) (index * 29 % 256);
            picture.pixels()[4 * index] = picture.pixels()[4 * index + 1] = picture.pixels()[4 * index + 2] = value;
            picture.pixels()[4 * index + 3] = (byte) 255;
        }
        var file = Images.encode(picture, ImageFormat.HDR, ImageEncoderOptions.ofQuality(90));
        var back = Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(true).build());
        var original = RasterTesting.tight(picture);
        var decoded = RasterTesting.tight(back.picture());
        for (var index = 0; index < original.length; index++) {
            assertEquals(original[index] & 0xFF, decoded[index] & 0xFF, 2, "sample " + index);
        }
        assertThrows(MediaException.Unsupported.class, () -> Images.encode(picture, ImageFormat.HDR,
                ImageEncoderOptions.lossless()));
    }

    @Nested
    @DisplayName("ffmpeg")
    class Ffmpeg {
        @ParameterizedTest
        @ValueSource(ints = {5, 64, 333})
        @DisplayName("ffmpeg reads Smalt's files to the same floats")
        void readsSmalt(int width) {
            var picture = RasterFixtures.floats(width, 3, PixelLayout.RGB, width);
            var file = HdrEncoder.encode(picture);
            var theirs = RasterFixtures.interleaveGbr(RasterFixtures.floatsLe(RasterFixtures.ffmpegDecode(file, "hdr",
                    "gbrpf32le")), width, 3, 3);
            assertArrayEquals(keep(file).pixels(), theirs);
        }

        @ParameterizedTest
        @ValueSource(ints = {5, 64, 333})
        @DisplayName("Smalt reads ffmpeg's files to the same floats")
        void readsFfmpeg(int width) {
            var picture = RasterFixtures.floats(width, 4, PixelLayout.RGB, width * 3L);
            var planar = new float[picture.pixels().length];
            var plane = width * 4;
            for (var index = 0; index < plane; index++) {
                planar[index] = picture.pixels()[3 * index + 1];
                planar[plane + index] = picture.pixels()[3 * index + 2];
                planar[2 * plane + index] = picture.pixels()[3 * index];
            }
            var raw = new byte[planar.length * 4];
            java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(planar);
            var file = RasterFixtures.ffmpegEncode(raw, "gbrpf32le", width, 4, "hdr", List.of("-c:v", "hdr"));
            var theirs = RasterFixtures.interleaveGbr(RasterFixtures.floatsLe(RasterFixtures.ffmpegDecode(file, "hdr",
                    "gbrpf32le")), width, 4, 3);
            assertArrayEquals(theirs, keep(file).pixels());
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @ParameterizedTest
        @ValueSource(strings = {"#?RADIANCE\n-Y 1 +X 1\n", "#?RADIANCE\n\n-Y 0 +X 1\n", "#?RADIANCE\n\n-Z 1 +X 1\n",
                "#?RADIANCE\n\n-Y 1 +Y 1\n", "#?RADIANCE\n\n-Y 1\n", "#?RADIANCE\n\n*Y 1 +X 1\n"})
        @DisplayName("rejects invalid headers and resolution lines")
        void header(String text) {
            assertThrows(MediaException.Malformed.class, () -> HdrDecoder.decode(MediaSource.of(ascii(text))));
        }

        @Test
        @DisplayName("rejects unknown pixel formats and runs that overrun their scanline")
        void scanlines() {
            assertThrows(MediaException.Unsupported.class, () -> HdrDecoder.decode(MediaSource.of(ascii(
                    "#?RADIANCE\nFORMAT=48-bit\n\n-Y 1 +X 1\n"))));
            var overrun = RasterTesting.concat(ascii("#?RADIANCE\n\n-Y 1 +X 8\n"), new byte[]{2, 2, 0, 8,
                    (byte) 200, 1});
            assertThrows(MediaException.Malformed.class, () -> HdrDecoder.decode(MediaSource.of(overrun)));
            var width = RasterTesting.concat(ascii("#?RADIANCE\n\n-Y 1 +X 8\n"), new byte[]{2, 2, 0, 9, 1, 1});
            assertThrows(MediaException.Malformed.class, () -> HdrDecoder.decode(MediaSource.of(width)));
        }

        @Test
        @DisplayName("survives mutations with only media exceptions")
        void mutations() {
            var seeds = List.of(HdrEncoder.encode(RasterFixtures.floats(20, 6, PixelLayout.RGB, 1)),
                    HdrEncoder.encode(RgbaPicture.allocate(7, 2, false)));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(300), 13,
                    source -> HdrDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }
}
