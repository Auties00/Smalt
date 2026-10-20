package com.github.auties00.smalt.image.tiff;

/**
 * Enumerates the TIFF compression schemes, as the {@code Compression} tag (259) names them.
 *
 * <p>{@link TiffDecoder} decodes every scheme but {@link #OTHER} ({@link #OLD_JPEG} when its stream
 * or tables can be read); {@link TiffEncoder} writes {@link #NONE}, {@link #PACKBITS}, {@link #LZW},
 * {@link #DEFLATE}, {@link #ZSTD}, {@link #WEBP} and {@link #JPEG_2000}.
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
     * NeXT 2-bit gray run lengths (code 32766).
     */
    NEXT(32766),

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
     * PixarLog, companded 11-bit codes in zlib (code 32909).
     */
    PIXARLOG(32909),

    /**
     * JBIG (code 34661), each strip or tile a bi-level image entity of ITU-T T.82.
     */
    JBIG(34661),

    /**
     * SGI LogL and LogLuv (codes 34676 and 34677).
     */
    SGILOG(34676),

    /**
     * JPEG 2000 (code 34712), each strip or tile a codestream or a JP2 file; also the Aperio codes
     * 33003 (Y, Cb and Cr components, converted to RGB) and 33005 (RGB components), which decode
     * the same.
     */
    JPEG_2000(34712),

    /**
     * LERC (code 34887), each strip or tile a LERC blob, wrapped in Deflate or Zstandard when the
     * {@code LercParameters} tag (50674) says so.
     */
    LERC(34887),

    /**
     * LZMA2 (code 34925), each strip or tile an XZ stream.
     */
    LZMA(34925),

    /**
     * Zstandard (code 50000), each strip or tile a Zstandard frame; also the code 34926, which decodes the
     * same.
     */
    ZSTD(50000),

    /**
     * WebP (code 50001), each strip or tile a WebP file of 8-bit RGB or RGBA samples.
     */
    WEBP(50001),

    /**
     * JPEG XL (code 50002), each strip or tile a codestream or a container; also the DNG 1.7 code
     * 52546, which decodes the same.
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
        if (code == 33003 || code == 33005) {
            return JPEG_2000;
        }
        if (code == 52546) {
            return JPEG_XL;
        }
        if (code == 34926) {
            return ZSTD;
        }
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return OTHER;
    }
}
