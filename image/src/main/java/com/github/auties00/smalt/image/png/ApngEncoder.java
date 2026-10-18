package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.zlib.Zlib;
import com.github.auties00.smalt.image.internal.zlib.ZlibDeflater;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.provider.image.ApngAnimationWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;

/**
 * Encodes an animated PNG (APNG, PNG 3rd edition 11.3.6) frame by frame.
 *
 * <p>Frames are full-canvas pictures added in display order with their delays, given as exact
 * fractions of a second as {@code fcTL} stores them. Each frame is coded as the smallest
 * rectangle covering what changed since the canvas a decoder shows before it. The encoder chooses
 * for each frame:
 * <ul>
 *   <li>its {@code dispose_op}: {@code APNG_DISPOSE_OP_NONE}, or
 *       {@code APNG_DISPOSE_OP_BACKGROUND} when clearing the frame's rectangle makes the next
 *       frame's rectangle cheaper to code; the choice is made when the next frame arrives;</li>
 *   <li>its {@code blend_op}: {@code APNG_BLEND_OP_SOURCE}, or {@code APNG_BLEND_OP_OVER} with the
 *       unchanged pixels of the rectangle made fully transparent, whichever compresses smaller,
 *       when compositing over the canvas gives the frame's pixels exactly;</li>
 * </ul>
 * and for the whole file one color type, since {@code IHDR} fixes it for every frame: indexed
 * color (with {@code tRNS} for translucent entries) when all frames together have at most 256
 * distinct colors, truecolor when every pixel is opaque and truecolor codes smaller than
 * truecolor with alpha on a sample of the frames, and truecolor with alpha otherwise.
 * Scanlines are filtered as {@link ApngEncoderOptions#filter()} selects, as {@link PngEncoder}
 * filters them, and compressed at {@link ApngEncoderOptions#compressionLevel()} by the deflate
 * implementation {@code PngEncoder} uses, as one stream per frame, so the bytes of the image data
 * can differ from those {@code PngEncoder} writes for the same picture.
 *
 * <p>The default image ({@code IDAT}) is the first frame, unless {@link #setFallbackImage} gives
 * one that decoders without APNG support show instead and APNG decoders skip. A frame identical
 * to the previous one is not coded: its delay is added to the previous frame's when the sum is
 * still a fraction of 16-bit terms. Fully transparent pixels are coded as transparent black, so
 * their color is not preserved; every other pixel round-trips exactly. An animation of a single
 * frame without a fallback image is written as a plain PNG.
 *
 * <p>An encoder is single-use: add every frame, then call {@link #finish()} once. The color type
 * is known only after the last frame, so the changed rectangles are kept in memory until then;
 * nothing reaches a sink before {@link #finish(MediaSink)}. Instances are not thread-safe.
 *
 * @implNote This implementation keeps the changed rectangles compressed at the platform zlib's
 * fastest level. The blend operations, and truecolor against truecolor with alpha (on up to eight
 * evenly spaced frames), are compared by their size at level 1; only the chosen form of each frame
 * is compressed at the configured level.
 */
public final class ApngEncoder {
    /**
     * The largest {@code IDAT} or {@code fdAT} payload written.
     */
    private static final int CHUNK_SIZE = 1 << 18;

    /**
     * The largest delay term {@code fcTL} stores.
     */
    private static final int MAX_DELAY_TERM = 0xFFFF;

    /**
     * The number of evenly spaced frames on which truecolor and truecolor with alpha are
     * compared.
     */
    private static final int TYPE_SAMPLES = 8;

    /**
     * The compression level at which the color types and blend operations are compared.
     */
    private static final int TRIAL_LEVEL = 1;

    /**
     * The indexed color type of {@code IHDR}.
     */
    private static final int INDEXED = 3;

    /**
     * The truecolor type of {@code IHDR}.
     */
    private static final int TRUECOLOR = 2;

    /**
     * The truecolor with alpha type of {@code IHDR}.
     */
    private static final int TRUECOLOR_ALPHA = 6;

    /**
     * The operation the calls of this encoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The encoder settings.
     */
    private final ApngEncoderOptions options;

    /**
     * The frames added so far, coalesced.
     */
    private final List<Frame> frames = new ArrayList<>();

