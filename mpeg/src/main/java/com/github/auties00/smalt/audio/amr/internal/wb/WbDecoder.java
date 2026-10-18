/* Ports opencore-amr 0.1.6 (pvamrwbdecoder.cpp, synthesis_amr_wb.cpp, homing_amr_wb_dec.cpp, mime_io.cpp,
   amrwb/wrapper.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import com.github.auties00.smalt.audio.amr.internal.AmrFrameDecoder;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbBitOrder;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbCodecTables;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbPitchOps;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.wb.WbMath.*;

/**
 * Decodes AMR-WB speech frames (3GPP TS 26.190) into 20 ms of 16 kHz, 16-bit samples.
 *
 * <p>The decoder accepts the frames of the RFC 4867 storage format through
 * {@link #decode(int, boolean, byte[], int, int, short[], int)} and the frames of the 3GPP
 * serial format of the TS 26.174 test sequences through
 * {@link #decodeSerial(int, int, short[], int, int, short[], int)}. Both apply the decoder homing
 * procedure, the comfort noise generation of the silence periods (TS 26.192) and the concealment
 * of bad and lost frames (TS 26.191), and round every output sample down to 14 bits. A frame
 * type the decoder cannot use (an undefined type, or a payload shorter than its type needs) is
 * decoded as a lost frame, so no input makes the decoder throw.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation aligns the arithmetic of every routine with TS 26.173 where opencore deviates from it
 * (missing saturations, masked shift counts, the concealment history update and the newer DTX rules; each routine
 * concerned documents its change), so it decodes every TS 26.174 decoder sequence bit for bit. The scratch memory keeps
 * opencore's single array and offsets.
 */
public final class WbDecoder implements AmrFrameDecoder {
    /**
     * The number of 16 kHz samples of a frame.
     */
    public static final int FRAME_SAMPLES = 320;

    /**
     * The number of 12.8 kHz samples of a frame.
     */
    private static final int L_FRAME = 256;

    /**
     * The number of 12.8 kHz samples of a subframe.
     */
    private static final int L_SUBFR = 64;

    /**
     * The number of 16 kHz samples of a subframe.
     */
    private static final int L_SUBFR16K = 80;

    /**
     * The LP order of the core.
     */
    private static final int M = WbLpc.M;

    /**
     * The LP order of the 6.60 kbit/s high band.
     */
    private static final int M16K = WbLpc.M16K;

    /**
     * The largest pitch lag.
     */
    private static final int PIT_MAX = 231;

    /**
     * The interpolation filter length plus one.
     */
    private static final int L_INTERPOL = 17;

    /**
     * The largest excitation scaling.
     */
    private static final int Q_MAX = 8;

    /**
     * The number of bits of the 6.60 kbit/s mode.
     */
    private static final int NBBITS_7K = 132;

    /**
     * The number of bits of the 8.85 kbit/s mode.
     */
    private static final int NBBITS_9K = 177;

    /**
     * The number of bits of the 12.65 kbit/s mode.
     */
    private static final int NBBITS_12K = 253;

    /**
     * The number of bits of the 14.25 kbit/s mode.
     */
    private static final int NBBITS_14K = 285;

    /**
     * The number of bits of the 15.85 kbit/s mode.
     */
    private static final int NBBITS_16K = 317;

    /**
     * The number of bits of the 18.25 kbit/s mode.
     */
    private static final int NBBITS_18K = 365;

    /**
     * The number of bits of the 19.85 kbit/s mode.
     */
    private static final int NBBITS_20K = 397;

    /**
     * The number of bits of the 23.85 kbit/s mode.
     */
    private static final int NBBITS_24K = 477;

    /**
     * The storage-format bit orders of the frame types 0 to 9.
     */
    private static final short[][] SORTING = {WbBitOrder.SORT_660, WbBitOrder.SORT_885, WbBitOrder.SORT_1265,
            WbBitOrder.SORT_1425, WbBitOrder.SORT_1585, WbBitOrder.SORT_1825, WbBitOrder.SORT_1985, WbBitOrder.SORT_2305,
            WbBitOrder.SORT_2385, WbBitOrder.SORT_SID};

    /**
     * The decoder homing frames of the modes 0 to 8.
     */
    private static final short[][] HOMING = {WbTables.DHF_7K, WbTables.DHF_9K, WbTables.DHF_12K,
            WbTables.DHF_14K, WbTables.DHF_16K, WbTables.DHF_18K, WbTables.DHF_20K, WbTables.DHF_23K,
            WbTables.DHF_24K};

    /**
     * The offset of the second part of the scratch memory, which holds the frame buffers.
     */
    private static final int SCRATCH2 = L_SUBFR + L_SUBFR16K + ((L_SUBFR + M + M16K + 1) << 1);

    /**
     * The offset of the past and current excitation in the scratch memory.
     */
    private static final int OLD_EXC = SCRATCH2;

    /**
     * The offset of the current frame's excitation in the scratch memory.
     */
    private static final int EXC = OLD_EXC + PIT_MAX + L_INTERPOL;

    /**
     * The offset of the LP coefficients of the four subframes in the scratch memory.
     */
    private static final int AQ = OLD_EXC + (L_FRAME + 1) + PIT_MAX + L_INTERPOL;

    /**
     * The offset of the current ISPs in the scratch memory.
     */
    private static final int ISP_NEW = AQ + 4 * (M + 1);

    /**
     * The offset of the current ISFs in the scratch memory.
     */
    private static final int ISF = ISP_NEW + M;

    /**
     * The offset of the previous ISFs in the scratch memory.
     */
    private static final int ISF_TMP = ISF + M;

    /**
     * The offset of the innovation in the scratch memory.
     */
    private static final int CODE = ISF_TMP + M;

