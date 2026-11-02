/* Ports libopus 1.5.2 (src/opus_compare.c). Copyright (c) 2011-2012 Xiph.Org Foundation, Mozilla Corporation;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.testkit.audio;

/**
 * Compares a decoded Opus signal with its reference by the criterion of libopus' {@code opus_compare} test tool
 * (RFC 6716 section 6.2, RFC 8251): the perceptually weighted comparison that decides whether a decoder's output of an
 * official test vector conforms, and the rule of {@link com.github.auties00.smalt.testkit.Tolerance#opusCompare}.
 *
 * <p>The only change from the C tool is how the per-bin spectra are computed: the tool evaluates the 480-point DFT
 * naively, this port uses a mixed-radix FFT in double precision, which changes rounding by far less than the metric
 * resolves; the core test kit's {@code ToleranceTest} checks the port against the tool where CI provisions it.
 */
public final class OpusCompare {
    private static final int NBANDS = 21;
    private static final int NFREQS = 240;
    private static final int[] BANDS = {0, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 68, 80, 96, 120, 156, 200};
    private static final int TEST_WIN_SIZE = 480;
    private static final int TEST_WIN_STEP = 120;

    /**
     * The outcome of a comparison.
     *
     * @param passes whether the decode conforms, that is the quality is not negative
     * @param quality the quality metric in percent, as the tool prints it
     * @param error the internal weighted error
     */
    public record Result(boolean passes, double quality, double error) {
    }

    private OpusCompare() {
        throw new AssertionError();
    }

    /**
     * The rate-independent analysis of a reference: masked band energies and bin spectra.
     *
     * @param channels the channel count of the comparison, 1 or 2
     * @param xlength the number of reference frames
     * @param nframes the number of analysis windows
     * @param xb the masked band energies
     * @param X the masked bin spectra
     */
    public record Reference(int channels, int xlength, int nframes, float[] xb, float[] X) {
    }

    /**
     * Analyzes a reference once so it can be compared with decodes at every rate.
     *
     * @param reference the reference {@code .dec} samples: interleaved stereo 16-bit at 48 kHz
     * @param channels  1 or 2 (the {@code -s} flag)
     */
    public static Reference prepare(short[] reference, int channels) {
        var xlength = reference.length / 2;
        var x = new float[xlength * channels];
        if (channels == 1) {
            for (var i = 0; i < xlength; i++) {
                x[i] = (float) (.5 * ((float) reference[2 * i] + (float) reference[2 * i + 1]));
            }
        } else {
            for (var i = 0; i < 2 * xlength; i++) {
                x[i] = reference[i];
            }
        }
        if (xlength < TEST_WIN_SIZE) {
            throw new IllegalArgumentException("insufficient sample data");
        }
        var nframes = (xlength - TEST_WIN_SIZE + TEST_WIN_STEP) / TEST_WIN_STEP;
        var xb = new float[nframes * NBANDS * channels];
        var X = new float[nframes * NFREQS * channels];
        bandEnergy(xb, X, NBANDS, x, channels, nframes, TEST_WIN_SIZE, TEST_WIN_STEP, 1);
        for (var xi = 0; xi < nframes; xi++) {
            for (var bi = 1; bi < NBANDS; bi++) {
                for (var ci = 0; ci < channels; ci++) {
                    xb[(xi * NBANDS + bi) * channels + ci] += 0.1F * xb[(xi * NBANDS + bi - 1) * channels + ci];
                }
            }
            for (var bi = NBANDS - 1; bi-- > 0; ) {
                for (var ci = 0; ci < channels; ci++) {
                    xb[(xi * NBANDS + bi) * channels + ci] += 0.03F * xb[(xi * NBANDS + bi + 1) * channels + ci];
                }
            }
            if (xi > 0) {
                for (var bi = 0; bi < NBANDS; bi++) {
                    for (var ci = 0; ci < channels; ci++) {
                        xb[(xi * NBANDS + bi) * channels + ci] += 0.5F * xb[((xi - 1) * NBANDS + bi) * channels + ci];
                    }
                }
            }
            if (channels == 2) {
                for (var bi = 0; bi < NBANDS; bi++) {
                    var l = xb[(xi * NBANDS + bi) * channels];
                    var r = xb[(xi * NBANDS + bi) * channels + 1];
                    xb[(xi * NBANDS + bi) * channels] += 0.01F * r;
                    xb[(xi * NBANDS + bi) * channels + 1] += 0.01F * l;
                }
            }
            for (var bi = 0; bi < NBANDS; bi++) {
                for (var xj = BANDS[bi]; xj < BANDS[bi + 1]; xj++) {
                    for (var ci = 0; ci < channels; ci++) {
                        X[(xi * NFREQS + xj) * channels + ci] += 0.1F * xb[(xi * NBANDS + bi) * channels + ci];
                    }
                }
            }
        }
        for (var bi = 0; bi < NBANDS; bi++) {
            for (var xj = BANDS[bi]; xj < BANDS[bi + 1]; xj++) {
                for (var ci = 0; ci < channels; ci++) {
                    var xtmp = X[xj * channels + ci];
                    for (var xi = 1; xi < nframes; xi++) {
                        var xtmp2 = X[(xi * NFREQS + xj) * channels + ci];
                        X[(xi * NFREQS + xj) * channels + ci] += xtmp;
                        xtmp = xtmp2;
                    }
                }
            }
        }
        return new Reference(channels, xlength, nframes, xb, X);
    }

