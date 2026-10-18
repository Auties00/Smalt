package com.github.auties00.smalt.audio.aac.internal;

/**
 * Holds the syntactic element identifiers {@code id_syn_ele} of an AAC {@code raw_data_block}
 * (ISO/IEC 14496-3 table 4.85), the 3-bit code that starts every element.
 */
public final class ElementId {
    /**
     * A single channel element.
     */
    public static final int SCE = 0;

    /**
     * A channel pair element.
     */
    public static final int CPE = 1;

    /**
     * A coupling channel element.
     */
    public static final int CCE = 2;

    /**
     * An LFE channel element.
     */
    public static final int LFE = 3;

    /**
     * A data stream element.
     */
    public static final int DSE = 4;

    /**
     * A program configuration element.
     */
    public static final int PCE = 5;

    /**
     * A fill element.
     */
    public static final int FIL = 6;

    /**
     * The end of the raw data block.
     */
    public static final int END = 7;

    /**
     * Prevents instantiation.
     */
    private ElementId() {
        throw new AssertionError();
    }
}