    /**
     * The distinct colors of every coded pixel, while there are at most 256 of them.
     */
    private final PngPalette colors = new PngPalette();

    /**
     * The fallback image, or {@code null}.
     */
    private Frame fallback;

    /**
     * The last added frame, as packed colors with transparent pixels normalized to 0.
     */
    private int[] current;

    /**
     * The scratch canvas the incoming frame is read into.
     */
    private int[] incoming;

    /**
     * The scratch canvas the cleared canvas is built in.
     */
    private int[] scratch;

    /**
     * Whether some coded pixel is not opaque.
     */
    private boolean translucent;

    /**
     * Whether {@link #finish()} was called.
     */
    private boolean finished;

    /**
     * The writer that rounds the durations of {@link #addFrame(Picture, Duration)}, created on
     * first use.
     */
    private ApngAnimationWriter durationWriter;

    /**
     * Creates an {@link AnimationWriter} that encodes an animation into a sink.
     *
     * <p>Frame durations are rounded as {@link #addFrame(Picture, Duration)} describes. Nothing
     * reaches the sink before {@link AnimationWriter#finish()}, which writes the whole file;
     * closing the writer without finishing writes nothing.
     *
     * @param width the canvas width, positive
     * @param height the canvas height, positive
     * @param options the encoder settings
     * @param sink the output, written from its position and not closed
     * @return the writer
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if the canvas has more than {@code 2^28} pixels
     */
    public static AnimationWriter openAnimationWriter(int width, int height, ApngEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        return new ApngAnimationWriter(new ApngEncoder(width, height, options), width, height, sink);
    }

    /**
     * Constructs an encoder with the {@link ApngEncoderOptions#defaults()}.
     *
     * @param canvasWidth the canvas width, positive
     * @param canvasHeight the canvas height, positive
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if the canvas has more than {@code 2^28} pixels
     */
    public ApngEncoder(int canvasWidth, int canvasHeight) {
        this(canvasWidth, canvasHeight, ApngEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param canvasWidth the canvas width, positive
     * @param canvasHeight the canvas height, positive
     * @param options the encoder settings
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if the canvas has more than {@code 2^28} pixels
     */
    public ApngEncoder(int canvasWidth, int canvasHeight, ApngEncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + canvasWidth + "x" + canvasHeight);
        }
        if ((long) canvasWidth * canvasHeight > 1 << 28) {
            throw new MediaException.LimitExceeded("an APNG canvas of " + canvasWidth + "x" + canvasHeight
                    + " exceeds 2^28 pixels");
        }
        this.width = canvasWidth;
        this.height = canvasHeight;
        this.options = options;
    }