    /**
     * Compares a decoded signal with a reference.
     *
     * @param reference the reference {@code .dec} samples: interleaved stereo 16-bit at 48 kHz
     * @param decoded   the decoded samples, interleaved, as 16-bit values
     * @param channels  1 or 2 (the {@code -s} flag)
     * @param rate      the rate of {@code decoded} (the {@code -r} flag)
     */
    public static Result compare(short[] reference, short[] decoded, int channels, int rate) {
        return compare(prepare(reference, channels), decoded, rate);
    }

    /**
     * Compares a decoded signal with a prepared reference ({@code opus_compare -r rate}).
     *
     * @param reference the prepared reference
     * @param decoded   the decoded samples, interleaved, as 16-bit values
     * @param rate      the rate of {@code decoded}
     */
    public static Result compare(Reference reference, short[] decoded, int rate) {
        var channels = reference.channels();
        var downsample = 48000 / rate;
        var ybands = switch (rate) {
            case 8000 -> 13;
            case 12000 -> 15;
            case 16000 -> 17;
            case 24000 -> 19;
            default -> NBANDS;
        };
        var yfreqs = NFREQS / downsample;
        var ylength = decoded.length / channels;
        if (reference.xlength() != (long) ylength * downsample) {
            throw new IllegalArgumentException("sample counts do not match: " + reference.xlength() + " != " + (long) ylength * downsample);
        }
        var y = new float[ylength * channels];
        for (var i = 0; i < y.length; i++) {
            y[i] = decoded[i];
        }
        var nframes = reference.nframes();
        var xb = reference.xb();
        var X = reference.X();
        var Y = new float[nframes * yfreqs * channels];
        bandEnergy(null, Y, ybands, y, channels, nframes, TEST_WIN_SIZE / downsample, TEST_WIN_STEP / downsample, downsample);
        for (var xi = 0; xi < nframes; xi++) {
            for (var bi = 0; bi < ybands; bi++) {
                for (var xj = BANDS[bi]; xj < BANDS[bi + 1]; xj++) {
                    for (var ci = 0; ci < channels; ci++) {
                        Y[(xi * yfreqs + xj) * channels + ci] += 0.1F * xb[(xi * NBANDS + bi) * channels + ci];
                    }
                }
            }
        }
        for (var bi = 0; bi < ybands; bi++) {
            for (var xj = BANDS[bi]; xj < BANDS[bi + 1]; xj++) {
                for (var ci = 0; ci < channels; ci++) {
                    var ytmp = Y[xj * channels + ci];
                    for (var xi = 1; xi < nframes; xi++) {
                        var ytmp2 = Y[(xi * yfreqs + xj) * channels + ci];
                        Y[(xi * yfreqs + xj) * channels + ci] += ytmp;
                        ytmp = ytmp2;
                    }
                }
            }
        }
        int maxCompare;
        if (rate == 48000) {
            maxCompare = BANDS[NBANDS];
        } else if (rate == 12000) {
            maxCompare = BANDS[ybands];
        } else {
            maxCompare = BANDS[ybands] - 3;
        }
        double err = 0;
        for (var xi = 0; xi < nframes; xi++) {
            double ef = 0;
            for (var bi = 0; bi < ybands; bi++) {
                double eb = 0;
                for (var xj = BANDS[bi]; xj < BANDS[bi + 1] && xj < maxCompare; xj++) {
                    for (var ci = 0; ci < channels; ci++) {
                        var re = Y[(xi * yfreqs + xj) * channels + ci] / X[(xi * NFREQS + xj) * channels + ci];
                        var im = (float) (re - Math.log(re) - 1);
                        if (xj >= 79 && xj <= 81) {
                            im *= 0.1F;
                        }
                        if (xj == 80) {
                            im *= 0.1F;
                        }
                        eb += im;
                    }
                }
                eb /= (BANDS[bi + 1] - BANDS[bi]) * channels;
                ef += eb * eb;
            }
            ef /= NBANDS;
            ef *= ef;
            err += ef * ef;
        }
        err = Math.pow(err / nframes, 1.0 / 16);
        var q = 100 * (1 - 0.5 * Math.log(1 + err) / Math.log(1.13));
        return new Result(q >= 0, q, err);
    }

