/* Ports libxaac (decoder/ixheaacd_acelp_bitparse.c, ixheaacd_lpc.c, ixheaacd_lpc_dec.c,
   ixheaacd_acelp_decode.c, ixheaacd_acelp_tools.c, ixheaacd_tcx_fwd_mdct.c, ixheaacd_tcx_fwd_alcnx.c,
   ixheaacd_fwd_alias_cnx.c: the linear prediction domain decoder and its transitions), commit 2fbadd57.
   Copyright 2018 The Android Open Source Project; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernel;
import com.github.auties00.smalt.audio.aac.internal.Dct4;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.FAC_LENGTH;
import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.FILTER_DELAY;
import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.INTERPOLATION_ORDER;
import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.MAX_PITCH;
import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.ORDER;
import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.SUBFRAME;
import static com.github.auties00.smalt.audio.aac.internal.usac.LpdState.SYNTH_DELAY_MAX;

/**
 * Decodes the linear prediction domain channel stream of USAC ({@code lpd_channel_stream}, ISO/IEC 23003-3
 * subclause 7.13): LPC quantization with the algebraic vector quantizer, ACELP and TCX divisions, forward
 * aliasing cancellation and the bass postfilter, and the transitions to and from the frequency domain.
 *
 * <p>Instances hold scratch memory and are not thread-safe; the per-channel state lives in {@link LpdState}.
 */
final class LpdDecoder {
    private static final float PREEMPHASIS = 0.68f;
    private static final float TILT_CODE = 0.3f;
    private static final int TMIN = 34;
    private static final int TFR2 = 162 - TMIN;
    private static final int TFR1 = 160;
    private static final int TMAX = 27 + 6 * TMIN;
    private static final int UP_SAMPLE = 4;
    private static final int[] CODE_BITS = {20, 28, 36, 44, 52, 64, 12, 16};
    private static final int[][] ICB_BITS = {
            {5, 5, 5, 5}, {9, 9, 5, 5}, {9, 9, 9, 9}, {13, 13, 9, 9}, {13, 13, 13, 13}, {16, 16, 16, 16},
            {1, 5, 1, 5}, {1, 5, 5, 5}
    };
    private static final float[] FACTORS = {60.0f, 65.0f, 64.0f, 63.0f};

    private final int frameLength;
    private final int division;
    private final int subframes;
    private final int superframeSubframes;
    private final int half;
    private final int synthDelay;
    private final int lpdDelay;
    private final int facLength;
    private final int pitchMin;
    private final int pitchFr2;
    private final int pitchFr1;
    private final int pitchMax;

    private final AacKernel kernel;
    private final Dct4[] transforms = new Dct4[12];
    private final Avq avq = new Avq();

    private int coreMode;
    private final int[] mod = new int[4];
    private final int[] meanEnergy = new int[4];
    private final int[] acbIndex = new int[16];
    private final int[] ltpFilter = new int[16];
    private final int[][] icbIndex = new int[16][8];
    private final int[] gainIndex = new int[16];
    private final int[] noiseFactor = new int[4];
    private final int[] globalGain = new int[4];
    private final int[] tcxLength = new int[4];
    private final int[] quant = new int[1024 + 8];
    private final int[] lpcIndices = new int[512];
    private final int[] nkMode = new int[1];
    private final int[] fac = new int[4 * FAC_LENGTH + 8];
    private final int[] fdFac = new int[1 + FAC_LENGTH + 8];
    private boolean bassPostFilter;
    private boolean firstLpd;
    private boolean shortFac;
    private boolean facPresent;

    private final float[] synthBuffer = new float[MAX_PITCH + SYNTH_DELAY_MAX + 1024 + 2 * SUBFRAME];
    private final float[] excitationBuffer = new float[MAX_PITCH + INTERPOLATION_ORDER + 1 + 1024 + 2 * SUBFRAME];
    private final int[] pitch = new int[16 + 8 + 4];
    private final float[] pitchGain = new float[16 + 8 + 4];
    private final float[] lsf = new float[5 * ORDER];
    private final float[] lspCurrent = new float[ORDER];
    private final float[] lsfCurrent = new float[ORDER];
    private final float[] filters = new float[(16 + 1) * (ORDER + 1)];
    private final float[] fsynth = new float[1024 + SUBFRAME];
    private final int[] avqOut = new int[16];
    private final float[] weights = new float[ORDER];
    private final float[] polyA = new float[ORDER / 2 + 2];
    private final float[] polyB = new float[ORDER / 2 + 2];
    private final float[] interpolated = new float[ORDER];
    private final float[] code = new float[SUBFRAME];
    private final float[] postProcess = new float[SUBFRAME];
    private final float[] synthMemory = new float[128 + 16];
    private final float[] x = new float[1024];
    private final float[] dctOut = new float[1024];
    private final float[] xnBuffer = new float[1024 + 2 * FAC_LENGTH];
    private final float[] facSignal = new float[2 * FAC_LENGTH + ORDER];
    private final float[] facWindow = new float[2 * FAC_LENGTH];
    private final float[] weighted = new float[ORDER + 1];
    private final float[] gains1 = new float[64];
    private final float[] gains2 = new float[64];
    private final float[] lowGains = new float[32];
    private final float[] shaped = new float[1024];
    private final float[] residualBuffer = new float[ORDER + 3 * 256];
    private final float[] fdSynth = new float[ORDER + 2 * 256];
    private final float[] excitationScratch = new float[3 * 256];
    private final float[] transitionFilters = new float[9 * (ORDER + 1)];
    private final float[] bassNoise = new float[FILTER_DELAY + 2 * SUBFRAME];
    private final float[] bassOut = new float[1024 + SUBFRAME];

    /**
     * Constructs the decoder of a frame length.
     *
     * @param frameLength the core frame length, 768 or 1024
     * @param coreRate the sampling frequency of the core coder, which scales the pitch lag ranges
     * @param imdct the transforms of the channel's frequency domain synthesis, whose kernel the TCX transforms
     * share
     */
    LpdDecoder(int frameLength, int coreRate, Imdct imdct) {
        this.frameLength = frameLength;
        this.division = frameLength / 4;
        this.subframes = frameLength / 256;
        this.superframeSubframes = 4 * subframes;
        this.half = superframeSubframes / 2 - 1;
        this.synthDelay = half * SUBFRAME;
        this.lpdDelay = (half + 1) * SUBFRAME;
        this.facLength = division / 2;
        this.kernel = imdct.kernel();
        var offset = (coreRate * TMIN + 6400) / 12800 - TMIN;
        this.pitchMin = TMIN + offset;
        this.pitchFr2 = TFR2 - offset;
        this.pitchFr1 = TFR1;
        this.pitchMax = TMAX + 6 * offset;
    }

    /**
     * Reads the FAC data of a frequency domain frame ({@code fac_data}): the gain index and {@code length}
     * coefficients.
     *
     * @param reader the bit source
     * @param length the number of coefficients, a multiple of 8
     * @param out receives the gain index at index 0 and the coefficients after it
     */
    void readFac(AacBitReader reader, int length, int[] out) {
        out[0] = reader.read(7);
        for (var i = 0; i < length; i += 8) {
            avq.readUnary(reader, out, 1 + i);
        }
    }

    private Dct4 dct(int size) {
        for (var i = 0; i < transforms.length; i++) {
            var transform = transforms[i];
            if (transform == null) {
                transform = new Dct4(size, 1.0, kernel);
                transforms[i] = transform;
                return transform;
            }
            if (transform.size() == size) {
                return transform;
            }
        }
        return new Dct4(size, 1.0, kernel);
    }

    /**
     * Decodes one linear prediction domain frame of a channel into its output.
     *
     * @param reader the bit source, after {@code core_mode}
     * @param channel the state of the channel
     * @param independent whether the frame is independent
     * @throws MediaException.Malformed if the stream is corrupt
     */
    void decode(AacBitReader reader, UsacChannel channel, boolean independent) {
        var state = channel.lpdState;
        if (!channel.lastLpd) {
            resetFromFd(channel);
        }
        parse(reader, channel, independent);
        reader.checkNotOverrun();
        synthesize(channel, state);
        combine(channel);
        channel.previousShape = 0;
        channel.lastSequence = FdChannel.EIGHT_SHORT;
    }

