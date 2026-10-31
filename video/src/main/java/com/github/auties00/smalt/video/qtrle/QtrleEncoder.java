package com.github.auties00.smalt.video.qtrle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.codec.IntermediateFrames;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into QuickTime Animation ({@link Codec#QTRLE}), the run-length codec of the QuickTime {@code rle }
 * sample entry.
 *
 * <p>Pictures are converted to 8-bit RGBA as {@link ImageOps#toRgba(com.github.auties00.smalt.image.Picture)} does
 * and coded at the {@link QtrleEncoderOptions#depth()}. A key frame codes every row; a later frame codes only the rows
 * from the first to the last that changed, skipping the pixels equal to the frame before, and a frame equal to the one
 * before it is a 4-byte packet that repeats it. Every {@link #encode(VideoFrame, Consumer)} emits one packet carrying
 * the frame's timestamp (or, without one, the timestamp following the previous frame at the nominal frame rate) and
 * duration; {@link #flush(Consumer)} has nothing to drain. {@link #codecConfig()} holds the depth, which the
 * containers that carry {@link Codec#QTRLE} (QuickTime and Matroska) write into the sample description.
 *
 * <p>Instances are not thread-safe.
 */
public final class QtrleEncoder implements VideoEncoder {
    /**
     * The largest frame dimension.
     */
    private static final int MAX_DIMENSION = 65535;

    /**
     * The longest literal run of one code.
     */
    private static final int MAX_LITERAL = 127;

    /**
     * The longest repeat run of one code.
     */
    private static final int MAX_REPEAT = 128;

    /**
     * The most units one skip code skips.
     */
    private static final int MAX_SKIP = 254;

    private final int width;

    private final int height;

    private final QtrleEncoderOptions options;

    /**
     * The coded units of a row: one per pixel, or four gray pixels per unit at depth 40.
     */
    private final int unitsPerRow;

    /**
     * The bytes of a coded unit.
     */
    private final int unitBytes;

    /**
     * The coded units of the frame being encoded, row after row.
     */
    private int[] current;

    /**
     * The coded units of the previous frame.
     */
    private int[] previous;

    /**
     * Whether {@link #previous} holds a frame.
     */
    private boolean hasPrevious;

    /**
     * The frames coded since the last key frame.
     */
    private int sinceKeyframe;

    /**
     * Whether the next frame must be a key frame.
     */
    private boolean keyframeRequested;

    /**
     * The packet being built.
     */
    private byte[] out = new byte[1024];

    /**
     * The number of valid bytes of {@link #out}.
     */
    private int size;

    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * Constructs an encoder for a picture size under the {@link QtrleEncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public QtrleEncoder(int width, int height) {
        this(width, height, QtrleEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public QtrleEncoder(int width, int height, QtrleEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.unitsPerRow = options.depth() == 40 ? (width + 3) / 4 : width;
        this.unitBytes = switch (options.depth()) {
            case 16 -> 2;
            case 24 -> 3;
            default -> 4;
        };
        LimitChecks.checkAllocation(options.limits(), 8L * unitsPerRow * height);
        this.current = new int[unitsPerRow * height];
        this.previous = new int[unitsPerRow * height];
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
    }

    /**
     * {@return the encoding options}
     */
    public QtrleEncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#QTRLE}
     */
    @Override
    public Codec codec() {
        return Codec.QTRLE;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return the depth as a big-endian 16-bit number, as {@link Codec#QTRLE} describes
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(IntermediateFrames.qtrleConfig(options.depth(), null));
    }

    /**
     * Encodes one frame into one packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the packet of track 0, whose presentation and decoding times are the frame's timestamp
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the timestamp does not
     * follow the previous one
     */
    public Packet encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + width + "x" + height);
        }
        var timing = clock.resolve(frame);
        var rgba = ImageOps.toRgba(picture);
        units(rgba.pixels(), rgba.stride());
        var key = !hasPrevious || keyframeRequested || sinceKeyframe >= options.keyframeInterval();
        size = 0;
        if (key) {
            u32(0);
            u16(0);
            for (var row = 0; row < height; row++) {
                row(row, true);
            }
            out(0);
            sinceKeyframe = 1;
            keyframeRequested = false;
        } else {
            var first = 0;
            while (first < height && sameRow(first)) {
                first++;
            }
            if (first == height) {
                u32(4);
            } else {
                var last = height - 1;
                while (sameRow(last)) {
                    last--;
                }
                u32(0);
                u16(0x0008);
                u16(first);
                u16(0);
                u16(last - first + 1);
                u16(0);
                for (var row = first; row <= last; row++) {
                    row(row, false);
                }
                out(0);
            }
            sinceKeyframe++;
        }
        if (size > 4 || key) {
            out[0] = (byte) (size >>> 24);
            out[1] = (byte) (size >>> 16);
            out[2] = (byte) (size >>> 8);
            out[3] = (byte) size;
        }
        var swap = previous;
        previous = current;
        current = swap;
        hasPrevious = true;
        clock.commit(timing.pts());
        return new Packet(0, Arrays.copyOf(out, size), timing.pts(), timing.pts(), timing.duration(), key);
    }

    /**
     * Converts a picture to the coded units of {@link #current}.
     *
     * @param pixels the RGBA samples
     * @param stride the distance in bytes between row starts
     */
    private void units(byte[] pixels, int stride) {
        var depth = options.depth();
        for (var y = 0; y < height; y++) {
            var base = y * unitsPerRow;
            var row = y * stride;
            if (depth == 40) {
                for (var u = 0; u < unitsPerRow; u++) {
                    var value = 0;
                    for (var i = 0; i < 4; i++) {
                        var x = u * 4 + i;
                        var index = 0;
                        if (x < width) {
                            var at = row + x * 4;
                            var luma = (77 * (pixels[at] & 0xFF) + 150 * (pixels[at + 1] & 0xFF)
                                    + 29 * (pixels[at + 2] & 0xFF) + 128) >>> 8;
                            index = 255 - luma;
                        }
                        value = value << 8 | index;
                    }
                    current[base + u] = value;
                }
                continue;
            }
            for (var x = 0; x < width; x++) {
                var at = row + x * 4;
                var r = pixels[at] & 0xFF;
                var g = pixels[at + 1] & 0xFF;
                var b = pixels[at + 2] & 0xFF;
                current[base + x] = switch (depth) {
                    case 16 -> (r >>> 3) << 10 | (g >>> 3) << 5 | b >>> 3;
                    case 24 -> r << 16 | g << 8 | b;
                    default -> (pixels[at + 3] & 0xFF) << 24 | r << 16 | g << 8 | b;
                };
            }
        }
    }

    /**
     * {@return whether a row equals the same row of the previous frame}
     *
     * @param row the picture row
     */
    private boolean sameRow(int row) {
        var base = row * unitsPerRow;
        return Arrays.equals(current, base, base + unitsPerRow, previous, base, base + unitsPerRow);
    }

    /**
     * Codes one row: its skip code, then skip, literal and repeat codes, then the end-of-line code.
     *
     * @param row the picture row
     * @param key whether every pixel is coded, which is the case of a key frame
     */
    private void row(int row, boolean key) {
        var base = row * unitsPerRow;
        var x = 0;
        var skipped = key ? 0 : unchanged(base, 0, MAX_SKIP);
        if (skipped == unitsPerRow) {
            out(1);
            out(0xFF);
            return;
        }
        out(skipped + 1);
        x += skipped;
        while (x < unitsPerRow) {
            if (!key) {
                var same = unchanged(base, x, unitsPerRow);
                if (same > 0) {
                    if (x + same == unitsPerRow) {
                        break;
                    }
                    x += same;
                    while (same > 0) {
                        var step = Math.min(same, MAX_SKIP);
                        out(0);
                        out(step + 1);
                        same -= step;
                    }
                    continue;
                }
            }
            var repeat = 1;
            while (x + repeat < unitsPerRow && repeat < MAX_REPEAT
                    && current[base + x + repeat] == current[base + x]) {
                repeat++;
            }
            if (repeat >= 2) {
                out(-repeat & 0xFF);
                unit(current[base + x]);
                x += repeat;
                continue;
            }
            var literal = 1;
            while (x + literal < unitsPerRow && literal < MAX_LITERAL) {
                var at = base + x + literal;
                if (x + literal + 1 < unitsPerRow && current[at] == current[at + 1]) {
                    break;
                }
                if (!key && current[at] == previous[at] && x + literal + 1 < unitsPerRow
                        && current[at + 1] == previous[at + 1]) {
                    break;
                }
                literal++;
            }
            out(literal);
            for (var i = 0; i < literal; i++) {
                unit(current[base + x + i]);
            }
            x += literal;
        }
        out(0xFF);
    }

    /**
     * {@return the number of units from a column on that equal the previous frame, at most a bound}
     *
     * @param base the index of the row's first unit
     * @param x the first column
     * @param max the bound
     */
    private int unchanged(int base, int x, int max) {
        var count = 0;
        while (x + count < unitsPerRow && count < max && current[base + x + count] == previous[base + x + count]) {
            count++;
        }
        return count;
    }

    /**
     * Writes one coded unit.
     *
     * @param value the unit
     */
    private void unit(int value) {
        for (var i = unitBytes - 1; i >= 0; i--) {
            out(value >>> (8 * i));
        }
    }

    private void u16(int value) {
        out(value >>> 8);
        out(value);
    }

    private void u32(int value) {
        u16(value >>> 16);
        u16(value);
    }

    /**
     * Appends one byte to the packet.
     *
     * @param value the byte in the low 8 bits
     */
    private void out(int value) {
        if (size == out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = (byte) value;
    }

    /**
     * Makes the next frame a key frame.
     */
    @Override
    public void requestKeyframe() {
        keyframeRequested = true;
    }

    /**
     * Does nothing: QuickTime Animation frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
