/* Ports opencore-amr 0.1.6 (mode.h). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * Lists the AMR-NB codec modes as the integer values the reference's {@code enum Mode} has.
 *
 * <p>The values index the per-mode tables ({@link BitTables#PRMNO}, {@link BitTables#BITNO},
 * ...) and equal the 3GPP frame types of the speech modes ({@link FrameType#AMR_475} to
 * {@link FrameType#AMR_122}).
 */
final class Mode {
    /**
     * The 4.75 kbit/s mode.
     */
    static final int MR475 = 0;

    /**
     * The 5.15 kbit/s mode.
     */
    static final int MR515 = 1;

    /**
     * The 5.90 kbit/s mode.
     */
    static final int MR59 = 2;

    /**
     * The 6.70 kbit/s mode.
     */
    static final int MR67 = 3;

    /**
     * The 7.40 kbit/s mode.
     */
    static final int MR74 = 4;

    /**
     * The 7.95 kbit/s mode.
     */
    static final int MR795 = 5;

    /**
     * The 10.2 kbit/s mode.
     */
    static final int MR102 = 6;

    /**
     * The 12.2 kbit/s mode.
     */
    static final int MR122 = 7;

    /**
     * The discontinuous transmission (comfort noise) mode.
     */
    static final int MRDTX = 8;

    /**
     * Prevents instantiation.
     */
    private Mode() {
    }
}