    /**
     * Prepares the state for an LPD frame that follows a frequency domain frame
     * ({@code ixheaacd_reset_acelp_data_fix}, {@code ixheaacd_fix2flt_data}).
     */
    private void resetFromFd(UsacChannel channel) {
        var state = channel.lpdState;
        var lastShort = channel.lastSequence == FdChannel.EIGHT_SHORT;
        state.modePrevious = lastShort ? -2 : -1;
        Arrays.fill(state.pitchPrevious, 64);
        Arrays.fill(state.gainPrevious, 0f);
        state.bassPostFilterPrevious = false;
        var overlap = channel.overlap;
        var fl = lastShort ? frameLength / 16 : division / 2;
        var window = UsacTables.window(2 * fl, false);
        for (var i = 0; i < 2 * fl; i++) {
            overlap[frameLength / 2 - fl + i] *= window[2 * fl - 1 - i];
        }
        Arrays.fill(overlap, frameLength / 2 + fl, frameLength, 0f);
        Arrays.fill(state.filterPrevious, 0f);
        Arrays.fill(state.excitationPrevious, 0f);
        Arrays.fill(state.synthPrevious, 0f);
        Arrays.fill(state.bassPrevious, 0f);
        state.gainThreshold = 0f;
        Arrays.fill(state.tail, 0f);
        for (var i = 0; i < 2 * fl + 1; i++) {
            state.tail[division / 2 - fl + i] = overlap[i + frameLength / 2 - fl - 1];
        }
    }

    private void parse(AacBitReader reader, UsacChannel channel, boolean independent) {
        var state = channel.lpdState;
        coreMode = reader.read(3);
        var lpdMode = reader.read(5);
        if (lpdMode > 25) {
            throw new MediaException.Malformed("reserved USAC lpd_mode " + lpdMode);
        }
        if (lpdMode == 25) {
            Arrays.fill(mod, 3);
        } else if (lpdMode == 24) {
            Arrays.fill(mod, 2);
        } else if (lpdMode >= 20) {
            mod[0] = lpdMode & 1;
            mod[1] = (lpdMode >> 1) & 1;
            mod[2] = 2;
            mod[3] = 2;
        } else if (lpdMode >= 16) {
            mod[0] = 2;
            mod[1] = 2;
            mod[2] = lpdMode & 1;
            mod[3] = (lpdMode >> 1) & 1;
        } else {
            for (var i = 0; i < 4; i++) {
                mod[i] = (lpdMode >> i) & 1;
            }
        }
        bassPostFilter = reader.readFlag();
        var coreModeLast = reader.read(1);
        facPresent = reader.readFlag();
        firstLpd = coreModeLast == 0;
        var lastMode = state.modePrevious;
        var quantOffset = 0;
        var firstTcx = true;
        var k = 0;
        while (k < 4) {
            var readFac = k == 0 ? coreModeLast == 1 && facPresent
                    : (lastMode == 0 && mod[k] > 0) || (lastMode > 0 && mod[k] == 0);
            if (readFac) {
                for (var i = 0; i < division / 2; i += 8) {
                    avq.readUnary(reader, fac, k * FAC_LENGTH + i);
                }
            }
            if (mod[k] == 0) {
                readAcelp(reader, k);
                lastMode = 0;
                tcxLength[k] = 0;
                k++;
            } else {
                noiseFactor[k] = reader.read(3);
                globalGain[k] = reader.read(7);
                var length = division << (mod[k] - 1);
                tcxLength[k] = length;
                var reset = false;
                if (firstTcx) {
                    reset = independent || reader.readFlag();
                }
                channel.coder.prepare(length, firstTcx && reset);
                channel.coder.decode(reader, quant, quantOffset, length / 2, length / 2);
                lastMode = mod[k];
                quantOffset += length;
                var count = 1 << (mod[k] - 1);
                for (var i = 1; i < count; i++) {
                    tcxLength[k + i] = 0;
                }
                k += count;
                firstTcx = false;
            }
        }
        readLpc(reader);
        shortFac = false;
        if (coreModeLast == 0 && facPresent) {
            shortFac = reader.readFlag();
            var length = shortFac ? frameLength / 16 : frameLength / 8;
            fdFac[0] = reader.read(7);
            for (var i = 0; i < length; i += 8) {
                avq.readUnary(reader, fdFac, 1 + i);
            }
        }
    }

    private void readAcelp(AacBitReader reader, int k) {
        meanEnergy[k] = reader.read(2);
        var bits = ICB_BITS[coreMode];
        for (var sfr = 0; sfr < subframes; sfr++) {
            var kk = k * 4 + sfr;
            acbIndex[kk] = reader.read(sfr == 0 || (subframes == 4 && sfr == 2) ? 9 : 6);
            ltpFilter[kk] = reader.read(1);
            if (coreMode == 5) {
                for (var i = 0; i < 4; i++) {
                    icbIndex[kk][i] = reader.read(2);
                }
                for (var i = 4; i < 8; i++) {
                    icbIndex[kk][i] = reader.read(14);
                }
            } else {
                for (var i = 0; i < 4; i++) {
                    icbIndex[kk][i] = reader.read(bits[i]);
                }
            }
            gainIndex[kk] = reader.read(7);
        }
    }

    private int lpcMode(AacBitReader reader, int set, int[] nkMode) {
        return switch (set) {
            case 4 -> 0;
            case 0, 2 -> {
                var mode = reader.read(1);
                if (mode == 1) {
                    nkMode[0] = 3;
                }
                yield mode;
            }
            case 1 -> {
                if (reader.read(1) == 0) {
                    nkMode[0] = 2;
                    yield 2;
                }
                if (reader.read(1) == 0) {
                    nkMode[0] = 0;
                    yield 0;
                }
                nkMode[0] = 1;
                yield 1;
            }
            default -> {
                if (reader.read(1) == 0) {
                    nkMode[0] = 1;
                    yield 1;
                }
                if (reader.read(1) == 0) {
                    nkMode[0] = 0;
                    yield 0;
                }
                nkMode[0] = 2;
                yield reader.read(1) == 0 ? 2 : 3;
            }
        };
    }

    private int codebookIndices(AacBitReader reader, int nkMode, int position) {
        var qn0 = 0;
        var qn1 = 0;
        if (nkMode == 1) {
            qn0 = unary(reader);
            qn1 = unary(reader);
        } else {
            qn0 = 2 + reader.read(2);
            qn1 = 2 + reader.read(2);
            if (nkMode == 2) {
                if (qn0 > 4) {
                    qn0 = unaryCount(reader);
                    if (qn0 > 0) {
                        qn0 += 4;
                    }
                }
                if (qn1 > 4) {
                    qn1 = unaryCount(reader);
                    if (qn1 > 0) {
                        qn1 += 4;
                    }
                }
            } else {
                if (qn0 > 4) {
                    qn0 = extension(unaryCount(reader));
                }
                if (qn1 > 4) {
                    qn1 = extension(unaryCount(reader));
                }
            }
        }
        lpcIndices[position++] = qn0;
        lpcIndices[position++] = qn1;
        for (var half = 0; half < 2; half++) {
            var qn = half == 0 ? qn0 : qn1;
            if (qn > 0) {
                int nk;
                int n;
                if (qn > 4) {
                    nk = (qn - 3) / 2;
                    n = qn - nk * 2;
                } else {
                    nk = 0;
                    n = qn;
                }
                lpcIndices[position++] = reader.read(4 * n);
                for (var i = 0; i < 8; i++) {
                    lpcIndices[position++] = reader.read(nk);
                }
            }
        }
        return position;
    }

    private static int extension(int count) {
        return switch (count) {
            case 0 -> 5;
            case 1 -> 6;
            case 2 -> 0;
            default -> count + 4;
        };
    }

    private static int unaryCount(AacBitReader reader) {
        var count = 0;
        while (reader.readBit() == 1) {
            count++;
            if (count > 64) {
                throw new MediaException.Malformed("USAC LPC codebook number overflows");
            }
        }
        return count;
    }

    private static int unary(AacBitReader reader) {
        var count = unaryCount(reader);
        return count > 0 ? count + 1 : 0;
    }

    /**
     * Reads the LPC filter indices ({@code ixheaacd_lpc_data}).
     */
    private void readLpc(AacBitReader reader) {
        var nk = nkMode;
        nk[0] = 0;
        var j = 0;
        lpcMode(reader, 4, nk);
        lpcIndices[j++] = reader.read(8);
        j = codebookIndices(reader, nk[0], j);
        if (firstLpd) {
            var mode = lpcMode(reader, 0, nk);
            lpcIndices[j++] = mode;
            if (mode == 0) {
                lpcIndices[j++] = reader.read(8);
            }
            j = codebookIndices(reader, nk[0], j);
        }
        if (mod[0] < 3) {
            var mode = lpcMode(reader, 2, nk);
            lpcIndices[j++] = mode;
            if (mode == 0) {
                lpcIndices[j++] = reader.read(8);
            }
            j = codebookIndices(reader, nk[0], j);
        }
        if (mod[0] < 2) {
            var mode = lpcMode(reader, 1, nk);
            lpcIndices[j++] = mode;
            if (mode == 0) {
                lpcIndices[j++] = reader.read(8);
            }
            if (mode != 1) {
                j = codebookIndices(reader, nk[0], j);
            }
        }
        if (mod[2] < 2) {
            var mode = lpcMode(reader, 3, nk);
            lpcIndices[j++] = mode;
            if (mode == 0) {
                lpcIndices[j++] = reader.read(8);
            }
            codebookIndices(reader, nk[0], j);
        }
    }

