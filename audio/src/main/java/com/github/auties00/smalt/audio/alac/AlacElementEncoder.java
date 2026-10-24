/* Ports Apple Lossless Audio Codec (codec/ALACEncoder.cpp, dp_enc.c, matrix_enc.c). Copyright (c) 2011 Apple Inc.;
   Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.alac;

/**
 * Codes the single-channel and channel-pair elements of ALAC frames.
 *
 * <p>A single channel is predicted with an adaptive linear predictor of order 4 or 8, whichever
 * a trial on a subsampled prefix of the frame estimates smaller, and its residual is Golomb coded.
 * A channel pair is first matrixed ({@code u = (r * L + (2^m - r) * R) >> m}, {@code v = L - R},
 * with the weight {@code r} chosen by trial, or {@code L} and {@code R} unchanged for weight zero),
 * and each matrixed channel is predicted and coded alike. Samples of 24 and 32 bits have their low
 * one and two bytes sent verbatim and the rest coded. An element whose code would not be smaller
 * than its samples is sent verbatim (escaped). The predictor coefficients and the stereo weight of
 * each channel carry over from frame to frame.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 * Instances are not thread-safe.
 *
 * @implNote This implementation reproduces the reference encoder's output byte for byte. The
 * reference keeps one set of matrixing, prediction and shift buffers for all elements and frames,
 * and its parameter searches read entries of them that the current search did not write (its
 * stereo order trial counts the residual of a prefix four times longer than the one it predicted);
 * the buffers here are shared and written in the same order, so those entries hold the same values.
 */
final class AlacElementEncoder {
    /**
     * The single channel element tag.
     */
    static final int ID_SCE = 0;

    /**
     * The channel pair element tag.
     */
    static final int ID_CPE = 1;

    /**
     * The end element tag.
     */
    static final int ID_END = 7;

    /**
     * The coefficient quantization shift every element uses.
     */
    private static final int DEN_SHIFT = 9;

    /**
     * The matrixing shift {@code m} every channel pair uses.
     */
    private static final int MIX_BITS = 2;

    /**
     * The largest matrixing weight the stereo search tries.
     */
    private static final int MAX_MIX_RES = 4;

    /**
     * The adaptation rate factor written in every predictor header, which selects the standard rate.
     */
    private static final int RATE_FACTOR = 4;

    /**
     * The low predictor order of the searches.
     */
    private static final int LOW_ORDER = 4;

    /**
     * The high predictor order of the searches, and the order of fast coding.
     */
    private static final int HIGH_ORDER = 8;

    /**
     * The initial first coefficient, {@code AINIT * 2^DEN_SHIFT / 16}.
     */
    private static final short INIT_A = (short) ((38 << DEN_SHIFT) >> 4);

    /**
     * The initial second coefficient, {@code BINIT * 2^DEN_SHIFT / 16}.
     */
    private static final short INIT_B = (short) ((-29 << DEN_SHIFT) >> 4);

    /**
     * The initial third coefficient, {@code CINIT * 2^DEN_SHIFT / 16}.
     */
    private static final short INIT_C = (short) ((-2 << DEN_SHIFT) >> 4);

    /**
     * The sample size of the stream.
     */
    private final int bitDepth;

    /**
     * The frame length; an element of another length is a partial frame.
     */
    private final int frameLength;

    /**
     * The first matrixed (or the single) channel.
     */
    private final int[] mixU;

    /**
     * The second matrixed channel.
     */
    private final int[] mixV;

    /**
     * The residual of the first channel.
     */
    private final int[] predictorU;

    /**
     * The residual of the second channel.
     */
    private final int[] predictorV;

    /**
     * The shifted-off low bits: per sample for a single channel, interleaved for a pair.
     */
    private final int[] shiftUV;

    /**
     * The coefficients of the first channel of each element, by bitstream channel and order (4 then 8).
     */
    private final short[][][] coefficientsU;

    /**
     * The coefficients of the second channel of each pair, as {@link #coefficientsU}.
     */
    private final short[][][] coefficientsV;

    /**
     * The stereo weight each pair chose in its last frame, by bitstream channel.
     */
    private final int[] lastMixRes;

