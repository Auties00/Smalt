package com.github.auties00.smalt.audio.celp.internal;

/**
 * The frame structure a base layer configuration implies: frame and subframe sizes, LPC order and the widths of the
 * frame fields (ISO/IEC 14496-3 Tables 3.53 to 3.59, 3.63 to 3.70, 3.73 to 3.75, 3.87, 3.95, 3.96 and 3.98).
 */
final class CelpLayout {
    /**
     * The 8 kHz values per {@code MPE_Configuration}: shape_positions bits, shape_signs bits (Table 3.66).
     */
    private static final int[][] NB_SHAPE = {{14, 3}, {17, 4}, {20, 5}, {20, 5}, {22, 6}, {24, 7}, {22, 6}, {24, 7},
            {26, 8}, {28, 9}, {30, 10}, {31, 11}, {32, 12}, {13, 4}, {15, 5}, {16, 6}, {17, 7}, {18, 8}, {19, 9},
            {20, 10}, {20, 11}, {20, 12}, {18, 8}, {19, 9}, {20, 10}, {20, 11}, {20, 12}, {19, 6}};

    /**
     * The 16 kHz values per {@code MPE_Configuration} modulo 16: shape_positions bits, shape_signs bits (Table 3.67).
     */
    private static final int[][] WB_SHAPE = {{20, 5}, {22, 6}, {24, 7}, {26, 8}, {28, 9}, {30, 10}, {31, 11}, {0, 0},
            {11, 3}, {13, 4}, {15, 5}, {16, 6}, {17, 7}, {18, 8}, {19, 9}, {20, 10}};

    /**
     * The RPE values per {@code RPE_Configuration}: frame size, subframes, pulse spacing, pulses, shape_index bits.
     */
    private static final int[][] RPE = {{240, 6, 8, 5, 11}, {160, 4, 8, 5, 11}, {240, 8, 5, 6, 12},
            {240, 10, 4, 6, 12}};

    /**
     * The bits of shape_bws_positions and the pulses of the bandwidth extension tool per {@code BWS_configuration}
     * (Tables 3.70 and 3.102); the sign bits equal the pulses.
     */
    private static final int[][] BWS = {{22, 6}, {26, 8}, {30, 10}, {32, 12}};

    /**
     * Whether the layer uses regular pulse excitation.
     */
    final boolean rpe;

    /**
     * Whether the core codes 16 kHz speech.
     */
    final boolean wideband;

    /**
     * The samples of a frame of the core.
     */
    final int frameSize;

    /**
     * The subframes of a frame of the core.
     */
    final int subframes;

    /**
     * The samples of a subframe of the core.
     */
    final int subframeSize;

    /**
     * The LPC order of the core, 10 or 20.
     */
    final int lpcOrder;

    /**
     * The width of shape_delay.
     */
    final int delayBits;

    /**
     * The width of shape_positions, or of shape_index with regular pulse excitation.
     */
    final int positionBits;

    /**
     * The width of shape_signs, which is also the number of pulses of the multi-pulse excitation.
     */
    final int signBits;

    /**
     * The width of gain_index.
     */
    final int gainBits;

    /**
     * The pulse spacing of the regular pulse excitation.
     */
    final int rpeSpacing;

    /**
     * The pulses of the regular pulse excitation.
     */
    final int rpePulses;

    /**
     * The widths of shape_enh_positions and shape_enh_signs.
     */
    final int enhancementPositionBits;

    /**
     * The width of shape_enh_signs, which is also the number of pulses of an enhancement layer.
     */
    final int enhancementSignBits;

    /**
     * The subframes of the bandwidth extension tool.
     */
    final int bandwidthSubframes;

    /**
     * Derives the structure of a base layer.
     *
     * @param config the base layer configuration
     */
    CelpLayout(CelpConfig config) {
        this.rpe = config.rpe();
        this.wideband = config.wideband();
        this.lpcOrder = wideband ? 20 : 10;
        var c = config.configuration();
        if (rpe) {
            var row = RPE[c];
            this.frameSize = row[0];
            this.subframes = row[1];
            this.rpeSpacing = row[2];
            this.rpePulses = row[3];
            this.positionBits = row[4];
            this.signBits = 0;
            this.delayBits = 8;
            this.gainBits = 0;
            this.enhancementPositionBits = 0;
            this.enhancementSignBits = 0;
            this.bandwidthSubframes = 0;
        } else if (wideband) {
            if (c < 7) {
                frameSize = 320;
                subframes = 4;
            } else if (c < 16) {
                frameSize = 320;
                subframes = 8;
            } else if (c < 23) {
                frameSize = 160;
                subframes = 2;
            } else {
                frameSize = 160;
                subframes = 4;
            }
            this.positionBits = WB_SHAPE[c & 15][0];
            this.signBits = WB_SHAPE[c & 15][1];
            this.delayBits = 9;
            this.gainBits = 7;
            var small = (c & 15) >= 8;
            this.enhancementPositionBits = small ? 4 : 12;
            this.enhancementSignBits = small ? 2 : 4;
            this.bandwidthSubframes = 0;
            this.rpeSpacing = 0;
            this.rpePulses = 0;
        } else {
            if (c < 3) {
                frameSize = 320;
                subframes = 4;
                bandwidthSubframes = 8;
            } else if (c < 6) {
                frameSize = 240;
                subframes = 3;
                bandwidthSubframes = 6;
            } else if (c < 13) {
                frameSize = 160;
                subframes = 2;
                bandwidthSubframes = 4;
            } else if (c < 22) {
                frameSize = 160;
                subframes = 4;
                bandwidthSubframes = 4;
            } else if (c < 27) {
                frameSize = 80;
                subframes = 2;
                bandwidthSubframes = 2;
            } else {
                frameSize = 240;
                subframes = 4;
                bandwidthSubframes = 0;
            }
            this.positionBits = NB_SHAPE[c][0];
            this.signBits = NB_SHAPE[c][1];
            this.delayBits = 8;
            this.gainBits = 6;
            this.enhancementPositionBits = c < 13 ? 12 : 4;
            this.enhancementSignBits = c < 13 ? 4 : 2;
            this.rpeSpacing = 0;
            this.rpePulses = 0;
        }
        this.subframeSize = frameSize / subframes;
    }

    /**
     * {@return the width of shape_bws_positions for a bandwidth configuration}
     *
     * @param bandwidthConfiguration {@code BWS_configuration}
     */
    static int bandwidthPositionBits(int bandwidthConfiguration) {
        return BWS[bandwidthConfiguration][0];
    }

    /**
     * {@return the number of pulses, and the width of shape_bws_signs, for a bandwidth configuration}
     *
     * @param bandwidthConfiguration {@code BWS_configuration}
     */
    static int bandwidthPulses(int bandwidthConfiguration) {
        return BWS[bandwidthConfiguration][1];
    }
}