    private int avqTool(int position, int[] out) {
        var start = position;
        position += 2;
        for (var k = 0; k < 2; k++) {
            var qn = lpcIndices[start + k];
            if (qn > 0) {
                var index = lpcIndices[position++];
                avq.decode(qn, index, lpcIndices, position, out, k * 8);
                position += 8;
            } else {
                avq.decode(0, 0, lpcIndices, 0, out, k * 8);
            }
        }
        return position - start;
    }

    /**
     * Decodes an absolute LPC vector: the first stage codebook vector refined by the second stage
     * ({@code vlpc_1st_dec}, {@code vlpc_2st_dec} with mode 0).
     */
    private int firstApproximationAbsolute(int target, int position) {
        var index = lpcIndices[position];
        for (var i = 0; i < ORDER; i++) {
            lsf[target + i] = LpdTables.LSF_ABSOLUTE[index * ORDER + i];
        }
        return 1 + secondStage(target, position + 1, 0);
    }

    /**
     * Refines an LPC vector with the second stage ({@code vlpc_2st_dec}): weights derived from the vector's
     * spacing, the RE8 refinement, and reordering to a minimum spacing of 50 Hz.
     */
    private int secondStage(int target, int position, int mode) {
        for (var i = 0; i < ORDER; i++) {
            var lower = i == 0 ? lsf[target] : lsf[target + i] - lsf[target + i - 1];
            var upper = i == ORDER - 1 ? 6400.0f - lsf[target + ORDER - 1] : lsf[target + i + 1] - lsf[target + i];
            weights[i] = (float) (FACTORS[mode] / (400.0f / StrictMath.sqrt(lower * upper)));
        }
        var consumed = avqTool(position, avqOut);
        for (var i = 0; i < ORDER; i++) {
            lsf[target + i] += weights[i] * (float) avqOut[i];
        }
        var min = 50.0f;
        for (var i = 0; i < ORDER; i++) {
            if (lsf[target + i] < min) {
                lsf[target + i] = min;
            }
            min = lsf[target + i] + 50.0f;
        }
        min = 6400.0f - 50.0f;
        for (var i = ORDER - 1; i >= 0; i--) {
            if (lsf[target + i] > min) {
                lsf[target + i] = min;
            }
            min = lsf[target + i] - 50.0f;
        }
        return consumed;
    }

    private int firstApproximationRelative(int target, int position, int mode) {
        return secondStage(target, position, mode);
    }

    /**
     * Dequantizes the LPC filters of the superframe ({@code ixheaacd_alg_vec_dequant}).
     */
    private void dequantizeLpc() {
        var position = firstApproximationAbsolute(4 * ORDER, 0);
        if (firstLpd) {
            var mode = lpcIndices[position++];
            if (mode == 0) {
                position += firstApproximationAbsolute(0, position);
            } else if (mode == 1) {
                System.arraycopy(lsf, 4 * ORDER, lsf, 0, ORDER);
                position += firstApproximationRelative(0, position, 3);
            }
        }
        if (mod[0] < 3) {
            var mode = lpcIndices[position++];
            if (mode == 0) {
                position += firstApproximationAbsolute(2 * ORDER, position);
            } else if (mode == 1) {
                System.arraycopy(lsf, 4 * ORDER, lsf, 2 * ORDER, ORDER);
                position += firstApproximationRelative(2 * ORDER, position, 3);
            }
        }
        if (mod[0] < 2) {
            var mode = lpcIndices[position++];
            if (mode == 1) {
                for (var i = 0; i < ORDER; i++) {
                    lsf[ORDER + i] = 0.5f * (lsf[i] + lsf[2 * ORDER + i]);
                }
            } else if (mode == 0) {
                position += firstApproximationAbsolute(ORDER, position);
            } else if (mode == 2) {
                System.arraycopy(lsf, 2 * ORDER, lsf, ORDER, ORDER);
                position += firstApproximationRelative(ORDER, position, 2);
            }
        }
        if (mod[2] < 2) {
            var mode = lpcIndices[position++];
            if (mode == 0) {
                firstApproximationAbsolute(3 * ORDER, position);
            } else if (mode == 1) {
                for (var i = 0; i < ORDER; i++) {
                    lsf[3 * ORDER + i] = 0.5f * (lsf[2 * ORDER + i] + lsf[4 * ORDER + i]);
                }
                firstApproximationRelative(3 * ORDER, position, 1);
            } else if (mode == 2) {
                System.arraycopy(lsf, 2 * ORDER, lsf, 3 * ORDER, ORDER);
                firstApproximationRelative(3 * ORDER, position, 2);
            } else {
                System.arraycopy(lsf, 4 * ORDER, lsf, 3 * ORDER, ORDER);
                firstApproximationRelative(3 * ORDER, position, 2);
            }
        }
    }

    private static void lsfToLsp(float[] lsf, int offset, float[] lsp) {
        for (var i = 0; i < ORDER; i++) {
            lsp[i] = (float) StrictMath.cos((double) lsf[offset + i] * (Math.PI / 6400.0));
        }
    }

    private static void lspToLsf(float[] lsp, float[] lsf, int offset) {
        for (var i = 0; i < ORDER; i++) {
            lsf[offset + i] = (float) (StrictMath.acos(lsp[i]) * (6400.0 / Math.PI));
        }
    }

    /**
     * Converts LSPs to LPC coefficients ({@code ixheaacd_lsp_to_lp_conversion}).
     */
    private void lspToLp(float[] lsp, float[] out, int offset) {
        var f1 = polyA;
        var f2 = polyB;
        f1[0] = 0;
        f2[0] = 0;
        f1[1] = 1.0f;
        f2[1] = 1.0f;
        var p = 0;
        for (var i = 1; i <= ORDER / 2; i++) {
            var b1 = -2.0f * lsp[p++];
            var b2 = -2.0f * lsp[p++];
            f1[i + 1] = b1 * f1[i] + 2.0f * f1[i - 1];
            f2[i + 1] = b2 * f2[i] + 2.0f * f2[i - 1];
            for (var j = i - 1; j > 0; j--) {
                f1[j + 1] += b1 * f1[j] + f1[j - 1];
                f2[j + 1] += b2 * f2[j] + f2[j - 1];
            }
        }
        for (var i = ORDER / 2 + 1; i > 1; i--) {
            f1[i] += f1[i - 1];
            f2[i] -= f2[i - 1];
        }
        out[offset] = 1.0f;
        for (var i = 0; i < ORDER / 2; i++) {
            out[offset + 1 + i] = 0.5f * (f1[i + 2] + f2[i + 2]);
            out[offset + ORDER - i] = 0.5f * (f1[i + 2] - f2[i + 2]);
        }
    }

    /**
     * Interpolates the filters of {@code count} subframes and appends the new filter
     * ({@code ixheaacd_interpolation_lsp_params}).
     */
    private void interpolate(float[] oldLsp, float[] newLsp, float[] out, int count) {
        var factor = 1.0f / count;
        var plus = 0.5f * factor;
        var offset = 0;
        for (var k = 0; k < count; k++) {
            var minus = 1.0f - plus;
            for (var i = 0; i < ORDER; i++) {
                interpolated[i] = oldLsp[i] * minus + newLsp[i] * plus;
            }
            plus += factor;
            lspToLp(interpolated, out, offset);
            offset += ORDER + 1;
        }
        lspToLp(newLsp, out, offset);
    }

    /**
     * Builds the TCX filters: the interpolated, the old and the new filter ({@code ixheaacd_lpc_coef_gen}).
     */
    private void tcxFilters(float[] oldLsp, float[] newLsp, float[] out, int count) {
        var inc = 1.0f / count;
        var fNew = 0.5f - 0.5f * inc;
        var fOld = 1.0f - fNew;
        for (var i = 0; i < ORDER; i++) {
            interpolated[i] = oldLsp[i] * fOld + newLsp[i] * fNew;
        }
        lspToLp(interpolated, out, 0);
        lspToLp(oldLsp, out, ORDER + 1);
        lspToLp(newLsp, out, 2 * (ORDER + 1));
    }

