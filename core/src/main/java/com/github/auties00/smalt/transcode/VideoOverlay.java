package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.ops.BlendMode;
import com.github.auties00.smalt.image.ops.CompositeOptions;
import com.github.auties00.smalt.image.ops.ImageCompositing;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Places a mark, an sRGB picture with straight alpha, on video pictures of one layout and color.
 *
 * <p>The mark is converted once to the color of the pictures (through
 * {@link ColorProfile#of(ColorInfo)} when their primaries or transfer differ from sRGB, so that
 * standard-dynamic-range marks land at the reference white of PQ and HLG video) and then composited
 * as {@link ImageCompositing#composite(Picture, Picture, int, int, CompositeOptions)} composites it.
 * Normal blending in gamma space on Y'CbCr pictures of a linear matrix is computed directly on the
 * planes, which gives the same result because the matrix is linear: luma per pixel, chroma with the
 * mean coverage and the mean premultiplied chroma of the pixels a chroma sample covers. Other blend
 * modes, linear-light blending, the nonlinear matrices and RGB pictures go through RGB for the
 * pixels the mark covers. Pictures are modified in place.
 *
 * <p>An instance is not thread-safe.
 */
final class VideoOverlay {
    /**
     * The fixed-point unit of coverage.
     */
    private static final int ONE = 1 << 14;

    /**
     * The color of the mark.
     */
    private static final ColorInfo SRGB = new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL,
            ColorPrimaries.BT709, TransferCharacteristics.SRGB);

    /**
     * The mark, converted to the picture color, as RGBA of 8 or 16 bits.
     */
    private final Picture mark;

    private final int x;
    private final int y;
    private final CompositeOptions options;

    /**
     * Whether the planes are blended directly.
     */
    private final boolean direct;

    /**
     * The clipped rectangle of the mark on the pictures, in luma samples.
     */
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    /**
     * The coverage of each luma sample of the rectangle, from 0 to {@link #ONE}.
     */
    private int[] alpha;

    /**
     * The luma of the mark times its coverage, for each luma sample of the rectangle.
     */
    private int[] lumaPremultiplied;

    /**
     * The chroma rectangle, in chroma samples.
     */
    private int chromaLeft;
    private int chromaTop;
    private int chromaRight;
    private int chromaBottom;

    /**
     * The mean coverage of each chroma sample of the chroma rectangle.
     */
    private int[] chromaAlpha;

    /**
     * The mean premultiplied blue-difference and red-difference chroma of each chroma sample.
     */
    private int[] cbPremultiplied;
    private int[] crPremultiplied;

    /**
     * Prepares a mark for pictures shaped like a template.
     *
     * @param mark the mark, sRGB with straight alpha
     * @param x the picture column of the mark's left edge; may be negative
     * @param y the picture row of the mark's top edge; may be negative
     * @param options the blend mode, opacity and linear-light setting
     * @param template a picture of the layout, size and color of the pictures the mark goes on
     * @param conversion the options of the conversion to the picture color
     */
    VideoOverlay(Picture mark, int x, int y, CompositeOptions options, Picture template, ColorTransformOptions conversion) {
        var color = colorOf(template);
        var converted = convertMark(mark, color, conversion);
        // compositing an 8-bit picture with a 16-bit mark would give a 16-bit picture
        this.mark = template instanceof RgbaPicture && converted instanceof Rgba16Picture ? ImageOps.toRgba(converted)
                : converted;
        this.x = x;
        this.y = y;
        this.options = options;
        this.left = Math.max(0, x);
        this.top = Math.max(0, y);
        this.right = (int) Math.min(template.width(), (long) x + mark.width());
        this.bottom = (int) Math.min(template.height(), (long) y + mark.height());
        this.direct = color != null && options.mode() == BlendMode.NORMAL && !options.linearLight()
                && linear(color.matrix());
        if (direct && left < right && top < bottom) {
            prepareDirect(template, color);
        }
    }

    /**
     * {@return whether the mark covers no pixel of the pictures, or is fully transparent}
     */
    boolean isEmpty() {
        return left >= right || top >= bottom || options.opacity() == 0;
    }

    /**
     * Places the mark on a picture.
     *
     * @param picture the picture, of the template's layout and size; modified in place
     */
    void apply(Picture picture) {
        if (isEmpty()) {
            return;
        }
        if (direct) {
            blendDirect(picture);
        } else {
            blendThroughRgb(picture);
        }
    }

    /**
     * Converts the mark to the color of the pictures.
     *
     * @param mark the mark, sRGB
     * @param color the picture color, or {@code null} for RGB pictures, taken as sRGB
     * @param conversion the conversion options
     * @return the mark, RGBA
     */
    private static Picture convertMark(Picture mark, ColorInfo color, ColorTransformOptions conversion) {
        var packed = mark instanceof RgbaPicture || mark instanceof Rgba16Picture ? mark : ImageOps.toRgba16(mark);
        if (color == null || VideoColor.sameColorimetry(SRGB, color)) {
            return packed;
        }
        var transform = ColorTransform.create(ColorProfile.of(SRGB), ColorProfile.of(color), conversion);
        return ImageOps.convertColor(packed instanceof RgbaPicture rgba ? ImageOps.toSixteenBit(rgba) : packed,
                transform);
    }

    /**
     * {@return the color of a Y'CbCr picture, or {@code null} for an RGB one}
     *
     * @param picture the picture the mark goes on
     */
    private static ColorInfo colorOf(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            default -> null;
        };
    }

    /**
     * {@return whether a matrix derives Y'CbCr linearly from R'G'B'}
     *
     * @param matrix the Y'CbCr matrix
     */
    private static boolean linear(MatrixCoefficients matrix) {
        return switch (matrix) {
            case IDENTITY, BT709, UNSPECIFIED, FCC, BT470BG, BT601, SMPTE240M, YCGCO, BT2020_NCL, CHROMA_DERIVED_NCL ->
                    true;
            default -> false;
        };
    }

    /**
     * Computes the premultiplied planes of the direct path.
     *
     * @param template a picture of the target layout
     * @param color the picture color
     */
    private void prepareDirect(Picture template, ColorInfo color) {
        var format = template instanceof YuvPicture yuv ? yuv.chromaFormat() : ((Yuv16Picture) template).chromaFormat();
        var depth = template instanceof Yuv16Picture yuv ? yuv.bitDepth() : 8;
        var ycc = ImageOps.toYuv16(mark, ChromaFormat.YUV444, color, 16);
        var scale = color.range() == ColorRange.LIMITED ? 1.0 / (1 << (16 - depth)) : ((1 << depth) - 1) / 65535.0;
        var width = right - left;
        var height = bottom - top;
        alpha = new int[width * height];
        lumaPremultiplied = new int[width * height];
        var cb = new double[width * height];
        var cr = new double[width * height];
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var mx = left + column - x;
                var my = top + row - y;
                var coverage = markAlpha(mx, my) * options.opacity();
                var a = (int) Math.round(coverage * ONE);
                var index = row * width + column;
                alpha[index] = a;
                var luma = (ycc.y()[my * ycc.yStride() + mx] & 0xFFFF) * scale;
                lumaPremultiplied[index] = (int) Math.round(luma * a);
                cb[index] = (ycc.u()[my * ycc.uvStride() + mx] & 0xFFFF) * scale * a;
                cr[index] = (ycc.v()[my * ycc.uvStride() + mx] & 0xFFFF) * scale * a;
            }
        }
        if (format == ChromaFormat.MONOCHROME) {
            return;
        }
        var shiftX = format.shiftX();
        var shiftY = format.shiftY();
        chromaLeft = left >> shiftX;
        chromaTop = top >> shiftY;
        chromaRight = (right + (1 << shiftX) - 1) >> shiftX;
        chromaBottom = (bottom + (1 << shiftY) - 1) >> shiftY;
        var chromaWidth = chromaRight - chromaLeft;
        var chromaHeight = chromaBottom - chromaTop;
        chromaAlpha = new int[chromaWidth * chromaHeight];
        cbPremultiplied = new int[chromaWidth * chromaHeight];
        crPremultiplied = new int[chromaWidth * chromaHeight];
        var block = 1 << (shiftX + shiftY);
        for (var row = 0; row < chromaHeight; row++) {
            for (var column = 0; column < chromaWidth; column++) {
                long coverage = 0;
                var blue = 0.0;
                var red = 0.0;
                for (var sy = 0; sy < 1 << shiftY; sy++) {
                    for (var sx = 0; sx < 1 << shiftX; sx++) {
                        var lx = ((chromaLeft + column) << shiftX) + sx - left;
                        var ly = ((chromaTop + row) << shiftY) + sy - top;
                        if (lx < 0 || ly < 0 || lx >= width || ly >= height) {
                            continue;
                        }
                        var index = ly * width + lx;
                        coverage += alpha[index];
                        blue += cb[index];
                        red += cr[index];
                    }
                }
                var index = row * chromaWidth + column;
                chromaAlpha[index] = (int) ((coverage + block / 2) / block);
                cbPremultiplied[index] = (int) Math.round(blue / block);
                crPremultiplied[index] = (int) Math.round(red / block);
            }
        }
    }

    /**
     * {@return the alpha of a pixel of the converted mark, from 0 to 1}
     *
     * @param column the mark column
     * @param row the mark row
     */
    private double markAlpha(int column, int row) {
        return switch (mark) {
            case RgbaPicture rgba -> rgba.hasAlpha() ? (rgba.pixels()[row * rgba.stride() + 4 * column + 3] & 0xFF) / 255.0
                    : 1;
            case Rgba16Picture rgba -> rgba.hasAlpha()
                    ? (rgba.pixels()[row * rgba.stride() + 4 * column + 3] & 0xFFFF) / 65535.0 : 1;
            default -> 1;
        };
    }

    /**
     * Blends the premultiplied planes into a Y'CbCr picture.
     *
     * @param picture the picture the mark goes on
     */
    private void blendDirect(Picture picture) {
        var width = right - left;
        var height = bottom - top;
        var chromaWidth = chromaRight - chromaLeft;
        var chromaHeight = chromaBottom - chromaTop;
        var bands = ParallelBands.bands(height, (long) width * height);
        switch (picture) {
            case YuvPicture yuv -> ParallelBands.run(bands, band -> {
                var last = ParallelBands.start(band + 1, bands, height);
                for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                    blendRow(yuv.y(), (top + row) * yuv.yStride() + left, alpha, lumaPremultiplied, row * width, width);
                    if (yuv.hasAlpha()) {
                        coverRow(yuv.alpha(), (top + row) * yuv.alphaStride() + left, alpha, row * width, width, 255);
                    }
                }
                if (chromaAlpha != null) {
                    var cLast = ParallelBands.start(band + 1, bands, chromaHeight);
                    for (var row = ParallelBands.start(band, bands, chromaHeight); row < cLast; row++) {
                        var offset = (chromaTop + row) * yuv.uvStride() + chromaLeft;
                        blendRow(yuv.u(), offset, chromaAlpha, cbPremultiplied, row * chromaWidth, chromaWidth);
                        blendRow(yuv.v(), offset, chromaAlpha, crPremultiplied, row * chromaWidth, chromaWidth);
                    }
                }
            });
            case Yuv16Picture yuv -> ParallelBands.run(bands, band -> {
                var last = ParallelBands.start(band + 1, bands, height);
                var max = (1 << yuv.bitDepth()) - 1;
                for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                    blendRow(yuv.y(), (top + row) * yuv.yStride() + left, alpha, lumaPremultiplied, row * width, width);
                    if (yuv.hasAlpha()) {
                        coverRow(yuv.alpha(), (top + row) * yuv.alphaStride() + left, alpha, row * width, width, max);
                    }
                }
                if (chromaAlpha != null) {
                    var cLast = ParallelBands.start(band + 1, bands, chromaHeight);
                    for (var row = ParallelBands.start(band, bands, chromaHeight); row < cLast; row++) {
                        var offset = (chromaTop + row) * yuv.uvStride() + chromaLeft;
                        blendRow(yuv.u(), offset, chromaAlpha, cbPremultiplied, row * chromaWidth, chromaWidth);
                        blendRow(yuv.v(), offset, chromaAlpha, crPremultiplied, row * chromaWidth, chromaWidth);
                    }
                }
            });
            default -> blendThroughRgb(picture);
        }
    }

    /**
     * Blends one row of 8-bit samples.
     *
     * @param plane the samples
     * @param offset the first sample
     * @param coverage the coverage of each sample
     * @param premultiplied the mark value times its coverage
     * @param from the first index of the row in the mark arrays
     * @param count the samples
     */
    private static void blendRow(byte[] plane, int offset, int[] coverage, int[] premultiplied, int from, int count) {
        for (var index = 0; index < count; index++) {
            var a = coverage[from + index];
            if (a == 0) {
                continue;
            }
            var value = (plane[offset + index] & 0xFF) * (ONE - a) + premultiplied[from + index];
            plane[offset + index] = (byte) Math.clamp((value + ONE / 2) >> 14, 0, 255);
        }
    }

    /**
     * Blends one row of 16-bit samples.
     *
     * @param plane the samples
     * @param offset the first sample
     * @param coverage the coverage of each sample
     * @param premultiplied the mark value times its coverage
     * @param from the first index of the row in the mark arrays
     * @param count the samples
     */
    private static void blendRow(short[] plane, int offset, int[] coverage, int[] premultiplied, int from, int count) {
        for (var index = 0; index < count; index++) {
            var a = coverage[from + index];
            if (a == 0) {
                continue;
            }
            var value = (long) (plane[offset + index] & 0xFFFF) * (ONE - a) + premultiplied[from + index];
            plane[offset + index] = (short) Math.clamp((value + ONE / 2) >> 14, 0, 65535);
        }
    }

    /**
     * Composites the mark's coverage over one row of an 8-bit alpha plane.
     *
     * @param plane the alpha samples
     * @param offset the first sample
     * @param coverage the coverage of each sample
     * @param from the first index of the row in the mark arrays
     * @param count the samples
     * @param max the opaque value
     */
    private static void coverRow(byte[] plane, int offset, int[] coverage, int from, int count, int max) {
        for (var index = 0; index < count; index++) {
            var a = coverage[from + index];
            var value = (plane[offset + index] & 0xFF) * (long) (ONE - a) + (long) max * a;
            plane[offset + index] = (byte) ((value + ONE / 2) >> 14);
        }
    }

    /**
     * Composites the mark's coverage over one row of a 16-bit alpha plane.
     *
     * @param plane the alpha samples
     * @param offset the first sample
     * @param coverage the coverage of each sample
     * @param from the first index of the row in the mark arrays
     * @param count the samples
     * @param max the opaque value
     */
    private static void coverRow(short[] plane, int offset, int[] coverage, int from, int count, int max) {
        for (var index = 0; index < count; index++) {
            var a = coverage[from + index];
            var value = (plane[offset + index] & 0xFFFF) * (long) (ONE - a) + (long) max * a;
            plane[offset + index] = (short) ((value + ONE / 2) >> 14);
        }
    }

    /**
     * Composites the mark through RGB: the covered region of the picture is converted to RGB,
     * composited, and converted back, and the samples the mark covers are written back.
     *
     * @param picture the picture the mark goes on
     */
    private void blendThroughRgb(Picture picture) {
        switch (picture) {
            case RgbaPicture rgba -> {
                var crop = ImageOps.crop(rgba, left, top, right - left, bottom - top);
                var result = (RgbaPicture) ImageCompositing.composite(crop, mark, x - left, y - top, options);
                for (var row = top; row < bottom; row++) {
                    System.arraycopy(result.pixels(), (row - top) * result.stride(), rgba.pixels(),
                            row * rgba.stride() + 4 * left, 4 * (right - left));
                }
            }
            case Rgba16Picture rgba -> {
                var crop = ImageOps.crop(rgba, left, top, right - left, bottom - top);
                var result = (Rgba16Picture) ImageCompositing.composite(crop, mark, x - left, y - top, options);
                for (var row = top; row < bottom; row++) {
                    System.arraycopy(result.pixels(), (row - top) * result.stride(), rgba.pixels(),
                            row * rgba.stride() + 4 * left, 4 * (right - left));
                }
            }
            case YuvPicture yuv -> {
                var format = yuv.chromaFormat();
                var area = alignedArea(format);
                var crop = ImageOps.crop(yuv, area[0], area[1], area[2] - area[0], area[3] - area[1]);
                var composited = ImageCompositing.composite(ImageOps.toRgba16(crop), mark, x - area[0], y - area[1],
                        options);
                var back = ImageOps.toYuv(composited, format, yuv.color());
                writeBack(yuv, back, area);
            }
            case Yuv16Picture yuv -> {
                var format = yuv.chromaFormat();
                var area = alignedArea(format);
                var crop = ImageOps.crop(yuv, area[0], area[1], area[2] - area[0], area[3] - area[1]);
                var composited = ImageCompositing.composite(ImageOps.toRgba16(crop), mark, x - area[0], y - area[1],
                        options);
                var back = ImageOps.toYuv16(composited, format, yuv.color(), yuv.bitDepth());
                writeBack(yuv, back, area);
            }
            default -> throw new IllegalArgumentException("cannot place a mark on a channel picture");
        }
    }

    /**
     * {@return the rectangle of the mark grown to whole chroma samples, as {left, top, right, bottom}}
     *
     * @param format the chroma format of the pictures
     */
    private int[] alignedArea(ChromaFormat format) {
        var stepX = 1 << format.shiftX();
        var stepY = 1 << format.shiftY();
        return new int[]{left & -stepX, top & -stepY, right, bottom};
    }

    /**
     * Writes the composited samples of a region back into an 8-bit picture: the luma samples the
     * mark covers, and the chroma samples of which it covers any pixel.
     *
     * @param target the picture
     * @param region the composited region
     * @param area the region's rectangle in the picture
     */
    private void writeBack(YuvPicture target, YuvPicture region, int[] area) {
        for (var row = top; row < bottom; row++) {
            for (var column = left; column < right; column++) {
                if (covered(column, row)) {
                    target.y()[row * target.yStride() + column] =
                            region.y()[(row - area[1]) * region.yStride() + column - area[0]];
                }
            }
        }
        if (target.chromaFormat() == ChromaFormat.MONOCHROME) {
            return;
        }
        var shiftX = target.chromaFormat().shiftX();
        var shiftY = target.chromaFormat().shiftY();
        for (var row = area[1] >> shiftY; row < (bottom + (1 << shiftY) - 1) >> shiftY; row++) {
            for (var column = area[0] >> shiftX; column < (right + (1 << shiftX) - 1) >> shiftX; column++) {
                if (chromaCovered(column, row, shiftX, shiftY)) {
                    var source = (row - (area[1] >> shiftY)) * region.uvStride() + column - (area[0] >> shiftX);
                    var index = row * target.uvStride() + column;
                    target.u()[index] = region.u()[source];
                    target.v()[index] = region.v()[source];
                }
            }
        }
    }

    /**
     * Writes the composited samples of a region back into a 16-bit picture, as the 8-bit overload
     * does.
     *
     * @param target the picture
     * @param region the composited region
     * @param area the region's rectangle in the picture
     */
    private void writeBack(Yuv16Picture target, Yuv16Picture region, int[] area) {
        for (var row = top; row < bottom; row++) {
            for (var column = left; column < right; column++) {
                if (covered(column, row)) {
                    target.y()[row * target.yStride() + column] =
                            region.y()[(row - area[1]) * region.yStride() + column - area[0]];
                }
            }
        }
        if (target.chromaFormat() == ChromaFormat.MONOCHROME) {
            return;
        }
        var shiftX = target.chromaFormat().shiftX();
        var shiftY = target.chromaFormat().shiftY();
        for (var row = area[1] >> shiftY; row < (bottom + (1 << shiftY) - 1) >> shiftY; row++) {
            for (var column = area[0] >> shiftX; column < (right + (1 << shiftX) - 1) >> shiftX; column++) {
                if (chromaCovered(column, row, shiftX, shiftY)) {
                    var source = (row - (area[1] >> shiftY)) * region.uvStride() + column - (area[0] >> shiftX);
                    var index = row * target.uvStride() + column;
                    target.u()[index] = region.u()[source];
                    target.v()[index] = region.v()[source];
                }
            }
        }
    }

    /**
     * {@return whether the mark covers a picture pixel with a nonzero alpha}
     *
     * @param column the picture column
     * @param row the picture row
     */
    private boolean covered(int column, int row) {
        if (column < left || column >= right || row < top || row >= bottom) {
            return false;
        }
        return markAlpha(column - x, row - y) > 0;
    }

    /**
     * {@return whether the mark covers any pixel of a chroma sample}
     *
     * @param column the chroma column
     * @param row the chroma row
     * @param shiftX the horizontal subsampling shift
     * @param shiftY the vertical subsampling shift
     */
    private boolean chromaCovered(int column, int row, int shiftX, int shiftY) {
        for (var sy = 0; sy < 1 << shiftY; sy++) {
            for (var sx = 0; sx < 1 << shiftX; sx++) {
                if (covered((column << shiftX) + sx, (row << shiftY) + sy)) {
                    return true;
                }
            }
        }
        return false;
    }
}
