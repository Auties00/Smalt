/* Ports libaom 3.13.1 (aom_dsp/noise_model.c: aom_wiener_denoise_2d and its window and dithering;
   aom_dsp/noise_util.c: aom_noise_tx_filter and aom_noise_psd_get_default_value). Copyright (c) 2017,
   Alliance for Open Media; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Denoises picture planes with a frequency-domain Wiener filter of a flat noise spectrum: each
 * plane is cut into half-overlapping blocks, the planar fit of each block is kept, its residual is
 * filtered in the two-dimensional Fourier domain, and the windowed blocks are added back and
 * dithered to integer samples.
 *
 * <p>The output does not depend on the thread count. An instance keeps its buffers from one
 * picture to the next and is used by one thread at a time.
 *
 * @implNote This implementation follows libaom's {@code aom_wiener_denoise_2d} with a complex
 * Fourier transform in place of its packed real transform, and also denoises the rectangular
 * chroma blocks of 4:2:2 pictures, which libaom rejects; the four block offsets are filtered into
 * separate sums added in libaom's order, so they may run in parallel.
 */
final class WienerDenoiser {
    /**
     * The noise floor factor of the filter ({@code kBeta}).
     */
    private static final float BETA = 1.1f;

    /**
     * The smallest power the filter divides by ({@code kEps}).
     */
    private static final float EPS = 1e-6f;

    /**
     * The luma block size.
     */
    private final int blockSize;

    /**
     * The bit depth.
     */
    private final int bitDepth;

    /**
     * The noise level.
     */
    private final float noiseLevel;

    /**
     * The buffers of the luma planes (index 0) and of the chroma planes (index 1), kept from one
     * picture to the next while the plane size stays.
     */
    private final Buffers[] buffers = new Buffers[2];

    /**
     * The buffers of the denoising of planes of one block size and grid: the window, the four
     * shifted sums, and the scratch of each band.
     */
    private static final class Buffers {
        /**
         * The block width.
         */
        final int bw;

        /**
         * The block height.
         */
        final int bh;

        /**
         * The half-cosine window of a block.
         */
        final float[] window;

        /**
         * The sums of the four shifted block grids.
         */
        final float[][] sums;

        /**
         * The scratch of each band, created at its first use.
         */
        final Band[] bands = new Band[4];

        /**
         * Creates the buffers of a block size and of sums of a size.
         *
         * @param bw the block width
         * @param bh the block height
         * @param sumLength the samples of each sum
         */
        Buffers(int bw, int bh, int sumLength) {
            this.bw = bw;
            this.bh = bh;
            this.window = window(bw, bh);
            this.sums = new float[4][sumLength];
        }
    }

    /**
     * The scratch of one band of the denoising of a plane.
     */
    private static final class Band {
        /**
         * The block extractor.
         */
        final NoiseModel.FlatBlockFinder finder;

        /**
         * The transform.
         */
        final Fft fft;

        /**
         * The fitted plane of a block.
         */
        final double[] planeD;

        /**
         * The block less its fitted plane.
         */
        final double[] blockD;

        /**
         * The real parts of the coefficients.
         */
        final float[] re;

        /**
         * The imaginary parts of the coefficients.
         */
        final float[] im;

        /**
         * The windowed fitted plane.
         */
        final float[] plane;

        /**
         * Creates the scratch of a block size.
         *
         * @param bw the block width
         * @param bh the block height
         * @param bitDepth the bit depth
         */
        Band(int bw, int bh, int bitDepth) {
            this.finder = new NoiseModel.FlatBlockFinder(bw, bh, bitDepth);
            this.fft = new Fft(bw, bh);
            this.planeD = new double[bw * bh];
            this.blockD = new double[bw * bh];
            this.re = new float[bw * bh];
            this.im = new float[bw * bh];
            this.plane = new float[bw * bh];
        }
    }

    /**
     * Creates a denoiser.
     *
     * @param blockSize the luma block size, a power of two from 8 to 32
     * @param bitDepth the bit depth of the planes
     * @param noiseLevel the noise level, positive
     */
    WienerDenoiser(int blockSize, int bitDepth, float noiseLevel) {
        this.blockSize = blockSize;
        this.bitDepth = bitDepth;
        this.noiseLevel = noiseLevel;
    }

    /**
     * Returns the flat noise power of a block's coefficients ({@code aom_noise_psd_get_default_value}).
     *
     * @param samples the number of samples of the block
     * @param factor the noise level
     * @return the power
     */
    static float defaultPsd(int samples, float factor) {
        return factor * factor / 10000 * samples / 8;
    }

    /**
     * Returns the separable half-cosine window of a block ({@code get_half_cos_window}).
     *
     * @param w the block width
     * @param h the block height
     * @return the window, row-major
     */
    private static float[] window(int w, int h) {
        var out = new float[w * h];
        for (var y = 0; y < h; y++) {
            var cy = StrictMath.cos((0.5 + y) * Math.PI / h - Math.PI / 2);
            for (var x = 0; x < w; x++) {
                var cx = StrictMath.cos((0.5 + x) * Math.PI / w - Math.PI / 2);
                out[y * w + x] = (float) (cy * cx);
            }
        }
        return out;
    }

