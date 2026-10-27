package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.ByteBuffer;

/**
 * Runs one image transcode.
 *
 * <p>The input is probed first, so the output format, the pass-through decision and the decoding
 * size are settled before any pixel is decoded. A still output is decoded at the smallest size
 * the decoder offers that still covers the planned output (JPEG scales on load), processed by
 * {@link PictureOps} and encoded, stepping quality and size down until it fits the byte budget. An
 * animated output streams the frames of an
 * {@link com.github.auties00.smalt.image.AnimationReader} through the same operations into an
 * {@link com.github.auties00.smalt.image.AnimationWriter}, one frame in memory at a time; under a
 * byte budget it is encoded into memory, again at a lower quality, then at a smaller size, then
 * with fewer frames, until it fits.
 *
 * <p>The color profile, EXIF block and XMP packet of the output come from the decoded input (for an
 * animation, from a decoding of its first frame) through {@link TranscodeMetadata}.
 *
 * <p>An instance runs once and is not thread-safe.
 *
 * @implNote This implementation stops an animation attempt as soon as its output passes the
 * budget, so that an attempt that cannot fit costs at most the decoding of the frames that
 * produced the budget's worth of output.
 */
final class ImageTranscode {
    /**
     * The quality steps tried below the starting quality, in quality points.
     */
    private static final int QUALITY_STEP = 10;

    /**
     * The lowest quality the byte budget steps down to.
     */
    private static final int MIN_QUALITY = 10;

    /**
     * The quality budget stepping starts from when the options name none.
     */
    private static final int DEFAULT_QUALITY = 75;

    /**
     * The factor each downscaling step of the byte budget applies to both dimensions.
     */
    private static final double SHRINK_FACTOR = 0.75;

    /**
     * The smallest long edge the byte budget shrinks a picture to.
     */
    private static final int MIN_EDGE = 16;

    /**
     * The size of the buffer a pass-through copies with.
     */
    private static final int COPY_BUFFER = 64 * 1024;

    private final MediaSource input;

    private final MediaSink output;

    private final ImageTranscodeOptions options;

    private final AnimationWriters writers;

    /**
     * Constructs a run.
     *
     * @param input the input image; not closed
     * @param output the sink the output is written to; not closed
     * @param options the job description
     * @param writers the animation encoders
     */
    ImageTranscode(MediaSource input, MediaSink output, ImageTranscodeOptions options, AnimationWriters writers) {
        this.input = input;
        this.output = output;
        this.options = options;
        this.writers = writers;
    }

