package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.color.YcbcrModel;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Assembles the component planes of an image item ({@link HeifComponents}) into a planar
 * picture.
 *
 * <p>The color model follows the component types: red, green and blue make a 4:4:4 picture with
 * the identity matrix (green in the luma plane, blue and red in the chroma planes, as H.273 codes
 * RGB); luma with both chroma components make a Y'CbCr picture in the chroma format their
 * subsampling gives, 4:1:1 being widened to 4:4:4 by repeating each chroma sample; luma or a
 * monochrome component alone makes a monochrome picture. An alpha component, straight or
 * premultiplied, becomes the alpha plane. Components of up to 8 bits make an 8-bit picture and
 * deeper ones a picture of the depth of the deepest color component; every plane of another depth
 * is rescaled to the picture depth, {@code (v * max + m / 2) / m} for a sample {@code v} of
 * largest value {@code m}, so that an exact 8-bit or deeper source keeps its samples.
 *
 * <p>Components that a planar picture cannot hold ({@link HeifComponents#needsChannels()}: ink
 * components or planes deeper than 16 bits) are assembled into a {@link ChannelPicture} by
 * {@link #toChannels(String, HeifComponents, boolean, ColorInfo, MediaLimits)} instead.
 */
public final class HeifComponentPictures {
    /**
     * Prevents instantiation.
     */
    private HeifComponentPictures() {
        throw new AssertionError();
    }

    /**
     * {@return whether the components hold alpha the color is premultiplied by}
     *
     * @param components the decoded planes of an item
     */
    public static boolean premultiplied(HeifComponents components) {
        return components.plane(HeifComponents.PREMULTIPLIED_ALPHA) != null;
    }

    /**
     * Assembles components into a planar picture.
     *
     * @param name the format name, for messages
     * @param components the decoded planes of an item
     * @param color the color description of Y'CbCr and monochrome pictures; an RGB picture takes it
     * with the identity matrix and full range
     * @param limits the bounds of the picture
     * @return a {@link YuvPicture}, or a {@link Yuv16Picture} for components deeper than 8 bits
     * @throws MediaException.Malformed if the planes do not fit the image size or a chroma
     * component lacks its pair
     * @throws IllegalArgumentException if the components need a channel picture
     * ({@link HeifComponents#needsChannels()})
     * @throws MediaException.Unsupported if the components hold no color, or chroma of an
     * unsupported subsampling
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static Picture toPicture(String name, HeifComponents components, ColorInfo color, MediaLimits limits) {
        if (components.needsChannels()) {
            throw new IllegalArgumentException("the components need a channel picture");
        }
        var width = components.width();
        var height = components.height();
        LimitChecks.checkPicture(limits, width, height);
        var red = components.plane(HeifComponents.RED);
        var green = components.plane(HeifComponents.GREEN);
        var blue = components.plane(HeifComponents.BLUE);
        var luma = components.plane(HeifComponents.Y);
        var cb = components.plane(HeifComponents.CB);
        var cr = components.plane(HeifComponents.CR);
        var alpha = components.plane(HeifComponents.ALPHA);
        if (alpha == null) {
            alpha = components.plane(HeifComponents.PREMULTIPLIED_ALPHA);
        }
        HeifComponents.Plane first;
        HeifComponents.Plane second = null;
        HeifComponents.Plane third = null;
        ChromaFormat format;
        if (red != null && green != null && blue != null) {
            first = green;
            second = blue;
            third = red;
            format = ChromaFormat.YUV444;
            color = color.withMatrix(MatrixCoefficients.IDENTITY).withRange(ColorRange.FULL);
        } else if (luma != null && (cb != null || cr != null)) {
            if (cb == null || cr == null) {
                throw new MediaException.Malformed(name + ": a chroma component lacks its pair");
            }
            first = luma;
            second = cb;
            third = cr;
            if (cb.subsamplingX() != cr.subsamplingX() || cb.subsamplingY() != cr.subsamplingY()) {
                throw new MediaException.Malformed(name + ": the chroma components differ in subsampling");
            }
            format = switch (cb.subsamplingX() * 8 + cb.subsamplingY()) {
                case 9 -> ChromaFormat.YUV444;
                case 17 -> ChromaFormat.YUV422;
                case 18 -> ChromaFormat.YUV420;
                case 33 -> ChromaFormat.YUV444;
                default -> throw new MediaException.Unsupported(name, "chroma subsampled " + cb.subsamplingX() + "x"
                        + cb.subsamplingY());
            };
        } else if (luma != null || components.plane(HeifComponents.MONOCHROME) != null) {
            first = luma != null ? luma : components.plane(HeifComponents.MONOCHROME);
            format = ChromaFormat.MONOCHROME;
        } else {
            throw new MediaException.Unsupported(name, "images without luma, monochrome or RGB components");
        }
        check(name, first, width, height, 1, 1);
        if (second != null) {
            check(name, second, width, height, second.subsamplingX(), second.subsamplingY());
            check(name, third, width, height, third.subsamplingX(), third.subsamplingY());
        }
        if (alpha != null) {
            check(name, alpha, width, height, 1, 1);
        }
        var depth = first.bitDepth();
        if (second != null) {
            depth = Math.max(depth, Math.max(second.bitDepth(), third.bitDepth()));
        }
        var deep = depth > 8;
        var target = deep ? depth : 8;
        var chromaWidth = format.chromaWidth(width);
        var chromaHeight = format.chromaHeight(height);
        var samples = (long) width * height * (alpha != null ? 2 : 1)
                + (format.hasChroma() ? 2L * chromaWidth * chromaHeight : 0);
        LimitChecks.checkAllocation(limits, samples * (deep ? 2 : 1));
        if (deep) {
            var builder = Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(target).color(color)
                    .luma(wide(first, target, width, height), width);
            if (second != null) {
                builder.chroma(wide(second, target, chromaWidth, chromaHeight),
                        wide(third, target, chromaWidth, chromaHeight), chromaWidth);
            }
            if (alpha != null) {
                builder.alpha(wide(alpha, target, width, height), width);
            }
            return builder.build();
        }
        var builder = YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                .luma(narrow(first, width, height), width);
        if (second != null) {
            builder.chroma(narrow(second, chromaWidth, chromaHeight), narrow(third, chromaWidth, chromaHeight),
                    chromaWidth);
        }
        if (alpha != null) {
            builder.alpha(narrow(alpha, width, height), width);
        }
        return builder.build();
    }

    /**
     * Checks that a plane covers the image at its subsampling.
     *
     * @param name the format name, for messages
     * @param plane the component plane to check
     * @param width the image width
     * @param height the image height
     * @param subsamplingX the horizontal subsampling the plane must have
     * @param subsamplingY the vertical subsampling the plane must have
     * @throws MediaException.Malformed if the plane has another size or subsampling
     */
    private static void check(String name, HeifComponents.Plane plane, int width, int height, int subsamplingX,
                              int subsamplingY) {
        var w = (width + subsamplingX - 1) / subsamplingX;
        var h = (height + subsamplingY - 1) / subsamplingY;
        if (plane.subsamplingX() != subsamplingX || plane.subsamplingY() != subsamplingY || plane.width() != w
                || plane.height() != h || plane.length() < (long) w * h || plane.bitDepth() < 1
                || plane.bitDepth() > (plane.deep() ? HeifComponents.MAX_BIT_DEPTH : 16)) {
            throw new MediaException.Malformed(name + ": a component plane of " + plane.width() + "x" + plane.height()
                    + " does not cover a " + width + "x" + height + " image");
        }
    }

    /**
     * Returns the samples of a plane at 8 bits, at the size of a picture plane.
     *
     * @param plane the component plane to convert
     * @param width the picture plane width: the plane width, or the image width for a plane widened from 4:1:1
     * @param height the picture plane height
     * @return the samples
     */
    private static byte[] narrow(HeifComponents.Plane plane, int width, int height) {
        var out = new byte[width * height];
        var max = (1 << plane.bitDepth()) - 1;
        var repeat = plane.width() == width ? 1 : plane.subsamplingX();
        var src = plane.samples();
        for (var y = 0; y < height; y++) {
            var in = y * plane.width();
            var at = y * width;
            for (var x = 0; x < width; x++) {
                var v = Math.min(src[in + x / repeat] & 0xFFFF, max);
                out[at + x] = (byte) (max == 255 ? v : (v * 255 + max / 2) / max);
            }
        }
        return out;
    }

    /**
     * Returns the samples of a plane at a depth above 8 bits, at the size of a picture plane.
     *
     * @param plane the component plane to convert
     * @param depth the picture depth
     * @param width the picture plane width: the plane width, or the image width for a plane widened from 4:1:1
     * @param height the picture plane height
     * @return the samples
     */
    private static short[] wide(HeifComponents.Plane plane, int depth, int width, int height) {
        var out = new short[width * height];
        var max = (1 << plane.bitDepth()) - 1;
        var target = (1 << depth) - 1;
        var repeat = plane.width() == width ? 1 : plane.subsamplingX();
        var src = plane.samples();
        for (var y = 0; y < height; y++) {
            var in = y * plane.width();
            var at = y * width;
            for (var x = 0; x < width; x++) {
                long v = Math.min(src[in + x / repeat] & 0xFFFF, max);
                out[at + x] = (short) (max == target ? v : (v * target + max / 2) / max);
            }
        }
        return out;
    }

    /**
     * Assembles components into a channel picture, for components that a planar picture cannot hold.
     *
     * <p>Cyan, magenta, yellow and black components make a {@link PixelLayout#CMYK} picture whose
     * inks keep their stored meaning, 0 being no ink. Otherwise the color model follows the
     * component types as for {@link #toPicture}: red, green and blue make RGB; luma with both chroma
     * components is converted to R'G'B' with the matrix and range of {@code color}, as ITU-T H.273
     * defines them; luma or a monochrome component alone makes gray, normalized with the range of
     * {@code color}. An alpha component, straight or premultiplied, follows the color channels as a
     * {@link ChannelType#ALPHA} or {@link ChannelType#PREMULTIPLIED_ALPHA} channel. A subsampled
     * plane is upsampled by replication, each pixel taking the sample that covers it.
     *
     * <p>When a plane is deeper than 16 bits the picture is a {@link ChannelFloatPicture} whose
     * samples are {@code v / (2^n - 1)} for a sample {@code v} of {@code n} bits (converted luma and
     * chroma give the normalized R'G'B' signals, unclamped). Otherwise, which leaves only ink
     * components, the picture is a {@link Channel16Picture} when {@code wide} is set and a plane has
     * more than 8 bits, and a {@link Channel8Picture} else, every sample scaled to the picture depth
     * as {@code (v * M + m / 2) / m} for a largest value {@code m} and a target largest value
     * {@code M}.
     *
     * @param name the format name, for messages
     * @param components the decoded planes of an item
     * @param wide whether ink components of more than 8 bits keep 16 bits
     * @param color the color description of luma, chroma and monochrome components
     * @param limits the bounds of the picture
     * @return the picture
     * @throws MediaException.Malformed if the planes do not fit the image size or a chroma
     * component lacks its pair
     * @throws MediaException.Unsupported if the components hold no color model, or only some inks
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static ChannelPicture toChannels(String name, HeifComponents components, boolean wide, ColorInfo color,
                                            MediaLimits limits) {
        var width = components.width();
        var height = components.height();
        LimitChecks.checkPicture(limits, width, height);
        var inks = new HeifComponents.Plane[]{components.plane(HeifComponents.CYAN),
                components.plane(HeifComponents.MAGENTA), components.plane(HeifComponents.YELLOW),
                components.plane(HeifComponents.BLACK)};
        var inkCount = 0;
        for (var ink : inks) {
            inkCount += ink != null ? 1 : 0;
        }
        HeifComponents.Plane[] colors;
        PixelLayout layout;
        var convert = false;
        if (inkCount == 4) {
            colors = inks;
            layout = PixelLayout.CMYK;
        } else if (inkCount > 0) {
            throw new MediaException.Unsupported(name, "images with " + inkCount + " of the four CMYK inks");
        } else {
            var red = components.plane(HeifComponents.RED);
            var green = components.plane(HeifComponents.GREEN);
            var blue = components.plane(HeifComponents.BLUE);
            var luma = components.plane(HeifComponents.Y);
            var cb = components.plane(HeifComponents.CB);
            var cr = components.plane(HeifComponents.CR);
            if (red != null && green != null && blue != null) {
                colors = new HeifComponents.Plane[]{red, green, blue};
                layout = PixelLayout.RGB;
            } else if (luma != null && (cb != null || cr != null)) {
                if (cb == null || cr == null) {
                    throw new MediaException.Malformed(name + ": a chroma component lacks its pair");
                }
                colors = new HeifComponents.Plane[]{luma, cb, cr};
                layout = PixelLayout.RGB;
                convert = true;
            } else if (luma != null || components.plane(HeifComponents.MONOCHROME) != null) {
                colors = new HeifComponents.Plane[]{luma != null ? luma : components.plane(HeifComponents.MONOCHROME)};
                layout = PixelLayout.GRAY;
            } else {
                throw new MediaException.Unsupported(name, "images without luma, monochrome, RGB or CMYK components");
            }
        }
        var alpha = components.plane(HeifComponents.ALPHA);
        var alphaType = ChannelType.ALPHA;
        if (alpha == null) {
            alpha = components.plane(HeifComponents.PREMULTIPLIED_ALPHA);
            alphaType = ChannelType.PREMULTIPLIED_ALPHA;
        }
        var deep = false;
        var maxDepth = 0;
        for (var plane : colors) {
            if (plane.subsamplingX() < 1 || plane.subsamplingY() < 1) {
                throw new MediaException.Malformed(name + ": a component plane has an invalid subsampling");
            }
            check(name, plane, width, height, plane.subsamplingX(), plane.subsamplingY());
            deep |= plane.deep();
            maxDepth = Math.max(maxDepth, plane.bitDepth());
        }
        var planes = new HeifComponents.Plane[colors.length + (alpha != null ? 1 : 0)];
        System.arraycopy(colors, 0, planes, 0, colors.length);
        if (alpha != null) {
            check(name, alpha, width, height, 1, 1);
            deep |= alpha.deep();
            maxDepth = Math.max(maxDepth, alpha.bitDepth());
            layout = layout.withChannel(Channel.of(alphaType));
            planes[colors.length] = alpha;
        }
        var count = planes.length;
        var length = (long) width * height * count;
        if (deep) {
            LimitChecks.checkAllocation(limits, length * 4);
            var picture = ChannelFloatPicture.allocate(width, height, layout);
            var out = picture.pixels();
            var converter = convert ? new YcbcrConverter(colors, color) : null;
            var limitedGray = layout.colorModel() == ColorModel.GRAY && color.range() == ColorRange.LIMITED;
            fill(planes, width, height, (y, columns, scratch) -> {
                if (converter != null) {
                    converter.row(y, columns, width, out, count, scratch);
                } else {
                    for (var c = 0; c < colors.length; c++) {
                        floats(planes[c], y, columns[c], out, c, count, width, limitedGray);
                    }
                }
                if (count > colors.length) {
                    floats(planes[count - 1], y, columns[count - 1], out, count - 1, count, width, false);
                }
            });
            return picture;
        }
        if (wide && maxDepth > 8) {
            LimitChecks.checkAllocation(limits, length * 2);
            var picture = Channel16Picture.allocate(width, height, layout);
            var out = picture.pixels();
            fill(planes, width, height, (y, columns, scratch) -> {
                for (var c = 0; c < count; c++) {
                    var plane = planes[c];
                    var max = (1L << plane.bitDepth()) - 1;
                    var at = y * width * count + c;
                    var row = (y / plane.subsamplingY()) * plane.width();
                    var map = columns[c];
                    for (var x = 0; x < width; x++, at += count) {
                        var v = Math.min(plane.sample(row + map[x]), max);
                        out[at] = (short) ((v * 65535 + max / 2) / max);
                    }
                }
            });
            return picture;
        }
        LimitChecks.checkAllocation(limits, length);
        var picture = Channel8Picture.allocate(width, height, layout);
        var out = picture.pixels();
        fill(planes, width, height, (y, columns, scratch) -> {
            for (var c = 0; c < count; c++) {
                var plane = planes[c];
                var max = (1L << plane.bitDepth()) - 1;
                var at = y * width * count + c;
                var row = (y / plane.subsamplingY()) * plane.width();
                var map = columns[c];
                for (var x = 0; x < width; x++, at += count) {
                    var v = Math.min(plane.sample(row + map[x]), max);
                    out[at] = (byte) ((v * 255 + max / 2) / max);
                }
            }
        });
        return picture;
    }

    /**
     * Replaces or appends the alpha channel of a channel picture.
     *
     * @param picture the color channels, not modified
     * @param alpha a {@link YuvPicture} or {@link Yuv16Picture} of the picture's size whose alpha
     * plane holds full-range alpha
     * @param premultiplied whether the color channels are premultiplied by the alpha
     * @return a new picture of the same sample type: the color channels of {@code picture} followed
     * by the alpha, rescaled from its largest value {@code m} to the sample type as
     * {@code (v * M + m / 2) / m} for a largest integer sample {@code M}, or {@code v / m}
     * @throws IllegalArgumentException if {@code alpha} is not planar or has no alpha plane
     */
    public static ChannelPicture withAlpha(ChannelPicture picture, Picture alpha, boolean premultiplied) {
        var width = picture.width();
        var height = picture.height();
        var source = picture.layout();
        var colors = source.colorChannels();
        var layout = source.colorOnly().withChannel(Channel.of(premultiplied ? ChannelType.PREMULTIPLIED_ALPHA
                : ChannelType.ALPHA));
        var count = layout.count();
        var oldCount = source.count();
        int alphaStride;
        long alphaMax;
        byte[] alpha8 = null;
        short[] alpha16 = null;
        switch (alpha) {
            case YuvPicture yuv when yuv.hasAlpha() -> {
                alphaMax = 255;
                alphaStride = yuv.alphaStride();
                alpha8 = yuv.alpha();
            }
            case Yuv16Picture yuv when yuv.hasAlpha() -> {
                alphaMax = (1L << yuv.bitDepth()) - 1;
                alphaStride = yuv.alphaStride();
                alpha16 = yuv.alpha();
            }
            default -> throw new IllegalArgumentException("alpha must be planar with an alpha plane: " + alpha);
        }
        var values = new long[width];
        switch (picture) {
            case Channel8Picture eight -> {
                var out = Channel8Picture.allocate(width, height, layout);
                for (var y = 0; y < height; y++) {
                    alphaRow(alpha8, alpha16, y * alphaStride, values, alphaMax, 255);
                    for (var x = 0; x < width; x++) {
                        var at = (y * width + x) * count;
                        System.arraycopy(eight.pixels(), y * eight.stride() + x * oldCount, out.pixels(), at, colors);
                        out.pixels()[at + colors] = (byte) values[x];
                    }
                }
                return out;
            }
            case Channel16Picture sixteen -> {
                var out = Channel16Picture.allocate(width, height, layout);
                for (var y = 0; y < height; y++) {
                    alphaRow(alpha8, alpha16, y * alphaStride, values, alphaMax, 65535);
                    for (var x = 0; x < width; x++) {
                        var at = (y * width + x) * count;
                        System.arraycopy(sixteen.pixels(), y * sixteen.stride() + x * oldCount, out.pixels(), at,
                                colors);
                        out.pixels()[at + colors] = (short) values[x];
                    }
                }
                return out;
            }
            case ChannelFloatPicture floats -> {
                var out = ChannelFloatPicture.allocate(width, height, layout);
                for (var y = 0; y < height; y++) {
                    alphaRow(alpha8, alpha16, y * alphaStride, values, alphaMax, alphaMax);
                    for (var x = 0; x < width; x++) {
                        var at = (y * width + x) * count;
                        System.arraycopy(floats.pixels(), y * floats.stride() + x * oldCount, out.pixels(), at,
                                colors);
                        out.pixels()[at + colors] = (float) (values[x] / (double) alphaMax);
                    }
                }
                return out;
            }
        }
    }

    /**
     * Reads one row of alpha, rescaled to another largest value.
     *
     * @param alpha8 the 8-bit alpha plane, or {@code null}
     * @param alpha16 the deeper alpha plane, or {@code null}
     * @param offset the first sample of the row
     * @param out receives the rescaled values, one per column
     * @param max the largest alpha value
     * @param target the largest value of the result
     */
    private static void alphaRow(byte[] alpha8, short[] alpha16, int offset, long[] out, long max, long target) {
        for (var x = 0; x < out.length; x++) {
            long v = alpha8 != null ? alpha8[offset + x] & 0xFF : alpha16[offset + x] & 0xFFFF;
            out[x] = max == target ? v : (v * target + max / 2) / max;
        }
    }

    /**
     * Fills the rows of a channel picture, in parallel bands.
     *
     * @param planes the planes of the channels, in channel order
     * @param width the image width
     * @param height the image height
     * @param filler fills one row of the picture
     */
    private static void fill(HeifComponents.Plane[] planes, int width, int height, RowFiller filler) {
        var columns = new int[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            var sx = planes[c].subsamplingX();
            var map = new int[width];
            for (var x = 0; x < width; x++) {
                map[x] = x / sx;
            }
            columns[c] = map;
        }
        var bands = ParallelBands.bands(height, 8L * width * height * planes.length);
        ParallelBands.run(bands, band -> {
            var scratch = new Scratch();
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                filler.fill(y, columns, scratch);
            }
        });
    }

    /**
     * Fills one row of a channel picture.
     */
    @FunctionalInterface
    private interface RowFiller {
        /**
         * Fills a row.
         *
         * @param y the row
         * @param columns the plane column of each image column, per channel
         * @param scratch the scratch arrays of the calling band
         */
        void fill(int y, int[][] columns, Scratch scratch);
    }

    /**
     * Holds the scratch arrays of one band of a conversion.
     */
    private static final class Scratch {
        /**
         * The first sample of the current row, per plane.
         */
        private final int[] rows = new int[3];

        /**
         * The converted signals of one pixel.
         */
        private final double[] rgb = new double[3];

        /**
         * The integer samples of one pixel.
         */
        private final int[] ints = new int[3];
    }

    /**
     * Writes the normalized samples of one plane into one row of a floating-point picture.
     *
     * @param plane the component plane to read
     * @param y the image row
     * @param columns the plane column of each image column
     * @param out the picture samples
     * @param channel the channel index
     * @param count the channels per pixel
     * @param width the image width
     * @param limited whether the plane is limited-range luma, whose black and white are
     * {@code 16 * 2^(n-8)} and {@code 235 * 2^(n-8)}
     */
    private static void floats(HeifComponents.Plane plane, int y, int[] columns, float[] out, int channel, int count,
                               int width, boolean limited) {
        var depth = plane.bitDepth();
        var offset = limited ? Math.scalb(16.0, depth - 8) : 0;
        var scale = limited ? Math.scalb(219.0, depth - 8) : Math.scalb(1.0, depth) - 1;
        var row = (y / plane.subsamplingY()) * plane.width();
        var at = y * width * count + channel;
        for (var x = 0; x < width; x++, at += count) {
            out[at] = (float) ((plane.sample(row + columns[x]) - offset) / scale);
        }
    }

    /**
     * Converts luma and chroma planes to normalized R'G'B' signals as ITU-T H.273 defines them.
     */
    private static final class YcbcrConverter {
        /**
         * The luma, Cb and Cr planes.
         */
        private final HeifComponents.Plane[] planes;

        /**
         * The model of the matrix, or {@code null} for YCgCo-Re and YCgCo-Ro.
         */
        private final YcbcrModel model;

        /**
         * The resolved matrix.
         */
        private final MatrixCoefficients matrix;

        /**
         * The quantization range.
         */
        private final ColorRange range;

        /**
         * The code value of a zero signal, per component.
         */
        private final double[] offset = new double[3];

        /**
         * The code-value span of a unit signal, per component.
         */
        private final double[] scale = new double[3];

        /**
         * Prepares the conversion.
         *
         * @param planes the luma, Cb and Cr planes
         * @param color the color description
         */
        YcbcrConverter(HeifComponents.Plane[] planes, ColorInfo color) {
            this.planes = planes;
            this.matrix = color.matrix().effective();
            this.range = color.range();
            this.model = YcgcoR.applies(matrix) ? null : YcbcrModel.of(color);
            for (var c = 0; c < 3; c++) {
                var depth = planes[c].bitDepth();
                if (model != null && depth >= 8) {
                    offset[c] = model.offset(c, range, depth);
                    scale[c] = model.scale(c, range, depth);
                } else {
                    // H.273 quantizes from 8 bits up, so shallower planes are read as full range
                    offset[c] = c == 0 ? 0 : Math.scalb(1.0, depth - 1);
                    scale[c] = Math.scalb(1.0, depth) - 1;
                }
            }
        }

        /**
         * Converts one row.
         *
         * @param y the image row
         * @param columns the plane column of each image column, per plane
         * @param width the image width
         * @param out the picture samples, red, green and blue first in each pixel
         * @param count the channels per pixel
         * @param scratch the scratch arrays of the calling band
         */
        void row(int y, int[][] columns, int width, float[] out, int count, Scratch scratch) {
            var rows = scratch.rows;
            for (var c = 0; c < 3; c++) {
                rows[c] = (y / planes[c].subsamplingY()) * planes[c].width();
            }
            var rgb = scratch.rgb;
            var ints = scratch.ints;
            var depth = planes[0].bitDepth();
            var rgbDepth = model == null ? YcgcoR.rgbDepth(matrix, depth) : 0;
            var at = y * width * count;
            for (var x = 0; x < width; x++, at += count) {
                var luma = planes[0].sample(rows[0] + columns[0][x]);
                var cb = planes[1].sample(rows[1] + columns[1][x]);
                var cr = planes[2].sample(rows[2] + columns[2][x]);
                if (model != null) {
                    model.toRgb((luma - offset[0]) / scale[0], (cb - offset[1]) / scale[1], (cr - offset[2]) / scale[2],
                            rgb);
                } else {
                    YcgcoR.inverse(luma, cb, cr, depth, rgbDepth, ints);
                    for (var c = 0; c < 3; c++) {
                        rgb[c] = YcgcoR.toSignal(ints[c], range, rgbDepth);
                    }
                }
                out[at] = (float) rgb[0];
                out[at + 1] = (float) rgb[1];
                out[at + 2] = (float) rgb[2];
            }
        }
    }
}
