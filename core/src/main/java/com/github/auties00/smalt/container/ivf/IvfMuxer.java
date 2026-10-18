package com.github.auties00.smalt.container.ivf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes IVF files, the container of the VP8, VP9 and AV1 reference tools, from the packets of
 * one video track.
 *
 * <p>The 32-byte header stores the codec FourCC ({@code VP80}, {@code VP90} or {@code AV01}),
 * the frame size of the {@link VideoTrack} and its time base as the rate and scale pair
 * {@code timeBase = scale / rate}; each packet becomes a 12-byte frame header (size and 64-bit
 * presentation timestamp, or the decoding timestamp when the presentation timestamp is unknown)
 * followed by the frame. A packet without any timestamp gets the previous timestamp plus one.
 *
 * <p>On a seekable sink {@link #finish()} patches the frame count of the header; on a streaming
 * sink it stays zero, which readers ignore. The sink is written from its position at
 * construction and is never closed by the muxer. Instances are not thread-safe.
 */
public final class IvfMuxer implements Muxer {
    /**
     * The size of the file header.
     */
    private static final int HEADER_SIZE = 32;

    /**
     * The offset of the frame count in the file header.
     */
    private static final int FRAME_COUNT_OFFSET = 24;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The sink position at construction, where the file starts.
     */
    private final long base;

    /**
     * The declared track, or {@code null}.
     */
    private VideoTrack track;

    /**
     * The number of frames written.
     */
    private long frames;

    /**
     * The timestamp of the previous frame.
     */
    private long previous = -1;

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public IvfMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
        this.base = output.position();
    }

    /**
     * Returns whether IVF can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for {@link Codec#VP8}, {@link Codec#VP9} and {@link Codec#AV1}
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case VP8, VP9, AV1 -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not a VP8, VP9 or AV1 video track, its frame size exceeds 65535
     * or its time base does not fit 32-bit rate and scale fields
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("an IVF file carries one track");
        }
        if (!(track instanceof VideoTrack video) || !supports(track.codec())) {
            throw new IllegalArgumentException("IVF cannot carry " + track.codec().displayName());
        }
        if (video.width() > 0xFFFF || video.height() > 0xFFFF) {
            throw new IllegalArgumentException("frame size " + video.width() + "x" + video.height() + " does not fit IVF");
        }
        var timeBase = video.timeBase().reduce();
        if (timeBase.numerator() > 0xFFFFFFFFL || timeBase.denominator() > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("time base " + timeBase + " does not fit IVF");
        }
        this.track = video;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track
     * @throws IllegalStateException if no track was declared or the muxer is finished
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
        if (!started) {
            writeHeader();
        }
        var timestamp = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts()
                : packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : previous + 1;
        previous = timestamp;
        var header = new ByteWriter(12);
        header.u32le(packet.size());
        header.u64le(timestamp);
        header.writeTo(output);
        output.write(ByteBuffer.wrap(packet.data()));
        frames++;
    }

    /**
     * {@inheritDoc}
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
        if (output.isSeekable()) {
            var end = output.position();
            var count = new ByteWriter(4);
            count.u32le(Math.min(frames, 0xFFFFFFFFL));
            output.position(base + FRAME_COUNT_OFFSET);
            count.writeTo(output);
            output.position(end);
        }
    }

    /**
     * Marks the muxer finished.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the file header.
     */
    private void writeHeader() {
        started = true;
        var timeBase = track.timeBase().reduce();
        var header = new ByteWriter(HEADER_SIZE);
        header.fourCc("DKIF");
        header.u16le(0);
        header.u16le(HEADER_SIZE);
        header.fourCc(switch (track.codec()) {
            case VP8 -> "VP80";
            case VP9 -> "VP90";
            default -> "AV01";
        });
        header.u16le(track.width());
        header.u16le(track.height());
        header.u32le(timeBase.denominator());
        header.u32le(timeBase.numerator());
        header.u32le(0);
        header.u32le(0);
        header.writeTo(output);
    }
}
