package com.github.auties00.smalt.image.raw;

/**
 * A compression of the raw image data of a DNG file, each lossless.
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
    LOSSLESS_JPEG(7),

    /**
     * Lossless JPEG XL (ISO/IEC 18181), DNG 1.7 compression 52546: each tile is a JPEG XL image of 16-bit
     * samples, a gray image for one sample per pixel and an RGB image for three, usually smaller than lossless JPEG
     * and readable only by DNG 1.7 readers. It cannot hold two samples per photosite.
     */
    JPEG_XL(52546);

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
