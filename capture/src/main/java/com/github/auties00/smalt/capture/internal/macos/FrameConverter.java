package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Copies captured frames from native memory into planar 4:2:0 pictures.
 *
 * <p>A source plane is described by its memory, its row stride (the {@code bytesPerRow} of a
 * {@code CVPixelBuffer}, which may exceed the visible row) and its visible size. The destination
 * is a {@link ChromaFormat#YUV420} picture with even dimensions. When the source is larger than
 * the destination the right columns and bottom rows are cropped; when it is smaller the uncovered
 * area is painted black. Instances are not thread-safe.
 */
final class FrameConverter {
    /**
     * The chroma sample of neutral gray.
     */
    private static final byte NEUTRAL = (byte) 128;

    private final ConversionKernel kernel;

    /**
     * The scratch rows: one row of interleaved chroma, or an upper and a lower row of pixels.
     */
    private byte[] rows = new byte[0];

    /**
     * Constructs a converter using {@link ConversionKernel#INSTANCE}.
     */
    FrameConverter() {
        this(ConversionKernel.INSTANCE);
    }

    /**
     * Constructs a converter using a kernel.
     */
    FrameConverter(ConversionKernel kernel) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
    }

    /**
     * Copies an NV12 frame: a luma plane and an interleaved Cb/Cr plane at half resolution.
     *
     * @param luma the luma plane
     * @param lumaStride the luma row stride in bytes
     * @param lumaWidth the visible luma width
     * @param lumaHeight the visible luma height
     * @param chroma the interleaved chroma plane
     * @param chromaStride the chroma row stride in bytes
     * @param chromaWidth the visible number of Cb/Cr pairs per row
     * @param chromaHeight the visible number of chroma rows
     * @param target the destination picture
     * @param black the luma sample of black, painted where the source does not reach
     * @throws NullPointerException if a segment or {@code target} is {@code null}
     * @throws IllegalArgumentException if a size or stride is negative or inconsistent, a plane is
     * shorter than its size and stride require, or the target is
     * not a 4:2:0 picture of even size
     */
    void nv12(MemorySegment luma, long lumaStride, int lumaWidth, int lumaHeight,
              MemorySegment chroma, long chromaStride, int chromaWidth, int chromaHeight,
              YuvPicture target, int black) {
        checkTarget(target);
        checkPlane("luma", luma, lumaStride, lumaWidth, lumaHeight, 1);
        checkPlane("chroma", chroma, chromaStride, chromaWidth, chromaHeight, 2);
        var width = target.width();
        var height = target.height();
        var y = target.y();
        var yStride = target.yStride();
        var copyWidth = Math.min(width, lumaWidth);
        var copyHeight = Math.min(height, lumaHeight);
        for (var row = 0; row < copyHeight; row++) {
            var offset = row * yStride;
            MemorySegment.copy(luma, JAVA_BYTE, row * lumaStride, y, offset, copyWidth);
            if (copyWidth < width) {
                Arrays.fill(y, offset + copyWidth, offset + width, (byte) black);
            }
        }
        for (var row = copyHeight; row < height; row++) {
            Arrays.fill(y, row * yStride, row * yStride + width, (byte) black);
        }
        var chromaTargetWidth = width / 2;
        var chromaTargetHeight = height / 2;
        var u = target.u();
        var v = target.v();
        var uvStride = target.uvStride();
        var pairs = Math.min(chromaTargetWidth, chromaWidth);
        var chromaRows = Math.min(chromaTargetHeight, chromaHeight);
        var scratch = scratch(2 * pairs);
        for (var row = 0; row < chromaRows; row++) {
            var offset = row * uvStride;
            MemorySegment.copy(chroma, JAVA_BYTE, row * chromaStride, scratch, 0, 2 * pairs);
            kernel.deinterleave(scratch, 0, pairs, u, v, offset);
            if (pairs < chromaTargetWidth) {
                Arrays.fill(u, offset + pairs, offset + chromaTargetWidth, NEUTRAL);
                Arrays.fill(v, offset + pairs, offset + chromaTargetWidth, NEUTRAL);
            }
        }
        for (var row = chromaRows; row < chromaTargetHeight; row++) {
            Arrays.fill(u, row * uvStride, row * uvStride + chromaTargetWidth, NEUTRAL);
            Arrays.fill(v, row * uvStride, row * uvStride + chromaTargetWidth, NEUTRAL);
        }
    }

    /**
     * Converts a BGRA frame.
     *
     * <p>A source of odd width or height contributes its even part only; the last column or row
     * is treated as outside the source.
     *
     * @param pixels the pixels, four bytes each in B, G, R, A order
     * @param stride the row stride in bytes
     * @param sourceWidth the visible width
     * @param sourceHeight the visible height
     * @param target the destination picture
     * @param coefficients the conversion coefficients, whose color the target should carry
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a size or stride is negative or inconsistent, the source
     * is shorter than its size and stride require, or the target
     * is not a 4:2:0 picture of even size
     */
    void bgra(MemorySegment pixels, long stride, int sourceWidth, int sourceHeight, YuvPicture target,
              YuvCoefficients coefficients) {
        Objects.requireNonNull(coefficients, "coefficients");
        checkTarget(target);
        checkPlane("bgra", pixels, stride, sourceWidth, sourceHeight, 4);
        var width = target.width();
        var height = target.height();
        var copyWidth = Math.min(width, sourceWidth & ~1);
        var copyHeight = Math.min(height, sourceHeight & ~1);
        var y = target.y();
        var u = target.u();
        var v = target.v();
        var yStride = target.yStride();
        var uvStride = target.uvStride();
        var black = (byte) coefficients.black();
        var rowBytes = 4 * copyWidth;
        var scratch = scratch(2 * rowBytes);
        for (var row = 0; row < copyHeight; row += 2) {
            MemorySegment.copy(pixels, JAVA_BYTE, row * stride, scratch, 0, rowBytes);
            MemorySegment.copy(pixels, JAVA_BYTE, (row + 1) * stride, scratch, rowBytes, rowBytes);
            var offset0 = row * yStride;
            var offset1 = offset0 + yStride;
            var chromaOffset = (row / 2) * uvStride;
            kernel.rgbToLuma(scratch, 0, copyWidth, RgbLayout.BGRX, coefficients, y, offset0);
            kernel.rgbToLuma(scratch, rowBytes, copyWidth, RgbLayout.BGRX, coefficients, y, offset1);
            kernel.rgbToChroma(scratch, 0, rowBytes, copyWidth / 2, RgbLayout.BGRX, coefficients, u, v, chromaOffset);
            if (copyWidth < width) {
                Arrays.fill(y, offset0 + copyWidth, offset0 + width, black);
                Arrays.fill(y, offset1 + copyWidth, offset1 + width, black);
                Arrays.fill(u, chromaOffset + copyWidth / 2, chromaOffset + width / 2, NEUTRAL);
                Arrays.fill(v, chromaOffset + copyWidth / 2, chromaOffset + width / 2, NEUTRAL);
            }
        }
        for (var row = copyHeight; row < height; row++) {
            Arrays.fill(y, row * yStride, row * yStride + width, black);
        }
        for (var row = copyHeight / 2; row < height / 2; row++) {
            Arrays.fill(u, row * uvStride, row * uvStride + width / 2, NEUTRAL);
            Arrays.fill(v, row * uvStride, row * uvStride + width / 2, NEUTRAL);
        }
    }

    /**
     * Returns the scratch rows, grown to a capacity in bytes.
     */
    private byte[] scratch(int capacity) {
        if (rows.length < capacity) {
            rows = new byte[capacity];
        }
        return rows;
    }

    /**
     * Checks that a picture can receive a frame.
     *
     * @param target the picture
     * @throws NullPointerException if {@code target} is {@code null}
     * @throws IllegalArgumentException if the picture is not 4:2:0 or has an odd dimension
     */
    private static void checkTarget(YuvPicture target) {
        Objects.requireNonNull(target, "target");
        if (target.chromaFormat() != ChromaFormat.YUV420) {
            throw new IllegalArgumentException("the target must be a 4:2:0 picture, got " + target.chromaFormat());
        }
        if ((target.width() & 1) != 0 || (target.height() & 1) != 0) {
            throw new IllegalArgumentException("the target size " + target.width() + "x" + target.height() + " is odd");
        }
    }

    /**
     * Checks that a source plane holds the rows its size and stride describe.
     *
     * @param name the plane name used in error messages
     * @param plane the plane memory
     * @param stride the row stride in bytes
     * @param width the visible width in samples
     * @param height the visible height in rows
     * @param bytesPerPixel the bytes per sample
     * @throws NullPointerException if {@code plane} is {@code null}
     * @throws IllegalArgumentException if a value is negative, the stride is shorter than a row,
     * or the memory is shorter than the rows require
     */
    private static void checkPlane(String name, MemorySegment plane, long stride, int width, int height, int bytesPerPixel) {
        Objects.requireNonNull(plane, name);
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(name + " size " + width + "x" + height + " is negative");
        }
        var rowBytes = (long) width * bytesPerPixel;
        if (stride < rowBytes) {
            throw new IllegalArgumentException(name + " stride " + stride + " is shorter than a row of " + rowBytes + " bytes");
        }
        if (height > 0 && plane.byteSize() < stride * (height - 1) + rowBytes) {
            throw new IllegalArgumentException(name + " plane of " + plane.byteSize() + " bytes is too short for "
                    + height + " rows of stride " + stride);
        }
    }
}
