/* Ports libjpeg-turbo (transupp.c). Copyright (C) 1997-2019, Thomas G. Lane, Guido Vollbeding; Copyright (C)
   2010-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.jpeg.JpegFill;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Rotates, flips and crops the DCT coefficients of a JPEG frame without decoding them.
 *
 * <p>Mirroring a block negates its odd columns (horizontally) or odd rows (vertically); transposing
 * a block transposes its coefficients; rotations and the transverse combine the two. Whole blocks
 * move as the image does, MCU by MCU, so only an image whose size is a multiple of the MCU size
 * transforms perfectly: the partial MCUs at the right and bottom edges cannot move to the other
 * edge, and are left in place (transposed but not mirrored) unless trimmed away. Cropping keeps a
 * region whose top-left corner is moved up and left to the nearest MCU boundary; without a transform, a
 * crop region larger than the image extends it, filling the added blocks with gray, flat DC or
 * reflections.
 *
 * <p>Construct a plan with the source geometry and the requested operation, which fixes the output
 * size, then call {@link #execute} with the source coefficients.
 *
 * @implNote This implementation is a port of libjpeg-turbo's transupp.c
 * ({@code jtransform_request_workspace}, {@code jtransform_execute_transform} and the
 * {@code do_*} routines it calls, for the rotations, flips, transpose, transverse, plain cropping
 * and crop extension), so its coefficients match {@code jpegtran}'s exactly, including the untouched
 * edge blocks of an imperfect transform.
 */
final class LosslessTransform {
    /**
     * The largest width or height a JPEG frame header can record.
     */
    private static final int MAX_DIMENSION = 0xFFFF;

    /**
     * Copies a block.
     */
    static final int COPY = 0;

    /**
     * Mirrors a block horizontally: negates its odd columns.
     */
    static final int MIRROR_COLUMNS = 1;

    /**
     * Mirrors a block vertically: negates its odd rows.
     */
    private static final int MIRROR_ROWS = 2;

    /**
     * Mirrors a block both ways: negates the coefficients whose row plus column is odd.
     */
    private static final int MIRROR_BOTH = 3;

    /**
     * Transposes a block.
     */
    private static final int TRANSPOSE = 4;

    /**
     * Transposes a block and negates the coefficients from its odd source rows (rotation by 90
     * degrees).
     */
    private static final int TRANSPOSE_ODD_ROWS = 5;

    /**
     * Transposes a block and negates the coefficients from its odd source columns (rotation by
     * 270 degrees).
     */
    private static final int TRANSPOSE_ODD_COLUMNS = 6;

    /**
     * Transposes a block and negates the coefficients whose row plus column is odd (transverse).
     */
    private static final int TRANSPOSE_BOTH = 7;

    /**
     * The operation.
     */
    private final Orientation transform;

    /**
     * The source image width.
     */
    private final int sourceWidth;

    /**
     * The source image height.
     */
    private final int sourceHeight;

    /**
     * The number of output components.
     */
    private final int components;

    /**
     * The horizontal sampling factor of each output component.
     */
    private final int[] samplingX;

    /**
     * The vertical sampling factor of each output component.
     */
    private final int[] samplingY;

    /**
     * The largest output horizontal sampling factor.
     */
    private final int maxSamplingX;

    /**
     * The largest output vertical sampling factor.
     */
    private final int maxSamplingY;

    /**
     * The output image width.
     */
    private int outputWidth;

    /**
     * The output image height.
     */
    private int outputHeight;

    /**
     * The width in pixels of one output iMCU.
     */
    private final int mcuWidth;

    /**
     * The height in pixels of one output iMCU.
     */
    private final int mcuHeight;

    /**
     * The horizontal crop offset, in iMCUs.
     */
    private final int cropX;

    /**
     * The vertical crop offset, in iMCUs.
     */
    private final int cropY;

    /**
     * How a crop extension fills the blocks it adds.
     */
    private final JpegFill fill;

    /**
     * Whether the output needs coefficient arrays separate from the source.
     */
    private final boolean workspace;

