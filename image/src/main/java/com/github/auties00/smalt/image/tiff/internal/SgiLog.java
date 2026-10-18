package com.github.auties00.smalt.image.tiff.internal;

/**
 * Decodes SGILog-compressed TIFF data (LogL and LogLuv, Greg Ward Larson's high dynamic range
 * encodings) to 8-bit display values.
 *
 * <p>Each row is coded on its own. LogL rows hold 16-bit values (a sign bit and a 15-bit log
 * luminance {@code Le}, {@code Y = 2^((Le + 0.5) / 256 - 64)}) as two run-length coded byte
 * planes, most significant first. 32-bit LogLuv rows hold four such planes: the 16-bit log
 * luminance then the 8-bit {@code u'} and {@code v'} chromaticity codes ({@code (code + 0.5) / 410}).
 * 24-bit LogLuv rows are not run-length coded: each pixel is three bytes holding a 10-bit log
 * luminance ({@code Y = 2^((Le + 0.5) / 64 - 12)}) and a 14-bit index into a table of
 * chromaticity cells. In a run-length coded plane a control byte of 128 or more repeats the next
 * byte {@code control - 126} times, and a smaller control byte is followed by that many literal
 * bytes.
 *
 * <p>The luminance and chromaticity give CIE XYZ, which is rendered as libtiff renders it for
 * display: gray {@code 256 * sqrt(Y)}, and color through the ITU-R BT.709 primaries matrix with
 * the same square-root (gamma 2.0) transfer, each clamped to 0..255. A row whose data ends early
 * stops decoding; that row and the rest stay zero.
 *
 * @implNote This implementation is a port of libtiff 4.7's tif_luv.c ({@code LogL16Decode},
 * {@code LogLuvDecode24}, {@code LogLuvDecode32}, {@code LogL16toY}, {@code LogL10toY},
 * {@code LogLuv24toXYZ}, {@code LogLuv32toXYZ}, {@code uv_decode}, {@code L16toGry} and
 * {@code XYZtoRGB24}) and of the chromaticity table of uvcode.h, by Greg Ward Larson; libtiff
 * selects the 8-bit data format the same way when reading RGBA images.
 */
public final class SgiLog {
    /**
     * The LogL layout: one 16-bit log luminance per pixel, run-length coded.
     */
    public static final int LOG_L = 0;

    /**
     * The 24-bit LogLuv layout: three bytes per pixel, not run-length coded.
     */
    public static final int LOG_LUV_24 = 1;

    /**
     * The 32-bit LogLuv layout: four bytes per pixel, run-length coded.
     */
    public static final int LOG_LUV_32 = 2;

    /**
     * The neutral {@code u'} chromaticity.
     */
    private static final double U_NEUTRAL = 0.210526316;

    /**
     * The neutral {@code v'} chromaticity.
     */
    private static final double V_NEUTRAL = 0.473684211;

    /**
     * The scale of the 8-bit {@code u'} and {@code v'} codes.
     */
    private static final double UV_SCALE = 410.0;

    /**
     * The natural logarithm of two.
     */
    private static final double LN2 = 0.69314718055994530942;

    /**
     * The side of a chromaticity cell.
     */
    private static final float UV_SQUARE = 0.003500F;

    /**
     * The number of chromaticity cells.
     */
    private static final int UV_CELLS = 16289;

    /**
     * The {@code v'} of the first cell row.
     */
    private static final float UV_V_START = 0.016940F;