    /**
     * Denoises one plane.
     *
     * @param data the plane
     * @param w the plane width
     * @param h the plane height
     * @param stride the plane stride
     * @param subX the plane's horizontal subsampling against the luma block grid
     * @param subY the plane's vertical subsampling
     * @param lumaW the luma width, which sets the block grid
     * @param lumaH the luma height
     * @param out receives the denoised plane, of the same stride
     * @param threads the most threads to use
     */
    void denoise(short[] data, int w, int h, int stride, int subX, int subY, int lumaW, int lumaH, short[] out,
                 int threads) {
        var bw = blockSize >> subX;
        var bh = blockSize >> subY;
        var blocksW = (lumaW + blockSize - 1) / blockSize;
        var blocksH = (lumaH + blockSize - 1) / blockSize;
        var resultStride = (blocksW + 2) * bw;
        var resultHeight = (blocksH + 2) * bh;
        var slot = subX == 0 && subY == 0 ? 0 : 1;
        var buf = buffers[slot];
        var sumLength = resultStride * resultHeight;
        if (buf == null || buf.bw != bw || buf.bh != bh || buf.sums[0].length != sumLength) {
            buf = new Buffers(bw, bh, sumLength);
            buffers[slot] = buf;
        } else {
            for (var sum : buf.sums) {
                Arrays.fill(sum, 0);
            }
        }
        var window = buf.window;
        var psd = defaultPsd(bw * bh, noiseLevel);
        var sums = buf.sums;
        var bands = Math.max(1, Math.min(threads, 4));
        var scratch = buf.bands;
        for (var band = 0; band < bands; band++) {
            if (scratch[band] == null) {
                scratch[band] = new Band(bw, bh, bitDepth);
            }
        }
        ParallelBands.run(bands, band -> {
            var s = scratch[band];
            var finder = s.finder;
            var fft = s.fft;
            var planeD = s.planeD;
            var blockD = s.blockD;
            var re = s.re;
            var im = s.im;
            var plane = s.plane;
            for (var k = band; k < 4; k += bands) {
                var offY = (k >> 1) * (bh / 2);
                var offX = (k & 1) * (bw / 2);
                var result = sums[k];
                for (var by = -1; by < blocksH; by++) {
                    for (var bx = -1; bx < blocksW; bx++) {
                        finder.extractBlock(data, w, h, stride, bx * bw + offX, by * bh + offY, planeD, blockD);
                        for (var j = 0; j < bw * bh; j++) {
                            re[j] = (float) blockD[j] * window[j];
                            im[j] = 0;
                            plane[j] = (float) planeD[j] * window[j];
                        }
                        fft.forward(re, im);
                        filter(re, im, psd);
                        fft.inverse(re, im);
                        for (var y = 0; y < bh; y++) {
                            var row = (y + (by + 1) * bh + offY) * resultStride + (bx + 1) * bw + offX;
                            for (var x = 0; x < bw; x++) {
                                var i = y * bw + x;
                                result[row + x] += (re[i] + plane[i]) * window[i];
                            }
                        }
                    }
                }
            }
        });
        var total = sums[0];
        for (var k = 1; k < 4; k++) {
            var add = sums[k];
            for (var i = 0; i < total.length; i++) {
                total[i] += add[i];
            }
        }
        ditherAndQuantize(total, resultStride, out, w, h, stride, bw, bh);
    }

    /**
     * Scales each coefficient by the Wiener gain of a flat noise power
     * ({@code aom_noise_tx_filter}).
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param psd the noise power of every coefficient
     */
    private static void filter(float[] re, float[] im, float psd) {
        for (var i = 0; i < re.length; i++) {
            var c0 = Math.max(Math.abs(re[i]), 1e-8f);
            var c1 = Math.max(Math.abs(im[i]), 1e-8f);
            var p = c0 * c0 + c1 * c1;
            float gain;
            if (p > BETA * psd && p > 1e-6) {
                gain = (p - psd) / Math.max(p, EPS);
            } else {
                gain = (BETA - 1.0f) / BETA;
            }
            re[i] *= gain;
            im[i] *= gain;
        }
    }

