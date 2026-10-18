package com.github.auties00.smalt.audio.voice.internal;

/**
 * Computes the single-precision elementary functions of the voice processor with the exact
 * results of the C library the reference build links against.
 *
 * <p>The noise suppressor raises two to fractional powers and maps features through a
 * hyperbolic tangent on every frame, and its adaptive estimators feed those results back into
 * their state, so a result that differs from the reference library in the last bit makes the
 * whole output drift away from it. Java's {@link Math} and {@link StrictMath} functions are
 * double precision and round differently, so this class reproduces the float functions the
 * reference uses bit for bit: {@link #powf(float, float)}, {@link #tanhf(float)} (through
 * {@link #expm1f(float)}) and the small-argument {@link #cosf(float)} and {@link #sinf(float)}
 * that build the transform tables. Every function evaluates its operations in the same types
 * and order as the reference, so the results are identical on every platform.
 *
 * @implNote This implementation follows musl 1.2.5 ({@code src/math/powf.c},
 * {@code powf_data.c} and {@code exp2f_data.c} from Arm's optimized routines, and
 * {@code tanhf.c}, {@code expm1f.c}, {@code cosf.c}, {@code sinf.c}, {@code __cosdf.c} and
 * {@code __sindf.c} from FreeBSD's msun), the C library of the WebAssembly build of
 * webrtc-audio-processing that serves as the bit-exact reference. The polynomial coefficients
 * and tables are copied verbatim as hexadecimal floating-point literals.
 */
public final class FloatMath {
    /**
     * The number of entries of the base-2 logarithm table of {@link #powf(float, float)}.
     */
    private static final int LOG2_TABLE_SIZE = 16;

    /**
     * The offset that centers the reduced argument of the base-2 logarithm on the table.
     */
    private static final int LOG2_OFFSET = 0x3f330000;

    /**
     * The reciprocals {@code 1/c} of the table centers of the base-2 logarithm.
     *
     * @implNote This implementation copies {@code __powf_log2_data.tab[].invc} from musl's
     * {@code powf_data.c}.
     */
    private static final double[] LOG2_INVC = {
            0x1.661ec79f8f3bep+0, 0x1.571ed4aaf883dp+0, 0x1.49539f0f010bp+0, 0x1.3c995b0b80385p+0,
            0x1.30d190c8864a5p+0, 0x1.25e227b0b8eap+0, 0x1.1bb4a4a1a343fp+0, 0x1.12358f08ae5bap+0,
            0x1.0953f419900a7p+0, 0x1p+0, 0x1.e608cfd9a47acp-1, 0x1.ca4b31f026aap-1,
            0x1.b2036576afce6p-1, 0x1.9c2d163a1aa2dp-1, 0x1.886e6037841edp-1, 0x1.767dcf5534862p-1
    };

    /**
     * The base-2 logarithms {@code log2(c)} of the table centers.
     *
     * @implNote This implementation copies {@code __powf_log2_data.tab[].logc} from musl's
     * {@code powf_data.c}.
     */
    private static final double[] LOG2_LOGC = {
            -0x1.efec65b963019p-2, -0x1.b0b6832d4fca4p-2, -0x1.7418b0a1fb77bp-2, -0x1.39de91a6dcf7bp-2,
            -0x1.01d9bf3f2b631p-2, -0x1.97c1d1b3b7afp-3, -0x1.2f9e393af3c9fp-3, -0x1.960cbbf788d5cp-4,
            -0x1.a6f9db6475fcep-5, 0x0p+0, 0x1.338ca9f24f53dp-4, 0x1.476a9543891bap-3,
            0x1.e840b4ac4e4d2p-3, 0x1.40645f0c6651cp-2, 0x1.88e9c2c1b9ff8p-2, 0x1.ce0a44eb17bccp-2
    };

    /**
     * The first coefficient of the polynomial approximating {@code log1p(r)/ln2}.
     */
    private static final double LOG2_A0 = 0x1.27616c9496e0bp-2;

    /**
     * The second coefficient of the polynomial approximating {@code log1p(r)/ln2}.
     */
    private static final double LOG2_A1 = -0x1.71969a075c67ap-2;

    /**
     * The third coefficient of the polynomial approximating {@code log1p(r)/ln2}.
     */
    private static final double LOG2_A2 = 0x1.ec70a6ca7baddp-2;

    /**
     * The fourth coefficient of the polynomial approximating {@code log1p(r)/ln2}.
     */
    private static final double LOG2_A3 = -0x1.7154748bef6c8p-1;