    private void synthesize(UsacChannel channel, LpdState state) {
        var synthBase = MAX_PITCH + synthDelay;
        Arrays.fill(synthBuffer, 0f);
        System.arraycopy(state.synthPrevious, 0, synthBuffer, 0, MAX_PITCH + synthDelay);
        var excitationBase = MAX_PITCH + INTERPOLATION_ORDER + 1;
        Arrays.fill(excitationBuffer, 0f);
        System.arraycopy(state.excitationPrevious, 0, excitationBuffer, 0, excitationBase);
        for (var i = 0; i < half; i++) {
            pitch[i] = state.pitchPrevious[i];
            pitchGain[i] = state.gainPrevious[i];
        }
        for (var i = 0; i < superframeSubframes; i++) {
            pitch[i + half] = 64;
            pitchGain[i + half] = 0f;
        }
        if (!firstLpd) {
            lspToLsf(state.lspOld, lsf, 0);
        }
        dequantizeLpc();
        if (firstLpd) {
            System.arraycopy(lsf, 0, state.lsfPrevious, 0, ORDER);
            lsfToLsp(state.lsfPrevious, 0, state.lspOld);
        }
        if (firstLpd && (mod[0] == 0 || mod[1] == 0 || (mod[2] == 0 && division != 256))) {
            transitionFromFd(channel, state, synthBase, excitationBase);
        }
        var k = 0;
        var stability = 0f;
        while (k < 4) {
            var mode = mod[k];
            if (state.modePrevious == 0 && mode > 0 && (k != 0 || state.bassPostFilterPrevious)) {
                var i = k * subframes + half;
                pitch[i + 1] = pitch[i] = pitch[i - 1];
                pitchGain[i + 1] = pitchGain[i] = pitchGain[i - 1];
            }
            var source = mode == 0 || mode == 1 ? (k + 1) * ORDER : mode == 2 ? (k + 2) * ORDER : (k + 4) * ORDER;
            System.arraycopy(lsf, source, lsfCurrent, 0, ORDER);
            lsfToLsp(lsfCurrent, 0, lspCurrent);
            var distance = 0.0f;
            for (var i = 0; i < ORDER; i++) {
                var d = lsfCurrent[i] - state.lsfPrevious[i];
                distance += d * d;
            }
            stability = Math.clamp(1.25f - distance / 400000.0f, 0.0f, 1.0f);
            if (mode == 0) {
                interpolate(state.lspOld, lspCurrent, filters, subframes);
                acelp(state, k, stability, synthBase + k * division, excitationBase + k * division);
                if (state.modePrevious != 0 && bassPostFilter) {
                    var i = k * subframes + half;
                    pitch[i - 1] = pitch[i];
                    pitchGain[i - 1] = pitchGain[i];
                    if (state.modePrevious != -2) {
                        pitch[i - 2] = pitch[i];
                        pitchGain[i - 2] = pitchGain[i];
                    }
                }
                k++;
            } else {
                var length = division << (mode - 1);
                var count = mode == 1 ? subframes : mode == 2 ? superframeSubframes / 2 : superframeSubframes;
                tcxFilters(state.lspOld, lspCurrent, filters, count);
                tcx(channel, state, k, length, synthBase + k * division, excitationBase + k * division);
                k += 1 << (mode - 1);
            }
            state.modePrevious = mode;
            System.arraycopy(lspCurrent, 0, state.lspOld, 0, ORDER);
            System.arraycopy(lsfCurrent, 0, state.lsfPrevious, 0, ORDER);
        }
        System.arraycopy(excitationBuffer, frameLength, state.excitationPrevious, 0, excitationBase);
        System.arraycopy(synthBuffer, frameLength, state.synthPrevious, 0, MAX_PITCH + synthDelay);
        if (!bassPostFilter) {
            var from = mod[0] != 0 && state.bassPostFilterPrevious ? 2 : 0;
            for (var i = from; i < superframeSubframes; i++) {
                pitchGain[half + i] = 0f;
            }
        }
        state.bassPostFilterPrevious = bassPostFilter;
        for (var i = 0; i < half; i++) {
            state.pitchPrevious[i] = pitch[superframeSubframes + i];
            state.gainPrevious[i] = pitchGain[superframeSubframes + i];
        }
        var synth = MAX_PITCH;
        recomputeGains(synthBuffer, synth, superframeSubframes);
        bassPostFilter(synthBuffer, synth, fsynth, frameLength, mod[3] == 0 ? synthDelay : synthDelay - division / 2,
                state.bassPrevious);
    }

    private void recomputeGains(float[] synth, int base, int count) {
        for (var i = 0; i < count; i++) {
            var tp = pitch[i];
            if (pitchGain[i] > 0.0f) {
                var correlation = 0.0f;
                var energy = 1e-6f;
                for (var k = 0; k < SUBFRAME; k++) {
                    var past = synth[base + i * SUBFRAME - tp + k];
                    correlation += synth[base + i * SUBFRAME + k] * past;
                    energy += past * past;
                }
                pitchGain[i] = correlation / energy;
            }
        }
    }

    /**
     * Builds the past synthesis and excitation of the first LPD frame after a frequency domain frame,
     * applying the FD to ACELP forward aliasing cancellation.
     */
    private void transitionFromFd(UsacChannel channel, LpdState state, int synthBase, int excitationBase) {
        interpolate(state.lspOld, state.lspOld, transitionFilters, 8);
        System.arraycopy(transitionFilters, 0, state.filterPrevious, 0, ORDER + 1);
        System.arraycopy(transitionFilters, 0, state.filterPrevious, ORDER + 1, ORDER + 1);
        var overlap = channel.overlap;
        if (mod[0] == 0) {
            var length = shortFac ? frameLength / 16 : division / 2;
            var gain = (float) StrictMath.pow(10.0, fdFac[0] / 28.0);
            for (var i = 0; i < length; i++) {
                x[i] = fdFac[1 + i];
            }
            dct(length).transform(x, 0, dctOut, 0);
            weight(transitionFilters, 0, weighted);
            Arrays.fill(facSignal, 0, ORDER, 0f);
            System.arraycopy(dctOut, 0, facSignal, ORDER, length);
            synthesisInPlace(weighted, facSignal, ORDER, length);
            for (var i = 0; i < length; i++) {
                overlap[frameLength / 2 - length + i] += facSignal[ORDER + i] * gain;
            }
            Arrays.fill(overlap, frameLength / 2, frameLength / 2 + length, 0f);
        }
        var n = 2 * division;
        for (var i = 0; i < n; i++) {
            fdSynth[ORDER + i] = overlap[i];
        }
        System.arraycopy(fdSynth, ORDER, synthBuffer, synthBase - n, n);
        preemphasis(fdSynth, ORDER, n, 0f);
        Arrays.fill(fdSynth, 0, ORDER, 0f);
        residual(transitionFilters, 0, fdSynth, ORDER, excitationScratch, 0, n, false);
        int count;
        if (mod[0] != 0 && (division == 256 || mod[1] != 0)) {
            count = Math.min(division, MAX_PITCH + INTERPOLATION_ORDER + 1);
        } else {
            count = Math.min(n, MAX_PITCH + INTERPOLATION_ORDER + 1);
        }
        System.arraycopy(excitationScratch, n - count, excitationBuffer, excitationBase - count, count);
    }

    private static void weight(float[] a, int offset, float[] out) {
        out[0] = a[offset];
        var f = 0.92f;
        for (var i = 1; i <= ORDER; i++) {
            out[i] = f * a[offset + i];
            f *= 0.92f;
        }
    }

    private static void preemphasis(float[] signal, int offset, int length, float memory) {
        for (var i = length - 1; i > 0; i--) {
            signal[offset + i] = signal[offset + i] - PREEMPHASIS * signal[offset + i - 1];
        }
        signal[offset] -= PREEMPHASIS * memory;
    }

    private static void preemphasis(float[] signal, int offset, int length, float memory, float factor) {
        for (var i = length - 1; i > 0; i--) {
            signal[offset + i] = signal[offset + i] - factor * signal[offset + i - 1];
        }
        signal[offset] -= factor * memory;
    }

    private static void deemphasis(float[] signal, int offset, int length, float memory) {
        signal[offset] = signal[offset] + PREEMPHASIS * memory;
        for (var i = 1; i < length; i++) {
            signal[offset + i] = signal[offset + i] + PREEMPHASIS * signal[offset + i - 1];
        }
    }

    /**
     * Applies {@code 1/A(z)} in place, reading the filter memory from the samples before {@code offset}.
     */
    private static void synthesisInPlace(float[] a, float[] x, int offset, int length) {
        for (var i = 0; i < length; i++) {
            var s = x[offset + i];
            for (var j = 1; j <= ORDER; j++) {
                s -= a[j] * x[offset + i - j];
            }
            x[offset + i] = s;
        }
    }

    /**
     * Applies {@code 1/A(z/0.92)} in place ({@code ixheaacd_lpc_wt_synthesis_tool}).
     */
    private static void weightedSynthesisInPlace(float[] a, int aOffset, float[] x, int offset, int length) {
        for (var i = 0; i < length; i++) {
            var s = x[offset + i];
            for (var j = 1; j <= ORDER; j++) {
                s -= (a[aOffset + j] * LpdTables.GAMMA[j]) * x[offset + i - j];
            }
            x[offset + i] = s;
        }
    }

    /**
     * Filters {@code length} samples through {@code A(z)}; with {@code advance} each subframe of 64 samples
     * takes the next filter.
     */
    private static void residual(float[] a, int aOffset, float[] x, int xOffset, float[] y, int yOffset, int length,
                                 boolean advance) {
        var filter = aOffset;
        for (var start = 0; start < length; start += SUBFRAME) {
            var end = Math.min(length, start + SUBFRAME);
            for (var i = start; i < end; i++) {
                var s = x[xOffset + i];
                for (var j = 1; j <= ORDER; j++) {
                    s += a[filter + j] * x[xOffset + i - j];
                }
                y[yOffset + i] = s;
            }
            if (advance) {
                filter += ORDER + 1;
            }
        }
    }

