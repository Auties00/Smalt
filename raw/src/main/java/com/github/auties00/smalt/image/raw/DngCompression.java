package com.github.auties00.smalt.image.raw;

/**
 * A compression of the raw image data of a DNG file, both lossless.
 */
public enum DngCompression {
    /**
     * Uncompressed 16-bit samples, DNG compression 1.
     */
    UNCOMPRESSED(1),

    /**
     * Lossless JPEG (ITU-T T.81 process 14), DNG compression 7: each tile is a lossless Huffman-coded JPEG
     * stream, about half the size of the uncompressed data for typical camera images.
     */
    LOSSLESS_JPEG(7);

    private final int code;

    DngCompression(int code) {
        this.code = code;
    }

    /**
     * {@return the value of the TIFF {@code Compression} tag}
     */
    public int code() {
        return code;
    }
}
