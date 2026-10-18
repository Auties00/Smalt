package com.github.auties00.smalt.video.av1.internal;

/**
 * Implements the film grain kernels with plain scalar Java; the reference for any vectorized
 * implementation.
 *
 * @implNote This implementation ports dav1d's {@code src/filmgrain_tmpl.c} ({@code generate_grain_y_c},
 *           {@code generate_grain_uv_c}, {@code fgy_32x32xn_c} and {@code fguv_32x32xn_c}) operation
 *           by operation. One implementation serves every bit depth: the grain range, rounding
 *           shifts and clipping bounds are derived from {@code bitdepthMax} exactly as dav1d's 8bpc
 *           and 16bpc templates derive them, so the output is bit-exact with dav1d at 8 and 10 bits.
 */
public final class ScalarFilmGrainDsp extends FilmGrainDsp {
    /**
     * The padding the auto-regressive filter leaves around the template.
     */
    private static final int AR_PAD = 3;

    /**
     * The luma overlap blending weights, as {@code [offset 2][old, new]}.
     */
    private static final int[] LUMA_WEIGHTS = {27, 17, 17, 27};

    /**
     * The chroma overlap blending weights, as {@code [subsampled 2][offset 2][old, new]}; a
     * subsampled direction overlaps a single sample.
     */
    private static final int[] CHROMA_WEIGHTS = {27, 17, 17, 27, 23, 22, 0, 0};

    /**
     * Creates the scalar implementation.
     */
    public ScalarFilmGrainDsp() {
    }

    /**
     * Advances the 16-bit linear feedback shift register of the grain generator by one step
     * ({@code get_random_number}, AV1 specification section 7.18.3.2).
     *
     * @param r the current register value
     * @return the next register value
     */
    private static int advance(int r) {
        var bit = (r ^ (r >> 1) ^ (r >> 3) ^ (r >> 12)) & 1;
        return (r >> 1) | (bit << 15);
    }

    /**
     * Returns the top bits of a register value.
     *
     * @param state the register value
     * @param bits  the number of bits
     * @return the value of the {@code bits} most significant of the 16 register bits
     */
    private static int top(int state, int bits) {
        return (state >> (16 - bits)) & ((1 << bits) - 1);
    }

    /**
     * Rounds a value shifted right with rounding to nearest, ties up.
     *
     * @param x     the value
     * @param shift the number of bits to drop, at least 0
     * @return {@code (x + 2^(shift - 1)) >> shift}, or {@code x} for a zero shift
     */
    private static int round2(int x, int shift) {
        return (x + ((1 << shift) >> 1)) >> shift;
    }

    /**
     * Returns the bit depth of a largest sample value.
     *
     * @param bitdepthMax the largest sample value
     * @return the number of bits
     */
    private static int bitdepth(int bitdepthMax) {
        return 32 - Integer.numberOfLeadingZeros(bitdepthMax);
    }

    /**
     * Reads a grain value from the template at a block's pseudo-random position
     * ({@code sample_lut}).
     *
     * @param lut     the template
     * @param randval the 8-bit random offset of the block
     * @param subx    the horizontal subsampling shift
     * @param suby    the vertical subsampling shift
     * @param bx      1 to read the block on the left instead of the current one
     * @param by      1 to read the block above instead of the current one
     * @param x       the column within the block
     * @param y       the row within the block
     * @return the grain value
     */
    private static int sampleLut(short[] lut, int randval, int subx, int suby, int bx, int by, int x, int y) {
        var offx = 3 + (2 >> subx) * (3 + (randval >> 4));
        var offy = 3 + (2 >> suby) * (3 + (randval & 0xF));
        return lut[(offy + y + (FG_BLOCK_SIZE >> suby) * by) * GRAIN_WIDTH + offx + x + (FG_BLOCK_SIZE >> subx) * bx];
    }