    /**
     * The {@code u'} of the first cell of each cell row.
     */
    private static final float[] U_START = {
            0.247663F, 0.243779F, 0.241684F, 0.237874F, 0.235906F, 0.232153F, 0.228352F, 0.226259F,
            0.222371F, 0.220410F, 0.214710F, 0.212714F, 0.210721F, 0.204976F, 0.202986F, 0.199245F,
            0.195525F, 0.193560F, 0.189878F, 0.186216F, 0.186216F, 0.182592F, 0.179003F, 0.175466F,
            0.172001F, 0.172001F, 0.168612F, 0.168612F, 0.163575F, 0.158642F, 0.158642F, 0.158642F,
            0.153815F, 0.153815F, 0.149097F, 0.149097F, 0.142746F, 0.142746F, 0.142746F, 0.138270F,
            0.138270F, 0.138270F, 0.132166F, 0.132166F, 0.126204F, 0.126204F, 0.126204F, 0.120381F,
            0.120381F, 0.120381F, 0.120381F, 0.112962F, 0.112962F, 0.112962F, 0.107450F, 0.107450F,
            0.107450F, 0.107450F, 0.100343F, 0.100343F, 0.100343F, 0.095126F, 0.095126F, 0.095126F,
            0.095126F, 0.088276F, 0.088276F, 0.088276F, 0.088276F, 0.081523F, 0.081523F, 0.081523F,
            0.081523F, 0.074861F, 0.074861F, 0.074861F, 0.074861F, 0.068290F, 0.068290F, 0.068290F,
            0.068290F, 0.063573F, 0.063573F, 0.063573F, 0.063573F, 0.057219F, 0.057219F, 0.057219F,
            0.057219F, 0.050985F, 0.050985F, 0.050985F, 0.050985F, 0.050985F, 0.044859F, 0.044859F,
            0.044859F, 0.044859F, 0.040571F, 0.040571F, 0.040571F, 0.040571F, 0.036339F, 0.036339F,
            0.036339F, 0.036339F, 0.032139F, 0.032139F, 0.032139F, 0.032139F, 0.027947F, 0.027947F,
            0.027947F, 0.023739F, 0.023739F, 0.023739F, 0.023739F, 0.019504F, 0.019504F, 0.019504F,
            0.016976F, 0.016976F, 0.016976F, 0.016976F, 0.012639F, 0.012639F, 0.012639F, 0.009991F,
            0.009991F, 0.009991F, 0.009016F, 0.009016F, 0.009016F, 0.006217F, 0.006217F, 0.005097F,
            0.005097F, 0.005097F, 0.003909F, 0.003909F, 0.002340F, 0.002389F, 0.001068F, 0.001653F,
            0.000717F, 0.001614F, 0.000270F, 0.000484F, 0.001103F, 0.001242F, 0.001188F, 0.001011F,
            0.000709F, 0.000301F, 0.002416F, 0.003251F, 0.003246F, 0.004141F, 0.005963F, 0.008839F,
            0.010490F, 0.016994F, 0.023659F};

    /**
     * The index of the first cell of each cell row.
     */
    private static final int[] CUMULATIVE = {
            0, 4, 10, 17, 26, 36, 48, 62, 77, 94, 112, 133,
            155, 178, 204, 231, 260, 291, 323, 357, 393, 429, 467, 507,
            549, 593, 637, 683, 729, 778, 830, 882, 934, 989, 1044, 1102,
            1160, 1222, 1284, 1346, 1411, 1476, 1541, 1610, 1679, 1752, 1825, 1898,
            1975, 2052, 2129, 2206, 2288, 2370, 2452, 2538, 2624, 2710, 2796, 2887,
            2978, 3069, 3164, 3259, 3354, 3449, 3549, 3649, 3749, 3849, 3954, 4059,
            4164, 4269, 4379, 4489, 4599, 4709, 4824, 4939, 5054, 5169, 5288, 5407,
            5526, 5645, 5769, 5893, 6017, 6141, 6270, 6399, 6528, 6657, 6786, 6920,
            7054, 7188, 7322, 7460, 7598, 7736, 7874, 8016, 8158, 8300, 8442, 8588,
            8734, 8880, 9026, 9176, 9326, 9476, 9630, 9784, 9938, 10092, 10250, 10408,
            10566, 10727, 10888, 11049, 11210, 11375, 11540, 11705, 11873, 12041, 12209, 12379,
            12549, 12719, 12892, 13065, 13240, 13415, 13590, 13767, 13944, 14121, 14291, 14455,
            14612, 14762, 14905, 15041, 15170, 15293, 15408, 15517, 15620, 15717, 15806, 15888,
            15964, 16033, 16095, 16150, 16197, 16237, 16268};

