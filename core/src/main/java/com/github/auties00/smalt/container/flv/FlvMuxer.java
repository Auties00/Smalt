package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Writes Flash Video files ({@code .flv}, FLV specification version 10.1 Annex E), with the Enhanced RTMP v2
 * headers for the codecs the legacy headers cannot name.
 *
 * <p>The first video track of Sorenson Spark ({@link Codec#FLV1}) or H.264 and the first audio track of AAC, MP3,
 * Speex (16 kHz mono), linear PCM ({@link Codec#PCM_U8} and {@link Codec#PCM_S16LE} at 5512, 11025, 22050 or 44100
 * Hz) or G.711 ({@link Codec#PCM_ALAW} and {@link Codec#PCM_MULAW} at 8 kHz) get the legacy tag headers that every
 * FLV reader understands. HEVC, AV1, VP9 and VP8 video and Opus, FLAC, AC-3 and E-AC-3 audio get the enhanced
 * FourCC headers, and every track after the first of its kind (up to 256 of each kind, all of codecs that have a
 * FourCC) is a track of a multitrack message with its own track identifier. The sequence start of a track (its
 * {@link Track#codecConfig()}, the {@code vpcC} record of VP9 and VP8 with its full box header; for AV1, VP9 and VP8
 * tracks without a configuration, the record their first key frame implies) precedes its first frame, and a packet
 * whose side data carries a new configuration is preceded by a new sequence start; tracks without a configuration
 * reject such a packet. H.264, HEVC and AV1, VP9 and VP8 tracks end with a sequence end tag.
 *
 * <p>Timestamps are rescaled to milliseconds and rounded: a tag's timestamp is the decoding time of an H.264 or
 * HEVC frame, whose presentation time follows as the composition time offset, and the presentation time of every
 * other packet (the decoding time when it has none, the previous timestamp of the track when it has neither). A
 * Sorenson picture is a key, inter or disposable inter frame as its picture type says; other video packets are key
 * or inter frames as their {@link Packet#keyframe()} flag says. Packets of the tracks are interleaved by
 * timestamp: the muxer holds each packet until every track has one waiting, or until it holds two seconds or 512
 * packets, then writes the earliest.
 *
 * <p>The {@code onMetaData} object states the duration (from time zero, or from the earliest presentation time when
 * that is negative, to the end of the last packet), the file size, the {@code encoder} and, for the first track
 * of each kind, the codec identifier (the legacy one, or the FourCC as a number), the frame size and rate or the
 * sampling rate, sample size and channel mode; {@code videoTrackIdInfoMap} and {@code audioTrackIdInfoMap} describe
 * the other tracks. The text entries of {@link #setTags(MediaTags)} become string properties of {@code onMetaData}
 * (an entry whose key is a property the muxer writes is left out), and its XMP packet an {@code onXMPData} tag;
 * pictures, chapters and track tags are not stored. With {@link FlvMuxerOptions#keyframeIndex()} the object also
 * holds the key frame index; see that option for how the output is written. The sink is written from its position
 * at construction and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class FlvMuxer implements Muxer {
    /**
     * The size of a tag header.
     */
    private static final int TAG_HEADER = 11;

    /**
     * The largest tag body: its size field has 24 bits.
     */
    private static final int MAX_BODY = 0xFFFFFF;

    /**
     * The longest time the interleaver holds packets, in milliseconds.
     */
    private static final long HOLD_MILLIS = 2000;

    /**
     * The most packets the interleaver holds.
     */
    private static final int HOLD_PACKETS = 512;

    /**
     * The most tracks of one kind.
     */
    private static final int MAX_TRACKS = 256;

    /**
     * The properties of {@code onMetaData} the muxer writes, which tags cannot replace.
     */
    private static final Set<String> RESERVED = Set.of("duration", "filesize", "width", "height", "framerate",
            "videocodecid", "audiocodecid", "audiosamplerate", "audiosamplesize", "stereo", "encoder", "keyframes",
            "videotrackidinfomap", "audiotrackidinfomap");

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The options.
     */
    private final FlvMuxerOptions options;

    /**
     * The sink position at construction, where the file starts.
     */
    private final long base;

    /**
     * The declared tracks, in declaration order.
     */
    private final List<Out> outs = new ArrayList<>();

    /**
     * The declared tracks by identifier.
     */
    private final Map<Integer, Out> byId = new HashMap<>();

    /**
     * The packets held for interleaving, earliest first.
     */
    private final PriorityQueue<Held> queue = new PriorityQueue<>();

    /**
     * The builder of tag headers.
     */
    private final ByteWriter head = new ByteWriter(64);

    /**
     * The builder of the codec-specific bytes of a tag body before its payload.
     */
    private final ByteWriter prefix = new ByteWriter(64);

    /**
     * The tags of {@link #setTags(MediaTags)}, or {@code null}.
     */
    private MediaTags tags;

    /**
     * The tags held until the key frame index is written, or {@code null} without the index.
     */
    private Spool spool;

    /**
     * The number of bytes of media tags written, the position of the next tag relative to the first media tag.
     */
    private long mediaBytes;

    /**
     * The sink position of the duration value of {@code onMetaData}, or {@code -1}.
     */
    private long durationAt = -1;

    /**
     * The sink position of the file size value of {@code onMetaData}, or {@code -1}.
     */
    private long fileSizeAt = -1;

    /**
     * The times of the indexed key frames in seconds.
     */
    private double[] keyTimes = new double[64];

    /**
     * The positions of the indexed key frame tags relative to the first media tag.
     */
    private long[] keyPositions = new long[64];

    /**
     * The number of indexed key frames.
     */
    private int keyCount;

    /**
     * The earliest presentation time in milliseconds.
     */
    private long startMillis = Long.MAX_VALUE;

    /**
     * The latest packet end in milliseconds.
     */
    private long endMillis = Long.MIN_VALUE;

    /**
     * The order in which packets were received, which breaks timestamp ties.
     */
    private long sequence;

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink with {@link FlvMuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public FlvMuxer(MediaSink output) {
        this(output, FlvMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the muxer options
     * @throws MediaException.Io if the sink position cannot be read
     */
    public FlvMuxer(MediaSink output, FlvMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        this.base = output.position();
    }

    /**
     * Returns whether FLV can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for Sorenson Spark, H.264, HEVC, AV1, VP9, VP8, AAC, MP3, Opus, FLAC, AC-3, E-AC-3,
     * Speex, 8-bit unsigned and 16-bit little-endian PCM, A-law and mu-law
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case FLV1, H264, HEVC, AV1, VP9, VP8, AAC, MP3, OPUS, FLAC, AC3, EAC3, SPEEX, PCM_U8, PCM_S16LE, PCM_ALAW,
                 PCM_MULAW -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the id is already used; the codec is not one {@link #supports(Codec)}
     * accepts; a Sorenson Spark, Speex, PCM, A-law or mu-law track is not the first of its kind; a 257th track of a
     * kind is declared; an H.264, HEVC, AAC or FLAC track has no configuration, or an Opus track neither a
     * configuration nor a layout of at most 8 channels; or a PCM, A-law, mu-law, Speex or legacy MP3 track has a
     * sampling rate or channel count its legacy header cannot state
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (byId.containsKey(track.id())) {
            throw new IllegalArgumentException("track id " + track.id() + " is already used");
        }
        var codec = track.codec();
        if (!supports(codec) || track.kind() == TrackKind.DATA) {
            throw new IllegalArgumentException("FLV cannot carry " + codec.displayName());
        }
        var video = track.kind() == TrackKind.VIDEO;
        var number = 0;
        for (var out : outs) {
            if (out.video == video) {
                number++;
            }
        }
        if (number == MAX_TRACKS) {
            throw new IllegalArgumentException("FLV carries at most " + MAX_TRACKS + " tracks of a kind");
        }
        var legacyOnly = codec == Codec.FLV1 || codec == Codec.SPEEX || codec.isPcm();
        if (legacyOnly && number > 0) {
            throw new IllegalArgumentException(codec.displayName() + " can only be the first track of its kind in FLV");
        }
        var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        var out = new Out(track, video, number, config);
        switch (codec) {
            case H264, HEVC, AAC, FLAC -> {
                if (config == null) {
                    throw new IllegalArgumentException(codec.displayName() + " track without a configuration");
                }
            }
            case OPUS -> {
                if (config == null) {
                    var channels = ((AudioTrack) track).layout().map(layout -> layout.channels()).orElse(0);
                    if (channels < 1 || channels > 8) {
                        throw new IllegalArgumentException("Opus track without a configuration or channel layout");
                    }
                    out.config = AudioConfigs.opusHead(channels, 0, Math.max(0, ((AudioTrack) track).sampleRate()),
                            false);
                }
            }
            default -> {
            }
        }
        if (!video) {
            out.legacyAudio = legacyAudioByte((AudioTrack) track, number == 0);
        }
        out.enhanced = video ? codec != Codec.FLV1 && (codec != Codec.H264 || number > 0)
                : out.legacyAudio < 0;
        outs.add(out);
        byId.put(track.id(), out);
    }

    /**
     * Returns the first byte of the legacy tag header of an audio track.
     *
     * @param track the audio track to describe
     * @param first whether it is the first audio track
     * @return the byte, or {@code -1} when the track gets the enhanced header
     * @throws IllegalArgumentException if the track's rate or channel count does not fit its legacy header
     */
    private static int legacyAudioByte(AudioTrack track, boolean first) {
        var channels = track.layout().map(layout -> layout.channels()).orElse(1);
        var rate = track.sampleRate();
        var codec = track.codec();
        if (!first || codec == Codec.OPUS || codec == Codec.FLAC || codec == Codec.AC3 || codec == Codec.EAC3) {
            return -1;
        }
        if (codec == Codec.AAC) {
            return FlvCodecs.AUDIO_AAC << 4 | 0x0F;
        }
        if (codec == Codec.SPEEX) {
            if ((rate != 0 && rate != 16000) || channels != 1) {
                throw new IllegalArgumentException("FLV carries Speex at 16000 Hz mono only");
            }
            return FlvCodecs.AUDIO_SPEEX << 4 | 0x02;
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("FLV carries " + codec.displayName() + " in one or two channels only");
        }
        var stereo = channels - 1;
        return switch (codec) {
            case MP3 -> rate == 8000 ? FlvCodecs.AUDIO_MP3_8K << 4 | 0x02 | stereo
                    : FlvCodecs.AUDIO_MP3 << 4 | rateCode(rate >= 44100 ? 44100 : rate >= 22050 ? 22050
                    : rate >= 11025 ? 11025 : 5512, codec) << 2 | 0x02 | stereo;
            case PCM_ALAW, PCM_MULAW -> {
                if (rate != 8000) {
                    throw new IllegalArgumentException("FLV carries " + codec.displayName() + " at 8000 Hz only");
                }
                yield (codec == Codec.PCM_ALAW ? FlvCodecs.AUDIO_ALAW : FlvCodecs.AUDIO_MULAW) << 4 | 0x02 | stereo;
            }
            default -> FlvCodecs.AUDIO_PCM_LE << 4 | rateCode(rate, codec) << 2
                    | (codec == Codec.PCM_S16LE ? 0x02 : 0) | stereo;
        };
    }

    /**
     * Returns the legacy {@code SoundRate} code of a sampling rate.
     *
     * @param rate the sampling rate
     * @param codec the codec, for the message
     * @return the code
     * @throws IllegalArgumentException if the rate has no code
     */
    private static int rateCode(int rate, Codec codec) {
        return switch (rate) {
            case 5512, 5513 -> 0;
            case 11025 -> 1;
            case 22050 -> 2;
            case 44100 -> 3;
            default -> throw new IllegalArgumentException("FLV carries " + codec.displayName()
                    + " at 5512, 11025, 22050 or 44100 Hz only, not " + rate);
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
     * <p>The text entries become string properties of {@code onMetaData} and the XMP packet an {@code onXMPData}
     * tag; pictures, chapters, attachments and track tags are not stored.
     *
     * @throws IllegalStateException if a packet was already written or the muxer is finished
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
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet's track was not declared, its body exceeds the 16 MiB of a tag,
     * or its timestamp or composition time offset does not fit the FLV fields
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the configuration of a track whose codec has none
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (outs.isEmpty()) {
            throw new IllegalStateException("no track was declared");
        }
        var out = byId.get(packet.trackId());
        if (out == null) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        if (!out.hasConfig()) {
            ConfigChanges.reject(packet, null, "FLV");
        }
        if (!started) {
            start();
        }
        var timeBase = out.track.timeBase();
        var pts = packet.pts() != Packet.NO_TIMESTAMP ? millis(packet.pts(), timeBase) : Long.MIN_VALUE;
        var dts = packet.dts() != Packet.NO_TIMESTAMP ? millis(packet.dts(), timeBase) : Long.MIN_VALUE;
        if (pts == Long.MIN_VALUE) {
            pts = dts != Long.MIN_VALUE ? dts : out.lastMillis;
        }
        if (dts == Long.MIN_VALUE) {
            dts = pts;
        }
        var composed = out.track.codec() == Codec.H264 || out.track.codec() == Codec.HEVC;
        var timestamp = composed ? dts : pts;
        var composition = composed ? pts - dts : 0;
        if (timestamp < Integer.MIN_VALUE || timestamp > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timestamp " + timestamp + " ms does not fit FLV");
        }
        if (composition < -0x800000 || composition > 0x7FFFFF) {
            throw new IllegalArgumentException("composition time offset " + composition + " ms does not fit FLV");
        }
        out.lastMillis = timestamp;
        var duration = packet.duration() > 0 ? millis(packet.duration(), timeBase) : 0;
        queue.add(new Held(out, packet, timestamp, (int) composition, pts, pts + duration, sequence++));
        out.queued++;
        drain(false);
    }

    /**
     * Rescales a time to milliseconds.
     *
     * @param value the time in units of {@code timeBase}
     * @param timeBase the time base
     * @return the time in milliseconds, rounded
     */
    private static long millis(long value, Rational timeBase) {
        try {
            return timeBase.rescale(value, Rational.MILLISECONDS);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timestamp " + value + " does not fit FLV", exception);
        }
    }

    /**
     * Writes held packets in timestamp order while the interleaving allows.
     *
     * @param all whether to write every held packet
     */
    private void drain(boolean all) {
        while (!queue.isEmpty()) {
            if (!all && !due()) {
                return;
            }
            var held = queue.poll();
            held.out.queued--;
            writeMedia(held);
        }
    }

    /**
     * Returns whether the earliest held packet can be written: every track has a packet held, or the hold limits
     * are reached.
     *
     * @return {@code true} when the earliest packet is due
     */
    private boolean due() {
        if (queue.size() > HOLD_PACKETS) {
            return true;
        }
        var waiting = true;
        var newest = Long.MIN_VALUE;
        for (var out : outs) {
            waiting &= out.queued > 0;
            newest = Math.max(newest, out.lastMillis);
        }
        return waiting || newest - queue.peek().timestamp > HOLD_MILLIS;
    }

    /**
     * Writes the tags of a packet: a sequence start when its track needs one, then the frame.
     *
     * @param held the packet with its timing
     */
    private void writeMedia(Held held) {
        var out = held.out;
        var packet = held.packet;
        var codec = out.track.codec();
        var changed = ConfigChanges.changed(packet, out.config);
        if (changed != null && out.hasConfig()) {
            out.config = changed;
            out.configWritten = false;
        }
        if (!out.configWritten) {
            if (out.config == null && packet.keyframe()) {
                out.config = derivedConfig(out, packet.data());
            }
            if (out.config != null) {
                writeSequenceStart(out, held.timestamp);
            }
            out.configWritten = out.config != null || !out.hasConfig() || packet.keyframe();
        }
        prefix.reset();
        if (out.video) {
            var frameType = frameType(codec, packet);
            if (codec == Codec.FLV1) {
                prefix.u8(frameType << 4 | FlvCodecs.VIDEO_SORENSON);
            } else if (!out.enhanced) {
                prefix.u8(frameType << 4 | FlvCodecs.VIDEO_AVC);
                prefix.u8(1);
                prefix.u24be(held.composition & 0xFFFFFF);
            } else if (codec == Codec.H264 || codec == Codec.HEVC) {
                videoPrefix(out, frameType, held.composition == 0 ? 3 : 1);
                if (held.composition != 0) {
                    prefix.u24be(held.composition & 0xFFFFFF);
                }
            } else {
                videoPrefix(out, frameType, 1);
            }
            if (out.number == 0 && frameType == 1) {
                indexKeyframe(held.timestamp);
            }
        } else if (out.enhanced) {
            audioPrefix(out, 1);
        } else {
            prefix.u8(out.legacyAudio);
            if (codec == Codec.AAC) {
                prefix.u8(1);
            }
        }
        writeTag(out.video ? 9 : 8, held.timestamp, prefix, packet.data());
        out.written = true;
        startMillis = Math.min(startMillis, held.start);
        endMillis = Math.max(endMillis, held.end);
    }

    /**
     * Returns the configuration record a key frame implies for a track that declares none.
     *
     * @param out the track
     * @param frame the key frame
     * @return the record, or {@code null} when the codec needs none or the frame implies none
     */
    private static byte[] derivedConfig(Out out, byte[] frame) {
        if (!(out.track instanceof VideoTrack video)) {
            return null;
        }
        return switch (video.codec()) {
            case AV1 -> CodecConfigs.av1Config(frame);
            case VP9 -> CodecConfigs.vp9Config(frame, video.width(), video.height(), video.color().orElse(null));
            case VP8 -> CodecConfigs.vp8Config(frame, video.width(), video.height(), video.color().orElse(null));
            default -> null;
        };
    }

    /**
     * Returns the frame type of a video packet.
     *
     * @param codec the codec of its track
     * @param packet the packet whose frame type is wanted
     * @return 1 for a key frame, 2 for an inter frame, 3 for a disposable Sorenson inter picture
     */
    private static int frameType(Codec codec, Packet packet) {
        if (codec == Codec.FLV1) {
            var data = packet.data();
            if (data.length >= 5 && data[0] == 0 && data[1] == 0 && (data[2] & 0x80) != 0) {
                var size = (int) (bits(data, 30) >>> 61);
                var bit = 33 + (size == 0 ? 16 : size == 1 ? 32 : 0);
                if (data.length * 8L >= bit + 2) {
                    var type = (int) (bits(data, bit) >>> 62);
                    return type == 0 ? 1 : type == 2 ? 3 : 2;
                }
            }
        }
        return packet.keyframe() ? 1 : 2;
    }

    /**
     * Reads 64 bits of a byte array from a bit position, zero past its end.
     *
     * @param data the bytes
     * @param bit the bit position
     * @return the bits, most significant first
     */
    private static long bits(byte[] data, int bit) {
        var at = bit >>> 3;
        var value = 0L;
        for (var index = 0; index < 8; index++) {
            value = value << 8 | byteAt(data, at + index);
        }
        var shift = bit & 7;
        return shift == 0 ? value : value << shift | byteAt(data, at + 8) >>> (8 - shift);
    }

    /**
     * Returns a byte of an array, zero past its end.
     *
     * @param data the bytes
     * @param index the index of the byte
     * @return the unsigned byte
     */
    private static int byteAt(byte[] data, int index) {
        return index < data.length ? data[index] & 0xFF : 0;
    }

    /**
     * Records a key frame of the first video track in the key frame index.
     *
     * @param timestamp its tag timestamp in milliseconds
     */
    private void indexKeyframe(long timestamp) {
        if (spool == null) {
            return;
        }
        if (keyCount == keyTimes.length) {
            keyTimes = Arrays.copyOf(keyTimes, keyCount * 2);
            keyPositions = Arrays.copyOf(keyPositions, keyCount * 2);
        }
        keyTimes[keyCount] = timestamp / 1000.0;
        keyPositions[keyCount] = mediaBytes;
        keyCount++;
    }

    /**
     * Writes the enhanced video header bytes of a track into {@link #prefix}.
     *
     * @param out the track
     * @param frameType the frame type
     * @param packetType the packet type
     */
    private void videoPrefix(Out out, int frameType, int packetType) {
        var fourCc = FlvCodecs.videoFourCc(out.track.codec());
        if (out.number == 0) {
            prefix.u8(0x80 | frameType << 4 | packetType);
            prefix.fourCc(fourCc);
        } else {
            prefix.u8(0x80 | frameType << 4 | 6);
            prefix.u8(packetType);
            prefix.fourCc(fourCc);
            prefix.u8(out.number);
        }
    }

    /**
     * Writes the enhanced audio header bytes of a track into {@link #prefix}.
     *
     * @param out the track
     * @param packetType the packet type
     */
    private void audioPrefix(Out out, int packetType) {
        var fourCc = FlvCodecs.audioFourCc(out.track.codec());
        if (out.number == 0) {
            prefix.u8(FlvCodecs.AUDIO_EX_HEADER << 4 | packetType);
            prefix.fourCc(fourCc);
        } else {
            prefix.u8(FlvCodecs.AUDIO_EX_HEADER << 4 | 5);
            prefix.u8(packetType);
            prefix.fourCc(fourCc);
            prefix.u8(out.number);
        }
    }

    /**
     * Writes the sequence start of a track with its current configuration.
     *
     * @param out the track
     * @param timestamp the timestamp in milliseconds
     */
    private void writeSequenceStart(Out out, long timestamp) {
        var codec = out.track.codec();
        prefix.reset();
        var config = out.config;
        if (out.video) {
            if (!out.enhanced) {
                prefix.u8(0x10 | FlvCodecs.VIDEO_AVC);
                prefix.u8(0);
                prefix.u24be(0);
            } else {
                videoPrefix(out, 1, 0);
                if (codec == Codec.VP9 || codec == Codec.VP8) {
                    prefix.u32be(0x01000000L);
                }
            }
        } else if (out.enhanced) {
            audioPrefix(out, 0);
        } else {
            prefix.u8(out.legacyAudio);
            prefix.u8(0);
        }
        writeTag(out.video ? 9 : 8, timestamp, prefix, config);
        out.configWritten = true;
    }

    /**
     * Writes one tag.
     *
     * @param type the tag type
     * @param timestamp the timestamp in milliseconds
     * @param header the codec-specific bytes of the body before the payload
     * @param payload the payload that follows the header bytes
     */
    private void writeTag(int type, long timestamp, ByteWriter header, byte[] payload) {
        var body = (long) header.size() + payload.length;
        if (body > MAX_BODY) {
            throw new IllegalArgumentException("a tag of " + body + " bytes exceeds the FLV limit of " + MAX_BODY);
        }
        head.reset();
        head.u8(type);
        head.u24be((int) body);
        head.u24be((int) (timestamp & 0xFFFFFF));
        head.u8((int) (timestamp >>> 24) & 0xFF);
        head.u24be(0);
        head.bytes(header.buffer(), 0, header.size());
        emit(head.buffer(), head.size(), false);
        emit(payload, payload.length, true);
        head.reset();
        head.u32be(body + TAG_HEADER);
        emit(head.buffer(), head.size(), false);
        mediaBytes += body + TAG_HEADER + 4;
    }

    /**
     * Writes bytes of a media tag to the sink, or to the spool when the key frame index is written.
     *
     * @param data the bytes
     * @param length the number of bytes from the start of {@code data}
     * @param immutable whether {@code data} is a packet payload the spool may keep by reference
     */
    private void emit(byte[] data, int length, boolean immutable) {
        if (length == 0) {
            return;
        }
        if (spool != null) {
            spool.write(immutable && length == data.length ? data : Arrays.copyOf(data, length));
        } else {
            output.write(ByteBuffer.wrap(data, 0, length));
        }
    }

    /**
     * Starts the file: writes the header and {@code onMetaData}, or opens the spool when the key frame index is
     * written.
     */
    private void start() {
        started = true;
        if (options.keyframeIndex()) {
            spool = new Spool(options.temporaryDirectory().orElse(null));
            return;
        }
        var header = header(0, 0, false, 0);
        var at = output.position();
        header.out.writeTo(output);
        durationAt = at + header.durationAt;
        fileSizeAt = at + header.fileSizeAt;
    }

    /**
     * Holds the bytes of the file header and the script tags with the offsets of the values patched at the end.
     *
     * @param out the bytes
     * @param durationAt the offset of the duration value
     * @param fileSizeAt the offset of the file size value
     */
    private record Header(ByteWriter out, int durationAt, int fileSizeAt) {
    }

    /**
     * Builds the file header, {@code onMetaData} and {@code onXMPData}.
     *
     * @param duration the duration in seconds
     * @param fileSize the file size in bytes
     * @param index whether {@code onMetaData} holds the key frame index
     * @param mediaStart the offset of the first media tag in the file, added to the indexed positions
     * @return the bytes and the offsets of the patched values
     */
    private Header header(double duration, double fileSize, boolean index, long mediaStart) {
        var out = new ByteWriter(1024);
        out.u8('F');
        out.u8('L');
        out.u8('V');
        out.u8(1);
        var audio = false;
        var video = false;
        for (var track : outs) {
            audio |= !track.video;
            video |= track.video;
        }
        out.u8((audio ? 4 : 0) | (video ? 1 : 0));
        out.u32be(9);
        out.u32be(0);
        var body = new ByteWriter(512);
        Amf0.writeString(body, "onMetaData");
        body.u8(Amf0.ECMA_ARRAY);
        var countAt = body.size();
        body.u32be(0);
        var count = 0;
        Amf0.writeName(body, "duration");
        var durationAt = body.size() + 1;
        Amf0.writeNumber(body, duration);
        count++;
        var extraVideo = new ArrayList<Out>();
        var extraAudio = new ArrayList<Out>();
        for (var track : outs) {
            if (track.number > 0) {
                (track.video ? extraVideo : extraAudio).add(track);
            } else if (track.video) {
                count += videoProperties(body, (VideoTrack) track.track, track);
            } else {
                count += audioProperties(body, (AudioTrack) track.track, track, true);
            }
        }
        if (!extraVideo.isEmpty()) {
            count++;
            Amf0.writeName(body, "videoTrackIdInfoMap");
            body.u8(Amf0.OBJECT);
            for (var track : extraVideo) {
                Amf0.writeName(body, Integer.toString(track.number));
                body.u8(Amf0.OBJECT);
                videoProperties(body, (VideoTrack) track.track, track);
                Amf0.writeObjectEnd(body);
            }
            Amf0.writeObjectEnd(body);
        }
        if (!extraAudio.isEmpty()) {
            count++;
            Amf0.writeName(body, "audioTrackIdInfoMap");
            body.u8(Amf0.OBJECT);
            for (var track : extraAudio) {
                Amf0.writeName(body, Integer.toString(track.number));
                body.u8(Amf0.OBJECT);
                audioProperties(body, (AudioTrack) track.track, track, false);
                Amf0.writeObjectEnd(body);
            }
            Amf0.writeObjectEnd(body);
        }
        Amf0.writeName(body, "encoder");
        Amf0.writeString(body, tags != null ? tags.get(StandardTag.ENCODER).orElse("Smalt") : "Smalt");
        count++;
        if (tags != null) {
            var written = new HashSet<String>();
            for (var entry : tags.entries()) {
                var key = entry.key();
                if (RESERVED.contains(key.toLowerCase(Locale.ROOT)) || !written.add(key)
                        || key.getBytes(StandardCharsets.UTF_8).length > 0xFFFF) {
                    continue;
                }
                Amf0.writeName(body, key);
                Amf0.writeString(body, entry.value());
                count++;
            }
        }
        Amf0.writeName(body, "filesize");
        var fileSizeAt = body.size() + 1;
        Amf0.writeNumber(body, fileSize);
        count++;
        if (index) {
            count++;
            Amf0.writeName(body, "keyframes");
            body.u8(Amf0.OBJECT);
            Amf0.writeName(body, "times");
            Amf0.writeNumbers(body, keyTimes, keyCount);
            Amf0.writeName(body, "filepositions");
            var positions = new double[keyCount];
            for (var key = 0; key < keyCount; key++) {
                positions[key] = mediaStart + keyPositions[key];
            }
            Amf0.writeNumbers(body, positions, keyCount);
            Amf0.writeObjectEnd(body);
        }
        Amf0.writeObjectEnd(body);
        body.patch32be(countAt, count);
        var metadataAt = out.size() + TAG_HEADER;
        scriptTag(out, body);
        var xmp = tags == null ? null : tags.xmp().filter(packet -> !packet.isEmpty()).orElse(null);
        if (xmp != null) {
            var xmpBody = new ByteWriter(1024);
            Amf0.writeString(xmpBody, "onXMPData");
            xmpBody.u8(Amf0.ECMA_ARRAY);
            xmpBody.u32be(1);
            Amf0.writeName(xmpBody, "liveXML");
            Amf0.writeString(xmpBody, xmp.toXml());
            Amf0.writeObjectEnd(xmpBody);
            scriptTag(out, xmpBody);
        }
        return new Header(out, metadataAt + durationAt, metadataAt + fileSizeAt);
    }

    /**
     * Writes the {@code onMetaData} properties of a video track.
     *
     * @param body the destination
     * @param track the video track to describe
     * @param out its state
     * @return the number of properties written
     */
    private static int videoProperties(ByteWriter body, VideoTrack track, Out out) {
        var count = 0;
        if (track.width() > 0 && track.height() > 0) {
            Amf0.writeName(body, "width");
            Amf0.writeNumber(body, track.width());
            Amf0.writeName(body, "height");
            Amf0.writeNumber(body, track.height());
            count += 2;
        }
        var rate = track.frameRate().orElse(null);
        if (rate != null) {
            Amf0.writeName(body, "framerate");
            Amf0.writeNumber(body, rate.toDouble());
            count++;
        }
        Amf0.writeName(body, "videocodecid");
        Amf0.writeNumber(body, out.enhanced ? FlvCodecs.fourCc(FlvCodecs.videoFourCc(track.codec()))
                : track.codec() == Codec.FLV1 ? FlvCodecs.VIDEO_SORENSON : FlvCodecs.VIDEO_AVC);
        return count + 1;
    }

    /**
     * Writes the {@code onMetaData} properties of an audio track.
     *
     * @param body the destination
     * @param track the audio track to describe
     * @param out its state
     * @param first whether it is the first audio track, described with the top-level property names
     * @return the number of properties written
     */
    private static int audioProperties(ByteWriter body, AudioTrack track, Out out, boolean first) {
        var count = 0;
        if (track.sampleRate() > 0) {
            Amf0.writeName(body, first ? "audiosamplerate" : "samplerate");
            Amf0.writeNumber(body, track.sampleRate());
            count++;
        }
        var channels = track.layout().map(layout -> layout.channels()).orElse(0);
        if (first) {
            Amf0.writeName(body, "audiosamplesize");
            Amf0.writeNumber(body, track.codec() == Codec.PCM_U8 ? 8 : 16);
            Amf0.writeName(body, "stereo");
            Amf0.writeBoolean(body, channels >= 2);
            count += 2;
        } else if (channels > 0) {
            Amf0.writeName(body, "channels");
            Amf0.writeNumber(body, channels);
            count++;
        }
        Amf0.writeName(body, "audiocodecid");
        Amf0.writeNumber(body, out.enhanced ? FlvCodecs.fourCc(FlvCodecs.audioFourCc(track.codec()))
                : out.legacyAudio >>> 4);
        return count + 1;
    }

    /**
     * Appends a script data tag at timestamp zero.
     *
     * @param out the destination
     * @param body the tag body
     */
    private static void scriptTag(ByteWriter out, ByteWriter body) {
        out.u8(18);
        out.u24be(body.size());
        out.u24be(0);
        out.u8(0);
        out.u24be(0);
        out.bytes(body.buffer(), 0, body.size());
        out.u32be(body.size() + TAG_HEADER);
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
        if (outs.isEmpty()) {
            throw new IllegalStateException("no track was declared");
        }
        if (!started) {
            start();
        }
        drain(true);
        for (var out : outs) {
            var codec = out.track.codec();
            if (!out.video || codec == Codec.FLV1 || !out.written) {
                continue;
            }
            prefix.reset();
            if (!out.enhanced) {
                prefix.u8(0x10 | FlvCodecs.VIDEO_AVC);
                prefix.u8(2);
                prefix.u24be(0);
            } else {
                videoPrefix(out, 1, 2);
            }
            writeTag(9, out.lastMillis, prefix, new byte[0]);
        }
        finished = true;
        var duration = endMillis > startMillis ? (endMillis - Math.min(0, startMillis)) / 1000.0 : 0;
        if (spool != null) {
            try {
                var probe = header(duration, 0, true, 0);
                var mediaStart = probe.out.size();
                var header = header(duration, mediaStart + spool.size(), true, mediaStart);
                header.out.writeTo(output);
                spool.transferTo(output);
            } finally {
                spool.close();
                spool = null;
            }
            return;
        }
        if (output.isSeekable() && durationAt >= 0) {
            var end = output.position();
            var value = new ByteWriter(8);
            value.u64be(Double.doubleToRawLongBits(duration));
            output.position(durationAt);
            value.writeTo(output);
            value.reset();
            value.u64be(Double.doubleToRawLongBits(end - base));
            output.position(fileSizeAt);
            value.writeTo(output);
            output.position(end);
        }
    }

    /**
     * Marks the muxer finished and deletes the temporary file of the key frame index.
     */
    @Override
    public void close() {
        finished = true;
        if (spool != null) {
            spool.close();
            spool = null;
        }
    }

    /**
     * Holds the state of one declared track.
     */
    private static final class Out {
        /**
         * The track.
         */
        final Track track;

        /**
         * Whether the track is video.
         */
        final boolean video;

        /**
         * The position of the track among the tracks of its kind, its multitrack track identifier.
         */
        final int number;

        /**
         * The configuration in use, or {@code null}.
         */
        byte[] config;

        /**
         * Whether the sequence start of {@link #config} was written.
         */
        boolean configWritten;

        /**
         * Whether the track gets the enhanced headers.
         */
        boolean enhanced;

        /**
         * The first byte of the legacy audio tag header, or {@code -1}.
         */
        int legacyAudio = -1;

        /**
         * The timestamp of the last packet in milliseconds.
         */
        long lastMillis;

        /**
         * The number of packets of the track held.
         */
        int queued;

        /**
         * Whether a frame of the track was written.
         */
        boolean written;

        /**
         * Constructs the state of a track.
         *
         * @param track the declared track
         * @param video whether it is video
         * @param number its position among the tracks of its kind
         * @param config its configuration, or {@code null}
         */
        Out(Track track, boolean video, int number, byte[] config) {
            this.track = track;
            this.video = video;
            this.number = number;
            this.config = config;
        }

        /**
         * {@return whether the codec of the track has a configuration that a sequence start carries}
         */
        boolean hasConfig() {
            return switch (track.codec()) {
                case H264, HEVC, AV1, VP9, VP8, AAC, OPUS, FLAC -> true;
                default -> false;
            };
        }
    }

    /**
     * Holds a packet waiting for the interleaving.
     *
     * @param out its track
     * @param packet the packet as written by the caller
     * @param timestamp its tag timestamp in milliseconds
     * @param composition its composition time offset in milliseconds
     * @param start its presentation time in milliseconds
     * @param end its presentation end in milliseconds
     * @param sequence the order it was received in
     */
    private record Held(Out out, Packet packet, long timestamp, int composition, long start, long end, long sequence)
            implements Comparable<Held> {
        @Override
        public int compareTo(Held other) {
            var order = Long.compare(timestamp, other.timestamp);
            return order != 0 ? order : Long.compare(sequence, other.sequence);
        }
    }

    /**
     * Holds the media tags until the key frame index is written: in memory up to 16 MiB, then in a temporary file
     * deleted when the spool is closed.
     */
    private static final class Spool {
        /**
         * The most bytes held in memory.
         */
        private static final long MEMORY_LIMIT = 16 << 20;

        /**
         * The directory of the temporary file, or {@code null} for the default.
         */
        private final Path directory;

        /**
         * The arrays held in memory, or the arrays not yet written to the file.
         */
        private final List<byte[]> chunks = new ArrayList<>();

        /**
         * The temporary file, or {@code null} while the bytes are in memory.
         */
        private FileChannel file;

        /**
         * The path of the temporary file, or {@code null}.
         */
        private Path path;

        /**
         * The number of bytes held.
         */
        private long size;

        /**
         * Constructs an empty spool.
         *
         * @param directory the directory of the temporary file, or {@code null} for the default
         */
        Spool(Path directory) {
            this.directory = directory;
        }

        /**
         * {@return the number of bytes held}
         */
        long size() {
            return size;
        }

        /**
         * Appends bytes, keeping the array by reference while the spool is in memory.
         *
         * @param data the bytes, not modified afterwards
         * @throws MediaException.Io if the temporary file cannot be written
         */
        void write(byte[] data) {
            try {
                if (file == null && size + data.length > MEMORY_LIMIT) {
                    path = directory == null ? Files.createTempFile("smalt-flv", ".tmp")
                            : Files.createTempFile(directory, "smalt-flv", ".tmp");
                    file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                            StandardOpenOption.DELETE_ON_CLOSE);
                    for (var chunk : chunks) {
                        writeFully(chunk);
                    }
                    chunks.clear();
                }
                if (file == null) {
                    chunks.add(data);
                } else {
                    writeFully(data);
                }
                size += data.length;
            } catch (IOException exception) {
                throw new MediaException.Io("cannot write the FLV spool", exception);
            }
        }

        /**
         * Writes an array to the temporary file.
         *
         * @param data the bytes
         * @throws IOException if writing fails
         */
        private void writeFully(byte[] data) throws IOException {
            var buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                file.write(buffer);
            }
        }

        /**
         * Copies every byte held to a sink at its position.
         *
         * @param sink the destination
         * @throws MediaException.Io if reading the spool or writing the sink fails
         */
        void transferTo(MediaSink sink) {
            if (file == null) {
                for (var chunk : chunks) {
                    sink.write(ByteBuffer.wrap(chunk));
                }
                return;
            }
            try {
                var buffer = ByteBuffer.allocate(256 * 1024);
                var position = 0L;
                while (position < size) {
                    buffer.clear();
                    var read = file.read(buffer, position);
                    if (read <= 0) {
                        throw new IOException("the FLV spool ended early");
                    }
                    position += read;
                    buffer.flip();
                    sink.write(buffer);
                }
            } catch (IOException exception) {
                throw new MediaException.Io("cannot read the FLV spool", exception);
            }
        }

        /**
         * Drops the bytes held and deletes the temporary file.
         */
        void close() {
            chunks.clear();
            if (file != null) {
                try {
                    file.close();
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    // the file is deleted on close where the platform supports it
                }
                file = null;
            }
        }
    }
}
