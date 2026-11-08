package com.github.auties00.smalt.video.mpeg2.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Mpeg4Dsp;

import java.util.Arrays;

/**
 * Decodes the slices of a picture (ISO/IEC 13818-2 6.2.4 to 6.2.6 and clause 7, ISO/IEC 11172-2 2.4.2.7 to 2.4.4):
 * it parses each macroblock, decodes its motion vectors and coefficients, forms its prediction and adds the inverse
 * transformed residual into the frame of the {@link Picture}, and conceals the macroblocks the stream left out or
 * damaged.
 *
 * <p>One instance decodes one slice at a time; the slices of a picture may be decoded by several instances at once,
 * since each writes only its own macroblocks. Arguments are not checked; values outside the documented ranges give
 * unspecified results. Instances are not thread-safe.
 */
final class SliceDecoder {
    /**
     * The non-linear quantizer scales of {@code quantiser_scale_code} 0 to 31 (ISO/IEC 13818-2 Table 7-6).
     */
    private static final int[] NON_LINEAR = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36,
            40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 104, 112};

    /**
     * The {@code frame_motion_type} and {@code field_motion_type} of field prediction.
     */
    private static final int FIELD_MOTION = 1;

    /**
     * The {@code frame_motion_type} of frame prediction, and the {@code field_motion_type} of 16x8 prediction.
     */
    private static final int FRAME_MOTION = 2;

    /**
     * The motion type of dual-prime prediction.
     */
    private static final int DUAL_PRIME = 3;

    /**
     * The row stride of the emulated reference block.
     */
    private static final int EDGE_STRIDE = 17;

    /**
     * The transform and prediction kernels.
     */
    private final Mpeg4Dsp dsp = Mpeg4Dsp.select();

    /**
     * The reader of the slice being decoded.
     */
    private final MsbBitReader bits = new MsbBitReader(new byte[0]);

    /**
     * The coefficient blocks of the macroblock, 64 per block in the transposed layout of {@link Mpeg4Dsp}; the
     * transforms clear them.
     */
    private final short[] blocks = new short[12 * 64];

    /**
     * The scratch of the inverse transform.
     */
    private final int[] work = new int[64];

    /**
     * A second prediction, 16 samples per row, averaged into the first.
     */
    private final byte[] second = new byte[16 * 16];

    /**
     * The reference samples of a block that reaches past the reference picture, with the picture's edge samples
     * repeated.
     */
    private final byte[] edge = new byte[EDGE_STRIDE * EDGE_STRIDE];

    /**
     * The intra DC predictors of luma, Cb and Cr.
     */
    private final int[] dcPredictor = new int[3];

    /**
     * The motion vector predictors {@code PMV[r][s][t]}, in half samples.
     */
    private final int[][][] predictors = new int[2][2][2];

    /**
     * {@code motion_vertical_field_select[r][s]}.
     */
    private final int[][] fieldSelect = new int[2][2];

    /**
     * {@code dmvector[t]} of a dual-prime macroblock.
     */
    private final int[] dualDelta = new int[2];

    /**
     * The derived dual-prime vectors: for a frame picture the vector predicting the top field from the bottom one
     * and the bottom field from the top one, for a field picture the vector predicting from the opposite parity.
     */
    private final int[][] dualVectors = new int[2][2];

    /**
     * The picture whose slices are decoded.
     */
    private Picture picture;

    /**
     * {@code quantiser_scale} in force, doubled for MPEG-1 so that both standards use the MPEG-2 formulas.
     */
    private int quantizerScale;

    /**
     * The macroblock type flags of the previous macroblock, which a skipped macroblock of a B-picture repeats.
     */
    private int previousType;

    /**
     * Whether decoding the current motion vectors met an invalid code.
     */
    private boolean invalid;

    /**
     * Whether a prediction needed a reference frame the decoder does not have.
     */
    boolean missingReference;

    /**
     * Decodes a slice into the frame of a picture.
     *
     * @param picture the parameters and frames of the picture the slice belongs to
     * @param data the elementary stream bytes
     * @param start the index of the slice start code prefix
     * @param end the index of the next start code prefix, or of the end of the data
     * @return {@code true} when the slice decoded without error; the macroblocks it reached before an error stay
     * decoded
     */
    boolean decode(Picture picture, byte[] data, int start, int end) {
        this.picture = picture;
        var code = data[start + 3] & 0xFF;
        bits.reset(data, start + 4, end - start - 4);
        var row = code - 1;
        if (picture.mpeg2 && picture.verticalSize > 2800) {
            row += bits.read(3) << 7;
        }
        setQuantizer(bits.read(5));
        if (picture.mpeg2) {
            if (bits.readBit() == 1) {
                bits.skip(8);
                while (bits.readBit() == 1) {
                    bits.skip(8);
                }
            }
        } else {
            while (bits.readBit() == 1) {
                bits.skip(8);
            }
        }
        var width = picture.mbWidth;
        if (row >= picture.mbRows) {
            return false;
        }
        var increment = Vlc.addressIncrement(bits);
        if (increment == Vlc.ERROR) {
            return false;
        }
        var address = row * width + increment - 1;
        var limit = picture.mpeg2 ? (row + 1) * width : picture.mbCount();
        if (address >= limit) {
            return false;
        }
        resetDc();
        resetPredictors();
        previousType = 0;
        while (true) {
            if (!macroblock(address)) {
                Arrays.fill(blocks, (short) 0);
                return false;
            }
            address++;
            if (bits.peek(23) == 0) {
                return !bits.overrun();
            }
            increment = Vlc.addressIncrement(bits);
            if (increment == Vlc.ERROR || bits.overrun()) {
                return false;
            }
            for (var skip = 1; skip < increment; skip++) {
                if (address >= limit) {
                    return false;
                }
                skipped(address);
                address++;
            }
            if (address >= limit) {
                return false;
            }
        }
    }

    /**
     * Sets {@code quantiser_scale} from {@code quantiser_scale_code}.
     *
     * @param code the code, from 0 to 31
     */
    private void setQuantizer(int code) {
        quantizerScale = picture.mpeg2 && picture.qScaleType ? NON_LINEAR[code] : code << 1;
    }

    /**
     * Resets the intra DC predictors to {@code 2^(7 + intra_dc_precision)}.
     */
    private void resetDc() {
        var reset = 1 << (7 + picture.intraDcPrecision);
        dcPredictor[0] = reset;
        dcPredictor[1] = reset;
        dcPredictor[2] = reset;
    }

    /**
     * Resets every motion vector predictor to zero.
     */
    private void resetPredictors() {
        for (var r = 0; r < 2; r++) {
            for (var s = 0; s < 2; s++) {
                predictors[r][s][0] = 0;
                predictors[r][s][1] = 0;
            }
        }
    }

    /**
     * Decodes and reconstructs one coded macroblock.
     *
     * @param address the macroblock address in the picture
     * @return {@code false} on an invalid code or truncated data
     */
    private boolean macroblock(int address) {
        var picture = this.picture;
        var type = switch (picture.codingType) {
            case Picture.I -> Vlc.intraType(bits);
            case Picture.P -> Vlc.predictiveType(bits);
            case Picture.B -> Vlc.bidirectionalType(bits);
            default -> bits.readBit() == 1 ? Vlc.INTRA : Vlc.ERROR;
        };
        if (type == Vlc.ERROR) {
            return false;
        }
        var frame = picture.structure == Picture.FRAME;
        var intra = (type & Vlc.INTRA) != 0;
        var concealment = intra && picture.concealmentVectors;
        var motion = FRAME_MOTION;
        var fieldDct = false;
        if (picture.mpeg2) {
            if ((type & (Vlc.FORWARD | Vlc.BACKWARD)) != 0) {
                motion = frame && picture.framePredFrameDct ? FRAME_MOTION : bits.read(2);
                if (motion == 0 || motion == DUAL_PRIME && picture.codingType != Picture.P) {
                    return false;
                }
            } else if (concealment) {
                motion = frame ? FRAME_MOTION : FIELD_MOTION;
            }
            if (frame && !picture.framePredFrameDct && (type & (Vlc.PATTERN | Vlc.INTRA)) != 0) {
                fieldDct = bits.readBit() == 1;
            }
        }
        if ((type & Vlc.QUANT) != 0) {
            setQuantizer(bits.read(5));
        }
        invalid = false;
        if ((type & Vlc.FORWARD) != 0 || concealment) {
            vectors(0, motion, frame);
        }
        if ((type & Vlc.BACKWARD) != 0) {
            vectors(1, motion, frame);
        }
        if (invalid) {
            return false;
        }
        if (concealment) {
            bits.skip(1);
        }
        var count = picture.blockCount;
        int pattern;
        if ((type & Vlc.PATTERN) != 0) {
            pattern = Vlc.codedBlockPattern(bits);
            if (pattern == Vlc.ERROR) {
                return false;
            }
            if (picture.chromaFormat == 2) {
                pattern = pattern << 2 | bits.read(2);
            } else if (picture.chromaFormat == 3) {
                pattern = pattern << 6 | bits.read(6);
            }
        } else {
            pattern = intra ? (1 << count) - 1 : 0;
        }
        for (var block = 0; block < count; block++) {
            if ((pattern & 1 << (count - 1 - block)) != 0) {
                var ok = intra ? intraBlock(block) : interBlock(block);
                if (!ok) {
                    return false;
                }
            }
        }
        if (picture.codingType == Picture.D && bits.readBit() != 1) {
            return false;
        }
        if (bits.overrun()) {
            return false;
        }
        if (!intra) {
            resetDc();
        }
        if (intra && !picture.concealmentVectors) {
            resetPredictors();
        }
        if (picture.codingType == Picture.P && (type & (Vlc.FORWARD | Vlc.INTRA)) == 0) {
            predictors[0][0][0] = 0;
            predictors[0][0][1] = 0;
            predictors[1][0][0] = 0;
            predictors[1][0][1] = 0;
            motion = frame ? FRAME_MOTION : FIELD_MOTION;
            fieldSelect[0][0] = picture.parity();
        }
        if (!intra) {
            predict(address, type, motion, frame);
        }
        reconstruct(address, intra, pattern, fieldDct);
        recordVector(address, type, concealment);
        previousType = type & ~Vlc.INTRA;
        picture.current.decoded[address] = true;
        return true;
    }

    /**
     * Reconstructs a skipped macroblock (ISO/IEC 13818-2 7.6.6): the prediction alone, with zero vectors in a
     * P-picture and the previous macroblock's vectors and directions in a B-picture; a skipped macroblock of an I- or
     * D-picture, which the syntax forbids, is left for concealment.
     *
     * @param address the macroblock address
     */
    private void skipped(int address) {
        var picture = this.picture;
        var frame = picture.structure == Picture.FRAME;
        resetDc();
        if (picture.codingType == Picture.P) {
            predictors[0][0][0] = 0;
            predictors[0][0][1] = 0;
            predictors[1][0][0] = 0;
            predictors[1][0][1] = 0;
        }
        var motion = frame ? FRAME_MOTION : FIELD_MOTION;
        if (!frame) {
            fieldSelect[0][0] = picture.parity();
            fieldSelect[0][1] = picture.parity();
        }
        var type = picture.codingType == Picture.B ? previousType : Vlc.FORWARD;
        if ((type & (Vlc.FORWARD | Vlc.BACKWARD)) == 0) {
            type = Vlc.FORWARD;
        }
        if (picture.codingType == Picture.I || picture.codingType == Picture.D) {
            return;
        }
        predict(address, type, motion, frame);
        recordVector(address, type, false);
        picture.current.decoded[address] = true;
    }

    /**
     * Records the forward vector a macroblock leaves for concealing the one below it.
     *
     * @param address the macroblock address
     * @param type the macroblock type flags
     * @param concealment whether the macroblock carried concealment motion vectors
     */
    private void recordVector(int address, int type, boolean concealment) {
        var frame = picture.current;
        var forward = (type & Vlc.FORWARD) != 0 || concealment
                || picture.codingType == Picture.P && (type & Vlc.INTRA) == 0;
        frame.hasVector[address] = forward;
        if (forward) {
            frame.vectorX[address] = predictors[0][0][0];
            frame.vectorY[address] = predictors[0][0][1];
        }
    }

    /**
     * Decodes the motion vectors of one direction (ISO/IEC 13818-2 6.2.5.2, 7.6.3), updating the predictors and
     * the field selections; an invalid code sets {@link #invalid}.
     *
     * @param direction 0 forward, 1 backward
     * @param motion the motion type
     * @param frame whether the picture is a frame picture
     */
    private void vectors(int direction, int motion, boolean frame) {
        var picture = this.picture;
        var hSize = picture.fCode[direction][0] - 1;
        var vSize = picture.fCode[direction][1] - 1;
        if (hSize < 0 || vSize < 0 || hSize > 13 || vSize > 13) {
            invalid = true;
            return;
        }
        if (!picture.mpeg2) {
            vector(predictors[0][direction], hSize, vSize, false, false, picture.fullPel[direction]);
            return;
        }
        int count;
        boolean fieldFormat;
        if (frame) {
            count = motion == FIELD_MOTION ? 2 : 1;
            fieldFormat = motion != FRAME_MOTION;
        } else {
            count = motion == FRAME_MOTION ? 2 : 1;
            fieldFormat = true;
        }
        var dual = motion == DUAL_PRIME;
        var scale = fieldFormat && frame;
        if (count == 1) {
            if (fieldFormat && !dual) {
                var select = bits.readBit();
                fieldSelect[0][direction] = select;
                fieldSelect[1][direction] = select;
            }
            vector(predictors[0][direction], hSize, vSize, dual, scale, false);
            predictors[1][direction][0] = predictors[0][direction][0];
            predictors[1][direction][1] = predictors[0][direction][1];
        } else {
            fieldSelect[0][direction] = bits.readBit();
            vector(predictors[0][direction], hSize, vSize, false, scale, false);
            fieldSelect[1][direction] = bits.readBit();
            vector(predictors[1][direction], hSize, vSize, false, scale, false);
        }
    }

    /**
     * Decodes one motion vector into its predictor.
     *
     * @param predictor the predictor {@code PMV[r][s]}, updated in place
     * @param hSize {@code r_size} of the horizontal component
     * @param vSize {@code r_size} of the vertical component
     * @param dual whether {@code dmvector} components follow the codes
     * @param scale whether the vector is a field vector of a frame picture, whose vertical predictor is held in
     * frame units
     * @param fullPel whether the MPEG-1 vector is in whole samples
     */
    private void vector(int[] predictor, int hSize, int vSize, boolean dual, boolean scale, boolean fullPel) {
        var code = Vlc.motionCode(bits);
        if (code == Integer.MIN_VALUE) {
            invalid = true;
            return;
        }
        var residual = hSize != 0 && code != 0 ? bits.read(hSize) : 0;
        predictor[0] = component(predictor[0], hSize, code, residual, fullPel);
        if (dual) {
            dualDelta[0] = Vlc.dualPrimeVector(bits);
        }
        code = Vlc.motionCode(bits);
        if (code == Integer.MIN_VALUE) {
            invalid = true;
            return;
        }
        residual = vSize != 0 && code != 0 ? bits.read(vSize) : 0;
        if (scale) {
            predictor[1] = component(predictor[1] >> 1, vSize, code, residual, fullPel) << 1;
        } else {
            predictor[1] = component(predictor[1], vSize, code, residual, fullPel);
        }
        if (dual) {
            dualDelta[1] = Vlc.dualPrimeVector(bits);
        }
    }

    /**
     * Reconstructs one motion vector component from its prediction (ISO/IEC 13818-2 7.6.3.1), wrapped into the
     * range of {@code f_code}.
     *
     * @param prediction the predictor, in half samples
     * @param size {@code r_size}
     * @param code {@code motion_code}
     * @param residual {@code motion_residual}
     * @param fullPel whether the MPEG-1 vector is in whole samples
     * @return the reconstructed component, in half samples
     */
    private static int component(int prediction, int size, int code, int residual, boolean fullPel) {
        var limit = 16 << size;
        var vector = fullPel ? prediction >> 1 : prediction;
        if (code > 0) {
            vector += ((code - 1) << size) + residual + 1;
            if (vector >= limit) {
                vector -= limit + limit;
            }
        } else if (code < 0) {
            vector -= ((-code - 1) << size) + residual + 1;
            if (vector < -limit) {
                vector += limit + limit;
            }
        }
        return fullPel ? vector << 1 : vector;
    }

    /**
     * Decodes an intra block (ISO/IEC 13818-2 7.2.1, 7.4.2, ISO/IEC 11172-2 2.4.4.1) into its coefficients.
     *
     * @param block the block index in the macroblock
     * @return {@code false} on an invalid code
     */
    private boolean intraBlock(int block) {
        var picture = this.picture;
        var component = block < 4 ? 0 : 1 + (block & 1);
        var size = component == 0 ? Vlc.lumaDcSize(bits) : Vlc.chromaDcSize(bits);
        if (size == Vlc.ERROR) {
            return false;
        }
        var differential = 0;
        if (size > 0) {
            differential = bits.read(size);
            if ((differential & 1 << (size - 1)) == 0) {
                differential -= (1 << size) - 1;
            }
        }
        var dc = dcPredictor[component] += differential;
        var offset = block << 6;
        var value = saturate(dc << (3 - picture.intraDcPrecision));
        blocks[offset] = (short) value;
        if (picture.codingType == Picture.D) {
            return true;
        }
        var sum = value;
        var weights = component == 0 ? picture.intraLuma : picture.intraChroma;
        var scan = picture.scan;
        var mpeg2 = picture.mpeg2;
        var tableOne = mpeg2 && picture.intraVlcFormat;
        var scale = quantizerScale;
        var position = 1;
        while (true) {
            var entry = Vlc.coefficient(bits.peek(16), false, tableOne);
            if (entry == 0) {
                return false;
            }
            var run = entry >>> 16;
            bits.skip(entry & 0xFF);
            if (run == Vlc.EOB) {
                break;
            }
            int level;
            if (run == Vlc.ESCAPE) {
                run = bits.read(6);
                level = escapeLevel(mpeg2);
                if (level == 0) {
                    return false;
                }
            } else {
                level = entry >> 8 & 0xFF;
                if (bits.readBit() == 1) {
                    level = -level;
                }
            }
            position += run;
            if (position >= 64) {
                return false;
            }
            var magnitude = ((level < 0 ? -level : level) * scale * weights[position]) >> 4;
            int coefficient;
            if (mpeg2) {
                coefficient = saturate(level < 0 ? -magnitude : magnitude);
                sum += coefficient;
            } else {
                coefficient = oddify(magnitude, level < 0);
            }
            blocks[offset + scan[position]] = (short) coefficient;
            position++;
        }
        if (mpeg2 && (sum & 1) == 0) {
            blocks[offset + 63] ^= 1;
        }
        return true;
    }

    /**
     * Decodes a non-intra block (ISO/IEC 13818-2 7.2.2, 7.4.2, ISO/IEC 11172-2 2.4.4.2) into its coefficients.
     *
     * @param block the block index in the macroblock
     * @return {@code false} on an invalid code
     */
    private boolean interBlock(int block) {
        var picture = this.picture;
        var offset = block << 6;
        var weights = block < 4 ? picture.interLuma : picture.interChroma;
        var scan = picture.scan;
        var mpeg2 = picture.mpeg2;
        var scale = quantizerScale;
        var sum = 0;
        var position = 0;
        var first = true;
        while (true) {
            var entry = Vlc.coefficient(bits.peek(16), first, false);
            first = false;
            if (entry == 0) {
                return false;
            }
            var run = entry >>> 16;
            bits.skip(entry & 0xFF);
            if (run == Vlc.EOB) {
                break;
            }
            int level;
            if (run == Vlc.ESCAPE) {
                run = bits.read(6);
                level = escapeLevel(mpeg2);
                if (level == 0) {
                    return false;
                }
            } else {
                level = entry >> 8 & 0xFF;
                if (bits.readBit() == 1) {
                    level = -level;
                }
            }
            position += run;
            if (position >= 64) {
                return false;
            }
            var magnitude = (((level < 0 ? -level : level) * 2 + 1) * scale * weights[position]) >> 5;
            int coefficient;
            if (mpeg2) {
                coefficient = saturate(level < 0 ? -magnitude : magnitude);
                sum += coefficient;
            } else {
                coefficient = oddify(magnitude, level < 0);
            }
            blocks[offset + scan[position]] = (short) coefficient;
            position++;
        }
        if (mpeg2 && (sum & 1) == 0) {
            blocks[offset + 63] ^= 1;
        }
        return true;
    }

    /**
     * Reads the level of an escape-coded coefficient: 12 bits in two's complement for MPEG-2, 8 or 16 bits for
     * MPEG-1.
     *
     * @param mpeg2 whether the stream is MPEG-2
     * @return the signed level, or 0 for a forbidden value
     */
    private int escapeLevel(boolean mpeg2) {
        if (mpeg2) {
            var value = bits.read(12);
            if ((value & 2047) == 0) {
                return 0;
            }
            return value >= 2048 ? value - 4096 : value;
        }
        var value = bits.read(8);
        if (value == 0) {
            return bits.read(8);
        }
        if (value == 128) {
            return bits.read(8) - 256;
        }
        return value > 128 ? value - 256 : value;
    }

    /**
     * Applies the MPEG-1 mismatch control and saturation (ISO/IEC 11172-2 2.4.4.1): an even reconstructed value is
     * moved one step toward zero, then clipped to [-2048, 2047].
     *
     * @param magnitude the magnitude of the reconstructed value
     * @param negative whether the level is negative
     * @return the coefficient
     */
    private static int oddify(int magnitude, boolean negative) {
        if (magnitude != 0) {
            magnitude = (magnitude - 1) | 1;
        }
        return negative ? -Math.min(magnitude, 2048) : Math.min(magnitude, 2047);
    }

    /**
     * Clips a coefficient to [-2048, 2047].
     *
     * @param value the reconstructed coefficient
     * @return the clipped value
     */
    private static int saturate(int value) {
        return value > 2047 ? 2047 : value < -2048 ? -2048 : value;
    }

    /**
     * Inverse transforms the coded blocks of a macroblock into its frame: stored for an intra macroblock, added to
     * the prediction for the others (ISO/IEC 13818-2 7.6.8).
     *
     * @param address the macroblock address
     * @param intra whether the macroblock is intra coded
     * @param pattern the coded block pattern, bit {@code count - 1 - block} for each block
     * @param fieldDct whether the luma blocks (and the chroma blocks of 4:2:2 and 4:4:4) are field blocks
     */
    private void reconstruct(int address, boolean intra, int pattern, boolean fieldDct) {
        var picture = this.picture;
        var frame = picture.current;
        var mbx = address % picture.mbWidth;
        var mby = address / picture.mbWidth;
        var x = mbx << 4;
        var width = frame.width;
        var count = picture.blockCount;
        int lumaBase;
        int lumaStride;
        int lumaStep;
        if (picture.structure == Picture.FRAME) {
            lumaBase = (mby << 4) * width + x;
            lumaStride = fieldDct ? width << 1 : width;
            lumaStep = fieldDct ? width : 8 * width;
        } else {
            lumaBase = ((mby << 5) + picture.parity()) * width + x;
            lumaStride = width << 1;
            lumaStep = 8 * lumaStride;
        }
        for (var block = 0; block < 4; block++) {
            if ((pattern & 1 << (count - 1 - block)) == 0) {
                continue;
            }
            var target = lumaBase + (block >> 1) * lumaStep + (block & 1) * 8;
            if (intra) {
                dsp.idctPut(blocks, block << 6, frame.y, target, lumaStride, work);
            } else {
                dsp.idctAdd(blocks, block << 6, frame.y, target, lumaStride, work);
            }
        }
        var chromaWidth = frame.chromaWidth;
        var shiftX = picture.chromaFormat == 3 ? 0 : 1;
        var shiftY = picture.chromaFormat == 1 ? 1 : 0;
        var cx = x >> shiftX;
        int chromaBase;
        int chromaStride;
        int chromaStep;
        if (picture.structure == Picture.FRAME) {
            chromaBase = ((mby << 4) >> shiftY) * chromaWidth + cx;
            var field = fieldDct && picture.chromaFormat != 1;
            chromaStride = field ? chromaWidth << 1 : chromaWidth;
            chromaStep = field ? chromaWidth : 8 * chromaWidth;
        } else {
            chromaBase = ((((mby << 4) >> shiftY) << 1) + picture.parity()) * chromaWidth + cx;
            chromaStride = chromaWidth << 1;
            chromaStep = 8 * chromaStride;
        }
        for (var block = 4; block < count; block++) {
            if ((pattern & 1 << (count - 1 - block)) == 0) {
                continue;
            }
            var index = (block - 4) >> 1;
            var target = chromaBase + (index & 1) * chromaStep + (index >> 1) * 8;
            var plane = (block & 1) == 0 ? frame.u : frame.v;
            if (intra) {
                dsp.idctPut(blocks, block << 6, plane, target, chromaStride, work);
            } else {
                dsp.idctAdd(blocks, block << 6, plane, target, chromaStride, work);
            }
        }
    }

    /**
     * Forms the prediction of a non-intra macroblock in its frame (ISO/IEC 13818-2 7.6).
     *
     * @param address the macroblock address
     * @param type the macroblock type flags; a P-picture macroblock without forward motion predicts forward with
     * zero vectors
     * @param motion the motion type
     * @param frame whether the picture is a frame picture
     */
    private void predict(int address, int type, int motion, boolean frame) {
        var forward = (type & Vlc.FORWARD) != 0 || picture.codingType == Picture.P;
        var backward = (type & Vlc.BACKWARD) != 0 && picture.codingType == Picture.B;
        if (forward) {
            predictDirection(address, 0, motion, frame, false);
        }
        if (backward) {
            predictDirection(address, 1, motion, frame, forward);
        }
    }

    /**
     * Forms the prediction of one direction of a macroblock.
     *
     * @param address the macroblock address
     * @param direction 0 forward, 1 backward
     * @param motion the motion type
     * @param frame whether the picture is a frame picture
     * @param average whether to average into a prediction already formed
     */
    private void predictDirection(int address, int direction, int motion, boolean frame, boolean average) {
        var picture = this.picture;
        var x = (address % picture.mbWidth) << 4;
        var row = (address / picture.mbWidth) << 4;
        var vectors = predictors;
        if (frame) {
            var reference = direction == 0 ? picture.forward : picture.backward;
            if (motion == FRAME_MOTION) {
                region(reference, -1, x, row, -1, row, 16, 16, vectors[0][direction][0], vectors[0][direction][1],
                        average);
            } else if (motion == FIELD_MOTION) {
                for (var field = 0; field < 2; field++) {
                    region(reference, fieldSelect[field][direction], x, row >> 1, field, row >> 1, 16, 8,
                            vectors[field][direction][0], vectors[field][direction][1] >> 1, average);
                }
            } else {
                var mvx = vectors[0][0][0];
                var mvy = vectors[0][0][1] >> 1;
                dualPrime(mvx, mvy, true);
                for (var field = 0; field < 2; field++) {
                    region(reference, field, x, row >> 1, field, row >> 1, 16, 8, mvx, mvy, false);
                    region(reference, 1 - field, x, row >> 1, field, row >> 1, 16, 8, dualVectors[field][0],
                            dualVectors[field][1], true);
                }
            }
            return;
        }
        var parity = picture.parity();
        if (motion == FIELD_MOTION) {
            var select = fieldSelect[0][direction];
            region(fieldReference(direction, select), select, x, row, parity, row, 16, 16, vectors[0][direction][0],
                    vectors[0][direction][1], average);
        } else if (motion == FRAME_MOTION) {
            for (var half = 0; half < 2; half++) {
                var select = fieldSelect[half][direction];
                region(fieldReference(direction, select), select, x, row + 8 * half, parity, row + 8 * half, 16, 8,
                        vectors[half][direction][0], vectors[half][direction][1], average);
            }
        } else {
            var mvx = vectors[0][0][0];
            var mvy = vectors[0][0][1];
            dualPrime(mvx, mvy, false);
            region(picture.forward, parity, x, row, parity, row, 16, 16, mvx, mvy, false);
            var opposite = picture.secondField ? picture.current : picture.forward;
            region(opposite, 1 - parity, x, row, parity, row, 16, 16, dualVectors[0][0], dualVectors[0][1], true);
        }
    }

    /**
     * Returns the frame a field picture predicts a field from (ISO/IEC 13818-2 7.6.1): the second field of a
     * P-frame predicts the opposite parity from the first field of its own frame.
     *
     * @param direction 0 forward, 1 backward
     * @param select {@code motion_vertical_field_select}
     * @return the frame, or {@code null} when it is missing
     */
    private Frame fieldReference(int direction, int select) {
        var picture = this.picture;
        if (direction == 1) {
            return picture.backward;
        }
        if (picture.codingType == Picture.P && picture.secondField && select != picture.parity()) {
            return picture.current;
        }
        return picture.forward;
    }

    /**
     * Derives the dual-prime vectors (ISO/IEC 13818-2 7.6.3.6) into {@link #dualVectors}.
     *
     * @param mvx the horizontal component of the transmitted vector
     * @param mvy the vertical component, in field units
     * @param frame whether the picture is a frame picture
     */
    private void dualPrime(int mvx, int mvy, boolean frame) {
        var roundX = mvx > 0 ? 1 : 0;
        var roundY = mvy > 0 ? 1 : 0;
        if (frame) {
            var near = picture.topFieldFirst ? 1 : 3;
            var far = picture.topFieldFirst ? 3 : 1;
            dualVectors[0][0] = ((near * mvx + roundX) >> 1) + dualDelta[0];
            dualVectors[0][1] = ((near * mvy + roundY) >> 1) + dualDelta[1] - 1;
            dualVectors[1][0] = ((far * mvx + roundX) >> 1) + dualDelta[0];
            dualVectors[1][1] = ((far * mvy + roundY) >> 1) + dualDelta[1] + 1;
        } else {
            dualVectors[0][0] = ((mvx + roundX) >> 1) + dualDelta[0];
            dualVectors[0][1] = ((mvy + roundY) >> 1) + dualDelta[1] + (picture.structure == Picture.TOP_FIELD
                    ? -1 : 1);
        }
    }

    /**
     * Predicts the luma and chroma samples of a region of the macroblock.
     *
     * @param reference the reference frame, or {@code null} when it is missing
     * @param field the reference field (0 top, 1 bottom), or -1 for frame prediction
     * @param x the luma column of the region in the reference
     * @param y the luma row of the region in the reference frame or field
     * @param targetField the field of the destination rows (0 top, 1 bottom), or -1 for frame rows
     * @param targetY the luma row of the region in the destination frame or field
     * @param width the luma width, 16
     * @param height the luma height, 8 or 16
     * @param mvx the horizontal vector, in half samples
     * @param mvy the vertical vector, in half samples of the reference frame or field
     * @param average whether to average into a prediction already formed
     */
    private void region(Frame reference, int field, int x, int y, int targetField, int targetY, int width, int height,
                        int mvx, int mvy, boolean average) {
        var current = picture.current;
        if (reference == null) {
            missingReference = true;
            if (!average) {
                fillRegion(current, x, targetField, targetY, width, height);
            }
            return;
        }
        var lumaWidth = current.width;
        var refStride = field < 0 ? lumaWidth : lumaWidth << 1;
        var refRows = field < 0 ? current.height : current.height >> 1;
        var refBase = field < 0 ? 0 : field * lumaWidth;
        var dstStride = targetField < 0 ? lumaWidth : lumaWidth << 1;
        var dstOffset = targetField < 0 ? targetY * lumaWidth + x : ((targetY << 1) + targetField) * lumaWidth + x;
        block(reference.y, refBase, refStride, lumaWidth, refRows, x, y, mvx, mvy, current.y, dstOffset, dstStride,
                width, height, average);
        var shiftX = picture.chromaFormat == 3 ? 0 : 1;
        var shiftY = picture.chromaFormat == 1 ? 1 : 0;
        var chromaWidth = current.chromaWidth;
        var chromaStride = field < 0 ? chromaWidth : chromaWidth << 1;
        var chromaRows = field < 0 ? current.chromaHeight : current.chromaHeight >> 1;
        var chromaBase = field < 0 ? 0 : field * chromaWidth;
        var cx = x >> shiftX;
        var cy = y >> shiftY;
        var ctY = targetY >> shiftY;
        var cDstStride = targetField < 0 ? chromaWidth : chromaWidth << 1;
        var cDstOffset = targetField < 0 ? ctY * chromaWidth + cx : ((ctY << 1) + targetField) * chromaWidth + cx;
        var cmvx = shiftX == 0 ? mvx : mvx / 2;
        var cmvy = shiftY == 0 ? mvy : mvy / 2;
        var cw = width >> shiftX;
        var ch = height >> shiftY;
        block(reference.u, chromaBase, chromaStride, chromaWidth, chromaRows, cx, cy, cmvx, cmvy, current.u,
                cDstOffset, cDstStride, cw, ch, average);
        block(reference.v, chromaBase, chromaStride, chromaWidth, chromaRows, cx, cy, cmvx, cmvy, current.v,
                cDstOffset, cDstStride, cw, ch, average);
    }

    /**
     * Fills a region of the current frame with mid-gray, the prediction of a missing reference.
     *
     * @param frame the frame being decoded
     * @param x the luma column
     * @param targetField the destination field, or -1 for frame rows
     * @param targetY the luma row in the destination frame or field
     * @param width the luma width
     * @param height the luma height
     */
    private void fillRegion(Frame frame, int x, int targetField, int targetY, int width, int height) {
        var stride = targetField < 0 ? frame.width : frame.width << 1;
        var offset = targetField < 0 ? targetY * frame.width + x : ((targetY << 1) + targetField) * frame.width + x;
        for (var row = 0; row < height; row++) {
            Arrays.fill(frame.y, offset + row * stride, offset + row * stride + width, (byte) 128);
        }
        var shiftX = picture.chromaFormat == 3 ? 0 : 1;
        var shiftY = picture.chromaFormat == 1 ? 1 : 0;
        var cStride = targetField < 0 ? frame.chromaWidth : frame.chromaWidth << 1;
        var ctY = targetY >> shiftY;
        var cOffset = targetField < 0 ? ctY * frame.chromaWidth + (x >> shiftX)
                : ((ctY << 1) + targetField) * frame.chromaWidth + (x >> shiftX);
        for (var row = 0; row < height >> shiftY; row++) {
            var start = cOffset + row * cStride;
            Arrays.fill(frame.u, start, start + (width >> shiftX), (byte) 128);
            Arrays.fill(frame.v, start, start + (width >> shiftX), (byte) 128);
        }
    }

    /**
     * Predicts one block of one plane at half sample precision (ISO/IEC 13818-2 7.6.4), repeating the edge
     * samples of the reference for the parts of the block that reach past it.
     *
     * @param src the reference plane
     * @param base the index of the first sample of the reference frame or field
     * @param stride the row stride of the reference frame or field
     * @param columns the width of the reference
     * @param rows the height of the reference frame or field
     * @param x the column of the block
     * @param y the row of the block
     * @param mvx the horizontal vector, in half samples
     * @param mvy the vertical vector, in half samples
     * @param dst the destination plane
     * @param dstOffset the index of the block's top-left destination sample
     * @param dstStride the row stride of the destination
     * @param width the block width
     * @param height the block height
     * @param average whether to average into the destination
     */
    private void block(byte[] src, int base, int stride, int columns, int rows, int x, int y, int mvx, int mvy,
                       byte[] dst, int dstOffset, int dstStride, int width, int height, boolean average) {
        var left = x + (mvx >> 1);
        var top = y + (mvy >> 1);
        var halfX = mvx & 1;
        var halfY = mvy & 1;
        byte[] source;
        int sourceOffset;
        int sourceStride;
        if (left >= 0 && top >= 0 && left + width + halfX <= columns && top + height + halfY <= rows) {
            source = src;
            sourceOffset = base + top * stride + left;
            sourceStride = stride;
        } else {
            for (var row = 0; row < height + halfY; row++) {
                var sourceRow = Math.clamp(top + row, 0, rows - 1);
                var rowStart = base + sourceRow * stride;
                for (var column = 0; column < width + halfX; column++) {
                    edge[row * EDGE_STRIDE + column] = src[rowStart + Math.clamp(left + column, 0, columns - 1)];
                }
            }
            source = edge;
            sourceOffset = 0;
            sourceStride = EDGE_STRIDE;
        }
        if (average) {
            dsp.halfPel(source, sourceOffset, sourceStride, second, 0, 16, width, height, halfX, halfY, 0);
            dsp.average(second, 0, 16, dst, dstOffset, dstStride, width, height);
        } else {
            dsp.halfPel(source, sourceOffset, sourceStride, dst, dstOffset, dstStride, width, height, halfX, halfY, 0);
        }
    }

    /**
     * Conceals a macroblock the stream left out or damaged: it is predicted from the forward reference frame (the
     * previous reference frame for an I-picture) with the vector the macroblock above left, or with a zero vector,
     * and filled with mid-gray without a reference.
     *
     * @param picture the picture whose frame holds the macroblock
     * @param address the macroblock address
     */
    void conceal(Picture picture, int address) {
        this.picture = picture;
        var frame = picture.current;
        var reference = picture.forward != null ? picture.forward : picture.backward;
        if (reference == frame) {
            reference = null;
        }
        var x = (address % picture.mbWidth) << 4;
        var row = (address / picture.mbWidth) << 4;
        var above = address - picture.mbWidth;
        var mvx = 0;
        var mvy = 0;
        if (above >= 0 && frame.hasVector[above]) {
            mvx = frame.vectorX[above];
            mvy = frame.vectorY[above];
        }
        var saved = missingReference;
        if (picture.structure == Picture.FRAME) {
            region(reference, -1, x, row, -1, row, 16, 16, mvx, mvy, false);
        } else {
            region(reference, picture.parity(), x, row, picture.parity(), row, 16, 16, mvx, mvy, false);
        }
        missingReference = saved;
        frame.hasVector[address] = above >= 0 && frame.hasVector[above];
        frame.vectorX[address] = mvx;
        frame.vectorY[address] = mvy;
    }
}