    /**
     * Sets the image that decoders without APNG support show; APNG decoders skip it.
     *
     * <p>Without a fallback image, the first frame is the image such decoders show.
     *
     * @param picture the image, of the canvas size; its alpha channel is used only when
     * {@link RgbaPicture#hasAlpha()} is set
     * @throws IllegalArgumentException if the picture size differs from the canvas size
     * @throws IllegalStateException if a frame or a fallback image was already added, or
     * the encoder is finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void setFallbackImage(RgbaPicture picture) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(picture, "picture");
            checkOpen();
            if (fallback != null || !frames.isEmpty()) {
                throw new IllegalStateException("the fallback image must be set once, before the first frame");
            }
            checkSize(picture);
            fallback = Guards.encoding("APNG image", () -> {
                var pixels = new int[width * height];
                read(picture, pixels);
                return store(pixels, new int[pixels.length], 0, 0, width, height, 0, 1, false);
            });
        }
    }

    /**
     * Adds a frame.
     *
     * <p>The frame is displayed for {@code delayNumerator / delayDenominator} seconds.
     *
     * @param frame the frame, of the canvas size; its alpha channel is used only when
     * {@link RgbaPicture#hasAlpha()} is set
     * @param delayNumerator the numerator of the delay, from 0 to 65535
     * @param delayDenominator the denominator of the delay, from 1 to 65535
     * @throws IllegalArgumentException if the frame size differs from the canvas size or a
     * delay term is out of range
     * @throws IllegalStateException if {@link #finish()} was called
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(RgbaPicture frame, int delayNumerator, int delayDenominator) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(frame, "frame");
            checkOpen();
            checkSize(frame);
            if (delayNumerator < 0 || delayNumerator > MAX_DELAY_TERM) {
                throw new IllegalArgumentException("delay numerator " + delayNumerator + " is outside 0..65535");
            }
            if (delayDenominator < 1 || delayDenominator > MAX_DELAY_TERM) {
                throw new IllegalArgumentException("delay denominator " + delayDenominator + " is outside 1..65535");
            }
            Guards.encoding("APNG image", () -> {
                add(frame, delayNumerator, delayDenominator);
                return null;
            });
        }
    }

    /**
     * Adds a frame displayed for a duration, converting it to packed RGBA when it is not.
     *
     * <p>APNG stores delays as fractions of a second whose terms are at most 65535. Durations,
     * truncated to the microsecond, are stored exactly while their reduced fraction fits; from the
     * first one that does not, each frame added through this method gets the fraction that brings
     * the stored end time of those frames closest to the true one, so the error never accumulates
     * over the animation.
     *
     * @param frame the frame, of the canvas size
     * @param duration the display time, not negative
     * @throws IllegalArgumentException if the frame size differs from the canvas size, the
     * duration is negative, or the delay exceeds 65535 seconds
     * @throws IllegalStateException if {@link #finish()} was called
     * @throws MediaException.Unsupported if the frame's color matrix cannot be converted to RGB
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(Picture frame, Duration duration) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(duration, "duration");
        if (durationWriter == null) {
            durationWriter = new ApngAnimationWriter(this, width, height, null);
        }
        durationWriter.write(ImageOps.toRgba(frame), toMicros(duration));
    }

    /**
     * Finishes the animation and writes the datastream to a sink.
     *
     * <p>The bytes written are those {@link #finish()} returns.
     *
     * @param sink the output, written from its position and not closed
     * @throws IllegalStateException if no frame was added or the encoder is already finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory or the datastream would
     * exceed the largest Java array
     * @throws MediaException.Io if writing the sink fails
     */
    public void finish(MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(finish());
    }

    /**
     * Finishes the animation and returns the datastream.
     *
     * @return the APNG datastream, or a plain PNG datastream for a single frame without a
     * fallback image
     * @throws IllegalStateException if no frame was added or the encoder is already
     * finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory or the datastream
     * would exceed the largest Java array
     */
    public byte[] finish() {
        try (var _ = ParallelBands.operation(operation)) {
            checkOpen();
            if (frames.isEmpty()) {
                throw new IllegalStateException("no frame was added");
            }
            finished = true;
            try {
                return Guards.encoding("APNG image", this::write);
            } finally {
                frames.clear();
                fallback = null;
            }
        }
    }

    /**
     * Throws when the encoder is finished.
     *
     * @throws IllegalStateException if {@link #finish()} was called
     */
    private void checkOpen() {
        if (finished) {
            throw new IllegalStateException("the animation is finished");
        }
    }

