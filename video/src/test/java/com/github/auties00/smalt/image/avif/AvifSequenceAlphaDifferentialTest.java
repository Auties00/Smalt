package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.image.avif.AvifSequenceAlphaTest.DELTA;
import static com.github.auties00.smalt.image.avif.AvifSequenceAlphaTest.FRAMES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compares the frames of AVIF sequences whose alpha track is timed unlike the color track with libavif 1.3.0's
 * {@code avifdec --index}, as a black box: frame {@code n} read from the start and frame {@code n} after a seek to
 * its time must both carry the alpha plane libavif pairs with color sample {@code n}, within one code value (the
 * two libraries round the expansion of limited-range alpha apart), and its color within 35 dB PSNR (libavif
 * converts Y'CbCr to R'G'B' with other rounding). libavif's PNG output is read back as raw RGBA through ffmpeg.
 */
@DisplayName("AVIF sequence alpha pairing against libavif")
class AvifSequenceAlphaDifferentialTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private static final Tolerance ALPHA = Tolerance.offByOne();

    private static final Tolerance COLOR = Tolerance.psnr(35);

    /** {@return frame {@code index} of a sequence as libavif decodes it, as 8-bit RGBA without padding} */
    private static byte[] libavif(byte[] file, String name, int index) throws IOException {
        var directory = Scratch.directory("avif-sequence-alpha");
        var input = directory.resolve(name + ".avif");
        Files.write(input, file);
        var png = directory.resolve(name + "-" + index + ".png");
        var raw = directory.resolve(name + "-" + index + ".rgba");
        Files.deleteIfExists(png);
        Files.deleteIfExists(raw);
        Oracle.run(Oracle.Tool.AVIFDEC, TIMEOUT, List.of("--index", Integer.toString(index), "--depth", "8",
                input.toString(), png.toString()));
        Oracle.run(Oracle.Tool.FFMPEG, TIMEOUT, List.of("-v", "error", "-y", "-i", png.toString(), "-f", "rawvideo",
                "-pix_fmt", "rgba", raw.toString()));
        return Files.readAllBytes(raw);
    }

    private static byte[] channels(byte[] rgba, boolean alpha) {
        var out = new byte[rgba.length / 4 * (alpha ? 1 : 3)];
        var at = 0;
        for (var pixel = 0; pixel < rgba.length; pixel += 4) {
            if (alpha) {
                out[at++] = rgba[pixel + 3];
            } else {
                out[at++] = rgba[pixel];
                out[at++] = rgba[pixel + 1];
                out[at++] = rgba[pixel + 2];
            }
        }
        return out;
    }

    private static void compare(String what, byte[] expected, AnimationFrame frame) {
        assertNotNull(frame, what);
        var actual = (byte[]) AnimationSeeks.pixels((RgbaPicture) frame.picture());
        assertEquals(expected.length, actual.length, what + ": size");
        ALPHA.assertWithin(what + ": alpha", channels(expected, true), channels(actual, true));
        COLOR.assertWithin(what + ": color", channels(expected, false), channels(actual, false));
    }

    @ParameterizedTest(name = "alpha samples of {0} ms")
    @ValueSource(ints = {0, 40, 230})
    @DisplayName("every frame read in order and after a seek pairs color and alpha as libavif does")
    void matchesLibavif(int alphaDelta) throws IOException {
        Oracle.assume(Oracle.Tool.AVIFDEC);
        Oracle.assume(Oracle.Tool.FFMPEG);
        var color = AvifSequenceAlphaTest.color(3);
        var alpha = AvifSequenceAlphaTest.alpha(5);
        int[] durations;
        if (alphaDelta == 0) {
            durations = new int[FRAMES];
            for (var index = 0; index < FRAMES; index++) {
                durations[index] = 1 + index * 53 % 260;
            }
        } else {
            durations = AvifSequenceAlphaTest.constant(alphaDelta);
        }
        var file = AvifSequenceAlphaTest.sequence(color, alpha, durations);
        var name = "alpha-" + alphaDelta;
        var expected = new ArrayList<byte[]>();
        for (var index = 0; index < FRAMES; index++) {
            expected.add(libavif(file, name, index));
        }
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
            for (var index = 0; index < FRAMES; index++) {
                compare("frame " + index, expected.get(index), reader.read());
            }
        }
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
            for (var index = FRAMES - 1; index >= 0; index -= 2) {
                reader.seek(index * DELTA * 1000L + DELTA * 500L);
                compare("frame " + index + " after a seek", expected.get(index), reader.read());
            }
        }
    }
}
