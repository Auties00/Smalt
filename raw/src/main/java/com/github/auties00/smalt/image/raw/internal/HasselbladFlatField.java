/* Ports the Adobe DNG SDK 1.4 (source/dng_gain_map.cpp: dng_gain_map_interpolator and
   dng_opcode_GainMap::ProcessArea). Copyright 2008-2009 Adobe Systems Incorporated; DNG SDK License Agreement.
   Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Reads and applies the sensor calibration a Hasselblad CCD camera (H3D, H4D) records in its 3FR files: a
 * correction of the charge each photosite takes from its vertical neighbors, a flat field that evens the color
 * of the image across the sensor, and a list of defective columns.
 *
 * <p>The calibration is the {@code GAIN} block of maker note tag {@code 0x19}, a little-endian structure whose
 * version is 20 (H3D) or 30 (H4D). Its header holds the sensor's size at byte 44 and, at byte 144, the offset of
 * a grid of the sensor's response to an even light; a table of one record per sensor row ends where the grid
 * starts:
 * {@snippet :
 * 32       u16 version                20 or 30
 * 44       u16 width, u16 height      of the sensor
 * 80       u16 shift, u16 red, u16 green, u16 blue    the gains of version 30, as in the CMOS cameras' block
 * 108      u32 defects                the offset in the block of the list of defective columns
 * 144      u32 grid                   the offset of the grid in the block
 * grid - 20 * height                  per sensor row: s16 above, below (even columns), above, below (odd
 *                                     columns), six more s16
 * grid     u16 1, u16 cell width, u16 cell height, u16 columns, u16 rows, eleven more u16
 * grid+32  per cell, row by row: u16 red, green (even rows), green (odd rows), blue
 * defects  u16 count, then per entry: u16 column, u16 kind (6 or 7 for a whole column), two more u16
 * }
 *
 * <p>A photosite {@code v} at column {@code x} and row {@code y}, whose neighbors in its column are {@code u}
 * above and {@code d} below, all relative to the black level, is corrected to
 * {@code (v + a * u / 16384 + b * d / 16384) * f(x, y)}, rounded, where {@code a} and {@code b} are the row's
 * coefficients for the photosite's column parity and {@code f} its color's flat field. The grid's cells cover
 * the sensor's center, and each cell's value for a color is {@code c / r}, the reciprocal of the cell's response
 * {@code r = q / m}, the color's value {@code q} over the mean {@code m} of the cell's four values, times the
 * response {@code c} interpolated at the sensor's center; the flat field interpolates these values bilinearly
 * between the cells' centers, holding the nearest one beyond them, as {@link GainMap} does. The values are
 * normalized to the white level 65535 and computed in single precision, the correction capped at that white
 * level. A grid of the same four values everywhere is no correction. The flat field is applied in tiles across
 * the sensor ({@link Table#tileWidth}), and each row of a tile interpolates it from the left edge of the tile's
 * buffer, which reaches four columns left of the tile per listed defective column ({@link Table#margin}).
 *
 * <p>The listed columns are then interpolated by {@link HasselbladColumns}, from the corrected photosites relative
 * to the black level; entries of other kinds, and lists that do not end before the row table, are ignored. The
 * photosites of version 20 keep black level 0 and have white level {@link HasselbladCalibration#FILE_WHITE}.
 * Those of version 30, whose files store signed photosites, are then re-encoded by {@link HasselbladCalibration}
 * to black level {@link HasselbladCalibration#MINIMUM_BLACK} and white level 65535 with the header's gains, from
 * the converter's white level of {@value #SIGNED_FILE_WHITE} above the black level.
 *
 * @implNote This implementation's rule was derived with Adobe DNG Converter, run as a black box on copies of the
 * CC0 H3D and H4D samples whose photosites were replaced by constant, impulse and patterned images and whose
 * block was changed: a constant image with a grid of equal values shows the row coefficients alone, impulses
 * show the coefficients of the neighbors, and grids with single changed cells show the interpolation, the cell
 * centers and the normalization. Its interpolation and arithmetic are the Adobe DNG SDK's gain map opcode,
 * whose single-precision rounding the converter's photosites follow. The tiles were measured on noise copies
 * whose block listed zero to four defective columns: the photosites the restarted interpolation rounds the
 * other way place the tiles and the start of their rows exactly. The rule reproduces every photosite of the H3D
 * sample, its four defective columns included, and of noise copies of the H3D and H4D samples with zero to four
 * listed columns. The list's entries name the column first: the converter changes exactly the listed columns,
 * on every row, when the copies' photosites are noise and the grid and row records are neutral.
 */
final class HasselbladFlatField {
    /**
     * The versions of the block this class applies.
     */
    static final int VERSION_H3D = 20, VERSION_H4D = 30;