    /**
     * Throws when a picture is not of the canvas size.
     *
     * @param picture the picture whose size is checked
     * @throws IllegalArgumentException if the sizes differ
     */
    private void checkSize(RgbaPicture picture) {
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("frame " + picture.width() + "x" + picture.height()
                    + " does not match the canvas " + width + "x" + height);
        }
    }

    /**
     * Reads a picture as packed colors, normalizing fully transparent pixels to 0.
     *
     * @param picture the picture to copy
     * @param target the canvas
     */
    private void read(RgbaPicture picture, int[] target) {
        var pixels = picture.pixels();
        var stride = picture.stride();
        var force = picture.hasAlpha() ? 0 : 0xFF00_0000;
        for (var y = 0; y < height; y++) {
            var source = y * stride;
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var color = ByteArrays.i32le(pixels, source + x * 4) | force;
                target[row + x] = color >>> 24 == 0 ? 0 : color;
            }
        }
    }

    /**
     * Adds a validated frame: merges it into the previous one when identical, otherwise decides
     * the previous frame's disposal and stores the frame's changed rectangle.
     *
     * @param picture the frame
     * @param numerator the delay numerator
     * @param denominator the delay denominator
     */
    private void add(RgbaPicture picture, int numerator, int denominator) {
        if (current == null) {
            current = new int[width * height];
            incoming = new int[width * height];
            scratch = new int[width * height];
            read(picture, current);
            frames.add(store(current, scratch, 0, 0, width, height, numerator, denominator, false));
            return;
        }
        read(picture, incoming);
        var last = frames.getLast();
        if (Arrays.equals(incoming, current)) {
            var mergedNumerator = (long) last.numerator * denominator + (long) numerator * last.denominator;
            var mergedDenominator = (long) last.denominator * denominator;
            var divisor = gcd(mergedNumerator, mergedDenominator);
            if (mergedNumerator / divisor <= MAX_DELAY_TERM && mergedDenominator / divisor <= MAX_DELAY_TERM) {
                last.numerator = (int) (mergedNumerator / divisor);
                last.denominator = (int) (mergedDenominator / divisor);
                return;
            }
        }
        var noneLeft = width;
        var noneTop = height;
        var noneRight = 0;
        var noneBottom = 0;
        var noneCount = 0L;
        var backLeft = width;
        var backTop = height;
        var backRight = 0;
        var backBottom = 0;
        var backCount = 0L;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            var insideRows = y >= last.y && y < last.y + last.height;
            for (var x = 0; x < width; x++) {
                var a = incoming[row + x];
                var b = current[row + x];
                if (a != b) {
                    noneCount++;
                    noneLeft = Math.min(noneLeft, x);
                    noneRight = Math.max(noneRight, x + 1);
                    noneTop = Math.min(noneTop, y);
                    noneBottom = y + 1;
                }
                var cleared = insideRows && x >= last.x && x < last.x + last.width;
                if (cleared ? a != 0 : a != b) {
                    backCount++;
                    backLeft = Math.min(backLeft, x);
                    backRight = Math.max(backRight, x + 1);
                    backTop = Math.min(backTop, y);
                    backBottom = y + 1;
                }
            }
        }
        var background = cost(backLeft, backTop, backRight, backBottom, backCount)
                < cost(noneLeft, noneTop, noneRight, noneBottom, noneCount);
        last.dispose = background ? ApngFrameControl.DISPOSE_BACKGROUND : ApngFrameControl.DISPOSE_NONE;
        int[] under;
        if (background) {
            System.arraycopy(current, 0, scratch, 0, current.length);
            for (var y = last.y; y < last.y + last.height; y++) {
                Arrays.fill(scratch, y * width + last.x, y * width + last.x + last.width, 0);
            }
            under = scratch;
        } else {
            under = current;
        }
        var x0 = background ? backLeft : noneLeft;
        var y0 = background ? backTop : noneTop;
        var x1 = background ? backRight : noneRight;
        var y1 = background ? backBottom : noneBottom;
        if (x1 <= x0 || y1 <= y0) {
            x0 = 0;
            y0 = 0;
            x1 = 1;
            y1 = 1;
        }
        frames.add(store(incoming, under, x0, y0, x1 - x0, y1 - y0, numerator, denominator, true));
        var recycled = current;
        current = incoming;
        incoming = recycled;
    }

    /**
     * Estimates the coding cost of a frame rectangle: one unit per changed pixel and an eighth
     * per unchanged pixel inside the rectangle.
     *
     * @param x0 the left edge
     * @param y0 the top edge
     * @param x1 the right edge, exclusive
     * @param y1 the bottom edge, exclusive
     * @param changed the number of changed pixels
     * @return the estimate
     */
    private static double cost(int x0, int y0, int x1, int y1, long changed) {
        var area = x1 <= x0 || y1 <= y0 ? 0 : (long) (x1 - x0) * (y1 - y0);
        return changed + (area - changed) / 8.0;
    }

    /**
     * Returns the greatest common divisor of two positive numbers.
     *
     * @param a the first number
     * @param b the second number
     * @return the divisor
     */
    private static long gcd(long a, long b) {
        while (b != 0) {
            var t = a % b;
            a = b;
            b = t;
        }
        return Math.max(a, 1);
    }

    /**
     * Stores the rectangle of a frame: its pixels and which of them the canvas already shows,
     * compressed with zlib's fastest level, and records its colors.
     *
     * @param frame the frame's canvas
     * @param under the canvas a decoder shows before the frame
     * @param x the left edge of the rectangle
     * @param y the top edge of the rectangle
     * @param w the rectangle width
     * @param h the rectangle height
     * @param numerator the delay numerator
     * @param denominator the delay denominator
     * @param overAllowed whether the frame may be blended over the canvas
     * @return the stored frame
     */
    private Frame store(int[] frame, int[] under, int x, int y, int w, int h, int numerator, int denominator,
                        boolean overAllowed) {
        var area = w * h;
        var raw = new byte[area * 5];
        var over = overAllowed;
        var unchanged = false;
        for (var row = 0; row < h; row++) {
            var source = (y + row) * width + x;
            for (var column = 0; column < w; column++) {
                var i = row * w + column;
                var color = frame[source + column];
                var below = under[source + column];
                ByteArrays.put32le(raw, i * 4, color);
                if (color == below) {
                    raw[area * 4 + i] = 1;
                    unchanged = true;
                } else if (color == 0 || (color >>> 24 != 0xFF && below != 0)) {
                    over = false;
                }
                colors.add(color);
                translucent |= color >>> 24 != 0xFF;
            }
        }
        over &= unchanged;
        if (over) {
            colors.add(0);
        }
        return new Frame(x, y, w, h, numerator, denominator, over, Zlib.deflate(raw, Deflater.BEST_SPEED));
    }

    /**
     * Chooses the color type, codes every frame and assembles the datastream.
     *
     * @return the datastream
     */
    private byte[] write() {
        if (frames.size() == 1 && fallback == null) {
            var picture = RgbaPicture.allocate(width, height, translucent);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    ByteArrays.put32le(picture.pixels(), y * picture.stride() + x * 4, current[y * width + x]);
                }
            }
            return PngEncoder.encode(picture, PngEncoderOptions.builder()
                    .compressionLevel(options.compressionLevel()).filter(options.filter()).build());
        }
        int type;
        int[] blends;
        if (!colors.overflowed()) {
            colors.order();
            type = INDEXED;
            blends = plan(type, new long[1]);
        } else if (!translucent) {
            var rgb = 0L;
            var rgba = 0L;
            var step = Math.max(1, frames.size() / TYPE_SAMPLES);
            for (var i = 0; i < frames.size(); i += step) {
                var frame = frames.get(i);
                rgb += trial(frame, TRUECOLOR, false);
                var alpha = trial(frame, TRUECOLOR_ALPHA, false);
                if (frame.over) {
                    alpha = Math.min(alpha, trial(frame, TRUECOLOR_ALPHA, true));
                }
                rgba += alpha;
            }
            type = rgb <= rgba ? TRUECOLOR : TRUECOLOR_ALPHA;
            blends = type == TRUECOLOR ? new int[frames.size()] : plan(type, new long[1]);
        } else {
            type = TRUECOLOR_ALPHA;
            blends = plan(type, new long[1]);
        }
        var coded = new ArrayList<Coded>(frames.size());
        for (var i = 0; i < frames.size(); i++) {
            coded.add(new Coded(code(frames.get(i), type, blends[i] == ApngFrameControl.BLEND_OVER), blends[i]));
        }
        var hidden = fallback == null ? null : new Coded(code(fallback, type, false), ApngFrameControl.BLEND_SOURCE);
        return assemble(type, coded, hidden);
    }

    /**
     * Chooses the blend operation of every frame in a color type, and sums the measured sizes of
     * the choices.
     *
     * <p>A frame is blended over the canvas when it allows it and that measures smaller.
     *
     * @param type the color type, indexed or with alpha
     * @param total receives, in its first element, the sum of the measured sizes
     * @return the {@code blend_op} of each frame
     */
    private int[] plan(int type, long[] total) {
        var blends = new int[frames.size()];
        var sum = 0L;
        for (var i = 0; i < blends.length; i++) {
            var frame = frames.get(i);
            var source = trial(frame, type, false);
            blends[i] = ApngFrameControl.BLEND_SOURCE;
            if (frame.over) {
                var over = trial(frame, type, true);
                if (over < source) {
                    blends[i] = ApngFrameControl.BLEND_OVER;
                    source = over;
                }
            }
            sum += source;
        }
        total[0] = sum;
        return blends;
    }

    /**
     * Measures the size of a frame compressed at level 1.
     *
     * @param frame the stored frame
     * @param type the color type
     * @param over whether the unchanged pixels are made transparent for
     * {@code APNG_BLEND_OP_OVER}
     * @return the compressed size in bytes
     */
    private long trial(Frame frame, int type, boolean over) {
        return compress(TRIAL_LEVEL, pixels(frame, over), frame.width, frame.height, type).length;
    }

    /**
     * Compresses a frame at the configured level.
     *
     * @param frame the stored frame
     * @param type the color type
     * @param over whether the unchanged pixels are made transparent for
     * {@code APNG_BLEND_OP_OVER}
     * @return the zlib stream
     */
    private byte[] code(Frame frame, int type, boolean over) {
        return compress(options.compressionLevel(), pixels(frame, over), frame.width, frame.height, type);
    }

    /**
     * Restores the pixels of a stored frame.
     *
     * @param frame the stored frame
     * @param over whether the pixels the canvas already shows are made transparent
     * @return the packed colors of the rectangle, row-major
     */
    private static int[] pixels(Frame frame, boolean over) {
        var area = frame.width * frame.height;
        var raw = Zlib.inflate(frame.stored, area * 5);
        if (raw == null) {
            throw new IllegalStateException("stored APNG frame is corrupt");
        }
        var pixels = new int[area];
        for (var i = 0; i < area; i++) {
            pixels[i] = over && raw[area * 4 + i] != 0 ? 0 : ByteArrays.i32le(raw, i * 4);
        }
        return pixels;
    }

    /**
     * Filters and compresses the pixels of a rectangle into a zlib stream.
     *
     * @param level the compression level
     * @param pixels the packed colors, row-major
     * @param w the rectangle width
     * @param h the rectangle height
     * @param type the color type
     * @return the zlib stream
     */
    private byte[] compress(int level, int[] pixels, int w, int h, int type) {
        var channels = type == INDEXED ? 1 : type == TRUECOLOR ? 3 : 4;
        var rowBytes = w * channels;
        var kernel = PngFilterKernel.INSTANCE;
        var filterType = PngFilters.type(options.filter());
        var adaptive = filterType < 0 && type != INDEXED;
        var fixedType = Math.max(0, filterType);
        var candidates = new byte[adaptive ? PngFilters.TYPES : 1][rowBytes + 1];
        var currentRow = new byte[rowBytes];
        var previousRow = new byte[rowBytes];
        var zero = new byte[rowBytes];
        var stride = rowBytes + 1;
        var data = new byte[Math.multiplyExact(stride, h)];
        var lastColor = 0;
        var lastIndex = -1;
        for (var y = 0; y < h; y++) {
            var row = y * w;
            for (var x = 0; x < w; x++) {
                var color = pixels[row + x];
                switch (channels) {
                    case 1 -> {
                        if (lastIndex < 0 || color != lastColor) {
                            lastColor = color;
                            lastIndex = colors.indexOf(color);
                        }
                        currentRow[x] = (byte) lastIndex;
                    }
                    case 3 -> {
                        currentRow[x * 3] = (byte) color;
                        currentRow[x * 3 + 1] = (byte) (color >>> 8);
                        currentRow[x * 3 + 2] = (byte) (color >>> 16);
                    }
                    default -> ByteArrays.put32le(currentRow, x * 4, color);
                }
            }
            var prior = y == 0 ? zero : previousRow;
            byte[] chosen;
            if (adaptive) {
                chosen = PngFilters.filterAdaptive(kernel, currentRow, 0, prior, 0, rowBytes, channels, candidates);
            } else {
                PngFilters.filter(kernel, fixedType, currentRow, 0, prior, 0, rowBytes, channels, candidates[0]);
                chosen = candidates[0];
            }
            System.arraycopy(chosen, 0, data, y * stride, stride);
            var swap = previousRow;
            previousRow = currentRow;
            currentRow = swap;
        }
        return ZlibDeflater.zlib(data, 0, data.length, level, adaptive || fixedType != 0);
    }

    /**
     * Writes the datastream: signature, {@code IHDR}, the palette chunks, {@code acTL}, the
     * fallback image, the frames and {@code IEND}.
     *
     * @param type the color type
     * @param coded the coded frames
     * @param hidden the coded fallback image, or {@code null}
     * @return the datastream
     */
    private byte[] assemble(int type, List<Coded> coded, Coded hidden) {
        var size = 1024L;
        for (var frame : coded) {
            size += frame.data.length + 64;
        }
        var out = new PngChunkWriter((int) Math.min(Integer.MAX_VALUE - 16,
                size + (hidden == null ? 0 : hidden.data.length)));
        out.header(width, height, type, type == INDEXED ? colors : null, null);
        var actl = new byte[8];
        ByteArrays.put32be(actl, 0, coded.size());
        ByteArrays.put32be(actl, 4, options.loopCount());
        out.chunk(PngChunks.ACTL, actl, 0, actl.length);
        if (hidden != null) {
            out.data(PngChunks.IDAT, hidden.data, -1, CHUNK_SIZE);
        }
        var sequence = 0;
        for (var i = 0; i < coded.size(); i++) {
            var frame = frames.get(i);
            var fctl = new byte[ApngFrameControl.LENGTH];
            ByteArrays.put32be(fctl, 0, sequence++);
            ByteArrays.put32be(fctl, 4, frame.width);
            ByteArrays.put32be(fctl, 8, frame.height);
            ByteArrays.put32be(fctl, 12, frame.x);
            ByteArrays.put32be(fctl, 16, frame.y);
            ByteArrays.put16be(fctl, 20, frame.numerator);
            ByteArrays.put16be(fctl, 22, frame.denominator);
            fctl[24] = (byte) frame.dispose;
            fctl[25] = (byte) coded.get(i).blend;
            out.chunk(PngChunks.FCTL, fctl, 0, fctl.length);
            if (i == 0 && hidden == null) {
                out.data(PngChunks.IDAT, coded.get(i).data, -1, CHUNK_SIZE);
            } else {
                sequence = out.data(PngChunks.FDAT, coded.get(i).data, sequence, CHUNK_SIZE);
            }
        }
        out.end();
        return out.toByteArray();
    }

    /**
     * Holds the changed rectangle of a frame until the color type is known.
     */
    private static final class Frame {
        /**
         * The left edge of the rectangle.
         */
        private final int x;

        /**
         * The top edge of the rectangle.
         */
        private final int y;

        /**
         * The rectangle width.
         */
        private final int width;

        /**
         * The rectangle height.
         */
        private final int height;

        /**
         * Whether blending over the canvas, with the unchanged pixels made transparent, gives the
         * frame exactly.
         */
        private final boolean over;

        /**
         * The rectangle's pixels (4 bytes each, little-endian RGBA) followed by one byte per pixel
         * that is 1 when the canvas already shows the pixel, compressed with zlib.
         */
        private final byte[] stored;

        /**
         * The delay numerator; grows when identical frames merge.
         */
        private int numerator;

        /**
         * The delay denominator; changes when identical frames merge.
         */
        private int denominator;

        /**
         * The {@code dispose_op}, decided when the next frame arrives.
         */
        private int dispose = ApngFrameControl.DISPOSE_NONE;

        /**
         * Constructs a stored frame.
         *
         * @param x the left edge
         * @param y the top edge
         * @param width the width of the frame region
         * @param height the height of the frame region
         * @param numerator the delay numerator
         * @param denominator the delay denominator
         * @param over whether the frame may be blended over the canvas
         * @param stored the compressed pixels and unchanged flags
         */
        private Frame(int x, int y, int width, int height, int numerator, int denominator, boolean over,
                      byte[] stored) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.numerator = numerator;
            this.denominator = denominator;
            this.over = over;
            this.stored = stored;
        }
    }

    /**
     * Holds the compressed image data of a frame and its blend operation.
     *
     * @param data the zlib stream
     * @param blend the {@code blend_op}
     */
    private record Coded(byte[] data, int blend) {
    }

    /**
     * Converts a frame duration to whole microseconds, truncating, saturating at
     * {@link Long#MAX_VALUE} and keeping negative durations negative for the writer to reject.
     *
     * @param duration the frame duration, possibly negative
     * @return the duration in microseconds
     */
    private static long toMicros(Duration duration) {
        if (duration.isNegative()) {
            return -1;
        }
        try {
            return Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