    private static void residualOne(float[] a, int aOffset, float[] x, int xOffset, float[] y, int yOffset,
                                    int length) {
        for (var i = 0; i < length; i++) {
            var s = x[xOffset + i];
            for (var j = 1; j <= ORDER; j++) {
                s += a[aOffset + j] * x[xOffset + i - j];
            }
            y[yOffset + i] = s;
        }
    }

    /**
     * Decodes one ACELP division ({@code ixheaacd_acelp_alias_cnx}).
     */
    private void acelp(LpdState state, int k, float stability, int synth, int excitation) {
        var fl = facLength;
        var s = synthBuffer;
        var e = excitationBuffer;
        if (state.modePrevious > 0) {
            for (var i = 0; i < fl / 2; i++) {
                x[i] = state.facGain * fac[k * FAC_LENGTH + 2 * i];
                x[fl / 2 + i] = state.facGain * fac[k * FAC_LENGTH + fl - 2 * i - 1];
            }
            for (var i = 0; i < fl / 8; i++) {
                x[i] *= state.facWeights[2 * i];
                x[fl - i - 1] *= state.facWeights[2 * i + 1];
            }
            reorderInverse(x, fl);
            dct(fl).transform(shaped, 0, dctOut, 0);
            var xn = facSignal;
            Arrays.fill(xn, 0, fl, 0f);
            for (var i = 0; i < fl; i++) {
                xn[fl + i] = dctOut[i] * (2.0f / fl);
            }
            weightedSynthesisInPlace(state.filterPrevious, 0, xn, fl, fl);
            for (var i = 0; i < 2 * fl; i++) {
                xn[i] += s[synth + i - 2 * fl];
            }
            System.arraycopy(xn, fl, s, synth - fl, fl);
            preemphasis(xn, 0, 2 * fl, 0f);
            var remainder = fl % SUBFRAME;
            var filter = 0;
            var done = 0;
            if (remainder != 0) {
                residualOne(state.filterPrevious, 0, xn, fl, e, excitation - fl, remainder);
                filter += ORDER + 1;
                done = remainder;
            }
            while (done < fl) {
                residualOne(state.filterPrevious, Math.min(filter, ORDER + 1), xn, fl + done, e,
                        excitation - fl + done, SUBFRAME);
                filter += ORDER + 1;
                done += SUBFRAME;
            }
        }
        var memory = synthMemory;
        for (var i = 0; i < ORDER; i++) {
            memory[i] = s[synth + i - ORDER] - PREEMPHASIS * s[synth + i - ORDER - 1];
        }
        var filter = 0;
        var subframe = 0;
        var lag = 0;
        var fraction = 0;
        var lagMin = 0;
        for (var start = 0; start < division; start += SUBFRAME) {
            var absolute = start == 0 || (division == 256 && start == 2 * SUBFRAME);
            var index = acbIndex[k * 4 + subframe];
            if (absolute) {
                if (index < (pitchFr2 - pitchMin) * 4) {
                    lag = pitchMin + index / 4;
                    fraction = index - (lag - pitchMin) * 4;
                } else if (index < (pitchFr2 - pitchMin) * 4 + (pitchFr1 - pitchFr2) * 2) {
                    index -= (pitchFr2 - pitchMin) * 4;
                    lag = pitchFr2 + index / 2;
                    fraction = (index - (lag - pitchFr2) * 2) * 2;
                } else {
                    lag = index + pitchFr1 - (pitchFr2 - pitchMin) * 4 - (pitchFr1 - pitchFr2) * 2;
                    fraction = 0;
                }
                lagMin = Math.max(lag - 8, pitchMin);
                var lagMax = lagMin + 15;
                if (lagMax > pitchMax) {
                    lagMax = pitchMax;
                    lagMin = lagMax - 15;
                }
            } else {
                lag = lagMin + index / 4;
                fraction = index - (lag - lagMin) * 4;
            }
            if (lag > MAX_PITCH) {
                throw new MediaException.Malformed("USAC ACELP pitch lag " + lag + " out of range");
            }
            adaptiveCodebook(e, excitation + start, lag, fraction);
            var meanCode = meanEnergy[k] * 12.0f + 18.0f;
            if (ltpFilter[k * 4 + subframe] == 0) {
                for (var i = 0; i < SUBFRAME; i++) {
                    code[i] = (float) (0.18 * e[excitation + start + i - 1] + 0.64 * e[excitation + start + i]
                            + 0.18 * e[excitation + start + i + 1]);
                }
                System.arraycopy(code, 0, e, excitation + start, SUBFRAME);
            }
            decodePulses(icbIndex[k * 4 + subframe], CODE_BITS[coreMode], code);
            preemphasis(code, 0, SUBFRAME, 0f, TILT_CODE);
            var sharpLag = lag;
            if (fraction > 2) {
                sharpLag++;
            }
            if (sharpLag >= 0) {
                for (var i = sharpLag; i < SUBFRAME; i++) {
                    code[i] += code[i - sharpLag] * 0.85f;
                }
            }
            var g = gainIndex[k * 4 + subframe];
            var innovation = 0.01f;
            for (var i = 0; i < SUBFRAME; i++) {
                innovation += code[i] * code[i];
            }
            var innovationEnergy = innovation;
            var average = (float) (10.0 * StrictMath.log10(innovation / (float) SUBFRAME));
            var estimate = meanCode - average;
            estimate = (float) StrictMath.pow(10.0, 0.05 * estimate);
            var gainPitch = LpdTables.GAINS[g * 2];
            var gainCode = LpdTables.GAINS[g * 2 + 1] * estimate;
            var pitchEnergy = 0.0f;
            for (var i = 0; i < SUBFRAME; i++) {
                pitchEnergy += e[excitation + start + i] * e[excitation + start + i];
            }
            pitchEnergy *= gainPitch * gainPitch;
            innovationEnergy *= gainCode * gainCode;
            var voicing = (pitchEnergy - innovationEnergy) / (pitchEnergy + innovationEnergy);
            for (var i = 0; i < SUBFRAME; i++) {
                postProcess[i] = gainPitch * e[excitation + start + i];
            }
            for (var i = 0; i < SUBFRAME; i++) {
                e[excitation + start + i] = gainPitch * e[excitation + start + i] + gainCode * code[i];
            }
            var reported = lag;
            if (fraction > 2) {
                reported++;
            }
            if (reported > pitchMax) {
                reported = pitchMax;
            }
            var slot = k * subframes + half + subframe;
            pitch[slot] = reported;
            pitchGain[slot] = gainPitch;
            var voicingFactor = (float) (0.5 * (1.0 - voicing));
            var smoothing = stability * voicingFactor;
            var gainCode0 = gainCode;
            if (gainCode0 < state.gainThreshold) {
                gainCode0 = (float) (gainCode0 * 1.19);
                if (gainCode0 > state.gainThreshold) {
                    gainCode0 = state.gainThreshold;
                }
            } else {
                gainCode0 = (float) (gainCode0 / 1.19);
                if (gainCode0 < state.gainThreshold) {
                    gainCode0 = state.gainThreshold;
                }
            }
            state.gainThreshold = gainCode0;
            var gainSmooth = (float) (smoothing * gainCode0 + (1.0 - smoothing) * gainCode);
            for (var i = 0; i < SUBFRAME; i++) {
                code[i] *= gainSmooth;
            }
            var cpe = (float) (0.125 * (1.0 + voicing));
            postProcess[0] += code[0] - cpe * code[1];
            for (var i = 1; i < SUBFRAME - 1; i++) {
                postProcess[i] += code[i] - cpe * (code[i - 1] + code[i + 1]);
            }
            postProcess[SUBFRAME - 1] += code[SUBFRAME - 1] - cpe * code[SUBFRAME - 2];
            synthesis(filters, filter, postProcess, s, synth + start, memory);
            System.arraycopy(s, synth + start + SUBFRAME - ORDER, memory, 0, ORDER);
            filter += ORDER + 1;
            subframe++;
        }
        deemphasis(s, synth, division, s[synth - 1]);
        Arrays.fill(memory, 16, 16 + 128, 0f);
        synthesisInPlaceAt(filters, filter, memory, 16, 128);
        System.arraycopy(filters, filter - 2 * (ORDER + 1), state.filterPrevious, 0, 2 * (ORDER + 1));
        System.arraycopy(s, synth + division - (1 + fl), state.tail, 0, 1 + fl);
        System.arraycopy(memory, 16, state.tail, 1 + fl, fl);
        deemphasis(state.tail, 1 + fl, fl, s[synth + division - 1]);
    }

    /**
     * Applies {@code 1/A(z)} in place with the filter at {@code aOffset}.
     */
    private static void synthesisInPlaceAt(float[] a, int aOffset, float[] x, int offset, int length) {
        for (var i = 0; i < length; i++) {
            var s = x[offset + i];
            for (var j = 1; j <= ORDER; j++) {
                s -= a[aOffset + j] * x[offset + i - j];
            }
            x[offset + i] = s;
        }
    }

