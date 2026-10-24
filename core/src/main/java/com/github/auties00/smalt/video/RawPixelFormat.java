package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.ChromaFormat;

/**
 * Enumerates the sample layouts of uncompressed video frames that {@link RawVideoFormat} describes.
 *
 * <p>Planar formats store each component in its own plane, one after the other; their samples are one byte at a bit
 * depth of 8 and two bytes (little-endian unless the {@link RawVideoFormat#bigEndian()} flag is set) at deeper bit
 * depths, from 9 to 16. Packed formats interleave the components of a pixel, or of a pair of pixels for the 4:2:2
 * formats, in the byte order their name gives; their bit depth is fixed. Chroma subsampled widths and heights round
 * up.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum RawPixelFormat {
    /**
     * Planar YUV 4:2:0: the Y plane, then the U (Cb) and V (Cr) planes at half width and half height
     * ({@code I420}).
     */
    YUV420P(Kind.PLANAR, ChromaFormat.YUV420, 0),

    /**
     * Planar YUV 4:2:0 with the V plane before the U plane ({@code YV12}).
     */
    YVU420P(Kind.PLANAR, ChromaFormat.YUV420, 0),

    /**
     * Planar YUV 4:2:2: chroma planes at half width.
     */
    YUV422P(Kind.PLANAR, ChromaFormat.YUV422, 0),

    /**
     * Planar YUV 4:4:4.
     */
    YUV444P(Kind.PLANAR, ChromaFormat.YUV444, 0),

    /**
     * Planar YUV 4:4:0: chroma planes at half height.
     */
    YUV440P(Kind.PLANAR, ChromaFormat.YUV440, 0),

    /**
     * Planar YUV 4:1:1: chroma planes at a quarter of the width; decoded to 4:2:2 by repeating each chroma sample
     * horizontally.
     */
    YUV411P(Kind.PLANAR, ChromaFormat.YUV422, 0),

    /**
     * Planar YUV 4:2:0 followed by a full-size alpha plane.
     */
    YUVA420P(Kind.PLANAR, ChromaFormat.YUV420, 0),

    /**
     * Planar YUV 4:4:4 followed by a full-size alpha plane.
     */
    YUVA444P(Kind.PLANAR, ChromaFormat.YUV444, 0),

    /**
     * One luma plane ({@code Y800}, {@code GREY}).
     */
    GRAY(Kind.PLANAR, ChromaFormat.MONOCHROME, 0),

    /**
     * 8-bit YUV 4:2:0 with a Y plane and one plane of interleaved U and V samples ({@code NV12}).
     */
    NV12(Kind.SEMI_PLANAR, ChromaFormat.YUV420, 8),

    /**
     * 8-bit YUV 4:2:0 with a Y plane and one plane of interleaved V and U samples ({@code NV21}).
     */
    NV21(Kind.SEMI_PLANAR, ChromaFormat.YUV420, 8),

    /**
     * Packed 8-bit YUV 4:2:2 in the byte order U, Y, V, Y ({@code UYVY}, QuickTime {@code 2vuy}).
     */
    UYVY422(Kind.PACKED_422, ChromaFormat.YUV422, 8),

    /**
     * Packed 8-bit YUV 4:2:2 in the byte order Y, U, Y, V ({@code YUY2}, QuickTime {@code yuvs}).
     */
    YUYV422(Kind.PACKED_422, ChromaFormat.YUV422, 8),

    /**
     * Packed 8-bit YUV 4:2:2 in the byte order Y, V, Y, U ({@code YVYU}).
     */
    YVYU422(Kind.PACKED_422, ChromaFormat.YUV422, 8),

    /**
     * Packed 10-bit YUV 4:2:2 in little-endian 32-bit words of three components, six pixels in 16 bytes, each row
     * padded to a multiple of 128 bytes ({@code v210}).
     */
    V210(Kind.V210, ChromaFormat.YUV422, 10),

    /**
     * Packed 8-bit red, green, blue.
     */
    RGB24(Kind.RGB, null, 8),

    /**
     * Packed 8-bit blue, green, red.
     */
    BGR24(Kind.RGB, null, 8),

    /**
     * Packed 8-bit red, green, blue, alpha.
     */
    RGBA(Kind.RGB, null, 8),

    /**
     * Packed 8-bit blue, green, red, alpha.
     */
    BGRA(Kind.RGB, null, 8),

    /**
     * Packed 8-bit alpha, red, green, blue.
     */
    ARGB(Kind.RGB, null, 8),

    /**
     * Packed 8-bit alpha, blue, green, red.
     */
    ABGR(Kind.RGB, null, 8),

    /**
     * Packed 8-bit red, green, blue and an unused byte.
     */
    RGB0(Kind.RGB, null, 8),

    /**
     * Packed 8-bit blue, green, red and an unused byte.
     */
    BGR0(Kind.RGB, null, 8),

    /**
     * 16-bit little-endian pixels of 5 bits of red, 6 of green and 5 of blue, red in the high bits.
     */
    RGB565LE(Kind.RGB, null, 8),

    /**
     * 16-bit big-endian pixels of 5 bits of red, 6 of green and 5 of blue, red in the high bits.
     */
    RGB565BE(Kind.RGB, null, 8),

    /**
     * 16-bit little-endian pixels of an unused bit and 5 bits each of red, green and blue, red in the high bits.
     */
    RGB555LE(Kind.RGB, null, 8),

    /**
     * 16-bit big-endian pixels of an unused bit and 5 bits each of red, green and blue, red in the high bits.
     */
    RGB555BE(Kind.RGB, null, 8),

    /**
     * Packed 16-bit big-endian red, green, blue (QuickTime {@code b48r}).
     */
    RGB48BE(Kind.RGB, null, 16),

    /**
     * Packed 16-bit little-endian red, green, blue.
     */
    RGB48LE(Kind.RGB, null, 16),

    /**
     * Packed 16-bit big-endian red, green, blue, alpha.
     */
    RGBA64BE(Kind.RGB, null, 16),

    /**
     * Packed 16-bit big-endian alpha, red, green, blue (QuickTime {@code b64a}).
     */
    ARGB64BE(Kind.RGB, null, 16),

    /**
     * 8-bit indices into the {@linkplain RawVideoFormat#palette() palette} of the format.
     */
    PAL8(Kind.PALETTE, null, 8);

    /**
     * The storage family of a format.
     */
    enum Kind {
        /**
         * One plane per component.
         */
        PLANAR,

        /**
         * A luma plane and an interleaved chroma plane.
         */
        SEMI_PLANAR,

        /**
         * Pairs of pixels in four bytes.
         */
        PACKED_422,

        /**
         * The {@code v210} word packing.
         */
        V210,

        /**
         * Packed RGB pixels.
         */
        RGB,

        /**
         * Palette indices.
         */
        PALETTE
    }

    private final Kind kind;

    private final ChromaFormat chroma;

    private final int fixedDepth;

    RawPixelFormat(Kind kind, ChromaFormat chroma, int fixedDepth) {
        this.kind = kind;
        this.chroma = chroma;
        this.fixedDepth = fixedDepth;
    }

    /**
     * {@return the storage family}
     */
    Kind kind() {
        return kind;
    }

    /**
     * {@return whether the format stores YUV samples}
     */
    public boolean isYuv() {
        return chroma != null;
    }

    /**
     * Returns the chroma subsampling of the pictures the format decodes to.
     *
     * @return the chroma format, {@link ChromaFormat#MONOCHROME} for {@link #GRAY}, or {@code null} for the RGB and
     * palette formats
     */
    public ChromaFormat chromaFormat() {
        return chroma;
    }

    /**
     * {@return whether the format carries an alpha component}
     */
    public boolean hasAlpha() {
        return switch (this) {
            case YUVA420P, YUVA444P, RGBA, BGRA, ARGB, ABGR, RGBA64BE, ARGB64BE, PAL8 -> true;
            default -> false;
        };
    }

    /**
     * {@return whether the bit depth of the format may be chosen, from 8 to 16: true for the planar formats}
     */
    public boolean hasVariableDepth() {
        return kind == Kind.PLANAR;
    }

    /**
     * {@return the bit depth of every component of a format whose depth is fixed, or 8 for the planar formats}
     */
    public int defaultBitDepth() {
        return fixedDepth == 0 ? 8 : fixedDepth;
    }
}