    /**
     * The fifth coefficient of the polynomial approximating {@code log1p(r)/ln2}.
     */
    private static final double LOG2_A4 = 0x1.71547652ab82bp0;

    /**
     * The number of entries of the {@code 2^(i/N)} table of the exponential.
     */
    private static final int EXP2_TABLE_SIZE = 32;

    /**
     * The table of {@code 2^(i/32)}, stored as the bit patterns of the doubles minus
     * {@code i << 47}.
     *
     * @implNote This implementation copies {@code __exp2f_data.tab} from musl's
     * {@code exp2f_data.c}.
     */
    private static final long[] EXP2_TABLE = {
            0x3ff0000000000000L, 0x3fefd9b0d3158574L, 0x3fefb5586cf9890fL, 0x3fef9301d0125b51L,
            0x3fef72b83c7d517bL, 0x3fef54873168b9aaL, 0x3fef387a6e756238L, 0x3fef1e9df51fdee1L,
            0x3fef06fe0a31b715L, 0x3feef1a7373aa9cbL, 0x3feedea64c123422L, 0x3feece086061892dL,
            0x3feebfdad5362a27L, 0x3feeb42b569d4f82L, 0x3feeab07dd485429L, 0x3feea47eb03a5585L,
            0x3feea09e667f3bcdL, 0x3fee9f75e8ec5f74L, 0x3feea11473eb0187L, 0x3feea589994cce13L,
            0x3feeace5422aa0dbL, 0x3feeb737b0cdc5e5L, 0x3feec49182a3f090L, 0x3feed503b23e255dL,
            0x3feee89f995ad3adL, 0x3feeff76f2fb5e47L, 0x3fef199bdd85529cL, 0x3fef3720dcef9069L,
            0x3fef5818dcfba487L, 0x3fef7c97337b9b5fL, 0x3fefa4afa2a490daL, 0x3fefd0765b6e4540L
    };

    /**
     * The rounding shift that splits the exponent into a table index and a remainder,
     * {@code 0x1.8p+52 / 32}.
     */
    private static final double EXP2_SHIFT = 0x1.8p+52 / EXP2_TABLE_SIZE;

    /**
     * The first coefficient of the polynomial approximating {@code 2^r}.
     */
    private static final double EXP2_C0 = 0x1.c6af84b912394p-5;

    /**
     * The second coefficient of the polynomial approximating {@code 2^r}.
     */
    private static final double EXP2_C1 = 0x1.ebfce50fac4f3p-3;

    /**
     * The third coefficient of the polynomial approximating {@code 2^r}.
     */
    private static final double EXP2_C2 = 0x1.62e42ff0c52d6p-1;

    /**
     * The bias added to the exponent to flip the sign of a negative power's result.
     */
    private static final int SIGN_BIAS = 1 << (5 + 11);

    /**
     * The high part of {@code ln 2} used by {@link #expm1f(float)}.
     */
    private static final float LN2_HI = 6.9313812256e-01f;

    /**
     * The low part of {@code ln 2} used by {@link #expm1f(float)}.
     */
    private static final float LN2_LO = 9.0580006145e-06f;

    /**
     * {@code 1 / ln 2} in single precision.
     */
    private static final float INV_LN2 = 1.4426950216e+00f;

    /**
     * The first scaled coefficient of the rational approximation of {@link #expm1f(float)}.
     */
    private static final float EXPM1_Q1 = -3.3333212137e-2f;

    /**
     * The second scaled coefficient of the rational approximation of {@link #expm1f(float)}.
     */
    private static final float EXPM1_Q2 = 1.5807170421e-3f;

    /**
     * The first coefficient of the cosine kernel polynomial.
     */
    private static final double COS_C0 = -0x1ffffffd0c5e81.0p-54;

    /**
     * The second coefficient of the cosine kernel polynomial.
     */
    private static final double COS_C1 = 0x155553e1053a42.0p-57;

    /**
     * The third coefficient of the cosine kernel polynomial.
     */
    private static final double COS_C2 = -0x16c087e80f1e27.0p-62;

    /**
     * The fourth coefficient of the cosine kernel polynomial.
     */
    private static final double COS_C3 = 0x199342e0ee5069.0p-68;

    /**
     * The first coefficient of the sine kernel polynomial.
     */
    private static final double SIN_S1 = -0x15555554cbac77.0p-55;

    /**
     * The second coefficient of the sine kernel polynomial.
     */
    private static final double SIN_S2 = 0x111110896efbb2.0p-59;

    /**
     * The third coefficient of the sine kernel polynomial.
     */
    private static final double SIN_S3 = -0x1a00f9e2cae774.0p-65;

