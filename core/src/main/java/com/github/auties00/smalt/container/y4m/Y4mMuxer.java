package com.github.auties00.smalt.container.y4m;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.RawVideoFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes one {@link Codec#RAW_VIDEO} track as a YUV4MPEG2 file ({@code .y4m}).
 *
 * <p>The track's {@link RawVideoFormat} must be one YUV4MPEG2 carries: planar YUV 4:2:0, 4:2:2 or 4:4:4 at 8 to 16
 * bits, 4:1:1 or 4:4:4 with alpha at 8 bits, or gray at 8 to 16 bits, with rows one after the other, top to bottom,
 * and little-endian samples. The header gives the track's size, frame rate (from the track's frame rate, else the
 * inverse of its time base), interlacing, sample aspect ratio (unknown, {@code A0:0}, when the track has none),
 * colorspace (a 4:2:0 track's chroma siting choosing {@code 420jpeg}, {@code 420mpeg2} or {@code 420paldv}) and,
 * when the track declares a color description, its range as {@code XCOLORRANGE}. Every packet must hold exactly one
 * frame and is written after a bare {@code FRAME} line; timestamps are ignored.
 *
 * <p>The sink is written from its position at construction and never closed by the muxer. Instances are not
 * thread-safe.
 */
public final class Y4mMuxer implements Muxer {
    /**
     * The line that starts every frame.
     */
    private static final byte[] FRAME_LINE = {'F', 'R', 'A', 'M', 'E', '\n'};

    private final MediaSink output;

    private VideoTrack track;

    private long frameSize;

    private boolean started;

    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     */
    public Y4mMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Returns whether a track can be written: a raw video track whose format YUV4MPEG2 carries.
     *
     * @param track the track to write
     * @return {@code true} when {@link #addTrack(Track)} accepts it
     */
    public static boolean supports(Track track) {
        Objects.requireNonNull(track, "track");
        if (!(track instanceof VideoTrack video) || video.codec() != Codec.RAW_VIDEO || video.width() <= 0
                || video.height() <= 0) {
            return false;
        }
        try {
            var format = RawVideoFormat.fromCodecConfig(video.codecConfig().orElse(new byte[0]));
            return Y4mHeader.tag(format, ChromaSiting.CENTER) != null;
        } catch (MediaException invalid) {
            return false;
        }
    }

    /**
     * Returns whether a codec can be written.
     *
     * @param codec the codec of a track
     * @return {@code true} for {@link Codec#RAW_VIDEO}, whose tracks YUV4MPEG2 carries in the formats the class
     * description lists
     */
    public static boolean supports(Codec codec) {
        return Objects.requireNonNull(codec, "codec") == Codec.RAW_VIDEO;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not a raw video track with a size in a format YUV4MPEG2
     * carries, or a track was already declared
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalArgumentException("a YUV4MPEG2 file carries one track");
        }
        if (!supports(track)) {
            throw new IllegalArgumentException("YUV4MPEG2 cannot carry " + track.codec().displayName()
                    + " in this format");
        }
        var video = (VideoTrack) track;
        this.frameSize = RawVideoFormat.fromCodecConfig(video.codecConfig().orElseThrow())
                .frameSize(video.width(), video.height());
        this.track = video;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track or does not hold exactly one frame
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the codec configuration
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        if (ConfigChanges.changed(packet, track.codecConfig().orElse(null)) != null) {
            throw ConfigChanges.unsupported("a YUV4MPEG2 file", packet.trackId());
        }
        if (packet.size() != frameSize) {
            throw new IllegalArgumentException("a packet of " + packet.size() + " bytes is not one " + frameSize
                    + " byte frame");
        }
        if (!started) {
            writeHeader();
        }
        output.write(ByteBuffer.wrap(FRAME_LINE));
        output.write(ByteBuffer.wrap(packet.data()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A file without frames still gets its header.
     *
     * @throws IllegalStateException if no track was declared or the muxer is already finished
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (!started) {
            writeHeader();
        }
        finished = true;
    }

    /**
     * Marks the muxer finished.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the header line.
     */
    private void writeHeader() {
        started = true;
        var format = RawVideoFormat.fromCodecConfig(track.codecConfig().orElseThrow());
        var color = track.color().orElse(null);
        var siting = color == null ? ChromaSiting.CENTER : color.chromaSiting();
        var rate = track.frameRate().orElse(track.timeBase().invert()).reduce();
        var header = new StringBuilder("YUV4MPEG2 W").append(track.width()).append(" H").append(track.height())
                .append(" F").append(rate.numerator()).append(':').append(rate.denominator())
                .append(" I").append(interlacing(track.interlacing().orElse(Interlacing.PROGRESSIVE)));
        var aspect = track.sampleAspectRatio().map(Rational::reduce).orElse(null);
        header.append(" A").append(aspect == null ? "0:0" : aspect.numerator() + ":" + aspect.denominator());
        header.append(" C").append(Y4mHeader.tag(format, siting));
        if (color != null) {
            header.append(color.range() == ColorRange.FULL ? " XCOLORRANGE=FULL" : " XCOLORRANGE=LIMITED");
        }
        header.append('\n');
        output.write(ByteBuffer.wrap(header.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * Returns the {@code I} parameter of an interlacing.
     *
     * @param interlacing the scan of the track
     * @return {@code p}, {@code t}, {@code b} or {@code m}
     */
    private static char interlacing(Interlacing interlacing) {
        return switch (interlacing) {
            case PROGRESSIVE -> 'p';
            case TOP_FIELD_FIRST, TOP_FIELD_FIRST_SEPARATED -> 't';
            case BOTTOM_FIELD_FIRST, BOTTOM_FIELD_FIRST_SEPARATED -> 'b';
            default -> 'm';
        };
    }
}