    /**
     * Filters one subframe through {@code 1/A(z)} with explicit memory ({@code ixheaacd_synthesis_tool_float}).
     */
    private final float[] synthesisBuffer = new float[ORDER + SUBFRAME];

    private void synthesis(float[] a, int aOffset, float[] in, float[] out, int outOffset, float[] memory) {
        var buffer = synthesisBuffer;
        System.arraycopy(memory, 0, buffer, 0, ORDER);
        for (var i = 0; i < SUBFRAME; i++) {
            var s = in[i];
            for (var j = 1; j <= ORDER; j++) {
                s -= a[aOffset + j] * buffer[ORDER + i - j];
            }
            buffer[ORDER + i] = s;
            out[outOffset + i] = s;
        }
    }

    /**
     * Interpolates the adaptive codebook at a fractional lag ({@code ixheaacd_cb_exc_calc}).
     */
    private static void adaptiveCodebook(float[] e, int base, int lag, int fraction) {
        var x0 = base - lag;
        fraction = -fraction;
        if (fraction < 0) {
            fraction += UP_SAMPLE;
            x0--;
        }
        for (var j = 0; j < SUBFRAME + 1; j++) {
            var x1 = x0++;
            var x2 = x1 + 1;
            var c1 = fraction;
            var c2 = UP_SAMPLE - fraction;
            var sum = 0.0f;
            for (var i = 0; i < INTERPOLATION_ORDER; i++) {
                sum += e[x1--] * LpdTables.INTERPOLATION[c1] + e[x2++] * LpdTables.INTERPOLATION[c2];
                c1 += UP_SAMPLE;
                c2 += UP_SAMPLE;
            }
            e[base + j] = sum;
        }
    }

    private static void onePulse(int index, int m, int offset, int track, float[] code) {
        var mask = (1 << m) - 1;
        var position = (index & mask) + offset;
        var sign = (index >> m) & 1;
        var i = (position << 2) + track;
        code[i] += sign == 1 ? -1.0f : 1.0f;
    }

    private static void twoPulses(int index, int m, int offset, int track, float[] code) {
        var mask = (1 << m) - 1;
        var p0 = ((index >> m) & mask) + offset;
        var p1 = (index & mask) + offset;
        var sign = (index >> 2 * m) & 1;
        var i0 = (p0 << 2) + track;
        var i1 = (p1 << 2) + track;
        if (p1 - p0 < 0) {
            if (sign == 1) {
                code[i0] -= 1.0f;
                code[i1] += 1.0f;
            } else {
                code[i0] += 1.0f;
                code[i1] -= 1.0f;
            }
        } else if (sign == 1) {
            code[i0] -= 1.0f;
            code[i1] -= 1.0f;
        } else {
            code[i0] += 1.0f;
            code[i1] += 1.0f;
        }
    }

    private static void threePulses(int index, int m, int offset, int track, float[] code) {
        var mask = (1 << (2 * m - 1)) - 1;
        var j = offset;
        if (((index >> (2 * m - 1)) & 1) == 1) {
            j += 1 << (m - 1);
        }
        twoPulses(index & mask, m - 1, j, track, code);
        mask = (1 << (m + 1)) - 1;
        onePulse((index >> 2 * m) & mask, m, offset, track, code);
    }

    private static void fourPulsesSection(int index, int offset, int track, float[] code) {
        var j = offset;
        if (((index >> 5) & 1) == 1) {
            j += 4;
        }
        twoPulses(index & 31, 2, j, track, code);
        twoPulses((index >> 6) & 127, 3, offset, track, code);
    }

    private static void fourPulses(int index, int track, float[] code) {
        switch ((index >> 14) & 3) {
            case 0 -> fourPulsesSection(index, ((index >> 13) & 1) == 0 ? 0 : 8, track, code);
            case 1 -> {
                onePulse(index >> 10, 3, 0, track, code);
                threePulses(index, 3, 8, track, code);
            }
            case 2 -> {
                twoPulses(index >> 7, 3, 0, track, code);
                twoPulses(index, 3, 8, track, code);
            }
            default -> {
                threePulses(index >> 4, 3, 0, track, code);
                onePulse(index, 3, 8, track, code);
            }
        }
    }

    private static void addPulse(int position, int track, float[] code) {
        var i = ((position & 15) << 2) + track;
        code[i] += (position & 16) == 0 ? 1.0f : -1.0f;
    }

    private static int onePosition(int index, int n) {
        var mask = (1 << n) - 1;
        var position = index & mask;
        if (((index >> n) & 1) == 1) {
            position += 16;
        }
        return position;
    }

    /**
     * Decodes the innovative codebook ({@code ixheaacd_acelp_decode_pulses_per_track}).
     */
    private static void decodePulses(int[] index, int bits, float[] code) {
        Arrays.fill(code, 0f);
        switch (bits) {
            case 12 -> {
                for (var track = 0; track < 4; track += 2) {
                    var offset = index[2 * (track / 2)];
                    var position = onePosition(index[2 * (track / 2) + 1], 4);
                    addPulse(position, 2 * offset + track / 2, code);
                }
            }
            case 16 -> {
                var i = 0;
                var skip = index[i++] == 0 ? 1 : 3;
                for (var track = 0; track < 4; track++) {
                    if (track != skip) {
                        addPulse(onePosition(index[i++], 4), track, code);
                    }
                }
            }
            case 20 -> {
                for (var track = 0; track < 4; track++) {
                    onePulse(index[track], 4, 0, track, code);
                }
            }
            case 28 -> {
                for (var track = 0; track < 2; track++) {
                    twoPulses(index[track], 4, 0, track, code);
                }
                for (var track = 2; track < 4; track++) {
                    onePulse(index[track], 4, 0, track, code);
                }
            }
            case 36 -> {
                for (var track = 0; track < 4; track++) {
                    twoPulses(index[track], 4, 0, track, code);
                }
            }
            case 44 -> {
                for (var track = 0; track < 2; track++) {
                    threePulses(index[track], 4, 0, track, code);
                }
                for (var track = 2; track < 4; track++) {
                    twoPulses(index[track], 4, 0, track, code);
                }
            }
            case 52 -> {
                for (var track = 0; track < 4; track++) {
                    threePulses(index[track], 4, 0, track, code);
                }
            }
            default -> {
                for (var track = 0; track < 4; track++) {
                    fourPulses((index[track] << 14) + index[track + 4], track, code);
                }
            }
        }
    }

    /**
     * Restores natural order from the even-then-reversed-odd arrangement the decoder uses for its DCT
     * inputs, into {@link #shaped}.
     */
    private void reorderInverse(float[] in, int length) {
        for (var i = 0; i < length / 2; i++) {
            shaped[2 * i] = in[i];
            shaped[length - 2 * i - 1] = in[length / 2 + i];
        }
    }