    @Override
    public void generateGrainY(short[] buf, FilmGrainData data, int bitdepthMax) {
        var bitdepthMin8 = bitdepth(bitdepthMax) - 8;
        var seed = data.seed;
        var shift = 4 - bitdepthMin8 + data.grainScaleShift;
        var grainCtr = 128 << bitdepthMin8;
        var grainMin = -grainCtr;
        var grainMax = grainCtr - 1;
        var gaussian = FilmGrainTables.GAUSSIAN_SEQUENCE;
        for (var y = 0; y < GRAIN_HEIGHT; y++) {
            var row = y * GRAIN_WIDTH;
            for (var x = 0; x < GRAIN_WIDTH; x++) {
                seed = advance(seed);
                buf[row + x] = (short) round2(gaussian[top(seed, 11)], shift);
            }
        }
        var lag = data.arCoeffLag;
        var coeffs = data.arCoeffsY;
        var arShift = data.arCoeffShift;
        for (var y = AR_PAD; y < GRAIN_HEIGHT; y++) {
            for (var x = AR_PAD; x < GRAIN_WIDTH - AR_PAD; x++) {
                var coeff = 0;
                var sum = 0;
                outer:
                for (var dy = -lag; dy <= 0; dy++) {
                    for (var dx = -lag; dx <= lag; dx++) {
                        if (dx == 0 && dy == 0) {
                            break outer;
                        }
                        sum += coeffs[coeff++] * buf[(y + dy) * GRAIN_WIDTH + x + dx];
                    }
                }
                var index = y * GRAIN_WIDTH + x;
                var grain = buf[index] + round2(sum, arShift);
                buf[index] = (short) Math.clamp(grain, grainMin, grainMax);
            }
        }
    }

    @Override
    public void generateGrainUv(int layout, short[] buf, short[] bufY, FilmGrainData data, int uv,
                                int bitdepthMax) {
        var subx = layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
        var suby = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var bitdepthMin8 = bitdepth(bitdepthMax) - 8;
        var seed = data.seed ^ (uv != 0 ? 0x49d8 : 0xb524);
        var shift = 4 - bitdepthMin8 + data.grainScaleShift;
        var grainCtr = 128 << bitdepthMin8;
        var grainMin = -grainCtr;
        var grainMax = grainCtr - 1;
        var chromaW = subx != 0 ? SUB_GRAIN_WIDTH : GRAIN_WIDTH;
        var chromaH = suby != 0 ? SUB_GRAIN_HEIGHT : GRAIN_HEIGHT;
        var gaussian = FilmGrainTables.GAUSSIAN_SEQUENCE;
        for (var y = 0; y < chromaH; y++) {
            var row = y * GRAIN_WIDTH;
            for (var x = 0; x < chromaW; x++) {
                seed = advance(seed);
                buf[row + x] = (short) round2(gaussian[top(seed, 11)], shift);
            }
        }
        var lag = data.arCoeffLag;
        var coeffs = data.arCoeffsUv[uv];
        var arShift = data.arCoeffShift;
        var hasLuma = data.numYPoints != 0;
        for (var y = AR_PAD; y < chromaH; y++) {
            for (var x = AR_PAD; x < chromaW - AR_PAD; x++) {
                var coeff = 0;
                var sum = 0;
                outer:
                for (var dy = -lag; dy <= 0; dy++) {
                    for (var dx = -lag; dx <= lag; dx++) {
                        if (dx == 0 && dy == 0) {
                            if (hasLuma) {
                                sum += lumaGrain(bufY, x, y, subx, suby) * coeffs[coeff];
                            }
                            break outer;
                        }
                        sum += coeffs[coeff++] * buf[(y + dy) * GRAIN_WIDTH + x + dx];
                    }
                }
                var index = y * GRAIN_WIDTH + x;
                var grain = buf[index] + round2(sum, arShift);
                buf[index] = (short) Math.clamp(grain, grainMin, grainMax);
            }
        }
    }

    /**
     * Returns the average of the luma template values co-located with a chroma template entry.
     *
     * @param bufY the luma template
     * @param x    the chroma column
     * @param y    the chroma row
     * @param subx the horizontal subsampling shift
     * @param suby the vertical subsampling shift
     * @return the rounded average
     */
    private static int lumaGrain(short[] bufY, int x, int y, int subx, int suby) {
        var lumaX = ((x - AR_PAD) << subx) + AR_PAD;
        var lumaY = ((y - AR_PAD) << suby) + AR_PAD;
        var luma = 0;
        for (var i = 0; i <= suby; i++) {
            for (var j = 0; j <= subx; j++) {
                luma += bufY[(lumaY + i) * GRAIN_WIDTH + lumaX + j];
            }
        }
        return round2(luma, subx + suby);
    }

