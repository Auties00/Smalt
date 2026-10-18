package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.MjpegReducer;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Converts the frames of one camera media type to planar 4:2:0 pictures.
 *
 * <p>Uncompressed frames are read from the locked sample buffer row by row: planar and
 * semi-planar 4:2:0 luma is copied straight into the picture, and every other layout goes through
 * a small heap buffer and a {@link ConversionKernel}. Motion-JPEG frames are copied out of the
 * buffer and reduced to 4:2:0 by a {@link MjpegReducer}, then converted with {@link ImageOps} when
 * their color differs from the type's. Pictures have the type's size cropped to even dimensions,
 * and the color the type declares.
 * Every read of native memory is bounds-checked against the buffer, so a buffer smaller than its
 * type announces raises {@link IndexOutOfBoundsException} instead of reading past it.
 */
final class CameraConverter {
    private final ConversionKernel kernel = ConversionKernel.INSTANCE;

    /**
     * The reducer of Motion-JPEG frames, or {@code null} for the uncompressed formats.
     */
    private final MjpegReducer mjpeg;

    /**
     * The conversion of R'G'B' frames, or {@code null} for the Y'CbCr formats.
     */
    private final YuvCoefficients coefficients;

    /**
     * The media type of the frames.
     */
    private final NativeType type;

    /**
     * The rows being converted.
     */
    private byte[] rows = new byte[0];

    /**
     * The Motion-JPEG frame being decoded.
     */
    private byte[] compressed = new byte[0];

    /**
     * Creates a converter for a media type.
     *
     * @param type the media type of the frames
     */
    CameraConverter(NativeType type) {
        this.type = type;
        this.mjpeg = type.format() == CameraFormat.MJPG ? new MjpegReducer(kernel) : null;
        this.coefficients = type.format().rgb() ? YuvCoefficients.of(type.color()) : null;
    }

    /**
     * Returns the media type of the frames.
     *
     * @return the type
     */
    NativeType type() {
        return type;
    }

    /**
     * Returns the width of the pictures, the type's width cropped to even.
     *
     * @return the width
     */
    int width() {
        return type.width() & ~1;
    }

    /**
     * Returns the height of the pictures, the type's height cropped to even.
     *
     * @return the height
     */
    int height() {
        return type.height() & ~1;
    }

    /**
     * Returns the signed row stride of a contiguous buffer of the type.
     *
     * @return the stride the type declares, or the minimum stride of its format when it declares
     * none
     */
    long contiguousPitch() {
        return type.defaultStride() != 0 ? type.defaultStride() : type.format().rowBytes(type.width());
    }

    /**
     * Returns the number of bytes an uncompressed frame of the type spans with a row pitch.
     *
     * @param pitch the absolute row pitch of the first plane
     * @return the span from the first byte of the first row to the end of the last plane
     */
    long extent(long pitch) {
        var height = (long) type.height();
        var chromaRows = (height + 1) / 2;
        return switch (type.format()) {
            case NV12 -> pitch * (height + chromaRows);
            case I420, IYUV, YV12 -> pitch * height + 2 * (pitch / 2) * chromaRows;
            default -> pitch * height;
        };
    }

    /**
     * Converts an uncompressed frame.
     *
     * @param memory the locked buffer
     * @param origin the offset of the first row of the first plane in {@code memory}
     * @param pitch the signed byte distance between rows of the first plane, negative for a
     * bottom-up RGB image
     * @param out the picture receiving the frame, of {@link #width()} by {@link #height()}
     * @throws IndexOutOfBoundsException if the frame does not fit in {@code memory}
     */
    void convert(MemorySegment memory, long origin, long pitch, YuvPicture out) {
        var width = out.width();
        var height = out.height();
        switch (type.format()) {
            case NV12 -> {
                copyLuma(memory, origin, pitch, out);
                var chroma = origin + pitch * type.height();
                ensureRows(width);
                for (var row = 0; row < height / 2; row++) {
                    MemorySegment.copy(memory, JAVA_BYTE, chroma + row * pitch, rows, 0, width);
                    kernel.deinterleave(rows, 0, width / 2, out.u(), out.v(), row * out.uvStride());
                }
            }
            case I420, IYUV, YV12 -> {
                copyLuma(memory, origin, pitch, out);
                var chromaPitch = pitch / 2;
                var first = origin + pitch * type.height();
                var second = first + chromaPitch * ((type.height() + 1) / 2);
                var cb = type.format() == CameraFormat.YV12 ? second : first;
                var cr = type.format() == CameraFormat.YV12 ? first : second;
                for (var row = 0; row < height / 2; row++) {
                    MemorySegment.copy(memory, JAVA_BYTE, cb + row * chromaPitch, out.u(), row * out.uvStride(), width / 2);
                    MemorySegment.copy(memory, JAVA_BYTE, cr + row * chromaPitch, out.v(), row * out.uvStride(), width / 2);
                }
            }
            case YUY2, YUYV, UYVY -> {
                var rowBytes = 2 * width;
                ensureRows(2 * rowBytes);
                var lumaIndex = type.format() == CameraFormat.UYVY ? 1 : 0;
                var cbIndex = 1 - lumaIndex;
                for (var row = 0; row < height; row += 2) {
                    MemorySegment.copy(memory, JAVA_BYTE, origin + row * pitch, rows, 0, rowBytes);
                    MemorySegment.copy(memory, JAVA_BYTE, origin + (row + 1) * pitch, rows, rowBytes, rowBytes);
                    kernel.packedLuma(rows, 0, width, lumaIndex, out.y(), row * out.yStride());
                    kernel.packedLuma(rows, rowBytes, width, lumaIndex, out.y(), (row + 1) * out.yStride());
                    kernel.packedChroma(rows, 0, rowBytes, width / 2, cbIndex, cbIndex + 2, out.u(), out.v(),
                            (row >> 1) * out.uvStride());
                }
            }
            case RGB32, ARGB32 -> {
                var rowBytes = 4 * width;
                ensureRows(2 * rowBytes);
                for (var row = 0; row < height; row += 2) {
                    MemorySegment.copy(memory, JAVA_BYTE, origin + row * pitch, rows, 0, rowBytes);
                    MemorySegment.copy(memory, JAVA_BYTE, origin + (row + 1) * pitch, rows, rowBytes, rowBytes);
                    bgrxToI420(rowBytes, width, out, row);
                }
            }
            case RGB24 -> {
                var rowBytes = 4 * width;
                var packedBytes = 3 * width;
                ensureRows(2 * rowBytes + 2 * packedBytes);
                var packed = 2 * rowBytes;
                for (var row = 0; row < height; row += 2) {
                    MemorySegment.copy(memory, JAVA_BYTE, origin + row * pitch, rows, packed, packedBytes);
                    MemorySegment.copy(memory, JAVA_BYTE, origin + (row + 1) * pitch, rows, packed + packedBytes, packedBytes);
                    expand(rows, packed, rows, 0, width);
                    expand(rows, packed + packedBytes, rows, rowBytes, width);
                    bgrxToI420(rowBytes, width, out, row);
                }
            }
            case MJPG -> throw new IllegalStateException("Motion-JPEG frames are converted by convertJpeg");
        }
    }

