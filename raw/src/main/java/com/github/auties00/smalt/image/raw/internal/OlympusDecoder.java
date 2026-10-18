package com.github.auties00.smalt.image.raw.internal;

/**
 * Decodes Olympus' compressed ORF sensor data: 12-bit photosites coded row by row as prediction
 * residuals with an adaptive Rice code.
 *
 * <p>The data starts with a header of seven bytes ({@code 00 00 00 00 01 00 00}); the codes follow
 * from the eighth byte, read most significant bit first, without any alignment between rows. Every
 * row starts afresh, and keeps a separate state for its even and its odd photosites (the two colors
 * of the row). A photosite is coded as:
 * {@snippet :
 * sign (1 bit) | low (2 bits) | q zero bits then a one bit | mid (b bits)
 * }
 * The magnitude {@code M} is {@code (q << b) | mid}; the coded value {@code G} is {@code M} for a
 * sign of 0 and {@code -M - 1} otherwise. When {@code q} reaches 12 the twelve zero bits are followed
 * by {@code q} itself in {@code 15 - b} bits and the one bit. The residual high part is
 * {@code H = G - S} and the photosite {@code P + 4 * H + low}, where {@code P} is the prediction and
 * {@code S} a per-color bias, 0 at the start of a row and then {@code -floor((3 * H - S) / 32)} of
 * the previous photosite of the color.
 *
 * <p>The width {@code b} comes from the one's complement magnitudes {@code m1}, {@code m2} and
 * {@code m3} of the last three coded values of the color: {@code max(4, bitlen(m1) - 2)} when
 * {@code m1} is at least 17, otherwise 4 when {@code m2} or {@code m3} is at least 17, otherwise
 * {@code max(2, bitlen(m1))}. At the start of a row the three magnitudes count as large, so the first
 * photosites of a color use a width of 4.
 *
 * <p>The prediction of the first two rows is 0 for their first two photosites and the photosite two
 * columns to the left otherwise. From the third row on, with {@code u} the photosite two rows up,
 * {@code l} the one two columns to the left and {@code ul} the one two rows up and two columns to the
 * left, the prediction is {@code u} in the first two columns; otherwise {@code min(l, u)} when
 * {@code ul} is at least {@code max(l, u)}, {@code max(l, u)} when {@code ul} is at most
 * {@code min(l, u)}, {@code l + u - ul} when {@code ul} differs from {@code l} or {@code u} by more
 * than 32, and {@code (l + u) / 2} rounded down otherwise.
 *
 * @implNote This implementation's layout, code and constants were derived without reference to any
 * other decoder, from CC0 sample files and the values Adobe DNG Converter, run as a black
 * box, stores for them; the raw module's test-scope {@code OlympusDerivation} records
 * and re-runs the derivation.
 */
final class OlympusDecoder {
    /**
     * The bytes of the header before the first code.
     */
    static final int HEADER = 7;

    /**
     * The length of a zero run that escapes to an explicit quotient.
     */
    static final int ESCAPE = 12;

    /**
     * The bits of an escaped quotient and its width together.
     */
    static final int ESCAPE_BITS = 15;

    /**
     * The magnitude from which a coded value counts as large.
     */
    static final int LARGE = 17;

    /**
     * The difference from the upper-left photosite above which the gradient predicts.
     */
    static final int EDGE = 32;

    /**
     * The magnitude standing for the history at the start of a row: large, and coded with a width
     * of 4.
     */
    private static final int START = 32;

    /**
     * Prevents instantiation.
     */
    private OlympusDecoder() {
        throw new AssertionError();
    }

    /**
     * Decodes the sensor data into a plane.
     *
     * @param data the strip, from its first byte
     * @param plane the plane to fill with 12-bit photosites
     */
    static void decode(byte[] data, SensorPlane plane) {
        var bits = new MsbBits(data, HEADER, data.length);
        var width = plane.width;
        var out = plane.integers;
        var bias = new int[2];
        var m1 = new int[2];
        var m2 = new int[2];
        var m3 = new int[2];
        for (var y = 0; y < plane.height; y++) {
            var row = y * width;
            for (var c = 0; c < 2; c++) {
                bias[c] = 0;
                m1[c] = START;
                m2[c] = START;
                m3[c] = START;
            }
            for (var x = 0; x < width; x++) {
                var c = x & 1;
                var b = width(m1[c], m2[c], m3[c]);
                var sign = bits.read(1);
                var low = bits.read(2);
                var q = bits.zeros(ESCAPE);
                if (q == ESCAPE) {
                    q = bits.read(Math.max(0, ESCAPE_BITS - b));
                }
                bits.read(1);
                var magnitude = (q << b) | bits.read(b);
                var coded = sign == 0 ? magnitude : -magnitude - 1;
                var high = coded - bias[c];
                var value = predict(out, width, row, x, y) + 4 * high + low;
                out[row + x] = (short) Math.clamp(value, 0, 0xFFFF);
                bias[c] = -Math.floorDiv(3 * high - bias[c], 32);
                m3[c] = m2[c];
                m2[c] = m1[c];
                m1[c] = magnitude;
            }
        }
    }

    /**
     * Returns the bits of the mid part of a code from the magnitudes of the last three coded values
     * of the color, most recent first.
     *
     * @param m1 the magnitude of the previous coded value
     * @param m2 the magnitude of the one before
     * @param m3 the magnitude of the one before that
     * @return the width, from 2 up
     */
    static int width(int m1, int m2, int m3) {
        if (m1 >= LARGE) {
            return Math.max(4, 32 - Integer.numberOfLeadingZeros(m1) - 2);
        }
        if (m2 >= LARGE || m3 >= LARGE) {
            return 4;
        }
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(m1));
    }

    /**
     * Returns the prediction of a photosite from the photosites of its color already decoded.
     *
     * @param out the decoded photosites
     * @param width the row width
     * @param row the index of the row's first photosite
     * @param x the column
     * @param y the row
     * @return the prediction
     */
    static int predict(short[] out, int width, int row, int x, int y) {
        if (y < 2) {
            return x < 2 ? 0 : out[row + x - 2] & 0xFFFF;
        }
        var up = out[row - 2 * width + x] & 0xFFFF;
        if (x < 2) {
            return up;
        }
        var left = out[row + x - 2] & 0xFFFF;
        var upLeft = out[row - 2 * width + x - 2] & 0xFFFF;
        var high = Math.max(left, up);
        var low = Math.min(left, up);
        if (upLeft >= high) {
            return low;
        }
        if (upLeft <= low) {
            return high;
        }
        if (Math.max(Math.abs(left - upLeft), Math.abs(up - upLeft)) > EDGE) {
            return left + up - upLeft;
        }
        return (left + up) >> 1;
    }
}
