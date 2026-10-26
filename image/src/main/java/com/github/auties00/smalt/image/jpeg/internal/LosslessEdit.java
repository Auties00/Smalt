/* Ports libjpeg-turbo (transupp.c). Copyright (C) 1995-2019, Thomas G. Lane, Guido Vollbeding; Copyright (C)
   2010-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jpeg.JpegFill;

import java.util.Arrays;

/**
 * Edits the DCT coefficients of a JPEG frame in place, without decoding them: clears a region (a wipe)
 * or replaces one with the coefficients of another JPEG frame (a drop).
 *
 * <p>Both work on whole iMCUs of the output, whose size and component sampling a
 * {@link LosslessTransform} plan without transform or crop fixes.
 *
 * @implNote This implementation is a port of libjpeg-turbo's transupp.c ({@code do_wipe},
 * {@code do_flatten}, {@code do_reflect}, {@code do_drop}, {@code adjust_quant} and the region
 * arithmetic of {@code jtransform_request_workspace}), so its coefficients and quantization tables
 * match {@code jpegtran}'s exactly. Its 16-bit arithmetic on coefficients and quantizers is kept, as
 * {@code short} casts.
 */
final class LosslessEdit {
    /**
     * Prevents instantiation.
     */
    private LosslessEdit() {
        throw new AssertionError();
    }

    /**
     * Describes the coefficients of one frame.
     *
     * @param frame the frame, whose components give the sampling and the block counts
     * @param planes the coefficients of every frame component, on its MCU-padded grid
     * @param quantization the natural-order quantization table of every frame component
     */
    record Image(JpegFrame frame, CoefficientPlane[] planes, int[][] quantization) {
    }

    /**
     * Clears a region: grows it to the iMCU grid so that it covers every requested pixel, then fills its
     * blocks.
     *
     * @param planes the coefficients of the output components, modified
     * @param plan the output geometry, without transform or crop
     * @param region the region as {@code x, y, width, height} in pixels
     * @param fill how the cleared blocks are filled
     * @throws IllegalArgumentException if the region does not fit the image
     */
    static void wipe(CoefficientPlane[] planes, LosslessTransform plan, int[] region, JpegFill fill) {
        var width = plan.outputWidth();
        var height = plan.outputHeight();
        var x = region[0];
        var y = region[1];
        var w = region[2];
        var h = region[3];
        if (w > width || h > height || x >= width || x > width - w || y >= height || y > height - h) {
            throw new IllegalArgumentException("wipe region " + w + "x" + h + "+" + x + "+" + y
                    + " does not fit the " + width + "x" + height + " image");
        }
        var mcuWidth = plan.mcuWidth();
        var mcuHeight = plan.mcuHeight();
        var columns = JpegFrame.ceilDiv(w + x % mcuWidth, mcuWidth);
        var rows = JpegFrame.ceilDiv(h + y % mcuHeight, mcuHeight);
        var cropX = x / mcuWidth;
        var cropY = y / mcuHeight;
        var reflect = fill == JpegFill.REFLECT && cropY == 0 && rows == JpegFrame.ceilDiv(height, mcuHeight)
                && (cropX == 0 || cropX + columns == JpegFrame.ceilDiv(width, mcuWidth));
        for (var c = 0; c < planes.length; c++) {
            var plane = planes[c];
            var sx = plan.samplingX(c);
            var sy = plan.samplingY(c);
            var left = cropX * sx;
            var count = columns * sx;
            var right = left + count;
            var top = cropY * sy;
            var bottom = top + rows * sy;
            var blocksWide = plan.blocksWide(c);
            for (var row = top; row < bottom; row++) {
                if (reflect) {
                    if (left > 0) {
                        LosslessTransform.reflectRight(plane, row, left, count, left);
                    } else if (blocksWide > right) {
                        LosslessTransform.reflectLeft(plane, row, right, count, blocksWide - right);
                    } else {
                        zero(plane, row, left, right);
                    }
                    continue;
                }
                zero(plane, row, left, right);
                if (fill != JpegFill.FLAT) {
                    continue;
                }
                int average;
                if (left > 0) {
                    var leftDc = plane.dc(left - 1, row);
                    average = right < blocksWide ? (leftDc + plane.dc(right, row)) >> 1 : leftDc;
                } else if (right < blocksWide) {
                    average = plane.dc(right, row);
                } else {
                    continue;
                }
                for (var column = left; column < right; column++) {
                    LosslessTransform.setDc(plane, column, row, (short) average);
                }
            }
        }
    }

