/* Ports vo-amrwbenc 0.1.3 (voAMRWBEnc.c, bits.c, homing.c). Copyright VisualOn; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.AmrFrameEncoder;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbBitOrder;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbCodecTables;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbPitchOps;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Encodes AMR-WB (3GPP TS 26.190) speech: 20 ms frames of 320 samples at 16 kHz into one of the
 * nine speech modes (6.60 to 23.85 kbit/s), with optional voice activity detection and
 * discontinuous transmission (TS 26.193, TS 26.194) producing SID and NO_DATA frames.
 *
 * <p>Frames are produced in the RFC 4867 storage format ({@link #encode(int, short[], int, byte[], int)})
 * or in the 3GPP serial format of the TS 26.174 test sequences
 * ({@link #encodeSerial(int, short[], int, short[])}). The mode may change on every frame. An
 * encoder homing frame (320 samples of {@code 0x0008}) resets the encoder after it is encoded, as
 * TS 26.190 section 6 requires. The encoder allocates nothing per frame and is not thread-safe.
 *
 * @implNote This implementation computes as the 3GPP TS 26.173 fixed-point reference does, so it reproduces every TS
 * 26.174 test sequence bit for bit (speech in every mode, mode switching and DTX). vo-amrwbenc departs from TS 26.173:
 * it wraps where the reference saturates, aligns the 32-bit synthesis filter's coefficients differently, and lacks the
 * 6.60 and 8.85 kbit/s pitch gain clipping and the impulse response scaling of the codebook search.
 */
public final class WbEncoder implements AmrFrameEncoder {

    /**
     * The number of samples of a frame at 16 kHz.
     */
    private static final int L_FRAME16K = 320;

    /**
     * The number of samples of a frame at 12.8 kHz.
     */
    private static final int L_FRAME = 256;

    /**
     * The number of samples of a subframe at 12.8 kHz.
     */
    private static final int L_SUBFR = 64;

    /**
     * The number of samples of a subframe at 16 kHz.
     */
    private static final int L_SUBFR16K = 80;

    /**
     * The look-ahead of the LP analysis at 12.8 kHz.
     */
    private static final int L_NEXT = 64;

    /**
     * The number of samples of the speech buffer at 12.8 kHz.
     */
    private static final int L_TOTAL = 384;

    /**
     * The number of samples of the LP analysis window.
     */
    private static final int L_WINDOW = 384;

    /**
     * The LP order.
     */
    private static final int M = 16;

    /**
     * The delay of the decimation filter at 16 kHz.
     */
    private static final int L_FILT16K = 15;

    /**
     * The delay of the decimation filter at 12.8 kHz.
     */
    private static final int L_FILT = 12;

    /**
     * The largest quantized pitch gain when the gain is clipped: 0.95 in Q14.
     */
    private static final int GP_CLIP = 15565;

    /**
     * The pitch sharpening factor: 0.85 in Q15.
     */
    private static final int PIT_SHARP = 27853;

    /**
     * The smallest pitch lag.
     */
    private static final int PIT_MIN = 34;

    /**
     * The lag below which 9-bit pitch coding uses quarter resolution.
     */
    private static final int PIT_FR2 = 128;

    /**
     * The lag from which 9-bit pitch coding uses integer resolution.
     */
    private static final int PIT_FR1_9B = 160;

    /**
     * The lag from which 8-bit pitch coding uses integer resolution.
     */
    private static final int PIT_FR1_8B = 92;

    /**
     * The largest pitch lag.
     */
    private static final int PIT_MAX = 231;

    /**
     * The length of the adaptive codebook interpolation filter.
     */
    private static final int L_INTERPOL = 16 + 1;

    /**
     * The decimation of the open-loop pitch search.
     */
    private static final int OPL_DECIM = 2;

    /**
     * The pre-emphasis factor: 0.68 in Q15.
     */
    private static final int PREEMPH_FAC = 22282;

    /**
     * The weighting factor of the perceptual filter: 0.92 in Q15.
     */
    private static final int GAMMA1 = 30147;

    /**
     * The tilt factor of the perceptual filter: 0.68 in Q15.
     */
    private static final int TILT_FAC = 22282;

    /**
     * The largest scaling of the speech.
     */
    private static final int Q_MAX = 8;

    /**
     * The mode of comfort-noise frames.
     */
    private static final int MRDTX = 9;

    /**
     * The frame type of a speech frame ({@code TX_SPEECH}).
     */
    private static final short TX_SPEECH = 0;

    /**
     * The frame type of the first SID frame of a pause ({@code TX_SID_FIRST}).
     */
    private static final short TX_SID_FIRST = 1;

    /**
     * The frame type of a SID update frame ({@code TX_SID_UPDATE}).
     */
    private static final short TX_SID_UPDATE = 2;

    /**
     * The frame type of a frame without data ({@code TX_NO_DATA}).
     */
    private static final short TX_NO_DATA = 3;

    /**
     * The first word of a frame of the 3GPP serial format ({@code TX_FRAME_TYPE}).
     */
    private static final short TX_FRAME_TYPE = 0x6b21;

    /**
     * The sample value of the encoder homing frame.
     */
    private static final short EHF_MASK = 0x0008;

    /**
     * The order of the bits of each storage-format frame type, {@code null} for frame types
     * without bits.
     */
    private static final short[][] SORT = {
            WbBitOrder.SORT_660, WbBitOrder.SORT_885, WbBitOrder.SORT_1265, WbBitOrder.SORT_1425,
            WbBitOrder.SORT_1585, WbBitOrder.SORT_1825, WbBitOrder.SORT_1985, WbBitOrder.SORT_2305,
            WbBitOrder.SORT_2385, WbBitOrder.SORT_SID, null, null, null, null, null, null
    };

    /**
     * Whether voice activity detection and discontinuous transmission are on.
     */
    private final boolean dtx;

    /**
     * The filters and their scratch buffers.
     */
    private final WbFilters filters = new WbFilters();

    /**
     * The doubled coefficients of the open-loop pitch correlations.
     */
    final int[] pitchCoefficients = new int[128];

    /**
     * The sums of the pitch filters.
     */
    final int[] pitchSums = new int[128];

    /**
     * The LP analysis and its scratch buffers.
     */
    private final WbLpc lpc = new WbLpc();

    /**
     * The ISF quantizer.
     */
    private final WbIsfQuantizer isfQuantizer = new WbIsfQuantizer();

    /**
     * The pitch analysis.
     */
    private final WbPitch pitch = new WbPitch();

    /**
     * The algebraic codebook search.
     */
    private final WbAcelp acelp = new WbAcelp();

    /**
     * The gain quantizer.
     */
    private final WbGainQuantizer gainQuantizer = new WbGainQuantizer();

    /**
     * The voice activity detector.
     */
    private final WbVad vad = new WbVad();

    /**
     * The discontinuous transmission state.
     */
    private final WbDtx dtxState = new WbDtx(WbCodecTables.ISF_INIT);

    /**
     * The bits of the frame being encoded.
     */
    private final WbParams params = new WbParams();

    /**
     * The memory of the 16 kHz to 12.8 kHz decimation.
     */
    private final short[] memDecim = new short[2 * L_FILT16K];