    /**
     * The fourth coefficient of the sine kernel polynomial.
     */
    private static final double SIN_S4 = 0x16cd878c3b46a7.0p-71;

    /**
     * {@code pi/2} in double precision.
     */
    private static final double PI_2 = 1.57079632679489661923;

    /**
     * Prevents instantiation.
     */
    private FloatMath() {
        throw new AssertionError();
    }

    /**
     * Returns {@code x} raised to the power {@code y}, rounded exactly as musl's {@code powf}.
     *
     * <p>Special cases follow C99 Annex F: a zero exponent or a base of one gives one, a
     * negative finite base with a non-integer exponent gives NaN, and results beyond the float
     * range overflow to infinity or underflow to zero with the sign the exponent's parity
     * selects.
     *
     * @param x the base
     * @param y the exponent
     * @return {@code x^y}
     */
    public static float powf(float x, float y) {
        var signBias = 0;
        var ix = Float.floatToRawIntBits(x);
        var iy = Float.floatToRawIntBits(y);
        if (Integer.compareUnsigned(ix - 0x00800000, 0x7f800000 - 0x00800000) >= 0 || zeroInfNan(iy)) {
            if (zeroInfNan(iy)) {
                if (iy << 1 == 0) {
                    return 1.0f;
                }
                if (ix == 0x3f800000) {
                    return 1.0f;
                }
                if (Integer.compareUnsigned(ix << 1, 0xff000000) > 0 || Integer.compareUnsigned(iy << 1, 0xff000000) > 0) {
                    return x + y;
                }
                if (ix << 1 == 0x3f800000 << 1) {
                    return 1.0f;
                }
                if ((Integer.compareUnsigned(ix << 1, 0x3f800000 << 1) < 0) == ((iy & 0x80000000) == 0)) {
                    return 0.0f;
                }
                return y * y;
            }
            if (zeroInfNan(ix)) {
                var x2 = x * x;
                if ((ix & 0x80000000) != 0 && checkInt(iy) == 1) {
                    x2 = -x2;
                }
                return (iy & 0x80000000) != 0 ? 1 / x2 : x2;
            }
            if ((ix & 0x80000000) != 0) {
                var yint = checkInt(iy);
                if (yint == 0) {
                    return Float.NaN;
                }
                if (yint == 1) {
                    signBias = SIGN_BIAS;
                }
                ix &= 0x7fffffff;
            }
            if (ix < 0x00800000) {
                ix = Float.floatToRawIntBits(x * 0x1p23f);
                ix &= 0x7fffffff;
                ix -= 23 << 23;
            }
        }
        var logx = log2Inline(ix);
        var ylogx = y * logx;
        if (((Double.doubleToRawLongBits(ylogx) >>> 47) & 0xffff) >= (Double.doubleToRawLongBits(126.0) >>> 47)) {
            if (ylogx > 0x1.fffffffd1d571p+6) {
                return signBias != 0 ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            }
            if (ylogx <= -150.0) {
                return signBias != 0 ? -0.0f : 0.0f;
            }
        }
        return exp2Inline(ylogx, signBias);
    }

    /**
     * Returns whether a float bit pattern is a zero, an infinity or a NaN.
     *
     * @param ix the bit pattern
     * @return whether the value is zero, infinite or NaN
     */
    private static boolean zeroInfNan(int ix) {
        return Integer.compareUnsigned((ix << 1) - 1, 0xff000000 - 1) >= 0;
    }

    /**
     * Classifies the integrality of a non-zero finite float.
     *
     * @param iy the bit pattern of the value
     * @return {@code 0} when the value is not an integer, {@code 1} when it is an odd integer and
     *         {@code 2} when it is an even integer
     */
    private static int checkInt(int iy) {
        var e = (iy >>> 23) & 0xff;
        if (e < 0x7f) {
            return 0;
        }
        if (e > 0x7f + 23) {
            return 2;
        }
        if ((iy & ((1 << (0x7f + 23 - e)) - 1)) != 0) {
            return 0;
        }
        if ((iy & (1 << (0x7f + 23 - e))) != 0) {
            return 1;
        }
        return 2;
    }