    /**
     * Constructs an element encoder with the initial coefficients and stereo weights.
     *
     * @param bitDepth the sample size: 16, 20, 24 or 32
     * @param frameLength the frame length
     * @param channels the number of channels, from 1 to 8
     */
    AlacElementEncoder(int bitDepth, int frameLength, int channels) {
        this.bitDepth = bitDepth;
        this.frameLength = frameLength;
        this.mixU = new int[frameLength];
        this.mixV = new int[frameLength];
        this.predictorU = new int[frameLength];
        this.predictorV = new int[frameLength];
        this.shiftUV = new int[2 * frameLength];
        this.coefficientsU = new short[channels][2][];
        this.coefficientsV = new short[channels][2][];
        for (var channel = 0; channel < channels; channel++) {
            for (var order = 0; order < 2; order++) {
                coefficientsU[channel][order] = initialCoefficients();
                coefficientsV[channel][order] = initialCoefficients();
            }
        }
        this.lastMixRes = new int[channels];
    }

    /**
     * Returns the reference's initial predictor coefficients ({@code init_coefs}).
     *
     * @return the coefficients, room for order 8
     */
    private static short[] initialCoefficients() {
        var coefficients = new short[HIGH_ORDER];
        coefficients[0] = INIT_A;
        coefficients[1] = INIT_B;
        coefficients[2] = INIT_C;
        return coefficients;
    }

    /**
     * Returns the number of low bytes sent verbatim at the stream's sample size.
     *
     * @return 2 for 32-bit samples, 1 for 24-bit samples, 0 otherwise
     */
    private int bytesShifted() {
        return bitDepth == 32 ? 2 : bitDepth >= 24 ? 1 : 0;
    }

    /**
     * Codes a single channel element, after the element tag and instance ({@code EncodeMono}).
     *
     * @param out the packet
     * @param input the channel's samples, sign-extended from the sample size
     * @param channelIndex the bitstream index of the channel, which selects its coefficients
     * @param numSamples the number of samples, from 1 to the frame length
     */
    void encodeMono(AlacBitWriter out, int[] input, int channelIndex, int numSamples) {
        var start = out.position();
        var coefficients = coefficientsU[channelIndex];
        var bytesShifted = bytesShifted();
        var shift = bytesShifted * 8;
        var mask = (1 << shift) - 1;
        var chanBits = bitDepth - shift;
        var partial = numSamples != frameLength;
        for (var index = 0; index < numSamples; index++) {
            var value = input[index];
            if (shift != 0) {
                shiftUV[index] = value & mask;
                value >>= shift;
            }
            mixU[index] = value;
        }
        var minBits = 1L << 31;
        var bestOrder = LOW_ORDER;
        for (var order = LOW_ORDER; order <= HIGH_ORDER; order += 4) {
            var trial = coefficients[order == LOW_ORDER ? 0 : 1];
            for (var converge = 0; converge < 7; converge++) {
                predict(mixU, predictorU, numSamples / 32, trial, order, chanBits);
            }
            predict(mixU, predictorU, numSamples / 8, trial, order, chanBits);
            var bits = AlacResidualEncoder.encode(predictorU, numSamples / 8, chanBits, null);
            var total = 8 * bits + 16L * order;
            if (total < minBits) {
                bestOrder = order;
                minBits = total;
            }
        }
        minBits += 4 * 8 + (partial ? 32 : 0);
        if (bytesShifted != 0) {
            minBits += (long) numSamples * shift;
        }
        var escapeBits = (long) numSamples * bitDepth + (partial ? 32 : 0) + 2 * 8;
        var escape = minBits >= escapeBits;
        if (!escape) {
            elementHeader(out, partial, bytesShifted, false, numSamples);
            out.write(0, 16);
            var best = coefficients[bestOrder == LOW_ORDER ? 0 : 1];
            predictorHeader(out, best, bestOrder);
            if (bytesShifted != 0) {
                for (var index = 0; index < numSamples; index++) {
                    out.write(shiftUV[index], shift);
                }
            }
            predict(mixU, predictorU, numSamples, best, bestOrder, chanBits);
            AlacResidualEncoder.encode(predictorU, numSamples, chanBits, out);
            if (out.position() - start >= escapeBits) {
                out.rewind(start);
                escape = true;
            }
        }
        if (escape) {
            elementHeader(out, partial, 0, true, numSamples);
            if (bitDepth == 20 || bitDepth == 24) {
                System.arraycopy(input, 0, mixU, 0, numSamples);
            }
            for (var index = 0; index < numSamples; index++) {
                out.write(input[index], bitDepth);
            }
        }
    }