    /**
     * The memory of the 50 Hz high-pass filter of the input.
     */
    private final short[] memSigIn = new short[6];

    /**
     * The memory of the pre-emphasis filter.
     */
    private short memPreemph;

    /**
     * The past speech at 12.8 kHz.
     */
    private final short[] oldSpeech = new short[L_TOTAL - L_FRAME];

    /**
     * The past decimated weighted speech.
     */
    private final short[] oldWsp = new short[PIT_MAX / OPL_DECIM];

    /**
     * The past excitation.
     */
    private final short[] oldExc = new short[PIT_MAX + L_INTERPOL];

    /**
     * The memory of the Levinson-Durbin recursion.
     */
    private final short[] memLevinson = new short[M + 2];

    /**
     * The unquantized ISPs of the previous frame.
     */
    private final short[] ispold = new short[M];

    /**
     * The quantized ISPs of the previous frame.
     */
    private final short[] ispoldQ = new short[M];

    /**
     * The quantized ISF prediction residuals of the previous frame.
     */
    private final short[] pastIsfq = new short[M];

    /**
     * The memory of the weighted speech de-emphasis.
     */
    private short memWsp;

    /**
     * The memory of the decimation by 2 of the weighted speech.
     */
    private final short[] memDecim2 = new short[3];

    /**
     * The memory of the weighting filter of the target.
     */
    private short memW0;

    /**
     * The memory of the LP synthesis filter.
     */
    private final short[] memSyn = new short[M];

    /**
     * The tilt of the code for the next subframe.
     */
    private short tiltCode;

    /**
     * The largest absolute weighted speech value of the previous frame.
     */
    private short oldWspMax;

    /**
     * The scaling of the weighted speech of the previous frame.
     */
    private short oldWspShift;

    /**
     * The scaling of the speech of the previous frame.
     */
    private short qOld;

    /**
     * The largest allowed scalings of the last two frames.
     */
    private final short[] qMax = new short[2];

    /**
     * The memory of the pitch gain clipping test.
     */
    private final short[] gpClip = new short[2];

    /**
     * The memory of the gain quantizer's energy predictor.
     */
    private final short[] quaGain = new short[4];

    /**
     * The median open-loop lag, read by {@link WbPitch}.
     */
    short oldT0Med;

    /**
     * The open-loop pitch gain, written by {@link WbPitch}.
     */
    short olGain;

    /**
     * The adaptive weight of the open-loop lag weighting.
     */
    private short adaW;

    /**
     * Whether the open-loop correlation is weighted around the median lag, read by
     * {@link WbPitch}.
     */
    short olWeightFlag;

    /**
     * The last five open-loop lags.
     */
    private final short[] oldOlLag = new short[5];

    /**
     * The memory of the weighted speech high-pass filter, used by {@link WbPitch}.
     */
    final short[] hpWspMem = new short[9];

    /**
     * The past high-pass filtered weighted speech, used by {@link WbPitch}.
     */
    final short[] oldHpWsp = new short[L_FRAME / OPL_DECIM + PIT_MAX / OPL_DECIM];

    /**
     * Whether the next speech frame is the first after a reset.
     */
    private boolean firstFrame;

    /**
     * The quantized ISFs of the previous frame.
     */
    private final short[] isfold = new short[M];

    /**
     * The threshold of the noise enhancer.
     */
    private int lGcThres;

    /**
     * The memory of the double-precision synthesis filter, high halves.
     */
    private final short[] memSynHi = new short[M];

    /**
     * The memory of the double-precision synthesis filter, low halves.
     */
    private final short[] memSynLo = new short[M];

    /**
     * The memory of the synthesis de-emphasis.
     */
    private short memDeemph;

    /**
     * The memory of the 50 Hz high-pass filter of the synthesis.
     */
    private final short[] memSigOut = new short[6];

    /**
     * The memory of the 400 Hz high-pass filter of the synthesis.
     */
    private final short[] memHp400 = new short[6];

    /**
     * The memory of the high-band synthesis filter.
     */
    private final short[] memSynHf = new short[M];

    /**
     * The memory of the band-pass filter of the high-band noise.
     */
    private final short[] memHf = new short[30];

    /**
     * The memory of the band-pass filter of the input high band.
     */
    private final short[] memHf2 = new short[30];

    /**
     * The seed of the high-band noise generator.
     */
    private short seed2;

    /**
     * The number of consecutive inactive frames.
     */
    private short vadHist;

    /**
     * The weight of the measured high-band gain against the estimated one.
     */
    private short gainAlpha;

    /**
     * The number of frames until the next SID update.
     */
    private short sidUpdateCounter;

    /**
     * The number of SID updates owed after a SID first frame.
     */
    private short sidHandoverDebt;

    /**
     * The frame type of the previous frame.
     */
    private short prevFt;

    /**
     * The masked input frame.
     */
    private final short[] speech16k = new short[L_FRAME16K];

    /**
     * The speech buffer of the current frame at 12.8 kHz.
     */
    private final short[] workSpeech = new short[L_TOTAL];

    /**
     * The decimated weighted speech of the current frame with its history.
     */
    private final short[] workWsp = new short[L_FRAME + PIT_MAX / OPL_DECIM];

    /**
     * The excitation of the current frame with its history.
     */
    private final short[] workExc = new short[(L_FRAME + 1) + PIT_MAX + L_INTERPOL];

    /**
     * The high halves of the autocorrelation.
     */
    private final short[] rH = new short[M + 1];

    /**
     * The low halves of the autocorrelation.
     */
    private final short[] rL = new short[M + 1];

    /**
     * The reflection coefficients.
     */
    private final short[] rc = new short[M];

    /**
     * The weighted LP coefficients.
     */
    private final short[] ap = new short[M + 1];

    /**
     * The unquantized ISPs of the current frame.
     */
    private final short[] ispnew = new short[M];

    /**
     * The quantized ISPs of the current frame.
     */
    private final short[] ispnewQ = new short[M];

    /**
     * The ISFs of the current frame.
     */
    private final short[] isf = new short[M];

    /**
     * The unquantized LP coefficients of the four subframes.
     */
    private final short[] a = new short[4 * (M + 1)];

    /**
     * The quantized LP coefficients of the four subframes.
     */
    private final short[] aq = new short[4 * (M + 1)];

    /**
     * The target of the pitch search.
     */
    private final short[] xn = new short[L_SUBFR];

    /**
     * The target of the codebook search.
     */
    private final short[] xn2 = new short[L_SUBFR];

    /**
     * The correlation of the target with the impulse response.
     */
    private final short[] dn = new short[L_SUBFR];

    /**
     * The target in the residual domain.
     */
    private final short[] cn = new short[L_SUBFR];

    /**
     * The impulse response of the weighted synthesis filter.
     */
    private final short[] h1 = new short[L_SUBFR];

    /**
     * The impulse response for the codebook search.
     */
    private final short[] h2 = new short[L_SUBFR];

    /**
     * The fixed codebook vector.
     */
    private final short[] code = new short[L_SUBFR];

    /**
     * The filtered adaptive codebook vector.
     */
    private final short[] y1 = new short[L_SUBFR];

    /**
     * The filtered fixed codebook vector.
     */
    private final short[] y2 = new short[L_SUBFR];