    /**
     * Compares a decode of any channel count with a reference of the same channel count: a mono or stereo signal as
     * {@code opus_compare} compares it ({@code -s} for stereo), a signal of more channels channel by channel, each as
     * a mono signal.
     *
     * @param reference the reference samples, interleaved, at 48 kHz
     * @param decoded the decoded samples, interleaved, at {@code rate}
     * @param channels the channel count of both signals
     * @param rate the rate of {@code decoded}
     * @return the result of each comparison: one for a mono or stereo signal, one per channel otherwise
     * @throws IllegalArgumentException if the lengths do not match or the signals are shorter than one window
     */
    public static java.util.List<Result> compareChannels(short[] reference, short[] decoded, int channels, int rate) {
        if (channels == 2) {
            return java.util.List.of(compare(reference, decoded, 2, rate));
        }
        var frames = reference.length / channels;
        var results = new java.util.ArrayList<Result>();
        for (var channel = 0; channel < channels; channel++) {
            var stereo = new short[frames * 2];
            for (var frame = 0; frame < frames; frame++) {
                stereo[2 * frame] = reference[frame * channels + channel];
                stereo[2 * frame + 1] = reference[frame * channels + channel];
            }
            var mono = new short[decoded.length / channels];
            for (var frame = 0; frame < mono.length; frame++) {
                mono[frame] = decoded[frame * channels + channel];
            }
            results.add(compare(stereo, mono, 1, rate));
        }
        return results;
    }

    /**
     * Converts full-scale float samples to 16-bit values, rounding to nearest and saturating, the input of the tool.
     *
     * @param samples the samples, full scale 1.0
     * @return the 16-bit samples
     */
    public static short[] toPcm16(float[] samples) {
        var out = new short[samples.length];
        for (var index = 0; index < samples.length; index++) {
            out[index] = (short) Math.max(-32768, Math.min(32767, Math.round(samples[index] * 32768.0)));
        }
        return out;
    }