    /**
     * The offset of the enhanced excitation of the low rates in the scratch memory.
     */
    private static final int EXCP = CODE + L_SUBFR;

    /**
     * The offset of the post-processed excitation in the scratch memory.
     */
    private static final int EXC2 = EXCP + L_SUBFR;

    /**
     * The offset of the high-band ISFs in the scratch memory.
     */
    private static final int HF_ISF = EXC2 + L_FRAME;

    /**
     * The size of the scratch memory.
     */
    private static final int SCRATCH_SIZE = SCRATCH2 + (2 * L_FRAME + 1) + PIT_MAX + L_INTERPOL + 4 * (M + 1)
            + 3 * (M + L_SUBFR) + M16K;

    /**
     * The offset of the high part of the synthesis in the scratch memory.
     */
    private static final int SYNTH_HI = 0;

    /**
     * The offset of the low part of the synthesis in the scratch memory.
     */
    private static final int SYNTH_LO = M + L_SUBFR;

    /**
     * The offset of the 12.8 kHz synthesis in the scratch memory.
     */
    private static final int SYNTH = SYNTH_LO + M + L_SUBFR;

    /**
     * The offset of the high-band signal in the scratch memory.
     */
    private static final int HF = SYNTH + L_SUBFR;

    /**
     * The offset of the weighted LP coefficients in the scratch memory.
     */
    private static final int AP = HF + L_SUBFR16K;

    /**
     * The offset of the high-band LP coefficients in the scratch memory.
     */
    private static final int HF_A = AP + M16K + 1;

    /**
     * The scratch memory shared by the routines of a frame.
     */
    private final short[] scratch = new short[SCRATCH_SIZE];

    /**
     * The integer accumulators of the exact filter paths.
     */
    private final int[] firAcc = new int[96];

    /**
     * The bits of the current frame in parameter order.
     */
    private final WbBits prms = new WbBits();

    /**
     * The LP parameter routines and their scratch.
     */
    private final WbLpc lpc = new WbLpc();

    /**
     * The comfort noise state.
     */
    private final WbDtx dtx = new WbDtx();

    /**
     * The past excitation.
     */
    private final short[] oldExc = new short[PIT_MAX + L_INTERPOL];

    /**
     * The ISPs of the previous frame.
     */
    private final short[] ispold = new short[M];

    /**
     * The ISFs of the previous frame.
     */
    private final short[] isfold = new short[M];

    /**
     * The last three good ISF vectors.
     */
    private final short[] isfBuf = new short[3 * M];

    /**
     * The past quantized ISF residual.
     */
    private final short[] pastIsfq = new short[M];

    /**
     * The tilt of the innovation pre-emphasis.
     */
    private short tiltCode;

    /**
     * The excitation scaling of the previous subframe.
     */
    private short qOld;

    /**
     * The headroom of the last four subframes.
     */
    private final short[] qsubfr = new short[4];

    /**
     * The smoothed code gain threshold of the noise enhancer.
     */
    private int lGcThres;

    /**
     * The memory of the high part of the synthesis filter.
     */
    private final short[] memSynHi = new short[M];

    /**
     * The memory of the low part of the synthesis filter.
     */
    private final short[] memSynLo = new short[M];

    /**
     * The memory of the de-emphasis filter.
     */
    private short memDeemph;

    /**
     * The memory of the 50 Hz high-pass filter.
     */
    private final short[] memSigOut = new short[6];

    /**
     * The memory of the resampler.
     */
    private final short[] memOversamp = new short[24];

    /**
     * The memory of the high-band synthesis filter.
     */
    private final short[] memSynHf = new short[M16K];

    /**
     * The memory of the band-pass filter.
     */
    private final short[] memHf = new short[30];

    /**
     * The memory of the 7 kHz low-pass filter.
     */
    private final short[] memHf3 = new short[30];

    /**
     * The seeds of the innovation noise, the high-band noise and the lag concealment.
     */
    private final short[] seeds = new short[3];

    /**
     * The previous pitch lag.
     */
    private short oldT0;

    /**
     * The history of the last five good pitch lags, newest first.
     */
    private final short[] lagHist = new short[5];

    /**
     * The gain decoder memory.
     */
    private final short[] decGain = new short[23];

    /**
     * The phase dispersion memory.
     */
    private final short[] dispMem = new short[8];

    /**
     * The memory of the 400 Hz high-pass filter.
     */
    private final short[] memHp400 = new short[6];

    /**
     * Whether the previous frame was bad.
     */
    private short prevBfi;

    /**
     * The error state, from 0 (good) to 6.
     */
    private short state;

    /**
     * Whether the next speech frame is the first after a reset.
     */
    private short firstFrame;

    /**
     * The number of consecutive good frames without voice activity.
     */
    private short vadHist;

    /**
     * The scratch codebook and ISF indices.
     */
    private final short[] ind = new short[8];

    /**
     * The scratch pulse positions.
     */
    private final short[] pos = new short[6];

    /**
     * The scratch sorted lag history of the lag concealment.
     */
    private final short[] lagHist2 = new short[5];

    /**
     * The scratch copy of the current ISPs passed to the interpolation.
     */
    private final short[] isfNewIsp = new short[M];

    /**
     * The scratch parameters of the homing test.
     */
    private final short[] homingParams = new short[32];

    /**
     * The mode of the last frame with data, used for the frames without.
     */
    private short modeOld;

    /**
     * The mode of the last frame, used by the storage-format SID and empty frames.
     */
    private short prevMode;

    /**
     * Whether the current frame is a homing frame.
     */
    private boolean resetFlag;

    /**
     * Whether the previous frame was a homing frame or the decoder was just reset.
     */
    private boolean resetFlagOld;

    /**
     * Creates a decoder in its home state.
     */
    public WbDecoder() {
        reset();
    }

