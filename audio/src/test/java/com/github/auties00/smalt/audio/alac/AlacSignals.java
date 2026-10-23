package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.ChannelLayout;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Integer test signals for the ALAC encoder tests, each shaped to reach a different path of the encoder: predictable
 * music for the predictor and matrixing searches, noise for escaped elements, silence and sparse impulses for the
 * zero runs of the residual code, alternating extremes for wrapped prediction, nearly identical channels for strong
 * matrixing, and samples whose low bytes alone vary for the shifted-off bytes of wide samples.
 */
final class AlacSignals {
    enum Shape {
        MUSIC, NOISE, SILENCE, EXTREMES, IMPULSES, CORRELATED, LOW_BYTES, RAMP, QUIET_NOISE
    }

    private AlacSignals() {
        throw new AssertionError();
    }

    /** Returns the layout whose channel order is ALAC's bitstream order, so channel i is coded as bitstream channel i. */
    static ChannelLayout bitstreamLayout(int channels) {
        return new ChannelLayout(List.of(AlacDecoder.BITSTREAM_ORDER[channels - 1]));
    }

    /**
     * Returns planar samples of a shape.
     *
     * @param shape the shape
     * @param channels the channel count
     * @param frames the samples per channel
     * @param bits the sample size
     * @param seed the random seed
     * @return one array per channel, every sample within the sample size
     */
    static int[][] samples(Shape shape, int channels, int frames, int bits, long seed) {
        var random = new SplittableRandom(seed);
        var max = bits == 32 ? Integer.MAX_VALUE : (1 << (bits - 1)) - 1;
        var min = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
        var planes = new int[channels][frames];
        var common = new double[frames];
        for (var i = 0; i < frames; i++) {
            common[i] = 0.5 * StrictMath.sin(2 * StrictMath.PI * 311 * i / 44100.0)
                    + 0.25 * StrictMath.sin(2 * StrictMath.PI * 1733 * i / 44100.0 + 0.7);
        }
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var phase = 0.9 * c;
            for (var i = 0; i < frames; i++) {
                double value = switch (shape) {
                    case MUSIC -> 0.6 * StrictMath.sin(2 * StrictMath.PI * (220 + 110 * c) * i / 44100.0 + phase)
                            + 0.2 * StrictMath.sin(2 * StrictMath.PI * (3100 + 500 * c) * i / 44100.0)
                            + 0.01 * (random.nextDouble() - 0.5);
                    case NOISE -> random.nextDouble() * 2 - 1;
                    case SILENCE -> 0;
                    case EXTREMES -> ((i + c) & 1) == 0 ? 1 : -1;
                    case IMPULSES -> random.nextInt(97) == 0 ? random.nextDouble() * 2 - 1 : 0;
                    case CORRELATED -> common[i] + 0.002 * c * StrictMath.sin(i * 0.01) + 0.0005 * (random.nextDouble() - 0.5);
                    case RAMP -> ((i * (c + 1) * 977L) % 4001) / 2000.0 - 1;
                    case QUIET_NOISE, LOW_BYTES -> 0;
                };
                long sample = Math.round(value * max);
                if (shape == Shape.LOW_BYTES) {
                    // a slow sine in the high bits with random low bytes
                    var high = Math.round(0.4 * StrictMath.sin(i * 0.003 + phase) * max);
                    var low = bits > 16 ? random.nextInt(1 << Math.min(bits - 8, 16)) : random.nextInt(256);
                    sample = (high & ~0xFFFFL) | low;
                } else if (shape == Shape.QUIET_NOISE) {
                    sample = random.nextInt(-3, 4);
                }
                plane[i] = (int) Math.max(min, Math.min(max, sample));
            }
        }
        return planes;
    }

    /** Interleaves planar samples. */
    static int[] interleave(int[][] planes) {
        var channels = planes.length;
        var frames = planes[0].length;
        var out = new int[channels * frames];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < frames; i++) {
                out[i * channels + c] = planes[c][i];
            }
        }
        return out;
    }
}
