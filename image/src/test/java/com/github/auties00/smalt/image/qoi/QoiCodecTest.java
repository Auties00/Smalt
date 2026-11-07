package com.github.auties00.smalt.image.qoi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the QOI codec: every chunk kind, the encoder's output against ffmpeg's QOI encoder (a port of the reference
 * {@code qoi.h}) byte for byte, ffmpeg reading Smalt's files and Smalt reading ffmpeg's, the colorspace label, and
 * rejection of malformed files.
 */
@DisplayName("QOI codec")
class QoiCodecTest {
    // runs of every length up to past 62, repeated colors (index hits), small and luma-sized steps, alpha changes
    static RgbaPicture chunky(int width, int height, long seed, boolean alpha) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        var colors = new int[8];
        for (var index = 0; index < colors.length; index++) {
            colors[index] = random.nextInt();
        }
        var current = 0xFF808080;
        var index = 0;
        while (index < width * height) {
            var kind = random.nextInt(6);
            var length = kind == 0 ? 1 + random.nextInt(80) : 1;
            switch (kind) {
                case 1 -> current = colors[random.nextInt(colors.length)];
                case 2 -> current = step(current, 2);
                case 3 -> current = step(current, 40);
                case 4 -> current = random.nextInt();
                default -> {
                }
            }
            if (!alpha) {
                current |= 0xFF000000;
            }
            for (var count = 0; count < length && index < width * height; count++, index++) {
                pixels[4 * index] = (byte) (current >>> 16);
                pixels[4 * index + 1] = (byte) (current >>> 8);
                pixels[4 * index + 2] = (byte) current;
                pixels[4 * index + 3] = (byte) (current >>> 24);
            }
        }
        return picture;
    }

    private static int step(int color, int range) {
        var random = new Random(color);
        var r = ((color >>> 16) + random.nextInt(2 * range + 1) - range) & 0xFF;
        var g = ((color >>> 8) + random.nextInt(2 * range + 1) - range) & 0xFF;
        var b = (color + random.nextInt(2 * range + 1) - range) & 0xFF;
        return (color & 0xFF000000) | r << 16 | g << 8 | b;
    }

    @Test
    @DisplayName("recognizes the magic and registers the codec")
    void recognition() {
        var file = QoiEncoder.encode(chunky(4, 4, 1, false));
        assertEquals(ImageFormat.QOI, ImageFormat.detect(file, file.length).orElseThrow());
        assertTrue(Images.isDecodable(ImageFormat.QOI));
        assertTrue(Images.isEncodable(ImageFormat.QOI));
        assertEquals(ImageFormat.QOI, QoiDecoder.probe(MediaSource.of(file)).format());
    }

    @ParameterizedTest
    @CsvSource({"1,1,false", "3,2,true", "64,64,true", "97,31,false", "200,3,true"})
    @DisplayName("round trips pictures exercising every chunk")
    void roundTrip(int width, int height, boolean alpha) {
        var picture = chunky(width, height, width * 31L + height, alpha);
        var file = QoiEncoder.encode(picture);
        assertEquals(alpha ? 4 : 3, file[12]);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}, Arrays.copyOfRange(file, file.length - 8, file.length));
        var decoded = QoiDecoder.decode(MediaSource.of(file));
        assertEquals(alpha, decoded.picture().hasAlpha());
        assertArrayEquals(RasterTesting.tight(picture), RasterTesting.tight(decoded.picture()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("writes the bytes of ffmpeg's QOI encoder, a port of qoi.h")
    void matchesReferenceEncoder(boolean alpha) {
        for (var seed = 0; seed < 4; seed++) {
            var picture = seed % 2 == 0 ? chunky(61, 37, seed, alpha) : RasterFixtures.rgba(61, 37, seed, alpha);
            var reference = RasterFixtures.ffmpegEncode(RasterFixtures.raw(picture), "rgba", 61, 37, "qoi",
                    List.of("-c:v", "qoi", "-pix_fmt", alpha ? "rgba" : "rgb24"));
            assertArrayEquals(reference, QoiEncoder.encode(picture), "seed " + seed);
            var ours = RasterTesting.tight(QoiDecoder.decode(MediaSource.of(reference)).picture());
            var theirs = RasterFixtures.ffmpegDecode(reference, "qoi", "rgba");
            Tolerance.exact().assertWithin("decode of ffmpeg's file", theirs, ours);
        }
    }

    @Test
    @DisplayName("decodes a three-channel file opaque whatever alpha its chunks carry")
    void threeChannels() {
        var picture = chunky(9, 9, 5, true);
        var file = QoiEncoder.encode(picture);
        file[12] = 3;
        var decoded = QoiDecoder.decode(MediaSource.of(file));
        assertFalse(decoded.picture().hasAlpha());
        var rgba = RasterTesting.tight(decoded.picture());
        var original = RasterTesting.tight(picture);
        for (var index = 0; index < rgba.length; index++) {
            assertEquals(index % 4 == 3 ? (byte) 255 : original[index], rgba[index]);
        }
    }

    @Test
    @DisplayName("reports linear light for colorspace 1 and converts it to sRGB on request")
    void linear() {
        var picture = chunky(8, 8, 3, false);
        var file = QoiEncoder.encode(picture, QoiEncoderOptions.builder().linear(true).build());
        assertEquals(1, file[13]);
        var image = QoiDecoder.decode(MediaSource.of(file));
        assertTrue(image.colorProfile().isPresent());
        var srgb = QoiDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(true).build());
        var mid = RasterTesting.tight(srgb.picture());
        var stored = RasterTesting.tight(image.picture());
        var brighter = 0;
        for (var index = 0; index < mid.length; index += 4) {
            if ((mid[index] & 0xFF) > (stored[index] & 0xFF)) {
                brighter++;
            }
        }
        assertTrue(brighter > 0, "linear values encode brighter in sRGB");
        assertTrue(QoiDecoder.decode(MediaSource.of(QoiEncoder.encode(picture))).colorProfile().isEmpty());
    }

    @Test
    @DisplayName("forces the channel count on request and rejects other counts")
    void channels() {
        var picture = chunky(5, 5, 9, true);
        var file = QoiEncoder.encode(picture, QoiEncoderOptions.builder().channels(3).build());
        assertEquals(3, file[12]);
        assertFalse(QoiDecoder.decode(MediaSource.of(file)).picture().hasAlpha());
        assertThrows(IllegalArgumentException.class, () -> QoiEncoderOptions.builder().channels(2).build());
    }

    @Test
    @DisplayName("encodes through Images losslessly")
    void generic() {
        var picture = RasterFixtures.rgba(13, 13, 2, true);
        var file = Images.encode(picture, ImageFormat.QOI, ImageEncoderOptions.lossless());
        assertArrayEquals(RasterTesting.tight(picture), RasterTesting.tight(Images.decode(MediaSource.of(file))
                .picture()));
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects a file whose chunks end before the last pixel")
        void truncated() {
            var file = QoiEncoder.encode(RasterFixtures.rgba(30, 30, 4, true));
            for (var cut : new int[]{14, 20, file.length / 2, file.length - 9}) {
                var data = Arrays.copyOf(file, cut);
                assertThrows(MediaException.Malformed.class, () -> QoiDecoder.decode(MediaSource.of(data)), "cut " + cut);
            }
        }

        @ParameterizedTest
        @CsvSource({"12,2", "12,5", "13,2", "4,0", "8,0"})
        @DisplayName("rejects invalid header fields")
        void header(int offset, int value) {
            var file = QoiEncoder.encode(RasterFixtures.rgba(4, 4, 4, true));
            file[offset] = (byte) value;
            if (offset == 4 || offset == 8) {
                file[offset + 1] = file[offset + 2] = file[offset + 3] = 0;
            }
            assertThrows(MediaException.Malformed.class, () -> QoiDecoder.decode(MediaSource.of(file)));
        }

        @Test
        @DisplayName("survives mutations with only media exceptions")
        void mutations() {
            var seeds = List.of(QoiEncoder.encode(chunky(20, 20, 1, true)), QoiEncoder.encode(chunky(7, 3, 2, false)));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(400), 11,
                    source -> QoiDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }
}
