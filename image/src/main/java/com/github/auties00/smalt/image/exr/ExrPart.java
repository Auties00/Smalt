package com.github.auties00.smalt.image.exr;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes one part of an OpenEXR file, as its header declares it.
 *
 * @param index the index of the part in the file
 * @param name the part name, or empty for the part of a single-part file without one
 * @param storage how the part stores its pixels
 * @param compression how the part compresses its pixels
 * @param width the width of the display window, the size of the decoded picture
 * @param height the height of the display window
 * @param dataX the left column of the data window, the pixels the file stores, in display coordinates
 * @param dataY the top row of the data window
 * @param dataWidth the width of the data window
 * @param dataHeight the height of the data window
 * @param channels the channel names, sorted as the file stores them
 * @param levels the number of resolution levels: 1, or the mipmap levels of a tiled part (for a ripmap, the levels
 * of the longer side)
 */
public record ExrPart(int index, Optional<String> name, Storage storage, Compression compression, int width,
                      int height, int dataX, int dataY, int dataWidth, int dataHeight, List<String> channels,
                      int levels) {
    /**
     * Validates the description.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public ExrPart {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(compression, "compression");
        channels = List.copyOf(channels);
    }

    /**
     * Enumerates the ways a part stores its pixels.
     */
    public enum Storage {
        /**
         * Chunks of scanlines.
         */
        SCANLINE,

        /**
         * Tiles, possibly of several resolution levels.
         */
        TILED,

        /**
         * Chunks of scanlines of any number of samples per pixel.
         */
        DEEP_SCANLINE,

        /**
         * Tiles of any number of samples per pixel.
         */
        DEEP_TILED
    }

    /**
     * Enumerates the compression methods of OpenEXR.
     */
    public enum Compression {
        /**
         * No compression.
         */
        NONE,

        /**
         * Run-length coding of the predicted bytes of each scanline.
         */
        RLE,

        /**
         * zlib of the predicted bytes of each scanline.
         */
        ZIPS,

        /**
         * zlib of the predicted bytes of 16 scanlines.
         */
        ZIP,

        /**
         * Wavelet and Huffman coding of 32 scanlines.
         */
        PIZ,

        /**
         * Floats cut to 24 bits, predicted and deflated, 16 scanlines (lossy for 32-bit floats).
         */
        PXR24,

        /**
         * Lossy 4 by 4 blocks of halves, 32 scanlines.
         */
        B44,

        /**
         * B44 with flat blocks in 3 bytes, 32 scanlines.
         */
        B44A,

        /**
         * Lossy DCT of 32 scanlines.
         */
        DWAA,

        /**
         * Lossy DCT of 256 scanlines.
         */
        DWAB,

        /**
         * Lossless High-Throughput JPEG 2000 of 256 scanlines.
         */
        HTJ2K256,

        /**
         * Lossless High-Throughput JPEG 2000 of 32 scanlines.
         */
        HTJ2K32,

        /**
         * High-Throughput JPEG 2000 of 256 scanlines, lossy in the RGB channels and lossless in the others.
         */
        LJ2K,

        /**
         * Zstandard of each scanline, after its samples are sorted by size, delta coded and split into byte planes.
         */
        ZSTD
    }
}
