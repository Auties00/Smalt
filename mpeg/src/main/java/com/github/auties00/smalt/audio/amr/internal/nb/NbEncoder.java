/* Ports opencore-amr 0.1.6 (amrencode.cpp, sp_enc.cpp, sid_sync.cpp, prm2bits.cpp, ets_to_wmf.cpp). Copyright
   PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import com.github.auties00.smalt.audio.amr.internal.AmrFrameEncoder;
import com.github.auties00.smalt.internal.codec.AmrConfigs;

import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.AMRSID_TXMODE_BIT_OFFSET;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.AMRSID_TXTYPE_BIT_OFFSET;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.EHF_MASK;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MAX_PRM_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MAX_SERIAL_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NUM_AMRSID_TXMODE_BITS;

/**
 * Encodes AMR-NB frames, bit-exactly with the 3GPP TS 26.073 fixed-point reference encoder, with
 * optional voice activity detection (option 1) and discontinuous transmission.
 *
 * <p>{@link #encode(int, short[], int, byte[], int)} writes the RFC 4867 storage format: a header
 * byte with the frame type and the quality bit set, then the speech bits in their sensitivity
 * order. {@link #encodeEts(int, short[], int, short[])} writes the 3GPP serial (ETS) frame the
 * conformance sequences use: the TX frame type, one word per bit in parameter order, and the mode.
 *
 * <p>Both paths apply the encoder homing procedure of TS 26.073: a frame whose 160 samples all
 * equal the homing pattern {@code 0x0008} is coded normally and then resets the encoder.
 *
 * <p>An instance is not thread-safe; the input array is never modified.
 *
 * @implNote This implementation adds the encoder homing of TS 26.073, which opencore-amr leaves out.
 */
public final class NbEncoder implements AmrFrameEncoder {
    /**
     * The number of frames between two comfort noise updates.
     */
    private static final short SID_UPDATE_RATE = 8;

    /**
     * The encoder of the frame parameters.
     */
    private final EncoderCore core;

    /**
     * The working copy of the input frame.
     */
    private final short[] speech = new short[L_FRAME];

    /**
     * The frame parameters.
     */
    private final short[] prm = new short[MAX_PRM_SIZE];

    /**
     * The frame bits in parameter order.
     */
    private final short[] bits = new short[MAX_SERIAL_SIZE];

    /**
     * The countdown to the next comfort noise update.
     */
    private short sidUpdateCounter;

    /**
     * The number of comfort noise updates owed after a handover.
     */
    private short sidHandoverDebt;

    /**
     * The TX frame type of the previous frame.
     */
    private int prevFt;

    /**
     * Creates an encoder.
     *
     * @param dtx whether voice activity detection and discontinuous transmission are on
     */
    public NbEncoder(boolean dtx) {
        this.core = new EncoderCore(dtx);
        resetSidSync();
    }

    @Override
    public void reset() {
        core.reset();
        resetSidSync();
    }

    /**
     * Resets the comfort noise update scheduling ({@code sid_sync_reset}).
     */
    private void resetSidSync() {
        sidUpdateCounter = 3;
        sidHandoverDebt = 0;
        prevFt = FrameType.TX_SPEECH_GOOD;
    }

    @Override
    public int encode(int mode, short[] pcm, int pcmOffset, byte[] out, int outOffset) {
        checkMode(mode);
        var home = isHomingFrame(pcm, pcmOffset);
        System.arraycopy(pcm, pcmOffset, speech, 0, L_FRAME);
        java.util.Arrays.fill(bits, (short) 0);
        var usedMode = core.encode(mode, speech, 0, prm);
        prm2bits(usedMode, bits, 0);
        var txType = sidSync(usedMode);
        int frameType;
        if (txType != FrameType.TX_NO_DATA) {
            frameType = usedMode;
            if (frameType == FrameType.AMR_SID) {
                if (txType == FrameType.TX_SID_FIRST) {
                    bits[AMRSID_TXTYPE_BIT_OFFSET] &= 0x0000;
                } else if (txType == FrameType.TX_SID_UPDATE) {
                    bits[AMRSID_TXTYPE_BIT_OFFSET] |= 0x0001;
                }
                for (var i = 0; i < NUM_AMRSID_TXMODE_BITS; i++) {
                    bits[AMRSID_TXMODE_BIT_OFFSET + i] = (short) ((mode >> i) & 0x0001);
                }
            }
        } else {
            frameType = FrameType.AMR_NO_DATA;
        }
        etsToIetf(frameType, out, outOffset);
        out[outOffset] |= 0x04;
        if (home) {
            reset();
        }
        return 1 + AmrConfigs.payloadSize(false, frameType);
    }