    /**
     * Decodes one TCX division ({@code ixheaacd_tcx_mdct}).
     */
    private void tcx(UsacChannel channel, LpdState state, int k, int length, int synth, int excitation) {
        var fl = facLength;
        var previousFl = state.modePrevious == -2 ? frameLength / 16 : fl;
        var window = UsacTables.window(2 * fl, false);
        var previousWindow = UsacTables.window(2 * previousFl, false);
        var tail = state.tail;
        if (state.modePrevious != 0) {
            if (state.modePrevious > 0) {
                for (var i = 0; i < 2 * previousFl; i++) {
                    tail[i + fl - previousFl + 1] *= previousWindow[2 * previousFl - 1 - i];
                }
            }
            for (var i = 0; i < fl - previousFl; i++) {
                tail[i + fl + previousFl + 1] = 0.0f;
            }
        }
        var noise = 0.0625f * (8.0f - noiseFactor[k]);
        var quantOffset = 0;
        for (var i = 0; i < k; i++) {
            quantOffset += tcxLength[i];
        }
        for (var i = 0; i < length; i++) {
            x[i] = quant[quantOffset + i];
        }
        for (var i = length / 6; i < length; i += 8) {
            var end = Math.min(length, i + 8);
            var energy = 0.0f;
            for (var j = i; j < end; j++) {
                energy += (float) quant[quantOffset + j] * quant[quantOffset + j];
            }
            if (energy == 0.0f) {
                for (var j = i; j < end; j++) {
                    var seed = channel.seed;
                    seed[0] = seed[0] * 69069 + 5;
                    x[j] = noise * ((seed[0] & 0x10000) != 0 ? -1.0f : 1.0f);
                }
            }
        }
        lowFrequencyDeemphasis(x, length, lowGains);
        weight(filters, ORDER + 1, weighted);
        lpcGains(weighted, gains1, division / 4);
        weight(filters, 2 * (ORDER + 1), weighted);
        lpcGains(weighted, gains2, division / 4);
        var energy = 0.01f;
        for (var i = 0; i < length; i++) {
            energy += x[i] * x[i];
        }
        var rms = (float) StrictMath.sqrt(energy) / length;
        var gain = (float) StrictMath.pow(10.0f, globalGain[k] / 28.0f) / (rms * 2.0f);
        noiseShaping(x, length, division / 4, gains1, gains2);
        dct(length).transform(shaped, 0, dctOut, 0);
        unfold(dctOut, xnBuffer, 2 * fl, length - 2 * fl);
        var scale = 2.0f / length;
        for (var i = 0; i < length + 2 * fl; i++) {
            xnBuffer[i] *= scale;
        }
        state.facGain = gain * 0.5f * (float) StrictMath.sqrt((float) fl / (float) length);
        for (var i = 0; i < fl / 4; i++) {
            state.facWeights[i] = lowGains[i * length / (8 * fl)];
        }
        var xn1 = facSignal;
        if (state.modePrevious == 0) {
            for (var i = 0; i < previousFl; i++) {
                facWindow[i] = previousWindow[i] * previousWindow[2 * previousFl - 1 - i];
                facWindow[previousFl + i] = 1.0f - previousWindow[previousFl + i] * previousWindow[previousFl + i];
            }
            for (var i = 0; i < fl / 2; i++) {
                x[i] = state.facGain * fac[k * FAC_LENGTH + 2 * i];
                x[fl / 2 + i] = state.facGain * fac[k * FAC_LENGTH + fl - 2 * i - 1];
            }
            for (var i = 0; i < fl / 8; i++) {
                x[i] *= state.facWeights[2 * i];
                x[fl - i - 1] *= state.facWeights[2 * i + 1];
            }
            reorderInverse(x, fl);
            dct(fl).transform(shaped, 0, dctOut, 0);
            Arrays.fill(xn1, 0, ORDER, 0f);
            for (var i = 0; i < fl; i++) {
                xn1[ORDER + i] = dctOut[i] * (2.0f / fl);
            }
            Arrays.fill(xn1, ORDER + fl, ORDER + 2 * fl, 0f);
            weight(filters, ORDER + 1, weighted);
            synthesisInPlace(weighted, xn1, ORDER, 2 * fl);
            for (var i = 0; i < fl; i++) {
                xn1[ORDER + i] += tail[1 + fl + i] * facWindow[fl + i] + tail[fl - i] * facWindow[fl - 1 - i];
            }
        }
        for (var i = 0; i < length + 2 * fl; i++) {
            xnBuffer[i] *= gain;
        }
        for (var i = 0; i < 2 * previousFl; i++) {
            xnBuffer[i + fl - previousFl] *= previousWindow[i];
        }
        for (var i = 0; i < fl - previousFl; i++) {
            xnBuffer[i] = 0.0f;
        }
        if (state.modePrevious != 0) {
            for (var i = fl - previousFl; i < fl + previousFl; i++) {
                xnBuffer[i] += tail[1 + i];
            }
        } else {
            for (var i = fl - previousFl; i < fl + previousFl; i++) {
                xnBuffer[i + fl] += xn1[ORDER + i];
            }
        }
        System.arraycopy(xnBuffer, length - 1, tail, 0, 1 + 2 * fl);
        for (var i = 0; i < 2 * fl; i++) {
            xnBuffer[i + length] *= window[2 * fl - 1 - i];
        }
        var s = synthBuffer;
        var e = excitationBuffer;
        var xn = fl;
        if (state.modePrevious != 0) {
            System.arraycopy(xnBuffer, fl - previousFl, s, synth - previousFl, previousFl);
            var buffer = residualBuffer;
            for (var i = 0; i < ORDER + fl; i++) {
                buffer[i] = s[synth + i - ORDER - fl] - PREEMPHASIS * s[synth + i - ORDER - fl - 1];
            }
            var remainder = fl % SUBFRAME;
            if (remainder != 0) {
                residualOne(state.filterPrevious, 0, buffer, ORDER, e, excitation - fl, remainder);
            }
            for (var j = 0; j < (fl - remainder) / SUBFRAME; j++) {
                residualOne(state.filterPrevious, j * (ORDER + 1), buffer, ORDER + remainder + j * SUBFRAME, e,
                        excitation - fl + remainder + j * SUBFRAME, SUBFRAME);
            }
        }
        System.arraycopy(xnBuffer, xn, s, synth, length);
        System.arraycopy(s, synth - ORDER - 1, xnBuffer, xn - ORDER - 1, ORDER + 1);
        var memory = xnBuffer[xn - ORDER - 1];
        preemphasis(xnBuffer, xn - ORDER, ORDER + length, memory);
        residualOne(filters, 2 * (ORDER + 1), xnBuffer, xn, e, excitation, length);
        System.arraycopy(filters, 2 * (ORDER + 1), state.filterPrevious, 0, ORDER + 1);
        System.arraycopy(filters, 2 * (ORDER + 1), state.filterPrevious, ORDER + 1, ORDER + 1);
    }

    /**
     * Unfolds a DCT-IV output into {@code 2 l + m} samples ({@code ixheaacd_acelp_mdct_main}).
     */
    private static void unfold(float[] in, float[] out, int l, int m) {
        for (var i = 0; i < m / 2; i++) {
            out[l + m / 2 - 1 - i] = -in[m / 2 + l / 2 + i];
        }
        for (var i = 0; i < l / 2; i++) {
            out[i] = in[m + l / 2 + i];
            out[l - 1 - i] = -in[m + l / 2 + i];
        }
        for (var i = 0; i < m / 2; i++) {
            out[l + m / 2 + i] = -in[m / 2 + l / 2 - 1 - i];
        }
        for (var i = 0; i < l / 2; i++) {
            out[l + m + i] = -in[l / 2 - 1 - i];
            out[2 * l + m - 1 - i] = -in[l / 2 - 1 - i];
        }
    }

    private static void lowFrequencyDeemphasis(float[] x, int length, float[] gains) {
        var limit = length / 4;
        var max = 0.01f;
        for (var i = 0; i < limit; i += 8) {
            var energy = 0.01f;
            for (var j = i; j < i + 8; j++) {
                energy += x[j] * x[j];
            }
            if (energy > max) {
                max = energy;
            }
        }
        var factor = 0.1f;
        for (var i = 0; i < limit; i += 8) {
            var energy = 0.01f;
            for (var j = i; j < i + 8; j++) {
                energy += x[j] * x[j];
            }
            energy = (float) StrictMath.sqrt(energy / max);
            if (energy > factor) {
                factor = energy;
            }
            for (var j = i; j < i + 8; j++) {
                x[j] *= factor;
            }
            gains[i / 8] = factor;
        }
    }

    /**
     * Computes the inverse magnitude of an LPC filter at {@code bands} frequencies ({@code ixheaacd_lpc_to_td}).
     */
    private static void lpcGains(float[] a, float[] gains, int bands) {
        var size = 2 * bands;
        for (var k = 0; k < bands; k++) {
            var re = 0.0;
            var im = 0.0;
            for (var i = 0; i <= ORDER; i++) {
                var phase = (float) (i * (Math.PI / size));
                var cr = (float) (a[i] * StrictMath.cos(phase));
                var ci = (float) (-a[i] * StrictMath.sin(phase));
                var angle = -2.0 * Math.PI * i * k / size;
                var c = StrictMath.cos(angle);
                var sn = StrictMath.sin(angle);
                re += cr * c - ci * sn;
                im += cr * sn + ci * c;
            }
            gains[k] = (float) (1.0f / (StrictMath.sqrt((float) re * (float) re + (float) im * (float) im)
                    + Math.ulp(1.0f)));
        }
    }

    /**
     * Shapes the spectrum by the LPC gains ({@code ixheaacd_noise_shaping}) into {@link #shaped}, in natural
     * order.
     */
    private void noiseShaping(float[] r, int length, int bands, float[] g1, float[] g2) {
        var k = length / bands;
        var previous = 0.0f;
        var a = 0.0f;
        var b = 0.0f;
        for (var i = 0; i < length; i++) {
            if (i % k == 0) {
                a = 2.0f * g1[i / k] * g2[i / k] / (g1[i / k] + g2[i / k]);
                b = (g2[i / k] - g1[i / k]) / (g1[i / k] + g2[i / k]);
            }
            shaped[i] = a * r[i] + b * previous;
            previous = shaped[i];
        }
    }

