/* Ports libavif (src/reformat.c, avifLimitedToFullY). Copyright 2019 Joe Drago; BSD 2-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the planes of the picture a HEIF image is assembled into, at 8 bits or at the source bit
 * depth, and copies decoded tiles, or planar pictures, into regions of them.
 *
 * <p>Color samples are copied at the source depth, or reduced to 8 bits by rounding to nearest
 * with the tile's own kernel ({@link DecodedTile#narrow}). A canvas that keeps the depth holds every
 * plane at the canvas depth: the samples of a shallower plane, such as 8-bit luma with 10-bit
 * chroma, are shifted left to it. Alpha samples are full range at the
 * canvas depth: a limited-range alpha plane is first expanded to full range, alpha of another
 * depth than the color is rescaled to it, and an 8-bit canvas of a deeper source holds alpha
 * rescaled exactly, {@code min(255, (a * 255 + max / 2) / max)}, as
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture) ImageOps.toEightBit}
 * reduces it, so that reducing the deep picture gives the 8-bit picture sample for sample. Alpha
 * samples above the largest value of their depth are clamped to it first.
 *
 * <p>Different threads may write disjoint regions concurrently. Instances are otherwise not
 * thread-safe.
 *
 * @implNote This implementation expands limited-range alpha as libavif's {@code avifLimitedToFullY}
 * ({@code src/reformat.c}, BSD 2-clause) does, which accepts the limited-range alpha of AVIF 1.0.0
 * files.
 */
public final class HeifCanvas {
    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The chroma layout.
     */
    private final ChromaFormat format;

    /**
     * The bit depth of the source samples, from 8 to 16.
     */
    private final int bitDepth;

    /**
     * Whether the planes keep the source bit depth in {@code short} samples.
     */
    private final boolean wide;

    /**
     * Whether the picture has an alpha plane.
     */
    private final boolean hasAlpha;

