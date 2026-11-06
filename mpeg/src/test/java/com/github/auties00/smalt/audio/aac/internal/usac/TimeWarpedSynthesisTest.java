package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks the time-warped filterbank against the reference software's. The resources {@code tw-<length>.f32} are
 * the output of {@code tools/testdata/usac-tw-oracle} (the ISO/IEC 23003-3 reference software's
 * {@code usac_tw_tools.c} and the time-warped branch of {@code fd2buffer}, driven like {@code decodeChannelElement})
 * over pseudo-random window sequences, window shapes, warp ratios and inverse transform outputs, which this suite
 * rebuilds from the same xorshift32 generator.
 */
class TimeWarpedSynthesisTest {
    // the resources were written with mingw-w64 gcc 15.2 on Windows x64, whose cos, sin and sqrt round the window
    // and interpolator tables as StrictMath does
    static final Tolerance TOLERANCE = Tolerance.exact();

    static int state;

    static int next() {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state;
    }

    static float[] reference(String name) {
        try (var input = TimeWarpedSynthesisTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            var bytes = input.readAllBytes();
            var samples = new float[bytes.length / 4];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
            return samples;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static int sequence(int last) {
        if (last == FdChannel.ONLY_LONG || last == FdChannel.LONG_STOP) {
            return Integer.remainderUnsigned(next(), 2) == 0 ? FdChannel.ONLY_LONG : FdChannel.LONG_START;
        }
        return switch (Integer.remainderUnsigned(next(), 3)) {
            case 0 -> FdChannel.EIGHT_SHORT;
            case 1 -> FdChannel.LONG_STOP;
            default -> FdChannel.STOP_START;
        };
    }

    @ParameterizedTest(name = "{0}-sample frames")
    @CsvSource({"1024, 12345", "768, 777"})
    @DisplayName("matches the reference software over random sequences, shapes and warps")
    void matchesReference(int length, int seed) {
        var expected = reference("tw-" + length + ".f32");
        var frames = expected.length / length;
        assertEquals(16, frames);
        var synthesis = new TimeWarpedSynthesis(length);
        var channel = new TimeWarpedSynthesis.State(length);
        var overlap = new float[2 * length];
        var transform = new float[2 * length];
        var ratios = new int[TimeWarpedSynthesis.NODES];
        var actual = new float[expected.length];
        state = seed;
        var last = FdChannel.ONLY_LONG;
        var lastShape = 0;
        for (var frame = 0; frame < frames; frame++) {
            var sequence = sequence(last);
            var shape = next() & 1;
            var present = Integer.remainderUnsigned(next(), 4) != 0;
            for (var i = 0; i < ratios.length; i++) {
                ratios[i] = present ? Integer.remainderUnsigned(next(), 8) : 0;
            }
            for (var i = 0; i < transform.length; i++) {
                transform[i] = (float) next() * (1.0f / 2147483648.0f) * 8192.0f;
            }
            synthesis.prepare(channel, present, ratios, sequence, last);
            synthesis.synthesize(channel, transform, sequence, last, shape, lastShape, overlap, actual,
                    frame * length);
            last = sequence;
            lastShape = shape;
        }
        TOLERANCE.assertPcm(length + "-sample frames", expected, actual);
    }

    @ParameterizedTest(name = "{0}-sample frames")
    @CsvSource({"1024, 4242", "768, 99"})
    @DisplayName("without warp data, equals the plain filterbank of the sine window half a frame later")
    void unwarpedLagsPlainFilterbank(int length, int seed) {
        var frames = 24;
        var imdct = new Imdct(AacKernels.INSTANCE);
        var plain = new FdSynthesis(length, imdct);
        var warped = new TimeWarpedSynthesis(length);
        var channel = new TimeWarpedSynthesis.State(length);
        var plainOverlap = new float[2 * length];
        var warpedOverlap = new float[2 * length];
        var spectrum = new float[length];
        var transform = new float[2 * length];
        var ratios = new int[TimeWarpedSynthesis.NODES];
        var plainOut = new float[frames * length];
        var warpedOut = new float[frames * length];
        state = seed;
        var last = FdChannel.ONLY_LONG;
        var lastShape = 0;
        for (var frame = 0; frame < frames; frame++) {
            var sequence = sequence(last);
            // the sine window: the time-warped filterbank derives every KBD transition from the long KBD window,
            // where the plain filterbank takes the short ones from the KBD window of alpha 6
            var shape = 0;
            for (var i = 0; i < length; i++) {
                spectrum[i] = (float) next() * (1.0f / 2147483648.0f) * 1024.0f;
            }
            plain.synthesize(spectrum, sequence, shape, lastShape, false, null, plainOverlap, plainOut,
                    frame * length);
            if (sequence == FdChannel.EIGHT_SHORT) {
                for (var k = 0; k < 8; k++) {
                    imdct.inverse(spectrum, k * length / 8, length / 8, transform, 2 * k * length / 8);
                }
            } else {
                imdct.inverse(spectrum, 0, length, transform, 0);
            }
            warped.prepare(channel, false, ratios, sequence, last);
            warped.synthesize(channel, transform, sequence, last, shape, lastShape, warpedOverlap, warpedOut,
                    frame * length);
            last = sequence;
            lastShape = shape;
        }
        var lag = length / 2;
        var expected = java.util.Arrays.copyOf(plainOut, plainOut.length - lag);
        var actual = java.util.Arrays.copyOfRange(warpedOut, lag, warpedOut.length);
        Tolerance.snr(100).assertPcm(length + "-sample frames", expected, actual);
    }
}
