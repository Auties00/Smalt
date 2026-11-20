/* Ports libjpeg-turbo (jcprepct.c, jcsample.c). Copyright (C) 1991-1996, Thomas G. Lane; Copyright (C)
   2009-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import java.util.Arrays;

/**
 * Fills the samples of every component for one fully interleaved MCU row ("iMCU row") of a frame, from
 * full-resolution rows or from planes already in the frame's subsampling.
 *
 * <p>Band {@code c} holds {@code v[c]} rows of data units: {@code v[c] * unit} sample rows of
 * {@link FrameLayout#bandWidth} samples each, {@link #stride}{@code [c]} apart. Samples outside the picture
 * are filled as libjpeg fills them: a row group of {@code Vmax} full-resolution rows that crosses the
 * bottom repeats the last row, the samples right of a row repeat its last sample, a component is
 * downsampled by averaging (with the alternating rounding of libjpeg's 2:1 filters), and the rows of an
 * iMCU row past the last row group repeat the last downsampled row. Instances are not thread-safe; each
 * thread uses its own.
 */
final class ComponentBands {
    /**
     * The frame.
     */
    private final FrameLayout layout;

    /**
     * The full-resolution rows, or {@code null} when {@link #planes} supply the samples.
     */
    private final SampleRows rows;

    /**
     * The component planes in the frame's subsampling, or {@code null}.
     */
    private final Planes planes;

    /**
     * The samples of each component, one band per component.
     */
    final short[][] bands;

    /**
     * The row stride of each band.
     */
    final int[] stride;

    /**
     * The full-resolution rows of one row group, indexed by row of the group then component.
     */
    private final short[][][] group;

    /**
     * Holds the planes of a picture already in the frame's subsampling: component {@code c} is
     * {@code width[c]} by {@code height[c]} samples, each sample a short or an unsigned byte.
     *
     * @param shorts the 16-bit planes by component, or {@code null}
     * @param bytes the 8-bit planes by component, or {@code null}
     * @param strides the stride of each plane
     * @param rescale the table that rescales a sample to the precision, or {@code null}
     * @param mask the mask of the valid bits of a sample
     */
    record Planes(short[][] shorts, byte[][] bytes, int[] strides, int[] rescale, int mask) {
        /**
         * Returns one sample.
         *
         * @param component the component index, from 0
         * @param index the sample index in its plane
         * @return the sample at the frame's precision
         */
        int sample(int component, int index) {
            var value = (shorts != null ? shorts[component][index] : bytes[component][index]) & mask;
            return rescale == null ? value : rescale[value];
        }
    }

    /**
     * Allocates the bands of one thread.
     *
     * @param layout the frame
     * @param rows the full-resolution rows, or {@code null} when {@code planes} is given; copied for this
     * thread
     * @param planes the planes, or {@code null}
     * @param minimumStride the smallest stride of each band, at least its width
     */
    ComponentBands(FrameLayout layout, SampleRows rows, Planes planes, int[] minimumStride) {
        this.layout = layout;
        this.rows = rows == null ? null : rows.copy();
        this.planes = planes;
        var count = layout.count;
        this.stride = new int[count];
        this.bands = new short[count][];
        var groupWidth = layout.width;
        for (var c = 0; c < count; c++) {
            stride[c] = Math.max(minimumStride[c], layout.bandWidth(c));
            bands[c] = new short[layout.v[c] * layout.unit * stride[c]];
            groupWidth = Math.max(groupWidth, layout.bandWidth(c) * (layout.maxH / layout.h[c]));
        }
        this.group = rows == null ? null : new short[layout.maxV][count][groupWidth];
    }

