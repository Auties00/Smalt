/* Ports bcdec (bcdec.h, iOrange/bcdec). Copyright (c) 2022 Sergii Kudlai; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.dds.internal;

/**
 * Decodes the 4 by 4 texel blocks of the BCn texture compressions (S3TC BC1 to BC3, RGTC BC4 and BC5, BPTC BC6H and
 * BC7), as the Direct3D 11 functional specification defines them.
 *
 * <p>Each method decodes one block from {@code data} at {@code offset} into a caller array of 16 texels in row-major
 * order, without allocating: an instance holds the working arrays its calls share. Arguments are not checked; values
 * outside the documented ranges give unspecified results. Instances are not thread-safe.
 */
public final class BcnBlocks {
    /**
     * The fix-up marked partition sets of BC6H's two-region tiles, 32 sets of 16 texels; bit 7 marks a fix-up texel.
     */
    private static final byte[] BC6H_PARTITIONS = bytes(new int[]{
            128, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,
            128, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 129,
            128, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 129,
            128, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 129,
            128, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 129,
            128, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 129,
            128, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 129,
            128, 1, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
            128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 1, 0,
            128, 1, 129, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0,
            128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
            128, 0, 0, 0, 1, 0, 0, 0, 129, 1, 0, 0, 1, 1, 1, 0,
            128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,
            128, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 129,
            128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0,
            128, 0, 0, 0, 1, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,
            128, 1, 129, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0,
            128, 0, 129, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0,
            128, 0, 0, 1, 0, 1, 1, 1, 129, 1, 1, 0, 1, 0, 0, 0,
            128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 0, 0, 0, 0,
            128, 1, 129, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0,
            128, 0, 129, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0
    });