    /**
     * Replaces a region of the source with the coefficients of another frame, adapting the
     * quantization tables of the output or requantizing the dropped coefficients when they differ.
     *
     * <p>The offset moves right and down to the next iMCU boundary and only the whole iMCUs of the
     * dropped frame that then fit are copied, with its partial last iMCU column or row when it reaches
     * the right or bottom edge. A dropped block outside the dropped frame's grid reads as zeros; an
     * output component the dropped frame lacks is filled with zeros.
     *
     * @param source the source frame; its planes are the output components' coefficients, modified
     * @param drop the dropped frame; its planes may be modified
     * @param plan the output geometry, without transform or crop
     * @param slotTables the natural-order quantization table of each slot of the output, modified
     * @param x the horizontal offset in pixels
     * @param y the vertical offset in pixels
     * @param requantize whether the dropped coefficients are requantized to the source's tables
     * rather than both images dequantized to common tables
     * @throws IllegalArgumentException if the dropped frame does not fit the source at the offset
     * @throws MediaException.Unsupported if a component of the dropped frame is sampled unlike the
     * source's
     */
    static void drop(Image source, Image drop, LosslessTransform plan, int[][] slotTables, int x, int y,
                     boolean requantize) {
        var width = plan.outputWidth();
        var height = plan.outputHeight();
        var dropWidth = drop.frame().width;
        var dropHeight = drop.frame().height;
        if (dropWidth > width || dropHeight > height || x >= width || x > width - dropWidth || y >= height
                || y > height - dropHeight) {
            throw new IllegalArgumentException("the " + dropWidth + "x" + dropHeight + " drop image at +" + x
                    + "+" + y + " does not fit the " + width + "x" + height + " image");
        }
        var mcuWidth = plan.mcuWidth();
        var mcuHeight = plan.mcuHeight();
        var skipX = mcuWidth - 1 - (x + mcuWidth - 1) % mcuWidth;
        x += skipX;
        var columns = extent(dropWidth, skipX, x, width, mcuWidth);
        var skipY = mcuHeight - 1 - (y + mcuHeight - 1) % mcuHeight;
        y += skipY;
        var rows = extent(dropHeight, skipY, y, height, mcuHeight);
        if (columns == 0 || rows == 0) {
            return;
        }
        var outputs = source.planes().length;
        var dropComponents = drop.frame().components;
        var sourceComponents = source.frame().components;
        var common = Math.min(outputs, dropComponents.length);
        for (var c = 0; c < common; c++) {
            var dropped = dropComponents[c];
            var kept = sourceComponents[c];
            if (dropped.h * source.frame().hMax != kept.h * drop.frame().hMax
                    || dropped.v * source.frame().vMax != kept.v * drop.frame().vMax) {
                throw new MediaException.Unsupported("jpeg drop sampling", "component " + c
                        + " of the drop image is sampled " + dropped.h + "x" + dropped.v + " of "
                        + drop.frame().hMax + "x" + drop.frame().vMax + ", that of the image " + kept.h + "x"
                        + kept.v + " of " + source.frame().hMax + "x" + source.frame().vMax);
            }
        }
        adjustQuantization(source, drop, common, slotTables, requantize);
        var cropX = x / mcuWidth;
        var cropY = y / mcuHeight;
        for (var c = 0; c < outputs; c++) {
            var plane = source.planes()[c];
            var sx = plan.samplingX(c);
            var sy = plan.samplingY(c);
            var count = columns * sx;
            var left = cropX * sx;
            var top = cropY * sy;
            var high = rows * sy;
            for (var row = 0; row < high; row++) {
                if (c < dropComponents.length) {
                    var from = drop.planes()[c];
                    for (var column = 0; column < count; column++) {
                        LosslessTransform.block(from, column, row, plane, left + column, top + row,
                                LosslessTransform.COPY);
                    }
                } else {
                    zero(plane, top + row, left, left + count);
                }
            }
        }
    }

    /**
     * Returns the number of iMCUs of a dropped frame copied along one dimension.
     *
     * @param size the dropped frame's extent in pixels
     * @param skip the pixels skipped to reach the iMCU boundary
     * @param offset the offset after the skip
     * @param limit the source's extent in pixels
     * @param unit the iMCU extent in pixels
     * @return the number of iMCUs, including a partial last one when the frame reaches the edge
     */
    private static int extent(int size, int skip, int offset, int limit, int unit) {
        if (size <= skip) {
            return 0;
        }
        return offset + size - skip == limit ? JpegFrame.ceilDiv(size - skip, unit) : (size - skip) / unit;
    }