    /**
     * Encodes 160 samples into one 3GPP serial frame ({@code AMREncode} with the ETS output).
     *
     * @param mode the codec mode, one of the {@link Mode} constants, 0 to 7
     * @param pcm the samples
     * @param pcmOffset the first sample
     * @param ets receives 246 words: the TX frame type, 244 bit words, and the mode or -1
     * for a no-data frame
     * @throws IllegalArgumentException if {@code mode} is not 0 to 7
     */
    public void encodeEts(int mode, short[] pcm, int pcmOffset, short[] ets) {
        checkMode(mode);
        var home = isHomingFrame(pcm, pcmOffset);
        System.arraycopy(pcm, pcmOffset, speech, 0, L_FRAME);
        java.util.Arrays.fill(ets, 1, 1 + MAX_SERIAL_SIZE, (short) 0);
        var usedMode = core.encode(mode, speech, 0, prm);
        prm2bits(usedMode, ets, 1);
        var txType = sidSync(usedMode);
        ets[0] = (short) txType;
        ets[1 + MAX_SERIAL_SIZE] = (short) (txType != FrameType.TX_NO_DATA ? mode : -1);
        if (home) {
            reset();
        }
    }

    /**
     * Rejects a mode outside 0 to 7.
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @throws IllegalArgumentException if {@code mode} is not 0 to 7
     */
    private static void checkMode(int mode) {
        if (mode < Mode.MR475 || mode > Mode.MR122) {
            throw new IllegalArgumentException("invalid AMR-NB mode " + mode);
        }
    }

    /**
     * Tells whether a frame is the encoder homing frame.
     *
     * @param pcm the samples
     * @param offset the first sample
     * @return whether all 160 samples equal {@code 0x0008}
     */
    private static boolean isHomingFrame(short[] pcm, int offset) {
        for (var i = 0; i < L_FRAME; i++) {
            if (pcm[offset + i] != EHF_MASK) {
                return false;
            }
        }
        return true;
    }

    /**
     * Schedules the comfort noise updates ({@code sid_sync}).
     *
     * @param mode the codec mode, one of the {@link Mode} constants used, {@link Mode#MRDTX} for comfort noise
     * @return the TX frame type
     */
    private int sidSync(int mode) {
        int txFrameType;
        if (mode == Mode.MRDTX) {
            sidUpdateCounter--;
            if (prevFt == FrameType.TX_SPEECH_GOOD) {
                txFrameType = FrameType.TX_SID_FIRST;
                sidUpdateCounter = 3;
            } else if (sidHandoverDebt > 0 && sidUpdateCounter > 2) {
                txFrameType = FrameType.TX_SID_UPDATE;
                sidHandoverDebt--;
            } else if (sidUpdateCounter == 0) {
                txFrameType = FrameType.TX_SID_UPDATE;
                sidUpdateCounter = SID_UPDATE_RATE;
            } else {
                txFrameType = FrameType.TX_NO_DATA;
            }
        } else {
            sidUpdateCounter = SID_UPDATE_RATE;
            txFrameType = FrameType.TX_SPEECH_GOOD;
        }
        prevFt = txFrameType;
        return txFrameType;
    }

    /**
     * Converts the parameters of the frame into bits in parameter order ({@code Prm2bits}).
     *
     * @param mode the codec mode, one of the {@link Mode} constants used
     * @param out receives one word per bit
     * @param offset the first bit
     */
    private void prm2bits(int mode, short[] out, int offset) {
        var bitno = BitTables.BITNO[mode];
        var position = offset;
        for (var p = 0; p < BitTables.PRMNO[mode]; p++) {
            var value = prm[p];
            var count = bitno[p];
            for (var i = count - 1; i >= 0; i--) {
                out[position + i] = (short) (value & 1);
                value >>= 1;
            }
            position += count;
        }
    }

    /**
     * Packs the bits of the frame into the storage format, header first ({@code ets_to_ietf}).
     *
     * @param frameType the frame type
     * @param out receives the frame
     * @param offset the first byte
     */
    private void etsToIetf(int frameType, byte[] out, int offset) {
        var j = offset;
        out[j++] = (byte) (frameType << 3);
        var numOfBits = BitTables.NUM_OF_BITS[frameType];
        var bitsLeft = numOfBits - (numOfBits & 0xFFF8);
        if (frameType < FrameType.AMR_SID) {
            var reorder = BitTables.REORDER_BITS[frameType];
            var i = 0;
            for (; i < numOfBits - 7; ) {
                var accum = 0;
                for (var k = 7; k >= 0; k--) {
                    accum |= (bits[reorder[i++]] & 0xFF) << k;
                }
                out[j++] = (byte) accum;
            }
            var accum = 0;
            for (var k = 0; k < bitsLeft; k++) {
                accum |= (bits[reorder[i++]] & 0xFF) << (7 - k);
            }
            out[j] = (byte) accum;
        } else {
            var p = 0;
            for (var i = numOfBits - 7; i > 0; i -= 8) {
                var accum = 0;
                for (var k = 7; k >= 0; k--) {
                    accum |= (bits[p++] & 0xFF) << k;
                }
                out[j++] = (byte) accum;
            }
            var accum = 0;
            for (var i = 0; i < bitsLeft; i++) {
                accum |= bits[p++] << (7 - i);
            }
            out[j] = (byte) accum;
        }
    }
}