    /**
     * Runs the bass postfilter ({@code ixheaacd_bass_post_filter}); the output lags the synthesis by one
     * subframe.
     */
    private void bassPostFilter(float[] synth, int base, float[] out, int length, int lookahead, float[] memory) {
        System.arraycopy(synth, base - SUBFRAME, out, 0, length);
        if (length % 64 != 0) {
            Arrays.fill(out, length, length + SUBFRAME - length % 64, 0f);
        }
        var noise = bassNoise;
        var subframe = 0;
        for (var start = 0; start < length; start += SUBFRAME, subframe++) {
            var lag = pitch[subframe];
            var gain = Math.clamp(pitchGain[subframe], 0.0f, 1.0f);
            if ((lag >> 1) + 96 - start > MAX_PITCH) {
                throw new MediaException.Malformed("USAC bass postfilter lag out of range");
            }
            var xEnergy = 0.01f;
            var correlation = 0.01f;
            var yEnergy = 0.01f;
            for (var i = 0; i < SUBFRAME + 96; i++) {
                var xv = synth[base + start - 96 + i];
                var yv = synth[base + start - lag / 2 - 96 + i];
                xEnergy += xv * xv;
                correlation += xv * yv;
                yEnergy += yv * yv;
            }
            var normalized = correlation / (float) StrictMath.sqrt(xEnergy * yEnergy);
            if (normalized > 0.95f) {
                lag >>= 1;
            }
            var lg = Math.clamp(length + lookahead - lag - start, 0, SUBFRAME);
            if (lag > MAX_PITCH) {
                throw new MediaException.Malformed("USAC bass postfilter lag out of range");
            }
            var current = FILTER_DELAY + SUBFRAME;
            if (gain > 0) {
                if (lg > 0) {
                    var tmp = 0.01f;
                    for (var i = 0; i < lg; i++) {
                        tmp += synth[base + i + start] * synth[base + i + start];
                    }
                    var energy = 0.01f;
                    for (var i = 0; i < lg; i++) {
                        energy += synth[base + i + start + lag] * synth[base + i + start + lag];
                    }
                    tmp = (float) StrictMath.sqrt(tmp / energy);
                    if (tmp < gain) {
                        gain = tmp;
                    }
                }
                var alpha = 0.5f * gain;
                for (var i = 0; i < lg; i++) {
                    noise[current + i] = alpha * (synth[base + i + start] - 0.5f * synth[base + i + start - lag]
                            - 0.5f * synth[base + i + start + lag]);
                }
                for (var i = lg; i < SUBFRAME; i++) {
                    noise[current + i] = alpha * (synth[base + i + start] - synth[base + i + start - lag]);
                }
            } else {
                Arrays.fill(noise, current, current + SUBFRAME, 0f);
            }
            System.arraycopy(memory, 0, noise, 0, FILTER_DELAY + SUBFRAME);
            System.arraycopy(noise, SUBFRAME, memory, 0, FILTER_DELAY + SUBFRAME);
            for (var i = 0; i < SUBFRAME; i++) {
                var n = FILTER_DELAY + i;
                var tmp = LpdTables.BASS_LOW_PASS[0] * noise[n];
                for (var j = 1; j <= FILTER_DELAY; j++) {
                    tmp += LpdTables.BASS_LOW_PASS[j] * (noise[n - j] + noise[n + j]);
                }
                out[i + start] -= tmp;
            }
        }
    }

    /**
     * Combines the LPD synthesis with the overlap left by the previous frame ({@code ixheaacd_td_frm_dec}).
     */
    private void combine(UsacChannel channel) {
        var n = frameLength;
        var overlap = channel.overlap;
        var output = channel.output;
        if (!channel.lastLpd) {
            var lfac = channel.lastSequence == FdChannel.EIGHT_SHORT ? n / 16 : n / 8;
            if (mod[0] == 0) {
                Arrays.fill(fsynth, 0, n / 2 - lfac - SUBFRAME, 0f);
                Arrays.fill(overlap, n / 2 - lfac - SUBFRAME, n / 2, 0f);
            } else {
                Arrays.fill(fsynth, 0, n / 2 - lfac, 0f);
                Arrays.fill(overlap, n / 2 - lfac, n, 0f);
            }
        }
        for (var i = 0; i < n; i++) {
            output[i] = overlap[i] + fsynth[i];
            overlap[i] = 0f;
        }
    }

    /**
     * Builds the overlap of a frequency domain frame that follows an LPD frame from the LPD state
     * ({@code ixheaacd_lpd_dec_update}).
     *
     * @param channel the state of the channel
     */
    void finishBeforeFd(UsacChannel channel) {
        var state = channel.lpdState;
        var overlap = channel.overlap;
        for (var i = 0; i < SUBFRAME + synthDelay; i++) {
            overlap[i] = state.synthPrevious[MAX_PITCH - SUBFRAME + i];
        }
        var offset = SUBFRAME + synthDelay - facLength;
        for (var k = 0; k < 2 * facLength; k++) {
            overlap[offset + k] = state.tail[k + 1];
        }
        Arrays.fill(overlap, lpdDelay + facLength, frameLength, 0f);
        if (state.modePrevious == 0) {
            System.arraycopy(state.filterPrevious, ORDER + 1, state.lastFilter, 0, ORDER + 1);
            System.arraycopy(state.tail, 0, state.acelpTail, 0, 1 + 2 * FAC_LENGTH);
        }
        channel.previousShape = 0;
        channel.lastSequence = FdChannel.EIGHT_SHORT;
    }

    /**
     * Computes the time signal of the FAC data of a frequency domain frame that follows an LPD frame
     * ({@code decode_fdfac}): the inverse DCT of the scaled coefficients through the weighted synthesis filter
     * of the last LPC filter, plus the windowed ACELP zero input response.
     *
     * @param channel the state of the channel
     * @param data the gain index then the coefficients
     * @param lfac the number of coefficients
     * @param out receives {@code 2 * lfac} samples
     */
    void decodeFdFac(UsacChannel channel, int[] data, int lfac, float[] out) {
        var state = channel.lpdState;
        var gain = (float) StrictMath.pow(10.0f, data[0] / 28.0f);
        for (var i = 0; i < lfac; i++) {
            x[i] = data[1 + i] * gain;
        }
        dct(lfac).transform(x, 0, dctOut, 0);
        weight(state.lastFilter, 0, weighted);
        Arrays.fill(facSignal, 0, ORDER, 0f);
        System.arraycopy(dctOut, 0, facSignal, ORDER, lfac);
        Arrays.fill(facSignal, ORDER + lfac, ORDER + 2 * lfac, 0f);
        synthesisInPlace(weighted, facSignal, ORDER, 2 * lfac);
        System.arraycopy(facSignal, ORDER, out, 0, 2 * lfac);
        var window = UsacTables.window(2 * lfac, false);
        for (var i = 0; i < lfac; i++) {
            facWindow[i] = window[i] * window[2 * lfac - 1 - i];
            facWindow[lfac + i] = 1.0f - window[lfac + i] * window[lfac + i];
        }
        var zir = state.acelpTail;
        var center = 1 + frameLength / 8;
        for (var i = 0; i < lfac; i++) {
            out[i] += zir[center + i] * facWindow[lfac + i] + zir[center - 1 - i] * facWindow[lfac - 1 - i];
        }
    }

    /**
     * Runs the bass postfilter over the start of a frequency domain frame that follows an LPD frame
     * ({@code ixheaacd_lpd_bpf_fix}).
     *
     * @param channel the state of the channel
     * @param shortWindows whether the frequency domain frame has eight short windows
     */
    void postFilterTransition(UsacChannel channel, boolean shortWindows) {
        var state = channel.lpdState;
        var length = frameLength;
        var lpdSubframes = superframeSubframes / 2;
        var buffer = synthBuffer;
        Arrays.fill(buffer, 0, MAX_PITCH + synthDelay + length, 0f);
        System.arraycopy(state.synthPrevious, 0, buffer, 0, MAX_PITCH + synthDelay);
        System.arraycopy(channel.output, 0, buffer, MAX_PITCH - SUBFRAME,
                Math.min(length, synthDelay + length + SUBFRAME));
        for (var i = 0; i < half; i++) {
            pitch[i] = state.pitchPrevious[i];
            pitchGain[i] = state.gainPrevious[i];
        }
        for (var i = half; i < lpdSubframes + 3; i++) {
            pitch[i] = 64;
            pitchGain[i] = 0.0f;
        }
        if (state.modePrevious == 0) {
            pitch[half] = pitch[half - 1];
            pitchGain[half] = pitchGain[half - 1];
            if (!shortWindows) {
                pitch[half + 1] = pitch[half];
                pitchGain[half + 1] = pitchGain[half];
            }
        }
        var base = MAX_PITCH;
        for (var i = 0; i < half + 2; i++) {
            if (pitchGain[i] > 0.0f) {
                var tp = pitch[i];
                var correlation = 0.0f;
                var energy = 1e-6f;
                for (var k = 0; k < SUBFRAME; k++) {
                    var past = buffer[base + i * SUBFRAME - tp + k];
                    correlation += buffer[base + i * SUBFRAME + k] * past;
                    energy += past * past;
                }
                pitchGain[i] = correlation / energy;
            }
        }
        var filtered = (lpdSubframes + 2) * SUBFRAME + SUBFRAME;
        bassPostFilter(buffer, base, bassOut, filtered, length - (lpdSubframes + 4) * SUBFRAME, state.bassPrevious);
        System.arraycopy(bassOut, 0, channel.output, 0, filtered);
    }
}
