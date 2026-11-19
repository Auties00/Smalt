package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.capture.internal.Pictures;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.YuvPicture;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Converts captured BGRX images in native memory to the planar 4:2:0 pictures a session delivers,
 * in the color of {@link YuvCoefficients#BT709_LIMITED}.
 *
 * <p>The image is read two rows at a time into a small heap buffer, the mouse pointer is
 * composed onto those rows, and a {@link ConversionKernel} converts them, so the working
 * set stays in the processor's caches and nothing is allocated per frame. An image stored rotated
 * (a display whose mode is rotated) is first turned upright into a heap copy. The captured area of
 * the upright image (all of it, or a {@link CaptureArea}) is converted in its even part (an odd last
 * row or column is cropped); when that differs from the picture's size, as after a resolution change,
 * the area is converted at its own size and rescaled.
 */
final class BgraConverter {
    private final ConversionKernel kernel = ConversionKernel.INSTANCE;

    /**
     * The two rows being converted.
     */
    private byte[] rows = new byte[0];

    /**
     * One stored row of a rotated image.
     */
    private int[] line = new int[0];

    /**
     * The upright copy of a rotated image.
     */
    private int[] upright = new int[0];

    /**
     * The picture a differently sized image is converted into before rescaling.
     */
    private YuvPicture scratch;

    /**
     * Creates a converter whose buffers grow on first use.
     */
    BgraConverter() {
    }

    /**
     * Converts an image.
     *
     * @param memory the memory holding the image
     * @param origin the offset of the first stored row in {@code memory}
     * @param pitch the byte distance between stored rows; may be negative
     * @param width the stored width in pixels
     * @param height the stored height in pixels
     * @param rotation the {@code DXGI_MODE_ROTATION} of the stored image; values other than
     * {@link Dxgi#ROTATE_90}, {@link Dxgi#ROTATE_180} and {@link Dxgi#ROTATE_270}
     * mean upright
     * @param pointer the pointer to compose, or {@code null}
     * @param area the rectangle of the upright image to convert, or {@code null} for all of it; the part
     * outside the image is ignored
     * @param out the picture receiving the frame
     * @return whether the image was converted; {@code false} when the captured area has no even part
     * @throws IndexOutOfBoundsException if the image does not fit in {@code memory}
     */
    boolean convert(MemorySegment memory, long origin, long pitch, int width, int height, int rotation,
                    PointerOverlay pointer, CaptureArea area, YuvPicture out) {
        var turned = rotation == Dxgi.ROTATE_90 || rotation == Dxgi.ROTATE_270;
        var uprightWidth = turned ? height : width;
        var uprightHeight = turned ? width : height;
        var source = memory;
        var sourceOrigin = origin;
        var sourcePitch = pitch;
        if (rotation == Dxgi.ROTATE_90 || rotation == Dxgi.ROTATE_180 || rotation == Dxgi.ROTATE_270) {
            source = MemorySegment.ofArray(rotate(memory, origin, pitch, width, height, rotation));
            sourceOrigin = 0;
            sourcePitch = 4L * uprightWidth;
        }
        var cropX = 0;
        var cropY = 0;
        var cropWidth = uprightWidth;
        var cropHeight = uprightHeight;
        if (area != null) {
            cropX = area.x();
            cropY = area.y();
            cropWidth = Math.min(area.width(), uprightWidth - cropX);
            cropHeight = Math.min(area.height(), uprightHeight - cropY);
        }
        var evenWidth = Math.max(0, cropWidth) & ~1;
        var evenHeight = Math.max(0, cropHeight) & ~1;
        if (evenWidth == 0 || evenHeight == 0) {
            return false;
        }
        sourceOrigin += cropY * sourcePitch + 4L * cropX;
        var target = out;
        if (evenWidth != out.width() || evenHeight != out.height()) {
            scratch = Pictures.reuse(scratch, evenWidth, evenHeight, out.color());
            target = scratch;
        }
        var rowBytes = 4 * evenWidth;
        if (rows.length < 2 * rowBytes) {
            rows = new byte[2 * rowBytes];
        }
        var coefficients = YuvCoefficients.BT709_LIMITED;
        var y = target.y();
        var yStride = target.yStride();
        var uvStride = target.uvStride();
        for (var row = 0; row < evenHeight; row += 2) {
            MemorySegment.copy(source, JAVA_BYTE, sourceOrigin + row * sourcePitch, rows, 0, rowBytes);
            MemorySegment.copy(source, JAVA_BYTE, sourceOrigin + (row + 1) * sourcePitch, rows, rowBytes, rowBytes);
            if (pointer != null) {
                pointer.apply(rows, 0, cropY + row, cropX, evenWidth);
                pointer.apply(rows, rowBytes, cropY + row + 1, cropX, evenWidth);
            }
            kernel.rgbToLuma(rows, 0, evenWidth, RgbLayout.BGRX, coefficients, y, row * yStride);
            kernel.rgbToLuma(rows, rowBytes, evenWidth, RgbLayout.BGRX, coefficients, y, (row + 1) * yStride);
            kernel.rgbToChroma(rows, 0, rowBytes, evenWidth / 2, RgbLayout.BGRX, coefficients, target.u(), target.v(),
                    (row >> 1) * uvStride);
        }
        if (target != out) {
            Pictures.fit(target, out);
        }
        return true;
    }

    /**
     * Turns a stored image upright.
     *
     * <p>Following Microsoft's Desktop Duplication sample, a stored pixel {@code (sx, sy)} of an
     * image whose upright size is {@code W x H} lands at {@code (W - 1 - sy, sx)} for a
     * 90-degree rotation, {@code (W - 1 - sx, H - 1 - sy)} for 180 degrees and
     * {@code (sy, H - 1 - sx)} for 270 degrees.
     *
     * @param memory the memory holding the image
     * @param origin the offset of the first stored row
     * @param pitch the byte distance between stored rows
     * @param width the stored width
     * @param height the stored height
     * @param rotation the rotation, one of {@link Dxgi#ROTATE_90}, {@link Dxgi#ROTATE_180} and
     * {@link Dxgi#ROTATE_270}
     * @return the upright pixels, one 32-bit BGRX value per pixel in native byte order, rows
     * {@code W} pixels long
     */
    int[] rotate(MemorySegment memory, long origin, long pitch, int width, int height, int rotation) {
        var turned = rotation != Dxgi.ROTATE_180;
        var uprightWidth = turned ? height : width;
        var uprightHeight = turned ? width : height;
        var pixels = uprightWidth * uprightHeight;
        if (upright.length < pixels) {
            upright = new int[pixels];
        }
        if (line.length < width) {
            line = new int[width];
        }
        for (var sy = 0; sy < height; sy++) {
            MemorySegment.copy(memory, JAVA_INT_UNALIGNED, origin + sy * pitch, line, 0, width);
            switch (rotation) {
                case Dxgi.ROTATE_90 -> {
                    var column = uprightWidth - 1 - sy;
                    for (var sx = 0; sx < width; sx++) {
                        upright[sx * uprightWidth + column] = line[sx];
                    }
                }
                case Dxgi.ROTATE_180 -> {
                    var base = (uprightHeight - 1 - sy) * uprightWidth + uprightWidth - 1;
                    for (var sx = 0; sx < width; sx++) {
                        upright[base - sx] = line[sx];
                    }
                }
                default -> {
                    for (var sx = 0; sx < width; sx++) {
                        upright[(uprightHeight - 1 - sx) * uprightWidth + sy] = line[sx];
                    }
                }
            }
        }
        return upright;
    }
}
