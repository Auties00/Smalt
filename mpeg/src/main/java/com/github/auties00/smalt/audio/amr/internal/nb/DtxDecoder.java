/* Ports opencore-amr 0.1.6 (dtx_dec.cpp, a_refl.cpp, b_cn_cod.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lSub;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_ELAPSED_FRAMES_THRESH;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_HANG_CONST;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_HIST_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DTX_MAX_EMPTY_THRESH;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.LSF_GAP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;

/**
 * Generates the comfort noise of AMR-NB discontinuous transmission and tracks the receiver's
 * DTX state ({@code dtx_decState}).
 *
 * <p>The receiver moves between three states: speech, comfort noise ({@link #DTX}) and muted
 * comfort noise ({@link #DTX_MUTE}, after too many frames without a SID update). Comfort noise is
 * a random excitation shaped by LSFs and an energy interpolated between the last two SID
 * frames, with LSF and energy averages over the eight frames before a DTX hangover.
 *
 * @implNote This implementation keeps the partially updated work array of the reflection coefficient conversion across
 * calls where the reference reads uninitialized stack memory after an unstable filter; the case arises only for comfort
 * noise parameters an encoder never produces. It mutes the comfort noise as the TS 26.073 reference does: each muted
 * frame subtracts 256 from the log energy with saturation and without opencore's clamp at zero, so a stream left
 * without SID updates keeps fading like the reference decoder's output.
 */
final class DtxDecoder {
    /**
     * The receiver state: decoding speech.
     */
    static final int SPEECH = 0;

    /**
     * The receiver state: generating comfort noise.
     */
    static final int DTX = 1;

    /**
     * The receiver state: generating muted comfort noise.
     */
    static final int DTX_MUTE = 2;

    /**
     * The initial state of the comfort noise generator's shift register.
     */
    private static final int PN_INITIAL_SEED = 0x70816958;

    /**
     * The number of pulses of a comfort noise excitation subframe.
     */
    private static final int NB_PULSE = 10;

    /**
     * The initial LSFs of the history.
     */
    private static final short[] LSF_HIST_INIT = {1384, 2077, 3420, 5108, 6742, 8122, 9863, 11092, 12714, 13701};

    /**
     * The scaling of the LSF deviations from their mean, per LSF ({@code lsf_hist_mean_scale}).
     */
    private static final short[] LSF_HIST_MEAN_SCALE = {20000, 20000, 20000, 20000, 20000, 18000, 16384, 8192, 0, 0};

    /**
     * The energy adjustment of each mode ({@code dtx_log_en_adjust}).
     */
    private static final short[] DTX_LOG_EN_ADJUST = {-1023, -878, -732, -586, -440, -294, -148, 0, 0};

    /**
     * The frames since the last SID frame.
     */
    short sinceLastSid;

    /**
     * The inverse of the SID period, Q13.
     */
    short trueSidPeriodInv;

    /**
     * The energy of the current SID frame.
     */
    short logEn;

    /**
     * The energy of the previous SID frame.
     */
    short oldLogEn;

    /**
     * The comfort noise generator's shift register.
     */
    int lPnSeedRx;

    /**
     * The LSPs of the current SID frame.
     */
    final short[] lsp = new short[M];

    /**
     * The LSPs of the previous SID frame.
     */
    final short[] lspOld = new short[M];

    /**
     * The LSFs of the last 8 frames.
     */
    final short[] lsfHist = new short[M * DTX_HIST_SIZE];

    /**
     * The position of the newest frame in {@link #lsfHist}.
     */
    short lsfHistPtr;

    /**
     * The deviations of the history LSFs from their mean, scaled and limited.
     */
    final short[] lsfHistMean = new short[M * DTX_HIST_SIZE];

    /**
     * The smoothed log prediction gain.
     */
    short logPgMean;

    /**
     * The energies of the last 8 frames.
     */
    final short[] logEnHist = new short[DTX_HIST_SIZE];