    /**
     * Codes a channel pair element, after the element tag and instance, searching for the stereo
     * weight and the predictor orders ({@code EncodeStereo}).
     *
     * @param out the packet
     * @param left the first channel's samples, sign-extended from the sample size
     * @param right the second channel's samples
     * @param channelIndex the bitstream index of the pair's first channel
     * @param numSamples the number of samples per channel, from 1 to the frame length
     */
    void encodeStereo(AlacBitWriter out, int[] left, int[] right, int channelIndex, int numSamples) {
        var start = out.position();
        var coefficientsU = this.coefficientsU[channelIndex];
        var coefficientsV = this.coefficientsV[channelIndex];
        var bytesShifted = bytesShifted();
        var shift = bytesShifted * 8;
        var chanBits = bitDepth - shift + 1;
        var partial = numSamples != frameLength;
        var minBits1 = 1L << 31;
        var bestRes = lastMixRes[channelIndex];
        var sampled = numSamples / 8;
        for (var mixRes = 0; mixRes <= MAX_MIX_RES; mixRes++) {
            mix(left, right, sampled, mixRes, shift);
            predict(mixU, predictorU, sampled, coefficientsU[1], HIGH_ORDER, chanBits);
            predict(mixV, predictorV, sampled, coefficientsV[1], HIGH_ORDER, chanBits);
            var bits = AlacResidualEncoder.encode(predictorU, sampled, chanBits, null)
                    + AlacResidualEncoder.encode(predictorV, sampled, chanBits, null);
            if (bits < minBits1) {
                minBits1 = bits;
                bestRes = mixRes;
            }
        }
        lastMixRes[channelIndex] = bestRes;
        var mixRes = bestRes;
        mix(left, right, numSamples, mixRes, shift);
        var orderU = LOW_ORDER;
        var orderV = LOW_ORDER;
        minBits1 = 1L << 31;
        var minBits2 = 1L << 31;
        for (var order = LOW_ORDER; order <= HIGH_ORDER; order += 4) {
            var slot = order == LOW_ORDER ? 0 : 1;
            for (var converge = 0; converge < 8; converge++) {
                predict(mixU, predictorU, numSamples / 32, coefficientsU[slot], order, chanBits);
                predict(mixV, predictorV, numSamples / 32, coefficientsV[slot], order, chanBits);
            }
            var bits1 = 8 * AlacResidualEncoder.encode(predictorU, sampled, chanBits, null) + 16L * order;
            if (bits1 < minBits1) {
                minBits1 = bits1;
                orderU = order;
            }
            var bits2 = 8 * AlacResidualEncoder.encode(predictorV, sampled, chanBits, null) + 16L * order;
            if (bits2 < minBits2) {
                minBits2 = bits2;
                orderV = order;
            }
        }
        var minBits = minBits1 + minBits2 + 8 * 8 + (partial ? 32 : 0);
        if (bytesShifted != 0) {
            minBits += (long) numSamples * shift * 2;
        }
        var escapeBits = (long) numSamples * bitDepth * 2 + (partial ? 32 : 0) + 2 * 8;
        var escape = minBits >= escapeBits;
        if (!escape) {
            var bestU = coefficientsU[orderU == LOW_ORDER ? 0 : 1];
            var bestV = coefficientsV[orderV == LOW_ORDER ? 0 : 1];
            writePair(out, partial, bytesShifted, numSamples, mixRes, bestU, orderU, bestV, orderV);
            predict(mixU, predictorU, numSamples, bestU, orderU, chanBits);
            AlacResidualEncoder.encode(predictorU, numSamples, chanBits, out);
            predict(mixV, predictorV, numSamples, bestV, orderV, chanBits);
            AlacResidualEncoder.encode(predictorV, numSamples, chanBits, out);
            if (out.position() - start >= escapeBits) {
                out.rewind(start);
                escape = true;
            }
        }
        if (escape) {
            encodeStereoEscape(out, left, right, numSamples, partial);
        }
    }

