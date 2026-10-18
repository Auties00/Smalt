package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;

/**
 * Holds the planes of the picture an AVIF image is assembled into, at 8 bits or at the source
 * bit depth, and copies the decoded AV1 frames of its tiles and of its alpha plane into them.
 *
 * <p>Color samples are written as the AV1 decoder writes its 8-bit output: copied at the source
 * depth, or reduced to 8 bits by rounding to nearest. Alpha samples are full range at the source
 * depth; a limited-range alpha frame is first expanded to full range, and an 8-bit picture of a
 * deeper source holds alpha rescaled exactly, {@code min(255, (a * 255 + max / 2) / max)}, as
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture)} reduces it, so
 * that reducing the deep picture gives the 8-bit picture sample for sample.
 *
 * <p>Different threads may write disjoint regions concurrently. Instances are otherwise not
 * thread-safe.
 *
 * @implNote This implementation expands limited-range alpha with libavif's
 * {@code avifLimitedToFullY} ({@code src/reformat.c}, BSD 2-clause), which accepts the
 * limited-range alpha of AVIF 1.0.0 files.
 */
final class Canvas {
    /**
     * The picture width.
     */
    final int width;

    /**
     * The picture height.
     */
    final int height;

    /**
     * The chroma layout.
     */
    final ChromaFormat format;

    /**
     * The bit depth of the source samples: 8, 10 or 12.
     */
    final int bitDepth;

    /**
     * Whether the planes keep the source bit depth in {@code short} samples.
     */
    final boolean wide;

    /**
     * Whether the picture has an alpha plane.
     */
    final boolean hasAlpha;

    /**
     * The 8-bit planes (luma, chroma, chroma, alpha), when not {@link #wide}.
     */
    private final byte[][] narrowPlanes = new byte[4][];

    /**
     * The deep planes (luma, chroma, chroma, alpha), when {@link #wide}.
     */
    private final short[][] widePlanes = new short[4][];

    /**
     * Allocates the planes, checking the size against the limits first.
     *
     * @param limits the bounds
     * @param width the picture width, in pixels
     * @param height the picture height, in pixels
     * @param format the chroma layout
     * @param bitDepth the source bit depth
     * @param wide whether to keep the source bit depth
     * @param hasAlpha whether to allocate an alpha plane
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    Canvas(MediaLimits limits, int width, int height, ChromaFormat format, int bitDepth, boolean wide, boolean hasAlpha) {
        LimitChecks.checkPicture(limits, width, height);
        var luma = (long) width * height;
        var chroma = format.hasChroma() ? (long) format.chromaWidth(width) * format.chromaHeight(height) : 0;
        var samples = luma * (hasAlpha ? 2 : 1) + 2 * chroma;
        LimitChecks.checkAllocation(limits, samples * (wide ? 2 : 1));
        LimitChecks.checkAllocation(limits, luma * (wide ? 2 : 1));
        this.width = width;
        this.height = height;
        this.format = format;
        this.bitDepth = bitDepth;
        this.wide = wide;
        this.hasAlpha = hasAlpha;
        for (var plane = 0; plane < 4; plane++) {
            var size = plane == 0 || plane == 3 ? luma : chroma;
            if ((plane == 3 && !hasAlpha) || size == 0) {
                continue;
            }
            if (wide) {
                widePlanes[plane] = new short[(int) size];
            } else {
                narrowPlanes[plane] = new byte[(int) size];
            }
        }
    }

    /**
     * Copies the color planes of a decoded frame into a region.
     *
     * @param frame the decoded frame, whose layout and bit depth match the canvas
     * @param x the left edge of the region, even when chroma is subsampled horizontally
     * @param y the top edge of the region, even when chroma is subsampled vertically
     * @param w the region width, at most the frame width
     * @param h the region height, at most the frame height
     * @param parallel whether to split a large region across the module's helper threads
     */
    void writeColor(Av1Picture frame, int x, int y, int w, int h,
                    boolean parallel) {
        copy(frame.data[0], 0, frame.stride[0], 0, x + y * width, width, w, h, parallel);
        if (format.hasChroma()) {
            var chromaWidth = format.chromaWidth(width);
            var cx = x >> format.shiftX();
            var cy = y >> format.shiftY();
            var cw = format.chromaWidth(x + w) - cx;
            var ch = format.chromaHeight(y + h) - cy;
            var offset = cx + cy * chromaWidth;
            copy(frame.data[1], 0, frame.stride[1], 1, offset, chromaWidth, cw, ch, parallel);
            copy(frame.data[2], 0, frame.stride[1], 2, offset, chromaWidth, cw, ch, parallel);
        }
    }

    /**
     * Copies one plane region, reducing it to 8 bits when the canvas is not wide.
     *
     * @param src the source plane
     * @param srcOff the first source sample
     * @param srcStride the source stride
     * @param plane the canvas plane index
     * @param dstOff the first canvas sample
     * @param dstStride the canvas stride
     * @param w the region width
     * @param h the region height
     * @param parallel whether to split a large region across threads
     */
    private void copy(short[] src, int srcOff, int srcStride, int plane, int dstOff, int dstStride, int w, int h,
                      boolean parallel) {
        if (wide) {
            var dst = widePlanes[plane];
            for (var row = 0; row < h; row++) {
                System.arraycopy(src, srcOff + row * srcStride, dst, dstOff + row * dstStride, w);
            }
            return;
        }
        Narrow.plane(src, srcOff, srcStride, narrowPlanes[plane], dstOff, dstStride, w, h, bitDepth - 8, parallel);
    }

    /**
     * Copies the luma plane of a decoded alpha frame into a region of the alpha plane.
     *
     * @param frame the decoded alpha frame, of the canvas bit depth
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param w the region width, at most the frame width
     * @param h the region height, at most the frame height
     * @param limited whether the alpha frame is limited range
     */
    void writeAlpha(Av1Picture frame, int x, int y, int w, int h,
                    boolean limited) {
        var src = frame.data[0];
        var stride = frame.stride[0];
        var max = (1 << bitDepth) - 1;
        var low = 16 << (bitDepth - 8);
        var span = (235 - 16) << (bitDepth - 8);
        for (var row = 0; row < h; row++) {
            var in = row * stride;
            var out = x + (y + row) * width;
            for (var col = 0; col < w; col++) {
                var value = src[in + col] & 0xFFFF;
                if (limited) {
                    value = Math.clamp(((value - low) * max + span / 2) / span, 0, max);
                }
                if (wide) {
                    widePlanes[3][out + col] = (short) value;
                } else {
                    narrowPlanes[3][out + col] = (byte) (bitDepth == 8 ? value : Math.min(255, (value * 255 + max / 2) / max));
                }
            }
        }
    }

    /**
     * Wraps the planes into a picture.
     *
     * @param color the color description
     * @return a {@link YuvPicture}, or a {@link Yuv16Picture} when the canvas is wide
     */
    Picture toPicture(ColorInfo color) {
        var chromaWidth = format.chromaWidth(width);
        if (wide) {
            var builder = Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(bitDepth)
                    .color(color).luma(widePlanes[0], width);
            if (format.hasChroma()) {
                builder.chroma(widePlanes[1], widePlanes[2], chromaWidth);
            }
            if (hasAlpha) {
                builder.alpha(widePlanes[3], width);
            }
            return builder.build();
        }
        var builder = YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                .luma(narrowPlanes[0], width);
        if (format.hasChroma()) {
            builder.chroma(narrowPlanes[1], narrowPlanes[2], chromaWidth);
        }
        if (hasAlpha) {
            builder.alpha(narrowPlanes[3], width);
        }
        return builder.build();
    }
}