    private static void bandEnergy(float[] out, float[] ps, int nbands, float[] in, int channels, int nframes,
                                   int windowSize, int step, int downsample) {
        var window = new float[windowSize];
        for (var xj = 0; xj < windowSize; xj++) {
            window[xj] = 0.5F - 0.5F * (float) Math.cos((2 * 3.14159265F / (windowSize - 1)) * xj);
        }
        var fft = new Fft(windowSize);
        var re = new double[windowSize];
        var im = new double[windowSize];
        var psSize = windowSize / 2;
        var p = new float[2];
        for (var xi = 0; xi < nframes; xi++) {
            var binRe = new double[channels][];
            var binIm = new double[channels][];
            for (var ci = 0; ci < channels; ci++) {
                for (var xk = 0; xk < windowSize; xk++) {
                    re[xk] = window[xk] * in[(xi * step + xk) * channels + ci];
                    im[xk] = 0;
                }
                fft.transform(re, im);
                binRe[ci] = re.clone();
                binIm[ci] = im.clone();
            }
            var xj = 0;
            for (var bi = 0; bi < nbands; bi++) {
                p[0] = 0;
                p[1] = 0;
                for (; xj < BANDS[bi + 1]; xj++) {
                    for (var ci = 0; ci < channels; ci++) {
                        var r = (float) binRe[ci][xj] * downsample;
                        var i = (float) binIm[ci][xj] * downsample;
                        ps[(xi * psSize + xj) * channels + ci] = r * r + i * i + 100000;
                        p[ci] += ps[(xi * psSize + xj) * channels + ci];
                    }
                }
                if (out != null) {
                    out[(xi * nbands + bi) * channels] = p[0] / (BANDS[bi + 1] - BANDS[bi]);
                    if (channels == 2) {
                        out[(xi * nbands + bi) * channels + 1] = p[1] / (BANDS[bi + 1] - BANDS[bi]);
                    }
                }
            }
        }
    }

    /** A small mixed-radix (2, 3, 5) complex FFT in double precision with preallocated scratch. */
    static final class Fft {
        private final int n;
        private final double[] cos;
        private final double[] sin;
        private final double[] tmpRe;
        private final double[] tmpIm;
        private final double[][] scratchRe;
        private final double[][] scratchIm;

        Fft(int n) {
            this.n = n;
            this.cos = new double[n];
            this.sin = new double[n];
            for (var k = 0; k < n; k++) {
                cos[k] = StrictMath.cos(2 * Math.PI * k / n);
                sin[k] = -StrictMath.sin(2 * Math.PI * k / n);
            }
            this.tmpRe = new double[n];
            this.tmpIm = new double[n];
            var depth = 0;
            for (var size = n; size > 1; size /= radix(size)) {
                depth++;
            }
            scratchRe = new double[depth + 1][];
            scratchIm = new double[depth + 1][];
            var size = n;
            for (var d = 0; d <= depth; d++) {
                scratchRe[d] = new double[size];
                scratchIm[d] = new double[size];
                if (size > 1) {
                    size /= radix(size);
                }
            }
        }

        private static int radix(int size) {
            return size % 2 == 0 ? 2 : size % 3 == 0 ? 3 : size % 5 == 0 ? 5 : size;
        }

        void transform(double[] re, double[] im) {
            recurse(re, im, 0, 1, n, tmpRe, tmpIm, 0, 0);
            System.arraycopy(tmpRe, 0, re, 0, n);
            System.arraycopy(tmpIm, 0, im, 0, n);
        }

        private void recurse(double[] inRe, double[] inIm, int offset, int stride, int size,
                             double[] outRe, double[] outIm, int outOff, int depth) {
            if (size == 1) {
                outRe[outOff] = inRe[offset];
                outIm[outOff] = inIm[offset];
                return;
            }
            var p = radix(size);
            var m = size / p;
            var subRe = scratchRe[depth];
            var subIm = scratchIm[depth];
            for (var j = 0; j < p; j++) {
                recurse(inRe, inIm, offset + j * stride, stride * p, m, subRe, subIm, j * m, depth + 1);
            }
            var twiddleStep = n / size;
            for (var k = 0; k < m; k++) {
                for (var q = 0; q < p; q++) {
                    var index = k + q * m;
                    double sr = 0;
                    double si = 0;
                    var t = 0;
                    var step = index * twiddleStep % n;
                    for (var j = 0; j < p; j++) {
                        var wr = cos[t];
                        var wi = sin[t];
                        var yr = subRe[j * m + k];
                        var yi = subIm[j * m + k];
                        sr += yr * wr - yi * wi;
                        si += yr * wi + yi * wr;
                        t += step;
                        if (t >= n) {
                            t -= n;
                        }
                    }
                    outRe[outOff + index] = sr;
                    outIm[outOff + index] = si;
                }
            }
        }
    }
}
