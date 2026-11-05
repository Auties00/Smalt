/* Ports libspeex 1.2.1 (libspeex/fixed_generic.h, libspeex/math_approx.h). Copyright 2002-2008 Xiph.org Foundation,
   Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

/**
 * The fixed-point arithmetic of the Speex reference decoder, operation for operation: 16-bit operands are the low
 * 16 bits of their argument, as the reference's casts make them, and 32-bit results wrap.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class SpeexFixed {
    private SpeexFixed() {
    }

    /**
     * {@return a constant in Q{@code bits}, truncated toward zero after adding one half ({@code QCONST16})}
     */
    static short q16(double value, int bits) {
        return (short) (int) (.5 + value * (1 << bits));
    }

    /**
     * {@return a constant in Q{@code bits} ({@code QCONST32})}
     */
    static int q32(double value, int bits) {
        return (int) (.5 + value * (1 << bits));
    }

    static int mult16_16(int a, int b) {
        return (short) a * (short) b;
    }

    static int mac16_16(int c, int a, int b) {
        return c + (short) a * (short) b;
    }

    static int mult16_16_q13(int a, int b) {
        return mult16_16(a, b) >> 13;
    }

    static int mult16_16_q14(int a, int b) {
        return mult16_16(a, b) >> 14;
    }

    static int mult16_16_q15(int a, int b) {
        return mult16_16(a, b) >> 15;
    }

    static int mult16_16_p13(int a, int b) {
        return (4096 + mult16_16(a, b)) >> 13;
    }

    static int mult16_16_p14(int a, int b) {
        return (8192 + mult16_16(a, b)) >> 14;
    }

    static int mult16_16_p15(int a, int b) {
        return (16384 + mult16_16(a, b)) >> 15;
    }

    static int mult16_16_16(int a, int b) {
        return (short) a * (short) b;
    }

    static int mult16_32_q14(int a, int b) {
        return mult16_16(a, b >> 14) + (mult16_16(a, b & 0x3fff) >> 14);
    }

    static int mult16_32_q15(int a, int b) {
        return mult16_16(a, b >> 15) + (mult16_16(a, b & 0x7fff) >> 15);
    }

    static int mult16_32_p15(int a, int b) {
        return mult16_16(a, b >> 15) + pshr32(mult16_16(a, b & 0x7fff), 15);
    }

    static int add16(int a, int b) {
        return (short) ((short) a + (short) b);
    }

    static int sub16(int a, int b) {
        return (short) a - (short) b;
    }

    static int shl16(int a, int shift) {
        return (short) ((a & 0xFFFF) << shift);
    }

    static int shl32(int a, int shift) {
        return a << shift;
    }

    static int pshr16(int a, int shift) {
        return (a + ((1 << shift) >> 1)) >> shift;
    }

    static int pshr32(int a, int shift) {
        return (a + ((1 << shift) >> 1)) >> shift;
    }

    static int vshr32(int a, int shift) {
        return shift > 0 ? a >> shift : a << -shift;
    }

    static int saturate(int x, int a) {
        return x > a ? a : x < -a ? -a : x;
    }

    /**
     * {@return {@code a / b} with the divisor taken as 16 bits and the quotient truncated to 16 bits
     * ({@code DIV32_16})}
     */
    static int div32_16(int a, int b) {
        return (short) (a / (short) b);
    }

    /**
     * {@return {@code (a + b / 2) / b} with the divisor taken as 16 bits and the quotient truncated to 16 bits
     * ({@code PDIV32_16})}
     */
    static int pdiv32_16(int a, int b) {
        return (short) ((a + ((short) b >> 1)) / (short) b);
    }

    static int pdiv32(int a, int b) {
        return (a + ((short) b >> 1)) / b;
    }

    /**
     * {@return the floor of the base-4 logarithm of an unsigned value ({@code spx_ilog4})}
     */
    static int ilog4(int x) {
        var r = 0;
        if (Integer.compareUnsigned(x, 65536) >= 0) {
            x >>>= 16;
            r += 8;
        }
        if (Integer.compareUnsigned(x, 256) >= 0) {
            x >>>= 8;
            r += 4;
        }
        if (Integer.compareUnsigned(x, 16) >= 0) {
            x >>>= 4;
            r += 2;
        }
        if (Integer.compareUnsigned(x, 4) >= 0) {
            r += 1;
        }
        return r;
    }

    /**
     * {@return the approximate square root of a value, 16 bits ({@code spx_sqrt})}
     */
    static int sqrt(int x) {
        var k = ilog4(x) - 6;
        x = vshr32(x, k << 1);
        var rt = add16(3634, mult16_16_q14(x, add16(21173, mult16_16_q14(x, add16(-12627, mult16_16_q14(x, 4204))))));
        rt = vshr32(rt, 7 - k);
        return (short) rt;
    }

    /**
     * {@return the approximate cosine of an angle in Q13, in Q14, 16 bits ({@code spx_cos})}
     */
    static int cos(int x) {
        x = (short) x;
        if (x < 12868) {
            var x2 = (short) mult16_16_p13(x, x);
            return (short) (8192 + mult16_16_p13(x2, -4096 + mult16_16_p13(x2, 340 + mult16_16_p13(-10, x2))));
        }
        x = (short) sub16(25736, x);
        var x2 = (short) mult16_16_p13(x, x);
        return (short) (-8192 - mult16_16_p13(x2, -4096 + mult16_16_p13(x2, 340 + mult16_16_p13(-10, x2))));
    }

    /**
     * {@return 2 to the power of a Q11 value, in Q16 ({@code spx_exp2})}
     */
    static int exp2(int x) {
        x = (short) x;
        var integer = x >> 11;
        if (integer > 14) {
            return 0x7fffffff;
        }
        if (integer < -15) {
            return 0;
        }
        var frac = shl16(x - shl16(integer, 11), 3);
        frac = add16(16384, mult16_16_q14(frac, add16(11356, mult16_16_q14(frac, add16(3726,
                mult16_16_q14(1301, frac))))));
        return vshr32(frac, -integer - 2);
    }

    /**
     * {@return e to the power of a Q11 value, in Q16 ({@code spx_exp})}
     */
    static int exp(int x) {
        x = (short) x;
        if (x > 21290) {
            return 0x7fffffff;
        }
        if (x < -21290) {
            return 0;
        }
        return exp2(mult16_16_p14(23637, x));
    }
}