    /**
     * Prevents instantiation.
     */
    private SgiLog() {
        throw new AssertionError();
    }

    /**
     * Decodes one strip or tile.
     *
     * @param raw    the coded bytes
     * @param length the number of coded bytes
     * @param out    the destination: one gray byte per pixel for {@link #LOG_L}, three RGB bytes
     *               per pixel otherwise, row after row
     * @param width  the pixels per row
     * @param rows   the number of rows
     * @param layout {@link #LOG_L}, {@link #LOG_LUV_24} or {@link #LOG_LUV_32}
     * @return the number of bytes produced: the complete rows decoded before the data ended
     */
    public static int decode(byte[] raw, int length, byte[] out, int width, int rows, int layout) {
        var pixels = new int[width];
        var position = 0;
        var remaining = length;
        var channels = layout == LOG_L ? 1 : 3;
        for (var row = 0; row < rows; row++) {
            if (layout == LOG_LUV_24) {
                if (remaining < 3L * width) {
                    return row * width * channels;
                }
                for (var i = 0; i < width; i++) {
                    pixels[i] = (raw[position] & 0xFF) << 16 | (raw[position + 1] & 0xFF) << 8 | (raw[position + 2] & 0xFF);
                    position += 3;
                }
                remaining -= 3 * width;
            } else {
                var consumed = runLengths(raw, position, remaining, pixels, width, layout == LOG_L ? 8 : 24);
                if (consumed < 0) {
                    return row * width * channels;
                }
                position += consumed;
                remaining -= consumed;
            }
            var at = row * width * channels;
            for (var i = 0; i < width; i++) {
                switch (layout) {
                    case LOG_L -> out[at + i] = gray(logL16ToY((short) pixels[i]));
                    case LOG_LUV_24 -> logLuv24(pixels[i], out, at + i * 3);
                    default -> logLuv32(pixels[i], out, at + i * 3);
                }
            }
        }
        return rows * width * channels;
    }

    /**
     * Decodes the run-length coded byte planes of one row.
     *
     * @param raw      the coded bytes
     * @param position the index of the row's first coded byte
     * @param count    the number of coded bytes left
     * @param pixels   the destination, {@code width} values, overwritten
     * @param width    the pixels per row
     * @param top      the shift of the first (most significant) plane: 8 for LogL, 24 for LogLuv
     * @return the number of coded bytes consumed, or {@code -1} when the data ends before the row
     */
    private static int runLengths(byte[] raw, int position, int count, int[] pixels, int width, int top) {
        java.util.Arrays.fill(pixels, 0);
        var at = position;
        var cc = count;
        for (var shift = top; shift >= 0; shift -= 8) {
            var i = 0;
            while (i < width && cc > 0) {
                if ((raw[at] & 0xFF) >= 128) {
                    if (cc < 2) {
                        break;
                    }
                    var run = (raw[at++] & 0xFF) + (2 - 128);
                    var value = (raw[at++] & 0xFF) << shift;
                    cc -= 2;
                    while (run-- > 0 && i < width) {
                        pixels[i++] |= value;
                    }
                } else {
                    var run = raw[at++] & 0xFF;
                    while (--cc > 0 && run-- > 0 && i < width) {
                        pixels[i++] |= (raw[at++] & 0xFF) << shift;
                    }
                }
            }
            if (i != width) {
                return -1;
            }
        }
        return at - position;
    }