    @Override
    public void fgy32x32xn(short[] dst, int dstOff, short[] src, int srcOff, int stride, FilmGrainData data,
                           int pw, byte[] scaling, short[] grainLut, int bh, int rowNum, int bitdepthMax) {
        var overlap = data.overlapFlag;
        var rows = 1 + (overlap && rowNum > 0 ? 1 : 0);
        var bitdepthMin8 = bitdepth(bitdepthMax) - 8;
        var grainCtr = 128 << bitdepthMin8;
        var grainMin = -grainCtr;
        var grainMax = grainCtr - 1;
        int minValue, maxValue;
        if (data.clipToRestrictedRange) {
            minValue = 16 << bitdepthMin8;
            maxValue = 235 << bitdepthMin8;
        } else {
            minValue = 0;
            maxValue = bitdepthMax;
        }
        var seed0 = rowSeed(data.seed, rowNum);
        var seed1 = rows > 1 ? rowSeed(data.seed, rowNum - 1) : 0;
        int off00 = 0, off01 = 0, off10 = 0, off11 = 0;
        var shift = data.scalingShift;
        for (var bx = 0; bx < pw; bx += FG_BLOCK_SIZE) {
            var bw = Math.min(FG_BLOCK_SIZE, pw - bx);
            if (overlap && bx != 0) {
                off10 = off00;
                off11 = off01;
            }
            seed0 = advance(seed0);
            off00 = top(seed0, 8);
            if (rows > 1) {
                seed1 = advance(seed1);
                off01 = top(seed1, 8);
            }
            var ystart = overlap && rowNum != 0 ? Math.min(2, bh) : 0;
            var xstart = overlap && bx != 0 ? Math.min(2, bw) : 0;
            var dstBlock = dstOff + bx;
            var srcBlock = srcOff + bx;
            for (var y = ystart; y < bh; y++) {
                var d = dstBlock + y * stride;
                var s = srcBlock + y * stride;
                for (var x = xstart; x < bw; x++) {
                    var grain = sampleLut(grainLut, off00, 0, 0, 0, 0, x, y);
                    dst[d + x] = noise(src[s + x], grain, scaling, shift, minValue, maxValue);
                }
                for (var x = 0; x < xstart; x++) {
                    var grain = sampleLut(grainLut, off00, 0, 0, 0, 0, x, y);
                    var old = sampleLut(grainLut, off10, 0, 0, 1, 0, x, y);
                    grain = blend(old, grain, LUMA_WEIGHTS, x * 2, grainMin, grainMax);
                    dst[d + x] = noise(src[s + x], grain, scaling, shift, minValue, maxValue);
                }
            }
            for (var y = 0; y < ystart; y++) {
                var d = dstBlock + y * stride;
                var s = srcBlock + y * stride;
                for (var x = xstart; x < bw; x++) {
                    var grain = sampleLut(grainLut, off00, 0, 0, 0, 0, x, y);
                    var old = sampleLut(grainLut, off01, 0, 0, 0, 1, x, y);
                    grain = blend(old, grain, LUMA_WEIGHTS, y * 2, grainMin, grainMax);
                    dst[d + x] = noise(src[s + x], grain, scaling, shift, minValue, maxValue);
                }
                for (var x = 0; x < xstart; x++) {
                    var topGrain = sampleLut(grainLut, off01, 0, 0, 0, 1, x, y);
                    var old = sampleLut(grainLut, off11, 0, 0, 1, 1, x, y);
                    topGrain = blend(old, topGrain, LUMA_WEIGHTS, x * 2, grainMin, grainMax);
                    var grain = sampleLut(grainLut, off00, 0, 0, 0, 0, x, y);
                    old = sampleLut(grainLut, off10, 0, 0, 1, 0, x, y);
                    grain = blend(old, grain, LUMA_WEIGHTS, x * 2, grainMin, grainMax);
                    grain = blend(topGrain, grain, LUMA_WEIGHTS, y * 2, grainMin, grainMax);
                    dst[d + x] = noise(src[s + x], grain, scaling, shift, minValue, maxValue);
                }
            }
        }
    }