    /**
     * Fills the bands of one iMCU row.
     *
     * @param imcu the iMCU row
     */
    void fill(int imcu) {
        if (planes != null) {
            fillPlanes(imcu);
            return;
        }
        var count = layout.count;
        var unit = layout.unit;
        var maxV = layout.maxV;
        var height = layout.height;
        var groups = 0;
        for (var g = 0; g < unit; g++) {
            var first = (imcu * unit + g) * maxV;
            if (first >= height) {
                break;
            }
            for (var k = 0; k < maxV; k++) {
                if (first + k >= height) {
                    for (var c = 0; c < count; c++) {
                        System.arraycopy(group[k - 1][c], 0, group[k][c], 0, layout.width);
                    }
                } else {
                    rows.fill(first + k, group[k]);
                }
            }
            for (var c = 0; c < count; c++) {
                downsample(c, g * layout.v[c]);
            }
            groups++;
        }
        for (var c = 0; c < count; c++) {
            var produced = groups * layout.v[c];
            var total = unit * layout.v[c];
            var width = layout.bandWidth(c);
            var band = bands[c];
            var s = stride[c];
            for (var r = produced; r < total; r++) {
                System.arraycopy(band, (produced - 1) * s, band, r * s, width);
            }
        }
    }

    /**
     * Downsamples the current row group into one component's band.
     *
     * @param c the component
     * @param outRow the first band row the group fills
     */
    private void downsample(int c, int outRow) {
        var width = layout.width;
        var outCols = layout.bandWidth(c);
        var hExpand = layout.maxH / layout.h[c];
        var vExpand = layout.maxV / layout.v[c];
        var inCols = outCols * hExpand;
        for (var k = 0; k < layout.maxV; k++) {
            var row = group[k][c];
            if (inCols > width) {
                Arrays.fill(row, width, inCols, row[width - 1]);
            }
        }
        var band = bands[c];
        var s = stride[c];
        var v = layout.v[c];
        if (hExpand == 1 && vExpand == 1) {
            for (var r = 0; r < v; r++) {
                var in = group[r][c];
                var target = (outRow + r) * s;
                for (var x = 0; x < outCols; x++) {
                    band[target + x] = in[x];
                }
            }
        } else if (hExpand == 2 && vExpand == 1) {
            for (var r = 0; r < v; r++) {
                var in = group[r][c];
                var target = (outRow + r) * s;
                var bias = 0;
                for (var x = 0; x < outCols; x++) {
                    band[target + x] = (short) (((in[2 * x] & 0xFFFF) + (in[2 * x + 1] & 0xFFFF) + bias) >> 1);
                    bias ^= 1;
                }
            }
        } else if (hExpand == 2 && vExpand == 2) {
            for (var r = 0; r < v; r++) {
                var in0 = group[2 * r][c];
                var in1 = group[2 * r + 1][c];
                var target = (outRow + r) * s;
                var bias = 1;
                for (var x = 0; x < outCols; x++) {
                    band[target + x] = (short) (((in0[2 * x] & 0xFFFF) + (in0[2 * x + 1] & 0xFFFF) + (in1[2 * x] & 0xFFFF)
                            + (in1[2 * x + 1] & 0xFFFF) + bias) >> 2);
                    bias ^= 3;
                }
            }
        } else {
            var pixels = hExpand * vExpand;
            var half = pixels / 2;
            for (var r = 0; r < v; r++) {
                var target = (outRow + r) * s;
                for (var x = 0; x < outCols; x++) {
                    var sum = 0L;
                    for (var dy = 0; dy < vExpand; dy++) {
                        var in = group[r * vExpand + dy][c];
                        for (var dx = 0; dx < hExpand; dx++) {
                            sum += in[x * hExpand + dx] & 0xFFFF;
                        }
                    }
                    band[target + x] = (short) ((sum + half) / pixels);
                }
            }
        }
    }

    /**
     * Fills the bands from planes already in the frame's subsampling, repeating the last row and column of
     * each.
     *
     * @param imcu the iMCU row
     */
    private void fillPlanes(int imcu) {
        for (var c = 0; c < layout.count; c++) {
            var rowsInBand = layout.v[c] * layout.unit;
            var width = layout.sampleWidth[c];
            var bandWidth = layout.bandWidth(c);
            var band = bands[c];
            var s = stride[c];
            var planeStride = planes.strides()[c];
            for (var r = 0; r < rowsInBand; r++) {
                var row = Math.min(imcu * rowsInBand + r, layout.sampleHeight[c] - 1);
                var target = r * s;
                var source = row * planeStride;
                for (var x = 0; x < width; x++) {
                    band[target + x] = (short) planes.sample(c, source + x);
                }
                Arrays.fill(band, target + width, target + bandWidth, band[target + width - 1]);
            }
        }
    }
}