    /**
     * Converts a 16-bit log luminance to luminance.
     *
     * @param p16 the code: a sign bit and 15 bits of log luminance
     * @return the luminance
     */
    private static double logL16ToY(int p16) {
        var le = p16 & 0x7FFF;
        if (le == 0) {
            return 0.0;
        }
        var y = Math.exp(LN2 / 256.0 * (le + 0.5) - LN2 * 64.0);
        return (p16 & 0x8000) == 0 ? y : -y;
    }

    /**
     * Converts a 10-bit log luminance to luminance.
     *
     * @param p10 the code
     * @return the luminance
     */
    private static double logL10ToY(int p10) {
        if (p10 == 0) {
            return 0.0;
        }
        return Math.exp(LN2 / 64.0 * (p10 + 0.5) - LN2 * 12.0);
    }

    /**
     * Renders a luminance as an 8-bit gray value.
     *
     * @param y the luminance
     * @return {@code 256 * sqrt(y)}, clamped to 0..255
     */
    private static byte gray(double y) {
        return (byte) (y <= 0.0 ? 0 : y >= 1.0 ? 255 : (int) (256.0 * Math.sqrt(y)));
    }

    /**
     * Renders a 24-bit LogLuv pixel as RGB.
     *
     * @param p   the pixel: 10 bits of log luminance then a 14-bit chromaticity index
     * @param out the destination
     * @param at  the index of the red byte
     */
    private static void logLuv24(int p, byte[] out, int at) {
        var l = logL10ToY(p >> 14 & 0x3FF);
        if (l <= 0.0) {
            out[at] = 0;
            out[at + 1] = 0;
            out[at + 2] = 0;
            return;
        }
        var cell = p & 0x3FFF;
        double u;
        double v;
        if (cell >= UV_CELLS) {
            u = U_NEUTRAL;
            v = V_NEUTRAL;
        } else {
            var lower = 0;
            var upper = U_START.length;
            while (upper - lower > 1) {
                var middle = (lower + upper) >>> 1;
                var offset = cell - CUMULATIVE[middle];
                if (offset > 0) {
                    lower = middle;
                } else if (offset < 0) {
                    upper = middle;
                } else {
                    lower = middle;
                    break;
                }
            }
            u = U_START[lower] + (cell - CUMULATIVE[lower] + 0.5) * UV_SQUARE;
            v = UV_V_START + (lower + 0.5) * UV_SQUARE;
        }
        rgb(l, u, v, out, at);
    }

    /**
     * Renders a 32-bit LogLuv pixel as RGB.
     *
     * @param p   the pixel: 16 bits of log luminance then the 8-bit {@code u'} and {@code v'}
     *            codes
     * @param out the destination
     * @param at  the index of the red byte
     */
    private static void logLuv32(int p, byte[] out, int at) {
        var l = logL16ToY(p >> 16);
        if (l <= 0.0) {
            out[at] = 0;
            out[at + 1] = 0;
            out[at + 2] = 0;
            return;
        }
        var u = 1.0 / UV_SCALE * ((p >> 8 & 0xFF) + 0.5);
        var v = 1.0 / UV_SCALE * ((p & 0xFF) + 0.5);
        rgb(l, u, v, out, at);
    }

    /**
     * Renders a luminance and chromaticity as RGB.
     *
     * @param l   the luminance
     * @param u   the {@code u'} chromaticity
     * @param v   the {@code v'} chromaticity
     * @param out the destination
     * @param at  the index of the red byte
     */
    private static void rgb(double l, double u, double v, byte[] out, int at) {
        var s = 1.0 / (6.0 * u - 16.0 * v + 12.0);
        var x = 9.0 * u * s;
        var y = 4.0 * v * s;
        var cx = (float) (x / y * l);
        var cy = (float) l;
        var cz = (float) ((1.0 - x - y) / y * l);
        out[at] = gray(2.690 * cx + -1.276 * cy + -0.414 * cz);
        out[at + 1] = gray(-1.022 * cx + 1.978 * cy + 0.044 * cz);
        out[at + 2] = gray(0.061 * cx + -0.224 * cy + 1.163 * cz);
    }
}