    /**
     * Runs the transcode.
     *
     * @return what was written
     * @throws IllegalArgumentException if the crop region is not inside the picture
     * @throws MediaException.Unsupported if the input is not a decodable image or the output
     * format is not encodable
     * @throws MediaException.LimitExceeded if the image exceeds the limits or no encoding fits the
     * byte budget
     */
    ImageTranscodeResult run() {
        var info = Images.probe(input);
        var format = options.format().orElse(Images.isEncodable(info.format()) ? info.format() : ImageFormat.PNG);
        var animated = info.animated() && options.animation() == AnimationMode.KEEP && writers.supports(format);
        if (!animated && !Images.isEncodable(format)) {
            throw Artifacts.unsupported(Artifacts.nameOf(format), "encodable", Artifacts.imageEncoderOf(format));
        }
        var orientation = info.orientation();
        var swaps = orientation.swapsDimensions();
        var displayWidth = swaps ? info.height() : info.width();
        var displayHeight = swaps ? info.width() : info.height();
        var crop = options.crop().orElse(null);
        if (crop != null && !crop.fitsIn(displayWidth, displayHeight)) {
            throw new IllegalArgumentException("crop region " + crop + " is outside the " + displayWidth + "x" + displayHeight
                    + " picture");
        }
        var croppedWidth = crop == null ? displayWidth : crop.width();
        var croppedHeight = crop == null ? displayHeight : crop.height();
        var plan = options.sizePlan(croppedWidth, croppedHeight);
        var unchanged = (crop == null || (crop.width() == displayWidth && crop.height() == displayHeight))
                && plan.isIdentity(croppedWidth, croppedHeight);
        var size = input.size();
        if (options.passthrough() && format == info.format() && unchanged && !options.flatten()
                && options.encoderOptions().equals(ImageEncoderOptions.defaults()) && (!info.animated() || animated)
                && options.metadata() == MetadataMode.KEEP && options.color() == ColorMode.KEEP_PROFILE
                && size > 0 && size <= options.limits().maxBytes()
                && (options.maxBytes().isEmpty() || size <= options.maxBytes().getAsLong())) {
            copy(size);
            var frames = info.animated() ? Math.max(info.frameCount(), 2) : 1;
            return new ImageTranscodeResult(format, displayWidth, displayHeight, frames, true, size);
        }
        PictureOps.SizePlanner sizing = (width, height) -> plan;
        var background = !hasAlpha(format) || options.flatten() ? options.background() : -1;
        if (animated) {
            var metadata = animationMetadata();
            return options.maxBytes().isPresent() ? animateWithinBudget(format, crop, plan, background, metadata)
                    : animate(format, crop, sizing, background, metadata);
        }
        var minWidth = (int) Math.ceil((double) plan.scaledWidth() * displayWidth / croppedWidth);
        var minHeight = (int) Math.ceil((double) plan.scaledHeight() * displayHeight / croppedHeight);
        var decoderOptions = ImageDecoderOptions.ofMinimumSize(swaps ? minHeight : minWidth, swaps ? minWidth : minHeight)
                .toBuilder().limits(options.limits()).build();
        var decoded = decodeStill(decoderOptions, background >= 0);
        if (options.color() == ColorMode.CONVERT_TO_SRGB) {
            decoded = ImageOps.toSrgb(decoded);
        }
        var metadata = TranscodeMetadata.of(decoded, options.metadata(), options.color());
        var picture = decoded.picture();
        var decodedOrientation = decoded.orientation();
        var decodedSwaps = decodedOrientation.swapsDimensions();
        var decodedDisplayWidth = decodedSwaps ? picture.height() : picture.width();
        var decodedDisplayHeight = decodedSwaps ? picture.width() : picture.height();
        var scaledCrop = crop == null ? null : scale(crop, decodedDisplayWidth, decodedDisplayHeight, displayWidth, displayHeight);
        var ops = PictureOps.plan(picture.width(), picture.height(), decodedOrientation, true, scaledCrop, sizing,
                options.filter(), background, null, null);
        var processed = ops.apply(picture);
        var encoded = encodeWithinBudget(processed, format, metadata);
        output.write(encoded.data());
        return new ImageTranscodeResult(format, encoded.width(), encoded.height(), 1, false, encoded.data().length);
    }

    /**
     * Decodes the still picture, keeping CMYK inks that come with their profile as a channel picture, so that
     * the profile converts them or the output records it with them, rather than the decoder converting them to
     * RGB without color management.
     *
     * <p>Inks with an alpha channel that the output flattens are converted to sRGB through their profile here,
     * since a channel picture cannot be flattened.
     *
     * @param decoderOptions the options of the decoding
     * @param flattens whether the output is flattened on the background
     * @return the decoded image: a CMYK channel picture with its CMYK profile, the same converted to sRGB, or
     * what the decoder returns by default
     */
    private DecodedImage decodeStill(ImageDecoderOptions decoderOptions, boolean flattens) {
        var kept = Images.decode(input, decoderOptions.toBuilder().keepChannels(true).build());
        if (kept.picture() instanceof ChannelPicture channels) {
            if (channels.layout().colorModel() != ColorModel.CMYK || kept.colorProfile().isEmpty()) {
                return Images.decode(input, decoderOptions);
            }
            if (flattens && channels.hasAlpha()) {
                return ImageOps.toSrgb(kept);
            }
        }
        return kept;
    }

    /**
     * Holds an encoded still image.
     *
     * @param data the encoded bytes
     * @param width the width of the encoded picture
     * @param height the height of the encoded picture
     */
    private record Encoded(byte[] data, int width, int height) {
    }

