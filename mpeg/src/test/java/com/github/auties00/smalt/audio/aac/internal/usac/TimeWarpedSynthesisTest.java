package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the time-warped filterbank against the reference software's. The resources {@code tw-<length>.f32} are
 * the output of {@code tools/testdata/usac-tw-oracle} (the ISO/IEC 23003-3 reference software's
 * {@code usac_tw_tools.c} and the time-warped branch of {@code fd2buffer}, driven like {@code decodeChannelElement})
 * over pseudo-random window sequences, window shapes, warp ratios and inverse transform outputs, which this suite
 * rebuilds from the same xorshift32 generator; {@code tw-lpd-<length>.f32} add the transitions to and from
 * synthetic linear prediction domain frames ({@code usac_past_tw}, {@code usac_td2buffer} and the {@code FrameWasTd}
 * branches of {@code fd2buffer}, with the two changes {@code twref.c} documents). {@code tw.sha256} pins them.
 */
class TimeWarpedSynthesisTest {
    // the resources were written with mingw-w64 gcc 15.2 on Windows x64, whose cos, sin and sqrt round the window
    // and interpolator tables as StrictMath does
    static final Tolerance TOLERANCE = Tolerance.exact();

    static int state;

    static float sample() {
        return (float) next() * (1.0f / 2147483648.0f) * 8192.0f;
    }

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
            synthesis.prepare(channel, present, ratios, sequence, last, false);
            synthesis.synthesize(channel, transform, sequence, last, shape, lastShape, false, null, overlap, actual,
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
            warped.prepare(channel, false, ratios, sequence, last, false);
            warped.synthesize(channel, transform, sequence, last, shape, lastShape, false, null, warpedOverlap,
                    warpedOut, frame * length);
            last = sequence;
            lastShape = shape;
        }
        var lag = length / 2;
        var expected = java.util.Arrays.copyOf(plainOut, plainOut.length - lag);
        var actual = java.util.Arrays.copyOfRange(warpedOut, lag, warpedOut.length);
        Tolerance.snr(100).assertPcm(length + "-sample frames", expected, actual);
    }
    @ParameterizedTest(name = "{0}-sample frames")
    @CsvSource({"1024, 2026", "768, 1108"})
    @DisplayName("matches the reference software across transitions to and from linear prediction domain frames")
    void matchesReferenceAcrossLpdTransitions(int length, int seed) {
        var expected = reference("tw-lpd-" + length + ".f32");
        var synthesis = new TimeWarpedSynthesis(length);
        var channel = new TimeWarpedSynthesis.State(length);
        var overlap = new float[2 * length];
        var transform = new float[2 * length];
        var synth = new float[length];
        var fac = new float[length / 4];
        var out = new float[length];
        var ratios = new int[TimeWarpedSynthesis.NODES];
        var actual = new float[expected.length];
        var position = 0;
        var transitions = new TreeMap<String, Integer>();
        state = seed;
        var last = FdChannel.ONLY_LONG;
        var lastShape = 0;
        var lastLpd = false;
        for (var frame = 0; frame < 96; frame++) {
            var lpd = (lastLpd || last == FdChannel.LONG_START || last == FdChannel.EIGHT_SHORT
                    || last == FdChannel.STOP_START) && Integer.remainderUnsigned(next(), 2) == 0;
            if (lpd) {
                var acelpFirst = Integer.remainderUnsigned(next(), 2) == 0;
                for (var i = 0; i < length; i++) {
                    synth[i] = sample();
                }
                var lfac = 0;
                if (!lastLpd) {
                    synthesis.finishPast(channel, last, lastShape, overlap);
                    System.arraycopy(overlap, 0, actual, position, 2 * length);
                    position += 2 * length;
                    lfac = last == FdChannel.EIGHT_SHORT ? length / 16 : length / 8;
                    transitions.merge("fd " + last + " to " + (acelpFirst ? "acelp" : "tcx"), 1, Integer::sum);
                }
                TimeWarpedSynthesis.combineLpd(length, synth, overlap, lfac, acelpFirst, 64, out);
                System.arraycopy(out, 0, actual, position, length);
                position += length;
                last = FdChannel.EIGHT_SHORT;
                lastShape = 0;
                lastLpd = true;
                continue;
            }
            var sequence = sequence(last);
            var shape = next() & 1;
            var present = Integer.remainderUnsigned(next(), 4) != 0;
            for (var i = 0; i < ratios.length; i++) {
                ratios[i] = present ? Integer.remainderUnsigned(next(), 8) : 0;
            }
            for (var i = 0; i < transform.length; i++) {
                transform[i] = sample();
            }
            float[] signal = null;
            if (lastLpd) {
                for (var i = 0; i < length; i++) {
                    overlap[length / 2 + i] = i < length / 2 + length / 8 ? sample() : 0f;
                }
                var applyFac = Integer.remainderUnsigned(next(), 2) == 0;
                if (applyFac) {
                    var lfac = sequence == FdChannel.EIGHT_SHORT ? length / 16 : length / 8;
                    for (var i = 0; i < 2 * lfac; i++) {
                        fac[i] = sample();
                    }
                    signal = fac;
                }
                transitions.merge("lpd to fd " + sequence + (applyFac ? " with fac" : ""), 1, Integer::sum);
            }
            synthesis.prepare(channel, present, ratios, sequence, last, lastLpd);
            synthesis.synthesize(channel, transform, sequence, last, shape, lastShape, lastLpd, signal, overlap,
                    actual, position);
            position += length;
            last = sequence;
            lastShape = shape;
            lastLpd = false;
        }
        assertEquals(expected.length, position);
        TOLERANCE.assertPcm(length + "-sample frames", expected, actual);
        // every frequency domain to linear prediction domain transition and back, with and without FAC
        assertEquals(12, transitions.size(), transitions.toString());
    }

    @ParameterizedTest(name = "{0}-sample frames")
    @CsvSource({"1024", "768"})
    @DisplayName("add a frame's right half once when a linear prediction domain frame fails after completing it")
    void finishedPastIsAddedOnce(int length) {
        var expected = shortFrames(length, false);
        var actual = shortFrames(length, true);
        var energy = 0.0;
        for (var v : expected) {
            energy += v * v;
        }
        assertTrue(energy > 0);
        TOLERANCE.assertPcm(length + "-sample frames", expected, actual);
    }

    /**
     * Decodes three frames of eight short windows, whose right half {@code finishPast} does not window, optionally
     * completing the first one's right half before the second, as a failed linear prediction domain frame does.
     */
    private static float[] shortFrames(int length, boolean finishFirst) {
        state = 4242;
        var synthesis = new TimeWarpedSynthesis(length);
        var channel = new TimeWarpedSynthesis.State(length);
        var overlap = new float[2 * length];
        var transform = new float[2 * length];
        var ratios = new int[TimeWarpedSynthesis.NODES];
        var out = new float[3 * length];
        for (var f = 0; f < 3; f++) {
            for (var i = 0; i < transform.length; i++) {
                transform[i] = sample();
            }
            if (f == 1 && finishFirst) {
                synthesis.finishPast(channel, FdChannel.EIGHT_SHORT, 0, overlap);
            }
            synthesis.prepare(channel, false, ratios, FdChannel.EIGHT_SHORT, FdChannel.EIGHT_SHORT, false);
            synthesis.synthesize(channel, transform, FdChannel.EIGHT_SHORT, FdChannel.EIGHT_SHORT, 0, 0, false, null,
                    overlap, out, f * length);
        }
        return out;
    }

    @Test
    @DisplayName("match every committed reference output to its pin in tw.sha256")
    void pins() throws Exception {
        var lines = new String(bytes("tw.sha256"), StandardCharsets.US_ASCII).strip().split("\\R");
        assertEquals(4, lines.length);
        for (var line : lines) {
            var fields = line.split("  ");
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes(fields[1]));
            assertEquals(fields[0], HexFormat.of().formatHex(digest), fields[1]);
        }
    }

    static byte[] bytes(String name) {
        try (var input = TimeWarpedSynthesisTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