    /**
     * The synthesis error with the filter memory.
     */
    private final short[] error = new short[M + L_SUBFR];

    /**
     * The synthesis of a subframe.
     */
    private final short[] synth = new short[L_SUBFR];

    /**
     * The excitation of the frame or subframe for the synthesis.
     */
    private final short[] exc2 = new short[L_FRAME];

    /**
     * The scaled speech of the VAD.
     */
    private final short[] vadBuffer = new short[L_FRAME];

    /**
     * The codebook indices.
     */
    private final short[] indice = new short[8];

    /**
     * The pitch gain correlations of the unfiltered prediction.
     */
    private final short[] gCoeff = new short[4];

    /**
     * The pitch gain correlations of the filtered prediction.
     */
    private final short[] gCoeff2 = new short[4];

    /**
     * The high-pass filtered code of the pitch enhancer.
     */
    private final short[] code2 = new short[L_SUBFR];

    /**
     * The high halves of the synthesis with the filter memory.
     */
    private final short[] synthHi = new short[M + L_SUBFR];

    /**
     * The low halves of the synthesis with the filter memory.
     */
    private final short[] synthLo = new short[M + L_SUBFR];

    /**
     * The synthesis of a subframe in the high-band estimation.
     */
    private final short[] synthHb = new short[L_SUBFR];

    /**
     * The high-band noise.
     */
    private final short[] hf = new short[L_SUBFR16K];

    /**
     * The weighted LP coefficients of the high-band synthesis.
     */
    private final short[] apHf = new short[M + 1];

    /**
     * The high band of the input.
     */
    private final short[] hfSp = new short[L_SUBFR16K];

    /**
     * Creates an encoder in its initial state.
     *
     * @param dtx whether voice activity detection and discontinuous transmission are on
     */
    public WbEncoder(boolean dtx) {
        this.dtx = dtx;
        reset();
    }

    @Override
    public void reset() {
        sidUpdateCounter = 3;
        sidHandoverDebt = 0;
        prevFt = TX_SPEECH;
        vad.clear();
        dtxState.clear(WbCodecTables.ISF_INIT);
        resetEncoder(true);
    }

    @Override
    public int encode(int mode, short[] pcm, int pcmOffset, byte[] out, int outOffset) {
        var frameType = encodeFrame(mode, pcm, pcmOffset);
        var frameMode = codingMode;
        if (frameMode == MRDTX) {
            frameMode = 9;
            if (frameType == TX_SID_FIRST) {
                Arrays.fill(params.bits, 0, WbBitOrder.UNPACKED_SIZE[MRDTX], WbBitOrder.BIT_0);
            }
        }
        if (frameMode == 9 && frameType == TX_NO_DATA) {
            frameMode = 15;
        }
        out[outOffset] = (byte) WbBitOrder.HEADER_BYTE[frameMode];
        var count = WbBitOrder.UNPACKED_SIZE[frameMode];
        var sort = SORT[frameMode];
        var p = outOffset + 1;
        var temp = 0;
        for (var i = 1; i < count + 1; i++) {
            if (params.bits[sort[i - 1]] == WbBitOrder.BIT_1) {
                temp++;
            }
            if ((i & 0x7) != 0) {
                temp = (temp << 1) & 0xff;
            } else {
                out[p++] = (byte) temp;
                temp = 0;
            }
        }
        if (frameMode == 9) {
            if (frameType == TX_SID_UPDATE) {
                temp++;
            }
            temp = (temp << 4) & 0xff;
            temp = (temp + (mode & 0x000F)) & 0xff;
        }
        var unused = WbBitOrder.UNUSED_SIZE[frameMode];
        if (unused != 0) {
            temp = (temp << (unused - 1)) & 0xff;
        }
        var size = WbBitOrder.PACKED_SIZE[frameMode];
        if (size > 0) {
            out[p] = (byte) temp;
        }
        finishFrame();
        return 1 + size;
    }

    /**
     * Encodes one frame into the 3GPP serial format of the TS 26.174 test sequences: the word
     * {@code 0x6b21}, the TX frame type (0 speech, 1 SID first, 2 SID update, 3 no data), the
     * mode, then one word per bit ({@code 0x007f} for 1, {@code 0xff81} for 0); comfort-noise
     * frames carry the 35 SID bits.
     *
     * @param mode the mode, 0 (6.60 kbit/s) to 8 (23.85 kbit/s)
     * @param pcm the samples
     * @param pcmOffset the offset of the 320 samples of the frame
     * @param serial the output, with room for 480 words
     * @return the number of words written
     * @throws IllegalArgumentException if the mode is out of range
     */
    public int encodeSerial(int mode, short[] pcm, int pcmOffset, short[] serial) {
        var frameType = encodeFrame(mode, pcm, pcmOffset);
        serial[0] = TX_FRAME_TYPE;
        serial[1] = frameType;
        serial[2] = (short) mode;
        var count = WbBitOrder.UNPACKED_SIZE[codingMode];
        System.arraycopy(params.bits, 0, serial, 3, count);
        finishFrame();
        return 3 + count;
    }

    /**
     * The mode actually coded by the last frame: the requested mode, or {@link #MRDTX} for
     * comfort noise.
     */
    private int codingMode;

    /**
     * Whether the last frame was an encoder homing frame.
     */
    private boolean homing;

    /**
     * Encodes one frame into {@link #params} and returns its TX frame type
     * ({@code AMR_Enc_Encode} up to the packing).
     *
     * @param mode the mode, 0 to 8
     * @param pcm the samples
     * @param pcmOffset the offset of the frame
     * @return the TX frame type
     */
    private short encodeFrame(int mode, short[] pcm, int pcmOffset) {
        if (mode < 0 || mode > 8) {
            throw new IllegalArgumentException("AMR-WB mode " + mode + " is not between 0 and 8");
        }
        var isHoming = true;
        for (var i = 0; i < L_FRAME16K; i++) {
            var sample = pcm[pcmOffset + i];
            if (sample != EHF_MASK) {
                isHoming = false;
            }
            speech16k[i] = (short) (sample & 0xfffC);
        }
        homing = isHoming;
        params.rewind();
        codingMode = coder(mode);
        return frameType(codingMode);
    }

    /**
     * Completes a frame: resets the encoder after a homing frame.
     */
    private void finishFrame() {
        if (homing) {
            resetEncoder(true);
        }
    }

    /**
     * Computes the TX frame type of a frame from its coded mode and updates the SID scheduling
     * (the frame type logic of {@code PackBits}).
     *
     * @param mode the coded mode
     * @return the TX frame type
     */
    private short frameType(int mode) {
        short frameType;
        if (mode == MRDTX) {
            sidUpdateCounter--;
            if (prevFt == TX_SPEECH) {
                frameType = TX_SID_FIRST;
                sidUpdateCounter = 3;
            } else if (sidHandoverDebt > 0 && sidUpdateCounter > 2) {
                frameType = TX_SID_UPDATE;
                sidHandoverDebt--;
            } else if (sidUpdateCounter == 0) {
                frameType = TX_SID_UPDATE;
                sidUpdateCounter = 8;
            } else {
                frameType = TX_NO_DATA;
            }
        } else {
            sidUpdateCounter = 8;
            frameType = TX_SPEECH;
        }
        prevFt = frameType;
        return frameType;
    }

