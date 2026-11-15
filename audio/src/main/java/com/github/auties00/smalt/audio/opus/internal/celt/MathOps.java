/* Ports libopus 1.5.2 (celt/mathops.h, celt/mathops.c, celt/float_cast.h), Copyright Xiph.Org Foundation, CSIRO and
   Jean-Marc Valin, BSD 3-Clause License; and fdlibm 5.3 (k_cos.c, k_sin.c, s_sin.c, e_rem_pio2.c), Copyright Sun
   Microsystems, fdlibm notice. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Provides the scalar math helpers of the float build of Opus.
 *
 * <p>Transcendental functions go through {@link StrictMath}, so every result, and therefore every
 * encoder decision derived from it, is identical on every JVM and CPU.
 *
 * @implNote This implementation follows the float ({@code !FIXED_POINT}, {@code !FLOAT_APPROX}) branch of the
 * reference.
 */
public final class MathOps {
    /**
     * The single-precision value of pi used by CELT ({@code PI}).
     */
    public static final float PI = 3.141592653f;

    /**
     * The first 33 bits of {@code pi / 2} (fdlibm {@code pio2_1}).
     */
    private static final double PIO2_1 = 1.57079632673412561417e+00;

    /**
     * {@code pi / 2 - PIO2_1} (fdlibm {@code pio2_1t}).
     */
    private static final double PIO2_1T = 6.07710050650619224932e-11;

    /**
     * The second 33 bits of {@code pi / 2} (fdlibm {@code pio2_2}).
     */
    private static final double PIO2_2 = 6.07710050630396597660e-11;

    /**
     * {@code pi / 2 - (PIO2_1 + PIO2_2)} (fdlibm {@code pio2_2t}).
     */
    private static final double PIO2_2T = 2.02226624879595063154e-21;

    /**
     * The third 33 bits of {@code pi / 2} (fdlibm {@code pio2_3}).
     */
    private static final double PIO2_3 = 2.02226624871116645580e-21;

    /**
     * {@code pi / 2 - (PIO2_1 + PIO2_2 + PIO2_3)} (fdlibm {@code pio2_3t}).
     */
    private static final double PIO2_3T = 8.47842766036889956997e-32;

    /**
     * {@code 2 / pi} to 53 bits (fdlibm {@code invpio2}).
     */
    private static final double INV_PIO2 = 6.36619772367581382433e-01;

    /**
     * The high words of the first 32 multiples of {@code pi / 2} (fdlibm {@code npio2_hw}).
     */
    private static final int[] NPIO2_HW = {
        0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB,
        0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB,
        0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
        0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB
    };

    /**
     * The coefficients {@code C1} to {@code C6} of the fdlibm {@code __kernel_cos} polynomial.
     */
    private static final double COS_C1 = 4.16666666666666019037e-02, COS_C2 = -1.38888888888741095749e-03,
            COS_C3 = 2.48015872894767294178e-05, COS_C4 = -2.75573143513906633035e-07,
            COS_C5 = 2.08757232129817482790e-09, COS_C6 = -1.13596475577881948265e-11;

    /**
     * The coefficients {@code S1} to {@code S6} of the fdlibm {@code __kernel_sin} polynomial.
     */
    private static final double SIN_S1 = -1.66666666666666324348e-01, SIN_S2 = 8.33333333332248946124e-03,
            SIN_S3 = -1.98412698298579493134e-04, SIN_S4 = 2.75573137070700676789e-06,
            SIN_S5 = -2.50507602534068634195e-08, SIN_S6 = 1.58969099521155010221e-10;

    /**
     * The scale between the nominal {@code [-1, 1]} sample range and CELT's internal 16-bit
     * scaled signal ({@code CELT_SIG_SCALE}).
     */
    public static final float CELT_SIG_SCALE = 32768.f;

    /**
     * A small constant preventing divisions by zero ({@code EPSILON}).
     */
    public static final float EPSILON = 1e-15f;

    /**
     * A very small constant ({@code VERY_SMALL}).
     */
    public static final float VERY_SMALL = 1e-30f;

    private MathOps() {
        throw new AssertionError();
    }