    /**
     * Returns the base-2 logarithm of a positive normal float in double precision.
     *
     * @param ix the bit pattern of the argument, normalized so that subnormals have a negative
     *           biased exponent
     * @return {@code log2(x)}
     */
    private static double log2Inline(int ix) {
        var tmp = ix - LOG2_OFFSET;
        var i = (tmp >>> (23 - 4)) & (LOG2_TABLE_SIZE - 1);
        var top = tmp & 0xff800000;
        var iz = ix - top;
        var k = top >> 23;
        var invc = LOG2_INVC[i];
        var logc = LOG2_LOGC[i];
        var z = (double) Float.intBitsToFloat(iz);
        var r = z * invc - 1;
        var y0 = logc + (double) k;
        var r2 = r * r;
        var y = LOG2_A0 * r + LOG2_A1;
        var p = LOG2_A2 * r + LOG2_A3;
        var r4 = r2 * r2;
        var q = LOG2_A4 * r + y0;
        q = p * r2 + q;
        y = y * r4 + q;
        return y;
    }

    /**
     * Returns {@code 2^xd} rounded to float, with the sign bias of a negative result applied.
     *
     * @param xd       the exponent, in {@code [-150, 128]}
     * @param signBias {@link #SIGN_BIAS} for a negative result, {@code 0} otherwise
     * @return {@code 2^xd} with the selected sign
     */
    private static float exp2Inline(double xd, int signBias) {
        var kd = xd + EXP2_SHIFT;
        var ki = Double.doubleToRawLongBits(kd);
        kd -= EXP2_SHIFT;
        var r = xd - kd;
        var t = EXP2_TABLE[(int) (ki & (EXP2_TABLE_SIZE - 1))];
        var ski = ki + signBias;
        t += ski << (52 - 5);
        var s = Double.longBitsToDouble(t);
        var z = EXP2_C0 * r + EXP2_C1;
        var r2 = r * r;
        var y = EXP2_C2 * r + 1;
        y = z * r2 + y;
        y = y * s;
        return (float) y;
    }

    /**
     * Returns the hyperbolic tangent of {@code x}, rounded exactly as musl's {@code tanhf}.
     *
     * @param x the argument
     * @return {@code tanh(x)}
     */
    public static float tanhf(float x) {
        var bits = Float.floatToRawIntBits(x);
        var negative = bits < 0;
        var w = bits & 0x7fffffff;
        x = Float.intBitsToFloat(w);
        float t;
        if (w > 0x3f0c9f54) {
            if (w > 0x41200000) {
                t = 1 + 0 / x;
            } else {
                t = expm1f(2 * x);
                t = 1 - 2 / (t + 2);
            }
        } else if (w > 0x3e82c578) {
            t = expm1f(2 * x);
            t = t / (t + 2);
        } else if (w >= 0x00800000) {
            t = expm1f(-2 * x);
            t = -t / (t + 2);
        } else {
            t = x;
        }
        return negative ? -t : t;
    }

    /**
     * Returns {@code e^x - 1}, rounded exactly as musl's {@code expm1f}.
     *
     * @param x the argument
     * @return {@code e^x - 1}
     */
    public static float expm1f(float x) {
        var bits = Float.floatToRawIntBits(x);
        var hx = bits & 0x7fffffff;
        var negative = bits < 0;
        if (hx >= 0x4195b844) {
            if (hx > 0x7f800000) {
                return x;
            }
            if (negative) {
                return -1;
            }
            if (hx > 0x42b17217) {
                x *= 0x1p127f;
                return x;
            }
        }
        float hi;
        float lo;
        float c = 0;
        int k;
        if (hx > 0x3eb17218) {
            if (hx < 0x3F851592) {
                if (!negative) {
                    hi = x - LN2_HI;
                    lo = LN2_LO;
                    k = 1;
                } else {
                    hi = x + LN2_HI;
                    lo = -LN2_LO;
                    k = -1;
                }
            } else {
                k = (int) (INV_LN2 * x + (negative ? -0.5f : 0.5f));
                float t = k;
                hi = x - t * LN2_HI;
                lo = t * LN2_LO;
            }
            x = hi - lo;
            c = (hi - x) - lo;
        } else if (hx < 0x33000000) {
            return x;
        } else {
            k = 0;
        }
        var hfx = 0.5f * x;
        var hxs = x * hfx;
        var r1 = 1.0f + hxs * (EXPM1_Q1 + hxs * EXPM1_Q2);
        var t = 3.0f - r1 * hfx;
        var e = hxs * ((r1 - t) / (6.0f - x * t));
        if (k == 0) {
            return x - (x * e - hxs);
        }
        e = x * (e - c) - c;
        e -= hxs;
        if (k == -1) {
            return 0.5f * (x - e) - 0.5f;
        }
        if (k == 1) {
            if (x < -0.25f) {
                return -2.0f * (e - (x + 0.5f));
            }
            return 1.0f + 2.0f * (x - e);
        }
        var twopk = Float.intBitsToFloat((0x7f + k) << 23);
        float y;
        if (k < 0 || k > 56) {
            y = x - e + 1.0f;
            if (k == 128) {
                y = y * 2.0f * 0x1p127f;
            } else {
                y = y * twopk;
            }
            return y - 1.0f;
        }
        var inverse = Float.intBitsToFloat((0x7f - k) << 23);
        if (k < 23) {
            y = (x - e + (1 - inverse)) * twopk;
        } else {
            y = (x - (e + inverse) + 1) * twopk;
        }
        return y;
    }