    /**
     * Resets the speech coder memories ({@code Reset_encoder}).
     *
     * @param all whether to reset every memory, or only those of the excitation coding
     */
    private void resetEncoder(boolean all) {
        Arrays.fill(oldExc, (short) 0);
        Arrays.fill(memSyn, (short) 0);
        Arrays.fill(pastIsfq, (short) 0);
        memW0 = 0;
        tiltCode = 0;
        firstFrame = true;
        WbFilters.initGpClip(gpClip);
        lGcThres = 0;
        if (all) {
            Arrays.fill(oldSpeech, (short) 0);
            Arrays.fill(oldWsp, (short) 0);
            Arrays.fill(memDecim2, (short) 0);
            Arrays.fill(memDecim, (short) 0);
            Arrays.fill(memSigIn, (short) 0);
            Arrays.fill(memLevinson, (short) 0);
            WbGainQuantizer.init(quaGain);
            Arrays.fill(hpWspMem, (short) 0);
            System.arraycopy(WbCodecTables.ISP_INIT, 0, ispold, 0, M);
            System.arraycopy(WbCodecTables.ISP_INIT, 0, ispoldQ, 0, M);
            memPreemph = 0;
            memWsp = 0;
            qOld = 15;
            qMax[0] = 15;
            qMax[1] = 15;
            oldWspMax = 0;
            oldWspShift = 0;
            oldT0Med = 40;
            olGain = 0;
            adaW = 0;
            olWeightFlag = 0;
            Arrays.fill(oldOlLag, (short) 40);
            Arrays.fill(oldHpWsp, (short) 0);
            Arrays.fill(memSynHf, (short) 0);
            Arrays.fill(memSynHi, (short) 0);
            Arrays.fill(memSynLo, (short) 0);
            Arrays.fill(memSigOut, (short) 0);
            Arrays.fill(memHf, (short) 0);
            Arrays.fill(memHp400, (short) 0);
            System.arraycopy(WbCodecTables.ISF_INIT, 0, isfold, 0, M);
            memDeemph = 0;
            seed2 = 21845;
            Arrays.fill(memHf2, (short) 0);
            gainAlpha = 32767;
            vadHist = 0;
            vad.reset();
            dtxState.reset(WbCodecTables.ISF_INIT);
        }
    }

