package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.dv.DvFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.field.Plane;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

/**
 * Fits the pictures of a transcode into the raster of a DV format: the displayed picture, its samples square, is
 * scaled to fit the format's display (16:9 when it is wider than 14:9 or the format is DVCPRO HD, 4:3 otherwise) and
 * centered on black, the result coded at the format's size and sample aspect ratio.
 *
 * <p>Interlaced pictures keep their fields apart: when their height changes, each field is scaled on its own and the
 * two are woven again; when their field order differs from the format's, the picture moves one line down (top field
 * first into bottom field first) or up, so that the field shown first is still the earlier one.
 *
 * <p>Instances are not thread-safe.
 */
final class DvRaster {
    private final DvFormat format;

    /**
     * Whether the display is 16:9, decided by the first plan.
     */
    private boolean wide;

    private boolean planned;
    private int contentWidth;
    private int contentHeight;

    /**
     * Constructs the raster of a format.
     *
     * @param format the DV format written
     */
    DvRaster(DvFormat format) {
        this.format = format;
    }

    /**
     * {@return the DV format written}
     */
    DvFormat format() {
        return format;
    }

    /**
     * {@return whether the display is 16:9; valid once a plan was made}
     */
    boolean wide() {
        return wide;
    }

    /**
     * {@return the sample aspect ratio of the coded pictures; valid once a plan was made}
     */
    Rational sampleAspectRatio() {
        return format.sampleAspectRatio(wide);
    }

    /**
     * Plans the scaling of a displayed picture of square samples to the part of the raster it covers.
     *
     * @param displayWidth the displayed width in square samples
     * @param displayHeight the displayed height
     * @param field whether the picture is one field of a frame, whose samples the caller made square by halving
     * their width, scaled to half the covered height
     * @return the plan
     */
    SizePlan plan(int displayWidth, int displayHeight, boolean field) {
        var shape = displayWidth / (double) displayHeight;
        if (!planned) {
            wide = format.highDefinition() || shape > 14.0 / 9;
            var aspect = sampleAspectRatio();
            var width = format.width();
            var height = format.height();
            var display = width * (double) aspect.numerator() / aspect.denominator() / height;
            double coveredWidth = width;
            double coveredHeight = height;
            if (shape >= display) {
                coveredHeight = height * display / shape;
            } else {
                coveredWidth = width * shape / display;
            }
            contentWidth = even(coveredWidth, width);
            contentHeight = even(coveredHeight, height);
            planned = true;
        }
        var height = field ? contentHeight / 2 : contentHeight;
        return new SizePlan(contentWidth, height, 0, 0, contentWidth, height);
    }

    /**
     * {@return a length rounded to the nearest even number from 2 to a limit}
     *
     * @param length the exact length
     * @param limit the largest length, even
     */
    private static int even(double length, int limit) {
        return Math.clamp(2 * Math.round(length / 2), 2, limit);
    }

    /**
     * Returns the line shift that turns frames of a scan into frames of the format's field order.
     *
     * @param scan the scan of a frame
     * @return 1 to move the picture one line down, -1 up, 0 to keep it
     */
    int shift(FieldOrder scan) {
        var target = format.interlacing();
        if (!scan.isInterlaced() || target == Interlacing.PROGRESSIVE) {
            return 0;
        }
        var targetTop = target == Interlacing.TOP_FIELD_FIRST;
        return (scan == FieldOrder.TOP_FIRST) == targetTop ? 0 : targetTop ? -1 : 1;
    }

    /**
     * Places a picture on the raster, centered on black at even offsets, moved by a number of lines.
     *
     * @param picture the picture, at most the raster's size; Y'CbCr unless the shift is 0
     * @param shift the lines the picture moves down, from -1 to 1
     * @return the picture of the raster's size, {@code picture} itself when it already covers the raster unmoved
     */
    Picture place(Picture picture, int shift) {
        var width = format.width();
        var height = format.height();
        if (shift == 0 && picture.width() == width && picture.height() == height) {
            return picture;
        }
        var left = (width - picture.width()) / 2 & ~1;
        var top = (height - picture.height()) / 2 & ~1;
        Picture canvas;
        switch (picture) {
            case YuvPicture yuv -> {
                var target = YuvPicture.allocate(width, height, yuv.chromaFormat(), false, yuv.color());
                if (yuv.color().range() != ColorRange.FULL) {
                    java.util.Arrays.fill(target.y(), (byte) 16);
                }
                canvas = target;
            }
            case Yuv16Picture yuv -> {
                var target = Yuv16Picture.allocate(width, height, yuv.chromaFormat(), yuv.bitDepth(), false,
                        yuv.color());
                if (yuv.color().range() != ColorRange.FULL) {
                    java.util.Arrays.fill(target.y(), (short) (16 << (yuv.bitDepth() - 8)));
                }
                canvas = target;
            }
            default -> {
                return ImageOps.pad(picture, width, height, left, top, 0xFF000000);
            }
        }
        var sources = Plane.of(picture);
        var targets = Plane.of(canvas);
        var layout = picture instanceof YuvPicture yuv ? yuv.chromaFormat() : ((Yuv16Picture) picture).chromaFormat();
        for (var index = 0; index < targets.length; index++) {
            var chroma = index > 0;
            var x = chroma ? left >> layout.shiftX() : left;
            var y = chroma ? top >> layout.shiftY() : top;
            copyShifted(sources[index], targets[index], x, y, shift);
        }
        return canvas;
    }