    /**
     * Returns the cosine of {@code x}, rounded exactly as musl's {@code cosf}, for
     * {@code |x| <= 3*pi/4}.
     *
     * @param x the argument, in radians
     * @return {@code cos(x)}
     * @throws IllegalArgumentException if {@code |x|} exceeds {@code 3*pi/4}, which the
     *                                  transform tables never need
     */
    public static float cosf(float x) {
        var bits = Float.floatToRawIntBits(x);
        var ix = bits & 0x7fffffff;
        if (ix <= 0x3f490fda) {
            if (ix < 0x39800000) {
                return 1.0f;
            }
            return cosKernel(x);
        }
        if (ix <= 0x4016cbe3) {
            return bits < 0 ? sinKernel(x + PI_2) : sinKernel(PI_2 - x);
        }
        throw new IllegalArgumentException("cosf argument outside [-3*pi/4, 3*pi/4]: " + x);
    }

    /**
     * Returns the sine of {@code x}, rounded exactly as musl's {@code sinf}, for
     * {@code |x| <= 3*pi/4}.
     *
     * @param x the argument, in radians
     * @return {@code sin(x)}
     * @throws IllegalArgumentException if {@code |x|} exceeds {@code 3*pi/4}, which the
     *                                  transform tables never need
     */
    public static float sinf(float x) {
        var bits = Float.floatToRawIntBits(x);
        var ix = bits & 0x7fffffff;
        if (ix <= 0x3f490fda) {
            if (ix < 0x39800000) {
                return x;
            }
            return sinKernel(x);
        }
        if (ix <= 0x4016cbe3) {
            return bits < 0 ? -cosKernel(x + PI_2) : cosKernel(x - PI_2);
        }
        throw new IllegalArgumentException("sinf argument outside [-3*pi/4, 3*pi/4]: " + x);
    }

    /**
     * Evaluates the cosine kernel polynomial on {@code [-pi/4, pi/4]}.
     *
     * @param x the argument, in radians
     * @return {@code cos(x)} rounded to float
     */
    private static float cosKernel(double x) {
        var z = x * x;
        var w = z * z;
        var r = COS_C2 + z * COS_C3;
        return (float) (((1.0 + z * COS_C0) + w * COS_C1) + (w * z) * r);
    }

    /**
     * Evaluates the sine kernel polynomial on {@code [-pi/4, pi/4]}.
     *
     * @param x the argument, in radians
     * @return {@code sin(x)} rounded to float
     */
    private static float sinKernel(double x) {
        var z = x * x;
        var w = z * z;
        var r = SIN_S3 + z * SIN_S4;
        var s = z * x;
        return (float) ((x + s * (SIN_S1 + z * SIN_S2)) + s * w * r);
    }

    /**
     * Returns {@code atanf(1.0f)}, the float nearest {@code pi/4}, as musl's {@code atanf}
     * computes it.
     *
     * @return {@code pi/4} rounded to float
     */
    public static float atanOne() {
        return 0.785398185f;
    }

    /**
     * Returns the fast base-2 logarithm approximation WebRTC computes from a float's bit pattern.
     *
     * <p>The bit pattern of {@code x}, read as an unsigned integer, is converted to float and
     * scaled by {@code 2^-23}, then the exponent bias {@code 126.942695} is subtracted.
     *
     * @param x a positive argument
     * @return the approximation of {@code log2(x)}
     */
    public static float fastLog2f(float x) {
        float out = Integer.toUnsignedLong(Float.floatToRawIntBits(x));
        out *= 1.1920929e-7f;
        out -= 126.942695f;
        return out;
    }

    /**
     * Returns the larger of two floats as {@code std::max} does: {@code a} unless
     * {@code a < b}.
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a < b ? b : a}
     */
    public static float max(float a, float b) {
        return a < b ? b : a;
    }

    /**
     * Returns the smaller of two floats as {@code std::min} does: {@code a} unless
     * {@code b < a}.
     *
     * @param a the first value
     * @param b the second value
     * @return {@code b < a ? b : a}
     */
    public static float min(float a, float b) {
        return b < a ? b : a;
    }
}