    /**
     * Codes a channel pair element without the searches: stereo weight zero and order 8
     * ({@code EncodeStereoFast}).
     *
     * @param out the packet
     * @param left the first channel's samples, sign-extended from the sample size
     * @param right the second channel's samples
     * @param channelIndex the bitstream index of the pair's first channel
     * @param numSamples the number of samples per channel, from 1 to the frame length
     */
    void encodeStereoFast(AlacBitWriter out, int[] left, int[] right, int channelIndex, int numSamples) {
        var start = out.position();
        var bestU = coefficientsU[channelIndex][1];
        var bestV = coefficientsV[channelIndex][1];
        var bytesShifted = bytesShifted();
        var shift = bytesShifted * 8;
        var chanBits = bitDepth - shift + 1;
        var partial = numSamples != frameLength;
        mix(left, right, numSamples, 0, shift);
        writePair(out, partial, bytesShifted, numSamples, 0, bestU, HIGH_ORDER, bestV, HIGH_ORDER);
        predict(mixU, predictorU, numSamples, bestU, HIGH_ORDER, chanBits);
        var bits1 = AlacResidualEncoder.encode(predictorU, numSamples, chanBits, out);
        predict(mixV, predictorV, numSamples, bestV, HIGH_ORDER, chanBits);
        var bits2 = AlacResidualEncoder.encode(predictorV, numSamples, chanBits, out);
        var minBits = bits1 + HIGH_ORDER * 16 + bits2 + HIGH_ORDER * 16 + 8 * 8 + (partial ? 32 : 0);
        if (bytesShifted != 0) {
            minBits += (long) numSamples * shift * 2;
        }
        var escapeBits = (long) numSamples * bitDepth * 2 + (partial ? 32 : 0) + 2 * 8;
        if (minBits >= escapeBits || out.position() - start >= escapeBits) {
            out.rewind(start);
            encodeStereoEscape(out, left, right, numSamples, partial);
        }
    }

    /**
     * Writes a channel pair's header, predictor headers and shifted-off bits.
     *
     * @param out the packet
     * @param partial whether the element is shorter than the frame length
     * @param bytesShifted the number of low bytes sent verbatim
     * @param numSamples the number of samples per channel
     * @param mixRes the stereo weight
     * @param coefficientsU the first channel's coefficients
     * @param orderU the first channel's order
     * @param coefficientsV the second channel's coefficients
     * @param orderV the second channel's order
     */
    private void writePair(AlacBitWriter out, boolean partial, int bytesShifted, int numSamples, int mixRes,
                           short[] coefficientsU, int orderU, short[] coefficientsV, int orderV) {
        elementHeader(out, partial, bytesShifted, false, numSamples);
        out.write(MIX_BITS, 8);
        out.write(mixRes, 8);
        predictorHeader(out, coefficientsU, orderU);
        predictorHeader(out, coefficientsV, orderV);
        if (bytesShifted != 0) {
            var shift = bytesShifted * 8;
            for (var index = 0; index < 2 * numSamples; index += 2) {
                out.write(shiftUV[index] << shift | shiftUV[index + 1], 2 * shift);
            }
        }
    }

    /**
     * Codes a channel pair verbatim ({@code EncodeStereoEscape}).
     *
     * @param out the packet
     * @param left the first channel's samples
     * @param right the second channel's samples
     * @param numSamples the number of samples per channel
     * @param partial whether the element is shorter than the frame length
     */
    private void encodeStereoEscape(AlacBitWriter out, int[] left, int[] right, int numSamples, boolean partial) {
        elementHeader(out, partial, 0, true, numSamples);
        if (bitDepth == 20 || bitDepth == 24) {
            mix(left, right, numSamples, 0, 0);
        }
        for (var index = 0; index < numSamples; index++) {
            out.write(left[index], bitDepth);
            out.write(right[index], bitDepth);
        }
    }

    /**
     * Writes the common element header after the tag and instance: twelve reserved zero bits, the
     * partial, shift and escape fields, and the sample count of a partial frame.
     *
     * @param out the packet
     * @param partial whether the element is shorter than the frame length
     * @param bytesShifted the number of low bytes sent verbatim
     * @param escape whether the samples follow verbatim
     * @param numSamples the number of samples per channel
     */
    private static void elementHeader(AlacBitWriter out, boolean partial, int bytesShifted, boolean escape,
                                      int numSamples) {
        out.write(0, 12);
        out.write((partial ? 8 : 0) | bytesShifted << 1 | (escape ? 1 : 0), 4);
        if (partial) {
            out.write(numSamples, 32);
        }
    }

