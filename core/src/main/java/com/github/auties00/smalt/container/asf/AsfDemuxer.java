package com.github.auties00.smalt.container.asf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.AdpcmBlocks;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.container.internal.RawVideoTags;
import com.github.auties00.smalt.container.internal.RiffCodecs;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.asf.AsfGuid;
import com.github.auties00.smalt.internal.asf.AsfHeader;
import com.github.auties00.smalt.internal.asf.AsfTags;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Advanced Systems Format files ({@code .asf}, {@code .wmv}, {@code .wma}): the container of Windows Media
 * audio and video, as the Microsoft ASF Specification (revision 01.20.05) defines it.
 *
 * <p>The demuxer reads the Header Object and the data packets of the Data Object. Every audio and video stream of a
 * Stream Properties Object becomes a track, its {@link Track#id() identifier} the ASF stream number, whether the
 * object is at the top level of the header or embedded in an Extended Stream Properties Object:
 * <ul>
 *   <li>a video stream's codec comes from the compression FourCC of its {@code BITMAPINFOHEADER}, VC-1 for
 *       {@code WMV3} (Windows Media Video 9, the simple and main profiles; the extra data, a {@code STRUCT_C},
 *       becomes {@link Track#codecConfig()}) and for {@code WVC1} and {@code WMVA} (the advanced profile; the extra
 *       data holds the sequence header and entry point), and H.264, MPEG-4 Part 2, Motion JPEG and the other
 *       FourCCs of AVI files; uncompressed formats are raw video;</li>
 *   <li>an audio stream's codec comes from the format tag of its {@code WAVEFORMATEX}: MP3, MP2, AAC, AC-3, PCM,
 *       the ADPCM formats and the others AVI files carry; the Windows Media Audio formats, which Smalt does not
 *       decode, are {@link Codec#UNKNOWN_AUDIO};</li>
 *   <li>a JPEG stream ({@code ASF_JFIF_Media}) is a Motion JPEG video track; other stream types (commands, file
 *       transfer, binary, degradable JPEG) are skipped.</li>
 * </ul>
 * H.264 stored as an Annex B byte stream gets a configuration record built from the parameter sets of its extra
 * data or its first key frame, and its packets are returned as four-byte length-prefixed NAL units. The Extended
 * Stream Properties Object gives a stream's frame rate (its average time per frame), its language (from the
 * Language List Object) and its names; the {@code AspectRatioX} and {@code AspectRatioY} records of the Metadata
 * Object give a video stream's sample aspect ratio.
 *
 * <p>Packets are the media objects of the streams, reassembled from their payloads in file order, with the
 * compressed payloads of several objects split into one packet each; a media object a payload is missing from is
 * dropped, except the one whose payload the input ends inside of, which is returned with the bytes received. Audio
 * objects that audio spread error correction interleaves are put back in order, and MPEG audio is cut into whole
 * frames. Timestamps count milliseconds (the time base is 1/1000) from the start of the presentation: the stored
 * presentation time less the preroll of the File Properties Object. ASF stores one time per object, in decoding
 * order, so it is both the presentation and the decoding timestamp; decoders of codecs that reorder frames
 * derive the display order themselves. A packet's duration is the one its sample duration payload extension gives,
 * or {@link Packet#UNKNOWN_DURATION}. Video packets are key frames when their payload is flagged so; audio packets
 * always are.
 *
 * <p>{@link #seek(long)} restarts every track at its last key frame (audio: its last object) whose presentation time
 * is at or before the target, found by locating the last data packet sent at or before the target (the packets
 * have a fixed size) and scanning back; the Simple Index Object is not needed.
 *
 * <p>{@link #metadata()} holds the file's tags: the Content Description fields, the Extended Content Description
 * descriptors and the whole-file records of the Metadata and Metadata Library Objects, named by the
 * {@link StandardTag} keys their attribute names denote ({@link StandardTag#asfName()}) and by their own names
 * otherwise, plus {@code creation_time} from the File Properties Object. {@link #trackMetadata(int)} holds a stream's
 * name ({@code title}) and the Metadata and Metadata Library records of the stream. {@code WM/Picture} attributes are
 * the {@link #attachedPictures()}, the Marker Object gives the {@link #chapters()}, and the Script Command Object
 * the {@link #scriptCommands()}.
 *
 * <p>A file protected by digital rights management (a content encryption object in its header, or an encrypted
 * stream) cannot be read: the constructor throws {@link MediaException.Unsupported}.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every data packet which bytes are
 * no longer needed.
 *
 * <p>Instances are not thread-safe.
 */
public final class AsfDemuxer implements Demuxer {
    /**
     * The largest Header Object read into memory.
     */
    private static final long MAX_HEADER = 256L << 20;

    /**
     * The largest data packet.
     */
    private static final long MAX_PACKET = 16 << 20;

    /**
     * The size of the Data Object fields before its first packet.
     */
    private static final int DATA_PREFIX = 50;

    /**
     * The most packets scanned back from the seek target for the start of each track.
     */
    private static final int MAX_SEEK_SCAN = 1 << 20;

    /**
     * The most packets read ahead to find the parameter sets of an H.264 stream.
     */
    private static final int MAX_CONFIG_SCAN = 1 << 14;

    /**
     * The milliseconds between 1601-01-01 and 1970-01-01.
     */
    private static final long FILETIME_EPOCH_MILLIS = 11_644_473_600_000L;

    /**
     * The most parameter sets of one kind collected into a configuration record.
     */
    private static final int MAX_PARAMETER_SETS = 31;

    /**
     * The time base of every track.
     */
    private static final Rational MILLISECONDS = new Rational(1, 1000);

    /**
     * A command of the Script Command Object: an action a player runs at a presentation time, such as opening a
     * web page or showing a caption.
     *
     * @param micros the presentation time, in microseconds from the start of the presentation; negative for a
     * command timed within the preroll
     * @param type the command type, such as {@code URL}, {@code TEXT} or {@code FILENAME}; empty when the command
     * names a type the object does not list
     * @param command the command parameter, such as the address for {@code URL}
     */
    public record ScriptCommand(long micros, String type, String command) {
        /**
         * Validates the command.
         *
         * @throws NullPointerException if {@code type} or {@code command} is {@code null}
         */
        public ScriptCommand {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(command, "command");
        }
    }

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The limits.
     */
    private final MediaLimits limits;

    /**
     * The reader of the header and the packets.
     */
    private final SourceReader reader;

    /**
     * The parsed Header Object.
     */
    private final AsfHeader header;

    /**
     * The streams by stream number, {@code null} for those that are not tracks.
     */
    private final Stream[] streams = new Stream[128];

    /**
     * The tracks, in stream number order.
     */
    private final List<Track> tracks;

    /**
     * The file tags.
     */
    private final Map<String, String> metadata;

    /**
     * The pictures.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The chapters.
     */
    private final List<Chapter> chapters;

    /**
     * The script commands.
     */
    private final List<ScriptCommand> scriptCommands;

    /**
     * The preroll, in milliseconds.
     */
    private final long preroll;

    /**
     * The duration in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The position of the first data packet.
     */
    private final long dataStart;

    /**
     * The size of every data packet.
     */
    private final int packetSize;

    /**
     * The number of data packets, or {@code -1} when unknown (a broadcast file or a stream of unknown size).
     */
    private final long packetCount;

    /**
     * The bytes of the packet being parsed, allocated when the first whole packet is found.
     */
    private byte[] packet;

    /**
     * The number of bytes of {@link #packet} the input holds: the packet size, or less for the packet the input ends
     * inside of.
     */
    private int loaded;

    /**
     * The stream whose payload the last parsed data packet cut short (the input ended inside of it), or {@code -1}.
     */
    private int cutStream = -1;

    /**
     * The completed packets waiting to be returned.
     */
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();

    /**
     * The index of the next data packet to parse.
     */
    private long nextPacket;

    /**
     * Whether the last data packet was parsed.
     */
    private boolean ended;

    /**
     * The end of every track in microseconds by stream number, or {@code null} until a seek needs it.
     */
    private long[] trackEnds;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not a valid ASF file
     * @throws MediaException.Unsupported if the file is protected by digital rights management
     * @throws MediaException.LimitExceeded if a size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AsfDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not a valid ASF file
     * @throws MediaException.Unsupported if the file is protected by digital rights management
     * @throws MediaException.LimitExceeded if a size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AsfDemuxer(MediaSource source, DemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = options.limits();
        try {
            this.reader = new SourceReader(source, 64 * 1024);
            this.header = readHeader();
            var properties = header.properties();
            this.preroll = properties.preroll();
            if (header.isProtected()) {
                throw new MediaException.Unsupported("asf drm", "the ASF file is protected by digital rights"
                        + " management");
            }
            if (properties.minPacketSize() != properties.maxPacketSize() || properties.minPacketSize() < 16
                    || properties.minPacketSize() > MAX_PACKET) {
                throw new MediaException.Malformed("ASF file with variable or invalid data packet size "
                        + properties.minPacketSize() + ".." + properties.maxPacketSize());
            }
            this.packetSize = (int) properties.minPacketSize();
            var headerEnd = reader.position();
            if (reader.remaining(DATA_PREFIX) < DATA_PREFIX) {
                throw new MediaException.Malformed("ASF file without a data object");
            }
            var dataHeader = reader.bytes(DATA_PREFIX, DATA_PREFIX);
            if (!AsfGuid.is(dataHeader, 0, AsfGuid.DATA)) {
                throw new MediaException.Malformed("ASF header not followed by a data object");
            }
            this.dataStart = headerEnd + DATA_PREFIX;
            this.packetCount = countPackets(properties, ByteArrays.i64le(dataHeader, 16),
                    ByteArrays.i64le(dataHeader, 40));
            var list = new ArrayList<Track>();
            for (var stream : header.streams()) {
                if (stream.encrypted) {
                    throw new MediaException.Unsupported("asf drm", "ASF stream " + stream.number
                            + " is encrypted");
                }
                var built = stream.type == null ? null : build(stream);
                if (built != null) {
                    streams[stream.number] = built;
                }
            }
            for (var stream : streams) {
                if (stream != null && stream.annexB) {
                    configure(stream);
                }
            }
            for (var stream : streams) {
                if (stream != null) {
                    list.add(stream.track);
                }
            }
            if (list.isEmpty()) {
                throw new MediaException.Malformed("ASF file without audio or video streams");
            }
            this.tracks = Collections.unmodifiableList(list);
            var duration = properties.broadcast() ? -1 : properties.playDuration() / 10 - preroll * 1000;
            this.durationMicros = duration > 0 ? duration : -1;
            var builder = MediaTags.builder();
            AsfTags.read(header, builder, limits);
            var tags = builder.build();
            this.metadata = readMetadata(tags);
            this.pictures = tags.pictures();
            this.chapters = AsfTags.chapters(header, durationMicros);
            var commands = new ArrayList<ScriptCommand>();
            for (var command : header.scriptCommands()) {
                commands.add(new ScriptCommand((command.millis() - preroll) * 1000, command.type(),
                        command.command()));
            }
            this.scriptCommands = List.copyOf(commands);
            reader.seek(dataStart);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid ASF file", exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are an ASF Header Object.
     *
     * @param header the leading bytes
     * @return {@code true} when they start with the Header Object GUID
     */
    public static boolean matches(byte[] header) {
        return header.length >= 16 && AsfGuid.is(header, 0, AsfGuid.HEADER);
    }

    /**
     * Reads and parses the Header Object, leaving the reader after it.
     *
     * @return the header
     */
    private AsfHeader readHeader() {
        if (reader.remaining(AsfHeader.HEADER_PREFIX) < AsfHeader.HEADER_PREFIX) {
            throw new MediaException.Malformed("truncated ASF header");
        }
        var prefix = reader.bytes(AsfHeader.HEADER_PREFIX, AsfHeader.HEADER_PREFIX);
        if (!matches(prefix)) {
            throw new MediaException.Malformed("not an ASF file");
        }
        var size = ByteArrays.i64le(prefix, 16);
        if (size < AsfHeader.HEADER_PREFIX || size > MAX_HEADER) {
            throw new MediaException.Malformed("invalid ASF header size " + size);
        }
        LimitChecks.checkAllocation(limits, size);
        if (reader.remaining(size - AsfHeader.HEADER_PREFIX) < size - AsfHeader.HEADER_PREFIX) {
            throw new MediaException.Malformed("truncated ASF header");
        }
        var data = new byte[(int) size];
        System.arraycopy(prefix, 0, data, 0, prefix.length);
        reader.readFully(data, prefix.length, data.length - prefix.length);
        return AsfHeader.parse(data);
    }

    /**
     * Returns the number of data packets the file holds, the one the input ends inside of included.
     *
     * @param properties the File Properties Object
     * @param dataSize the size of the Data Object
     * @param declared the packet count of the Data Object
     * @return the count, or {@code -1} when it can be learned only by reading to the end
     */
    private long countPackets(AsfHeader.FileProperties properties, long dataSize, long declared) {
        var size = reader.size();
        var available = size >= 0 ? Math.max(0, size - dataStart) / packetSize : -1;
        if (!properties.broadcast()) {
            var count = declared > 0 ? declared : properties.packetCount();
            if (count <= 0 && dataSize > DATA_PREFIX) {
                count = (dataSize - DATA_PREFIX) / packetSize;
            }
            if (count > 0) {
                // the packet the input ends inside of counts: its payloads are read up to the bytes received
                var started = size >= 0 ? Math.ceilDiv(Math.max(0, size - dataStart), packetSize) : -1;
                return started >= 0 ? Math.min(count, started) : count;
            }
        }
        return available;
    }

    /**
     * Builds the track of a stream.
     *
     * @param info the stream's header objects
     * @return the stream, or {@code null} for a stream that is not a track
     */
    private Stream build(AsfHeader.Stream info) {
        Track track;
        var stream = new Stream(info.number);
        if (info.isVideo()) {
            track = videoTrack(info, stream);
        } else if (info.isAudio()) {
            track = audioTrack(info, stream);
        } else if (AsfGuid.is(info.type, 0, AsfGuid.JFIF_MEDIA) && info.typeData.length >= 8) {
            var width = (int) Math.min(ByteArrays.u32le(info.typeData, 0), Integer.MAX_VALUE);
            var height = (int) Math.min(ByteArrays.u32le(info.typeData, 4), Integer.MAX_VALUE);
            if (width > 0 && height > 0) {
                LimitChecks.checkPicture(limits, width, height);
            }
            stream.video = true;
            track = VideoTrack.builder().id(info.number).codec(Codec.MJPEG).timeBase(MILLISECONDS).width(width)
                    .height(height).build();
        } else {
            return null;
        }
        if (track == null) {
            return null;
        }
        var builder = new LinkedHashMap<String, String>();
        if (!info.names.isEmpty()) {
            builder.put(StandardTag.TITLE.key(), info.names.getFirst());
        }
        for (var group : List.of(header.metadata(), header.metadataLibrary())) {
            for (var attribute : group) {
                if (attribute.stream() == info.number) {
                    var text = AsfHeader.text(attribute);
                    if (text != null && !text.isEmpty()) {
                        builder.putIfAbsent(attribute.name(), text);
                    }
                }
            }
        }
        stream.metadata = Collections.unmodifiableMap(builder);
        stream.track = track;
        stream.durationOffset = extensionOffset(info, AsfGuid.SAMPLE_DURATION_EXTENSION);
        return stream;
    }

    /**
     * Returns where a payload extension system's data starts in the replicated data of a stream.
     *
     * @param info the stream
     * @param system the GUID of the payload extension system
     * @return the offset after the media object size and presentation time, or {@code -1} when the stream does
     * not use the system or a variable-size system precedes it
     */
    private static int extensionOffset(AsfHeader.Stream info, byte[] system) {
        var offset = 8;
        for (var index = 0; index < info.extensionSystems.size(); index++) {
            var size = info.extensionSizes.get(index);
            if (AsfGuid.is(info.extensionSystems.get(index), 0, system)) {
                return size == 2 ? offset : -1;
            }
            if (size == 0xFFFF) {
                return -1;
            }
            offset += size;
        }
        return -1;
    }

    /**
     * Builds a video track from a stream's video media type.
     *
     * @param info the stream
     * @param stream the stream state
     * @return the track
     */
    private Track videoTrack(AsfHeader.Stream info, Stream stream) {
        var data = info.typeData;
        if (data.length < 11) {
            throw new MediaException.Malformed("truncated ASF video media type of stream " + info.number);
        }
        var width = (int) Math.min(ByteArrays.u32le(data, 0), Integer.MAX_VALUE);
        var height = (int) Math.min(ByteArrays.u32le(data, 4), Integer.MAX_VALUE);
        var formatSize = Math.min(ByteArrays.u16le(data, 9), data.length - 11);
        var format = Arrays.copyOfRange(data, 11, 11 + formatSize);
        var codec = Codec.UNKNOWN_VIDEO;
        byte[] config = null;
        if (format.length >= 40) {
            var headerSize = (int) Math.min(ByteArrays.u32le(format, 0), format.length);
            var bitmapWidth = (int) Math.min(Math.abs((long) ByteArrays.i32le(format, 4)), Integer.MAX_VALUE);
            var bitmapHeight = (int) Math.min(Math.abs((long) ByteArrays.i32le(format, 8)), Integer.MAX_VALUE);
            if (bitmapWidth > 0 && bitmapHeight > 0) {
                width = bitmapWidth;
                height = bitmapHeight;
            }
            codec = RiffCodecs.video(new String(format, 16, 4, StandardCharsets.ISO_8859_1));
            // the extra data follows the 40 bytes of the header, which writers count in biSize or not
            var extra = headerSize == format.length ? 40 : Math.max(40, headerSize);
            if (format.length > extra) {
                config = Arrays.copyOfRange(format, extra, format.length);
            }
            if (codec == Codec.UNKNOWN_VIDEO) {
                var raw = RawVideoTags.bitmap(format);
                if (raw != null) {
                    codec = Codec.RAW_VIDEO;
                    config = raw.toCodecConfig();
                }
            }
        }
        if ((codec == Codec.H264 || codec == Codec.HEVC) && (config == null || config.length < 7 || config[0] != 1)) {
            stream.annexB = true;
            stream.annexBExtra = config;
            config = null;
        }
        if (width > 0 && height > 0) {
            LimitChecks.checkPicture(limits, width, height);
        }
        stream.video = true;
        var builder = VideoTrack.builder().id(info.number).codec(codec).timeBase(MILLISECONDS).codecConfig(config)
                .width(width).height(height);
        if (info.averageTimePerFrame > 0) {
            builder.frameRate(new Rational(10_000_000, info.averageTimePerFrame).reduce());
            stream.frameMillis = info.averageTimePerFrame / 10_000;
        }
        var language = language(info);
        if (language != null) {
            builder.language(language);
        }
        var aspectX = aspectTerm(info.number, "AspectRatioX");
        var aspectY = aspectTerm(info.number, "AspectRatioY");
        if (aspectX > 0 && aspectY > 0) {
            builder.sampleAspectRatio(new Rational(aspectX, aspectY).reduce());
        }
        return builder.build();
    }

    /**
     * Returns a term of the sample aspect ratio the metadata records give a stream.
     *
     * @param number the stream number
     * @param name the record name, {@code AspectRatioX} or {@code AspectRatioY}
     * @return the term, or 0 when absent
     */
    private long aspectTerm(int number, String name) {
        for (var group : List.of(header.metadata(), header.metadataLibrary())) {
            for (var attribute : group) {
                if (attribute.stream() == number && attribute.name().equals(name)) {
                    var text = AsfHeader.text(attribute);
                    try {
                        return text == null ? 0 : Long.parseLong(text.strip());
                    } catch (NumberFormatException exception) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Returns the ISO 639-2 code of a stream's language.
     *
     * @param info the stream
     * @return the code, or {@code null} when the stream declares none
     */
    private String language(AsfHeader.Stream info) {
        var languages = header.languages();
        if (info.languageIndex < 0 || info.languageIndex >= languages.size()) {
            return null;
        }
        var tag = languages.get(info.languageIndex);
        if (tag.isBlank()) {
            return null;
        }
        var code = Languages.bibliographicCode(tag.strip());
        return code.equals("und") ? null : code;
    }

    /**
     * Builds an audio track from a stream's {@code WAVEFORMATEX}.
     *
     * @param info the stream
     * @param stream the stream state
     * @return the track
     */
    private Track audioTrack(AsfHeader.Stream info, Stream stream) {
        var data = info.typeData;
        if (data.length < 14) {
            throw new MediaException.Malformed("truncated ASF audio media type of stream " + info.number);
        }
        var wave = WaveFormat.parse(data, data.length, false);
        var channels = wave.channels();
        var sampleRate = (int) Math.min(wave.sampleRate(), Integer.MAX_VALUE);
        var codec = wave.codec();
        LimitChecks.checkChannels(limits, Math.max(1, channels));
        var extra = wave.extra();
        var config = codec == Codec.AAC && extra.length > 0 ? extra : null;
        var codeSize = AdpcmBlocks.supports(codec) ? AdpcmBlocks.codeSize(codec, wave) : 0;
        if (AdpcmBlocks.supports(codec) && (codeSize < 0 || sampleRate <= 0)) {
            codec = Codec.UNKNOWN_AUDIO;
        }
        if (codec == Codec.ADPCM_MS) {
            config = WaveFormat.msAdpcmConfig(extra);
        }
        var adpcm = AdpcmBlocks.supports(codec);
        stream.mpegAudio = (codec == Codec.MP3 || codec == Codec.MP2) && sampleRate > 0;
        stream.byteRate = codec.isPcm() ? Math.max(0, wave.byteRate()) : 0;
        stream.sampleRate = sampleRate;
        if (AsfGuid.is(info.errorCorrection, 0, AsfGuid.AUDIO_SPREAD) && info.errorCorrectionData.length >= 5) {
            var correction = info.errorCorrectionData;
            var span = correction[0] & 0xFF;
            var packetLength = ByteArrays.u16le(correction, 1);
            var chunkLength = ByteArrays.u16le(correction, 3);
            if (span > 1 && chunkLength > 0 && packetLength % chunkLength == 0 && packetLength / chunkLength > 1) {
                stream.span = span;
                stream.spanPacket = packetLength;
                stream.spanChunk = chunkLength;
            }
        }
        var builder = AudioTrack.builder().id(info.number).codec(codec).timeBase(MILLISECONDS).codecConfig(config)
                .sampleRate(sampleRate).layout(channels > 0 ? wave.layout() : ChannelLayout.defaultFor(1))
                .bitsPerSample(codec.isPcm() ? wave.bitsPerSample()
                        : adpcm && codec != Codec.ADPCM_G722 ? codeSize : 0);
        var language = language(info);
        if (language != null) {
            builder.language(language);
        }
        return builder.build();
    }

    /**
     * Builds the configuration record of an H.264 or HEVC stream stored as an Annex B byte stream from the parameter
     * sets of its extra data or of its first key frame, and has its packets converted to length-prefixed form when
     * one is built.
     *
     * @param stream the stream state, whose packets are converted
     */
    private void configure(Stream stream) {
        var hevc = stream.track.codec() == Codec.HEVC;
        var videoSets = new ArrayList<byte[]>();
        var sequenceSets = new ArrayList<byte[]>();
        var pictureSets = new ArrayList<byte[]>();
        NalUnits.Sink collect = (data, offset, length) -> {
            var type = NalUnits.type(data[offset], hevc);
            var target = hevc
                    ? type == NalConfigs.HEVC_VPS ? videoSets : type == NalConfigs.HEVC_SPS ? sequenceSets
                    : type == NalConfigs.HEVC_PPS ? pictureSets : null
                    : type == NalConfigs.AVC_SPS ? sequenceSets : type == NalConfigs.AVC_PPS ? pictureSets : null;
            if (target == null || length > 0xFFFF || target.size() >= MAX_PARAMETER_SETS) {
                return;
            }
            var unit = Arrays.copyOfRange(data, offset, offset + length);
            if (target.stream().noneMatch(known -> Arrays.equals(known, unit))) {
                target.add(unit);
            }
        };
        if (stream.annexBExtra != null && NalUnits.looksLikeAnnexB(stream.annexBExtra)) {
            NalUnits.forEachAnnexB(stream.annexBExtra, collect);
        }
        if (sequenceSets.isEmpty()) {
            var first = firstKeyObject(stream.number);
            if (first != null && NalUnits.looksLikeAnnexB(first)) {
                NalUnits.forEachAnnexB(first, collect);
            }
        }
        if (sequenceSets.isEmpty()) {
            return;
        }
        var config = hevc ? NalConfigs.hevcConfig(videoSets, sequenceSets, pictureSets)
                : NalConfigs.avcConfig(sequenceSets, pictureSets);
        if (config == null) {
            return;
        }
        stream.track = ((VideoTrack) stream.track).toBuilder().codecConfig(config).build();
        stream.lengthPrefixed = true;
    }

    /**
     * Reads the first whole key frame object of a stream from the start of the data packets.
     *
     * @param number the stream number
     * @return the object, or {@code null} when none is found in the first packets
     */
    private byte[] firstKeyObject(int number) {
        var assembly = new Assembly();
        var found = new byte[1][];
        PayloadSink sink = (streamNumber, key, objectNumber, offset, objectSize, time, data, start, length,
                            replicated, replicatedStart, replicatedLength, ordinal) -> {
            if (streamNumber != number || found[0] != null) {
                return;
            }
            var object = assembly.append(key, objectNumber, offset, objectSize, time, data, start, length, limits);
            if (object != null && assembly.key) {
                found[0] = object;
            }
        };
        var end = packetCount >= 0 ? Math.min(packetCount, MAX_CONFIG_SCAN) : MAX_CONFIG_SCAN;
        for (long index = 0; index < end && found[0] == null; index++) {
            if (!load(index)) {
                break;
            }
            parse(index, sink);
        }
        return found[0];
    }

    /**
     * Returns the file tags: the tags {@link AsfTags} reads and the creation date.
     *
     * @param tags the tags {@link AsfTags} read
     * @return the tags, repeated values joined
     */
    private Map<String, String> readMetadata(MediaTags tags) {
        var result = new LinkedHashMap<>(tags.asMap());
        var created = header.properties().creationDate();
        if (created > 0 && !header.properties().broadcast()) {
            var millis = created / 10_000 - FILETIME_EPOCH_MILLIS;
            result.putIfAbsent(StandardTag.CREATION_TIME.key(), Instant.ofEpochMilli(millis).toString());
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.ASF;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags of a stream are its first name ({@code title}) and the Metadata and Metadata Library records of the
     * stream, under their attribute names ({@code IsVBR}, {@code AspectRatioX}, ...).
     */
    @Override
    public Map<String, String> trackMetadata(int trackId) {
        if (trackId <= 0 || trackId >= streams.length || streams[trackId] == null) {
            return Map.of();
        }
        return streams[trackId].metadata;
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    /**
     * Returns the commands of the Script Command Object, in file order.
     *
     * @return the commands; unmodifiable
     */
    public List<ScriptCommand> scriptCommands() {
        return scriptCommands;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public Packet read() {
        try {
            while (pending.isEmpty()) {
                if (ended) {
                    return null;
                }
                if (packetCount >= 0 && nextPacket >= packetCount
                        || !load(nextPacket) && !loadTruncated(nextPacket)) {
                    ended = true;
                    continue;
                }
                var index = nextPacket++;
                parse(index, this::payload);
                if (loaded < packetSize) {
                    ended = true;
                    flushCutObject();
                    continue;
                }
                ForwardOnlySource.release(reader.source(), dataStart + nextPacket * packetSize);
            }
            return pending.poll();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid ASF data packet", exception);
        }
    }

    /**
     * Queues the media object the input ended inside of, with the bytes received; the objects of the other streams
     * that miss payloads are dropped, as they are at the end of a whole file.
     */
    private void flushCutObject() {
        var stream = cutStream < 0 ? null : streams[cutStream];
        if (stream != null && stream.assembly.data != null && stream.assembly.filled > 0) {
            var assembly = stream.assembly;
            var data = Arrays.copyOf(assembly.data, assembly.filled);
            assembly.data = null;
            complete(stream, data, assembly.time, assembly.key, assembly.duration);
        }
    }

    /**
     * Reads one data packet into {@link #packet}.
     *
     * @param index the packet index
     * @return {@code false} when the input ends before the packet is whole
     */
    private boolean load(long index) {
        var position = dataStart + index * packetSize;
        reader.seek(position);
        if (reader.remaining(packetSize) < packetSize) {
            return false;
        }
        if (packet == null) {
            packet = new byte[packetSize];
        }
        reader.readFully(packet, 0, packetSize);
        loaded = packetSize;
        return true;
    }

    /**
     * Reads the bytes of a data packet the input ends inside of into {@link #packet}, the rest zeroed.
     *
     * @param index the packet index
     * @return {@code false} when the input holds no byte of the packet
     */
    private boolean loadTruncated(long index) {
        var position = dataStart + index * packetSize;
        reader.seek(position);
        var available = (int) Math.min(packetSize, reader.remaining(packetSize));
        if (available <= 0) {
            return false;
        }
        if (packet == null) {
            packet = new byte[packetSize];
        }
        reader.readFully(packet, 0, available);
        Arrays.fill(packet, available, packetSize, (byte) 0);
        loaded = available;
        return true;
    }

    /**
     * Receives the payloads of a data packet.
     */
    @FunctionalInterface
    private interface PayloadSink {
        /**
         * Receives one payload, or one media object of a compressed payload.
         *
         * @param stream the stream number
         * @param key whether the payload is flagged as part of a key frame
         * @param objectNumber the media object number
         * @param offset the offset of the payload in the media object
         * @param objectSize the size of the media object
         * @param time the presentation time of the media object, in milliseconds, preroll included
         * @param data the packet bytes
         * @param start the index of the payload data
         * @param length the payload data length
         * @param replicated the bytes holding the replicated data
         * @param replicatedStart the index of the replicated data
         * @param replicatedLength the replicated data length
         * @param ordinal the position of the payload in the packet, counting compressed media objects one by one
         */
        void accept(int stream, boolean key, long objectNumber, long offset, long objectSize, long time, byte[] data,
                    int start, int length, byte[] replicated, int replicatedStart, int replicatedLength, int ordinal);
    }

    /**
     * Parses the payloads of the loaded data packet.
     *
     * <p>A packet the input ends inside of yields its payloads up to the last byte received: a payload cut short is
     * passed with the bytes received and parsing stops there.
     *
     * @param index the packet index, named in failures
     * @param sink receives the payloads
     */
    private void parse(long index, PayloadSink sink) {
        var data = packet;
        var truncated = loaded < packetSize;
        cutStream = -1;
        var position = 0;
        var flags = data[position++] & 0xFF;
        if ((flags & 0x80) != 0) {
            // error correction data: the low nibble is its length
            position += flags & 0x0F;
            flags = data[position++] & 0xFF;
        }
        var properties = data[position++] & 0xFF;
        var packetLength = (int) field(data, position, flags >> 5);
        position += width(flags >> 5);
        position += width(flags >> 1);
        var padding = field(data, position, flags >> 3);
        position += width(flags >> 3);
        var sendTime = ByteArrays.u32le(data, position);
        position += 6;
        var end = packetLength > 0 && packetLength <= packetSize ? packetLength : packetSize;
        end -= (int) Math.min(padding, end);
        if (truncated && position >= loaded) {
            return;
        }
        if (position > end) {
            throw new MediaException.Malformed("ASF data packet " + index + " overruns its length");
        }
        var multiple = (flags & 1) != 0;
        var count = 1;
        var lengthType = 0;
        if (multiple) {
            var payloadFlags = data[position++] & 0xFF;
            count = payloadFlags & 0x3F;
            lengthType = payloadFlags >> 6;
        }
        var objectNumberType = properties >> 4;
        var offsetType = properties >> 2;
        var replicatedType = properties;
        var ordinal = 0;
        for (var payload = 0; payload < count; payload++) {
            if (truncated && position >= loaded) {
                return;
            }
            if (position >= end) {
                throw new MediaException.Malformed("ASF data packet " + index + " ends inside a payload header");
            }
            var streamByte = data[position++] & 0xFF;
            var objectNumber = field(data, position, objectNumberType);
            position += width(objectNumberType);
            var offset = field(data, position, offsetType);
            position += width(offsetType);
            var replicatedLength = (int) field(data, position, replicatedType);
            position += width(replicatedType);
            if (truncated && position + replicatedLength + (multiple ? width(lengthType) : 0) > loaded) {
                return;
            }
            if (replicatedLength > end - position) {
                throw new MediaException.Malformed("ASF payload replicated data overruns data packet " + index);
            }
            var replicatedStart = position;
            position += replicatedLength;
            int length;
            if (multiple) {
                length = (int) field(data, position, lengthType);
                position += width(lengthType);
            } else {
                length = end - position;
            }
            if (length < 0 || length > end - position) {
                throw new MediaException.Malformed("ASF payload overruns data packet " + index);
            }
            var cut = truncated && length > loaded - position;
            if (cut) {
                length = loaded - position;
            }
            var stream = streamByte & 0x7F;
            var key = (streamByte & 0x80) != 0;
            if (cut) {
                cutStream = stream;
            }
            if (replicatedLength == 1) {
                // a compressed payload: whole media objects of one stream, each after its size byte
                var delta = data[replicatedStart] & 0xFF;
                var time = offset;
                var sub = position;
                var subEnd = position + length;
                while (sub < subEnd) {
                    var size = data[sub++] & 0xFF;
                    if (cut && size > subEnd - sub) {
                        return;
                    }
                    if (size > subEnd - sub) {
                        throw new MediaException.Malformed("ASF compressed payload overruns data packet " + index);
                    }
                    sink.accept(stream, key, objectNumber++, 0, size, time, data, sub, size, data, replicatedStart,
                            0, ordinal++);
                    time += delta;
                    sub += size;
                }
            } else if (replicatedLength >= 8) {
                var objectSize = ByteArrays.u32le(data, replicatedStart);
                var time = ByteArrays.u32le(data, replicatedStart + 4);
                sink.accept(stream, key, objectNumber, offset, objectSize, time, data, position, length, data,
                        replicatedStart, replicatedLength, ordinal++);
            } else if (replicatedLength == 0 && offset == 0) {
                // no replicated data: the payload is a whole object, timed by the packet's send time
                sink.accept(stream, key, objectNumber, 0, length, sendTime, data, position, length, data,
                        replicatedStart, 0, ordinal++);
            } else {
                ordinal++;
            }
            if (cut) {
                return;
            }
            position += length;
        }
    }

    /**
     * Returns the size of a field of a length type.
     *
     * @param type the two-bit length type in the low bits: absent, byte, word or double word
     * @return the size in bytes
     */
    private static int width(int type) {
        return switch (type & 3) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
    }

    /**
     * Reads a field of a length type.
     *
     * @param data the bytes
     * @param position the index of the field
     * @param type the two-bit length type in the low bits
     * @return the value, 0 for an absent field
     */
    private static long field(byte[] data, int position, int type) {
        return switch (type & 3) {
            case 0 -> 0;
            case 1 -> data[position] & 0xFF;
            case 2 -> ByteArrays.u16le(data, position);
            default -> ByteArrays.u32le(data, position);
        };
    }

    /**
     * Reassembles one payload into its stream's media object and queues the object once whole.
     */
    private void payload(int number, boolean key, long objectNumber, long offset, long objectSize, long time,
                         byte[] data, int start, int length, byte[] replicated, int replicatedStart,
                         int replicatedLength, int ordinal) {
        var stream = streams[number];
        if (stream == null) {
            return;
        }
        if (stream.resumePacket >= 0) {
            if (nextPacket - 1 < stream.resumePacket
                    || nextPacket - 1 == stream.resumePacket && ordinal < stream.resumeOrdinal) {
                return;
            }
            stream.resumePacket = -1;
        }
        var assembly = stream.assembly;
        if (offset == 0) {
            assembly.duration = stream.durationOf(objectSize, replicated, replicatedStart, replicatedLength);
        }
        var object = assembly.append(key, objectNumber, offset, objectSize, time, data, start, length, limits);
        if (object != null) {
            complete(stream, object, assembly.time, assembly.key, assembly.duration);
        }
    }

    /**
     * Queues the packets of a whole media object.
     *
     * @param stream the stream that owns the object
     * @param object the object bytes
     * @param time the presentation time, in milliseconds, preroll included
     * @param key whether the payload was flagged as part of a key frame
     * @param duration the duration in milliseconds, or {@link Packet#UNKNOWN_DURATION}
     */
    private void complete(Stream stream, byte[] object, long time, boolean key, long duration) {
        var id = stream.track.id();
        var pts = time - preroll;
        if (stream.span > 1 && object.length == stream.span * stream.spanPacket) {
            object = descramble(object, stream.span, stream.spanPacket / stream.spanChunk, stream.spanChunk);
        }
        if (stream.video) {
            if (stream.waitForKey) {
                if (!key) {
                    return;
                }
                stream.waitForKey = false;
            }
            if (stream.lengthPrefixed && NalUnits.looksLikeAnnexB(object)) {
                object = NalUnits.toLengthPrefixed(object);
            }
            pending.add(new Packet(id, object, pts, pts, duration, key));
            return;
        }
        if (stream.mpegAudio) {
            stream.frame(object, pts, pending);
            return;
        }
        pending.add(new Packet(id, object, pts, pts, duration, true));
    }

    /**
     * Puts the chunks of an object that audio spread error correction interleaved back in order.
     *
     * <p>The object is {@code span} virtual packets of {@code chunks} chunks each; the chunk at index {@code i} of
     * the original order was stored at index {@code (i % span) * chunks + i / span}.
     *
     * @param object the stored object, {@code span * chunks * chunkSize} bytes
     * @param span the number of virtual packets
     * @param chunks the number of chunks in a virtual packet
     * @param chunkSize the size of a chunk
     * @return the object in its original order
     */
    static byte[] descramble(byte[] object, int span, int chunks, int chunkSize) {
        var result = new byte[object.length];
        var total = span * chunks;
        for (var index = 0; index < total; index++) {
            var stored = (index % span) * chunks + index / span;
            System.arraycopy(object, stored * chunkSize, result, index * chunkSize, chunkSize);
        }
        return result;
    }

    @Override
    public boolean isSeekable() {
        return packetCount > 0 && reader.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every track restarts at its last key frame (audio: its last media object) presented at or before the
     * target; reading resumes at the earliest data packet those start in, skipping the earlier objects of the other
     * tracks. A track without such an object restarts at its first key frame.
     *
     * @throws MediaException.Unsupported if the source has no known size or the file no packet count
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "the ASF input has no known size or packet count");
        }
        var savedNext = nextPacket;
        var savedEnded = ended;
        try {
            seekTo(micros);
        } catch (MediaException exception) {
            nextPacket = savedNext;
            ended = savedEnded;
            throw exception;
        } catch (RuntimeException exception) {
            nextPacket = savedNext;
            ended = savedEnded;
            throw new MediaException.Malformed("invalid ASF data packet", exception);
        }
    }

    /**
     * Positions the demuxer for {@link #seek(long)}.
     *
     * @param micros the target
     */
    private void seekTo(long micros) {
        var target = Math.floorDiv(micros, 1000) + preroll;
        var ends = trackEnds();
        var resolved = new long[streams.length];
        var ordinals = new int[streams.length];
        Arrays.fill(resolved, -1);
        var unresolved = new int[1];
        for (var stream : streams) {
            if (stream != null && micros < ends[stream.number]) {
                unresolved[0]++;
            }
        }
        var last = lastPacketSentBy(target);
        for (long index = last, scanned = 0; index >= 0 && unresolved[0] > 0 && scanned < MAX_SEEK_SCAN;
             index--, scanned++) {
            if (!load(index) && !loadTruncated(index)) {
                continue;
            }
            var packetIndex = index;
            parse(index, (number, key, objectNumber, offset, objectSize, time, data, start, length, replicated,
                          replicatedStart, replicatedLength, ordinal) -> {
                var stream = streams[number];
                if (stream == null || micros >= ends[number] || offset != 0 || time > target
                        || stream.video && !key) {
                    return;
                }
                if (resolved[number] < 0) {
                    unresolved[0]--;
                    resolved[number] = packetIndex;
                    ordinals[number] = ordinal;
                } else if (resolved[number] == packetIndex) {
                    ordinals[number] = Math.max(ordinals[number], ordinal);
                }
            });
        }
        var earliest = Long.MAX_VALUE;
        for (var stream : streams) {
            if (stream == null) {
                continue;
            }
            stream.reset();
            if (micros >= ends[stream.number]) {
                stream.resumePacket = Long.MAX_VALUE;
            } else if (resolved[stream.number] < 0) {
                stream.resumePacket = 0;
                stream.resumeOrdinal = 0;
                stream.waitForKey = stream.video;
                earliest = 0;
            } else {
                stream.resumePacket = resolved[stream.number];
                stream.resumeOrdinal = ordinals[stream.number];
                earliest = Math.min(earliest, resolved[stream.number]);
            }
        }
        pending.clear();
        nextPacket = earliest == Long.MAX_VALUE ? packetCount : earliest;
        ended = earliest == Long.MAX_VALUE;
    }

    /**
     * Returns the time every track ends at, found once by scanning back from the last data packet: a track ends one
     * duration after its last media object starts, the object's own duration when known and otherwise the interval
     * between its last two objects.
     *
     * @return the end of each track in microseconds by stream number, {@link Long#MAX_VALUE} when not found
     */
    private long[] trackEnds() {
        if (trackEnds != null) {
            return trackEnds;
        }
        var lastTimes = new long[streams.length];
        var previousTimes = new long[streams.length];
        var lastDurations = new long[streams.length];
        Arrays.fill(lastTimes, Long.MIN_VALUE);
        Arrays.fill(previousTimes, Long.MIN_VALUE);
        var missing = 0;
        for (var stream : streams) {
            if (stream != null) {
                missing++;
            }
        }
        var settled = new boolean[streams.length];
        for (long index = packetCount - 1, scanned = 0; index >= 0 && missing > 0 && scanned < MAX_SEEK_SCAN;
             index--, scanned++) {
            if (!load(index) && !loadTruncated(index)) {
                continue;
            }
            parse(index, (number, key, objectNumber, offset, objectSize, time, data, start, length, replicated,
                          replicatedStart, replicatedLength, ordinal) -> {
                var stream = streams[number];
                if (stream == null || offset != 0 || settled[number]) {
                    return;
                }
                if (lastTimes[number] == Long.MIN_VALUE || time > lastTimes[number]) {
                    if (lastTimes[number] != Long.MIN_VALUE) {
                        previousTimes[number] = lastTimes[number];
                    }
                    lastTimes[number] = time;
                    lastDurations[number] = stream.durationOf(objectSize, replicated, replicatedStart,
                            replicatedLength);
                } else if (time < lastTimes[number]) {
                    previousTimes[number] = Math.max(previousTimes[number], time);
                }
            });
            // a packet holds its payloads in send order, so a stream is settled once a whole packet gave it two times
            for (var stream : streams) {
                if (stream != null && !settled[stream.number] && previousTimes[stream.number] != Long.MIN_VALUE) {
                    settled[stream.number] = true;
                    missing--;
                }
            }
        }
        var ends = new long[streams.length];
        Arrays.fill(ends, Long.MAX_VALUE);
        for (var stream : streams) {
            if (stream == null || lastTimes[stream.number] == Long.MIN_VALUE) {
                continue;
            }
            var number = stream.number;
            var step = lastDurations[number] != Packet.UNKNOWN_DURATION ? lastDurations[number]
                    : previousTimes[number] != Long.MIN_VALUE ? lastTimes[number] - previousTimes[number] : 0;
            ends[number] = (lastTimes[number] - preroll + step) * 1000;
        }
        trackEnds = ends;
        return ends;
    }

    /**
     * Returns the last data packet whose send time is at or before a time.
     *
     * @param target the time, in milliseconds, preroll included
     * @return the packet index, {@code 0} when the first is sent later
     */
    private long lastPacketSentBy(long target) {
        long low = 0;
        long high = packetCount - 1;
        while (low < high) {
            var middle = (low + high + 1) >>> 1;
            if (load(middle) && sendTime() <= target) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * Returns the send time of the loaded data packet.
     *
     * @return the send time, in milliseconds
     */
    private long sendTime() {
        var position = 0;
        var flags = packet[position++] & 0xFF;
        if ((flags & 0x80) != 0) {
            position += flags & 0x0F;
            flags = packet[position++] & 0xFF;
        }
        position++;
        position += width(flags >> 5) + width(flags >> 1) + width(flags >> 3);
        return ByteArrays.u32le(packet, position);
    }

    /**
     * The reassembly of one stream's media objects.
     */
    private static final class Assembly {
        /**
         * The bytes of the object being reassembled, or {@code null}.
         */
        byte[] data;
        /**
         * The number of bytes received.
         */
        int filled;
        /**
         * The size of the object.
         */
        int size;
        /**
         * The media object number.
         */
        long number;
        /**
         * The presentation time, in milliseconds, preroll included.
         */
        long time;
        /**
         * Whether the first payload was flagged as part of a key frame.
         */
        boolean key;
        /**
         * The duration in milliseconds, or {@link Packet#UNKNOWN_DURATION}.
         */
        long duration = Packet.UNKNOWN_DURATION;

        /**
         * Adds a payload.
         *
         * @return the whole object, or {@code null} while it is incomplete
         */
        byte[] append(boolean payloadKey, long objectNumber, long offset, long objectSize, long objectTime,
                      byte[] source, int start, int length, MediaLimits limits) {
            if (offset == 0) {
                if (objectSize < 0 || objectSize > Integer.MAX_VALUE - 8) {
                    data = null;
                    return null;
                }
                if (objectSize == 0) {
                    // an empty object, such as a skipped video frame
                    data = null;
                    number = objectNumber;
                    time = objectTime;
                    key = payloadKey;
                    return new byte[0];
                }
                LimitChecks.checkAllocation(limits, objectSize);
                size = (int) objectSize;
                // the buffer grows with the payloads, so a size no payloads back costs nothing
                data = new byte[Math.min(size, Math.max(length, 4096))];
                filled = 0;
                number = objectNumber;
                time = objectTime;
                key = payloadKey;
            } else if (data == null || objectNumber != number || offset != filled) {
                data = null;
                return null;
            }
            var count = Math.min(length, size - filled);
            if (filled + count > data.length) {
                data = Arrays.copyOf(data, (int) Math.min(size, Math.max(filled + count, 2L * data.length)));
            }
            System.arraycopy(source, start, data, filled, count);
            filled += count;
            if (filled < size) {
                return null;
            }
            var whole = data.length == size ? data : Arrays.copyOf(data, size);
            data = null;
            return whole;
        }
    }

    /**
     * The state of one stream.
     */
    private static final class Stream {
        /**
         * The stream number.
         */
        final int number;
        /**
         * The reassembly of its media objects.
         */
        final Assembly assembly = new Assembly();
        /**
         * The track.
         */
        Track track;
        /**
         * The stream's tags.
         */
        Map<String, String> metadata = Map.of();
        /**
         * Whether the stream is video.
         */
        boolean video;
        /**
         * Whether the stream is MPEG audio, cut into frames.
         */
        boolean mpegAudio;
        /**
         * The sample rate of an audio stream.
         */
        int sampleRate;
        /**
         * The bytes per second of a PCM stream, or 0.
         */
        long byteRate;
        /**
         * The average frame duration of a video stream in milliseconds, or 0.
         */
        long frameMillis;
        /**
         * The number of virtual packets of audio spread interleaving, or 0.
         */
        int span;
        /**
         * The virtual packet size of audio spread interleaving.
         */
        int spanPacket;
        /**
         * The chunk size of audio spread interleaving.
         */
        int spanChunk;
        /**
         * Whether the stream is H.264 or HEVC stored as Annex B.
         */
        boolean annexB;
        /**
         * The extra data of an Annex B stream.
         */
        byte[] annexBExtra;
        /**
         * Whether Annex B packets are converted to length-prefixed form.
         */
        boolean lengthPrefixed;
        /**
         * Where the sample duration extension starts in the replicated data, or {@code -1}.
         */
        int durationOffset = -1;
        /**
         * The data packet whose payloads the stream resumes at after a seek, or {@code -1}.
         */
        long resumePacket = -1;
        /**
         * The payload ordinal within {@link #resumePacket} the stream resumes at.
         */
        int resumeOrdinal;
        /**
         * Whether video packets are dropped until a key frame.
         */
        boolean waitForKey;
        /**
         * The MPEG audio bytes of an incomplete frame.
         */
        byte[] carry = new byte[0];
        /**
         * The timestamp of the carried frame, in milliseconds.
         */
        long carryTime;

        Stream(int number) {
            this.number = number;
        }

        /**
         * Discards the partial object and frame.
         */
        void reset() {
            assembly.data = null;
            carry = new byte[0];
            waitForKey = false;
        }

        /**
         * Returns the duration of a media object: the one of its sample duration extension, the average frame
         * duration of a video stream, or the playing time of PCM bytes.
         *
         * @param objectSize the object size
         * @param replicated the bytes holding the replicated data
         * @param replicatedStart the index of the replicated data
         * @param replicatedLength the replicated data length
         * @return the duration in milliseconds, or {@link Packet#UNKNOWN_DURATION}
         */
        long durationOf(long objectSize, byte[] replicated, int replicatedStart, int replicatedLength) {
            if (durationOffset >= 0 && durationOffset + 2 <= replicatedLength) {
                return ByteArrays.u16le(replicated, replicatedStart + durationOffset);
            }
            if (video && frameMillis > 0) {
                return frameMillis;
            }
            if (byteRate > 0) {
                return objectSize * 1000 / byteRate;
            }
            return Packet.UNKNOWN_DURATION;
        }

        /**
         * Cuts MPEG audio into whole frames: a frame starting in the object is timed from the object, its offset in
         * samples rounded down to milliseconds, and a frame completed from bytes carried from the previous object
         * keeps that object's timing.
         *
         * @param object the object bytes
         * @param pts the object's timestamp, in milliseconds
         * @param output receives the packets
         */
        void frame(byte[] object, long pts, ArrayDeque<Packet> output) {
            var buffer = object;
            var prefix = carry.length;
            if (prefix > 0) {
                buffer = Arrays.copyOf(carry, prefix + object.length);
                System.arraycopy(object, 0, buffer, prefix, object.length);
            }
            var offset = 0;
            var samples = 0L;
            while (buffer.length - offset >= MpegAudioHeader.SIZE) {
                var header = MpegAudioHeader.read(buffer, offset);
                if (!MpegAudioHeader.isValid(header) || MpegAudioHeader.isFreeFormat(header)) {
                    offset++;
                    continue;
                }
                var size = MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header);
                if (size <= MpegAudioHeader.SIZE) {
                    offset++;
                    continue;
                }
                if (offset + size > buffer.length) {
                    break;
                }
                var frameSamples = MpegAudioHeader.samplesPerFrame(header);
                long time;
                if (offset < prefix) {
                    time = carryTime;
                } else {
                    time = pts + samples * 1000 / sampleRate;
                    samples += frameSamples;
                }
                var frame = offset == 0 && size == buffer.length ? buffer
                        : Arrays.copyOfRange(buffer, offset, offset + size);
                output.add(new Packet(track.id(), frame, time, time, frameSamples * 1000L / sampleRate, true));
                offset += size;
            }
            if (offset >= buffer.length || buffer.length - offset > 1 << 16) {
                carry = new byte[0];
            } else {
                carryTime = offset < prefix ? carryTime : pts + samples * 1000 / sampleRate;
                carry = Arrays.copyOfRange(buffer, offset, buffer.length);
            }
        }
    }
}