    /**
     * Copies a plane into a larger one at an offset, moved by a number of rows; a row the move leaves empty within
     * the copied area takes the nearest source row of the same field.
     *
     * @param source the plane copied
     * @param target the plane written
     * @param x the column of the source's first sample
     * @param y the row of the source's first row before the move
     * @param shift the rows the source moves down, from -1 to 1
     */
    private static void copyShifted(Plane source, Plane target, int x, int y, int shift) {
        var rows = source.height();
        for (var row = 0; row < rows; row++) {
            var to = y + row + shift;
            if (to >= 0 && to < target.height()) {
                copyRow(source, row, target, to, x);
            }
        }
        if (shift > 0 && rows > 1) {
            copyRow(source, 1, target, y, x);
        } else if (shift < 0 && rows > 1 && y + rows - 1 < target.height()) {
            copyRow(source, rows - 2, target, y + rows - 1, x);
        }
    }

    /**
     * Copies one row of a plane into a row of another at a column.
     *
     * @param source the plane read
     * @param from the row read
     * @param target the plane written
     * @param to the row written
     * @param x the column of the first sample written
     */
    private static void copyRow(Plane source, int from, Plane target, int to, int x) {
        var count = Math.min(source.width(), target.width() - x);
        if (source.wide()) {
            System.arraycopy(source.shorts(), from * source.stride(), target.shorts(), to * target.stride() + x, count);
        } else {
            System.arraycopy(source.bytes(), from * source.stride(), target.bytes(), to * target.stride() + x, count);
        }
    }

    /**
     * Returns whether a picture can be split into fields: a Y'CbCr picture of at least two lines.
     *
     * @param picture a decoded picture
     * @return {@code true} for an 8 or 16-bit Y'CbCr picture
     */
    static boolean splittable(Picture picture) {
        return (picture instanceof YuvPicture || picture instanceof Yuv16Picture) && picture.height() >= 2;
    }

    /**
     * Copies one field of a Y'CbCr picture: every other row of each plane, from row 0 or row 1. A picture of an odd
     * height loses its last row.
     *
     * @param picture the picture, {@linkplain #splittable(Picture) splittable}
     * @param parity 0 for the top field, 1 for the bottom field
     * @return the field, half the picture's height
     */
    static Picture field(Picture picture, int parity) {
        var width = picture.width();
        var height = picture.height() / 2;
        var field = switch (picture) {
            case YuvPicture yuv -> (Picture) YuvPicture.allocate(width, height, yuv.chromaFormat(), yuv.hasAlpha(),
                    yuv.color());
            case Yuv16Picture yuv -> Yuv16Picture.allocate(width, height, yuv.chromaFormat(), yuv.bitDepth(),
                    yuv.hasAlpha(), yuv.color());
            default -> throw new IllegalArgumentException("only Y'CbCr pictures split into fields");
        };
        var sources = Plane.of(picture);
        var targets = Plane.of(field);
        for (var index = 0; index < targets.length; index++) {
            var source = sources[index];
            var target = targets[index];
            var last = source.height() - 1;
            if ((last & 1) != parity) {
                last--;
            }
            for (var row = 0; row < target.height(); row++) {
                copyRow(source, Math.max(0, Math.min(2 * row + parity, last)), target, row, 0);
            }
        }
        return field;
    }

    /**
     * Weaves two fields of the same size and format into a frame of twice their height.
     *
     * @param top the field of the even rows
     * @param bottom the field of the odd rows
     * @return the frame
     */
    static Picture weave(Picture top, Picture bottom) {
        var width = top.width();
        var height = 2 * top.height();
        var frame = switch (top) {
            case YuvPicture yuv -> (Picture) YuvPicture.allocate(width, height, yuv.chromaFormat(), yuv.hasAlpha(),
                    yuv.color());
            case Yuv16Picture yuv -> Yuv16Picture.allocate(width, height, yuv.chromaFormat(), yuv.bitDepth(),
                    yuv.hasAlpha(), yuv.color());
            default -> throw new IllegalArgumentException("only Y'CbCr fields are woven");
        };
        var tops = Plane.of(top);
        var bottoms = Plane.of(bottom);
        var targets = Plane.of(frame);
        for (var index = 0; index < targets.length; index++) {
            var target = targets[index];
            for (var row = 0; row < target.height(); row++) {
                var source = (row & 1) == 0 ? tops[index] : bottoms[index];
                copyRow(source, Math.min(row / 2, source.height() - 1), target, row, 0);
            }
        }
        return frame;
    }
}
