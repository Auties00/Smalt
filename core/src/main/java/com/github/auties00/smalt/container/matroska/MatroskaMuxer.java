package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.internal.tags.Ebml;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.internal.tags.MuxerTags;
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Writes Matroska and WebM files (RFC 9559, and the WebM profile of it) from the packets of video,
 * audio and text subtitle tracks.
 *
 * <p>Supported codecs, with their codec identifier and {@code CodecPrivate}:
 * <ul>
 *   <li>WebM and Matroska: VP8 ({@code V_VP8}, none), VP9 ({@code V_VP9}, the profile, level, bit
 *       depth and chroma subsampling features, from the track's {@code vpcC} or the first key
 *       frame), AV1 ({@code V_AV1}, the {@code av1C} record, synthesized from the first key
 *       frame's sequence header when the track has none), Opus ({@code A_OPUS}, the
 *       {@code OpusHead}, synthesized for at most two channels when absent) and Vorbis
 *       ({@code A_VORBIS}, the Xiph-laced headers);</li>
 *   <li>Matroska only: H.264 ({@code V_MPEG4/ISO/AVC}, {@code avcC}), HEVC
 *       ({@code V_MPEGH/ISO/HEVC}, {@code hvcC}), ProRes ({@code V_PRORES}, the four-character
 *       sample entry type of the track's configuration), DNxHD and DNxHR ({@code V_QUICKTIME}, a
 *       QuickTime video sample description of the four-character sample entry type of the
 *       track's configuration, {@code AVdn} or {@code AVdh}), AAC ({@code A_AAC}, the
 *       {@code AudioSpecificConfig}), FLAC ({@code A_FLAC}, the {@code fLaC} marker and metadata
 *       blocks), MP3 and MP2 ({@code A_MPEG/L3}, {@code A_MPEG/L2}), AC-3 and E-AC-3, and PCM
 *       ({@code A_PCM/INT/LIT} for unsigned 8-bit and little-endian integers,
 *       {@code A_PCM/INT/BIG} for big-endian integers, {@code A_PCM/FLOAT/IEEE} for little-endian
 *       floats).</li>
 *   <li>Text subtitles: WebVTT ({@code D_WEBVTT/SUBTITLES}, with the WebVTT header as
 *       {@code CodecPrivate} in Matroska when it holds more than the signature, none in WebM),
 *       and in Matroska only SubRip ({@code S_TEXT/UTF8}, none) and ASS ({@code S_TEXT/ASS}, the
 *       script header). Subtitle packets become {@code BlockGroup}s with a {@code BlockDuration};
 *       a WebVTT block holds the cue identifier line, the settings line and the payload. Subtitle
 *       tracks get no cue points.</li>
 * </ul>
 * Each track becomes a {@code TrackEntry} numbered from one in declaration order, with its
 * language ({@code und} when undeclared), the flags of {@link #addTrack(Track, Set)}, the
 * {@code DefaultDuration} of a video track with a frame rate, the {@code Color} element of a
 * video track with a color description, and the sampling frequency, channel count and (for PCM)
 * bit depth of an audio track. An audio track's {@link AudioTrack#skipSamples()} becomes its
 * {@code CodecDelay}; Opus stores its pre-skip there instead, with an 80 ms {@code SeekPreRoll}.
 *
 * <p>Packets of every track are interleaved by decoding time: a packet is written once every
 * track has a later one queued. An audio or video track without a queued packet holds the others
 * back until its next packet arrives, so an encoder that holds frames back (a lookahead) still gets
 * its blocks written in decoding order; only {@linkplain MatroskaMuxerOptions#live() live} output
 * writes a packet more than a second older than the newest one without waiting for such a track. A
 * subtitle track, whose packets are sparse, holds the others back for at most a second. Over
 * 64 MiB of queued packets the oldest is written whatever the other tracks hold.
 * Blocks store presentation timestamps in segment ticks; an Opus block is stamped with its
 * presentation timestamp plus the pre-skip, as the {@code CodecDelay} semantics require, and an
 * Opus packet whose {@link Packet#duration()} is shorter than its coded duration gets a
 * {@code BlockGroup} with the difference as {@code DiscardPadding}. When the first packets
 * reaching the output have negative timestamps, every timestamp is shifted so the earliest is
 * zero. Every other packet becomes a {@code SimpleBlock}, flagged as a key frame when it is one
 * (audio packets always are), without lacing.
 *
 * <p>Output modes, following {@link MatroskaMuxerOptions}:
 * <ul>
 *   <li>on a seekable sink, each cluster is written with its size once complete, and
 *       {@link #finish()} appends the {@code Cues} (one cue point per video key frame, or per
 *       cluster for audio-only files) and patches the segment size, the {@code Duration} and the
 *       {@code SeekHead}, which then points at {@code Info}, {@code Tracks} and {@code Cues};</li>
 *   <li>on a streaming sink, the segment has an unknown size and no {@code Duration},
 *       {@code Cues} are not written, and clusters are still written with their size;</li>
 *   <li>{@linkplain MatroskaMuxerOptions#live() live} output also gives every cluster an unknown
 *       size and writes each block as soon as it is interleaved, whatever the sink.</li>
 * </ul>
 * The sink is written from its position at construction and is never closed by the muxer. Instances
 * are not thread-safe.
 *
 * @implNote This implementation numbers tracks and derives their {@code TrackUID}s from their declaration order and
 * writes no {@code DateUTC}, so equal input gives byte-identical output. WebVTT tracks use the WebM codec identifier
 * {@code D_WEBVTT/SUBTITLES} in Matroska files too, not {@code S_TEXT/WEBVTT}: common players read only the former,
 * and the demuxer reads both. In files with video a cluster ends before a video key frame only once it lasts at
 * least {@value #MIN_KEYFRAME_CLUSTER_MICROS} microseconds, so all-intra video does not get a cluster per frame.
 */
public final class MatroskaMuxer implements Muxer {
    /**
     * Enumerates the {@code TrackEntry} flags a track can carry.
     *
     * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
     * {@code default} branch.
     */
    public enum TrackFlag {
        /**
         * {@code FlagDefault}: players select the track when the user expressed no preference.
         */
        DEFAULT,

        /**
         * {@code FlagForced}: players select the track whatever the user preference, for example
         * subtitles of foreign dialogue.
         */
        FORCED,

        /**
         * {@code FlagHearingImpaired}: the track suits hearing-impaired users.
         */
        HEARING_IMPAIRED,

        /**
         * {@code FlagVisualImpaired}: the track suits visually impaired users.
         */
        VISUAL_IMPAIRED,

        /**
         * {@code FlagOriginal}: the track is in the content's original language.
         */
        ORIGINAL,

        /**
         * {@code FlagCommentary}: the track holds commentary.
         */
        COMMENTARY
    }

    /**
     * The shortest cluster ended before a video key frame, in microseconds.
     */
    private static final long MIN_KEYFRAME_CLUSTER_MICROS = 250_000;

    /**
     * The largest decoding-time gap between the oldest queued packet and the newest one before
     * the oldest is written without waiting for an empty subtitle track, or, in live output, for
     * any empty track, in microseconds.
     */
    private static final long MAX_INTERLEAVE_MICROS = 1_000_000;

    /**
     * The largest number of queued packet bytes before the oldest packet is written without
     * waiting for the other tracks.
     */
    private static final long MAX_QUEUED_BYTES = 64L << 20;

    /**
     * The room reserved after the {@code SeekHead} for the {@code Cues} entry added at the end.
     */
    private static final int SEEK_HEAD_RESERVE = 64;

    /**
     * The {@code SeekPreRoll} of Opus tracks, 80 ms in nanoseconds (RFC 7845 section 4.6).
     */
    private static final long OPUS_SEEK_PRE_ROLL = 80_000_000;

    /**
     * The Opus time base, one 48 kHz sample.
     */
    private static final Rational OPUS_RATE = Rational.timeBaseOf(48_000);

    /**
     * The nanosecond time base.
     */
    private static final Rational NANOSECONDS = new Rational(1, 1_000_000_000);

    /**
     * The {@code CuePoint} element identifier.
     */
    private static final int CUE_POINT = 0xBB;

    /**
     * The {@code CueTime} element identifier.
     */
    private static final int CUE_TIME = 0xB3;

    /**
     * The {@code CueTrackPositions} element identifier.
     */
    private static final int CUE_TRACK_POSITIONS = 0xB7;

    /**
     * The {@code CueTrack} element identifier.
     */
    private static final int CUE_TRACK = 0xF7;

    /**
     * The {@code CueClusterPosition} element identifier.
     */
    private static final int CUE_CLUSTER_POSITION = 0xF1;

    /**
     * The {@code CueRelativePosition} element identifier.
     */
    private static final int CUE_RELATIVE_POSITION = 0xF0;

    /**
     * The {@code FlagForced} element identifier.
     */
    private static final int FLAG_FORCED = 0x55AA;

    /**
     * The {@code FlagHearingImpaired} element identifier.
     */
    private static final int FLAG_HEARING_IMPAIRED = 0x55AB;

    /**
     * The {@code FlagVisualImpaired} element identifier.
     */
    private static final int FLAG_VISUAL_IMPAIRED = 0x55AC;

    /**
     * The {@code FlagOriginal} element identifier.
     */
    private static final int FLAG_ORIGINAL = 0x55AE;

    /**
     * The {@code FlagCommentary} element identifier.
     */
    private static final int FLAG_COMMENTARY = 0x55AF;

    /**
     * The {@code TrackType} of subtitle tracks.
     */
    private static final int TRACK_TYPE_SUBTITLE = 17;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The configuration.
     */
    private final MatroskaMuxerOptions options;

    /**
     * The segment tick as a time base.
     */
    private final Rational segmentBase;

    /**
     * Whether the muxer patches sizes and writes {@code Cues}: a seekable sink, not live.
     */
    private final boolean indexed;

    /**
     * The declared tracks, in declaration order.
     */
    private final List<TrackState> tracks;

    /**
     * The cue points written by {@link #finish()}: time, track number, cluster position and
     * relative position, four longs per point.
     */
    private long[] cues = new long[64];

    /**
     * The number of longs used in {@link #cues}.
     */
    private int cueLength;

    /**
     * The body of the cluster being assembled, when the output is not live.
     */
    private final EbmlOutput cluster;

    /**
     * Whether a cluster is open.
     */
    private boolean clusterOpen;

    /**
     * The timestamp of the open cluster in segment ticks.
     */
    private long clusterTicks;

    /**
     * The position of the open cluster relative to the segment data.
     */
    private long clusterPosition;

    /**
     * The number of bytes in the open cluster.
     */
    private long clusterBytes;

    /**
     * The size of the open cluster's {@code Timestamp} element, which precedes its blocks in the
     * cluster data that {@code CueRelativePosition} counts from.
     */
    private int clusterPrefix;

    /**
     * Whether any declared track is video.
     */
    private boolean hasVideo;

    /**
     * The absolute offset of the segment data.
     */
    private long segmentStart;

    /**
     * The absolute offset of the segment size field.
     */
    private long segmentSizeOffset;

    /**
     * The absolute offset of the {@code SeekHead} and the room reserved after it.
     */
    private long seekHeadOffset;

    /**
     * The bytes of the {@code SeekHead} area: the element and the reserved {@code Void}.
     */
    private int seekHeadArea;

    /**
     * The positions of {@code Info} and {@code Tracks} relative to the segment data.
     */
    private long infoPosition;

    /**
     * The position of {@code Tracks} relative to the segment data.
     */
    private long tracksPosition;

    /**
     * The absolute offset of the {@code Duration} value, or {@code -1}.
     */
    private long durationOffset = -1;

    /**
     * The shift added to every block timestamp, in segment ticks.
     */
    private long shiftTicks;

    /**
     * The end of the latest block, in segment ticks.
     */
    private long endTicks;

    /**
     * The decoding time of the newest queued packet, in microseconds.
     */
    private long newestMicros = Long.MIN_VALUE;

    /**
     * The number of queued packet bytes.
     */
    private long queuedBytes;

    /**
     * The {@code Chapters}, {@code Attachments} and {@code Tags} elements the tags were encoded to,
     * written after {@code Tracks}; each empty when absent.
     */
    private byte[][] metadata = new byte[][]{new byte[0], new byte[0], new byte[0]};

    /**
     * The segment title the tags set, overriding the options, or {@code null}.
     */
    private String title;

    /**
     * The positions of the {@code Chapters}, {@code Attachments} and {@code Tags} elements relative
     * to the segment data, {@code -1} for each absent one.
     */
    private long[] metadataPositions = new long[]{-1, -1, -1};

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether a packet was accepted.
     */
    private boolean accepting;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing a Matroska file to a sink with
     * {@link MatroskaMuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position; not closed by this muxer
     */
    public MatroskaMuxer(MediaSink output) {
        this(output, MatroskaMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the configuration
     */
    public MatroskaMuxer(MediaSink output, MatroskaMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        this.segmentBase = new Rational(options.timestampScaleNanos(), 1_000_000_000L);
        this.indexed = output.isSeekable() && !options.live();
        this.tracks = new ArrayList<>();
        this.cluster = new EbmlOutput(64 * 1024);
    }

    /**
     * Returns whether a document type can carry a codec.
     *
     * @param docType the document type
     * @param codec the codec of the track to add
     * @return {@code true} when {@link #addTrack(Track)} accepts tracks of the codec
     */
    public static boolean supports(MatroskaDocType docType, Codec codec) {
        Objects.requireNonNull(docType, "docType");
        var id = codecId(Objects.requireNonNull(codec, "codec"));
        if (id == null) {
            return false;
        }
        return docType != MatroskaDocType.WEBM
                || codec == Codec.VP8 || codec == Codec.VP9 || codec == Codec.AV1 || codec == Codec.OPUS || codec == Codec.VORBIS
                || codec == Codec.WEBVTT;
    }

    /**
     * Returns the Matroska codec identifier of a codec.
     *
     * @param codec the codec to identify
     * @return the identifier, or {@code null} when the muxer does not support the codec
     */
    private static String codecId(Codec codec) {
        return switch (codec) {
            case VP8 -> "V_VP8";
            case VP9 -> "V_VP9";
            case AV1 -> "V_AV1";
            case H264 -> "V_MPEG4/ISO/AVC";
            case HEVC -> "V_MPEGH/ISO/HEVC";
            case PRORES -> "V_PRORES";
            case DNXHD -> "V_QUICKTIME";
            case OPUS -> "A_OPUS";
            case VORBIS -> "A_VORBIS";
            case AAC -> "A_AAC";
            case FLAC -> "A_FLAC";
            case MP3 -> "A_MPEG/L3";
            case MP2 -> "A_MPEG/L2";
            case AC3 -> "A_AC3";
            case EAC3 -> "A_EAC3";
            case PCM_U8, PCM_S16LE, PCM_S24LE, PCM_S32LE -> "A_PCM/INT/LIT";
            case PCM_S16BE, PCM_S24BE, PCM_S32BE -> "A_PCM/INT/BIG";
            case PCM_F32LE, PCM_F64LE -> "A_PCM/FLOAT/IEEE";
            case SUBRIP -> "S_TEXT/UTF8";
            case ASS -> "S_TEXT/ASS";
            case WEBVTT -> "D_WEBVTT/SUBTITLES";
            default -> null;
        };
    }

    /**
     * Declares a track, flagged {@link TrackFlag#DEFAULT} when it is the first of its kind.
     *
     * @param track the track description
     * @throws IllegalArgumentException if the id is already used, the document type cannot carry the codec, or the
     * track lacks what its codec needs (a configuration, a frame size, a sample rate or a layout)
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        var first = true;
        for (var existing : tracks) {
            first &= existing.track.kind() != track.kind();
        }
        addTrack(track, first ? EnumSet.of(TrackFlag.DEFAULT) : EnumSet.noneOf(TrackFlag.class));
    }

    /**
     * Declares a track with explicit flags.
     *
     * @param track the track description
     * @param flags the flags of its {@code TrackEntry}; a flag not in the set is written as cleared
     * @throws IllegalArgumentException if the id is already used, the document type cannot carry the codec, or the
     * track lacks what its codec needs (a configuration, a frame size, a sample rate or a layout)
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    public void addTrack(Track track, Set<TrackFlag> flags) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(flags, "flags");
        if (finished || accepting) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        for (var existing : tracks) {
            if (existing.track.id() == track.id()) {
                throw new IllegalArgumentException("track id " + track.id() + " is already used");
            }
        }
        var codec = track.codec();
        if (!supports(options.docType(), codec)) {
            throw new IllegalArgumentException(options.docType().name().toLowerCase() + " cannot carry " + codec.displayName());
        }
        var state = new TrackState(track, tracks.size() + 1, EnumSet.copyOf(flags.isEmpty() ? EnumSet.noneOf(TrackFlag.class) : flags));
        state.configure();
        hasVideo |= state.video;
        tracks.add(state);
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
     * <p>The title becomes the segment title of {@code Info}, replacing the configured one; the
     * other tags become a {@code Tags} element, the chapters a {@code Chapters} element and the
     * pictures {@code Attachments} (the first front cover named {@code cover.*}), all written
     * after {@code Tracks} and indexed by the {@code SeekHead}.
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.title = tags.get(StandardTag.TITLE).orElse(null);
        var encoded = MuxerTags.matroska(tags.toBuilder().remove(StandardTag.TITLE).build());
        this.metadata = new byte[][]{encoded[1], encoded[2], encoded[0]};
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track was not declared or a timestamp cannot be represented in
     * microseconds
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        var state = find(packet.trackId());
        if (tracks.isEmpty() || state == null) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        accepting = true;
        var data = packet.data();
        if (state.track.codec() == Codec.PRORES && data.length >= 8 && data[4] == 'i' && data[5] == 'c' && data[6] == 'p'
                && data[7] == 'f') {
            // Matroska blocks of V_PRORES omit the frame size and icpf identifier of the frame
            packet = packet.withData(java.util.Arrays.copyOfRange(data, 8, data.length));
        }
        var micros = state.orderMicros(packet);
        state.queue.add(new Queued(packet, micros));
        queuedBytes += packet.size();
        newestMicros = Math.max(newestMicros, micros);
        drain(false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes the header and the track list.
     *
     * @throws IllegalStateException if the muxer is already finished
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        drain(true);
        if (!started) {
            writeHeader();
        }
        closeCluster();
        finished = true;
        if (!indexed) {
            return;
        }
        long cuesPosition = -1;
        if (options.cues() && cueLength > 0) {
            cuesPosition = output.position() - segmentStart;
            var element = new EbmlOutput(cueLength * 8 + 64);
            element.begin(EbmlIds.CUES);
            for (var index = 0; index < cueLength; index += 4) {
                element.begin(CUE_POINT);
                element.unsigned(CUE_TIME, cues[index]);
                element.begin(CUE_TRACK_POSITIONS);
                element.unsigned(CUE_TRACK, cues[index + 1]);
                element.unsigned(CUE_CLUSTER_POSITION, cues[index + 2]);
                element.unsigned(CUE_RELATIVE_POSITION, cues[index + 3]);
                element.end();
                element.end();
            }
            element.end();
            element.writeTo(output);
        }
        var end = output.position();
        var seekHead = new EbmlOutput(seekHeadArea);
        writeSeekHead(seekHead, cuesPosition);
        seekHead.voidElement(seekHeadArea - seekHead.size());
        output.position(seekHeadOffset);
        seekHead.writeTo(output);
        var duration = new EbmlOutput(8);
        duration.writeSize(end - segmentStart, 8);
        output.position(segmentSizeOffset);
        duration.writeTo(output);
        if (durationOffset >= 0) {
            var bits = Double.doubleToLongBits((double) endTicks);
            var value = new byte[8];
            for (var index = 0; index < 8; index++) {
                value[index] = (byte) (bits >>> (56 - 8 * index));
            }
            output.position(durationOffset);
            output.write(ByteBuffer.wrap(value));
        }
        output.position(end);
    }

    /**
     * Marks the muxer finished without writing what is still queued; the output is not closed.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Returns the state of a declared track.
     *
     * @param trackId the track identifier
     * @return the state, or {@code null} when the track was not declared
     */
    private TrackState find(int trackId) {
        for (var state : tracks) {
            if (state.track.id() == trackId) {
                return state;
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
            TrackState oldest = null;
            var waiting = false;
            var sparse = false;
            for (var state : tracks) {
                var head = state.queue.peekFirst();
                if (head == null) {
                    if (state.subtitle) {
                        sparse = true;
                    } else {
                        waiting = true;
                    }
                } else if (oldest == null || head.micros < oldest.queue.peekFirst().micros) {
                    oldest = state;
                }
            }
            if (oldest == null) {
                return;
            }
            var head = oldest.queue.peekFirst();
            if (!all && queuedBytes <= MAX_QUEUED_BYTES) {
                if (waiting && !options.live()) {
                    return;
                }
                if ((waiting || sparse) && newestMicros - head.micros <= MAX_INTERLEAVE_MICROS) {
                    return;
                }
            }
            if (!started) {
                writeHeader();
            }
            oldest.queue.removeFirst();
            queuedBytes -= head.packet.size();
            writeBlock(oldest, head.packet);
        }
    }

    /**
     * Writes the EBML header and the segment up to the first cluster.
     */
    private void writeHeader() {
        started = true;
        var minimum = Long.MAX_VALUE;
        for (var state : tracks) {
            var head = state.queue.peekFirst();
            if (head != null) {
                state.resolveConfig(head.packet);
                var ticks = state.rawTicks(head.packet);
                if (ticks != Long.MIN_VALUE) {
                    minimum = Math.min(minimum, ticks);
                }
            }
        }
        shiftTicks = minimum != Long.MAX_VALUE && minimum < 0 ? -minimum : 0;
        var header = new EbmlOutput(256);
        header.begin(EbmlIds.EBML);
        header.unsigned(EbmlIds.EBML_VERSION, 1);
        header.unsigned(EbmlIds.EBML_READ_VERSION, 1);
        header.unsigned(EbmlIds.EBML_MAX_ID_LENGTH, 4);
        header.unsigned(EbmlIds.EBML_MAX_SIZE_LENGTH, 8);
        header.string(EbmlIds.DOC_TYPE, options.docType() == MatroskaDocType.WEBM ? "webm" : "matroska");
        header.unsigned(EbmlIds.DOC_TYPE_VERSION, 4);
        header.unsigned(EbmlIds.DOC_TYPE_READ_VERSION, 2);
        header.end();
        header.id(EbmlIds.SEGMENT);
        var base = output.position();
        segmentSizeOffset = base + header.size();
        header.writeSize(indexed ? 0 : EbmlOutput.UNKNOWN_SIZE & 0x00FFFFFFFFFFFFFFL, 8);
        segmentStart = base + header.size();
        var info = buildInfo();
        var trackList = buildTracks();
        var seekHead = new EbmlOutput(128);
        var size = -1;
        while (size != seekHead.size()) {
            size = seekHead.size();
            infoPosition = size + (indexed ? SEEK_HEAD_RESERVE : 0);
            tracksPosition = infoPosition + info.output.size();
            var cursor = tracksPosition + trackList.size();
            for (var index = 0; index < metadata.length; index++) {
                metadataPositions[index] = metadata[index].length > 0 ? cursor : -1;
                cursor += metadata[index].length;
            }
            seekHead.reset();
            writeSeekHead(seekHead, -1);
        }
        seekHeadOffset = segmentStart;
        if (indexed) {
            seekHeadArea = seekHead.size() + SEEK_HEAD_RESERVE;
            seekHead.voidElement(SEEK_HEAD_RESERVE);
            durationOffset = segmentStart + infoPosition + info.durationOffset;
        }
        header.raw(seekHead.buffer(), 0, seekHead.size());
        header.raw(info.output.buffer(), 0, info.output.size());
        header.raw(trackList.buffer(), 0, trackList.size());
        for (var element : metadata) {
            header.raw(element, 0, element.length);
        }
        header.writeTo(output);
    }

    /**
     * Writes a {@code SeekHead} pointing at {@code Info}, {@code Tracks} and, when present,
     * {@code Cues}.
     *
     * @param out the destination
     * @param cuesPosition the position of {@code Cues} relative to the segment data, or {@code -1}
     */
    private void writeSeekHead(EbmlOutput out, long cuesPosition) {
        out.begin(EbmlIds.SEEK_HEAD);
        writeSeek(out, EbmlIds.INFO, infoPosition);
        writeSeek(out, EbmlIds.TRACKS, tracksPosition);
        var metadataIds = new int[]{EbmlIds.CHAPTERS, EbmlIds.ATTACHMENTS, EbmlIds.TAGS};
        for (var index = 0; index < metadataIds.length; index++) {
            if (metadataPositions[index] >= 0) {
                writeSeek(out, metadataIds[index], metadataPositions[index]);
            }
        }
        if (cuesPosition >= 0) {
            writeSeek(out, EbmlIds.CUES, cuesPosition);
        }
        out.end();
    }

    /**
     * Writes one {@code Seek} entry.
     *
     * @param out the destination
     * @param id the identifier of the element pointed at
     * @param position its position relative to the segment data
     */
    private static void writeSeek(EbmlOutput out, int id, long position) {
        out.begin(EbmlIds.SEEK);
        var length = Ebml.idLength(id);
        var bytes = new byte[length];
        for (var index = 0; index < length; index++) {
            bytes[index] = (byte) (id >>> (8 * (length - 1 - index)));
        }
        out.binary(EbmlIds.SEEK_ID, bytes);
        out.unsigned(EbmlIds.SEEK_POSITION, position);
        out.end();
    }

    /**
     * Holds a serialized {@code Info} element and the offset of its {@code Duration} value.
     *
     * @param output the element
     * @param durationOffset the offset of the 8-byte {@code Duration} value within the element, or {@code -1}
     */
    private record InfoElement(EbmlOutput output, int durationOffset) {
    }

    /**
     * Serializes the {@code Info} element.
     *
     * @return the element
     */
    private InfoElement buildInfo() {
        var body = new EbmlOutput(128);
        body.unsigned(EbmlIds.TIMESTAMP_SCALE, options.timestampScaleNanos());
        body.string(EbmlIds.MUXING_APP, "Smalt");
        body.string(EbmlIds.WRITING_APP, options.writingApp());
        var segmentTitle = title != null ? title : options.title().orElse(null);
        if (segmentTitle != null) {
            body.string(EbmlIds.TITLE, segmentTitle);
        }
        var duration = indexed ? body.floating(EbmlIds.DURATION, 0) : -1;
        var element = new EbmlOutput(body.size() + 16);
        element.id(EbmlIds.INFO);
        element.size(body.size());
        var prefix = element.size();
        element.raw(body.buffer(), 0, body.size());
        return new InfoElement(element, duration < 0 ? -1 : prefix + duration);
    }

    /**
     * Serializes the {@code Tracks} element.
     *
     * @return the element
     */
    private EbmlOutput buildTracks() {
        var out = new EbmlOutput(1024);
        out.begin(EbmlIds.TRACKS);
        for (var state : tracks) {
            state.writeEntry(out);
        }
        out.end();
        return out;
    }

    /**
     * Writes one packet as a block, opening a new cluster when needed.
     *
     * @param state the packet's track
     * @param packet the packet to write, on the track's time base
     */
    private void writeBlock(TrackState state, Packet packet) {
        var raw = state.rawTicks(packet);
        var ticks = raw == Long.MIN_VALUE ? state.lastTicks : Math.max(0, saturatingAdd(raw, shiftTicks));
        state.lastTicks = ticks;
        var key = !state.video || packet.keyframe();
        if (!clusterOpen || shouldSplit(state, ticks, key)) {
            openCluster(ticks);
        }
        if (state.subtitle) {
            writeTextBlock(state, packet, ticks);
            return;
        }
        var relative = ticks - clusterTicks;
        var padding = state.discardPadding(packet);
        var trackNumberLength = Ebml.sizeLength(state.number);
        var payload = trackNumberLength + 3L + packet.size();
        var header = new EbmlOutput(32);
        if (padding > 0) {
            var block = EbmlOutput.elementLength(EbmlIds.BLOCK, payload);
            var discard = new EbmlOutput(16);
            discard.signed(EbmlIds.DISCARD_PADDING, padding);
            header.id(EbmlIds.BLOCK_GROUP);
            header.size(block + discard.size());
            header.id(EbmlIds.BLOCK);
        } else {
            header.id(EbmlIds.SIMPLE_BLOCK);
        }
        header.size(payload);
        header.size(state.number);
        header.rawByte((int) (relative >> 8));
        header.rawByte((int) relative);
        header.rawByte(padding > 0 ? 0 : key ? 0x80 : 0);
        var position = clusterPrefix + clusterBytes;
        if (options.live()) {
            header.writeTo(output);
            output.write(ByteBuffer.wrap(packet.data()));
        } else {
            cluster.raw(header.buffer(), 0, header.size());
            cluster.raw(packet.data(), 0, packet.size());
        }
        clusterBytes += header.size() + packet.size();
        if (padding > 0) {
            var discard = new EbmlOutput(16);
            discard.signed(EbmlIds.DISCARD_PADDING, padding);
            if (options.live()) {
                discard.writeTo(output);
            } else {
                cluster.raw(discard.buffer(), 0, discard.size());
            }
            clusterBytes += discard.size();
        }
        if (key && (state.video || !hasVideo && !state.cuedInCluster)) {
            state.cuedInCluster = true;
            addCue(ticks, state.number, clusterPosition, position);
        }
        var duration = state.durationTicks(packet);
        endTicks = Math.max(endTicks, ticks + duration);
    }

    /**
     * Writes one subtitle packet as a {@code BlockGroup} with its {@code BlockDuration}; a WebVTT
     * block holds the cue identifier line, the settings line and the payload.
     *
     * @param state the packet's track
     * @param packet the subtitle packet to write
     * @param ticks the block timestamp in segment ticks
     */
    private void writeTextBlock(TrackState state, Packet packet, long ticks) {
        var relative = ticks - clusterTicks;
        var data = state.track.codec() == Codec.WEBVTT ? WebVttSamples.webmBlock(packet) : packet.data();
        var duration = state.durationTicks(packet);
        var group = new EbmlOutput(data.length + 64);
        group.begin(EbmlIds.BLOCK_GROUP);
        group.id(EbmlIds.BLOCK);
        group.size(Ebml.sizeLength(state.number) + 3L + data.length);
        group.size(state.number);
        group.rawByte((int) (relative >> 8));
        group.rawByte((int) relative);
        group.rawByte(0);
        group.raw(data, 0, data.length);
        if (duration > 0) {
            group.unsigned(EbmlIds.BLOCK_DURATION, duration);
        }
        group.end();
        if (options.live()) {
            group.writeTo(output);
        } else {
            cluster.raw(group.buffer(), 0, group.size());
        }
        clusterBytes += group.size();
        endTicks = Math.max(endTicks, ticks + duration);
    }

    /**
     * Returns whether a block must start a new cluster.
     *
     * @param state the block's track
     * @param ticks the block timestamp in segment ticks
     * @param key whether the block is a key frame
     * @return {@code true} when the block's offset does not fit 16 bits, the cluster reached a limit, or the block is a
     * video key frame of a long enough cluster
     */
    private boolean shouldSplit(TrackState state, long ticks, boolean key) {
        var relative = ticks - clusterTicks;
        if (relative < Short.MIN_VALUE || relative > Short.MAX_VALUE || clusterBytes >= options.maxClusterBytes()) {
            return true;
        }
        var micros = segmentBase.toMicros(relative);
        if (micros >= options.maxClusterMicros()) {
            return true;
        }
        return state.video && key && micros >= MIN_KEYFRAME_CLUSTER_MICROS;
    }

    /**
     * Writes the open cluster and starts a new one.
     *
     * @param ticks the cluster timestamp in segment ticks
     */
    private void openCluster(long ticks) {
        closeCluster();
        clusterOpen = true;
        clusterTicks = ticks;
        clusterBytes = 0;
        clusterPosition = output.position() - segmentStart;
        for (var state : tracks) {
            state.cuedInCluster = false;
        }
        var timestamp = new EbmlOutput(16);
        timestamp.unsigned(EbmlIds.TIMESTAMP, ticks);
        if (options.live()) {
            var header = new EbmlOutput(32);
            header.id(EbmlIds.CLUSTER);
            header.writeSize(EbmlOutput.UNKNOWN_SIZE & 0x00FFFFFFFFFFFFFFL, 8);
            header.raw(timestamp.buffer(), 0, timestamp.size());
            header.writeTo(output);
            clusterPrefix = timestamp.size();
        } else {
            cluster.reset();
            cluster.raw(timestamp.buffer(), 0, timestamp.size());
            clusterPrefix = timestamp.size();
        }
    }

    /**
     * Writes the open cluster with its size, when the output is not live.
     */
    private void closeCluster() {
        if (!clusterOpen) {
            return;
        }
        clusterOpen = false;
        if (options.live()) {
            return;
        }
        var header = new EbmlOutput(16);
        header.id(EbmlIds.CLUSTER);
        header.size(cluster.size());
        header.writeTo(output);
        cluster.writeTo(output);
    }

    /**
     * Records a cue point.
     *
     * @param ticks the block timestamp
     * @param trackNumber the track number
     * @param clusterPosition the cluster position relative to the segment data
     * @param blockPosition the block offset from the start of the cluster data
     */
    private void addCue(long ticks, int trackNumber, long clusterPosition, long blockPosition) {
        if (cueLength + 4 > cues.length) {
            cues = Arrays.copyOf(cues, cues.length * 2);
        }
        cues[cueLength++] = ticks;
        cues[cueLength++] = trackNumber;
        cues[cueLength++] = clusterPosition;
        cues[cueLength++] = blockPosition;
    }

    /**
     * Adds two values, saturating to the {@code long} range.
     *
     * @param a the first value
     * @param b the second value
     * @return the sum
     */
    private static long saturatingAdd(long a, long b) {
        var sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? (a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : sum;
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
     * Holds the description and state of one output track.
     */
    private final class TrackState {
        /**
         * The track.
         */
        final Track track;

        /**
         * The track number.
         */
        final int number;

        /**
         * The flags of the track entry.
         */
        final Set<TrackFlag> flags;

        /**
         * Whether the track is video.
         */
        final boolean video;

        /**
         * Whether the track is a text subtitle track.
         */
        final boolean subtitle;

        /**
         * The packets waiting for the interleaving.
         */
        final ArrayDeque<Queued> queue = new ArrayDeque<>();

        /**
         * The codec private data, or {@code null}.
         */
        byte[] codecPrivate;

        /**
         * The sampling frequency of an audio track.
         */
        int sampleRate;

        /**
         * The channel count of an audio track.
         */
        int channels;

        /**
         * The Opus pre-skip in track time-base units, or {@code 0}.
         */
        long preSkip;

        /**
         * The codec delay in nanoseconds.
         */
        long codecDelayNanos;

        /**
         * The timestamp of the previous block in segment ticks.
         */
        long lastTicks;

        /**
         * The decoding time of the previous packet in microseconds.
         */
        long lastMicros;

        /**
         * Whether a cue point was recorded for this track in the open cluster.
         */
        boolean cuedInCluster;

        /**
         * Constructs the state of a track.
         *
         * @param track the declared track
         * @param number the track number
         * @param flags the entry flags
         */
        TrackState(Track track, int number, Set<TrackFlag> flags) {
            this.track = track;
            this.number = number;
            this.flags = flags;
            this.video = track.kind() == TrackKind.VIDEO;
            this.subtitle = track.codec().isSubtitle();
        }

        /**
         * Validates the track and derives its codec private data and audio parameters.
         *
         * @throws IllegalArgumentException if the track lacks what its codec needs
         */
        void configure() {
            var codec = track.codec();
            var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
            if (track.kind() == TrackKind.DATA) {
                switch (codec) {
                    case ASS -> codecPrivate = require(config);
                    case WEBVTT -> {
                        var header = WebVttText.header(config == null ? null : new String(config, StandardCharsets.UTF_8));
                        codecPrivate = options.docType() == MatroskaDocType.WEBM
                                || header.equals(WebVttText.DEFAULT_HEADER) ? null : header.getBytes(StandardCharsets.UTF_8);
                    }
                    case SUBRIP -> codecPrivate = null;
                    default -> throw new IllegalArgumentException("data tracks are not supported");
                }
                return;
            }
            if (track instanceof VideoTrack videoTrack) {
                if (videoTrack.width() <= 0 || videoTrack.height() <= 0) {
                    throw new IllegalArgumentException("video track " + track.id() + " needs its frame size");
                }
                switch (codec) {
                    case H264, HEVC, PRORES -> codecPrivate = require(config);
                    case DNXHD -> codecPrivate = imageDescription(require(config), videoTrack);
                    case AV1 -> codecPrivate = config;
                    case VP9 -> codecPrivate = config == null ? null : vp9Features(config);
                    default -> {
                    }
                }
                return;
            }
            var audio = (AudioTrack) track;
            sampleRate = audio.sampleRate();
            channels = audio.layout().map(layout -> layout.channels()).orElse(0);
            switch (codec) {
                case OPUS -> {
                    codecPrivate = config != null ? config : opusHead(audio);
                    if (codecPrivate.length < 19 || !new String(codecPrivate, 0, 8, StandardCharsets.ISO_8859_1).equals("OpusHead")) {
                        throw new IllegalArgumentException("invalid OpusHead");
                    }
                    channels = codecPrivate[9] & 0xFF;
                    sampleRate = 48_000;
                    var skip = AudioConfigs.opusPreSkip(codecPrivate);
                    preSkip = OPUS_RATE.rescale(skip, track.timeBase());
                    codecDelayNanos = OPUS_RATE.rescale(skip, NANOSECONDS);
                }
                case VORBIS -> {
                    codecPrivate = require(config);
                    vorbisParameters(codecPrivate);
                }
                case AAC -> {
                    codecPrivate = require(config);
                    aacParameters(codecPrivate);
                }
                case FLAC -> {
                    codecPrivate = require(config);
                    if (codecPrivate.length < 42 || codecPrivate[0] != 'f' || codecPrivate[1] != 'L') {
                        throw new IllegalArgumentException("a FLAC track needs its fLaC marker and STREAMINFO block");
                    }
                    // TODO: implement updateCodecConfig, rewriting this CodecPrivate in place on a seekable
                    // sink, so an encoder's final STREAMINFO (length, MD5) reaches the file
                    try {
                        codecPrivate = AudioConfigs.flacContainerConfig(codecPrivate);
                    } catch (MediaException.Malformed exception) {
                        throw new IllegalArgumentException("a FLAC track needs its fLaC marker and STREAMINFO block", exception);
                    }
                    if (sampleRate == 0) {
                        sampleRate = (codecPrivate[18] & 0xFF) << 12 | (codecPrivate[19] & 0xFF) << 4 | (codecPrivate[20] & 0xF0) >> 4;
                    }
                    if (channels == 0) {
                        channels = ((codecPrivate[20] >> 1) & 7) + 1;
                    }
                }
                default -> {
                }
            }
            if (sampleRate <= 0 || channels <= 0) {
                throw new IllegalArgumentException("audio track " + track.id() + " needs its sample rate and channel layout");
            }
            if (codec != Codec.OPUS && audio.skipSamples() > 0) {
                codecDelayNanos = Rational.timeBaseOf(sampleRate).rescale(audio.skipSamples(), NANOSECONDS);
            }
        }

        /**
         * Rejects a missing codec configuration.
         *
         * @param config the configuration, or {@code null}
         * @return {@code config}
         * @throws IllegalArgumentException if {@code config} is {@code null}
         */
        private byte[] require(byte[] config) {
            if (config == null) {
                throw new IllegalArgumentException(track.codec().displayName() + " track " + track.id()
                        + " needs its codec configuration");
            }
            return config;
        }

        /**
         * Builds the {@code CodecPrivate} of a {@code V_QUICKTIME} track: a QuickTime video sample
         * description (Apple QuickTime File Format, "Video sample description"), from its size and
         * sample entry type to its color table ID, 86 bytes without extensions.
         *
         * @param fourCc the sample entry type, four bytes
         * @param video the track
         * @return the sample description
         * @throws IllegalArgumentException if {@code fourCc} is not four bytes long
         */
        private static byte[] imageDescription(byte[] fourCc, VideoTrack video) {
            if (fourCc.length != 4) {
                throw new IllegalArgumentException("a " + video.codec().displayName() + " track needs its"
                        + " four-character sample entry type as configuration");
            }
            var name = video.codec() == Codec.DNXHD && fourCc[3] == 'h' ? "DNxHR" : "DNxHD";
            var buffer = ByteBuffer.allocate(86);
            buffer.putInt(86).put(fourCc).put(new byte[6]).putShort((short) 1);
            buffer.putShort((short) 0).putShort((short) 0).putInt(0).putInt(0x200).putInt(0x200);
            buffer.putShort((short) video.width()).putShort((short) video.height());
            buffer.putInt(0x00480000).putInt(0x00480000).putInt(0).putShort((short) 1);
            var compressor = new byte[32];
            compressor[0] = (byte) name.length();
            System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, compressor, 1, name.length());
            buffer.put(compressor).putShort((short) 24).putShort((short) -1);
            return buffer.array();
        }

        /**
         * Builds an {@code OpusHead} for channel mapping family 0.
         *
         * @param audio the track
         * @return the header
         * @throws IllegalArgumentException if the track has no layout or more than two channels
         */
        private static byte[] opusHead(AudioTrack audio) {
            int count = audio.layout().map(layout -> layout.channels()).orElse(0);
            if (count < 1 || count > 2) {
                throw new IllegalArgumentException("an Opus track of " + count + " channels needs its OpusHead");
            }
            var rate = audio.sampleRate() > 0 ? audio.sampleRate() : 48_000;
            var skip = Rational.timeBaseOf(rate).rescale(audio.skipSamples(), OPUS_RATE);
            if (skip > 0xFFFF) {
                throw new IllegalArgumentException("pre-skip " + skip + " does not fit an OpusHead");
            }
            var head = new byte[19];
            System.arraycopy("OpusHead".getBytes(StandardCharsets.ISO_8859_1), 0, head, 0, 8);
            head[8] = 1;
            head[9] = (byte) count;
            head[10] = (byte) skip;
            head[11] = (byte) (skip >> 8);
            head[12] = (byte) rate;
            head[13] = (byte) (rate >> 8);
            head[14] = (byte) (rate >> 16);
            head[15] = (byte) (rate >> 24);
            return head;
        }

        /**
         * Reads the rate and channel count of a track from its {@code AudioSpecificConfig} when
         * the track does not declare them.
         *
         * @param config the configuration
         * @throws IllegalArgumentException if the configuration is truncated or uses a reserved sampling frequency
         * index
         */
        private void aacParameters(byte[] config) {
            AudioConfigs.AacConfig parsed;
            try {
                parsed = AudioConfigs.parseAac(config);
            } catch (MediaException.Malformed exception) {
                throw new IllegalArgumentException("invalid AudioSpecificConfig: " + exception.getMessage(), exception);
            }
            if (sampleRate == 0) {
                sampleRate = parsed.sampleRate();
            }
            if (channels == 0) {
                channels = parsed.channels();
            }
        }

        /**
         * Reads the rate and channel count of a track from its Vorbis identification header when
         * the track does not declare them.
         *
         * @param config the Xiph-laced header packets
         */
        private void vorbisParameters(byte[] config) {
            var count = (config[0] & 0xFF) + 1;
            var offset = 1;
            for (var lace = 0; lace < count - 1; lace++) {
                while (offset < config.length && (config[offset] & 0xFF) == 255) {
                    offset++;
                }
                offset++;
            }
            if (count != 3 || offset + 16 > config.length || config[offset] != 1) {
                throw new IllegalArgumentException("invalid Vorbis headers");
            }
            if (channels == 0) {
                channels = config[offset + 11] & 0xFF;
            }
            if (sampleRate == 0) {
                sampleRate = (config[offset + 12] & 0xFF) | (config[offset + 13] & 0xFF) << 8 | (config[offset + 14] & 0xFF) << 16
                        | (config[offset + 15] & 0x7F) << 24;
            }
        }

        /**
         * Converts a {@code vpcC} record body to the VP9 {@code CodecPrivate} features.
         *
         * @param vpcc the record body: profile, level, bit depth and chroma byte, ...
         * @return the features: profile, level, bit depth and chroma subsampling, or {@code null} when the record is
         * too short
         */
        private static byte[] vp9Features(byte[] vpcc) {
            if (vpcc.length < 3) {
                return null;
            }
            return new byte[]{1, 1, vpcc[0], 2, 1, vpcc[1], 3, 1, (byte) ((vpcc[2] & 0xFF) >> 4), 4, 1, (byte) ((vpcc[2] >> 1) & 7)};
        }

        /**
         * Derives a missing AV1 or VP9 configuration from the first packet.
         *
         * @param first the track's first packet
         */
        void resolveConfig(Packet first) {
            if (codecPrivate != null || !first.keyframe()) {
                return;
            }
            var videoTrack = (track instanceof VideoTrack v) ? v : null;
            if (track.codec() == Codec.AV1) {
                codecPrivate = CodecConfigs.av1Config(first.data());
            } else if (track.codec() == Codec.VP9 && videoTrack != null) {
                var vpcc = CodecConfigs.vp9Config(first.data(), videoTrack.width(), videoTrack.height(),
                        videoTrack.color().orElse(null));
                codecPrivate = vpcc == null ? null : vp9Features(vpcc);
            }
        }

        /**
         * Writes the {@code TrackEntry} element.
         *
         * @param out the destination
         */
        void writeEntry(EbmlOutput out) {
            out.begin(EbmlIds.TRACK_ENTRY);
            out.unsigned(EbmlIds.TRACK_NUMBER, number);
            out.unsigned(EbmlIds.TRACK_UID, uid(number));
            out.unsigned(EbmlIds.TRACK_TYPE, video ? 1 : subtitle ? TRACK_TYPE_SUBTITLE : 2);
            out.unsigned(EbmlIds.FLAG_LACING, 0);
            out.unsigned(EbmlIds.FLAG_DEFAULT, flags.contains(TrackFlag.DEFAULT) ? 1 : 0);
            if (flags.contains(TrackFlag.FORCED)) {
                out.unsigned(FLAG_FORCED, 1);
            }
            if (flags.contains(TrackFlag.HEARING_IMPAIRED)) {
                out.unsigned(FLAG_HEARING_IMPAIRED, 1);
            }
            if (flags.contains(TrackFlag.VISUAL_IMPAIRED)) {
                out.unsigned(FLAG_VISUAL_IMPAIRED, 1);
            }
            if (flags.contains(TrackFlag.ORIGINAL)) {
                out.unsigned(FLAG_ORIGINAL, 1);
            }
            if (flags.contains(TrackFlag.COMMENTARY)) {
                out.unsigned(FLAG_COMMENTARY, 1);
            }
            out.string(EbmlIds.LANGUAGE, track.language().filter(MatroskaMuxer::isLanguage).orElse("und"));
            out.string(EbmlIds.CODEC_ID, codecId(track.codec()));
            if (codecPrivate != null) {
                out.binary(EbmlIds.CODEC_PRIVATE, codecPrivate);
            }
            if (codecDelayNanos > 0) {
                out.unsigned(EbmlIds.CODEC_DELAY, codecDelayNanos);
            }
            if (track.codec() == Codec.OPUS) {
                out.unsigned(EbmlIds.SEEK_PRE_ROLL, OPUS_SEEK_PRE_ROLL);
            }
            if (track instanceof VideoTrack videoTrack) {
                videoTrack.frameRate().ifPresent(rate -> {
                    var nanos = rate.invert().rescale(1, NANOSECONDS);
                    if (nanos > 0) {
                        out.unsigned(EbmlIds.DEFAULT_DURATION, nanos);
                    }
                });
                out.begin(EbmlIds.VIDEO);
                out.unsigned(EbmlIds.PIXEL_WIDTH, videoTrack.width());
                out.unsigned(EbmlIds.PIXEL_HEIGHT, videoTrack.height());
                videoTrack.color().ifPresent(color -> {
                    out.begin(EbmlIds.COLOR);
                    out.unsigned(EbmlIds.MATRIX_COEFFICIENTS, color.matrix().code());
                    out.unsigned(EbmlIds.RANGE, color.range() == ColorRange.FULL ? 2 : 1);
                    out.unsigned(EbmlIds.TRANSFER_CHARACTERISTICS, color.transfer().code());
                    out.unsigned(EbmlIds.PRIMARIES, color.primaries().code());
                    out.end();
                });
                out.end();
            } else if (!subtitle) {
                out.begin(EbmlIds.AUDIO);
                out.floating(EbmlIds.SAMPLING_FREQUENCY, sampleRate);
                out.unsigned(EbmlIds.CHANNELS, channels);
                if (track.codec().isPcm()) {
                    out.unsigned(EbmlIds.BIT_DEPTH, track.codec().pcmBitsPerSample().orElseThrow());
                }
                out.end();
            }
            out.end();
        }

        /**
         * Returns the decoding time of a packet in microseconds, the interleaving key.
         *
         * @param packet a packet of this track
         * @return the decoding time, or the previous packet's when the packet has no timestamp
         * @throws IllegalArgumentException if the timestamp does not fit in microseconds
         */
        long orderMicros(Packet packet) {
            var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
            if (time == Packet.NO_TIMESTAMP) {
                return lastMicros;
            }
            try {
                lastMicros = track.timeBase().toMicros(time);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("timestamp " + time + " of track " + track.id() + " is out of range", exception);
            }
            return lastMicros;
        }

        /**
         * Returns the block timestamp of a packet in segment ticks, before the global shift.
         *
         * @param packet a packet of this track
         * @return the presentation timestamp plus the Opus pre-skip, or {@link Long#MIN_VALUE} when the packet has no
         * timestamp
         */
        long rawTicks(Packet packet) {
            var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
            if (time == Packet.NO_TIMESTAMP) {
                return Long.MIN_VALUE;
            }
            try {
                return track.timeBase().rescale(saturatingAdd(time, preSkip), segmentBase);
            } catch (ArithmeticException exception) {
                return time < 0 ? Long.MIN_VALUE + 1 : Long.MAX_VALUE / 2;
            }
        }

        /**
         * Returns the duration of a packet in segment ticks.
         *
         * @param packet a packet of this track
         * @return the duration, or {@code 0} when unknown
         */
        long durationTicks(Packet packet) {
            if (packet.duration() <= 0) {
                return 0;
            }
            try {
                return track.timeBase().rescale(packet.duration(), segmentBase);
            } catch (ArithmeticException exception) {
                return 0;
            }
        }

        /**
         * Returns the {@code DiscardPadding} of an Opus packet whose duration is shorter than
         * its coded duration.
         *
         * @param packet an Opus packet of this track
         * @return the padding in nanoseconds, or {@code 0}
         */
        long discardPadding(Packet packet) {
            if (track.codec() != Codec.OPUS || packet.duration() <= 0) {
                return 0;
            }
            var coded = opusSamples(packet.data());
            long kept;
            try {
                kept = track.timeBase().rescale(packet.duration(), OPUS_RATE);
            } catch (ArithmeticException exception) {
                return 0;
            }
            return coded > kept ? OPUS_RATE.rescale(coded - kept, NANOSECONDS) : 0;
        }
    }

    /**
     * Returns the number of 48 kHz samples an Opus packet codes, from its TOC byte (RFC 6716
     * section 3.1).
     *
     * @param packet the Opus packet, starting with its TOC byte
     * @return the sample count, or {@code 0} for an empty or malformed packet
     */
    static int opusSamples(byte[] packet) {
        if (packet.length == 0) {
            return 0;
        }
        var toc = packet[0] & 0xFF;
        var config = toc >> 3;
        int frame;
        if (config < 12) {
            frame = new int[]{480, 960, 1920, 2880}[config & 3];
        } else if (config < 16) {
            frame = (config & 1) == 0 ? 480 : 960;
        } else {
            frame = 120 << (config & 3);
        }
        var frames = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> packet.length < 2 ? 0 : packet[1] & 0x3F;
        };
        return frame * frames;
    }

    /**
     * Returns whether a string is a plausible ISO 639-2 code.
     *
     * @param language the candidate
     * @return {@code true} for three lowercase ASCII letters
     */
    private static boolean isLanguage(String language) {
        if (language.length() != 3) {
            return false;
        }
        for (var index = 0; index < 3; index++) {
            var c = language.charAt(index);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    /**
     * Derives a non-zero {@code TrackUID} from a track number.
     *
     * @implNote This implementation mixes the number with the SplitMix64 finalizer, so UIDs look random yet stay
     * reproducible.
     *
     * @param number the track number
     * @return the UID, non-zero
     */
    private static long uid(int number) {
        var z = number * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z & 0x7FFFFFFFFFFFFFFFL) | 1;
    }
}
