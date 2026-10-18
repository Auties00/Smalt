package com.github.auties00.smalt.capture.internal.linux;

/**
 * Enumerates the Video4Linux2 pixel formats this backend converts to planar 4:2:0, in order of
 * preference.
 *
 * <p>Planar and semi-planar 4:2:0 come first because they convert with plain copies, packed 4:2:2
 * next because it only needs its chroma averaged, and Motion-JPEG last because every frame must be
 * decoded. Each constant carries the byte positions of its samples: within a 4-byte macropixel of
 * two pixels for the packed formats, within a chroma pair for the semi-planar ones.
 */
enum V4l2PixelFormat {
    /**
     * {@code V4L2_PIX_FMT_YUV420} ({@code YU12}): planar Y, Cb, Cr at 4:2:0.
     */
    YUV420('Y', 'U', '1', '2', Kind.PLANAR, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_YVU420} ({@code YV12}): planar Y, Cr, Cb at 4:2:0.
     */
    YVU420('Y', 'V', '1', '2', Kind.PLANAR, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_NV12}: a Y plane followed by interleaved Cb, Cr pairs at 4:2:0.
     */
    NV12('N', 'V', '1', '2', Kind.SEMI_PLANAR, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_NV21}: a Y plane followed by interleaved Cr, Cb pairs at 4:2:0.
     */
    NV21('N', 'V', '2', '1', Kind.SEMI_PLANAR, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_YUYV}: packed 4:2:2, bytes {@code Y0 Cb Y1 Cr}.
     */
    YUYV('Y', 'U', 'Y', 'V', Kind.PACKED, 0, 1, 3),

    /**
     * {@code V4L2_PIX_FMT_YVYU}: packed 4:2:2, bytes {@code Y0 Cr Y1 Cb}.
     */
    YVYU('Y', 'V', 'Y', 'U', Kind.PACKED, 0, 3, 1),

    /**
     * {@code V4L2_PIX_FMT_UYVY}: packed 4:2:2, bytes {@code Cb Y0 Cr Y1}.
     */
    UYVY('U', 'Y', 'V', 'Y', Kind.PACKED, 1, 0, 2),

    /**
     * {@code V4L2_PIX_FMT_VYUY}: packed 4:2:2, bytes {@code Cr Y0 Cb Y1}.
     */
    VYUY('V', 'Y', 'U', 'Y', Kind.PACKED, 1, 2, 0),

    /**
     * {@code V4L2_PIX_FMT_MJPEG}: Motion-JPEG, one JPEG image per frame, possibly without Huffman
     * tables.
     */
    MJPEG('M', 'J', 'P', 'G', Kind.JPEG, 0, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_JPEG}: one JFIF JPEG image per frame.
     */
    JPEG('J', 'P', 'E', 'G', Kind.JPEG, 0, 0, 0);

    /**
     * The four-character code.
     */
    private final int fourcc;

    /**
     * The sample arrangement.
     */
    private final Kind kind;

    /**
     * The byte position of the first luma sample in a packed macropixel.
     */
    private final int lumaIndex;

    /**
     * The byte position of Cb: in a packed macropixel, in a semi-planar chroma pair, or the plane
     * number ({@code 0} first, {@code 1} second) of a planar format.
     */
    private final int cbIndex;

    /**
     * The byte position of Cr, with the meaning of {@link #cbIndex}.
     */
    private final int crIndex;

    /**
     * Describes a pixel format.
     *
     * @param a         the first character of the code
     * @param b         the second character of the code
     * @param c         the third character of the code
     * @param d         the fourth character of the code
     * @param kind      the sample arrangement
     * @param lumaIndex the byte position of the first luma sample in a packed macropixel
     * @param cbIndex   the position of Cb
     * @param crIndex   the position of Cr
     */
    V4l2PixelFormat(char a, char b, char c, char d, Kind kind, int lumaIndex, int cbIndex, int crIndex) {
        this.fourcc = V4l2.fourcc(a, b, c, d);
        this.kind = kind;
        this.lumaIndex = lumaIndex;
        this.cbIndex = cbIndex;
        this.crIndex = crIndex;
    }

    /**
     * Returns the format with a four-character code.
     *
     * @param fourcc the code
     * @return the format, or {@code null} when this backend does not convert it
     */
    static V4l2PixelFormat of(int fourcc) {
        for (var format : values()) {
            if (format.fourcc == fourcc) {
                return format;
            }
        }
        return null;
    }

    /**
     * Returns the four-character code.
     *
     * @return the code
     */
    int fourcc() {
        return fourcc;
    }

    /**
     * Returns the sample arrangement.
     *
     * @return the arrangement
     */
    Kind kind() {
        return kind;
    }

    /**
     * Returns the byte position of the first luma sample in a packed macropixel.
     *
     * @return {@code 0} or {@code 1}
     */
    int lumaIndex() {
        return lumaIndex;
    }

    /**
     * Returns the position of Cb in a packed macropixel, a semi-planar pair, or the order of the
     * chroma planes.
     *
     * @return the position
     */
    int cbIndex() {
        return cbIndex;
    }

    /**
     * Returns the position of Cr in a packed macropixel, a semi-planar pair, or the order of the
     * chroma planes.
     *
     * @return the position
     */
    int crIndex() {
        return crIndex;
    }

    /**
     * Enumerates the sample arrangements.
     */
    enum Kind {
        /**
         * Three planes at 4:2:0; the chroma rows are half the luma stride apart.
         */
        PLANAR,

        /**
         * A luma plane followed by one plane of interleaved chroma pairs at 4:2:0, with the luma
         * stride.
         */
        SEMI_PLANAR,

        /**
         * One plane of 4:2:2 macropixels, two pixels in four bytes.
         */
        PACKED,

        /**
         * One compressed JPEG image per frame.
         */
        JPEG
    }
}
