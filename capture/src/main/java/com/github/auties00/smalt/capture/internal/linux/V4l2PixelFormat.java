package com.github.auties00.smalt.capture.internal.linux;

/**
 * Enumerates the Video4Linux2 pixel formats this backend converts to planar 4:2:0, in order of
 * preference.
 *
 * <p>Planar and semi-planar 4:2:0 come first because they convert with plain copies, then the
 * 4:2:2 and 4:4:4 layouts whose chroma only needs averaging, then R'G'B', which needs a matrix,
 * then Motion-JPEG, whose every frame must be decoded, and last the luma-only formats, which carry
 * no color. A format whose code ends in {@code M} keeps each plane in its own memory plane and is
 * offered only by multi-planar devices. Each constant carries the byte positions of its samples:
 * within a 4-byte macropixel of two pixels for the packed 4:2:2 formats, within a chroma pair for
 * the semi-planar ones, the order of the chroma planes for the planar ones, and within a pixel for
 * the R'G'B' ones.
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
     * {@code V4L2_PIX_FMT_YUV420M} ({@code YM12}): Y, Cb and Cr at 4:2:0 in three memory planes.
     */
    YUV420M('Y', 'M', '1', '2', 3, Kind.PLANAR, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_YVU420M} ({@code YM21}): Y, Cr and Cb at 4:2:0 in three memory planes.
     */
    YVU420M('Y', 'M', '2', '1', 3, Kind.PLANAR, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_NV12M} ({@code NM12}): NV12 with its two planes in two memory planes.
     */
    NV12M('N', 'M', '1', '2', 2, Kind.SEMI_PLANAR, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_NV21M} ({@code NM21}): NV21 with its two planes in two memory planes.
     */
    NV21M('N', 'M', '2', '1', 2, Kind.SEMI_PLANAR, 0, 1, 0),

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
     * {@code V4L2_PIX_FMT_NV16}: a Y plane followed by interleaved Cb, Cr pairs at 4:2:2.
     */
    NV16('N', 'V', '1', '6', Kind.SEMI_PLANAR_422, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_NV61}: a Y plane followed by interleaved Cr, Cb pairs at 4:2:2.
     */
    NV61('N', 'V', '6', '1', Kind.SEMI_PLANAR_422, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_NV16M} ({@code NM16}): NV16 with its two planes in two memory planes.
     */
    NV16M('N', 'M', '1', '6', 2, Kind.SEMI_PLANAR_422, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_NV61M} ({@code NM61}): NV61 with its two planes in two memory planes.
     */
    NV61M('N', 'M', '6', '1', 2, Kind.SEMI_PLANAR_422, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_YUV422P} ({@code 422P}): planar Y, Cb, Cr at 4:2:2.
     */
    YUV422P('4', '2', '2', 'P', Kind.PLANAR_422, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_YUV422M} ({@code YM16}): Y, Cb and Cr at 4:2:2 in three memory planes.
     */
    YUV422M('Y', 'M', '1', '6', 3, Kind.PLANAR_422, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_YVU422M} ({@code YM61}): Y, Cr and Cb at 4:2:2 in three memory planes.
     */
    YVU422M('Y', 'M', '6', '1', 3, Kind.PLANAR_422, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_NV24}: a Y plane followed by interleaved Cb, Cr pairs at 4:4:4.
     */
    NV24('N', 'V', '2', '4', Kind.SEMI_PLANAR_444, 0, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_NV42}: a Y plane followed by interleaved Cr, Cb pairs at 4:4:4.
     */
    NV42('N', 'V', '4', '2', Kind.SEMI_PLANAR_444, 0, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_XRGB32} ({@code BX24}): bytes {@code X R G B}.
     */
    XRGB32('B', 'X', '2', '4', Kind.RGB, 4, 1, 2, 3),

    /**
     * {@code V4L2_PIX_FMT_ARGB32} ({@code BA24}): bytes {@code A R G B}, the alpha ignored.
     */
    ARGB32('B', 'A', '2', '4', Kind.RGB, 4, 1, 2, 3),

    /**
     * {@code V4L2_PIX_FMT_XBGR32} ({@code XR24}): bytes {@code B G R X}.
     */
    XBGR32('X', 'R', '2', '4', Kind.RGB, 4, 2, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_ABGR32} ({@code AR24}): bytes {@code B G R A}, the alpha ignored.
     */
    ABGR32('A', 'R', '2', '4', Kind.RGB, 4, 2, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_RGBX32} ({@code XB24}): bytes {@code R G B X}.
     */
    RGBX32('X', 'B', '2', '4', Kind.RGB, 4, 0, 1, 2),

    /**
     * {@code V4L2_PIX_FMT_RGBA32} ({@code AB24}): bytes {@code R G B A}, the alpha ignored.
     */
    RGBA32('A', 'B', '2', '4', Kind.RGB, 4, 0, 1, 2),

    /**
     * {@code V4L2_PIX_FMT_BGRX32} ({@code RX24}): bytes {@code X B G R}.
     */
    BGRX32('R', 'X', '2', '4', Kind.RGB, 4, 3, 2, 1),

    /**
     * {@code V4L2_PIX_FMT_BGRA32} ({@code RA24}): bytes {@code A B G R}, the alpha ignored.
     */
    BGRA32('R', 'A', '2', '4', Kind.RGB, 4, 3, 2, 1),

    /**
     * {@code V4L2_PIX_FMT_RGB24} ({@code RGB3}): bytes {@code R G B}.
     */
    RGB24('R', 'G', 'B', '3', Kind.RGB, 3, 0, 1, 2),

    /**
     * {@code V4L2_PIX_FMT_BGR24} ({@code BGR3}): bytes {@code B G R}.
     */
    BGR24('B', 'G', 'R', '3', Kind.RGB, 3, 2, 1, 0),

    /**
     * {@code V4L2_PIX_FMT_RGB565} ({@code RGBP}): a little-endian 16-bit value per pixel, red in
     * the top 5 bits, green in the middle 6, blue in the low 5.
     */
    RGB565('R', 'G', 'B', 'P', Kind.RGB, 2, 0, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_MJPEG}: Motion-JPEG, one JPEG image per frame, possibly without Huffman
     * tables.
     */
    MJPEG('M', 'J', 'P', 'G', Kind.JPEG, 0, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_JPEG}: one JFIF JPEG image per frame.
     */
    JPEG('J', 'P', 'E', 'G', Kind.JPEG, 0, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_GREY}: 8-bit luma.
     */
    GREY('G', 'R', 'E', 'Y', Kind.LUMA, 1, 8, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_Y16} ({@code Y16 }): 16-bit little-endian luma.
     */
    Y16('Y', '1', '6', ' ', Kind.LUMA, 2, 16, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_Y16_BE}: 16-bit big-endian luma, the code of {@link #Y16} with its top bit
     * set.
     */
    Y16_BE(V4l2.fourcc('Y', '1', '6', ' ') | 0x80000000, Kind.LUMA, 2, 16, 1, 0, 1),

    /**
     * {@code V4L2_PIX_FMT_Y12} ({@code Y12 }): 12-bit luma in the low bits of a little-endian 16-bit
     * value.
     */
    Y12('Y', '1', '2', ' ', Kind.LUMA, 2, 12, 0, 0),

    /**
     * {@code V4L2_PIX_FMT_Y10} ({@code Y10 }): 10-bit luma in the low bits of a little-endian 16-bit
     * value.
     */
    Y10('Y', '1', '0', ' ', Kind.LUMA, 2, 10, 0, 0);

    /**
     * The four-character code.
     */
    private final int fourcc;

    /**
     * The sample arrangement.
     */
    private final Kind kind;

    /**
     * The byte position of the first luma sample in a packed macropixel, of R in an R'G'B' pixel,
     * or the number of significant bits of a luma sample.
     */
    private final int lumaIndex;

    /**
     * The byte position of Cb in a packed macropixel or a semi-planar chroma pair, the plane
     * number ({@code 0} first, {@code 1} second) of a planar format, the byte position of G in an
     * R'G'B' pixel, or {@code 1} for a big-endian luma sample.
     */
    private final int cbIndex;

    /**
     * The byte position of Cr, with the meaning of {@link #cbIndex}, or of B in an R'G'B' pixel.
     */
    private final int crIndex;

    /**
     * The bytes per pixel of the R'G'B' and luma formats, else {@code 0}.
     */
    private final int bytesPerPixel;

    /**
     * The number of memory planes of a multi-planar buffer: {@code 1} unless the code ends in
     * {@code M}.
     */
    private final int memoryPlanes;

    /**
     * Describes a format of one memory plane without a pixel size.
     *
     * @param a the first character of the code
     * @param b the second character of the code
     * @param c the third character of the code
     * @param d the fourth character of the code
     * @param kind the sample arrangement
     * @param lumaIndex the byte position of the first luma sample in a packed macropixel
     * @param cbIndex the position of Cb
     * @param crIndex the position of Cr
     */
    V4l2PixelFormat(char a, char b, char c, char d, Kind kind, int lumaIndex, int cbIndex, int crIndex) {
        this(V4l2.fourcc(a, b, c, d), kind, 0, lumaIndex, cbIndex, crIndex, 1);
    }

    /**
     * Describes a format of several memory planes.
     *
     * @param memoryPlanes the number of memory planes
     */
    V4l2PixelFormat(char a, char b, char c, char d, int memoryPlanes, Kind kind, int lumaIndex, int cbIndex, int crIndex) {
        this(V4l2.fourcc(a, b, c, d), kind, 0, lumaIndex, cbIndex, crIndex, memoryPlanes);
    }

    /**
     * Describes an R'G'B' or luma format.
     *
     * @param bytesPerPixel the bytes per pixel
     * @param first the position of R, or the significant bits of luma
     * @param second the position of G, or {@code 1} for big-endian luma
     * @param third the position of B
     */
    V4l2PixelFormat(char a, char b, char c, char d, Kind kind, int bytesPerPixel, int first, int second, int third) {
        this(V4l2.fourcc(a, b, c, d), kind, bytesPerPixel, first, second, third, 1);
    }

    /**
     * Describes a format by its numeric code.
     */
    V4l2PixelFormat(int fourcc, Kind kind, int bytesPerPixel, int lumaIndex, int cbIndex, int crIndex, int memoryPlanes) {
        this.fourcc = fourcc;
        this.kind = kind;
        this.bytesPerPixel = bytesPerPixel;
        this.lumaIndex = lumaIndex;
        this.cbIndex = cbIndex;
        this.crIndex = crIndex;
        this.memoryPlanes = memoryPlanes;
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
     * Returns the byte position of the first luma sample in a packed macropixel, or of R in an
     * R'G'B' pixel.
     *
     * @return the position
     */
    int lumaIndex() {
        return lumaIndex;
    }

    /**
     * Returns the position of Cb in a packed macropixel, a semi-planar pair, or the order of the
     * chroma planes; the position of G in an R'G'B' pixel.
     *
     * @return the position
     */
    int cbIndex() {
        return cbIndex;
    }

    /**
     * Returns the position of Cr in a packed macropixel, a semi-planar pair, or the order of the
     * chroma planes; the position of B in an R'G'B' pixel.
     *
     * @return the position
     */
    int crIndex() {
        return crIndex;
    }

    /**
     * Returns the bytes per pixel of an R'G'B' or luma format.
     *
     * @return 1 to 4, or {@code 0} for the other kinds
     */
    int bytesPerPixel() {
        return bytesPerPixel;
    }

    /**
     * Returns the number of significant bits of a luma-only sample.
     *
     * @return 8 to 16, meaningful for {@link Kind#LUMA} only
     */
    int significantBits() {
        return lumaIndex;
    }

    /**
     * Returns whether a 16-bit luma-only sample is stored most significant byte first.
     *
     * @return whether the sample is big-endian, meaningful for {@link Kind#LUMA} only
     */
    boolean bigEndian() {
        return cbIndex == 1;
    }

    /**
     * Returns the number of memory planes a multi-planar buffer of this format has.
     *
     * @return {@code 1}, or the plane count of a format whose code ends in {@code M}
     */
    int memoryPlanes() {
        return memoryPlanes;
    }

    /**
     * Returns the number of image planes: luma and chroma, wherever they are stored.
     *
     * @return 1 to 3
     */
    int imagePlanes() {
        return switch (kind) {
            case PLANAR, PLANAR_422 -> 3;
            case SEMI_PLANAR, SEMI_PLANAR_422, SEMI_PLANAR_444 -> 2;
            case PACKED, RGB, LUMA, JPEG -> 1;
        };
    }

    /**
     * Returns whether a single-planar device can deliver this format.
     *
     * @return whether the format keeps all its planes in one memory plane
     */
    boolean contiguous() {
        return memoryPlanes == 1;
    }

    /**
     * Enumerates the sample arrangements.
     */
    enum Kind {
        /**
         * Three planes at 4:2:0; contiguous chroma rows are half the luma stride apart.
         */
        PLANAR,

        /**
         * Three planes at 4:2:2; contiguous chroma rows are half the luma stride apart.
         */
        PLANAR_422,

        /**
         * A luma plane and one plane of interleaved chroma pairs at 4:2:0, with the luma stride.
         */
        SEMI_PLANAR,

        /**
         * A luma plane and one plane of interleaved chroma pairs at 4:2:2, with the luma stride.
         */
        SEMI_PLANAR_422,

        /**
         * A luma plane and one plane of interleaved chroma pairs at 4:4:4, with twice the luma
         * stride.
         */
        SEMI_PLANAR_444,

        /**
         * One plane of 4:2:2 macropixels, two pixels in four bytes.
         */
        PACKED,

        /**
         * One plane of R'G'B' pixels of 2, 3 or 4 bytes.
         */
        RGB,

        /**
         * One plane of luma samples of 1 or 2 bytes.
         */
        LUMA,

        /**
         * One compressed JPEG image per frame.
         */
        JPEG
    }
}