    /**
     * Writes a predictor header: mode zero, the quantization shift, the rate factor, the order and
     * the coefficients.
     *
     * @param out the packet
     * @param coefficients the 16-bit predictor coefficients, at least {@code order} of them
     * @param order the order, 4 or 8
     */
    private static void predictorHeader(AlacBitWriter out, short[] coefficients, int order) {
        out.write(DEN_SHIFT, 8);
        out.write(RATE_FACTOR << 5 | order, 8);
        for (var index = 0; index < order; index++) {
            out.write(coefficients[index], 16);
        }
    }

    /**
     * Matrixes a channel pair into {@link #mixU} and {@link #mixV}, first moving the low bits into
     * {@link #shiftUV} ({@code mix16}, {@code mix20}, {@code mix24}, {@code mix32}).
     *
     * @param left the first channel's samples
     * @param right the second channel's samples
     * @param numSamples the number of samples per channel
     * @param mixRes the stereo weight, zero for the channels unchanged
     * @param shift the number of low bits sent verbatim
     */
    private void mix(int[] left, int[] right, int numSamples, int mixRes, int shift) {
        var mask = (1 << shift) - 1;
        var weight = (1 << MIX_BITS) - mixRes;
        var u = mixU;
        var v = mixV;
        for (var j = 0; j < numSamples; j++) {
            var l = left[j];
            var r = right[j];
            if (shift != 0) {
                shiftUV[2 * j] = l & mask;
                shiftUV[2 * j + 1] = r & mask;
                l >>= shift;
                r >>= shift;
            }
            if (mixRes != 0) {
                u[j] = (mixRes * l + weight * r) >> MIX_BITS;
                v[j] = l - r;
            } else {
                u[j] = l;
                v[j] = r;
            }
        }
    }

    /**
     * Runs the adaptive predictor over samples, writing the residual and adapting the coefficients
     * in place ({@code pc_block}).
     *
     * <p>The first {@code order} differences are written whatever {@code num} is, as the reference
     * writes them, so {@code in} and {@code residual} must hold at least {@code order + 1} entries.
     *
     * @param in the samples
     * @param residual receives the residual
     * @param num the number of samples, non-negative
     * @param coefficients the coefficients, adapted in place
     * @param order the predictor order, 4 or 8
     * @param chanBits the prediction size in bits, from 1 to 32
     */
    private static void predict(int[] in, int[] residual, int num, short[] coefficients, int order, int chanBits) {
        var chanShift = 32 - chanBits;
        residual[0] = in[0];
        for (var j = 1; j <= order; j++) {
            var del = in[j] - in[j - 1];
            residual[j] = (del << chanShift) >> chanShift;
        }
        if (order == LOW_ORDER) {
            predict4(in, residual, num, coefficients, chanShift);
        } else {
            predict8(in, residual, num, coefficients, chanShift);
        }
    }

    /**
     * Runs the fourth-order predictor past its warm-up.
     *
     * @param in the samples
     * @param residual receives the residual
     * @param num the number of samples
     * @param coefficients the coefficients, adapted in place
     * @param chanShift 32 minus the prediction size
     */
    private static void predict4(int[] in, int[] residual, int num, short[] coefficients, int chanShift) {
        var denHalf = 1 << (DEN_SHIFT - 1);
        short a0 = coefficients[0], a1 = coefficients[1], a2 = coefficients[2], a3 = coefficients[3];
        for (var j = 5; j < num; j++) {
            var top = in[j - 5];
            var b0 = top - in[j - 1];
            var b1 = top - in[j - 2];
            var b2 = top - in[j - 3];
            var b3 = top - in[j - 4];
            var sum = (denHalf - a0 * b0 - a1 * b1 - a2 * b2 - a3 * b3) >> DEN_SHIFT;
            var del = in[j] - top - sum;
            del = (del << chanShift) >> chanShift;
            residual[j] = del;
            var del0 = del;
            if (del > 0) {
                var sgn = Integer.signum(b3);
                a3 = (short) (a3 - sgn);
                del0 -= (sgn * b3) >> DEN_SHIFT;
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b2);
                a2 = (short) (a2 - sgn);
                del0 -= 2 * ((sgn * b2) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b1);
                a1 = (short) (a1 - sgn);
                del0 -= 3 * ((sgn * b1) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                a0 = (short) (a0 - Integer.signum(b0));
            } else if (del < 0) {
                var sgn = -Integer.signum(b3);
                a3 = (short) (a3 - sgn);
                del0 -= (sgn * b3) >> DEN_SHIFT;
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b2);
                a2 = (short) (a2 - sgn);
                del0 -= 2 * ((sgn * b2) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b1);
                a1 = (short) (a1 - sgn);
                del0 -= 3 * ((sgn * b1) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                a0 = (short) (a0 + Integer.signum(b0));
            }
        }
        coefficients[0] = a0;
        coefficients[1] = a1;
        coefficients[2] = a2;
        coefficients[3] = a3;
    }

