package com.github.auties00.smalt.capture.internal.windows;

/**
 * Lists the uncompressed and Motion-JPEG pixel formats the camera backend converts, in the
 * Media Foundation subtype that carries each one.
 *
 * <p>A Media Foundation video subtype is the GUID {@code XXXXXXXX-0000-0010-8000-00AA00389B71}
 * whose first field is a FourCC (for YUV and Motion-JPEG) or a {@code D3DFORMAT} value (for RGB).
 * The {@link #rank()} orders formats by preference when a camera offers several at the same size
 * and rate: planar and semi-planar 4:2:0 need only copies, packed 4:2:2 a cheap chroma average,
 * Motion-JPEG a full decode, and RGB a color conversion that also loses the camera's own
 * Y'CbCr encoding.
 */
enum CameraFormat {
    /**
     * Semi-planar 4:2:0: the luma plane followed by one plane of interleaved Cb/Cr pairs.
     */
    NV12(fourcc('N', 'V', '1', '2'), 0),

    /**
     * Planar 4:2:0: luma, then Cb, then Cr, the chroma rows half the luma stride.
     */
    I420(fourcc('I', '4', '2', '0'), 1),

    /**
     * Planar 4:2:0 identical to {@link #I420}.
     */
    IYUV(fourcc('I', 'Y', 'U', 'V'), 1),

    /**
     * Planar 4:2:0 with the Cr plane before the Cb plane.
     */
    YV12(fourcc('Y', 'V', '1', '2'), 1),

    /**
     * Packed 4:2:2 in the byte order {@code Y0 Cb Y1 Cr}.
     */
    YUY2(fourcc('Y', 'U', 'Y', '2'), 2),

    /**
     * Packed 4:2:2 identical to {@link #YUY2}.
     */
    YUYV(fourcc('Y', 'U', 'Y', 'V'), 2),

    /**
     * Packed 4:2:2 in the byte order {@code Cb Y0 Cr Y1}.
     */
    UYVY(fourcc('U', 'Y', 'V', 'Y'), 3),

    /**
     * Motion-JPEG: every sample is a complete JPEG image.
     */
    MJPG(fourcc('M', 'J', 'P', 'G'), 4),

    /**
     * Packed 32-bit {@code B G R X}, the X byte undefined ({@code D3DFMT_X8R8G8B8}).
     */
    RGB32(22, 5),

    /**
     * Packed 32-bit {@code B G R A} ({@code D3DFMT_A8R8G8B8}); alpha is ignored.
     */
    ARGB32(21, 5),

    /**
     * Packed 24-bit {@code B G R} ({@code D3DFMT_R8G8B8}).
     */
    RGB24(20, 6);

    /**
     * The first field of the subtype GUID.
     */
    private final int code;

    /**
     * The preference rank, lower is better.
     */
    private final int rank;

    /**
     * Describes a format.
     *
     * @param code the first field of the subtype GUID
     * @param rank the preference rank, lower is better
     */
    CameraFormat(int code, int rank) {
        this.code = code;
        this.rank = rank;
    }

    /**
     * Packs four ASCII characters into a little-endian FourCC.
     *
     * @param a the first character
     * @param b the second character
     * @param c the third character
     * @param d the fourth character
     * @return the FourCC
     */
    private static int fourcc(char a, char b, char c, char d) {
        return a | b << 8 | c << 16 | d << 24;
    }

    /**
     * Returns the format of a subtype.
     *
     * @param code the first field of the subtype GUID, whose other fields are the video subtype
     *             base
     * @return the format, or {@code null} when the backend does not convert it
     */
    static CameraFormat of(int code) {
        for (var format : values()) {
            if (format.code == code) {
                return format;
            }
        }
        return null;
    }

    /**
     * Returns the first field of the subtype GUID.
     *
     * @return the FourCC or {@code D3DFORMAT} value
     */
    int code() {
        return code;
    }

    /**
     * Returns the preference rank.
     *
     * @return the rank, lower is better
     */
    int rank() {
        return rank;
    }

    /**
     * Returns whether the format carries RGB rather than Y'CbCr samples.
     *
     * @return whether the format is RGB
     */
    boolean rgb() {
        return this == RGB32 || this == ARGB32 || this == RGB24;
    }

    /**
     * Returns the minimum number of bytes of one row of the first plane.
     *
     * @param width the width in pixels
     * @return the row length in bytes
     */
    long rowBytes(int width) {
        return switch (this) {
            case NV12, I420, IYUV, YV12 -> width;
            case YUY2, YUYV, UYVY -> 2L * ((width + 1) & ~1);
            case RGB32, ARGB32 -> 4L * width;
            case RGB24 -> 3L * width;
            case MJPG -> 0;
        };
    }
}
