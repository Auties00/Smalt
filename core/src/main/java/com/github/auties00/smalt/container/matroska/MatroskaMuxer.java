package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.DvTags;
import com.github.auties00.smalt.container.internal.AdpcmBlocks;
import com.github.auties00.smalt.container.internal.AdpcmFormat;
import com.github.auties00.smalt.container.internal.BitmapTrackConfigs;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.container.internal.SphericalBoxes;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.DolbyVisionConfigs;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.subtitle.AssScript;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.internal.tags.Ebml;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.internal.tags.MatroskaTags;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.SphericalVideo;
import com.github.auties00.smalt.track.StereoMode;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * audio, text subtitle and data tracks.
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
 *       ({@code V_MPEGH/ISO/HEVC}, {@code hvcC}), MPEG-4 Part 2 ({@code V_MPEG4/ISO/ASP}, the
 *       decoder specific information of the track's configuration, when it has one), MPEG-1 and MPEG-2 video
 *       ({@code V_MPEG1}, {@code V_MPEG2}, the sequence header of the track's configuration, when it has one),
 *       Motion-JPEG
 *       ({@code V_MJPEG}, none), Motion JPEG 2000 ({@code V_JPEG2000}, none: a frame that relies on the track's JP2
 *       Header box is made to carry it, a raw codestream or a single {@code jp2c} box written as a JP2 file and the
 *       two {@code jp2c} boxes of an interlaced frame after a copy of the header box), H.263
 *       ({@code V_MS/VFW/FOURCC}, a {@code BITMAPINFOHEADER} with the FourCC {@code H263}), DV
 *       ({@code V_MS/VFW/FOURCC}, a {@code BITMAPINFOHEADER} with the FourCC {@code dvsd}, {@code dvh1} for DVCPRO
 *       HD; one DIF frame per block), ProRes
 *       ({@code V_PRORES}, the four-character sample entry type of the track's configuration), Theora
 *       ({@code V_THEORA}, the Xiph-laced headers), FFV1 ({@code V_FFV1}, the configuration record of a version 3
 *       stream, none for versions 0 and 1), DNxHD and DNxHR ({@code V_QUICKTIME}, a
 *       QuickTime video sample description of the four-character sample entry type of the
 *       track's configuration, {@code AVdn} or {@code AVdh}), AAC ({@code A_AAC}, the
 *       {@code AudioSpecificConfig}), FLAC ({@code A_FLAC}, the {@code fLaC} marker and metadata
 *       blocks), ALAC ({@code A_ALAC}, the 24-byte {@code ALACSpecificConfig}), WavPack ({@code A_WAVPACK4}, the
 *       two-byte stream version, taken from the first packet when the track has no configuration; packets of
 *       complete blocks are written without their headers and their {@code "wavpack:correction"} side data as the
 *       {@code BlockAdditional} of {@code BlockAddID} 1), MP3 and MP2
 *       ({@code A_MPEG/L3}, {@code A_MPEG/L2}), AC-3 and E-AC-3, AMR-NB and AMR-WB
 *       ({@code A_MS/ACM}, a {@code WAVEFORMATEX} of format tag {@code 0x0038} or {@code 0x0058};
 *       storage-format frames), G.711 A-law and mu-law ({@code A_MS/ACM}, a {@code WAVEFORMATEX}
 *       of format tag 6 or 7), IMA ADPCM, Microsoft ADPCM, GSM 06.10 in Microsoft's packing, G.722 and G.726
 *       ({@code A_MS/ACM}, the {@code WAVEFORMATEX} a WAVE file stores for them, of format tag {@code 0x0011},
 *       {@code 0x0002}, {@code 0x0031}, {@code 0x028F} or {@code 0x0045}; one packet per block, the block size of
 *       IMA and Microsoft ADPCM the size of the track's first packet, or 256 bytes per channel when the header is
 *       written before it, which every later packet but the last must have), and PCM
 *       ({@code A_PCM/INT/LIT} for 8-bit and little-endian integers up to 32 bits, {@code A_PCM/INT/BIG}
 *       for big-endian integers up to 32 bits, {@code A_MS/ACM} with a {@code WAVEFORMATEXTENSIBLE} for
 *       64-bit integers, of the Ambisonic B-format sub-format for the Furse-Malham layouts of
 *       {@link com.github.auties00.smalt.audio.Ambisonics#ofFurseMalham(int)}, {@code A_PCM/FLOAT/IEEE} for
 *       floats). Matroska stores 8-bit PCM as
 *       unsigned, and float and 64-bit integer PCM as little-endian, so signed 8-bit samples are written
 *       with their sign bit flipped and big-endian floats and 64-bit integers byte-swapped; such a track
 *       reads back as {@link Codec#PCM_U8}, {@link Codec#PCM_F32LE}, {@link Codec#PCM_F64LE} or
 *       {@link Codec#PCM_S64LE}.</li>
 *   <li>Text subtitles: WebVTT ({@code D_WEBVTT/SUBTITLES}, with the WebVTT header as
 *       {@code CodecPrivate} in Matroska when it holds more than the signature, none in WebM),
 *       and in Matroska only SubRip ({@code S_TEXT/UTF8}, none) and ASS ({@code S_TEXT/ASS}, or
 *       {@code S_TEXT/SSA} for a v4.00 script, the script header). Subtitle packets become {@code BlockGroup}s
 *       with a {@code BlockDuration};
 *       a WebVTT block holds the cue identifier line, the settings line and the payload. Subtitle
 *       tracks get no cue points.</li>
 *   <li>Bitmap subtitles, in Matroska only: PGS ({@code S_HDMV/PGS}, none), DVB subtitles ({@code S_DVBSUB}, the
 *       composition page, ancillary page and subtitling type of the first subtitling descriptor entry) and DVD
 *       subpictures ({@code S_VOBSUB}, the VobSub index header), written like text subtitles.</li>
 *   <li>Other data, in Matroska only: a {@link Codec#DATA} track whose
 *       {@link com.github.auties00.smalt.track.DataTrack#handler() handler} is a Matroska codec
 *       identifier (a capital letter other than {@code V} and {@code A}, an underscore and
 *       printable ASCII, for example {@code S_HDMV/PGS}) is written with that identifier and its
 *       configuration as {@code CodecPrivate}, as a subtitle track ({@code TrackType} 17) for an
 *       {@code S_} identifier and a {@code D_WEBVTT/} one other than {@code D_WEBVTT/METADATA}, a
 *       buttons track (18) for a {@code B_} one and a metadata track (33) otherwise. Its packets
 *       are written as they are, like those of subtitle tracks.</li>
 * </ul>
 * Each track becomes a {@code TrackEntry} numbered from one in declaration order, with its
 * language ({@code und} when undeclared), the flags of {@link #addTrack(Track, Set)}, the
 * {@code DefaultDuration} of a video track with a frame rate, the {@code Colour} element of a
 * video track with a color description, the {@code FlagInterlaced} and {@code FieldOrder} of a
 * video track with a declared {@link VideoTrack#interlacing() scan}, the {@code DisplayWidth} and
 * {@code DisplayHeight} of a video track with a declared
 * {@link VideoTrack#sampleAspectRatio() sample aspect ratio} (in pixels with the coded height when
 * the stretched width is a whole number, otherwise as a display aspect ratio, {@code DisplayUnit}
 * 3), the {@code StereoMode} of a video track with a declared {@link VideoTrack#stereoMode() stereo mode}
 * (in WebM only the mono, side by side and top-bottom modes the WebM specification allows),
 * the {@code Projection} of a video track with a {@link VideoTrack#spherical() spherical projection} (its
 * type, the payload of its Spherical Video V2 box as {@code ProjectionPrivate} and its pose), and the
 * sampling frequency, channel count and (for PCM) bit depth of an audio track. An audio
 * track's {@link AudioTrack#skipSamples()} becomes its {@code CodecDelay}; Opus stores its pre-skip there
 * instead, with an 80 ms {@code SeekPreRoll}.
 * The {@code Colour} element holds the matrix, range, transfer characteristics and primaries, the
 * {@linkplain ColorInfo#chromaSiting() chroma siting} as
 * {@code ChromaSitingHorz} and {@code ChromaSitingVert} (a siting on the bottom luma row keeps only
 * its horizontal position, which is all Matroska can express), and the static high-dynamic-range
 * metadata: the content light level as {@code MaxCLL} and {@code MaxFALL} and the mastering
 * display as {@code MasteringMetadata}, with exact floating-point values.
 *
 * <p>The {@linkplain com.github.auties00.smalt.track.PacketSideData#hdr10Plus() HDR10+ message}
 * of a video packet is written as the {@code BlockAdditional} of {@code BlockAddID} 4 in the
 * {@code BlockAdditions} of the packet's {@code BlockGroup}. The track entry of a video track whose
 * transfer characteristics are PQ, or that has an HDR10+ message queued when the header is
 * written, declares that {@code BlockAddID} with a {@code MaxBlockAdditionID} of 4 and a
 * {@code BlockAdditionMapping} of {@code BlockAddIDType} 4 (ITU-T T.35 metadata). A message on
 * any other video track (one that is not PQ and whose first message comes after the header, as in
 * live output) is still written under {@code BlockAddID} 4, but its entry declares no mapping, so a
 * reader that follows the mappings may skip it; WebM assigns {@code BlockAddID} 4 of VP9 to ITU-T
 * T.35 metadata without a mapping.
 *
 * <p>In a Matroska file, the track entry of an H.264, HEVC or AV1 track with a
 * {@link VideoTrack#dolbyVision() Dolby Vision configuration} gets a {@code BlockAdditionMapping} of
 * {@code BlockAddIDType} {@code dvcC} (up to profile 7), {@code dvvC} (profiles 8 to 10) or {@code dvwC} (above)
 * whose {@code BlockAddIDExtraData} is the configuration record; the reference processing units stay in the
 * packets. WebM has no Dolby Vision mapping, so a WebM file stores the base layer only.
 *
 * <p>A video track that {@linkplain VideoTrack#hasAlpha() carries alpha} declares {@code AlphaMode} 1
 * in its {@code Video} element and a {@code MaxBlockAdditionID} of at least 1, and the
 * {@linkplain com.github.auties00.smalt.track.PacketSideData#alphaFrame() alpha frame} of each of its
 * packets is written as the {@code BlockAdditional} of {@code BlockAddID} 1 in the
 * {@code BlockAdditions} of the packet's {@code BlockGroup}, as transparent VP8 and VP9 video is
 * stored in WebM. The alpha frame of a packet on any other track is not written.
 *
 * <p>Packets of every track are interleaved by decoding time: a packet is written once every
 * track has a later one queued. An audio or video track without a queued packet holds the others
 * back until its next packet arrives, so an encoder that holds frames back (a lookahead) still gets
 * its blocks written in decoding order; only {@linkplain MatroskaMuxerOptions#live() live} output
 * writes a packet more than a second older than the newest one without waiting for such a track. A
 * subtitle or data track, whose packets are sparse, holds the others back for at most a second. Over
 * 64 MiB of queued packets the oldest is written whatever the other tracks hold.
 * Blocks store presentation timestamps in segment ticks; an Opus block is stamped with its
 * presentation timestamp plus the pre-skip, as the {@code CodecDelay} semantics require, and an
 * Opus or AAC packet whose {@link Packet#duration()} is shorter than its coded duration gets a
 * {@code BlockGroup} with the difference as {@code DiscardPadding}; an AAC track declares its
 * frame duration as {@code DefaultDuration}, which a reader needs to apply the padding. When the
 * first packets reaching the output have negative timestamps, every timestamp is shifted so the
 * earliest is zero. A video packet with an HDR10+ message or an alpha frame also gets a {@code BlockGroup}, with a
 * {@code ReferenceBlock} pointing at the track's previous block when it is not a key frame. Every
 * other packet becomes a {@code SimpleBlock}, flagged as a key frame when it is one (audio packets
 * always are), without lacing. A packet flagged {@link PacketFlag#DISCARD} gets the invisible flag,
 * so a reader decodes it without presenting it.
 *
 * <p>A track stores one {@code CodecPrivate}, so a packet that changes the codec configuration is
 * written in band where the codec allows it: from an H.264 or HEVC packet that changes the
 * configuration record on, every key frame that carries no parameter set gets those of the new
 * record, and the NAL unit lengths are rewritten to the size of the track's record; VP8, VP9 and
 * AV1 frames describe themselves and are written as they are. A change on any other track is
 * rejected. The final {@code STREAMINFO} of a FLAC track and the final {@code ALACSpecificConfig} of an
 * ALAC track, which their encoders report once flushed, reach the file through
 * {@link #updateCodecConfig(int, byte[])}.
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
     * The shortest cluster ended before a video key frame, in microseconds.
     */
    private static final long MIN_KEYFRAME_CLUSTER_MICROS = 250_000;

    /**
     * The largest decoding-time gap between the oldest queued packet and the newest one before
     * the oldest is written without waiting for an empty subtitle or data track, or, in live output, for
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
     * The block flag of a frame that is decoded but not presented.
     */
    private static final int INVISIBLE = 0x08;

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
     * The {@code TrackType} of subtitle tracks.
     */
    private static final int TRACK_TYPE_SUBTITLE = 17;

    /**
     * The {@code BlockAddID} of the HDR10+ ITU-T T.35 messages of a video track.
     */
    private static final int HDR10_PLUS_ADD_ID = 4;

    /**
     * The {@code BlockAddID} of the alpha frames of a video track whose {@code AlphaMode} is 1.
     */
    private static final int ALPHA_ADD_ID = 1;

    /**
     * The side data key of the WavPack correction data a block carries as its {@code BlockAdditional} 1.
     */
    private static final String WAVPACK_CORRECTION = "matroska:BlockAdditional:1";

    /**
     * The {@code BlockAddIDType} of ITU-T T.35 metadata.
     */
    private static final int ITU_T_T35_ADD_ID_TYPE = 4;

    /**
     * The {@code TrackType} of buttons tracks.
     */
    private static final int TRACK_TYPE_BUTTONS = 18;

    /**
     * The {@code TrackType} of metadata tracks.
     */
    private static final int TRACK_TYPE_METADATA = 33;

    /**
     * The bytes of the {@code Void} reserved after the {@code CodecPrivate} of a FLAC track on a
     * seekable sink, so that a final configuration longer than the initial one still fits.
     */
    private static final int PRIVATE_RESERVE = 64;

    /**
     * The {@code WAVEFORMATEX} format tag of AMR-NB.
     */
    private static final int AMR_NB_TAG = 0x0038;

    /**
     * The {@code WAVEFORMATEX} format tag of AMR-WB.
     */
    private static final int AMR_WB_TAG = 0x0058;

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
     * The scratch buffer of a block's header, reused for every block.
     */
    private final EbmlOutput blockHeader;

    /**
     * The scratch buffer of the elements that follow the {@code Block} of a {@code BlockGroup},
     * reused for every block.
     */
    private final EbmlOutput blockTrailer;

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
     * The end of the latest block, in segment ticks: fractional when the packets' own time bases are finer than a
     * tick, so that the segment {@code Duration} ends on the exact last sample.
     */
    private double endTicks;

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
     * The tags of {@link #setTags(MediaTags)}.
     */
    private MediaTags tags = MediaTags.empty();

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
        this.blockHeader = new EbmlOutput(32);
        this.blockTrailer = new EbmlOutput(64);
    }

    /**
     * Returns whether a document type can carry a codec.
     *
     * @param docType the document type
     * @param codec the codec of the track to add
     * @return {@code true} when {@link #addTrack(Track)} accepts tracks of the codec; a
     * {@link Codec#DATA} track also needs a handler that is a Matroska codec identifier
     */
    public static boolean supports(MatroskaDocType docType, Codec codec) {
        Objects.requireNonNull(docType, "docType");
        var id = codecId(Objects.requireNonNull(codec, "codec"));
        if (id == null) {
            return docType == MatroskaDocType.MATROSKA && codec == Codec.DATA;
        }
        return docType != MatroskaDocType.WEBM
                || codec == Codec.VP8 || codec == Codec.VP9 || codec == Codec.AV1 || codec == Codec.OPUS || codec == Codec.VORBIS
                || codec == Codec.WEBVTT;
    }

    /**
     * Returns the Matroska codec identifier of a codec.
     *
     * @param codec the codec to identify
     * @return the identifier, or {@code null} when the muxer does not support the codec or, for
     * {@link Codec#DATA}, the track's handler names it
     */
    private static String codecId(Codec codec) {
        return switch (codec) {
            case VP8 -> "V_VP8";
            case VP9 -> "V_VP9";
            case AV1 -> "V_AV1";
            case H264 -> "V_MPEG4/ISO/AVC";
            case HEVC -> "V_MPEGH/ISO/HEVC";
            case MPEG4_PART2 -> "V_MPEG4/ISO/ASP";
            case MPEG1_VIDEO -> "V_MPEG1";
            case MPEG2_VIDEO -> "V_MPEG2";
            case MJPEG -> "V_MJPEG";
            case JPEG2000 -> "V_JPEG2000";
            case H263, DV -> "V_MS/VFW/FOURCC";
            case PRORES -> "V_PRORES";
            case THEORA -> "V_THEORA";
            case FFV1 -> "V_FFV1";
            case DNXHD -> "V_QUICKTIME";
            case OPUS -> "A_OPUS";
            case VORBIS -> "A_VORBIS";
            case AAC -> "A_AAC";
            case FLAC -> "A_FLAC";
            case ALAC -> "A_ALAC";
            case WAVPACK -> "A_WAVPACK4";
            case MP3 -> "A_MPEG/L3";
            case MP2 -> "A_MPEG/L2";
            case AC3 -> "A_AC3";
            case EAC3 -> "A_EAC3";
            case AMR_NB, AMR_WB, PCM_ALAW, PCM_MULAW, PCM_S64LE, PCM_S64BE, ADPCM_IMA_WAV, ADPCM_MS, GSM_MS, ADPCM_G722,
                 ADPCM_G726 -> "A_MS/ACM";
            case PCM_U8, PCM_S8, PCM_S16LE, PCM_S24LE, PCM_S32LE -> "A_PCM/INT/LIT";
            case PCM_S16BE, PCM_S24BE, PCM_S32BE -> "A_PCM/INT/BIG";
            case PCM_F32LE, PCM_F64LE, PCM_F32BE, PCM_F64BE -> "A_PCM/FLOAT/IEEE";
            case SUBRIP -> "S_TEXT/UTF8";
            case ASS -> "S_TEXT/ASS";
            case WEBVTT -> "D_WEBVTT/SUBTITLES";
            case PGS -> "S_HDMV/PGS";
            case DVB_SUBTITLE -> "S_DVBSUB";
            case DVD_SUBTITLE -> "S_VOBSUB";
            default -> null;
        };
    }

    /**
     * Declares a track, flagged {@link TrackDisposition#DEFAULT} when it is the first of its kind.
     *
     * @param track the track description
     * @throws IllegalArgumentException if the id is already used, the document type cannot carry the codec, the
     * track lacks what its codec needs (a configuration, a frame size, a sample rate, a layout or, for a data track,
     * a Matroska codec identifier as its handler), or a DNxHD configuration holds anything but Avid sample
     * description extensions after its sample entry type
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        var first = true;
        for (var existing : tracks) {
            first &= existing.track.kind() != track.kind();
        }
        addTrack(track, first ? EnumSet.of(TrackDisposition.DEFAULT) : EnumSet.noneOf(TrackDisposition.class));
    }

    /**
     * Declares a track with explicit dispositions.
     *
     * <p>The dispositions become the flag elements of the {@code TrackEntry}: {@code FlagDefault},
     * {@code FlagForced}, {@code FlagHearingImpaired}, {@code FlagVisualImpaired}, {@code FlagTextDescriptions}
     * ({@link TrackDisposition#DESCRIPTIONS}), {@code FlagOriginal} and {@code FlagCommentary}; the other
     * dispositions are not stored. The track tags of {@link #setTags(MediaTags)} replace them.
     *
     * @param track the track description
     * @param flags the dispositions of its {@code TrackEntry}; a flag not in the set is written as cleared
     * @throws IllegalArgumentException if the id is already used, the document type cannot carry the codec, the
     * track lacks what its codec needs (a configuration, a frame size, a sample rate, a layout or, for a data track,
     * a Matroska codec identifier as its handler), or a DNxHD configuration holds anything but Avid sample
     * description extensions after its sample entry type
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    public void addTrack(Track track, Set<TrackDisposition> flags) {
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
        var copy = EnumSet.noneOf(TrackDisposition.class);
        copy.addAll(flags);
        var state = new TrackState(track, tracks.size() + 1, copy);
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
     * @return {@code true}
     */
    @Override
    public boolean supportsAlpha() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The title becomes the segment title of {@code Info}, replacing the configured one; the
     * other tags become a {@code Tags} element, the chapter editions a {@code Chapters} element (nested
     * chapters and localized titles included; an edition's names make a Matroska file declare
     * {@code DocTypeVersion} 5, the version of their {@code EditionDisplay} elements) and the pictures
     * (the first front cover named {@code cover.*}) and the other attachments {@code Attachments}, all written
     * after {@code Tracks} and indexed by the {@code SeekHead}. The tags of a declared track
     * become its {@code Name} (the title), its flag elements (the dispositions, replacing those
     * of {@link #addTrack(Track, Set)}) and a {@code Tag} targeting its {@code TrackUID} (the other
     * entries). A WebM file stores the default edition alone, without edition flags or names and keeping
     * only the localized titles in ISO 639-2 languages, and no attachment that is not a picture, as the WebM
     * specification allows none of them.
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.title = tags.get(StandardTag.TITLE).orElse(null);
        this.tags = tags;
    }

    /**
     * Encodes the {@code Chapters}, {@code Attachments} and {@code Tags} elements of the tags,
     * the {@code Tags} element holding the tags of the declared tracks too.
     */
    private void encodeMetadata() {
        var trackTags = new ArrayList<byte[]>();
        for (var state : tracks) {
            var track = tags.track(state.track.id());
            if (track.isEmpty()) {
                continue;
            }
            var entries = track.get().tags().toBuilder().remove(StandardTag.TITLE).build().entries();
            if (!entries.isEmpty()) {
                trackTags.add(MuxerTags.matroskaTrackTag(uid(state.number), entries));
            }
        }
        var encoded = MuxerTags.matroska(tags.toBuilder().remove(StandardTag.TITLE).build(), trackTags,
                options.docType() == MatroskaDocType.WEBM);
        this.metadata = new byte[][]{encoded[1], encoded[2], encoded[0]};
    }

    /**
     * {@inheritDoc}
     *
     * <p>A FLAC configuration, reduced to the blocks {@code CodecPrivate} keeps (without
     * {@code SEEKTABLE} and {@code PADDING}), or the {@code ALACSpecificConfig} of an ALAC
     * configuration, replaces the track's: before the header is written it is written instead of
     * the initial one, and afterwards, on a seekable sink without
     * {@linkplain MatroskaMuxerOptions#live() live} output, it is rewritten in place over the
     * initial {@code CodecPrivate} (and, for FLAC, the {@code Void} reserved after it), when it
     * fits there. On a streaming sink, in live output, or when it does not fit, the initial
     * configuration stays. Every other codec keeps its configuration.
     *
     * @throws IllegalArgumentException if the track was not declared, a FLAC configuration does
     * not start with {@code STREAMINFO} or describes another sample rate, channel count or sample
     * size than the track's, or an ALAC configuration holds no {@code ALACSpecificConfig} or
     * describes another frame length, sample size, channel count or sampling rate than the track's
     * @throws IllegalStateException if the muxer is finished
     */
    @Override
    public void updateCodecConfig(int trackId, byte[] config) {
        Objects.requireNonNull(config, "config");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        var state = find(trackId);
        if (state == null) {
            throw new IllegalArgumentException("undeclared track " + trackId);
        }
        var codec = state.track.codec();
        if (codec != Codec.FLAC && codec != Codec.ALAC) {
            return;
        }
        byte[] replacement;
        if (codec == Codec.FLAC) {
            AudioConfigs.FlacConfig parsed;
            try {
                replacement = AudioConfigs.flacContainerConfig(config);
                parsed = AudioConfigs.parseFlac(replacement);
            } catch (MediaException.Malformed exception) {
                throw new IllegalArgumentException("a FLAC configuration must start with STREAMINFO", exception);
            }
            if (!parsed.equals(AudioConfigs.parseFlac(state.codecPrivate))) {
                throw new IllegalArgumentException("the final FLAC configuration of track " + trackId
                        + " describes another stream than the initial one");
            }
        } else {
            AudioConfigs.AlacConfig parsed;
            try {
                replacement = AudioConfigs.alacConfig(config);
                parsed = AudioConfigs.parseAlac(replacement);
            } catch (MediaException.Malformed exception) {
                throw new IllegalArgumentException("invalid ALACSpecificConfig: " + exception.getMessage(), exception);
            }
            if (!parsed.equals(AudioConfigs.parseAlac(state.codecPrivate))) {
                throw new IllegalArgumentException("the final ALAC configuration of track " + trackId
                        + " describes another stream than the initial one");
            }
        }
        if (!started) {
            state.codecPrivate = replacement;
            return;
        }
        if (state.privateOffset < 0) {
            return;
        }
        var area = new EbmlOutput((int) state.privateArea);
        if (!writePrivate(area, replacement, state.privateArea)) {
            return;
        }
        var end = output.position();
        output.position(state.privateOffset);
        area.writeTo(output);
        output.position(end);
        state.codecPrivate = replacement;
    }

    /**
     * Writes a {@code CodecPrivate} element followed by the {@code Void} that fills an area.
     *
     * <p>The element's size field grows past its shortest length when the remainder would be a
     * single byte, which no {@code Void} element fits.
     *
     * @param out the destination
     * @param data the codec private data
     * @param area the bytes to fill
     * @return {@code true} when the element fits the area, {@code false} when nothing was written
     */
    private static boolean writePrivate(EbmlOutput out, byte[] data, long area) {
        var idLength = Ebml.idLength(EbmlIds.CODEC_PRIVATE);
        for (var sizeLength = Ebml.sizeLength(data.length); sizeLength <= 8; sizeLength++) {
            var rest = area - idLength - sizeLength - data.length;
            if (rest < 0) {
                return false;
            }
            if (rest != 1) {
                out.id(EbmlIds.CODEC_PRIVATE);
                out.writeSize(data.length, sizeLength);
                out.raw(data, 0, data.length);
                if (rest > 0) {
                    out.voidElement((int) rest);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track was not declared, a timestamp cannot be represented in
     * microseconds, or a new H.264 or HEVC configuration cannot be parsed
     * @throws MediaException.Unsupported if the packet changes the codec configuration of a track
     * that is not H.264, HEVC, VP8, VP9 or AV1
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
        if (state.adpcm != null) {
            state.adpcm.accept(packet.size());
        }
        packet = inBand(state, packet);
        accepting = true;
        var data = packet.data();
        if (state.track.codec() == Codec.WAVPACK) {
            packet = state.wavPack(packet);
        } else if (state.track.codec() == Codec.PRORES && data.length >= 8 && data[4] == 'i' && data[5] == 'c' && data[6] == 'p'
                && data[7] == 'f') {
            // Matroska blocks of V_PRORES omit the frame size and icpf identifier of the frame
            packet = packet.withData(java.util.Arrays.copyOfRange(data, 8, data.length));
        } else if (state.track.codec() == Codec.JPEG2000) {
            var framed = Jp2Frames.selfContained(data, state.track.codecConfig().orElse(null));
            if (framed != data) {
                packet = packet.withData(framed);
            }
        }
        var micros = state.orderMicros(packet);
        state.queue.add(new Queued(packet, micros));
        queuedBytes += packet.size();
        newestMicros = Math.max(newestMicros, micros);
        drain(false);
    }

    /**
     * Applies a packet's codec configuration change in band: records the parameter sets of a new
     * H.264 or HEVC record and rewrites the key frames that follow to carry them, and every packet
     * that follows when the NAL unit length size of the record differs from the track's.
     *
     * @param state the packet's track
     * @param packet the packet about to be queued
     * @return the packet to write
     * @throws IllegalArgumentException if a new H.264 or HEVC record cannot be parsed or a packet
     * is not made of length-prefixed NAL units
     * @throws MediaException.Unsupported if the packet changes the configuration of a track whose
     * codec cannot carry it in band
     */
    private Packet inBand(TrackState state, Packet packet) {
        var codec = state.track.codec();
        var newConfig = ConfigChanges.changed(packet, state.latestConfig);
        if (newConfig != null) {
            switch (codec) {
                case H264, HEVC -> {
                    var hevc = codec == Codec.HEVC;
                    var parsed = hevc ? NalConfigs.parseHevc(newConfig) : NalConfigs.parseAvc(newConfig);
                    var declared = hevc ? NalConfigs.parseHevc(state.codecPrivate) : NalConfigs.parseAvc(state.codecPrivate);
                    if (parsed == null || declared == null) {
                        throw new IllegalArgumentException("the new " + codec.displayName() + " configuration of track "
                                + packet.trackId() + " cannot be parsed");
                    }
                    state.inBandSets = parsed.parameterSets();
                    state.inBandLengthSize = parsed.lengthSize();
                    state.trackLengthSize = declared.lengthSize();
                }
                case VP8, VP9, AV1 -> {
                }
                default -> throw ConfigChanges.unsupported("Matroska", packet.trackId());
            }
            state.latestConfig = newConfig;
        }
        if (state.inBandSets == null || (!packet.keyframe() && state.inBandLengthSize == state.trackLengthSize)) {
            return packet;
        }
        var rewritten = NalUnits.relength(packet.data(), state.inBandLengthSize, state.trackLengthSize,
                codec == Codec.HEVC, packet.keyframe() ? state.inBandSets : null);
        return packet.withData(rewritten);
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
        if (durationOffset >= 0 && endTicks > 0) {
            var bits = Double.doubleToLongBits(endTicks);
            var value = new byte[8];
            for (var index = 0; index < 8; index++) {
                value[index] = (byte) (bits >>> (56 - 8 * index));
            }
            output.position(durationOffset);
            output.write(ByteBuffer.wrap(value));
        } else if (durationOffset >= 0) {
            // a Duration must be positive: an output without a timed frame turns the 11-byte element (2-byte id,
            // 1-byte size, 8-byte value) into a Void element of the same size (1-byte id, 2-byte size)
            var placeholder = new byte[11];
            placeholder[0] = (byte) 0xEC;
            placeholder[1] = 0x40;
            placeholder[2] = 0x08;
            output.position(durationOffset - 3);
            output.write(ByteBuffer.wrap(placeholder));
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
                    if (state.sparse) {
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
            state.hdr10PlusMapping = state.video && carriesHdr10Plus(state);
            if (state.adpcm != null) {
                state.adpcm.declare();
                state.codecPrivate = state.adpcm.waveFormatBytes();
            }
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
        header.unsigned(EbmlIds.DOC_TYPE_VERSION, options.docType() == MatroskaDocType.WEBM ? 4
                : MatroskaTags.docTypeVersion(tags.editions()));
        header.unsigned(EbmlIds.DOC_TYPE_READ_VERSION, 2);
        header.end();
        header.id(EbmlIds.SEGMENT);
        var base = output.position();
        segmentSizeOffset = base + header.size();
        header.writeSize(indexed ? 0 : EbmlOutput.UNKNOWN_SIZE & 0x00FFFFFFFFFFFFFFL, 8);
        segmentStart = base + header.size();
        encodeMetadata();
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
        var tracksAt = base + header.size();
        for (var state : tracks) {
            if (state.privateMark >= 0) {
                state.privateOffset = tracksAt + trackList.markPosition(state.privateMark);
            }
        }
        header.raw(trackList.buffer(), 0, trackList.size());
        for (var element : metadata) {
            header.raw(element, 0, element.length);
        }
        header.writeTo(output);
    }

    /**
     * Returns whether a video track declares HDR10+ metadata in its track entry: when its transfer
     * characteristics are {@linkplain TransferCharacteristics#PQ PQ}, the only transfer HDR10+
     * applies to, or when a packet queued for it carries an HDR10+ message.
     *
     * @param state the video track
     * @return whether the entry gets a {@code BlockAdditionMapping} for HDR10+
     */
    private static boolean carriesHdr10Plus(TrackState state) {
        if (state.track instanceof VideoTrack video
                && video.color().map(color -> color.transfer() == TransferCharacteristics.PQ).orElse(false)) {
            return true;
        }
        for (var queued : state.queue) {
            if (queued.packet.sideData().hdr10Plus().isPresent()) {
                return true;
            }
        }
        return false;
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
        var previousTicks = state.lastTicks;
        var ticks = raw == Long.MIN_VALUE ? state.lastTicks : Math.max(0, saturatingAdd(raw, shiftTicks));
        state.lastTicks = ticks;
        var key = !state.video || packet.keyframe();
        if (!clusterOpen || shouldSplit(state, ticks, key)) {
            openCluster(ticks);
        }
        if (state.sparse) {
            writeTextBlock(state, packet, ticks);
            return;
        }
        var relative = ticks - clusterTicks;
        var padding = state.discardPadding(packet);
        var hdr10Plus = state.video ? packet.sideData().hdr10Plus().orElse(null) : null;
        var alpha = state.alpha ? packet.sideData().alphaFrame().orElse(null)
                : state.track.codec() == Codec.WAVPACK ? packet.sideData().extra(WAVPACK_CORRECTION).orElse(null) : null;
        var trailer = groupTrailer(key, previousTicks - ticks, padding, hdr10Plus, alpha);
        var trackNumberLength = Ebml.sizeLength(state.number);
        var payload = trackNumberLength + 3L + packet.size();
        var header = blockHeader;
        header.reset();
        if (trailer != null) {
            var block = EbmlOutput.elementLength(EbmlIds.BLOCK, payload);
            header.id(EbmlIds.BLOCK_GROUP);
            header.size(block + trailer.size());
            header.id(EbmlIds.BLOCK);
        } else {
            header.id(EbmlIds.SIMPLE_BLOCK);
        }
        header.size(payload);
        header.size(state.number);
        header.rawByte((int) (relative >> 8));
        header.rawByte((int) relative);
        var invisible = packet.flags().contains(PacketFlag.DISCARD) ? INVISIBLE : 0;
        header.rawByte((trailer != null || !key ? 0 : 0x80) | invisible);
        var position = clusterPrefix + clusterBytes;
        var data = state.blockData(packet);
        if (options.live()) {
            header.writeTo(output);
            output.write(ByteBuffer.wrap(data, 0, packet.size()));
        } else {
            cluster.raw(header.buffer(), 0, header.size());
            cluster.raw(data, 0, packet.size());
        }
        clusterBytes += header.size() + packet.size();
        if (trailer != null) {
            if (options.live()) {
                trailer.writeTo(output);
            } else {
                cluster.raw(trailer.buffer(), 0, trailer.size());
            }
            clusterBytes += trailer.size();
        }
        if (key && (state.video || !hasVideo && !state.cuedInCluster)) {
            state.cuedInCluster = true;
            addCue(ticks, state.number, clusterPosition, position);
        }
        endTicks = Math.max(endTicks, state.endTicks(packet, ticks, shiftTicks));
    }

    /**
     * Serializes the elements that follow the {@code Block} of a {@code BlockGroup}: a
     * {@code ReferenceBlock} for a frame that is not a key frame, the {@code DiscardPadding} and the
     * {@code BlockAdditions} holding an alpha frame and an HDR10+ message.
     *
     * @param key whether the frame is a key frame
     * @param reference the timestamp of the track's previous block relative to this one, in segment ticks
     * @param padding the {@code DiscardPadding} in nanoseconds, or {@code 0} for none
     * @param hdr10Plus the HDR10+ ITU-T T.35 message, or {@code null} for none
     * @param alpha the coded alpha frame, or {@code null} for none
     * @return the elements, in a scratch buffer that the next block reuses, or {@code null} when the
     *         frame needs no {@code BlockGroup}
     */
    private EbmlOutput groupTrailer(boolean key, long reference, long padding, byte[] hdr10Plus, byte[] alpha) {
        if (padding <= 0 && hdr10Plus == null && alpha == null) {
            return null;
        }
        var trailer = blockTrailer;
        trailer.reset();
        if (!key) {
            // a ReferenceBlock of zero would point at the block itself
            trailer.signed(EbmlIds.REFERENCE_BLOCK, reference != 0 ? reference : -1);
        }
        if (padding > 0) {
            trailer.signed(EbmlIds.DISCARD_PADDING, padding);
        }
        if (hdr10Plus != null || alpha != null) {
            trailer.begin(EbmlIds.BLOCK_ADDITIONS);
            if (alpha != null) {
                trailer.begin(EbmlIds.BLOCK_MORE);
                trailer.unsigned(EbmlIds.BLOCK_ADD_ID, ALPHA_ADD_ID);
                trailer.binary(EbmlIds.BLOCK_ADDITIONAL, alpha);
                trailer.end();
            }
            if (hdr10Plus != null) {
                trailer.begin(EbmlIds.BLOCK_MORE);
                trailer.unsigned(EbmlIds.BLOCK_ADD_ID, HDR10_PLUS_ADD_ID);
                trailer.binary(EbmlIds.BLOCK_ADDITIONAL, hdr10Plus);
                trailer.end();
            }
            trailer.end();
        }
        return trailer;
    }

    /**
     * Writes one subtitle or data packet as a {@code BlockGroup} with its {@code BlockDuration}; a WebVTT
     * block holds the cue identifier line, the settings line and the payload.
     *
     * @param state the packet's track
     * @param packet the subtitle or data packet to write
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
        endTicks = Math.max(endTicks, state.endTicks(packet, ticks, shiftTicks));
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
         * The dispositions of the track entry.
         */
        final Set<TrackDisposition> flags;

        /**
         * Whether the track is video.
         */
        final boolean video;

        /**
         * Whether the track is video that carries alpha frames.
         */
        final boolean alpha;

        /**
         * Whether the track is a text subtitle or data track, whose packets are sparse and written
         * as {@code BlockGroup}s.
         */
        final boolean sparse;

        /**
         * The Matroska codec identifier.
         */
        String codecId;

        /**
         * The {@code TrackType}.
         */
        int trackType;

        /**
         * The handle of the {@code CodecPrivate} position in the {@code Tracks} output, or
         * {@code -1} when no room is reserved for a final configuration.
         */
        int privateMark = -1;

        /**
         * The absolute offset of the {@code CodecPrivate} element, or {@code -1} when it cannot
         * be rewritten.
         */
        long privateOffset = -1;

        /**
         * The bytes of the {@code CodecPrivate} element and the {@code Void} after it.
         */
        long privateArea;

        /**
         * How packet payloads are converted: {@code 0} not at all, {@code 1} by flipping the sign
         * bit of 8-bit samples, {@code 4} or {@code 8} by reversing the bytes of each sample of
         * that size.
         */
        int conversion;

        /**
         * The converted payload of the latest block, reused across blocks.
         */
        byte[] scratch = new byte[0];

        /**
         * The packets waiting for the interleaving.
         */
        final ArrayDeque<Queued> queue = new ArrayDeque<>();

        /**
         * The codec private data, or {@code null}.
         */
        byte[] codecPrivate;

        /**
         * The format of an ADPCM or telephony track, or {@code null}.
         */
        AdpcmFormat adpcm;

        /**
         * The codec configuration the track's packets last established, initially the track's.
         */
        byte[] latestConfig;

        /**
         * The parameter sets written in band before the key frames of an H.264 or HEVC track
         * whose configuration changed, or {@code null} while it has not.
         */
        List<byte[]> inBandSets;

        /**
         * The NAL unit length size of the packets after an in-band configuration change.
         */
        int inBandLengthSize = 4;

        /**
         * The NAL unit length size of the track's {@code CodecPrivate}.
         */
        int trackLengthSize = 4;

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
         * The number of samples every packet of an AAC track decodes to, at {@link #aacOutputRate},
         * or {@code 0} for another codec.
         */
        long aacFrameSamples;

        /**
         * The output sampling rate of an AAC track, or {@code 0} for another codec.
         */
        int aacOutputRate;

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
         * Whether the track entry maps {@link #HDR10_PLUS_ADD_ID} to ITU-T T.35 metadata.
         */
        boolean hdr10PlusMapping;

        /**
         * Constructs the state of a track.
         *
         * @param track the declared track
         * @param number the track number
         * @param flags the entry flags
         */
        TrackState(Track track, int number, Set<TrackDisposition> flags) {
            this.track = track;
            this.latestConfig = track.codecConfig().orElse(null);
            this.number = number;
            this.flags = flags;
            this.video = track.kind() == TrackKind.VIDEO;
            this.alpha = track instanceof VideoTrack videoTrack && videoTrack.hasAlpha();
            this.sparse = track.kind() == TrackKind.DATA;
            this.codecId = track.codec() == Codec.ASS && track.codecConfig()
                    .map(bytes -> AssScript.parse(TextFiles.decode(bytes)).isSsa()).orElse(false)
                    ? "S_TEXT/SSA" : codecId(track.codec());
            this.trackType = video ? 1 : sparse ? TRACK_TYPE_SUBTITLE : 2;
            this.conversion = switch (track.codec()) {
                case PCM_S8 -> 1;
                case PCM_F32BE -> 4;
                case PCM_F64BE, PCM_S64BE -> 8;
                default -> 0;
            };
        }

        /**
         * Returns the payload of a packet as the block stores it.
         *
         * @param packet a packet of this track
         * @return the packet's data, or the converted payload in {@link #scratch}; the first
         * {@code packet.size()} bytes hold the payload
         */
        byte[] blockData(Packet packet) {
            var data = packet.data();
            if (conversion == 0) {
                return data;
            }
            if (scratch.length < data.length) {
                scratch = new byte[data.length];
            }
            if (conversion == 1) {
                for (var index = 0; index < data.length; index++) {
                    scratch[index] = (byte) (data[index] ^ 0x80);
                }
                return scratch;
            }
            var whole = data.length - data.length % conversion;
            for (var sample = 0; sample < whole; sample += conversion) {
                for (var index = 0; index < conversion; index++) {
                    scratch[sample + index] = data[sample + conversion - 1 - index];
                }
            }
            System.arraycopy(data, whole, scratch, whole, data.length - whole);
            return scratch;
        }

        /**
         * Validates the handler of a data track and derives its codec identifier and track type.
         *
         * @throws IllegalArgumentException if the handler is absent or not a Matroska codec identifier of a track
         * that is neither video nor audio
         */
        private void configureData() {
            var handler = track instanceof DataTrack data ? data.handler().orElse(null) : null;
            if (handler == null || !isDataCodecId(handler)) {
                throw new IllegalArgumentException("data track " + track.id() + " needs a Matroska codec identifier as"
                        + " its handler, not " + handler);
            }
            codecId = handler;
            trackType = switch (handler.charAt(0)) {
                case 'S' -> TRACK_TYPE_SUBTITLE;
                case 'B' -> TRACK_TYPE_BUTTONS;
                default -> handler.startsWith("D_WEBVTT/") && !handler.equals("D_WEBVTT/METADATA")
                        ? TRACK_TYPE_SUBTITLE : TRACK_TYPE_METADATA;
            };
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
                    case SUBRIP, PGS -> codecPrivate = null;
                    case DVB_SUBTITLE -> codecPrivate = BitmapTrackConfigs.matroskaDvb(config);
                    case DVD_SUBTITLE -> codecPrivate = BitmapTrackConfigs.vobSubHeader(config);
                    case DATA -> {
                        configureData();
                        codecPrivate = config;
                    }
                    default -> throw new IllegalArgumentException("Matroska cannot carry " + codec.displayName());
                }
                return;
            }
            if (track instanceof VideoTrack videoTrack) {
                if (videoTrack.width() <= 0 || videoTrack.height() <= 0) {
                    throw new IllegalArgumentException("video track " + track.id() + " needs its frame size");
                }
                switch (codec) {
                    case H264, HEVC, PRORES, THEORA -> codecPrivate = require(config);
                    case DNXHD -> codecPrivate = imageDescription(require(config), videoTrack);
                    case AV1, MPEG4_PART2, MPEG1_VIDEO, MPEG2_VIDEO, FFV1 -> codecPrivate = config;
                    case VP9 -> codecPrivate = config == null ? null : vp9Features(config);
                    case H263 -> codecPrivate = bitmapInfo(videoTrack, "H263", config);
                    case DV -> codecPrivate = bitmapInfo(videoTrack, DvTags.riffFourCc(videoTrack.width()), null);
                    default -> {
                    }
                }
                return;
            }
            var audio = (AudioTrack) track;
            sampleRate = audio.sampleRate();
            channels = audio.layout().map(layout -> layout.channels()).orElse(0);
            if (AdpcmBlocks.supports(codec)) {
                if (sampleRate <= 0 || channels <= 0) {
                    throw new IllegalArgumentException("audio track " + track.id()
                            + " needs its sample rate and channel layout");
                }
                adpcm = new AdpcmFormat(audio, "Matroska");
                codecPrivate = adpcm.waveFormatBytes();
            }
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
                case WAVPACK -> {
                    if (config != null && config.length != 2) {
                        throw new IllegalArgumentException("a WavPack configuration is the two-byte stream version");
                    }
                    codecPrivate = config;
                    if (sampleRate <= 0 || channels <= 0) {
                        throw new IllegalArgumentException("audio track " + track.id()
                                + " needs its sample rate and channel layout");
                    }
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
                case ALAC -> {
                    AudioConfigs.AlacConfig parsed;
                    try {
                        codecPrivate = AudioConfigs.alacConfig(require(config));
                        parsed = AudioConfigs.parseAlac(codecPrivate);
                    } catch (MediaException.Malformed exception) {
                        throw new IllegalArgumentException("invalid ALACSpecificConfig: " + exception.getMessage(),
                                exception);
                    }
                    if (sampleRate == 0) {
                        sampleRate = parsed.sampleRate();
                    }
                    if (channels == 0) {
                        channels = parsed.channels();
                    }
                }
                case AMR_NB, AMR_WB -> {
                    var rate = codec == Codec.AMR_NB ? 8_000 : 16_000;
                    if (sampleRate != 0 && sampleRate != rate || channels > 1) {
                        throw new IllegalArgumentException(codec.displayName() + " track " + track.id()
                                + " must be mono at " + rate + " Hz");
                    }
                    sampleRate = rate;
                    channels = 1;
                    // the byte rates are those of the largest storage-format frames, 32 and 61 bytes per 20 ms
                    codecPrivate = codec == Codec.AMR_NB ? waveFormat(AMR_NB_TAG, 1, rate, 1600, 0)
                            : waveFormat(AMR_WB_TAG, 1, rate, 3050, 0);
                }
                case PCM_ALAW, PCM_MULAW -> {
                    if (sampleRate > 0 && channels > 0) {
                        codecPrivate = waveFormat(WaveFormat.tagOf(codec), channels, sampleRate,
                                (long) sampleRate * channels, 8);
                    }
                }
                case PCM_S64LE, PCM_S64BE -> {
                    if (sampleRate > 0 && channels > 0) {
                        var bFormat = audio.layout().filter(WaveFormat::isAmbisonicBFormat).isPresent();
                        var wave = new WaveFormat(WaveFormat.PCM, channels, sampleRate, (long) sampleRate * channels * 8,
                                channels * 8, 64, 64,
                                bFormat ? 0 : audio.layout().map(WaveFormat::channelMask).orElse(0L),
                                bFormat ? WaveFormat.SubFormat.AMBISONIC : WaveFormat.SubFormat.STANDARD, new byte[0]);
                        var out = new ByteWriter(wave.size(true));
                        wave.write(out, true);
                        codecPrivate = out.toByteArray();
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
         * sample entry type to its color table ID, 86 bytes, followed by the Avid extensions the
         * configuration carries after its sample entry type. The depth is 32 when the extensions
         * declare Avid's alpha, 24 otherwise.
         *
         * @param config the configuration: the sample entry type, four bytes, and the extensions
         * @param video the track
         * @return the sample description
         * @throws IllegalArgumentException if {@code config} is shorter than four bytes, or its bytes
         * after the type are not Avid extensions
         */
        private static byte[] imageDescription(byte[] config, VideoTrack video) {
            if (config.length < 4) {
                throw new IllegalArgumentException("a " + video.codec().displayName() + " track needs its"
                        + " four-character sample entry type as configuration");
            }
            var fourCc = Arrays.copyOf(config, 4);
            var extensions = CodecConfigs.dnxhdExtensions(config);
            var name = video.codec() == Codec.DNXHD && fourCc[3] == 'h' ? "DNxHR" : "DNxHD";
            var buffer = ByteBuffer.allocate(86 + extensions.length);
            buffer.putInt(86 + extensions.length).put(fourCc).put(new byte[6]).putShort((short) 1);
            buffer.putShort((short) 0).putShort((short) 0).putInt(0).putInt(0x200).putInt(0x200);
            buffer.putShort((short) video.width()).putShort((short) video.height());
            buffer.putInt(0x00480000).putInt(0x00480000).putInt(0).putShort((short) 1);
            var compressor = new byte[32];
            compressor[0] = (byte) name.length();
            System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, compressor, 1, name.length());
            buffer.put(compressor).putShort((short) (CodecConfigs.dnxhdAvidAlpha(config) ? 32 : 24))
                    .putShort((short) -1).put(extensions);
            return buffer.array();
        }

        /**
         * Builds the {@code CodecPrivate} of an {@code A_MS/ACM} track: an 18-byte
         * {@code WAVEFORMATEX} without extra bytes.
         *
         * @param tag the format tag
         * @param channels the channel count
         * @param rate the sampling rate in hertz
         * @param byteRate the average number of bytes per second
         * @param bits the bits per sample, 0 for a compressed format
         * @return the structure
         */
        private static byte[] waveFormat(int tag, int channels, int rate, long byteRate, int bits) {
            var blockAlign = bits == 0 ? 1 : channels * bits / 8;
            var wave = new WaveFormat(tag, channels, rate, byteRate, blockAlign, bits, bits, 0,
                    WaveFormat.SubFormat.NONE, new byte[0]);
            var out = new ByteWriter(wave.size(true));
            wave.write(out, true);
            return out.toByteArray();
        }

        /**
         * Builds the {@code CodecPrivate} of a {@code V_MS/VFW/FOURCC} track: a 40-byte
         * {@code BITMAPINFOHEADER} with a FourCC, followed by the track's configuration.
         *
         * @param video the track
         * @param fourCc the FourCC of the codec
         * @param config the configuration, or {@code null}
         * @return the structure
         */
        private static byte[] bitmapInfo(VideoTrack video, String fourCc, byte[] config) {
            var extra = config == null ? new byte[0] : config;
            var buffer = ByteBuffer.allocate(40 + extra.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(40 + extra.length).putInt(video.width()).putInt(video.height());
            buffer.putShort((short) 1).putShort((short) 24).put(fourCc.getBytes(StandardCharsets.US_ASCII));
            buffer.putInt((int) Math.min(3L * video.width() * video.height(), 0xFFFFFFFFL));
            buffer.putInt(0).putInt(0).putInt(0).putInt(0).put(extra);
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
            aacOutputRate = parsed.outputRate();
            aacFrameSamples = (long) parsed.frameLength() * parsed.outputRate() / parsed.sampleRate();
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
         * Whether a WavPack track's packets carry correction data.
         */
        boolean wavPackCorrection;

        /**
         * Rewrites a WavPack packet of complete blocks into the Matroska form (the block's sample count, then the
         * flags, checksum and, when the frame has several blocks, size of each block before its metadata; blocks
         * without audio are left out) and its correction blocks into the {@code BlockAdditional} form (the checksum
         * and, for several blocks, size of each correction block before its metadata), taking the stream version of
         * the first block as the track's {@code CodecPrivate}.
         *
         * @param packet a packet of complete blocks, or one already in the Matroska form
         * @return the packet to write
         * @throws IllegalArgumentException if the packet holds a truncated block
         */
        Packet wavPack(Packet packet) {
            var data = packet.data();
            if (!isWavPackBlock(data, 0)) {
                return packet;
            }
            if (codecPrivate == null) {
                codecPrivate = new byte[]{data[8], data[9]};
            }
            var count = 0;
            var samples = 0;
            for (var position = 0; position < data.length; ) {
                if (!isWavPackBlock(data, position)) {
                    throw new IllegalArgumentException("a WavPack packet must hold whole blocks");
                }
                if (ByteArrays.i32le(data, position + 20) != 0) {
                    count++;
                    samples = ByteArrays.i32le(data, position + 20);
                }
                position += ByteArrays.i32le(data, position + 4) + 8;
            }
            var single = count == 1;
            var out = new ByteArrayOutputStream(data.length);
            writeLe(out, samples);
            for (var position = 0; position < data.length; ) {
                var size = ByteArrays.i32le(data, position + 4) - 24;
                if (ByteArrays.i32le(data, position + 20) == 0) {
                    // blocks without audio (the original file's wrapper, the MD5 signature) have no Matroska form
                    position += size + 32;
                    continue;
                }
                writeLe(out, ByteArrays.i32le(data, position + 24));
                writeLe(out, ByteArrays.i32le(data, position + 28));
                if (!single) {
                    writeLe(out, size);
                }
                out.write(data, position + 32, size);
                position += size + 32;
            }
            var side = packet.sideData();
            var correction = side.extra("wavpack:correction").orElse(null);
            var builder = side.toBuilder().extra("wavpack:correction", null);
            if (correction != null) {
                var additional = new ByteArrayOutputStream(correction.length);
                for (var position = 0; position < correction.length; ) {
                    if (!isWavPackBlock(correction, position)) {
                        throw new IllegalArgumentException("WavPack correction data must hold whole blocks");
                    }
                    var size = ByteArrays.i32le(correction, position + 4) - 24;
                    writeLe(additional, ByteArrays.i32le(correction, position + 28));
                    if (!single) {
                        writeLe(additional, size);
                    }
                    additional.write(correction, position + 32, size);
                    position += size + 32;
                }
                builder.extra(WAVPACK_CORRECTION, additional.toByteArray());
                wavPackCorrection = true;
            }
            return packet.withData(out.toByteArray()).withSideData(builder.build());
        }

        private static boolean isWavPackBlock(byte[] data, int position) {
            if (data.length - position < 32 || data[position] != 'w' || data[position + 1] != 'v'
                    || data[position + 2] != 'p' || data[position + 3] != 'k') {
                return false;
            }
            var size = ByteArrays.i32le(data, position + 4) & 0xFFFFFFFFL;
            return size >= 24 && size + 8 <= data.length - position;
        }

        private static void writeLe(ByteArrayOutputStream out, int value) {
            out.write(value);
            out.write(value >> 8);
            out.write(value >> 16);
            out.write(value >> 24);
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
            out.unsigned(EbmlIds.TRACK_TYPE, trackType);
            out.unsigned(EbmlIds.FLAG_LACING, 0);
            var described = tags.track(track.id());
            var disposition = described.isPresent() ? described.get().disposition() : flags;
            out.unsigned(EbmlIds.FLAG_DEFAULT, disposition.contains(TrackDisposition.DEFAULT) ? 1 : 0);
            if (disposition.contains(TrackDisposition.FORCED)) {
                out.unsigned(EbmlIds.FLAG_FORCED, 1);
            }
            if (disposition.contains(TrackDisposition.HEARING_IMPAIRED)) {
                out.unsigned(EbmlIds.FLAG_HEARING_IMPAIRED, 1);
            }
            if (disposition.contains(TrackDisposition.VISUAL_IMPAIRED)) {
                out.unsigned(EbmlIds.FLAG_VISUAL_IMPAIRED, 1);
            }
            if (disposition.contains(TrackDisposition.DESCRIPTIONS)) {
                out.unsigned(EbmlIds.FLAG_TEXT_DESCRIPTIONS, 1);
            }
            if (disposition.contains(TrackDisposition.ORIGINAL)) {
                out.unsigned(EbmlIds.FLAG_ORIGINAL, 1);
            }
            if (disposition.contains(TrackDisposition.COMMENTARY)) {
                out.unsigned(EbmlIds.FLAG_COMMENTARY, 1);
            }
            var name = described.flatMap(TrackTags::name).orElse(null);
            if (name != null && !name.isEmpty()) {
                out.string(EbmlIds.NAME, name);
            }
            out.string(EbmlIds.LANGUAGE, track.language().filter(MatroskaMuxer::isLanguage).orElse("und"));
            out.string(EbmlIds.CODEC_ID, codecId);
            if (codecPrivate != null && indexed && (track.codec() == Codec.FLAC || track.codec() == Codec.ALAC)) {
                // an ALAC configuration keeps its length, so it needs no room to grow
                privateMark = out.mark();
                privateArea = EbmlOutput.elementLength(EbmlIds.CODEC_PRIVATE, codecPrivate.length)
                        + (track.codec() == Codec.FLAC ? PRIVATE_RESERVE : 0);
                writePrivate(out, codecPrivate, privateArea);
            } else if (codecPrivate != null) {
                out.binary(EbmlIds.CODEC_PRIVATE, codecPrivate);
            }
            if ((alpha || wavPackCorrection) && !hdr10PlusMapping) {
                out.unsigned(EbmlIds.MAX_BLOCK_ADDITION_ID, ALPHA_ADD_ID);
            }
            if (hdr10PlusMapping) {
                out.unsigned(EbmlIds.MAX_BLOCK_ADDITION_ID, HDR10_PLUS_ADD_ID);
                out.begin(EbmlIds.BLOCK_ADDITION_MAPPING);
                out.unsigned(EbmlIds.BLOCK_ADD_ID_VALUE, HDR10_PLUS_ADD_ID);
                out.unsigned(EbmlIds.BLOCK_ADD_ID_TYPE, ITU_T_T35_ADD_ID_TYPE);
                out.end();
            }
            if (track instanceof VideoTrack video && options.docType() != MatroskaDocType.WEBM
                    && (track.codec() == Codec.H264 || track.codec() == Codec.HEVC || track.codec() == Codec.AV1)) {
                video.dolbyVision().ifPresent(configuration -> {
                    out.begin(EbmlIds.BLOCK_ADDITION_MAPPING);
                    out.unsigned(EbmlIds.BLOCK_ADD_ID_TYPE, DolbyVisionConfigs.configType(configuration));
                    out.binary(EbmlIds.BLOCK_ADD_ID_EXTRA_DATA, DolbyVisionConfigs.record(configuration));
                    out.end();
                });
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
                videoTrack.interlacing().ifPresent(scan -> {
                    out.unsigned(EbmlIds.FLAG_INTERLACED, scan.isInterlaced() ? 1 : 2);
                    if (scan.isInterlaced() && scan != Interlacing.INTERLACED) {
                        out.unsigned(EbmlIds.FIELD_ORDER, scan.code());
                    }
                });
                videoTrack.stereoMode().filter(mode -> options.docType() != MatroskaDocType.WEBM || webmStereo(mode))
                        .ifPresent(mode -> out.unsigned(EbmlIds.STEREO_MODE, mode.code()));
                if (alpha) {
                    out.unsigned(EbmlIds.ALPHA_MODE, 1);
                }
                out.unsigned(EbmlIds.PIXEL_WIDTH, videoTrack.width());
                out.unsigned(EbmlIds.PIXEL_HEIGHT, videoTrack.height());
                videoTrack.sampleAspectRatio().ifPresent(aspect -> writeDisplaySize(out, videoTrack, aspect));
                videoTrack.color().ifPresent(color -> writeColor(out, color));
                videoTrack.spherical().ifPresent(spherical -> writeProjection(out, spherical));
                out.end();
            } else if (!sparse) {
                if (aacFrameSamples > 0) {
                    // the frame duration lets a reader apply the DiscardPadding of a shortened last frame
                    out.unsigned(EbmlIds.DEFAULT_DURATION, Rational.timeBaseOf(aacOutputRate).rescale(aacFrameSamples,
                            NANOSECONDS));
                }
                out.begin(EbmlIds.AUDIO);
                out.floating(EbmlIds.SAMPLING_FREQUENCY, sampleRate);
                out.unsigned(EbmlIds.CHANNELS, channels);
                if (track.codec().isPcm() && codecId.startsWith("A_PCM/")) {
                    out.unsigned(EbmlIds.BIT_DEPTH, track.codec().pcmBitsPerSample().orElseThrow());
                }
                if (track instanceof AudioTrack audioTrack && track.codec() == Codec.WAVPACK
                        && audioTrack.bitsPerSample() > 0) {
                    out.unsigned(EbmlIds.BIT_DEPTH, audioTrack.bitsPerSample());
                }
                out.end();
            }
            out.end();
        }

        /**
         * Returns whether a WebM file can store a stereo mode: mono, the two side by side and the two top-bottom
         * packings.
         *
         * @param mode the stereo mode
         * @return {@code true} when the WebM {@code StereoMode} element accepts it
         */
        private static boolean webmStereo(StereoMode mode) {
            return switch (mode) {
                case MONO, SIDE_BY_SIDE_LEFT_FIRST, SIDE_BY_SIDE_RIGHT_FIRST, TOP_BOTTOM_LEFT_FIRST,
                     TOP_BOTTOM_RIGHT_FIRST -> true;
                default -> false;
            };
        }

        /**
         * Writes the {@code Projection} element of a spherical projection: its type, the payload of its
         * Spherical Video V2 box as {@code ProjectionPrivate}, and the angles of its pose that are not zero.
         *
         * @param out the destination, inside the {@code Video} element
         * @param spherical the projection
         */
        private static void writeProjection(EbmlOutput out, SphericalVideo spherical) {
            out.begin(EbmlIds.PROJECTION);
            out.unsigned(EbmlIds.PROJECTION_TYPE, SphericalBoxes.type(spherical.projection()));
            out.binary(EbmlIds.PROJECTION_PRIVATE, SphericalBoxes.payload(spherical.projection()));
            if (spherical.yaw() != 0) {
                out.floating(EbmlIds.PROJECTION_POSE_YAW, spherical.yaw());
            }
            if (spherical.pitch() != 0) {
                out.floating(EbmlIds.PROJECTION_POSE_PITCH, spherical.pitch());
            }
            if (spherical.roll() != 0) {
                out.floating(EbmlIds.PROJECTION_POSE_ROLL, spherical.roll());
            }
            out.end();
        }

        /**
         * Writes the {@code DisplayWidth} and {@code DisplayHeight} of a sample aspect ratio: in
         * pixels, with the coded height, when the stretched width is a whole number; otherwise as
         * the display aspect ratio in lowest terms ({@code DisplayUnit} 3).
         *
         * @param out the destination, inside the {@code Video} element
         * @param video the track
         * @param aspect the sample aspect ratio
         */
        private static void writeDisplaySize(EbmlOutput out, VideoTrack video, Rational aspect) {
            var stretched = BigInteger.valueOf(video.width()).multiply(BigInteger.valueOf(aspect.numerator()));
            var height = BigInteger.valueOf(video.height());
            var divisions = stretched.divideAndRemainder(BigInteger.valueOf(aspect.denominator()));
            if (divisions[1].signum() == 0 && divisions[0].bitLength() <= 63 && divisions[0].signum() > 0) {
                out.unsigned(EbmlIds.DISPLAY_WIDTH, divisions[0].longValue());
                out.unsigned(EbmlIds.DISPLAY_HEIGHT, video.height());
                return;
            }
            var tall = height.multiply(BigInteger.valueOf(aspect.denominator()));
            var gcd = stretched.gcd(tall);
            var width = stretched.divide(gcd);
            var shown = tall.divide(gcd);
            while (width.bitLength() > 63 || shown.bitLength() > 63) {
                width = width.shiftRight(1).max(BigInteger.ONE);
                shown = shown.shiftRight(1).max(BigInteger.ONE);
            }
            out.unsigned(EbmlIds.DISPLAY_WIDTH, width.longValue());
            out.unsigned(EbmlIds.DISPLAY_HEIGHT, shown.longValue());
            out.unsigned(EbmlIds.DISPLAY_UNIT, 3);
        }

        /**
         * Writes the {@code Colour} element of a color description: the matrix, the chroma siting,
         * the range, the transfer characteristics and the primaries, then the {@code MaxCLL} and
         * {@code MaxFALL} of its content light level and the {@code MasteringMetadata} of its
         * mastering display when present.
         *
         * <p>A siting on the bottom luma row, which {@code ChromaSitingVert} cannot express, keeps
         * only its horizontal position.
         *
         * @param out the destination
         * @param color the color description
         */
        private static void writeColor(EbmlOutput out, ColorInfo color) {
            out.begin(EbmlIds.COLOR);
            out.unsigned(EbmlIds.MATRIX_COEFFICIENTS, color.matrix().code());
            var siting = color.chromaSiting();
            var horizontal = switch (siting) {
                case LEFT, TOP_LEFT, BOTTOM_LEFT -> 1;
                case CENTER, TOP, BOTTOM -> 2;
                case UNSPECIFIED -> 0;
            };
            var vertical = switch (siting) {
                case TOP_LEFT, TOP -> 1;
                case LEFT, CENTER -> 2;
                case BOTTOM_LEFT, BOTTOM, UNSPECIFIED -> 0;
            };
            if (horizontal != 0) {
                out.unsigned(EbmlIds.CHROMA_SITING_HORZ, horizontal);
            }
            if (vertical != 0) {
                out.unsigned(EbmlIds.CHROMA_SITING_VERT, vertical);
            }
            out.unsigned(EbmlIds.RANGE, color.range() == ColorRange.FULL ? 2 : 1);
            out.unsigned(EbmlIds.TRANSFER_CHARACTERISTICS, color.transfer().code());
            out.unsigned(EbmlIds.PRIMARIES, color.primaries().code());
            color.contentLightLevel().ifPresent(level -> {
                out.unsigned(EbmlIds.MAX_CLL, level.maxContentLightLevel());
                out.unsigned(EbmlIds.MAX_FALL, level.maxFrameAverageLightLevel());
            });
            color.masteringDisplay().ifPresent(display -> {
                out.begin(EbmlIds.MASTERING_METADATA);
                out.floating(EbmlIds.PRIMARY_R_CHROMATICITY_X, display.red().x());
                out.floating(EbmlIds.PRIMARY_R_CHROMATICITY_Y, display.red().y());
                out.floating(EbmlIds.PRIMARY_G_CHROMATICITY_X, display.green().x());
                out.floating(EbmlIds.PRIMARY_G_CHROMATICITY_Y, display.green().y());
                out.floating(EbmlIds.PRIMARY_B_CHROMATICITY_X, display.blue().x());
                out.floating(EbmlIds.PRIMARY_B_CHROMATICITY_Y, display.blue().y());
                out.floating(EbmlIds.WHITE_POINT_CHROMATICITY_X, display.whitePoint().x());
                out.floating(EbmlIds.WHITE_POINT_CHROMATICITY_Y, display.whitePoint().y());
                out.floating(EbmlIds.LUMINANCE_MAX, display.maxLuminance());
                out.floating(EbmlIds.LUMINANCE_MIN, display.minLuminance());
                out.end();
            });
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
         * Returns where a packet ends in segment ticks: its exact end in its own time base, unrounded, when its
         * timestamp and duration are known, otherwise its block timestamp plus its rounded duration.
         *
         * @param packet a packet of this track
         * @param ticks the block timestamp of the packet in segment ticks, shifted
         * @param shift the shift added to every block timestamp, in segment ticks
         * @return the end, in segment ticks
         */
        double endTicks(Packet packet, long ticks, long shift) {
            var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
            if (time == Packet.NO_TIMESTAMP || packet.duration() <= 0) {
                return ticks + durationTicks(packet);
            }
            var timeBase = track.timeBase();
            var end = ((double) time + preSkip + packet.duration()) * timeBase.numerator() * segmentBase.denominator()
                    / ((double) timeBase.denominator() * segmentBase.numerator()) + shift;
            return Double.isFinite(end) ? Math.max(end, ticks) : ticks + durationTicks(packet);
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
         * Returns the {@code DiscardPadding} of an Opus or AAC packet whose duration is shorter
         * than its coded duration.
         *
         * <p>A duration equal to the coded duration rounded to the track's time base, as a time
         * base coarser than one sample gives every packet, is not shortened.
         *
         * @param packet a packet of this track
         * @return the padding in nanoseconds, or {@code 0}
         */
        long discardPadding(Packet packet) {
            if (packet.duration() <= 0) {
                return 0;
            }
            long coded;
            Rational base;
            if (track.codec() == Codec.OPUS) {
                coded = opusSamples(packet.data());
                base = OPUS_RATE;
            } else if (aacFrameSamples > 0) {
                coded = aacFrameSamples;
                base = Rational.timeBaseOf(aacOutputRate);
            } else {
                return 0;
            }
            long kept;
            try {
                if (packet.duration() >= base.rescale(coded, track.timeBase())) {
                    return 0;
                }
                kept = track.timeBase().rescale(packet.duration(), base);
            } catch (ArithmeticException exception) {
                return 0;
            }
            return coded > kept ? base.rescale(coded - kept, NANOSECONDS) : 0;
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
     * Returns whether a string is a Matroska codec identifier a data track can carry.
     *
     * @param id the candidate
     * @return {@code true} for a capital letter other than {@code V} and {@code A}, an underscore,
     * and at least one printable ASCII character
     */
    private static boolean isDataCodecId(String id) {
        if (id.length() < 3 || id.charAt(1) != '_') {
            return false;
        }
        var prefix = id.charAt(0);
        if (prefix < 'A' || prefix > 'Z' || prefix == 'V' || prefix == 'A') {
            return false;
        }
        for (var index = 2; index < id.length(); index++) {
            var c = id.charAt(index);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
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