    /**
     * Encodes one frame of {@link #speech16k} into {@link #params} ({@code coder}).
     *
     * @param mode the requested mode, 0 to 8
     * @return the coded mode: {@code mode}, or {@link #MRDTX} for comfort noise
     */
    private int coder(int mode) {
        var serSize = (int) WbBitOrder.UNPACKED_SIZE[mode];
        var codecMode = mode;
        var oldSp = workSpeech;
        final int newSpeech = L_TOTAL - L_FRAME - L_FILT;
        final int speech = L_TOTAL - L_FRAME - L_NEXT;
        final int window = L_TOTAL - L_WINDOW;
        var wspBuf = workWsp;
        final int wsp = PIT_MAX / OPL_DECIM;
        var excBuf = workExc;
        final int exc = PIT_MAX + L_INTERPOL;
        System.arraycopy(oldSpeech, 0, oldSp, 0, L_TOTAL - L_FRAME);
        System.arraycopy(oldWsp, 0, wspBuf, 0, PIT_MAX / OPL_DECIM);
        System.arraycopy(oldExc, 0, excBuf, 0, PIT_MAX + L_INTERPOL);

        filters.decim12k8(speech16k, 0, L_FRAME16K, oldSp, newSpeech, memDecim);
        System.arraycopy(memDecim, 0, code, 0, 2 * L_FILT16K);
        Arrays.fill(error, 0, L_FILT16K, (short) 0);
        filters.decim12k8(error, 0, L_FILT16K, oldSp, newSpeech + L_FRAME, code);
        WbFilters.hp50(oldSp, newSpeech, L_FRAME, memSigIn);
        System.arraycopy(memSigIn, 0, code, 0, 6);
        WbFilters.hp50(oldSp, newSpeech + L_FRAME, L_FILT, code);

        var mu = shr(PREEMPH_FAC, 1);
        var tmp32 = lMult(oldSp[newSpeech], 16384);
        tmp32 = lMsu(tmp32, memPreemph, mu);
        var lMax = lAbs(tmp32);
        for (var i = 1; i < L_FRAME + L_FILT; i++) {
            tmp32 = lMult(oldSp[newSpeech + i], 16384);
            tmp32 = lMsu(tmp32, oldSp[newSpeech + i - 1], mu);
            tmp32 = lAbs(tmp32);
            if (tmp32 > lMax) {
                lMax = tmp32;
            }
        }
        var tmp = extractH(lMax);
        short shift;
        if (tmp == 0) {
            shift = Q_MAX;
        } else {
            shift = (short) (normS(tmp) - 1);
            if (shift < 0) {
                shift = 0;
            }
            if (shift > Q_MAX) {
                shift = Q_MAX;
            }
        }
        var qNew = shift;
        if (qNew > qMax[0]) {
            qNew = qMax[0];
        }
        if (qNew > qMax[1]) {
            qNew = qMax[1];
        }
        var exp = sub(qNew, qOld);
        qOld = qNew;
        qMax[1] = qMax[0];
        qMax[0] = shift;
        tmp = oldSp[newSpeech + L_FRAME - 1];
        for (var i = L_FRAME + L_FILT - 1; i > 0; i--) {
            tmp32 = lMult(oldSp[newSpeech + i], 16384);
            tmp32 = lMsu(tmp32, oldSp[newSpeech + i - 1], mu);
            tmp32 = lShl(tmp32, qNew);
            oldSp[newSpeech + i] = round(tmp32);
        }
        tmp32 = lMult(oldSp[newSpeech], 16384);
        tmp32 = lMsu(tmp32, memPreemph, mu);
        tmp32 = lShl(tmp32, qNew);
        oldSp[newSpeech] = round(tmp32);
        memPreemph = tmp;

        WbFilters.scaleSig(oldSp, 0, L_TOTAL - L_FRAME - L_FILT, exp);
        WbFilters.scaleSig(excBuf, 0, PIT_MAX + L_INTERPOL, exp);
        WbFilters.scaleSig(memSyn, 0, M, exp);
        WbFilters.scaleSig(memDecim2, 0, 3, exp);
        memWsp = WbFilters.scaleOne(memWsp, exp);
        memW0 = WbFilters.scaleOne(memW0, exp);

        System.arraycopy(oldSp, newSpeech, vadBuffer, 0, L_FRAME);
        WbFilters.scaleSig(vadBuffer, 0, L_FRAME, 1 - qNew);
        var vadFlag = vad.vad(vadBuffer);
        if (vadFlag == 0) {
            vadHist = add(vadHist, 1);
        } else {
            vadHist = 0;
        }
        if (dtx) {
            mode = dtxState.txDtxHandler(vadFlag, mode);
            serSize = WbBitOrder.UNPACKED_SIZE[mode];
        }
        if (mode != MRDTX) {
            params.put(vadFlag, 1);
        }

        lpc.autocorr(oldSp, window, rH, rL);
        WbLpc.lagWindow(rH, rL);
        lpc.levinson(rH, rL, a, rc, memLevinson);
        lpc.azIsp(a, 0, ispnew, ispold);
        lpc.intIsp(ispold, ispnew, WbCodecTables.INTERPOL_FRAC, a);
        System.arraycopy(ispnew, 0, ispold, 0, M);
        WbLpc.ispIsf(ispnew, isf);
        WbFilters.gpClipTestIsf(serSize, isf, gpClip);

        for (int iSubfr = 0, pA = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR, pA += M + 1) {
            WbFilters.weightA(a, pA, ap, 0, GAMMA1, M);
            filters.residu(ap, 0, oldSp, speech + iSubfr, wspBuf, wsp + iSubfr, L_SUBFR);
        }
        memWsp = WbFilters.deemph2(wspBuf, wsp, TILT_FAC, L_FRAME, memWsp);
        var max = (short) 0;
        for (var i = 0; i < L_FRAME; i++) {
            tmp = absS(wspBuf[wsp + i]);
            if (tmp > max) {
                max = tmp;
            }
        }
        tmp = oldWspMax;
        if (max > tmp) {
            tmp = max;
        }
        oldWspMax = max;
        shift = (short) (normS(tmp) - 3);
        if (shift > 0) {
            shift = 0;
        }
        filters.lpDecim2(wspBuf, wsp, L_FRAME, memDecim2);
        WbFilters.scaleSig(wspBuf, wsp, L_FRAME / OPL_DECIM, shift);
        exp = (short) (exp + (shift - oldWspShift));
        oldWspShift = shift;
        WbFilters.scaleSig(wspBuf, 0, PIT_MAX / OPL_DECIM, exp);
        WbFilters.scaleSig(oldHpWsp, 0, PIT_MAX / OPL_DECIM, exp);
        WbFilters.scaleMemHpWsp(hpWspMem, exp);

        short tOp;
        if (serSize == WbBitOrder.UNPACKED_SIZE[0]) {
            tOp = WbPitch.pitchMedOl(wspBuf, wsp, L_FRAME / OPL_DECIM, this);
        } else {
            tOp = WbPitch.pitchMedOl(wspBuf, wsp, (L_FRAME / 2) / OPL_DECIM, this);
        }
        if (olGain > 19661) {
            oldT0Med = WbPitch.medOlag(tOp, oldOlLag);
            adaW = 32767;
        } else {
            adaW = mult(adaW, 29491);
        }
        olWeightFlag = (short) (adaW < 26214 ? 0 : 1);
        vad.toneDetection(olGain);
        tOp = (short) (tOp * OPL_DECIM);
        short tOp2;
        if (serSize != WbBitOrder.UNPACKED_SIZE[0]) {
            tOp2 = WbPitch.pitchMedOl(wspBuf, wsp + (L_FRAME / 2) / OPL_DECIM, (L_FRAME / 2) / OPL_DECIM, this);
            if (olGain > 19661) {
                oldT0Med = WbPitch.medOlag(tOp2, oldOlLag);
                adaW = 32767;
            } else {
                adaW = mult(adaW, 29491);
            }
            olWeightFlag = (short) (adaW < 26214 ? 0 : 1);
            vad.toneDetection(olGain);
            tOp2 = (short) (tOp2 * OPL_DECIM);
        } else {
            tOp2 = tOp;
        }

        if (mode == MRDTX) {
            filters.residu(a, 3 * (M + 1), oldSp, speech, excBuf, exc, L_FRAME);
            for (var i = 0; i < L_FRAME; i++) {
                exc2[i] = shr(excBuf[exc + i], qNew);
            }
            tmp32 = 0;
            for (var i = 0; i < L_FRAME; i++) {
                tmp32 = lMac(tmp32, exc2[i], exc2[i]);
            }
            tmp32 = lShr(tmp32, 1);
            dtxState.buffer(isf, tmp32, codecMode);
            dtxState.encode(isf, exc2, params, isfQuantizer);
            WbLpc.isfIsp(isf, ispnewQ);
            lpc.ispAz(ispnewQ, 0, aq, 0);
            for (var iSubfr = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR) {
                synthesis(aq, 0, exc2, iSubfr, 0, iSubfr * 5 / 4);
            }
            System.arraycopy(isf, 0, isfold, 0, M);
            resetEncoder(false);
            System.arraycopy(oldSp, L_FRAME, oldSpeech, 0, L_TOTAL - L_FRAME);
            System.arraycopy(wspBuf, L_FRAME / OPL_DECIM, oldWsp, 0, PIT_MAX / OPL_DECIM);
            return mode;
        }

        if (serSize <= WbBitOrder.UNPACKED_SIZE[0]) {
            isfQuantizer.qpisf36b(isf, isf, pastIsfq, indice, 4);
            params.put(indice[0], 8);
            params.put(indice[1], 8);
            params.put(indice[2], 7);
            params.put(indice[3], 7);
            params.put(indice[4], 6);
        } else {
            isfQuantizer.qpisf46b(isf, isf, pastIsfq, indice, 4);
            params.put(indice[0], 8);
            params.put(indice[1], 8);
            params.put(indice[2], 6);
            params.put(indice[3], 7);
            params.put(indice[4], 7);
            params.put(indice[5], 5);
            params.put(indice[6], 5);
        }
        tmp32 = 0;
        for (var i = 0; i < M - 1; i++) {
            tmp = sub(isf[i], isfold[i]);
            tmp32 = lMac(tmp32, tmp, tmp);
        }
        tmp = extractH(lShl(tmp32, 8));
        tmp = mult(tmp, 26214);
        tmp = sub(20480, tmp);
        var stabFac = shl(tmp, 1);
        if (stabFac < 0) {
            stabFac = 0;
        }
        System.arraycopy(isf, 0, isfold, 0, M);
        WbLpc.isfIsp(isf, ispnewQ);
        if (firstFrame) {
            firstFrame = false;
            System.arraycopy(ispnewQ, 0, ispoldQ, 0, M);
        }
        lpc.intIsp(ispoldQ, ispnewQ, WbCodecTables.INTERPOL_FRAC, aq);
        System.arraycopy(ispnewQ, 0, ispoldQ, 0, M);
        for (int iSubfr = 0, pAq = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR, pAq += M + 1) {
            filters.residu(aq, pAq, oldSp, speech + iSubfr, excBuf, exc + iSubfr, L_SUBFR);
        }
        if (vadFlag == 0) {
            for (var i = 0; i < L_FRAME; i++) {
                exc2[i] = shr(excBuf[exc + i], qNew);
            }
            tmp32 = 0;
            for (var i = 0; i < L_FRAME; i++) {
                tmp32 = lMac(tmp32, exc2[i], exc2[i]);
            }
            tmp32 = lShr(tmp32, 1);
            dtxState.buffer(isf, tmp32, codecMode);
        }

        var t0Min = tOp - 8;
        if (t0Min < PIT_MIN) {
            t0Min = PIT_MIN;
        }
        var t0Max = t0Min + 15;
        if (t0Max > PIT_MAX) {
            t0Max = PIT_MAX;
            t0Min = t0Max - 15;
        }

        for (int iSubfr = 0, pA = 0, pAq = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR, pA += M + 1, pAq += M + 1) {
            var pitFlag = iSubfr;
            if (iSubfr == 2 * L_SUBFR && serSize > WbBitOrder.UNPACKED_SIZE[0]) {
                pitFlag = 0;
                t0Min = tOp2 - 8;
                if (t0Min < PIT_MIN) {
                    t0Min = PIT_MIN;
                }
                t0Max = t0Min + 15;
                if (t0Max > PIT_MAX) {
                    t0Max = PIT_MAX;
                    t0Min = t0Max - 15;
                }
            }
            for (var i = 0; i < M; i++) {
                error[i] = sub(oldSp[speech + i + iSubfr - M], memSyn[i]);
            }
            filters.residu(aq, pAq, oldSp, speech + iSubfr, excBuf, exc + iSubfr, L_SUBFR);
            filters.synFilt(aq, pAq, excBuf, exc + iSubfr, error, M, L_SUBFR, error, 0, false);
            WbFilters.weightA(a, pA, ap, 0, GAMMA1, M);
            filters.residu(ap, 0, error, M, xn, 0, L_SUBFR);
            memW0 = WbFilters.deemph2(xn, 0, TILT_FAC, L_SUBFR, memW0);

            Arrays.fill(code, 0, M, (short) 0);
            System.arraycopy(xn, 0, code, M, L_SUBFR / 2);
            WbFilters.preemph2(code, M, TILT_FAC, L_SUBFR / 2, (short) 0);
            WbFilters.weightA(a, pA, ap, 0, GAMMA1, M);
            filters.synFilt(ap, 0, code, M, code, M, L_SUBFR / 2, code, 0, false);
            filters.residu(aq, pAq, code, M, cn, 0, L_SUBFR / 2);
            System.arraycopy(excBuf, exc + iSubfr + L_SUBFR / 2, cn, L_SUBFR / 2, L_SUBFR / 2);

            Arrays.fill(error, (short) 0);
            WbFilters.weightA(a, pA, error, M, GAMMA1, M);
            for (var i = 0; i < L_SUBFR; i++) {
                var acc = msuReverse(lMult(error[M + i], 16384), aq, pAq + 1, error, M + i - 1, M);
                var value = round(lShl(acc, 3));
                error[M + i] = value;
                h1[i] = value;
            }
            WbFilters.deemph2(h1, 0, TILT_FAC, L_SUBFR, (short) 0);
            System.arraycopy(h1, 0, h2, 0, L_SUBFR);
            WbFilters.scaleSig(h2, 0, L_SUBFR, -2);
            WbFilters.scaleSig(xn, 0, L_SUBFR, shift);
            WbFilters.scaleSig(h1, 0, L_SUBFR, 1 + shift);

            int t0;
            int t0Frac;
            if (serSize <= WbBitOrder.UNPACKED_SIZE[1]) {
                var result = pitch.pitchFr4(excBuf, exc + iSubfr, xn, h1, t0Min, t0Max, pitFlag, PIT_MIN, PIT_FR1_8B);
                t0 = result >> 16;
                t0Frac = result & 0xffff;
                if (pitFlag == 0) {
                    int index;
                    if (t0 < PIT_FR1_8B) {
                        index = (t0 << 1) + (t0Frac >> 1) - (PIT_MIN << 1);
                    } else {
                        index = (t0 - PIT_FR1_8B) + ((PIT_FR1_8B - PIT_MIN) * 2);
                    }
                    params.put(index, 8);
                    t0Min = t0 - 8;
                    if (t0Min < PIT_MIN) {
                        t0Min = PIT_MIN;
                    }
                    t0Max = t0Min + 15;
                    if (t0Max > PIT_MAX) {
                        t0Max = PIT_MAX;
                        t0Min = t0Max - 15;
                    }
                } else {
                    var i = t0 - t0Min;
                    params.put((i << 1) + (t0Frac >> 1), 5);
                }
            } else {
                var result = pitch.pitchFr4(excBuf, exc + iSubfr, xn, h1, t0Min, t0Max, pitFlag, PIT_FR2, PIT_FR1_9B);
                t0 = result >> 16;
                t0Frac = result & 0xffff;
                if (pitFlag == 0) {
                    int index;
                    if (t0 < PIT_FR2) {
                        index = ((t0 << 2) + t0Frac) - (PIT_MIN << 2);
                    } else if (t0 < PIT_FR1_9B) {
                        index = (((t0 << 1) + (t0Frac >> 1)) - (PIT_FR2 << 1)) + ((PIT_FR2 - PIT_MIN) << 2);
                    } else {
                        index = ((t0 - PIT_FR1_9B) + ((PIT_FR2 - PIT_MIN) << 2)) + ((PIT_FR1_9B - PIT_FR2) << 1);
                    }
                    params.put(index, 9);
                    t0Min = t0 - 8;
                    if (t0Min < PIT_MIN) {
                        t0Min = PIT_MIN;
                    }
                    t0Max = t0Min + 15;
                    if (t0Max > PIT_MAX) {
                        t0Max = PIT_MAX;
                        t0Min = t0Max - 15;
                    }
                } else {
                    var i = t0 - t0Min;
                    params.put((i << 2) + t0Frac, 6);
                }
            }

            var clipGain = WbFilters.gpClip(serSize, gpClip);
            WbPitchOps.predLt4(excBuf, exc + iSubfr, t0, t0Frac, L_SUBFR + 1, pitchSums);
            short gain1;
            if (serSize > WbBitOrder.UNPACKED_SIZE[1]) {
                filters.convolve(excBuf, exc + iSubfr, h1, y1);
                gain1 = WbPitch.gPitch(xn, y1, gCoeff, L_SUBFR);
                if (clipGain && gain1 > GP_CLIP) {
                    gain1 = GP_CLIP;
                }
                WbFilters.updtTar(xn, dn, y1, 0, gain1, L_SUBFR);
            } else {
                gain1 = 0;
            }
            var base = exc + iSubfr;
            for (var i = 0; i < L_SUBFR; i++) {
                var acc = lMult(5898, excBuf[base + i - 1]);
                acc = lMac(acc, 20972, excBuf[base + i]);
                acc = lMac(acc, 5898, excBuf[base + i + 1]);
                code[i] = round(acc);
            }
            filters.convolve(code, 0, h1, y2);
            var gain2 = WbPitch.gPitch(xn, y2, gCoeff2, L_SUBFR);
            if (clipGain && gain2 > GP_CLIP) {
                gain2 = GP_CLIP;
            }
            WbFilters.updtTar(xn, xn2, y2, 0, gain2, L_SUBFR);
            var select = 0;
            if (serSize > WbBitOrder.UNPACKED_SIZE[1]) {
                tmp32 = 0;
                for (var i = 0; i < L_SUBFR; i++) {
                    tmp32 = lMac(tmp32, dn[i], dn[i]);
                }
                for (var i = 0; i < L_SUBFR; i++) {
                    tmp32 = lMsu(tmp32, xn2[i], xn2[i]);
                }
                if (tmp32 <= 0) {
                    select = 1;
                }
                params.put(select, 1);
            }
            short gainPit;
            if (select == 0) {
                gainPit = gain2;
                System.arraycopy(code, 0, excBuf, exc + iSubfr, L_SUBFR);
                System.arraycopy(y2, 0, y1, 0, L_SUBFR);
                System.arraycopy(gCoeff2, 0, gCoeff, 0, 4);
            } else {
                gainPit = gain1;
                System.arraycopy(dn, 0, xn2, 0, L_SUBFR);
            }
            WbFilters.updtTar(cn, cn, excBuf, exc + iSubfr, gainPit, L_SUBFR);
            WbFilters.scaleSig(cn, 0, L_SUBFR, shift);

            WbFilters.preemph(h2, 0, tiltCode, L_SUBFR, (short) 0);
            if (t0Frac > 2) {
                t0 = t0 + 1;
            }
            WbFilters.pitShrp(h2, t0, PIT_SHARP, L_SUBFR);
            acelp.corHX(h2, xn2, dn);
            codebook(serSize);
            WbFilters.preemph(code, 0, tiltCode, L_SUBFR, (short) 0);
            WbFilters.pitShrp(code, t0, PIT_SHARP, L_SUBFR);

            var gainBits = serSize <= WbBitOrder.UNPACKED_SIZE[1] ? 6 : 7;
            var index = gainQuantizer.quantize(xn, y1, (short) (qNew + shift), y2, code, gCoeff, L_SUBFR, gainBits,
                    gainPit, clipGain, quaGain);
            params.put(index, gainBits);
            gainPit = gainQuantizer.gainPit();
            var lGainCode = gainQuantizer.gainCode();
            WbFilters.gpClipTestGainPit(serSize, gainPit, gpClip);
            tmp32 = lShl(lGainCode, qNew);
            var gainCode = round(tmp32);

            System.arraycopy(excBuf, exc + iSubfr, exc2, 0, L_SUBFR);
            WbFilters.scaleSig(exc2, 0, L_SUBFR, shift);
            var voiceFac = WbGainQuantizer.voiceFactor(exc2, shift, gainPit, code, gainCode, L_SUBFR);
            tiltCode = add(shr(voiceFac, 2), 8192);

            tmp32 = lMult(gainCode, y2[L_SUBFR - 1]);
            tmp32 = lShl(tmp32, add(5, shift));
            tmp32 = lNegate(tmp32);
            tmp32 = lMac(tmp32, xn[L_SUBFR - 1], 16384);
            tmp32 = lMsu(tmp32, y1[L_SUBFR - 1], gainPit);
            tmp32 = lShl(tmp32, sub(1, shift));
            memW0 = round(tmp32);

            if (serSize >= WbBitOrder.UNPACKED_SIZE[8]) {
                System.arraycopy(excBuf, exc + iSubfr, exc2, 0, L_SUBFR);
            }
            for (var i = 0; i < L_SUBFR; i++) {
                tmp32 = lMult(gainCode, code[i]);
                tmp32 = lShl(tmp32, 5);
                tmp32 = lMac(tmp32, excBuf[exc + i + iSubfr], gainPit);
                tmp32 = lShl(tmp32, 1);
                excBuf[exc + i + iSubfr] = round(tmp32);
            }
            filters.synFilt(aq, pAq, excBuf, exc + iSubfr, synth, 0, L_SUBFR, memSyn, 0, true);

            if (serSize >= WbBitOrder.UNPACKED_SIZE[8]) {
                var gainCodeHi = WbBasicOps.hi(lGainCode);
                var gainCodeLo = WbBasicOps.lo(lGainCode);
                tmp = sub(16384, shr(voiceFac, 1));
                var fac = mult(stabFac, tmp);
                tmp32 = lGainCode;
                if (lSub(tmp32, lGcThres) < 0) {
                    tmp32 = lAdd(tmp32, mpy3216(gainCodeHi, gainCodeLo, 6226));
                    if (lSub(tmp32, lGcThres) > 0) {
                        tmp32 = lGcThres;
                    }
                } else {
                    tmp32 = mpy3216(gainCodeHi, gainCodeLo, 27536);
                    if (lSub(tmp32, lGcThres) < 0) {
                        tmp32 = lGcThres;
                    }
                }
                lGcThres = tmp32;
                lGainCode = mpy3216(gainCodeHi, gainCodeLo, sub(32767, fac));
                gainCodeHi = WbBasicOps.hi(tmp32);
                gainCodeLo = WbBasicOps.lo(tmp32);
                lGainCode = lAdd(lGainCode, mpy3216(gainCodeHi, gainCodeLo, fac));

                tmp = add(shr(voiceFac, 3), 4096);
                tmp32 = code[0] << 16;
                tmp32 = lMsu(tmp32, code[1], tmp);
                code2[0] = round(tmp32);
                for (var i = 1; i < L_SUBFR - 1; i++) {
                    tmp32 = code[i] << 16;
                    tmp32 = lMsu(tmp32, code[i + 1], tmp);
                    tmp32 = lMsu(tmp32, code[i - 1], tmp);
                    code2[i] = round(tmp32);
                }
                tmp32 = code[L_SUBFR - 1] << 16;
                tmp32 = lMsu(tmp32, code[L_SUBFR - 2], tmp);
                code2[L_SUBFR - 1] = round(tmp32);

                gainCode = round(lShl(lGainCode, qNew));
                for (var i = 0; i < L_SUBFR; i++) {
                    tmp32 = lMult(code2[i], gainCode);
                    tmp32 = lShl(tmp32, 5);
                    tmp32 = lMac(tmp32, exc2[i], gainPit);
                    tmp32 = lShl(tmp32, 1);
                    exc2[i] = round(tmp32);
                }
                var corrGain = synthesis(aq, pAq, exc2, 0, qNew, iSubfr * 5 / 4);
                params.put(corrGain, 4);
            }
        }
        System.arraycopy(oldSp, L_FRAME, oldSpeech, 0, L_TOTAL - L_FRAME);
        System.arraycopy(wspBuf, L_FRAME / OPL_DECIM, oldWsp, 0, PIT_MAX / OPL_DECIM);
        System.arraycopy(excBuf, L_FRAME, oldExc, 0, PIT_MAX + L_INTERPOL);
        return mode;
    }