    /**
     * The white level, above the black level, that Adobe DNG Converter gives the signed photosites of a version
     * 30 file: 96% of their stated 32767.
     */
    static final int SIGNED_FILE_WHITE = 31456;

    /**
     * The number of bytes of the block's header this class reads.
     */
    static final int HEADER = 148;

    /**
     * The offset in the block's header of the offset of the list of defective columns.
     */
    static final int DEFECTS = 108;

    /**
     * The number of bytes of an entry of the list of defective columns.
     */
    static final int DEFECT = 8;

    /**
     * The kinds of the entries of the list of defective columns that name a whole column: those the H3D sample
     * lists, all of whose photosites Adobe DNG Converter interpolates.
     */
    private static final int COLUMN = 6, COLUMN_ALTERNATE = 7;

    /**
     * The denominator of the row coefficients.
     */
    private static final double COEFFICIENT_SCALE = 16384;

    /**
     * The number of bytes of a row's record.
     */
    private static final int RECORD = 20;

    /**
     * The number of bytes of the grid's header.
     */
    private static final int GRID_HEADER = 32;

    /**
     * The offset in the block's header of the sensor's width, its height following it.
     */
    private static final int SIZE = 44;

    /**
     * The offset in the block's header of the grid's offset.
     */
    private static final int GRID = 144;

    /**
     * The block's version.
     */
    final int version;

    /**
     * The sensor's width.
     */
    final int width;

    /**
     * The sensor's height.
     */
    final int height;

    /**
     * The position of the block in the file.
     */
    private final long position;

    /**
     * The offset of the grid in the block.
     */
    private final int grid;

    /**
     * The width of a cell.
     */
    final int cellWidth;

    /**
     * The height of a cell.
     */
    final int cellHeight;

    /**
     * The number of cells across.
     */
    final int columns;

    /**
     * The number of cells down.
     */
    final int rows;

    /**
     * The red, green and blue gains of a version 30 block, or {@code null} for version 20.
     */
    final double[] gains;

    /**
     * The defective columns, in the order the block lists them, without repetitions.
     */
    final int[] defects;

    /**
     * Creates the description of a calibration block.
     *
     * @param version the block's version
     * @param width the sensor's width
     * @param height the sensor's height
     * @param position the position of the block in the file
     * @param grid the offset of the grid in the block
     * @param cellWidth the width of a cell
     * @param cellHeight the height of a cell
     * @param columns the number of cells across
     * @param rows the number of cells down
     * @param gains the gains of a version 30 block, or {@code null}
     * @param defects the defective columns
     */
    private HasselbladFlatField(int version, int width, int height, long position, int grid, int cellWidth,
                                int cellHeight, int columns, int rows, double[] gains, int[] defects) {
        this.version = version;
        this.width = width;
        this.height = height;
        this.position = position;
        this.grid = grid;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.columns = columns;
        this.rows = rows;
        this.gains = gains;
        this.defects = defects;
    }

    /**
     * Reads the description of the calibration block of a CCD camera's 3FR file.
     *
     * @param tree the file's directories
     * @param sensor the sensor, whose size, filter array and levels the parser set
     * @param signed whether the file's photosites are signed
     * @return the description, or {@code null} when the file records no calibration this class applies: no
     * maker note or block, another version, a block whose sensor size, grid or table do not fit the sensor or
     * the block, a version whose photosites are not signed as it requires, or a filter array other than red,
     * green, green, blue
     */
    static HasselbladFlatField read(TiffTree tree, Sensor sensor, boolean signed) {
        var note = HasselbladCalibration.note(tree);
        if (note == null || !"RGGB".equals(String.valueOf(sensor.cfa))) {
            return null;
        }
        var length = note.valueCount(HasselbladCalibration.TAG);
        if (length < HEADER || length > Integer.MAX_VALUE) {
            return null;
        }
        var reader = tree.first.reader();
        var position = note.valuePosition(HasselbladCalibration.TAG);
        var header = new byte[HEADER];
        if (reader.readAvailable(position, header, 0, HEADER) < HEADER) {
            return null;
        }
        var grid = u32(header, GRID);
        if (grid > length - GRID_HEADER) {
            return null;
        }
        var gridHeader = new byte[GRID_HEADER];
        if (reader.readAvailable(position + grid, gridHeader, 0, GRID_HEADER) < GRID_HEADER) {
            return null;
        }
        var field = parse(header, gridHeader, position, length, sensor.width, sensor.height, signed);
        if (field == null) {
            return null;
        }
        var list = u32(header, DEFECTS);
        var end = field.grid - (long) RECORD * field.height;
        var count = new byte[2];
        if (list < HEADER || list + 2 > end || reader.readAvailable(position + list, count, 0, 2) < 2) {
            return field;
        }
        var entries = u16(count, 0);
        if (entries == 0 || list + 2 + (long) DEFECT * entries > end) {
            return field;
        }
        var data = new byte[DEFECT * entries];
        if (reader.readAvailable(position + list + 2, data, 0, data.length) < data.length) {
            return field;
        }
        return field.withDefects(defects(data, entries, field.width));
    }