    /**
     * The position of the newest frame in {@link #logEnHist}.
     */
    short logEnHistPtr;

    /**
     * The smoothed energy adjustment.
     */
    short logEnAdjust;

    /**
     * The frames left in the encoder's DTX hangover.
     */
    short dtxHangoverCount;

    /**
     * The frames since the last update of the averages.
     */
    short decAnaElapsedCount;

    /**
     * Whether the current frame is a SID frame.
     */
    short sidFrame;

    /**
     * Whether the current SID frame carries valid parameters.
     */
    short validData;

    /**
     * Whether a DTX hangover precedes the current SID frame.
     */
    short dtxHangoverAdded;

    /**
     * The receiver state of the previous frame.
     */
    int dtxGlobalState;

    /**
     * Whether comfort noise parameters were ever received.
     */
    short dataUpdated;

    /**
     * The interpolated LSPs, scratch.
     */
    private final short[] lspInt = new short[M];

    /**
     * The interpolated LSFs, scratch.
     */
    private final short[] lsfInt = new short[M];

    /**
     * The randomized interpolated LSFs, scratch.
     */
    private final short[] lsfIntVariab = new short[M];

    /**
     * The randomized interpolated LSPs, scratch.
     */
    private final short[] lspIntVariab = new short[M];

    /**
     * The comfort noise LP filter, scratch.
     */
    private final short[] acoeff = new short[MP1];

    /**
     * The randomized comfort noise LP filter, scratch.
     */
    private final short[] acoeffVariab = new short[MP1];

    /**
     * The reflection coefficients, scratch.
     */
    private final short[] refl = new short[M];

    /**
     * The averaged LSFs, scratch.
     */
    private final short[] lsf = new short[M];

    /**
     * The LSF sums, scratch.
     */
    private final int[] lLsf = new int[M];

    /**
     * The comfort noise excitation of a subframe, scratch.
     */
    private final short[] ex = new short[L_SUBFR];

    /**
     * The filter state of the reflection coefficient conversion.
     */
    private final short[] aState = new short[M];

    /**
     * The next filter state of the reflection coefficient conversion.
     */
    private final short[] bState = new short[M];

    /**
     * The LP analysis tools.
     */
    private final Lpc lpc;

    /**
     * Creates the state, reset.
     *
     * @param lpc the LP analysis tools of the decoder
     */
    DtxDecoder(Lpc lpc) {
        this.lpc = lpc;
        reset();
    }