    /**
     * Searches the algebraic codebook of the mode and appends its indices to {@link #params}.
     *
     * @param serSize the number of bits of the frame
     */
    private void codebook(int serSize) {
        if (serSize <= WbBitOrder.UNPACKED_SIZE[0]) {
            acelp.acelp2t64(dn, cn, h2, code, y2, indice);
            params.put(indice[0], 12);
        } else if (serSize <= WbBitOrder.UNPACKED_SIZE[1]) {
            acelp.acelp4t64(dn, cn, h2, code, y2, 20, serSize, indice);
            for (var i = 0; i < 4; i++) {
                params.put(indice[i], 5);
            }
        } else if (serSize <= WbBitOrder.UNPACKED_SIZE[2]) {
            acelp.acelp4t64(dn, cn, h2, code, y2, 36, serSize, indice);
            for (var i = 0; i < 4; i++) {
                params.put(indice[i], 9);
            }
        } else if (serSize <= WbBitOrder.UNPACKED_SIZE[3]) {
            acelp.acelp4t64(dn, cn, h2, code, y2, 44, serSize, indice);
            params.put(indice[0], 13);
            params.put(indice[1], 13);
            params.put(indice[2], 9);
            params.put(indice[3], 9);
        } else if (serSize <= WbBitOrder.UNPACKED_SIZE[4]) {
            acelp.acelp4t64(dn, cn, h2, code, y2, 52, serSize, indice);
            for (var i = 0; i < 4; i++) {
                params.put(indice[i], 13);
            }
        } else if (serSize <= WbBitOrder.UNPACKED_SIZE[5]) {
            acelp.acelp4t64(dn, cn, h2, code, y2, 64, serSize, indice);
            for (var i = 0; i < 4; i++) {
                params.put(indice[i], 2);
            }
            for (var i = 4; i < 8; i++) {
                params.put(indice[i], 14);
            }
        } else if (serSize <= WbBitOrder.UNPACKED_SIZE[6]) {
            acelp.acelp4t64(dn, cn, h2, code, y2, 72, serSize, indice);
            params.put(indice[0], 10);
            params.put(indice[1], 10);
            params.put(indice[2], 2);
            params.put(indice[3], 2);
            params.put(indice[4], 10);
            params.put(indice[5], 10);
            params.put(indice[6], 14);
            params.put(indice[7], 14);
        } else {
            acelp.acelp4t64(dn, cn, h2, code, y2, 88, serSize, indice);
            for (var i = 0; i < 8; i++) {
                params.put(indice[i], 11);
            }
        }
    }

