package com.github.auties00.smalt.image.dds;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.dds.internal.DdsHeader;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Objects;

/**
 * Decodes DirectDraw Surface (DDS) textures.
 *
 * <p>Supported: the legacy header with pixel formats described by channel masks (RGB, luminance, alpha and bump
 * map {@code DUDV} formats of 8 to 32 bits per texel), the four-character codes {@code DXT1} to {@code DXT5},
 * {@code ATI1}, {@code BC4U}, {@code BC4S}, {@code ATI2}, {@code BC5U}, {@code BC5S}, {@code RGBG} and
 * {@code GRGB}, the Direct3D 9 numbers of the 16-bit and floating-point formats (36, 110 to 116); and the DX10
 * header with the DXGI formats BC1 to BC7 (BC6H signed and unsigned), the 32, 16 and 8-bit float, normalized and
 * integer formats of one to four channels, {@code R10G10B10A2}, {@code R11G11B10_FLOAT},
 * {@code R9G9B9E5_SHAREDEXP}, {@code R10G10B10_XR_BIAS_A2}, {@code B5G6R5}, {@code B5G5R5A1}, {@code B4G4R4A4},
 * {@code B8G8R8A8}, {@code B8G8R8X8}, {@code A8} and the RGB pair formats; texture arrays, cube maps (the faces a
 * legacy header lists) and volume textures, with their mipmap levels. YUV, depth, palette and video formats are
 * unsupported. Block-compressed formats decode bit for bit as the reference decoder {@code bcdec} decodes them, the
 * BC4 and BC5 channels with its precise interpolation.
 *
 * <p>Every surface is a page of {@link #openPages(MediaSource, ImageDecoderOptions)}, in file order: each array
 * element, then each cube face, then each mipmap level, then each depth slice of a volume level.
 * {@link #decode(MediaSource, ImageDecoderOptions)} decodes the first surface, or with a reduced-size hint the
 * smallest mipmap level of it that still meets the hint. A file that ends inside its surfaces keeps the surfaces
 * before the end.
 *
 * <p>Channels decode to their nominal values: normalized and integer fields scaled to the full range, signed ones
 * mapped from -1..1 to 0..1 as {@code (v + 1) / 2}. Single-channel formats (BC4, {@code R8}, {@code R16},
 * {@code R32}, luminance) decode to gray, two-channel ones (BC5, {@code R8G8}, {@code R16G16}, {@code R32G32}) to
 * red and green with zero blue ({@code ATI2} holds green in its first block and red in its second, the order of ATI's
 * 3Dc), alpha-only ones to black with alpha. Premultiplied alpha ({@code DXT2},
 * {@code DXT4}, and a DX10 header whose alpha mode is 2) is divided out. Pixels of up to 8 bits decode to packed
 * RGBA; deeper integer ones to packed RGBA of 8 bits, or 16 bits with {@link ImageDecoderOptions#highBitDepth()};
 * floating-point ones (BC6H, the float formats, and 32-bit integers) to packed RGBA as well, clamped to 0..1, or to
 * a {@link ChannelFloatPicture} with {@link ImageDecoderOptions#keepChannels()}. No color profile is reported: a
 * texture's color space is its application's; the {@code _SRGB} formats are sRGB.
 *
 * <p>This class is thread-safe.
 */
public final class DdsDecoder {
    private static final String WHAT = "DDS texture";