    /**
     * Returns the defective columns of the entries of a list.
     *
     * @param entries the entries, {@value #DEFECT} bytes each
     * @param count the number of entries
     * @param width the sensor's width
     * @return the columns of the entries that name a whole column within the sensor, in their order, without
     * repetitions
     */
    static int[] defects(byte[] entries, int count, int width) {
        var columns = new int[count];
        var found = 0;
        for (var entry = 0; entry < count; entry++) {
            var column = u16(entries, DEFECT * entry);
            var kind = u16(entries, DEFECT * entry + 2);
            var repeated = false;
            for (var index = 0; index < found; index++) {
                repeated |= columns[index] == column;
            }
            if ((kind == COLUMN || kind == COLUMN_ALTERNATE) && column < width && !repeated) {
                columns[found++] = column;
            }
        }
        return Arrays.copyOf(columns, found);
    }

    /**
     * Returns this description with a list of defective columns.
     *
     * @param columns the defective columns
     * @return the description
     */
    HasselbladFlatField withDefects(int[] columns) {
        return new HasselbladFlatField(version, width, height, position, grid, cellWidth, cellHeight, this.columns,
                rows, gains, columns);
    }

    /**
     * Parses the headers of a calibration block and of its grid.
     *
     * @param header the block's first {@value #HEADER} bytes
     * @param gridHeader the grid's first 32 bytes
     * @param position the position of the block in the file
     * @param length the block's length in bytes
     * @param sensorWidth the width of the sensor's stored image
     * @param sensorHeight the height of the sensor's stored image
     * @param signed whether the file's photosites are signed
     * @return the description, or {@code null} when the block is not a calibration this class applies to the
     * sensor
     */
    static HasselbladFlatField parse(byte[] header, byte[] gridHeader, long position, long length, int sensorWidth,
                                     int sensorHeight, boolean signed) {
        var version = HasselbladCalibration.version(header);
        if (header.length < HEADER || gridHeader.length < GRID_HEADER
                || (version == VERSION_H3D ? signed : version != VERSION_H4D || !signed)) {
            return null;
        }
        var width = u16(header, SIZE);
        var height = u16(header, SIZE + 2);
        var grid = u32(header, GRID);
        if (width != sensorWidth || height != sensorHeight || height < 2 || grid < (long) RECORD * height
                || grid > length - GRID_HEADER) {
            return null;
        }
        var cellWidth = u16(gridHeader, 2);
        var cellHeight = u16(gridHeader, 4);
        var columns = u16(gridHeader, 6);
        var rows = u16(gridHeader, 8);
        if (u16(gridHeader, 0) != 1 || cellWidth < 1 || cellHeight < 1 || columns < 2 || rows < 2
                || (long) columns * cellWidth > width || (long) rows * cellHeight > height
                || grid + GRID_HEADER + 8L * columns * rows > length) {
            return null;
        }
        double[] gains = null;
        if (version == VERSION_H4D) {
            gains = HasselbladCalibration.gains(header);
            if (gains == null) {
                return null;
            }
        }
        return new HasselbladFlatField(version, width, height, position, (int) grid, cellWidth, cellHeight, columns,
                rows, gains, new int[0]);
    }

