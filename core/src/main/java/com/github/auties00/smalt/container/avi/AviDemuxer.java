package com.github.auties00.smalt.container.avi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AdpcmBlocks;
import com.github.auties00.smalt.container.internal.DifAudioTracks;
import com.github.auties00.smalt.container.internal.RawVideoTags;
import com.github.auties00.smalt.container.internal.RiffCodecs;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.codec.IntermediateFrames;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads AVI files: the RIFF {@code AVI } form of the Video for Windows file format with the
 * OpenDML 1.02 extensions for files over 1 GiB.
 *
 * <p>The demuxer reads the {@code hdrl} headers of every video ({@code vids}) and audio
 * ({@code auds}) stream: the stream header ({@code strh}) gives the time base and the stream
 * format ({@code strf}) the codec: the compression FourCC and extra data of a
 * {@code BITMAPINFOHEADER}, or the format tag (with the sub-format of
 * {@code WAVEFORMATEXTENSIBLE}, whose Dolby GUIDs announce AC-3 and E-AC-3) and extra data of a
 * {@code WAVEFORMATEX}. An interleaved DV stream ({@code iavs}, a type-1 DV file), whose chunks are whole DIF
 * frames, becomes a {@link Codec#DV} video track described by its first frame, followed by one
 * {@link Codec#PCM_S16LE} stereo track for each audio pair its frames carry; each video packet is followed by the
 * audio packets of its frame. Streams of other types (text, MIDI) are skipped. The extra data
 * becomes {@link Track#codecConfig()}:
 * the video object layer headers of MPEG-4 Part 2, the sequence header of MPEG-1 and MPEG-2 video, the
 * {@code STRUCT_C} of VC-1 simple and main profile ({@code WMV3}) or the sequence header and entry point of the
 * advanced profile ({@code WVC1}, {@code WMVA}), an
 * {@code AVCDecoderConfigurationRecord} or
 * {@code HEVCDecoderConfigurationRecord} when the extra data is one, the {@code AudioSpecificConfig} of
 * AAC, and the HAP sample entry type of HAP ({@code Hap1} for a {@code HAP1} FourCC). H.264 and HEVC stored
 * as an Annex B byte stream, the usual form in AVI, get a configuration record
 * built from the parameter sets of the extra data and of the first key frame, and their packets are
 * returned as four-byte length-prefixed NAL units, the parameter sets they carry left in band; when no
 * sequence parameter set is found the track has no configuration and its packets stay Annex B. The
 * OpenDML video properties header ({@code vprp}) of a video stream gives its sample aspect ratio, from the
 * frame aspect ratio and
 * frame size, and its scan, progressive for one field per frame and interlaced (in an undeclared
 * order) for two ({@link VideoTrack#sampleAspectRatio()}, {@link VideoTrack#interlacing()}).
 *
 * <p>Samples are located through the OpenDML super index ({@code indx}) and the standard indexes
 * ({@code ix##}) it points to, or else through the legacy {@code idx1} index (with offsets relative
 * to the {@code movi} list or absolute, as found), or else by walking the {@code movi} lists of the
 * {@code AVI } and {@code AVIX} RIFF forms, which also recovers truncated files. Packets are read in
 * file order. Video packets carry their frame number in the stream's time base as both
 * presentation and decoding timestamps (AVI stores no reordering information; decoders of codecs
 * with B-frames derive the display order themselves) and are key frames when the index flags them,
 * or, for walked files, when their data starts an intra picture. Empty video chunks, which writers
 * use to drop frames, produce no packet but keep their time slot. Audio packets are chunks with
 * timestamps counted in chunks (variable bitrate streams) or in sample blocks (constant bitrate
 * streams); MPEG audio is re-framed so that every packet is one whole frame, whatever the chunking,
 * with timestamps counted in samples.
 *
 * <p>{@link #seek(long)} moves every stream to its last key frame (audio: its last chunk) at or
 * before the target, through the index. {@code LIST INFO} tags are reported by
 * {@link #metadata()} under their chunk identifiers ({@code INAM}, {@code IART}, ...).
 *
 * <p>Instances are not thread-safe.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every chunk which
 * bytes are no longer needed; the legacy index at the end of the file keeps the media data until
 * it is read.
 */
public final class AviDemuxer implements Demuxer {
    /**
     * The largest header list read into memory.
     */
    private static final int MAX_HEADER = 16 << 20;

    /**
     * The size of the fields of an OpenDML video properties header ({@code vprp}) that precede its
     * per-field descriptions, through {@code nbFieldPerFrame}.
     */
    private static final int VPRP_HEADER = 36;

    /**
     * The largest number of chunks a file may index.
     */
    private static final int MAX_CHUNKS = 64 << 20;

    /**
     * The most parameter sets of one kind collected into a configuration record, the 31 sequence
     * parameter sets an {@code AVCDecoderConfigurationRecord} holds.
     */
    private static final int MAX_PARAMETER_SETS = 31;

    /**
     * The {@code AVIIF_KEYFRAME} flag of {@code idx1} entries.
     */
    private static final int KEYFRAME = 0x10;

    /**
     * The {@code AVIIF_LIST} flag of {@code idx1} entries.
     */
    private static final int LIST_FLAG = 0x01;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The limits.
     */
    private final MediaLimits limits;

    /**
     * The reader used for headers and indexes.
     */
    private final SourceReader reader;

    /**
     * The streams by stream number, {@code null} for skipped streams.
     */
    private final List<Stream> streams = new ArrayList<>();

    /**
     * The tracks.
     */
    private final List<Track> tracks;

    /**
     * The {@code LIST INFO} tags.
     */
    private final Map<String, String> metadata = new LinkedHashMap<>();

    /**
     * The {@code movi} lists: the position of their {@code movi} FourCC and their end.
     */
    private final List<long[]> moviLists = new ArrayList<>();

    /**
     * The position of the {@code idx1} chunk body, or {@code -1}.
     */
    private long idx1Position = -1;

    /**
     * The size of the {@code idx1} chunk body.
     */
    private long idx1Size;

    /**
     * The microseconds per frame of the main header.
     */
    private long microsPerFrame;

    /**
     * The total frame count of the OpenDML extended header, or 0.
     */
    private long extendedFrames;

    /**
     * The stream of every chunk in file order.
     */
    private int[] orderStream;

    /**
     * The index entry of every chunk in file order.
     */
    private int[] orderEntry;

    /**
     * The number of chunks in file order.
     */
    private int orderCount;

    /**
     * The next chunk in file order.
     */
    private int cursor;

    /**
     * The re-framed MPEG audio packets waiting to be returned.
     */
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();

    /**
     * The offset below which a source read once was told the demuxer reads nothing again.
     */
    private long released;

    /**
     * The offset just past the last chunk read, the end of what a seek over a source read once may read again.
     */
    private long readEnd;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not a valid AVI file
     * @throws MediaException.LimitExceeded if a size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AviDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not a valid AVI file
     * @throws MediaException.LimitExceeded if a size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AviDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private AviDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        try {
            this.reader = new SourceReader(source, 64 * 1024);
            parseFile();
            if (streams.stream().allMatch(Objects::isNull)) {
                throw new MediaException.Malformed("AVI file without audio or video streams");
            }
            buildIndex();
            var list = new ArrayList<Track>();
            var nextId = streams.size() + 1;
            for (var stream : streams) {
                if (stream != null) {
                    if (stream.annexB) {
                        configure(stream);
                    }
                    if (stream.interleavedDv) {
                        nextId += configureDv(stream, nextId);
                    }
                    list.add(stream.track);
                    if (stream.dvAudio != null) {
                        list.addAll(stream.dvAudio.tracks());
                    }
                }
            }
            this.tracks = Collections.unmodifiableList(list);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AVI file", exception);
        }
    }

    /**
     * Returns whether a stream starts with the RIFF AVI signature.
     *
     * @param header the first bytes of the stream
     * @return {@code true} for an AVI file
     */
    public static boolean matches(byte[] header) {
        return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'A' && header[9] == 'V' && header[10] == 'I' && header[11] == ' ';
    }

    /**
     * Returns the integer value of a FourCC as {@link SourceReader#fourCc()} reads it.
     *
     * @param tag the four characters
     * @return the value
     */
    private static int fourCc(String tag) {
        return (tag.charAt(0) << 24) | (tag.charAt(1) << 16) | (tag.charAt(2) << 8) | tag.charAt(3);
    }

    /**
     * Returns the characters of a FourCC value.
     *
     * @param value the four-character code, first character in the high byte
     * @return the four characters
     */
    private static String fourCcString(int value) {
        var bytes = new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads the RIFF forms of the file: the headers and the positions of the {@code movi} lists
     * and of the legacy index.
     */
    private void parseFile() {
        var size = reader.size();
        if (size >= 0 && size < 12) {
            throw new MediaException.Malformed("truncated AVI header");
        }
        var position = 0L;
        var first = true;
        var forms = 0;
        while (true) {
            reader.seek(position);
            if (reader.remaining(12) < 12) {
                break;
            }
            var id = reader.fourCc();
            var formSize = reader.u32le();
            var type = reader.fourCc();
            if (id != fourCc("RIFF") || (first && type != fourCc("AVI ")) || (!first && type != fourCc("AVIX"))) {
                if (first) {
                    throw new MediaException.Malformed("not an AVI file");
                }
                break;
            }
            var end = position + 8 + formSize;
            if (formSize < 4 || size >= 0 && end > size) {
                end = size >= 0 ? size : Long.MAX_VALUE;
            }
            parseForm(position + 12, end, first);
            first = false;
            if (++forms > 4096) {
                throw new MediaException.Malformed("too many RIFF forms");
            }
            position = end + (end & 1);
            if (size >= 0 && position >= size) {
                break;
            }
        }
        if (streams.isEmpty()) {
            throw new MediaException.Malformed("AVI file without stream headers");
        }
    }

    /**
     * Reads the chunks of one RIFF form.
     *
     * @param start the position of the first chunk
     * @param end the end of the form
     * @param first whether the form is the {@code AVI } form
     */
    private void parseForm(long start, long end, boolean first) {
        var position = start;
        var count = 0;
        while (position + 8 <= end) {
            reader.seek(position);
            var id = reader.fourCc();
            var chunkSize = reader.u32le();
            var body = position + 8;
            var chunkEnd = Math.min(end, body + chunkSize);
            if (id == fourCc("LIST") && chunkSize >= 4) {
                var listType = reader.fourCc();
                if (listType == fourCc("hdrl") && first) {
                    parseHeaderList(body + 4, chunkEnd);
                } else if (listType == fourCc("movi")) {
                    moviLists.add(new long[]{body, chunkEnd});
                } else if (listType == fourCc("INFO")) {
                    parseInfo(body + 4, chunkEnd);
                }
            } else if (id == fourCc("idx1") && first) {
                idx1Position = body;
                idx1Size = chunkEnd - body;
            }
            if (++count > 1 << 20) {
                throw new MediaException.Malformed("too many chunks in a RIFF form");
            }
            position = body + chunkSize + (chunkSize & 1);
        }
    }

    /**
     * Reads the {@code hdrl} list: the main header, the stream lists and the OpenDML header.
     *
     * @param start the position of the first chunk of the list
     * @param end the end of the list
     */
    private void parseHeaderList(long start, long end) {
        if (end - start > MAX_HEADER) {
            throw new MediaException.Malformed("oversized AVI header list");
        }
        var position = start;
        while (position + 8 <= end) {
            reader.seek(position);
            var id = reader.fourCc();
            var chunkSize = reader.u32le();
            var body = position + 8;
            var chunkEnd = Math.min(end, body + chunkSize);
            if (id == fourCc("avih") && chunkSize >= 4) {
                microsPerFrame = reader.u32le();
            } else if (id == fourCc("LIST") && chunkSize >= 4) {
                var listType = reader.fourCc();
                if (listType == fourCc("strl")) {
                    if (streams.size() >= 100) {
                        throw new MediaException.Malformed("more than 100 AVI streams");
                    }
                    streams.add(parseStream(body + 4, chunkEnd, streams.size()));
                } else if (listType == fourCc("odml")) {
                    reader.seek(body + 4);
                    if (chunkEnd - reader.position() >= 12 && reader.fourCc() == fourCc("dmlh")) {
                        reader.u32le();
                        extendedFrames = reader.u32le();
                    }
                }
            }
            position = body + chunkSize + (chunkSize & 1);
        }
    }

    /**
     * Reads one {@code strl} list into a stream.
     *
     * @param start the position of the first chunk of the list
     * @param end the end of the list
     * @param number the stream number
     * @return the stream, or {@code null} for a stream of an unsupported type
     */
    private Stream parseStream(long start, long end, int number) {
        var position = start;
        int type = 0;
        int handler = 0;
        long scale = 0;
        long rate = 0;
        long first = 0;
        long length = 0;
        long sampleSize = 0;
        byte[] format = null;
        byte[] properties = null;
        var superIndex = -1L;
        var superIndexSize = 0L;
        String name = null;
        while (position + 8 <= end) {
            reader.seek(position);
            var id = reader.fourCc();
            var chunkSize = reader.u32le();
            var body = position + 8;
            var available = Math.min(end, body + chunkSize) - body;
            if (id == fourCc("strh") && available >= 48) {
                type = reader.fourCc();
                handler = reader.fourCc();
                reader.u32le();
                reader.u32le();
                reader.u32le();
                scale = reader.u32le();
                rate = reader.u32le();
                first = reader.u32le();
                length = reader.u32le();
                reader.u32le();
                reader.u32le();
                sampleSize = reader.u32le();
            } else if (id == fourCc("strf")) {
                format = reader.bytes(Math.min(available, MAX_HEADER), MAX_HEADER);
            } else if (id == fourCc("vprp") && available >= VPRP_HEADER) {
                properties = reader.bytes(VPRP_HEADER, VPRP_HEADER);
            } else if (id == fourCc("indx")) {
                superIndex = body;
                superIndexSize = available;
            } else if (id == fourCc("strn") && available > 0) {
                var bytes = reader.bytes(Math.min(available, 4096), 4096);
                var text = new String(bytes, StandardCharsets.UTF_8);
                var zero = text.indexOf('\0');
                name = (zero >= 0 ? text.substring(0, zero) : text).strip();
            }
            position = body + chunkSize + (chunkSize & 1);
        }
        if (scale == 0 || rate == 0) {
            scale = 1;
            rate = microsPerFrame > 0 ? 1_000_000 : 25;
            if (microsPerFrame > 0) {
                scale = microsPerFrame;
            }
        }
        var timeBase = new Rational(scale, rate).reduce();
        Stream stream;
        if (type == fourCc("vids")) {
            stream = videoStream(number, handler, format, properties, timeBase, first, length);
        } else if (type == fourCc("auds")) {
            stream = audioStream(number, format, timeBase, scale, rate, first, length, sampleSize);
        } else if (type == fourCc("iavs")) {
            stream = new Stream(number, VideoTrack.builder().id(number + 1).codec(Codec.DV).timeBase(timeBase)
                    .duration(length > 0 ? first + length : -1).frameRate(timeBase.invert()).build(), true);
            stream.first = first;
            stream.interleavedDv = true;
        } else {
            return null;
        }
        if (stream == null) {
            return null;
        }
        stream.superIndex = superIndex;
        stream.superIndexSize = superIndexSize;
        stream.name = name;
        return stream;
    }

    /**
     * Returns the configuration of a HAP stream: its HAP sample entry type, from the compression FourCC or the
     * stream handler, in the case the HAP specification gives it.
     *
     * @param format the stream format, or {@code null}
     * @param handler the stream handler FourCC, or 0
     * @return the type in ASCII
     */
    private static byte[] hapType(byte[] format, int handler) {
        var type = format != null && format.length >= 20 ? Arrays.copyOfRange(format, 16, 20)
                : new byte[]{(byte) (handler >>> 24), (byte) (handler >>> 16), (byte) (handler >>> 8), (byte) handler};
        type[0] = 'H';
        type[1] = 'a';
        type[2] = 'p';
        if (type[3] >= 'a' && type[3] <= 'z') {
            type[3] = (byte) (type[3] - 'a' + 'A');
        }
        return type;
    }

    /**
     * Builds a video stream from its stream format.
     *
     * @param number the stream number
     * @param handler the handler FourCC of the stream header
     * @param format the {@code BITMAPINFOHEADER}, or {@code null}
     * @param properties the first {@value #VPRP_HEADER} bytes of the OpenDML video properties
     * header ({@code vprp}), or {@code null}
     * @param timeBase the time base
     * @param first the start time in time base units
     * @param length the length in frames
     * @return the stream
     */
    private Stream videoStream(int number, int handler, byte[] format, byte[] properties, Rational timeBase, long first,
                               long length) {
        var width = 0;
        var height = 0;
        var codec = Codec.UNKNOWN_VIDEO;
        byte[] config = null;
        if (format != null && format.length >= 40) {
            var headerSize = (int) Math.min(ByteArrays.u32le(format, 0), format.length);
            width = (int) Math.min(Math.abs((long) ByteArrays.i32le(format, 4)), Integer.MAX_VALUE);
            height = (int) Math.min(Math.abs((long) ByteArrays.i32le(format, 8)), Integer.MAX_VALUE);
            var compression = new String(format, 16, 4, StandardCharsets.ISO_8859_1);
            codec = RiffCodecs.video(compression);
            if (codec == Codec.UNKNOWN_VIDEO && handler != 0) {
                codec = RiffCodecs.video(fourCcString(handler));
            }
            // the extra data follows the 40 bytes of the header, which writers count in biSize or not
            var extra = headerSize == format.length ? 40 : headerSize;
            if (headerSize >= 40 && format.length > extra) {
                config = Arrays.copyOfRange(format, extra, format.length);
            }
            if (codec == Codec.UNKNOWN_VIDEO) {
                var raw = RawVideoTags.bitmap(format);
                if (raw == null && handler != 0) {
                    raw = RawVideoTags.fourCc(fourCcString(handler));
                }
                if (raw != null) {
                    codec = Codec.RAW_VIDEO;
                    config = raw.toCodecConfig();
                }
            }
        } else if (handler != 0) {
            codec = RiffCodecs.video(fourCcString(handler));
        }
        if (codec == Codec.HAP) {
            config = hapType(format, handler);
        }
        byte[] annexBExtra = null;
        var annexB = false;
        if ((codec == Codec.H264 || codec == Codec.HEVC) && (config == null || config.length < 7 || config[0] != 1)) {
            annexBExtra = config;
            config = null;
            annexB = true;
        }
        if (width > 0 && height > 0) {
            LimitChecks.checkPicture(limits, width, height);
        }
        var builder = VideoTrack.builder().id(number + 1).codec(codec).timeBase(timeBase)
                .duration(length > 0 ? first + length : -1)
                .codecConfig(config)
                .width(width)
                .height(height)
                .frameRate(timeBase.invert());
        if (properties != null) {
            var aspect = sampleAspect(properties, width, height);
            if (aspect != null) {
                builder.sampleAspectRatio(aspect);
            }
            var fields = ByteArrays.u32le(properties, 32);
            if (fields == 1 || fields == 2) {
                builder.interlacing(fields == 1 ? Interlacing.PROGRESSIVE : Interlacing.INTERLACED);
            }
        }
        var stream = new Stream(number, builder.build(), true);
        stream.annexB = annexB;
        stream.annexBExtra = annexBExtra;
        stream.first = first;
        return stream;
    }

    /**
     * Returns the sample aspect ratio of an OpenDML video properties header: the frame aspect
     * ratio ({@code dwFrameAspectRatio}, the horizontal term in the high 16 bits) against the
     * frame size ({@code dwFrameWidthInPixels} and {@code dwFrameHeightInLines}, or the stream
     * format's size when they are zero).
     *
     * @param properties the header
     * @param width the width of the stream format
     * @param height the height of the stream format
     * @return the ratio in lowest terms, or {@code null} when a term or a dimension is zero
     */
    private static Rational sampleAspect(byte[] properties, int width, int height) {
        var ratio = ByteArrays.u32le(properties, 20);
        var horizontal = ratio >>> 16;
        var vertical = ratio & 0xFFFF;
        var frameWidth = ByteArrays.u32le(properties, 24);
        var frameHeight = ByteArrays.u32le(properties, 28);
        if (frameWidth == 0 || frameHeight == 0) {
            frameWidth = width;
            frameHeight = height;
        }
        if (horizontal == 0 || vertical == 0 || frameWidth == 0 || frameHeight == 0) {
            return null;
        }
        return new Rational(horizontal * frameHeight, vertical * frameWidth).reduce();
    }

    /**
     * Builds an audio stream from its stream format.
     *
     * @param number the stream number
     * @param format the {@code WAVEFORMATEX}, or {@code null}
     * @param timeBase the time base of the stream header
     * @param scale the {@code dwScale} of the stream header
     * @param rate the {@code dwRate} of the stream header
     * @param first the start time in time base units
     * @param length the length in time base units
     * @param sampleSize the {@code dwSampleSize} of the stream header
     * @return the stream, or {@code null} without a format
     */
    private Stream audioStream(int number, byte[] format, Rational timeBase, long scale, long rate, long first,
                               long length, long sampleSize) {
        if (format == null || format.length < 14) {
            return null;
        }
        var wave = WaveFormat.parse(format, format.length, false);
        var channels = wave.channels();
        var sampleRate = (int) Math.min(wave.sampleRate(), Integer.MAX_VALUE);
        var blockAlign = wave.blockAlign();
        var bits = wave.bitsPerSample();
        var codec = wave.codec();
        LimitChecks.checkChannels(limits, Math.max(1, channels));
        var extra = wave.extra();
        var config = codec == Codec.AAC && extra.length > 0 ? extra : null;
        var codeSize = AdpcmBlocks.supports(codec) ? AdpcmBlocks.codeSize(codec, wave) : 0;
        if (AdpcmBlocks.supports(codec) && (codeSize < 0 || sampleRate <= 0)) {
            codec = Codec.UNKNOWN_AUDIO;
        }
        var adpcm = AdpcmBlocks.supports(codec);
        if (codec == Codec.ADPCM_MS) {
            config = WaveFormat.msAdpcmConfig(extra);
        }
        var mpegAudio = codec == Codec.MP3 || codec == Codec.MP2;
        var trackBase = (mpegAudio || adpcm) && sampleRate > 0 ? Rational.timeBaseOf(sampleRate) : timeBase;
        long duration = -1;
        if (length > 0) {
            duration = mpegAudio || adpcm ? timeBase.rescale(first + length, trackBase) : first + length;
            if (mpegAudio && sampleSize > 0) {
                duration = -1;
            }
        }
        var track = AudioTrack.builder().id(number + 1).codec(codec).timeBase(trackBase)
                .duration(duration)
                .codecConfig(config)
                .sampleRate(sampleRate)
                .layout(channels > 0 ? wave.layout() : ChannelLayout.defaultFor(1))
                .bitsPerSample(codec.isPcm() ? bits : adpcm && codec != Codec.ADPCM_G722 ? codeSize : 0)
                .build();
        var stream = new Stream(number, track, false);
        stream.first = first;
        stream.sampleSize = sampleSize > 0 ? sampleSize : 0;
        stream.blockAlign = Math.max(1, blockAlign);
        stream.mpegAudio = mpegAudio;
        if (adpcm) {
            stream.adpcmUnitBytes = AdpcmBlocks.hasBlocks(codec) ? blockAlign : AdpcmBlocks.unitBytes(codec, codeSize);
            stream.adpcmCodeSize = codeSize;
            stream.adpcmChannels = channels;
        }
        stream.headerTimeBase = timeBase;
        stream.sampleRate = sampleRate;
        if (mpegAudio && sampleSize > 0 && sampleRate > 0) {
            stream.bytesPerSecond = rate * (long) Math.max(1, (int) sampleSize) / Math.max(1, scale);
        }
        return stream;
    }

    /**
     * Reads the {@code LIST INFO} entries.
     *
     * @param start the position of the first entry
     * @param end the end of the list
     */
    private void parseInfo(long start, long end) {
        if (end - start > MAX_HEADER) {
            return;
        }
        var position = start;
        while (position + 8 <= end) {
            reader.seek(position);
            var id = reader.fourCc();
            var chunkSize = reader.u32le();
            var available = Math.min(end, position + 8 + chunkSize) - position - 8;
            if (available > 0 && available < 1 << 20) {
                var bytes = reader.bytes(available, 1 << 20);
                var text = new String(bytes, StandardCharsets.UTF_8);
                var zero = text.indexOf('\0');
                var value = (zero >= 0 ? text.substring(0, zero) : text).strip();
                if (!value.isEmpty()) {
                    metadata.put(fourCcString(id), value);
                }
            }
            position = position + 8 + chunkSize + (chunkSize & 1);
        }
    }

    /**
     * Locates every chunk of every stream and orders the chunks by file position.
     */
    private void buildIndex() {
        var indexed = false;
        for (var stream : streams) {
            if (stream != null && stream.superIndex >= 0) {
                indexed |= readSuperIndex(stream);
            }
        }
        if (!indexed && idx1Position >= 0) {
            indexed = readIdx1();
        }
        if (!indexed) {
            for (var stream : streams) {
                if (stream != null) {
                    stream.count = 0;
                }
            }
            walkMovi();
        }
        var total = 0L;
        for (var stream : streams) {
            if (stream != null) {
                total += stream.count;
                stream.computeTimes();
            }
        }
        LimitChecks.checkFrames(limits, total);
        orderStream = new int[(int) total];
        orderEntry = new int[(int) total];
        var heads = new int[streams.size()];
        for (var k = 0; k < total; k++) {
            var best = -1;
            var bestOffset = Long.MAX_VALUE;
            for (var s = 0; s < streams.size(); s++) {
                var stream = streams.get(s);
                if (stream != null && heads[s] < stream.count && stream.offsets[heads[s]] < bestOffset) {
                    bestOffset = stream.offsets[heads[s]];
                    best = s;
                }
            }
            orderStream[k] = best;
            orderEntry[k] = heads[best]++;
        }
        orderCount = (int) total;
    }

    /**
     * Reads the OpenDML super index of a stream and the standard indexes it points to.
     *
     * @param stream the stream whose super index is read
     * @return {@code true} when an index was read
     */
    private boolean readSuperIndex(Stream stream) {
        if (stream.superIndexSize < 24) {
            return false;
        }
        reader.seek(stream.superIndex);
        var longsPerEntry = reader.u16le();
        reader.u8();
        var indexType = reader.u8();
        var entries = reader.u32le();
        reader.u32le();
        reader.skip(12);
        if (indexType == 1) {
            readStandardIndex(stream, stream.superIndex, stream.superIndexSize);
            return stream.count > 0;
        }
        if (indexType != 0 || longsPerEntry != 4 || entries > (stream.superIndexSize - 24) / 16) {
            return false;
        }
        var targets = new long[(int) entries];
        for (var i = 0; i < entries; i++) {
            targets[i] = reader.u64le();
            reader.u32le();
            reader.u32le();
        }
        for (var target : targets) {
            if (target < 0 || reader.size() >= 0 && target + 8 > reader.size()) {
                continue;
            }
            reader.seek(target);
            reader.fourCc();
            var size = reader.u32le();
            readStandardIndex(stream, target + 8, size);
        }
        return stream.count > 0;
    }

    /**
     * Reads one OpenDML standard index ({@code AVI_INDEX_OF_CHUNKS}).
     *
     * @param stream the stream whose index entries are added
     * @param start the position of the index header
     * @param size the size of the index
     */
    private void readStandardIndex(Stream stream, long start, long size) {
        if (size < 24) {
            return;
        }
        reader.seek(start);
        var longsPerEntry = reader.u16le();
        var subType = reader.u8();
        var indexType = reader.u8();
        var entries = reader.u32le();
        reader.u32le();
        var base = reader.u64le();
        reader.u32le();
        if (indexType != 1 || subType != 0 || longsPerEntry != 2 || entries > (size - 24) / 8) {
            return;
        }
        for (var i = 0; i < entries; i++) {
            var offset = reader.u32le();
            var chunkSize = reader.u32le();
            stream.add(base + offset, (int) (chunkSize & 0x7FFFFFFFL), (chunkSize & 0x80000000L) == 0, limits);
        }
    }

    /**
     * Reads the legacy {@code idx1} index.
     *
     * @return {@code true} when the index located chunks
     */
    private boolean readIdx1() {
        if (moviLists.isEmpty()) {
            return false;
        }
        var count = idx1Size / 16;
        if (count == 0) {
            return false;
        }
        LimitChecks.checkFrames(limits, count);
        var movi = moviLists.getFirst()[0];
        reader.seek(idx1Position);
        long baseOffset = -1;
        for (var i = 0; i < count; i++) {
            var id = reader.fourCc();
            var flags = reader.u32le();
            var offset = reader.u32le();
            var size = reader.u32le();
            if ((flags & LIST_FLAG) != 0) {
                continue;
            }
            var stream = streamOf(id);
            if (stream == null) {
                continue;
            }
            if (baseOffset < 0) {
                var saved = reader.position();
                baseOffset = matchesChunk(movi + offset, id) ? movi : matchesChunk(offset, id) ? 0 : movi;
                reader.seek(saved);
            }
            stream.add(baseOffset + offset + 8, (int) Math.min(size, Integer.MAX_VALUE), (flags & KEYFRAME) != 0,
                    limits);
        }
        for (var stream : streams) {
            if (stream != null && stream.count > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a chunk with an identifier starts at a position.
     *
     * @param position the absolute offset of the candidate chunk header
     * @param id the identifier
     * @return {@code true} when it does
     */
    private boolean matchesChunk(long position, int id) {
        if (position < 0 || reader.size() >= 0 && position + 8 > reader.size()) {
            return false;
        }
        reader.seek(position);
        return reader.fourCc() == id;
    }

    /**
     * Returns the stream a chunk identifier belongs to.
     *
     * @param id the chunk identifier, {@code ##dc}, {@code ##wb} and so on
     * @return the stream, or {@code null}
     */
    private Stream streamOf(int id) {
        var high = (id >>> 24) & 0xFF;
        var low = (id >>> 16) & 0xFF;
        if (high < '0' || high > '9' || low < '0' || low > '9') {
            return null;
        }
        var number = (high - '0') * 10 + (low - '0');
        var kind = id & 0xFFFF;
        if (number >= streams.size() || kind == ('p' << 8 | 'c') || kind == ('i' << 8 | 'x')) {
            return null;
        }
        return streams.get(number);
    }

    /**
     * Locates chunks by walking the {@code movi} lists, for files without a usable index.
     */
    private void walkMovi() {
        var probe = new byte[64];
        for (var list : moviLists) {
            walkList(list[0] + 4, list[1], probe, 0);
        }
    }

    /**
     * Walks the chunks of a list, descending into {@code rec } lists.
     *
     * @param start the position of the first chunk
     * @param end the end of the list
     * @param probe a buffer for the start of video chunks
     * @param depth the nesting depth
     */
    private void walkList(long start, long end, byte[] probe, int depth) {
        var position = start;
        while (position + 8 <= end) {
            reader.seek(position);
            var id = reader.fourCc();
            var size = reader.u32le();
            if (id == fourCc("LIST") && depth < 4) {
                walkList(position + 12, Math.min(end, position + 8 + size), probe, depth + 1);
            } else {
                var stream = streamOf(id);
                if (stream != null && position + 8 + size <= end) {
                    var key = true;
                    if (stream.video) {
                        var head = (int) Math.min(size, probe.length);
                        reader.readFully(probe, 0, head);
                        key = stream.count == 0 || startsIntra(stream.track.codec(), probe, head);
                    }
                    stream.add(position + 8, (int) Math.min(size, Integer.MAX_VALUE), key, limits);
                }
            }
            position = position + 8 + size + (size & 1);
        }
    }

    /**
     * Returns whether the start of a video chunk begins an intra picture, for files without an
     * index.
     *
     * @param codec the codec of the stream
     * @param data the start of the chunk
     * @param length the number of valid bytes
     * @return {@code true} for a key frame
     */
    private static boolean startsIntra(Codec codec, byte[] data, int length) {
        switch (codec) {
            case MJPEG, JPEG2000, DV, PNG, TIFF, HAP -> {
                return true;
            }
            case CFHD -> {
                return IntermediateFrames.isCineFormKey(data, 0, length);
            }
            case MPEG4_PART2 -> {
                for (var i = 0; i + 4 < length; i++) {
                    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0xFF) == 0xB6) {
                        return (data[i + 4] & 0xC0) == 0;
                    }
                }
                return false;
            }
            case H264 -> {
                for (var i = 0; i + 3 < length; i++) {
                    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                        var type = data[i + 3] & 0x1F;
                        if (type == 5 || type == 7) {
                            return true;
                        }
                        if (type == 1) {
                            return false;
                        }
                    }
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.AVI;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags of a stream are its {@code strn} name, under {@code "title"}.
     */
    @Override
    public Map<String, String> trackMetadata(int trackId) {
        for (var stream : streams) {
            if (stream != null && stream.track.id() == trackId && stream.name != null && !stream.name.isEmpty()) {
                return Map.of("title", stream.name);
            }
        }
        return Map.of();
    }

    @Override
    public long durationMicros() {
        var longest = -1L;
        for (var stream : streams) {
            if (stream == null) {
                continue;
            }
            var end = stream.endTime();
            if (end >= 0) {
                try {
                    longest = Math.max(longest, stream.track.timeBase().toMicros(end));
                } catch (ArithmeticException exception) {
                    // a duration past the long range in microseconds is as good as unknown
                }
            }
        }
        if (longest < 0 && microsPerFrame > 0 && extendedFrames > 0
                && microsPerFrame <= Long.MAX_VALUE / extendedFrames) {
            longest = microsPerFrame * extendedFrames;
        }
        return longest;
    }

    /**
     * Returns the number of samples of a track, as the index lists them, without reading the samples.
     *
     * @param trackId the identifier of the track
     * @return the sample count, or {@code -1} for an unknown track or an MPEG audio track, whose index entries are not
     * frames
     */
    public long sampleCount(int trackId) {
        for (var stream : streams) {
            if (stream != null && stream.track.id() == trackId) {
                return stream.mpegAudio ? -1 : stream.count;
            }
        }
        return -1;
    }

    /**
     * Returns the total size of the samples of a track, as the index lists them, without reading the
     * samples.
     *
     * @param trackId the identifier of the track
     * @return the size in bytes, or {@code -1} for an unknown track
     */
    public long sampleBytes(int trackId) {
        for (var stream : streams) {
            if (stream != null && stream.track.id() == trackId) {
                var total = 0L;
                for (var i = 0; i < stream.count; i++) {
                    total += stream.sizes[i];
                }
                return total;
            }
        }
        return -1;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                var queued = pending.poll();
                if (queued != null) {
                    return queued;
                }
                if (cursor >= orderCount) {
                    return null;
                }
                var stream = streams.get(orderStream[cursor]);
                var entry = orderEntry[cursor];
                cursor++;
                if (entry < stream.skipBefore) {
                    continue;
                }
                var size = stream.sizes[entry];
                if (size == 0) {
                    continue;
                }
                var offset = stream.offsets[entry];
                if (reader.size() >= 0 && offset + size > reader.size()) {
                    size = (int) Math.max(0, reader.size() - offset);
                    if (size == 0) {
                        continue;
                    }
                }
                LimitChecks.checkAllocation(limits, size);
                var data = new byte[size];
                reader.seek(offset);
                reader.readFully(data, 0, size);
                var next = cursor < orderCount ? streams.get(orderStream[cursor]).offsets[orderEntry[cursor]]
                        : Long.MAX_VALUE;
                ForwardOnlySource.release(reader.source(), next);
                released = Math.max(released, next);
                readEnd = Math.max(readEnd, offset + size);
                if (stream.mpegAudio) {
                    stream.frame(data, entry, pending);
                    continue;
                }
                if (stream.adpcmUnitBytes > 0) {
                    stream.split(data, entry, pending);
                    continue;
                }
                if (stream.lengthPrefixed && NalUnits.looksLikeAnnexB(data)) {
                    data = NalUnits.toLengthPrefixed(data);
                }
                if (stream.dvAudio != null && DifProfile.detect(data, 0, data.length) == stream.dvProfile
                        && data.length >= stream.dvProfile.frameSize()) {
                    stream.dvAudio.split(data, 0, pending::add);
                }
                var time = stream.times[entry];
                var duration = Math.max(0, stream.times[entry + 1] - time);
                return new Packet(stream.track.id(), data, time, time, duration, stream.keys[entry]);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AVI chunk", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}: the index locates every chunk
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every video stream restarts at its last key frame at or before the target and every
     * audio stream at its last chunk starting at or before it; reading resumes at the first of
     * these chunks in file order, skipping the earlier chunks of the other streams.
     *
     * @throws MediaException.Unsupported if the source reads its input once and one of these chunks lies before the
     * bytes it was told are no longer needed and those bytes are gone; the demuxer stays where it was
     */
    @Override
    public void seek(long micros) {
        if (reader.source() instanceof ForwardOnlySource && released > 0) {
            var first = Long.MAX_VALUE;
            for (var stream : streams) {
                if (stream != null && stream.count > 0) {
                    first = Math.min(first, stream.offsets[Math.min(seekEntry(stream, micros), stream.count - 1)]);
                }
            }
            var end = Math.max(first, readEnd);
            if (first < released && !reader.buffers(first, end)
                    && !ForwardOnlySource.keeps(reader.source(), first, end)) {
                throw new MediaException.Unsupported("seek", "the AVI stream is read once and its data before "
                        + released + " is gone");
            }
        }
        pending.clear();
        var earliest = Integer.MAX_VALUE;
        var anyLeft = false;
        for (var stream : streams) {
            if (stream == null) {
                continue;
            }
            var target = SeekTimes.floor(micros, stream.track.timeBase());
            var chosen = seekEntry(stream, micros);
            var end = stream.endTime();
            if (end >= 0 && target >= end || chosen >= stream.count) {
                stream.skipBefore = stream.count;
                continue;
            }
            anyLeft = true;
            stream.skipBefore = chosen;
            stream.resetFraming();
            if (stream.dvAudio != null) {
                stream.dvAudio.restart(chosen, micros);
            }
            for (var k = 0; k < orderCount; k++) {
                if (orderStream[k] == stream.number && orderEntry[k] == chosen) {
                    earliest = Math.min(earliest, k);
                    break;
                }
            }
        }
        cursor = anyLeft && earliest != Integer.MAX_VALUE ? earliest : orderCount;
    }

    /**
     * Returns the chunk a stream restarts at for a seek: its last key frame (audio: its last chunk) starting at or
     * before the target, or its first key frame when none does.
     *
     * @param stream the stream whose chunks are searched
     * @param micros the target in microseconds
     * @return the chunk index, {@code stream.count} when the stream has no key frame
     */
    private static int seekEntry(Stream stream, long micros) {
        var target = SeekTimes.floor(micros, stream.track.timeBase());
        var chosen = -1;
        for (var i = 0; i < stream.count; i++) {
            if (stream.times[i] > target) {
                break;
            }
            if (stream.keys[i]) {
                chosen = i;
            }
        }
        if (chosen < 0) {
            chosen = 0;
            while (chosen < stream.count && !stream.keys[chosen]) {
                chosen++;
            }
        }
        return chosen;
    }

    /**
     * Describes an interleaved DV stream ({@code iavs}, a type-1 DV AVI) from its first frame: the format, picture
     * size, aspect ratio and scan of the video track, and the PCM tracks of the audio its DIF frames carry.
     *
     * @param stream the stream, its index built
     * @param firstId the track ID of its first audio track
     * @return the number of audio tracks found
     */
    private int configureDv(Stream stream, int firstId) {
        if (stream.count == 0) {
            return 0;
        }
        var length = 0;
        var chunks = Math.min(2, stream.count);
        for (var i = 0; i < chunks; i++) {
            length += stream.sizes[i];
        }
        var size = (int) Math.min(length, 2L * DifProfile.DVCPRO_HD_1080I50.frameSize());
        LimitChecks.checkAllocation(limits, size);
        var head = new byte[size];
        var at = 0;
        for (var i = 0; i < chunks && at < size; i++) {
            var part = (int) Math.min(stream.sizes[i], size - at);
            reader.seek(stream.offsets[i]);
            part = (int) Math.min(part, reader.remaining(part));
            reader.readFully(head, at, part);
            at += part;
        }
        var profile = DifProfile.detect(head, 0, Math.min(at, stream.sizes[0]));
        if (profile == null) {
            return 0;
        }
        var video = (VideoTrack) stream.track;
        var wide = profile.wide(head, 0, Math.min(at, profile.frameSize()));
        stream.track = video.toBuilder()
                .width(profile.width())
                .height(profile.height())
                .sampleAspectRatio(profile.sampleAspectRatio(wide))
                .color(profile.highDefinition() ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED)
                .interlacing(profile.progressive() ? Interlacing.PROGRESSIVE
                        : profile.highDefinition() ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST)
                .build();
        if (at < profile.frameSize()) {
            return 0;
        }
        stream.dvProfile = profile;
        var available = profile.progressive() && stream.count > 1 && stream.sizes[0] == profile.frameSize()
                ? Math.min(at, 2 * profile.frameSize()) : profile.frameSize();
        stream.dvAudio = DifAudioTracks.of(profile, head, 0, available, firstId);
        return stream.dvAudio.tracks().size();
    }

    /**
     * Builds the configuration record of an H.264 or HEVC stream stored as an Annex B byte stream from the
     * parameter sets of its extra data and of its first key frame, and has its packets converted to
     * length-prefixed form when one is built.
     *
     * @param stream the H.264 or HEVC stream, its index built
     */
    private void configure(Stream stream) {
        var hevc = stream.track.codec() == Codec.HEVC;
        var videoSets = new ArrayList<byte[]>();
        var sequenceSets = new ArrayList<byte[]>();
        var pictureSets = new ArrayList<byte[]>();
        NalUnits.Sink collect = (data, offset, length) -> {
            var type = NalUnits.type(data[offset], hevc);
            var target = hevc
                    ? type == 32 ? videoSets : type == 33 ? sequenceSets : type == 34 ? pictureSets : null
                    : type == 7 ? sequenceSets : type == 8 ? pictureSets : null;
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
            var entry = firstChunk(stream);
            if (entry >= 0) {
                var size = stream.sizes[entry];
                LimitChecks.checkAllocation(limits, size);
                var data = new byte[size];
                reader.seek(stream.offsets[entry]);
                reader.readFully(data, 0, size);
                if (NalUnits.looksLikeAnnexB(data)) {
                    NalUnits.forEachAnnexB(data, collect);
                }
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
     * Returns the index entry of the first non-empty key frame of a stream that the file holds
     * whole, or of its first non-empty chunk when no key frame is flagged.
     *
     * @param stream the video stream, its index built
     * @return the entry, or {@code -1} when the stream has no such chunk
     */
    private int firstChunk(Stream stream) {
        var size = reader.size();
        var fallback = -1;
        for (var entry = 0; entry < stream.count; entry++) {
            if (stream.sizes[entry] == 0 || size >= 0 && stream.offsets[entry] + stream.sizes[entry] > size) {
                continue;
            }
            if (stream.keys[entry]) {
                return entry;
            }
            if (fallback < 0) {
                fallback = entry;
            }
        }
        return fallback;
    }

    /**
     * Holds one stream: its track, its chunk index and the re-framing state of MPEG audio.
     */
    private static final class Stream {
        /**
         * The stream number.
         */
        final int number;

        /**
         * The track, given its configuration record once an Annex B stream is configured.
         */
        Track track;

        /**
         * Whether the stream is a video stream.
         */
        final boolean video;

        /**
         * The start time of the stream header, in its time base.
         */
        long first;

        /**
         * The {@code dwSampleSize} of an audio stream, 0 for one chunk per time unit.
         */
        long sampleSize;

        /**
         * The block alignment of an audio stream.
         */
        int blockAlign = 1;

        /**
         * Whether the stream is MPEG audio, which is re-framed.
         */
        boolean mpegAudio;

        /**
         * The bytes of a unit of an ADPCM or telephony stream (an IMA or Microsoft ADPCM block, which becomes one
         * packet, a GSM 06.10 block, the bytes of whole G.726 codes, a G.722 byte), or 0 for another stream.
         */
        int adpcmUnitBytes;

        /**
         * The G.726 code size of an ADPCM stream.
         */
        int adpcmCodeSize;

        /**
         * The channel count of an ADPCM stream.
         */
        int adpcmChannels;

        /**
         * Whether the stream is H.264 or HEVC without a configuration record in its extra data.
         */
        boolean annexB;

        /**
         * The extra data of an {@link #annexB} stream, which may hold Annex B parameter sets, or
         * {@code null}.
         */
        byte[] annexBExtra;

        /**
         * Whether the Annex B packets of the stream are converted to length-prefixed NAL units.
         */
        boolean lengthPrefixed;

        /**
         * Whether the stream is interleaved DV ({@code iavs}), whose chunks are DIF frames carrying the audio too.
         */
        boolean interleavedDv;

        /**
         * The format of the first frame of an interleaved DV stream, or {@code null}.
         */
        DifProfile dvProfile;

        /**
         * The audio tracks an interleaved DV stream carries, or {@code null}.
         */
        DifAudioTracks dvAudio;

        /**
         * The time base of the stream header.
         */
        Rational headerTimeBase;

        /**
         * The sample rate of an audio stream.
         */
        int sampleRate;

        /**
         * The byte rate of a constant bitrate MPEG audio stream, or 0.
         */
        long bytesPerSecond;

        /**
         * The position of the {@code indx} chunk body, or {@code -1}.
         */
        long superIndex = -1;

        /**
         * The size of the {@code indx} chunk body.
         */
        long superIndexSize;

        /**
         * The {@code strn} name, or {@code null}.
         */
        String name;

        /**
         * The positions of the chunks' data.
         */
        long[] offsets = new long[64];

        /**
         * The sizes of the chunks.
         */
        int[] sizes = new int[64];

        /**
         * Whether each chunk is a key frame.
         */
        boolean[] keys = new boolean[64];

        /**
         * The timestamps of the chunks in the track time base.
         */
        long[] times;

        /**
         * The number of chunks.
         */
        int count;

        /**
         * The first chunk to return after a seek.
         */
        int skipBefore;

        /**
         * The bytes of MPEG audio not yet framed.
         */
        byte[] carry = new byte[0];

        /**
         * The timestamp of the next MPEG audio frame, in samples, or {@code Long.MIN_VALUE} until
         * the next chunk sets it.
         */
        long nextSample = Long.MIN_VALUE;

        /**
         * Constructs a stream.
         *
         * @param number the stream number
         * @param track the track this stream feeds
         * @param video whether the stream is a video stream
         */
        Stream(int number, Track track, boolean video) {
            this.number = number;
            this.track = track;
            this.video = video;
        }

        /**
         * Appends a chunk to the index.
         *
         * @param offset the position of its data
         * @param size its size
         * @param key whether it is a key frame
         * @param limits the limits bounding the chunk count
         */
        void add(long offset, int size, boolean key, MediaLimits limits) {
            if (count == offsets.length) {
                if (count >= MAX_CHUNKS) {
                    throw new MediaException.LimitExceeded("AVI stream with more than " + MAX_CHUNKS + " chunks");
                }
                LimitChecks.checkFrames(limits, count * 2L);
                offsets = Arrays.copyOf(offsets, count * 2);
                sizes = Arrays.copyOf(sizes, count * 2);
                keys = Arrays.copyOf(keys, count * 2);
            }
            offsets[count] = offset;
            sizes[count] = size;
            keys[count] = key || !video;
            count++;
        }

        /**
         * Computes the timestamp of every chunk.
         */
        void computeTimes() {
            times = new long[count + 1];
            var bytes = 0L;
            for (var i = 0; i <= count; i++) {
                times[i] = headerTime(i, bytes);
                if (i < count) {
                    bytes += sizes[i];
                }
            }
            if (video && count > 0) {
                keys[0] = true;
            }
        }

        /**
         * Returns the timestamp of a chunk in the track time base.
         *
         * @param index the chunk index
         * @param bytes the bytes of the stream before the chunk
         * @return the timestamp
         */
        private long headerTime(int index, long bytes) {
            if (video) {
                return first + index;
            }
            if (adpcmUnitBytes > 0) {
                return headerTimeBase.rescale(first, track.timeBase()) + bytes / adpcmUnitBytes
                        * AdpcmBlocks.samples(track.codec(), adpcmUnitBytes, adpcmChannels, adpcmCodeSize);
            }
            var units = sampleSize > 0 ? first + bytes / sampleSize : first + index;
            if (!mpegAudio) {
                return units;
            }
            if (sampleSize > 0 && bytesPerSecond > 0) {
                return headerTimeBase.rescale(first, track.timeBase()) + bytes * sampleRate / bytesPerSecond;
            }
            return headerTimeBase.rescale(units, track.timeBase());
        }

        /**
         * {@return the end of the stream in the track time base, or {@code -1} when the stream has no chunk}
         */
        long endTime() {
            return count == 0 ? -1 : times[count];
        }

        /**
         * Forgets the framing state, as when reading restarts at another chunk.
         */
        void resetFraming() {
            carry = new byte[0];
            nextSample = Long.MIN_VALUE;
        }

        /**
         * Splits MPEG audio chunk data into whole frames.
         *
         * @param data the chunk data
         * @param entry the chunk index
         * @param output receives one packet per complete frame
         */
        void frame(byte[] data, int entry, ArrayDeque<Packet> output) {
            if (nextSample == Long.MIN_VALUE) {
                nextSample = times[entry];
                carry = new byte[0];
            }
            var buffer = carry.length == 0 ? data : concat(carry, data);
            var offset = 0;
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
                var frame = Arrays.copyOfRange(buffer, offset, offset + size);
                var samples = MpegAudioHeader.samplesPerFrame(header);
                output.add(new Packet(track.id(), frame, nextSample, nextSample, samples, true));
                nextSample += samples;
                offset += size;
            }
            carry = offset >= buffer.length ? new byte[0] : Arrays.copyOfRange(buffer, offset, buffer.length);
            if (carry.length > 1 << 16) {
                carry = new byte[0];
            }
        }

        /**
         * Splits the chunk data of an ADPCM or telephony stream into packets: one per IMA or Microsoft ADPCM block,
         * the leading bytes that hold whole codes for the other codecs, timed in samples.
         *
         * @param data the chunk data
         * @param entry the chunk index
         * @param output receives the packets
         */
        void split(byte[] data, int entry, ArrayDeque<Packet> output) {
            var codec = track.codec();
            var time = times[entry];
            var step = AdpcmBlocks.hasBlocks(codec) ? adpcmUnitBytes : data.length;
            for (var offset = 0; offset < data.length; offset += step) {
                var length = AdpcmBlocks.tail(codec, Math.min(step, data.length - offset), adpcmChannels,
                        adpcmCodeSize);
                var samples = AdpcmBlocks.samples(codec, length, adpcmChannels, adpcmCodeSize);
                if (samples == 0) {
                    break;
                }
                var packet = offset == 0 && length == data.length ? data
                        : Arrays.copyOfRange(data, offset, offset + length);
                output.add(new Packet(track.id(), packet, time, time, samples, true));
                time += samples;
            }
        }

        /**
         * Concatenates two arrays.
         *
         * @param a the first array
         * @param b the second array
         * @return the concatenation
         */
        private static byte[] concat(byte[] a, byte[] b) {
            var result = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }
    }
}