    private DdsDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a DDS texture from its header.
     *
     * <p>The size is that of the top mipmap level and {@link ImageInfo#frameCount()} the number of surfaces.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the header is invalid or the file ends before its first surface
     * @throws MediaException.Unsupported if the pixel format is not supported
     * @throws MediaException.LimitExceeded if the file exceeds the default byte limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
                var header = DdsHeader.parse(data);
                var surfaces = header.surfaces(data.length, Integer.MAX_VALUE);
                return new ImageInfo(ImageFormat.DDS, header.width(), header.height(), header.format().hasAlpha(),
                        false, surfaces.size(), Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes the first surface of a DDS texture under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the header is invalid or the file ends before its first surface
     * @throws MediaException.Unsupported if the pixel format is not supported
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first surface of a DDS texture, or the smallest mipmap level of it that meets the size hint.
     *
     * <p>{@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()} are the size of the top level.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint, the region (cut out of the decoded level), the bit depth, the
     * channel and the sRGB conversion choices
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the header is invalid or the file ends before its first surface
     * @throws MediaException.Unsupported if the pixel format is not supported
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, () -> probe(source), whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), false);
                var header = DdsHeader.parse(data);
                var surfaces = header.surfaces(data.length, Integer.MAX_VALUE);
                var chosen = surfaces.getFirst();
                var hinted = whole.minWidth() > 0 || whole.minHeight() > 0;
                for (var surface : surfaces) {
                    if (hinted && surface.element() == 0 && surface.face() == chosen.face() && surface.slice() == 0
                            && surface.width() >= whole.minWidth() && surface.height() >= whole.minHeight()
                            && surface.level() > chosen.level()) {
                        chosen = surface;
                    }
                }
                return decodeSurface(data, header, chosen, whole, true);
            }));
        }
    }

    /**
     * Opens every surface of a DDS texture as a page.
     *
     * <p>The file is read once, when the reader is opened. Each page decodes as
     * {@link #decode(MediaSource, ImageDecoderOptions)} decodes the first surface, with its own size as the source
     * size; the size hint is ignored. Page summaries report the size of their surface.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region (cut out of every page), the bit depth, the channel and the sRGB
     * conversion choices applied to every page; a page that the region does not lie inside raises
     * {@link IllegalArgumentException} when it is read
     * @return the page reader
     * @throws MediaException.Malformed if the header is invalid or the file ends before its first surface
     * @throws MediaException.Unsupported if the pixel format is not supported
     * @throws MediaException.LimitExceeded if the file exceeds the byte limit or holds more surfaces than the frame
     * limit
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var region = options.region();
        if (region.isPresent()) {
            return ImageRegions.cropPages(openPages(source, options.toBuilder().wholeImage().build()), region.get());
        }
        try (var _ = ParallelBands.operation()) {
            var data = Guards.malformed(WHAT, () -> SourceBytes.readAll(source, options.limits(), false));
            var header = Guards.malformed(WHAT, () -> DdsHeader.parse(data));
            List<DdsHeader.Surface> surfaces = Guards.malformed(WHAT, () -> header.surfaces(data.length,
                    options.limits().maxFrames() + 1));
            LimitChecks.checkFrames(options.limits(), surfaces.size());
            var full = options.toBuilder().fullSize().build();
            return new PageReader() {
                @Override
                public int pageCount() {
                    return surfaces.size();
                }

                @Override
                public ImageInfo probe(int index) {
                    var surface = surfaces.get(Objects.checkIndex(index, surfaces.size()));
                    return new ImageInfo(ImageFormat.DDS, surface.width(), surface.height(),
                            header.format().hasAlpha(), false, surfaces.size(), Orientation.NORMAL);
                }

                @Override
                public DecodedImage read(int index) {
                    var surface = surfaces.get(Objects.checkIndex(index, surfaces.size()));
                    try (var _ = ParallelBands.operation()) {
                        return Guards.malformed(WHAT, () -> decodeSurface(data, header, surface, full, false));
                    }
                }
            };
        }
    }

    /**
     * Opens every surface of a DDS texture as a page under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if the header is invalid or the file ends before its first surface
     * @throws MediaException.Unsupported if the pixel format is not supported
     * @throws MediaException.LimitExceeded if the file holds more surfaces than the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes one surface, reporting as its source size the top level's when {@code reduced} is set and its own
     * otherwise.
     */
    private static DecodedImage decodeSurface(byte[] data, DdsHeader header, DdsHeader.Surface surface,
                                              ImageDecoderOptions options, boolean reduced) {
        LimitChecks.checkPicture(options.limits(), surface.width(), surface.height());
        LimitChecks.checkAllocation(options.limits(), (long) surface.width() * surface.height() * 16);
        var picture = header.format().decode(data, surface.offset(), surface.width(), surface.height());
        var image = RasterOutput.finish(picture, ImageFormat.DDS, Orientation.NORMAL, null, null, options);
        if (!reduced || surface.level() == 0) {
            return image;
        }
        return new DecodedImage(image.picture(), ImageFormat.DDS, Orientation.NORMAL, header.width(),
                header.height(), image.iccProfile(), image.exif(), image.xmp(), image.colorProfile());
    }
}
