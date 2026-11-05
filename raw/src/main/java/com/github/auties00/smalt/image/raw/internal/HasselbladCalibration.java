package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Reads and applies the sensor calibration a Hasselblad camera records in its 3FR files: a gain for each of the
 * red, green and blue photosites, which the camera measured for its sensor.
 *
 * <p>The calibration is the {@code GAIN} block of maker note tag {@code 0x19}, a little-endian structure
 * whatever the file's byte order. Its header holds a version at byte 32, a fraction width at byte 80 and the
 * red, green and blue gains at bytes 82, 84 and 86, unsigned numbers of that many fractional bits:
 * {@snippet :
 * 0     "GAIN"
 * 32    u16 version     40 or 41 for the CMOS cameras (X1D, X1D II, X2D, CFV-50c, CFV II, CFV 100C)
 * 44    u16 width, u16 height of the sensor
 * 80    u16 shift       14
 * 82    u16 red, u16 green, u16 blue    gain * 2^shift, 16384 = 1
 * }
 *
 * <p>Applying the calibration re-encodes the photosites the way Adobe DNG Converter does. A photosite {@code v}
 * of the file, whose black level is {@code b} and whose white level is {@value #FILE_WHITE}, first becomes
 * {@code s = floor((v - b) * (65535 - B) / (62914 - b) + B + 0.5)}, clamped to the 16-bit range, where the new
 * black level {@code B} is {@code round(b * 65535 / 62914)} clamped to the range {@value #MINIMUM_BLACK} to
 * {@value #MAXIMUM_BLACK} (1/63.75 and 1/5 of 65535). Its color's gain {@code g} then makes it close to
 * {@code (s - B) * g + B}, a product computed in single precision on values normalized to 65535, each operation
 * rounded to a {@code float}: with {@code b' = (float) (B / 65535.0)}, {@code w = 1 - b'}, {@code r = 1 / w} and
 * {@code c = r - 1},
 * {@snippet :
 * float x = s * (1f / 65535f);
 * float l = (x * r - c) * g;                         // the linear value, its gain applied
 * float y = (l * w + b') * 65535f;
 * int calibrated = (int) Math.rint(y);               // ties to even, clamped to the 16-bit range
 * }
 * which differs from the exact product by a unit where that product is close to one half. The calibrated
 * photosites have black level {@code B} and white level 65535.
 *
 * <p>Hasselblad's own software writes the FFF files, whose maker note may carry the same block but whose
 * photosites Adobe DNG Converter takes as they are; those files are big-endian and their maker note records the
 * software's version in tag {@code 0x28}, and their block is not applied.
 *
 * @implNote This implementation's rule was derived with Adobe DNG Converter, run as a black box on CC0 sample
 * files and on copies of them with the block's fields changed: zeroing the block leaves the file's photosites
 * unchanged, each gain field scales its color alone, and the fraction width scales all three. The rounding of
 * each step and the order of the single-precision operations were fixed on copies of the X1D II sample whose
 * photosites cover every 16-bit value in each filter array position, with black levels from 0 to 30000, gains
 * from 1/16384 to 4 and fraction widths 14 and 15: the rule reproduces the converter's 65536 values of every
 * position of all seven copies, and every photosite of the X1D, X1D II, CFV-50c and CFV 100C samples. The
 * blocks of the CCD cameras (H3D, H4D, versions 20 and 30) hold a flat field, which {@link HasselbladFlatField}
 * applies; version 30 then uses this re-encoding.
 */
final class HasselbladCalibration {
    /**
     * The maker note tag of the calibration block.
     */
    static final int TAG = 0x19;

    /**
     * The maker note tag of the version of Hasselblad's software, which only the files it wrote carry.
     */
    static final int SOFTWARE_TAG = 0x28;

    /**
     * The number of bytes of the block's header this class reads.
     */
    static final int HEADER = 88;

    /**
     * The white level of the file's photosites that the re-encoding maps to 65535: the level of Hasselblad's
     * files whose raw directory states a black level, 96% of the stated 65535.
     */
    static final int FILE_WHITE = 62914;

    /**
     * The smallest black level of the calibrated photosites.
     */
    static final int MINIMUM_BLACK = 1028;

    /**
     * The largest black level of the calibrated photosites.
     */
    static final int MAXIMUM_BLACK = 13107;

    /**
     * The white level of the calibrated photosites.
     */
    static final int WHITE = 65535;

    /**
     * The offset of the version in the block.
     */
    private static final int VERSION = 32;

    /**
     * The offset of the fraction width of the gains in the block.
     */
    private static final int SHIFT = 80;

    /**
     * The offset of the red gain in the block, the green and blue ones following it.
     */
    private static final int GAINS = 82;

    /**
     * The black level of the file's photosites.
     */
    final int fileBlack;

    /**
     * The white level of the file's photosites that the re-encoding maps to 65535.
     */
    final int fileWhite;

    /**
     * The black level of the calibrated photosites.
     */
    final int black;

    /**
     * The red, green and blue gains.
     */
    final double[] gains;

    /**
     * Creates the calibration of a CMOS camera's photosites, whose white level is {@value #FILE_WHITE}.
     *
     * @param fileBlack the black level of the file's photosites, below {@value #FILE_WHITE}
     * @param gains the red, green and blue gains
     */
    HasselbladCalibration(int fileBlack, double[] gains) {
        this(fileBlack, FILE_WHITE, blackOf(fileBlack, FILE_WHITE), gains);
    }

    /**
     * Creates a calibration.
     *
     * @param fileBlack the black level of the file's photosites
     * @param fileWhite the white level of the file's photosites, above {@code fileBlack}
     * @param black the black level of the calibrated photosites
     * @param gains the red, green and blue gains
     */
    HasselbladCalibration(int fileBlack, int fileWhite, int black, double[] gains) {
        this.fileBlack = fileBlack;
        this.fileWhite = fileWhite;
        this.black = black;
        this.gains = gains;
    }

    /**
     * Returns the black level of the calibrated photosites of a file's black level.
     *
     * @param fileBlack the black level of the file's photosites, as Adobe DNG Converter reads them
     * @param fileWhite the white level of the file's photosites the re-encoding maps to 65535
     * @return the black level, between {@value #MINIMUM_BLACK} and {@value #MAXIMUM_BLACK}
     */
    static int blackOf(int fileBlack, int fileWhite) {
        return Math.clamp((long) Math.rint(fileBlack * (double) WHITE / fileWhite), MINIMUM_BLACK, MAXIMUM_BLACK);
    }

    /**
     * Returns the maker note directory of a 3FR file that may hold a calibration block.
     *
     * @param tree the file's directories
     * @return the maker note, or {@code null} for a big-endian file, a file without a maker note, a maker note that
     * cannot be read, or one of a file Hasselblad's software wrote
     */
    static Ifd note(TiffTree tree) {
        var reader = tree.first.reader();
        if (!reader.little() || tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0) {
            return null;
        }
        Ifd note;
        try {
            note = reader.directory(position - reader.base());
        } catch (MediaException.Malformed exception) {
            return null;
        }
        return note.has(SOFTWARE_TAG) ? null : note;
    }

    /**
     * Returns the version of a calibration block.
     *
     * @param block the block's first {@value #HEADER} bytes
     * @return the version, or -1 when the bytes are not a calibration block
     */
    static int version(byte[] block) {
        if (block.length < HEADER || block[0] != 'G' || block[1] != 'A' || block[2] != 'I' || block[3] != 'N') {
            return -1;
        }
        return u16(block, VERSION);
    }

    /**
     * Returns the red, green and blue gains of a calibration block's header.
     *
     * @param block the block's first {@value #HEADER} bytes
     * @return the gains, or {@code null} when the fraction width is above 16 or a gain is zero
     */
    static double[] gains(byte[] block) {
        var shift = u16(block, SHIFT);
        if (shift > 16) {
            return null;
        }
        var gains = new double[3];
        for (var color = 0; color < 3; color++) {
            var gain = u16(block, GAINS + 2 * color);
            if (gain == 0) {
                return null;
            }
            gains[color] = gain / (double) (1 << shift);
        }
        return gains;
    }

    /**
     * Reads the calibration of a 3FR file.
     *
     * @param tree the file's directories
     * @param fileBlack the black level of the raw directory's photosites
     * @return the calibration, or {@code null} when the file records none this class applies: no maker note or
     * block, a block of another version or with a zero gain, or a file Hasselblad's software wrote
     */
    static HasselbladCalibration read(TiffTree tree, double fileBlack) {
        if (fileBlack < 0 || fileBlack >= FILE_WHITE || fileBlack != Math.rint(fileBlack)) {
            return null;
        }
        var note = note(tree);
        if (note == null || note.valueCount(TAG) < HEADER) {
            return null;
        }
        var block = new byte[HEADER];
        if (tree.first.reader().readAvailable(note.valuePosition(TAG), block, 0, HEADER) < HEADER) {
            return null;
        }
        return parse(block, (int) fileBlack);
    }

    /**
     * Parses the header of a calibration block.
     *
     * @param block the block's first {@value #HEADER} bytes
     * @param fileBlack the black level of the file's photosites
     * @return the calibration, or {@code null} when the block is not a calibration this class applies
     */
    static HasselbladCalibration parse(byte[] block, int fileBlack) {
        var version = version(block);
        if (version != 40 && version != 41) {
            return null;
        }
        var gains = gains(block);
        return gains == null ? null : new HasselbladCalibration(fileBlack, gains);
    }

    /**
     * Returns the calibrated value of each photosite value of a color.
     *
     * @param color the color, 0 red, 1 green, 2 blue
     * @return the 65536 calibrated values, as unsigned 16-bit numbers
     */
    short[] table(int color) {
        var table = new short[65536];
        var scale = (WHITE - black) / (double) (fileWhite - fileBlack);
        var gain = (float) gains[color];
        var normalizedBlack = (float) (black / (double) WHITE);
        var range = 1 - normalizedBlack;
        var reciprocal = 1 / range;
        var offset = reciprocal - 1;
        for (var value = 0; value < table.length; value++) {
            var encoded = (float) Math.clamp(Math.floor((value - fileBlack) * scale + black + 0.5), 0, WHITE);
            var linear = (encoded * (1f / WHITE) * reciprocal - offset) * gain;
            var calibrated = Math.rint((linear * range + normalizedBlack) * WHITE);
            table[value] = (short) (int) Math.clamp(calibrated, 0, WHITE);
        }
        return table;
    }

    /**
     * Returns a reader that calibrates the photosites another reader reads.
     *
     * @param inner the reader of the file's photosites
     * @param cfa the color filter array, of red, green and blue photosites
     * @return the calibrating reader
     */
    Sensor.Reader apply(Sensor.Reader inner, CfaPattern cfa) {
        return new Sensor.Reader() {
            @Override
            public void read(SensorPlane plane, MediaLimits limits) {
                read(plane, limits, true);
            }

            @Override
            public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
                inner.read(plane, limits, corrections);
                calibrate(plane, cfa);
            }
        };
    }

    /**
     * Calibrates the photosites of a plane in place.
     *
     * @param plane the plane, of the file's photosites
     * @param cfa the color filter array, of red, green and blue photosites
     */
    void calibrate(SensorPlane plane, CfaPattern cfa) {
        var tables = new short[3][];
        for (var color = 0; color < 3; color++) {
            tables[color] = table(color);
        }
        var period = cfa.width();
        var rows = cfa.height();
        var lookup = new short[rows * period][];
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < period; column++) {
                lookup[row * period + column] = tables[index(cfa.color(row, column))];
            }
        }
        var width = plane.width;
        var height = plane.height;
        var values = plane.integers;
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                var line = y * width;
                var base = y % rows * period;
                for (var phase = 0; phase < period; phase++) {
                    var table = lookup[base + phase];
                    for (var index = line + phase; index < line + width; index += period) {
                        values[index] = table[values[index] & 0xFFFF];
                    }
                }
            }
        });
    }

    /**
     * Returns the index of a color's gain.
     *
     * @param color the color of a photosite
     * @return 0 for red, 1 for green, 2 for blue
     * @throws IllegalArgumentException if the color is not red, green or blue
     */
    private static int index(CfaColor color) {
        return switch (color) {
            case RED -> 0;
            case GREEN -> 1;
            case BLUE -> 2;
            default -> throw new IllegalArgumentException("a Hasselblad calibration of a " + color + " photosite");
        };
    }

    /**
     * Returns whether a color filter array holds only red, green and blue photosites.
     *
     * @param cfa the color filter array
     * @return whether the calibration can apply to it
     */
    static boolean supports(CfaPattern cfa) {
        for (var row = 0; row < cfa.height(); row++) {
            for (var column = 0; column < cfa.width(); column++) {
                var color = cfa.color(row, column);
                if (color != CfaColor.RED && color != CfaColor.GREEN && color != CfaColor.BLUE) {
                    return false;
                }
            }
        }
        return true;
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
}
