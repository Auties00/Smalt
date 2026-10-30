package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads Flash Video files ({@code .flv}, FLV specification version 10.1 Annex E) and their Enhanced RTMP v2
 * extensions.
 *
 * <p>The legacy tag headers carry Sorenson Spark ({@link Codec#FLV1}), H.264 and, as {@link Codec#UNKNOWN_VIDEO},
 * Screen video and On2 VP6; MP3, AAC, Speex, linear PCM (8-bit unsigned or 16-bit little-endian), G.711 A-law and
 * mu-law and, as {@link Codec#UNKNOWN_AUDIO}, Nellymoser and the SWF ADPCM. The enhanced headers carry the video
 * FourCCs {@code avc1}, {@code hvc1}, {@code av01}, {@code vp09} and {@code vp08} ({@code vvc1} as
 * {@link Codec#UNKNOWN_VIDEO}) and the audio FourCCs {@code mp4a}, {@code Opus}, {@code fLaC}, {@code .mp3},
 * {@code ac-3} and {@code ec-3}, the multitrack messages of every kind, the multichannel configuration, the
 * {@code colorInfo} metadata frame (as {@link VideoTrack#color()}) and the {@code ModEx} extensions (whose
 * nanosecond timestamp offsets are not applied, since timestamps keep the millisecond time base).
 *
 * <p>Every track has the time base {@code 1/1000}: a packet's decoding timestamp is its tag's timestamp (a signed
 * 32-bit value), and its presentation timestamp adds the composition time offset of H.264, HEVC and VVC frames.
 * Video packets last the frame period of the track's frame rate, rounded to a millisecond, when it is known; the
 * other packets carry {@link Packet#UNKNOWN_DURATION}. Video packets of key frames are flagged {@link PacketFlag#KEY},
 * disposable inter frames {@link PacketFlag#DISCARDABLE}; every audio packet is a key frame. The codec
 * configurations follow {@link Track#codecConfig()}: an AAC sequence header is the {@code AudioSpecificConfig}, an
 * H.264 or HEVC one the decoder configuration record, a VP9 or VP8 one the {@code vpcC} record without its full box
 * header, an Opus one the {@code OpusHead} (synthesized from the channel count when the sequence start is empty), a
 * FLAC one {@code fLaC} with its metadata blocks. An empty sequence start is ignored. A sequence header that changes
 * a track's configuration later in the file reaches the next packet of the track as
 * {@link PacketSideData#newCodecConfig()}.
 *
 * <p>The track list comes from the tags of the start of the file: the demuxer reads a second of media and then
 * tags until every track it met has its configuration and a frame and every media type the header or the
 * {@code onMetaData} object announces has a track, or until 8 MiB, 4096 tags or 10 seconds of media were read.
 * Frames of a track that first appears later, of a codec other than the one its track started with, and of tracks
 * beyond the 64th are skipped. The frame
 * dimensions come from the configuration or the first frame, else from {@code onMetaData}; the frame rate from
 * {@code onMetaData} ({@code videoTrackIdInfoMap} for the extra tracks of a multitrack file). Script data tags other
 * than {@code onMetaData} are skipped; {@link #metadata()} holds the numbers, booleans and strings of the first
 * {@code onMetaData}, numbers as their shortest decimal form. The duration is that of {@code onMetaData}, or the
 * span of the tag timestamps when it declares none.
 *
 * <p>A tag whose type is not audio, video or script data makes the demuxer look for the next plausible tag (one
 * whose following back-pointer matches its size) within 1 MiB; a tag whose body contradicts its size is skipped;
 * a truncated last tag ends the stream. An encrypted tag (filter flag set) raises
 * {@link MediaException.Unsupported}.
 *
 * <p>The demuxer seeks through the {@code keyframes} object of {@code onMetaData} ({@code times} in seconds and
 * {@code filepositions} of the video tags of the key frames) when it has one whose positions point at key frame
 * tags of those times, walking back through the back-pointers to the audio before the key frame, and otherwise
 * through an index of every tag built on the first seek. A source that reads its input once, front to back, is
 * told after every tag which bytes are no longer needed.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation, when it seeks through the {@code onMetaData} key frames, sees the codec
 * configuration changes only among the tags it walks back over; a configuration change made earlier and never
 * repeated reaches the decoder only after a seek through the full index.
 */
public final class FlvDemuxer implements Demuxer {
    /**
     * The size of a tag header.
     */
    private static final int TAG_HEADER = 11;

    /**
     * The tag type of audio.
     */
    private static final int AUDIO = 8;

    /**
     * The tag type of video.
     */
    private static final int VIDEO = 9;

    /**
     * The tag type of script data.
     */
    private static final int SCRIPT = 18;

    /**
     * The most tracks read.
     */
    private static final int MAX_TRACKS = 64;

    /**
     * The most bytes read to find the tracks.
     */
    private static final long PROBE_BYTES = 8L << 20;

    /**
     * The most tags read to find the tracks.
     */
    private static final int PROBE_TAGS = 4096;

    /**
     * The most media time read to find the tracks, in milliseconds.
     */
    private static final long PROBE_MILLIS = 10_000;

    /**
     * The media time read to find the tracks even when every track is complete, in milliseconds, so that the extra
     * tracks of a multitrack file that start a little later are found.
     */
    private static final long PROBE_MIN_MILLIS = 1000;

    /**
     * The farthest the demuxer looks for a plausible tag after an implausible one.
     */
    private static final int RESYNC_BYTES = 1 << 20;

    /**
     * The most tags walked back from a key frame of the {@code onMetaData} index.
     */
    private static final int WALK_BACK_TAGS = 4096;

    /**
     * The bytes of a tag's body read to index it, unless it needs more.
     */
    private static final int INDEX_PREFIX = 32;

    /**
     * The key bit of the streams of video tags.
     */
    private static final int VIDEO_KEY = 0x100;

    /**
     * A unit holding a coded frame.
     */
    private static final int FRAME = 0;

    /**
     * A unit holding a sequence start: the codec configuration.
     */
    private static final int CONFIG = 1;

    /**
     * A unit holding a {@code colorInfo} object.
     */
    private static final int COLOR = 2;

    /**
     * A unit holding a multichannel configuration.
     */
    private static final int CHANNELS = 3;

    /**
     * The sampling rates of the legacy {@code SoundRate} field.
     */
    private static final int[] SOUND_RATES = {5512, 11025, 22050, 44100};

    /**
     * The speaker of each {@code AudioChannel} code, for the channels of a custom multichannel order.
     */
    private static final Speaker[] SPEAKERS = {
            Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
            Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
            Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER,
            Speaker.BACK_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT,
            Speaker.TOP_CENTER, Speaker.TOP_FRONT_LEFT,
            Speaker.TOP_FRONT_CENTER, Speaker.TOP_FRONT_RIGHT,
            Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_CENTER,
            Speaker.TOP_BACK_RIGHT, Speaker.LOW_FREQUENCY_2,
            Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT,
            Speaker.BOTTOM_FRONT_CENTER, Speaker.BOTTOM_FRONT_LEFT,
            Speaker.BOTTOM_FRONT_RIGHT
    };

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The reader positioned at the next tag header.
     */
    private final SourceReader reader;

    /**
     * The offset of the first tag header.
     */
    private final long firstTag;

    /**
     * The streams, in the order their first tags appear.
     */
    private final List<Stream> streams = new ArrayList<>();

    /**
     * The streams by key: the tag type bit {@link #VIDEO_KEY} and the multitrack track identifier.
     */
    private final Map<Integer, Stream> byKey = new HashMap<>();

    /**
     * The tracks, in stream order.
     */
    private final List<Track> tracks;

    /**
     * The scalar values of the first {@code onMetaData} object.
     */
    private final Map<String, String> metadata;

    /**
     * The first {@code onMetaData} object, or an empty map.
     */
    private Map<String, Object> onMetaData = Map.of();

    /**
     * The duration in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The units parsed from the tag being processed.
     */
    private final List<Unit> units = new ArrayList<>();

    /**
     * The packets of the last tag not yet returned.
     */
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();

    /**
     * The filter trimming the packets after a seek, or {@code null}.
     */
    private SeekFilter filter;

    /**
     * Whether the end of the stream was reached.
     */
    private boolean finished;

    /**
     * The index of every startable packet, built on the first seek that needs it, or {@code null}.
     */
    private TagIndex index;

    /**
     * The times of the {@code onMetaData} key frames in milliseconds, or {@code null} when unusable.
     */
    private long[] keyframeTimes;

    /**
     * The tag positions of the {@code onMetaData} key frames.
     */
    private long[] keyframePositions;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not an FLV file
     * @throws MediaException.Io if reading fails
     */
    public FlvDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not an FLV file
     * @throws MediaException.LimitExceeded if a tag exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public FlvDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(options, "options").limits();
        try {
            this.reader = new SourceReader(source, 64 * 1024);
            if (reader.remaining(9) < 9) {
                throw new MediaException.Malformed("truncated FLV header");
            }
            if (reader.u8() != 'F' || reader.u8() != 'L' || reader.u8() != 'V') {
                throw new MediaException.Malformed("not an FLV file");
            }
            reader.u8();
            var flags = reader.u8();
            var dataOffset = reader.u32be();
            if (dataOffset < 9 || dataOffset > RESYNC_BYTES) {
                throw new MediaException.Malformed("invalid FLV header size " + dataOffset);
            }
            reader.seek(dataOffset + 4);
            this.firstTag = dataOffset + 4;
            probe((flags & 4) != 0, (flags & 1) != 0);
            reader.seek(firstTag);
            var built = new ArrayList<Track>(streams.size());
            for (var stream : streams) {
                stream.track = stream.video ? videoTrack(stream) : audioTrack(stream);
                if (stream.track instanceof VideoTrack video && video.frameRate().isPresent()) {
                    var rate = video.frameRate().get();
                    stream.frameDuration = Math.max(1, Math.round(1000.0 * rate.denominator() / rate.numerator()));
                }
                stream.current = stream.track.codecConfig().orElse(null);
                built.add(stream.track);
            }
            this.tracks = List.copyOf(built);
            this.metadata = scalars(onMetaData);
            this.durationMicros = duration();
            readKeyframeIndex();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid FLV file", exception);
        }
    }

    /**
     * Returns whether a stream starts with the FLV signature and a plausible header.
     *
     * @param header the first bytes of the stream
     * @return {@code true} for an FLV file
     */
    public static boolean matches(byte[] header) {
        if (header.length < 9 || header[0] != 'F' || header[1] != 'L' || header[2] != 'V' || (header[4] & 0xFA) != 0) {
            return false;
        }
        var dataOffset = ((long) (header[5] & 0xFF) << 24) | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 8)
                | (header[8] & 0xFF);
        return dataOffset >= 9 && dataOffset <= RESYNC_BYTES;
    }

    /**
     * Reads the tags of the start of the file until the tracks are known.
     *
     * @param audioFlag whether the header announces audio
     * @param videoFlag whether the header announces video
     */
    private void probe(boolean audioFlag, boolean videoFlag) {
        var start = reader.position();
        var tags = 0;
        var firstTime = Long.MIN_VALUE;
        var wantAudio = audioFlag;
        var wantVideo = videoFlag;
        var tag = new TagHeader();
        while (tags < PROBE_TAGS && reader.position() - start < PROBE_BYTES && nextTag(tag)) {
            tags++;
            var payload = payload(tag);
            if (tag.type == SCRIPT) {
                if (onMetaData.isEmpty()) {
                    readScript(payload);
                    if (!onMetaData.isEmpty()) {
                        wantAudio |= onMetaData.get("audiocodecid") instanceof Double;
                        wantVideo |= onMetaData.get("videocodecid") instanceof Double;
                    }
                }
                continue;
            }
            if (tag.encrypted) {
                continue;
            }
            if (firstTime == Long.MIN_VALUE) {
                firstTime = tag.timestamp;
            }
            parse(tag.type, tag.timestamp, payload, 0, payload.length, true);
            for (var unit : units) {
                learn(unit);
            }
            var span = tag.timestamp - firstTime;
            if ((span >= PROBE_MIN_MILLIS && ready(wantAudio, wantVideo)) || span > PROBE_MILLIS) {
                break;
            }
        }
        units.clear();
    }

    /**
     * Returns whether the probe has found every track it waits for.
     *
     * @param wantAudio whether an audio track is announced
     * @param wantVideo whether a video track is announced
     * @return {@code true} when every stream is complete and every announced media type has one
     */
    private boolean ready(boolean wantAudio, boolean wantVideo) {
        var audio = false;
        var video = false;
        for (var stream : streams) {
            if (!stream.seenFrame || (stream.needsConfig() && stream.config == null)) {
                return false;
            }
            audio |= !stream.video;
            video |= stream.video;
        }
        return (audio || !wantAudio) && (video || !wantVideo);
    }

    /**
     * Records what a unit of the probe tells about its stream.
     *
     * @param unit a unit of a probed tag
     */
    private void learn(Unit unit) {
        var stream = byKey.get(unit.key);
        if (stream == null) {
            if (streams.size() == MAX_TRACKS) {
                return;
            }
            stream = new Stream(unit.key, unit.codec, streams.size() + 1);
            streams.add(stream);
            byKey.put(unit.key, stream);
        }
        if (stream.codec != unit.codec) {
            return;
        }
        switch (unit.kind) {
            case FRAME -> {
                if (!stream.seenFrame) {
                    stream.seenFrame = true;
                    stream.firstFrame = unit.copy();
                    stream.legacy = unit.legacy;
                }
            }
            case CONFIG -> {
                if (stream.config == null && unit.end > unit.offset) {
                    stream.config = normalize(stream, unit.copy());
                }
            }
            case COLOR -> stream.colorInfo = unit.object;
            default -> stream.layout = unit.layout;
        }
    }

    /**
     * Reads the next tag header at the reader's position, looking for a plausible one after an implausible one.
     *
     * @param tag receives the header
     * @return {@code false} at the end of the input or of its last complete tag
     */
    private boolean nextTag(TagHeader tag) {
        while (true) {
            var position = reader.position();
            if (reader.remaining(TAG_HEADER) < TAG_HEADER) {
                return false;
            }
            var first = reader.u8();
            var size = reader.u24be();
            var timestamp = reader.u24be() | (reader.u8() << 24);
            reader.u24be();
            var type = first & 0x1F;
            if (type != AUDIO && type != VIDEO && type != SCRIPT) {
                if (!resync(position + 1)) {
                    return false;
                }
                continue;
            }
            if (reader.remaining(size) < size) {
                return false;
            }
            tag.position = position;
            tag.type = type;
            tag.size = size;
            tag.timestamp = timestamp;
            tag.encrypted = (first & 0x20) != 0;
            return true;
        }
    }

    /**
     * Moves the reader to the next plausible tag header: an audio, video or script data tag whose back-pointer
     * holds its size.
     *
     * <p>The candidate headers are read in order through one buffer and their back-pointers through a second,
     * small one, so that checking a candidate never discards the buffer of the scan.
     *
     * @param from the first offset tried
     * @return {@code true} when one was found within {@link #RESYNC_BYTES}
     */
    private boolean resync(long from) {
        var scan = new SourceReader(reader.source(), 64 * 1024);
        var check = new SourceReader(reader.source(), 64);
        for (var position = from; position < from + RESYNC_BYTES; position++) {
            scan.seek(position);
            if (scan.remaining(TAG_HEADER) < TAG_HEADER) {
                return false;
            }
            var type = scan.u8() & 0xFF;
            if (type != AUDIO && type != VIDEO && type != SCRIPT) {
                continue;
            }
            var size = scan.u24be();
            check.seek(position + TAG_HEADER + size);
            if (check.remaining(4) < 4) {
                continue;
            }
            if (check.u32be() == size + TAG_HEADER) {
                reader.seek(position);
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the body of the tag whose header was just read and moves past its back-pointer.
     *
     * @param tag the header
     * @return the body
     * @throws MediaException.LimitExceeded if the body exceeds the allocation limit
     */
    private byte[] payload(TagHeader tag) {
        LimitChecks.checkAllocation(limits, tag.size);
        var data = new byte[tag.size];
        reader.readFully(data, 0, data.length);
        reader.skip(Math.min(4, reader.remaining(4)));
        return data;
    }

    /**
     * Reads a script data tag and keeps its {@code onMetaData} object.
     *
     * @param payload the tag body
     */
    private void readScript(byte[] payload) {
        try {
            var amf = new Amf0(payload, 0, payload.length);
            var name = amf.read();
            if (!(name instanceof String text) || !text.equalsIgnoreCase("onMetaData") || !amf.hasMore()) {
                return;
            }
            if (amf.read() instanceof Map<?, ?> map) {
                var object = new LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    object.put((String) entry.getKey(), entry.getValue());
                }
                onMetaData = object;
            }
        } catch (MediaException.Malformed exception) {
            // a corrupt onMetaData leaves the file readable without it
        }
    }

    /**
     * Splits the body of an audio or video tag into units.
     *
     * @param type the tag type
     * @param timestamp the tag timestamp in milliseconds
     * @param body the bytes
     * @param offset the index of the body
     * @param end the end of the body
     * @param full whether the body is complete; a prefix of it otherwise, which may end a parse early
     */
    private void parse(int type, long timestamp, byte[] body, int offset, int end, boolean full) {
        units.clear();
        try {
            if (type == AUDIO) {
                parseAudio(new Body(body, offset, end), timestamp);
            } else {
                parseVideo(new Body(body, offset, end), timestamp);
            }
        } catch (TruncatedBody truncated) {
            if (full) {
                units.clear();
            }
        }
    }

    /**
     * Splits an audio tag body into units.
     *
     * @param body the reader of the tag body, after the tag header
     * @param timestamp the tag timestamp
     */
    private void parseAudio(Body body, long timestamp) {
        if (body.remaining() == 0) {
            return;
        }
        var first = body.u8();
        var format = first >>> 4;
        if (format != FlvCodecs.AUDIO_EX_HEADER) {
            var codec = legacyAudioCodec(format, first);
            if (format == FlvCodecs.AUDIO_AAC) {
                var packetType = body.u8();
                units.add(new Unit(0, packetType == 0 ? CONFIG : FRAME, codec, first, body, timestamp, timestamp, 1));
            } else {
                units.add(new Unit(0, FRAME, codec, first, body, timestamp, timestamp, 1));
            }
            return;
        }
        var packetType = modEx(body, first & 15);
        var multitrack = packetType == 5;
        var multitrackType = 0;
        var fourCc = 0;
        if (multitrack) {
            var next = body.u8();
            multitrackType = next >>> 4;
            packetType = next & 15;
            if (multitrackType != 2) {
                fourCc = body.u32();
            }
        } else {
            fourCc = body.u32();
        }
        while (true) {
            var trackId = 0;
            var end = body.end;
            if (multitrack) {
                if (multitrackType == 2) {
                    fourCc = body.u32();
                }
                trackId = body.u8();
                if (multitrackType != 0) {
                    end = body.position + body.u24() + 3;
                    if (end > body.end) {
                        throw TruncatedBody.INSTANCE;
                    }
                }
            }
            var codec = FlvCodecs.ofAudioFourCc(fourCc);
            var track = new Body(body.data, body.position, end);
            switch (packetType) {
                case 0 -> units.add(new Unit(trackId, CONFIG, codec, -1, track, timestamp, timestamp, 1));
                case 1 -> units.add(new Unit(trackId, FRAME, codec, -1, track, timestamp, timestamp, 1));
                case 4 -> units.add(Unit.layout(trackId, codec, multichannel(track)));
                case 2 -> {
                }
                default -> {
                    return;
                }
            }
            body.position = end;
            if (!multitrack || multitrackType == 0 || body.position >= body.end) {
                return;
            }
        }
    }

    /**
     * Returns the codec of a legacy audio tag.
     *
     * @param format the {@code SoundFormat}
     * @param first the first byte of the tag body, which holds the sample size
     * @return the codec
     */
    private static Codec legacyAudioCodec(int format, int first) {
        return switch (format) {
            case 0, FlvCodecs.AUDIO_PCM_LE -> (first & 2) != 0 ? Codec.PCM_S16LE : Codec.PCM_U8;
            case FlvCodecs.AUDIO_MP3, FlvCodecs.AUDIO_MP3_8K -> Codec.MP3;
            case FlvCodecs.AUDIO_ALAW -> Codec.PCM_ALAW;
            case FlvCodecs.AUDIO_MULAW -> Codec.PCM_MULAW;
            case FlvCodecs.AUDIO_AAC -> Codec.AAC;
            case FlvCodecs.AUDIO_SPEEX -> Codec.SPEEX;
            default -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Reads a multichannel configuration.
     *
     * @param body the body of the track's part of the tag
     * @return the layout, or {@code null} when it names only the channel count
     */
    private static ChannelLayout multichannel(Body body) {
        var order = body.u8();
        var count = body.u8();
        if (count == 0) {
            return null;
        }
        if (order == 2) {
            var speakers = new ArrayList<Speaker>(count);
            for (var index = 0; index < count; index++) {
                var code = body.u8();
                if (code >= SPEAKERS.length) {
                    return ChannelLayout.defaultFor(count);
                }
                speakers.add(SPEAKERS[code]);
            }
            return layoutOf(speakers, count);
        }
        if (order == 1) {
            var mask = body.u32();
            var speakers = new ArrayList<Speaker>(count);
            for (var bit = 0; bit < SPEAKERS.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    speakers.add(SPEAKERS[bit]);
                }
            }
            return layoutOf(speakers, count);
        }
        return ChannelLayout.defaultFor(count);
    }

    /**
     * Returns the layout of a list of speakers, or the default layout of the channel count when the speakers are
     * not distinct or not as many.
     *
     * @param speakers the speakers in channel order
     * @param count the channel count
     * @return the layout
     */
    private static ChannelLayout layoutOf(List<Speaker> speakers, int count) {
        if (speakers.size() == count && Set.copyOf(speakers).size() == count) {
            try {
                return new ChannelLayout(speakers);
            } catch (IllegalArgumentException exception) {
                return ChannelLayout.defaultFor(count);
            }
        }
        return ChannelLayout.defaultFor(count);
    }

    /**
     * Splits a video tag body into units.
     *
     * @param body the reader of the tag body, after the tag header
     * @param timestamp the tag timestamp
     */
    private void parseVideo(Body body, long timestamp) {
        if (body.remaining() == 0) {
            return;
        }
        var first = body.u8();
        if ((first & 0x80) == 0) {
            var frameType = first >>> 4;
            var codecId = first & 15;
            if (frameType == 5) {
                return;
            }
            var codec = FlvCodecs.ofLegacyVideo(codecId);
            if (codecId != FlvCodecs.VIDEO_AVC) {
                units.add(new Unit(VIDEO_KEY, FRAME, codec, frameType, body, timestamp, timestamp, frameType));
                return;
            }
            var packetType = body.u8();
            var composition = body.s24();
            if (packetType == 0) {
                units.add(new Unit(VIDEO_KEY, CONFIG, codec, frameType, body, timestamp, timestamp, frameType));
            } else if (packetType == 1) {
                units.add(new Unit(VIDEO_KEY, FRAME, codec, frameType, body, timestamp + composition, timestamp,
                        frameType));
            }
            return;
        }
        var frameType = (first >>> 4) & 7;
        var packetType = modEx(body, first & 15);
        if (packetType != 4 && frameType == 5) {
            return;
        }
        var multitrack = packetType == 6;
        var multitrackType = 0;
        var fourCc = 0;
        if (multitrack) {
            var next = body.u8();
            multitrackType = next >>> 4;
            packetType = next & 15;
            if (multitrackType != 2) {
                fourCc = body.u32();
            }
        } else {
            fourCc = body.u32();
        }
        while (true) {
            var trackId = 0;
            var end = body.end;
            if (multitrack) {
                if (multitrackType == 2) {
                    fourCc = body.u32();
                }
                trackId = body.u8();
                if (multitrackType != 0) {
                    end = body.position + body.u24() + 3;
                    if (end > body.end) {
                        throw TruncatedBody.INSTANCE;
                    }
                }
            }
            var key = VIDEO_KEY | trackId;
            var codec = FlvCodecs.ofVideoFourCc(fourCc);
            var track = new Body(body.data, body.position, end);
            switch (packetType) {
                case 0 -> units.add(new Unit(key, CONFIG, codec, frameType, track, timestamp, timestamp, frameType));
                case 1 -> {
                    var composition = FlvCodecs.hasCompositionTime(fourCc) ? track.s24() : 0;
                    units.add(new Unit(key, FRAME, codec, frameType, track, timestamp + composition, timestamp,
                            frameType));
                }
                case 3 -> units.add(new Unit(key, FRAME, codec, frameType, track, timestamp, timestamp, frameType));
                case 4 -> {
                    var color = videoMetadata(track);
                    if (color != null) {
                        units.add(Unit.color(key, codec, color));
                    }
                }
                case 2, 5 -> {
                }
                default -> {
                    return;
                }
            }
            body.position = end;
            if (!multitrack || multitrackType == 0 || body.position >= body.end) {
                return;
            }
        }
    }

    /**
     * Skips the {@code ModEx} extensions of an enhanced header.
     *
     * @param body the body, after the first byte
     * @param packetType the packet type of the first byte
     * @return the packet type after the extensions
     */
    private static int modEx(Body body, int packetType) {
        while (packetType == 7) {
            var size = body.u8() + 1;
            if (size == 256) {
                size = body.u16() + 1;
            }
            body.skip(size);
            packetType = body.u8() & 15;
        }
        return packetType;
    }

    /**
     * Reads the AMF0 name and value pairs of a video metadata frame and returns its {@code colorInfo} object.
     *
     * @param body the metadata
     * @return the object, or {@code null} when the frame holds none
     */
    private static Map<?, ?> videoMetadata(Body body) {
        try {
            var amf = new Amf0(body.data, body.position, body.end);
            while (amf.hasMore()) {
                var name = amf.read();
                if (!amf.hasMore()) {
                    return null;
                }
                var value = amf.read();
                if ("colorInfo".equals(name) && value instanceof Map<?, ?> map) {
                    return map;
                }
            }
        } catch (MediaException.Malformed exception) {
            return null;
        }
        return null;
    }

    /**
     * Builds the track of a video stream.
     *
     * @param stream the probed video stream
     * @return the track
     */
    private VideoTrack videoTrack(Stream stream) {
        var config = stream.codec == Codec.UNKNOWN_VIDEO || stream.codec == Codec.FLV1 ? null : stream.config;
        var info = VideoConfigs.describe(stream.codec, config);
        var width = info != null ? info.width() : 0;
        var height = info != null ? info.height() : 0;
        var frame = stream.firstFrame;
        if ((width <= 0 || height <= 0) && frame != null) {
            var size = switch (stream.codec) {
                case FLV1 -> sorensonSize(frame);
                case VP8 -> vp8Size(frame);
                case VP9 -> {
                    var key = VideoConfigs.vp9KeyFrame(frame);
                    yield key == null ? null : new int[]{key.width(), key.height()};
                }
                default -> null;
            };
            if (size != null) {
                width = size[0];
                height = size[1];
            }
        }
        var described = trackInfo(stream);
        if (width <= 0 || height <= 0) {
            width = positive(described.get("width"));
            height = positive(described.get("height"));
        }
        var builder = VideoTrack.builder().id(stream.id).codec(stream.codec).timeBase(Rational.MILLISECONDS)
                .codecConfig(config).width(Math.max(0, width)).height(Math.max(0, height));
        var rate = frameRate(described.get("framerate"));
        if (rate == null && info != null) {
            rate = info.frameRate();
        }
        builder.frameRate(rate);
        if (info != null && info.sampleAspect() != null) {
            builder.sampleAspectRatio(info.sampleAspect());
        }
        var color = color(info != null ? info.color() : null, stream.colorInfo);
        if (color != null) {
            builder.color(color);
        }
        return builder.build();
    }

    /**
     * Returns the {@code onMetaData} description of a stream: the top-level object for the streams of track 0, the
     * entry of {@code videoTrackIdInfoMap} or {@code audioTrackIdInfoMap} for the others.
     *
     * @param stream the stream whose description is wanted
     * @return the description, possibly empty
     */
    private Map<?, ?> trackInfo(Stream stream) {
        var trackId = stream.key & 0xFF;
        if (trackId == 0) {
            return onMetaData;
        }
        if (onMetaData.get(stream.video ? "videoTrackIdInfoMap" : "audioTrackIdInfoMap") instanceof Map<?, ?> map
                && map.get(Integer.toString(trackId)) instanceof Map<?, ?> entry) {
            return entry;
        }
        return Map.of();
    }

    /**
     * Builds the track of an audio stream.
     *
     * @param stream the probed audio stream
     * @return the track
     */
    private AudioTrack audioTrack(Stream stream) {
        var builder = AudioTrack.builder().id(stream.id).codec(stream.codec).timeBase(Rational.MILLISECONDS);
        var legacy = stream.legacy;
        var format = legacy >= 0 ? legacy >>> 4 : -1;
        var rate = legacy >= 0 ? SOUND_RATES[(legacy >> 2) & 3] : 0;
        var channels = legacy >= 0 ? (legacy & 1) + 1 : 0;
        var frame = stream.firstFrame;
        var config = stream.config;
        switch (stream.codec) {
            case AAC -> {
                if (config != null) {
                    var aac = AudioConfigs.parseAac(config);
                    rate = aac.outputRate() > 0 ? aac.outputRate() : aac.sampleRate();
                    var layout = aac.layout();
                    builder.layout(layout != null ? layout : ChannelLayout.defaultFor(Math.max(1, aac.channels())));
                    channels = 0;
                }
            }
            case MP3 -> {
                config = null;
                var header = frame == null ? null : AudioConfigs.parseMpegHeader(frame, 0, frame.length);
                if (header != null) {
                    rate = header.sampleRate();
                    channels = ((MpegAudioHeader.read(frame, 0) >>> 6) & 3) == MpegAudioHeader.MODE_MONO ? 1 : 2;
                    if (header.layer() != 3) {
                        builder.codec(Codec.MP2);
                    }
                } else if (format == FlvCodecs.AUDIO_MP3_8K) {
                    rate = 8000;
                }
            }
            case OPUS -> {
                rate = 48000;
                if (config == null || config.length == 0) {
                    var count = stream.layout != null ? stream.layout.channels()
                            : frame != null && frame.length > 0 && (frame[0] & 4) != 0 ? 2 : 1;
                    config = count <= 8 ? AudioConfigs.opusHead(count, 0, 48000, false) : null;
                    channels = count;
                } else {
                    channels = AudioConfigs.parseOpusHead(config).channels();
                }
            }
            case FLAC -> {
                if (config != null) {
                    var flac = AudioConfigs.parseFlac(config);
                    rate = flac.sampleRate();
                    channels = flac.channels();
                    builder.bitsPerSample(flac.bitDepth());
                }
            }
            case AC3, EAC3 -> {
                config = null;
                var described = frame == null ? null : Ac3Configs.describe(frame, 0, frame.length);
                if (described != null) {
                    rate = described.sampleRate();
                    builder.layout(described.layout());
                    channels = 0;
                }
            }
            case PCM_U8, PCM_S16LE -> {
                config = null;
                builder.bitsPerSample(stream.codec == Codec.PCM_U8 ? 8 : 16);
            }
            case PCM_ALAW, PCM_MULAW -> {
                config = null;
                rate = 8000;
                builder.bitsPerSample(8);
            }
            case SPEEX -> {
                config = null;
                rate = 16000;
                channels = 1;
            }
            default -> {
                config = null;
                if (format == 4 || format == 5) {
                    rate = format == 4 ? 16000 : 8000;
                    channels = 1;
                }
            }
        }
        if (stream.layout != null && stream.codec != Codec.AC3 && stream.codec != Codec.EAC3) {
            builder.layout(stream.layout);
            channels = 0;
        }
        var described = trackInfo(stream);
        if (rate <= 0) {
            rate = positive(described.get(stream.key == 0 ? "audiosamplerate" : "samplerate"));
        }
        if (channels > 0) {
            builder.layout(ChannelLayout.defaultFor(channels));
        }
        var track = builder.sampleRate(Math.max(0, rate)).codecConfig(config).build();
        if (track.codec() == Codec.AAC && config != null && AacExtensions.mayRefine(track)) {
            track = AacExtensions.refine(track, frame);
        }
        return track;
    }

    /**
     * Removes the full box header (version 1, flags 0) some writers keep in front of a {@code vpcC} record.
     *
     * @param config the record as the sequence start holds it
     * @return the record without the header
     */
    private static byte[] stripFullBox(byte[] config) {
        if (config.length >= 12 && config[0] == 1 && config[1] == 0 && config[2] == 0 && config[3] == 0
                && config.length == 12 + (((config[10] & 0xFF) << 8) | (config[11] & 0xFF))) {
            return Arrays.copyOfRange(config, 4, config.length);
        }
        return config;
    }

    /**
     * Reads the picture size of a Sorenson H.263 picture header.
     *
     * @param frame the picture
     * @return the width and height, or {@code null} when the header is invalid
     */
    static int[] sorensonSize(byte[] frame) {
        if (frame.length < 9 || frame[0] != 0 || frame[1] != 0 || (frame[2] & 0x80) == 0) {
            return null;
        }
        var bits = 0L;
        for (var index = 0; index < 8; index++) {
            bits = bits << 8 | (frame[index + 1] & 0xFF);
        }
        // bits holds the frame from bit 8 on: start code bit 8 to 24, version 5, temporal reference 8, size 3
        var position = 9 + 5 + 8;
        var size = (int) (bits >>> (64 - position - 3)) & 7;
        position += 3;
        return switch (size) {
            case 0 -> new int[]{(int) (bits >>> (64 - position - 8)) & 0xFF,
                    (int) (bits >>> (64 - position - 16)) & 0xFF};
            case 1 -> new int[]{(int) (bits >>> (64 - position - 16)) & 0xFFFF,
                    (int) (bits >>> (64 - position - 32)) & 0xFFFF};
            case 2 -> new int[]{352, 288};
            case 3 -> new int[]{176, 144};
            case 4 -> new int[]{128, 96};
            case 5 -> new int[]{320, 240};
            case 6 -> new int[]{160, 120};
            default -> null;
        };
    }

    /**
     * Reads the frame size of a VP8 key frame.
     *
     * @param frame the first frame of the stream
     * @return the width and height, or {@code null} when the frame is not a key frame
     */
    private static int[] vp8Size(byte[] frame) {
        if (frame.length < 10 || (frame[0] & 1) != 0 || (frame[3] & 0xFF) != 0x9D || (frame[4] & 0xFF) != 0x01
                || (frame[5] & 0xFF) != 0x2A) {
            return null;
        }
        return new int[]{((frame[6] & 0xFF) | ((frame[7] & 0xFF) << 8)) & 0x3FFF,
                ((frame[8] & 0xFF) | ((frame[9] & 0xFF) << 8)) & 0x3FFF};
    }

    /**
     * Returns the color description of a video track: the one the configuration signals, completed by the
     * {@code colorInfo} metadata.
     *
     * @param configured the description of the configuration, or {@code null}
     * @param colorInfo the {@code colorInfo} object, or {@code null}
     * @return the description, or {@code null} when neither gives one
     */
    private static ColorInfo color(ColorInfo configured, Map<?, ?> colorInfo) {
        if (colorInfo == null) {
            return configured;
        }
        var color = configured;
        if (colorInfo.get("colorConfig") instanceof Map<?, ?> config) {
            var primaries = code(config.get("colorPrimaries"));
            var transfer = code(config.get("transferCharacteristics"));
            var matrix = code(config.get("matrixCoefficients"));
            if (color == null && (primaries >= 0 || transfer >= 0 || matrix >= 0)) {
                color = new ColorInfo(MatrixCoefficients.of(Math.max(2, matrix)), ColorRange.LIMITED,
                        ColorPrimaries.of(Math.max(2, primaries)), TransferCharacteristics.of(Math.max(2, transfer)),
                        ChromaSiting.UNSPECIFIED, Optional.empty(), Optional.empty());
            }
        }
        if (color == null) {
            return null;
        }
        try {
            if (colorInfo.get("hdrMdcv") instanceof Map<?, ?> mdcv) {
                color = color.withMasteringDisplay(new MasteringDisplay(
                        chromaticity(mdcv, "redX", "redY"), chromaticity(mdcv, "greenX", "greenY"),
                        chromaticity(mdcv, "blueX", "blueY"), chromaticity(mdcv, "whitePointX", "whitePointY"),
                        number(mdcv.get("maxLuminance")), number(mdcv.get("minLuminance"))));
            }
        } catch (IllegalArgumentException exception) {
            // an incomplete or out-of-range mastering display is left out
        }
        try {
            if (colorInfo.get("hdrCll") instanceof Map<?, ?> cll) {
                color = color.withContentLightLevel(new ContentLightLevel((int) Math.round(number(cll.get("maxCLL"))),
                        (int) Math.round(number(cll.get("maxFall")))));
            }
        } catch (IllegalArgumentException exception) {
            // an out-of-range content light level is left out
        }
        return color;
    }

    /**
     * Returns a chromaticity of a mastering display object.
     *
     * @param mdcv the object
     * @param x the name of the x coordinate
     * @param y the name of the y coordinate
     * @return the chromaticity
     * @throws IllegalArgumentException if a coordinate is missing or invalid
     */
    private static Chromaticity chromaticity(Map<?, ?> mdcv, String x, String y) {
        return new Chromaticity(number(mdcv.get(x)), number(mdcv.get(y)));
    }

    /**
     * Returns a number of an AMF0 object.
     *
     * @param value the AMF0 value, a number when valid
     * @return the number
     * @throws IllegalArgumentException if the value is not a number
     */
    private static double number(Object value) {
        if (value instanceof Double number) {
            return number;
        }
        throw new IllegalArgumentException("not a number");
    }

    /**
     * Returns an H.273 code of an AMF0 number.
     *
     * @param value the AMF0 value, a number when valid
     * @return the code from 0 to 255, or {@code -1} when the value is not such a number
     */
    private static int code(Object value) {
        return value instanceof Double number && number >= 0 && number <= 255 ? (int) (double) number : -1;
    }

    /**
     * Returns a positive integer of an AMF0 number.
     *
     * @param value the AMF0 value, a number when valid
     * @return the integer, or {@code 0} when the value is not a positive number below 2^31
     */
    private static int positive(Object value) {
        return value instanceof Double number && number >= 1 && number < Integer.MAX_VALUE
                ? (int) Math.round(number) : 0;
    }

    /**
     * Returns the frame rate of an {@code onMetaData} {@code framerate}, recognizing the NTSC rates.
     *
     * @param value the {@code framerate} value, a number when valid
     * @return the rate, or {@code null} when the value is not a positive number below 10000
     */
    static Rational frameRate(Object value) {
        if (!(value instanceof Double number) || !(number > 0 && number < 10000)) {
            return null;
        }
        var rounded = Math.round(number);
        if (rounded > 0 && Math.abs(number - rounded) < 1e-6) {
            return new Rational(rounded, 1);
        }
        var ntsc = Math.round(number * 1.001);
        if (ntsc > 0 && Math.abs(number - ntsc * 1000.0 / 1001) < 1e-3) {
            return new Rational(ntsc * 1000, 1001);
        }
        var millis = Math.round(number * 1000);
        return millis > 0 ? new Rational(millis, 1000).reduce() : null;
    }

    /**
     * Returns the scalar values of an {@code onMetaData} object as text.
     *
     * @param object the {@code onMetaData} properties
     * @return the values, numbers in their shortest decimal form; unmodifiable
     */
    private static Map<String, String> scalars(Map<String, Object> object) {
        var values = new LinkedHashMap<String, String>();
        for (var entry : object.entrySet()) {
            var value = entry.getValue();
            if (value instanceof Double number) {
                values.put(entry.getKey(), Double.isFinite(number)
                        ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() : number.toString());
            } else if (value instanceof Boolean || value instanceof String) {
                values.put(entry.getKey(), value.toString());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns the duration: the {@code onMetaData} one, else the span of the tag timestamps when the last tag can
     * be read cheaply.
     *
     * @return the duration in microseconds, or {@code -1}
     */
    private long duration() {
        if (onMetaData.get("duration") instanceof Double seconds && seconds > 0 && seconds < 1e9) {
            return Math.round(seconds * 1_000_000);
        }
        var size = reader.size();
        if (size < firstTag + TAG_HEADER + 4 || !ForwardOnlySource.isCheapToReach(reader.source(), size)) {
            return -1;
        }
        var scan = new SourceReader(reader.source(), 64);
        scan.seek(firstTag);
        var first = scan.u8() & 0x1F;
        scan.skip(3);
        var start = scan.u24be() | (scan.u8() << 24);
        scan.seek(size - 4);
        var back = scan.u32be();
        if (first == 0 || back < TAG_HEADER || back > size - firstTag - 4) {
            return -1;
        }
        scan.seek(size - 4 - back);
        var type = scan.u8() & 0x1F;
        var length = scan.u24be();
        if ((type != AUDIO && type != VIDEO && type != SCRIPT) || length + TAG_HEADER != back) {
            return -1;
        }
        var end = scan.u24be() | (scan.u8() << 24);
        return end > start ? (end - start) * 1000L : -1;
    }

    /**
     * Keeps the {@code keyframes} index of {@code onMetaData} when its times and positions are consistent.
     */
    private void readKeyframeIndex() {
        if (!(onMetaData.get("keyframes") instanceof Map<?, ?> keyframes)
                || !(keyframes.get("times") instanceof List<?> times)
                || !(keyframes.get("filepositions") instanceof List<?> positions)
                || times.size() != positions.size() || times.isEmpty() || !byKey.containsKey(VIDEO_KEY)) {
            return;
        }
        var count = times.size();
        var millis = new long[count];
        var offsets = new long[count];
        for (var index = 0; index < count; index++) {
            if (!(times.get(index) instanceof Double time) || !(positions.get(index) instanceof Double offset)
                    || !(time >= 0 && time < 1e9) || !(offset >= firstTag && offset < 1e15)) {
                return;
            }
            millis[index] = Math.round(time * 1000);
            offsets[index] = (long) (double) offset;
            if (index > 0 && (millis[index] < millis[index - 1] || offsets[index] <= offsets[index - 1])) {
                return;
            }
        }
        keyframeTimes = millis;
        keyframePositions = offsets;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.FLV;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}: the {@code onMetaData} key frame index or an index of the tags built on the first seek
     * locates the key frames
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.LimitExceeded if the index of the tags built on the first seek meets more frames than
     * the limits allow
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        try {
            pending.clear();
            finished = false;
            if (tracks.isEmpty()) {
                finished = true;
                return;
            }
            var target = SeekTimes.floor(micros, Rational.MILLISECONDS);
            if (index == null && keyframeTimes != null && seekThroughKeyframes(target)) {
                filter = new SeekFilter(tracks, micros);
                return;
            }
            if (index == null) {
                index = buildIndex();
            }
            var position = index.start(target, tracks.size());
            if (position < 0) {
                finished = true;
                return;
            }
            for (var stream : streams) {
                var inForce = index.configAt(stream.index(), position);
                stream.current = stream.track.codecConfig().orElse(null);
                stream.pendingConfig = inForce != null && !Arrays.equals(inForce, stream.current) ? inForce : null;
            }
            reader.seek(position);
            filter = new SeekFilter(tracks, micros);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid FLV tag", exception);
        }
    }

    /**
     * Positions the reader through the {@code onMetaData} key frame index: at the last indexed key frame at or
     * before the target, moved back over the tags that hold the start packets of the other tracks.
     *
     * @param target the target in milliseconds
     * @return {@code false} when the index does not match the file, which then is indexed in full
     */
    private boolean seekThroughKeyframes(long target) {
        var chosen = 0;
        for (var index = 0; index < keyframeTimes.length; index++) {
            if (keyframeTimes[index] <= target) {
                chosen = index;
            }
        }
        var scan = new SourceReader(reader.source(), 4096);
        while (true) {
            var pts = keyframePts(scan, chosen);
            if (pts == Long.MIN_VALUE) {
                keyframeTimes = null;
                return false;
            }
            if (pts <= target || chosen == 0) {
                break;
            }
            chosen--;
        }
        var position = keyframePositions[chosen];
        var needed = new boolean[streams.size()];
        var missing = 0;
        for (var stream : streams) {
            stream.current = stream.track.codecConfig().orElse(null);
            stream.pendingConfig = null;
            if (stream.key != VIDEO_KEY) {
                needed[stream.index()] = true;
                missing++;
            }
        }
        var configured = new boolean[streams.size()];
        for (var steps = 0; steps < WALK_BACK_TAGS && position > firstTag && missing > 0; steps++) {
            scan.seek(position - 4);
            var back = scan.u32be();
            if (back < TAG_HEADER || back > position - 4 - firstTag) {
                break;
            }
            var previous = position - 4 - back;
            scan.seek(previous);
            var first2 = scan.u8();
            var size = scan.u24be();
            var timestamp = scan.u24be() | (scan.u8() << 24);
            scan.skip(3);
            var kind = first2 & 0x1F;
            if (size + TAG_HEADER != back || (kind != AUDIO && kind != VIDEO && kind != SCRIPT)) {
                break;
            }
            position = previous;
            if (kind == SCRIPT || (first2 & 0x20) != 0) {
                continue;
            }
            LimitChecks.checkAllocation(limits, size);
            var body = new byte[size];
            scan.readFully(body, 0, size);
            parse(kind, timestamp, body, 0, size, true);
            for (var unit : units) {
                var stream = byKey.get(unit.key);
                if (stream == null || stream.codec != unit.codec) {
                    continue;
                }
                var at = stream.index();
                if (unit.kind == CONFIG && !configured[at] && unit.end > unit.offset) {
                    configured[at] = true;
                    var config = normalize(stream, unit.copy());
                    if (!Arrays.equals(config, stream.current)) {
                        stream.pendingConfig = config;
                    }
                }
                if (unit.kind == FRAME && needed[at] && unit.pts <= target && (!stream.video || unit.key())) {
                    needed[at] = false;
                    missing--;
                }
            }
        }
        units.clear();
        reader.seek(position);
        return true;
    }

    /**
     * Returns the presentation timestamp of the key frame an {@code onMetaData} index entry points at.
     *
     * @param scan a reader of the source
     * @param entry the index entry
     * @return the timestamp, or {@link Long#MIN_VALUE} when the entry does not point at a key frame tag of the
     * default video track at its time
     */
    private long keyframePts(SourceReader scan, int entry) {
        scan.seek(keyframePositions[entry]);
        if (scan.remaining(TAG_HEADER) < TAG_HEADER) {
            return Long.MIN_VALUE;
        }
        var type = scan.u8() & 0x1F;
        var size = scan.u24be();
        var stamp = scan.u24be() | (scan.u8() << 24);
        scan.skip(3);
        if (type != VIDEO || Math.abs(stamp - keyframeTimes[entry]) > 1 || scan.remaining(size) < size) {
            return Long.MIN_VALUE;
        }
        LimitChecks.checkAllocation(limits, size);
        var body = new byte[size];
        scan.readFully(body, 0, size);
        parse(VIDEO, stamp, body, 0, size, true);
        var pts = Long.MIN_VALUE;
        for (var unit : units) {
            if (unit.key == VIDEO_KEY && unit.kind == FRAME && unit.key()) {
                pts = unit.pts;
                break;
            }
        }
        units.clear();
        return pts;
    }

    /**
     * Indexes every tag of the file from the first.
     *
     * @return the index
     */
    private TagIndex buildIndex() {
        var built = new TagIndex(streams.size());
        var scan = new SourceReader(reader.source(), 64 * 1024);
        var prefix = new byte[INDEX_PREFIX];
        scan.seek(firstTag);
        var tag = new TagHeader();
        var count = 0L;
        while (nextTag(scan, tag)) {
            if (tag.type == SCRIPT || tag.encrypted) {
                scan.skip(tag.size);
                scan.skip(Math.min(4, scan.remaining(4)));
                continue;
            }
            var head = Math.min(tag.size, INDEX_PREFIX);
            scan.readFully(prefix, 0, head);
            var needsAll = head > 0 && needsWholeBody(tag.type, prefix, head);
            byte[] body;
            if (needsAll && tag.size > head) {
                LimitChecks.checkAllocation(limits, tag.size);
                body = Arrays.copyOf(prefix, tag.size);
                scan.readFully(body, head, tag.size - head);
            } else {
                body = prefix;
                scan.skip(tag.size - head);
            }
            scan.skip(Math.min(4, scan.remaining(4)));
            parse(tag.type, tag.timestamp, body, 0, needsAll ? tag.size : head, needsAll || head == tag.size);
            for (var unit : units) {
                var stream = byKey.get(unit.key);
                if (stream == null || stream.codec != unit.codec) {
                    continue;
                }
                if (unit.kind == FRAME) {
                    LimitChecks.checkFrames(limits, ++count);
                }
                if (unit.kind == FRAME && (!stream.video || unit.key())) {
                    built.add(tag.position, unit.pts, stream.index());
                } else if (unit.kind == CONFIG && needsAll && unit.end > unit.offset) {
                    built.config(stream.index(), tag.position, normalize(stream, unit.copy()));
                }
            }
        }
        units.clear();
        return built;
    }

    /**
     * Returns whether indexing a tag needs its whole body: for a sequence start, whose configuration the index
     * keeps, and for a multitrack or {@code ModEx} header, whose layout only the whole body gives.
     *
     * @param type the tag type
     * @param prefix the first bytes of the body
     * @param length the number of bytes of {@code prefix}, at least 1
     * @return {@code true} when the prefix is not enough
     */
    private static boolean needsWholeBody(int type, byte[] prefix, int length) {
        var first = prefix[0] & 0xFF;
        var packetType = first & 15;
        if (type == AUDIO) {
            var format = first >>> 4;
            if (format == FlvCodecs.AUDIO_EX_HEADER) {
                return packetType == 0 || packetType == 5 || packetType == 7;
            }
            return format == FlvCodecs.AUDIO_AAC && length > 1 && prefix[1] == 0;
        }
        if ((first & 0x80) != 0) {
            return packetType == 0 || packetType == 6 || packetType == 7;
        }
        return packetType == FlvCodecs.VIDEO_AVC && length > 1 && prefix[1] == 0;
    }

    /**
     * Reads the next tag header of an index scan, ending at an implausible tag.
     *
     * @param scan the reader of the scan
     * @param tag receives the header
     * @return {@code false} at the end of the scan
     */
    private static boolean nextTag(SourceReader scan, TagHeader tag) {
        var position = scan.position();
        if (scan.remaining(TAG_HEADER) < TAG_HEADER) {
            return false;
        }
        var first = scan.u8();
        var size = scan.u24be();
        var timestamp = scan.u24be() | (scan.u8() << 24);
        scan.u24be();
        var type = first & 0x1F;
        if ((type != AUDIO && type != VIDEO && type != SCRIPT) || scan.remaining(size) < size) {
            return false;
        }
        tag.position = position;
        tag.type = type;
        tag.size = size;
        tag.timestamp = timestamp;
        tag.encrypted = (first & 0x20) != 0;
        return true;
    }

    /**
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        try {
            if (filter != null) {
                while (true) {
                    var packet = filter.poll();
                    if (packet != null) {
                        return packet;
                    }
                    if (filter.isTransparent()) {
                        filter = null;
                        return nextPacket();
                    }
                    var next = nextPacket();
                    if (next == null) {
                        filter.finish();
                    } else {
                        filter.offer(next);
                    }
                }
            }
            return nextPacket();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid FLV tag", exception);
        }
    }

    /**
     * Returns the next packet in file order.
     *
     * @return the packet, or {@code null} at the end of the input
     */
    private Packet nextPacket() {
        var tag = new TagHeader();
        while (pending.isEmpty()) {
            if (finished || !nextTag(tag)) {
                finished = true;
                return null;
            }
            if (tag.type == SCRIPT) {
                reader.skip(tag.size);
                reader.skip(Math.min(4, reader.remaining(4)));
                ForwardOnlySource.release(reader.source(), reader.position());
                continue;
            }
            if (tag.encrypted) {
                throw new MediaException.Unsupported("flv encryption", "encrypted FLV tags cannot be read");
            }
            var payload = payload(tag);
            ForwardOnlySource.release(reader.source(), reader.position());
            parse(tag.type, tag.timestamp, payload, 0, payload.length, true);
            for (var unit : units) {
                var stream = byKey.get(unit.key);
                if (stream == null || stream.codec != unit.codec) {
                    continue;
                }
                if (unit.kind == CONFIG && unit.end > unit.offset) {
                    var config = normalize(stream, unit.copy());
                    var effective = stream.pendingConfig != null ? stream.pendingConfig : stream.current;
                    if (!Arrays.equals(config, effective)) {
                        stream.pendingConfig = config;
                    }
                } else if (unit.kind == FRAME) {
                    pending.add(packet(stream, unit));
                }
            }
            units.clear();
        }
        return pending.poll();
    }

    /**
     * Returns a configuration in the form the track holds it.
     *
     * @param stream the stream of the configuration
     * @param config the configuration of a sequence start
     * @return the configuration
     */
    private static byte[] normalize(Stream stream, byte[] config) {
        return stream.codec == Codec.VP9 || stream.codec == Codec.VP8 ? stripFullBox(config) : config;
    }

    /**
     * Builds the packet of a frame unit.
     *
     * @param stream its stream
     * @param unit the frame unit to package
     * @return the packet
     */
    private Packet packet(Stream stream, Unit unit) {
        var data = unit.copy();
        var flags = EnumSet.noneOf(PacketFlag.class);
        if (!stream.video || unit.key()) {
            flags.add(PacketFlag.KEY);
        }
        if (stream.video && unit.frameType == 3) {
            flags.add(PacketFlag.DISCARDABLE);
        }
        var side = PacketSideData.NONE;
        if (stream.pendingConfig != null) {
            side = PacketSideData.builder().newCodecConfig(stream.pendingConfig).build();
            stream.current = stream.pendingConfig;
            stream.pendingConfig = null;
        }
        return new Packet(stream.id, data, unit.pts, unit.dts, stream.frameDuration, flags, side);
    }

    /**
     * Holds the fields of a tag header.
     */
    private static final class TagHeader {
        /**
         * The offset of the header.
         */
        long position;

        /**
         * The tag type.
         */
        int type;

        /**
         * The size of the body.
         */
        int size;

        /**
         * The timestamp in milliseconds.
         */
        long timestamp;

        /**
         * Whether the filter flag marks the body encrypted.
         */
        boolean encrypted;
    }

    /**
     * Reads the fields of a tag body.
     */
    private static final class Body {
        /**
         * The bytes.
         */
        final byte[] data;

        /**
         * The end of the body.
         */
        final int end;

        /**
         * The index of the next byte.
         */
        int position;

        /**
         * Constructs a reader over a range.
         *
         * @param data the bytes
         * @param position the first byte
         * @param end the end of the range
         */
        Body(byte[] data, int position, int end) {
            this.data = data;
            this.position = position;
            this.end = end;
        }

        /**
         * {@return the number of bytes left}
         */
        int remaining() {
            return end - position;
        }

        /**
         * Checks that bytes remain.
         *
         * @param count the bytes needed
         * @throws TruncatedBody if fewer remain
         */
        void need(int count) {
            if (end - position < count) {
                throw TruncatedBody.INSTANCE;
            }
        }

        /**
         * Skips bytes.
         *
         * @param count the number of bytes
         */
        void skip(int count) {
            need(count);
            position += count;
        }

        /**
         * {@return the next byte}
         */
        int u8() {
            need(1);
            return data[position++] & 0xFF;
        }

        /**
         * {@return the next big-endian 16-bit value}
         */
        int u16() {
            need(2);
            var value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        /**
         * {@return the next big-endian 24-bit value}
         */
        int u24() {
            need(3);
            var value = ((data[position] & 0xFF) << 16) | ((data[position + 1] & 0xFF) << 8)
                    | (data[position + 2] & 0xFF);
            position += 3;
            return value;
        }

        /**
         * {@return the next big-endian signed 24-bit value}
         */
        int s24() {
            return u24() << 8 >> 8;
        }

        /**
         * {@return the next big-endian 32-bit value}
         */
        int u32() {
            return (u16() << 16) | u16();
        }
    }

    /**
     * Signals a tag body that ends before its fields do.
     */
    private static final class TruncatedBody extends RuntimeException {
        /**
         * The shared instance, without a stack trace.
         */
        static final TruncatedBody INSTANCE = new TruncatedBody();

        /**
         * Constructs the shared instance.
         */
        private TruncatedBody() {
            super("truncated FLV tag body", null, false, false);
        }
    }

    /**
     * Describes one piece of a tag body: a frame, a configuration, a {@code colorInfo} object or a channel layout
     * of one stream.
     */
    private static final class Unit {
        /**
         * The stream key.
         */
        final int key;

        /**
         * The kind, one of {@link #FRAME}, {@link #CONFIG}, {@link #COLOR} and {@link #CHANNELS}.
         */
        final int kind;

        /**
         * The codec the tag names.
         */
        final Codec codec;

        /**
         * The first byte of a legacy audio tag, or {@code -1}.
         */
        final int legacy;

        /**
         * The bytes holding the payload.
         */
        final byte[] data;

        /**
         * The index of the payload.
         */
        final int offset;

        /**
         * The end of the payload.
         */
        final int end;

        /**
         * The presentation timestamp.
         */
        final long pts;

        /**
         * The decoding timestamp.
         */
        final long dts;

        /**
         * The video frame type, or 1 for audio.
         */
        final int frameType;

        /**
         * The {@code colorInfo} object, or {@code null}.
         */
        final Map<?, ?> object;

        /**
         * The channel layout, or {@code null}.
         */
        final ChannelLayout layout;

        /**
         * Constructs a frame or configuration unit over the rest of a body.
         *
         * @param key the stream key
         * @param kind the unit kind, {@link #FRAME} or {@link #CONFIG}
         * @param codec the codec the tag header names
         * @param legacy the first byte of a legacy audio tag, or any value for others
         * @param body the body, at the payload
         * @param pts the presentation timestamp
         * @param dts the decoding timestamp
         * @param frameType the frame type
         */
        Unit(int key, int kind, Codec codec, int legacy, Body body, long pts, long dts, int frameType) {
            this(key, kind, codec, codec.kind() == TrackKind.AUDIO ? legacy : -1,
                    body.data, body.position, body.end, pts, dts, frameType, null, null);
        }

        /**
         * Constructs a unit.
         *
         * @param key the stream key
         * @param kind the unit kind
         * @param codec the codec the tag header names
         * @param legacy the first byte of a legacy audio tag, or {@code -1}
         * @param data the bytes
         * @param offset the index of the payload
         * @param end the end of the payload
         * @param pts the presentation timestamp
         * @param dts the decoding timestamp
         * @param frameType the frame type
         * @param object the {@code colorInfo} object, or {@code null}
         * @param layout the channel layout, or {@code null}
         */
        private Unit(int key, int kind, Codec codec, int legacy, byte[] data, int offset, int end, long pts, long dts,
                     int frameType, Map<?, ?> object, ChannelLayout layout) {
            this.key = key;
            this.kind = kind;
            this.codec = codec;
            this.legacy = legacy;
            this.data = data;
            this.offset = offset;
            this.end = end;
            this.pts = pts;
            this.dts = dts;
            this.frameType = frameType;
            this.object = object;
            this.layout = layout;
        }

        /**
         * Returns a {@code colorInfo} unit.
         *
         * @param key the stream key
         * @param codec the codec the tag header names
         * @param object the {@code colorInfo} object of the metadata frame
         * @return the unit
         */
        static Unit color(int key, Codec codec, Map<?, ?> object) {
            return new Unit(key, COLOR, codec, -1, null, 0, 0, 0, 0, 0, object, null);
        }

        /**
         * Returns a multichannel configuration unit.
         *
         * @param key the stream key
         * @param codec the codec the tag header names
         * @param layout the layout, or {@code null}
         * @return the unit
         */
        static Unit layout(int key, Codec codec, ChannelLayout layout) {
            return new Unit(key, CHANNELS, codec, -1, null, 0, 0, 0, 0, 0, null, layout);
        }

        /**
         * {@return a copy of the payload}
         */
        byte[] copy() {
            return Arrays.copyOfRange(data, offset, end);
        }

        /**
         * {@return whether the unit is a key frame (frame type 1, or 4 for a generated one)}
         */
        boolean key() {
            return frameType == 1 || frameType == 4;
        }
    }

    /**
     * Collects what the tags tell about one elementary stream.
     */
    private static final class Stream {
        /**
         * The stream key.
         */
        final int key;

        /**
         * The codec of the first tag.
         */
        final Codec codec;

        /**
         * The track identifier.
         */
        final int id;

        /**
         * Whether the stream is video.
         */
        final boolean video;

        /**
         * The first byte of the first legacy audio frame, or {@code -1}.
         */
        int legacy = -1;

        /**
         * The first configuration, or {@code null}.
         */
        byte[] config;

        /**
         * The first frame, or {@code null}.
         */
        byte[] firstFrame;

        /**
         * Whether a frame was met.
         */
        boolean seenFrame;

        /**
         * The {@code colorInfo} object, or {@code null}.
         */
        Map<?, ?> colorInfo;

        /**
         * The layout of the multichannel configuration, or {@code null}.
         */
        ChannelLayout layout;

        /**
         * The track, once built.
         */
        Track track;

        /**
         * The duration of the packets, in milliseconds: the frame period of a video track's frame rate, else
         * {@link Packet#UNKNOWN_DURATION}.
         */
        long frameDuration = Packet.UNKNOWN_DURATION;

        /**
         * The configuration the packets returned so far use.
         */
        byte[] current;

        /**
         * A configuration the next packet announces, or {@code null}.
         */
        byte[] pendingConfig;

        /**
         * Constructs a stream.
         *
         * @param key the stream key
         * @param codec the codec of its first tag
         * @param id the track identifier
         */
        Stream(int key, Codec codec, int id) {
            this.key = key;
            this.codec = codec;
            this.id = id;
            this.video = (key & VIDEO_KEY) != 0;
        }

        /**
         * {@return the position of the stream in the track list}
         */
        int index() {
            return id - 1;
        }

        /**
         * {@return whether the stream cannot be decoded without a sequence start}
         */
        boolean needsConfig() {
            return switch (codec) {
                case H264, HEVC, AV1, AAC, FLAC -> true;
                default -> false;
            };
        }
    }

    /**
     * Indexes the startable packets of a file: the key frames of video tracks and every audio packet.
     */
    private static final class TagIndex {
        /**
         * The positions of the tags.
         */
        private long[] positions = new long[256];

        /**
         * The presentation timestamps of the packets.
         */
        private long[] times = new long[256];

        /**
         * The stream index of each entry.
         */
        private int[] streams = new int[256];

        /**
         * The number of entries.
         */
        private int count;

        /**
         * The positions of the configuration changes of each stream.
         */
        private final List<List<Long>> configPositions = new ArrayList<>();

        /**
         * The configurations of the changes of each stream.
         */
        private final List<List<byte[]>> configs = new ArrayList<>();

        /**
         * Constructs an empty index.
         *
         * @param streamCount the number of streams
         */
        TagIndex(int streamCount) {
            for (var index = 0; index < streamCount; index++) {
                configPositions.add(new ArrayList<>());
                configs.add(new ArrayList<>());
            }
        }

        /**
         * Adds a startable packet.
         *
         * @param position the position of its tag
         * @param time its presentation timestamp
         * @param stream its stream index
         */
        void add(long position, long time, int stream) {
            if (count == positions.length) {
                positions = Arrays.copyOf(positions, count * 2);
                times = Arrays.copyOf(times, count * 2);
                streams = Arrays.copyOf(streams, count * 2);
            }
            positions[count] = position;
            times[count] = time;
            streams[count] = stream;
            count++;
        }

        /**
         * Records a sequence start that changes the configuration of a stream.
         *
         * @param stream the stream index
         * @param position the position of its tag
         * @param config the configuration
         */
        void config(int stream, long position, byte[] config) {
            var list = configs.get(stream);
            if (list.isEmpty() || !Arrays.equals(list.getLast(), config)) {
                list.add(config);
                configPositions.get(stream).add(position);
            }
        }

        /**
         * Returns the configuration in force for a stream at a position.
         *
         * @param stream the stream index
         * @param position the offset of a tag header
         * @return the configuration of the last change before the position, or {@code null}
         */
        byte[] configAt(int stream, long position) {
            var at = configPositions.get(stream);
            byte[] chosen = null;
            for (var index = 0; index < at.size() && at.get(index) < position; index++) {
                chosen = configs.get(stream).get(index);
            }
            return chosen;
        }

        /**
         * Returns the position the demuxer reads from to reach every stream's last startable packet at or before a
         * target, or its first one when it has none.
         *
         * @param target the target in milliseconds
         * @param streamCount the number of streams
         * @return the position, or {@code -1} when nothing is indexed
         */
        long start(long target, int streamCount) {
            var chosen = new long[streamCount];
            var first = new long[streamCount];
            Arrays.fill(chosen, -1);
            Arrays.fill(first, -1);
            for (var index = 0; index < count; index++) {
                var stream = streams[index];
                if (first[stream] < 0) {
                    first[stream] = positions[index];
                }
                if (times[index] <= target) {
                    chosen[stream] = positions[index];
                }
            }
            var position = Long.MAX_VALUE;
            for (var stream = 0; stream < streamCount; stream++) {
                var candidate = chosen[stream] >= 0 ? chosen[stream] : first[stream];
                if (candidate >= 0) {
                    position = Math.min(position, candidate);
                }
            }
            return position == Long.MAX_VALUE ? -1 : position;
        }
    }
}