    /**
     * The fix-up marked partition sets of BC7's two-subset (first 64) and three-subset (last 64) tiles, of 16 texels
     * each; bit 7 marks a fix-up texel.
     */
    private static final byte[] BC7_PARTITIONS = bytes(new int[]{
            128, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,
            128, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 129,
            128, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 129,
            128, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 129,
            128, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 129,
            128, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 129,
            128, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 129,
            128, 1, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
            128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 1, 0,
            128, 1, 129, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0,
            128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
            128, 0, 0, 0, 1, 0, 0, 0, 129, 1, 0, 0, 1, 1, 1, 0,
            128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,
            128, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 129,
            128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0,
            128, 0, 0, 0, 1, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0,
            128, 1, 129, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0,
            128, 0, 129, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0,
            128, 0, 0, 1, 0, 1, 1, 1, 129, 1, 1, 0, 1, 0, 0, 0,
            128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 0, 0, 0, 0,
            128, 1, 129, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0,
            128, 0, 129, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0,
            128, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 129,
            128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 129,
            128, 1, 0, 1, 1, 0, 129, 0, 0, 1, 0, 1, 1, 0, 1, 0,
            128, 0, 1, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 1, 0, 0,
            128, 0, 129, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0,
            128, 1, 0, 1, 0, 1, 0, 1, 129, 0, 1, 0, 1, 0, 1, 0,
            128, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 129,
            128, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 129,
            128, 1, 129, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 0,
            128, 0, 0, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 0, 0, 0,
            128, 0, 129, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0,
            128, 0, 129, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0,
            128, 1, 129, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0,
            128, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 129,
            128, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 129,
            128, 0, 0, 0, 0, 1, 129, 0, 0, 1, 1, 0, 0, 0, 0, 0,
            128, 1, 0, 0, 1, 1, 129, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            128, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0,
            128, 0, 0, 0, 0, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0,
            128, 0, 0, 0, 0, 1, 0, 0, 129, 1, 1, 0, 0, 1, 0, 0,
            128, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 129,
            128, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 129,
            128, 1, 129, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0,
            128, 0, 129, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0,
            128, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 129,
            128, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 129,
            128, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 129,
            128, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 129,
            128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129,
            128, 0, 129, 1, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0,
            128, 0, 129, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0,
            128, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 129,
            128, 0, 1, 129, 0, 0, 1, 1, 0, 2, 2, 1, 2, 2, 2, 130,
            128, 0, 0, 129, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 2, 1,
            128, 0, 0, 0, 2, 0, 0, 1, 130, 2, 1, 1, 2, 2, 1, 129,
            128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 1, 0, 1, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 2, 2, 1, 1, 2, 130,
            128, 0, 1, 129, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 2, 130,
            128, 0, 2, 130, 0, 0, 2, 2, 1, 1, 1, 1, 1, 1, 1, 129,
            128, 0, 1, 1, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 1, 129,
            128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 1, 1, 2, 2, 2, 130,
            128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 2, 2, 2, 130,
            128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 2, 2, 2, 2, 2, 130,
            128, 0, 1, 2, 0, 0, 129, 2, 0, 0, 1, 2, 0, 0, 1, 130,
            128, 1, 1, 2, 0, 1, 129, 2, 0, 1, 1, 2, 0, 1, 1, 130,
            128, 1, 2, 2, 0, 129, 2, 2, 0, 1, 2, 2, 0, 1, 2, 130,
            128, 0, 1, 129, 0, 1, 1, 2, 1, 1, 2, 2, 1, 2, 2, 130,
            128, 0, 1, 129, 2, 0, 0, 1, 130, 2, 0, 0, 2, 2, 2, 0,
            128, 0, 0, 129, 0, 0, 1, 1, 0, 1, 1, 2, 1, 1, 2, 130,
            128, 1, 1, 129, 0, 0, 1, 1, 130, 0, 0, 1, 2, 2, 0, 0,
            128, 0, 0, 0, 1, 1, 2, 2, 129, 1, 2, 2, 1, 1, 2, 130,
            128, 0, 2, 130, 0, 0, 2, 2, 0, 0, 2, 2, 1, 1, 1, 129,
            128, 1, 1, 129, 0, 1, 1, 1, 0, 2, 2, 2, 0, 2, 2, 130,
            128, 0, 0, 129, 0, 0, 0, 1, 130, 2, 2, 1, 2, 2, 2, 1,
            128, 0, 0, 0, 0, 0, 129, 1, 0, 1, 2, 2, 0, 1, 2, 130,
            128, 0, 0, 0, 1, 1, 0, 0, 130, 2, 129, 0, 2, 2, 1, 0,
            128, 1, 2, 130, 0, 129, 2, 2, 0, 0, 1, 1, 0, 0, 0, 0,
            128, 0, 1, 2, 0, 0, 1, 2, 129, 1, 2, 2, 2, 2, 2, 130,
            128, 1, 1, 0, 1, 2, 130, 1, 129, 2, 2, 1, 0, 1, 1, 0,
            128, 0, 0, 0, 0, 1, 129, 0, 1, 2, 130, 1, 1, 2, 2, 1,
            128, 0, 2, 2, 1, 1, 0, 2, 129, 1, 0, 2, 0, 0, 2, 130,
            128, 1, 1, 0, 0, 129, 1, 0, 2, 0, 0, 2, 2, 2, 2, 130,
            128, 0, 1, 1, 0, 1, 2, 2, 0, 1, 130, 2, 0, 0, 1, 129,
            128, 0, 0, 0, 2, 0, 0, 0, 130, 2, 1, 1, 2, 2, 2, 129,
            128, 0, 0, 0, 0, 0, 0, 2, 129, 1, 2, 2, 1, 2, 2, 130,
            128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 2, 0, 0, 1, 129,
            128, 0, 1, 129, 0, 0, 1, 2, 0, 0, 2, 2, 0, 2, 2, 130,
            128, 1, 2, 0, 0, 129, 2, 0, 0, 1, 130, 0, 0, 1, 2, 0,
            128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 130, 2, 0, 0, 0, 0,
            128, 1, 2, 0, 1, 2, 0, 1, 130, 0, 129, 2, 0, 1, 2, 0,
            128, 1, 2, 0, 2, 0, 1, 2, 129, 130, 0, 1, 0, 1, 2, 0,
            128, 0, 1, 1, 2, 2, 0, 0, 1, 1, 130, 2, 0, 0, 1, 129,
            128, 0, 1, 1, 1, 1, 130, 2, 2, 2, 0, 0, 0, 0, 1, 129,
            128, 1, 0, 129, 0, 1, 0, 1, 2, 2, 2, 2, 2, 2, 2, 130,
            128, 0, 0, 0, 0, 0, 0, 0, 130, 1, 2, 1, 2, 1, 2, 129,
            128, 0, 2, 2, 1, 129, 2, 2, 0, 0, 2, 2, 1, 1, 2, 130,
            128, 0, 2, 130, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 1, 129,
            128, 2, 2, 0, 1, 2, 130, 1, 0, 2, 2, 0, 1, 2, 2, 129,
            128, 1, 0, 1, 2, 2, 130, 2, 2, 2, 2, 2, 0, 1, 0, 129,
            128, 0, 0, 0, 2, 1, 2, 1, 130, 1, 2, 1, 2, 1, 2, 129,
            128, 1, 0, 129, 0, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 130,
            128, 2, 2, 130, 0, 1, 1, 1, 0, 2, 2, 2, 0, 1, 1, 129,
            128, 0, 0, 2, 1, 129, 1, 2, 0, 0, 0, 2, 1, 1, 1, 130,
            128, 0, 0, 0, 2, 129, 1, 2, 2, 1, 1, 2, 2, 1, 1, 130,
            128, 2, 2, 2, 0, 129, 1, 1, 0, 1, 1, 1, 0, 2, 2, 130,
            128, 0, 0, 2, 1, 1, 1, 2, 129, 1, 1, 2, 0, 0, 0, 130,
            128, 1, 1, 0, 0, 129, 1, 0, 0, 1, 1, 0, 2, 2, 2, 130,
            128, 0, 0, 0, 0, 0, 0, 0, 2, 1, 129, 2, 2, 1, 1, 130,
            128, 1, 1, 0, 0, 129, 1, 0, 2, 2, 2, 2, 2, 2, 2, 130,
            128, 0, 2, 2, 0, 0, 1, 1, 0, 0, 129, 1, 0, 0, 2, 130,
            128, 0, 2, 2, 1, 1, 2, 2, 129, 1, 2, 2, 0, 0, 2, 130,
            128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 129, 1, 130,
            128, 0, 0, 130, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 129,
            128, 2, 2, 2, 1, 2, 2, 2, 0, 2, 2, 2, 129, 2, 2, 130,
            128, 1, 0, 129, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 130,
            128, 1, 1, 129, 2, 0, 1, 1, 130, 2, 0, 1, 2, 2, 2, 0
    });