    /**
     * Plans a transform.
     *
     * @param transform    the operation
     * @param sourceWidth  the source image width
     * @param sourceHeight the source image height
     * @param samplingX    the horizontal sampling factor of every source component
     * @param samplingY    the vertical sampling factor of every source component
     * @param components   the number of output components: all of the source's, or 1 to keep
     *                     only the first
     * @param crop         the crop region as {@code x, y, width, height} in output coordinates,
     *                     or {@code null}; a width or height beyond the image's extends it, the
     *                     offset then placing the image in the output
     * @param exactWidth   whether a crop that keeps the image width yields exactly its width
     * @param exactHeight  whether a crop that keeps the image height yields exactly its height
     * @param fill         how a crop extension fills the blocks left and right of the image
     * @param perfect      whether to reject a transform that would leave edge blocks in place
     * @param trim         whether to drop the edge blocks that cannot be transformed
     * @throws MediaException.Unsupported if {@code perfect} is set and the transform is not
     *                                    perfect for this image
     * @throws IllegalArgumentException   if the crop region is empty, lies outside the image, or
     *                                    extends it under a transform, past the image or beyond
     *                                    65535 pixels per side
     */
    LosslessTransform(Orientation transform, int sourceWidth, int sourceHeight, int[] samplingX, int[] samplingY,
                      int components, int[] crop, boolean exactWidth, boolean exactHeight, JpegFill fill,
                      boolean perfect, boolean trim) {
        this.transform = transform;
        this.fill = fill;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.components = components;
        var sourceMaxX = 1;
        var sourceMaxY = 1;
        for (var i = 0; i < samplingX.length; i++) {
            sourceMaxX = Math.max(sourceMaxX, samplingX[i]);
            sourceMaxY = Math.max(sourceMaxY, samplingY[i]);
        }
        var transposed = transform.swapsDimensions();
        if (perfect) {
            var blockX = components == 1 ? 8 : sourceMaxX * 8;
            var blockY = components == 1 ? 8 : sourceMaxY * 8;
            if (!isPerfect(sourceWidth, sourceHeight, blockX, blockY, transform)) {
                throw new MediaException.Unsupported("jpeg imperfect transform", "a " + sourceWidth + "x"
                        + sourceHeight + " image with " + blockX + "x" + blockY
                        + " MCUs cannot be transformed perfectly by " + transform);
            }
        }
        this.samplingX = new int[components];
        this.samplingY = new int[components];
        for (var i = 0; i < components; i++) {
            if (components == 1) {
                this.samplingX[i] = 1;
                this.samplingY[i] = 1;
            } else if (transposed) {
                this.samplingX[i] = samplingY[i];
                this.samplingY[i] = samplingX[i];
            } else {
                this.samplingX[i] = samplingX[i];
                this.samplingY[i] = samplingY[i];
            }
        }
        var maxX = 1;
        var maxY = 1;
        for (var i = 0; i < components; i++) {
            maxX = Math.max(maxX, this.samplingX[i]);
            maxY = Math.max(maxY, this.samplingY[i]);
        }
        this.maxSamplingX = maxX;
        this.maxSamplingY = maxY;
        if (transposed) {
            outputWidth = sourceHeight;
            outputHeight = sourceWidth;
            mcuWidth = components == 1 ? 8 : sourceMaxY * 8;
            mcuHeight = components == 1 ? 8 : sourceMaxX * 8;
        } else {
            outputWidth = sourceWidth;
            outputHeight = sourceHeight;
            mcuWidth = components == 1 ? 8 : sourceMaxX * 8;
            mcuHeight = components == 1 ? 8 : sourceMaxY * 8;
        }
        if (crop != null) {
            var x = crop[0];
            var y = crop[1];
            var width = crop[2];
            var height = crop[3];
            var extendX = width > outputWidth;
            var extendY = height > outputHeight;
            if ((extendX || extendY) && transform != Orientation.NORMAL) {
                throw new IllegalArgumentException("crop region " + width + "x" + height + "+" + x + "+" + y
                        + " extends the " + outputWidth + "x" + outputHeight + " image, which " + transform
                        + " cannot do");
            }
            if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                throw new IllegalArgumentException("crop region " + width + "x" + height + "+" + x + "+" + y
                        + " extends the image past the " + MAX_DIMENSION + " pixels per side of a JPEG frame");
            }
            var badX = extendX ? x >= width || x > width - outputWidth : x >= outputWidth || x > outputWidth - width;
            var badY = extendY ? y >= height || y > height - outputHeight
                    : y >= outputHeight || y > outputHeight - height;
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || badX || badY) {
                throw new IllegalArgumentException("crop region " + width + "x" + height + "+" + x + "+" + y
                        + " does not fit the " + outputWidth + "x" + outputHeight + " image");
            }
            outputWidth = exactWidth || extendX ? width : width + x % mcuWidth;
            outputHeight = exactHeight || extendY ? height : height + y % mcuHeight;
            cropX = x / mcuWidth;
            cropY = y / mcuHeight;
        } else {
            cropX = 0;
            cropY = 0;
        }
        this.workspace = switch (transform) {
            case NORMAL -> cropX != 0 || cropY != 0 || outputWidth > sourceWidth || outputHeight > sourceHeight;
            case FLIP_HORIZONTAL -> {
                if (trim) {
                    trimRight(sourceWidth);
                }
                yield cropY != 0;
            }
            case FLIP_VERTICAL -> {
                if (trim) {
                    trimBottom(sourceHeight);
                }
                yield true;
            }
            case TRANSPOSE -> true;
            case TRANSVERSE -> {
                if (trim) {
                    trimRight(sourceHeight);
                    trimBottom(sourceWidth);
                }
                yield true;
            }
            case ROTATE_90 -> {
                if (trim) {
                    trimRight(sourceHeight);
                }
                yield true;
            }
            case ROTATE_180 -> {
                if (trim) {
                    trimRight(sourceWidth);
                    trimBottom(sourceHeight);
                }
                yield true;
            }
            case ROTATE_270 -> {
                if (trim) {
                    trimBottom(sourceWidth);
                }
                yield true;
            }
        };
    }

    /**
     * Returns whether a transform moves every block of an image, leaving no edge block in place.
     *
     * @param width     the image width
     * @param height    the image height
     * @param mcuWidth  the MCU width in pixels
     * @param mcuHeight the MCU height in pixels
     * @param transform the operation
     * @return {@code true} if the transform is perfect
     */
    static boolean isPerfect(int width, int height, int mcuWidth, int mcuHeight, Orientation transform) {
        return switch (transform) {
            case FLIP_HORIZONTAL, ROTATE_270 -> width % mcuWidth == 0;
            case FLIP_VERTICAL, ROTATE_90 -> height % mcuHeight == 0;
            case TRANSVERSE, ROTATE_180 -> width % mcuWidth == 0 && height % mcuHeight == 0;
            case NORMAL, TRANSPOSE -> true;
        };
    }

    /**
     * Drops the partial iMCU column at the right edge when the output reaches it.
     *
     * @param fullWidth the source extent that becomes the output width
     */
    private void trimRight(int fullWidth) {
        var columns = outputWidth / mcuWidth;
        if (columns > 0 && cropX + columns == fullWidth / mcuWidth) {
            outputWidth = columns * mcuWidth;
        }
    }

    /**
     * Drops the partial iMCU row at the bottom edge when the output reaches it.
     *
     * @param fullHeight the source extent that becomes the output height
     */
    private void trimBottom(int fullHeight) {
        var rows = outputHeight / mcuHeight;
        if (rows > 0 && cropY + rows == fullHeight / mcuHeight) {
            outputHeight = rows * mcuHeight;
        }
    }

    /**
     * Returns the output image width.
     *
     * @return the width in pixels
     */
    int outputWidth() {
        return outputWidth;
    }

    /**
     * Returns the output image height.
     *
     * @return the height in pixels
     */
    int outputHeight() {
        return outputHeight;
    }

    /**
     * Returns the width in pixels of one output iMCU, the unit of crop, wipe and drop offsets.
     *
     * @return 8 for a single output component, else 8 times the largest horizontal sampling factor
     */
    int mcuWidth() {
        return mcuWidth;
    }

    /**
     * Returns the height in pixels of one output iMCU.
     *
     * @return 8 for a single output component, else 8 times the largest vertical sampling factor
     */
    int mcuHeight() {
        return mcuHeight;
    }

    /**
     * Returns the horizontal sampling factor of an output component.
     *
     * @param component the output component index
     * @return the factor, 1 when only one component is output
     */
    int samplingX(int component) {
        return samplingX[component];
    }

    /**
     * Returns the vertical sampling factor of an output component.
     *
     * @param component the output component index
     * @return the factor, 1 when only one component is output
     */
    int samplingY(int component) {
        return samplingY[component];
    }

    /**
     * Returns the number of block columns an output component covers.
     *
     * @param component the output component index
     * @return {@code ceil(width * h / (hMax * 8))}
     */
    int blocksWide(int component) {
        return JpegFrame.ceilDiv((long) outputWidth * samplingX[component], maxSamplingX * 8);
    }

    /**
     * Returns the number of block rows an output component covers.
     *
     * @param component the output component index
     * @return {@code ceil(height * v / (vMax * 8))}
     */
    int blocksHigh(int component) {
        return JpegFrame.ceilDiv((long) outputHeight * samplingY[component], maxSamplingY * 8);
    }

    /**
     * Transforms the coefficients.
     *
     * <p>The source planes must cover every component's MCU-padded block grid; the in-place
     * horizontal flip modifies them.
     *
     * @param source the coefficients of the source components; only the first
     *               {@link #components} are read
     * @param limits the bound on the output allocation
     * @return the coefficients of each output component, covering at least its
     *         {@link #blocksWide(int)} by {@link #blocksHigh(int)} blocks
     * @throws MediaException.LimitExceeded if the output planes exceed the limits
     */
    CoefficientPlane[] execute(CoefficientPlane[] source, MediaLimits limits) {
        var output = new CoefficientPlane[components];
        if (!workspace) {
            System.arraycopy(source, 0, output, 0, components);
            if (transform == Orientation.FLIP_HORIZONTAL) {
                flipInPlace(source);
            }
            return output;
        }
        var mcusWide = JpegFrame.ceilDiv(outputWidth, mcuWidth);
        var mcusHigh = JpegFrame.ceilDiv(outputHeight, mcuHeight);
        var total = 0L;
        for (var i = 0; i < components; i++) {
            total += (long) mcusWide * samplingX[i] * mcusHigh * samplingY[i] * 128;
        }
        LimitChecks.checkAllocation(limits, total);
        for (var i = 0; i < components; i++) {
            output[i] = new CoefficientPlane(mcusWide * samplingX[i], mcusHigh * samplingY[i]);
        }
        switch (transform) {
            case NORMAL -> {
                if (outputWidth > sourceWidth || outputHeight > sourceHeight) {
                    extend(source, output, outputWidth > sourceWidth ? fill : JpegFill.GRAY);
                } else {
                    crop(source, output);
                }
            }
            case FLIP_HORIZONTAL -> flipHorizontal(source, output);
            case FLIP_VERTICAL -> flipVertical(source, output);
            case TRANSPOSE -> transpose(source, output);
            case TRANSVERSE -> transverse(source, output);
            case ROTATE_90 -> rotate90(source, output);
            case ROTATE_180 -> rotate180(source, output);
            case ROTATE_270 -> rotate270(source, output);
        }
        return output;
    }

    /**
     * Copies the cropped region.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void crop(CoefficientPlane[] source, CoefficientPlane[] output) {
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    for (var x = 0; x < wide; x++) {
                        block(source[c], x + cropColumns, y + oy + cropRows, output[c], x, y + oy, COPY);
                    }
                }
            }
        }
    }

    /**
     * Places the image in a larger output, then fills the blocks left and right of it; the blocks
     * above and below it, and the partial MCUs at the image's right and bottom edges along an
     * extended dimension, stay zero.
     *
     * @implNote This implementation ports {@code do_crop_ext_zero}, {@code do_crop_ext_flat} and
     * {@code do_crop_ext_reflect}; for an image less than one MCU wide, a reflection, which those
     * loop on forever, leaves the added blocks zero, and a flat fill, which those read before the
     * block row for, repeats the DC coefficient of the image's first block on both sides.
     * @param source the source planes
     * @param output the output planes, zeroed
     * @param fill how the blocks left and right of the image are filled, {@link JpegFill#GRAY} when
     * only the height is extended
     */
    private void extend(CoefficientPlane[] source, CoefficientPlane[] output, JpegFill fill) {
        var mcuColumns = sourceWidth / mcuWidth;
        var mcuRows = sourceHeight / mcuHeight;
        var wider = outputWidth > sourceWidth;
        var taller = outputHeight > sourceHeight;
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var imageColumns = mcuColumns * h;
            var imageRows = mcuRows * v;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            var in = source[c];
            var out = output[c];
            for (var y = 0; y < high; y += v) {
                int sourceRow;
                if (taller) {
                    if (y < cropRows || y >= cropRows + imageRows) {
                        continue;
                    }
                    sourceRow = y - cropRows;
                } else {
                    sourceRow = y + cropRows;
                }
                for (var oy = 0; oy < v; oy++) {
                    var row = y + oy;
                    var from = sourceRow + oy;
                    if (!wider) {
                        for (var x = 0; x < wide; x++) {
                            block(in, x + cropColumns, from, out, x, row, COPY);
                        }
                        continue;
                    }
                    for (var x = 0; x < imageColumns; x++) {
                        block(in, x, from, out, x + cropColumns, row, COPY);
                    }
                    switch (fill) {
                        case GRAY -> {
                        }
                        case FLAT -> {
                            if (cropColumns > 0) {
                                var dc = (short) in.dc(0, from);
                                for (var x = 0; x < cropColumns; x++) {
                                    setDc(out, x, row, dc);
                                }
                            }
                            if (wide > cropColumns + imageColumns) {
                                var dc = (short) in.dc(Math.max(imageColumns - 1, 0), from);
                                for (var x = cropColumns + imageColumns; x < wide; x++) {
                                    setDc(out, x, row, dc);
                                }
                            }
                        }
                        case REFLECT -> {
                            if (imageColumns > 0) {
                                reflectLeft(out, row, cropColumns, cropColumns, imageColumns);
                                reflectRight(out, row, cropColumns + imageColumns, wide - cropColumns - imageColumns,
                                        imageColumns);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fills blocks leftward from an axis with repeated mirror images of the blocks right of it,
     * reflecting the reflections when they run out.
     *
     * @param plane the plane, modified
     * @param row the block row
     * @param axis the first block column right of the filled run
     * @param count the number of blocks to fill
     * @param period the number of blocks each reflection copies, positive
     */
    static void reflectLeft(CoefficientPlane plane, int row, int axis, int count, int period) {
        var target = axis;
        while (count > 0) {
            var from = target;
            for (var s = period; s > 0 && count > 0; s--, count--) {
                block(plane, from++, row, plane, --target, row, MIRROR_COLUMNS);
            }
        }
    }

    /**
     * Fills blocks rightward from an axis with repeated mirror images of the blocks left of it,
     * reflecting the reflections when they run out.
     *
     * @param plane the plane, modified
     * @param row the block row
     * @param axis the first filled block column
     * @param count the number of blocks to fill
     * @param period the number of blocks each reflection copies, positive
     */
    static void reflectRight(CoefficientPlane plane, int row, int axis, int count, int period) {
        var target = axis;
        while (count > 0) {
            var from = target;
            for (var s = period; s > 0 && count > 0; s--, count--) {
                block(plane, --from, row, plane, target++, row, MIRROR_COLUMNS);
            }
        }
    }

    /**
     * Makes a block flat: zeroes its AC coefficients and sets its DC coefficient; a block outside
     * the grid is skipped.
     *
     * @param plane the plane, modified
     * @param column the block column
     * @param row the block row
     * @param dc the DC coefficient
     */
    static void setDc(CoefficientPlane plane, int column, int row, short dc) {
        if (!plane.contains(column, row)) {
            return;
        }
        var offset = plane.offset(column, row);
        Arrays.fill(plane.data, offset, offset + 64, (short) 0);
        plane.data[offset] = dc;
    }

    /**
     * Mirrors horizontally in place, then shifts the kept region left by the crop offset; valid
     * only without a vertical crop offset.
     *
     * @param planes the source planes, modified
     */
    private void flipInPlace(CoefficientPlane[] planes) {
        var mcuColumns = sourceWidth / (maxSamplingX * 8);
        for (var c = 0; c < components; c++) {
            var plane = planes[c];
            var data = plane.data;
            var h = samplingX[c];
            var v = samplingY[c];
            var width = mcuColumns * h;
            var cropColumns = cropX * h;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    var row = y + oy;
                    if (row >= plane.blocksHigh) {
                        continue;
                    }
                    for (var x = 0; x * 2 < width; x++) {
                        if (width - x - 1 >= plane.blocksWide) {
                            continue;
                        }
                        var first = plane.offset(x, row);
                        var second = plane.offset(width - x - 1, row);
                        for (var k = 0; k < 64; k += 2) {
                            var a = data[first + k];
                            var b = data[second + k];
                            data[first + k] = b;
                            data[second + k] = a;
                            a = data[first + k + 1];
                            b = data[second + k + 1];
                            data[first + k + 1] = (short) -b;
                            data[second + k + 1] = (short) -a;
                        }
                    }
                    if (cropColumns > 0) {
                        for (var x = 0; x < wide; x++) {
                            block(plane, x + cropColumns, row, plane, x, row, COPY);
                        }
                    }
                }
            }
        }
    }

    /**
     * Mirrors horizontally into separate planes.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void flipHorizontal(CoefficientPlane[] source, CoefficientPlane[] output) {
        var mcuColumns = sourceWidth / (maxSamplingX * 8);
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var width = mcuColumns * h;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    var row = y + oy;
                    for (var x = 0; x < wide; x++) {
                        if (cropColumns + x < width) {
                            block(source[c], width - cropColumns - x - 1, row + cropRows, output[c], x, row,
                                    MIRROR_COLUMNS);
                        } else {
                            block(source[c], x + cropColumns, row + cropRows, output[c], x, row, COPY);
                        }
                    }
                }
            }
        }
    }

    /**
     * Mirrors vertically.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void flipVertical(CoefficientPlane[] source, CoefficientPlane[] output) {
        var mcuRows = sourceHeight / (maxSamplingY * 8);
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var height = mcuRows * v;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                var mirrored = cropRows + y < height;
                for (var oy = 0; oy < v; oy++) {
                    for (var x = 0; x < wide; x++) {
                        if (mirrored) {
                            var sourceRow = height - cropRows - y - v + (v - oy - 1);
                            block(source[c], x + cropColumns, sourceRow, output[c], x, y + oy, MIRROR_ROWS);
                        } else {
                            block(source[c], x + cropColumns, y + oy + cropRows, output[c], x, y + oy, COPY);
                        }
                    }
                }
            }
        }
    }

    /**
     * Transposes.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void transpose(CoefficientPlane[] source, CoefficientPlane[] output) {
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    for (var x = 0; x < wide; x += h) {
                        for (var ox = 0; ox < h; ox++) {
                            block(source[c], y + oy + cropRows, x + ox + cropColumns, output[c], x + ox, y + oy,
                                    TRANSPOSE);
                        }
                    }
                }
            }
        }
    }

    /**
     * Rotates by 90 degrees clockwise: transposes, then mirrors horizontally.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void rotate90(CoefficientPlane[] source, CoefficientPlane[] output) {
        var mcuColumns = sourceHeight / (maxSamplingX * 8);
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var width = mcuColumns * h;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    for (var x = 0; x < wide; x += h) {
                        var mirrored = cropColumns + x < width;
                        for (var ox = 0; ox < h; ox++) {
                            if (mirrored) {
                                block(source[c], y + oy + cropRows, width - cropColumns - x - h + (h - ox - 1),
                                        output[c], x + ox, y + oy, TRANSPOSE_ODD_ROWS);
                            } else {
                                block(source[c], y + oy + cropRows, x + cropColumns + ox, output[c], x + ox, y + oy,
                                        TRANSPOSE);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Rotates by 270 degrees clockwise: mirrors horizontally, then transposes.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void rotate270(CoefficientPlane[] source, CoefficientPlane[] output) {
        var mcuRows = sourceWidth / (maxSamplingY * 8);
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var height = mcuRows * v;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    for (var x = 0; x < wide; x += h) {
                        for (var ox = 0; ox < h; ox++) {
                            var sourceRow = x + cropColumns + ox;
                            if (cropRows + y < height) {
                                block(source[c], height - cropRows - y - oy - 1, sourceRow, output[c], x + ox, y + oy,
                                        TRANSPOSE_ODD_COLUMNS);
                            } else {
                                block(source[c], y + oy + cropRows, sourceRow, output[c], x + ox, y + oy, TRANSPOSE);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Rotates by 180 degrees: mirrors vertically and horizontally.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void rotate180(CoefficientPlane[] source, CoefficientPlane[] output) {
        var mcuColumns = sourceWidth / (maxSamplingX * 8);
        var mcuRows = sourceHeight / (maxSamplingY * 8);
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var width = mcuColumns * h;
            var height = mcuRows * v;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                var mirroredRows = cropRows + y < height;
                for (var oy = 0; oy < v; oy++) {
                    var sourceRow = mirroredRows ? height - cropRows - y - v + (v - oy - 1) : y + cropRows + oy;
                    for (var x = 0; x < wide; x++) {
                        var mirroredColumns = cropColumns + x < width;
                        var sourceColumn = mirroredColumns ? width - cropColumns - x - 1 : cropColumns + x;
                        int operation;
                        if (mirroredRows) {
                            operation = mirroredColumns ? MIRROR_BOTH : MIRROR_ROWS;
                        } else {
                            operation = mirroredColumns ? MIRROR_COLUMNS : COPY;
                        }
                        block(source[c], sourceColumn, sourceRow, output[c], x, y + oy, operation);
                    }
                }
            }
        }
    }

    /**
     * Transposes across the anti-diagonal: rotates by 180 degrees, then transposes.
     *
     * @param source the source planes
     * @param output the output planes
     */
    private void transverse(CoefficientPlane[] source, CoefficientPlane[] output) {
        var mcuColumns = sourceHeight / (maxSamplingX * 8);
        var mcuRows = sourceWidth / (maxSamplingY * 8);
        for (var c = 0; c < components; c++) {
            var h = samplingX[c];
            var v = samplingY[c];
            var width = mcuColumns * h;
            var height = mcuRows * v;
            var cropColumns = cropX * h;
            var cropRows = cropY * v;
            var wide = blocksWide(c);
            var high = blocksHigh(c);
            for (var y = 0; y < high; y += v) {
                for (var oy = 0; oy < v; oy++) {
                    for (var x = 0; x < wide; x += h) {
                        var mirroredColumns = cropColumns + x < width;
                        for (var ox = 0; ox < h; ox++) {
                            var sourceRow = mirroredColumns ? width - cropColumns - x - h + (h - ox - 1)
                                    : x + cropColumns + ox;
                            if (cropRows + y < height) {
                                block(source[c], height - cropRows - y - oy - 1, sourceRow, output[c], x + ox, y + oy,
                                        mirroredColumns ? TRANSPOSE_BOTH : TRANSPOSE_ODD_COLUMNS);
                            } else {
                                block(source[c], y + oy + cropRows, sourceRow, output[c], x + ox, y + oy,
                                        mirroredColumns ? TRANSPOSE_ODD_ROWS : TRANSPOSE);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes one transformed block; a source block outside its grid reads as zeros and an output
     * block outside its grid is skipped. The source and output may be the same plane when the blocks
     * differ.
     *
     * @param source       the source plane
     * @param sourceColumn the source block column
     * @param sourceRow    the source block row
     * @param output       the output plane
     * @param column       the output block column
     * @param row          the output block row
     * @param operation    the block operation
     */
    static void block(CoefficientPlane source, int sourceColumn, int sourceRow, CoefficientPlane output,
                              int column, int row, int operation) {
        if (!output.contains(column, row)) {
            return;
        }
        var to = output.offset(column, row);
        var out = output.data;
        if (!source.contains(sourceColumn, sourceRow)) {
            Arrays.fill(out, to, to + 64, (short) 0);
            return;
        }
        var from = source.offset(sourceColumn, sourceRow);
        var in = source.data;
        switch (operation) {
            case COPY -> System.arraycopy(in, from, out, to, 64);
            case MIRROR_COLUMNS -> {
                for (var k = 0; k < 64; k += 2) {
                    out[to + k] = in[from + k];
                    out[to + k + 1] = (short) -in[from + k + 1];
                }
            }
            case MIRROR_ROWS -> {
                for (var i = 0; i < 8; i++) {
                    var negate = (i & 1) != 0;
                    for (var j = 0; j < 8; j++) {
                        var value = in[from + i * 8 + j];
                        out[to + i * 8 + j] = negate ? (short) -value : value;
                    }
                }
            }
            case MIRROR_BOTH -> {
                for (var i = 0; i < 8; i++) {
                    for (var j = 0; j < 8; j++) {
                        var value = in[from + i * 8 + j];
                        out[to + i * 8 + j] = ((i + j) & 1) != 0 ? (short) -value : value;
                    }
                }
            }
            default -> {
                for (var i = 0; i < 8; i++) {
                    for (var j = 0; j < 8; j++) {
                        var value = in[from + i * 8 + j];
                        var negate = switch (operation) {
                            case TRANSPOSE_ODD_ROWS -> (i & 1) != 0;
                            case TRANSPOSE_ODD_COLUMNS -> (j & 1) != 0;
                            case TRANSPOSE_BOTH -> ((i + j) & 1) != 0;
                            default -> false;
                        };
                        out[to + j * 8 + i] = negate ? (short) -value : value;
                    }
                }
            }
        }
    }
}
