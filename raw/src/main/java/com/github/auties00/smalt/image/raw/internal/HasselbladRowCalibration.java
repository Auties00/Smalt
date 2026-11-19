package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Reads and applies the sensor calibration that the CCD camera backs of the CFV and CF series record in their
 * 3FR files: a gain per sensor row and filter color, a correction of the charge each photosite takes from its
 * vertical neighbors, and a list of defective columns.
 *
 * <p>The calibration is version 13 of the {@code GAIN} block of maker note tag {@code 0x19}, a little-endian
 * structure that holds one record per sensor row:
 * {@snippet :
 * 32       u16 version                13
 * 44       u16 width, u16 height      of the sensor
 * 60       u16 stride                 the bytes of a row's record, at least 20
 * 96       u32 records                the offset of row 0's record in the block
 * 108      u32 defects                the offset of the list of defective columns
 * records + stride * y                row y: per column parity, even then odd, s16 above, s16, s16 gain,
 *                                     s16, s16 below
 * defects  u16 count, then per entry: u16 column, u16 kind (6 or 7 for a whole column), two more u16
 * }
 *
 * <p>The photosites are 16-bit words with black level 0: those of an unsigned file as stored, those of a signed
 * file (white level 32767) as the words the stream holds, whose values above 32767 are highlights. A photosite
 * {@code v} at column {@code x} and row {@code y}, whose neighbors in its column are {@code u} above and
 * {@code d} below, is corrected to {@code (g * v + a * u + b * d) / 16384}, rounded to the nearest integer and
 * held between 0 and 65535, where {@code a} and {@code b} are the coefficients of row {@code y}'s record for the
 * photosite's column parity and {@code g} its gain. The gain of a red or blue photosite is its record's; that of
 * a green photosite is the mean of the gain of the other column parity in its row and of the gain of its own
 * column parity in the row below (the row above, on the sensor's last row): the gains of the red and blue
 * photosites beside and below it. The first and last rows read their missing neighbor from the row next to them
 * on the other side. The arithmetic is single precision on the photosites normalized to 65535.
 *
 * <p>The listed columns are then interpolated by {@link HasselbladColumns}, in the list's order; entries of other
 * kinds, and lists that do not end before the first record, are ignored. The calibrated photosites have black
 * level 0 and white level {@link HasselbladCalibration#FILE_WHITE} (unsigned files) or
 * {@link HasselbladFlatField#SIGNED_FILE_WHITE} (signed files), 96% of the stated one, as Adobe DNG Converter
 * gives them.
 *
 * @implNote This implementation's rule was derived with Adobe DNG Converter, run as a black box, on a CFV and a
 * CF132 sample (used as local oracles only, their licence not permitting redistribution): least squares fits of
 * each row's converted photosites on their stored ones and on their vertical neighbors give the records' gains
 * and coefficients, and the green photosites' gains the means above. The converter's photosites equal this
 * rule's on every photosite of both samples, the CFV sample's two listed columns included; the near ties of the
 * rounding fix the single precision order of the arithmetic. The block's other contents (a third record per
 * row, the second field of each parity's record, the tables at the offsets of bytes 100 and 104) do not change
 * the converter's photosites of the samples and are not read.
 */
final class HasselbladRowCalibration {
    /**
     * The version of the block this class applies.
     */
    static final int VERSION = 13;

    /**
     * The number of bytes of the block's header this class reads.
     */
    static final int HEADER = 112;

    /**
     * The offset in the block's header of the sensor's width, its height following it.
     */
    private static final int SIZE = 44;

    /**
     * The offset in the block's header of the number of bytes of a row's record.
     */
    private static final int STRIDE = 60;

    /**
     * The offset in the block's header of the offset of row 0's record.
     */
    private static final int RECORDS = 96;

    /**
     * The number of bytes a row's record holds for each column parity.
     */
    private static final int ENTRY = 10;

    /**
     * The offset in a parity's entry of its gain.
     */
    private static final int GAIN = 4;

    /**
     * The offset in a parity's entry of its coefficient of the neighbor below.
     */
    private static final int BELOW = 8;

    /**
     * The denominator of the gains and coefficients.
     */
    private static final float SCALE = 16384;

    /**
     * The reciprocal of the white level of the normalized photosites.
     */
    private static final float NORMALIZATION = 1f / 65535f;

    /**
     * The sensor's width.
     */
    final int width;

    /**
     * The sensor's height.
     */
    final int height;

    /**
     * Whether the file's photosites are signed.
     */
    final boolean signed;

    /**
     * The defective columns, in the order the block lists them, without repetitions.
     */
    final int[] defects;

    /**
     * The position of row 0's record in the file.
     */
    private final long records;

    /**
     * The number of bytes of a row's record.
     */
    private final int stride;

    /**
     * Creates the description of a calibration block.
     *
     * @param width the sensor's width
     * @param height the sensor's height
     * @param signed whether the file's photosites are signed
     * @param records the position of row 0's record in the file
     * @param stride the number of bytes of a row's record
     * @param defects the defective columns
     */
    private HasselbladRowCalibration(int width, int height, boolean signed, long records, int stride,
                                     int[] defects) {
        this.width = width;
        this.height = height;
        this.signed = signed;
        this.records = records;
        this.stride = stride;
        this.defects = defects;
    }

    /**
     * Reads the description of the calibration block of a CCD camera back's 3FR file.
     *
     * @param tree the file's directories
     * @param sensor the sensor, whose size and filter array the parser set
     * @param signed whether the file's photosites are signed
     * @return the description, or {@code null} when the file records no calibration this class applies: no
     * maker note or block, another version, a block whose sensor size or records do not fit the sensor or the
     * block, records that end past the end of the file, or a filter array other than red, green, green, blue
     */
    static HasselbladRowCalibration read(TiffTree tree, Sensor sensor, boolean signed) {
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
        var field = parse(header, position, length, sensor.width, sensor.height, signed);
        if (field == null || reader.readAvailable(field.records + (long) field.stride * field.height - 1,
                new byte[1], 0, 1) < 1) {
            return null;
        }
        var list = u32(header, HasselbladFlatField.DEFECTS);
        var end = field.records - position;
        var count = new byte[2];
        if (list < HEADER || list + 2 > end || reader.readAvailable(position + list, count, 0, 2) < 2) {
            return field;
        }
        var entries = u16(count, 0);
        if (entries == 0 || list + 2 + (long) HasselbladFlatField.DEFECT * entries > end) {
            return field;
        }
        var data = new byte[HasselbladFlatField.DEFECT * entries];
        if (reader.readAvailable(position + list + 2, data, 0, data.length) < data.length) {
            return field;
        }
        return field.withDefects(HasselbladFlatField.defects(data, entries, field.width));
    }

    /**
     * Parses the header of a calibration block.
     *
     * @param header the block's first {@value #HEADER} bytes
     * @param position the position of the block in the file
     * @param length the block's length in bytes
     * @param sensorWidth the width of the sensor's stored image
     * @param sensorHeight the height of the sensor's stored image
     * @param signed whether the file's photosites are signed
     * @return the description, or {@code null} when the block is not a calibration this class applies to the
     * sensor
     */
    static HasselbladRowCalibration parse(byte[] header, long position, long length, int sensorWidth,
                                          int sensorHeight, boolean signed) {
        if (header.length < HEADER || HasselbladCalibration.version(header) != VERSION) {
            return null;
        }
        var width = u16(header, SIZE);
        var height = u16(header, SIZE + 2);
        var stride = u16(header, STRIDE);
        var records = u32(header, RECORDS);
        if (width != sensorWidth || height != sensorHeight || width < 2 || height < 2 || stride < 2 * ENTRY
                || records < HEADER || records + (long) stride * height > length) {
            return null;
        }
        return new HasselbladRowCalibration(width, height, signed, position + records, stride, new int[0]);
    }

    /**
     * Returns this description with a list of defective columns.
     *
     * @param columns the defective columns
     * @return the description
     */
    HasselbladRowCalibration withDefects(int[] columns) {
        return new HasselbladRowCalibration(width, height, signed, records, stride, columns);
    }

    /**
     * Returns the white level of the calibrated photosites.
     *
     * @return {@link HasselbladFlatField#SIGNED_FILE_WHITE} for a signed file, otherwise
     * {@link HasselbladCalibration#FILE_WHITE}
     */
    int white() {
        return signed ? HasselbladFlatField.SIGNED_FILE_WHITE : HasselbladCalibration.FILE_WHITE;
    }

    /**
     * Returns a reader that calibrates the photosites another reader reads.
     *
     * @param inner the reader of the file's photosites
     * @param source the reader of the file holding the block
     * @param black the black level of the file's photosites: {@link HasselbladJpeg#SIGNED_OFFSET} for a signed
     *              file, whose stored photosites are the stream's words plus it modulo 65536, otherwise 0
     * @return the calibrating reader, whose photosites have black level 0 and white level {@link #white()} and
     * whose defective columns are interpolated
     */
    Sensor.Reader apply(Sensor.Reader inner, TiffReader source, int black) {
        return new Sensor.Reader() {
            @Override
            public void read(SensorPlane plane, MediaLimits limits) {
                read(plane, limits, true);
            }

            @Override
            public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
                inner.read(plane, limits, corrections);
                var table = Table.read(HasselbladRowCalibration.this, source, limits);
                if (table != null && plane.width == width && plane.height == height) {
                    table.correct(plane, black);
                    HasselbladColumns.interpolate(plane, defects, 0);
                }
            }
        };
    }

    /**
     * The gains and coefficients of a block's rows.
     *
     * @param gains the gain of each row and column parity, at index {@code 2 * y + parity}, divided by 16384,
     *              the green photosites' gains already the means the class description states
     * @param above the coefficient of each row and column parity for the neighbor above, divided by 16384
     * @param below the coefficient of each row and column parity for the neighbor below, divided by 16384
     */
    record Table(float[] gains, float[] above, float[] below) {
        /**
         * Reads the table of a block.
         *
         * @param field the block's description
         * @param source the reader of the file
         * @param limits the resource bounds
         * @return the table, or {@code null} when the records are truncated
         */
        static Table read(HasselbladRowCalibration field, TiffReader source, MediaLimits limits) {
            var length = (long) field.stride * field.height;
            LimitChecks.checkAllocation(limits, length + 24L * field.height);
            var data = new byte[(int) length];
            if (source.readAvailable(field.records, data, 0, data.length) < data.length) {
                return null;
            }
            return of(data, field.stride, field.height);
        }

        /**
         * Builds a table from the bytes of a block's row records, for a red, green, green, blue filter array.
         *
         * @param data the records
         * @param stride the number of bytes of a record
         * @param height the number of rows
         * @return the table
         */
        static Table of(byte[] data, int stride, int height) {
            var gains = new float[2 * height];
            var above = new float[2 * height];
            var below = new float[2 * height];
            for (var y = 0; y < height; y++) {
                var other = y + 1 < height ? y + 1 : y - 1;
                for (var parity = 0; parity < 2; parity++) {
                    var entry = stride * y + ENTRY * parity;
                    above[2 * y + parity] = s16(data, entry) / SCALE;
                    below[2 * y + parity] = s16(data, entry + BELOW) / SCALE;
                    var gain = (double) s16(data, entry + GAIN);
                    if (((y + parity) & 1) == 1) {
                        gain = (s16(data, stride * y + ENTRY * (1 - parity) + GAIN)
                                + s16(data, stride * other + ENTRY * parity + GAIN)) / 2.0;
                    }
                    gains[2 * y + parity] = (float) (gain / SCALE);
                }
            }
            return new Table(gains, above, below);
        }

        /**
         * Corrects the photosites of a plane in place, to black level 0.
         *
         * @param plane the plane, of the sensor's size, holding the file's photosites
         * @param black the black level of the stored photosites, which a signed file stores plus it modulo
         *              65536
         */
        void correct(SensorPlane plane, int black) {
            var width = plane.width;
            var height = plane.height;
            if (height < 2 || 2 * height != gains.length) {
                return;
            }
            var values = plane.integers;
            var bands = ParallelBands.bands(height, (long) width * height);
            var edges = new short[2 * bands][];
            for (var band = 0; band < bands; band++) {
                var first = ParallelBands.start(band, bands, height);
                var last = ParallelBands.start(band + 1, bands, height) - 1;
                edges[2 * band] = copy(values, width, first > 0 ? first - 1 : 1, new short[width]);
                edges[2 * band + 1] = copy(values, width, last < height - 1 ? last + 1 : height - 2,
                        new short[width]);
            }
            ParallelBands.run(bands, band -> {
                var first = ParallelBands.start(band, bands, height);
                var end = ParallelBands.start(band + 1, bands, height);
                var previous = edges[2 * band];
                var current = copy(values, width, first, new short[width]);
                var spare = new short[width];
                for (var y = first; y < end; y++) {
                    var next = y + 1 < end ? copy(values, width, y + 1, spare) : edges[2 * band + 1];
                    var line = y * width;
                    for (var parity = 0; parity < 2; parity++) {
                        var g = gains[2 * y + parity];
                        var a = above[2 * y + parity];
                        var b = below[2 * y + parity];
                        for (var x = parity; x < width; x += 2) {
                            var v = normalized(current[x], black);
                            var u = normalized(previous[x], black);
                            var d = normalized(next[x], black);
                            values[line + x] = (short) (int) Math.rint(correct(v, u, d, g, a, b));
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
         * Returns the corrected value of a photosite from its normalized value and those of its neighbors.
         *
         * @param value the photosite's normalized value
         * @param up the normalized value of the photosite above it in its column
         * @param down the normalized value of the photosite below it in its column
         * @param gain the photosite's gain
         * @param a the row's coefficient of the neighbor above
         * @param b the row's coefficient of the neighbor below
         * @return the corrected value, from 0 to 65535, unrounded
         */
        static float correct(float value, float up, float down, float gain, float a, float b) {
            var linear = value * gain + a * up + b * down;
            return Math.clamp(linear, 0f, 1f) * 65535f;
        }

        /**
         * Returns a stored photosite's 16-bit word, normalized to the white level 65535.
         *
         * @param stored the stored photosite
         * @param black the black level of the stored photosites, which the stored photosite holds plus the word
         *              modulo 65536
         * @return the normalized word
         */
        private static float normalized(short stored, int black) {
            return ((stored - black) & 0xFFFF) * NORMALIZATION;
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
     * Reads a little-endian signed 16-bit number.
     *
     * @param data the bytes
     * @param offset the number's offset
     * @return the number
     */
    private static int s16(byte[] data, int offset) {
        return (short) u16(data, offset);
    }

    /**
     * Reads a little-endian unsigned 16-bit number.
     *
     * @param data the bytes
     * @param offset the number's offset
     * @return the number
     */
    private static int u16(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8;
    }

    /**
     * Reads a little-endian unsigned 32-bit number.
     *
     * @param data the bytes
     * @param offset the number's offset
     * @return the number
     */
    private static long u32(byte[] data, int offset) {
        return u16(data, offset) | (long) u16(data, offset + 2) << 16;
    }
}
