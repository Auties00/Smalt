package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Applies AV1 film grain synthesis to a decoded picture (AV1 specification section 7.18.3),
 * producing a separate output picture.
 *
 * <p>Pictures are described by three planes ({@code null} chroma planes for monochrome layouts),
 * the offset of each plane's first sample, and row strides in samples shared by the input and the
 * output: one for luma and one for both chroma planes. Grain is applied in stripes of
 * {@link FilmGrainDsp#FG_BLOCK_SIZE} luma rows: {@link #prepGrain} builds the grain templates and
 * scaling tables and copies the planes that receive no grain, after which every stripe can be
 * processed independently with {@link #applyGrainRow}, in any order or concurrently.
 * {@link #applyGrain} runs the whole process on the calling thread.
 *
 * <p>When the layout is horizontally subsampled and the width is odd, {@link #applyGrainRow} writes
 * the last luma sample of every row it reads into the padding column just past the width of the
 * INPUT luma plane, which must therefore exist (the luma stride must exceed the width); the visible
 * samples of the input are never modified.
 *
 * @implNote This implementation ports dav1d's {@code src/fg_apply_tmpl.c} ({@code generate_scaling},
 *           {@code dav1d_prep_grain}, {@code dav1d_apply_grain_row} and {@code dav1d_apply_grain}),
 *           taking the picture geometry as explicit parameters. Planes without grain are copied
 *           over their visible area only, where dav1d copies whole strides including padding.
 */
public final class FilmGrainApplier {
    /**
     * Prevents instantiation.
     */
    private FilmGrainApplier() {
        throw new AssertionError();
    }

    /**
     * Returns the number of entries of a scaling table at a bit depth.
     *
     * @param bitdepthMax the largest sample value: 255, 1023 or 4095
     * @return {@code bitdepthMax + 1}
     */
    public static int scalingSize(int bitdepthMax) {
        return bitdepthMax + 1;
    }

    /**
     * Returns the number of stripes of a picture.
     *
     * @param height the picture height in luma samples
     * @return the number of {@link FilmGrainDsp#FG_BLOCK_SIZE}-row stripes
     */
    public static int rows(int height) {
        return (height + FilmGrainDsp.FG_BLOCK_SIZE - 1) / FilmGrainDsp.FG_BLOCK_SIZE;
    }

    /**
     * Fills a scaling table by piecewise-linear interpolation of scaling points
     * ({@code generate_scaling}).
     *
     * <p>Entries below the first point take its scaling, entries above the last point take the last
     * scaling, and at bit depths above 8 the entries between the 8-bit point positions are
     * interpolated again.
     *
     * @param bitdepth the bit depth: 8, 10 or 12
     * @param points   the scaling points as {@code [point][value, scaling]}, with increasing values
     * @param num      the number of points; zero yields an all-zero table
     * @param scaling  the table, of at least {@code 1 << bitdepth} entries
     */
    static void generateScaling(int bitdepth, int[][] points, int num, byte[] scaling) {
        var shiftX = bitdepth - 8;
        var scalingSize = 1 << bitdepth;
        if (num == 0) {
            Arrays.fill(scaling, 0, scalingSize, (byte) 0);
            return;
        }
        Arrays.fill(scaling, 0, points[0][0] << shiftX, (byte) points[0][1]);
        for (var i = 0; i < num - 1; i++) {
            var bx = points[i][0];
            var by = points[i][1];
            var ex = points[i + 1][0];
            var ey = points[i + 1][1];
            var dx = ex - bx;
            var dy = ey - by;
            var delta = dy * ((0x10000 + (dx >> 1)) / dx);
            for (int x = 0, d = 0x8000; x < dx; x++) {
                scaling[(bx + x) << shiftX] = (byte) (by + (d >> 16));
                d += delta;
            }
        }
        var n = points[num - 1][0] << shiftX;
        Arrays.fill(scaling, n, scalingSize, (byte) points[num - 1][1]);
        if (bitdepth != 8) {
            var pad = 1 << shiftX;
            var rnd = pad >> 1;
            for (var i = 0; i < num - 1; i++) {
                var bx = points[i][0] << shiftX;
                var ex = points[i + 1][0] << shiftX;
                var dx = ex - bx;
                for (var x = 0; x < dx; x += pad) {
                    var base = scaling[bx + x] & 0xFF;
                    var range = (scaling[bx + x + pad] & 0xFF) - base;
                    for (int k = 1, r = rnd; k < pad; k++) {
                        r += range;
                        scaling[bx + x + k] = (byte) (base + (r >> shiftX));
                    }
                }
            }
        }
    }

    /**
     * Builds the grain templates and scaling tables a picture's grain needs.
     *
     * @param dsp         the kernels
     * @param data        the film grain parameters
     * @param layout      the pixel layout, one of the {@code SequenceHeader.LAYOUT_} values
     * @param bitdepthMax the largest sample value: 255, 1023 or 4095
     * @param scaling     three scaling tables (luma, Cb, Cr) of {@link #scalingSize} entries each
     * @param grainLut    three grain templates (luma, Cb, Cr) of
     *                    {@link FilmGrainDsp#GRAIN_LUT_SIZE} entries each
     */
    public static void prepareTables(FilmGrainDsp dsp, FilmGrainData data, int layout, int bitdepthMax,
                                     byte[][] scaling, short[][] grainLut) {
        var bitdepth = 32 - Integer.numberOfLeadingZeros(bitdepthMax);
        dsp.generateGrainY(grainLut[0], data, bitdepthMax);
        if (layout != SequenceHeader.LAYOUT_I400) {
            if (data.numUvPoints[0] != 0 || data.chromaScalingFromLuma) {
                dsp.generateGrainUv(layout, grainLut[1], grainLut[0], data, 0, bitdepthMax);
            }
            if (data.numUvPoints[1] != 0 || data.chromaScalingFromLuma) {
                dsp.generateGrainUv(layout, grainLut[2], grainLut[0], data, 1, bitdepthMax);
            }
        }
        if (data.numYPoints != 0 || data.chromaScalingFromLuma) {
            generateScaling(bitdepth, data.yPoints, data.numYPoints, scaling[0]);
        }
        if (data.numUvPoints[0] != 0) {
            generateScaling(bitdepth, data.uvPoints[0], data.numUvPoints[0], scaling[1]);
        }
        if (data.numUvPoints[1] != 0) {
            generateScaling(bitdepth, data.uvPoints[1], data.numUvPoints[1], scaling[2]);
        }
    }

    /**
     * Copies the planes that receive no grain from the input to the output: luma without luma
     * scaling points, and each chroma plane without its own scaling points unless chroma scaling
     * is derived from luma.
     *
     * @param out      the output planes
     * @param outOff   the offset of each output plane's first sample
     * @param in       the input planes
     * @param inOff    the offset of each input plane's first sample
     * @param stride   the luma row stride
     * @param uvStride the chroma row stride
     * @param width    the picture width
     * @param height   the picture height
     * @param layout   the pixel layout, one of the {@code SequenceHeader.LAYOUT_} values
     * @param data     the film grain parameters
     */
    public static void copyUnmodifiedPlanes(short[][] out, int[] outOff, short[][] in, int[] inOff, int stride,
                                            int uvStride, int width, int height, int layout, FilmGrainData data) {
        if (data.numYPoints == 0) {
            copyPlane(out[0], outOff[0], in[0], inOff[0], stride, width, height);
        }
        if (layout != SequenceHeader.LAYOUT_I400 && !data.chromaScalingFromLuma) {
            var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var cw = (width + ssHor) >> ssHor;
            var ch = (height + ssVer) >> ssVer;
            for (var pl = 0; pl < 2; pl++) {
                if (data.numUvPoints[pl] == 0) {
                    copyPlane(out[1 + pl], outOff[1 + pl], in[1 + pl], inOff[1 + pl], uvStride, cw, ch);
                }
            }
        }
    }

    /**
     * Copies the visible area of a plane.
     *
     * @param dst    the destination plane
     * @param dstOff the offset of the destination's first sample
     * @param src    the source plane
     * @param srcOff the offset of the source's first sample
     * @param stride the row stride of both planes
     * @param width  the width of the region, in samples
     * @param height the height of the region, in rows
     */
    private static void copyPlane(short[] dst, int dstOff, short[] src, int srcOff, int stride, int width,
                                  int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, srcOff + y * stride, dst, dstOff + y * stride, width);
        }
    }

    /**
     * Prepares a picture's grain synthesis ({@code dav1d_prep_grain}): builds the templates and
     * scaling tables, then copies the planes that receive no grain.
     *
     * @param dsp         the kernels
     * @param out         the output planes
     * @param outOff      the offset of each output plane's first sample
     * @param in          the input planes
     * @param inOff       the offset of each input plane's first sample
     * @param stride      the luma row stride
     * @param uvStride    the chroma row stride
     * @param width       the picture width
     * @param height      the picture height
     * @param layout      the pixel layout, one of the {@code SequenceHeader.LAYOUT_} values
     * @param bitdepthMax the largest sample value: 255, 1023 or 4095
     * @param data        the film grain parameters
     * @param scaling     three scaling tables of {@link #scalingSize} entries each
     * @param grainLut    three grain templates of {@link FilmGrainDsp#GRAIN_LUT_SIZE} entries each
     */
    public static void prepGrain(FilmGrainDsp dsp, short[][] out, int[] outOff, short[][] in, int[] inOff,
                                 int stride, int uvStride, int width, int height, int layout, int bitdepthMax,
                                 FilmGrainData data, byte[][] scaling, short[][] grainLut) {
        prepareTables(dsp, data, layout, bitdepthMax, scaling, grainLut);
        copyUnmodifiedPlanes(out, outOff, in, inOff, stride, uvStride, width, height, layout, data);
    }

    /**
     * Synthesizes the grain of one stripe of the picture ({@code dav1d_apply_grain_row}).
     *
     * @param dsp           the kernels
     * @param out           the output planes
     * @param outOff        the offset of each output plane's first sample
     * @param in            the input planes
     * @param inOff         the offset of each input plane's first sample
     * @param stride        the luma row stride, greater than the width when the layout is
     *                      horizontally subsampled and the width is odd
     * @param uvStride      the chroma row stride
     * @param width         the picture width
     * @param height        the picture height
     * @param layout        the pixel layout, one of the {@code SequenceHeader.LAYOUT_} values
     * @param identityMatrix whether the matrix coefficients are the identity (code point 0)
     * @param bitdepthMax   the largest sample value: 255, 1023 or 4095
     * @param data          the film grain parameters
     * @param scaling       the scaling tables built by {@link #prepGrain}
     * @param grainLut      the grain templates built by {@link #prepGrain}
     * @param row           the stripe index, from 0 to {@link #rows(int)} minus one
     */
    public static void applyGrainRow(FilmGrainDsp dsp, short[][] out, int[] outOff, short[][] in, int[] inOff,
                                     int stride, int uvStride, int width, int height, int layout,
                                     boolean identityMatrix, int bitdepthMax, FilmGrainData data, byte[][] scaling,
                                     short[][] grainLut, int row) {
        var block = FilmGrainDsp.FG_BLOCK_SIZE;
        var ssY = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var ssX = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var cpw = (width + ssX) >> ssX;
        var lumaSrc = inOff[0] + row * block * stride;
        var rowHeight = Math.min(height - row * block, block);
        if (data.numYPoints != 0) {
            dsp.fgy32x32xn(out[0], outOff[0] + row * block * stride, in[0], lumaSrc, stride, data, width,
                    scaling[0], grainLut[0], rowHeight, row, bitdepthMax);
        }
        if ((data.numUvPoints[0] == 0 && data.numUvPoints[1] == 0 && !data.chromaScalingFromLuma)
                || layout == SequenceHeader.LAYOUT_I400) {
            return;
        }
        var bh = (rowHeight + ssY) >> ssY;
        if ((width & ssX) != 0) {
            var luma = in[0];
            for (int y = 0, p = lumaSrc; y < bh; y++, p += stride << ssY) {
                luma[p + width] = luma[p + width - 1];
            }
        }
        var uvOff = (row * block * uvStride) >> ssY;
        for (var pl = 0; pl < 2; pl++) {
            if (data.chromaScalingFromLuma) {
                dsp.fguv32x32xn(layout, out[1 + pl], outOff[1 + pl] + uvOff, in[1 + pl], inOff[1 + pl] + uvOff,
                        uvStride, data, cpw, scaling[0], grainLut[1 + pl], bh, row, in[0], lumaSrc, stride, pl,
                        identityMatrix, bitdepthMax);
            } else if (data.numUvPoints[pl] != 0) {
                dsp.fguv32x32xn(layout, out[1 + pl], outOff[1 + pl] + uvOff, in[1 + pl], inOff[1 + pl] + uvOff,
                        uvStride, data, cpw, scaling[1 + pl], grainLut[1 + pl], bh, row, in[0], lumaSrc, stride, pl,
                        identityMatrix, bitdepthMax);
            }
        }
    }

    /**
     * Synthesizes the grain of a whole picture on the calling thread ({@code dav1d_apply_grain}).
     *
     * @param dsp            the kernels
     * @param out            the output planes
     * @param outOff         the offset of each output plane's first sample
     * @param in             the input planes
     * @param inOff          the offset of each input plane's first sample
     * @param stride         the luma row stride
     * @param uvStride       the chroma row stride
     * @param width          the picture width
     * @param height         the picture height
     * @param layout         the pixel layout, one of the {@code SequenceHeader.LAYOUT_} values
     * @param identityMatrix whether the matrix coefficients are the identity (code point 0)
     * @param bitdepthMax    the largest sample value: 255, 1023 or 4095
     * @param data           the film grain parameters
     * @param scaling        three scaling tables of {@link #scalingSize} entries each
     * @param grainLut       three grain templates of {@link FilmGrainDsp#GRAIN_LUT_SIZE} entries each
     */
    public static void applyGrain(FilmGrainDsp dsp, short[][] out, int[] outOff, short[][] in, int[] inOff,
                                  int stride, int uvStride, int width, int height, int layout, boolean identityMatrix,
                                  int bitdepthMax, FilmGrainData data, byte[][] scaling, short[][] grainLut) {
        prepGrain(dsp, out, outOff, in, inOff, stride, uvStride, width, height, layout, bitdepthMax, data, scaling,
                grainLut);
        var rows = rows(height);
        for (var row = 0; row < rows; row++) {
            applyGrainRow(dsp, out, outOff, in, inOff, stride, uvStride, width, height, layout, identityMatrix,
                    bitdepthMax, data, scaling, grainLut, row);
        }
    }
}
