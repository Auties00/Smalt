package com.github.auties00.smalt.capture.internal.convert;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.color.LumaWeights;

/**
 * The fixed-point coefficients that convert 8-bit R'G'B' to 8-bit Y'CbCr for one color.
 *
 * <p>Every coefficient has {@value #BITS} fractional bits:
 * {@snippet :
 * Y  = clamp((yr * R + yg * G + yb * B + yBias) >> 16)
 * Cb = clamp((ur * sumR + ug * sumG + ub * sumB + cBias) >> 18)   // sums over a 2x2 block
 * Cr = clamp((vr * sumR + vg * sumG + vb * sumB + cBias) >> 18)
 * }
 * The coefficients and both constants are those of
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toYuv}, so a conversion through a
 * {@link ConversionKernel} is bit-identical to converting an RGBA picture to 4:2:0 of the same
 * color with {@code ImageOps.toYuv}. Instances are immutable and thread-safe.
 */
public final class YuvCoefficients {
    /**
     * The number of fractional bits of every coefficient.
     */
    public static final int BITS = 16;

    /**
     * The right shift of a chroma sample computed from the sums over a 2x2 block.
     */
    static final int CHROMA_SHIFT = BITS + 2;

    /**
     * The coefficients of limited-range BT.709, the color of every picture converted from R'G'B'.
     */
    public static final YuvCoefficients BT709_LIMITED = of(ColorInfo.BT709_LIMITED);

    private final ColorInfo color;
    final int yr;
    final int yg;
    final int yb;
    final int ur;
    final int ug;
    final int ub;
    final int vr;
    final int vg;
    final int vb;

    /**
     * The luma offset in fixed point plus one half.
     */
    final int yBias;

    /**
     * The chroma offset of a 2x2 sum in fixed point plus one half minus one, so chroma rounds half
     * down.
     */
    final int cBias;

    private final int black;

    private YuvCoefficients(ColorInfo color, int[] luma, int[] cb, int[] cr) {
        this.color = color;
        this.yr = luma[0];
        this.yg = luma[1];
        this.yb = luma[2];
        this.ur = cb[0];
        this.ug = cb[1];
        this.ub = cb[2];
        this.vr = cr[0];
        this.vg = cr[1];
        this.vb = cr[2];
        this.black = color.range() == ColorRange.LIMITED ? 16 : 0;
        this.yBias = (black << BITS) + (1 << (BITS - 1));
        this.cBias = (128 << CHROMA_SHIFT) + (1 << (CHROMA_SHIFT - 1)) - 1;
    }

    /**
     * Returns the coefficients that produce a color.
     *
     * @param color the color, whose matrix two luma weights define once resolved with
     * {@link com.github.auties00.smalt.image.MatrixCoefficients#effective()} (BT.601, BT.470 BG, BT.709,
     * FCC, SMPTE 240M, BT.2020 non-constant luminance or unspecified)
     * @return the coefficients
     * @throws IllegalArgumentException if two luma weights do not define the matrix
     */
    public static YuvCoefficients of(ColorInfo color) {
        var weights = LumaWeights.of(color.matrix().effective())
                .orElseThrow(() -> new IllegalArgumentException("unsupported matrix " + color.matrix()));
        var full = color.range() == ColorRange.FULL;
        if (full && weights.equals(LumaWeights.BT601)) {
            // libjpeg-turbo's jccolor.c constants, which ImageOps uses for full-range BT.601
            return new YuvCoefficients(color, new int[]{19595, 38470, 7471}, new int[]{-11059, -21709, 32768},
                    new int[]{32768, -27439, -5329});
        }
        var lumaScale = (full ? 255.0 : 219.0) / 255.0;
        var chromaScale = (full ? 255.0 : 224.0) / 255.0;
        var kr = weights.kr();
        var kb = weights.kb();
        var kg = 1 - kr - kb;
        var cb = 2 * (1 - kb);
        var cr = 2 * (1 - kr);
        return new YuvCoefficients(color,
                fixedRow(kr * lumaScale, kg * lumaScale, kb * lumaScale),
                fixedRow(-kr / cb * chromaScale, -kg / cb * chromaScale, 0.5 * chromaScale),
                fixedRow(0.5 * chromaScale, -kg / cr * chromaScale, -kb / cr * chromaScale));
    }

    /**
     * Rounds one row to fixed point, adjusting the green coefficient so the row sums to its
     * rounded real sum, which keeps grays neutral.
     */
    private static int[] fixedRow(double r, double g, double b) {
        var fixed = new int[]{(int) Math.round(r * (1 << BITS)), 0, (int) Math.round(b * (1 << BITS))};
        fixed[1] = (int) (Math.round((r + g + b) * (1 << BITS)) - fixed[0] - fixed[2]);
        return fixed;
    }

    /**
     * {@return the color these coefficients produce}
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the luma sample of black: 16 at limited range, 0 at full range}
     */
    public int black() {
        return black;
    }

    @Override
    public String toString() {
        return "YuvCoefficients[" + color.matrix() + ", " + color.range() + "]";
    }
}
