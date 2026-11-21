package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.audio.Speaker;

/**
 * The DTS loudspeaker mask bits of ETSI TS 102 114 Table 6-22, one bit per speaker, and the 16-bit mask of
 * Table 7-10 whose bits stand for a speaker or a pair.
 */
public final class Speakers {
    /** Centre in front. */
    public static final int C = 0x1;
    /** Left in front. */
    public static final int L = 0x2;
    /** Right in front. */
    public static final int R = 0x4;
    /** Left surround on side in rear. */
    public static final int LS = 0x8;
    /** Right surround on side in rear. */
    public static final int RS = 0x10;
    /** The first low frequency effects channel. */
    public static final int LFE1 = 0x20;
    /** Centre surround in rear. */
    public static final int CS = 0x40;
    /** Left surround in rear. */
    public static final int LSR = 0x80;
    /** Right surround in rear. */
    public static final int RSR = 0x100;
    /** Left surround on side. */
    public static final int LSS = 0x200;
    /** Right surround on side. */
    public static final int RSS = 0x400;
    /** Between left and centre in front. */
    public static final int LC = 0x800;
    /** Between right and centre in front. */
    public static final int RC = 0x1000;
    /** Left height in front. */
    public static final int LH = 0x2000;
    /** Centre height in front. */
    public static final int CH = 0x4000;
    /** Right height in front. */
    public static final int RH = 0x8000;
    /** The second low frequency effects channel. */
    public static final int LFE2 = 0x10000;
    /** Left on side in front. */
    public static final int LW = 0x20000;
    /** Right on side in front. */
    public static final int RW = 0x40000;
    /** Over the listener's head. */
    public static final int OH = 0x80000;
    /** Left height on side. */
    public static final int LHS = 0x100000;
    /** Right height on side. */
    public static final int RHS = 0x200000;
    /** Centre height in rear. */
    public static final int CHR = 0x400000;
    /** Left height in rear. */
    public static final int LHR = 0x800000;
    /** Right height in rear. */
    public static final int RHR = 0x1000000;
    /** Centre below ear level. */
    public static final int CL = 0x2000000;
    /** Left below ear level. */
    public static final int LL = 0x4000000;
    /** Right below ear level. */
    public static final int RL = 0x8000000;

    /**
     * The speaker bits of each bit of the 16-bit mask of Table 7-10.
     */
    private static final int[] PAIRS = {C, L | R, LS | RS, LFE1, CS, LH | RH, LSR | RSR, CH, OH, LC | RC, LW | RW,
            LSS | RSS, LFE2, LHS | RHS, CHR, LHR | RHR};

    /**
     * Expands a 16-bit mask of Table 7-10 into speaker bits.
     *
     * @param mask the mask of speakers and pairs
     * @return the speaker bits
     */
    public static int expand(int mask) {
        var bits = 0;
        for (var i = 0; i < 16; i++) {
            if ((mask & 1 << i) != 0) {
                bits |= PAIRS[i];
            }
        }
        return bits;
    }

    /**
     * Returns the speaker of a DTS speaker bit.
     *
     * @param bit the speaker bit of Table 6-22
     * @param sideTaken whether a side surround speaker is already fed by {@link #LS} or {@link #RS}
     * @return the speaker, or {@link Speaker#UNKNOWN} for a reserved bit
     */
    public static Speaker speaker(int bit, boolean sideTaken) {
        return switch (bit) {
            case C -> Speaker.FRONT_CENTER;
            case L -> Speaker.FRONT_LEFT;
            case R -> Speaker.FRONT_RIGHT;
            case LS -> Speaker.SIDE_LEFT;
            case RS -> Speaker.SIDE_RIGHT;
            case LFE1 -> Speaker.LOW_FREQUENCY;
            case CS -> Speaker.BACK_CENTER;
            case LSR -> Speaker.BACK_LEFT;
            case RSR -> Speaker.BACK_RIGHT;
            case LSS -> sideTaken ? Speaker.SURROUND_DIRECT_LEFT : Speaker.SIDE_LEFT;
            case RSS -> sideTaken ? Speaker.SURROUND_DIRECT_RIGHT : Speaker.SIDE_RIGHT;
            case LC -> Speaker.FRONT_LEFT_OF_CENTER;
            case RC -> Speaker.FRONT_RIGHT_OF_CENTER;
            case LH -> Speaker.TOP_FRONT_LEFT;
            case CH -> Speaker.TOP_FRONT_CENTER;
            case RH -> Speaker.TOP_FRONT_RIGHT;
            case LFE2 -> Speaker.LOW_FREQUENCY_2;
            case LW -> Speaker.WIDE_LEFT;
            case RW -> Speaker.WIDE_RIGHT;
            case OH -> Speaker.TOP_CENTER;
            case LHS -> Speaker.TOP_SIDE_LEFT;
            case RHS -> Speaker.TOP_SIDE_RIGHT;
            case CHR -> Speaker.TOP_BACK_CENTER;
            case LHR -> Speaker.TOP_BACK_LEFT;
            case RHR -> Speaker.TOP_BACK_RIGHT;
            case CL -> Speaker.BOTTOM_FRONT_CENTER;
            case LL -> Speaker.BOTTOM_FRONT_LEFT;
            case RL -> Speaker.BOTTOM_FRONT_RIGHT;
            default -> Speaker.UNKNOWN;
        };
    }

    /**
     * Prevents instantiation.
     */
    private Speakers() {
    }
}