    /**
     * Runs the eighth-order predictor past its warm-up.
     *
     * @param in the samples
     * @param residual receives the residual
     * @param num the number of samples
     * @param coefficients the coefficients, adapted in place
     * @param chanShift 32 minus the prediction size
     */
    private static void predict8(int[] in, int[] residual, int num, short[] coefficients, int chanShift) {
        var denHalf = 1 << (DEN_SHIFT - 1);
        short a0 = coefficients[0], a1 = coefficients[1], a2 = coefficients[2], a3 = coefficients[3];
        short a4 = coefficients[4], a5 = coefficients[5], a6 = coefficients[6], a7 = coefficients[7];
        for (var j = 9; j < num; j++) {
            var top = in[j - 9];
            var b0 = top - in[j - 1];
            var b1 = top - in[j - 2];
            var b2 = top - in[j - 3];
            var b3 = top - in[j - 4];
            var b4 = top - in[j - 5];
            var b5 = top - in[j - 6];
            var b6 = top - in[j - 7];
            var b7 = top - in[j - 8];
            var sum = (denHalf - a0 * b0 - a1 * b1 - a2 * b2 - a3 * b3 - a4 * b4 - a5 * b5 - a6 * b6 - a7 * b7)
                    >> DEN_SHIFT;
            var del = in[j] - top - sum;
            del = (del << chanShift) >> chanShift;
            residual[j] = del;
            var del0 = del;
            if (del > 0) {
                var sgn = Integer.signum(b7);
                a7 = (short) (a7 - sgn);
                del0 -= (sgn * b7) >> DEN_SHIFT;
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b6);
                a6 = (short) (a6 - sgn);
                del0 -= 2 * ((sgn * b6) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b5);
                a5 = (short) (a5 - sgn);
                del0 -= 3 * ((sgn * b5) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b4);
                a4 = (short) (a4 - sgn);
                del0 -= 4 * ((sgn * b4) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b3);
                a3 = (short) (a3 - sgn);
                del0 -= 5 * ((sgn * b3) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b2);
                a2 = (short) (a2 - sgn);
                del0 -= 6 * ((sgn * b2) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                sgn = Integer.signum(b1);
                a1 = (short) (a1 - sgn);
                del0 -= 7 * ((sgn * b1) >> DEN_SHIFT);
                if (del0 <= 0) {
                    continue;
                }
                a0 = (short) (a0 - Integer.signum(b0));
            } else if (del < 0) {
                var sgn = -Integer.signum(b7);
                a7 = (short) (a7 - sgn);
                del0 -= (sgn * b7) >> DEN_SHIFT;
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b6);
                a6 = (short) (a6 - sgn);
                del0 -= 2 * ((sgn * b6) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b5);
                a5 = (short) (a5 - sgn);
                del0 -= 3 * ((sgn * b5) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b4);
                a4 = (short) (a4 - sgn);
                del0 -= 4 * ((sgn * b4) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b3);
                a3 = (short) (a3 - sgn);
                del0 -= 5 * ((sgn * b3) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b2);
                a2 = (short) (a2 - sgn);
                del0 -= 6 * ((sgn * b2) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                sgn = -Integer.signum(b1);
                a1 = (short) (a1 - sgn);
                del0 -= 7 * ((sgn * b1) >> DEN_SHIFT);
                if (del0 >= 0) {
                    continue;
                }
                a0 = (short) (a0 + Integer.signum(b0));
            }
        }
        coefficients[0] = a0;
        coefficients[1] = a1;
        coefficients[2] = a2;
        coefficients[3] = a3;
        coefficients[4] = a4;
        coefficients[5] = a5;
        coefficients[6] = a6;
        coefficients[7] = a7;
    }
}
