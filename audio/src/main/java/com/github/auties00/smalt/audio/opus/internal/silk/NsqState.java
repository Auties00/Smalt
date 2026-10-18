/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_SHAPE_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_SUB_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.NSQ_LPC_BUF_LENGTH;

/**
 * Holds the state of the noise shaping quantizer.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class NsqState {
    /**
     * The buffer of the quantized output signal.
     */
    public final short[] xq = new short[2 * MAX_FRAME_LENGTH];

    /**
     * The long-term shaping state in Q14.
     */
    public final int[] sLTP_shp_Q14 = new int[2 * MAX_FRAME_LENGTH];

    /**
     * The short-term prediction state in Q14.
     */
    public final int[] sLPC_Q14 = new int[MAX_SUB_FRAME_LENGTH + NSQ_LPC_BUF_LENGTH];

    /**
     * The short-term shaping state in Q14.
     */
    public final int[] sAR2_Q14 = new int[MAX_SHAPE_LPC_ORDER];

    /**
     * The low-frequency shaping state in Q14.
     */
    public int sLF_AR_shp_Q14;

    /**
     * The difference shaping state in Q14.
     */
    public int sDiff_shp_Q14;

    /**
     * The pitch lag of the previous subframe.
     */
    public int lagPrev;

    /**
     * The write index of the LTP buffer.
     */
    public int sLTP_buf_idx;

    /**
     * The write index of the long-term shaping buffer.
     */
    public int sLTP_shp_buf_idx;

    /**
     * The seed of the dither generator.
     */
    public int rand_seed;

    /**
     * The gain of the previous subframe in Q16.
     */
    public int prev_gain_Q16;

    /**
     * Whether the LTP state must be re-whitened.
     */
    public int rewhite_flag;

    /**
     * Creates a zeroed state.
     */
    public NsqState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(xq, (short) 0);
        Arrays.fill(sLTP_shp_Q14, 0);
        Arrays.fill(sLPC_Q14, 0);
        Arrays.fill(sAR2_Q14, 0);
        sLF_AR_shp_Q14 = 0;
        sDiff_shp_Q14 = 0;
        lagPrev = 0;
        sLTP_buf_idx = 0;
        sLTP_shp_buf_idx = 0;
        rand_seed = 0;
        prev_gain_Q16 = 0;
        rewhite_flag = 0;
    }

    /**
     * Copies every field of another state into this one, like a structure assignment.
     *
     * @param other the state to copy
     */
    public void copyFrom(NsqState other) {
        System.arraycopy(other.xq, 0, xq, 0, xq.length);
        System.arraycopy(other.sLTP_shp_Q14, 0, sLTP_shp_Q14, 0, sLTP_shp_Q14.length);
        System.arraycopy(other.sLPC_Q14, 0, sLPC_Q14, 0, sLPC_Q14.length);
        System.arraycopy(other.sAR2_Q14, 0, sAR2_Q14, 0, sAR2_Q14.length);
        sLF_AR_shp_Q14 = other.sLF_AR_shp_Q14;
        sDiff_shp_Q14 = other.sDiff_shp_Q14;
        lagPrev = other.lagPrev;
        sLTP_buf_idx = other.sLTP_buf_idx;
        sLTP_shp_buf_idx = other.sLTP_shp_buf_idx;
        rand_seed = other.rand_seed;
        prev_gain_Q16 = other.prev_gain_Q16;
        rewhite_flag = other.rewhite_flag;
    }
}
