package com.github.auties00.smalt.container.y4m;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoFormat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads YUV4MPEG2 files ({@code .y4m}): a text header line starting with {@code YUV4MPEG2}, then frames, each a
 * {@code FRAME} line followed by the planar samples of one picture.
 *
 * <p>The file becomes one {@link VideoTrack} of {@link Codec#RAW_VIDEO} with identifier 1, whose codec
 * configuration is the {@link RawVideoFormat} its {@code C} parameter names: {@code 420jpeg}, {@code 420mpeg2},
 * {@code 420paldv} and {@code 420} (8-bit 4:2:0, with the chroma siting the name gives), {@code 422}, {@code 444},
 * {@code 411}, {@code 444alpha}, {@code mono} and the deeper {@code 420pN}, {@code 422pN}, {@code 444pN} and
 * {@code monoN} (little-endian samples of N bits, 9 to 16); a header without {@code C} is 4:2:0. The {@code W} and
 * {@code H} parameters give the size, {@code F} the frame rate (25 frames per second when absent), {@code A} the
 * sample aspect ratio, {@code I} the interlacing ({@code p}, {@code t}, {@code b}, {@code m}) and the
 * {@code XCOLORRANGE} extension the color range (limited by default, full for gray). Every frame becomes one key
 * frame packet; the time base is one frame period and packet {@code n} has presentation and decoding time {@code n}.
 * Frame parameters are ignored. A frame cut short by the end of the file ends the stream.
 *
 * <p>The demuxer is seekable; the first {@link #seek(long)} of a file whose frame lines carry parameters reads every
 * frame line once. The source is not closed. Instances are not thread-safe.
 */
public final class Y4mDemuxer implements Demuxer {
    /**
     * The longest header or frame line accepted, in bytes.
     */
    private static final int MAX_LINE = 64 * 1024;

    /**
     * The magic that starts a file.
     */
    private static final String MAGIC = "YUV4MPEG2";

    private final MediaSource source;

    private final SourceReader reader;

    private final VideoTrack track;

    private final long frameSize;

    /**
     * The offset of the first frame line.
     */
    private final long dataStart;

    /**
     * The index of the next frame.
     */
    private long next;

    /**
     * Whether a frame cut short by the end of the input or a seek past the last frame ended the stream.
     */
    private boolean ended;

    /**
     * The offsets of the frame lines, built on the first seek of a file whose frame lines are not all bare, or
     * {@code null}.
     */
    private long[] offsets;

    /**
     * Constructs a demuxer with {@link DemuxerOptions#defaults()}.
     *
     * @param source the file, read from offset zero and not closed by this demuxer
     * @throws MediaException.Malformed if the header is missing, malformed or lacks the picture size
     * @throws MediaException.Unsupported if the header names an unknown colorspace
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public Y4mDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer with options.
     *
     * @param source the file, read from offset zero and not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the header is missing, malformed or lacks the picture size
     * @throws MediaException.Unsupported if the header names an unknown colorspace
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public Y4mDemuxer(MediaSource source, DemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        var limits = Objects.requireNonNull(options, "options").limits();
        this.reader = new SourceReader(source);
        var header = readLine();
        if (header == null || !header.startsWith(MAGIC) || (header.length() > MAGIC.length()
                && header.charAt(MAGIC.length()) != ' ')) {
            throw new MediaException.Malformed("not a YUV4MPEG2 file");
        }
        this.dataStart = reader.position();
        var width = -1;
        var height = -1;
        Rational frameRate = null;
        Rational aspect = null;
        Interlacing interlacing = null;
        var colorspace = "420jpeg";
        ColorRange range = null;
        for (var parameter : header.substring(MAGIC.length()).trim().split(" +")) {
            if (parameter.isEmpty()) {
                continue;
            }
            var value = parameter.substring(1);
            switch (parameter.charAt(0)) {
                case 'W' -> width = parseInt(value, "width");
                case 'H' -> height = parseInt(value, "height");
                case 'F' -> frameRate = ratio(value);
                case 'A' -> aspect = ratio(value);
                case 'I' -> interlacing = switch (value) {
                    case "p" -> Interlacing.PROGRESSIVE;
                    case "t" -> Interlacing.TOP_FIELD_FIRST;
                    case "b" -> Interlacing.BOTTOM_FIELD_FIRST;
                    case "m" -> Interlacing.INTERLACED;
                    default -> null;
                };
                case 'C' -> colorspace = value;
                case 'X' -> {
                    if (value.equalsIgnoreCase("COLORRANGE=FULL")) {
                        range = ColorRange.FULL;
                    } else if (value.equalsIgnoreCase("COLORRANGE=LIMITED")) {
                        range = ColorRange.LIMITED;
                    }
                }
                default -> {
                }
            }
        }
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("the YUV4MPEG2 header lacks the picture size");
        }
        LimitChecks.checkPicture(limits, width, height);
        var format = Y4mHeader.format(colorspace);
        if (format == null) {
            throw new MediaException.Unsupported("y4m", "unknown YUV4MPEG2 colorspace " + colorspace);
        }
        this.frameSize = format.frameSize(width, height);
        LimitChecks.checkAllocation(limits, frameSize);
        if (frameRate == null || frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
            frameRate = new Rational(25, 1);
        }
        var gray = format.pixelFormat() == RawPixelFormat.GRAY;
        var color = (gray ? ColorInfo.JPEG : ColorInfo.BT601_LIMITED)
                .withRange(range != null ? range : gray ? ColorRange.FULL : ColorRange.LIMITED);
        if (format.pixelFormat() == RawPixelFormat.YUV420P) {
            color = color.withChromaSiting(Y4mHeader.siting(colorspace));
        }
        var builder = VideoTrack.builder()
                .id(1)
                .codec(Codec.RAW_VIDEO)
                .timeBase(frameRate.invert())
                .frameRate(frameRate)
                .width(width)
                .height(height)
                .color(color)
                .codecConfig(format.toCodecConfig());
        if (aspect != null && aspect.numerator() > 0 && aspect.denominator() > 0) {
            builder.sampleAspectRatio(aspect);
        }
        if (interlacing != null) {
            builder.interlacing(interlacing);
        }
        var size = source.size();
        var bare = 6 + frameSize;
        if (size >= 0 && (size - dataStart) % bare == 0) {
            builder.duration((size - dataStart) / bare);
        }
        this.track = builder.build();
    }

    /**
     * Returns whether bytes start a YUV4MPEG2 file.
     *
     * @param header the leading bytes of the input
     * @return {@code true} when they start with {@code YUV4MPEG2} and a space
     */
    public static boolean matches(byte[] header) {
        Objects.requireNonNull(header, "header");
        if (header.length < MAGIC.length() + 1) {
            return false;
        }
        for (var i = 0; i < MAGIC.length(); i++) {
            if (header[i] != MAGIC.charAt(i)) {
                return false;
            }
        }
        return header[MAGIC.length()] == ' ';
    }

    /**
     * Reads a decimal parameter.
     *
     * @param value the digits
     * @param name the parameter, for messages
     * @return the value
     * @throws MediaException.Malformed if the value is not a non-negative integer
     */
    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new MediaException.Malformed("invalid YUV4MPEG2 " + name + " " + value);
        }
    }

    /**
     * Reads a ratio parameter.
     *
     * @param value {@code n:d}
     * @return the ratio, or {@code null} when malformed or with a zero term
     */
    private static Rational ratio(String value) {
        var colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            var numerator = Long.parseLong(value.substring(0, colon));
            var denominator = Long.parseLong(value.substring(colon + 1));
            return numerator > 0 && denominator > 0 && numerator <= Integer.MAX_VALUE
                    && denominator <= Integer.MAX_VALUE ? new Rational(numerator, denominator).reduce() : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * Reads one line.
     *
     * @return the line without its line feed, or {@code null} at the end of the input
     * @throws MediaException.Malformed if the line exceeds the longest accepted
     */
    private String readLine() {
        if (reader.atEnd()) {
            return null;
        }
        var line = new byte[256];
        var length = 0;
        while (true) {
            if (reader.atEnd()) {
                return null;
            }
            var b = reader.u8();
            if (b == '\n') {
                return new String(line, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (length == MAX_LINE) {
                throw new MediaException.Malformed("a YUV4MPEG2 line exceeds " + MAX_LINE + " bytes");
            }
            if (length == line.length) {
                line = Arrays.copyOf(line, line.length * 2);
            }
            line[length++] = (byte) b;
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.Y4M;
    }

    @Override
    public List<Track> tracks() {
        return List.of(track);
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duration is known when every frame line is bare ({@code FRAME} alone) and the file ends with a whole
     * frame, or once a seek has indexed the file.
     */
    @Override
    public long durationMicros() {
        if (offsets != null) {
            return track.timeBase().toMicros(offsets.length);
        }
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * @throws MediaException.Malformed if a frame does not start with a {@code FRAME} line
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        if (ended) {
            return null;
        }
        var line = readLine();
        if (line == null) {
            return null;
        }
        if (!line.startsWith("FRAME") || (line.length() > 5 && line.charAt(5) != ' ')) {
            throw new MediaException.Malformed("expected a FRAME line at offset " + reader.position());
        }
        if (reader.remaining(frameSize) < frameSize) {
            ended = true;
            return null;
        }
        var data = new byte[(int) frameSize];
        reader.readFully(data, 0, data.length);
        var time = next++;
        return new Packet(1, data, time, time, 1, true);
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        var target = Math.max(0, SeekTimes.floor(micros, track.timeBase()));
        var bare = 6 + frameSize;
        var offset = dataStart + target * bare;
        if (offsets == null && isBareFrameLine(offset, target)) {
            reader.seek(offset);
            next = target;
            ended = target >= track.duration();
            return;
        }
        if (offsets == null) {
            offsets = index();
        }
        if (target >= offsets.length) {
            next = offsets.length;
            ended = true;
            return;
        }
        reader.seek(offsets[(int) target]);
        next = target;
        ended = false;
    }

    /**
     * Returns whether every frame line up to a frame may be assumed bare: the file holds whole bare frames up to
     * there and a bare frame line (or the end) at the computed offset.
     *
     * @param offset the computed offset of the frame line
     * @param frame the frame index
     * @return {@code true} when the computed offset can be used
     */
    private boolean isBareFrameLine(long offset, long frame) {
        var size = source.size();
        if (size < 0 || track.duration() < 0) {
            return false;
        }
        if (frame >= track.duration()) {
            return offset >= size;
        }
        var probe = new byte[6];
        var read = source.read(offset, probe, 0, 6);
        return read == 6 && new String(probe, StandardCharsets.ISO_8859_1).equals("FRAME\n");
    }

    /**
     * Reads every frame line and returns their offsets.
     *
     * @return the offsets
     */
    private long[] index() {
        var position = reader.position();
        var found = new ArrayList<Long>();
        try {
            reader.seek(dataStart);
            while (true) {
                var start = reader.position();
                var line = readLine();
                if (line == null || !line.startsWith("FRAME") || reader.remaining(frameSize) < frameSize) {
                    break;
                }
                found.add(start);
                reader.skip(frameSize);
            }
        } finally {
            reader.seek(position);
        }
        var result = new long[found.size()];
        for (var i = 0; i < result.length; i++) {
            result[i] = found.get(i);
        }
        return result;
    }
}
