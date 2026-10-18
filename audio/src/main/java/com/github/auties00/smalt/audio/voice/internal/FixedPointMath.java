/* Ports webrtc-audio-processing 2.1 (common_audio/signal_processing). Copyright (c) 2011, The WebRTC project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal;

/**
 * Computes the fixed-point primitives of the gain controller and the mobile echo canceller with
 * the exact results of WebRTC's signal processing library.
 *
 * <p>Both components keep their state in 16-bit and 32-bit integers and rely on the C integer
 * semantics of the reference: two's complement wrap-around, arithmetic right shifts of negative
 * values, truncating division and the narrowing conversions of {@code (int16_t)} casts. Every
 * method here reproduces one function or macro of the library with the same widths, so a port
 * that calls them in the reference's order computes the same bits.
 *
 * @implNote This implementation ports the generic C paths of WebRTC's
 * {@code common_audio/signal_processing} (webrtc-audio-processing 2.1: {@code spl_inl.h},
 * {@code division_operations.c}, {@code spl_sqrt.c}, {@code resample_by_2.c},
 * {@code dot_product_with_scale.cc}, {@code min_max_operations.c},
 * {@code randomization_functions.c} and the macros of {@code signal_processing_library.h}),
 * not the ARMv7, NEON or MIPS variants, which the WebAssembly reference does not build.
 */
public final class FixedPointMath {
    /**
     * The coefficients of the upper all-pass branch of {@link #downsampleBy2}.
     */
    private static final int[] ALLPASS_1 = {3284, 24441, 49528};

    /**
     * The coefficients of the lower all-pass branch of {@link #downsampleBy2}.
     */
    private static final int[] ALLPASS_2 = {12199, 37471, 60255};

    /**
     * Prevents instantiation.
     */
    private FixedPointMath() {
    }

