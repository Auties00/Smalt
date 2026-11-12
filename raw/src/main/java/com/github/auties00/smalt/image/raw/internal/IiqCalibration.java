package com.github.auties00.smalt.image.raw.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the sensor calibration a Phase One IIQ file records for its back: the black level of each row and column,
 * the gains and level tables of the sensor's outputs and the gain grids.
 *
 * <p>A file holds one of two kinds of photosites. Those of the backs whose block has a black table per output
 * ({@link IiqBlock#ROW_BLACK}) lose their black level, {@code v - black + row[y] + column[x]}, the row number taken
 * from the sensor's left or right half and the column number from its upper or lower half. Those of the newer backs
 * ({@link IiqBlock#KEPT_ROW_BLACK}) keep theirs: the grids scale the stored value, black level included.
 *
 * <p>The backs of four outputs (the P40+ and P65+) scale each output by its own gain and then map it through the level
 * table of the sensor's gain stage: the levels each output gave at a set of exposures, which the table maps to the
 * highest level any output gave at the same exposure. The gain of an output is the ratio of the two, interpolated
 * linearly between the output's levels and held beyond them; both steps give whole numbers.
 *
 * <p>A response table (the Credo, IQ140 and IQ180) holds {@code n} levels and {@code n} gains (over 10000) for each of
 * the four outputs; each output scales a photosite by its gain at the photosite's level, interpolated linearly between
 * the levels and held beyond them, to a whole number.
 *
 * <p>The backs of two outputs side by side (the P30 and P45) scale the photosites of one half by the ratio of the
 * halves' gains, a linear function of the value and of the sensor's temperature, to a whole number: those of the
 * right half when the ratio is at least one, those of the left half by its inverse otherwise, so that no photosite
 * loses level. They then correct every photosite's value {@code v} by adding a cubic polynomial of it, to a whole
 * number.
 *
 * <p>A grid is a rectangle of the sensor, in sensor coordinates, with gains (over 32768) at the nodes of a lattice,
 * interpolated bilinearly; one value per node scales every photosite, two scale the red and the blue ones. The
 * photosites of a rectangle that overlaps an earlier one take the gains of the last.
 *
 * @implNote This implementation's rules were derived with Adobe DNG Converter, run as a black box, on CC0 sample
 * files and on copies of them whose block entries were renamed or changed and whose sensor data, or some of its rows,
 * was replaced by constant images and ramps. Values are computed in double precision and rounded; the converter's
 * rounding differs on values within about 0.01 of one half.
 */
// TODO: add the converter's seam correction of the backs with four outputs (the P40+, P65+, IQ140 and Credo samples;
//  their binned (Sensor+) files, the IQ180 samples and the two-output backs get none). Its seams are the edges of the
//  gain grid rectangles (the sensor columns 1856, 3696 and 5536 and the row 2748 on the P40+, IQ140 and Credo; 2272,
//  4528 and 6784 and 3372 on the P65+), not only the edges of the outputs. Measured on copies with constant synthetic
//  sensor data and every grid and output table disabled: a step d across one column seam (the right side's level
//  minus the left side's) is closed by tilting the two rectangles beside it, the left one's correction rising
//  linearly from 0 at its left edge to l at the seam and the right one's from l - d at the seam to 2l - d at its
//  right edge, every column beyond keeping 2l - d; l is about 2d/3 (8, 20, 28, 40, 60 and 70 units for steps of 15,
//  30, 45, 60, 89 and 104 units at a level of 2978), and a step of 4% or more is left open while the columns beyond
//  it move by d/3. A step on the rows of one half also corrects the other half, by an amount fading linearly from
//  the row seam to the image's top or bottom edge. The step is estimated per row and per column parity from the 8
//  photosites of that parity on each side within 16 columns, the largest and smallest dropped and the rest averaged,
//  but the correction is not the per-row step: a step on a contiguous block of rows gets none while one on rows
//  scattered over the seam gets one in proportion. On the samples the correction (the converter's photosites minus
//  Smalt's) is linear within each rectangle and jumps at their edges by up to about 1%, matching neither the
//  16-column trimmed-mean steps of the image there nor that rule, so the estimator of real images is unknown; plus
//  gains of up to 2% around bright areas on the P40+ that are not explained. A real file with every grid and table
//  disabled is still corrected by such a field (linear within each rectangle, fading to zero at the image's top and
//  bottom edges), and adding a constant d to one rectangle of it changes the correction by far less than d and not
//  linearly (doubling d leaves the median change as it was, negating d gives a quarter of the opposite change), so
//  the estimator weights or rejects image content and the constant-image rule above is only its limit
final class IiqCalibration {
    /**
     * The full-scale value of a grid's 16-bit gains.
     */
    private static final int GRID_UNIT = 32768;

    /**
     * The full-scale value of a response table's gains.
     */
    private static final double TABLE_UNIT = 10000;

    /**
     * The largest stored photosite.
     */
    private static final int WHITE = 65535;

    /**
     * Prevents instantiation.
     */
    private IiqCalibration() {
        throw new AssertionError();
    }

    /**
     * A rectangle of gains.
     *
     * @param left the first column, in sensor coordinates
     * @param top the first row
     * @param right the column after the last
     * @param bottom the row after the last
     * @param stepX the columns between nodes
     * @param stepY the rows between nodes
     * @param nodesX the nodes of a row
     * @param nodesY the rows of nodes
     * @param perNode the values per node
     * @param values the values, row by row
     */
    record Grid(int left, int top, int right, int bottom, int stepX, int stepY, int nodesX, int nodesY, int perNode,
                float[] values) {
        /**
         * Parses a grid's entry.
         *
         * @param data the entry
         * @param perNode the values per node
         * @return the grid, or {@code null} when the entry's size does not fit its header
         */
        static Grid parse(byte[] data, int perNode) {
            if (data.length < 16) {
                return null;
            }
            var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int left = buffer.getChar(0), top = buffer.getChar(2), width = buffer.getChar(4), height = buffer.getChar(6);
            int stepX = buffer.getChar(8), stepY = buffer.getChar(10);
            if (stepX == 0 || stepY == 0 || width == 0 || height == 0) {
                return null;
            }
            var nodesX = (width + stepX - 1) / stepX;
            var nodesY = (height + stepY - 1) / stepY;
            var count = (long) nodesX * nodesY * perNode;
            if (count * 2 + 16 > data.length) {
                return null;
            }
            var values = new float[(int) count];
            for (var index = 0; index < values.length; index++) {
                values[index] = buffer.getChar(16 + index * 2) / (float) GRID_UNIT;
            }
            return new Grid(left, top, left + width, top + height, stepX, stepY, nodesX, nodesY, perNode, values);
        }

        /**
         * Fills a row's gains from this grid, leaving the columns outside its rectangle.
         *
         * @param y the sensor row, inside the rectangle
         * @param lane the value of each node to use
         * @param gains the gains by sensor column
         * @param from the first column to fill
         * @param step the columns between the filled ones
         */
        void row(int y, int lane, double[] gains, int from, int step) {
            var position = (y - top) / (double) stepY;
            var above = Math.min((int) position, nodesY - 1);
            var below = Math.min(above + 1, nodesY - 1);
            var fractionY = position - above;
            var start = Math.max(left, from);
            if ((start - from) % step != 0) {
                start += step - (start - from) % step;
            }
            var end = Math.min(right, gains.length);
            for (var x = start; x < end; x += step) {
                var across = (x - left) / (double) stepX;
                var first = Math.min((int) across, nodesX - 1);
                var second = Math.min(first + 1, nodesX - 1);
                var fractionX = across - first;
                var a = value(above, first, lane) * (1 - fractionX) + value(above, second, lane) * fractionX;
                var b = value(below, first, lane) * (1 - fractionX) + value(below, second, lane) * fractionX;
                gains[x] = a * (1 - fractionY) + b * fractionY;
            }
        }

        /**
         * {@return a node's gain}
         *
         * @param row the row of nodes
         * @param column the node of the row
         * @param lane the value of the node
         */
        private double value(int row, int column, int lane) {
            return values[(row * nodesX + column) * perNode + lane];
        }
    }

    /**
     * The response table of the sensor's four outputs.
     *
     * @param levels the levels, ascending
     * @param gains the gains of each output: upper left, upper right, lower left, lower right
     */
    record Response(int[] levels, double[][] gains) {
        /**
         * Parses a table's entry: the levels, the gains of each output, as 32-bit integers, then their count.
         *
         * @param data the entry
         * @return the table, or {@code null} when its size does not fit
         */
        static Response parse(byte[] data) {
            var numbers = data.length / 4;
            if (numbers < 6 || (numbers - 1) % 5 != 0) {
                return null;
            }
            var size = (numbers - 1) / 5;
            var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            var levels = new int[size];
            var gains = new double[4][size];
            for (var index = 0; index < size; index++) {
                levels[index] = buffer.getInt(index * 4);
                if (index > 0 && levels[index] <= levels[index - 1]) {
                    return null;
                }
                for (var output = 0; output < 4; output++) {
                    gains[output][index] = buffer.getInt((size * (output + 1) + index) * 4) / TABLE_UNIT;
                }
            }
            return new Response(levels, gains);
        }

        /**
         * {@return an output's gain at a level, interpolated between the table's levels and held beyond them}
         *
         * @param output the index of the output, 0 to 3
         * @param level the photosite level, above black
         */
        double gain(int output, double level) {
            var row = gains[output];
            if (level <= levels[0]) {
                return row[0];
            }
            var last = levels.length - 1;
            if (level >= levels[last]) {
                return row[last];
            }
            var low = 0;
            while (level >= levels[low + 1]) {
                low++;
            }
            var fraction = (level - levels[low]) / (levels[low + 1] - levels[low]);
            return row[low] + (row[low + 1] - row[low]) * fraction;
        }
    }

    /**
     * The level table of the sensor's four outputs: the levels each output gave at a set of exposures, which this
     * table maps to the highest level any output gave at the same exposure.
     *
     * @param levels the levels of each output, ascending: upper left, upper right, lower left, lower right
     * @param gains the gain at each output's levels
     */
    record LevelMap(int[][] levels, double[][] gains) {
        /**
         * Parses a table's entry: the levels of each output, as 32-bit integers, then their count.
         *
         * @param data the entry
         * @return the table, or {@code null} when its size does not fit or an output's levels do not ascend
         */
        static LevelMap parse(byte[] data) {
            var numbers = data.length / 4;
            if (numbers < 9 || (numbers - 1) % 4 != 0) {
                return null;
            }
            var size = (numbers - 1) / 4;
            var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            var levels = new int[4][size];
            for (var output = 0; output < 4; output++) {
                for (var index = 0; index < size; index++) {
                    var level = buffer.getInt((output * size + index) * 4);
                    if (level <= 0 || (index > 0 && level <= levels[output][index - 1])) {
                        return null;
                    }
                    levels[output][index] = level;
                }
            }
            var gains = new double[4][size];
            for (var index = 0; index < size; index++) {
                var target = 0;
                for (var output = 0; output < 4; output++) {
                    target = Math.max(target, levels[output][index]);
                }
                for (var output = 0; output < 4; output++) {
                    gains[output][index] = target / (double) levels[output][index];
                }
            }
            return new LevelMap(levels, gains);
        }

        /**
         * {@return an output's gain at a level, interpolated between the output's levels and held beyond them}
         *
         * @param output the index of the output, 0 to 3
         * @param level the photosite level, above black
         */
        double gain(int output, double level) {
            var knots = levels[output];
            var row = gains[output];
            if (level <= knots[0]) {
                return row[0];
            }
            var last = knots.length - 1;
            if (level >= knots[last]) {
                return row[last];
            }
            var low = 0;
            while (level >= knots[low + 1]) {
                low++;
            }
            var fraction = (level - knots[low]) / (knots[low + 1] - knots[low]);
            return row[low] + (row[low + 1] - row[low]) * fraction;
        }
    }

    /**
     * Everything this class applies to a file's photosites.
     *
     * @param width the sensor's width
     * @param height the sensor's height
     * @param left the first column of the image
     * @param top the first row of the image
     * @param right the column after the image's last
     * @param bottom the row after the image's last
     * @param firstRed the row parity of the red photosites, in sensor coordinates
     * @param firstRedColumn the column parity of the red photosites
     * @param split the column where the outputs meet, or {@code -1} for a single one
     * @param splitY the row where the outputs meet, or {@code -1}
     * @param black the black level to subtract, or {@code 0} to keep it
     * @param rowBlack the numbers per row and output half, or {@code null}
     * @param rowBlackPairs whether the rows hold one number per half
     * @param columnBlack the numbers per column and output half, or {@code null}
     * @param response the response table, or {@code null}
     * @param polynomial the coefficients of the correction every photosite's value adds to itself, or {@code null}
     * @param halfGain the constant and the slope of the ratio of the right half's gain to the left half's, which
     *                 scales up the right half's photosites when it is at least one and the left half's by its inverse
     *                 otherwise, or {@code null}
     * @param outputGains the gain of each of the four outputs, or {@code null}
     * @param levelMap the level table, or {@code null}
     * @param levelGrids the grids that scale every photosite
     * @param colorGrids the grids that scale the red and blue photosites
     */
    record Plan(int width, int height, int left, int top, int right, int bottom, int firstRed, int firstRedColumn,
                int split, int splitY, int black, short[] rowBlack, boolean rowBlackPairs, short[] columnBlack,
                Response response, double[] polynomial, double[] halfGain, double[] outputGains, LevelMap levelMap,
                List<Grid> levelGrids, List<Grid> colorGrids) {
    }

    /**
     * The buffers a thread calibrates rows with.
     *
     * @param values the row's values
     * @param gains the row's gains, zero where none applies
     */
    record Scratch(double[] values, double[] gains) {
        /**
         * Creates the buffers of a sensor's rows.
         *
         * @param width the sensor's width
         */
        Scratch(int width) {
            this(new double[width], new double[width]);
        }
    }

    /**
     * Calibrates a row and clamps it to the 16-bit range. The photosites of the rows outside the image only lose their
     * black level; those of the image's rows outside its columns also take the grids, not the output corrections.
     *
     * @param plan what to apply
     * @param samples the row's photosites, which may exceed the 16-bit range
     * @param out the calibrated photosites of the sensor
     * @param y the row
     * @param scratch the buffers to use
     */
    static void row(Plan plan, int[] samples, short[] out, int y, Scratch scratch) {
        var width = plan.width();
        var base = y * width;
        var values = scratch.values();
        for (var x = 0; x < width; x++) {
            values[x] = samples[x] - plan.black() + black(plan, x, y);
        }
        if (y >= plan.top() && y < plan.bottom()) {
            levels(plan, values, y);
            if (plan.response() != null) {
                response(plan, values, y);
            }
            var gains = scratch.gains();
            for (var grid : plan.levelGrids()) {
                if (y >= grid.top() && y < grid.bottom()) {
                    grid.row(y, 0, gains, 0, 1);
                }
            }
            multiply(values, gains, 0, width, 1);
            colors(plan, values, gains, y);
        }
        for (var x = 0; x < width; x++) {
            var value = Math.rint(values[x]);
            out[base + x] = (short) (value < 0 ? 0 : value > WHITE ? WHITE : (int) value);
        }
    }

    /**
     * Applies the gain between the halves, the polynomial, the gains of the sensor's outputs and the level table to a
     * row. The gain between the halves, the polynomial and, with a level table, the output's gain and the table's each
     * give a whole number.
     *
     * @param plan what to apply
     * @param values the row's values
     * @param y the row
     */
    private static void levels(Plan plan, double[] values, int y) {
        var polynomial = plan.polynomial();
        var half = plan.halfGain();
        var outputs = plan.outputGains();
        var map = plan.levelMap();
        if (polynomial == null && half == null && outputs == null && map == null) {
            return;
        }
        var lower = plan.splitY() >= 0 && y >= plan.splitY();
        for (var x = plan.left(); x < plan.right() && x < values.length; x++) {
            var value = values[x];
            var right = plan.split() >= 0 && x >= plan.split();
            if (half != null) {
                var gain = half[0] + half[1] * value;
                if (gain >= 1 ? right : !right) {
                    value = gain >= 1 ? value * gain : value / gain;
                }
                value = Math.rint(value);
            }
            if (polynomial != null) {
                value += polynomial[0] + value * (polynomial[1] + value * (polynomial[2] + value * polynomial[3]));
                value = Math.rint(value);
            }
            var output = (lower ? 2 : 0) + (right ? 1 : 0);
            if (outputs != null) {
                value *= outputs[output];
            }
            if (map != null) {
                value = Math.rint(value);
                value = Math.rint(value * map.gain(output, value));
            }
            values[x] = value;
        }
    }

    /**
     * Applies the grids of the row's red or blue photosites.
     *
     * @param plan what to apply
     * @param values the row's values
     * @param gains a row of gains to fill
     * @param y the row
     */
    private static void colors(Plan plan, double[] values, double[] gains, int y) {
        if (plan.colorGrids().isEmpty()) {
            return;
        }
        var redRow = ((y ^ plan.firstRed()) & 1) == 0;
        var lane = redRow ? 0 : 1;
        var from = (redRow ? plan.firstRedColumn() : plan.firstRedColumn() ^ 1) & 1;
        for (var grid : plan.colorGrids()) {
            if (y >= grid.top() && y < grid.bottom()) {
                grid.row(y, lane, gains, from, 2);
            }
        }
        multiply(values, gains, from, values.length, 2);
    }

    /**
     * Multiplies values by gains, skipping the columns without one.
     *
     * @param values the row of values, multiplied in place
     * @param gains the gains, zero where none applies
     * @param from the first column
     * @param end the column after the last
     * @param step the columns between the multiplied ones
     */
    private static void multiply(double[] values, double[] gains, int from, int end, int step) {
        for (var x = from; x < end; x += step) {
            if (gains[x] != 0) {
                values[x] *= gains[x];
                gains[x] = 0;
            }
        }
    }

    /**
     * {@return the black adjustment of a photosite}
     *
     * @param plan what to apply
     * @param x the column
     * @param y the row
     */
    private static int black(Plan plan, int x, int y) {
        var adjust = 0;
        var row = plan.rowBlack();
        if (row != null) {
            adjust += plan.rowBlackPairs() ? row[y * 2 + (plan.split() >= 0 && x >= plan.split() ? 1 : 0)] : row[y];
        }
        var column = plan.columnBlack();
        if (column != null) {
            adjust += column[x * 2 + (plan.splitY() >= 0 && y >= plan.splitY() ? 1 : 0)];
        }
        return adjust;
    }

    /**
     * Applies the response table to a row.
     *
     * @param plan what to apply
     * @param values the row's values
     * @param y the row
     */
    private static void response(Plan plan, double[] values, int y) {
        var response = plan.response();
        var lower = y >= plan.splitY() ? 2 : 0;
        for (var x = plan.left(); x < plan.right() && x < values.length; x++) {
            var level = values[x];
            values[x] = Math.rint(level * response.gain(lower + (x >= plan.split() ? 1 : 0), level));
        }
    }

    /**
     * {@return a new empty grid list}
     */
    static List<Grid> grids() {
        return new ArrayList<>();
    }
}
