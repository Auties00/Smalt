/* Ports opencore-amr 0.1.6 (dtx_decoder_amr_wb.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import static com.github.auties00.smalt.audio.amr.internal.wb.WbMath.*;

/**
 * Tracks the discontinuous transmission state of the AMR-WB decoder and generates the comfort
 * noise excitation and ISFs of the frames without speech.
 *
 * @implNote This implementation follows the DTX rules of the current TS 26.173 where opencore's are older: the energy
 * history starts at {@code 3500 >> 3}, a frame without data counts as a DTX frame for the hangover only after speech
 * when seven or more frames without voice activity preceded it ({@link #dtxVadHist}), a SID update never mutes the
 * noise, the SID period of a muted frame defaults to 8 frames and the muted energy is not clamped at zero. Its
 * arithmetic saturates where the TS 26.173 operators do; opencore wraps some sums.
 */
final class WbDtx {
    /**
     * The state of a frame with speech.
     */
    static final int SPEECH = 0;

    /**
     * The state of a frame of comfort noise.
     */
    static final int DTX = 1;

    /**
     * The state of a frame of comfort noise fading out after too many frames without a SID.
     */
    static final int DTX_MUTE = 2;

    /**
     * The receive type of a good speech frame.
     */
    static final int RX_SPEECH_GOOD = 0;

    /**
     * The receive type of a speech frame with probable errors.
     */
    static final int RX_SPEECH_PROBABLY_DEGRADED = 1;

    /**
     * The receive type of a lost speech frame.
     */
    static final int RX_SPEECH_LOST = 2;

    /**
     * The receive type of a bad speech frame.
     */
    static final int RX_SPEECH_BAD = 3;

    /**
     * The receive type of the first SID frame of a silence period.
     */
    static final int RX_SID_FIRST = 4;

    /**
     * The receive type of a SID update frame.
     */
    static final int RX_SID_UPDATE = 5;

    /**
     * The receive type of a bad SID frame.
     */
    static final int RX_SID_BAD = 6;

    /**
     * The receive type of a frame without data.
     */
    static final int RX_NO_DATA = 7;

    /**
     * The number of frames of the ISF and energy histories.
     */
    private static final int HIST_SIZE = 8;

    /**
     * The number of frames without a SID after which the noise is muted.
     */
    private static final int MAX_EMPTY_THRESH = 50;

    /**
     * The number of frames after which a hangover is assumed.
     */
    private static final int ELAPSED_FRAMES_THRESH = 24 + 7 - 1;

    /**
     * The length of the DTX hangover.
     */
    private static final int HANG_CONST = 7;

    /**
     * The minimum distance of the first ISF after dithering.
     */
    private static final int ISF_GAP = 128;

    /**
     * The minimum distance between dithered ISFs.
     */
    private static final int ISF_DITH_GAP = 448;

    /**
     * The dithering factor of the first ISF.
     */
    private static final int ISF_FACTOR_LOW = 256;

    /**
     * The increase of the dithering factor per ISF.
     */
    private static final int ISF_FACTOR_STEP = 2;

    /**
     * The dithering factor of the energy.
     */
    private static final int GAIN_FACTOR = 75;

    /**
     * The order of the ISF vectors.
     */
    private static final int M = WbLpc.M;

    /**
     * The number of samples of a 12.8 kHz frame.
     */
    private static final int L_FRAME = 256;

    /**
     * The number of frames since the last SID.
     */
    short sinceLastSid;

    /**
     * The inverse of the measured SID period.
     */
    short trueSidPeriodInv;

    /**
     * The log energy of the noise.
     */
    short logEn;

    /**
     * The log energy of the previous noise.
     */
    short oldLogEn;

    /**
     * The ISFs of the noise.
     */
    final short[] isf = new short[M];

    /**
     * The ISFs of the previous noise.
     */
    final short[] isfOld = new short[M];

    /**
     * The seed of the noise generator, as a one-element holder.
     */
    final short[] cngSeed = new short[1];

    /**
     * The ISF history of the last speech frames.
     */
    final short[] isfHist = new short[M * HIST_SIZE];

    /**
     * The log energy history of the last speech frames.
     */
    final short[] logEnHist = new short[HIST_SIZE];

    /**
     * The index of the newest history entry.
     */
    short histPtr;