    /**
     * Synthesizes a subframe at 16 kHz with the high-band noise and returns the index of the
     * quantized high-band gain correction ({@code synthesis} of {@code voAMRWBEnc.c}).
     *
     * @param aqBuf the quantized LP coefficients
     * @param aqOff the offset of the subframe's coefficients
     * @param excBuf the excitation, scaled down in place
     * @param excOff the offset of the subframe's excitation
     * @param qNew the scaling of the excitation
     * @param speechOff the offset of the subframe in {@link #speech16k}
     * @return the high-band gain index, 0 to 15
     */
    private int synthesis(short[] aqBuf, int aqOff, short[] excBuf, int excOff, int qNew, int speechOff) {
        System.arraycopy(memSynHi, 0, synthHi, 0, M);
        System.arraycopy(memSynLo, 0, synthLo, 0, M);
        WbFilters.synFilt32(aqBuf, aqOff, excBuf, excOff, qNew, synthHi, synthLo, M, L_SUBFR);
        System.arraycopy(synthHi, L_SUBFR, memSynHi, 0, M);
        System.arraycopy(synthLo, L_SUBFR, memSynLo, 0, M);
        memDeemph = WbFilters.deemph32(synthHi, synthLo, M, synthHb, PREEMPH_FAC, L_SUBFR, memDeemph);
        WbFilters.hp50(synthHb, 0, L_SUBFR, memSigOut);
        System.arraycopy(speech16k, speechOff, hfSp, 0, L_SUBFR16K);
        for (var i = 0; i < L_SUBFR16K; i++) {
            seed2 = WbFilters.random(seed2);
            hf[i] = shr(seed2, 3);
        }
        WbFilters.scaleSig(excBuf, excOff, L_SUBFR, -3);
        qNew = sub(qNew, 3);
        var packed = dotProduct12(excBuf, excOff, excBuf, excOff, L_SUBFR);
        var ener = extractH(value(packed));
        var expEner = sub(exponent(packed), add(qNew, qNew));
        packed = dotProduct12(hf, 0, hf, 0, L_SUBFR16K);
        var tmp = extractH(value(packed));
        var exp = exponent(packed);
        if (tmp > ener) {
            tmp = shr(tmp, 1);
            exp = add(exp, 1);
        }
        var tmp32 = divS(tmp, ener) << 16;
        exp = sub(exp, expEner);
        packed = isqrtN(tmp32, exp);
        tmp32 = lShl(value(packed), add(exponent(packed), 1));
        tmp = extractH(tmp32);
        for (var i = 0; i < L_SUBFR16K; i++) {
            hf[i] = mult(hf[i], tmp);
        }
        WbFilters.hp400(synthHb, 0, L_SUBFR, memHp400);
        tmp32 = 1;
        for (var i = 0; i < L_SUBFR; i++) {
            tmp32 = lMac(tmp32, synthHb[i], synthHb[i]);
        }
        exp = normL(tmp32);
        ener = extractH(lShl(tmp32, exp));
        tmp32 = 1;
        for (var i = 1; i < L_SUBFR; i++) {
            tmp32 = lMac(tmp32, synthHb[i], synthHb[i - 1]);
        }
        tmp = extractH(lShl(tmp32, exp));
        var fac = tmp > 0 ? divS(tmp, ener) : 0;
        var gain1 = sub(32767, fac);
        var gain2 = mult(sub(32767, fac), 20480);
        gain2 = shl(gain2, 1);
        short weight1;
        short weight2;
        if (vadHist > 0) {
            weight1 = 0;
            weight2 = 32767;
        } else {
            weight1 = 32767;
            weight2 = 0;
        }
        tmp = mult(weight1, gain1);
        tmp = add(tmp, mult(weight2, gain2));
        if (tmp != 0) {
            tmp = add(tmp, 1);
        }
        var hpEstGain = tmp;
        if (hpEstGain < 3277) {
            hpEstGain = 3277;
        }
        WbFilters.weightA(aqBuf, aqOff, apHf, 0, 19661, M);
        filters.synFilt(apHf, 0, hf, 0, hf, 0, L_SUBFR16K, memSynHf, 0, true);
        filters.filt6k7k(hf, 0, L_SUBFR16K, memHf);
        filters.filt6k7k(hfSp, 0, L_SUBFR16K, memHf2);
        WbFilters.scaleSig(hfSp, 0, L_SUBFR16K, -1);
        packed = dotProduct12(hfSp, 0, hfSp, 0, L_SUBFR16K);
        ener = extractH(value(packed));
        expEner = exponent(packed);
        packed = dotProduct12(hf, 0, hf, 0, L_SUBFR16K);
        tmp = extractH(value(packed));
        exp = exponent(packed);
        if (tmp > ener) {
            tmp = shr(tmp, 1);
            exp = add(exp, 1);
        }
        tmp32 = divS(tmp, ener) << 16;
        exp = sub(exp, expEner);
        packed = isqrtN(tmp32, exp);
        tmp32 = lShl(value(packed), exponent(packed));
        var hpCalcGain = extractH(tmp32);
        tmp32 = lShl(lMult(dtxState.dtxHangoverCount, 4681), 15);
        gainAlpha = mult(gainAlpha, extractH(tmp32));
        if (dtxState.dtxHangoverCount > 6) {
            gainAlpha = 32767;
        }
        hpEstGain = shr(hpEstGain, 1);
        var hpCorrGain = add(mult(hpCalcGain, gainAlpha), mult(sub(32767, gainAlpha), hpEstGain));
        var distMin = (short) 32767;
        var hpGainInd = 0;
        for (var i = 0; i < 16; i++) {
            var dist = mult(sub(hpCorrGain, WbCodecTables.HP_GAIN[i]), sub(hpCorrGain, WbCodecTables.HP_GAIN[i]));
            if (distMin > dist) {
                distMin = dist;
                hpGainInd = i;
            }
        }
        return hpGainInd;
    }
}
