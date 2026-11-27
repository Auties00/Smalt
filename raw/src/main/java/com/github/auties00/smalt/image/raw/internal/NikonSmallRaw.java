package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Converts the YCbCr samples of a Nikon small raw image (NEFCompression 8) to linear red, green and
 * blue.
 *
 * <p>The strip holds, row by row, units of two pixels: six bytes of four 12-bit values packed least
 * significant bit first ({@link Unpack#lsbFirst}), the luma of the left and right pixel, then the
 * blue-difference and red-difference chroma of both, centered on 2048. Each pixel takes its unit's
 * chroma as it is. With {@code y} its luma and {@code b} and {@code r} the chroma less 2048, its
 * stored values are red {@code 32y + ((400b + 45858r) >> 10)}, green {@code 32y - ((10920b + 23502r) >> 10)}
 * and blue {@code 32y + ((58080b - 202r) >> 10)}, clamped to 0 to 65535 (the conversion of Canon's
 * mRAW and sRAW data of {@link CanonSraw}, on a luma scaled by 32). The samples are already white
 * balanced, by the maker note's red and blue multipliers, and encoded by a tone curve:
 * {@link #linearization()} maps them to linear values, of no black level and a white level of 65535.
 *
 * @implNote This implementation's layout, siting and conversion were worked out from a CC0 sample
 * file (Nikon D810) against the values Adobe DNG Converter 18.0, run as a black box, stores for it
 * (linear red, green and blue), which it reproduces exactly; the converter stores the maker note's
 * multipliers as the analog balance and a neutral as-shot white balance, as this decoder does. The
 * converter's linearization table is, within one code at 33 of its 65536 entries (ties of its
 * rounding), {@code round(65535 f(i / 65535))} for the odd polynomial {@code f} of {@link #CURVE},
 * found by fitting that table; it is the one sample's, and no other camera's small raw files are
 * known to use another.
 */
final class NikonSmallRaw {
    /**
     * The largest stored value.
     */
    static final int MAXIMUM = 65535;

    /**
     * The value of zero chroma.
     */
    private static final int CENTER = 2048;

    /**
     * The coefficients of the odd powers 1, 3, 5 and 7 of the tone curve's linear value of an encoded value,
     * both on a scale of 0 to 1.
     */
    private static final double[] CURVE = {0.170893027428, 1.372090029095, -1.101650211392, 0.558667210907};

    /**
     * Prevents instantiation.
     */
    private NikonSmallRaw() {
        throw new AssertionError();
    }

    /**
     * Describes the small raw image of a raw image directory: its size, three 16-bit samples per pixel and
     * the linearization of the tone curve.
     *
     * @param raw the raw image directory
     * @param sensor the sensor description to fill
     * @return the reader
     * @throws MediaException.Malformed if the image is not 12-bit data of an even width or its strips are shorter
     * than its rows
     */
    static Sensor.Reader describe(Ifd raw, Sensor sensor) {
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        if (width <= 0 || height <= 0 || (width & 1) != 0 || bits != 12) {
            throw new MediaException.Malformed("invalid NEF small raw image of " + width + "x" + height + "x" + bits);
        }
        var blocks = TiffSamples.blocks(raw, width, height);
        var row = 3L * width;
        for (var block : blocks) {
            if (block.height() <= 0 || block.length() / block.height() < row) {
                throw new MediaException.Malformed("the NEF strips are shorter than their rows");
            }
        }
        sensor.width = width;
        sensor.height = height;
        sensor.samplesPerPixel = 3;
        sensor.bitsPerSample = 16;
        sensor.compression = "Nikon small raw";
        sensor.white = new double[]{MAXIMUM, MAXIMUM, MAXIMUM};
        sensor.linearization = linearization();
        var source = raw.reader().source();
        return (plane, limits) -> {
            var units = new short[2 * plane.width];
            for (var block : blocks) {
                LimitChecks.checkAllocation(limits, block.length());
                var data = new byte[(int) Math.min(block.length(), Integer.MAX_VALUE - 16)];
                source.readFully(block.offset(), data, 0, data.length);
                var stride = (int) (block.length() / block.height());
                var rows = Math.min(block.height(), plane.height - block.y());
                for (var y = 0; y < rows; y++) {
                    Unpack.lsbFirst(data, y * stride, y * stride + stride, 12, units, 0, units.length);
                    convert(units, plane.integers, (block.y() + y) * plane.width * 3, plane.width);
                }
            }
        };
    }

    /**
     * Converts one row of units to interleaved red, green and blue samples.
     *
     * @param units the row's 12-bit values, four per unit of two pixels
     * @param out receives the samples
     * @param target the index of the row's first sample in {@code out}
     * @param width the row's width in pixels, even
     */
    static void convert(short[] units, short[] out, int target, int width) {
        for (var x = 0; x < width; x++) {
            var unit = (x >> 1) * 4;
            var luma = 32 * units[unit + (x & 1)];
            var blue = units[unit + 2] - CENTER;
            var red = units[unit + 3] - CENTER;
            var at = target + x * 3;
            out[at] = clamp(luma + ((400 * blue + 45858 * red) >> 10));
            out[at + 1] = clamp(luma - ((10920 * blue + 23502 * red) >> 10));
            out[at + 2] = clamp(luma + ((58080 * blue - 202 * red) >> 10));
        }
    }

    /**
     * Returns the linearization table of the tone curve.
     *
     * @return the linear value of every stored value, of 65536 entries
     */
    static int[] linearization() {
        var table = new int[MAXIMUM + 1];
        for (var index = 0; index <= MAXIMUM; index++) {
            var x = index / (double) MAXIMUM;
            var square = x * x;
            var linear = x * (CURVE[0] + square * (CURVE[1] + square * (CURVE[2] + square * CURVE[3])));
            table[index] = (int) Math.clamp(Math.floor(linear * MAXIMUM + 0.5), 0, MAXIMUM);
        }
        return table;
    }

    /**
     * Clamps a value to the range of the stored samples.
     *
     * @param value the converted sample, of any range
     * @return the clamped value as a sample
     */
    private static short clamp(int value) {
        return (short) Math.clamp(value, 0, MAXIMUM);
    }
}
