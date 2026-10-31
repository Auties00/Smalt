package com.github.auties00.smalt.image.exr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileCurve;
import com.github.auties00.smalt.image.exr.internal.ExrFile;
import com.github.auties00.smalt.image.exr.internal.ExrHeader;
import com.github.auties00.smalt.image.exr.internal.ExrSamples;
import com.github.auties00.smalt.image.exr.internal.ExrYca;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes OpenEXR images.
 *
 * <p>Supported: single-part and multi-part files; scanline, tiled (one level, mipmap and ripmap levels, either level
 * rounding), deep scanline and deep tiled parts; the compression methods none, RLE, ZIPS, ZIP, PIZ, PXR24, B44, B44A,
 * DWAA, DWAB, and those of OpenEXR 3.4 and later, HTJ2K256, HTJ2K32, LJ2K (JPEG 2000 codestreams with the
 * high-throughput block coder, lossy in LJ2K's RGB channels) and ZSTD (deep parts included); 32-bit unsigned integer,
 * 16-bit and 32-bit floating-point channels, subsampled channels of scanline parts, and offset tables that an
 * incompletely written file left broken, rebuilt from the chunks. Lossless methods decode bit for bit; the lossy
 * ones decode as OpenEXR's reference library decodes them (DWA through its scalar transforms, which its vector paths
 * match within a few units of a half in a few samples; LJ2K's irreversible wavelet in floating point, which rounds
 * apart from the reference one in the last units of some samples).
 *
 * <p>Each part is a page of {@link #openPages(MediaSource, ImageDecoderOptions)}, in file order;
 * {@link #decode(MediaSource, ImageDecoderOptions)} decodes the first part, or with a reduced-size hint the smallest
 * mipmap level of it that meets the hint. A part decodes to its display window: the pixels of its data window placed
 * at their position and the rest left zero; a level other than the full-resolution one decodes to the level's own
 * size. Deep parts are flattened: each pixel's samples are sorted front to back by {@code Z} (then {@code ZBack}) and
 * composited over with {@code A}, {@code Z} keeping the nearest depth.
 *
 * <p>The color of a part comes from its unprefixed {@code R}, {@code G}, {@code B} and {@code A} channels (a
 * missing color channel reads as zero), its {@code Y}, {@code RY} and {@code BY} luminance and chroma channels,
 * converted to RGB as OpenEXR's RGBA interface converts them, or its {@code Y} channel alone as gray; a part without
 * any of these takes them from the first layer (the channels named {@code layer.R} and so on) that has them, and
 * otherwise decodes its first channel as gray. OpenEXR's alpha premultiplies the color. With
 * {@link ImageDecoderOptions#keepChannels()} every channel of the part decodes into a {@link ChannelFloatPicture} of
 * as many channels: the color channels first, then alpha, then the others in name order (subsampled ones
 * replicated); otherwise the color decodes to packed RGBA of 8 bits, or 16 bits with
 * {@link ImageDecoderOptions#highBitDepth()}, values clamped to 0..1 and scaled without a transfer function.
 * Integer channels decode to their values as floats.
 *
 * <p>The samples are linear light. {@link DecodedImage#colorProfile()} reports the primaries and white of the part's
 * {@code chromaticities} attribute, by default those of BT.709 with the D65 white, with a linear tone curve;
 * {@link ImageDecoderOptions#convertToSrgb()} converts the floating-point samples to sRGB first.
 *
 * <p>This class is thread-safe.
 */
public final class ExrDecoder {
    private static final String WHAT = "OpenEXR image";

    /**
     * The chromaticities OpenEXR assumes when a part declares none: BT.709 primaries and the D65 white.
     */
    private static final float[] DEFAULT_CHROMATICITIES = {0.6400f, 0.3300f, 0.3000f, 0.6000f, 0.1500f, 0.0600f,
            0.3127f, 0.3290f};

    private ExrDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes the first part of an OpenEXR file from its header.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} when the part has an {@code A} channel;
     * {@link ImageInfo#frameCount()} is the number of parts.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return Guards.malformed(WHAT, () -> {
            var file = read(source, ImageDecoderOptions.defaults().limits());
            return info(file, 0);
        });
    }

    /**
     * Lists the parts of an OpenEXR file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the parts, in file order, at least one
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the file exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #parts(MediaSource, ImageDecoderOptions)
     */
    public static List<ExrPart> parts(MediaSource source) {
        return parts(source, ImageDecoderOptions.defaults());
    }

    /**
     * Lists the parts of an OpenEXR file, reading it under the limits of {@code options}.
     *
     * @param source the input, read from offset zero
     * @param options the limits; the other options are ignored
     * @return the parts, in file order, at least one
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the file or its part count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<ExrPart> parts(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> {
            var file = read(source, options.limits());
            var parts = new ArrayList<ExrPart>();
            for (var index = 0; index < file.parts().size(); index++) {
                parts.add(describe(file.parts().get(index), index));
            }
            return List.copyOf(parts);
        });
    }

    /**
     * Decodes the first part under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first part, or with a reduced-size hint the smallest mipmap level of it that meets the hint.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint, the region (cut out of the decoded level), the bit depth, the channel
     * choice and the sRGB conversion
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, () -> probe(source), whole -> Guards.malformed(WHAT, () -> {
                var file = read(source, whole.limits());
                var header = file.parts().getFirst();
                var level = 0;
                if ((whole.minWidth() > 0 || whole.minHeight() > 0) && header.levelMode() == ExrHeader.MIPMAP_LEVELS) {
                    for (var candidate = 1; candidate < header.levelsX(); candidate++) {
                        if (header.levelSize(header.width(), candidate) >= whole.minWidth()
                                && header.levelSize(header.height(), candidate) >= whole.minHeight()) {
                            level = candidate;
                        }
                    }
                }
                return decodePart(file, 0, level, whole);
            }));
        }
    }

    /**
     * Decodes one part at full resolution.
     *
     * @param source the input, read from offset zero
     * @param part the part index, from zero to the part count minus one
     * @param options the limits, the region (cut out of the decoded part), the bit depth, the channel choice and the
     * sRGB conversion; the size hint is ignored
     * @return the decoded image
     * @throws IndexOutOfBoundsException if {@code part} is out of range
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the part uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, int part, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var file = Guards.malformed(WHAT, () -> read(source, options.limits()));
            Objects.checkIndex(part, file.parts().size());
            return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT,
                    () -> decodePart(file, part, 0, whole)));
        }
    }

    /**
     * Opens the parts of an OpenEXR file as pages, in file order, each at full resolution.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region (cut out of every whole part) and the decoding choices applied to every
     * part; a part that the region does not lie inside raises {@link IllegalArgumentException} when it is read
     * @return the page reader
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the file or its part count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var region = options.region();
        if (region.isPresent()) {
            return ImageRegions.cropPages(openPages(source, options.toBuilder().wholeImage().build()), region.get());
        }
        var file = Guards.malformed(WHAT, () -> read(source, options.limits()));
        var full = options.toBuilder().fullSize().build();
        return new PageReader() {
            @Override
            public int pageCount() {
                return file.parts().size();
            }

            @Override
            public ImageInfo probe(int index) {
                Objects.checkIndex(index, pageCount());
                return Guards.malformed(WHAT, () -> info(file, index));
            }

            @Override
            public DecodedImage read(int index) {
                Objects.checkIndex(index, pageCount());
                try (var _ = ParallelBands.operation()) {
                    return Guards.malformed(WHAT, () -> decodePart(file, index, 0, full));
                }
            }
        };
    }

    /**
     * Opens the parts of an OpenEXR file as pages under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the file exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Reads and parses a whole file.
     */
    private static ExrFile read(MediaSource source, MediaLimits limits) {
        var data = SourceBytes.readAll(source, limits, false);
        return ExrFile.read(data, limits);
    }

    /**
     * Summarizes one part.
     */
    private static ImageInfo info(ExrFile file, int index) {
        var header = file.parts().get(index);
        var window = window(header);
        var alpha = header.channels().stream().anyMatch(channel -> channel.name().equals("A")
                || channel.name().endsWith(".A"));
        return new ImageInfo(ImageFormat.EXR, window[2], window[3], alpha, false, file.parts().size(),
                Orientation.NORMAL);
    }

    /**
     * Describes one part.
     */
    private static ExrPart describe(ExrHeader header, int index) {
        var window = window(header);
        var storage = switch (header.storage()) {
            case ExrHeader.SCANLINE -> ExrPart.Storage.SCANLINE;
            case ExrHeader.TILED -> ExrPart.Storage.TILED;
            case ExrHeader.DEEP_SCANLINE -> ExrPart.Storage.DEEP_SCANLINE;
            default -> ExrPart.Storage.DEEP_TILED;
        };
        var methods = ExrPart.Compression.values();
        var names = header.channels().stream().map(ExrHeader.Channel::name).toList();
        return new ExrPart(index, Optional.ofNullable(header.name()), storage, methods[header.compression()],
                window[2], window[3], header.xMin() - window[0], header.yMin() - window[1], header.width(),
                header.height(), names, Math.max(header.levelsX(), header.levelsY()));
    }

    /**
     * {@return the display window of a part: left, top, width and height; the data window when it declares none or
     * an invalid one}
     */
    private static int[] window(ExrHeader header) {
        var display = header.attribute("displayWindow");
        if (display != null && display.length >= 16) {
            long xMin = ExrHeader.i32(display, 0);
            long yMin = ExrHeader.i32(display, 4);
            var width = ExrHeader.i32(display, 8) - xMin + 1;
            var height = ExrHeader.i32(display, 12) - yMin + 1;
            if (width > 0 && height > 0 && width <= Integer.MAX_VALUE && height <= Integer.MAX_VALUE) {
                return new int[]{(int) xMin, (int) yMin, (int) width, (int) height};
            }
        }
        return new int[]{header.xMin(), header.yMin(), header.width(), header.height()};
    }

    /**
     * {@return the chromaticities of a part: red, green, blue and white x and y}
     */
    private static float[] chromaticities(ExrHeader header) {
        var value = header.attribute("chromaticities");
        if (value == null || value.length < 32 || !"chromaticities".equals(header.attributeType("chromaticities"))) {
            return DEFAULT_CHROMATICITIES;
        }
        var result = new float[8];
        for (var index = 0; index < 8; index++) {
            result[index] = Float.intBitsToFloat(ExrHeader.i32(value, 4 * index));
            if (!Float.isFinite(result[index])) {
                return DEFAULT_CHROMATICITIES;
            }
        }
        return result;
    }

    /**
     * Decodes one level of a part.
     */
    private static DecodedImage decodePart(ExrFile file, int part, int level, ImageDecoderOptions options) {
        var header = file.parts().get(part);
        var limits = options.limits();
        var planes = ExrSamples.read(file, part, level, level, limits);
        var channels = header.channels();
        var keep = options.keepChannels() && !options.convertToSrgb();
        var selected = new ArrayList<Integer>();
        var layoutChannels = new ArrayList<Channel>();
        float[][] sources;
        var gray = false;
        var luminanceChroma = false;
        var prefix = colorPrefix(channels);
        var red = find(channels, prefix + "R");
        var green = find(channels, prefix + "G");
        var blue = find(channels, prefix + "B");
        var luminance = find(channels, prefix + "Y");
        var redChroma = find(channels, prefix + "RY");
        var blueChroma = find(channels, prefix + "BY");
        var alpha = find(channels, prefix + "A");
        var width = planes.width();
        var height = planes.height();
        var count = (long) width * height;
        float[][] color;
        if (red >= 0 || green >= 0 || blue >= 0) {
            color = new float[][]{full(planes, channels, red, width, height),
                    full(planes, channels, green, width, height), full(planes, channels, blue, width, height)};
            layoutChannels.add(new Channel(ChannelType.RED, red >= 0 ? channels.get(red).name() : ""));
            layoutChannels.add(new Channel(ChannelType.GREEN, green >= 0 ? channels.get(green).name() : ""));
            layoutChannels.add(new Channel(ChannelType.BLUE, blue >= 0 ? channels.get(blue).name() : ""));
            selected.add(red);
            selected.add(green);
            selected.add(blue);
        } else if (luminance >= 0 && redChroma >= 0 && blueChroma >= 0
                && channels.get(redChroma).xSampling() == 2 && channels.get(redChroma).ySampling() == 2
                && channels.get(blueChroma).xSampling() == 2 && channels.get(blueChroma).ySampling() == 2
                && channels.get(luminance).xSampling() == 1 && channels.get(luminance).ySampling() == 1) {
            luminanceChroma = true;
            var alphaPlane = alpha >= 0 ? full(planes, channels, alpha, width, height) : null;
            var rgba = ExrYca.toRgb(width, height, header.xMin(), header.yMin(), planes.planes()[luminance],
                    planes.planes()[redChroma], planes.planes()[blueChroma], planes.widths()[redChroma], alphaPlane,
                    ExrYca.weights(chromaticities(header)));
            color = new float[][]{rgba[0], rgba[1], rgba[2]};
            if (alpha >= 0) {
                planes.planes()[alpha] = rgba[3];
            }
            layoutChannels.add(new Channel(ChannelType.RED, prefix + "R"));
            layoutChannels.add(new Channel(ChannelType.GREEN, prefix + "G"));
            layoutChannels.add(new Channel(ChannelType.BLUE, prefix + "B"));
            selected.add(luminance);
            selected.add(redChroma);
            selected.add(blueChroma);
        } else {
            var single = luminance >= 0 ? luminance : 0;
            gray = true;
            color = new float[][]{full(planes, channels, single, width, height)};
            layoutChannels.add(new Channel(ChannelType.GRAY, channels.get(single).name()));
            selected.add(single);
        }
        var planesOut = new ArrayList<float[]>(List.of(color));
        if (alpha >= 0) {
            planesOut.add(full(planes, channels, alpha, width, height));
            layoutChannels.add(new Channel(ChannelType.PREMULTIPLIED_ALPHA, channels.get(alpha).name()));
            selected.add(alpha);
        }
        if (keep) {
            for (var index = 0; index < channels.size(); index++) {
                if (selected.contains(index)) {
                    continue;
                }
                var name = channels.get(index).name();
                var type = switch (name) {
                    case "Z", "ZBack" -> ChannelType.DEPTH;
                    default -> ChannelType.UNSPECIFIED;
                };
                layoutChannels.add(new Channel(type, name));
                planesOut.add(full(planes, channels, index, width, height));
            }
        }
        var layout = PixelLayout.of(layoutChannels.toArray(Channel[]::new));
        var outWidth = width;
        var outHeight = height;
        var offsetX = 0;
        var offsetY = 0;
        if (level == 0) {
            var display = window(header);
            outWidth = display[2];
            outHeight = display[3];
            offsetX = header.xMin() - display[0];
            offsetY = header.yMin() - display[1];
        }
        LimitChecks.checkPicture(limits, outWidth, outHeight);
        var stride = layout.count();
        LimitChecks.checkAllocation(limits, (long) outWidth * outHeight * stride * 4);
        var picture = ChannelFloatPicture.allocate(outWidth, outHeight, layout);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            var outY = y + offsetY;
            if (outY < 0 || outY >= outHeight) {
                continue;
            }
            for (var x = 0; x < width; x++) {
                var outX = x + offsetX;
                if (outX < 0 || outX >= outWidth) {
                    continue;
                }
                var target = ((long) outY * outWidth + outX) * stride;
                for (var channel = 0; channel < stride; channel++) {
                    pixels[(int) (target + channel)] = planesOut.get(channel)[y * width + x];
                }
            }
        }
        var chroma = chromaticities(header);
        var white = new Chromaticity(chroma[6], chroma[7]);
        var profile = gray ? ColorProfile.gray(white, ProfileCurve.linear())
                : ColorProfile.rgb(new Chromaticity(chroma[0], chroma[1]), new Chromaticity(chroma[2], chroma[3]),
                new Chromaticity(chroma[4], chroma[5]), white, ProfileCurve.linear());
        if (count == 0) {
            throw new MediaException.Malformed("the OpenEXR part is empty");
        }
        return RasterOutput.finish(picture, ImageFormat.EXR, Orientation.NORMAL, profile, null, options);
    }

    /**
     * {@return the prefix of the channels the color comes from: empty when the part has unprefixed color channels,
     * otherwise the first layer that has them}
     */
    private static String colorPrefix(List<ExrHeader.Channel> channels) {
        for (var name : new String[]{"R", "G", "B", "Y"}) {
            if (find(channels, name) >= 0) {
                return "";
            }
        }
        for (var channel : channels) {
            var name = channel.name();
            var dot = name.lastIndexOf('.');
            if (dot < 0) {
                continue;
            }
            var suffix = name.substring(dot + 1);
            if (suffix.equals("R") || suffix.equals("G") || suffix.equals("B") || suffix.equals("Y")) {
                return name.substring(0, dot + 1);
            }
        }
        return "";
    }

    /**
     * {@return the index of a channel, or -1}
     */
    private static int find(List<ExrHeader.Channel> channels, String name) {
        for (var index = 0; index < channels.size(); index++) {
            if (channels.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * {@return a channel's samples at one per pixel, subsampled samples replicated, or zeros for a missing channel}
     */
    private static float[] full(ExrSamples.Planes planes, List<ExrHeader.Channel> channels, int index, int width,
                                int height) {
        if (index < 0) {
            return new float[width * height];
        }
        var channel = channels.get(index);
        var plane = planes.planes()[index];
        if (channel.xSampling() == 1 && channel.ySampling() == 1) {
            return plane;
        }
        var result = new float[width * height];
        var columns = planes.widths()[index];
        var rows = planes.heights()[index];
        if (columns == 0 || rows == 0) {
            return result;
        }
        for (var y = 0; y < height; y++) {
            var row = Math.min(rows - 1, Math.max(0, y / channel.ySampling()));
            for (var x = 0; x < width; x++) {
                var column = Math.min(columns - 1, Math.max(0, x / channel.xSampling()));
                result[y * width + x] = plane[row * columns + column];
            }
        }
        return result;
    }
}
