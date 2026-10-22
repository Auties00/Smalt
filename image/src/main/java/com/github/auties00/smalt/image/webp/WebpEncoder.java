package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaEncoder;
import com.github.auties00.smalt.image.webp.internal.anim.FrameEncoder;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.image.webp.internal.riff.FourCc;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.image.webp.internal.riff.WebpWriter;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Encodes still WebP images.
 *
 * <p>Lossless mode codes the RGBA pixels as VP8L: every pixel decodes to its exact value, except that
 * the color of a fully transparent pixel is kept only with {@link WebpEncoderOptions#exact()} and
 * that a {@linkplain WebpEncoderOptions#nearLossless() near-lossless level} below 100 bounds the
 * change of every channel instead. Lossy mode converts RGBA to limited-range BT.601 4:2:0
 * (averaging chroma alpha-weighted in a gamma-linear domain, or with the
 * {@linkplain WebpEncoderOptions#sharpYuv() sharp conversion} or
 * {@linkplain WebpEncoderOptions#dithering() dithering} the options select), codes it as VP8, and
 * codes any transparency as a separate alpha plane. The simple container (a single
 * {@code VP8 } or {@code VP8L} chunk) is written unless the options ask for the extended one, a
 * lossy image has transparency, which needs {@code VP8X} and {@code ALPH}, or the options embed a
 * color profile, which needs {@code VP8X} and {@code ICCP}.
 *
 * <p>With a target size, lossy encoding first tries the requested quality and, when the file is
 * too large, bisects the quality down to 0 and returns the best-quality result that fits, color
 * profile included; when not even quality 0 fits, it returns the smallest file it produced and
 * the caller decides. Lossless encoding ignores the target size. With a
 * {@linkplain WebpEncoderOptions#targetPsnr() target PSNR} the quality is searched inside the VP8
 * encoder, over its entropy-analysis passes, instead.
 *
 * <p>Without a target size and with one token partition, files are byte-identical to those of
 * libwebp 1.6.0's {@code cwebp} for the same pixels and equivalent settings.
 *
 * <p>Lossy encoding runs independent work in parallel on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes: the alpha plane beside
 * the color, the alpha filter trials, and the qualities the size search tries. The file does not
 * depend on the thread count. This class is thread-safe.
 *
 * @implNote This implementation cleans up the color of fully transparent pixels unless the
 * options ask for exact coding, and computes the alpha plane and the color conversion once,
 * since they do not depend on the quality. The size search is an outer bisection over whole
 * encodes rather than a search inside one encode.
 */
public final class WebpEncoder {
    /**
     * The number of bisection steps of the size search.
     */
    private static final int SIZE_SEARCH_STEPS = 8;

    /**
     * Prevents instantiation.
     */
    private WebpEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a WebP file with the {@link WebpEncoderOptions#defaults()}.
     *
     * @param picture the picture, at most 16383 pixels per side
     * @return the WebP file
     * @throws MediaException.Unsupported if the image is lossy and no VP8 encoder is available
     * @throws MediaException.LimitExceeded if a side exceeds 16383 pixels, encoding runs out of
     * memory, or the file would exceed the RIFF size limit
     * @see #encode(Picture, WebpEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, WebpEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as a WebP file and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, WebpEncoderOptions)} returns.
     *
     * @param picture the picture, at most 16383 pixels per side
     * @param options the encoder settings
     * @param sink the output, written from its position and not closed
     * @throws MediaException.Unsupported if the image is lossy and no VP8 encoder is available
     * @throws MediaException.LimitExceeded if a side exceeds 16383 pixels, encoding runs out of
     * memory, or the file would exceed the RIFF size limit
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, WebpEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture as a WebP file.
     *
     * <p>An {@link RgbaPicture} contributes alpha only when {@link RgbaPicture#hasAlpha()} is set;
     * otherwise it is encoded as opaque. A limited-range BT.601 4:2:0 {@link YuvPicture}, the
     * representation lossy WebP decodes to, is coded as it is, its alpha plane kept; any other
     * {@link YuvPicture} is first converted with {@link ImageOps#toRgba(Picture)}. A
     * {@link Rgba16Picture} or {@link Yuv16Picture} is first reduced to 8 bits with
     * {@link ImageOps#toEightBit(Picture)}, except that a {@link Yuv16Picture} in the YCgCo-Re or
     * YCgCo-Ro matrix, whose 8-bit samples would keep only 6 or 7 bits of R'G'B', is converted with
     * {@link ImageOps#toRgba(Picture)}; a {@link ChannelPicture} is first converted with
     * {@link ImageOps#toRgba(Picture)}.
     *
     * @param picture the picture, at most 16383 pixels per side
     * @param options the encoder settings
     * @return the WebP file
     * @throws MediaException.Unsupported if the image is lossy and no VP8 encoder is available
     * @throws MediaException.LimitExceeded if a side exceeds 16383 pixels, encoding runs out of
     * memory, or the file would exceed the RIFF size limit
     */
    public static byte[] encode(Picture picture, WebpEncoderOptions options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(options, "options");
            FrameEncoder.checkDimensions(picture.width(), picture.height());
            var icc = options.colorProfile().map(ColorProfile::toByteArray).orElse(null);
            return Guards.encoding("WebP image",
                    () -> options.isLossless() ? encodeLossless(picture, options, icc) : encodeLossy(picture, options, icc));
        }
    }

    /**
     * Encodes a picture losslessly.
     *
     * @param picture the picture to encode
     * @param options the settings
     * @return the file
     */
    private static byte[] encodeLossless(Picture picture, WebpEncoderOptions options, byte[] icc) {
        var width = picture.width();
        var height = picture.height();
        int[] argb;
        switch (picture) {
            case RgbaPicture rgba -> argb = FrameEncoder.toArgb(rgba.pixels(), 0, rgba.stride(), width, height,
                    rgba.hasAlpha(), options.exact());
            case YuvPicture yuv when FrameEncoder.isLossyLayout(yuv) -> {
                var rgba = new byte[width * height * 4];
                YuvConverter.toRgba(yuv, rgba, 0, width * 4);
                argb = FrameEncoder.toArgb(rgba, 0, width * 4, width, height, yuv.hasAlpha(), options.exact());
            }
            case YuvPicture yuv -> {
                return encodeLossless(ImageOps.toRgba(yuv), options, icc);
            }
            case Rgba16Picture high -> {
                return encodeLossless(ImageOps.toEightBit(high), options, icc);
            }
            case Yuv16Picture high when YcgcoR.applies(high.color().matrix()) -> {
                return encodeLossless(ImageOps.toRgba(high), options, icc);
            }
            case Yuv16Picture high -> {
                return encodeLossless(ImageOps.toEightBit(high), options, icc);
            }
            case ChannelPicture channels -> {
                return encodeLossless(ImageOps.toRgba(channels), options, icc);
            }
        }
        var image = FrameEncoder.encodeLossless(argb, width, height, options.quality(), options.method(), options.exact(),
                options.nearLossless());
        return assemble(image, width, height, options.extendedFormat(), icc);
    }

    /**
     * Encodes a picture lossily, searching the quality when a target size is set.
     *
     * @implNote This implementation codes the alpha plane and the color at the requested quality
     * concurrently. The size search encodes the next {@link #speculationDepth()} levels of the
     * bisection tree at once and follows the branch the sizes select, so it keeps exactly the
     * encodes of the step-by-step bisection in fewer rounds; the depth follows the threads free at
     * the start of each round.
     *
     * @param picture the picture to encode
     * @param options the settings
     * @return the file
     */
    private static byte[] encodeLossy(Picture picture, WebpEncoderOptions options, byte[] icc) {
        var width = picture.width();
        var height = picture.height();
        var limits = MediaLimits.DEFAULT;
        var yuv = switch (picture) {
            case RgbaPicture rgba -> prepareLossyRgba(rgba, options, limits);
            case YuvPicture source when FrameEncoder.isLossyLayout(source) -> prepareLossyYuv(source, options);
            case YuvPicture source -> prepareLossyRgba(ImageOps.toRgba(source), options, limits);
            case Rgba16Picture high -> prepareLossyRgba(ImageOps.toEightBit(high), options, limits);
            case ChannelPicture channels -> prepareLossyRgba(ImageOps.toRgba(channels), options, limits);
            case Yuv16Picture high when YcgcoR.applies(high.color().matrix()) ->
                    prepareLossyRgba(ImageOps.toRgba(high), options, limits);
            case Yuv16Picture high -> {
                var eight = ImageOps.toEightBit(high);
                yield FrameEncoder.isLossyLayout(eight) ? prepareLossyYuv(eight, options)
                        : prepareLossyRgba(ImageOps.toRgba(eight), options, limits);
            }
        };
        var first = ParallelBands.<byte[]>invokeAll(List.of(
                () -> FrameEncoder.encodeAlpha(yuv, options.alphaQuality(), alphaFilterMode(options.alphaFilter()),
                        options.method()),
                () -> FrameEncoder.encodeVp8(yuv, vp8Settings(options, options.quality()))));
        var alph = first.get(0);
        var file = assemble(new FrameEncoder.EncodedImage(first.get(1), false, alph, alph != null), width, height,
                options.extendedFormat(), icc);
        var target = options.targetSize().orElse(0);
        if (target <= 0 || file.length <= target) {
            return file;
        }
        var smallest = file;
        byte[] bestFit = null;
        var low = 0f;
        float high = options.quality();
        for (var step = 0; step < SIZE_SEARCH_STEPS; ) {
            var levels = Math.min(speculationDepth(), SIZE_SEARCH_STEPS - step);
            var nodes = (1 << levels) - 1;
            var lows = new float[nodes + 1];
            var highs = new float[nodes + 1];
            var mids = new float[nodes + 1];
            var tasks = new ArrayList<Callable<byte[]>>(nodes);
            lows[1] = low;
            highs[1] = high;
            for (var node = 1; node <= nodes; node++) {
                var mid = (lows[node] + highs[node]) / 2;
                mids[node] = mid;
                if (2 * node <= nodes) {
                    lows[2 * node] = mid;
                    highs[2 * node] = highs[node];
                    lows[2 * node + 1] = lows[node];
                    highs[2 * node + 1] = mid;
                }
                tasks.add(() -> lossyFile(yuv, alph, mid, options, icc));
            }
            var attempts = ParallelBands.invokeAll(tasks);
            var node = 1;
            for (var level = 0; level < levels; level++, step++) {
                var attempt = attempts.get(node - 1);
                if (attempt.length < smallest.length) {
                    smallest = attempt;
                }
                if (attempt.length <= target) {
                    bestFit = attempt;
                    low = mids[node];
                    node = 2 * node;
                } else {
                    high = mids[node];
                    node = 2 * node + 1;
                }
            }
        }
        if (bestFit == null) {
            var floor = lossyFile(yuv, alph, 0, options, icc);
            if (floor.length <= target) {
                return floor;
            }
            return floor.length < smallest.length ? floor : smallest;
        }
        return bestFit;
    }

    /**
     * Returns the number of bisection steps the size search evaluates in its next round: the
     * deepest complete bisection tree whose encodes can all run at once on the calling thread and
     * the helpers free now, at most 3.
     *
     * @return the depth, 1 when no helper is free
     */
    private static int speculationDepth() {
        var depth = 31 - Integer.numberOfLeadingZeros(ParallelBands.available() + 2);
        return Math.clamp(depth, 1, 3);
    }

    /**
     * Encodes a prepared lossy picture at one quality and assembles the file.
     *
     * @param yuv the prepared picture
     * @param alph the alpha payload, or {@code null}
     * @param quality the VP8 quality, from 0 to 100
     * @param options the settings
     * @return the file
     */
    private static byte[] lossyFile(YuvPicture yuv, byte[] alph, float quality, WebpEncoderOptions options,
                                    byte[] icc) {
        var vp8 = FrameEncoder.encodeVp8(yuv, vp8Settings(options, quality));
        var image = new FrameEncoder.EncodedImage(vp8, false, alph, alph != null);
        return assemble(image, yuv.width(), yuv.height(), options.extendedFormat(), icc);
    }

    /**
     * Returns the VP8 coding settings of the options at a quality.
     *
     * @param options the settings
     * @param quality the VP8 quality, from 0 to 100
     * @return the VP8 settings
     */
    private static Vp8KeyFrameSettings vp8Settings(WebpEncoderOptions options, float quality) {
        var target = options.targetPsnr();
        var passes = options.passes().orElse(target.isPresent() ? 6 : 1);
        return new Vp8KeyFrameSettings(quality, options.method(), options.segments(), options.filterStrength(),
                options.filterSharpness(), options.tokenPartitions(), options.snsStrength(), options.simpleFilter(),
                options.autoFilter(), passes, (float) target.orElse(0));
    }

    /**
     * Returns the dithering amplitude of the RGB to Y'CbCr conversion: from 1 at quality 0 down to
     * 0.5 at quality 100 when the options ask for dithering, otherwise 0.
     *
     * @param options the settings
     * @return the amplitude, from 0 to 1
     */
    private static float ditheringAmplitude(WebpEncoderOptions options) {
        if (!options.dithering()) {
            return 0;
        }
        var x = options.quality() / 100f;
        var x2 = x * x;
        return 1.0f + (0.5f - 1.0f) * x2 * x2;
    }

    /**
     * Prepares a packed picture for lossy coding: converts it to 4:2:0 and, unless the options
     * ask for exact coding, flattens the color of its transparent area.
     *
     * @param rgba the picture
     * @param options the settings
     * @param limits the bounds of the conversion's allocations
     * @return the prepared picture
     */
    private static YuvPicture prepareLossyRgba(RgbaPicture rgba, WebpEncoderOptions options, MediaLimits limits) {
        return FrameEncoder.prepareLossy(rgba.pixels(), rgba.stride(), rgba.width(), rgba.height(), rgba.hasAlpha(),
                options.exact(), options.sharpYuv(), ditheringAmplitude(options), limits);
    }

    /**
     * Prepares a planar picture for lossy coding: checks its layout, copies its planes and, unless
     * the options ask for exact coding, flattens the color of its transparent area.
     *
     * @param source the picture
     * @param options the settings
     * @return the prepared copy
     * @throws MediaException.Unsupported if the picture is not limited-range BT.601 4:2:0
     */
    private static YuvPicture prepareLossyYuv(YuvPicture source, WebpEncoderOptions options) {
        var copy = (YuvPicture) PictureCopy.copy(source, null);
        if (!options.exact()) {
            YuvConverter.cleanupTransparentArea(copy);
        }
        return copy;
    }

    /**
     * Returns the alpha filter mode of the internal alpha encoder for a filter selection.
     *
     * @param filter the selection
     * @return {@link AlphaEncoder#FILTER_NONE}, {@link AlphaEncoder#FILTER_FAST} or
     * {@link AlphaEncoder#FILTER_BEST}
     */
    private static int alphaFilterMode(WebpEncoderOptions.AlphaFilter filter) {
        return switch (filter) {
            case NONE -> AlphaEncoder.FILTER_NONE;
            case FAST -> AlphaEncoder.FILTER_FAST;
            case BEST -> AlphaEncoder.FILTER_BEST;
        };
    }

    /**
     * Wraps an encoded image in the simple or extended container.
     *
     * @param image the encoded image
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param extended whether the extended container is requested
     * @return the file
     */
    private static byte[] assemble(FrameEncoder.EncodedImage image, int width, int height, boolean extended,
                                   byte[] icc) {
        var chunks = new ArrayList<byte[]>();
        if (extended || image.alph() != null || icc != null) {
            var flags = (image.hasAlpha() ? WebpParser.FLAG_ALPHA : 0) | (icc != null ? WebpParser.FLAG_ICCP : 0);
            chunks.add(WebpWriter.vp8x(flags, width, height));
        }
        if (icc != null) {
            chunks.add(WebpWriter.chunk(FourCc.ICCP, icc));
        }
        chunks.addAll(image.chunks());
        return WebpWriter.riff(chunks);
    }
}