    /**
     * Rounds the filtered samples to integers with Floyd-Steinberg error diffusion
     * ({@code dither_and_quantize}).
     *
     * @param result the filtered samples, normalized to [0, 1], offset by one block
     * @param resultStride the stride of the filtered samples
     * @param out receives the samples
     * @param w the plane width
     * @param h the plane height
     * @param stride the output stride
     * @param bw the block width
     * @param bh the block height
     */
    private void ditherAndQuantize(float[] result, int resultStride, short[] out, int w, int h, int stride, int bw,
                                   int bh) {
        var normalization = (float) ((1 << bitDepth) - 1);
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var idx = (y + bh) * resultStride + x + bw;
                var value = (int) Math.min(Math.max(result[idx] * normalization + 0.5f, 0), normalization);
                var err = -(value / normalization - result[idx]);
                out[y * stride + x] = (short) value;
                if (x + 1 < w) {
                    result[idx + 1] += err * 7.0f / 16.0f;
                }
                if (y + 1 < h) {
                    if (x > 0) {
                        result[idx + resultStride - 1] += err * 3.0f / 16.0f;
                    }
                    result[idx + resultStride] += err * 5.0f / 16.0f;
                    if (x + 1 < w) {
                        result[idx + resultStride + 1] += err * 1.0f / 16.0f;
                    }
                }
            }
        }
    }

    /**
     * A two-dimensional complex Fourier transform of a power-of-two block, radix 2.
     */
    static final class Fft {
        /**
         * The block width.
         */
        private final int w;

        /**
         * The block height.
         */
        private final int h;

        /**
         * The cosines of the width's twiddle factors.
         */
        private final float[] cosW;

        /**
         * The sines of the width's twiddle factors.
         */
        private final float[] sinW;

        /**
         * The cosines of the height's twiddle factors.
         */
        private final float[] cosH;

        /**
         * The sines of the height's twiddle factors.
         */
        private final float[] sinH;

        /**
         * The real parts of one column.
         */
        private final float[] colRe;

        /**
         * The imaginary parts of one column.
         */
        private final float[] colIm;

        /**
         * Creates the transform of a block size.
         *
         * @param w the width, a power of two
         * @param h the height, a power of two
         */
        Fft(int w, int h) {
            this.w = w;
            this.h = h;
            this.cosW = new float[w / 2];
            this.sinW = new float[w / 2];
            this.cosH = new float[h / 2];
            this.sinH = new float[h / 2];
            for (var i = 0; i < w / 2; i++) {
                cosW[i] = (float) StrictMath.cos(-2 * Math.PI * i / w);
                sinW[i] = (float) StrictMath.sin(-2 * Math.PI * i / w);
            }
            for (var i = 0; i < h / 2; i++) {
                cosH[i] = (float) StrictMath.cos(-2 * Math.PI * i / h);
                sinH[i] = (float) StrictMath.sin(-2 * Math.PI * i / h);
            }
            this.colRe = new float[h];
            this.colIm = new float[h];
        }

        /**
         * Transforms a block in place without scaling.
         *
         * @param re the real parts, row-major
         * @param im the imaginary parts
         */
        void forward(float[] re, float[] im) {
            transform(re, im, 1);
        }

        /**
         * Transforms a block back in place, dividing by the number of samples.
         *
         * @param re the real parts, row-major
         * @param im the imaginary parts
         */
        void inverse(float[] re, float[] im) {
            transform(re, im, -1);
            var n = (float) (w * h);
            for (var i = 0; i < re.length; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }

        /**
         * Transforms the rows, then the columns.
         *
         * @param re the real parts
         * @param im the imaginary parts
         * @param sign 1 for the forward transform, -1 for the inverse
         */
        private void transform(float[] re, float[] im, int sign) {
            for (var y = 0; y < h; y++) {
                fft1d(re, im, y * w, 1, w, cosW, sinW, sign);
            }
            for (var x = 0; x < w; x++) {
                for (var y = 0; y < h; y++) {
                    colRe[y] = re[y * w + x];
                    colIm[y] = im[y * w + x];
                }
                fft1d(colRe, colIm, 0, 1, h, cosH, sinH, sign);
                for (var y = 0; y < h; y++) {
                    re[y * w + x] = colRe[y];
                    im[y * w + x] = colIm[y];
                }
            }
        }

        /**
         * Transforms one sequence in place: bit-reversal permutation, then butterflies.
         *
         * @param re the real parts
         * @param im the imaginary parts
         * @param off the index of the first element
         * @param step the distance between elements
         * @param n the length, a power of two
         * @param cos the cosines of the twiddle factors
         * @param sin the sines of the twiddle factors of the forward transform
         * @param sign 1 for the forward transform, -1 for the inverse
         */
        private static void fft1d(float[] re, float[] im, int off, int step, int n, float[] cos, float[] sin,
                                  int sign) {
            var bits = Integer.numberOfTrailingZeros(n);
            for (var i = 0; i < n; i++) {
                var j = Integer.reverse(i) >>> (32 - bits);
                if (j > i) {
                    var a = off + i * step;
                    var b = off + j * step;
                    var tr = re[a];
                    re[a] = re[b];
                    re[b] = tr;
                    var ti = im[a];
                    im[a] = im[b];
                    im[b] = ti;
                }
            }
            for (var size = 2; size <= n; size <<= 1) {
                var half = size >> 1;
                var tableStep = n / size;
                for (var start = 0; start < n; start += size) {
                    for (var k = 0; k < half; k++) {
                        var wr = cos[k * tableStep];
                        var wi = sign * sin[k * tableStep];
                        var a = off + (start + k) * step;
                        var b = off + (start + k + half) * step;
                        var xr = re[b] * wr - im[b] * wi;
                        var xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }
}