    /**
     * Rounds a float to the nearest integer, ties to even, as the SSE {@code cvtss2si}
     * instruction behind libopus's {@code float2int} does.
     *
     * <p>Values outside the {@code int} range and NaN return {@link Integer#MIN_VALUE}, the
     * integer indefinite value of the instruction.
     *
     * @param x the value
     * @return the rounded value
     */
    public static int float2int(float x) {
        var r = Math.rint(x);
        if (!(r >= -2147483648.0 && r < 2147483648.0)) {
            return Integer.MIN_VALUE;
        }
        return (int) r;
    }

    /**
     * Returns the larger of two floats with C macro semantics ({@code MAX16}, {@code MAX32}).
     *
     * <p>Unlike {@link Math#max(float, float)}, this returns {@code b} when the values compare
     * equal (so {@code max(0f, -0f)} is {@code -0f}) or when either is NaN, exactly as the
     * reference {@code ((a) > (b) ? (a) : (b))} does.
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a > b ? a : b}
     */
    public static float max(float a, float b) {
        return a > b ? a : b;
    }

    /**
     * Returns the smaller of two floats with C macro semantics ({@code MIN16}, {@code MIN32}).
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a < b ? a : b}
     */
    public static float min(float a, float b) {
        return a < b ? a : b;
    }

    /**
     * Converts a nominal {@code [-1, 1]} float sample to a clamped 16-bit sample
     * ({@code FLOAT2INT16}).
     *
     * @param x the sample
     * @return the 16-bit sample
     */
    public static short float2int16(float x) {
        x = x * CELT_SIG_SCALE;
        x = max(x, -32768);
        x = min(x, 32767);
        return (short) float2int(x);
    }

    /**
     * Returns the base-2 logarithm ({@code celt_log2}).
     *
     * @param x the argument, positive
     * @return {@code log2(x)} rounded to float
     */
    public static float celtLog2(float x) {
        return (float) (1.442695040888963387 * StrictMath.log(x));
    }

    /**
     * Returns two raised to a power ({@code celt_exp2}).
     *
     * @param x the exponent
     * @return {@code 2^x} rounded to float
     */
    public static float celtExp2(float x) {
        return (float) StrictMath.exp(0.6931471805599453094 * x);
    }

    /**
     * Returns the square root ({@code celt_sqrt}).
     *
     * @param x the argument
     * @return the square root rounded to float
     */
    public static float celtSqrt(float x) {
        return (float) Math.sqrt(x);
    }

    /**
     * Returns the reciprocal square root ({@code celt_rsqrt} and {@code celt_rsqrt_norm}).
     *
     * @param x the argument, positive
     * @return {@code 1 / sqrt(x)}
     */
    public static float celtRsqrt(float x) {
        return 1.f / celtSqrt(x);
    }

    /**
     * Returns the cosine of an angle normalized so that {@code 1} is a quarter turn
     * ({@code celt_cos_norm}).
     *
     * @param x the normalized angle
     * @return {@code cos(pi / 2 * x)}
     */
    public static float celtCosNorm(float x) {
        return (float) cos((.5f * PI) * x);
    }