    /**
     * Encodes a picture, stepping its quality and then its size down until the output fits the
     * byte budget; the quality is stepped only for formats that honor it.
     *
     * @param picture the picture to encode
     * @param format the output format
     * @param metadata the metadata the output records
     * @return the encoded image
     * @throws MediaException.LimitExceeded if no encoding fits the budget
     */
    private Encoded encodeWithinBudget(Picture picture, ImageFormat format, TranscodeMetadata metadata) {
        var encoderOptions = options.encoderOptions();
        var budget = options.maxBytes().orElse(Long.MAX_VALUE);
        var lossy = honorsQuality(format) && !encoderOptions.isLossless();
        var startQuality = encoderOptions.quality().orElse(DEFAULT_QUALITY);
        var current = picture;
        while (true) {
            var sized = metadata.apply(encoderOptions, format, current.width(), current.height());
            var encoded = encode(current, format, sized);
            if (encoded.length <= budget) {
                return new Encoded(encoded, current.width(), current.height());
            }
            if (lossy) {
                for (var quality = startQuality - QUALITY_STEP; quality >= MIN_QUALITY; quality -= QUALITY_STEP) {
                    encoded = encode(current, format, sized.toBuilder().quality(quality).build());
                    if (encoded.length <= budget) {
                        return new Encoded(encoded, current.width(), current.height());
                    }
                }
            }
            var longEdge = Math.max(current.width(), current.height());
            if (longEdge <= MIN_EDGE) {
                throw new MediaException.LimitExceeded("no " + format + " encoding of the picture fits within " + budget + " bytes");
            }
            var width = Math.max(1, (int) Math.round(current.width() * SHRINK_FACTOR));
            var height = Math.max(1, (int) Math.round(current.height() * SHRINK_FACTOR));
            current = ImageOps.resize(current, width, height, options.filter());
        }
    }

    /**
     * Encodes a picture, handing the encoder RGBA when it rejects the picture's planar layout.
     *
     * <p>A channel picture with a color profile is converted to sRGB through the profile for the retry,
     * which then records no profile.
     *
     * @implNote This implementation retries with {@link ImageOps#toRgba(Picture)} because some
     * encoders accept only the planar layout their format stores (lossy WebP takes limited-range
     * BT.601 4:2:0), while a decoded picture may be full-range JPEG YCbCr or 4:4:4.
     *
     * @param picture the picture to encode
     * @param format the output format
     * @param options the encoder settings
     * @return the encoded image
     */
    private static byte[] encode(Picture picture, ImageFormat format, ImageEncoderOptions options) {
        try {
            return Images.encode(picture, format, options);
        } catch (MediaException.Unsupported unsupported) {
            if (picture instanceof RgbaPicture || !Images.isEncodable(format)) {
                throw unsupported;
            }
            var profile = options.colorProfile();
            if (picture instanceof ChannelPicture && profile.isPresent()) {
                var converted = ImageOps.toRgba(EncodeMetadata.toSrgb(picture, profile.get()));
                return Images.encode(converted, format, options.toBuilder().noColorProfile().build());
            }
            return Images.encode(ImageOps.toRgba(picture), format, options);
        }
    }

