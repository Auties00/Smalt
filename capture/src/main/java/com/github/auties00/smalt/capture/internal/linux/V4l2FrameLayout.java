package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Describes where the planes of one negotiated Video4Linux2 format lie in a driver buffer and in
 * the assembled frame handed to the {@link FrameConverter}, and copies buffers into that frame.
 *
 * <p>A driver buffer has one memory plane, or one per plane for the formats whose code ends in
 * {@code M} on a multi-planar device. Within a memory plane the image planes follow each other
 * without gaps, as the V4L2 specification defines: the planar chroma rows are half the luma stride
 * apart, the semi-planar 4:2:0 and 4:2:2 chroma rows one luma stride, the 4:4:4 ones twice.
 *
 * <p>The field order decides how buffers become frames:
 * <ul>
 * <li>a progressive or interleaved buffer is a frame;</li>
 * <li>a sequential buffer ({@code V4L2_FIELD_SEQ_TB} or {@code V4L2_FIELD_SEQ_BT}) holds the
 * rows of one field followed by the rows of the other in every plane, and is woven into a
 * frame;</li>
 * <li>a buffer of a single field ({@code V4L2_FIELD_TOP} or {@code V4L2_FIELD_BOTTOM}) has half
 * the frame's rows, and every row is doubled;</li>
 * <li>alternating buffers ({@code V4L2_FIELD_ALTERNATE}) each hold one field, named by the
 * buffer's {@code field}, and the top and bottom fields of one sequence number (the driver gives
 * both fields of a frame the same {@code sequence}) are woven into a frame, in either arrival
 * order.</li>
 * </ul>
 * In the assembled frame each image plane is stored once, at {@link #frameOffset(int)}, with the
 * rows of the woven fields stored one field after the other as in a sequential buffer.
 */
final class V4l2FrameLayout {
    /**
     * The row orders of a plane in the assembled frame.
     */
    enum RowOrder {
        /**
         * The rows are stored in display order.
         */
        PROGRESSIVE,

        /**
         * The rows of the top field (the even rows) are stored first, then those of the bottom field.
         */
        TOP_FIRST,

        /**
         * The rows of the bottom field (the odd rows) are stored first, then those of the top field.
         */
        BOTTOM_FIRST,

        /**
         * Only one field is stored, and each stored row is shown twice.
         */
        DOUBLED
    }

    /**
     * The captured pixel format.
     */
    private final V4l2PixelFormat format;

    /**
     * The frame width in pixels.
     */
    private final int width;

    /**
     * The frame height in pixels, twice the buffer's rows for single and alternating fields.
     */
    private final int height;

    /**
     * The row order of every plane in the assembled frame.
     */
    private final RowOrder order;

    /**
     * Whether frames are woven from alternating field buffers.
     */
    private final boolean alternate;

    /**
     * The memory plane of each image plane.
     */
    private final int[] memoryPlane;

    /**
     * The offset of each image plane in its memory plane.
     */
    private final long[] sourceOffset;

    /**
     * The row stride of each image plane.
     */
    private final int[] stride;

    /**
     * The rows of each image plane in one buffer.
     */
    private final int[] rows;

    /**
     * The bytes of the last row of each image plane a conversion reads.
     */
    private final int[] rowBytes;

    /**
     * The offset of each image plane in the assembled frame.
     */
    private final int[] frameOffset;

    /**
     * The size of the assembled frame, or {@code 0} for JPEG, whose frame is the buffer.
     */
    private final int frameBytes;

    /**
     * Derives the layout of a negotiated format.
     *
     * @param format the pixel format
     * @param width the width in pixels
     * @param bufferRows the {@code height} the driver reported: the frame's rows, or one field's
     * rows for {@code V4L2_FIELD_TOP}, {@code V4L2_FIELD_BOTTOM} and {@code V4L2_FIELD_ALTERNATE}
     * @param field the {@code field} the driver reported
     * @param strides the {@code bytesperline} of each memory plane, {@code 0} for a tight stride
     * @throws MediaException.Unsupported if the field order is unknown, a stride cannot hold a row,
     * the number of strides does not match the format's memory planes, or the frame is smaller than
     * 2x2
     * @throws MediaException.LimitExceeded if the frame exceeds the default limits
     */
    V4l2FrameLayout(V4l2PixelFormat format, int width, int bufferRows, int field, int... strides) {
        this.format = Objects.requireNonNull(format, "format");
        if (strides.length != format.memoryPlanes()) {
            throw new MediaException.Unsupported("v4l2 planes", format + " has " + format.memoryPlanes()
                    + " memory planes, the driver described " + strides.length);
        }
        var fieldRows = switch (field) {
            case V4l2.FIELD_TOP, V4l2.FIELD_BOTTOM, V4l2.FIELD_ALTERNATE -> true;
            case V4l2.FIELD_ANY, V4l2.FIELD_NONE, V4l2.FIELD_INTERLACED, V4l2.FIELD_INTERLACED_TB,
                 V4l2.FIELD_INTERLACED_BT, V4l2.FIELD_SEQ_TB, V4l2.FIELD_SEQ_BT -> false;
            default -> throw new MediaException.Unsupported("v4l2 field order", "the camera delivers field order " + field);
        };
        this.order = switch (field) {
            case V4l2.FIELD_SEQ_TB, V4l2.FIELD_ALTERNATE -> RowOrder.TOP_FIRST;
            case V4l2.FIELD_SEQ_BT -> RowOrder.BOTTOM_FIRST;
            case V4l2.FIELD_TOP, V4l2.FIELD_BOTTOM -> RowOrder.DOUBLED;
            default -> RowOrder.PROGRESSIVE;
        };
        this.alternate = field == V4l2.FIELD_ALTERNATE;
        var frameRows = fieldRows ? 2L * bufferRows : bufferRows;
        if (frameRows > Integer.MAX_VALUE) {
            throw new MediaException.LimitExceeded("v4l2 frame of " + width + "x" + frameRows);
        }
        LimitChecks.checkPicture(MediaLimits.DEFAULT, width, (int) frameRows);
        if (width < 2 || frameRows < 2 || bufferRows < 1) {
            throw new MediaException.Unsupported("v4l2 frame size", "frames of " + width + "x" + frameRows + " are too small");
        }
        this.width = width;
        this.height = (int) frameRows;
        var planes = format.imagePlanes();
        this.memoryPlane = new int[planes];
        this.sourceOffset = new long[planes];
        this.stride = new int[planes];
        this.rows = new int[planes];
        this.rowBytes = new int[planes];
        this.frameOffset = new int[planes];
        var chromaWidth = (width + 1) / 2;
        var chromaRows = format.kind() == V4l2PixelFormat.Kind.PLANAR || format.kind() == V4l2PixelFormat.Kind.SEMI_PLANAR
                ? (bufferRows + 1) / 2 : bufferRows;
        for (var plane = 0; plane < planes; plane++) {
            rows[plane] = plane == 0 ? bufferRows : chromaRows;
            rowBytes[plane] = plane == 0 ? lumaBytes(format, width, chromaWidth) : switch (format.kind()) {
                case PLANAR, PLANAR_422 -> chromaWidth;
                case SEMI_PLANAR, SEMI_PLANAR_422 -> 2 * chromaWidth;
                default -> 2 * width;
            };
        }
        if (format.contiguous()) {
            var base = strides[0] == 0 ? tightLumaStride(format, width, chromaWidth) : strides[0];
            for (var plane = 0; plane < planes; plane++) {
                stride[plane] = plane == 0 ? base : switch (format.kind()) {
                    case PLANAR, PLANAR_422 -> base / 2;
                    case SEMI_PLANAR_444 -> (int) Math.min(Integer.MAX_VALUE, 2L * base);
                    default -> base;
                };
                sourceOffset[plane] = plane == 0 ? 0 : sourceOffset[plane - 1] + (long) stride[plane - 1] * rows[plane - 1];
            }
        } else {
            for (var plane = 0; plane < planes; plane++) {
                memoryPlane[plane] = plane;
                stride[plane] = strides[plane] == 0 ? rowBytes[plane] : strides[plane];
            }
        }
        var offset = 0L;
        for (var plane = 0; plane < planes; plane++) {
            if (stride[plane] < rowBytes[plane]) {
                throw new MediaException.Unsupported("v4l2 stride", "a stride of " + stride[plane] + " bytes cannot hold "
                        + width + " pixels of " + format + " in plane " + plane);
            }
            frameOffset[plane] = (int) Math.min(Integer.MAX_VALUE, offset);
            offset += (long) stride[plane] * rows[plane] * (alternate ? 2 : 1);
            LimitChecks.checkAllocation(MediaLimits.DEFAULT, offset);
        }
        this.frameBytes = format.kind() == V4l2PixelFormat.Kind.JPEG ? 0 : (int) offset;
    }

    /**
     * Returns the bytes a luma or packed row of a format occupies.
     */
    private static int lumaBytes(V4l2PixelFormat format, int width, int chromaWidth) {
        return switch (format.kind()) {
            case PACKED -> 4 * chromaWidth;
            case RGB, LUMA -> format.bytesPerPixel() * width;
            case JPEG -> 0;
            default -> width;
        };
    }

    /**
     * Returns the luma stride of a tightly packed contiguous buffer: for the planar formats even,
     * so that half of it holds a chroma row.
     */
    private static int tightLumaStride(V4l2PixelFormat format, int width, int chromaWidth) {
        return switch (format.kind()) {
            case PLANAR, PLANAR_422, SEMI_PLANAR, SEMI_PLANAR_422 -> 2 * chromaWidth;
            default -> lumaBytes(format, width, chromaWidth);
        };
    }

    /**
     * Returns the pixel format.
     *
     * @return the format
     */
    V4l2PixelFormat format() {
        return format;
    }

    /**
     * Returns the frame width.
     *
     * @return the width in pixels
     */
    int width() {
        return width;
    }

    /**
     * Returns the frame height.
     *
     * @return the height in pixels, after weaving or doubling the fields
     */
    int height() {
        return height;
    }

    /**
     * Returns the row order of the planes in the assembled frame.
     *
     * @return the order
     */
    RowOrder order() {
        return order;
    }

    /**
     * Returns whether frames are woven from pairs of alternating field buffers.
     *
     * @return whether the field order is {@code V4L2_FIELD_ALTERNATE}
     */
    boolean alternate() {
        return alternate;
    }

    /**
     * Returns the number of image planes.
     *
     * @return 1 to 3
     */
    int planes() {
        return rows.length;
    }

    /**
     * Returns the offset of an image plane in the assembled frame.
     *
     * @param plane the image plane
     * @return the offset in bytes
     */
    int frameOffset(int plane) {
        return frameOffset[plane];
    }

    /**
     * Returns the row stride of an image plane.
     *
     * @param plane the image plane
     * @return the stride in bytes
     */
    int stride(int plane) {
        return stride[plane];
    }

    /**
     * Returns the rows of an image plane in the assembled frame.
     *
     * @param plane the image plane
     * @return the stored rows: twice a buffer's rows when alternating fields are woven
     */
    int frameRows(int plane) {
        return alternate ? 2 * rows[plane] : rows[plane];
    }

    /**
     * Returns the bytes of a row of an image plane a conversion reads.
     *
     * @param plane the image plane
     * @return the row's size in bytes
     */
    int rowBytes(int plane) {
        return rowBytes[plane];
    }

    /**
     * Returns the size of an assembled frame.
     *
     * @return the size in bytes, or {@code 0} for JPEG
     */
    int frameBytes() {
        return frameBytes;
    }

    /**
     * Returns the smallest number of bytes each memory plane of a buffer must hold.
     *
     * @param memory the memory plane
     * @return the size in bytes, or {@code 1} for JPEG
     */
    long requiredBytes(int memory) {
        if (format.kind() == V4l2PixelFormat.Kind.JPEG) {
            return 1;
        }
        var required = 0L;
        for (var plane = 0; plane < rows.length; plane++) {
            if (memoryPlane[plane] == memory) {
                required = Math.max(required, sourceOffset[plane] + (long) stride[plane] * (rows[plane] - 1) + rowBytes[plane]);
            }
        }
        return required;
    }

    /**
     * Copies the image planes of a driver buffer into an assembled frame.
     *
     * <p>A progressive, sequential or single-field buffer fills the whole frame; a buffer of an
     * alternating field fills the rows of that field.
     *
     * @param memory the memory planes of the buffer
     * @param dataOffset the offset of the data in each memory plane
     * @param used the bytes used in each memory plane, the data offset included
     * @param bottom whether an alternating buffer holds the bottom field
     * @param frame the assembled frame
     * @return the number of bytes the frame holds, or {@code -1} when a memory plane holds too few
     * bytes for its image planes
     */
    int copy(MemorySegment[] memory, long[] dataOffset, long[] used, boolean bottom, byte[] frame) {
        if (format.kind() == V4l2PixelFormat.Kind.JPEG) {
            var length = used[0] - dataOffset[0];
            if (length <= 0 || length > frame.length || dataOffset[0] + length > memory[0].byteSize()) {
                return -1;
            }
            MemorySegment.copy(memory[0], JAVA_BYTE, dataOffset[0], frame, 0, (int) length);
            return (int) length;
        }
        for (var plane = 0; plane < rows.length; plane++) {
            var source = memory[memoryPlane[plane]];
            var start = dataOffset[memoryPlane[plane]] + sourceOffset[plane];
            var end = Math.min(used[memoryPlane[plane]], source.byteSize());
            var size = (long) stride[plane] * rows[plane];
            var available = Math.min(size, end - start);
            if (available < (long) stride[plane] * (rows[plane] - 1) + rowBytes[plane]) {
                return -1;
            }
            var target = frameOffset[plane] + (alternate && bottom ? (int) size : 0);
            MemorySegment.copy(source, JAVA_BYTE, start, frame, target, (int) available);
        }
        return frameBytes;
    }
}
