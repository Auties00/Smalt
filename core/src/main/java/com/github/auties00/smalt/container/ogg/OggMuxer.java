package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes Opus audio into an Ogg file following RFC 7845, and Vorbis audio following the Vorbis I
 * specification's Ogg encapsulation.
 *
 * <p>Every declared track must be an {@link AudioTrack} with {@link Codec#OPUS} or
 * {@link Codec#VORBIS}. An Opus track's codec configuration is the {@code OpusHead} header
 * written on the stream's first page; a track without configuration gets a synthesized
 * {@code OpusHead} for channel mapping family 0 (at most two channels) with the track's
 * {@code skipSamples}, converted to 48 kHz, as pre-skip. The second header is an
 * {@code OpusTags} packet carrying the configured vendor string and comments; it may span several
 * pages and always finishes its page. A Vorbis track's configuration holds the three Vorbis
 * headers, Xiph-laced: the identification header is written alone on the first page, then the
 * comment header (rebuilt from the tags when {@link #setTags(MediaTags)} set some) and the setup
 * header, the last finishing its page. Several tracks become grouped logical streams with
 * consecutive serial numbers: all beginning-of-stream pages come first, then the remaining
 * headers, then data pages in the order their pages fill up.
 *
 * <p>Opus granule positions count 48 kHz samples decoded from the start of the stream, pre-skip
 * included, using the duration coded in each packet's TOC byte. Vorbis granule positions count
 * samples at the stream's rate: each packet adds a quarter of the previous block size plus a
 * quarter of its own (the first adds nothing), the block size coming from the packet's mode.
 * Packet timestamps are ignored.
 * A page is completed when it holds {@code maxPacketsPerPage} packets, when its packets last
 * {@code maxPageDurationMicros}, or when its 255 lacing values are used up, in which case a
 * packet continues on the next page. Packets whose size is a multiple of 255 end with a zero
 * lacing value. Pages carry the Ogg CRC-32; the first page of every stream has the
 * beginning-of-stream flag and the last page, written by {@link #finish()}, the end-of-stream
 * flag.
 *
 * <p>End trimming: when a track declares a duration, the last page's granule position is the
 * pre-skip plus that duration converted to 48 kHz samples; otherwise, when the last packet's
 * {@link Packet#duration()} is shorter than its coded duration, the difference is trimmed. The
 * trimmed granule position never drops below the granule position of the previous page nor rises
 * above the samples actually coded. A stream without data packets ends with an empty
 * end-of-stream page.
 *
 * <p>Instances are not thread-safe.
 */
public final class OggMuxer implements Muxer {
    /**
     * The Opus time base, one 48 kHz sample.
     */
    private static final Rational OPUS_TIME_BASE = Rational.timeBaseOf(48_000);

    /**
     * The vendor string of rebuilt Vorbis comment headers.
     */
    private static final String VORBIS_VENDOR = "Smalt";

    /**
     * The index of a page's body in a page buffer: room for the fixed header and the largest
     * lacing table before it.
     */
    private static final int BODY_OFFSET = OggPageReader.HEADER_SIZE + 255;

    /**
     * The destination.
     */
    private final MediaSink output;

    /**
     * The configuration.
     */
    private final OggMuxerOptions options;

    /**
     * The streams, keyed by track identifier, in declaration order.
     */
    private final Map<Integer, StreamWriter> streams;

    /**
     * The page checksum calculator shared by every stream.
     */
    private final OggCrc crc;

    /**
     * The tags set by {@link #setTags(MediaTags)}, or {@code null}.
     */
    private MediaTags tags;

    /**
     * Whether the headers were written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Whether {@link #close()} ran.
     */
    private boolean closed;

    /**
     * Constructs a muxer writing to a sink with {@link OggMuxerOptions#defaults()}.
     *
     * @param output the destination, written from its position; not closed by this muxer
     */
    public OggMuxer(MediaSink output) {
        this(output, OggMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink.
     *
     * <p>Ogg pages are written strictly in order, so any sink works, a streaming one included.
     *
     * @param output the destination, written from its position; not closed by this muxer
     * @param options the configuration
     */
    public OggMuxer(MediaSink output, OggMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        this.streams = new LinkedHashMap<>();
        this.crc = new OggCrc();
    }

    /**
     * Declares an Opus or Vorbis track.
     *
     * @param track the track; an {@link AudioTrack} with {@link Codec#OPUS} or {@link Codec#VORBIS}
     * @throws IllegalArgumentException if the track is neither Opus nor Vorbis, its identifier is already used, or its
     * {@code OpusHead} or Vorbis headers are missing or invalid
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        checkOpen();
        if (started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (!(track instanceof AudioTrack audio) || (audio.codec() != Codec.OPUS && audio.codec() != Codec.VORBIS)) {
            throw new IllegalArgumentException("Ogg output supports only Opus and Vorbis audio, not " + track.codec().displayName());
        }
        if (streams.containsKey(track.id())) {
            throw new IllegalArgumentException("duplicate track id " + track.id());
        }
        if (audio.codec() == Codec.VORBIS) {
            var headers = unlace(audio.codecConfig().orElseThrow(
                    () -> new IllegalArgumentException("a Vorbis track needs its three headers")));
            var mapping = new VorbisMapping();
            try {
                for (var i = 0; i < 3; i++) {
                    mapping.header(i, headers[i], MediaLimits.DEFAULT, new LinkedHashMap<>());
                }
            } catch (MediaException exception) {
                throw new IllegalArgumentException("invalid Vorbis headers: " + exception.getMessage(), exception);
            }
            streams.put(track.id(), new StreamWriter(audio, headers, mapping));
            return;
        }
        var head = audio.codecConfig().map(byte[]::clone).orElseGet(() -> synthesizeHead(audio));
        try {
            OpusHeader.parse(head, MediaLimits.DEFAULT);
        } catch (MediaException exception) {
            throw new IllegalArgumentException("invalid OpusHead: " + exception.getMessage(), exception);
        }
        streams.put(track.id(), new StreamWriter(audio, head));
    }

    /**
     * Splits the three Xiph-laced Vorbis header packets of a codec configuration.
     *
     * @param config the laced packets
     * @return the three packets
     * @throws IllegalArgumentException if the lacing is malformed or does not hold three packets
     */
    private static byte[][] unlace(byte[] config) {
        var packets = XiphLacing.unlace(config);
        if (packets == null || packets.length != 3) {
            throw new IllegalArgumentException("Vorbis headers must be three Xiph-laced packets");
        }
        return packets;
    }

    /**
     * Builds an {@code OpusHead} for a track that declares none.
     *
     * @param track the Opus track without an {@code OpusHead}
     * @return the header
     * @throws IllegalArgumentException if the track has more than two channels or no layout
     */
    private static byte[] synthesizeHead(AudioTrack track) {
        var channels = track.layout().orElseThrow(() -> new IllegalArgumentException("Opus track without OpusHead or layout"))
                .channels();
        if (channels > 2) {
            throw new IllegalArgumentException("an OpusHead is required for " + channels + " channels");
        }
        var rate = track.sampleRate() > 0 ? track.sampleRate() : 48_000;
        var preSkip = Rational.timeBaseOf(rate).rescale(track.skipSamples(), OPUS_TIME_BASE);
        if (preSkip > 0xFFFF) {
            throw new IllegalArgumentException("pre-skip " + preSkip + " does not fit OpusHead");
        }
        var head = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1;
        head[9] = (byte) channels;
        head[10] = (byte) preSkip;
        head[11] = (byte) (preSkip >>> 8);
        for (var i = 0; i < 4; i++) {
            head[12 + i] = (byte) (rate >>> (8 * i));
        }
        return head;
    }

    /**
     * Returns whether Ogg files written by this muxer can carry a codec.
     *
     * @param codec the codec of the track
     * @return {@code true} for Opus and Vorbis
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case OPUS, VORBIS -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags become fields of the {@code OpusTags} header after the configured comments,
     * which they override for the same name: pictures as {@code METADATA_BLOCK_PICTURE} and
     * chapters as {@code CHAPTERxxx} fields. A Vorbis stream's comment header is rebuilt the same
     * way.
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.tags = tags;
    }

    /**
     * Writes one Opus or Vorbis packet.
     *
     * @param packet the next packet of its track
     * @throws IllegalArgumentException if the packet's track was not declared or the packet is not a valid Opus or
     * Vorbis audio packet
     * @throws IllegalStateException if the muxer is finished or no track was declared
     * @throws MediaException.Io if writing fails
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        checkOpen();
        var stream = streams.get(packet.trackId());
        if (stream == null) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        long samples;
        if (stream.vorbis != null) {
            var data = packet.data();
            if (data.length == 0 || (data[0] & 1) != 0) {
                throw new IllegalArgumentException("invalid Vorbis audio packet of " + packet.size() + " bytes");
            }
            samples = stream.vorbis.packetDuration(data);
            if (!stream.audioStarted) {
                stream.audioStarted = true;
                samples = 0;
            }
        } else {
            samples = AudioConfigs.opusPacketSamples(packet.data());
            if (samples <= 0) {
                throw new IllegalArgumentException("invalid Opus packet of " + packet.size() + " bytes");
            }
        }
        start();
        stream.add(packet, samples);
    }

    /**
     * Writes the end-of-stream page of every stream.
     *
     * @throws IllegalStateException if the muxer is already finished or no track was declared
     * @throws MediaException.Io if writing fails
     */
    @Override
    public void finish() {
        checkOpen();
        start();
        for (var stream : streams.values()) {
            stream.end();
        }
        finished = true;
    }

    /**
     * Releases the muxer; the output channel is not closed and an unfinished file stays
     * incomplete.
     */
    @Override
    public void close() {
        closed = true;
    }

    /**
     * Fails when the muxer can no longer accept calls.
     *
     * @throws IllegalStateException if the muxer is finished or closed
     */
    private void checkOpen() {
        if (finished || closed) {
            throw new IllegalStateException("the muxer is " + (closed ? "closed" : "finished"));
        }
    }

    /**
     * Writes the header pages of every stream once.
     *
     * @throws IllegalStateException if no track was declared
     */
    private void start() {
        if (started) {
            return;
        }
        if (streams.isEmpty()) {
            throw new IllegalStateException("no track declared");
        }
        started = true;
        var serial = options.serial().orElseGet(() -> ThreadLocalRandom.current().nextInt());
        for (var stream : streams.values()) {
            stream.serial = serial++;
            stream.writeHead();
        }
        for (var stream : streams.values()) {
            stream.writeTags();
        }
    }

    /**
     * Writes a range of bytes to the output.
     *
     * @param bytes the array holding the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws MediaException.Io if writing fails
     */
    private void write(byte[] bytes, int offset, int length) {
        output.write(bytes, offset, length);
    }

    /**
     * Builds the Vorbis comment header: the stream's own one, or one rebuilt from the tags.
     *
     * @param original the stream's comment header
     * @return the header packet
     */
    private byte[] vorbisTags(byte[] original) {
        if (tags == null) {
            return original;
        }
        var merged = tags.toBuilder();
        for (var entry : options.comments().entrySet()) {
            if (!tags.contains(entry.getKey())) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        var block = MuxerTags.vorbisComment(VORBIS_VENDOR, merged.build());
        var packet = new byte[7 + block.length + 1];
        packet[0] = 3;
        System.arraycopy("vorbis".getBytes(StandardCharsets.US_ASCII), 0, packet, 1, 6);
        System.arraycopy(block, 0, packet, 7, block.length);
        packet[packet.length - 1] = 1;
        return packet;
    }

    /**
     * Builds the {@code OpusTags} header.
     *
     * @return the header packet
     */
    private byte[] tags() {
        if (tags != null) {
            var merged = tags.toBuilder();
            for (var entry : options.comments().entrySet()) {
                if (!tags.contains(entry.getKey())) {
                    merged.add(entry.getKey(), entry.getValue());
                }
            }
            var block = MuxerTags.vorbisComment(options.vendor(), merged.build());
            var packet = new byte[8 + block.length];
            System.arraycopy("OpusTags".getBytes(StandardCharsets.US_ASCII), 0, packet, 0, 8);
            System.arraycopy(block, 0, packet, 8, block.length);
            return packet;
        }
        var vendor = options.vendor().getBytes(StandardCharsets.UTF_8);
        var comments = new byte[options.comments().size()][];
        var size = 8 + 4 + vendor.length + 4;
        var index = 0;
        for (var entry : options.comments().entrySet()) {
            comments[index] = (entry.getKey() + "=" + entry.getValue()).getBytes(StandardCharsets.UTF_8);
            size += 4 + comments[index].length;
            index++;
        }
        var packet = new byte[size];
        System.arraycopy("OpusTags".getBytes(StandardCharsets.US_ASCII), 0, packet, 0, 8);
        var offset = putLength(packet, 8, vendor.length);
        System.arraycopy(vendor, 0, packet, offset, vendor.length);
        offset = putLength(packet, offset + vendor.length, comments.length);
        for (var comment : comments) {
            offset = putLength(packet, offset, comment.length);
            System.arraycopy(comment, 0, packet, offset, comment.length);
            offset += comment.length;
        }
        return packet;
    }

    /**
     * Writes a little-endian 32-bit length.
     *
     * @param data the destination
     * @param offset the index of the first byte
     * @param value the length
     * @return the index after the length
     */
    private static int putLength(byte[] data, int offset, int value) {
        for (var i = 0; i < 4; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
        return offset + 4;
    }

    /**
     * Assembles the pages of one logical stream.
     */
    private final class StreamWriter {
        /**
         * The track written.
         */
        private final AudioTrack track;

        /**
         * The identification header.
         */
        private final byte[] head;

        /**
         * The pre-skip declared by the identification header, zero for Vorbis.
         */
        private final int preSkip;

        /**
         * The three Vorbis headers, or {@code null} for Opus.
         */
        private final byte[][] vorbisHeaders;

        /**
         * The Vorbis mapping that sizes the packets, or {@code null} for Opus.
         */
        private final VorbisMapping vorbis;

        /**
         * The unit of granule positions: 48 kHz for Opus, the sampling rate for Vorbis.
         */
        private final Rational timeBase;

        /**
         * Whether a Vorbis audio packet was added.
         */
        private boolean audioStarted;

        /**
         * The serial number.
         */
        private int serial;

        /**
         * The sequence number of the next page.
         */
        private int sequence;

        /**
         * The lacing values of the page being filled.
         */
        private final byte[] lacing = new byte[255];

        /**
         * The two page buffers: one holds the page being filled, the other the pending page.
         * A page's body starts at {@link #BODY_OFFSET}; its header and lacing values are placed
         * right before the body when the page is completed.
         */
        private final byte[][] pages = {new byte[BODY_OFFSET + 4096], new byte[BODY_OFFSET + 4096]};

        /**
         * The index in {@link #pages} of the buffer holding the page being filled.
         */
        private int current;

        /**
         * The number of lacing values used.
         */
        private int segments;

        /**
         * The number of body bytes used.
         */
        private int bodyLength;

        /**
         * Whether the page being filled starts with the continuation of a packet.
         */
        private boolean continued;

        /**
         * The number of packets completed on the page being filled.
         */
        private int pagePackets;

        /**
         * The duration of the packets completed on the page being filled, in granule units.
         */
        private long pageSamples;

        /**
         * The granule position after the last completed packet.
         */
        private long granule;

        /**
         * The granule position of the last page flushed, or {@code 0} before the first data page.
         */
        private long previousGranule;

        /**
         * The granule position of the page before {@link #pending}, used to bound end trimming.
         */
        private long granuleBeforePending;

        /**
         * The buffer of the last completed data page, held back so the end-of-stream flag can be
         * set on it, or {@code null}.
         */
        private byte[] pending;

        /**
         * The index of {@link #pending}'s first byte.
         */
        private int pendingStart;

        /**
         * The size of {@link #pending}'s page.
         */
        private int pendingLength;

        /**
         * The index of the first byte of the page {@link #buildPage} completed last.
         */
        private int builtStart;

        /**
         * The size of the page {@link #buildPage} completed last.
         */
        private int builtLength;

        /**
         * The number of granule units the last packet was declared shorter than coded.
         */
        private long lastPacketTrim;

        /**
         * Constructs the writer of a track.
         *
         * @param track the Opus track written
         * @param head the identification header
         */
        private StreamWriter(AudioTrack track, byte[] head) {
            this.track = track;
            this.head = head;
            this.preSkip = AudioConfigs.opusPreSkip(head);
            this.vorbisHeaders = null;
            this.vorbis = null;
            this.timeBase = OPUS_TIME_BASE;
        }

        /**
         * Constructs the writer of a Vorbis track.
         *
         * @param track the Vorbis track written
         * @param headers the three headers
         * @param mapping the mapping that parsed them
         */
        private StreamWriter(AudioTrack track, byte[][] headers, VorbisMapping mapping) {
            this.track = track;
            this.head = headers[0];
            this.preSkip = 0;
            this.vorbisHeaders = headers;
            this.vorbis = mapping;
            var rate = (head[12] & 0xFF) | (head[13] & 0xFF) << 8 | (head[14] & 0xFF) << 16 | (head[15] & 0xFF) << 24;
            this.timeBase = Rational.timeBaseOf(rate);
        }

        /**
         * Writes the beginning-of-stream page holding the identification header.
         */
        private void writeHead() {
            appendPacket(head);
            writeBuilt(buildPage(OggPageReader.FLAG_BOS, 0));
        }

        /**
         * Writes the comment header, finishing its last page.
         */
        private void writeTags() {
            if (vorbisHeaders != null) {
                writeContinued(vorbisTags(vorbisHeaders[1]), false);
                writeContinued(vorbisHeaders[2], true);
                return;
            }
            var tags = tags();
            var offset = 0;
            while (true) {
                offset = appendSegments(tags, offset);
                if (offset < 0) {
                    writeBuilt(buildPage(0, 0));
                    return;
                }
                writeBuilt(buildPage(0, -1));
            }
        }

        /**
         * Appends a header packet, completing every page it fills and, when asked, its last page.
         *
         * @param packet the header
         * @param finish whether to complete the page the header ends on
         */
        private void writeContinued(byte[] packet, boolean finish) {
            var offset = 0;
            while (true) {
                offset = appendSegments(packet, offset);
                if (offset < 0) {
                    if (finish) {
                        writeBuilt(buildPage(0, 0));
                    }
                    return;
                }
                writeBuilt(buildPage(0, -1));
            }
        }

        /**
         * Adds a data packet.
         *
         * @param packet the audio packet
         * @param samples its coded duration in granule units
         */
        private void add(Packet packet, long samples) {
            var data = packet.data();
            var offset = 0;
            while (true) {
                offset = appendSegments(data, offset);
                if (offset < 0) {
                    break;
                }
                flush(pagePackets > 0 ? granule : -1);
            }
            granule += samples;
            pagePackets++;
            pageSamples += samples;
            lastPacketTrim = 0;
            if (packet.duration() > 0) {
                var declared = track.timeBase().rescale(packet.duration(), timeBase);
                if (declared < samples) {
                    lastPacketTrim = samples - declared;
                }
            }
            if (pagePackets >= options.maxPacketsPerPage() || segments == 255
                    || timeBase.toMicros(pageSamples) >= options.maxPageDurationMicros()) {
                flush(granule);
            }
        }

        /**
         * Appends as many lacing values of a packet as the current page holds.
         *
         * @param data the packet
         * @param offset the index of the first byte not yet laced
         * @return {@code -1} when the packet is complete, otherwise the index where the next page must continue
         */
        private int appendSegments(byte[] data, int offset) {
            while (segments < 255) {
                var size = Math.min(255, data.length - offset);
                lacing[segments++] = (byte) size;
                ensureBody(size);
                System.arraycopy(data, offset, pages[current], BODY_OFFSET + bodyLength, size);
                bodyLength += size;
                offset += size;
                if (size < 255) {
                    return -1;
                }
            }
            return offset;
        }

        /**
         * Appends a whole packet to an empty page.
         *
         * @param data the packet, shorter than 65025 bytes
         */
        private void appendPacket(byte[] data) {
            if (appendSegments(data, 0) >= 0) {
                throw new IllegalArgumentException("header packet does not fit one page");
            }
        }

        /**
         * Grows the page body.
         *
         * @param extra the number of bytes about to be added
         */
        private void ensureBody(int extra) {
            var page = pages[current];
            if (BODY_OFFSET + bodyLength + extra > page.length) {
                pages[current] = Arrays.copyOf(page, Math.max(page.length * 2, BODY_OFFSET + bodyLength + extra));
            }
        }

        /**
         * Writes the page {@link #buildPage} completed last.
         *
         * @param page the buffer holding it
         */
        private void writeBuilt(byte[] page) {
            write(page, builtStart, builtLength);
        }

        /**
         * Completes the data page being filled and holds it back as the pending page.
         *
         * @param pageGranule the granule position of the page, {@code -1} when no packet completes on it
         */
        private void flush(long pageGranule) {
            var page = buildPage(0, pageGranule);
            if (pending != null) {
                write(pending, pendingStart, pendingLength);
                granuleBeforePending = previousGranule;
            }
            pending = page;
            pendingStart = builtStart;
            pendingLength = builtLength;
            current ^= 1;
            if (pageGranule != -1) {
                previousGranule = pageGranule;
            }
            pagePackets = 0;
            pageSamples = 0;
        }

        /**
         * Writes the final page with the end-of-stream flag and the trimmed granule position.
         */
        private void end() {
            if (segments > 0 || pagePackets > 0) {
                flush(granule);
            }
            if (pending == null) {
                writeBuilt(buildPage(OggPageReader.FLAG_EOS, granule == 0 ? -1 : granule));
                return;
            }
            var target = granule;
            if (track.duration() >= 0) {
                target = preSkip + track.timeBase().rescale(track.duration(), timeBase);
            } else if (lastPacketTrim > 0) {
                target = granule - lastPacketTrim;
            }
            var finalGranule = Math.max(granuleBeforePending, Math.min(granule, target));
            pending[pendingStart + 5] |= OggPageReader.FLAG_EOS;
            for (var i = 0; i < 8; i++) {
                pending[pendingStart + 6 + i] = (byte) (finalGranule >>> (8 * i));
            }
            seal(pending, pendingStart, pendingLength);
            write(pending, pendingStart, pendingLength);
            pending = null;
        }

        /**
         * Completes the page being filled in place and resets the fill state.
         *
         * <p>The header and the lacing values are written right before the body, so the page is
         * the contiguous range {@link #builtStart} to {@link #builtStart} plus
         * {@link #builtLength} of the returned buffer; no byte of the body moves.
         *
         * @param flags the header-type flags besides continuation
         * @param pageGranule the granule position
         * @return the buffer holding the page
         */
        private byte[] buildPage(int flags, long pageGranule) {
            var page = pages[current];
            var start = BODY_OFFSET - OggPageReader.HEADER_SIZE - segments;
            page[start] = 'O';
            page[start + 1] = 'g';
            page[start + 2] = 'g';
            page[start + 3] = 'S';
            page[start + 4] = 0;
            page[start + 5] = (byte) (flags | (continued ? OggPageReader.FLAG_CONTINUED : 0));
            for (var i = 0; i < 8; i++) {
                page[start + 6 + i] = (byte) (pageGranule >>> (8 * i));
            }
            for (var i = 0; i < 4; i++) {
                page[start + 14 + i] = (byte) (serial >>> (8 * i));
                page[start + 18 + i] = (byte) (sequence >>> (8 * i));
            }
            page[start + 26] = (byte) segments;
            System.arraycopy(lacing, 0, page, start + OggPageReader.HEADER_SIZE, segments);
            builtStart = start;
            builtLength = OggPageReader.HEADER_SIZE + segments + bodyLength;
            seal(page, start, builtLength);
            continued = segments > 0 && lacing[segments - 1] == (byte) 255;
            sequence++;
            segments = 0;
            bodyLength = 0;
            return page;
        }

        /**
         * Computes and stores the CRC of a page.
         *
         * @param page the buffer holding the page, with any previous checksum
         * @param start the index of the page's first byte
         * @param length the page size
         */
        private void seal(byte[] page, int start, int length) {
            var checksum = crc.page(page, start, length);
            for (var i = 0; i < 4; i++) {
                page[start + 22 + i] = (byte) (checksum >>> (8 * i));
            }
        }
    }
}
