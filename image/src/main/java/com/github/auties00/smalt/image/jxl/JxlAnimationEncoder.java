package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jxl.internal.encode.HeaderWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.JxlBitWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.JxlImageEncoder;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Encodes JPEG XL animations frame by frame.
 *
 * <p>Frames are full-canvas pictures added in display order with their durations, which are stored in
 * milliseconds (1000 ticks per second): each frame ends at its running end time rounded to the millisecond, so
 * the rounding never accumulates. Every frame after the first codes only the rectangle that changed since the
 * previous frame, over the previous canvas kept as a reference frame, and consecutive identical frames are merged
 * into one of their summed duration. Frames are coded losslessly or lossily as the
 * {@link JxlEncoderOptions options} say (CMYK frames always losslessly), with an alpha channel unless the frames
 * have other extra channels, and every frame has the channels and sample depth of the first; the color profile,
 * orientation, EXIF and XMP metadata and the {@linkplain JxlEncoderOptions#loopCount() loop count} apply to the
 * whole animation.
 *
 * <p>Nothing reaches the output before {@link #finish(MediaSink)}. Each frame is encoded on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes as it is added; the file does not depend
 * on the thread count. Instances are not thread-safe.
 */
public final class JxlAnimationEncoder {
    private static final int TICKS_PER_SECOND = 1000;

    private final int width;

    private final int height;

    private final JxlEncoderOptions options;

    private final JxlBitWriter out = new JxlBitWriter(1 << 16);

    private ImageHeader header;

    private boolean lossless;

    private JxlImageEncoder.Source previous;

    private JxlImageEncoder.Source pending;

    private int[] pendingRect;

    private long pendingTicks;

    private long elapsedMicros;

    private boolean finished;

    /**
     * Creates an {@link AnimationWriter} that encodes an animation into a sink.
     *
     * <p>Nothing reaches the sink before {@link AnimationWriter#finish()}, which writes the whole file; closing
     * the writer without finishing writes nothing.
     *
     * @param width the canvas width, from 1 to 2^30
     * @param height the canvas height, from 1 to 2^30
     * @param options the encoder settings
     * @param sink the output; not closed
     * @return the writer
     * @throws IllegalArgumentException if a size is not positive
     * @throws MediaException.LimitExceeded if a side exceeds 2^30 pixels
     */
    public static AnimationWriter openAnimationWriter(int width, int height, JxlEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        var encoder = new JxlAnimationEncoder(width, height, options);
        return new AnimationWriter() {
            private boolean done;

            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public void write(RgbaPicture picture, long durationMicros) {
                if (done) {
                    throw new IllegalStateException("the animation is finished");
                }
                encoder.addFrame(picture, Duration.ofNanos(Math.multiplyExact(durationMicros, 1000L)));
            }

            @Override
            public void finish() {
                if (done) {
                    throw new IllegalStateException("the animation is finished");
                }
                done = true;
                encoder.finish(sink);
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * Constructs an encoder with the {@link JxlEncoderOptions#lossless()} options.
     *
     * @param width the canvas width, from 1 to 2^30
     * @param height the canvas height, from 1 to 2^30
     * @throws IllegalArgumentException if a size is not positive
     * @throws MediaException.LimitExceeded if a side exceeds 2^30 pixels
     */
    public JxlAnimationEncoder(int width, int height) {
        this(width, height, JxlEncoderOptions.lossless());
    }

    /**
     * Constructs an encoder.
     *
     * @param width the canvas width, from 1 to 2^30
     * @param height the canvas height, from 1 to 2^30
     * @param options the encoder settings
     * @throws IllegalArgumentException if a size is not positive
     * @throws MediaException.LimitExceeded if a side exceeds 2^30 pixels
     */
    public JxlAnimationEncoder(int width, int height, JxlEncoderOptions options) {
        Objects.requireNonNull(options, "options");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + width + "x" + height);
        }
        if (width > 1 << 30 || height > 1 << 30) {
            throw new MediaException.LimitExceeded("JPEG XL canvas of " + width + "x" + height
                    + " exceeds 2^30 pixels per side");
        }
        this.width = width;
        this.height = height;
        this.options = options;
    }

    /**
     * Adds a frame.
     *
     * @param frame the frame, of the canvas size
     * @param duration the display duration, not negative
     * @throws IllegalArgumentException if the frame is not of the canvas size, its channels, sample depth or color
     * model differ from the first frame's, or the duration is negative or longer than 2^32 - 1 milliseconds
     * @throws IllegalStateException if the animation is finished
     * @throws MediaException.LimitExceeded if encoding runs out of memory
     */
    public void addFrame(Picture frame, Duration duration) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(duration, "duration");
        if (finished) {
            throw new IllegalStateException("the animation is finished");
        }
        if (frame.width() != width || frame.height() != height) {
            throw new IllegalArgumentException("frame of " + frame.width() + "x" + frame.height()
                    + " does not match the canvas of " + width + "x" + height);
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("negative frame duration " + duration);
        }
        var micros = duration.toNanos() / 1000;
        var end = Math.addExact(elapsedMicros, micros);
        var ticks = round(end) - round(elapsedMicros);
        try (var _ = ParallelBands.operation()) {
            Guards.encoding("JPEG XL animation frame", () -> {
                add(frame, ticks);
                return null;
            });
        }
        elapsedMicros = end;
    }

    private static long round(long micros) {
        return (micros * TICKS_PER_SECOND + 500_000) / 1_000_000;
    }

    private void add(Picture frame, long ticks) {
        var frameLossless = JxlEncoder.isLossless(frame, options);
        if (header == null) {
            lossless = frameLossless;
        }
        // Gray frames stay RGB unless the profile is gray, so that every frame has the channels of the first.
        var grayProfile = options.colorProfile().map(p -> p.colorSpace() == ProfileColorSpace.GRAY).orElse(false);
        var source = withAlpha(JxlEncoder.source(frame, options, lossless, grayProfile));
        if (header == null) {
            var animation = new ImageHeader.Animation(TICKS_PER_SECOND, 1, options.loopCount(), false);
            header = JxlImageEncoder.header(source, !lossless, animation, JxlEncoder.toneOf(source.colorEncoding()));
            JxlImageEncoder.writeHeaders(out, source, header);
            pending = JxlEncoder.coded(source, options, lossless);
            pendingRect = new int[]{0, 0, width, height};
            pendingTicks = ticks;
            previous = source;
            return;
        }
        if (frameLossless != lossless || !sameChannels(previous, source)) {
            throw new IllegalArgumentException("a frame's channels, sample depth or color model differ from the "
                    + "first frame's");
        }
        var rect = changed(previous, source);
        if (rect == null) {
            pendingTicks += ticks;
            return;
        }
        flush(false);
        pending = JxlEncoder.coded(source, options, lossless);
        pendingRect = rect;
        pendingTicks = ticks;
        previous = source;
    }

    private static boolean sameChannels(JxlImageEncoder.Source a, JxlImageEncoder.Source b) {
        if (a.color().size() != b.color().size() || !a.depth().equals(b.depth())
                || a.extraInfo().size() != b.extraInfo().size()) {
            return false;
        }
        for (var i = 0; i < a.extraInfo().size(); i++) {
            var x = a.extraInfo().get(i);
            var y = b.extraInfo().get(i);
            if (x.type() != y.type() || !x.bitDepth().equals(y.bitDepth())
                    || x.alphaAssociated() != y.alphaAssociated() || !x.name().equals(y.name())) {
                return false;
            }
        }
        return true;
    }

    private static JxlImageEncoder.Source withAlpha(JxlImageEncoder.Source s) {
        if (!s.extra().isEmpty()) {
            return s;
        }
        var depth = s.depth();
        var alpha = new int[s.width() * s.height()];
        Arrays.fill(alpha, depth.floatSample() ? Float.floatToRawIntBits(1.0f)
                : (int) ((1L << depth.bitsPerSample()) - 1));
        var info = new ExtraChannelInfo(ExtraChannelInfo.TYPE_ALPHA, depth, 0, "", false, null, 0);
        return new JxlImageEncoder.Source(s.width(), s.height(), s.color(), depth, List.of(alpha), List.of(info),
                s.colorEncoding(), s.icc(), s.orientation());
    }

    private static int[] changed(JxlImageEncoder.Source a, JxlImageEncoder.Source b) {
        var w = a.width();
        var h = a.height();
        int x0 = w;
        int y0 = h;
        int x1 = -1;
        int y1 = -1;
        var planesA = new ArrayList<int[]>(a.color());
        planesA.addAll(a.extra());
        var planesB = new ArrayList<int[]>(b.color());
        planesB.addAll(b.extra());
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var i = y * w + x;
                for (var p = 0; p < planesA.size(); p++) {
                    if (planesA.get(p)[i] != planesB.get(p)[i]) {
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                        break;
                    }
                }
            }
        }
        if (x1 < 0) {
            return null;
        }
        return new int[]{x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }

    private void flush(boolean last) {
        if (pendingTicks > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("frame duration of " + pendingTicks
                    + " milliseconds exceeds what JPEG XL stores");
        }
        var frame = new HeaderWriter.Frame();
        frame.duration = (int) pendingTicks;
        frame.isLast = last;
        frame.saveAsReference = last ? 0 : 1;
        var rect = pendingRect;
        var full = rect[0] == 0 && rect[1] == 0 && rect[2] == width && rect[3] == height;
        var source = pending;
        if (!full) {
            frame.haveCrop = true;
            frame.x0 = rect[0];
            frame.y0 = rect[1];
            frame.width = rect[2];
            frame.height = rect[3];
            frame.blendSource = 1;
            source = crop(pending, rect);
        }
        if (lossless) {
            JxlImageEncoder.writeLosslessFrame(out, source, header, frame, options.effort());
        } else {
            JxlImageEncoder.writeLossyFrame(out, source, header, frame, options.distance(), options.effort());
        }
    }

    private static JxlImageEncoder.Source crop(JxlImageEncoder.Source s, int[] rect) {
        var color = new ArrayList<int[]>();
        for (var p : s.color()) {
            color.add(cropPlane(p, s.width(), rect));
        }
        var extra = new ArrayList<int[]>();
        for (var p : s.extra()) {
            extra.add(cropPlane(p, s.width(), rect));
        }
        return new JxlImageEncoder.Source(rect[2], rect[3], color, s.depth(), extra, s.extraInfo(),
                s.colorEncoding(), s.icc(), s.orientation());
    }

    private static int[] cropPlane(int[] plane, int stride, int[] rect) {
        var out = new int[rect[2] * rect[3]];
        for (var y = 0; y < rect[3]; y++) {
            System.arraycopy(plane, (rect[1] + y) * stride + rect[0], out, y * rect[2], rect[2]);
        }
        return out;
    }

    /**
     * Completes the animation and writes the file to a sink.
     *
     * @param sink the output, written from its position and not closed
     * @throws IllegalStateException if no frame was added or the animation is finished
     * @throws MediaException.Io if writing the sink fails
     */
    public void finish(MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(finish());
    }

    /**
     * Completes the animation.
     *
     * @return the JPEG XL file
     * @throws IllegalStateException if no frame was added or the animation is finished
     */
    public byte[] finish() {
        if (finished) {
            throw new IllegalStateException("the animation is finished");
        }
        if (header == null) {
            throw new IllegalStateException("no frame was added");
        }
        finished = true;
        try (var _ = ParallelBands.operation()) {
            return Guards.encoding("JPEG XL animation", () -> {
                flush(true);
                return JxlEncoder.wrap(out.toByteArray(), options, null);
            });
        }
    }
}
