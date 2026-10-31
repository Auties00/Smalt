package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;

/**
 * The real-valued conversion between the R'G'B' signals {@code E'R}, {@code E'G}, {@code E'B} and the luma and color
 * difference signals {@code E'Y}, {@code E'PB}, {@code E'PR} of one ITU-T H.273 color description (equations 11 to 22
 * and 45 to 87), and the quantization of those signals to code values (equations 27 to 38).
 *
 * <p>Every matrix except YCgCo-Re and YCgCo-Ro, which are integer transforms of quantized samples (see
 * {@link YcgcoR}), has a model. The signals are normalized: R'G'B' and luma are 0 at nominal black and 1 at nominal
 * white, and color differences are 0 for both. For the identity matrix the three outputs are G', B' and R'; for YCgCo
 * they are Y, Cg and Co ({@code 0.5 G' + 0.25 (R' + B')}, {@code 0.5 G' - 0.25 (R' + B')} and
 * {@code 0.5 (R' - B')}); for SMPTE ST 2085 they are Y', D'Z and D'X computed from X'Y'Z' in place of R'G'B'; for
 * ICtCp and IPT-C2 they are I and the two color differences.
 *
 * <p>Descriptions are resolved before use: an unspecified matrix is BT.601 ({@link MatrixCoefficients#effective()}),
 * unspecified primaries are BT.709, and an unspecified transfer is BT.709 for the constant-luminance matrices and PQ
 * for ICtCp and IPT-C2. The constant-luminance, ICtCp and IPT-C2 models apply the description's
 * {@link TransferFunction}; ICtCp uses the color differences H.273 designs for HLG when the transfer is HLG and those
 * designed for PQ otherwise. The two directions are exact inverses in real arithmetic, so a round trip returns its
 * input up to rounding error.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class YcbcrModel {
    /**
     * The kinds of model.
     */
    private enum Kind {
        /**
         * Weighted non-constant luminance, equations 45 to 47.
         */
        WEIGHTED,

        /**
         * The identity matrix, outputs G', B', R'.
         */
        IDENTITY,

        /**
         * YCgCo, equations 51 to 53 on real values.
         */
        YCGCO,

        /**
         * SMPTE ST 2085 Y'D'zD'x, equations 76 to 78.
         */
        YDZDX,

        /**
         * Constant luminance, equations 66 to 75.
         */
        CONSTANT_LUMINANCE,

        /**
         * A cone-response space (ICtCp, IPT-C2), equations 14 to 22 and 79 to 87.
         */
        LMS
    }

    /**
     * The ICtCp conversion from linear R, G, B to L, M, S, in units of 1/4096 (equations 14 to 16).
     */
    private static final double[] ICTCP_LMS = {1688, 2146, 262, 683, 2951, 462, 99, 309, 3688};

    /**
     * The IPT-C2 conversion from linear R, G, B to L, M, S, in units of 1/4096 (equations 17 to 19).
     */
    private static final double[] IPT_C2_LMS = {1747, 2169, 180, 673, 3029, 394, 50, 207, 3839};

    /**
     * The ICtCp conversion from L', M', S' to I, Ct, Cp for PQ, in units of 1/4096 (equations 79 to 81).
     */
    private static final double[] ICTCP_PQ = {2048, 2048, 0, 6610, -13613, 7003, 17933, -17390, -543};

    /**
     * The ICtCp conversion from L', M', S' to I, Ct, Cp for HLG, in units of 1/4096 (equations 82 to 84).
     */
    private static final double[] ICTCP_HLG = {2048, 2048, 0, 3625, -7465, 3840, 9500, -9212, -288};

    /**
     * The IPT-C2 conversion from L', M', S' to I, P, T, in units of 1/4096 (equations 85 to 87).
     */
    private static final double[] IPT_C2 = {1638, 1638, 820, 18248, -19870, 1622, 3300, 1463, -4763};

    /**
     * The SMPTE ST 2085 weight of Z' in D'Z.
     */
    private static final double YDZDX_Z = 0.986566;

    /**
     * The SMPTE ST 2085 weight of Y' in D'X.
     */
    private static final double YDZDX_X = 0.991902;

    private final MatrixCoefficients matrix;
    private final Kind kind;
    private final double kr;
    private final double kb;
    private final double kg;
    private final TransferFunction transfer;
    private final double nb;
    private final double pb;
    private final double nr;
    private final double pr;
    private final double[] lms;
    private final double[] lmsInverse;
    private final double[] differences;
    private final double[] differencesInverse;

    /**
     * Constructs a model.
     *
     * @param color the description, whose matrix is not YCgCo-R
     */
    private YcbcrModel(ColorInfo color) {
        this.matrix = color.matrix().effective();
        this.kind = switch (matrix) {
            case IDENTITY -> Kind.IDENTITY;
            case YCGCO -> Kind.YCGCO;
            case SMPTE2085 -> Kind.YDZDX;
            case BT2020_CL, CHROMA_DERIVED_CL -> Kind.CONSTANT_LUMINANCE;
            case ICTCP, IPT_C2 -> Kind.LMS;
            case YCGCO_RE, YCGCO_RO -> throw new IllegalArgumentException(matrix + " is an integer transform");
            default -> Kind.WEIGHTED;
        };
        var weights = LumaWeights.of(matrix, color.primaries()).orElse(LumaWeights.BT709);
        this.kr = weights.kr();
        this.kb = weights.kb();
        this.kg = weights.kg();
        var resolved = color.transfer() == TransferCharacteristics.UNSPECIFIED && kind == Kind.LMS
                ? TransferCharacteristics.PQ : color.transfer();
        this.transfer = TransferFunction.of(resolved);
        this.nb = transfer.encode(1 - kb);
        this.pb = 1 - transfer.encode(kb);
        this.nr = transfer.encode(1 - kr);
        this.pr = 1 - transfer.encode(kr);
        if (kind == Kind.LMS) {
            var ipt = matrix == MatrixCoefficients.IPT_C2;
            this.lms = scaled(ipt ? IPT_C2_LMS : ICTCP_LMS);
            this.differences = scaled(ipt ? IPT_C2 : transfer.transfer() == TransferCharacteristics.HLG
                    ? ICTCP_HLG : ICTCP_PQ);
            this.lmsInverse = invert(lms);
            this.differencesInverse = invert(differences);
        } else {
            this.lms = null;
            this.differences = null;
            this.lmsInverse = null;
            this.differencesInverse = null;
        }
    }

    /**
     * Returns the model of a color description.
     *
     * @param color the description
     * @return the model
     * @throws IllegalArgumentException if the matrix is YCgCo-Re or YCgCo-Ro
     */
    public static YcbcrModel of(ColorInfo color) {
        return new YcbcrModel(color);
    }

    /**
     * {@return the resolved matrix this model implements}
     */
    public MatrixCoefficients matrix() {
        return matrix;
    }

    /**
     * {@return the transfer function the model applies; unused by the matrices that need none}
     */
    public TransferFunction transfer() {
        return transfer;
    }

    /**
     * Converts R'G'B' signals to luma and color differences.
     *
     * @param r the red signal (X' for SMPTE ST 2085)
     * @param g the green signal (Y' for SMPTE ST 2085)
     * @param b the blue signal (Z' for SMPTE ST 2085)
     * @param out receives luma and the two color differences, at least 3 long
     */
    public void toYcbcr(double r, double g, double b, double[] out) {
        switch (kind) {
            case WEIGHTED -> {
                var y = kr * r + kg * g + kb * b;
                out[0] = y;
                out[1] = 0.5 * (b - y) / (1 - kb);
                out[2] = 0.5 * (r - y) / (1 - kr);
            }
            case IDENTITY -> {
                out[0] = g;
                out[1] = b;
                out[2] = r;
            }
            case YCGCO -> {
                out[0] = 0.5 * g + 0.25 * (r + b);
                out[1] = 0.5 * g - 0.25 * (r + b);
                out[2] = 0.5 * (r - b);
            }
            case YDZDX -> {
                out[0] = g;
                out[1] = (YDZDX_Z * b - g) / 2;
                out[2] = (r - YDZDX_X * g) / 2;
            }
            case CONSTANT_LUMINANCE -> {
                var luminance = kr * transfer.decode(r) + kg * transfer.decode(g) + kb * transfer.decode(b);
                var y = transfer.encode(luminance);
                var db = b - y;
                var dr = r - y;
                out[0] = y;
                out[1] = db / (2 * (db <= 0 ? nb : pb));
                out[2] = dr / (2 * (dr <= 0 ? nr : pr));
            }
            case LMS -> {
                var lr = transfer.decode(r);
                var lg = transfer.decode(g);
                var lb = transfer.decode(b);
                var l = transfer.encode(lms[0] * lr + lms[1] * lg + lms[2] * lb);
                var m = transfer.encode(lms[3] * lr + lms[4] * lg + lms[5] * lb);
                var s = transfer.encode(lms[6] * lr + lms[7] * lg + lms[8] * lb);
                var d = differences;
                out[0] = d[0] * l + d[1] * m + d[2] * s;
                out[1] = d[3] * l + d[4] * m + d[5] * s;
                out[2] = d[6] * l + d[7] * m + d[8] * s;
            }
        }
    }

    /**
     * Converts luma and color differences to R'G'B' signals, unclamped.
     *
     * @param y the luma signal
     * @param cb the first color difference
     * @param cr the second color difference
     * @param out receives the red, green and blue signals (X', Y', Z' for SMPTE ST 2085), at least 3 long
     */
    public void toRgb(double y, double cb, double cr, double[] out) {
        switch (kind) {
            case WEIGHTED -> {
                var r = y + 2 * (1 - kr) * cr;
                var b = y + 2 * (1 - kb) * cb;
                out[0] = r;
                out[1] = (y - kr * r - kb * b) / kg;
                out[2] = b;
            }
            case IDENTITY -> {
                out[0] = cr;
                out[1] = y;
                out[2] = cb;
            }
            case YCGCO -> {
                var t = y - cb;
                out[0] = t + cr;
                out[1] = y + cb;
                out[2] = t - cr;
            }
            case YDZDX -> {
                out[0] = 2 * cr + YDZDX_X * y;
                out[1] = y;
                out[2] = (2 * cb + y) / YDZDX_Z;
            }
            case CONSTANT_LUMINANCE -> {
                var b = y + 2 * cb * (cb <= 0 ? nb : pb);
                var r = y + 2 * cr * (cr <= 0 ? nr : pr);
                var lb = transfer.decode(b);
                var lr = transfer.decode(r);
                var lg = (transfer.decode(y) - kr * lr - kb * lb) / kg;
                out[0] = r;
                out[1] = transfer.encode(lg);
                out[2] = b;
            }
            case LMS -> {
                var d = differencesInverse;
                var l = transfer.decode(d[0] * y + d[1] * cb + d[2] * cr);
                var m = transfer.decode(d[3] * y + d[4] * cb + d[5] * cr);
                var s = transfer.decode(d[6] * y + d[7] * cb + d[8] * cr);
                var i = lmsInverse;
                out[0] = transfer.encode(i[0] * l + i[1] * m + i[2] * s);
                out[1] = transfer.encode(i[3] * l + i[4] * m + i[5] * s);
                out[2] = transfer.encode(i[6] * l + i[7] * m + i[8] * s);
            }
        }
    }

    /**
     * Returns the factor that maps a normalized signal to code values (ITU-T H.273 equations 27 to 38).
     *
     * @param component 0 for luma, 1 or 2 for a color difference
     * @param range the quantization range
     * @param depth the bit depth of the samples, at least 8
     * @return the code-value span of a unit signal
     */
    public double scale(int component, ColorRange range, int depth) {
        if (range == ColorRange.FULL) {
            return (1 << depth) - 1;
        }
        var unit = 1 << (depth - 8);
        return (component == 0 || kind == Kind.IDENTITY || kind == Kind.YCGCO ? 219 : 224) * unit;
    }

    /**
     * Returns the code value of a zero signal (ITU-T H.273 equations 27 to 38).
     *
     * @param component 0 for luma, 1 or 2 for a color difference
     * @param range the quantization range
     * @param depth the bit depth of the samples, at least 8
     * @return the offset
     */
    public double offset(int component, ColorRange range, int depth) {
        var limited = range != ColorRange.FULL;
        if (component == 0 || kind == Kind.IDENTITY) {
            return limited ? 16 << (depth - 8) : 0;
        }
        return 1 << (depth - 1);
    }

    /**
     * Scales a 3x3 matrix in units of 1/4096 to real values.
     *
     * @param units the matrix, row-major
     * @return the real matrix
     */
    private static double[] scaled(double[] units) {
        var out = new double[9];
        for (var i = 0; i < 9; i++) {
            out[i] = units[i] / 4096;
        }
        return out;
    }

    /**
     * Inverts a 3x3 matrix.
     *
     * @param m the matrix, row-major and invertible
     * @return the inverse
     */
    private static double[] invert(double[] m) {
        var ca = m[4] * m[8] - m[5] * m[7];
        var cb = m[5] * m[6] - m[3] * m[8];
        var cc = m[3] * m[7] - m[4] * m[6];
        var determinant = m[0] * ca + m[1] * cb + m[2] * cc;
        return new double[]{
                ca / determinant, (m[2] * m[7] - m[1] * m[8]) / determinant, (m[1] * m[5] - m[2] * m[4]) / determinant,
                cb / determinant, (m[0] * m[8] - m[2] * m[6]) / determinant, (m[2] * m[3] - m[0] * m[5]) / determinant,
                cc / determinant, (m[1] * m[6] - m[0] * m[7]) / determinant, (m[0] * m[4] - m[1] * m[3]) / determinant
        };
    }
}
