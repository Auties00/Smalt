package com.github.auties00.smalt.internal.sei;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Renders the frequency filtering film grain of a film grain characteristics SEI message onto a
 * decoded picture, bit-exactly as SMPTE RDD 5-2006 specifies for 8-bit 4:2:0 frames.
 *
 * <p>Each component is split into 8x8 blocks. A block whose average (the sum of its samples shifted
 * right by 6, plus the bit depth above 8) falls in an intensity interval of the message receives an
 * 8x8 block of a 64x64 grain pattern, chosen by the interval's cut frequencies, at an offset drawn
 * from the pseudo-random number generator, which steps once per 16x16 area in raster order from a
 * seed of the picture order offset and the component, and scaled by the interval's intensity with a
 * random sign. The vertical edges between horizontally adjacent blocks are smoothed with a 3-tap
 * filter before the grain is added to the samples, which are clipped to the sample range.
 *
 * <p>Beyond RDD 5, which covers 8-bit 4:2:0 progressive frames: a block at the right or bottom edge
 * that the picture cuts short averages its present samples as if the missing ones were 0; deeper
 * samples average in the 8-bit domain and receive the grain at their own depth, the intensity
 * being in units of the component's bit depth; 4:2:2 chroma scales the intensity by 181/256 and
 * doubles only the horizontal cut frequency, and 4:4:4 chroma keeps the luma model's scale. Cut
 * frequencies are clamped to the range of the pattern database, 2 to 14; the first matching
 * interval wins when intervals overlap; the low cut frequencies and the color correlation (model
 * values 3 to 5) are ignored.
 *
 * <p>Instances are not thread-safe; {@link #apply} may run its stripes on the shared worker
 * threads.
 *
 * @implNote This implementation builds each of the 169 grain patterns the first time a message
 * uses it and keeps it for the life of the JVM. The extension rules for 4:2:2 chroma follow the
 * film grain synthesizer of the VVC reference software.
 */
public final class FilmGrainSynthesizer {
    /**
     * The attenuation of the horizontal 8x8 edges of a pattern, per vertical cut frequency index
     * (RDD 5 2.1.3), in units of 1/128.
     */
    private static final int[] DEBLOCK = {64, 71, 77, 84, 90, 96, 103, 109, 116, 122, 128, 128, 128};

    /**
     * The seed offsets of the three components (RDD 5 1.5.2.1).
     */
    private static final int[] COLOR_OFFSET = {0, 85, 170};

    /**
     * The 64x64 transformation matrix of RDD 5 2.2.3, {@code [row * 64 + column]}.
     */
    static final int[] R64 = transformMatrix();

    /**
     * The grain patterns built so far, by {@code h + 13 * v}, each {@code [l * 64 + k]} for
     * horizontal position {@code k} and vertical position {@code l}.
     */
    private static final AtomicReferenceArray<byte[]> PATTERNS = new AtomicReferenceArray<>(169);

    /**
     * The model of the component rendered last, per component, reused while its message, chroma
     * format and bit depth stay the same.
     */
    private final Model[] models = {new Model(), new Model(), new Model()};

    /**
     * The generator state of each 16x16 area of the component being rendered, in raster order.
     */
    private int[] areaSeeds = new int[0];

    /**
     * The grain of a stripe of 16 rows, one buffer per band.
     */
    private int[][] stripes = new int[0][];

    /**
     * Constructs a synthesizer.
     */
    public FilmGrainSynthesizer() {
    }

    /**
     * Returns the next state of the pseudo-random number generator of RDD 5 1.5.2: a left shift of
     * the 32-bit register that brings in the complement of bit 30 exclusive-or bit 2.
     *
     * @param state the current state
     * @return the next state
     */
    static int next(int state) {
        return (state << 1) | (~((state >>> 30) ^ (state >>> 2)) & 1);
    }

    /**
     * Computes the transformation matrix of RDD 5 2.2.3, whose rows are the basis functions of the
     * 64-point DCT scaled by {@code 32 * sqrt(2)} and rounded, the first row 32.
     *
     * @return the matrix, {@code [row * 64 + column]}
     */
    private static int[] transformMatrix() {
        var matrix = new int[64 * 64];
        for (var k = 0; k < 64; k++) {
            for (var n = 0; n < 64; n++) {
                matrix[k * 64 + n] = k == 0 ? 32
                        : (int) StrictMath.round(32 * StrictMath.sqrt(2) * StrictMath.cos(StrictMath.PI * (2 * n + 1) * k / 128));
            }
        }
        return matrix;
    }

    /**
     * Returns the grain pattern of a pair of cut frequencies (RDD 5 2.1), building it on first use.
     *
     * @param h the horizontal cut frequency minus 2, from 0 to 12
     * @param v the vertical cut frequency minus 2, from 0 to 12
     * @return the pattern, {@code [l * 64 + k]}, from -127 to 127
     */
    static byte[] pattern(int h, int v) {
        var index = h + 13 * v;
        var pattern = PATTERNS.get(index);
        if (pattern == null) {
            pattern = buildPattern(h, v);
            PATTERNS.compareAndSet(index, null, pattern);
            pattern = PATTERNS.get(index);
        }
        return pattern;
    }

    /**
     * Builds a grain pattern: Gaussian coefficients below the cut frequencies, an inverse integer
     * transform, clipping and the attenuation of the horizontal 8x8 edges (RDD 5 2.1.1 to 2.1.3).
     *
     * @param h the horizontal cut frequency minus 2, from 0 to 12
     * @param v the vertical cut frequency minus 2, from 0 to 12
     * @return the pattern, {@code [l * 64 + k]}
     */
    private static byte[] buildPattern(int h, int v) {
        var horizontalCut = ((h + 3) << 2) - 1;
        var verticalCut = ((v + 3) << 2) - 1;
        // coefficients[k * 64 + l]: k is the horizontal frequency, l the vertical one
        var coefficients = new int[64 * 64];
        var state = FilmGrainTables.SEEDS[h + v * 13];
        for (var l = 0; l <= verticalCut; l++) {
            for (var k = 0; k <= horizontalCut; k += 4) {
                for (var j = 0; j < 4; j++) {
                    coefficients[(k + j) * 64 + l] = FilmGrainTables.GAUSSIAN[Integer.remainderUnsigned(state + j, 2048)];
                }
                state = next(state);
            }
        }
        coefficients[0] = 0;
        // first pass: t = (R^T * B + 128) >> 8, only the columns below the vertical cut are non-zero
        var first = new int[64 * 64];
        for (var i = 0; i < 64; i++) {
            for (var j = 0; j <= verticalCut; j++) {
                var sum = 0;
                for (var k = 0; k <= horizontalCut; k++) {
                    sum += R64[k * 64 + i] * coefficients[k * 64 + j];
                }
                first[i * 64 + j] = (sum + 128) >> 8;
            }
        }
        var pattern = new byte[64 * 64];
        var factor = DEBLOCK[v];
        for (var i = 0; i < 64; i++) {
            for (var j = 0; j < 64; j++) {
                var sum = 0;
                for (var k = 0; k <= verticalCut; k++) {
                    sum += first[i * 64 + k] * R64[k * 64 + j];
                }
                var value = Math.clamp((sum + 128) >> 8, -127, 127);
                if ((j & 7) == 0 || (j & 7) == 7) {
                    value = (value * factor) >> 7;
                }
                pattern[j * 64 + i] = (byte) value;
            }
        }
        return pattern;
    }

    /**
     * Adds the grain of one component of an 8-bit picture to its samples.
     *
     * <p>{@code dst} receives every sample of the component: the source sample plus its grain,
     * clipped to 0 to 255, or the source sample alone where no interval matches or the message does
     * not model the component. The planes may not overlap.
     *
     * @param params the message, which {@link FilmGrainParameters#renderable} accepts
     * @param picOffset the picture order offset that seeds the generator: for H.264 the picture
     * order count plus the offset of RDD 5 1.5.2.1.1, for H.265 the picture order count
     * @param component the component, 0 for luma, 1 for Cb and 2 for Cr
     * @param chromaFormatIdc the {@code chroma_format_idc} of the picture, 0 to 3
     * @param src the decoded samples
     * @param srcOffset the index of the top-left sample in {@code src}
     * @param srcStride the row stride of {@code src}
     * @param dst the output samples
     * @param dstOffset the index of the top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param width the width of the component, as decoded before any cropping
     * @param height the height of the component, as decoded before any cropping
     * @param threads the most threads the call may use, at least 1
     */
    public void apply(FilmGrainParameters params, int picOffset, int component, int chromaFormatIdc, byte[] src,
                      int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int width, int height,
                      int threads) {
        render(params, picOffset, component, chromaFormatIdc, 8, src, null, srcOffset, srcStride, dst, null,
                dstOffset, dstStride, width, height, threads);
    }

    /**
     * Adds the grain of one component of a picture deeper than 8 bits to its samples.
     *
     * <p>{@code dst} receives every sample of the component: the source sample plus its grain,
     * clipped to 0 to {@code (1 << bitDepth) - 1}, or the source sample alone where no interval
     * matches or the message does not model the component. The planes may not overlap.
     *
     * @param params the message, which {@link FilmGrainParameters#renderable} accepts
     * @param picOffset the picture order offset that seeds the generator
     * @param component the component, 0 for luma, 1 for Cb and 2 for Cr
     * @param chromaFormatIdc the {@code chroma_format_idc} of the picture, 0 to 3
     * @param bitDepth the bit depth of the component, 8 to 16
     * @param src the decoded samples
     * @param srcOffset the index of the top-left sample in {@code src}
     * @param srcStride the row stride of {@code src}
     * @param dst the output samples
     * @param dstOffset the index of the top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param width the width of the component, as decoded before any cropping
     * @param height the height of the component, as decoded before any cropping
     * @param threads the most threads the call may use, at least 1
     */
    public void apply(FilmGrainParameters params, int picOffset, int component, int chromaFormatIdc, int bitDepth,
                      short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                      int width, int height, int threads) {
        render(params, picOffset, component, chromaFormatIdc, bitDepth, null, src, srcOffset, srcStride, null, dst,
                dstOffset, dstStride, width, height, threads);
    }

    /**
     * Renders one component of either sample size.
     */
    private void render(FilmGrainParameters params, int picOffset, int component, int chromaFormatIdc, int bitDepth,
                        byte[] src8, short[] src16, int srcOffset, int srcStride, byte[] dst8, short[] dst16,
                        int dstOffset, int dstStride, int width, int height, int threads) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!params.models(component)) {
            copy(src8, src16, srcOffset, srcStride, dst8, dst16, dstOffset, dstStride, width, height);
            return;
        }
        var model = models[component];
        model.prepare(params, component, chromaFormatIdc, bitDepth);
        var areasX = (width + 15) >> 4;
        var areasY = (height + 15) >> 4;
        var areas = areasX * areasY;
        if (areaSeeds.length < areas) {
            areaSeeds = new int[areas];
        }
        var state = FilmGrainTables.SEEDS[Math.floorMod(picOffset + COLOR_OFFSET[component], 256)];
        for (var i = 0; i < areas; i++) {
            areaSeeds[i] = state;
            state = next(state);
        }
        var bands = threads <= 1 ? 1 : Math.min(threads, ParallelBands.bands(areasY, (long) width * height));
        var stripeSize = 16 * width;
        if (stripes.length < bands) {
            var grown = new int[bands][];
            System.arraycopy(stripes, 0, grown, 0, stripes.length);
            stripes = grown;
        }
        for (var band = 0; band < bands; band++) {
            if (stripes[band] == null || stripes[band].length < stripeSize) {
                stripes[band] = new int[stripeSize];
            }
        }
        var seeds = areaSeeds;
        var buffers = stripes;
        var shift = bitDepth - 8;
        var max = (1 << bitDepth) - 1;
        ParallelBands.run(bands, band -> {
            var grain = buffers[band];
            var last = ParallelBands.start(band + 1, bands, areasY);
            for (var areaY = ParallelBands.start(band, bands, areasY); areaY < last; areaY++) {
                var top = areaY << 4;
                var rows = Math.min(16, height - top);
                Arrays.fill(grain, 0, rows * width, 0);
                for (var areaX = 0; areaX < areasX; areaX++) {
                    var seed = seeds[areaY * areasX + areaX];
                    for (var block = 0; block < 4; block++) {
                        var left = (areaX << 4) + ((block & 1) << 3);
                        var blockTop = top + ((block >> 1) << 3);
                        if (left >= width || blockTop >= height) {
                            continue;
                        }
                        var blockWidth = Math.min(8, width - left);
                        var blockHeight = Math.min(8, height - blockTop);
                        var sum = src8 != null
                                ? sum(src8, srcOffset + blockTop * srcStride + left, srcStride, blockWidth, blockHeight)
                                : sum(src16, srcOffset + blockTop * srcStride + left, srcStride, blockWidth, blockHeight);
                        var interval = model.intervalOf[Math.min(255, sum >> (6 + shift))];
                        if (interval < 0) {
                            continue;
                        }
                        fillBlock(grain, width, left, blockTop - top, blockWidth, blockHeight, seed, model, interval,
                                params.log2ScaleFactor + 6);
                    }
                }
                deblock(grain, width, rows);
                var srcRow = srcOffset + top * srcStride;
                var dstRow = dstOffset + top * dstStride;
                for (var y = 0; y < rows; y++, srcRow += srcStride, dstRow += dstStride) {
                    if (src8 != null) {
                        blend(src8, srcRow, dst8, dstRow, grain, y * width, width);
                    } else {
                        blend(src16, srcRow, dst16, dstRow, grain, y * width, width, max);
                    }
                }
            }
        });
    }

    /**
     * Writes the grain of one 8x8 block into a stripe (RDD 5 1.5.3.3).
     *
     * @param grain the stripe, {@code [row * width + column]}
     * @param width the stripe width
     * @param left the column of the block
     * @param top the row of the block in the stripe, 0 or 8
     * @param blockWidth the columns of the block inside the picture, 1 to 8
     * @param blockHeight the rows of the block inside the picture, 1 to 8
     * @param seed the generator state of the block's 16x16 area
     * @param model the component model
     * @param interval the intensity interval of the block
     * @param shift the right shift of the scaled pattern values, {@code log2_scale_factor + 6}
     */
    private static void fillBlock(int[] grain, int width, int left, int top, int blockWidth, int blockHeight,
                                  int seed, Model model, int interval, int shift) {
        var kOffset = ((seed >>> 16) % 52 & 0xFFFC) + (left & 8);
        var lOffset = ((seed & 0xFFFF) % 56 & 0xFFF8) + (top & 8);
        var scale = (seed & 1) == 0 ? model.scale[interval] : -model.scale[interval];
        var pattern = model.patterns[interval];
        for (var l = 0; l < blockHeight; l++) {
            var from = (lOffset + l) * 64 + kOffset;
            var to = (top + l) * width + left;
            for (var k = 0; k < blockWidth; k++) {
                grain[to + k] = (scale * pattern[from + k]) >> shift;
            }
        }
    }

    /**
     * Smooths the vertical edges between horizontally adjacent 8x8 blocks of a stripe with a
     * 3-tap filter (RDD 5 1.5.3.4).
     *
     * @param grain the stripe, {@code [row * width + column]}
     * @param width the stripe width
     * @param rows the rows of the stripe inside the picture
     */
    private static void deblock(int[] grain, int width, int rows) {
        for (var y = 0; y < rows; y++) {
            var row = y * width;
            for (var edge = 8; edge + 1 < width; edge += 8) {
                var at = row + edge;
                var left1 = grain[at - 2];
                var left0 = grain[at - 1];
                var right0 = grain[at];
                var right1 = grain[at + 1];
                grain[at] = (left0 + (right0 << 1) + right1) >> 2;
                grain[at - 1] = (left1 + (left0 << 1) + right0) >> 2;
            }
        }
    }

    /**
     * Sums the samples of an 8-bit block.
     *
     * @param plane the samples
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param width the block width
     * @param height the block height
     * @return the sum
     */
    private static int sum(byte[] plane, int offset, int stride, int width, int height) {
        var sum = 0;
        for (var y = 0; y < height; y++, offset += stride) {
            for (var x = 0; x < width; x++) {
                sum += plane[offset + x] & 0xFF;
            }
        }
        return sum;
    }

    /**
     * Sums the samples of a 16-bit block.
     *
     * @param plane the samples
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param width the block width
     * @param height the block height
     * @return the sum
     */
    private static int sum(short[] plane, int offset, int stride, int width, int height) {
        var sum = 0;
        for (var y = 0; y < height; y++, offset += stride) {
            for (var x = 0; x < width; x++) {
                sum += plane[offset + x] & 0xFFFF;
            }
        }
        return sum;
    }

    /**
     * Adds a row of grain to a row of 8-bit samples (RDD 5 1.5.3.5).
     *
     * @param src the decoded samples
     * @param srcRow the index of the row's first sample in {@code src}
     * @param dst the output samples
     * @param dstRow the index of the row's first sample in {@code dst}
     * @param grain the stripe
     * @param grainRow the index of the row's first grain value
     * @param width the row width
     */
    private static void blend(byte[] src, int srcRow, byte[] dst, int dstRow, int[] grain, int grainRow, int width) {
        for (var x = 0; x < width; x++) {
            dst[dstRow + x] = (byte) Math.clamp((src[srcRow + x] & 0xFF) + grain[grainRow + x], 0, 255);
        }
    }

    /**
     * Adds a row of grain to a row of 16-bit samples.
     *
     * @param src the decoded samples
     * @param srcRow the index of the row's first sample in {@code src}
     * @param dst the output samples
     * @param dstRow the index of the row's first sample in {@code dst}
     * @param grain the stripe
     * @param grainRow the index of the row's first grain value
     * @param width the row width
     * @param max the largest sample value
     */
    private static void blend(short[] src, int srcRow, short[] dst, int dstRow, int[] grain, int grainRow, int width,
                              int max) {
        for (var x = 0; x < width; x++) {
            dst[dstRow + x] = (short) Math.clamp((src[srcRow + x] & 0xFFFF) + grain[grainRow + x], 0, max);
        }
    }

    /**
     * Copies a component the message does not model.
     */
    private static void copy(byte[] src8, short[] src16, int srcOffset, int srcStride, byte[] dst8, short[] dst16,
                             int dstOffset, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            if (src8 != null) {
                System.arraycopy(src8, srcOffset + y * srcStride, dst8, dstOffset + y * dstStride, width);
            } else {
                System.arraycopy(src16, srcOffset + y * srcStride, dst16, dstOffset + y * dstStride, width);
            }
        }
    }

    /**
     * The intensity intervals of one component of a message, scaled for a chroma format and bit
     * depth.
     */
    private static final class Model {
        /**
         * The message the model was prepared for.
         */
        private FilmGrainParameters source;

        /**
         * The component, chroma format and bit depth the model was prepared for, packed.
         */
        private int key = -1;

        /**
         * The interval of each 8-bit block average, or -1 for none.
         */
        final int[] intervalOf = new int[256];

        /**
         * The intensity of each interval, the standard deviation term of the model.
         */
        int[] scale = new int[0];

        /**
         * The grain pattern of each interval.
         */
        byte[][] patterns = new byte[0][];

        /**
         * Prepares the model of a component unless it already holds it.
         *
         * @param params the message
         * @param component the component index, 0 for luma, 1 for Cb and 2 for Cr
         * @param chromaFormatIdc the {@code chroma_format_idc} of the picture
         * @param bitDepth the bit depth of the component
         */
        void prepare(FilmGrainParameters params, int component, int chromaFormatIdc, int bitDepth) {
            var newKey = component | chromaFormatIdc << 2 | bitDepth << 4;
            if (source == params && key == newKey) {
                return;
            }
            var bounds = params.bounds[component];
            var values = params.values[component];
            var intervals = bounds.length / 2;
            if (scale.length < intervals) {
                scale = new int[intervals];
                patterns = new byte[intervals][];
            }
            Arrays.fill(intervalOf, -1);
            var max = (1 << bitDepth) - 1;
            for (var i = 0; i < intervals; i++) {
                var intensity = Math.clamp(values[6 * i], 0, max);
                var horizontal = values[6 * i + 1];
                var vertical = values[6 * i + 2];
                if (component > 0 && chromaFormatIdc == 1) {
                    intensity >>= 1;
                    horizontal <<= 1;
                    vertical <<= 1;
                } else if (component > 0 && chromaFormatIdc == 2) {
                    intensity = (intensity * 181) >> 8;
                    horizontal <<= 1;
                }
                scale[i] = intensity;
                patterns[i] = pattern(Math.clamp(horizontal, 2, 14) - 2, Math.clamp(vertical, 2, 14) - 2);
                // an earlier interval keeps the levels it shares with a later one
                for (var level = bounds[2 * i]; level <= bounds[2 * i + 1]; level++) {
                    if (intervalOf[level] < 0) {
                        intervalOf[level] = i;
                    }
                }
            }
            source = params;
            key = newKey;
        }
    }
}
