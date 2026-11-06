package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the synthetic multi-frame files of {@link PixelShiftFiles} through the public API: the Pentax
 * pixel-shift PEF and DNG files merge to the scene they were made from, each frame decodes alone, and the Sony ARQ
 * file merges its two greens; truncated and corrupted files fail only with a {@link MediaException}.
 */
@DisplayName("pixel-shift and ARQ files")
class PixelShiftFilesTest {
    private static final PixelShiftFiles FILES = new PixelShiftFiles(24, 18, 11);

    private static byte[] file(String kind) {
        return switch (kind) {
            case "pef" -> FILES.pef();
            case "dng" -> FILES.dng();
            case "arq" -> FILES.arq();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pef", "dng"})
    @DisplayName("a Pentax pixel-shift file merges its four frames into the scene")
    void merged(String kind) {
        var data = file(kind);
        var info = RawDecoder.info(MediaSource.of(data));
        assertEquals(kind.equals("pef") ? RawFormat.PEF : RawFormat.DNG, info.format());
        assertEquals(4, info.frameCount());
        assertTrue(info.cfaPattern().isEmpty(), "the merged image has no color filter array");
        var image = RawDecoder.decodeRaw(MediaSource.of(data));
        assertEquals(3, image.channels());
        assertEquals(FILES.width, image.width());
        assertEquals(FILES.height, image.height());
        var samples = image.samples();
        for (var y = 0; y < FILES.height - 1; y++) {
            for (var x = 0; x < FILES.width - 1; x++) {
                for (var color = 0; color < 3; color++) {
                    assertEquals(scaled(kind, FILES.scene(color, x, y)), samples[(y * FILES.width + x) * 3 + color]
                            & 0xFFFF, 1, kind + " pixel " + x + "," + y + " color " + color);
                }
            }
        }
        var picture = RawDecoder.decode(MediaSource.of(data)).picture();
        assertEquals(FILES.width, picture.width());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pef", "dng"})
    @DisplayName("each frame of a Pentax pixel-shift file decodes alone, and a frame past the last is refused")
    void frames(String kind) {
        var data = file(kind);
        for (var frame = 0; frame < 4; frame++) {
            var options = RawDecoderOptions.builder().frame(frame).build();
            var image = RawDecoder.decodeRaw(MediaSource.of(data), options);
            assertEquals(1, image.channels());
            assertEquals(CfaPattern.bayer("RGGB"), image.cfaPattern().orElseThrow());
            var expected = FILES.frame(frame);
            var samples = image.samples();
            for (var index = 0; index < expected.length; index++) {
                assertEquals(scaled(kind, expected[index]), samples[index] & 0xFFFF, 1,
                        kind + " frame " + frame + " photosite " + index);
            }
        }
        var past = RawDecoderOptions.builder().frame(4).build();
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(data), past));
    }

    @Test
    @DisplayName("the frame option survives toBuilder, allFrames resets it, and a negative frame is refused")
    void frameOption() {
        var framed = RawDecoderOptions.builder().frame(2).build();
        assertEquals(OptionalInt.of(2), framed.frame());
        assertEquals(framed, framed.toBuilder().build());
        assertTrue(RawDecoderOptions.defaults().frame().isEmpty());
        var all = framed.toBuilder().allFrames().build();
        assertTrue(all.frame().isEmpty());
        assertEquals(RawDecoderOptions.defaults(), all);
        assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().frame(-1).build());
    }

    @Test
    @DisplayName("an ARQ file merges its two greens, rounding half up, and keeps red and blue")
    void arq() {
        var data = FILES.arq();
        var info = RawDecoder.info(MediaSource.of(data));
        assertEquals(RawFormat.ARW, info.format());
        assertEquals(1, info.frameCount());
        var image = RawDecoder.decodeRaw(MediaSource.of(data));
        assertEquals(3, image.channels());
        var samples = image.samples();
        for (var y = 0; y < FILES.height; y++) {
            for (var x = 0; x < FILES.width; x++) {
                var index = y * FILES.width + x;
                assertEquals(scaled("arq", FILES.scene(0, x, y)), samples[index * 3] & 0xFFFF, 1);
                // the second green is larger by index % 2, so an odd sum rounds up
                assertEquals(scaled("arq", FILES.scene(1, x, y) + index % 2), samples[index * 3 + 1] & 0xFFFF, 1);
                assertEquals(scaled("arq", FILES.scene(2, x, y)), samples[index * 3 + 2] & 0xFFFF, 1);
            }
        }
        var neutral = image.cameraNeutral();
        assertEquals(1024.0 / 2400, neutral[0], 1e-9);
        assertEquals(1024.0 / 1700, neutral[2], 1e-9);
    }

    /**
     * Returns a stored value as the sensor data of {@link RawImage} holds it: black subtracted and scaled so that
     * the white level is 65535. The PEF file records no black level, so its black is 0.
     */
    private static double scaled(String kind, int value) {
        var black = kind.equals("pef") ? 0 : PixelShiftFiles.BLACK;
        return (value - black) * 65535.0 / (16383 - black);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pef", "dng", "arq"})
    @DisplayName("truncated and corrupted files decode or fail with a media exception")
    void damaged(String kind) {
        var data = file(kind);
        var random = new Random(kind.hashCode());
        for (var length = 0; length < data.length; length += 1 + data.length / 97) {
            survive(Arrays.copyOf(data, length), kind + " cut at " + length);
        }
        for (var round = 0; round < 200; round++) {
            var damaged = data.clone();
            // corrupt the directories, where the frames and their geometry are described
            for (var flip = 0; flip < 4; flip++) {
                var at = random.nextInt(Math.min(damaged.length, 1024));
                damaged[at] = (byte) random.nextInt(256);
            }
            survive(damaged, kind + " corrupted, round " + round);
        }
    }

    private static void survive(byte[] data, String what) {
        try {
            RawDecoder.decodeRaw(MediaSource.of(data));
            RawDecoder.decode(MediaSource.of(data));
        } catch (MediaException expected) {
            // a clean rejection
        } catch (RuntimeException | Error failure) {
            fail(what + ": " + failure, failure);
        }
    }
}