    @Override
    public void reset() {
        dtx.reset(WbCodecTables.ISF_INIT);
        resetState(true);
        prms.clear();
        resetFlag = false;
        resetFlagOld = true;
        modeOld = 0;
        prevMode = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Frame types 0 to 8 are the speech modes from 6.60 to 23.85 kbit/s, 9 is a SID frame, 14 a lost speech frame
     * and 15 a frame without data; the reserved types 10 to 13 decode as frames without data. A clear quality bit marks
     * the frame bad: its speech parameters are concealed, and a bad SID frame is ignored. A payload shorter than its
     * frame type needs decodes as a lost frame.
     */
    @Override
    public void decode(int frameType, boolean quality, byte[] payload, int offset, int length, short[] out,
                       int outOffset) {
        frameType &= 0x0f;
        if (frameType <= 9 && length < WbBitOrder.PACKED_SIZE[frameType]) {
            frameType = 14;
        }
        int rxType;
        short mode;
        var bits = prms.bits;
        if (frameType <= 9) {
            var size = WbBitOrder.UNPACKED_SIZE[frameType];
            var sorting = SORTING[frameType];
            Arrays.fill(bits, 0, size, (short) 0);
            for (var k = 0; k < size; k++) {
                if ((payload[offset + (k >> 3)] & (0x80 >> (k & 7))) != 0) {
                    bits[sorting[k]] = WbBitOrder.BIT_1;
                }
            }
            if (frameType < 9) {
                rxType = quality ? WbDtx.RX_SPEECH_GOOD : WbDtx.RX_SPEECH_BAD;
                mode = (short) frameType;
            } else {
                if (quality) {
                    var sti = (payload[offset + 4] & (0x80 >> 3)) != 0;
                    rxType = sti ? WbDtx.RX_SID_UPDATE : WbDtx.RX_SID_FIRST;
                } else {
                    rxType = WbDtx.RX_SID_BAD;
                }
                mode = prevMode;
            }
        } else {
            rxType = frameType == 14 ? WbDtx.RX_SPEECH_LOST : WbDtx.RX_NO_DATA;
            mode = prevMode;
        }
        prevMode = mode;
        decodeFrame(rxType, mode, out, outOffset);
    }

    /**
     * Decodes one frame of the 3GPP serial format of the TS 26.174 test sequences.
     *
     * <p>The TX frame types are 0 (speech), 1 (first SID), 2 (SID update) and 3 (no data); any
     * other type, or a mode outside 0 to 8, decodes as a frame without data. Like the
     * TS 26.173 reader, the call overwrites only the first {@code bitCount} bits of the frame
     * buffer; the homing test of a short frame reads the bits of earlier frames beyond them.
     *
     * @param txType the TX frame type word
     * @param mode the codec mode, 0 (6.60 kbit/s) to 8 (23.85 kbit/s) word, 0 to 8
     * @param bits the bit words, {@code 0x007f} for a one and {@code 0xff81} for a zero
     * @param bitsOffset the offset of the bit words
     * @param bitCount the number of bit words, at most 477
     * @param out the output samples
     * @param outOffset the offset of the {@value #FRAME_SAMPLES} output samples
     */
    public void decodeSerial(int txType, int mode, short[] bits, int bitsOffset, int bitCount, short[] out,
                             int outOffset) {
        System.arraycopy(bits, bitsOffset, prms.bits, 0, Math.min(bitCount, WbBits.MAX_BITS));
        int rxType;
        if (mode < 0 || mode > 8) {
            rxType = WbDtx.RX_NO_DATA;
            mode = 0;
        } else {
            rxType = switch (txType) {
                case 0 -> WbDtx.RX_SPEECH_GOOD;
                case 1 -> WbDtx.RX_SID_FIRST;
                case 2 -> WbDtx.RX_SID_UPDATE;
                default -> WbDtx.RX_NO_DATA;
            };
        }
        decodeFrame(rxType, (short) mode, out, outOffset);
    }

    /**
     * Applies the homing procedure around the decoding of a frame whose bits are loaded.
     *
     * @param rxType the receive type
     * @param mode the codec mode, 0 (6.60 kbit/s) to 8 (23.85 kbit/s)
     * @param out the output samples
     * @param outOffset the offset of the output samples
     */
    private void decodeFrame(int rxType, short mode, short[] out, int outOffset) {
        if (rxType == WbDtx.RX_NO_DATA || rxType == WbDtx.RX_SPEECH_LOST) {
            mode = modeOld;
            resetFlag = false;
        } else {
            modeOld = mode;
            if (resetFlagOld) {
                resetFlag = homingTest(mode, WbTables.DHF_FIRST_BITS[mode]);
            }
        }
        if (resetFlag && resetFlagOld) {
            Arrays.fill(out, outOffset, outOffset + FRAME_SAMPLES, (short) 0x0008);
        } else {
            prms.position = 0;
            decodeSpeech(mode, out, outOffset, rxType);
        }
        for (var i = 0; i < FRAME_SAMPLES; i++) {
            out[outOffset + i] &= (short) 0xfffC;
        }
        if (!resetFlagOld) {
            resetFlag = homingTest(mode, WbBitOrder.UNPACKED_SIZE[mode]);
        }
        if (resetFlag) {
            resetState(true);
        }
        resetFlagOld = resetFlag;
    }

    /**
     * Tests whether the first bits of the loaded frame are those of the decoder homing frame of
     * a mode.
     *
     * @param mode the codec mode, 0 (6.60 kbit/s) to 8 (23.85 kbit/s), 0 to 8
     * @param nparms the number of bits compared
     * @return {@code true} for a homing frame
     */
    private boolean homingTest(int mode, int nparms) {
        var param = homingParams;
        var dhf = HOMING[mode];
        var bits = prms;
        bits.position = 0;
        int i = 0;
        int shift;
        if (mode != 8) {
            var j = 0;
            var tmp = nparms - 15;
            while (tmp > j) {
                param[i] = bits.parm(15);
                j += 15;
                i++;
            }
            tmp = nparms - j;
            param[i] = bits.parm(tmp);
            shift = 15 - tmp;
            param[i] = shl16(param[i], shift);
        } else {
            for (i = 0; i < 10; i++) {
                param[i] = bits.parm(15);
            }
            param[10] = (short) (bits.parm(15) & 0x61FF);
            for (i = 11; i < 17; i++) {
                param[i] = bits.parm(15);
            }
            param[17] = (short) (bits.parm(15) & 0xE0FF);
            for (i = 18; i < 24; i++) {
                param[i] = bits.parm(15);
            }
            param[24] = (short) (bits.parm(15) & 0x7F0F);
            for (i = 25; i < 31; i++) {
                param[i] = bits.parm(15);
            }
            param[31] = shl16(bits.parm(8), 7);
            shift = 0;
        }
        var count = i;
        var j = 0;
        for (i = 0; i < count; i++) {
            j = param[i] ^ dhf[i];
            if (j != 0) {
                break;
            }
        }
        var mask = (short) (0x7fff >> shift);
        mask = shl16(mask, shift);
        var last = (short) (param[i] ^ (dhf[i] & mask));
        return (short) (j | last) == 0;
    }

    /**
     * Resets the decoder state.
     *
     * @param all whether the filter memories and histories are reset too, or only the excitation
     * and ISF prediction state, as after a comfort noise frame
     */
    private void resetState(boolean all) {
        Arrays.fill(oldExc, (short) 0);
        Arrays.fill(pastIsfq, (short) 0);
        oldT0 = 64;
        firstFrame = 1;
        lGcThres = 0;
        tiltCode = 0;
        Arrays.fill(dispMem, (short) 0);
        qOld = Q_MAX;
        Arrays.fill(qsubfr, (short) Q_MAX);
        if (all) {
            WbCodebook.initGain(decGain);
            Arrays.fill(memOversamp, (short) 0);
            Arrays.fill(memHf, (short) 0);
            Arrays.fill(memHf3, (short) 0);
            Arrays.fill(memSigOut, (short) 0);
            Arrays.fill(memHp400, (short) 0);
            Arrays.fill(lagHist, (short) 64);
            System.arraycopy(WbCodecTables.ISP_INIT, 0, ispold, 0, M);
            System.arraycopy(WbCodecTables.ISF_INIT, 0, isfold, 0, M);
            for (var i = 0; i < 3; i++) {
                System.arraycopy(WbCodecTables.ISF_INIT, 0, isfBuf, i * M, M);
            }
            memDeemph = 0;
            seeds[0] = 21845;
            seeds[1] = 21845;
            seeds[2] = 21845;
            state = 0;
            prevBfi = 0;
            Arrays.fill(memSynHf, (short) 0);
            Arrays.fill(memSynHi, (short) 0);
            Arrays.fill(memSynLo, (short) 0);
            dtx.reset(WbCodecTables.ISF_INIT);
            vadHist = 0;
        }
    }

    /**
     * Decodes the parameters of a frame and synthesizes its samples.
     *
     * @param mode the codec mode, 0 (6.60 kbit/s) to 8 (23.85 kbit/s) of the frame, 0 to 8
     * @param synth16k the output samples
     * @param outOffset the offset of the output samples
     * @param frameType the receive type
     */
    private void decodeSpeech(int mode, short[] synth16k, int outOffset, int frameType) {
        var sm = scratch;
        var nbBits = WbBitOrder.UNPACKED_SIZE[mode];
        var newDtxState = dtx.rxHandler(frameType);
        if (newDtxState != WbDtx.SPEECH) {
            dtx.decode(sm, EXC2, newDtxState, sm, ISF, prms);
        }
        boolean bfi;
        boolean unusable;
        if (frameType == WbDtx.RX_SPEECH_BAD || frameType == WbDtx.RX_SPEECH_PROBABLY_DEGRADED) {
            bfi = true;
            unusable = false;
        } else if (frameType == WbDtx.RX_NO_DATA || frameType == WbDtx.RX_SPEECH_LOST) {
            bfi = true;
            unusable = true;
        } else {
            bfi = false;
            unusable = false;
        }
        if (bfi) {
            state++;
            if (state > 6) {
                state = 6;
            }
        } else {
            state >>= 1;
        }
        if (dtx.dtxGlobalState == WbDtx.DTX) {
            state = 5;
            prevBfi = 0;
        } else if (dtx.dtxGlobalState == WbDtx.DTX_MUTE) {
            state = 5;
            prevBfi = 1;
        }
        if (newDtxState == WbDtx.SPEECH) {
            var vadFlag = prms.parm1();
            if (!bfi) {
                if (vadFlag == 0) {
                    vadHist = add16(vadHist, 1);
                    dtx.dtxVadHist = add16(dtx.dtxVadHist, 1);
                } else {
                    vadHist = 0;
                    dtx.dtxVadHist = 0;
                }
            } else if (dtx.dtxVadHist > 0) {
                dtx.dtxVadHist = add16(dtx.dtxVadHist, 1);
            }
        }
        if (newDtxState != WbDtx.SPEECH) {
            WbLpc.isfToIsp(sm, ISF, sm, ISP_NEW, M);
            lpc.ispToAz(sm, ISP_NEW, sm, AQ, M, true);
            System.arraycopy(isfold, 0, sm, ISF_TMP, M);
            for (var iSubfr = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR) {
                interpolateHfIsf(iSubfr >> 6);
                synthesis(AQ, EXC2 + iSubfr, 0, synth16k, outOffset + iSubfr * 5 / 4, 1, nbBits, newDtxState, bfi);
            }
            resetState(false);
            System.arraycopy(sm, ISF, isfold, 0, M);
            prevBfi = (short) (bfi ? 1 : 0);
            dtx.dtxGlobalState = (short) newDtxState;
            return;
        }
        System.arraycopy(oldExc, 0, sm, OLD_EXC, PIT_MAX + L_INTERPOL);
        var ind = this.ind;
        if (nbBits > NBBITS_7K) {
            ind[0] = prms.parm(8);
            ind[1] = prms.parm(8);
            ind[2] = prms.parm(6);
            ind[3] = prms.parm(7);
            ind[4] = prms.parm(7);
            ind[5] = prms.parm(5);
            ind[6] = prms.parm(5);
            lpc.dpisf46b(ind, sm, ISF, pastIsfq, isfold, isfBuf, bfi);
        } else {
            ind[0] = prms.parm(8);
            ind[1] = prms.parm(8);
            ind[2] = prms.parm(14);
            ind[3] = (short) (ind[2] & 0x007F);
            ind[2] = (short) (ind[2] >> 7);
            ind[4] = prms.parm(6);
            lpc.dpisf36b(ind, sm, ISF, pastIsfq, isfold, isfBuf, bfi);
        }
        WbLpc.isfToIsp(sm, ISF, sm, ISP_NEW, M);
        if (firstFrame != 0) {
            firstFrame = 0;
            System.arraycopy(sm, ISP_NEW, ispold, 0, M);
        }
        System.arraycopy(sm, ISP_NEW, isfNewIsp, 0, M);
        lpc.interpolateIsp(ispold, isfNewIsp, WbCodecTables.INTERPOL_FRAC, sm, AQ);
        System.arraycopy(sm, ISP_NEW, ispold, 0, M);
        var sum = 0;
        for (var i = 0; i < M - 1; i++) {
            var tmp = sub16(sm[ISF + i], isfold[i]);
            sum = mac(sum, tmp, tmp);
        }
        var tmp = (short) (shl32(sum, 8) >> 16);
        tmp = mult(tmp, 26214);
        tmp = (short) (20480 - tmp);
        var stabFac = shl16(tmp, 1);
        if (stabFac < 0) {
            stabFac = 0;
        }
        System.arraycopy(isfold, 0, sm, ISF_TMP, M);
        System.arraycopy(sm, ISF, isfold, 0, M);
        var pAq = AQ;
        short t0 = 0;
        short t0Frac = 0;
        short t0Min = 0;
        short t0Max;
        short qNew = 0;
        for (var iSubfr = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR) {
            var pitFlag = iSubfr;
            if (iSubfr == 2 * L_SUBFR && nbBits > NBBITS_7K) {
                pitFlag = 0;
            }
            if (pitFlag == 0) {
                if (nbBits <= NBBITS_9K) {
                    var index = prms.parm(8);
                    if (index < (92 - 34) * 2) {
                        t0 = (short) (34 + (index >> 1));
                        t0Frac = sub16(index, shl16(sub16(t0, 34), 1));
                        t0Frac = shl16(t0Frac, 1);
                    } else {
                        t0 = add16(index, 92 - ((92 - 34) * 2));
                        t0Frac = 0;
                    }
                } else {
                    var index = prms.parm(9);
                    if (index < (128 - 34) * 4) {
                        t0 = (short) (34 + (index >> 2));
                        t0Frac = sub16(index, shl16(sub16(t0, 34), 2));
                    } else if (index < ((128 - 34) << 2) + ((160 - 128) << 1)) {
                        index -= (128 - 34) << 2;
                        t0 = (short) (128 + (index >> 1));
                        t0Frac = sub16(index, shl16(sub16(t0, 128), 1));
                        t0Frac = shl16(t0Frac, 1);
                    } else {
                        t0 = add16(index, 160 - ((128 - 34) * 4) - ((160 - 128) * 2));
                        t0Frac = 0;
                    }
                }
                t0Min = (short) (t0 - 8);
                if (t0Min < 34) {
                    t0Min = 34;
                }
                t0Max = (short) (t0Min + 15);
                if (t0Max > PIT_MAX) {
                    t0Max = PIT_MAX;
                    t0Min = PIT_MAX - 15;
                }
            } else if (nbBits <= NBBITS_9K) {
                var index = prms.parm(5);
                t0 = (short) (t0Min + (index >> 1));
                t0Frac = sub16(index, shl16((short) (t0 - t0Min), 1));
                t0Frac = shl16(t0Frac, 1);
            } else {
                var index = prms.parm(6);
                t0 = (short) (t0Min + (index >> 2));
                t0Frac = sub16(index, shl16((short) (t0 - t0Min), 2));
            }
            if (bfi) {
                t0 = WbCodebook.lagConceal(decGain, 17, lagHist, t0, oldT0, seeds, 2, unusable, lagHist2);
                t0Frac = 0;
            }
            WbPitchOps.predLt4(sm, EXC + iSubfr, t0, t0Frac, L_SUBFR + 1, firAcc);
            int select;
            if (unusable) {
                select = 1;
            } else if (nbBits <= NBBITS_9K) {
                select = 0;
            } else {
                select = prms.parm1();
            }
            if (select == 0) {
                for (var i = 0; i < L_SUBFR; i++) {
                    var smooth = mul(5898, sm[EXC + i - 1 + iSubfr]);
                    smooth = mac(smooth, 20972, sm[EXC + i + iSubfr]);
                    smooth = mac(smooth, 5898, sm[EXC + i + 1 + iSubfr]);
                    sm[CODE + i] = round(smooth);
                }
                System.arraycopy(sm, CODE, sm, EXC + iSubfr, L_SUBFR);
            }
            readInnovation(unusable, nbBits);
            WbFilters.preemph(sm, CODE, tiltCode, L_SUBFR);
            tmp = t0;
            if (t0Frac > 2) {
                tmp++;
            }
            WbFilters.pitShrp(sm, CODE, tmp, 27853, L_SUBFR);
            long gains;
            if (nbBits <= NBBITS_9K) {
                var index = prms.parm(6);
                gains = WbCodebook.decGain(index, 6, sm, CODE, L_SUBFR, bfi, prevBfi, state, unusable, vadHist,
                        decGain);
            } else {
                var index = prms.parm(7);
                gains = WbCodebook.decGain(index, 7, sm, CODE, L_SUBFR, bfi, prevBfi, state, unusable, vadHist,
                        decGain);
            }
            var gainPit = (short) exponent(gains);
            var lGainCode = value(gains);
            tmp = qsubfr[0];
            for (var i = 1; i < 4; i++) {
                if (qsubfr[i] < tmp) {
                    tmp = qsubfr[i];
                }
            }
            if (tmp > Q_MAX) {
                tmp = Q_MAX;
            }
            qNew = 0;
            var scaled = lGainCode;
            while (scaled < 0x08000000 && qNew < tmp) {
                scaled <<= 1;
                qNew += 1;
            }
            var gainCode = round(scaled);
            WbFilters.scaleSignal(sm, EXC + iSubfr - (PIT_MAX + L_INTERPOL), PIT_MAX + L_INTERPOL + L_SUBFR,
                    qNew - qOld);
            qOld = qNew;
            if (!bfi) {
                for (var i = 4; i > 0; i--) {
                    lagHist[i] = lagHist[i - 1];
                }
                lagHist[0] = t0;
                oldT0 = t0;
            }
            System.arraycopy(sm, EXC + iSubfr, sm, EXC2, L_SUBFR);
            WbFilters.scaleSignal(sm, EXC2, L_SUBFR, -3);
            short pitSharp;
            if (nbBits <= NBBITS_9K) {
                pitSharp = shl16(gainPit, 1);
                if (pitSharp > 16384) {
                    for (var i = 0; i < L_SUBFR; i++) {
                        var t = mult(sm[EXC2 + i], pitSharp);
                        var product = mul(t, gainPit);
                        product >>= 1;
                        sm[EXCP + i] = round(product);
                    }
                }
            } else {
                pitSharp = 0;
            }
            var voiceFac = WbFilters.voiceFactor(sm, EXC2, -3, gainPit, sm, CODE, gainCode, L_SUBFR);
            tiltCode = (short) ((voiceFac >> 2) + 8192);
            System.arraycopy(sm, EXC + iSubfr, sm, EXC2, L_SUBFR);
            var max = 1;
            for (var i = 0; i < L_SUBFR; i++) {
                var total = mul(sm[CODE + i], gainCode);
                total = shl32(total, 5);
                total = mac(total, sm[EXC + i + iSubfr], gainPit);
                total = shl32(total, 1);
                var t = round(total);
                sm[EXC + i + iSubfr] = t;
                t = (short) (t - (t < 0 ? 1 : 0));
                max |= t ^ (t >> 15);
            }
            tmp = (short) (add16(normS(max), qNew) - 1);
            qsubfr[3] = qsubfr[2];
            qsubfr[2] = qsubfr[1];
            qsubfr[1] = qsubfr[0];
            qsubfr[0] = tmp;
            var dispersionMode = nbBits <= NBBITS_7K ? 0 : nbBits <= NBBITS_9K ? 1 : 2;
            WbFilters.phaseDispersion((short) (lGainCode >> 16), gainPit, sm, CODE, dispersionMode, dispMem, sm, 0);
            tmp = sub16(16384, voiceFac >> 1);
            var fac = mult(stabFac, tmp);
            var threshold = lGainCode;
            if (threshold < lGcThres) {
                threshold = add32(threshold, mulDpf(lGainCode, 6226));
                if (threshold > lGcThres) {
                    threshold = lGcThres;
                }
            } else {
                threshold = mulDpf(lGainCode, 27536);
                if (threshold < lGcThres) {
                    threshold = lGcThres;
                }
            }
            lGcThres = threshold;
            lGainCode = mulDpf(lGainCode, sub16(32767, fac));
            lGainCode = add32(lGainCode, mulDpf(threshold, fac));
            tmp = (short) ((voiceFac >> 3) + 4096);
            gainCode = round(shl32(lGainCode, qNew));
            enhanceExcitation(tmp, gainCode, gainPit);
            if (nbBits <= NBBITS_9K && pitSharp > 16384) {
                for (var i = 0; i < L_SUBFR; i++) {
                    sm[EXCP + i] = add16(sm[EXCP + i], sm[EXC2 + i]);
                }
                WbFilters.agc2(sm, EXC2, sm, EXCP, L_SUBFR);
                System.arraycopy(sm, EXCP, sm, EXC2, L_SUBFR);
            }
            if (nbBits <= NBBITS_7K) {
                interpolateHfIsf(iSubfr >> 6);
            } else {
                Arrays.fill(memSynHf, 0, M16K - M, (short) 0);
            }
            var corrGain = nbBits >= NBBITS_24K ? prms.parm(4) : 0;
            synthesis(pAq, EXC2, qNew, synth16k, outOffset + iSubfr + (iSubfr >> 2), corrGain, nbBits, newDtxState,
                    bfi);
            pAq += M + 1;
        }
        System.arraycopy(sm, OLD_EXC + L_FRAME, oldExc, 0, PIT_MAX + L_INTERPOL);
        WbFilters.scaleSignal(sm, EXC, L_FRAME, -qNew);
        dtx.activityUpdate(sm, ISF, sm, EXC);
        dtx.dtxGlobalState = (short) newDtxState;
        prevBfi = (short) (bfi ? 1 : 0);
    }

    /**
     * Multiplies a 32-bit value, split into double-precision parts, by a 16-bit value, as the
     * noise enhancer does with the code gain.
     *
     * @param value the 32-bit value
     * @param n the multiplier
     * @return the doubled product
     */
    private static int mulDpf(int value, int n) {
        var dpf = toDpf(value);
        return mul32By16((short) (dpf >> 16), (short) dpf, n);
    }

    /**
     * Reads or generates the innovation of a subframe into the scratch memory.
     *
     * @param unusable whether the frame carries no usable data, in which case the innovation is
     * noise
     * @param nbBits the number of bits of the mode
     */
    private void readInnovation(boolean unusable, int nbBits) {
        var sm = scratch;
        var ind = this.ind;
        if (unusable) {
            for (var i = 0; i < L_SUBFR; i++) {
                sm[CODE + i] = (short) (noise(seeds, 0) >> 3);
            }
        } else if (nbBits <= NBBITS_7K) {
            WbCodebook.decAcelp2p(prms.parm(12), sm, CODE);
        } else if (nbBits <= NBBITS_9K) {
            for (var i = 0; i < 4; i++) {
                ind[i] = prms.parm(5);
            }
            WbCodebook.decAcelp4p(ind, 20, sm, CODE, pos);
        } else if (nbBits <= NBBITS_12K) {
            for (var i = 0; i < 4; i++) {
                ind[i] = prms.parm(9);
            }
            WbCodebook.decAcelp4p(ind, 36, sm, CODE, pos);
        } else if (nbBits <= NBBITS_14K) {
            ind[0] = prms.parm(13);
            ind[1] = prms.parm(13);
            ind[2] = prms.parm(9);
            ind[3] = prms.parm(9);
            WbCodebook.decAcelp4p(ind, 44, sm, CODE, pos);
        } else if (nbBits <= NBBITS_16K) {
            for (var i = 0; i < 4; i++) {
                ind[i] = prms.parm(13);
            }
            WbCodebook.decAcelp4p(ind, 52, sm, CODE, pos);
        } else if (nbBits <= NBBITS_18K) {
            for (var i = 0; i < 4; i++) {
                ind[i] = prms.parm(2);
            }
            for (var i = 4; i < 8; i++) {
                ind[i] = prms.parm(14);
            }
            WbCodebook.decAcelp4p(ind, 64, sm, CODE, pos);
        } else if (nbBits <= NBBITS_20K) {
            ind[0] = prms.parm(10);
            ind[1] = prms.parm(10);
            ind[2] = prms.parm(2);
            ind[3] = prms.parm(2);
            ind[4] = prms.parm(10);
            ind[5] = prms.parm(10);
            ind[6] = prms.parm(14);
            ind[7] = prms.parm(14);
            WbCodebook.decAcelp4p(ind, 72, sm, CODE, pos);
        } else {
            for (var i = 0; i < 8; i++) {
                ind[i] = prms.parm(11);
            }
            WbCodebook.decAcelp4p(ind, 88, sm, CODE, pos);
        }
    }

    /**
     * Builds the post-processed excitation from the innovation, smoothed by the noise enhancer,
     * and the adaptive excitation.
     *
     * @implNote This implementation subtracts the two neighbors one at a time with saturation,
     * as TS 26.173 specifies; opencore subtracts their wrapped 16-bit sum.
     *
     * @param tilt the innovation smoothing factor
     * @param gainCode the enhanced innovation gain
     * @param gainPit the pitch gain
     */
    private void enhanceExcitation(short tilt, short gainCode, short gainPit) {
        var sm = scratch;
        for (var i = 0; i < L_SUBFR; i++) {
            var total = sm[CODE + i] << 16;
            if (i < L_SUBFR - 1) {
                total = msu(total, sm[CODE + i + 1], tilt);
            }
            if (i > 0) {
                total = msu(total, sm[CODE + i - 1], tilt);
            }
            total = mul(round(total), gainCode);
            total = shl32(total, 5);
            total = mac(total, sm[EXC2 + i], gainPit);
            total = shl32(total, 1);
            sm[EXC2 + i] = round(total);
        }
    }

    /**
     * Interpolates the high-band ISFs of a subframe between the previous and the current ISFs.
     *
     * @param subframe the subframe, 0 to 3
     */
    private void interpolateHfIsf(int subframe) {
        var sm = scratch;
        var frac = WbCodecTables.INTERPOL_FRAC[subframe];
        for (var i = 0; i < M; i++) {
            var sum = mul(sm[ISF_TMP + i], sub16(32767, frac));
            sum = mac(sum, sm[ISF + i], frac);
            sm[HF_ISF + i] = round(sum);
        }
    }

    /**
     * Synthesizes the 80 output samples of a subframe: the 12.8 kHz synthesis resampled to
     * 16 kHz plus the 6.4 to 7 kHz high band generated from noise.
     *
     * @implNote This implementation scales the excitation, the energies and the 23.85 kbit/s
     * high-band gain with the saturating TS 26.173 operators; opencore saturates the excitation
     * scaling before its shift and wraps the doubled high-band gain.
     *
     * @param aqOff the offset of the subframe's LP coefficients in the scratch memory
     * @param excOff the offset of the excitation in the scratch memory
     * @param qNew the scaling of the excitation
     * @param synth16k the output samples
     * @param sOff the offset of the subframe's output samples
     * @param corrGain the high-band gain index of the 23.85 kbit/s mode
     * @param nbBits the number of bits of the mode
     * @param newDtxState the DTX state of the frame
     * @param bfi whether the frame is bad
     */
    private void synthesis(int aqOff, int excOff, int qNew, short[] synth16k, int sOff, int corrGain, int nbBits,
                           int newDtxState, boolean bfi) {
        var sm = scratch;
        System.arraycopy(memSynHi, 0, sm, SYNTH_HI, M);
        System.arraycopy(memSynLo, 0, sm, SYNTH_LO, M);
        WbFilters.synFilt32(sm, aqOff, M, sm, excOff, qNew, sm, SYNTH_HI + M, SYNTH_LO + M, L_SUBFR);
        System.arraycopy(sm, SYNTH_HI + L_SUBFR, memSynHi, 0, M);
        System.arraycopy(sm, SYNTH_LO + L_SUBFR, memSynLo, 0, M);
        memDeemph = WbFilters.deemphasis32(sm, SYNTH_HI + M, SYNTH_LO + M, sm, SYNTH, 22282, L_SUBFR, memDeemph);
        WbFilters.highpass50(sm, SYNTH, L_SUBFR, memSigOut);
        WbFilters.oversample(sm, SYNTH, L_SUBFR, synth16k, sOff, memOversamp, sm, 0);
        for (var i = 0; i < L_SUBFR16K; i++) {
            sm[HF + i] = (short) (noise(seeds, 1) >> 3);
        }
        WbFilters.scaleSignal(sm, excOff, L_SUBFR, -3);
        qNew -= 3;
        var packed = dotProduct12(sm, excOff, sm, excOff, L_SUBFR);
        var ener = (short) (value(packed) >> 16);
        var expEner = sub16(exponent(packed), add16(qNew, qNew));
        packed = dotProduct12(sm, HF, sm, HF, L_SUBFR16K);
        var tmp = (short) (value(packed) >> 16);
        var exp = (short) exponent(packed);
        if (tmp > ener) {
            tmp = shr16(tmp, 1);
            exp = add16(exp, 1);
        }
        var lTmp = div(tmp, ener) << 16;
        exp = sub16(exp, expEner);
        packed = oneOvSqrtNorm(lTmp, exp);
        lTmp = shl32(value(packed), add16(exponent(packed), 1));
        tmp = (short) (lTmp >> 16);
        for (var i = 0; i < L_SUBFR16K; i++) {
            sm[HF + i] = mult(sm[HF + i], tmp);
        }
        WbFilters.highpass400(sm, SYNTH, L_SUBFR, memHp400);
        var energyWide = 1L + ((long) sm[SYNTH] * sm[SYNTH] << 1);
        var correlationWide = 1L;
        var range = (energyWide + (1L << 31)) | (correlationWide + (1L << 31));
        for (var i = 1; i < L_SUBFR; i++) {
            energyWide += (long) sm[SYNTH + i] * sm[SYNTH + i] << 1;
            correlationWide += (long) sm[SYNTH + i] * sm[SYNTH + i - 1] << 1;
            range |= (energyWide + (1L << 31)) | (correlationWide + (1L << 31));
        }
        int energy;
        int correlation;
        if (range >>> 32 == 0) {
            energy = (int) energyWide;
            correlation = (int) correlationWide;
        } else {
            energy = 1;
            correlation = 1;
            energy = mac(energy, sm[SYNTH], sm[SYNTH]);
            for (var i = 1; i < L_SUBFR; i++) {
                energy = mac(energy, sm[SYNTH + i], sm[SYNTH + i]);
                correlation = mac(correlation, sm[SYNTH + i], sm[SYNTH + i - 1]);
            }
        }
        exp = (short) normalize(energy);
        ener = (short) (shl32(energy, exp) >> 16);
        tmp = (short) (shl32(correlation, exp) >> 16);
        var fac = tmp > 0 ? div(tmp, ener) : 0;
        var gain1 = (short) (32767 - fac);
        var gain2 = mult(gain1, 20480);
        gain2 = shl16(gain2, 1);
        tmp = (short) (vadHist > 0 ? gain2 - 1 : gain1 - 1);
        if (tmp != 0) {
            tmp++;
        }
        if (tmp < 3277) {
            tmp = 3277;
        }
        if (nbBits >= NBBITS_24K && !bfi) {
            var hfCorrGain = WbCodecTables.HP_GAIN[corrGain & 0x0f];
            for (var i = 0; i < L_SUBFR16K; i++) {
                sm[HF + i] = shl16(mult(sm[HF + i], hfCorrGain), 1);
            }
        } else {
            for (var i = 0; i < L_SUBFR16K; i++) {
                sm[HF + i] = mult(sm[HF + i], tmp);
            }
        }
        if (nbBits <= NBBITS_7K && newDtxState == WbDtx.SPEECH) {
            lpc.isfExtrapolation(sm, HF_ISF);
            lpc.ispToAz(sm, HF_ISF, sm, HF_A, M16K, false);
            WbFilters.weightLpc(sm, HF_A, sm, AP, 29491, M16K);
            WbFilters.wbSynFilt(sm, AP, M16K, sm, HF, sm, HF, L_SUBFR16K, memSynHf, 0, sm, 0);
        } else {
            WbFilters.weightLpc(sm, aqOff, sm, AP, 19661, M);
            WbFilters.wbSynFilt(sm, AP, M, sm, HF, sm, HF, L_SUBFR16K, memSynHf, M16K - M, sm, 0);
        }
        WbFilters.bandPass6k7k(sm, HF, L_SUBFR16K, memHf, sm, 0, firAcc);
        if (nbBits >= NBBITS_24K) {
            WbFilters.lowPass7k(sm, HF, L_SUBFR16K, memHf3, sm, 0, firAcc);
        }
        for (var i = 0; i < L_SUBFR16K; i++) {
            synth16k[sOff + i] = add16(synth16k[sOff + i], sm[HF + i]);
        }
    }
}
