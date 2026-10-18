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
     * Constructs a precinct.
     *
     * @param blocksWide the number of code-block columns
     * @param blocksHigh the number of code-block rows
     * @param codeBlocks the code-blocks to decode, empty when encoding
     * @param encodedBlocks the code-blocks to encode, empty when decoding
     */
    Precinct(int blocksWide, int blocksHigh, CodeBlock[] codeBlocks, EncodedBlock[] encodedBlocks) {
        this.codeBlocks = codeBlocks;
        this.encodedBlocks = encodedBlocks;
        var empty = blocksWide == 0 || blocksHigh == 0;
        this.inclusion = empty ? null : new TagTree(blocksWide, blocksHigh);
        this.zeroPlanes = empty ? null : new TagTree(blocksWide, blocksHigh);
    }
}
