package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Writes MPEG program streams (ISO/IEC 13818-1 clause 2.5), MPEG-1 system streams (ISO/IEC 11172-1) and the video
 * objects of DVD-Video ({@code .vob}, {@link PsMuxerOptions#forDvd()}).
 *
 * <p>Supported codecs, with their {@code stream_id} (and, for private stream 1, the DVD-Video {@code sub_stream_id})
 * and the {@code stream_type} of the program stream map:
 * <ul>
 *   <li>MPEG-1 and MPEG-2 video ({@code 0x01}, {@code 0x02}), ids from {@code 0xE0}: frames as they are, a key frame
 *       that does not start with a sequence header getting the one of the track's configuration, when it has
 *       one;</li>
 *   <li>MPEG-4 Part 2 ({@code 0x10}), H.264 ({@code 0x1B}), HEVC ({@code 0x24}) and VVC ({@code 0x33}), ids from
 *       {@code 0xE0}, as {@link TsMuxer} writes them: MPEG-4 Part 2 key frames get the headers of the configuration
 *       they lack, and H.264, HEVC and VVC access units become Annex B byte streams with an access unit delimiter
 *       and the parameter sets of the configuration on every key frame;</li>
 *   <li>MP2 and MP3 ({@code 0x03}, or {@code 0x04} below 32 kHz) and AAC ({@code 0x0F}, every packet framed in ADTS
 *       from the {@code AudioSpecificConfig}), ids from {@code 0xC0};</li>
 *   <li>in private stream 1 ({@code 0xBD}), with the DVD-Video substream headers: AC-3 ({@code 0x80} to
 *       {@code 0x87}), E-AC-3 ({@code 0xC0} to {@code 0xC7}, as HD DVD uses them), DTS ({@code 0x88} to
 *       {@code 0x8F}), linear PCM ({@code 0xA0} to {@code 0xA7}) and DVD subpictures ({@code 0x20} to {@code 0x3F}).
 *       The AC-3, E-AC-3 and DTS headers count the frames that start in each PES packet and point to the first;
 *       linear PCM tracks of 16-bit or 24-bit samples, big-endian or little-endian, at 48, 96, 44.1 or 32 kHz with
 *       one to eight channels, are written as 16-bit, 20-bit (a 24-bit track that declares 20
 *       {@linkplain AudioTrack#bitsPerSample() bits per sample}) or 24-bit DVD linear PCM, in the sample layout
 *       {@link PsDemuxer} reads, each PES packet holding whole sample groups and its header pointing to the first
 *       audio frame (1/600 s at 48 and 96 kHz) that starts in it. A 20-bit or 24-bit group spans two sample frames
 *       at one, two or six channels, four at three, five or seven and one at four or eight, and {@link #finish()}
 *       completes a last group the track's samples leave short with silent frames;</li>
 * </ul>
 * MPEG-4 Part 2, H.264, HEVC, VVC and AAC need the program stream map, which is then written in the first pack and
 * before every video key frame, with the ISO 639 language descriptor of every track that has a language
 * ({@link PsMuxerOptions#programStreamMap()} writes it for any streams); it never lists private stream 1, whose
 * substreams identify themselves. The {@link PsMuxerOptions.Syntax#MPEG1} syntax carries MPEG-1 and MPEG-2 video and
 * MPEG audio only. With {@linkplain PsMuxerOptions#navigationPacks() navigation packs} the muxer accepts only the
 * codecs of DVD-Video (MPEG-1 and MPEG-2 video, MP2, AC-3, DTS, linear PCM at 48 or 96 kHz and subpictures) and at
 * most one video track, eight audio tracks and 32 subpicture tracks.
 *
 * <p>Packing. Packets of every track are interleaved by decoding time, as {@link TsMuxer} interleaves them. Each pack
 * holds a pack header and one PES packet (the first pack, and the packs that open a video key frame, also the system
 * header and the program stream map). A video access unit and a subpicture unit start a PES packet that carries their
 * timestamps; audio frames are gathered into packs filled up to {@link PsMuxerOptions#packBytes()} bytes, each PES
 * packet carrying the timestamps of the first frame that starts in it, for at most 100 ms. The first PES packet of
 * every stream carries its P-STD buffer size. With {@linkplain PsMuxerOptions#fixedPackSize() fixed packs} each pack
 * is filled to the pack size with PES header stuffing (up to seven bytes) or a padding packet.
 *
 * <p>Timing. A video track whose packets carry presentation timestamps only gets decoding times from its sorted
 * presentation times, as {@link TsMuxer} gives them. Timestamps become 90 kHz PES timestamps shifted so that the first
 * decoding time equals {@link PsMuxerOptions#delayMicros()}, modulo 2^33. The system clock reference of each pack is
 * the decoding time of the access unit its PES packet continues less the delay, and at least that of the previous
 * pack plus the time the previous pack takes at {@link PsMuxerOptions#muxRate()}, which every pack header and the
 * system header declare.
 *
 * <p>Navigation packs. Each video object unit, from the first pack and then from every video key frame (every second
 * of the timeline without a video track), opens with a navigation pack: the system header of DVD-Video and the
 * presentation control and data search information packets of private stream 2. Their general information holds the
 * logical block number of the pack, its system clock reference, the start and end presentation times of the unit,
 * the cell elapsed time, the relative address of the unit's last pack and of the packs that end its first three
 * reference pictures; the highlight, seamless playback, search and synchronous information are zero. Each unit is held
 * in memory until the next one starts, for those addresses.
 *
 * <p>The output is written in order and the sink is never repositioned, so a streaming sink works. Instances are not
 * thread-safe.
 *
 * @implNote This implementation paces the system clock references from decoding times rather than modeling the
 * system target decoder buffers, which players and authoring tools do not need; a stream whose content exceeds the mux
 * rate gets clock references that lag its decoding times rather than an error.
 */
public final class PsMuxer implements Muxer {
    /**
     * The 90 kHz PES time base.
     */
    private static final Rational TICKS = Rational.timeBaseOf(90_000);

    /**
     * The mask of the 33-bit timestamps.
     */
    private static final long WRAP_MASK = (1L << 33) - 1;

    /**
     * The longest stretch of decoding times held for the interleaving.
     */
    private static final long MAX_INTERLEAVE_MICROS = 1_000_000;

    /**
     * The most packet bytes held for the interleaving.
     */
    private static final long MAX_QUEUED_BYTES = 64L << 20;

    /**
     * The number of packets of a track with presentation timestamps only that are held to measure its reordering.
     */
    private static final int REORDER_WINDOW = 16;

    /**
     * The longest time audio frames are gathered before their pack is written, in 90 kHz ticks.
     */
    private static final long GATHER_TICKS = 9_000;

    /**
     * The longest video object unit without a video track, in 90 kHz ticks.
     */
    private static final long AUDIO_VOBU_TICKS = 90_000;

    /**
     * The output buffer size above which the buffer is written to the sink.
     */
    private static final int FLUSH_BYTES = 256 * 1024;

    /**
     * The access unit delimiter of H.264 access units.
     */
    private static final byte[] AVC_DELIMITER = {0, 0, 0, 1, 0x09, (byte) 0xF0};

    /**
     * The access unit delimiter of HEVC access units.
     */
    private static final byte[] HEVC_DELIMITER = {0, 0, 0, 1, 0x46, 0x01, 0x50};

    /**
     * The access unit delimiter of VVC access units that are not IRAP or GDR access units.
     */
    private static final byte[] VVC_DELIMITER = {0, 0, 0, 1, 0x00, (byte) 0xA1, 0x28};

    /**
     * The access unit delimiter of VVC IRAP and GDR access units ({@code aud_irap_or_gdr_flag} set).
     */
    private static final byte[] VVC_KEY_DELIMITER = {0, 0, 0, 1, 0x00, (byte) 0xA1, (byte) 0xA8};

    /**
     * The length of the presentation control information packet's payload, its substream byte included.
     */
    private static final int PCI_LENGTH = 980;

    /**
     * The length of the data search information packet's payload, its substream byte included.
     */
    private static final int DSI_LENGTH = 1018;

    /**
     * The system header of DVD-Video navigation packs: every video stream, every audio stream, private stream 1 and
     * private stream 2 with their P-STD buffer bounds, and the rate bound of DVD-Video.
     */
    private static final byte[] DVD_SYSTEM_HEADER = {
            0, 0, 1, (byte) 0xBB, 0, 18, (byte) 0x80, (byte) 0xC4, (byte) 0xE1, 0x04, (byte) 0xE1, (byte) 0xFF,
            (byte) 0xB9, (byte) 0xE0, (byte) 0xE8, (byte) 0xB8, (byte) 0xC0, 0x20, (byte) 0xBD, (byte) 0xE0, 0x3A,
            (byte) 0xBF, (byte) 0xE0, 0x02
    };

    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The settings.
     */
    private final PsMuxerOptions options;

    /**
     * The streams, in track order.
     */
    private final List<Stream> streams = new ArrayList<>();

    /**
     * The bytes not yet written to the sink.
     */
    private final ByteWriter buffer = new ByteWriter(FLUSH_BYTES + 4096);

    /**
     * The pack being built.
     */
    private final ByteWriter pack = new ByteWriter(PsMuxerOptions.MAX_PACK_BYTES + 64);

    /**
     * The offset added to every 90 kHz timestamp, or {@link Long#MIN_VALUE} before the first packet.
     */
    private long shift = Long.MIN_VALUE;

    /**
     * The system clock reference of the last pack, in 27 MHz ticks, or {@code -1} before the first.
     */
    private long scr = -1;

    /**
     * The size of the last pack.
     */
    private int lastPackBytes;

    /**
     * The number of packs written, which numbers the logical blocks of a DVD-Video object.
     */
    private long packs;

    /**
     * Whether the next pack carries the system header and the program stream map.
     */
    private boolean headersDue = true;

    /**
     * Whether a program stream map is written.
     */
    private boolean mapped;

    /**
     * The system header and program stream map, or {@code null} until first needed.
     */
    private byte[] headers;

    /**
     * The video object unit being collected, or {@code null}.
     */
    private Vobu vobu;

    /**
     * The packs of the video object unit being collected, the navigation pack first, or {@code null} before the
     * first unit.
     */
    private ByteWriter vobuPacks;

    /**
     * The system header of the navigation packs, or {@code null} until first needed.
     */
    private byte[] navigationHeader;

    /**
     * The start presentation time of the first video object unit, in 90 kHz ticks, or {@link Long#MIN_VALUE}.
     */
    private long cellStart = Long.MIN_VALUE;

    /**
     * The newest decoding time queued, in microseconds.
     */
    private long newestMicros = Long.MIN_VALUE;

    /**
     * The bytes of the queued packets.
     */
    private long queuedBytes;

    /**
     * The newest decoding time written, in 90 kHz ticks with the shift applied.
     */
    private long writtenDts = Long.MIN_VALUE;

    /**
     * Whether a packet was written.
     */
    private boolean started;

    /**
     * Whether the muxer is finished.
     */
    private boolean finished;

    /**
     * Constructs a muxer with {@link PsMuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position and not closed by the muxer
     */
    public PsMuxer(MediaSink output) {
        this(output, PsMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer.
     *
     * @param output the sink, written from its position and not closed by the muxer
     * @param options the settings
     */
    public PsMuxer(MediaSink output, PsMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns whether program streams written by this muxer with its default options can carry a codec.
     *
     * @param codec the codec of a track to add
     * @return {@code true} for MPEG-1 and MPEG-2 video, MPEG-4 Part 2, H.264, HEVC, VVC, MP2, MP3, AAC, AC-3, E-AC-3,
     * DTS, 16-bit and 24-bit PCM and DVD subtitles
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case MPEG1_VIDEO, MPEG2_VIDEO, MPEG4_PART2, H264, HEVC, VVC, MP2, MP3, AAC, AC3, EAC3, DTS, PCM_S16BE, PCM_S16LE,
                 PCM_S24BE, PCM_S24LE, DVD_SUBTITLE -> true;
            default -> false;
        };
    }

    /**
     * Returns whether program streams written by this muxer with some options can carry a track.
     *
     * <p>A PCM track is carried when it has one to eight channels at 48, 96, 44.1 or 32 kHz, with navigation packs
     * at 48 or 96 kHz only; any other track when {@link #supports(Codec)} accepts its codec and, with the MPEG-1
     * syntax or navigation packs, when that variant carries it.
     *
     * @param track the track to add
     * @param options the options of the muxer
     * @return {@code true} when the track can be carried
     */
    public static boolean supports(Track track, PsMuxerOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        var codec = track.codec();
        if (!supports(codec)) {
            return false;
        }
        if (options.syntax() == PsMuxerOptions.Syntax.MPEG1) {
            return switch (codec) {
                case MPEG1_VIDEO, MPEG2_VIDEO, MP2, MP3 -> true;
                default -> false;
            };
        }
        if (options.navigationPacks()) {
            var dvd = switch (codec) {
                case MPEG1_VIDEO, MPEG2_VIDEO, MP2, AC3, DTS, PCM_S16BE, PCM_S16LE, PCM_S24BE, PCM_S24LE,
                     DVD_SUBTITLE -> true;
                default -> false;
            };
            if (!dvd) {
                return false;
            }
        }
        if (!codec.isPcm()) {
            return true;
        }
        if (!(track instanceof AudioTrack audio)) {
            return false;
        }
        var channels = audio.layout().map(layout -> layout.channels()).orElse(0);
        var rate = audio.sampleRate();
        var rateOk = rate == 48_000 || rate == 96_000
                || !options.navigationPacks() && (rate == 44_100 || rate == 32_000);
        return channels >= 1 && channels <= 8 && rateOk;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the id is already used, the track cannot be carried
     * ({@link #supports(Track, PsMuxerOptions)}), its configuration is missing or unusable (an H.264, HEVC, VVC,
     * MPEG-4 Part 2 or AAC track without one), or the track would exceed the stream ids of its kind or, with navigation
     * packs, the one video track or eight audio tracks of DVD-Video
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (!supports(track, options)) {
            throw new IllegalArgumentException("program streams written with these options cannot carry "
                    + track.codec().displayName() + (track.codec().isPcm() ? " in this sample format" : ""));
        }
        for (var stream : streams) {
            if (stream.track.id() == track.id()) {
                throw new IllegalArgumentException("track id " + track.id() + " is already declared");
            }
        }
        var stream = new Stream(track);
        configure(stream);
        streams.add(stream);
        mapped = options.syntax() == PsMuxerOptions.Syntax.MPEG2
                && (options.programStreamMap() || streams.stream().anyMatch(other -> other.needsMap));
    }

    /**
     * Assigns the stream id, substream id, stream type and codec state of a stream.
     *
     * @param stream the stream to configure from its track
     * @throws IllegalArgumentException if the configuration is missing or the stream ids of its kind are taken
     */
    private void configure(Stream stream) {
        var track = stream.track;
        var config = track.codecConfig().orElse(null);
        switch (track.codec()) {
            case MPEG1_VIDEO, MPEG2_VIDEO -> {
                stream.streamId = nextId(0xE0, 16, false, track);
                stream.streamType = track.codec() == Codec.MPEG1_VIDEO ? 0x01 : 0x02;
                stream.parameterSets = config == null ? new byte[0] : config;
                stream.bufferScale = 1;
                stream.bufferSize = 232;
            }
            case MPEG4_PART2 -> {
                if (config == null || config.length == 0) {
                    throw new IllegalArgumentException("an MPEG-4 Part 2 track needs its decoder configuration");
                }
                stream.streamId = nextId(0xE0, 16, false, track);
                stream.streamType = 0x10;
                stream.parameterSets = config;
                stream.needsMap = true;
                stream.bufferScale = 1;
                stream.bufferSize = 232;
            }
            case H264, HEVC, VVC -> {
                var codec = track.codec();
                var parsed = parseNalConfig(codec, config);
                if (parsed == null) {
                    throw new IllegalArgumentException("a " + codec.displayName() + " track needs its "
                            + (codec == Codec.VVC ? "vvcC" : codec == Codec.HEVC ? "hvcC" : "avcC")
                            + " configuration");
                }
                stream.hevc = codec == Codec.HEVC;
                stream.lengthSize = parsed.lengthSize();
                stream.parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
                stream.streamId = nextId(0xE0, 16, false, track);
                stream.streamType = switch (codec) {
                    case HEVC -> 0x24;
                    case VVC -> 0x33;
                    default -> 0x1B;
                };
                stream.needsMap = true;
                stream.bufferScale = 1;
                stream.bufferSize = 1024;
            }
            case MP2, MP3 -> {
                var rate = track instanceof AudioTrack audio ? audio.sampleRate() : 0;
                stream.streamId = nextId(0xC0, options.navigationPacks() ? 8 : 32, false, track);
                stream.streamType = rate > 0 && rate < 32_000 ? 0x04 : 0x03;
                stream.bufferScale = 0;
                stream.bufferSize = 32;
            }
            case AAC -> {
                if (config == null || config.length < 2) {
                    throw new IllegalArgumentException("an AAC track needs its AudioSpecificConfig");
                }
                stream.adts = AdtsHeader.fromAudioSpecificConfig(config);
                stream.streamId = nextId(0xC0, 32, false, track);
                stream.streamType = 0x0F;
                stream.needsMap = true;
                stream.bufferScale = 0;
                stream.bufferSize = 32;
            }
            case AC3 -> substream(stream, 0x80, 8);
            case EAC3 -> substream(stream, 0xC0, 8);
            case DTS -> substream(stream, 0x88, 8);
            case PCM_S16BE, PCM_S16LE, PCM_S24BE, PCM_S24LE -> {
                substream(stream, 0xA0, 8);
                configureLpcm(stream);
            }
            case DVD_SUBTITLE -> substream(stream, 0x20, 32);
            default -> throw new IllegalArgumentException("program streams cannot carry "
                    + track.codec().displayName());
        }
        stream.acceptedConfig = config;
        stream.appliedConfig = config;
        if (options.navigationPacks() && track.kind() == TrackKind.VIDEO
                && streams.stream().anyMatch(other -> other.track.kind() == TrackKind.VIDEO)) {
            throw new IllegalArgumentException("a DVD-Video object has one video track");
        }
        if (options.navigationPacks() && track.kind() == TrackKind.AUDIO
                && streams.stream().filter(other -> other.track.kind() == TrackKind.AUDIO).count() >= 8) {
            throw new IllegalArgumentException("a DVD-Video object has at most eight audio tracks");
        }
    }

    /**
     * Makes a stream a DVD-Video substream of private stream 1.
     *
     * @param stream the stream to configure from its track
     * @param first the first {@code sub_stream_id} of its kind
     * @param count the number of ids of its kind
     * @throws IllegalArgumentException if they are all taken
     */
    private void substream(Stream stream, int first, int count) {
        stream.streamId = PsScanner.PRIVATE_1;
        stream.subId = nextId(first, count, true, stream.track);
        stream.bufferScale = 1;
        stream.bufferSize = 58;
    }

    /**
     * Returns the next free stream or substream id of a kind.
     *
     * @param first the first id of the kind
     * @param count the number of ids of the kind
     * @param sub whether the ids are substream ids
     * @param track the track, for the message
     * @return the id
     * @throws IllegalArgumentException if they are all taken
     */
    private int nextId(int first, int count, boolean sub, Track track) {
        for (var id = first; id < first + count; id++) {
            var taken = false;
            for (var other : streams) {
                if ((sub ? other.subId : other.streamId) == id && (!sub || other.streamId == PsScanner.PRIVATE_1)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return id;
            }
        }
        throw new IllegalArgumentException("a program stream carries at most " + count + " streams like track "
                + track.id() + " (" + track.codec().displayName() + ")");
    }

    /**
     * Fills in the linear PCM format of a stream.
     *
     * @param stream the PCM stream
     */
    private static void configureLpcm(Stream stream) {
        var audio = (AudioTrack) stream.track;
        var codec = audio.codec();
        var wide = codec == Codec.PCM_S24BE || codec == Codec.PCM_S24LE;
        stream.pcmBits = wide ? (audio.bitsPerSample() == 20 ? 20 : 24) : 16;
        stream.pcmWidth = wide ? 3 : 2;
        stream.pcmLittle = codec == Codec.PCM_S16LE || codec == Codec.PCM_S24LE;
        stream.pcmChannels = audio.layout().orElseThrow().channels();
        stream.pcmRate = audio.sampleRate();
        stream.pcmGroup = DvdLpcmParser.groupBytes(stream.pcmBits, stream.pcmChannels);
        stream.pcmGroupFrames = DvdLpcmParser.groupFrames(stream.pcmBits, stream.pcmChannels);
        stream.pcmFrameSamples = Math.ceilDiv(Math.ceilDiv(stream.pcmRate, 600), stream.pcmGroupFrames)
                * stream.pcmGroupFrames;
        stream.pcmCode = switch (stream.pcmRate) {
            case 48_000 -> 0;
            case 96_000 -> 1;
            case 44_100 -> 2;
            default -> 3;
        } << 4 | (stream.pcmBits == 16 ? 0 : stream.pcmBits == 20 ? 1 : 2) << 6 | (stream.pcmChannels - 1);
        stream.pending = new byte[stream.pcmWidth * stream.pcmChannels * stream.pcmGroupFrames];
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to no declared track, carries no timestamp, has a
     * malformed body for its codec (a PCM packet that is not whole sample frames, an AAC frame too long for ADTS), or
     * changes the codec configuration of a track whose codec has no in-band configuration change (only MPEG-1, MPEG-2
     * and MPEG-4 Part 2 video, H.264, HEVC, VVC and AAC change theirs)
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Io if writing the output fails
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
            throw new IllegalArgumentException("a program stream packet needs a timestamp");
        }
        if (stream.pcmBits > 0 && packet.size() % (stream.pcmWidth * stream.pcmChannels) != 0) {
            throw new IllegalArgumentException("a PCM packet of track " + packet.trackId()
                    + " must hold whole sample frames");
        }
        var newConfig = ConfigChanges.changed(packet, stream.acceptedConfig);
        if (newConfig != null) {
            switch (stream.track.codec()) {
                case H264, HEVC, VVC -> {
                    if (parseNalConfig(stream.track.codec(), newConfig) == null) {
                        throw new IllegalArgumentException("the new " + stream.track.codec().displayName()
                                + " configuration of track " + packet.trackId() + " cannot be parsed");
                    }
                }
                case MPEG4_PART2 -> {
                    if (newConfig.length == 0) {
                        throw new IllegalArgumentException("the new MPEG-4 Part 2 configuration of track "
                                + packet.trackId() + " is empty");
                    }
                }
                case MPEG1_VIDEO, MPEG2_VIDEO -> {
                    if (MpegVideoHeaders.find(newConfig) == null) {
                        throw new IllegalArgumentException("the new " + stream.track.codec().displayName()
                                + " configuration of track " + packet.trackId() + " holds no sequence header");
                    }
                }
                case AAC -> AdtsHeader.fromAudioSpecificConfig(newConfig);
                default -> throw ConfigChanges.unsupported("MPEG-PS", packet.trackId());
            }
            stream.acceptedConfig = newConfig;
        }
        started = true;
        if (stream.track.kind() == TrackKind.VIDEO && packet.dts() == Packet.NO_TIMESTAMP) {
            stream.window.add(packet);
            stream.pendingPts.add(packet.pts());
            if (stream.window.size() > REORDER_WINDOW) {
                release(stream);
            }
        } else {
            enqueue(stream, packet);
        }
        drain(false);
    }

    /**
     * Gives the oldest packet of a track with presentation timestamps only a decoding time and queues it.
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
        var dts = stream.pendingPts.poll() - stream.reorderDelay;
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
     * @param packet the packet to queue, with its decoding time set
     */
    private void enqueue(Stream stream, Packet packet) {
        var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        var micros = stream.track.timeBase().toMicros(time);
        stream.queue.add(new Queued(packet, micros));
        queuedBytes += packet.size();
        newestMicros = Math.max(newestMicros, micros);
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
            if (!all && !complete && newestMicros - head.micros <= MAX_INTERLEAVE_MICROS
                    && queuedBytes <= MAX_QUEUED_BYTES) {
                return;
            }
            oldest.queue.removeFirst();
            queuedBytes -= head.packet.size();
            emit(oldest, head.packet);
        }
    }

    /**
     * Turns one packet into the bytes of its stream and writes the packs they complete.
     *
     * @param stream the packet's stream
     * @param packet the packet to write
     */
    private void emit(Stream stream, Packet packet) {
        var timeBase = stream.track.timeBase();
        var pts = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var dts = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        var pts90 = timeBase.rescale(pts, TICKS);
        var dts90 = Math.min(timeBase.rescale(dts, TICKS), pts90);
        if (shift == Long.MIN_VALUE) {
            shift = options.delayMicros() * 9 / 100 - dts90;
        }
        var ptsOut = pts90 + shift;
        var dtsOut = dts90 + shift;
        writtenDts = Math.max(writtenDts, dtsOut);
        for (var other : streams) {
            if (other != stream && other.track.kind() == TrackKind.AUDIO) {
                gather(other, false);
            }
        }
        switch (stream.track.kind()) {
            case VIDEO -> {
                var payload = videoPayload(stream, packet);
                var reference = packet.keyframe() || isReference(stream, payload);
                if (options.navigationPacks() && packet.keyframe() && (vobu == null || vobu.hasVideo)) {
                    startVobu(dtsOut);
                }
                stream.append(payload, ptsOut, dtsOut);
                if (packet.keyframe() && !options.navigationPacks()) {
                    headersDue = true;
                }
                writeAll(stream, reference, ptsOut + Math.max(0, timeBase.rescale(packet.duration(), TICKS)));
            }
            case DATA -> {
                stream.append(packet.data(), ptsOut, ptsOut);
                writeAll(stream, false, Long.MIN_VALUE);
            }
            case AUDIO -> {
                if (stream.pcmBits > 0) {
                    appendPcm(stream, packet.data(), ptsOut);
                } else {
                    stream.append(audioFrame(stream, packet), ptsOut, ptsOut);
                }
                gather(stream, false);
            }
        }
    }

    /**
     * Returns whether a video frame is a reference picture: an I- or P-picture of MPEG-1 or MPEG-2 video.
     *
     * @param stream the video stream
     * @param payload the frame
     * @return {@code true} for a reference picture of MPEG-1 or MPEG-2 video
     */
    private static boolean isReference(Stream stream, byte[] payload) {
        if (stream.streamType != 0x01 && stream.streamType != 0x02) {
            return false;
        }
        var type = MpegVideoHeaders.firstPictureType(payload);
        return type == 1 || type == 2;
    }

    /**
     * Writes the gathered audio of a stream once a pack is full or its oldest frame waited long enough.
     *
     * @param stream the audio stream
     * @param all whether every gathered byte is written
     */
    private void gather(Stream stream, boolean all) {
        var due = all || stream.available() > 0 && writtenDts - headDts(stream) >= GATHER_TICKS;
        while (stream.available() > 0 && (due || stream.available() >= budget(stream, true, false))) {
            writePack(stream);
        }
    }

    /**
     * Writes every byte of a stream, the last pack possibly short.
     *
     * @param stream the stream whose bytes are written
     * @param reference whether the access unit is a reference picture, whose end a navigation pack locates
     * @param end the end of the access unit's presentation, or {@link Long#MIN_VALUE} for no video
     */
    private void writeAll(Stream stream, boolean reference, long end) {
        while (stream.available() > 0) {
            writePack(stream);
        }
        if (vobu != null && end != Long.MIN_VALUE) {
            vobu.hasVideo = true;
            var start = stream.lastPts;
            vobu.startPts = vobu.startPts == Long.MIN_VALUE ? start : Math.min(vobu.startPts, start);
            vobu.endPts = Math.max(vobu.endPts, end);
            if (reference && vobu.references < 3) {
                vobu.referenceEnds[vobu.references++] = packs - 1 - vobu.navLbn;
            }
        }
    }

    /**
     * Returns the payload a PES packet of a stream can carry in one pack.
     *
     * @param stream the stream whose PES packets are sized
     * @param timestamps whether the PES header carries a presentation time
     * @param decoding whether it also carries a decoding time
     * @return the payload bytes after any substream header
     */
    private int budget(Stream stream, boolean timestamps, boolean decoding) {
        var mpeg1 = options.syntax() == PsMuxerOptions.Syntax.MPEG1;
        var size = options.packBytes() - (mpeg1 ? 12 : 14) - (headersDue ? headers().length : 0) - 6
                - pesHeader(stream, timestamps, decoding, 0) - substreamHeader(stream);
        if (stream.pcmBits > 0) {
            size -= size % stream.pcmGroup;
        }
        return Math.min(size, 0xFFFF - 64);
    }

    /**
     * Returns the length of a PES header after its six-byte prefix, without the substream header.
     *
     * @param stream the stream, whose first PES packet carries its P-STD buffer size
     * @param timestamps whether the header carries a presentation time
     * @param decoding whether it also carries a decoding time
     * @param stuffing the number of stuffing bytes
     * @return the length in bytes
     */
    private int pesHeader(Stream stream, boolean timestamps, boolean decoding, int stuffing) {
        var times = timestamps ? (decoding ? 10 : 5) : 0;
        if (options.syntax() == PsMuxerOptions.Syntax.MPEG1) {
            return stuffing + (stream.firstPes ? 2 : 0) + (timestamps ? times : 1);
        }
        return 3 + times + (stream.firstPes ? 3 : 0) + stuffing;
    }

    /**
     * Returns the length of the substream header of a stream's PES packets.
     *
     * @param stream the stream whose PES packets carry the substream header
     * @return the bytes after the PES header and before the stream's data
     */
    private static int substreamHeader(Stream stream) {
        if (stream.subId < 0) {
            return 0;
        }
        if (stream.pcmBits > 0) {
            return 1 + DvdLpcmParser.HEADER;
        }
        return stream.track.kind() == TrackKind.DATA ? 1 : 4;
    }

    /**
     * Writes one pack holding as many of a stream's bytes as fit.
     *
     * @param stream the stream whose bytes the pack carries
     */
    private void writePack(Stream stream) {
        if (options.navigationPacks() && vobu == null) {
            startVobu(headDts(stream));
        } else if (options.navigationPacks() && !hasVideoTrack() && writtenDts - vobu.startDts >= AUDIO_VOBU_TICKS) {
            startVobu(writtenDts);
        }
        var available = stream.available();
        var plain = Math.min(available, budget(stream, false, false));
        var first = firstStart(stream, plain);
        var decoding = first != null && first.dts != first.pts;
        var take = first == null ? plain : Math.min(available, budget(stream, true, decoding));
        if (stream.pcmBits > 0) {
            take -= take % stream.pcmGroup;
        }
        if (take <= 0) {
            throw new IllegalStateException("a pack of " + options.packBytes() + " bytes leaves no room for data");
        }
        if (first != null && first.offset - stream.head >= take) {
            first = null;
            decoding = false;
        }
        var timed = first != null;
        var mpeg1 = options.syntax() == PsMuxerOptions.Syntax.MPEG1;
        var headerBytes = headersDue ? headers().length : 0;
        var pesBytes = 6 + pesHeader(stream, timed, decoding, 0) + substreamHeader(stream) + take;
        var stuffing = 0;
        var padding = 0;
        if (options.fixedPackSize()) {
            var leftover = options.packBytes() - (mpeg1 ? 12 : 14) - headerBytes - pesBytes;
            if (leftover >= 7) {
                padding = leftover;
            } else {
                stuffing = leftover;
            }
        }
        pack.reset();
        writePackHeader(headDts(stream));
        if (headersDue) {
            pack.bytes(headers());
            headersDue = false;
        }
        writePes(stream, take, first, stuffing);
        if (padding > 0) {
            writePadding(padding);
        }
        commitPack();
        consume(stream, take);
    }

    /**
     * Returns the first access unit (or, for linear PCM, the first audio frame) that starts within the first bytes of
     * a stream's data.
     *
     * @param stream the stream whose next start is looked for
     * @param within the number of bytes considered
     * @return the start, or {@code null} when none starts there
     */
    private static Start firstStart(Stream stream, int within) {
        var start = stream.starts.peekFirst();
        return start != null && start.offset - stream.head < within ? start : null;
    }

    /**
     * Returns the system header and the program stream map, when one is written, that the first pack and the pack
     * of every video key frame carry.
     *
     * @return the bytes, built when first needed
     */
    private byte[] headers() {
        if (headers == null) {
            var system = systemHeader();
            headers = mapped ? join(system, programStreamMap()) : system;
        }
        return headers;
    }

    /**
     * Appends a pack header with the system clock reference of a pack carrying an access unit.
     *
     * @param dts the decoding time of the access unit, with the shift applied
     */
    private void writePackHeader(long dts) {
        var due = Math.max(0, dts - options.delayMicros() * 9 / 100) * 300;
        var next = scr < 0 ? due : Math.max(due, scr + transfer(lastPackBytes));
        scr = next;
        var base = (next / 300) & WRAP_MASK;
        var extension = next % 300;
        var rate = options.muxRate() / 400;
        pack.u8(0);
        pack.u8(0);
        pack.u8(1);
        pack.u8(0xBA);
        if (options.syntax() == PsMuxerOptions.Syntax.MPEG1) {
            pack.u8((int) (0x21 | ((base >> 30) & 7) << 1));
            pack.u8((int) ((base >> 22) & 0xFF));
            pack.u8((int) (((base >> 15) & 0x7F) << 1 | 1));
            pack.u8((int) ((base >> 7) & 0xFF));
            pack.u8((int) ((base & 0x7F) << 1 | 1));
            pack.u8((int) (0x80 | (rate >> 15) & 0x7F));
            pack.u8((int) ((rate >> 7) & 0xFF));
            pack.u8((int) ((rate & 0x7F) << 1 | 1));
            return;
        }
        pack.u8((int) (0x44 | ((base >> 30) & 7) << 3 | ((base >> 28) & 3)));
        pack.u8((int) ((base >> 20) & 0xFF));
        pack.u8((int) (((base >> 15) & 0x1F) << 3 | 0x04 | ((base >> 13) & 3)));
        pack.u8((int) ((base >> 5) & 0xFF));
        pack.u8((int) ((base & 0x1F) << 3 | 0x04 | ((extension >> 7) & 3)));
        pack.u8((int) ((extension & 0x7F) << 1 | 1));
        pack.u8((int) ((rate >> 14) & 0xFF));
        pack.u8((int) ((rate >> 6) & 0xFF));
        pack.u8((int) ((rate & 0x3F) << 2 | 0x03));
        pack.u8(0xF8);
    }

    /**
     * Returns the time a pack takes to arrive at the mux rate.
     *
     * @param bytes the size of the pack
     * @return the time in 27 MHz ticks, rounded up
     */
    private long transfer(int bytes) {
        return Math.ceilDiv(bytes * 8L * 27_000_000L, options.muxRate());
    }

    /**
     * Builds the system header of the streams.
     *
     * @return the header
     */
    private byte[] systemHeader() {
        if (options.navigationPacks()) {
            return navigationHeader();
        }
        var ids = new ArrayList<Stream>();
        var audio = 0;
        var video = 0;
        for (var stream : streams) {
            if (stream.track.kind() == TrackKind.AUDIO) {
                audio++;
            } else if (stream.track.kind() == TrackKind.VIDEO) {
                video++;
            }
            var listed = false;
            for (var other : ids) {
                listed |= other.streamId == stream.streamId;
            }
            if (!listed) {
                ids.add(stream);
            }
        }
        var header = new byte[12 + 3 * ids.size()];
        var rate = options.muxRate() / 400;
        header[2] = 1;
        header[3] = (byte) 0xBB;
        header[4] = (byte) ((header.length - 6) >> 8);
        header[5] = (byte) (header.length - 6);
        header[6] = (byte) (0x80 | (rate >> 15) & 0x7F);
        header[7] = (byte) (rate >> 7);
        header[8] = (byte) ((rate & 0x7F) << 1 | 1);
        header[9] = (byte) (Math.min(audio, 32) << 2);
        header[10] = (byte) (0xE0 | Math.min(video, 16));
        header[11] = (byte) (options.syntax() == PsMuxerOptions.Syntax.MPEG1 ? 0xFF : 0x7F);
        var position = 12;
        for (var stream : ids) {
            var scale = stream.streamId == PsScanner.PRIVATE_1 ? 1 : stream.bufferScale;
            var size = stream.streamId == PsScanner.PRIVATE_1 ? 58 : stream.bufferSize;
            header[position] = (byte) stream.streamId;
            header[position + 1] = (byte) (0xC0 | scale << 5 | (size >> 8) & 0x1F);
            header[position + 2] = (byte) size;
            position += 3;
        }
        return header;
    }

    /**
     * Returns the system header of the navigation packs: that of DVD-Video, its rate bound raised to the mux rate
     * when that is higher.
     *
     * @return the header, built when first needed
     */
    private byte[] navigationHeader() {
        if (navigationHeader == null) {
            var header = DVD_SYSTEM_HEADER.clone();
            var rate = Math.max(PsMuxerOptions.DVD_MUX_RATE, options.muxRate()) / 400;
            header[6] = (byte) (0x80 | (rate >> 15) & 0x7F);
            header[7] = (byte) (rate >> 7);
            header[8] = (byte) ((rate & 0x7F) << 1 | 1);
            navigationHeader = header;
        }
        return navigationHeader;
    }

    /**
     * Builds the program stream map of the streams: every stream but private stream 1, with the ISO 639 language
     * descriptor of the tracks that have a language.
     *
     * @return the map
     */
    private byte[] programStreamMap() {
        var entries = new ByteWriter(64);
        for (var stream : streams) {
            if (stream.streamId == PsScanner.PRIVATE_1) {
                continue;
            }
            entries.u8(stream.streamType);
            entries.u8(stream.streamId);
            var language = stream.track.language().map(code -> code.toLowerCase(Locale.ROOT)).orElse(null);
            if (language != null && language.length() == 3 && language.chars().allMatch(c -> c >= 'a' && c <= 'z')) {
                entries.u16be(6);
                entries.u8(0x0A);
                entries.u8(4);
                entries.bytes(language.getBytes(StandardCharsets.US_ASCII));
                entries.u8(0);
            } else {
                entries.u16be(0);
            }
        }
        var body = entries.toByteArray();
        var map = new byte[16 + body.length];
        map[2] = 1;
        map[3] = (byte) 0xBC;
        map[4] = (byte) ((map.length - 6) >> 8);
        map[5] = (byte) (map.length - 6);
        map[6] = (byte) 0xA0;
        map[7] = (byte) 0xFF;
        map[10] = (byte) (body.length >> 8);
        map[11] = (byte) body.length;
        System.arraycopy(body, 0, map, 12, body.length);
        var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, map, 0, map.length - 4);
        map[map.length - 4] = (byte) (crc >>> 24);
        map[map.length - 3] = (byte) (crc >>> 16);
        map[map.length - 2] = (byte) (crc >>> 8);
        map[map.length - 1] = (byte) crc;
        return map;
    }

    /**
     * Appends one PES packet of a stream to the pack.
     *
     * @param stream the stream whose bytes were written
     * @param take the number of the stream's bytes it carries
     * @param first the access unit or audio frame whose timestamps it carries, or {@code null}
     * @param stuffing the number of stuffing bytes in its header
     */
    private void writePes(Stream stream, int take, Start first, int stuffing) {
        var mpeg1 = options.syntax() == PsMuxerOptions.Syntax.MPEG1;
        var timed = first != null;
        var decoding = timed && first.dts != first.pts;
        var header = pesHeader(stream, timed, decoding, stuffing);
        pack.u8(0);
        pack.u8(0);
        pack.u8(1);
        pack.u8(stream.streamId);
        pack.u16be(header + substreamHeader(stream) + take);
        if (mpeg1) {
            for (var index = 0; index < stuffing; index++) {
                pack.u8(0xFF);
            }
            if (stream.firstPes) {
                pack.u8(0x40 | stream.bufferScale << 5 | (stream.bufferSize >> 8) & 0x1F);
                pack.u8(stream.bufferSize & 0xFF);
            }
            if (timed) {
                timestamp(decoding ? 0x30 : 0x20, first.pts);
                if (decoding) {
                    timestamp(0x10, first.dts);
                }
            } else {
                pack.u8(0x0F);
            }
        } else {
            var aligned = timed && first.offset == stream.head && stream.track.kind() != TrackKind.AUDIO;
            pack.u8(0x81 | (aligned ? 0x04 : 0));
            pack.u8((timed ? (decoding ? 0xC0 : 0x80) : 0) | (stream.firstPes ? 0x01 : 0));
            pack.u8(header - 3);
            if (timed) {
                timestamp(decoding ? 0x30 : 0x20, first.pts);
                if (decoding) {
                    timestamp(0x10, first.dts);
                }
            }
            if (stream.firstPes) {
                pack.u8(0x1E);
                pack.u8(0x40 | stream.bufferScale << 5 | (stream.bufferSize >> 8) & 0x1F);
                pack.u8(stream.bufferSize & 0xFF);
            }
            for (var index = 0; index < stuffing; index++) {
                pack.u8(0xFF);
            }
        }
        stream.firstPes = false;
        if (stream.subId >= 0) {
            pack.u8(stream.subId);
            if (stream.pcmBits > 0) {
                lpcmHeader(stream, take);
            } else if (stream.track.kind() != TrackKind.DATA) {
                var count = 0;
                for (var start : stream.starts) {
                    if (start.offset - stream.head >= take) {
                        break;
                    }
                    count++;
                }
                pack.u8(Math.min(count, 255));
                pack.u16be(timed ? first.offset - stream.head + 1 : 0);
            }
        }
        pack.bytes(stream.data, stream.head, take);
    }

    /**
     * Appends the six bytes of a linear PCM substream header after its identifier.
     *
     * @param stream the PCM stream
     * @param take the number of sample bytes the PES packet carries
     */
    private void lpcmHeader(Stream stream, int take) {
        var startSample = stream.pcmSample;
        var samples = (long) take / stream.pcmGroup * stream.pcmGroupFrames;
        var frame = stream.pcmFrameSamples;
        var boundary = Math.ceilDiv(startSample, frame) * frame;
        var count = boundary < startSample + samples ? (int) ((startSample + samples - 1 - boundary) / frame + 1) : 0;
        var pointer = count > 0 ? (int) ((boundary - startSample) / stream.pcmGroupFrames * stream.pcmGroup) + 4 : 0;
        pack.u8(Math.min(count, 255));
        pack.u16be(pointer);
        pack.u8((int) ((boundary / frame) % 20));
        pack.u8(stream.pcmCode);
        pack.u8(0x80);
    }

    /**
     * Appends a 33-bit timestamp with its four-bit prefix and marker bits.
     *
     * @param prefix the prefix in the high nibble of the first byte
     * @param value the timestamp, before the 33-bit wrap
     */
    private void timestamp(int prefix, long value) {
        var masked = value & WRAP_MASK;
        pack.u8((int) (prefix | ((masked >> 30) & 7) << 1 | 1));
        pack.u16be((int) (((masked >> 15) & 0x7FFF) << 1 | 1));
        pack.u16be((int) ((masked & 0x7FFF) << 1 | 1));
    }

    /**
     * Appends a padding packet.
     *
     * @param total the size of the packet, at least 7 bytes
     */
    private void writePadding(int total) {
        pack.u8(0);
        pack.u8(0);
        pack.u8(1);
        pack.u8(0xBE);
        pack.u16be(total - 6);
        for (var index = 6; index < total; index++) {
            pack.u8(0xFF);
        }
    }

    /**
     * Moves the pack built to the output, or to the video object unit being collected.
     */
    private void commitPack() {
        lastPackBytes = pack.size();
        if (vobu != null) {
            vobu.packs.bytes(pack.buffer(), 0, pack.size());
        } else {
            buffer.bytes(pack.buffer(), 0, pack.size());
            if (buffer.size() >= FLUSH_BYTES) {
                buffer.writeTo(output);
                buffer.reset();
            }
        }
        packs++;
    }

    /**
     * Drops the first bytes of a stream's data, with the starts within them.
     *
     * @param stream the video stream that holds the configuration
     * @param take the number of bytes written
     */
    private static void consume(Stream stream, int take) {
        stream.head += take;
        if (stream.pcmBits > 0) {
            stream.pcmSample += (long) take / stream.pcmGroup * stream.pcmGroupFrames;
        }
        while (!stream.starts.isEmpty() && stream.starts.peekFirst().offset < stream.head) {
            stream.currentDts = stream.starts.pollFirst().dts;
        }
        if (stream.head == stream.length) {
            stream.compact();
        }
    }

    /**
     * Closes the video object unit being collected and opens the next with a navigation pack.
     *
     * @param dts the decoding time of the unit's first access unit, with the shift applied
     */
    private void startVobu(long dts) {
        closeVobu();
        if (vobuPacks == null) {
            vobuPacks = new ByteWriter(1 << 20);
        }
        vobuPacks.reset();
        vobu = new Vobu(vobuPacks);
        vobu.navLbn = packs;
        vobu.startDts = dts;
        pack.reset();
        writePackHeader(dts);
        vobu.scr = scr;
        pack.bytes(navigationHeader());
        vobu.pciOffset = pack.size();
        pack.u8(0);
        pack.u8(0);
        pack.u8(1);
        pack.u8(0xBF);
        pack.u16be(PCI_LENGTH);
        pack.u8(0x00);
        pack.zeros(PCI_LENGTH - 1);
        vobu.dsiOffset = pack.size();
        pack.u8(0);
        pack.u8(0);
        pack.u8(1);
        pack.u8(0xBF);
        pack.u16be(DSI_LENGTH);
        pack.u8(0x01);
        pack.zeros(DSI_LENGTH - 1);
        headersDue = false;
        commitPack();
    }

    /**
     * Fills in the navigation pack of the video object unit being collected and writes the unit.
     */
    private void closeVobu() {
        if (vobu == null) {
            return;
        }
        var bytes = vobu.packs.buffer();
        var pci = vobu.pciOffset + 7;
        var dsi = vobu.dsiOffset + 7;
        var startPts = vobu.startPts == Long.MIN_VALUE ? vobu.startDts : vobu.startPts;
        var endPts = vobu.endPts == Long.MIN_VALUE ? startPts : vobu.endPts;
        if (cellStart == Long.MIN_VALUE) {
            cellStart = startPts;
        }
        var units = packs - vobu.navLbn;
        put32(bytes, pci, vobu.navLbn);
        put32(bytes, pci + 12, startPts & 0xFFFFFFFFL);
        put32(bytes, pci + 16, endPts & 0xFFFFFFFFL);
        put32(bytes, dsi, (vobu.scr / 300) & 0xFFFFFFFFL);
        put32(bytes, dsi + 4, vobu.navLbn);
        put32(bytes, dsi + 8, units - 1);
        for (var index = 0; index < vobu.references; index++) {
            put32(bytes, dsi + 12 + 4 * index, vobu.referenceEnds[index]);
        }
        bytes[dsi + 24] = 0;
        bytes[dsi + 25] = 1;
        bytes[dsi + 27] = 1;
        // TODO: fill VOBU_SRI with the addresses of the units around this one (the next units need the output held or
        // a seekable sink), and the highlight and synchronous information

        elapsed(bytes, pci + 24, startPts - cellStart);
        elapsed(bytes, dsi + 28, startPts - cellStart);
        if (buffer.size() > 0) {
            buffer.writeTo(output);
            buffer.reset();
        }
        vobu.packs.writeTo(output);
        vobu = null;
    }

    /**
     * Writes a cell elapsed time: hours, minutes, seconds and frames in BCD, the frame byte's top bits giving the
     * frame rate (25 or 29.97 Hz).
     *
     * @param bytes the navigation pack
     * @param offset the index of the four bytes
     * @param ticks the elapsed time in 90 kHz ticks
     */
    private void elapsed(byte[] bytes, int offset, long ticks) {
        var video = streams.stream().filter(stream -> stream.track.kind() == TrackKind.VIDEO).findFirst();
        var rate = video.flatMap(stream -> ((VideoTrack) stream.track).frameRate())
                .orElse(new Rational(25, 1));
        var ntsc = rate.numerator() * 1001 == rate.denominator() * 30000L
                || rate.numerator() == 30 * rate.denominator();
        var fps = ntsc ? 30 : 25;
        var total = Math.max(0, ticks);
        var seconds = total / 90_000;
        var frames = (int) ((total % 90_000) * fps / 90_000);
        bytes[offset] = bcd((int) Math.min(99, seconds / 3600));
        bytes[offset + 1] = bcd((int) (seconds / 60 % 60));
        bytes[offset + 2] = bcd((int) (seconds % 60));
        bytes[offset + 3] = (byte) ((ntsc ? 0xC0 : 0x40) | bcd(frames) & 0x3F);
    }

    /**
     * Encodes a number from 0 to 99 in binary-coded decimal.
     *
     * @param value the number
     * @return the two digits
     */
    private static byte bcd(int value) {
        return (byte) ((value / 10) << 4 | (value % 10));
    }

    /**
     * Stores a big-endian 32-bit value.
     *
     * @param bytes the navigation pack being completed
     * @param offset the index of the first byte
     * @param value the value, its low 32 bits stored
     */
    private static void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    /**
     * Returns whether a video track is declared.
     *
     * @return {@code true} when one is
     */
    private boolean hasVideoTrack() {
        for (var stream : streams) {
            if (stream.track.kind() == TrackKind.VIDEO) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the bytes a video packet contributes.
     *
     * @param stream the video stream
     * @param packet the video packet
     * @return the access unit
     */
    private static byte[] videoPayload(Stream stream, Packet packet) {
        var newConfig = ConfigChanges.changed(packet, stream.appliedConfig);
        if (newConfig != null) {
            var codec = stream.track.codec();
            if (codec == Codec.H264 || codec == Codec.HEVC || codec == Codec.VVC) {
                var parsed = parseNalConfig(codec, newConfig);
                stream.lengthSize = parsed.lengthSize();
                stream.parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
            } else {
                stream.parameterSets = newConfig;
            }
            stream.appliedConfig = newConfig;
        }
        var data = packet.data();
        return switch (stream.track.codec()) {
            case H264, HEVC -> NalUnits.toAnnexB(data, stream.lengthSize, stream.hevc,
                    packet.keyframe() ? stream.parameterSets : null, stream.hevc ? HEVC_DELIMITER : AVC_DELIMITER);
            case VVC -> NalUnits.toAnnexB(data, stream.lengthSize, Codec.VVC,
                    packet.keyframe() ? stream.parameterSets : null,
                    packet.keyframe() ? VVC_KEY_DELIMITER : VVC_DELIMITER);
            case MPEG4_PART2 -> packet.keyframe() && !startsWithMpeg4Headers(data) ? join(stream.parameterSets, data)
                    : data;
            default -> !packet.keyframe() || stream.parameterSets.length == 0
                    || MpegVideoHeaders.startsWithSequenceHeader(data) ? data : join(stream.parameterSets, data);
        };
    }

    /**
     * Returns whether an MPEG-4 Part 2 frame starts with a visual object sequence, visual object or video object
     * layer header.
     *
     * @param data the frame
     * @return {@code true} when its first start code is one of those headers
     */
    private static boolean startsWithMpeg4Headers(byte[] data) {
        for (var index = 0; index + 3 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) {
                var code = data[index + 3] & 0xFF;
                return code == 0xB0 || code == 0xB5 || code <= 0x2F;
            }
        }
        return false;
    }

    /**
     * Concatenates two byte arrays.
     *
     * @param head the first bytes
     * @param tail the bytes after them
     * @return the concatenation
     */
    private static byte[] join(byte[] head, byte[] tail) {
        var joined = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    /**
     * Frames an audio packet for its stream.
     *
     * @param stream the audio stream
     * @param packet the audio packet to frame
     * @return the bytes the stream carries for it
     * @throws IllegalArgumentException if an AAC frame does not fit an ADTS frame
     */
    private static byte[] audioFrame(Stream stream, Packet packet) {
        var data = packet.data();
        if (stream.track.codec() != Codec.AAC) {
            return data;
        }
        var newConfig = ConfigChanges.changed(packet, stream.appliedConfig);
        if (newConfig != null) {
            stream.adts = AdtsHeader.fromAudioSpecificConfig(newConfig);
            stream.appliedConfig = newConfig;
        }
        var length = AdtsHeader.SIZE + data.length;
        if (length > AdtsHeader.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("an AAC frame of " + data.length + " bytes does not fit an ADTS frame");
        }
        var frame = new byte[length];
        stream.adts.write(frame, 0, length);
        System.arraycopy(data, 0, frame, AdtsHeader.SIZE, data.length);
        return frame;
    }

    /**
     * Converts interleaved PCM samples to DVD linear PCM sample groups and appends them, with a start at every
     * audio frame boundary they cross.
     *
     * @param stream the PCM stream
     * @param data the interleaved samples, whole sample frames
     * @param ptsOut the presentation time of the first sample, with the shift applied
     */
    private void appendPcm(Stream stream, byte[] data, long ptsOut) {
        var frameBytes = stream.pcmWidth * stream.pcmChannels;
        var frames = data.length / frameBytes;
        var groupFrames = stream.pcmGroupFrames;
        var held = stream.pendingFrames;
        var firstSample = stream.pcmSample + (long) stream.available() / stream.pcmGroup * groupFrames + held;
        var total = held + frames;
        var groups = total / groupFrames;
        var bytes = groups * stream.pcmGroup;
        stream.ensure(bytes);
        var out = stream.length;
        var frameIndex = -held;
        for (var group = 0; group < groups; group++) {
            for (var inGroup = 0; inGroup < groupFrames; inGroup++, frameIndex++) {
                for (var channel = 0; channel < stream.pcmChannels; channel++) {
                    var value = frameIndex < 0
                            ? sample(stream.pending, ((held + frameIndex) * stream.pcmChannels + channel)
                            * stream.pcmWidth, stream)
                            : sample(data, (frameIndex * stream.pcmChannels + channel) * stream.pcmWidth, stream);
                    putSample(stream, out, inGroup, channel, value);
                }
            }
            out += stream.pcmGroup;
        }
        var left = total - groups * groupFrames;
        if (left > 0) {
            System.arraycopy(data, (frames - left) * frameBytes, stream.pending, 0, left * frameBytes);
        }
        stream.pendingFrames = left;
        stream.length += bytes;
        var frame = stream.pcmFrameSamples;
        var grouped = firstSample - held + (long) groups * groupFrames;
        for (var boundary = Math.ceilDiv(firstSample - held, frame) * frame; boundary < grouped; boundary += frame) {
            var offset = stream.head + (int) ((boundary - stream.pcmSample) / groupFrames * stream.pcmGroup);
            var pts = ptsOut + Math.floorDiv((boundary - firstSample) * 90_000, stream.pcmRate);
            stream.starts.add(new Start(offset, pts, pts));
        }
        stream.pcmEndPts = ptsOut + Math.floorDiv((long) frames * 90_000, stream.pcmRate);
    }

    /**
     * Reads one sample of a PCM packet as a left-aligned 24-bit value (16-bit samples in its high bits).
     *
     * @param data the samples
     * @param offset the index of the sample's first byte
     * @param stream the PCM stream
     * @return the sample, in the low 24 bits
     */
    private static int sample(byte[] data, int offset, Stream stream) {
        if (stream.pcmWidth == 2) {
            return stream.pcmLittle ? (data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8) << 8
                    : ((data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF) << 8;
        }
        return stream.pcmLittle ? data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                : (data[offset] & 0xFF) << 16 | (data[offset + 1] & 0xFF) << 8 | data[offset + 2] & 0xFF;
    }

    /**
     * Stores one sample in a DVD linear PCM sample group.
     *
     * @param stream the PCM stream, whose data holds the group
     * @param group the index of the group's first byte
     * @param frame the sample frame within the group
     * @param channel the channel index within the frame
     * @param value the sample, left-aligned in 24 bits
     */
    private static void putSample(Stream stream, int group, int frame, int channel, int value) {
        var data = stream.data;
        var sample = frame * stream.pcmChannels + channel;
        if (stream.pcmBits == 16) {
            data[group + 2 * sample] = (byte) (value >> 16);
            data[group + 2 * sample + 1] = (byte) (value >> 8);
            return;
        }
        var chunk = DvdLpcmParser.chunkSamples(stream.pcmChannels);
        var chunkBytes = stream.pcmBits == 24 ? 3 * chunk : chunk * 5 / 2;
        var base = group + sample / chunk * chunkBytes;
        var position = sample % chunk;
        data[base + 2 * position] = (byte) (value >> 16);
        data[base + 2 * position + 1] = (byte) (value >> 8);
        var low = base + 2 * chunk;
        if (stream.pcmBits == 24) {
            data[low + position] = (byte) value;
        } else if ((position & 1) == 0) {
            data[low + (position >> 1)] = (byte) (data[low + (position >> 1)] & 0x0F | value & 0xF0);
        } else {
            data[low + (position >> 1)] = (byte) (data[low + (position >> 1)] & 0xF0 | (value >> 4) & 0x0F);
        }
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
            if (stream.pcmBits > 0 && stream.pendingFrames > 0) {
                var silence = (stream.pcmGroupFrames - stream.pendingFrames) * stream.pcmWidth * stream.pcmChannels;
                appendPcm(stream, new byte[silence], stream.pcmEndPts);
            }
        }
        while (true) {
            Stream next = null;
            for (var stream : streams) {
                if (stream.available() > 0 && (next == null || headDts(stream) < headDts(next))) {
                    next = stream;
                }
            }
            if (next == null) {
                break;
            }
            writePack(next);
        }
        finished = true;
        closeVobu();
        if (!options.fixedPackSize()) {
            buffer.u32be(0x000001B9L);
        }
        buffer.writeTo(output);
        buffer.reset();
    }

    /**
     * Returns the decoding time of a stream's next byte to write.
     *
     * @param stream the stream whose next byte is looked at
     * @return the time, with the shift applied
     */
    private static long headDts(Stream stream) {
        var start = stream.starts.peekFirst();
        return start != null && start.offset == stream.head ? start.dts : stream.currentDts;
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
     * Holds a packet waiting for the interleaving.
     *
     * @param packet the PCM packet of interleaved samples
     * @param micros its decoding time, in microseconds
     */
    private record Queued(Packet packet, long micros) {
    }

    /**
     * Marks where an access unit, or an audio frame of linear PCM, starts in a stream's data.
     */
    private static final class Start {
        /**
         * The index of its first byte in the stream's data.
         */
        int offset;

        /**
         * Its presentation time, with the shift applied.
         */
        final long pts;

        /**
         * Its decoding time, with the shift applied.
         */
        final long dts;

        /**
         * Constructs a start.
         *
         * @param offset the index of its first byte
         * @param pts its presentation time
         * @param dts its decoding time
         */
        Start(int offset, long pts, long dts) {
            this.offset = offset;
            this.pts = pts;
            this.dts = dts;
        }
    }

    /**
     * Holds the packs of a video object unit until its navigation pack can be completed.
     */
    private static final class Vobu {
        /**
         * The packs, the navigation pack first.
         */
        final ByteWriter packs;

        /**
         * The logical block number of the navigation pack.
         */
        long navLbn;

        /**
         * The system clock reference of the navigation pack, in 27 MHz ticks.
         */
        long scr;

        /**
         * The decoding time the unit started at, with the shift applied.
         */
        long startDts;

        /**
         * The earliest presentation time of its pictures, or {@link Long#MIN_VALUE}.
         */
        long startPts = Long.MIN_VALUE;

        /**
         * The latest end of presentation of its pictures, or {@link Long#MIN_VALUE}.
         */
        long endPts = Long.MIN_VALUE;

        /**
         * Whether a video access unit was written in it.
         */
        boolean hasVideo;

        /**
         * The index of the presentation control information packet in the navigation pack.
         */
        int pciOffset;

        /**
         * The index of the data search information packet in the navigation pack.
         */
        int dsiOffset;

        /**
         * The number of reference pictures whose end is recorded.
         */
        int references;

        /**
         * The block number, relative to the navigation pack, of the last pack of each of the first three reference
         * pictures.
         */
        final long[] referenceEnds = new long[3];

        Vobu(ByteWriter packs) {
            this.packs = packs;
        }
    }

    /**
     * Holds the state of one elementary stream.
     */
    private static final class Stream {
        final Track track;
        int streamId;
        int subId = -1;
        int streamType;
        boolean needsMap;
        int bufferScale;
        int bufferSize;
        boolean firstPes = true;
        boolean hevc;
        int lengthSize = 4;
        byte[] parameterSets = new byte[0];
        byte[] acceptedConfig;
        byte[] appliedConfig;
        AdtsHeader adts;
        final ArrayDeque<Queued> queue = new ArrayDeque<>();
        final List<Packet> window = new ArrayList<>();
        final PriorityQueue<Long> pendingPts = new PriorityQueue<>();
        boolean delayKnown;
        long reorderDelay;
        long lastDts = Packet.NO_TIMESTAMP;
        long lastPts;
        byte[] data = new byte[16 * 1024];
        int head;
        int length;
        final ArrayDeque<Start> starts = new ArrayDeque<>();
        long currentDts;
        int pcmBits;
        int pcmWidth;
        boolean pcmLittle;
        int pcmChannels;
        int pcmRate;
        int pcmGroup;
        int pcmGroupFrames;
        int pcmFrameSamples;
        int pcmCode;
        long pcmSample;
        long pcmEndPts;
        byte[] pending;
        int pendingFrames;

        Stream(Track track) {
            this.track = track;
        }

        /**
         * {@return the number of bytes not yet written}
         */
        int available() {
            return length - head;
        }

        /**
         * Moves the bytes not yet written to the start of the data.
         */
        void compact() {
            if (head == 0) {
                return;
            }
            System.arraycopy(data, head, data, 0, length - head);
            for (var start : starts) {
                start.offset -= head;
            }
            length -= head;
            head = 0;
        }

        /**
         * Makes room for more bytes of data.
         *
         * @param more the number of bytes to add
         */
        void ensure(int more) {
            if (length + more > data.length) {
                compact();
            }
            if (length + more > data.length) {
                data = Arrays.copyOf(data, Math.max(length + more, 2 * data.length));
            }
        }

        /**
         * Appends the bytes of an access unit.
         *
         * @param unit the bytes
         * @param pts its presentation time, with the shift applied
         * @param dts its decoding time, with the shift applied
         */
        void append(byte[] unit, long pts, long dts) {
            ensure(unit.length);
            starts.add(new Start(length, pts, dts));
            System.arraycopy(unit, 0, data, length, unit.length);
            length += unit.length;
            lastPts = pts;
        }
    }

    /**
     * Takes apart the configuration record of an H.264, HEVC or VVC track.
     *
     * @param codec the codec of the track
     * @param config the {@code avcC}, {@code hvcC} or {@code vvcC} record, or {@code null}
     * @return the length prefix size and parameter sets, or {@code null} when the record is missing or truncated
     */
    private static NalRecords.Parsed parseNalConfig(Codec codec, byte[] config) {
        return switch (codec) {
            case HEVC -> NalConfigs.parseHevc(config);
            case VVC -> NalConfigs.parseVvc(config);
            default -> NalConfigs.parseAvc(config);
        };
    }
}