    /**
     * The remaining hangover frames.
     */
    short dtxHangoverCount;

    /**
     * The number of frames since the last DTX analysis.
     */
    short decAnaElapsedCount;

    /**
     * Whether the current frame is a SID.
     */
    short sidFrame;

    /**
     * Whether the current SID carries valid data.
     */
    short validData;

    /**
     * Whether a hangover period was added.
     */
    short dtxHangoverAdded;

    /**
     * The DTX state of the previous frame: {@link #SPEECH}, {@link #DTX} or {@link #DTX_MUTE}.
     */
    short dtxGlobalState;

    /**
     * Whether the noise parameters were updated by a SID.
     */
    short dataUpdated;

    /**
     * The seed of the dithering generator, as a one-element holder.
     */
    final short[] ditherSeed = new short[1];

    /**
     * Whether comfort noise dithering is on.
     */
    short cnDith;

    /**
     * The number of consecutive frames without voice activity seen by the DTX hangover logic.
     */
    short dtxVadHist;

    /**
     * The scratch SID indices.
     */
    private final short[] ind = new short[7];

    /**
     * The scratch ISF sums.
     */
    private final int[] isfSum = new int[M];

    /**
     * Creates the state; {@link #reset(short[])} must be called before use.
     */
    WbDtx() {
    }

    /**
     * Resets the state.
     *
     * @param isfInit the initial ISFs
     */
    void reset(short[] isfInit) {
        sinceLastSid = 0;
        trueSidPeriodInv = 1 << 13;
        logEn = 3500;
        oldLogEn = 3500;
        cngSeed[0] = 21845;
        histPtr = 0;
        System.arraycopy(isfInit, 0, isf, 0, M);
        System.arraycopy(isfInit, 0, isfOld, 0, M);
        for (var i = 0; i < HIST_SIZE; i++) {
            System.arraycopy(isfInit, 0, isfHist, i * M, M);
            logEnHist[i] = 3500 >> 3;
        }
        dtxHangoverCount = HANG_CONST;
        decAnaElapsedCount = 32767;
        sidFrame = 0;
        validData = 0;
        dtxHangoverAdded = 0;
        dtxGlobalState = SPEECH;
        dataUpdated = 0;
        ditherSeed[0] = 21845;
        cnDith = 0;
        dtxVadHist = 0;
    }