    /**
     * Reconciles the quantization tables of the components both frames have
     * ({@code adjust_quant}).
     *
     * @param source the source frame, whose coefficients may be dequantized
     * @param drop the dropped frame, whose coefficients may be dequantized or requantized
     * @param common the number of components both frames have
     * @param slotTables the output's slot tables, which may be replaced by common divisors
     * @param requantize whether to requantize the dropped coefficients rather than dequantize both
     */
    private static void adjustQuantization(Image source, Image drop, int common, int[][] slotTables,
                                           boolean requantize) {
        for (var c = 0; c < common; c++) {
            var kept = source.quantization()[c];
            var dropped = drop.quantization()[c];
            if (Arrays.equals(kept, dropped)) {
                continue;
            }
            if (requantize) {
                requantize(drop.frame().components[c], drop.planes()[c], dropped, kept);
                continue;
            }
            var table = slotTables[source.frame().components[c].quantSelector];
            for (var k = 0; k < 64; k++) {
                if (kept[k] != dropped[k]) {
                    table[k] = greatestCommonDivisor(kept[k], dropped[k]);
                }
            }
            dequantize(source.frame().components[c], source.planes()[c], kept, table);
            dequantize(drop.frame().components[c], drop.planes()[c], dropped, table);
        }
    }

    /**
     * Returns the greatest common divisor of two different quantizers, computed on their 16-bit signed
     * values ({@code largest_common_denominator}).
     *
     * <p>A zero quantizer, which a malformed table may hold, divides nothing: the divisor is then the
     * other quantizer.
     *
     * @param a the first quantizer, from 0 to 65535
     * @param b the second quantizer, from 0 to 65535
     * @return the divisor, as a 16-bit quantizer, not zero
     */
    private static int greatestCommonDivisor(int a, int b) {
        if (b == 0) {
            return a;
        }
        int x = (short) a;
        int y = (short) b;
        int remainder;
        do {
            remainder = x % y;
            x = y;
            y = remainder;
        } while (remainder != 0);
        return x & 0xFFFF;
    }

    /**
     * Rescales the coefficients of a component from its quantization table to a table of divisors
     * of its quantizers ({@code dequant_comp}).
     *
     * @param component the frame component, whose real block grid is rescaled
     * @param plane its coefficients, modified
     * @param from its quantization table
     * @param to the new table, each entry dividing the one of {@code from} where they differ
     */
    private static void dequantize(JpegComponent component, CoefficientPlane plane, int[] from, int[] to) {
        var rows = JpegFrame.ceilDiv(component.blocksHigh, component.v) * component.v;
        var data = plane.data;
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < component.blocksWide; column++) {
                if (!plane.contains(column, row)) {
                    continue;
                }
                var offset = plane.offset(column, row);
                for (var k = 0; k < 64; k++) {
                    if (from[k] != to[k]) {
                        data[offset + k] = (short) (data[offset + k] * (from[k] / to[k]));
                    }
                }
            }
        }
    }

    /**
     * Requantizes the coefficients of a component from its quantization table to another, rounding
     * to nearest with 16-bit intermediates ({@code requant_comp}).
     *
     * @param component the frame component, whose real block grid is requantized
     * @param plane its coefficients, modified
     * @param from its quantization table
     * @param to the new table
     */
    private static void requantize(JpegComponent component, CoefficientPlane plane, int[] from, int[] to) {
        var rows = JpegFrame.ceilDiv(component.blocksHigh, component.v) * component.v;
        var data = plane.data;
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < component.blocksWide; column++) {
                if (!plane.contains(column, row)) {
                    continue;
                }
                var offset = plane.offset(column, row);
                for (var k = 0; k < 64; k++) {
                    var temp = (short) from[k];
                    var quantizer = (short) to[k];
                    if (temp == quantizer || quantizer == 0) {
                        continue;
                    }
                    temp = (short) (temp * data[offset + k]);
                    if (temp < 0) {
                        temp = (short) -temp;
                        temp = (short) (temp + (quantizer >> 1));
                        temp = temp >= quantizer ? (short) (temp / quantizer) : 0;
                        temp = (short) -temp;
                    } else {
                        temp = (short) (temp + (quantizer >> 1));
                        temp = temp >= quantizer ? (short) (temp / quantizer) : 0;
                    }
                    data[offset + k] = temp;
                }
            }
        }
    }

    /**
     * Zeroes a run of blocks of one block row; blocks outside the grid are skipped.
     *
     * @param plane the plane, modified
     * @param row the block row
     * @param from the first block column
     * @param to the block column after the last
     */
    private static void zero(CoefficientPlane plane, int row, int from, int to) {
        for (var column = from; column < to; column++) {
            LosslessTransform.setDc(plane, column, row, (short) 0);
        }
    }
}