    /**
     * Returns {@link StrictMath#cos(double)} of an angle, bit for bit, without the temporary array
     * the JDK's argument reduction allocates.
     *
     * <p>Angles in {@code [0, 3 pi / 4)}, the only ones {@link #celtCosNorm(float)} produces, are
     * evaluated here; every other argument is delegated to {@link StrictMath#cos(double)}.
     *
     * @implNote This implementation follows fdlibm 5.3, whose results {@link StrictMath} is
     * specified to reproduce: {@code __kernel_cos} below {@code pi / 4}, otherwise the one-step
     * reduction of {@code __ieee754_rem_pio2} for {@code |x| < 3 pi / 4} followed by
     * {@code -__kernel_sin}.
     *
     * @param x the angle in radians
     * @return the cosine
     */
    static double cos(double x) {
        var high = (int) (Double.doubleToRawLongBits(x) >>> 32);
        var ix = high & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelCos(x, 0.0);
        }
        if (high < 0 || ix >= 0x4002d97c) {
            return StrictMath.cos(x);
        }
        var z = x - PIO2_1;
        double y0;
        double y1;
        if (ix != 0x3ff921fb) {
            y0 = z - PIO2_1T;
            y1 = (z - y0) - PIO2_1T;
        } else {
            z -= PIO2_2;
            y0 = z - PIO2_2T;
            y1 = (z - y0) - PIO2_2T;
        }
        return -kernelSin(y0, y1);
    }

    /**
     * Returns {@link StrictMath#sin(double)} of an angle, bit for bit, without the temporary array the JDK's
     * argument reduction allocates.
     *
     * <p>Angles of magnitude up to {@code 2^19 pi / 2} are evaluated here; larger and non-finite arguments are
     * delegated to {@link StrictMath#sin(double)}.
     *
     * @implNote This implementation follows fdlibm 5.3 ({@code s_sin.c}), whose results {@link StrictMath} is
     * specified to reproduce: {@code __kernel_sin} below {@code pi / 4}, otherwise the small and medium-size
     * reductions of {@code __ieee754_rem_pio2} followed by the kernel of the quadrant.
     *
     * @param x the angle in radians
     * @return the sine
     */
    public static double sin(double x) {
        var hx = (int) (Double.doubleToRawLongBits(x) >>> 32);
        var ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelSin0(x);
        }
        if (ix > 0x413921fb) {
            return StrictMath.sin(x);
        }
        double y0;
        double y1;
        int n;
        if (ix < 0x4002d97c) {
            double z;
            if (hx > 0) {
                z = x - PIO2_1;
                if (ix != 0x3ff921fb) {
                    y0 = z - PIO2_1T;
                    y1 = (z - y0) - PIO2_1T;
                } else {
                    z -= PIO2_2;
                    y0 = z - PIO2_2T;
                    y1 = (z - y0) - PIO2_2T;
                }
                n = 1;
            } else {
                z = x + PIO2_1;
                if (ix != 0x3ff921fb) {
                    y0 = z + PIO2_1T;
                    y1 = (z - y0) + PIO2_1T;
                } else {
                    z += PIO2_2;
                    y0 = z + PIO2_2T;
                    y1 = (z - y0) + PIO2_2T;
                }
                n = -1;
            }
        } else {
            var t = Math.abs(x);
            n = (int) (t * INV_PIO2 + 0.5);
            var fn = (double) n;
            var r = t - fn * PIO2_1;
            var w = fn * PIO2_1T;
            if (n < 32 && ix != NPIO2_HW[n - 1]) {
                y0 = r - w;
            } else {
                var j = ix >> 20;
                y0 = r - w;
                var i = j - (((int) (Double.doubleToRawLongBits(y0) >>> 32) >> 20) & 0x7ff);
                if (i > 16) {
                    t = r;
                    w = fn * PIO2_2;
                    r = t - w;
                    w = fn * PIO2_2T - ((t - r) - w);
                    y0 = r - w;
                    i = j - (((int) (Double.doubleToRawLongBits(y0) >>> 32) >> 20) & 0x7ff);
                    if (i > 49) {
                        t = r;
                        w = fn * PIO2_3;
                        r = t - w;
                        w = fn * PIO2_3T - ((t - r) - w);
                        y0 = r - w;
                    }
                }
            }
            y1 = (r - y0) - w;
            if (hx < 0) {
                y0 = -y0;
                y1 = -y1;
                n = -n;
            }
        }
        return switch (n & 3) {
            case 0 -> kernelSin(y0, y1);
            case 1 -> kernelCos(y0, y1);
            case 2 -> -kernelSin(y0, y1);
            default -> -kernelCos(y0, y1);
        };
    }

    /**
     * Returns the sine of {@code x} for {@code |x| <= pi / 4} (fdlibm {@code __kernel_sin} with {@code y = 0} and
     * {@code iy = 0}).
     *
     * @param x the angle
     * @return the sine
     */
    private static double kernelSin0(double x) {
        var ix = (int) (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return x;
        }
        var z = x * x;
        var v = z * x;
        var r = SIN_S2 + z * (SIN_S3 + z * (SIN_S4 + z * (SIN_S5 + z * SIN_S6)));
        return x + v * (SIN_S1 + z * r);
    }

    /**
     * Returns the cosine of {@code x + y} for {@code |x| <= pi / 4}, {@code y} being the tail of
     * the reduced argument (fdlibm {@code __kernel_cos}).
     *
     * @param x the head of the reduced angle
     * @param y the tail of the reduced angle
     * @return the cosine
     */
    private static double kernelCos(double x, double y) {
        var ix = (int) (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return 1.0;
        }
        var z = x * x;
        var r = z * (COS_C1 + z * (COS_C2 + z * (COS_C3 + z * (COS_C4 + z * (COS_C5 + z * COS_C6)))));
        if (ix < 0x3FD33333) {
            return 1.0 - (0.5 * z - (z * r - x * y));
        }
        var qx = ix > 0x3fe90000 ? 0.28125 : Double.longBitsToDouble((long) (ix - 0x00200000) << 32);
        var hz = 0.5 * z - qx;
        var a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    /**
     * Returns the sine of {@code x + y} for {@code |x| <= pi / 4}, {@code y} being the tail of the
     * reduced argument (fdlibm {@code __kernel_sin} with {@code iy = 1}).
     *
     * @param x the head of the reduced angle
     * @param y the tail of the reduced angle
     * @return the sine
     */
    private static double kernelSin(double x, double y) {
        var ix = (int) (Double.doubleToRawLongBits(x) >>> 32) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return x;
        }
        var z = x * x;
        var v = z * x;
        var r = SIN_S2 + z * (SIN_S3 + z * (SIN_S4 + z * (SIN_S5 + z * SIN_S6)));
        return x - ((z * (0.5 * y - v * r) - y) - v * SIN_S1);
    }

    /**
     * Returns the reciprocal ({@code celt_rcp}).
     *
     * @param x the argument, nonzero
     * @return {@code 1 / x}
     */
    public static float celtRcp(float x) {
        return 1.f / x;
    }

    /**
     * Returns a fast approximation of {@code atan2(y, x)} ({@code fast_atan2f}).
     *
     * <p>Returns zero when both arguments are tiny.
     *
     * @param y the ordinate
     * @param x the abscissa
     * @return the approximate angle in radians
     */
    public static float fastAtan2f(float y, float x) {
        final var cA = 0.43157974f;
        final var cB = 0.67848403f;
        final var cC = 0.08595542f;
        final var cE = PI / 2;
        var x2 = x * x;
        var y2 = y * y;
        if (x2 + y2 < 1e-18f) {
            return 0;
        }
        if (x2 < y2) {
            var den = (y2 + cB * x2) * (y2 + cC * x2);
            return -x * y * (y2 + cA * x2) / den + (y < 0 ? -cE : cE);
        } else {
            var den = (x2 + cB * y2) * (x2 + cC * y2);
            return x * y * (x2 + cA * y2) / den + (y < 0 ? -cE : cE) - (x * y < 0 ? -cE : cE);
        }
    }

    /**
     * Returns the largest absolute value of a run of samples ({@code celt_maxabs16}).
     *
     * @param x      the samples
     * @param offset the index of the first sample
     * @param len    the number of samples
     * @return the maximum absolute value, zero for an empty run
     */
    public static float celtMaxabs16(float[] x, int offset, int len) {
        return CeltKernels.INSTANCE.maxabs(x, offset, len);
    }

    /**
     * Returns {@code floor(sqrt(val))} computed exactly ({@code isqrt32}).
     *
     * @implNote This implementation truncates the correctly rounded double square root of the
     * unsigned argument, which is exact for every 32-bit value: when {@code val < k^2}, its root is
     * below {@code k - 2^-17}, while a double near {@code 2^16} rounds by at most {@code 2^-37}. It
     * replaces the reference's bit-by-bit loop, whose data-dependent branches the processor
     * mispredicts.
     *
     * @param val the unsigned argument, nonzero
     * @return the integer square root
     */
    public static int isqrt32(int val) {
        return (int) Math.sqrt(Integer.toUnsignedLong(val));
    }
}