    /**
     * Generates the comfort noise excitation and ISFs of a frame without speech.
     *
     * @param exc2 the 256-sample excitation
     * @param excOff the offset of the excitation
     * @param newState the DTX state of the frame
     * @param isfOut the interpolated noise ISFs
     * @param isfOff the offset of the noise ISFs
     * @param prms the bits of the frame, positioned at the SID parameters
     */
    void decode(short[] exc2, int excOff, int newState, short[] isfOut, int isfOff, WbBits prms) {
        if (dtxHangoverAdded != 0 && sidFrame != 0) {
            var ptr = (short) (histPtr + 1);
            if (ptr == HIST_SIZE) {
                ptr = 0;
            }
            System.arraycopy(isfHist, histPtr * M, isfHist, ptr * M, M);
            logEnHist[ptr] = logEnHist[histPtr];
            logEn = 0;
            java.util.Arrays.fill(isfSum, 0);
            for (var i = 0; i < HIST_SIZE; i++) {
                logEn = add16(logEn, logEnHist[i]);
                for (var j = 0; j < M; j++) {
                    isfSum[j] = add32(isfSum[j], isfHist[i * M + j]);
                }
            }
            logEn = (short) (logEn >> 1);
            logEn = add16(logEn, 1024);
            if (logEn < 0) {
                logEn = 0;
            }
            for (var j = 0; j < M; j++) {
                isf[j] = (short) (isfSum[j] >> 3);
            }
        }
        if (sidFrame != 0) {
            System.arraycopy(isf, 0, isfOld, 0, M);
            oldLogEn = logEn;
            if (validData != 0) {
                var length = sinceLastSid;
                if (length > 32) {
                    length = 32;
                }
                if (length >= 2) {
                    trueSidPeriodInv = div(1 << 10, shl16(length, 10));
                } else {
                    trueSidPeriodInv = 1 << 14;
                }
                ind[0] = prms.parm(6);
                ind[1] = prms.parm(6);
                ind[2] = prms.parm(6);
                ind[3] = prms.parm(5);
                ind[4] = prms.parm(5);
                WbLpc.disfNs(ind, isf);
                var logEnIndex = prms.parm(6);
                cnDith = prms.parm1();
                logEn = shl16(logEnIndex, 15 - 6);
                logEn = mult(logEn, 12483);
                if (dataUpdated == 0 || dtxGlobalState == SPEECH) {
                    System.arraycopy(isf, 0, isfOld, 0, M);
                    oldLogEn = logEn;
                }
            }
        }
        if (sidFrame != 0 && validData != 0) {
            sinceLastSid = 0;
        }
        var intFac = shl16(sinceLastSid, 10);
        intFac = mult(intFac, trueSidPeriodInv);
        if (intFac > 1024) {
            intFac = 1024;
        }
        intFac = shl16(intFac, 4);
        var logEnInt = mul(intFac, logEn);
        for (var i = 0; i < M; i++) {
            isfOut[isfOff + i] = mult(intFac, isf[i]);
        }
        intFac = (short) (16384 - intFac);
        logEnInt = mac(logEnInt, intFac, oldLogEn);
        for (var i = 0; i < M; i++) {
            isfOut[isfOff + i] = add16(isfOut[isfOff + i], mult(intFac, isfOld[i]));
            isfOut[isfOff + i] = shl16(isfOut[isfOff + i], 1);
        }
        if (cnDith != 0) {
            logEnInt = dither(isfOut, isfOff, logEnInt);
        }
        logEnInt >>= 9;
        var logEnIntE = (short) (logEnInt >> 16);
        var logEnIntM = (short) (sub32(logEnInt, logEnIntE << 16) >> 1);
        logEnIntE = add16(logEnIntE, 15);
        var level32 = powerOf2(logEnIntE, logEnIntM);
        var exp0 = (short) normalize(level32);
        level32 <<= exp0;
        exp0 = (short) (15 - exp0);
        var level = (short) (level32 >> 16);
        for (var i = 0; i < L_FRAME; i++) {
            exc2[excOff + i] = (short) (noise(cngSeed, 0) >> 4);
        }
        var packed = dotProduct12(exc2, excOff, exc2, excOff, L_FRAME);
        packed = oneOvSqrtNorm(value(packed), exponent(packed));
        var gain = (short) (value(packed) >> 16);
        gain = mult(level, gain);
        var exp = add16(add16(exp0, exponent(packed)), 4);
        for (var i = 0; i < L_FRAME; i++) {
            var tmp = mult(exc2[excOff + i], gain);
            exc2[excOff + i] = shl16(tmp, exp);
        }
        if (newState == DTX_MUTE) {
            var length = sinceLastSid;
            if (length > 32) {
                length = 32;
            }
            if (length <= 0) {
                length = 8;
            }
            trueSidPeriodInv = div(1 << 10, shl16(length, 10));
            sinceLastSid = 0;
            oldLogEn = logEn;
            logEn = sub16(logEn, 64);
        }
        if (sidFrame != 0 && (validData != 0 || dtxHangoverAdded != 0)) {
            sinceLastSid = 0;
            dataUpdated = 1;
        }
    }

    /**
     * Adds the dithering of comfort noise to the ISFs and the log energy.
     *
     * @param isfOut the ISFs, dithered in place
     * @param isfOff the offset of the ISFs
     * @param logEnInt the interpolated log energy
     * @return the dithered log energy
     */
    private int dither(short[] isfOut, int isfOff, int logEnInt) {
        var rand = (short) (noise(ditherSeed, 0) >> 1);
        var rand2 = (short) (noise(ditherSeed, 0) >> 1);
        rand += rand2;
        logEnInt = add32(logEnInt, mul(rand, GAIN_FACTOR));
        if (logEnInt < 0) {
            logEnInt = 0;
        }
        var ditherFac = (short) ISF_FACTOR_LOW;
        rand = (short) (noise(ditherSeed, 0) >> 1);
        rand2 = (short) (noise(ditherSeed, 0) >> 1);
        rand += rand2;
        var temp = add16(isfOut[isfOff], multR(rand, ditherFac));
        isfOut[isfOff] = temp < ISF_GAP ? ISF_GAP : temp;
        for (var i = 1; i < M - 1; i++) {
            ditherFac = add16(ditherFac, ISF_FACTOR_STEP);
            rand = (short) (noise(ditherSeed, 0) >> 1);
            rand2 = (short) (noise(ditherSeed, 0) >> 1);
            rand += rand2;
            temp = add16(isfOut[isfOff + i], multR(rand, ditherFac));
            var temp1 = sub16(temp, isfOut[isfOff + i - 1]);
            if (temp1 < ISF_DITH_GAP) {
                isfOut[isfOff + i] = add16(isfOut[isfOff + i - 1], ISF_DITH_GAP);
            } else {
                isfOut[isfOff + i] = temp;
            }
        }
        if (isfOut[isfOff + M - 2] > 16384) {
            isfOut[isfOff + M - 2] = 16384;
        }
        return logEnInt;
    }