    /**
     * The format name, for messages.
     */
    private final String name;

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
     * @param name the format name, for messages, such as {@code "avif"}
     * @param limits the bounds
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @param format the chroma layout
     * @param bitDepth the source bit depth, from 8 to 16
     * @param wide whether to keep the source bit depth
     * @param hasAlpha whether to allocate an alpha plane
     * @throws MediaException.Malformed if a side is not positive
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public HeifCanvas(String name, MediaLimits limits, int width, int height, ChromaFormat format, int bitDepth,
                      boolean wide, boolean hasAlpha) {
        this.name = Objects.requireNonNull(name, "name");
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
     * {@return the picture width}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the picture height}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the chroma layout}
     */
    public ChromaFormat format() {
        return format;
    }

    /**
     * {@return the bit depth of the source samples}
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return whether the planes keep the source bit depth}
     */
    public boolean wide() {
        return wide;
    }

    /**
     * {@return whether the picture has an alpha plane}
     */
    public boolean hasAlpha() {
        return hasAlpha;
    }

    /**
     * Copies the color planes of a decoded tile into a region.
     *
     * @param tile the decoded tile, whose layout matches the canvas
     * @param x the left edge of the region, even when chroma is subsampled horizontally
     * @param y the top edge of the region, even when chroma is subsampled vertically
     * @param w the region width, at most the visible tile width
     * @param h the region height, at most the visible tile height
     * @param parallel whether to split a large region of deep samples across the helper threads
     */
    public void writeColor(DecodedTile tile, int x, int y, int w, int h, boolean parallel) {
        copy(tile, 0, x + y * width, width, w, h, parallel);
        if (format.hasChroma()) {
            var chromaWidth = format.chromaWidth(width);
            var cx = x >> format.shiftX();
            var cy = y >> format.shiftY();
            var cw = format.chromaWidth(x + w) - cx;
            var ch = format.chromaHeight(y + h) - cy;
            var offset = cx + cy * chromaWidth;
            copy(tile, 1, offset, chromaWidth, cw, ch, parallel);
            copy(tile, 2, offset, chromaWidth, cw, ch, parallel);
        }
    }

    /**
     * Copies one plane region of a tile, widening shallower samples to the depth of a wide canvas
     * and narrowing deep samples for an 8-bit one.
     *
     * @param tile the decoded tile
     * @param plane the plane index
     * @param dstOffset the first canvas sample
     * @param dstStride the canvas stride
     * @param w the region width
     * @param h the region height
     * @param parallel whether to split a large narrowing across threads
     */
    private void copy(DecodedTile tile, int plane, int dstOffset, int dstStride, int w, int h, boolean parallel) {
        var srcOffset = tile.offset(plane);
        var srcStride = tile.stride(plane);
        if (tile.eightBit()) {
            var src = tile.plane8(plane);
            if (!wide) {
                Planes.copy(src, srcOffset, srcStride, narrowPlanes[plane], dstOffset, dstStride, w, h);
                return;
            }
            var dst = widePlanes[plane];
            var up = bitDepth - 8;
            for (var row = 0; row < h; row++) {
                var in = srcOffset + row * srcStride;
                var out = dstOffset + row * dstStride;
                for (var col = 0; col < w; col++) {
                    dst[out + col] = (short) ((src[in + col] & 0xFF) << up);
                }
            }
            return;
        }
        var src = tile.plane16(plane);
        if (wide) {
            var up = bitDepth - tile.depth(plane);
            if (up <= 0) {
                Planes.copy(src, srcOffset, srcStride, widePlanes[plane], dstOffset, dstStride, w, h);
                return;
            }
            var dst = widePlanes[plane];
            for (var row = 0; row < h; row++) {
                var in = srcOffset + row * srcStride;
                var out = dstOffset + row * dstStride;
                for (var col = 0; col < w; col++) {
                    dst[out + col] = (short) (src[in + col] << up);
                }
            }
            return;
        }
        var dst = narrowPlanes[plane];
        var shift = tile.depth(plane) - 8;
        var bands = parallel ? ParallelBands.bands(h, (long) w * h) : 1;
        if (bands <= 1) {
            tile.narrow(src, srcOffset, srcStride, dst, dstOffset, dstStride, w, h, shift);
            return;
        }
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, h);
            var last = ParallelBands.start(band + 1, bands, h);
            tile.narrow(src, srcOffset + first * srcStride, srcStride, dst, dstOffset + first * dstStride, dstStride,
                    w, last - first, shift);
        });
    }

    /**
     * Copies the planes of a planar picture into a region.
     *
     * @param picture the picture, of the canvas layout; a {@link Yuv16Picture} of the canvas
     * depth when the canvas is wide, a {@link YuvPicture} otherwise
     * @param x the left edge of the region, even when chroma is subsampled horizontally
     * @param y the top edge of the region, even when chroma is subsampled vertically
     * @param w the region width, at most the picture width
     * @param h the region height, at most the picture height
     * @throws MediaException.Malformed if the picture does not match the canvas
     */
    public void writeColor(Picture picture, int x, int y, int w, int h) {
        var cx = x >> format.shiftX();
        var cy = y >> format.shiftY();
        var cw = format.hasChroma() ? format.chromaWidth(x + w) - cx : 0;
        var ch = format.hasChroma() ? format.chromaHeight(y + h) - cy : 0;
        var chromaWidth = format.chromaWidth(width);
        switch (picture) {
            case YuvPicture yuv when !wide && yuv.chromaFormat() == format -> {
                Planes.copy(yuv.y(), 0, yuv.yStride(), narrowPlanes[0], x + y * width, width, w, h);
                if (format.hasChroma()) {
                    Planes.copy(yuv.u(), 0, yuv.uvStride(), narrowPlanes[1], cx + cy * chromaWidth, chromaWidth, cw, ch);
                    Planes.copy(yuv.v(), 0, yuv.uvStride(), narrowPlanes[2], cx + cy * chromaWidth, chromaWidth, cw, ch);
                }
            }
            case Yuv16Picture yuv when wide && yuv.chromaFormat() == format && yuv.bitDepth() == bitDepth -> {
                Planes.copy(yuv.y(), 0, yuv.yStride(), widePlanes[0], x + y * width, width, w, h);
                if (format.hasChroma()) {
                    Planes.copy(yuv.u(), 0, yuv.uvStride(), widePlanes[1], cx + cy * chromaWidth, chromaWidth, cw, ch);
                    Planes.copy(yuv.v(), 0, yuv.uvStride(), widePlanes[2], cx + cy * chromaWidth, chromaWidth, cw, ch);
                }
            }
            default -> throw new MediaException.Malformed(name + ": the inputs of a grid differ in layout or depth");
        }
    }

    /**
     * Copies the luma plane of a decoded alpha tile into a region of the alpha plane.
     *
     * @param tile the decoded alpha tile
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param w the region width, at most the visible tile width
     * @param h the region height, at most the visible tile height
     * @param limited whether the alpha samples are limited range
     */
    public void writeAlpha(DecodedTile tile, int x, int y, int w, int h, boolean limited) {
        var offset = tile.offset(0);
        var stride = tile.stride(0);
        if (tile.eightBit()) {
            var src = tile.plane8(0);
            var samples = new short[w];
            for (var row = 0; row < h; row++) {
                var in = offset + row * stride;
                for (var col = 0; col < w; col++) {
                    samples[col] = (short) (src[in + col] & 0xFF);
                }
                writeAlpha(samples, 0, w, 8, x, y + row, w, 1, limited);
            }
            return;
        }
        writeAlpha(tile.plane16(0), offset, stride, tile.depth(0), x, y, w, h, limited);
    }

    /**
     * Makes a region of the alpha plane fully opaque.
     *
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param w the region width
     * @param h the region height
     */
    public void fillAlpha(int x, int y, int w, int h) {
        for (var row = y; row < y + h; row++) {
            var from = x + row * width;
            if (wide) {
                Arrays.fill(widePlanes[3], from, from + w, (short) ((1 << bitDepth) - 1));
            } else {
                Arrays.fill(narrowPlanes[3], from, from + w, (byte) 0xFF);
            }
        }
    }

    /**
     * Copies the luma plane of a planar alpha picture into the alpha plane.
     *
     * @param picture the alpha picture, a {@link YuvPicture} or a {@link Yuv16Picture} of the
     * canvas size
     * @param limited whether the alpha samples are limited range
     * @throws MediaException.Malformed if the picture is not planar
     */
    public void writeAlpha(Picture picture, boolean limited) {
        writeAlpha(picture, 0, 0, width, height, limited);
    }

    /**
     * Copies the top-left region of the luma plane of a planar alpha picture into a region of the
     * alpha plane.
     *
     * @param picture the alpha picture, a {@link YuvPicture} or a {@link Yuv16Picture} at least
     * {@code w} by {@code h}
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param w the region width
     * @param h the region height
     * @param limited whether the alpha samples are limited range
     * @throws MediaException.Malformed if the picture is not planar
     */
    public void writeAlpha(Picture picture, int x, int y, int w, int h, boolean limited) {
        switch (picture) {
            case YuvPicture yuv -> {
                var samples = new short[w];
                for (var row = 0; row < h; row++) {
                    var in = row * yuv.yStride();
                    for (var col = 0; col < w; col++) {
                        samples[col] = (short) (yuv.y()[in + col] & 0xFF);
                    }
                    writeAlpha(samples, 0, w, 8, x, y + row, w, 1, limited);
                }
            }
            case Yuv16Picture yuv -> writeAlpha(yuv.y(), 0, yuv.yStride(), yuv.bitDepth(), x, y, w, h, limited);
            default -> throw new MediaException.Malformed(name + ": the alpha image is not a planar picture");
        }
    }

    /**
     * Copies alpha samples into a region of the alpha plane.
     *
     * @param src the alpha samples
     * @param srcOffset the first sample
     * @param stride the source stride
     * @param depth the bit depth of the samples
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param w the region width
     * @param h the region height
     * @param limited whether the samples are limited range
     */
    private void writeAlpha(short[] src, int srcOffset, int stride, int depth, int x, int y, int w, int h,
                            boolean limited) {
        var max = (1 << depth) - 1;
        var low = 16 << (depth - 8);
        var span = (235 - 16) << (depth - 8);
        var canvasMax = (1 << bitDepth) - 1;
        for (var row = 0; row < h; row++) {
            var in = srcOffset + row * stride;
            var out = x + (y + row) * width;
            for (var col = 0; col < w; col++) {
                // long: (value - low) * max overflows an int for 16-bit limited-range alpha
                long value = Math.min(src[in + col] & 0xFFFF, max);
                if (limited) {
                    value = Math.clamp(((value - low) * max + span / 2) / span, 0, max);
                }
                if (depth != bitDepth) {
                    value = (value * canvasMax + max / 2) / max;
                }
                if (wide) {
                    widePlanes[3][out + col] = (short) value;
                } else {
                    narrowPlanes[3][out + col] = (byte) (bitDepth == 8 ? value
                            : Math.min(255, (value * 255 + canvasMax / 2) / canvasMax));
                }
            }
        }
    }

    /**
     * Wraps the planes into a picture, without copying them.
     *
     * @param color the color description
     * @return a {@link YuvPicture}, or a {@link Yuv16Picture} when the canvas is wide
     */
    public Picture toPicture(ColorInfo color) {
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