    /**
     * The bits of BC6H's base endpoint and of its red, green and blue deltas, per mode.
     */
    private static final int[][] BC6H_BITS = {
            {10, 7, 11, 11, 11, 9, 8, 8, 8, 6, 10, 11, 12, 16},
            {5, 6, 5, 4, 4, 5, 6, 5, 5, 6, 10, 9, 8, 4},
            {5, 6, 4, 5, 4, 5, 5, 6, 5, 6, 10, 9, 8, 4},
            {5, 6, 4, 4, 5, 5, 5, 5, 6, 6, 10, 9, 8, 4}
    };

    /**
     * The color and alpha endpoint bits of BC7, per mode.
     */
    private static final int[][] BC7_BITS = {
            {4, 6, 5, 7, 5, 7, 7, 5},
            {0, 0, 0, 0, 6, 8, 7, 5}
    };

    private static final int[] WEIGHTS2 = {0, 21, 43, 64};

    private static final int[] WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};

    private static final int[] WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    /**
     * The modes of BC7 with a P-bit, as a bit set.
     */
    private static final int BC7_PBITS = 0b11001011;

    /**
     * The interpolation weights of the six-value mode of BC4 and BC5, in 1/65536.
     */
    private static final int[] SMOOTH_WEIGHTS6 = {9363, 18724, 28086, 37450, 46812, 56173};

    /**
     * The interpolation weights of the four-value mode of BC4 and BC5, in 1/65536.
     */
    private static final int[] SMOOTH_WEIGHTS4 = {13107, 26215, 39321, 52429};

    /**
     * The reader of the block being decoded.
     */
    private final Bits bits = new Bits();

    /**
     * The four colors of a BC1 block.
     */
    private final int[] colors = new int[4];

    /**
     * The eight values of an interpolated single-channel block.
     */
    private final int[] values = new int[8];

    /**
     * The red endpoints of a BC6H block.
     */
    private final int[] red = new int[4];

    /**
     * The green endpoints of a BC6H block.
     */
    private final int[] green = new int[4];

    /**
     * The blue endpoints of a BC6H block.
     */
    private final int[] blue = new int[4];

    /**
     * The six RGBA endpoints of a BC7 block.
     */
    private final int[][] endpoints = new int[6][4];

    /**
     * The primary indices of a BC7 block.
     */
    private final int[] indices = new int[16];

    /**
     * Constructs a decoder of blocks with its own working arrays.
     */
    public BcnBlocks() {
    }

    private static byte[] bytes(int[] values) {
        var result = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static long u64(byte[] data, int offset) {
        var value = 0L;
        for (var index = 7; index >= 0; index--) {
            value = value << 8 | (data[offset + index] & 0xFF);
        }
        return value;
    }

    /**
     * Decodes a BC1 color block (or the color half of BC2 and BC3) into RGBA texels.
     *
     * @param data the compressed data
     * @param offset the offset of the 8-byte color block
     * @param out the 16 texels, packed as {@code 0xAABBGGRR}
     * @param opaqueOnly whether the block always uses the four-color mode, as in BC2 and BC3
     */
    public void color(byte[] data, int offset, int[] out, boolean opaqueOnly) {
        var c0 = u16(data, offset);
        var c1 = u16(data, offset + 2);
        var r0 = (c0 >>> 11) & 0x1F;
        var g0 = (c0 >>> 5) & 0x3F;
        var b0 = c0 & 0x1F;
        var r1 = (c1 >>> 11) & 0x1F;
        var g1 = (c1 >>> 5) & 0x3F;
        var b1 = c1 & 0x1F;
        var colors = this.colors;
        colors[0] = 0xFF000000 | ((b0 * 527 + 23) >>> 6) << 16 | ((g0 * 259 + 33) >>> 6) << 8 | ((r0 * 527 + 23) >>> 6);
        colors[1] = 0xFF000000 | ((b1 * 527 + 23) >>> 6) << 16 | ((g1 * 259 + 33) >>> 6) << 8 | ((r1 * 527 + 23) >>> 6);
        if (c0 > c1 || opaqueOnly) {
            colors[2] = 0xFF000000 | (((2 * b0 + b1) * 351 + 61) >>> 7) << 16
                    | (((2 * g0 + g1) * 2763 + 1039) >>> 11) << 8 | (((2 * r0 + r1) * 351 + 61) >>> 7);
            colors[3] = 0xFF000000 | (((b0 + b1 * 2) * 351 + 61) >>> 7) << 16
                    | (((g0 + g1 * 2) * 2763 + 1039) >>> 11) << 8 | (((r0 + r1 * 2) * 351 + 61) >>> 7);
        } else {
            colors[2] = 0xFF000000 | (((b0 + b1) * 1053 + 125) >>> 8) << 16
                    | (((g0 + g1) * 4145 + 1019) >>> 11) << 8 | (((r0 + r1) * 1053 + 125) >>> 8);
            colors[3] = 0;
        }
        var indices = (data[offset + 4] & 0xFF) | (data[offset + 5] & 0xFF) << 8 | (data[offset + 6] & 0xFF) << 16
                | (data[offset + 7] & 0xFF) << 24;
        for (var texel = 0; texel < 16; texel++) {
            out[texel] = colors[(indices >>> (2 * texel)) & 3];
        }
    }

    /**
     * Decodes the explicit 4-bit alpha block of BC2 into the alpha byte of RGBA texels.
     *
     * @param data the compressed data
     * @param offset the offset of the 8-byte alpha block
     * @param out the 16 texels, whose alpha byte is replaced
     */
    public void sharpAlpha(byte[] data, int offset, int[] out) {
        for (var texel = 0; texel < 16; texel++) {
            var alpha = ((data[offset + texel / 2] & 0xFF) >>> (4 * (texel & 1))) & 0x0F;
            out[texel] = (out[texel] & 0x00FFFFFF) | (alpha * 17) << 24;
        }
    }

    /**
     * Decodes an interpolated single-channel block: the alpha block of BC3 and the channels of BC4 and BC5.
     *
     * <p>Unsigned endpoints give values from 0 to 255; signed ones values from -127 to 127, -128 being taken as
     * -127.
     *
     * @param data the compressed data
     * @param offset the offset of the 8-byte block
     * @param out the 16 values
     * @param signed whether the endpoints are signed bytes
     * @param precise whether the six and four intermediate values are rounded from fixed-point weights, as the BC4
     * and BC5 formats have them, rather than truncated, as the BC3 alpha block has them
     */
    public void smooth(byte[] data, int offset, int[] out, boolean signed, boolean precise) {
        var block = u64(data, offset);
        var values = this.values;
        if (signed) {
            values[0] = Math.max(-127, (byte) block);
            values[1] = Math.max(-127, (byte) (block >>> 8));
        } else {
            values[0] = (int) (block & 0xFF);
            values[1] = (int) ((block >>> 8) & 0xFF);
        }
        var a0 = values[0];
        var a1 = values[1];
        if (precise) {
            if (a0 > a1) {
                var weights = SMOOTH_WEIGHTS6;
                for (var index = 0; index < 6; index++) {
                    values[2 + index] = (weights[5 - index] * a0 + weights[index] * a1 + 32768) >> 16;
                }
            } else {
                var weights = SMOOTH_WEIGHTS4;
                for (var index = 0; index < 4; index++) {
                    values[2 + index] = (weights[3 - index] * a0 + weights[index] * a1 + 32768) >> 16;
                }
                values[6] = signed ? -127 : 0;
                values[7] = signed ? 127 : 255;
            }
        } else if (a0 > a1) {
            for (var index = 0; index < 6; index++) {
                values[2 + index] = ((6 - index) * a0 + (1 + index) * a1) / 7;
            }
        } else {
            for (var index = 0; index < 4; index++) {
                values[2 + index] = ((4 - index) * a0 + (1 + index) * a1) / 5;
            }
            values[6] = 0;
            values[7] = 255;
        }
        var indices = block >>> 16;
        for (var texel = 0; texel < 16; texel++) {
            out[texel] = values[(int) ((indices >>> (3 * texel)) & 7)];
        }
    }

    /**
     * Decodes an interpolated single-channel block into the alpha byte of RGBA texels, as {@link #smooth} decodes
     * unsigned values.
     *
     * @param data the compressed data
     * @param offset the offset of the 8-byte block
     * @param out the 16 texels, packed as {@code 0xAABBGGRR}, whose alpha byte is replaced
     * @param precise whether the intermediate values are rounded, as for BC4, or truncated, as for BC3 alpha
     */
    public void alpha(byte[] data, int offset, int[] out, boolean precise) {
        var alpha = this.indices;
        smooth(data, offset, alpha, false, precise);
        for (var texel = 0; texel < 16; texel++) {
            out[texel] = (out[texel] & 0x00FFFFFF) | alpha[texel] << 24;
        }
    }

    /**
     * Reads the bits of a 128-bit block from its least significant end.
     */
    private static final class Bits {
        long low;
        long high;

        void reset(byte[] data, int offset) {
            low = u64(data, offset);
            high = u64(data, offset + 8);
        }

        int read(int count) {
            var mask = (1L << count) - 1;
            var bits = (int) (low & mask);
            low = low >>> count | (high & mask) << (64 - count);
            high >>>= count;
            return bits;
        }

        int reversed(int count) {
            var bits = read(count);
            var result = 0;
            for (var index = 0; index < count; index++) {
                result = result << 1 | (bits & 1);
                bits >>>= 1;
            }
            return result;
        }
    }

    private static int extendSign(int value, int bits) {
        return (value << (32 - bits)) >> (32 - bits);
    }

    private static int transformInverse(int value, int base, int bits, boolean signed) {
        value = (value + base) & ((1 << bits) - 1);
        return signed ? extendSign(value, bits) : value;
    }

    private static int unquantize(int value, int bits, boolean signed) {
        if (!signed) {
            if (bits >= 15) {
                return value;
            }
            if (value == 0) {
                return 0;
            }
            if (value == (1 << bits) - 1) {
                return 0xFFFF;
            }
            return ((value << 16) + 0x8000) >> bits;
        }
        if (bits >= 16) {
            return value;
        }
        var negative = value < 0;
        if (negative) {
            value = -value;
        }
        int result;
        if (value == 0) {
            result = 0;
        } else if (value >= (1 << (bits - 1)) - 1) {
            result = 0x7FFF;
        } else {
            result = ((value << 15) + 0x4000) >> (bits - 1);
        }
        return negative ? -result : result;
    }

    private static int interpolate(int a, int b, int[] weights, int index) {
        return (a * (64 - weights[index]) + b * weights[index] + 32) >> 6;
    }

    private static int finishUnquantize(int value, boolean signed) {
        if (!signed) {
            return (value * 31) >> 6;
        }
        value = value < 0 ? -(((-value) * 31) >> 5) : (value * 31) >> 5;
        return value < 0 ? 0x8000 | -value : value;
    }

    /**
     * Decodes a BC6H block into half-precision RGB texels; a reserved mode decodes to zero.
     *
     * @param data the compressed data
     * @param offset the offset of the 16-byte block
     * @param out the 48 IEEE 754 binary16 values, three per texel
     * @param signed whether the block is of the signed format ({@code BC6H_SF16})
     */
    public void bc6h(byte[] data, int offset, short[] out, boolean signed) {
        var bits = this.bits;
        bits.reset(data, offset);
        var r = red;
        var g = green;
        var b = blue;
        java.util.Arrays.fill(r, 0);
        java.util.Arrays.fill(g, 0);
        java.util.Arrays.fill(b, 0);
        var mode = bits.read(2);
        if (mode > 1) {
            mode |= bits.read(3) << 2;
        }
        var partition = 0;
        var reserved = false;
        switch (mode) {
            case 0b00 -> {
                g[2] |= bits.read(1) << 4;
                b[2] |= bits.read(1) << 4;
                b[3] |= bits.read(1) << 4;
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(5);
                g[3] |= bits.read(1) << 4;
                g[2] |= bits.read(4);
                g[1] |= bits.read(5);
                b[3] |= bits.read(1);
                g[3] |= bits.read(4);
                b[1] |= bits.read(5);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(4);
                r[2] |= bits.read(5);
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(5);
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 0;
            }
            case 0b01 -> {
                g[2] |= bits.read(1) << 5;
                g[3] |= bits.read(1) << 4;
                g[3] |= bits.read(1) << 5;
                r[0] |= bits.read(7);
                b[3] |= bits.read(1);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(1) << 4;
                g[0] |= bits.read(7);
                b[2] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 2;
                g[2] |= bits.read(1) << 4;
                b[0] |= bits.read(7);
                b[3] |= bits.read(1) << 3;
                b[3] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 4;
                r[1] |= bits.read(6);
                g[2] |= bits.read(4);
                g[1] |= bits.read(6);
                g[3] |= bits.read(4);
                b[1] |= bits.read(6);
                b[2] |= bits.read(4);
                r[2] |= bits.read(6);
                r[3] |= bits.read(6);
                partition = bits.read(5);
                mode = 1;
            }
            case 0b00010 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(5);
                r[0] |= bits.read(1) << 10;
                g[2] |= bits.read(4);
                g[1] |= bits.read(4);
                g[0] |= bits.read(1) << 10;
                b[3] |= bits.read(1);
                g[3] |= bits.read(4);
                b[1] |= bits.read(4);
                b[0] |= bits.read(1) << 10;
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(4);
                r[2] |= bits.read(5);
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(5);
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 2;
            }
            case 0b00110 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(4);
                r[0] |= bits.read(1) << 10;
                g[3] |= bits.read(1) << 4;
                g[2] |= bits.read(4);
                g[1] |= bits.read(5);
                g[0] |= bits.read(1) << 10;
                g[3] |= bits.read(4);
                b[1] |= bits.read(4);
                b[0] |= bits.read(1) << 10;
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(4);
                r[2] |= bits.read(4);
                b[3] |= bits.read(1);
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(4);
                g[2] |= bits.read(1) << 4;
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 3;
            }
            case 0b01010 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(4);
                r[0] |= bits.read(1) << 10;
                b[2] |= bits.read(1) << 4;
                g[2] |= bits.read(4);
                g[1] |= bits.read(4);
                g[0] |= bits.read(1) << 10;
                b[3] |= bits.read(1);
                g[3] |= bits.read(4);
                b[1] |= bits.read(5);
                b[0] |= bits.read(1) << 10;
                b[2] |= bits.read(4);
                r[2] |= bits.read(4);
                b[3] |= bits.read(1) << 1;
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(4);
                b[3] |= bits.read(1) << 4;
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 4;
            }
            case 0b01110 -> {
                r[0] |= bits.read(9);
                b[2] |= bits.read(1) << 4;
                g[0] |= bits.read(9);
                g[2] |= bits.read(1) << 4;
                b[0] |= bits.read(9);
                b[3] |= bits.read(1) << 4;
                r[1] |= bits.read(5);
                g[3] |= bits.read(1) << 4;
                g[2] |= bits.read(4);
                g[1] |= bits.read(5);
                b[3] |= bits.read(1);
                g[3] |= bits.read(4);
                b[1] |= bits.read(5);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(4);
                r[2] |= bits.read(5);
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(5);
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 5;
            }
            case 0b10010 -> {
                r[0] |= bits.read(8);
                g[3] |= bits.read(1) << 4;
                b[2] |= bits.read(1) << 4;
                g[0] |= bits.read(8);
                b[3] |= bits.read(1) << 2;
                g[2] |= bits.read(1) << 4;
                b[0] |= bits.read(8);
                b[3] |= bits.read(1) << 3;
                b[3] |= bits.read(1) << 4;
                r[1] |= bits.read(6);
                g[2] |= bits.read(4);
                g[1] |= bits.read(5);
                b[3] |= bits.read(1);
                g[3] |= bits.read(4);
                b[1] |= bits.read(5);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(4);
                r[2] |= bits.read(6);
                r[3] |= bits.read(6);
                partition = bits.read(5);
                mode = 6;
            }
            case 0b10110 -> {
                r[0] |= bits.read(8);
                b[3] |= bits.read(1);
                b[2] |= bits.read(1) << 4;
                g[0] |= bits.read(8);
                g[2] |= bits.read(1) << 5;
                g[2] |= bits.read(1) << 4;
                b[0] |= bits.read(8);
                g[3] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 4;
                r[1] |= bits.read(5);
                g[3] |= bits.read(1) << 4;
                g[2] |= bits.read(4);
                g[1] |= bits.read(6);
                g[3] |= bits.read(4);
                b[1] |= bits.read(5);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(4);
                r[2] |= bits.read(5);
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(5);
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 7;
            }
            case 0b11010 -> {
                r[0] |= bits.read(8);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(1) << 4;
                g[0] |= bits.read(8);
                b[2] |= bits.read(1) << 5;
                g[2] |= bits.read(1) << 4;
                b[0] |= bits.read(8);
                b[3] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 4;
                r[1] |= bits.read(5);
                g[3] |= bits.read(1) << 4;
                g[2] |= bits.read(4);
                g[1] |= bits.read(5);
                b[3] |= bits.read(1);
                g[3] |= bits.read(4);
                b[1] |= bits.read(6);
                b[2] |= bits.read(4);
                r[2] |= bits.read(5);
                b[3] |= bits.read(1) << 2;
                r[3] |= bits.read(5);
                b[3] |= bits.read(1) << 3;
                partition = bits.read(5);
                mode = 8;
            }
            case 0b11110 -> {
                r[0] |= bits.read(6);
                g[3] |= bits.read(1) << 4;
                b[3] |= bits.read(1);
                b[3] |= bits.read(1) << 1;
                b[2] |= bits.read(1) << 4;
                g[0] |= bits.read(6);
                g[2] |= bits.read(1) << 5;
                b[2] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 2;
                g[2] |= bits.read(1) << 4;
                b[0] |= bits.read(6);
                g[3] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 3;
                b[3] |= bits.read(1) << 5;
                b[3] |= bits.read(1) << 4;
                r[1] |= bits.read(6);
                g[2] |= bits.read(4);
                g[1] |= bits.read(6);
                g[3] |= bits.read(4);
                b[1] |= bits.read(6);
                b[2] |= bits.read(4);
                r[2] |= bits.read(6);
                r[3] |= bits.read(6);
                partition = bits.read(5);
                mode = 9;
            }
            case 0b00011 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(10);
                g[1] |= bits.read(10);
                b[1] |= bits.read(10);
                mode = 10;
            }
            case 0b00111 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(9);
                r[0] |= bits.read(1) << 10;
                g[1] |= bits.read(9);
                g[0] |= bits.read(1) << 10;
                b[1] |= bits.read(9);
                b[0] |= bits.read(1) << 10;
                mode = 11;
            }
            case 0b01011 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(8);
                r[0] |= bits.reversed(2) << 10;
                g[1] |= bits.read(8);
                g[0] |= bits.reversed(2) << 10;
                b[1] |= bits.read(8);
                b[0] |= bits.reversed(2) << 10;
                mode = 12;
            }
            case 0b01111 -> {
                r[0] |= bits.read(10);
                g[0] |= bits.read(10);
                b[0] |= bits.read(10);
                r[1] |= bits.read(4);
                r[0] |= bits.reversed(6) << 10;
                g[1] |= bits.read(4);
                g[0] |= bits.reversed(6) << 10;
                b[1] |= bits.read(4);
                b[0] |= bits.reversed(6) << 10;
                mode = 13;
            }
            default -> reserved = true;
        }
        if (reserved) {
            java.util.Arrays.fill(out, (short) 0);
            return;
        }
        var partitions = mode >= 10 ? 0 : 1;
        var baseBits = BC6H_BITS[0][mode];
        if (signed) {
            r[0] = extendSign(r[0], baseBits);
            g[0] = extendSign(g[0], baseBits);
            b[0] = extendSign(b[0], baseBits);
        }
        if ((mode != 9 && mode != 10) || signed) {
            for (var i = 1; i < (partitions + 1) * 2; i++) {
                r[i] = extendSign(r[i], BC6H_BITS[1][mode]);
                g[i] = extendSign(g[i], BC6H_BITS[2][mode]);
                b[i] = extendSign(b[i], BC6H_BITS[3][mode]);
            }
        }
        if (mode != 9 && mode != 10) {
            for (var i = 1; i < (partitions + 1) * 2; i++) {
                r[i] = transformInverse(r[i], r[0], baseBits, signed);
                g[i] = transformInverse(g[i], g[0], baseBits, signed);
                b[i] = transformInverse(b[i], b[0], baseBits, signed);
            }
        }
        for (var i = 0; i < (partitions + 1) * 2; i++) {
            r[i] = unquantize(r[i], baseBits, signed);
            g[i] = unquantize(g[i], baseBits, signed);
            b[i] = unquantize(b[i], baseBits, signed);
        }
        var weights = mode >= 10 ? WEIGHTS4 : WEIGHTS3;
        for (var texel = 0; texel < 16; texel++) {
            var set = mode >= 10 ? (texel == 0 ? 0x80 : 0) : BC6H_PARTITIONS[partition * 16 + texel] & 0xFF;
            var indexBits = mode >= 10 ? 4 : 3;
            if ((set & 0x80) != 0) {
                indexBits--;
            }
            set &= 1;
            var index = bits.read(indexBits);
            var endpoint = set * 2;
            out[3 * texel] = (short) finishUnquantize(interpolate(r[endpoint], r[endpoint + 1], weights, index),
                    signed);
            out[3 * texel + 1] = (short) finishUnquantize(interpolate(g[endpoint], g[endpoint + 1], weights, index),
                    signed);
            out[3 * texel + 2] = (short) finishUnquantize(interpolate(b[endpoint], b[endpoint + 1], weights, index),
                    signed);
        }
    }

    /**
     * Decodes a BC7 block into RGBA texels; an invalid mode decodes to transparent black.
     *
     * @param data the compressed data
     * @param offset the offset of the 16-byte block
     * @param out the 16 texels, packed as {@code 0xAABBGGRR}
     */
    public void bc7(byte[] data, int offset, int[] out) {
        var bits = this.bits;
        bits.reset(data, offset);
        var mode = 0;
        while (mode < 8 && bits.read(1) == 0) {
            mode++;
        }
        if (mode >= 8) {
            java.util.Arrays.fill(out, 0);
            return;
        }
        var partition = 0;
        var partitions = 1;
        var rotation = 0;
        var selection = 0;
        if (mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7) {
            partitions = mode == 0 || mode == 2 ? 3 : 2;
            partition = bits.read(mode == 0 ? 4 : 6);
        }
        var endpointCount = partitions * 2;
        if (mode == 4 || mode == 5) {
            rotation = bits.read(2);
            if (mode == 4) {
                selection = bits.read(1);
            }
        }
        var endpoints = this.endpoints;
        for (var row : endpoints) {
            java.util.Arrays.fill(row, 0);
        }
        for (var channel = 0; channel < 3; channel++) {
            for (var endpoint = 0; endpoint < endpointCount; endpoint++) {
                endpoints[endpoint][channel] = bits.read(BC7_BITS[0][mode]);
            }
        }
        if (BC7_BITS[1][mode] > 0) {
            for (var endpoint = 0; endpoint < endpointCount; endpoint++) {
                endpoints[endpoint][3] = bits.read(BC7_BITS[1][mode]);
            }
        }
        if (mode == 0 || mode == 1 || mode == 3 || mode == 6 || mode == 7) {
            for (var endpoint = 0; endpoint < endpointCount; endpoint++) {
                for (var channel = 0; channel < 4; channel++) {
                    endpoints[endpoint][channel] <<= 1;
                }
            }
            if (mode == 1) {
                var first = bits.read(1);
                var second = bits.read(1);
                for (var channel = 0; channel < 3; channel++) {
                    endpoints[0][channel] |= first;
                    endpoints[1][channel] |= first;
                    endpoints[2][channel] |= second;
                    endpoints[3][channel] |= second;
                }
            } else if ((BC7_PBITS & (1 << mode)) != 0) {
                for (var endpoint = 0; endpoint < endpointCount; endpoint++) {
                    var pbit = bits.read(1);
                    for (var channel = 0; channel < 4; channel++) {
                        endpoints[endpoint][channel] |= pbit;
                    }
                }
            }
        }
        for (var endpoint = 0; endpoint < endpointCount; endpoint++) {
            var precision = BC7_BITS[0][mode] + ((BC7_PBITS >>> mode) & 1);
            for (var channel = 0; channel < 3; channel++) {
                var value = endpoints[endpoint][channel] << (8 - precision);
                endpoints[endpoint][channel] = value | (value >> precision);
            }
            precision = BC7_BITS[1][mode] + ((BC7_PBITS >>> mode) & 1);
            var value = endpoints[endpoint][3] << (8 - precision);
            endpoints[endpoint][3] = value | (value >> precision);
        }
        if (BC7_BITS[1][mode] == 0) {
            for (var endpoint = 0; endpoint < endpointCount; endpoint++) {
                endpoints[endpoint][3] = 0xFF;
            }
        }
        var indexBits = mode == 0 || mode == 1 ? 3 : mode == 6 ? 4 : 2;
        var secondBits = mode == 4 ? 3 : mode == 5 ? 2 : 0;
        var weights = indexBits == 2 ? WEIGHTS2 : indexBits == 3 ? WEIGHTS3 : WEIGHTS4;
        var weights2 = secondBits == 2 ? WEIGHTS2 : WEIGHTS3;
        var indices = this.indices;
        for (var texel = 0; texel < 16; texel++) {
            var set = partitions == 1 ? (texel == 0 ? 0x80 : 0)
                    : BC7_PARTITIONS[(partitions - 2) * 1024 + partition * 16 + texel] & 0xFF;
            indices[texel] = bits.read((set & 0x80) != 0 ? indexBits - 1 : indexBits);
        }
        for (var texel = 0; texel < 16; texel++) {
            var set = partitions == 1 ? 0 : BC7_PARTITIONS[(partitions - 2) * 1024 + partition * 16 + texel] & 0x03;
            var index = indices[texel];
            var e0 = endpoints[set * 2];
            var e1 = endpoints[set * 2 + 1];
            int red;
            int green;
            int blue;
            int alpha;
            if (secondBits == 0) {
                red = interpolate(e0[0], e1[0], weights, index);
                green = interpolate(e0[1], e1[1], weights, index);
                blue = interpolate(e0[2], e1[2], weights, index);
                alpha = interpolate(e0[3], e1[3], weights, index);
            } else {
                var index2 = bits.read(texel == 0 ? secondBits - 1 : secondBits);
                if (selection == 0) {
                    red = interpolate(e0[0], e1[0], weights, index);
                    green = interpolate(e0[1], e1[1], weights, index);
                    blue = interpolate(e0[2], e1[2], weights, index);
                    alpha = interpolate(e0[3], e1[3], weights2, index2);
                } else {
                    red = interpolate(e0[0], e1[0], weights2, index2);
                    green = interpolate(e0[1], e1[1], weights2, index2);
                    blue = interpolate(e0[2], e1[2], weights2, index2);
                    alpha = interpolate(e0[3], e1[3], weights, index);
                }
            }
            switch (rotation) {
                case 1 -> {
                    var swap = alpha;
                    alpha = red;
                    red = swap;
                }
                case 2 -> {
                    var swap = alpha;
                    alpha = green;
                    green = swap;
                }
                case 3 -> {
                    var swap = alpha;
                    alpha = blue;
                    blue = swap;
                }
                default -> {
                }
            }
            out[texel] = (alpha & 0xFF) << 24 | (blue & 0xFF) << 16 | (green & 0xFF) << 8 | (red & 0xFF);
        }
    }
}
