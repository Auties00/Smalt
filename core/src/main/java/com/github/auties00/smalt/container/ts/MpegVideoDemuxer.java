package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Demuxes an MPEG-1 or MPEG-2 video elementary stream ({@code .m1v}, {@code .m2v}, {@code .mpv}, ISO/IEC 11172-2
 * and ISO/IEC 13818-2): a sequence header, then groups of pictures, without a container.
 *
 * <p>The single track, of identifier 1 in a 90 kHz time base, is {@link Codec#MPEG1_VIDEO} or
 * {@link Codec#MPEG2_VIDEO} as the sequence header says (a sequence extension makes it MPEG-2), with the
 * configuration, size, frame rate, sample aspect ratio and color of the first sequence header, as a transport stream
 * declares an MPEG video stream. Each packet is one access unit: a picture with the sequence and group of pictures
 * headers and user data before it, a second field picture joined to its first field. Timestamps come from the frame
 * rate: the first picture is decoded and shown at zero, each later picture is decoded one frame period (a field more
 * for a repeated first field) after the one before, and pictures are shown in the order of the temporal references
 * of their group, as the pictures of a transport stream without timestamps are; a packet lasts until the next one's
 * decoding time. A packet is a key frame when its picture is an I-picture; one whose sequence header differs from
 * the configuration carries it as its {@link com.github.auties00.smalt.track.PacketSideData#newCodecConfig()}.
 * Pictures before the first key frame with a sequence header are dropped.
 *
 * <p>The demuxer reads its input front to back and cannot seek. The source is not closed. Instances are not
 * thread-safe.
 */
public final class MpegVideoDemuxer implements Demuxer {
    /**
     * The bytes read at a time.
     */
    private static final int CHUNK = 64 * 1024;

    /**
     * The most bytes read before the track is known.
     */
    private static final int PROBE_BYTES = 16 << 20;

    /**
     * The input.
     */
    private final MediaSource source;

    /**
     * Cuts the stream into access units and describes it.
     */
    private final ElementaryParser parser;

    /**
     * The timeline of the parser.
     */
    private final TsClock clock = new TsClock(0, TsClock.NONE);

    /**
     * The packets parsed and not yet returned.
     */
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();

    /**
     * Collects the parser's packets.
     */
    private final ElementaryParser.UnitSink sink = (packet, fileOffset) -> pending.add(packet);

    /**
     * The track.
     */
    private final Track track;

    /**
     * The offset of the next byte to read.
     */
    private long position;

    /**
     * Whether the end of the input was reached and the parser flushed.
     */
    private boolean ended;

    /**
     * Constructs a demuxer with {@link DemuxerOptions#defaults()}.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @throws MediaException.Unsupported if the input does not start with an MPEG video sequence header
     * @throws MediaException.Malformed if no picture follows a sequence header
     * @throws MediaException.Io if reading fails
     */
    public MpegVideoDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer whose {@link DemuxerOptions#limits()} bound the size of one access unit.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Unsupported if the input does not start with an MPEG video sequence header
     * @throws MediaException.Malformed if no picture follows a sequence header
     * @throws MediaException.Io if reading fails
     */
    public MpegVideoDemuxer(MediaSource source, DemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        var limits = Objects.requireNonNull(options, "options").limits();
        var header = new byte[8];
        var read = Math.max(0, source.read(0, header, 0, header.length));
        if (detect(Arrays.copyOf(header, read)).isEmpty()) {
            throw new MediaException.Unsupported("mpeg video", "the input is not an MPEG video elementary stream");
        }
        this.parser = new AccessUnitFramer(new MpegVideoParser(Psi.plainEntry(1, 0x02, null), limits,
                Codec.MPEG2_VIDEO), false);
        while (!parser.configured() && !ended && position < PROBE_BYTES) {
            feed();
        }
        if (!parser.configured()) {
            throw new MediaException.Malformed("the MPEG video stream has no picture after a sequence header");
        }
        this.track = parser.track();
    }

    /**
     * Recognizes an MPEG-1 or MPEG-2 video elementary stream from its leading bytes: a sequence header start code
     * ({@code 00 00 01 B3}) whose picture size, aspect ratio and frame rate codes are valid.
     *
     * @param header the leading bytes of the input, at least 8 for a match
     * @return {@link ContainerFormat#MPEG_VIDEO}, or empty when the bytes do not start a sequence header
     */
    public static Optional<ContainerFormat> detect(byte[] header) {
        Objects.requireNonNull(header, "header");
        if (header.length < 8 || header[0] != 0 || header[1] != 0 || header[2] != 1 || (header[3] & 0xFF) != 0xB3) {
            return Optional.empty();
        }
        var width = (header[4] & 0xFF) << 4 | (header[5] & 0xFF) >>> 4;
        var height = (header[5] & 0x0F) << 8 | header[6] & 0xFF;
        var aspect = (header[7] & 0xFF) >>> 4;
        var rate = header[7] & 0x0F;
        if (width == 0 || height == 0 || aspect == 0 || aspect > 14 || rate == 0 || rate > 8) {
            return Optional.empty();
        }
        return Optional.of(ContainerFormat.MPEG_VIDEO);
    }

    /**
     * Reads the next chunk into the parser, or flushes it at the end of the input.
     */
    private void feed() {
        var chunk = new byte[CHUNK];
        var read = source.read(position, chunk, 0, CHUNK);
        if (read <= 0) {
            parser.flush(clock, sink);
            ended = true;
            return;
        }
        // the first picture is stamped zero; the framer times the others from the frame rate
        var stamp = position == 0 ? 0 : TsClock.NONE;
        parser.accept(new ElementaryParser.Pes(chunk, 0, read, stamp, stamp, position, false, false), clock, sink);
        position += read;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MPEG_VIDEO;
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
     * {@return {@code -1}: the duration of an elementary stream is not known before reading it}
     */
    @Override
    public long durationMicros() {
        return -1;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if an access unit is invalid
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        while (pending.isEmpty() && !ended) {
            feed();
        }
        return pending.pollFirst();
    }
}
