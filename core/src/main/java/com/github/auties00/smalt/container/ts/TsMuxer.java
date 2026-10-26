package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.container.SegmentSink;
import com.github.auties00.smalt.container.internal.BitmapTrackConfigs;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.DolbyVisionConfigs;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.tags.TsDescriptors;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Writes MPEG-2 transport streams (ISO/IEC 13818-1) with one program, as a single stream or as
 * the key-frame-aligned segments of HLS.
 *
 * <p>Supported codecs, with their {@code stream_type}, PES {@code stream_id} and descriptors:
 * <ul>
 *   <li>H.264 ({@code 0x1B}) and HEVC ({@code 0x24}), stream ids from {@code 0xE0}: each packet
 *       becomes an Annex B access unit that starts with an access unit delimiter (which ISO/IEC
 *       13818-1 clause 2.14 requires), and every key frame that carries no parameter set gets
 *       those of the track's {@code avcC} or {@code hvcC} record, so decoding can start at any
 *       key frame;</li>
 *   <li>AV1 ({@code 0x06}, id {@code 0xBD}, the {@code "AV01"} registration and, when the track
 *       has an {@code av1C} record, the AV1 video descriptor built from it, as the AOM "Carriage of
 *       AV1 in MPEG-2 TS" specifies): each temporal unit becomes an access unit that opens with a
 *       temporal delimiter, whose OBUs (with their size fields) each get a start code and emulation
 *       prevention bytes; a key frame without a sequence header gets the one of the track's
 *       {@code av1C} record. The descriptor's {@code hdr_wcg_idc} follows the track's color
 *       description;</li>
 *   <li>an H.264, HEVC or AV1 track with a
 *       {@link VideoTrack#dolbyVision() Dolby Vision configuration} also gets the {@code "DOVI"} registration and
 *       the DOVI video stream descriptor (tag {@code 0xB0}) of its configuration, as Dolby's "Dolby Vision
 *       Streams Within the MPEG-2 Transport Stream Format" specifies; a track without a base layer names the PID
 *       of the first track of the same codec added before it whose Dolby Vision configuration declares a base
 *       layer, else of the first such track without a Dolby Vision configuration, or {@code 0x1FFF} when there
 *       is none;</li>
 *   <li>MPEG-4 Part 2 ({@code 0x10}, ids from {@code 0xE0}): frames as they are, a key frame that
 *       does not start with the visual object sequence, visual object or video object layer
 *       headers getting those of the track's configuration;</li>
 *   <li>AAC ({@code 0x0F}, ids from {@code 0xC0}): every packet gets an ADTS header built from the
 *       {@code AudioSpecificConfig} (HE-AAC is signaled implicitly, by its core);</li>
 *   <li>MP3 and MP2 ({@code 0x03}, or {@code 0x04} below 32 kHz, ids from {@code 0xC0}): frames as
 *       they are;</li>
 *   <li>AC-3 ({@code 0x81}) and E-AC-3 ({@code 0x87}), id {@code 0xBD}, with the {@code "AC-3"} or
 *       {@code "EAC3"} registration descriptor: sync frames as they are;</li>
 *   <li>Opus ({@code 0x06}, id {@code 0xBD}, the {@code "Opus"} registration and the DVB extension
 *       descriptor with its {@code channel_config_code}): every packet gets an
 *       {@code opus_control_header}; the first carries the {@code OpusHead} pre-skip as its start
 *       trim, and a packet whose duration is shorter than its coded duration carries the
 *       difference as its end trim. Up to eight channels with the Vorbis channel order are
 *       supported;</li>
 *   <li>16-bit and 24-bit PCM, id {@code 0xBD}: SMPTE ST 302 AES3 data ({@code 0x06} with the
 *       {@code "BSSD"} registration) in a plain transport stream at 48 kHz with two, four, six or
 *       eight channels, and Blu-ray linear PCM ({@code 0x80}, with the {@code "HDMV"} registration
 *       for the program) otherwise and in M2TS, at 48, 96 or 192 kHz with one to eight channels.
 *       A 24-bit track that declares 20 {@linkplain AudioTrack#bitsPerSample() bits per sample}
 *       is written as 20-bit words. Channels are taken in the order of the track's layout; a
 *       Blu-ray channel assignment is chosen by the channel count (and, for three and four
 *       channels, by whether the layout has a back center or a front center). A PCM packet is cut
 *       into PES packets of at most 5 ms (Blu-ray) or 40 ms (ST 302);</li>
 *   <li>bitmap subtitles and teletext, id {@code 0xBD}: PGS ({@code 0x90}, with the {@code "HDMV"} registration for
 *       the program, as Blu-ray defines the type), one PES packet per packet; DVB
 *       subtitles ({@code 0x06} with the subtitling descriptor of the track's configuration, or one for page 1),
 *       each packet's segments framed by the {@code data_identifier}, {@code subtitle_stream_id} and end marker of
 *       the PES data field; DVB teletext ({@code 0x06} with the teletext descriptor of the track's configuration,
 *       or one for subtitle page 888), each packet a PES data field, written with the fixed 45-byte PES header and
 *       the stuffing data units that fill the last transport packet, as ETSI EN 300 472 requires;</li>
 *   <li>data tracks, id {@code 0xBD}: a track whose handler is {@code "id3"} becomes ID3 timed
 *       metadata ({@code 0x15} with the metadata pointer and metadata descriptors of the HLS
 *       timed metadata convention), one whose handler is {@code "scte35"} SCTE 35 splice
 *       information ({@code 0x86}, its packets whole {@code splice_info_section}s written as
 *       sections, with the {@code "CUEI"} registration for the program and a cue identifier
 *       descriptor for the stream; a section's splice times plus its {@code pts_adjustment} are
 *       times of the track timeline, so the {@code pts_adjustment} is moved by the offset added to
 *       every timestamp and the {@code CRC_32} recomputed), any other a private PES stream ({@code 0x06}). SCTE 35 and
 *       Blu-ray linear PCM cannot share a program, where {@code 0x86} is DTS-HD audio.</li>
 * </ul>
 * A packet that changes the codec configuration of an H.264, HEVC, AV1 or MPEG-4 Part 2 track
 * replaces the headers key frames get from then on, and one that changes the {@code AudioSpecificConfig} of an AAC
 * track the ADTS header of the following packets; a change on any other track is rejected.
 * Every track gets the language descriptor of its language. The track tags of
 * {@link #setTags(MediaTags)} give an audio track the {@code audio_type} of that descriptor
 * ({@link TrackDisposition#CLEAN_EFFECTS} 1, {@link TrackDisposition#HEARING_IMPAIRED} 2,
 * {@link TrackDisposition#VISUAL_IMPAIRED} 3, the first of them in that order, with the language
 * {@code und} when the track has none) and, for its name, an ATSC A/65 component name descriptor.
 * Audio frames are gathered into PES
 * packets of up to 2930 payload bytes and 400 ms (100 ms for the stream that carries the clock
 * reference); video and data packets each make a PES packet of their own.
 *
 * <p>Timing. A video track whose packets carry presentation timestamps only (H.264 from
 * Matroska) gets decoding times from its sorted presentation times, delayed by the reordering of
 * its first packets. Packets of every track are interleaved by decoding time: a packet is written once
 * every video and audio track has a later one queued, or once it is more than a second older than
 * the newest packet. Timestamps become 90 kHz PES timestamps, shifted so that the first decoding
 * time (or the {@linkplain TsMuxerOptions#timestampOriginMicros() timestamp origin}) equals
 * {@link TsMuxerOptions#delayMicros()}, modulo 2^33. The program clock reference rides
 * on the first video track (the first track otherwise): at a variable rate it is the decoding time
 * of the PES packet being written less the delay, with adaptation-field-only packets filling
 * longer gaps than {@link TsMuxerOptions#pcrIntervalMicros()}; at a constant rate it is the
 * position in the stream, and null packets hold every PES packet back until the clock reaches its
 * decoding time less the delay. The program association and map tables open the stream and every
 * segment and repeat every {@link TsMuxerOptions#psiIntervalMicros()}. M2TS packets carry an
 * arrival time stamp equal to the clock at the packet.
 *
 * <p>Outputs. With a {@link MediaSink} the stream is written in order and the sink is never
 * repositioned, so a streaming sink works. With a {@link SegmentSink} the stream is cut before
 * the first key frame of the reference track (the first video track, or the first track) once the
 * current segment lasts {@link TsMuxerOptions#segmentMicros()}; each segment is held in memory
 * and handed to a sink of its own, which the muxer closes. Instances are not thread-safe.
 *
 * @implNote This implementation stamps the clock reference from decoding times rather than
 * modeling the transport stream system target decoder buffers, which players and segmenters do
 * not need; at a constant rate a stream whose content exceeds the rate gets a clock that lags its
 * decoding times rather than an error.
 */
public final class TsMuxer implements Muxer {
    /**
     * The size of a transport packet.
     */
    private static final int PACKET = 188;

    /**
     * The largest {@code section_length} of a PSI section (ISO/IEC 13818-1 clause 2.4.4).
     */
    private static final int MAX_SECTION_LENGTH = 1021;

    /**
     * The payload capacity of a transport packet without an adaptation field.
     */
    private static final int PAYLOAD = 184;

    /**
     * The {@code PES_header_data_length} of a teletext PES packet, which makes its header 45 bytes long.
     */
    private static final int TELETEXT_HEADER_LENGTH = 0x24;

    /**
     * The 90 kHz time base of PES timestamps.
     */
    private static final Rational TICKS = Rational.timeBaseOf(90_000);

    /**
     * The mask of a 33-bit timestamp.
     */
    private static final long WRAP_MASK = (1L << 33) - 1;

    /**
     * The largest audio PES payload, the value most writers use.
     */
    private static final int MAX_AUDIO_PES = 2930;

    /**
     * The longest stretch of audio gathered into one PES packet, in 90 kHz ticks.
     */
    private static final long MAX_AUDIO_TICKS = 36_000;

    /**
     * The largest decoding-time gap between the oldest queued packet and the newest one before
     * the oldest is written without waiting for the other tracks.
     */
    private static final long MAX_INTERLEAVE_MICROS = 1_000_000;

    /**
     * The largest number of queued packet bytes before the oldest packet is written without
     * waiting for the other tracks.
     */
    private static final long MAX_QUEUED_BYTES = 64L << 20;

    /**
     * The number of buffered bytes after which a single-stream output is flushed to its sink.
     */
    private static final int FLUSH_BYTES = 256 * 1024;

    /**
     * The number of packets of a track with presentation timestamps only held back to give them
     * decoding times; it bounds the reordering depth handled.
     */
    private static final int REORDER_WINDOW = 16;

    /**
     * The PID of the program association table.
     */
    private static final int PAT_PID = 0;

    /**
     * The PID of null packets.
     */
    private static final int NULL_PID = 0x1FFF;

    /**
     * An H.264 access unit delimiter allowing every slice type, start code included.
     */
    private static final byte[] AVC_DELIMITER = {0, 0, 0, 1, 0x09, (byte) 0xF0};

    /**
     * An HEVC access unit delimiter allowing every slice type, start code included.
     */
    private static final byte[] HEVC_DELIMITER = {0, 0, 0, 1, 0x46, 0x01, 0x50};

    /**
     * The single-stream output, or {@code null}.
     */
    private final MediaSink output;

    /**
     * The segmented output, or {@code null}.
     */
    private final SegmentSink segments;

    /**
     * The configuration.
     */
    private final TsMuxerOptions options;

    /**
     * The declared streams, in declaration order.
     */
    private final List<Stream> streams = new ArrayList<>();

    /**
     * The bytes written but not yet handed to the sink: the current segment, or a block of the
     * single stream.
     */
    private final ByteWriter buffer = new ByteWriter(FLUSH_BYTES + PACKET * 4);

    /**
     * The scratch bytes of one transport packet.
     */
    private final byte[] packet = new byte[PACKET];

    /**
     * The stream carrying the program clock reference.
     */
    private Stream pcrStream;

    /**
     * The stream whose key frames delimit segments.
     */
    private Stream reference;

    /**
     * The value added to 90 kHz timestamps to make PES timestamps, or {@link Long#MIN_VALUE}
     * before the first packet.
     */
    private long shift = Long.MIN_VALUE;

    /**
     * The last program clock reference written, in 27 MHz ticks, or {@code -1}.
     */
    private long lastPcr = -1;

    /**
     * The clock value stamped on packets written now at a variable rate, in 27 MHz ticks.
     */
    private long currentPcr;

    /**
     * The clock value of the first packet at a constant rate, in 27 MHz ticks: zero, since the
     * first decoding time equals the multiplexing delay.
     */
    private final long pcrBase = 0;

    /**
     * The number of transport packets written.
     */
    private long packetsWritten;

    /**
     * The last arrival time stamp of an M2TS packet, or {@code -1}.
     */
    private long lastArrival = -1;

    /**
     * The decoding time of the last program table repetition, in 90 kHz PES ticks.
     */
    private long lastPsi = Long.MIN_VALUE;

    /**
     * Whether the program tables must be written before the next packet: at the start of the
     * stream and of every segment.
     */
    private boolean tablesDue;

    /**
     * The continuity counter of the program association table.
     */
    private int patCounter;

    /**
     * The continuity counter of the program map table.
     */
    private int pmtCounter;

    /**
     * The decoding time of the newest queued packet, in microseconds.
     */
    private long newestMicros = Long.MIN_VALUE;

    /**
     * The number of queued packet bytes.
     */
    private long queuedBytes;

    /**
     * The number of the current segment.
     */
    private int sequence = 1;

    /**
     * The start of the current segment on the reference track, in microseconds, or
     * {@link Long#MIN_VALUE}.
     */
    private long segmentStart = Long.MIN_VALUE;

    /**
     * The end of the reference track's packets written so far, in microseconds.
     */
    private long segmentEnd = Long.MIN_VALUE;

    /**
     * Whether the current segment starts with a key frame of the reference track.
     */
    private boolean segmentIndependent;

    /**
     * Whether a packet was accepted.
     */
    private boolean started;

    /**
     * The tags of {@link #setTags(MediaTags)}, whose track tags go in the program map table.
     */
    private MediaTags tags = MediaTags.empty();

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing a transport stream to a sink with {@link TsMuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position; not closed by this muxer
     */
    public TsMuxer(MediaSink output) {
        this(output, TsMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing a transport stream to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the configuration; its segment duration is ignored
     */
    public TsMuxer(MediaSink output, TsMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.segments = null;
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Constructs a muxer writing key-frame-aligned segments to sinks of their own.
     *
     * @param segments the provider of the segment sinks
     * @param options the configuration
     */
    public TsMuxer(SegmentSink segments, TsMuxerOptions options) {
        this.output = null;
        this.segments = Objects.requireNonNull(segments, "segments");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns whether transport streams written by this muxer can carry a codec.
     *
     * @param codec the codec of a track to add
     * @return {@code true} for H.264, HEVC, AV1, MPEG-4 Part 2, AAC, MP3, MP2, AC-3, E-AC-3, Opus, 16-bit and
     * 24-bit PCM, PGS and DVB subtitles, DVB teletext, and data
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case H264, HEVC, AV1, MPEG4_PART2, AAC, MP3, MP2, AC3, EAC3, OPUS, PCM_S16LE, PCM_S16BE, PCM_S24LE,
                 PCM_S24BE, PGS, DVB_SUBTITLE, DVB_TELETEXT, DATA -> true;
            default -> false;
        };
    }

    /**
     * Returns whether transport streams written by this muxer can carry a track's codec and audio format.
     *
     * <p>A PCM track is carried when it has a layout of one to eight channels at 48, 96 or 192 kHz; any other
     * track when {@link #supports(Codec)} accepts its codec. A track this method accepts may still be refused by
     * {@link #addTrack} for a configuration that is missing or does not parse.
     *
     * @param track the track to add
     * @return {@code true} when the track's codec and, for PCM, its rate and channel count can be carried
     */
    public static boolean supports(Track track) {
        var codec = Objects.requireNonNull(track, "track").codec();
        if (!supports(codec)) {
            return false;
        }
        if (!codec.isPcm()) {
            return true;
        }
        if (!(track instanceof AudioTrack audio)) {
            return false;
        }
        var channels = audio.layout().map(layout -> layout.channels()).orElse(0);
        var rate = audio.sampleRate();
        return channels >= 1 && channels <= 8 && (rate == 48_000 || rate == 96_000 || rate == 192_000);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the id is already used, the codec is not supported, the
     * configuration an H.264, HEVC, MPEG-4 Part 2, AAC or Opus track needs is missing or cannot be
     * expressed in a transport stream, the configuration of an AV1 track is not an {@code av1C} record, a PCM
     * track has a rate, channel count or layout no PCM carriage of a transport stream supports, or Blu-ray
     * linear PCM or PGS subtitles and SCTE 35 would share the program
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (!supports(track.codec())) {
            throw new IllegalArgumentException("transport streams written here cannot carry " + track.codec().displayName());
        }
        for (var stream : streams) {
            if (stream.track.id() == track.id()) {
                throw new IllegalArgumentException("track id " + track.id() + " is already declared");
            }
        }
        if (streams.size() >= 0x1FFE - options.firstPid()) {
            throw new IllegalArgumentException("too many tracks for the PID range");
        }
        var stream = new Stream(track, options.firstPid() + streams.size());
        configure(stream);
        var hdmv = blurayType(stream) || streams.stream().anyMatch(TsMuxer::blurayType);
        var cue = stream.splice || streams.stream().anyMatch(other -> other.splice);
        if (hdmv && cue) {
            throw new IllegalArgumentException("Blu-ray linear PCM and PGS subtitles make stream type 0x86 DTS-HD audio,"
                    + " not SCTE 35");
        }
        streams.add(stream);
        if (pcrStream == null || (track.kind() == TrackKind.VIDEO && pcrStream.track.kind() != TrackKind.VIDEO)) {
            pcrStream = stream;
            reference = stream;
        }
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
     * <p>A transport stream stores the track tags only: the name of a track becomes an ATSC component name
     * descriptor and the {@link TrackDisposition#CLEAN_EFFECTS}, {@link TrackDisposition#HEARING_IMPAIRED} or
     * {@link TrackDisposition#VISUAL_IMPAIRED} disposition of an audio track the {@code audio_type} of its language
     * descriptor. The
     * other tags, pictures and chapters are not stored. The program map table of the tracks declared before is built
     * here, so names it cannot hold are rejected before any packet is written.
     *
     * @throws MediaException.LimitExceeded if the program map table of the declared tracks outgrows the 1021 bytes
     * a PSI section holds
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        var previous = this.tags;
        this.tags = tags;
        try {
            checkSection(programMapBody().length);
        } catch (MediaException.LimitExceeded exception) {
            this.tags = previous;
            throw exception;
        }
    }

    /**
     * Fills in the stream type, stream id, descriptors and codec state of a stream.
     *
     * @param stream the stream to configure from its track
     * @throws IllegalArgumentException if the track's configuration is missing or unusable
     */
    private void configure(Stream stream) {
        var track = stream.track;
        var config = track.codecConfig().orElse(null);
        var video = 0;
        var audio = 0;
        for (var other : streams) {
            if (other.streamId >= 0xE0 && other.streamId <= 0xEF) {
                video++;
            } else if (other.streamId >= 0xC0 && other.streamId <= 0xDF) {
                audio++;
            }
        }
        var descriptors = new ByteWriter(32);
        switch (track.codec()) {
            case AV1 -> {
                if (config != null && (config.length < 4 || (config[0] & 0x80) == 0)) {
                    throw new IllegalArgumentException("the configuration of an AV1 track must be an av1C record");
                }
                stream.streamType = 0x06;
                stream.streamId = 0xBD;
                registration(descriptors, "AV01");
                if (config != null) {
                    configureAv1(stream, config);
                    descriptors.u8(0x80);
                    descriptors.u8(4);
                    descriptors.u8(config[0] & 0xFF);
                    descriptors.u8(config[1] & 0xFF);
                    descriptors.u8(config[2] & 0xFF);
                    descriptors.u8(hdrWcg(track, config) << 6 | (config[3] & 0x1F));
                }
            }
            case MPEG4_PART2 -> {
                if (config == null || config.length == 0) {
                    throw new IllegalArgumentException("an MPEG-4 Part 2 track needs its decoder configuration");
                }
                stream.parameterSets = config;
                stream.streamType = 0x10;
                stream.streamId = 0xE0 + Math.min(video, 15);
            }
            case PCM_S16LE, PCM_S16BE, PCM_S24LE, PCM_S24BE -> {
                configurePcm(stream);
                stream.streamId = 0xBD;
                if (stream.lpcm) {
                    stream.streamType = 0x80;
                } else {
                    stream.streamType = 0x06;
                    registration(descriptors, "BSSD");
                }
            }
            case H264, HEVC -> {
                var hevc = track.codec() == Codec.HEVC;
                var parsed = hevc ? NalConfigs.parseHevc(config) : NalConfigs.parseAvc(config);
                if (parsed == null) {
                    throw new IllegalArgumentException("an " + track.codec().displayName() + " track needs its "
                            + (hevc ? "hvcC" : "avcC") + " configuration");
                }
                stream.lengthSize = parsed.lengthSize();
                stream.parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
                stream.hevc = hevc;
                stream.streamType = hevc ? 0x24 : 0x1B;
                stream.streamId = 0xE0 + Math.min(video, 15);
            }
            case AAC -> {
                configureAac(stream, config);
                stream.streamType = 0x0F;
                stream.streamId = 0xC0 + Math.min(audio, 31);
            }
            case MP3, MP2 -> {
                var rate = track instanceof AudioTrack audioTrack ? audioTrack.sampleRate() : 0;
                stream.streamType = rate > 0 && rate < 32_000 ? 0x04 : 0x03;
                stream.streamId = 0xC0 + Math.min(audio, 31);
            }
            case AC3, EAC3 -> {
                stream.streamType = track.codec() == Codec.AC3 ? 0x81 : 0x87;
                stream.streamId = 0xBD;
                registration(descriptors, track.codec() == Codec.AC3 ? "AC-3" : "EAC3");
            }
            case OPUS -> {
                configureOpus(stream, config);
                stream.streamType = 0x06;
                stream.streamId = 0xBD;
                registration(descriptors, "Opus");
                descriptors.u8(0x7F);
                descriptors.u8(2);
                descriptors.u8(0x80);
                descriptors.u8(stream.opusCode);
            }
            case PGS -> {
                stream.streamType = 0x90;
                stream.streamId = 0xBD;
            }
            case DVB_SUBTITLE -> {
                stream.streamType = 0x06;
                stream.streamId = 0xBD;
                stream.dvbSubtitle = true;
                var body = BitmapTrackConfigs.dvbDescriptor(config, track.language().orElse(null));
                descriptors.u8(0x59);
                descriptors.u8(body.length);
                descriptors.bytes(body);
            }
            case DVB_TELETEXT -> {
                stream.streamType = 0x06;
                stream.streamId = 0xBD;
                stream.teletext = true;
                var body = config != null && config.length >= 5 && config.length <= 250
                        ? Arrays.copyOf(config, config.length - config.length % 5) : defaultTeletext(track);
                descriptors.u8(0x56);
                descriptors.u8(body.length);
                descriptors.bytes(body);
            }
            case DATA -> {
                stream.streamId = 0xBD;
                var handler = track instanceof DataTrack data ? data.handler().orElse("") : "";
                if (handler.toLowerCase(Locale.ROOT).equals(Scte35Parser.HANDLER)) {
                    stream.streamType = 0x86;
                    stream.splice = true;
                    descriptors.u8(0x8A);
                    descriptors.u8(1);
                    descriptors.u8(0x01);
                } else if (handler.toLowerCase(Locale.ROOT).equals(PesParser.ID3_HANDLER)) {
                    stream.streamType = 0x15;
                    stream.id3 = true;
                    descriptors.u8(0x26);
                    descriptors.u8(13);
                    descriptors.u16be(0xFFFF);
                    descriptors.fourCc("ID3 ");
                    descriptors.u8(0xFF);
                    descriptors.fourCc("ID3 ");
                    descriptors.u8(0);
                    descriptors.u8(0x0F);
                } else {
                    stream.streamType = 0x06;
                }
            }
            default -> throw new IllegalArgumentException("transport streams written here cannot carry "
                    + track.codec().displayName());
        }
        if (track instanceof VideoTrack videoTrack && videoTrack.dolbyVision().isPresent()
                && (track.codec() == Codec.H264 || track.codec() == Codec.HEVC || track.codec() == Codec.AV1)) {
            var configuration = videoTrack.dolbyVision().get();
            var dependency = DolbyVisionConfigs.NO_PID;
            var plain = DolbyVisionConfigs.NO_PID;
            for (var other : streams) {
                if (!(other.track instanceof VideoTrack base) || base.codec() != track.codec()) {
                    continue;
                }
                var declared = base.dolbyVision().orElse(null);
                if (declared != null && declared.baseLayerPresent()) {
                    dependency = other.pid;
                    break;
                }
                if (declared == null && plain == DolbyVisionConfigs.NO_PID) {
                    plain = other.pid;
                }
            }
            if (dependency == DolbyVisionConfigs.NO_PID) {
                dependency = plain;
            }
            var body = DolbyVisionConfigs.descriptor(configuration, dependency);
            registration(descriptors, "DOVI");
            descriptors.u8(DolbyVisionConfigs.DESCRIPTOR_TAG);
            descriptors.u8(body.length);
            descriptors.bytes(body);
        }
        var key = options.sampleAesKey().orElse(null);
        if (key != null) {
            var encrypted = SampleAesEncryptor.streamType(track.codec());
            if (encrypted < 0) {
                throw new IllegalArgumentException("SAMPLE-AES transport streams carry H.264, AAC, AC-3 and E-AC-3, not "
                        + track.codec().displayName());
            }
            stream.streamType = encrypted;
            descriptors = new ByteWriter(64);
            var setup = config;
            if (setup == null && (track.codec() == Codec.AC3 || track.codec() == Codec.EAC3)) {
                var audioTrack = (AudioTrack) track;
                setup = Ac3Configs.declared(track.codec() == Codec.EAC3, audioTrack.sampleRate(),
                        audioTrack.layout().orElse(null));
            }
            SampleAesEncryptor.descriptors(descriptors, track.codec(), setup);
            stream.sampleAes = new SampleAesEncryptor(key, options.sampleAesInitializationVector().orElseThrow());
        }
        stream.descriptors = descriptors.toByteArray();
    }

    /**
     * Returns the descriptor loop of the program map entry of a stream: the descriptors of its codec, the ISO 639
     * language descriptor of its language and the {@code audio_type} its dispositions give, and the ATSC component
     * name descriptor of its name.
     *
     * @param stream the stream whose program map entry is written
     * @return the descriptors
     */
    private byte[] entryDescriptors(Stream stream) {
        var track = stream.track;
        var described = tags.track(track.id()).orElse(null);
        var out = new ByteWriter(stream.descriptors.length + 64);
        out.bytes(stream.descriptors);
        var language = track.language().filter(TsDescriptors::isLanguageCode).orElse(null);
        var audioType = described == null || track.kind() != TrackKind.AUDIO ? 0
                : TsDescriptors.audioType(described.disposition());
        if (language != null || audioType != 0) {
            out.u8(TsDescriptors.LANGUAGE);
            out.u8(4);
            out.bytes((language != null ? language : TsDescriptors.UNDETERMINED).getBytes(StandardCharsets.US_ASCII));
            out.u8(audioType);
        }
        var name = described == null ? "" : described.name().orElse("");
        if (!name.isEmpty()) {
            out.bytes(TsDescriptors.componentName(language != null ? language : TsDescriptors.UNDETERMINED, name));
        }
        return out.toByteArray();
    }

    /**
     * Writes a registration descriptor (ISO/IEC 13818-1 clause 2.6.8).
     *
     * @param descriptors the descriptor loop being written
     * @param identifier the four-character {@code format_identifier}
     */
    private static void registration(ByteWriter descriptors, String identifier) {
        descriptors.u8(0x05);
        descriptors.u8(4);
        descriptors.fourCc(identifier);
    }

    /**
     * Derives the ADTS header fields of an AAC track from its {@code AudioSpecificConfig}.
     *
     * @param stream the AAC stream
     * @param config the configuration, or {@code null} when the track has none
     * @throws IllegalArgumentException if the configuration is missing or cannot be expressed in
     * ADTS
     */
    private static void configureAac(Stream stream, byte[] config) {
        if (config == null || config.length < 2) {
            throw new IllegalArgumentException("an AAC track needs its AudioSpecificConfig");
        }
        stream.adts = AdtsHeader.fromAudioSpecificConfig(config);
    }

    /**
     * Keeps the sequence header OBU of an AV1 track's {@code av1C} record, which key frames without one get.
     *
     * @param stream the AV1 stream
     * @param config the record
     */
    private static void configureAv1(Stream stream, byte[] config) {
        stream.parameterSets = Arrays.copyOfRange(config, 4, config.length);
    }

    /**
     * Returns the {@code hdr_wcg_idc} of the AV1 video descriptor (AOM "Carriage of AV1 in MPEG-2 TS" section
     * 2.2.2) of a track: 2 for a PQ or HLG transfer, 1 for BT.2020 primaries with another transfer, 0 for
     * BT.709 primaries, and 3 (no indication) when the color description is unknown.
     *
     * @param track the AV1 track
     * @param config its {@code av1C} record
     * @return the value
     */
    private static int hdrWcg(Track track, byte[] config) {
        ColorInfo color = track instanceof VideoTrack video ? video.color().orElse(null) : null;
        if (color == null) {
            var info = VideoConfigs.describe(Codec.AV1, config);
            color = info != null ? info.color() : null;
        }
        if (color == null) {
            return 3;
        }
        var transfer = color.transfer();
        if (transfer == TransferCharacteristics.PQ || transfer == TransferCharacteristics.HLG) {
            return 2;
        }
        return switch (color.primaries()) {
            case BT2020 -> 1;
            case BT709 -> 0;
            default -> 3;
        };
    }

    /**
     * Chooses the PCM carriage of a track: SMPTE ST 302 in a plain transport stream when the track is at
     * 48 kHz with an even number of channels up to eight, Blu-ray linear PCM otherwise and in M2TS, and the
     * channel assignment and word size of each.
     *
     * @param stream the PCM stream
     * @throws IllegalArgumentException if the track's rate, channel count or layout has no carriage
     */
    private void configurePcm(Stream stream) {
        if (!(stream.track instanceof AudioTrack audio)) {
            throw new IllegalArgumentException("a PCM track must be an audio track");
        }
        var rate = audio.sampleRate();
        var layout = audio.layout().orElse(null);
        var channels = layout != null ? layout.channels() : 0;
        if (channels <= 0) {
            throw new IllegalArgumentException("a PCM track needs its channel layout");
        }
        var wide = audio.codec() == Codec.PCM_S24LE || audio.codec() == Codec.PCM_S24BE;
        stream.pcmWidth = wide ? 3 : 2;
        stream.pcmBits = !wide ? 16 : audio.bitsPerSample() == 20 ? 20 : 24;
        stream.pcmLittleEndian = audio.codec() == Codec.PCM_S16LE || audio.codec() == Codec.PCM_S24LE;
        stream.pcmChannels = channels;
        var m2ts = options.packetFormat() == TsMuxerOptions.PacketFormat.M2TS;
        if (!m2ts && rate == 48_000 && channels % 2 == 0 && channels <= 8) {
            stream.pcmFrames = rate / 25;
            return;
        }
        var frequency = switch (rate) {
            case 48_000 -> 1;
            case 96_000 -> 4;
            case 192_000 -> 5;
            default -> throw new IllegalArgumentException("Blu-ray linear PCM cannot carry " + rate + " Hz");
        };
        if (channels > 8) {
            throw new IllegalArgumentException("transport stream PCM carries at most eight channels");
        }
        var assignment = switch (channels) {
            case 1 -> 1;
            case 2 -> 3;
            case 3 -> layout.indexOf(Speaker.BACK_CENTER) >= 0 ? 5 : 4;
            case 4 -> layout.indexOf(Speaker.FRONT_CENTER) >= 0 ? 6 : 7;
            case 5 -> 8;
            case 6 -> 9;
            case 7 -> 10;
            default -> 11;
        };
        stream.lpcm = true;
        stream.lpcmHeader = (assignment << 12) | (frequency << 8) | ((stream.pcmBits == 16 ? 1 : stream.pcmBits == 20 ? 2 : 3) << 6);
        stream.lpcmOrder = PcmParser.lpcmOrder(assignment);
        stream.pcmFrames = rate / 200;
    }

    /**
     * Reads the channel configuration and pre-skip of an Opus track from its {@code OpusHead}.
     *
     * @param stream the Opus stream
     * @param config the {@code OpusHead}
     * @throws IllegalArgumentException if the configuration is missing or its channel mapping has
     * no transport stream code
     */
    private static void configureOpus(Stream stream, byte[] config) {
        AudioConfigs.OpusConfig head;
        if (config == null) {
            var channels = stream.track instanceof AudioTrack audio ? audio.layout().map(l -> l.channels()).orElse(2) : 2;
            if (channels > 2) {
                throw new IllegalArgumentException("an Opus track of more than two channels needs its OpusHead");
            }
            stream.opusCode = channels;
            return;
        }
        try {
            head = AudioConfigs.parseOpusHead(config);
        } catch (MediaException exception) {
            throw new IllegalArgumentException("invalid OpusHead", exception);
        }
        var channels = head.channels();
        var standard = switch (head.family()) {
            case 0 -> channels <= 2;
            case 1 -> channels <= 8 && Arrays.equals(head.mapping(), standardMapping(channels));
            default -> false;
        };
        if (!standard) {
            throw new IllegalArgumentException("Opus channel mapping family " + head.family() + " with " + channels
                    + " channels has no transport stream channel configuration");
        }
        stream.opusCode = channels;
        stream.opusPreSkip = head.preSkip();
    }

    /**
     * Returns the stream counts and Vorbis-order mapping that a transport stream channel
     * configuration code implies, as an {@code OpusHead} stores them.
     *
     * @param channels the channel count, from 1 to 8
     * @return the stream count, coupled count and mapping
     */
    private static byte[] standardMapping(int channels) {
        int[] streams = {1, 1, 2, 2, 3, 4, 4, 5};
        int[] coupled = {0, 1, 1, 2, 2, 2, 3, 3};
        int[][] maps = {{0}, {0, 1}, {0, 2, 1}, {0, 1, 2, 3}, {0, 4, 1, 2, 3}, {0, 4, 1, 2, 3, 5}, {0, 4, 1, 2, 3, 5, 6},
                {0, 6, 1, 2, 3, 4, 5, 7}};
        var mapping = new byte[2 + channels];
        mapping[0] = (byte) streams[channels - 1];
        mapping[1] = (byte) coupled[channels - 1];
        for (var index = 0; index < channels; index++) {
            mapping[2 + index] = (byte) maps[channels - 1][index];
        }
        return mapping;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet's track was not declared, it has no timestamp,
     * an H.264 or HEVC packet is not made of length-prefixed NAL units, or a new codec
     * configuration cannot be parsed
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the codec configuration of a track
     * that is not H.264, HEVC, AV1, MPEG-4 Part 2 or AAC
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (streams.isEmpty()) {
            throw new IllegalStateException("no track was declared");
        }
        var stream = streamOf(packet.trackId());
        if (stream == null) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        if (time == Packet.NO_TIMESTAMP) {
            throw new IllegalArgumentException("a transport stream packet needs a timestamp");
        }
        var newConfig = ConfigChanges.changed(packet, stream.acceptedConfig);
        if (newConfig != null) {
            switch (stream.track.codec()) {
                case H264, HEVC -> {
                    if ((stream.hevc ? NalConfigs.parseHevc(newConfig) : NalConfigs.parseAvc(newConfig)) == null) {
                        throw new IllegalArgumentException("the new " + stream.track.codec().displayName()
                                + " configuration of track " + packet.trackId() + " cannot be parsed");
                    }
                }
                case AV1 -> {
                    if (newConfig.length < 4 || (newConfig[0] & 0x80) == 0) {
                        throw new IllegalArgumentException("the new AV1 configuration of track " + packet.trackId()
                                + " is not an av1C record");
                    }
                }
                case MPEG4_PART2 -> {
                    if (newConfig.length == 0) {
                        throw new IllegalArgumentException("the new MPEG-4 Part 2 configuration of track "
                                + packet.trackId() + " is empty");
                    }
                }
                case AAC -> configureAac(new Stream(stream.track, stream.pid), newConfig);
                default -> throw ConfigChanges.unsupported("MPEG-TS", packet.trackId());
            }
            stream.acceptedConfig = newConfig;
        }
        started = true;
        if (stream.track.kind() == TrackKind.VIDEO && packet.dts() == Packet.NO_TIMESTAMP) {
            stream.window.add(packet);
            stream.pending.add(packet.pts());
            if (stream.window.size() > REORDER_WINDOW) {
                release(stream);
            }
        } else {
            enqueue(stream, packet);
        }
        drain(false);
    }

    /**
     * Gives the oldest packet of a track with presentation timestamps only a decoding time and
     * queues it for the interleaving.
     *
     * <p>Decoding times are the presentation times sorted, so that the n-th packet in decoding
     * order decodes at the n-th presentation time, less the reordering delay measured on the
     * first packets; each is kept at or below its packet's presentation time and above the
     * previous decoding time.
     *
     * @param stream the stream whose oldest packet is released
     */
    private void release(Stream stream) {
        if (!stream.delayKnown) {
            var sorted = new long[stream.window.size()];
            for (var index = 0; index < sorted.length; index++) {
                sorted[index] = stream.window.get(index).pts();
            }
            Arrays.sort(sorted);
            var delay = 0L;
            for (var index = 0; index < sorted.length; index++) {
                delay = Math.max(delay, sorted[index] - stream.window.get(index).pts());
            }
            stream.reorderDelay = delay;
            stream.delayKnown = true;
        }
        var packet = stream.window.removeFirst();
        var dts = stream.pending.poll() - stream.reorderDelay;
        if (stream.lastDts != Packet.NO_TIMESTAMP) {
            dts = Math.max(dts, stream.lastDts + 1);
        }
        dts = Math.min(dts, packet.pts());
        stream.lastDts = dts;
        enqueue(stream, packet.withTiming(packet.pts(), dts, packet.duration()));
    }

    /**
     * Queues a packet with a decoding time for the interleaving.
     *
     * @param stream the stream of the packet
     * @param packet the packet, with its decoding time set
     */
    private void enqueue(Stream stream, Packet packet) {
        var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        var micros = stream.track.timeBase().toMicros(time);
        stream.queue.add(new Queued(packet, micros));
        queuedBytes += packet.size();
        newestMicros = Math.max(newestMicros, micros);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the muxer is already finished
     * @throws MediaException.Io if writing the output fails
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        for (var stream : streams) {
            while (!stream.window.isEmpty()) {
                release(stream);
            }
        }
        drain(true);
        for (var stream : streams) {
            flushAudio(stream);
        }
        if (shift == Long.MIN_VALUE && !streams.isEmpty()) {
            shift = 0;
            writeTables();
        }
        finished = true;
        if (segments != null) {
            closeSegment(segmentEnd);
        } else if (buffer.size() > 0) {
            buffer.writeTo(output);
            buffer.reset();
        }
    }

    /**
     * Marks the muxer finished without writing what is still queued; the output is not closed.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Returns the stream of a track.
     *
     * @param trackId the track identifier
     * @return the stream, or {@code null}
     */
    private Stream streamOf(int trackId) {
        for (var stream : streams) {
            if (stream.track.id() == trackId) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Writes queued packets in decoding-time order while the interleaving allows.
     *
     * @param all whether to write every queued packet
     */
    private void drain(boolean all) {
        while (true) {
            Stream oldest = null;
            var complete = true;
            for (var stream : streams) {
                var head = stream.queue.peekFirst();
                if (head == null) {
                    if (stream.track.kind() != TrackKind.DATA) {
                        complete = false;
                    }
                } else if (oldest == null || head.micros < oldest.queue.peekFirst().micros) {
                    oldest = stream;
                }
            }
            if (oldest == null) {
                return;
            }
            var head = oldest.queue.peekFirst();
            if (!all && !complete && newestMicros - head.micros <= MAX_INTERLEAVE_MICROS && queuedBytes <= MAX_QUEUED_BYTES) {
                return;
            }
            oldest.queue.removeFirst();
            queuedBytes -= head.packet.size();
            emit(oldest, head.packet);
        }
    }

    /**
     * Writes one packet in interleaved order.
     *
     * @param stream the packet's stream
     * @param packet the packet to write
     */
    private void emit(Stream stream, Packet packet) {
        var timeBase = stream.track.timeBase();
        var pts = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var dts = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        var pts90 = timeBase.rescale(pts, TICKS);
        var dts90 = timeBase.rescale(dts, TICKS);
        if (shift == Long.MIN_VALUE) {
            var origin = options.timestampOriginMicros();
            shift = options.delayMicros() * 9 / 100 - (origin.isPresent()
                    ? Rational.MICROSECONDS.rescale(origin.getAsLong(), TICKS) : dts90);
            tablesDue = true;
        }
        var ptsMicros = timeBase.toMicros(pts);
        if (stream == reference) {
            var key = stream.track.kind() != TrackKind.VIDEO || packet.keyframe();
            if (segments != null && segmentStart != Long.MIN_VALUE && key
                    && ptsMicros - segmentStart >= options.segmentMicros()) {
                for (var other : streams) {
                    flushAudio(other);
                }
                closeSegment(ptsMicros);
                tablesDue = true;
            }
            if (segmentStart == Long.MIN_VALUE) {
                segmentStart = ptsMicros;
                segmentIndependent = key;
            }
            var end = ptsMicros + (packet.duration() > 0 ? timeBase.toMicros(packet.duration()) : 0);
            segmentEnd = Math.max(segmentEnd, end);
        }
        var ptsOut = pts90 + shift;
        var dtsOut = dts90 + shift;
        if (tablesDue || dtsOut - lastPsi >= options.psiIntervalMicros() * 9 / 100) {
            writeTables();
            lastPsi = dtsOut;
            tablesDue = false;
        }
        switch (stream.track.kind()) {
            case VIDEO -> writePes(stream, videoPayload(stream, packet), ptsOut, dtsOut, packet.keyframe());
            case DATA -> {
                if (stream.splice) {
                    writeSplice(stream, packet);
                } else if (stream.dvbSubtitle) {
                    writePes(stream, dvbSubtitlePayload(packet.data()), ptsOut, ptsOut, true);
                } else {
                    writePes(stream, packet.data(), ptsOut, ptsOut, true);
                }
            }
            case AUDIO -> {
                if (stream.pcmFrames > 0) {
                    writePcm(stream, packet, ptsOut);
                } else {
                    gather(stream, packet, ptsOut);
                }
            }
        }
    }

    /**
     * Adds an audio frame to the PES packet being gathered for its stream, writing the gathered
     * packet first when the frame would make it too long.
     *
     * @param stream the audio stream of the frame
     * @param packet the frame
     * @param ptsOut the frame's PES presentation time
     */
    private void gather(Stream stream, Packet packet, long ptsOut) {
        var frame = audioFrame(stream, packet);
        var limit = stream == pcrStream ? Math.min(MAX_AUDIO_TICKS, options.pcrIntervalMicros() * 9 / 100) : MAX_AUDIO_TICKS;
        if (stream.gathered.size() > 0
                && (stream.gathered.size() + frame.length > MAX_AUDIO_PES || ptsOut - stream.gatheredPts >= limit)) {
            flushAudio(stream);
        }
        if (stream.gathered.size() == 0) {
            stream.gatheredPts = ptsOut;
        }
        stream.gathered.bytes(frame);
        if (stream.gathered.size() >= MAX_AUDIO_PES) {
            flushAudio(stream);
        }
    }

    /**
     * Writes the audio PES packet being gathered for a stream, if any.
     *
     * @param stream the audio stream to flush
     */
    private void flushAudio(Stream stream) {
        if (stream.gathered.size() == 0) {
            return;
        }
        var payload = stream.gathered.toByteArray();
        stream.gathered.reset();
        writePes(stream, payload, stream.gatheredPts, stream.gatheredPts, true);
    }

    /**
     * Frames an audio packet for its stream type.
     *
     * @param stream the audio stream of the packet
     * @param packet the audio packet to frame
     * @return the bytes the PES packet carries for it
     */
    private byte[] audioFrame(Stream stream, Packet packet) {
        var newConfig = ConfigChanges.changed(packet, stream.appliedConfig);
        if (newConfig != null && stream.track.codec() == Codec.AAC) {
            configureAac(stream, newConfig);
            stream.appliedConfig = newConfig;
        }
        var data = packet.data();
        return switch (stream.track.codec()) {
            case AAC -> {
                var length = AdtsHeader.SIZE + data.length;
                if (length > AdtsHeader.MAX_FRAME_LENGTH) {
                    throw new IllegalArgumentException("an AAC frame of " + data.length + " bytes does not fit an ADTS frame");
                }
                var frame = new byte[length];
                stream.adts.write(frame, 0, length);
                System.arraycopy(data, 0, frame, AdtsHeader.SIZE, data.length);
                if (stream.sampleAes != null) {
                    stream.sampleAes.audio(frame, Codec.AAC, AdtsHeader.SIZE);
                }
                yield frame;
            }
            case OPUS -> opusFrame(stream, packet);
            case AC3, EAC3 -> {
                if (stream.sampleAes == null) {
                    yield data;
                }
                var frame = data.clone();
                stream.sampleAes.audio(frame, stream.track.codec(), 0);
                yield frame;
            }
            default -> data;
        };
    }

    /**
     * Prefixes an Opus packet with its {@code opus_control_header}.
     *
     * @param stream the Opus stream of the packet
     * @param packet the Opus packet to prefix
     * @return the access unit
     */
    private byte[] opusFrame(Stream stream, Packet packet) {
        var data = packet.data();
        var startTrim = 0;
        if (stream.firstOpus) {
            stream.firstOpus = false;
            startTrim = Math.min(stream.opusPreSkip, 0x1FFF);
        }
        var endTrim = 0;
        if (packet.duration() > 0) {
            var coded = AudioConfigs.opusPacketSamples(data);
            var duration = stream.track.timeBase().rescale(packet.duration(), Rational.timeBaseOf(48_000));
            if (coded > 0 && duration < coded) {
                endTrim = (int) Math.min(0x1FFF, coded - duration);
            }
        }
        var header = new ByteWriter(16 + data.length / 255);
        header.u8(0x7F);
        header.u8(0xE0 | (startTrim > 0 ? 0x10 : 0) | (endTrim > 0 ? 0x08 : 0));
        var size = data.length;
        while (size >= 255) {
            header.u8(0xFF);
            size -= 255;
        }
        header.u8(size);
        if (startTrim > 0) {
            header.u16be(startTrim);
        }
        if (endTrim > 0) {
            header.u16be(endTrim);
        }
        header.bytes(data);
        return header.toByteArray();
    }

    /**
     * Converts a video packet into the PES payload of its stream type.
     *
     * @param stream the video stream
     * @param packet a packet of the stream's track
     * @return the payload
     */
    private static byte[] videoPayload(Stream stream, Packet packet) {
        return switch (stream.track.codec()) {
            case AV1 -> av1AccessUnit(stream, packet);
            case MPEG4_PART2 -> mpeg4Frame(stream, packet);
            default -> annexB(stream, packet);
        };
    }

    /**
     * Converts an AV1 temporal unit into the start-code format of the AOM "Carriage of AV1 in MPEG-2 TS"
     * section 3.2: every OBU, with its size field, gets the start code {@code 00 00 01} and an emulation
     * prevention byte {@code 03} after every {@code 00 00} that a byte of value 0 to 3 follows. The unit opens
     * with a temporal delimiter, and a key frame without a sequence header gets the one of the track's
     * configuration; the temporal delimiters of the packet are dropped.
     *
     * @param stream the AV1 stream, which holds the sequence header
     * @param packet the temporal unit, OBUs with size fields but possibly the last
     * @return the PES payload
     * @throws IllegalArgumentException if an OBU overruns the packet
     */
    private static byte[] av1AccessUnit(Stream stream, Packet packet) {
        var newConfig = ConfigChanges.changed(packet, stream.appliedConfig);
        if (newConfig != null) {
            configureAv1(stream, newConfig);
            stream.appliedConfig = newConfig;
        }
        var data = packet.data();
        var out = new ByteWriter(data.length + data.length / 64 + stream.parameterSets.length + 32);
        out.u24be(1);
        out.u8(0x12);
        out.u8(0);
        var hasSequenceHeader = false;
        for (var offset = 0; offset < data.length; ) {
            var obuEnd = av1ObuEnd(data, offset);
            if (((data[offset] >> 3) & 0x0F) == 1) {
                hasSequenceHeader = true;
            }
            offset = obuEnd;
        }
        if (packet.keyframe() && !hasSequenceHeader && stream.parameterSets.length > 0) {
            for (var offset = 0; offset < stream.parameterSets.length; ) {
                var obuEnd = av1ObuEnd(stream.parameterSets, offset);
                startCodeObu(out, stream.parameterSets, offset, obuEnd);
                offset = obuEnd;
            }
        }
        for (var offset = 0; offset < data.length; ) {
            var obuEnd = av1ObuEnd(data, offset);
            if (((data[offset] >> 3) & 0x0F) != 2) {
                startCodeObu(out, data, offset, obuEnd);
            }
            offset = obuEnd;
        }
        return out.toByteArray();
    }

    /**
     * Returns the end of the OBU starting at an index.
     *
     * <p>An OBU without a size field extends to the end of the data, as the last OBU of an ISOBMFF or Matroska
     * sample may.
     *
     * @param data the OBUs, each with its size field but the last
     * @param offset the index of the OBU header
     * @return the index after the OBU
     * @throws IllegalArgumentException if the OBU overruns the data
     */
    private static int av1ObuEnd(byte[] data, int offset) {
        var header = data[offset] & 0xFF;
        if ((header & 0x02) == 0) {
            if (offset + ((header & 0x04) != 0 ? 2 : 1) > data.length) {
                throw new IllegalArgumentException("an AV1 OBU header overruns the packet");
            }
            return data.length;
        }
        var position = offset + ((header & 0x04) != 0 ? 2 : 1);
        var size = 0L;
        for (var shift = 0; ; shift += 7) {
            if (position >= data.length || shift > 49) {
                throw new IllegalArgumentException("an AV1 OBU size overruns the packet");
            }
            var b = data[position++] & 0xFF;
            size |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        if (size > data.length - position) {
            throw new IllegalArgumentException("an AV1 OBU overruns the packet");
        }
        return position + (int) size;
    }

    /**
     * Writes one OBU behind a start code, with emulation prevention.
     *
     * @param out the payload being written
     * @param data the bytes
     * @param from the index of the OBU header
     * @param to the index after the OBU
     */
    private static void startCodeObu(ByteWriter out, byte[] data, int from, int to) {
        out.u24be(1);
        var zeros = 0;
        for (var index = from; index < to; index++) {
            var value = data[index] & 0xFF;
            if (zeros >= 2 && value <= 3) {
                out.u8(3);
                zeros = 0;
            }
            out.u8(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
    }

    /**
     * Prepends the decoder configuration (the visual object sequence, visual object and video object layer
     * headers) to an MPEG-4 Part 2 key frame that does not start with it.
     *
     * @param stream the MPEG-4 Part 2 stream, which holds the configuration
     * @param packet the frame
     * @return the PES payload
     */
    private static byte[] mpeg4Frame(Stream stream, Packet packet) {
        var newConfig = ConfigChanges.changed(packet, stream.appliedConfig);
        if (newConfig != null) {
            stream.parameterSets = newConfig;
            stream.appliedConfig = newConfig;
        }
        var data = packet.data();
        if (!packet.keyframe() || startsWithHeaders(data)) {
            return data;
        }
        var frame = Arrays.copyOf(stream.parameterSets, stream.parameterSets.length + data.length);
        System.arraycopy(data, 0, frame, stream.parameterSets.length, data.length);
        return frame;
    }

    /**
     * Returns whether an MPEG-4 Part 2 frame starts with a visual object sequence, visual object or video
     * object layer header.
     *
     * @param data the frame
     * @return {@code true} when its first start code is one of those headers
     */
    private static boolean startsWithHeaders(byte[] data) {
        for (var index = 0; index + 3 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) {
                var code = data[index + 3] & 0xFF;
                return code == 0xB0 || code == 0xB5 || (code >= 0x00 && code <= 0x2F);
            }
        }
        return false;
    }

    /**
     * Writes a PCM packet as PES packets of at most {@code pcmFrames} sample frames, each with its header:
     * the Blu-ray linear PCM header, with big-endian words in the stream's channel order and an odd channel
     * count padded with a silent channel, or the SMPTE ST 302 header, with every word sent least significant bit
     * first and followed by its validity, user, channel status and framing bits (all zero but the framing bit of
     * the first subframe of every block of 192 frames).
     *
     * @param stream the PCM stream
     * @param packet the interleaved samples
     * @param ptsOut the PES presentation time of the packet
     */
    private void writePcm(Stream stream, Packet packet, long ptsOut) {
        var data = packet.data();
        var frameBytes = stream.pcmChannels * stream.pcmWidth;
        var frames = data.length / frameBytes;
        var rate = ((AudioTrack) stream.track).sampleRate();
        for (var first = 0; first < frames; first += stream.pcmFrames) {
            var count = Math.min(stream.pcmFrames, frames - first);
            var pts = ptsOut + (long) first * 90_000 / rate;
            var payload = stream.lpcm ? lpcmPayload(stream, data, first * frameBytes, count)
                    : aes3Payload(stream, data, first * frameBytes, count);
            writePes(stream, payload, pts, pts, true);
        }
    }

    /**
     * Builds the payload of a Blu-ray linear PCM PES packet.
     *
     * @param stream the PCM stream
     * @param data the interleaved samples
     * @param offset the index of the first frame
     * @param frames the number of frames
     * @return the payload, header included
     */
    private static byte[] lpcmPayload(Stream stream, byte[] data, int offset, int frames) {
        var channels = stream.pcmChannels;
        var stored = channels + (channels & 1);
        var width = stream.pcmWidth;
        var size = frames * stored * width;
        var payload = new byte[4 + size];
        payload[0] = (byte) (size >> 8);
        payload[1] = (byte) size;
        payload[2] = (byte) (stream.lpcmHeader >> 8);
        payload[3] = (byte) stream.lpcmHeader;
        var in = offset;
        var out = 4;
        for (var frame = 0; frame < frames; frame++) {
            for (var channel = 0; channel < channels; channel++) {
                var target = out + stream.lpcmOrder[channel] * width;
                for (var index = 0; index < width; index++) {
                    payload[target + index] = data[in + (stream.pcmLittleEndian ? width - 1 - index : index)];
                }
                in += width;
            }
            out += stored * width;
        }
        return payload;
    }

    /**
     * Builds the payload of a SMPTE ST 302 PES packet.
     *
     * @param stream the PCM stream
     * @param data the interleaved samples
     * @param offset the index of the first frame
     * @param frames the number of frames
     * @return the payload, header included
     */
    private static byte[] aes3Payload(Stream stream, byte[] data, int offset, int frames) {
        var bits = stream.pcmBits;
        var channels = stream.pcmChannels;
        var size = frames * channels * (bits + 4) / 8;
        var payload = new byte[4 + size];
        payload[0] = (byte) (size >> 8);
        payload[1] = (byte) size;
        payload[2] = (byte) ((channels / 2 - 1) << 6);
        payload[3] = (byte) ((bits == 16 ? 0 : bits == 20 ? 1 : 2) << 4);
        var bit = 32L;
        var width = stream.pcmWidth;
        var in = offset;
        for (var frame = 0; frame < frames; frame++) {
            var blockStart = stream.aes3Frame % 192 == 0;
            stream.aes3Frame++;
            for (var channel = 0; channel < channels; channel++) {
                var value = 0;
                for (var index = 0; index < width; index++) {
                    var b = data[in + (stream.pcmLittleEndian ? index : width - 1 - index)] & 0xFF;
                    value |= b << (8 * index);
                }
                in += width;
                if (bits == 20) {
                    value >>>= 4;
                }
                for (var index = 0; index < bits; index++, bit++) {
                    if (((value >>> index) & 1) != 0) {
                        payload[(int) (bit >>> 3)] |= (byte) (0x80 >>> (int) (bit & 7));
                    }
                }
                bit += 3;
                if (blockStart && channel % 2 == 0) {
                    payload[(int) (bit >>> 3)] |= (byte) (0x80 >>> (int) (bit & 7));
                }
                bit++;
            }
        }
        return payload;
    }

    /**
     * Writes an SCTE 35 {@code splice_info_section} on its stream's PID, its {@code pts_adjustment} moved by the
     * offset the muxer adds to every timestamp.
     *
     * @param stream the SCTE 35 stream
     * @param cue the section, its {@code CRC_32} included
     * @throws IllegalArgumentException if the packet is not a {@code splice_info_section} whose length and
     * {@code CRC_32} hold
     */
    private void writeSplice(Stream stream, Packet cue) {
        var section = cue.data();
        if (section.length < 15 || (section[0] & 0xFF) != Psi.SPLICE_INFO_TABLE
                || 3 + ((section[1] & 0x0F) << 8 | (section[2] & 0xFF)) != section.length
                || Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, section.length) != 0) {
            throw new IllegalArgumentException("an SCTE 35 packet must be a whole splice_info_section with its CRC_32");
        }
        section = Scte35Parser.rebased(section, shift);
        var position = 0;
        var first = true;
        while (position < section.length) {
            startPacket(stream.pid, first, false, true, stream.counter);
            stream.counter = (stream.counter + 1) & 0x0F;
            var cursor = 4;
            if (first) {
                this.packet[cursor++] = 0;
            }
            var take = Math.min(PACKET - cursor, section.length - position);
            System.arraycopy(section, position, this.packet, cursor, take);
            Arrays.fill(this.packet, cursor + take, PACKET, (byte) 0xFF);
            position += take;
            first = false;
            commit(-1);
        }
    }

    /**
     * Converts an access unit of length-prefixed NAL units into an Annex B access unit that
     * starts with an access unit delimiter and carries the parameter sets when it is a key frame
     * without them.
     *
     * @param stream the video stream, which holds the NAL length size and parameter sets
     * @param packet the access unit
     * @return the Annex B bytes
     * @throws IllegalArgumentException if a NAL unit length overruns the packet
     */
    private static byte[] annexB(Stream stream, Packet packet) {
        var newConfig = ConfigChanges.changed(packet, stream.appliedConfig);
        if (newConfig != null) {
            var parsed = stream.hevc ? NalConfigs.parseHevc(newConfig) : NalConfigs.parseAvc(newConfig);
            stream.lengthSize = parsed.lengthSize();
            stream.parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
            stream.appliedConfig = newConfig;
        }
        var data = packet.data();
        var lengthSize = stream.lengthSize;
        if (stream.sampleAes != null) {
            data = stream.sampleAes.video(data, lengthSize);
            lengthSize = 4;
        }
        return NalUnits.toAnnexB(data, lengthSize, stream.hevc,
                packet.keyframe() ? stream.parameterSets : null, stream.hevc ? HEVC_DELIMITER : AVC_DELIMITER);
    }

    /**
     * Writes one PES packet, split into transport packets.
     *
     * @param stream the stream the PES packet belongs to
     * @param payload the PES payload
     * @param ptsOut the PES presentation time, before the 33-bit wrap
     * @param dtsOut the PES decoding time, before the 33-bit wrap
     * @param key whether the payload starts with a random access point
     */
    private void writePes(Stream stream, byte[] payload, long ptsOut, long dtsOut, boolean key) {
        var delay = options.delayMicros() * 9 / 100;
        var due = (dtsOut - delay) * 300;
        if (options.muxRate() > 0) {
            while (pcrAt(packetsWritten) < due) {
                if (pcrAt(packetsWritten) - lastPcr >= pcrInterval()) {
                    writePcrOnly(pcrAt(packetsWritten));
                } else {
                    writeNull();
                }
            }
        } else {
            var target = Math.max(lastPcr + 1, due);
            if (lastPcr >= 0) {
                while (target - lastPcr > pcrInterval()) {
                    writePcrOnly(lastPcr + pcrInterval());
                }
            }
            currentPcr = Math.max(currentPcr, target);
        }
        var withDts = dtsOut != ptsOut;
        var timestamps = withDts ? 10 : 5;
        var headerLength = stream.teletext ? TELETEXT_HEADER_LENGTH : timestamps;
        if (stream.teletext) {
            payload = teletextPayload(payload);
        }
        var length = 3 + headerLength + payload.length;
        if (length > 0xFFFF && stream.track.kind() != TrackKind.VIDEO) {
            throw new IllegalArgumentException("a " + stream.track.codec().displayName() + " packet of " + payload.length
                    + " bytes does not fit a PES packet");
        }
        var pes = new ByteWriter(payload.length + 9 + headerLength);
        pes.u8(0);
        pes.u8(0);
        pes.u8(1);
        pes.u8(stream.streamId);
        pes.u16be(length > 0xFFFF || (stream.streamId & 0xF0) == 0xE0 ? 0 : length);
        pes.u8(0x84);
        pes.u8(withDts ? 0xC0 : 0x80);
        pes.u8(headerLength);
        writeTimestamp(pes, withDts ? 3 : 2, ptsOut);
        if (withDts) {
            writeTimestamp(pes, 1, dtsOut);
        }
        for (var stuffing = timestamps; stuffing < headerLength; stuffing++) {
            pes.u8(0xFF);
        }
        pes.bytes(payload);
        var bytes = pes.toByteArray();
        var position = 0;
        var first = true;
        while (position < bytes.length) {
            var pcr = -1L;
            if (stream == pcrStream) {
                var now = options.muxRate() > 0 ? pcrAt(packetsWritten) : currentPcr;
                if (first || now - lastPcr >= pcrInterval()) {
                    pcr = now;
                }
            }
            var randomAccess = first && key;
            var fields = pcr >= 0 || randomAccess ? 2 + (pcr >= 0 ? 6 : 0) : 0;
            var remaining = bytes.length - position;
            var adaptation = remaining < PAYLOAD - fields ? PAYLOAD - remaining : fields;
            var take = PAYLOAD - adaptation;
            startPacket(stream.pid, first, adaptation > 0, true, stream.counter);
            stream.counter = (stream.counter + 1) & 0x0F;
            var cursor = 4;
            if (adaptation > 0) {
                cursor = writeAdaptation(cursor, adaptation, pcr, randomAccess,
                        randomAccess && stream.track.codec() == Codec.AV1);
            }
            System.arraycopy(bytes, position, packet, cursor, take);
            position += take;
            first = false;
            commit(pcr);
        }
    }

    /**
     * Returns whether a stream uses a stream type that only Blu-ray programs define, Blu-ray linear PCM ({@code 0x80})
     * or presentation graphics ({@code 0x90}), so that the program carries the {@code "HDMV"} registration.
     *
     * @param stream the declared stream to check
     * @return {@code true} for those streams
     */
    private static boolean blurayType(Stream stream) {
        return stream.lpcm || stream.streamType == 0x90;
    }

    /**
     * Wraps the segments of a DVB subtitle packet in a PES data field: the {@code data_identifier} and
     * {@code subtitle_stream_id}, then the segments, then the end marker.
     *
     * @param segments the packet payload
     * @return the PES payload
     */
    private static byte[] dvbSubtitlePayload(byte[] segments) {
        var payload = new byte[segments.length + 3];
        payload[0] = 0x20;
        System.arraycopy(segments, 0, payload, 2, segments.length);
        payload[payload.length - 1] = (byte) 0xFF;
        return payload;
    }

    /**
     * Pads a teletext PES data field with stuffing data units so that the PES packet, whose header is
     * {@link #TELETEXT_HEADER_LENGTH} bytes long, fills whole transport packets (ETSI EN 300 472 clause 4.2).
     *
     * @param payload the PES data field
     * @return the padded data field
     */
    private static byte[] teletextPayload(byte[] payload) {
        var total = 9 + TELETEXT_HEADER_LENGTH + payload.length;
        var missing = (PAYLOAD - total % PAYLOAD) % PAYLOAD;
        if (missing == 0) {
            return payload;
        }
        var padded = Arrays.copyOf(payload, payload.length + missing);
        var position = payload.length;
        while (missing >= 46) {
            padded[position] = (byte) 0xFF;
            padded[position + 1] = 0x2C;
            Arrays.fill(padded, position + 2, position + 46, (byte) 0xFF);
            position += 46;
            missing -= 46;
        }
        Arrays.fill(padded, position, padded.length, (byte) 0xFF);
        return padded;
    }

    /**
     * Returns the teletext descriptor body of a track without one: an initial subtitle page 888 in the track's
     * language.
     *
     * @param track the teletext track, whose language names the page
     * @return the descriptor body
     */
    private static byte[] defaultTeletext(Track track) {
        var language = track.language().filter(code -> code.length() == 3).orElse("und")
                .getBytes(StandardCharsets.US_ASCII);
        return new byte[]{language[0], language[1], language[2], 0x02 << 3, (byte) 0x88};
    }

    /**
     * Writes a 33-bit PES timestamp with its prefix and marker bits (ISO/IEC 13818-1 clause
     * 2.4.3.7).
     *
     * @param pes the PES header being written
     * @param prefix the four-bit prefix: {@code 2} for a lone PTS, {@code 3} for a PTS followed by
     * a DTS, {@code 1} for a DTS
     * @param value the timestamp, reduced modulo 2^33
     */
    private static void writeTimestamp(ByteWriter pes, int prefix, long value) {
        var time = value & WRAP_MASK;
        pes.u8((prefix << 4) | (int) ((time >>> 29) & 0x0E) | 1);
        pes.u16be((int) ((time >>> 14) & 0xFFFE) | 1);
        pes.u16be((int) ((time << 1) & 0xFFFE) | 1);
    }

    /**
     * {@return the longest clock reference interval in 27 MHz ticks}
     */
    private long pcrInterval() {
        return options.pcrIntervalMicros() * 27;
    }

    /**
     * Returns the clock at a packet of a constant-rate output.
     *
     * @param index the number of packets written before it
     * @return the clock in 27 MHz ticks
     */
    private long pcrAt(long index) {
        var bits = PACKET * 8L * 27_000_000L;
        var rate = options.muxRate();
        if (index < Long.MAX_VALUE / bits) {
            return pcrBase + index * bits / rate;
        }
        return pcrBase + BigInteger.valueOf(index).multiply(BigInteger.valueOf(bits))
                .divide(BigInteger.valueOf(rate)).longValueExact();
    }

    /**
     * Fills the scratch packet's header.
     *
     * @param pid the PID
     * @param unitStart whether the payload unit start indicator is set
     * @param adaptation whether an adaptation field follows
     * @param payload whether a payload follows
     * @param counter the continuity counter
     */
    private void startPacket(int pid, boolean unitStart, boolean adaptation, boolean payload, int counter) {
        packet[0] = (byte) TsScanner.SYNC;
        packet[1] = (byte) ((unitStart ? 0x40 : 0) | (pid >> 8));
        packet[2] = (byte) pid;
        packet[3] = (byte) ((adaptation ? 0x20 : 0) | (payload ? 0x10 : 0) | (counter & 0x0F));
    }

    /**
     * Writes an adaptation field into the scratch packet.
     *
     * @param cursor the index of the adaptation field
     * @param size the size of the whole field, its length byte included
     * @param pcr the clock reference to carry, or {@code -1}
     * @param randomAccess whether to set the random access indicator
     * @param priority whether to set the elementary stream priority indicator, which the AOM "Carriage of AV1 in
     * MPEG-2 TS" section 3.4 requires on the packet that starts an AV1 key frame
     * @return the index after the field
     */
    private int writeAdaptation(int cursor, int size, long pcr, boolean randomAccess, boolean priority) {
        packet[cursor] = (byte) (size - 1);
        if (size == 1) {
            return cursor + 1;
        }
        packet[cursor + 1] = (byte) ((randomAccess ? 0x40 : 0) | (priority ? 0x20 : 0) | (pcr >= 0 ? 0x10 : 0));
        var position = cursor + 2;
        if (pcr >= 0) {
            var base = (pcr / 300) & WRAP_MASK;
            var extension = pcr % 300;
            packet[position] = (byte) (base >>> 25);
            packet[position + 1] = (byte) (base >>> 17);
            packet[position + 2] = (byte) (base >>> 9);
            packet[position + 3] = (byte) (base >>> 1);
            packet[position + 4] = (byte) (((base & 1) << 7) | 0x7E | (extension >>> 8));
            packet[position + 5] = (byte) extension;
            position += 6;
        }
        Arrays.fill(packet, position, cursor + size, (byte) 0xFF);
        return cursor + size;
    }

    /**
     * Writes an adaptation-field-only packet carrying a clock reference on the clock PID.
     *
     * @param pcr the clock reference in 27 MHz ticks
     */
    private void writePcrOnly(long pcr) {
        startPacket(pcrStream.pid, false, true, false, pcrStream.counter == 0 ? 15 : pcrStream.counter - 1);
        writeAdaptation(4, PAYLOAD, pcr, false, false);
        if (options.muxRate() == 0) {
            currentPcr = Math.max(currentPcr, pcr);
        }
        commit(pcr);
    }

    /**
     * Writes a null packet.
     */
    private void writeNull() {
        startPacket(NULL_PID, false, false, true, 0);
        Arrays.fill(packet, 4, PACKET, (byte) 0xFF);
        commit(-1);
    }

    /**
     * Writes the program association and map tables.
     */
    private void writeTables() {
        var pat = new ByteWriter(16);
        pat.u16be(1);
        pat.u8(0xC1);
        pat.u8(0);
        pat.u8(0);
        pat.u16be(options.programNumber());
        pat.u16be(0xE000 | options.pmtPid());
        writeSection(PAT_PID, Psi.PAT_TABLE, pat.toByteArray(), true);
        writeSection(options.pmtPid(), Psi.PMT_TABLE, programMapBody(), false);
    }

    /**
     * Builds the program map table of the declared tracks.
     *
     * @return the section after its {@code section_length} field, CRC excluded
     */
    private byte[] programMapBody() {
        var pmt = new ByteWriter(64);
        pmt.u16be(options.programNumber());
        pmt.u8(0xC1);
        pmt.u8(0);
        pmt.u8(0);
        pmt.u16be(0xE000 | (pcrStream != null ? pcrStream.pid : NULL_PID));
        var id3 = streams.stream().filter(stream -> stream.id3).findFirst().orElse(null);
        var program = new ByteWriter(32);
        if (streams.stream().anyMatch(TsMuxer::blurayType)) {
            registration(program, "HDMV");
        }
        if (streams.stream().anyMatch(stream -> stream.splice)) {
            registration(program, "CUEI");
        }
        pmt.u16be(0xF000 | (program.size() + (id3 != null ? 17 : 0)));
        pmt.bytes(program.toByteArray());
        if (id3 != null) {
            pmt.u8(0x25);
            pmt.u8(15);
            pmt.u16be(0xFFFF);
            pmt.fourCc("ID3 ");
            pmt.u8(0xFF);
            pmt.fourCc("ID3 ");
            pmt.u8(0);
            pmt.u8(0x1F);
            pmt.u16be(options.programNumber());
        }
        for (var stream : streams) {
            var descriptors = entryDescriptors(stream);
            pmt.u8(stream.streamType);
            pmt.u16be(0xE000 | stream.pid);
            pmt.u16be(0xF000 | descriptors.length);
            pmt.bytes(descriptors);
        }
        return pmt.toByteArray();
    }

    /**
     * Returns the {@code section_length} of a long-form PSI section.
     *
     * @param bodyLength the length of the section after its {@code section_length} field, CRC excluded
     * @return the section length, the CRC included
     * @throws MediaException.LimitExceeded if the section exceeds the 1021 bytes a PSI section length allows, which
     * the descriptors of many named tracks can reach
     */
    private static int checkSection(int bodyLength) {
        var length = bodyLength + 4;
        if (length > MAX_SECTION_LENGTH) {
            throw new MediaException.LimitExceeded("a PSI section of " + length + " bytes exceeds the "
                    + MAX_SECTION_LENGTH + " a section length allows");
        }
        return length;
    }

    /**
     * Writes one long-form PSI section, split into transport packets.
     *
     * @param pid the PID
     * @param table the {@code table_id}
     * @param body the section after its {@code section_length} field, CRC excluded
     * @param pat whether this is the program association table, whose counter it uses
     * @throws MediaException.LimitExceeded if the section exceeds the 1021 bytes a PSI section length allows, which
     * the descriptors of many named tracks can reach
     */
    private void writeSection(int pid, int table, byte[] body, boolean pat) {
        var length = checkSection(body.length);
        var section = new byte[3 + body.length + 4];
        section[0] = (byte) table;
        section[1] = (byte) (0xB0 | (length >> 8));
        section[2] = (byte) length;
        System.arraycopy(body, 0, section, 3, body.length);
        var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, section.length - 4);
        section[section.length - 4] = (byte) (crc >>> 24);
        section[section.length - 3] = (byte) (crc >>> 16);
        section[section.length - 2] = (byte) (crc >>> 8);
        section[section.length - 1] = (byte) crc;
        var position = 0;
        var first = true;
        while (position < section.length) {
            var counter = pat ? patCounter : pmtCounter;
            startPacket(pid, first, false, true, counter);
            if (pat) {
                patCounter = (patCounter + 1) & 0x0F;
            } else {
                pmtCounter = (pmtCounter + 1) & 0x0F;
            }
            var cursor = 4;
            if (first) {
                packet[cursor++] = 0;
            }
            var take = Math.min(PACKET - cursor, section.length - position);
            System.arraycopy(section, position, packet, cursor, take);
            Arrays.fill(packet, cursor + take, PACKET, (byte) 0xFF);
            position += take;
            first = false;
            commit(-1);
        }
    }

    /**
     * Appends the scratch packet to the output, with its M2TS header when the layout has one.
     *
     * @param pcr the clock reference the packet carries, or {@code -1}
     */
    private void commit(long pcr) {
        if (pcr >= 0) {
            lastPcr = Math.max(lastPcr, pcr);
        }
        if (options.packetFormat() == TsMuxerOptions.PacketFormat.M2TS) {
            var clock = options.muxRate() > 0 ? pcrAt(packetsWritten) : Math.max(currentPcr, lastPcr);
            var arrival = Math.max(lastArrival + 1, clock);
            lastArrival = arrival;
            var stamp = arrival & 0x3FFFFFFF;
            buffer.u32be(stamp);
        }
        buffer.bytes(packet);
        packetsWritten++;
        if (segments == null && buffer.size() >= FLUSH_BYTES) {
            buffer.writeTo(output);
            buffer.reset();
        }
    }

    /**
     * Hands the current segment to its sink and starts the next one.
     *
     * @param end the presentation time at which the segment ends, in microseconds
     */
    private void closeSegment(long end) {
        if (buffer.size() == 0) {
            return;
        }
        var start = segmentStart == Long.MIN_VALUE ? 0 : segmentStart;
        var duration = end == Long.MIN_VALUE ? 0 : Math.max(0, end - start);
        var segment = new Segment(Segment.Kind.MEDIA, sequence++, start, duration, segmentIndependent, buffer.size());
        var sink = segments.open(segment);
        try {
            buffer.writeTo(sink);
        } finally {
            sink.close();
        }
        buffer.reset();
        segmentStart = Long.MIN_VALUE;
    }

    /**
     * Holds a queued packet with its decoding time.
     *
     * @param packet the packet waiting for its turn in decoding order
     * @param micros its decoding time in microseconds, the interleaving key
     */
    private record Queued(Packet packet, long micros) {
    }

    /**
     * Holds the state of one elementary stream.
     */
    private static final class Stream {
        /**
         * The track.
         */
        final Track track;

        /**
         * The PID.
         */
        final int pid;

        /**
         * The packets waiting for the interleaving.
         */
        final ArrayDeque<Queued> queue = new ArrayDeque<>();

        /**
         * The audio frames gathered for the next PES packet.
         */
        final ByteWriter gathered = new ByteWriter(MAX_AUDIO_PES + 512);

        /**
         * The presentation time of the first gathered frame.
         */
        long gatheredPts;

        /**
         * The {@code stream_type}.
         */
        int streamType;

        /**
         * The SAMPLE-AES encryptor of the stream's samples, or {@code null} for a clear stream.
         */
        SampleAesEncryptor sampleAes;

        /**
         * The PES {@code stream_id}.
         */
        int streamId;

        /**
         * Whether the stream is DVB subtitles, whose packets get the PES data field framing.
         */
        boolean dvbSubtitle;

        /**
         * Whether the stream is DVB teletext, whose PES packets have a fixed header length and fill whole
         * transport packets.
         */
        boolean teletext;

        /**
         * The descriptors of the program map entry.
         */
        byte[] descriptors = new byte[0];

        /**
         * The continuity counter of the next packet.
         */
        int counter;

        /**
         * Whether the stream is HEVC rather than H.264.
         */
        boolean hevc;

        /**
         * The NAL unit length size of an H.264 or HEVC track.
         */
        int lengthSize = 4;

        /**
         * The parameter sets of an H.264 or HEVC track, start-code prefixed.
         */
        byte[] parameterSets = new byte[0];

        /**
         * The ADTS header fields of an AAC track, or {@code null} for other tracks.
         */
        AdtsHeader adts;

        /**
         * The codec configuration of the last packet accepted by {@code writePacket}, initially
         * the track's.
         */
        byte[] acceptedConfig;

        /**
         * The codec configuration the packets being converted use, initially the track's.
         */
        byte[] appliedConfig;

        /**
         * The {@code channel_config_code} of an Opus track.
         */
        int opusCode;

        /**
         * The pre-skip of an Opus track, written as the first packet's start trim.
         */
        int opusPreSkip;

        /**
         * Whether the next Opus packet is the first.
         */
        boolean firstOpus = true;

        /**
         * Whether the stream is ID3 timed metadata.
         */
        boolean id3;

        /**
         * Whether the stream is SCTE 35 splice information, carried in sections.
         */
        boolean splice;

        /**
         * Whether the PCM stream is Blu-ray linear PCM rather than SMPTE ST 302.
         */
        boolean lpcm;

        /**
         * The third and fourth bytes of the Blu-ray linear PCM header.
         */
        int lpcmHeader;

        /**
         * The Blu-ray linear PCM stream slot of each channel of the track.
         */
        int[] lpcmOrder;

        /**
         * The largest number of sample frames per PES packet of a PCM stream, or {@code 0} for another stream.
         */
        int pcmFrames;

        /**
         * The number of channels of a PCM stream.
         */
        int pcmChannels;

        /**
         * The bytes per sample of the track's PCM codec.
         */
        int pcmWidth;

        /**
         * The word size written: 16, 20 or 24 bits.
         */
        int pcmBits;

        /**
         * Whether the track's PCM samples are little-endian.
         */
        boolean pcmLittleEndian;

        /**
         * The number of SMPTE ST 302 frames written, which places the framing bit of each block of 192.
         */
        long aes3Frame;

        /**
         * The packets of a track with presentation timestamps only waiting for their decoding
         * times, in decoding order.
         */
        final ArrayList<Packet> window = new ArrayList<>();

        /**
         * The presentation times of the waiting packets not yet given out as decoding times.
         */
        final PriorityQueue<Long> pending = new PriorityQueue<>();

        /**
         * The reordering delay subtracted from the sorted presentation times.
         */
        long reorderDelay;

        /**
         * Whether {@link #reorderDelay} was measured.
         */
        boolean delayKnown;

        /**
         * The last decoding time given out, or {@link Packet#NO_TIMESTAMP}.
         */
        long lastDts = Packet.NO_TIMESTAMP;

        /**
         * Constructs the state of a stream.
         *
         * @param track the track the stream carries
         * @param pid the PID
         */
        Stream(Track track, int pid) {
            this.track = track;
            this.pid = pid;
            this.acceptedConfig = track.codecConfig().orElse(null);
            this.appliedConfig = acceptedConfig;
        }
    }
}