    /**
     * Saturates a 32-bit value to the 16-bit range ({@code WebRtcSpl_SatW32ToW16}).
     *
     * @param value the 32-bit value
     * @return {@code value} clamped to {@code [-32768, 32767]}
     */
    private static short satW32ToW16(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    /**
     * Adds two 16-bit values with saturation ({@code WebRtcSpl_AddSatW16}).
     *
     * @param a the first addend
     * @param b the second addend
     * @return the saturated sum
     */
    public static short addSatW16(int a, int b) {
        return satW32ToW16((short) a + (short) b);
    }

    /**
     * Adds two 32-bit values with saturation ({@code WebRtcSpl_AddSatW32}).
     *
     * @param a the first addend
     * @param b the second addend
     * @return the sum clamped to the 32-bit range
     */
    public static int addSatW32(int a, int b) {
        var sum = a + b;
        if (a < 0 == b < 0 && sum < 0 != a < 0) {
            return a < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        return sum;
    }

    /**
     * Returns how far a signed 32-bit value can be shifted left without overflow
     * ({@code WebRtcSpl_NormW32}).
     *
     * @param a the value
     * @return the number of redundant sign bits, or {@code 0} for {@code 0}
     */
    public static int normW32(int a) {
        return a == 0 ? 0 : Integer.numberOfLeadingZeros(a < 0 ? ~a : a) - 1;
    }

    /**
     * Returns how far an unsigned 32-bit value can be shifted left without overflow
     * ({@code WebRtcSpl_NormU32}).
     *
     * @param a the value, read as unsigned
     * @return the number of leading zero bits, or {@code 0} for {@code 0}
     */
    public static int normU32(int a) {
        return a == 0 ? 0 : Integer.numberOfLeadingZeros(a);
    }

    /**
     * Returns how far a signed 16-bit value can be shifted left without overflow
     * ({@code WebRtcSpl_NormW16}).
     *
     * @param a the value, read as a 16-bit integer
     * @return the number of redundant sign bits, or {@code 0} for {@code 0}
     */
    public static int normW16(int a) {
        var value = (short) a;
        return value == 0 ? 0 : Integer.numberOfLeadingZeros(value < 0 ? ~value : value) - 17;
    }

    /**
     * Divides a 32-bit value by a 16-bit one, truncating ({@code WebRtcSpl_DivW32W16}).
     *
     * @param num the dividend
     * @param den the divisor, read as a 16-bit integer
     * @return the quotient, or {@code 0x7FFFFFFF} when {@code den} is zero
     */
    public static int divW32W16(int num, int den) {
        var divisor = (short) den;
        return divisor != 0 ? num / divisor : Integer.MAX_VALUE;
    }

    /**
     * Divides a 32-bit value by a 16-bit one and narrows the quotient to 16 bits
     * ({@code WebRtcSpl_DivW32W16ResW16}).
     *
     * @param num the dividend
     * @param den the divisor, read as a 16-bit integer
     * @return the quotient wrapped to 16 bits, or {@code 0x7FFF} when {@code den} is zero
     */
    public static short divW32W16ResW16(int num, int den) {
        var divisor = (short) den;
        return divisor != 0 ? (short) (num / divisor) : Short.MAX_VALUE;
    }

    /**
     * Divides an unsigned 32-bit value by an unsigned 16-bit one ({@code WebRtcSpl_DivU32U16}).
     *
     * @param num the dividend, read as unsigned
     * @param den the divisor, read as unsigned 16-bit
     * @return the quotient, or {@code 0xFFFFFFFF} when {@code den} is zero
     */
    public static int divU32U16(int num, int den) {
        var divisor = den & 0xFFFF;
        return divisor != 0 ? Integer.divideUnsigned(num, divisor) : 0xFFFFFFFF;
    }

    /**
     * Returns {@code c + (b * a >> 16)} computed in two halves ({@code WEBRTC_SPL_SCALEDIFF32}),
     * where {@code a} is a non-negative coefficient below {@code 2^16}.
     *
     * @param a the coefficient
     * @param b the 32-bit value
     * @param c the accumulator
     * @return the scaled sum, wrapped to 32 bits
     */
    private static int scaleDiff32(int a, int b, int c) {
        return c + (b >> 16) * a + (((b & 0xFFFF) * a) >>> 16);
    }

    /**
     * Returns the integer square root of a 32-bit value ({@code WebRtcSpl_Sqrt}), a six-term
     * Taylor approximation in fixed point.
     *
     * @param value the value; negative values are negated first
     * @return the approximate square root
     */
    public static int sqrt(int value) {
        var a = value;
        if (a < 0) {
            a = a == Integer.MIN_VALUE ? Integer.MAX_VALUE : -a;
        } else if (a == 0) {
            return 0;
        }
        var sh = normW32(a);
        a <<= sh;
        if (a < Integer.MAX_VALUE - 32767) {
            a += 32768;
        } else {
            a = Integer.MAX_VALUE;
        }
        var xNorm = (short) (a >> 16);
        var nshift = sh / 2;
        a = xNorm << 16;
        a = a >= 0 ? a : -a;
        a = sqrtLocal(a);
        if (2 * nshift == sh) {
            var t16 = (short) (a >> 16);
            a = 23170 * t16 * 2;
            a += 32768;
            a &= 0x7fff0000;
            a >>= 15;
        } else {
            a >>= 16;
        }
        a &= 0x0000ffff;
        a >>= nshift;
        return a;
    }

    /**
     * Evaluates the series of {@link #sqrt(int)} on a normalized argument
     * ({@code WebRtcSpl_SqrtLocal}).
     *
     * @param in the normalized argument
     * @return the square root in Q31
     */
    private static int sqrtLocal(int in) {
        var b = in / 2;
        b -= 0x40000000;
        var xHalf = (short) (b >> 16);
        b += 0x40000000;
        b += 0x40000000;
        var x2 = xHalf * xHalf * 2;
        var a = -x2;
        b += a >> 1;
        a >>= 16;
        a = a * a * 2;
        var t16 = (short) (a >> 16);
        b += -20480 * t16 * 2;
        a = xHalf * t16 * 2;
        t16 = (short) (a >> 16);
        b += 28672 * t16 * 2;
        t16 = (short) (x2 >> 16);
        a = xHalf * t16 * 2;
        b += a >> 1;
        b += 32768;
        return b;
    }

    /**
     * Halves the sampling rate of a block with the two-branch all-pass decimator of WebRTC's
     * signal processing library ({@code WebRtcSpl_DownsampleBy2}).
     *
     * @param in the input samples
     * @param inOffset the index of the first input sample
     * @param length the number of input samples, even
     * @param out receives {@code length / 2} samples
     * @param outOffset the index of the first output sample
     * @param state the eight filter states, updated in place
     */
    public static void downsampleBy2(short[] in, int inOffset, int length, short[] out, int outOffset, int[] state) {
        var state0 = state[0];
        var state1 = state[1];
        var state2 = state[2];
        var state3 = state[3];
        var state4 = state[4];
        var state5 = state[5];
        var state6 = state[6];
        var state7 = state[7];
        var input = inOffset;
        var output = outOffset;
        for (var i = length >> 1; i > 0; i--) {
            var in32 = in[input++] * (1 << 10);
            var diff = in32 - state1;
            var tmp1 = scaleDiff32(ALLPASS_2[0], diff, state0);
            state0 = in32;
            diff = tmp1 - state2;
            var tmp2 = scaleDiff32(ALLPASS_2[1], diff, state1);
            state1 = tmp1;
            diff = tmp2 - state3;
            state3 = scaleDiff32(ALLPASS_2[2], diff, state2);
            state2 = tmp2;

            in32 = in[input++] * (1 << 10);
            diff = in32 - state5;
            tmp1 = scaleDiff32(ALLPASS_1[0], diff, state4);
            state4 = in32;
            diff = tmp1 - state6;
            tmp2 = scaleDiff32(ALLPASS_1[1], diff, state5);
            state5 = tmp1;
            diff = tmp2 - state7;
            state7 = scaleDiff32(ALLPASS_1[2], diff, state6);
            state6 = tmp2;

            var out32 = (state3 + state7 + 1024) >> 11;
            out[output++] = satW32ToW16(out32);
        }
        state[0] = state0;
        state[1] = state1;
        state[2] = state2;
        state[3] = state3;
        state[4] = state4;
        state[5] = state5;
        state[6] = state6;
        state[7] = state7;
    }

    /**
     * Returns the dot product of two 16-bit vectors with every product shifted right before it is
     * summed, saturated to 32 bits ({@code WebRtcSpl_DotProductWithScale}).
     *
     * @param a the first vector
     * @param aOffset the index of the first element of {@code a}
     * @param b the second vector
     * @param bOffset the index of the first element of {@code b}
     * @param length the number of elements
     * @param scaling the right shift applied to each product
     * @return the saturated sum
     */
    public static int dotProductWithScale(short[] a, int aOffset, short[] b, int bOffset, int length, int scaling) {
        long sum = 0;
        for (var i = 0; i < length; i++) {
            sum += (a[aOffset + i] * b[bOffset + i]) >> scaling;
        }
        return (int) Math.clamp(sum, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns the largest absolute value of a 16-bit vector, capped at {@code 32767}
     * ({@code WebRtcSpl_MaxAbsValueW16}).
     *
     * @param vector the 16-bit samples
     * @param offset the index of the first element
     * @param length the number of elements, positive
     * @return the largest absolute value
     */
    public static short maxAbsValueW16(short[] vector, int offset, int length) {
        var maximum = 0;
        for (var i = 0; i < length; i++) {
            var absolute = Math.abs(vector[offset + i]);
            if (absolute > maximum) {
                maximum = absolute;
            }
        }
        return (short) Math.min(maximum, Short.MAX_VALUE);
    }

    /**
     * Converts a sample in the 16-bit float range to a 16-bit integer, clamping and rounding half
     * away from zero ({@code FloatS16ToS16} of {@code audio_util.h}).
     *
     * @param v the sample
     * @return the rounded sample
     */
    public static short floatS16ToS16(float v) {
        v = Math.min(v, 32767.f);
        v = Math.max(v, -32768.f);
        return (short) (int) (v + Math.copySign(0.5f, v));
    }
}