    /**
     * Updates the ISF and energy histories with a frame of speech.
     *
     * @param isfIn the ISFs of the frame
     * @param isfOff the offset of the ISFs
     * @param exc the excitation of the frame
     * @param excOff the offset of the excitation
     */
    void activityUpdate(short[] isfIn, int isfOff, short[] exc, int excOff) {
        histPtr++;
        if (histPtr == HIST_SIZE) {
            histPtr = 0;
        }
        System.arraycopy(isfIn, isfOff, isfHist, histPtr * M, M);
        var frameEn = 0;
        for (var i = 0; i < L_FRAME; i++) {
            frameEn = mac(frameEn, exc[excOff + i], exc[excOff + i]);
        }
        frameEn >>= 1;
        var log = log2(frameEn);
        var logEnE = (short) (log >> 16);
        var logEnM = (short) log;
        var en = shl16(logEnE, 7);
        en = add16(en, logEnM >> 8);
        en = sub16(en, 1024);
        logEnHist[histPtr] = en;
    }

    /**
     * Derives the DTX state of a frame from its receive type and updates the hangover tracking.
     *
     * @param frameType the receive type
     * @return the DTX state of the frame
     */
    int rxHandler(int frameType) {
        int newState;
        if (frameType == RX_SID_FIRST || frameType == RX_SID_UPDATE || frameType == RX_SID_BAD
                || ((dtxGlobalState == DTX || dtxGlobalState == DTX_MUTE)
                && (frameType == RX_NO_DATA || frameType == RX_SPEECH_BAD || frameType == RX_SPEECH_LOST))) {
            newState = DTX;
            if (dtxGlobalState == DTX_MUTE && (frameType == RX_SID_BAD || frameType == RX_SID_FIRST
                    || frameType == RX_SPEECH_LOST || frameType == RX_NO_DATA)) {
                newState = DTX_MUTE;
            }
            sinceLastSid = add16(sinceLastSid, 1);
            if (frameType != RX_SID_UPDATE && sinceLastSid > MAX_EMPTY_THRESH) {
                newState = DTX_MUTE;
            }
        } else {
            newState = SPEECH;
            sinceLastSid = 0;
        }
        if (dataUpdated == 0 && frameType == RX_SID_UPDATE) {
            decAnaElapsedCount = 0;
        }
        decAnaElapsedCount = add16(decAnaElapsedCount, 1);
        dtxHangoverAdded = 0;
        var encState = frameType == RX_SID_FIRST || frameType == RX_SID_UPDATE || frameType == RX_SID_BAD
                || (frameType == RX_NO_DATA && (dtxGlobalState != SPEECH || dtxVadHist >= HANG_CONST))
                ? DTX : SPEECH;
        if (encState == SPEECH) {
            dtxHangoverCount = HANG_CONST;
        } else if (decAnaElapsedCount > ELAPSED_FRAMES_THRESH) {
            dtxHangoverAdded = 1;
            decAnaElapsedCount = 0;
            dtxHangoverCount = 0;
        } else if (dtxHangoverCount == 0) {
            decAnaElapsedCount = 0;
        } else {
            dtxHangoverCount = sub16(dtxHangoverCount, 1);
        }
        if (newState != SPEECH) {
            sidFrame = 0;
            validData = 0;
            if (frameType == RX_SID_FIRST) {
                sidFrame = 1;
            } else if (frameType == RX_SID_UPDATE) {
                sidFrame = 1;
                validData = 1;
            } else if (frameType == RX_SID_BAD) {
                sidFrame = 1;
                dtxHangoverAdded = 0;
            }
        }
        return newState;
    }
}