    /**
     * Returns a reader that calibrates the photosites another reader reads.
     *
     * @param inner the reader of the file's photosites
     * @param source the reader of the file holding the block
     * @param black the black level of the file's photosites
     * @param cfa the color filter array, red, green, green, blue
     * @return the calibrating reader, whose photosites have the levels the class description states and whose
     * defective columns are interpolated
     */
    Sensor.Reader apply(Sensor.Reader inner, TiffReader source, int black, CfaPattern cfa) {
        var recoding = gains == null ? null : new HasselbladCalibration(black, black + SIGNED_FILE_WHITE,
                HasselbladCalibration.blackOf(0, SIGNED_FILE_WHITE), gains);
        return new Sensor.Reader() {
            @Override
            public void read(SensorPlane plane, MediaLimits limits) {
                read(plane, limits, true);
            }

            @Override
            public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
                inner.read(plane, limits, corrections);
                var table = Table.read(HasselbladFlatField.this, source, limits);
                if (table != null) {
                    table.calibrate(plane, black, defects);
                    if (recoding != null) {
                        recoding.calibrate(plane, cfa);
                    }
                }
            }
        };
    }

    /**
     * The row coefficients and the flat field of a block.
     *
     * @param field the block's description
     * @param above the coefficient of each row and column parity for the neighbor above, divided by 16384
     * @param below the coefficient of each row and column parity for the neighbor below, divided by 16384
     * @param entries the flat field's value at each cell's center, for each filter array position (row parity
     *                times 2 plus column parity), normalized
     */
    record Table(HasselbladFlatField field, float[] above, float[] below, float[][] entries) {
        /**
         * The reciprocal of the white level of the normalized photosites.
         */
        private static final float NORMALIZATION = 1f / 65535f;

        /**
         * The value, above the black level, of the saturated photosites of a version 30 file: the largest value
         * of the normalized photosites, for the photosite and for the correction of its vertical neighbors.
         */
        static final int SATURATED = 65535;

        /**
         * The largest width of the tiles Adobe DNG Converter applies the flat field in when the block lists fewer
         * than two defective columns. The tiles measured across 7262 photosites (25 tiles) and 9044 (31 tiles) place
         * it between 292 and 301.
         */
        static final int TILE_LIMIT = 300;

        /**
         * The largest width of the tiles Adobe DNG Converter applies the flat field in when the block lists two or
         * more defective columns. The tiles measured across 7262 photosites (30 tiles, with two to four columns)
         * place it between 243 and 250.
         */
        static final int TILE_LIMIT_DEFECTS = 245;

        /**
         * The number of columns, per listed defective column, that the buffer of a tile reaches left of it: the
         * padding the Adobe DNG SDK gives the source area of each fix of a bad rectangle. Each row of a tile
         * interpolates the flat field from the buffer's left edge.
         */
        static final int TILE_MARGIN = 4;

        /**
         * Reads the table of a block.
         *
         * @param field the block's description
         * @param source the reader of the file
         * @param limits the resource bounds
         * @return the table, or {@code null} when the block is truncated or a grid value is zero
         */
        static Table read(HasselbladFlatField field, TiffReader source, MediaLimits limits) {
            var height = field.height;
            var cells = field.columns * field.rows;
            var length = RECORD * height + GRID_HEADER + 8 * cells;
            LimitChecks.checkAllocation(limits, length + 16L * cells + 8L * height);
            var data = new byte[length];
            var start = field.position + field.grid - (long) RECORD * height;
            if (source.readAvailable(start, data, 0, length) < length) {
                return null;
            }
            return of(field, data, RECORD * height + GRID_HEADER);
        }

        /**
         * Builds a table from the bytes of a block's row records followed by its grid.
         *
         * <p>A cell's value for a color is the reciprocal of its response {@code q / m}, the cell's value
         * {@code q} for the color over the mean {@code m} of its four values, times the response interpolated at
         * the sensor's center, in single precision.
         *
         * @param field the block's description
         * @param data the row records, the grid's header and the grid's cells
         * @param cellsOffset the offset of the grid's cells in {@code data}
         * @return the table, or {@code null} when a grid value is zero
         */
        static Table of(HasselbladFlatField field, byte[] data, int cellsOffset) {
            var height = field.height;
            var above = new float[2 * height];
            var below = new float[2 * height];
            for (var y = 0; y < height; y++) {
                for (var parity = 0; parity < 2; parity++) {
                    above[2 * y + parity] = (short) u16(data, RECORD * y + 4 * parity) / (float) COEFFICIENT_SCALE;
                    below[2 * y + parity] = (short) u16(data, RECORD * y + 4 * parity + 2) / (float) COEFFICIENT_SCALE;
                }
            }
            var cells = field.columns * field.rows;
            var responses = new float[4][cells];
            for (var cell = 0; cell < cells; cell++) {
                var at = cellsOffset + 8 * cell;
                var sum = 0;
                for (var position = 0; position < 4; position++) {
                    var value = u16(data, at + 2 * position);
                    if (value == 0) {
                        return null;
                    }
                    sum += value;
                }
                var mean = sum / 4f;
                for (var position = 0; position < 4; position++) {
                    responses[position][cell] = u16(data, at + 2 * position) / mean;
                }
            }
            var entries = new float[4][cells];
            var geometry = new GainMap(field, null);
            for (var position = 0; position < 4; position++) {
                var center = geometry.center(responses[position]);
                for (var cell = 0; cell < cells; cell++) {
                    entries[position][cell] = center / responses[position][cell];
                }
            }
            return new Table(field, above, below, entries);
        }

        /**
         * Returns the corrected value of a photosite.
         *
         * @param x the photosite's column
         * @param y the photosite's row
         * @param value the photosite's value, relative to the black level
         * @param up the value of the photosite above it in its column, relative to the black level
         * @param down the value of the photosite below it in its column, relative to the black level
         * @return the corrected value, relative to the black level, unrounded
         */
        double correct(int x, int y, double value, double up, double down) {
            var parity = x & 1;
            var map = new GainMap(field, entries[(y & 1) * 2 + parity]);
            map.start(y, interpolationStart(x, tileWidth(field), margin(field)));
            map.advance(x);
            return correct(map.value(), normalized(value), normalized(up), normalized(down),
                    above[2 * y + parity], below[2 * y + parity]);
        }

        /**
         * Returns the width of the tiles Adobe DNG Converter applies the flat field in: the widths of the fewest
         * tiles no wider than {@value #TILE_LIMIT} photosites, or {@value #TILE_LIMIT_DEFECTS} when the block lists
         * two or more defective columns, that cover a row, made as equal as possible.
         *
         * @param field the block's description
         * @return the tiles' width
         */
        static int tileWidth(HasselbladFlatField field) {
            // TODO: the converter's rule for the tiles' width is not known: the two limits are fitted to the tiles
            //  measured across 7262 and 9044 photosites, and other sensor widths may be tiled otherwise
            var limit = field.defects.length < 2 ? TILE_LIMIT : TILE_LIMIT_DEFECTS;
            var count = Math.max(1, Math.ceilDiv(field.width, limit));
            return Math.max(1, Math.ceilDiv(field.width, count));
        }

        /**
         * Returns the number of columns the buffer of a tile reaches left of it: {@value #TILE_MARGIN} per listed
         * defective column.
         *
         * @param field the block's description
         * @return the number of columns
         */
        static int margin(HasselbladFlatField field) {
            return (int) Math.min((long) TILE_MARGIN * field.defects.length, field.width);
        }

        /**
         * Returns the column at which the interpolation of a photosite's tile row starts: the first column of the
         * photosite's column parity at least {@code margin} columns left of its tile, or the parity's first column
         * when that is left of the sensor.
         *
         * @param x the photosite's column
         * @param tileWidth the tiles' width
         * @param margin the number of columns the tile's buffer reaches left of it
         * @return the column
         */
        static int interpolationStart(int x, int tileWidth, int margin) {
            var parity = x & 1;
            var start = x / tileWidth * tileWidth - margin;
            start += (start ^ parity) & 1;
            return Math.max(start, parity);
        }

        /**
         * Returns the corrected value of a photosite from its normalized value and those of its neighbors.
         *
         * @param gain the flat field's gain at the photosite
         * @param value the photosite's normalized value
         * @param up the normalized value of the photosite above it in its column
         * @param down the normalized value of the photosite below it in its column
         * @param a the row's coefficient of the neighbor above
         * @param b the row's coefficient of the neighbor below
         * @return the corrected value, relative to the black level, unrounded
         */
        private static double correct(float gain, float value, float up, float down, float a, float b) {
            var linear = value + a * up + b * down;
            return Math.min(linear * gain, 1f) * 65535f;
        }

        /**
         * Returns a photosite's value relative to the black level, normalized to the white level 65535; a value
         * below the black level is zero.
         *
         * @param value the value relative to the black level
         * @return the normalized value
         */
        private static float normalized(double value) {
            return (float) Math.max(0, value) * NORMALIZATION;
        }

        /**
         * Corrects the photosites of a plane in place.
         *
         * @param plane the plane, of the sensor's size, holding the file's photosites
         * @param black the black level of the file's photosites
         */
        void correct(SensorPlane plane, int black) {
            var width = plane.width;
            var height = plane.height;
            if (width != field.width || height != field.height || height < 2) {
                return;
            }
            var values = plane.integers;
            var tileWidth = tileWidth(field);
            var margin = margin(field);
            var bands = ParallelBands.bands(height, (long) width * height);
            var edges = new short[2 * bands][];
            for (var band = 0; band < bands; band++) {
                var first = ParallelBands.start(band, bands, height);
                var last = ParallelBands.start(band + 1, bands, height) - 1;
                edges[2 * band] = copy(values, width, first > 0 ? first - 1 : 1, new short[width]);
                edges[2 * band + 1] = copy(values, width, last < height - 1 ? last + 1 : height - 2, new short[width]);
            }
            ParallelBands.run(bands, band -> {
                var first = ParallelBands.start(band, bands, height);
                var end = ParallelBands.start(band + 1, bands, height);
                var previous = edges[2 * band];
                var current = copy(values, width, first, new short[width]);
                var spare = new short[width];
                var maps = new GainMap[4];
                for (var position = 0; position < 4; position++) {
                    maps[position] = new GainMap(field, entries[position]);
                }
                for (var y = first; y < end; y++) {
                    var next = y + 1 < end ? copy(values, width, y + 1, spare) : edges[2 * band + 1];
                    var line = y * width;
                    // TODO: on a copy of the H3D sample whose grid cells change by up to half their value from one
                    //  cell to the next, the converter rounds 13 of its 39 million photosites one unit the other
                    //  way, at steep steps of the flat field; the cause is not found
                    for (var parity = 0; parity < 2; parity++) {
                        var map = maps[(y & 1) * 2 + parity];
                        var a = above[2 * y + parity];
                        var b = below[2 * y + parity];
                        var tileEnd = 0;
                        for (var x = parity; x < width; x += 2) {
                            if (x >= tileEnd) {
                                map.start(y, interpolationStart(x, tileWidth, margin));
                                map.advance(x);
                                tileEnd = (x / tileWidth + 1) * tileWidth;
                            }
                            var v = normalized(relative(current[x], black));
                            var u = normalized(relative(previous[x], black));
                            var d = normalized(relative(next[x], black));
                            var corrected = Math.rint(correct(map.value(), v, u, d, a, b));
                            values[line + x] = (short) (int) Math.clamp(corrected + black, 0, 65535);
                            map.increment();
                            map.increment();
                        }
                    }
                    var released = previous;
                    previous = current;
                    current = next;
                    spare = released;
                }
            });
        }

        /**
         * Corrects the photosites of a plane in place and interpolates its defective columns, as
         * {@link #correct(SensorPlane, int)} and {@link #fixedColumns} describe.
         *
         * @param plane the plane, of the sensor's size, holding the file's photosites
         * @param black the black level of the file's photosites
         * @param columns the defective columns, in the list's order, each within the plane
         */
        void calibrate(SensorPlane plane, int black, int[] columns) {
            var fixed = columns.length == 0 ? new short[0][] : fixedColumns(plane, black, columns);
            correct(plane, black);
            HasselbladColumns.place(plane, columns, fixed);
        }

        /**
         * Returns the defective columns' photosites as Adobe DNG Converter interpolates them, from the plane's
         * uncorrected photosites.
         *
         * <p>The converter fixes each listed column with an opcode of its own, in the list's order, in the same
         * tiles as the flat field: the buffer of a tile holds the corrected photosites from {@link #margin} columns
         * left of the tile to as many right of it, interpolated from the left edge of the buffer, and each column's
         * opcode, the {@code i}th of {@code n}, fixes it when it lies within {@code 4 * (n - 1 - i)} columns of the
         * tile, from the buffer as the opcodes before it left it. A tile's own columns are then those of the
         * buffer, so a fix reads the photosites beyond its tile as its tile's interpolation corrects them.
         *
         * @param plane the plane, of the sensor's size, holding the file's photosites, which the method does not
         *              change
         * @param black the black level of the file's photosites
         * @param columns the defective columns, in the list's order, each within the plane
         * @return the photosites of each listed column, row by row, in the order of {@code columns}
         */
        short[][] fixedColumns(SensorPlane plane, int black, int[] columns) {
            var width = plane.width;
            var height = plane.height;
            var fixed = new short[columns.length][];
            if (width != field.width || height != field.height || height < 2) {
                return fixed;
            }
            var tileWidth = tileWidth(field);
            var margin = margin(field);
            var count = columns.length;
            for (var index = 0; index < count; index++) {
                if (fixed[index] != null) {
                    continue;
                }
                var left = columns[index] / tileWidth * tileWidth;
                var right = Math.min(width, left + tileWidth);
                var from = Math.max(0, left - margin);
                var to = (int) Math.min(width, (long) right + margin);
                var span = to - from;
                var buffer = new short[span * height];
                correctColumns(plane.integers, width, height, black, from, to, left - margin, buffer);
                for (var opcode = 0; opcode < count; opcode++) {
                    var column = columns[opcode];
                    var reach = 4L * (count - 1 - opcode);
                    if (column >= left - reach && column < right + reach) {
                        HasselbladColumns.fix(buffer, span, height, width, from, column, black);
                    }
                }
                for (var other = index; other < count; other++) {
                    var column = columns[other];
                    if (column >= left && column < right) {
                        var values = new short[height];
                        for (var y = 0; y < height; y++) {
                            values[y] = buffer[y * span + column - from];
                        }
                        fixed[other] = values;
                    }
                }
            }
            return fixed;
        }

        /**
         * Corrects a range of columns of a plane into a buffer, interpolating the flat field of every row from one
         * column, as a tile's buffer does.
         *
         * @param values the plane's uncorrected photosites
         * @param width the plane's width
         * @param height the plane's height, at least 2
         * @param black the black level of the photosites
         * @param from the first column
         * @param to the column past the last one
         * @param start the column the interpolation starts at, before the first photosite of each parity, which
         *              starts at the parity's first column when that is left of the plane
         * @param target the buffer, {@code to - from} photosites per row, row by row
         */
        private void correctColumns(short[] values, int width, int height, int black, int from, int to, int start,
                                    short[] target) {
            var span = to - from;
            var maps = new GainMap[4];
            for (var position = 0; position < 4; position++) {
                maps[position] = new GainMap(field, entries[position]);
            }
            for (var y = 0; y < height; y++) {
                var line = y * width;
                var up = (y > 0 ? y - 1 : 1) * width;
                var down = (y < height - 1 ? y + 1 : height - 2) * width;
                for (var parity = 0; parity < 2; parity++) {
                    var map = maps[(y & 1) * 2 + parity];
                    var first = start + ((start ^ parity) & 1);
                    map.start(y, Math.max(first, parity));
                    var a = above[2 * y + parity];
                    var b = below[2 * y + parity];
                    for (var x = from + ((from ^ parity) & 1); x < to; x += 2) {
                        map.advance(x);
                        var v = normalized(relative(values[line + x], black));
                        var u = normalized(relative(values[up + x], black));
                        var d = normalized(relative(values[down + x], black));
                        var corrected = Math.rint(correct(map.value(), v, u, d, a, b));
                        target[y * span + x - from] = (short) (int) Math.clamp(corrected + black, 0, 65535);
                    }
                }
            }
        }

        /**
         * Returns a stored photosite's value relative to the black level. A version 30 file stores its saturated
         * photosites as 0, below every value its signed range leaves, which is read as {@value #SATURATED} above
         * the black level, the white level of the normalized photosites.
         *
         * @param stored the stored photosite
         * @param black the black level of the stored photosites
         * @return the value
         */
        private int relative(short stored, int black) {
            var value = stored & 0xFFFF;
            return value == 0 && field.version == VERSION_H4D ? SATURATED : value - black;
        }

        /**
         * Copies a row of a plane.
         *
         * @param values the plane's values
         * @param width the plane's width
         * @param y the row
         * @param target the array to copy the row into, of {@code width} elements
         * @return {@code target}
         */
        private static short[] copy(short[] values, int width, int y, short[] target) {
            System.arraycopy(values, y * width, target, 0, width);
            return target;
        }
    }

    /**
     * Interpolates the flat field of one filter array position along a row, as the Adobe DNG SDK's gain map
     * interpolator does: in sensor coordinates scaled to the unit square, whose photosites' centers are at half
     * integers, with the cells' centers the map's points.
     *
     * <p>Starting a row interpolates the map's points in single precision between the two rows of cells around
     * the row. Between two columns of cells the gain is a base value, interpolated at the column the row starts
     * at or at the first photosite past the left column of cells, whichever is later, plus a step per photosite
     * times the number of photosites since then; the base and the step are rounded to single precision and the
     * gain is computed in single precision, so where a row starts changes the rounding of its gains. Before the
     * first column of cells and past the last one the gain is the nearest one's value.
     */
    static final class GainMap {
        /**
         * The map's values, row by row.
         */
        private final float[] entries;

        /**
         * The number of the map's columns.
         */
        private final int columns;

        /**
         * The number of the map's rows.
         */
        private final int rows;

        /**
         * The scale from photosite coordinates to the unit square, across.
         */
        private final double scaleH;

        /**
         * The scale from photosite coordinates to the unit square, down.
         */
        private final double scaleV;

        /**
         * The position of the map's first column in the unit square.
         */
        private final double originH;

        /**
         * The position of the map's first row in the unit square.
         */
        private final double originV;

        /**
         * The distance between the map's columns in the unit square.
         */
        private final double spacingH;

        /**
         * The distance between the map's rows in the unit square.
         */
        private final double spacingV;

        /**
         * The column of the sensor's center, between the photosites' centers.
         */
        private final double centerH;

        /**
         * The row of the sensor's center, between the photosites' centers.
         */
        private final double centerV;

        /**
         * The photosite column the interpolation is at.
         */
        private int column;

        /**
         * The map row above the photosite row.
         */
        private int row1;

        /**
         * The map row below the photosite row.
         */
        private int row2;

        /**
         * The photosite row's fraction of the way from {@link #row1} to {@link #row2}.
         */
        private float rowFraction;

        /**
         * The column at which the base and the step are computed again.
         */
        private int resetColumn;

        /**
         * The gain at the column where the base and the step were last computed.
         */
        private float base;

        /**
         * The gain's change per photosite.
         */
        private float step;

        /**
         * The number of photosites since the base and the step were last computed.
         */
        private float index;

        /**
         * Creates the interpolator of a flat field's values for one filter array position.
         *
         * @param field the block's description, whose sensor and grid give the map's geometry
         * @param entries the values at the cells' centers, row by row
         */
        GainMap(HasselbladFlatField field, float[] entries) {
            this.entries = entries;
            this.columns = field.columns;
            this.rows = field.rows;
            this.scaleH = 1.0 / field.width;
            this.scaleV = 1.0 / field.height;
            this.originH = ((field.width - (double) field.columns * field.cellWidth) / 2 + field.cellWidth / 2.0)
                    / field.width;
            this.originV = ((field.height - (double) field.rows * field.cellHeight) / 2 + field.cellHeight / 2.0)
                    / field.height;
            this.spacingH = field.cellWidth / (double) field.width;
            this.spacingV = field.cellHeight / (double) field.height;
            this.centerH = field.width / 2.0;
            this.centerV = field.height / 2.0;
        }

        /**
         * Interpolates values given at the map's points at the center of the unit square with the arithmetic of a
         * row's interpolation: the two rows of points around the center interpolated in single precision, then the
         * two columns around it in double precision, rounded to single precision, holding the first and last points
         * beyond them.
         *
         * @param nodes the values, row by row
         * @return the interpolated value
         */
        float center(float[] nodes) {
            var down = (scaleV * centerV - originV) / spacingV;
            int top;
            int bottom;
            float fraction;
            if (down <= 0) {
                top = bottom = 0;
                fraction = 0;
            } else if (down >= rows - 1) {
                top = bottom = rows - 1;
                fraction = 0;
            } else {
                top = (int) down;
                bottom = top + 1;
                fraction = (float) (down - top);
            }
            var across = (scaleH * centerH - originH) / spacingH;
            if (across <= 0) {
                return between(nodes, top, bottom, fraction, 0);
            }
            if (across >= columns - 1) {
                return between(nodes, top, bottom, fraction, columns - 1);
            }
            var column = (int) across;
            double first = between(nodes, top, bottom, fraction, column);
            var delta = between(nodes, top, bottom, fraction, column + 1) - first;
            return (float) (first + delta * (across - column));
        }

        /**
         * Interpolates a column of values given at the map's points between two rows, in single precision.
         *
         * @param nodes the values, row by row
         * @param top the upper row
         * @param bottom the lower row
         * @param fraction the fraction of the way from the upper row to the lower one
         * @param column the map column to interpolate
         * @return the interpolated value
         */
        private float between(float[] nodes, int top, int bottom, float fraction, int column) {
            return nodes[top * columns + column] * (1f - fraction) + nodes[bottom * columns + column] * fraction;
        }

        /**
         * Positions the interpolation at a photosite.
         *
         * @param row the photosite's row
         * @param column the photosite's column
         */
        void start(int row, int column) {
            var rowIndex = (scaleV * (row + 0.5) - originV) / spacingV;
            if (rowIndex <= 0) {
                row1 = row2 = 0;
                rowFraction = 0;
            } else if (rowIndex >= rows - 1) {
                row1 = row2 = rows - 1;
                rowFraction = 0;
            } else {
                row1 = (int) rowIndex;
                row2 = row1 + 1;
                rowFraction = (float) (rowIndex - row1);
            }
            this.column = column;
            resetColumn();
        }

        /**
         * Returns the gain at the current photosite.
         *
         * @return the gain
         */
        float value() {
            return base + step * index;
        }

        /**
         * Moves the interpolation along the row to a photosite, one photosite at a time.
         *
         * @param target the photosite's column, not left of the current one
         */
        void advance(int target) {
            while (column < target) {
                increment();
            }
        }

        /**
         * Moves the interpolation to the next photosite of the row.
         */
        void increment() {
            if (++column >= resetColumn) {
                resetColumn();
            } else {
                index += 1f;
            }
        }

        /**
         * Returns a map column's value interpolated at the current row.
         *
         * @param mapColumn the map column
         * @return the value
         */
        private float entry(int mapColumn) {
            return entries[row1 * columns + mapColumn] * (1f - rowFraction)
                    + entries[row2 * columns + mapColumn] * rowFraction;
        }

        /**
         * Computes the base and the step at the current column.
         */
        private void resetColumn() {
            var columnIndex = (scaleH * (column + 0.5) - originH) / spacingH;
            if (columnIndex <= 0) {
                base = entry(0);
                step = 0;
                resetColumn = (int) Math.ceil(originH / scaleH - 0.5);
            } else if (columnIndex >= columns - 1) {
                base = entry(columns - 1);
                step = 0;
                resetColumn = Integer.MAX_VALUE;
            } else {
                var mapColumn = (int) columnIndex;
                double first = entry(mapColumn);
                var delta = entry(mapColumn + 1) - first;
                base = (float) (first + delta * (columnIndex - mapColumn));
                step = (float) ((delta * scaleH) / spacingH);
                resetColumn = (int) Math.ceil(((mapColumn + 1) * spacingH + originH) / scaleH - 0.5);
            }
            index = 0;
        }
    }

    /**
     * Returns a little-endian unsigned 16-bit number.
     *
     * @param data the bytes
     * @param offset the offset of the number
     * @return the number
     */
    private static int u16(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8;
    }

    /**
     * Returns a little-endian unsigned 32-bit number.
     *
     * @param data the bytes
     * @param offset the offset of the number
     * @return the number
     */
    private static long u32(byte[] data, int offset) {
        return (u16(data, offset) | (long) u16(data, offset + 2) << 16);
    }
}
