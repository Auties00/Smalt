package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the code-block coding style options of JPEG 2000 (ISO/IEC 15444-1 Table A.19).
 *
 * <p>They trade compression for speed or error resilience; none is needed for an ordinary file.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2CodeBlockStyle {
    /**
     * Selective arithmetic coding bypass: the lower bit-planes' significance and refinement
     * passes are stored raw, which speeds coding up for a slightly larger file.
     */
    BYPASS(0x01),

    /**
     * Resets the context probabilities after each coding pass.
     */
    RESET(0x02),

    /**
     * Terminates the arithmetic coder after each coding pass.
     */
    TERMINATE_ALL(0x04),

    /**
     * Forms contexts from the current and lower stripes only (vertically causal contexts).
     */
    VERTICALLY_CAUSAL(0x08),

    /**
     * Terminates coding passes predictably, so a decoder can detect errors.
     */
    PREDICTABLE_TERMINATION(0x10),

    /**
     * Adds a segmentation symbol after each cleanup pass, so a decoder can detect errors.
     */
    SEGMENTATION_SYMBOLS(0x20);

    /**
     * The bit of the {@code SPcod} code-block style field.
     */
    private final int bit;

    /**
     * Constructs a constant.
     *
     * @param bit the field bit
     */
    Jp2CodeBlockStyle(int bit) {
        this.bit = bit;
    }

    /**
     * Returns the bit of this option in the code-block style field.
     *
     * @return the bit
     */
    int bit() {
        return bit;
    }
}
