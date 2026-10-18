/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: the generic transforms). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

/**
 * Turns the dequantized coefficients of a varblock into samples (ISO/IEC 18181-1 sections I.6.2
 * to I.6.4): the low-frequency coefficients are derived from the LF image, then the inverse of the
 * varblock's transform runs in place.
 *
 * <p>Coordinates follow the coefficient layout: {@code buf[off + y * stride + x]} holds column
 * {@code x} and row {@code y} of the block. Every method writes only the block it is given.
 *
 * <p>DCT2, DCT4 and Hornuss use a 2x2 auxiliary inverse; DCT4 transposes its 4x4 sub-blocks,
 * DCT4x8 and DCT8x4 work in 4x8 halves, and AFV uses 16 basis functions.
 */
public final class Varblocks {
    /**
     * The 16 basis functions of the AFV 4x4 corner (ISO/IEC 18181-1 table I.8), 16 samples each.
     */
    private static final float[][] AFV_BASIS = {
            {0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f,
                    0.25f, 0.25f},
            {0.876902929799142f, 0.2206518106944235f, -0.10140050393753763f, -0.1014005039375375f,
                    0.2206518106944236f, -0.10140050393753777f, -0.10140050393753772f, -0.10140050393753763f,
                    -0.10140050393753758f, -0.10140050393753769f, -0.1014005039375375f, -0.10140050393753768f,
                    -0.10140050393753768f, -0.10140050393753759f, -0.10140050393753763f, -0.10140050393753741f},
            {0.0f, 0.0f, 0.40670075830260755f, 0.44444816619734445f, 0.0f, 0.0f, 0.19574399372042936f,
                    0.2929100136981264f, -0.40670075830260716f, -0.19574399372042872f, 0.0f, 0.11379074460448091f,
                    -0.44444816619734384f, -0.29291001369812636f, -0.1137907446044814f, 0.0f},
            {0.0f, 0.0f, -0.21255748058288748f, 0.3085497062849767f, 0.0f, 0.4706702258572536f,
                    -0.1621205195722993f, 0.0f, -0.21255748058287047f, -0.16212051957228327f, -0.47067022585725277f,
                    -0.1464291867126764f, 0.3085497062849487f, 0.0f, -0.14642918671266536f, 0.4251149611657548f},
            {0.0f, -0.7071067811865474f, 0.0f, 0.0f, 0.70710678118654752f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {-0.4105377591765233f, 0.6235485373547691f, -0.06435071657946274f, -0.06435071657946266f,
                    0.6235485373547694f, -0.06435071657946284f, -0.0643507165794628f, -0.06435071657946274f,
                    -0.06435071657946272f, -0.06435071657946279f, -0.06435071657946266f, -0.06435071657946277f,
                    -0.06435071657946277f, -0.06435071657946273f, -0.06435071657946274f, -0.0643507165794626f},
            {0.0f, 0.0f, -0.4517556589999482f, 0.15854503551840063f, 0.0f, -0.04038515160822202f,
                    0.0074182263792423875f, 0.39351034269210167f, -0.45175565899994635f, 0.007418226379244351f,
                    0.1107416575309343f, 0.08298163094882051f, 0.15854503551839705f, 0.3935103426921022f,
                    0.0829816309488214f, -0.45175565899994796f},
            {0.0f, 0.0f, -0.304684750724869f, 0.5112616136591823f, 0.0f, 0.0f, -0.290480129728998f,
                    -0.06578701549142804f, 0.304684750724884f, 0.2904801297290076f, 0.0f, -0.23889773523344604f,
                    -0.5112616136592012f, 0.06578701549142545f, 0.23889773523345467f, 0.0f},
            {0.0f, 0.0f, 0.3017929516615495f, 0.25792362796341184f, 0.0f, 0.16272340142866204f,
                    0.09520022653475037f, 0.0f, 0.3017929516615503f, 0.09520022653475055f, -0.16272340142866173f,
                    -0.35312385449816297f, 0.25792362796341295f, 0.0f, -0.3531238544981624f, -0.6035859033230976f},
            {0.0f, 0.0f, 0.40824829046386274f, 0.0f, 0.0f, 0.0f, 0.0f, -0.4082482904638628f, -0.4082482904638635f,
                    0.0f, 0.0f, -0.40824829046386296f, 0.0f, 0.4082482904638634f, 0.408248290463863f, 0.0f},
            {0.0f, 0.0f, 0.1747866975480809f, 0.0812611176717539f, 0.0f, 0.0f, -0.3675398009862027f,
                    -0.307882213957909f, -0.17478669754808135f, 0.3675398009862011f, 0.0f, 0.4826689115059883f,
                    -0.08126111767175039f, 0.30788221395790305f, -0.48266891150598584f, 0.0f},
            {0.0f, 0.0f, -0.21105601049335784f, 0.18567180916109802f, 0.0f, 0.0f, 0.49215859013738733f,
                    -0.38525013709251915f, 0.21105601049335806f, -0.49215859013738905f, 0.0f, 0.17419412659916217f,
                    -0.18567180916109904f, 0.3852501370925211f, -0.1741941265991621f, 0.0f},
            {0.0f, 0.0f, -0.14266084808807264f, -0.3416446842253372f, 0.0f, 0.7367497537172237f,
                    0.24627107722075148f, -0.08574019035519306f, -0.14266084808807344f, 0.24627107722075137f,
                    0.14883399227113567f, -0.04768680350229251f, -0.3416446842253373f, -0.08574019035519267f,
                    -0.047686803502292804f, -0.14266084808807242f},
            {0.0f, 0.0f, -0.13813540350758585f, 0.3302282550303788f, 0.0f, 0.08755115000587084f,
                    -0.07946706605909573f, -0.4613374887461511f, -0.13813540350758294f, -0.07946706605910261f,
                    0.49724647109535086f, 0.12538059448563663f, 0.3302282550303805f, -0.4613374887461554f,
                    0.12538059448564315f, -0.13813540350758452f},
            {0.0f, 0.0f, -0.17437602599651067f, 0.0702790691196284f, 0.0f, -0.2921026642334881f,
                    0.3623817333531167f, 0.0f, -0.1743760259965108f, 0.36238173335311646f, 0.29210266423348785f,
                    -0.4326608024727445f, 0.07027906911962818f, 0.0f, -0.4326608024727457f, 0.34875205199302267f},
            {0.0f, 0.0f, 0.11354987314994337f, -0.07417504595810355f, 0.0f, 0.19402893032594343f,
                    -0.435190496523228f, 0.21918684838857466f, 0.11354987314994257f, -0.4351904965232251f,
                    0.5550443808910661f, -0.25468277124066463f, -0.07417504595810233f, 0.2191868483885728f,
                    -0.25468277124066413f, 0.1135498731499429f},
    };

    /**
     * Prevents instantiation.
     */
    private Varblocks() {
        throw new AssertionError();
    }

    /**
     * Fills the low-frequency coefficients of a varblock from the LF image and applies its inverse
     * transform.
     *
     * @param type the transform type
     * @param buf the coefficients, replaced by samples
     * @param off the index of the block's top-left coefficient
     * @param stride the row stride of {@code buf}
     * @param lf the LF samples
     * @param lfOff the index of the block's first LF sample
     * @param lfStride the row stride of {@code lf}
     * @param scratch a scratch buffer of at least 1024 elements
     */
    public static void transform(int type, float[] buf, int off, int stride, float[] lf, int lfOff, int lfStride,
                                 float[] scratch) {
        var bw = TransformType.width(type);
        var bh = TransformType.height(type);
        if (bw == 1 && bh == 1) {
            buf[off] = lf[lfOff];
        } else {
            for (var y = 0; y < bh; y++) {
                for (var x = 0; x < bw; x++) {
                    buf[off + y * stride + x] = lf[lfOff + y * lfStride + x];
                }
            }
            Dct.dct2d(buf, off, stride, bw, bh, scratch, false);
            var logbw = Integer.numberOfTrailingZeros(bw);
            var logbh = Integer.numberOfTrailingZeros(bh);
            for (var y = 0; y < bh; y++) {
                for (var x = 0; x < bw; x++) {
                    buf[off + y * stride + x] /= Dct.scaleF(y, 5 - logbh) * Dct.scaleF(x, 5 - logbw);
                }
            }
        }
        switch (type) {
            case TransformType.DCT2 -> dct2(buf, off, stride);
            case TransformType.DCT4 -> dct4(buf, off, stride, scratch);
            case TransformType.HORNUSS -> hornuss(buf, off, stride);
            case TransformType.DCT4X8 -> dct4x8(buf, off, stride, false, scratch);
            case TransformType.DCT8X4 -> dct4x8(buf, off, stride, true, scratch);
            case TransformType.AFV0, TransformType.AFV1, TransformType.AFV2, TransformType.AFV3 ->
                    afv(buf, off, stride, type - TransformType.AFV0, scratch);
            default -> Dct.dct2d(buf, off, stride, bw * 8, bh * 8, scratch, true);
        }
    }

    /**
     * Applies the 2x2 auxiliary inverse to the {@code size x size} corner of a block.
     *
     * @param buf the block
     * @param off the index of the top-left coefficient
     * @param stride the row stride
     * @param size the corner size: 2, 4 or 8
     */
    private static void auxIdct2(float[] buf, int off, int stride, int size) {
        var half = size / 2;
        var tmp = new float[size * size];
        for (var y = 0; y < half; y++) {
            for (var x = 0; x < half; x++) {
                var c00 = buf[off + y * stride + x];
                var c01 = buf[off + y * stride + x + half];
                var c10 = buf[off + (y + half) * stride + x];
                var c11 = buf[off + (y + half) * stride + x + half];
                tmp[(2 * y) * size + 2 * x] = c00 + c01 + c10 + c11;
                tmp[(2 * y) * size + 2 * x + 1] = c00 + c01 - c10 - c11;
                tmp[(2 * y + 1) * size + 2 * x] = c00 - c01 + c10 - c11;
                tmp[(2 * y + 1) * size + 2 * x + 1] = c00 - c01 - c10 + c11;
            }
        }
        for (var y = 0; y < size; y++) {
            System.arraycopy(tmp, y * size, buf, off + y * stride, size);
        }
    }

    /**
     * Inverts the DCT2 transform.
     *
     * @param buf the block
     * @param off the index of the top-left coefficient
     * @param stride the row stride
     */
    private static void dct2(float[] buf, int off, int stride) {
        auxIdct2(buf, off, stride, 2);
        auxIdct2(buf, off, stride, 4);
        auxIdct2(buf, off, stride, 8);
    }

    /**
     * Inverts the DCT4 transform: four 4x4 inverse DCTs on interleaved coefficients.
     *
     * @param buf the block
     * @param off the index of the top-left coefficient
     * @param stride the row stride
     * @param scratch the scratch buffer
     */
    private static void dct4(float[] buf, int off, int stride, float[] scratch) {
        auxIdct2(buf, off, stride, 2);
        var sub = new float[64];
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 2; x++) {
                var base = (y * 2 + x) * 16;
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        sub[base + ix * 4 + iy] = buf[off + (y + iy * 2) * stride + x + ix * 2];
                    }
                }
                Dct.dct2d(sub, base, 4, 4, 4, scratch, true);
            }
        }
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 2; x++) {
                var base = (y * 2 + x) * 16;
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        buf[off + (y * 4 + iy) * stride + x * 4 + ix] = sub[base + iy * 4 + ix];
                    }
                }
            }
        }
    }

    /**
     * Inverts the Hornuss transform.
     *
     * @param buf the block
     * @param off the index of the top-left coefficient
     * @param stride the row stride
     */
    private static void hornuss(float[] buf, int off, int stride) {
        auxIdct2(buf, off, stride, 2);
        var sub = new float[64];
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 2; x++) {
                var base = (y * 2 + x) * 16;
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        sub[base + iy * 4 + ix] = buf[off + (y + iy * 2) * stride + x + ix * 2];
                    }
                }
                var residualSum = 0.0f;
                for (var i = 1; i < 16; i++) {
                    residualSum += sub[base + i];
                }
                var avg = sub[base] - residualSum / 16.0f;
                sub[base] = sub[base + 5];
                sub[base + 5] = 0.0f;
                for (var i = 0; i < 16; i++) {
                    sub[base + i] += avg;
                }
            }
        }
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 2; x++) {
                var base = (y * 2 + x) * 16;
                for (var iy = 0; iy < 4; iy++) {
                    for (var ix = 0; ix < 4; ix++) {
                        buf[off + (y * 4 + iy) * stride + x * 4 + ix] = sub[base + iy * 4 + ix];
                    }
                }
            }
        }
    }

    /**
     * Inverts the DCT4x8 transform (two 8-wide, 4-tall halves) or, transposed, DCT8x4.
     *
     * @param buf the block
     * @param off the index of the top-left coefficient
     * @param stride the row stride
     * @param transposed whether the halves are placed side by side (DCT8x4)
     * @param scratch the scratch buffer
     */
    private static void dct4x8(float[] buf, int off, int stride, boolean transposed, float[] scratch) {
        var c0 = buf[off];
        var c1 = buf[off + stride];
        buf[off] = c0 + c1;
        buf[off + stride] = c0 - c1;
        var sub = new float[64];
        for (var idx = 0; idx < 2; idx++) {
            var base = idx * 32;
            for (var iy = 0; iy < 4; iy++) {
                for (var ix = 0; ix < 8; ix++) {
                    sub[base + iy * 8 + ix] = buf[off + (iy * 2 + idx) * stride + ix];
                }
            }
            Dct.dct2d(sub, base, 8, 8, 4, scratch, true);
        }
        if (transposed) {
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 8; x++) {
                    buf[off + x * stride + y] = sub[y * 8 + x];
                }
            }
        } else {
            for (var y = 0; y < 8; y++) {
                System.arraycopy(sub, y * 8, buf, off + y * stride, 8);
            }
        }
    }

    /**
     * Inverts an AFV transform.
     *
     * @param buf the block
     * @param off the index of the top-left coefficient
     * @param stride the row stride
     * @param variant the variant, from 0 to 3, giving which corner is the 4x4 AFV part
     * @param scratch the scratch buffer
     */
    private static void afv(float[] buf, int off, int stride, int variant, float[] scratch) {
        var flipX = variant % 2;
        var flipY = variant / 2;
        var coeffAfv = new float[16];
        coeffAfv[0] = (buf[off] + buf[off + 1] + buf[off + stride]) * 4.0f;
        for (var idx = 1; idx < 16; idx++) {
            var iy = idx / 4;
            var ix = idx % 4;
            coeffAfv[idx] = buf[off + 2 * iy * stride + 2 * ix];
        }
        var samples = new float[16];
        for (var k = 0; k < 16; k++) {
            var coefficient = coeffAfv[k];
            var basis = AFV_BASIS[k];
            for (var i = 0; i < 16; i++) {
                samples[i] = Math.fma(coefficient, basis[i], samples[i]);
            }
        }
        var s4 = new float[16];
        var s8 = new float[32];
        s4[0] = buf[off] - buf[off + 1] + buf[off + stride];
        for (var iy = 0; iy < 4; iy++) {
            for (var ix = 0; ix < 4; ix++) {
                if ((ix | iy) == 0) {
                    continue;
                }
                s4[ix * 4 + iy] = buf[off + 2 * iy * stride + 2 * ix + 1];
            }
        }
        Dct.dct2d(s4, 0, 4, 4, 4, scratch, true);
        s8[0] = buf[off] - buf[off + stride];
        for (var iy = 0; iy < 4; iy++) {
            for (var ix = 0; ix < 8; ix++) {
                if ((ix | iy) == 0) {
                    continue;
                }
                s8[iy * 8 + ix] = buf[off + (2 * iy + 1) * stride + ix];
            }
        }
        Dct.dct2d(s8, 0, 8, 8, 4, scratch, true);
        for (var iy = 0; iy < 4; iy++) {
            var afvY = flipY == 0 ? iy : 3 - iy;
            for (var ix = 0; ix < 4; ix++) {
                var afvX = flipX == 0 ? ix : 3 - ix;
                buf[off + (flipY * 4 + iy) * stride + flipX * 4 + ix] = samples[afvY * 4 + afvX];
            }
        }
        for (var iy = 0; iy < 4; iy++) {
            var y = flipY * 4 + iy;
            for (var ix = 0; ix < 4; ix++) {
                buf[off + y * stride + (1 - flipX) * 4 + ix] = s4[iy * 4 + ix];
            }
        }
        for (var iy = 0; iy < 4; iy++) {
            var y = (1 - flipY) * 4 + iy;
            System.arraycopy(s8, iy * 8, buf, off + y * stride, 8);
        }
    }
}