    @Override
    public void fguv32x32xn(int layout, short[] dst, int dstOff, short[] src, int srcOff, int stride,
                            FilmGrainData data, int pw, byte[] scaling, short[] grainLut, int bh, int rowNum,
                            short[] luma, int lumaOff, int lumaStride, int uv, boolean isId, int bitdepthMax) {
        var sx = layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
        var sy = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var overlap = data.overlapFlag;
        var rows = 1 + (overlap && rowNum > 0 ? 1 : 0);
        var bitdepthMin8 = bitdepth(bitdepthMax) - 8;
        var grainCtr = 128 << bitdepthMin8;
        var grainMin = -grainCtr;
        var grainMax = grainCtr - 1;
        int minValue, maxValue;
        if (data.clipToRestrictedRange) {
            minValue = 16 << bitdepthMin8;
            maxValue = (isId ? 235 : 240) << bitdepthMin8;
        } else {
            minValue = 0;
            maxValue = bitdepthMax;
        }
        var seed0 = rowSeed(data.seed, rowNum);
        var seed1 = rows > 1 ? rowSeed(data.seed, rowNum - 1) : 0;
        int off00 = 0, off01 = 0, off10 = 0, off11 = 0;
        var blockW = FG_BLOCK_SIZE >> sx;
        var wx = sx * 4;
        var wy = sy * 4;
        for (var bx = 0; bx < pw; bx += blockW) {
            var bw = Math.min(blockW, pw - bx);
            if (overlap && bx != 0) {
                off10 = off00;
                off11 = off01;
            }
            seed0 = advance(seed0);
            off00 = top(seed0, 8);
            if (rows > 1) {
                seed1 = advance(seed1);
                off01 = top(seed1, 8);
            }
            var ystart = overlap && rowNum != 0 ? Math.min(2 >> sy, bh) : 0;
            var xstart = overlap && bx != 0 ? Math.min(2 >> sx, bw) : 0;
            for (var y = ystart; y < bh; y++) {
                for (var x = xstart; x < bw; x++) {
                    var grain = sampleLut(grainLut, off00, sx, sy, 0, 0, x, y);
                    addNoiseUv(dst, dstOff, src, srcOff, stride, data, scaling, luma, lumaOff, lumaStride, uv, sx, sy,
                            bx + x, y, grain, minValue, maxValue, bitdepthMax, bitdepthMin8);
                }
                for (var x = 0; x < xstart; x++) {
                    var grain = sampleLut(grainLut, off00, sx, sy, 0, 0, x, y);
                    var old = sampleLut(grainLut, off10, sx, sy, 1, 0, x, y);
                    grain = blend(old, grain, CHROMA_WEIGHTS, wx + x * 2, grainMin, grainMax);
                    addNoiseUv(dst, dstOff, src, srcOff, stride, data, scaling, luma, lumaOff, lumaStride, uv, sx, sy,
                            bx + x, y, grain, minValue, maxValue, bitdepthMax, bitdepthMin8);
                }
            }
            for (var y = 0; y < ystart; y++) {
                for (var x = xstart; x < bw; x++) {
                    var grain = sampleLut(grainLut, off00, sx, sy, 0, 0, x, y);
                    var old = sampleLut(grainLut, off01, sx, sy, 0, 1, x, y);
                    grain = blend(old, grain, CHROMA_WEIGHTS, wy + y * 2, grainMin, grainMax);
                    addNoiseUv(dst, dstOff, src, srcOff, stride, data, scaling, luma, lumaOff, lumaStride, uv, sx, sy,
                            bx + x, y, grain, minValue, maxValue, bitdepthMax, bitdepthMin8);
                }
                for (var x = 0; x < xstart; x++) {
                    var topGrain = sampleLut(grainLut, off01, sx, sy, 0, 1, x, y);
                    var old = sampleLut(grainLut, off11, sx, sy, 1, 1, x, y);
                    topGrain = blend(old, topGrain, CHROMA_WEIGHTS, wx + x * 2, grainMin, grainMax);
                    var grain = sampleLut(grainLut, off00, sx, sy, 0, 0, x, y);
                    old = sampleLut(grainLut, off10, sx, sy, 1, 0, x, y);
                    grain = blend(old, grain, CHROMA_WEIGHTS, wx + x * 2, grainMin, grainMax);
                    grain = blend(topGrain, grain, CHROMA_WEIGHTS, wy + y * 2, grainMin, grainMax);
                    addNoiseUv(dst, dstOff, src, srcOff, stride, data, scaling, luma, lumaOff, lumaStride, uv, sx, sy,
                            bx + x, y, grain, minValue, maxValue, bitdepthMax, bitdepthMin8);
                }
            }
        }
    }

