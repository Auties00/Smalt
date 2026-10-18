package com.github.auties00.smalt.image.tiff;

/**
 * Enumerates the TIFF compression schemes, as the {@code Compression} tag (259) names them.
 *
 * <p>{@link TiffDecoder} decodes {@link #NONE}, {@link #CCITT_RLE}, {@link #CCITT_T4},
 * {@link #CCITT_T6}, {@link #LZW}, {@link #OLD_JPEG} (when its stream or tables can be read),
 * {@link #JPEG}, {@link #DEFLATE}, {@link #PACKBITS}, {@link #CCITT_RLEW} and {@link #THUNDERSCAN};
 * {@link TiffEncoder} writes {@link #NONE}, {@link #PACKBITS}, {@link #LZW} and {@link #DEFLATE}.
 * The other constants name schemes a file may use, so that a page can report them.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum TiffCompression {
    /**
     * No compression (code 1).
     */
    NONE(1),

    /**
     * CCITT modified Huffman run lengths (code 2).
     */
    CCITT_RLE(2),

    /**
     * CCITT T.4 Group 3 fax coding (code 3).
     */
    CCITT_T4(3),

    /**
     * CCITT T.6 Group 4 fax coding (code 4).
     */
    CCITT_T6(4),

    /**
     * Lempel-Ziv-Welch (code 5).
     */
    LZW(5),

    /**
     * Old-style JPEG, TIFF 6.0 section 22 (code 6).
     */
    OLD_JPEG(6),

    /**
     * JPEG as TIFF Technical Note 2 defines it (code 7).
     */
    JPEG(7),

    /**
     * Deflate (zlib), code 8, or the older code 32946, which decodes the same.
     */
    DEFLATE(8),

    /**
     * CCITT modified Huffman run lengths with word alignment (code 32771).
     */
    CCITT_RLEW(32771),

    /**
     * PackBits run lengths (code 32773).
     */
    PACKBITS(32773),

    /**
     * ThunderScan 4-bit coding (code 32809).
     */
    THUNDERSCAN(32809),

    /**
     * JBIG (code 34661).
     */
    JBIG(34661),

    /**
     * SGI LogL and LogLuv (codes 34676 and 34677).
     */
    SGILOG(34676),

    /**
     * JPEG 2000 (code 34712).
     */
    JPEG_2000(34712),

    /**
     * LZMA2 (code 34925).
     */
    LZMA(34925),

    /**
     * Zstandard (code 50000).
     */
    ZSTD(50000),

    /**
     * WebP (code 50001).
     */
    WEBP(50001),

    /**
     * JPEG XL (code 50002).
     */
    JPEG_XL(50002),

    /**
     * A scheme this enumeration does not name.
     */
    OTHER(-1);

    /**
     * The value of the {@code Compression} tag.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the tag value
     */
    TiffCompression(int code) {
        this.code = code;
    }

    /**
     * Returns the value of the {@code Compression} tag for this scheme.
     *
     * @return the tag value, or {@code -1} for {@link #OTHER}
     */
    public int code() {
        return code;
    }

    /**
     * Returns the scheme of a {@code Compression} tag value.
     *
     * @param code the tag value
     * @return the scheme, {@link #OTHER} when unknown
     */
    public static TiffCompression of(int code) {
        if (code == 32946) {
            return DEFLATE;
        }
        if (code == 34677) {
            return SGILOG;
        }
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return OTHER;
    }
}