    /**
     * Resets the state ({@code dtx_dec_reset}).
     */
    void reset() {
        sinceLastSid = 0;
        trueSidPeriodInv = 1 << 13;
        logEn = 3500;
        oldLogEn = 3500;
        lPnSeedRx = PN_INITIAL_SEED;
        System.arraycopy(CommonTables.LSP_INIT_DATA, 0, lsp, 0, M);
        System.arraycopy(CommonTables.LSP_INIT_DATA, 0, lspOld, 0, M);
        lsfHistPtr = 0;
        logPgMean = 0;
        logEnHistPtr = 0;
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            System.arraycopy(LSF_HIST_INIT, 0, lsfHist, i * M, M);
        }
        Arrays.fill(lsfHistMean, (short) 0);
        for (var i = 0; i < DTX_HIST_SIZE; i++) {
            logEnHist[i] = logEn;
        }
        logEnAdjust = 0;
        dtxHangoverCount = DTX_HANG_CONST;
        decAnaElapsedCount = 32767;
        sidFrame = 0;
        validData = 0;
        dtxHangoverAdded = 0;
        dtxGlobalState = DTX;
        dataUpdated = 0;
    }

    /**
     * Synthesizes one frame of comfort noise ({@code dtx_dec}).
     *
     * @param memSyn the synthesis filter memory of the decoder
     * @param lsfState the LSF decoder state
     * @param predState the gain predictor
     * @param averState the codebook gain averaging state
     * @param newState the new receiver state
     * @param mode the mode, 0 to 7
     * @param parm the SID parameters
     * @param parmOffset the index of the first parameter
     * @param synth receives the 160 samples
     * @param aT receives the 4 LP filters of the frame
     * @param overflow the flag raised on saturation
     */
    void decode(short[] memSyn, LsfDecoder lsfState, GcPred predState, CodebookGainAverage averState, int newState,
                int mode, short[] parm, int parmOffset, short[] synth, short[] aT, Flag overflow) {
        short temp;
        if (dtxHangoverAdded != 0 && sidFrame != 0) {
            logEnAdjust = DTX_LOG_EN_ADJUST[mode];
            var ptr = (short) (lsfHistPtr + M);
            if (ptr == 80) {
                ptr = 0;
            }
            System.arraycopy(lsfHist, lsfHistPtr, lsfHist, ptr, M);
            ptr = (short) (logEnHistPtr + 1);
            if (ptr == DTX_HIST_SIZE) {
                ptr = 0;
            }
            logEnHist[ptr] = logEnHist[logEnHistPtr];
            logEn = 0;
            for (var i = M - 1; i >= 0; i--) {
                lLsf[i] = 0;
            }
            for (var i = DTX_HIST_SIZE - 1; i >= 0; i--) {
                temp = (short) (logEnHist[i] >> 3);
                logEn = add(logEn, temp, overflow);
                for (var j = M - 1; j >= 0; j--) {
                    lLsf[j] = lAdd(lLsf[j], lsfHist[i * M + j], overflow);
                }
            }
            for (var j = M - 1; j >= 0; j--) {
                lsf[j] = (short) (lLsf[j] >> 3);
            }
            Lsf.lsfLsp(lsf, 0, lsp, 0, M);
            logEn = sub(logEn, logEnAdjust, overflow);
            System.arraycopy(lsfHist, 0, lsfHistMean, 0, 80);
            for (var i = M - 1; i >= 0; i--) {
                var sum = 0;
                for (var j = 8 - 1; j >= 0; j--) {
                    sum = lAdd(sum, lsfHistMean[i + j * M], overflow);
                }
                var lsfMean = (short) (sum >> 3);
                for (var j = 8 - 1; j >= 0; j--) {
                    var value = sub(lsfHistMean[i + j * M], lsfMean, overflow);
                    value = mult(value, LSF_HIST_MEAN_SCALE[i], overflow);
                    var negative = value < 0;
                    value = abs(value);
                    if (value > 655) {
                        value = (short) (655 + ((value - 655) >> 2));
                    }
                    if (value > 1310) {
                        value = 1310;
                    }
                    if (negative) {
                        value = (short) -value;
                    }
                    lsfHistMean[i + j * M] = value;
                }
            }
        }
        if (sidFrame != 0) {
            System.arraycopy(lsp, 0, lspOld, 0, M);
            oldLogEn = logEn;
            if (validData != 0) {
                var tmpIntLength = sinceLastSid;
                sinceLastSid = 0;
                if (tmpIntLength >= 32) {
                    tmpIntLength = 32;
                }
                temp = saturatedShift10(tmpIntLength, overflow);
                if (tmpIntLength >= 2) {
                    trueSidPeriodInv = divS((short) (1 << 10), temp);
                } else {
                    trueSidPeriodInv = 1 << 14;
                }
                lsfState.init3(parm[parmOffset]);
                lsfState.decode3(Mode.MRDTX, 0, parm, parmOffset + 1, lsp, 0, overflow);
                Arrays.fill(lsfState.pastRQ, (short) 0);
                var logEnIndex = parm[parmOffset + 4];
                if (logEnIndex > 63 || logEnIndex < -64) {
                    logEn = logEnIndex > 0 ? MAX_16 : MIN_16;
                } else {
                    logEn = (short) (logEnIndex << (11 - 2));
                }
                logEn = (short) (logEn - (2560 * 2));
                if (logEnIndex == 0) {
                    logEn = MIN_16;
                }
                if (dataUpdated == 0 || dtxGlobalState == SPEECH) {
                    System.arraycopy(lsp, 0, lspOld, 0, M);
                    oldLogEn = logEn;
                }
            }
            temp = (short) (logEn >> 1);
            var maPredInit = (short) (temp - 9000);
            if (maPredInit > 0) {
                maPredInit = 0;
            } else if (maPredInit < -14436) {
                maPredInit = -14436;
            }
            predState.pastQuaEn[0] = maPredInit;
            predState.pastQuaEn[1] = maPredInit;
            predState.pastQuaEn[2] = maPredInit;
            predState.pastQuaEn[3] = maPredInit;
            maPredInit = (short) ((maPredInit * 5443) >> 15);
            predState.pastQuaEnMR122[0] = maPredInit;
            predState.pastQuaEnMR122[1] = maPredInit;
            predState.pastQuaEnMR122[2] = maPredInit;
            predState.pastQuaEnMR122[3] = maPredInit;
        }
        var adjust = DTX_LOG_EN_ADJUST[mode];
        if (adjust > 1023) {
            temp = MAX_16;
        } else if (adjust < -1024) {
            temp = MIN_16;
        } else {
            temp = (short) (((adjust << 5) * 3277) >> 15);
        }
        temp = (short) (temp >> 5);
        logEnAdjust = add((short) ((logEnAdjust * 29491) >> 15), temp, overflow);
        var intFac = shl((short) (sinceLastSid + 1), (short) 10);
        intFac = mult(intFac, trueSidPeriodInv, overflow);
        if (intFac > 1024) {
            intFac = 16384;
        } else if (intFac < -2048) {
            intFac = MIN_16;
        } else {
            intFac = (short) (intFac << 4);
        }
        var logEnInt = lMult(intFac, logEn, overflow);
        for (var i = M - 1; i >= 0; i--) {
            lspInt[i] = mult(intFac, lsp[i], overflow);
        }
        intFac = (short) (16384 - intFac);
        logEnInt = lMac(logEnInt, intFac, oldLogEn, overflow);
        for (var i = M - 1; i >= 0; i--) {
            lspInt[i] = add(lspInt[i], mult(intFac, lspOld[i], overflow), overflow);
            var doubled = lspInt[i] << 1;
            if (doubled != (short) doubled) {
                overflow.value = true;
                doubled = lspInt[i] > 0 ? MAX_16 : MIN_16;
            }
            lspInt[i] = (short) doubled;
        }
        var lsfVariabFactor = (short) (logPgMean - 2457);
        lsfVariabFactor = (short) (4096 - mult(lsfVariabFactor, (short) 9830, overflow));
        if (lsfVariabFactor > 4095) {
            lsfVariabFactor = MAX_16;
        } else if (lsfVariabFactor < 0) {
            lsfVariabFactor = 0;
        } else {
            lsfVariabFactor = (short) (lsfVariabFactor << 3);
        }
        var lsfVariabIndex = pseudonoise(3);
        Lsf.lspLsf(lspInt, 0, lsfInt, 0, M);
        System.arraycopy(lsfInt, 0, lsfIntVariab, 0, M);
        for (var i = M - 1; i >= 0; i--) {
            lsfIntVariab[i] = add(lsfIntVariab[i],
                    mult(lsfVariabFactor, lsfHistMean[i + lsfVariabIndex * M], overflow), overflow);
        }
        Lsf.reorderLsf(lsfInt, 0, LSF_GAP, M);
        Lsf.reorderLsf(lsfIntVariab, 0, LSF_GAP, M);
        System.arraycopy(lsfInt, 0, lsfState.pastLsfQ, 0, M);
        Lsf.lsfLsp(lsfInt, 0, lspInt, 0, M);
        Lsf.lsfLsp(lsfIntVariab, 0, lspIntVariab, 0, M);
        lpc.lspAz(lspInt, 0, acoeff, 0);
        lpc.lspAz(lspIntVariab, 0, acoeffVariab, 0);
        for (var k = 0; k < 4; k++) {
            System.arraycopy(acoeff, 0, aT, k * MP1, MP1);
        }
        reflection(acoeff, 1, refl, overflow);
        var predErr = MAX_16;
        for (var i = 0; i < M; i++) {
            var square = (refl[i] * refl[i]) >> 15;
            if (square <= 0x00007fff) {
                temp = (short) (MAX_16 - (short) square);
            } else {
                overflow.value = true;
                temp = 0;
            }
            predErr = mult(predErr, temp, overflow);
        }
        var log = FixedMath.log2(predErr);
        var logPgE = (short) (log >> 16);
        var logPgM = (short) log;
        var logPg = shl((short) (logPgE - 15), (short) 12);
        logPg = shr(sub((short) 0, add(logPg, shr(logPgM, (short) (15 - 12), overflow), overflow), overflow),
                (short) 1, overflow);
        logPgMean = add(mult((short) 29491, logPgMean, overflow), mult((short) 3277, logPg, overflow), overflow);
        logEnInt = lShr(logEnInt, (short) 10);
        logEnInt = lAdd(logEnInt, 4 * 65536, overflow);
        logEnInt = lSub(logEnInt, lShl(logPg, (short) 4), overflow);
        logEnInt = lAdd(logEnInt, lShl(logEnAdjust, (short) 5), overflow);
        var logEnIntE = (short) (logEnInt >> 16);
        var logEnIntM = (short) lShr(lSub(logEnInt, logEnIntE << 16, overflow), (short) 1);
        var level = (short) FixedMath.pow2(logEnIntE, logEnIntM, overflow);
        for (var i = 0; i < 4; i++) {
            buildComfortNoiseCode(ex, overflow);
            for (var j = L_SUBFR - 1; j >= 0; j--) {
                ex[j] = mult(level, ex[j], overflow);
            }
            Filters.synFilt(acoeffVariab, 0, ex, 0, synth, i * L_SUBFR, L_SUBFR, memSyn, 0, true, overflow);
        }
        averState.hangVar = 20;
        averState.hangCount = 0;
        if (newState == DTX_MUTE) {
            var tmpIntLength = sinceLastSid;
            if (tmpIntLength > 32) {
                tmpIntLength = 32;
            } else if (tmpIntLength <= 0) {
                tmpIntLength = 8;
            }
            temp = saturatedShift10(tmpIntLength, overflow);
            trueSidPeriodInv = divS((short) (1 << 10), temp);
            sinceLastSid = 0;
            System.arraycopy(lsp, 0, lspOld, 0, M);
            oldLogEn = logEn;
            logEn = sub(logEn, (short) 256, overflow);
        }
        if (sidFrame != 0 && (validData != 0 || dtxHangoverAdded != 0)) {
            sinceLastSid = 0;
            dataUpdated = 1;
        }
    }

    /**
     * Shifts a frame count left by 10, saturating to 16 bits.
     *
     * @param value the count
     * @param overflow the flag raised on saturation
     * @return the shifted count
     */
    private static short saturatedShift10(short value, Flag overflow) {
        var shifted = value << 10;
        if (shifted != (short) shifted) {
            overflow.value = true;
            shifted = value > 0 ? MAX_16 : MIN_16;
        }
        return (short) shifted;
    }

    /**
     * Records the LSFs and the energy of a decoded speech frame in the histories
     * ({@code dtx_dec_activity_update}).
     *
     * @param lsfIn the 10 LSFs of the frame
     * @param frame the 160 decoded samples
     * @param overflow the flag raised on saturation
     */
    void activityUpdate(short[] lsfIn, short[] frame, Flag overflow) {
        lsfHistPtr += M;
        if (lsfHistPtr == 80) {
            lsfHistPtr = 0;
        }
        System.arraycopy(lsfIn, 0, lsfHist, lsfHistPtr, M);
        var frameEnergy = 0;
        for (var i = L_FRAME - 1; i >= 0; i--) {
            var square = frame[i] * frame[i];
            square = square != 0x40000000 ? square << 1 : MAX_32;
            frameEnergy = lAdd(frameEnergy, square, overflow);
        }
        var log = FixedMath.log2(frameEnergy);
        var logEnE = (short) (log >> 16);
        var logEnM = (short) log;
        logEnE = saturatedShift10(logEnE, overflow);
        logEnM = (short) (logEnM >> 5);
        var logEnergy = (short) (logEnE + logEnM);
        logEnergy = (short) (logEnergy - (7497 + 1024));
        logEnHistPtr += 1;
        if (logEnHistPtr == DTX_HIST_SIZE) {
            logEnHistPtr = 0;
        }
        logEnHist[logEnHistPtr] = logEnergy;
    }

    /**
     * Derives the receiver state of a frame from its type and the previous state
     * ({@code rx_dtx_handler}).
     *
     * @param frameType the RX frame type
     * @param overflow the flag raised on saturation
     * @return the new receiver state: {@link #SPEECH}, {@link #DTX} or {@link #DTX_MUTE}
     */
    int handle(int frameType, Flag overflow) {
        int newState;
        if (frameType == FrameType.RX_SID_FIRST || frameType == FrameType.RX_SID_UPDATE
                || frameType == FrameType.RX_SID_BAD
                || ((dtxGlobalState == DTX || dtxGlobalState == DTX_MUTE)
                && (frameType == FrameType.RX_NO_DATA || frameType == FrameType.RX_SPEECH_BAD
                || frameType == FrameType.RX_ONSET))) {
            newState = DTX;
            if (dtxGlobalState == DTX_MUTE && (frameType == FrameType.RX_SID_BAD || frameType == FrameType.RX_SID_FIRST
                    || frameType == FrameType.RX_ONSET || frameType == FrameType.RX_NO_DATA)) {
                newState = DTX_MUTE;
            }
            sinceLastSid += 1;
            if (frameType != FrameType.RX_SID_UPDATE && sinceLastSid > DTX_MAX_EMPTY_THRESH) {
                newState = DTX_MUTE;
            }
        } else {
            newState = SPEECH;
            sinceLastSid = 0;
        }
        if (dataUpdated == 0 && frameType == FrameType.RX_SID_UPDATE) {
            decAnaElapsedCount = 0;
        }
        decAnaElapsedCount = add(decAnaElapsedCount, (short) 1, overflow);
        dtxHangoverAdded = 0;
        int encState;
        if (frameType == FrameType.RX_SID_FIRST || frameType == FrameType.RX_SID_UPDATE
                || frameType == FrameType.RX_SID_BAD || frameType == FrameType.RX_ONSET
                || frameType == FrameType.RX_NO_DATA) {
            encState = DTX;
            if (frameType == FrameType.RX_NO_DATA && newState == SPEECH) {
                encState = SPEECH;
            }
        } else {
            encState = SPEECH;
        }
        if (encState == SPEECH) {
            dtxHangoverCount = DTX_HANG_CONST;
        } else if (decAnaElapsedCount > DTX_ELAPSED_FRAMES_THRESH) {
            dtxHangoverAdded = 1;
            decAnaElapsedCount = 0;
            dtxHangoverCount = 0;
        } else if (dtxHangoverCount == 0) {
            decAnaElapsedCount = 0;
        } else {
            dtxHangoverCount -= 1;
        }
        if (newState != SPEECH) {
            sidFrame = 0;
            validData = 0;
            if (frameType == FrameType.RX_SID_FIRST) {
                sidFrame = 1;
            } else if (frameType == FrameType.RX_SID_UPDATE) {
                sidFrame = 1;
                validData = 1;
            } else if (frameType == FrameType.RX_SID_BAD) {
                sidFrame = 1;
                dtxHangoverAdded = 0;
            }
        }
        return newState;
    }

    /**
     * Draws bits from the comfort noise generator's shift register ({@code pseudonoise}).
     *
     * @param bits the number of bits, at most 15
     * @return the bits
     */
    private short pseudonoise(int bits) {
        short noise = 0;
        for (var i = 0; i < bits; i++) {
            var sn = (lPnSeedRx & 1) ^ ((lPnSeedRx & 0x10000000) != 0 ? 1 : 0);
            noise = (short) (noise << 1);
            noise |= (short) (lPnSeedRx & 1);
            lPnSeedRx >>= 1;
            if ((sn & 1) != 0) {
                lPnSeedRx |= 0x40000000;
            }
        }
        return noise;
    }

    /**
     * Builds a random comfort noise excitation of 10 unit pulses ({@code build_CN_code}).
     *
     * @param cod receives the 40 samples
     * @param overflow the flag raised on saturation
     */
    private void buildComfortNoiseCode(short[] cod, Flag overflow) {
        Arrays.fill(cod, 0, L_SUBFR, (short) 0);
        for (var k = 0; k < NB_PULSE; k++) {
            var i = pseudonoise(2);
            var temp = (short) lMult(i, (short) 10, overflow);
            i = (short) (temp >> 1);
            i = add(i, (short) k, overflow);
            var j = pseudonoise(1);
            cod[i] = j > 0 ? (short) 4096 : (short) -4096;
        }
    }

    /**
     * Fills the parameters of a lost frame from the decoder's no-data generator
     * ({@code build_CN_param}).
     *
     * @param seed the generator state
     * @param mode the mode whose parameters are built
     * @param parm receives the parameters
     * @param overflow the flag raised on saturation
     * @return the new generator state
     */
    static short buildComfortNoiseParameters(short seed, int mode, short[] parm, Flag overflow) {
        var temp = lMult(seed, (short) 31821, overflow);
        temp >>= 1;
        seed = (short) lAdd(temp, 13849, overflow);
        var position = seed & 0x7F;
        var window = CommonTables.WINDOW_200_40;
        var count = BitTables.PRMNO[mode];
        var sizes = BitTables.BITNO[mode];
        for (var i = 0; i < count; i++) {
            var mask = (short) ~(0xFFFF << sizes[i]);
            parm[i] = (short) (window[position++] & mask);
        }
        return seed;
    }

    /**
     * Converts LP coefficients to reflection coefficients, all zero for an unstable filter
     * ({@code A_Refl}).
     *
     * @param a the LP coefficients a[1] to a[10]
     * @param aOffset the index of a[1]
     * @param out receives the 10 reflection coefficients
     * @param overflow the flag raised on saturation
     */
    private void reflection(short[] a, int aOffset, short[] out, Flag overflow) {
        System.arraycopy(a, aOffset, aState, 0, M);
        for (var i = M - 1; i >= 0; i--) {
            if (abs(aState[i]) >= 4096) {
                Arrays.fill(out, 0, M, (short) 0);
                break;
            }
            out[i] = shl(aState[i], (short) 3);
            var square = lMult(out[i], out[i], overflow);
            var acc = lSub(MAX_32, square, overflow);
            var normShift = normL(acc);
            var scale = (short) (15 - normShift);
            acc = lShl(acc, normShift);
            var normProd = round(acc, overflow);
            var factor = divS((short) 16384, normProd);
            for (var j = 0; j < i; j++) {
                acc = aState[j] << 16;
                acc = lMsu(acc, out[i], aState[i - j - 1], overflow);
                var temp = round(acc, overflow);
                var product = lMult(factor, temp, overflow);
                product = lShrR(product, scale);
                var magnitude = product - (product < 0 ? 1 : 0);
                magnitude = magnitude ^ (magnitude >> 31);
                if (magnitude > 32767) {
                    Arrays.fill(out, 0, M, (short) 0);
                    break;
                }
                bState[j] = (short) product;
            }
            System.arraycopy(bState, 0, aState, 0, i);
        }
    }
}