    /**
     * Converts the two rows of {@code B G R X} pixels at the start of the row buffer into the rows
     * {@code row} and {@code row + 1} of a picture.
     */
    private void bgrxToI420(int rowBytes, int width, YuvPicture out, int row) {
        kernel.rgbToLuma(rows, 0, width, RgbLayout.BGRX, coefficients, out.y(), row * out.yStride());
        kernel.rgbToLuma(rows, rowBytes, width, RgbLayout.BGRX, coefficients, out.y(), (row + 1) * out.yStride());
        kernel.rgbToChroma(rows, 0, rowBytes, width / 2, RgbLayout.BGRX, coefficients, out.u(), out.v(),
                (row >> 1) * out.uvStride());
    }

    /**
     * Copies the luma rows of a planar or semi-planar frame.
     *
     * @param memory the locked buffer
     * @param origin the offset of the first luma row
     * @param pitch the byte distance between luma rows
     * @param out the picture receiving the samples
     */
    private static void copyLuma(MemorySegment memory, long origin, long pitch, YuvPicture out) {
        for (var row = 0; row < out.height(); row++) {
            MemorySegment.copy(memory, JAVA_BYTE, origin + row * pitch, out.y(), row * out.yStride(), out.width());
        }
    }

    /**
     * Expands one row of packed 24-bit {@code B G R} pixels to 32-bit {@code B G R X}.
     *
     * @param src the packed pixels
     * @param sOff the index of the first packed byte
     * @param dst the expanded pixels
     * @param dOff the index of the first expanded byte
     * @param width the number of pixels
     */
    static void expand(byte[] src, int sOff, byte[] dst, int dOff, int width) {
        for (var x = 0; x < width; x++) {
            dst[dOff + 4 * x] = src[sOff + 3 * x];
            dst[dOff + 4 * x + 1] = src[sOff + 3 * x + 1];
            dst[dOff + 4 * x + 2] = src[sOff + 3 * x + 2];
            dst[dOff + 4 * x + 3] = (byte) 0xFF;
        }
    }

    /**
     * Decodes and converts a Motion-JPEG frame.
     *
     * @param memory the locked buffer holding the frame
     * @param length the number of bytes of the frame
     * @param out the picture receiving the frame, of {@link #width()} by {@link #height()}
     * @throws MediaException.Malformed if the frame is not a decodable JPEG image
     * @throws MediaException.Unsupported if the frame uses a JPEG feature the decoder lacks
     * @throws MediaException.LimitExceeded if the frame exceeds the default limits
     */
    void convertJpeg(MemorySegment memory, long length, YuvPicture out) {
        LimitChecks.checkAllocation(MediaLimits.DEFAULT, length);
        var size = (int) length;
        if (compressed.length < size) {
            compressed = new byte[size];
        }
        MemorySegment.copy(memory, JAVA_BYTE, 0, compressed, 0, size);
        var reduced = mjpeg.decode(compressed, 0, size);
        var planar = reduced.color().equals(out.color()) ? reduced : ImageOps.toYuv(reduced, ChromaFormat.YUV420, out.color());
        if (planar.width() == out.width() && planar.height() == out.height()) {
            Pictures.copy(planar, out);
        } else {
            Pictures.fit(planar, out);
        }
    }

    /**
     * Grows the row buffer to a length.
     *
     * @param length the required length
     */
    private void ensureRows(int length) {
        if (rows.length < length) {
            rows = new byte[length];
        }
    }
}