    /**
     * Returns the initial generator state of a stripe's block offsets.
     *
     * @param seed   the frame's grain seed
     * @param rowNum the stripe index
     * @return the 16-bit state
     */
    private static int rowSeed(int seed, int rowNum) {
        var s = seed;
        s ^= ((rowNum * 37 + 178) & 0xFF) << 8;
        s ^= (rowNum * 173 + 105) & 0xFF;
        return s;
    }

    /**
     * Blends the grain of an overlapping block with the current grain.
     *
     * @param old      the grain of the overlapping block
     * @param grain    the current grain
     * @param weights  the weight table
     * @param index    the index of the weight pair in the table
     * @param grainMin the smallest grain value
     * @param grainMax the largest grain value
     * @return the blended grain, clipped to the grain range
     */
    private static int blend(int old, int grain, int[] weights, int index, int grainMin, int grainMax) {
        return Math.clamp(round2(old * weights[index] + grain * weights[index + 1], 5), grainMin, grainMax);
    }

    /**
     * Adds scaled grain to one luma sample.
     *
     * @param sample   the source sample
     * @param grain    the grain value
     * @param scaling  the scaling table
     * @param shift    the scaling shift
     * @param minValue the smallest output value
     * @param maxValue the largest output value
     * @return the output sample
     */
    private static short noise(int sample, int grain, byte[] scaling, int shift, int minValue, int maxValue) {
        var noise = round2((scaling[sample] & 0xFF) * grain, shift);
        return (short) Math.clamp(sample + noise, minValue, maxValue);
    }

    /**
     * Adds scaled grain to one chroma sample ({@code add_noise_uv}).
     *
     * @param dst          the destination plane
     * @param dstOff       the index of the stripe's first destination sample
     * @param src          the source plane
     * @param srcOff       the index of the stripe's first source sample
     * @param stride       the chroma row stride
     * @param data         the film grain parameters
     * @param scaling      the scaling table
     * @param luma         the input luma plane
     * @param lumaOff      the index of the stripe's first luma sample
     * @param lumaStride   the luma row stride
     * @param uv           the chroma plane: 0 for Cb, 1 for Cr
     * @param sx           the horizontal subsampling shift
     * @param sy           the vertical subsampling shift
     * @param cx           the chroma column within the stripe
     * @param y            the chroma row within the stripe
     * @param grain        the grain value
     * @param minValue     the smallest output value
     * @param maxValue     the largest output value
     * @param bitdepthMax  the largest sample value
     * @param bitdepthMin8 the bit depth minus 8
     */
    private static void addNoiseUv(short[] dst, int dstOff, short[] src, int srcOff, int stride, FilmGrainData data,
                                   byte[] scaling, short[] luma, int lumaOff, int lumaStride, int uv, int sx, int sy,
                                   int cx, int y, int grain, int minValue, int maxValue, int bitdepthMax,
                                   int bitdepthMin8) {
        var l = lumaOff + (y << sy) * lumaStride + (cx << sx);
        int avg = luma[l];
        if (sx != 0) {
            avg = (avg + luma[l + 1] + 1) >> 1;
        }
        var s = srcOff + y * stride + cx;
        int sample = src[s];
        var val = avg;
        if (!data.chromaScalingFromLuma) {
            var combined = avg * data.uvLumaMult[uv] + sample * data.uvMult[uv];
            val = Math.clamp((combined >> 6) + data.uvOffset[uv] * (1 << bitdepthMin8), 0, bitdepthMax);
        }
        var noise = round2((scaling[val] & 0xFF) * grain, data.scalingShift);
        dst[dstOff + y * stride + cx] = (short) Math.clamp(sample + noise, minValue, maxValue);
    }
}
