package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the frequency mapping of the SSR filterbank: a single spectral line, held over many frames, must
 * come out of the four band transforms, the spectral reversal of the odd bands and the IPQF as the tone the
 * 1024-line filterbank makes of it, with the images of the other bands cancelled.
 */
class SsrSynthesisTest {
    /** Parses an all-zero long-window AAC SSR stream at 48 kHz. */
    static ChannelStream silentFrame() {
        var writer = new MsbBitWriter();
        writer.write(100, 8);
        writer.write(0, 1);
        writer.write(ChannelStream.ONLY_LONG_SEQUENCE, 2);
        writer.write(0, 1);
        writer.write(0, 6);
        writer.write(0, 1);
        writer.write(0, 3);
        writer.write(0, 8);
        var stream = new ChannelStream(1024, 3, AudioSpecificConfig.AOT_SSR, AacKernels.scalar());
        stream.parse(new AacBitReader(writer.toByteArray()), false, false);
        return stream;
    }

    @ParameterizedTest(name = "line {0}")
    @ValueSource(ints = {5, 100, 250, 262, 300, 400, 500, 530, 600, 700, 760, 790, 900, 1010})
    @DisplayName("turns a spectral line into the tone the 1024-line filterbank makes of it")
    void lineFrequency(int line) {
        var stream = silentFrame();
        stream.spectrum()[line] = 1000;
        var frames = 16;
        var ssr = new float[frames * 1024];
        var plain = new float[frames * 1024];
        var synthesis = new SsrSynthesis(AacKernels.scalar());
        var state = new SsrSynthesis.State();
        var reference = new Synthesis(1024, AacKernels.scalar());
        var overlap = new float[1024];
        for (var frame = 0; frame < frames; frame++) {
            synthesis.synthesize(stream, state, ssr, frame * 1024);
            reference.synthesize(stream.spectrum(), ChannelStream.ONLY_LONG_SEQUENCE, 0, 0, overlap, plain, frame * 1024);
        }
        var expected = tone(plain, 0, LENGTH / 2);
        var actual = tone(ssr, (int) expected[0] - 64, (int) expected[0] + 64);
        assertEquals(expected[0], actual[0], "tone of line " + line);
        // near a band edge the image a single band leaves is cancelled only by its neighbor's
        var edge = Math.min(line % 256, 256 - line % 256);
        if (edge >= 32) {
            assertTrue(actual[1] > 0.9, "the tone holds " + actual[1] + " of the energy");
        }
    }

    /** The number of samples analyzed, after 8 frames of settling. */
    private static final int LENGTH = 8 * 1024;

    /**
     * Returns the strongest DFT bin between two bins, stepping by two, and its share of the energy.
     */
    static double[] tone(float[] output, int from, int to) {
        var start = 8 * 1024;
        var length = LENGTH;
        double total = 0;
        for (var i = start; i < start + length; i++) {
            total += (double) output[i] * output[i];
        }
        var best = 0.0;
        var bestBin = 0;
        for (var bin = Math.max(0, from); bin < Math.min(to, length / 2); bin += 2) {
            double re = 0;
            double im = 0;
            for (var i = 0; i < length; i++) {
                var angle = 2 * Math.PI * bin * (double) i / length;
                re += output[start + i] * Math.cos(angle);
                im += output[start + i] * Math.sin(angle);
            }
            var power = re * re + im * im;
            if (power > best) {
                best = power;
                bestBin = bin;
            }
        }
        return new double[]{bestBin, 2 * best / length / total};
    }
}