    /**
     * Transcodes an animation frame by frame.
     *
     * @param format the output format
     * @param crop the displayed region kept, or {@code null} for the whole picture
     * @param sizing plans the scaling
     * @param background the flattening background as {@code 0xRRGGBB}, or {@code -1} to keep alpha
     * @param metadata the metadata of the output and the conversion of the frames
     * @return what was written
     */
    private ImageTranscodeResult animate(ImageFormat format, Region crop, PictureOps.SizePlanner sizing, int background,
                                         AnimationMetadata metadata) {
        var start = output.position();
        try (var reader = Images.openAnimation(input, ImageDecoderOptions.builder().limits(options.limits()).build())) {
            var ops = PictureOps.plan(reader.width(), reader.height(), Orientation.NORMAL, true, crop, sizing,
                    options.filter(), background, null, null);
            var frames = 0;
            var converter = metadata.converter();
            var encoderOptions = metadata.output().apply(options.encoderOptions(), format, ops.outputWidth(),
                    ops.outputHeight());
            try (var writer = writers.open(format, ops.outputWidth(), ops.outputHeight(), encoderOptions, output)) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    writer.write(converter.convert(ImageOps.toRgba(ops.apply(frame.picture()))), frame.durationMicros());
                    frames++;
                }
                writer.finish();
            }
            return new ImageTranscodeResult(format, ops.outputWidth(), ops.outputHeight(), frames, false,
                    output.position() - start);
        }
    }

    /**
     * Transcodes an animation into memory until an encoding fits the byte budget, then writes it.
     *
     * <p>Attempts go from the most to the least faithful: every frame at the planned size, at the
     * starting quality and then (for formats that honor it) at each lower quality step; then the
     * same at sizes a quarter smaller each time, down to a long edge of {@value #MIN_EDGE} pixels;
     * then the whole ladder again keeping every second frame, every fourth, and so on, each kept
     * frame shown for the time of the frames it replaces, down to a single frame.
     *
     * @param format the output format
     * @param crop the displayed region kept, or {@code null} for the whole picture
     * @param plan the planned scaling
     * @param background the flattening background as {@code 0xRRGGBB}, or {@code -1} to keep alpha
     * @param metadata the metadata of the output and the conversion of the frames
     * @return what was written
     * @throws MediaException.LimitExceeded if no attempt fits the budget
     */
    private ImageTranscodeResult animateWithinBudget(ImageFormat format, Region crop, SizePlan plan, int background,
                                                     AnimationMetadata metadata) {
        var encoderOptions = options.encoderOptions();
        var budget = options.maxBytes().getAsLong();
        var lossy = honorsQuality(format) && !encoderOptions.isLossless();
        var startQuality = encoderOptions.quality().orElse(DEFAULT_QUALITY);
        var frames = -1;
        for (var stride = 1; ; stride *= 2) {
            for (var factor = 1.0; ; factor *= SHRINK_FACTOR) {
                var scaled = shrink(plan, factor);
                var attempt = encodeAnimation(format, crop, scaled, background, encoderOptions, metadata, stride,
                        budget);
                for (var quality = startQuality - QUALITY_STEP; attempt.data() == null && lossy && quality >= MIN_QUALITY;
                     quality -= QUALITY_STEP) {
                    attempt = encodeAnimation(format, crop, scaled, background,
                            encoderOptions.toBuilder().quality(quality).build(), metadata, stride, budget);
                }
                if (attempt.data() != null) {
                    output.write(attempt.data());
                    return new ImageTranscodeResult(format, attempt.width(), attempt.height(), attempt.frames(), false,
                            attempt.data().length);
                }
                if (Math.max(attempt.width(), attempt.height()) <= MIN_EDGE) {
                    break;
                }
            }
            if (frames < 0) {
                frames = countFrames();
            }
            if (stride >= frames) {
                break;
            }
        }
        throw new MediaException.LimitExceeded("no " + format + " encoding of the animation fits within " + budget
                + " bytes");
    }

    /**
     * Holds one encoding of an animation.
     *
     * @param data the encoded bytes, or {@code null} when they passed the budget
     * @param width the canvas width
     * @param height the canvas height
     * @param frames the number of frames written
     */
    private record AnimationAttempt(byte[] data, int width, int height, int frames) {
    }

    /**
     * Encodes an animation into memory, stopping once the output passes the budget.
     *
     * @param format the output format
     * @param crop the displayed region kept, or {@code null} for the whole picture
     * @param plan the scaling
     * @param background the flattening background as {@code 0xRRGGBB}, or {@code -1} to keep alpha
     * @param encoderOptions the encoder settings
     * @param metadata the metadata of the output and the conversion of the frames
     * @param stride the distance between two kept frames, 1 to keep every frame
     * @param budget the largest output in bytes
     * @return the encoding
     */
    private AnimationAttempt encodeAnimation(ImageFormat format, Region crop, SizePlan plan, int background,
                                             ImageEncoderOptions encoderOptions, AnimationMetadata metadata, int stride,
                                             long budget) {
        try (var reader = Images.openAnimation(input, ImageDecoderOptions.builder().limits(options.limits()).build())) {
            var ops = PictureOps.plan(reader.width(), reader.height(), Orientation.NORMAL, true, crop, (width, height) -> plan,
                    options.filter(), background, null, null);
            var sink = new BudgetSink(budget);
            var read = 0;
            var written = 0;
            var sized = metadata.output().apply(encoderOptions, format, ops.outputWidth(), ops.outputHeight());
            var converter = metadata.converter();
            try (var writer = writers.open(format, ops.outputWidth(), ops.outputHeight(), sized, sink)) {
                RgbaPicture held = null;
                RgbaPicture spare = null;
                var heldDuration = 0L;
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    if (read++ % stride != 0) {
                        heldDuration += frame.durationMicros();
                        continue;
                    }
                    if (held != null) {
                        writer.write(held, heldDuration);
                        written++;
                    }
                    var processed = converter.convert(ImageOps.toRgba(ops.apply(frame.picture())));
                    // a kept frame outlives the next read, which may reuse the reader's picture; the
                    // writer copies what it keeps, so one copy is recycled for every kept frame (the
                    // converter's picture is overwritten only by the next conversion, after it is written)
                    if (stride > 1 && processed == frame.picture()) {
                        spare = (RgbaPicture) PictureCopy.copy(processed, spare);
                        held = spare;
                    } else {
                        held = processed;
                    }
                    heldDuration = frame.durationMicros();
                    if (stride == 1) {
                        writer.write(held, heldDuration);
                        written++;
                        held = null;
                    }
                }
                if (held != null) {
                    writer.write(held, heldDuration);
                    written++;
                }
                writer.finish();
            } catch (RuntimeException failure) {
                if (sink.exceeded) {
                    return new AnimationAttempt(null, ops.outputWidth(), ops.outputHeight(), written);
                }
                throw failure;
            }
            return new AnimationAttempt(sink.memory.toByteArray(), ops.outputWidth(), ops.outputHeight(), written);
        }
    }

    /**
     * Holds what an animated transcode records and how it converts the frames.
     *
     * @param output the metadata the output records
     * @param transform the conversion of every frame to sRGB, or {@code null} to keep the frames as decoded
     */
    private record AnimationMetadata(TranscodeMetadata output, ColorTransform transform) {
        /**
         * {@return a converter of the frames of one encoding, as the job asks}
         */
        EncodeMetadata.FrameConverter converter() {
            return new EncodeMetadata.FrameConverter(transform);
        }
    }

    /**
     * Derives the metadata of an animated transcode from a decoding of the input's first frame, which
     * carries the color profile, EXIF block and XMP packet of the file.
     *
     * @return the metadata, with the frame conversion of {@link ColorMode#CONVERT_TO_SRGB}
     */
    private AnimationMetadata animationMetadata() {
        var first = Images.decode(input, ImageDecoderOptions.builder().limits(options.limits()).build());
        var kept = TranscodeMetadata.of(first, options.metadata(), ColorMode.KEEP_PROFILE);
        if (options.color() != ColorMode.CONVERT_TO_SRGB) {
            return new AnimationMetadata(kept, null);
        }
        ColorTransform transform = null;
        if (kept.profile() != null) {
            try {
                transform = EncodeMetadata.frameTransform(kept.profile());
            } catch (IllegalArgumentException | MediaException.Unsupported | MediaException.Malformed exception) {
                // a profile that cannot convert the frames leaves them as decoded, as ImageOps.toSrgb does
            }
        }
        return new AnimationMetadata(TranscodeMetadata.of(first, options.metadata(), ColorMode.CONVERT_TO_SRGB),
                transform);
    }

    /**
     * {@return the number of frames of the input animation}
     */
    private int countFrames() {
        try (var reader = Images.openAnimation(input, ImageDecoderOptions.builder().limits(options.limits()).build())) {
            if (reader.frameCount() > 0) {
                return reader.frameCount();
            }
            var count = 0;
            while (reader.read() != null) {
                count++;
            }
            return count;
        }
    }

    /**
     * Scales a size plan down by a factor, keeping a centered crop centered.
     *
     * @param plan the planned scaling of the full-size output
     * @param factor the factor, at most 1
     * @return the scaled plan, every dimension at least 1
     */
    private static SizePlan shrink(SizePlan plan, double factor) {
        if (factor == 1.0) {
            return plan;
        }
        var outputWidth = Math.max(1, (int) Math.round(plan.outputWidth() * factor));
        var outputHeight = Math.max(1, (int) Math.round(plan.outputHeight() * factor));
        var scaledWidth = Math.max(outputWidth, (int) Math.round(plan.scaledWidth() * factor));
        var scaledHeight = Math.max(outputHeight, (int) Math.round(plan.scaledHeight() * factor));
        return new SizePlan(scaledWidth, scaledHeight, (scaledWidth - outputWidth) / 2, (scaledHeight - outputHeight) / 2,
                outputWidth, outputHeight);
    }

    /**
     * Collects an encoding in memory and fails the write that takes it past a byte budget.
     */
    private static final class BudgetSink implements MediaSink {
        /**
         * Signals that an encoding passed the budget; it carries no stack trace.
         */
        private static final class OverBudget extends RuntimeException {
            private OverBudget() {
                super("the encoding passed the byte budget", null, false, false);
            }
        }

        private final ByteArrayMediaSink memory = MediaSink.memory();

        /**
         * The largest size in bytes.
         */
        private final long budget;

        /**
         * Whether a write passed the budget.
         */
        private boolean exceeded;

        private BudgetSink(long budget) {
            this.budget = budget;
        }

        @Override
        public long position() {
            return memory.position();
        }

        @Override
        public void position(long position) {
            memory.position(position);
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public void write(ByteBuffer data) {
            if (exceeded || memory.position() + data.remaining() > budget) {
                exceeded = true;
                throw new OverBudget();
            }
            memory.write(data);
        }

        @Override
        public void close() {
        }
    }

    /**
     * Copies the input to the output unchanged.
     *
     * @param size the input size in bytes
     */
    private void copy(long size) {
        var buffer = new byte[(int) Math.min(COPY_BUFFER, size)];
        var position = 0L;
        while (position < size) {
            var length = (int) Math.min(buffer.length, size - position);
            input.readFully(position, buffer, 0, length);
            output.write(buffer, 0, length);
            position += length;
        }
    }

    /**
     * {@return whether the encoder of a format keeps transparency: PNG, WebP, GIF, AVIF, TIFF, ICO,
     * ANI and JPEG 2000}
     *
     * @param format an output image format
     */
    private static boolean hasAlpha(ImageFormat format) {
        return switch (format) {
            case PNG, WEBP, GIF, AVIF, TIFF, ICO, ANI, JP2 -> true;
            default -> false;
        };
    }

    /**
     * {@return whether the encoder of a format trades size for quality, so that a lower quality
     * can bring an encoding under the byte budget: JPEG, WebP, AVIF, GIF and JPEG 2000}
     *
     * @param format an output image format
     */
    private static boolean honorsQuality(ImageFormat format) {
        return switch (format) {
            case JPEG, WEBP, AVIF, GIF, JP2 -> true;
            default -> false;
        };
    }

    /**
     * Scales a displayed region of the full-size picture to a picture decoded at a reduced size.
     *
     * @param crop the region in full-size displayed coordinates
     * @param width the displayed width of the decoded picture
     * @param height the displayed height of the decoded picture
     * @param fullWidth the displayed width of the full-size picture
     * @param fullHeight the displayed height of the full-size picture
     * @return the region in the decoded picture's displayed coordinates
     */
    private static Region scale(Region crop, int width, int height, int fullWidth, int fullHeight) {
        if (width == fullWidth && height == fullHeight) {
            return crop;
        }
        var x = (int) Math.floor((double) crop.x() * width / fullWidth);
        var y = (int) Math.floor((double) crop.y() * height / fullHeight);
        var right = Math.min(width, (int) Math.ceil((double) (crop.x() + crop.width()) * width / fullWidth));
        var bottom = Math.min(height, (int) Math.ceil((double) (crop.y() + crop.height()) * height / fullHeight));
        return new Region(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }
}
