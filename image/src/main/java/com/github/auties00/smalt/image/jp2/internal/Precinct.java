package com.github.auties00.smalt.image.jp2.internal;

/**
 * Holds the code-blocks of one precinct of a sub-band, with the tag trees its packet headers code
 * them with.
 *
 * <p>A precinct built for decoding holds {@link CodeBlock}s and no {@link EncodedBlock}; one built
 * for encoding holds the reverse.
 */
final class Precinct {
    /**
     * The code-blocks to decode, row-major; empty in a precinct built for encoding.
     */
    final CodeBlock[] codeBlocks;

    /**
     * The code-blocks to encode, row-major; empty in a precinct built for decoding.
     */
    final EncodedBlock[] encodedBlocks;

    /**
     * The inclusion tag tree, or {@code null} when the precinct has no code-block.
     */
    final TagTree inclusion;

    /**
     * The zero bit-plane tag tree, or {@code null} when the precinct has no code-block.
     */
    final TagTree zeroPlanes;

    /**
     * The quantization step size of the precinct's coefficients, for the irreversible wavelets; the sub-band's unless
     * a {@code QPD} or {@code QPC} segment of ISO/IEC 15444-2 overrides it.
     */
    final float stepSize;

    /**
     * The maximum number of magnitude bit-planes of the precinct's code-blocks, as {@link Band#bitPlanes()} counts
     * them.
     */
    final int bitPlanes;

    /**
     * The deadzone adjustment {@code nz_b} of ISO/IEC 15444-2 equation C-1, 0 for the deadzone of ISO/IEC 15444-1.
     */
    final float deadzone;

    /**
     * Whether the coefficients are trellis coded (ISO/IEC 15444-2 annex D).
     */
    final boolean trellis;

    /**
     * Constructs a precinct.
     *
     * @param blocksWide the number of code-block columns
     * @param blocksHigh the number of code-block rows
     * @param codeBlocks the code-blocks to decode, empty when encoding
     * @param encodedBlocks the code-blocks to encode, empty when decoding
     * @param stepSize the quantization step size
     * @param bitPlanes the maximum number of magnitude bit-planes
     * @param deadzone the deadzone adjustment
     * @param trellis whether the coefficients are trellis coded
     */
    Precinct(int blocksWide, int blocksHigh, CodeBlock[] codeBlocks, EncodedBlock[] encodedBlocks, float stepSize,
             int bitPlanes, float deadzone, boolean trellis) {
        this.codeBlocks = codeBlocks;
        this.encodedBlocks = encodedBlocks;
        this.stepSize = stepSize;
        this.bitPlanes = bitPlanes;
        this.deadzone = deadzone;
        this.trellis = trellis;
        var empty = blocksWide == 0 || blocksHigh == 0;
        this.inclusion = empty ? null : new TagTree(blocksWide, blocksHigh);
        this.zeroPlanes = empty ? null : new TagTree(blocksWide, blocksHigh);
    }
}
