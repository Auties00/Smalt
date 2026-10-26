package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.container.internal.TimecodeTags;
import com.github.auties00.smalt.internal.cenc.CencCipher;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.heif.DataReferences;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.caption.QuickTimeCaptions;
import com.github.auties00.smalt.internal.subtitle.QuickTimeText;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.internal.tags.Mp4ChapterTrack;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Demuxes ISO base media files: MP4, MOV, M4A, M4V, 3GP and 3G2, progressive or fragmented.
 *
 * <p>The constructor walks the top-level boxes, reads the {@code moov} wherever it is (before or
 * after the media data), and indexes every sample of every track, including the samples of movie
 * fragments ({@code moof}); {@code sidx} and {@code mfra} indexes are not needed and ignored.
 * {@link #read()} then returns the samples of all tracks interleaved in file-offset order
 * (the order they are stored in), each track's samples in decoding order.
 *
 * <p>Tracks are identified from their sample descriptions: H.264 ({@code avc1}, {@code avc3}),
 * HEVC ({@code hvc1}, {@code hev1}, Dolby Vision {@code dvh1}/{@code dvhe}), VP8/VP9
 * ({@code vp08}/{@code vp09}), AV1 ({@code av01}), MPEG-4 Part 2 ({@code mp4v}), H.263
 * ({@code s263}), Motion JPEG and ProRes for video; AAC, MP3 and MP2 ({@code mp4a} with the
 * matching object type, {@code .mp3}), Opus, FLAC, ALAC, AMR-NB/WB, AC-3/E-AC-3 and every PCM
 * layout of the ISO and QuickTime sound descriptions for audio. The image sequence ({@code pict})
 * and auxiliary video ({@code auxv}) tracks of HEIF and AVIF sequences are video tracks too. Every
 * other handler (text, subtitles, timecode, timed metadata, hint) becomes a
 * {@link com.github.auties00.smalt.track.DataTrack}: 3GPP timed text ({@code tx3g}) is
 * {@link Codec#MOV_TEXT}, and so is QuickTime text media ({@code text}), its samples and description
 * converted to the 3GPP form, WebVTT ({@code wvtt}) is {@link Codec#WEBVTT}, TTML ({@code stpp}) is
 * {@link Codec#TTML}, DVD subpictures ({@code mp4s} with object type {@code 0xE0}) are {@link Codec#DVD_SUBTITLE}
 * with a VobSub index header built from the palette and the track size, and QuickTime closed captions
 * ({@code c608}, {@code c708}) are {@link Codec#CEA_608} and {@link Codec#CEA_708}, their samples converted to
 * {@code cc_data} constructs, except in the chapter tracks a {@code chap} reference names, which stay
 * {@link Codec#DATA}. Each WebVTT sample is returned as one packet per cue it shows (none for an
 * empty sample), all with the sample's timing, in the {@link Codec#WEBVTT} packet form; every
 * other sample is one packet. Codec
 * configurations follow {@link Track#codecConfig()}: the {@code avcC}, {@code hvcC} and {@code av1C} payloads (an
 * {@code hvcC} followed by the arrays of the {@code lhvC} record of a multi-layer HEVC entry, which carries the
 * parameter sets of the layers above the base layer), the {@code vpcC} record without its full box header, the
 * AAC {@code AudioSpecificConfig} and the
 * MPEG-4 Part 2 decoder specific info from {@code esds}, an {@code OpusHead} synthesized from
 * {@code dOps}, {@code fLaC} followed by the metadata blocks of {@code dfLa}, the 24-byte
 * {@code ALACSpecificConfig}, and for data tracks the sample entry payload after its reserved
 * fields and data reference index. A visual sample entry's {@code colr}, {@code pasp} and
 * QuickTime {@code fiel} boxes give the track's color, sample aspect ratio and scan
 * ({@link com.github.auties00.smalt.track.VideoTrack#sampleAspectRatio()},
 * {@link com.github.auties00.smalt.track.VideoTrack#interlacing()}), and its {@code mdcv} and
 * {@code clli} boxes the mastering display and content light level of that color; an entry with
 * either box but no {@code colr} gets a color of unspecified matrix, primaries and transfer
 * characteristics, in the limited range, that carries them.
 *
 * <p>Timestamps are in each track's media timescale ({@code 1/timescale}). Decoding times come
 * from {@code stts} or the fragment runs, presentation times add the {@code ctts} or {@code trun}
 * composition offsets (both versions, negative offsets allowed), and an edit list of empty edits
 * followed by one media edit shifts both: the empty edits delay the track, and the media time of
 * the media edit becomes timestamp zero for video and data, and
 * {@link AudioTrack#skipSamples()} for audio (whose timestamps are left unshifted, so the first
 * kept sample has the timestamp of the first packet). Negative composition offsets lower the
 * decoding timestamps of their track so that no sample is decoded after it is presented. Without
 * an edit list, an iTunes {@code iTunSMPB} tag provides the audio skip.
 *
 * <p>Any other edit list (ISO/IEC 14496-12 clause 8.6.6: several media edits, empty edits between
 * them, dwell edits of media rate zero) makes the track's packets those of its presentation: each
 * edit returns the samples it presents with timestamps on the presentation timeline, preceded by
 * the samples a decoder needs before them (from the key frame at or before them for video and
 * data; for audio, the packets that rebuild the codec's state, every packet up to an edit that
 * does not go back in the media for an AAC object type that predicts from the start of the stream,
 * whose output only converges after an edit that does), which are flagged
 * {@link PacketFlag#DISCARD}; a sample several edits present is returned once per edit. A video
 * or data sample straddling an edit boundary is cut to the edit, and a discarded video sample has
 * a presentation timestamp no presented sample of its track has. An audio packet cut by its edit
 * keeps its timestamp and carries the samples outside the edit as its
 * {@link PacketSideData#discardStart()} and {@link PacketSideData#discardEnd()}, its duration
 * excluding the end. A dwell edit presents the video or data sample shown at its media time for its
 * duration, and nothing for audio; every other edit plays at normal speed, whatever its media rate.
 * Decoding timestamps never decrease, so the pre-roll of an edit that goes back in the media takes
 * the decoding time of the packet before it. Such a track declares no audio skip, and its duration
 * is that of its edits. The image sequence and auxiliary video tracks of HEIF and AVIF files
 * ({@code pict} and {@code auxv} handlers) are the exception: their timestamps follow the first
 * media edit as in the simple form, and each of their samples is returned once.
 *
 * <p>Scalable (SVC) and multiview (MVC) H.264 video is stored as ISO/IEC 14496-15 clauses 7 and 8 describe.
 * The {@code svc1}, {@code svc2}, {@code mvc1} to {@code mvc4}, {@code avc2} and {@code avc4} sample entries
 * are H.264 tracks too, and the parameter sets of an entry's {@code svcC} and {@code mvcC} boxes join those of
 * its {@code avcC} box in the track's configuration (an {@code AVCDecoderConfigurationRecord} whose sequence
 * parameter sets include the subset sequence parameter sets). A layer stored in a track of its own, one whose
 * {@code tref}/{@code scal} box names the tracks it extends, gets the parameter sets of those tracks too, and
 * each of its packets is the whole access unit up to its layer: with the {@code svc1}, {@code mvc1} and
 * {@code mvc3} entries the time-aligned samples of the referenced tracks followed by its own sample (implicit
 * reconstruction), otherwise its sample with every extractor (NAL unit type 31) replaced by the bytes it names
 * in a referenced track's sample and every aggregator (type 30) by the NAL units it holds. Data an extractor
 * names that cannot be read, such as a sample a stream has already passed beyond the last 128 samples kept
 * per referenced track, is left out, so the decoder sees the access unit damaged. The referenced tracks keep
 * their own samples.
 *
 * <p>Every sample description of a track is read. The public track describes the one of its first
 * sample; a sample of another description (chosen by {@code stsc}, or by the {@code tfhd} or
 * {@code trex} of a fragment) whose codec configuration differs from the one of the track's previous
 * packet carries the new configuration as its {@link PacketSideData#newCodecConfig()}, and so does
 * the first packet after a seek when its description is not the track's. A description of another
 * codec raises {@link MediaException.Unsupported}, and one that does not parse
 * {@link MediaException.Malformed}, when a sample using it is read.
 *
 * <p>An AAC track whose sample entry declares the rate of the AAC core while spectral band
 * replication doubles it (HE-AAC, signaled explicitly or only inside the raw data blocks), or
 * whose parametric stereo turns a mono core into stereo, is described by the format its first
 * sample decodes to through the registered AAC decoder, as players do; the track keeps its media
 * timescale, and its audio skip is rescaled to the decoded rate. Uncompressed audio stored one
 * frame per sample is returned in packets of up to 1024 frames within a chunk.
 *
 * <p>Every size read from the file is checked against the {@link MediaLimits} before allocating:
 * the {@code moov}, each {@code moof}, each sample, and the number of samples per track.
 * Encrypted tracks ({@code encv}, {@code enca}) raise {@link MediaException.Unsupported}. The
 * compressed boxes of ISO/IEC 14496-12 clause 8.20 are read in place of the boxes they replace: a
 * compressed movie box ({@code !mov}) as the {@code moov} and compressed movie fragments
 * ({@code !mof}) as {@code moof} boxes, each decompressed size checked against the byte limit too.
 * Their offsets count in the file as it would be with every compressed box uncompressed, compressed
 * segment indexes ({@code !six}, {@code !ssx}) included, and are mapped into the file as it is. A QuickTime
 * compressed movie (a {@code moov} holding a {@code cmov} atom, zlib-compressed) is decompressed within the byte
 * limit too, its offsets counting in the file as it is.
 *
 * <p>The samples of a track whose data reference ({@code dref}) names another file, as a reference movie's do,
 * are read from the source that the {@link DemuxerOptions#dataReferences() resolver} of the options supplies for
 * that reference, at their offsets in it; the demuxer asks for each reference once and closes the sources it
 * obtained in {@link #close()}. Without such a source the samples raise {@link MediaException.Unsupported}.
 * Samples of one file are read in the order of their offsets in it, and samples of different files in decoding
 * order.
 *
 * <p>Cover pictures ({@code covr} items) become {@link #attachedPictures()}; chapters come from a
 * Nero {@code chpl} box or, without one, from the first QuickTime chapter text track a track
 * references through {@code tref}/{@code chap}, whose samples hold the titles; the chapter track
 * is also preferred when it completes the chapters or titles a full {@code chpl} box had to cut
 * (255 chapters, 255-byte titles). The first QuickTime timecode track ({@code tmcd} handler and sample entry)
 * gives {@link #timecode()}: the frame number of its first sample, counted at the nominal rate and with the
 * drop-frame flag of its sample entry (a counter track gives none); the track itself is a data track. Without
 * such a track, a {@code timecode} iTunes item gives it, counted at the first video track's frame rate rounded
 * up (at 30 frames per second for a drop-frame address when there is no rate). A visual
 * sample entry's Spherical Video V2 {@code st3d} and {@code sv3d} boxes give the track's
 * {@link com.github.auties00.smalt.track.VideoTrack#stereoMode() stereo mode} and
 * {@link com.github.auties00.smalt.track.VideoTrack#spherical() spherical projection}. Seeking uses the
 * sample index, as {@link #seek(long)} describes.
 *
 * <p>A source that is not in memory is read forwards as far as the layout allows: a file whose
 * {@code moov} follows its media data is read with one jump to the {@code moov} and one back to
 * the media data, and a fragmented file with one jump per fragment.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every sample
 * which bytes are no longer needed, so it keeps only the bytes from the next sample on. The
 * top-level walk of such a source stops at the first {@code mdat} once the {@code moov} is known,
 * and the {@code moof} boxes of a fragmented file are then read as the demux reaches them rather
 * than at construction, so a fast-start or fragmented file is read with a bounded window while a
 * file whose {@code moov} follows its media data keeps that media data until the {@code moov} is
 * read. {@link #seek(long)} and the sample statistics read every remaining fragment first, and so
 * does the constructor when an edit list needs the presentation index. A track of such a
 * fragmented file whose duration would be measured from its samples reports an unknown duration,
 * since the fragments not yet read would lengthen it.
 *
 * @implNote This implementation reads the payloads of the {@code moov} and {@code moof} boxes
 * together with their headers during the top-level walk, up to {@value #PREFETCH_BUDGET} bytes of
 * them. It takes the composition offset that aligns the decoding timestamps of
 * a track ({@code compositionShift}) as the lowest offset among the samples indexed at construction,
 * so a fragmented file read from a stream takes it from its first fragments only; a later fragment
 * with a lower offset then gives decoding timestamps above the presentation ones, where a
 * random-access source would have shifted every timestamp of the track.
 */
public final class Mp4Demuxer implements Demuxer {
    /**
     * The largest first sample read to refine an AAC track; a raw data block of 48 channels at
     * the 6144 bits each channel may use fits.
     */
    private static final int MAX_AAC_SAMPLE = 1 << 16;

    /**
     * The size of the read-ahead window that serves small samples.
     */
    private static final int WINDOW_SIZE = 1 << 20;

    /**
     * The largest sample read through the window rather than directly.
     */
    private static final int WINDOW_MAX_SAMPLE = 64 * 1024;

    /**
     * The largest number of bytes of {@code moov} and {@code moof} payloads read during the
     * top-level walk of a source that is not in memory.
     *
     * @implNote This implementation allows 16 MiB, which holds the {@code moov} of a multi-hour
     * progressive file and the {@code moof} boxes of thousands of fragments; the payloads are the
     * ones the constructor reads anyway, and only their lifetime grows, from their parse to the
     * end of the walk. In-memory sources prefetch nothing.
     */
    private static final int PREFETCH_BUDGET = 16 << 20;

    /**
     * The bytes of an empty sample.
     */
    private static final byte[] EMPTY = new byte[0];

    /**
     * The flags of a WebVTT cue packet, every cue being a random access point, and of any other
     * sync sample that carries side data.
     */
    private static final Set<PacketFlag> KEY_FLAGS = Set.of(PacketFlag.KEY);

    /**
     * The flags of a sample decoded only so that later samples decode.
     */
    private static final Set<PacketFlag> DISCARD_FLAGS = Set.of(PacketFlag.DISCARD);

    /**
     * The flags of a sync sample decoded only so that later samples decode.
     */
    private static final Set<PacketFlag> KEY_DISCARD_FLAGS = Set.of(PacketFlag.KEY, PacketFlag.DISCARD);

    /**
     * The source being demuxed.
     */
    private final MediaSource source;

    /**
     * The limits applied to every size read from the source.
     */
    private final MediaLimits limits;

    /**
     * The files the data references of the tracks name, which {@link #close()} closes.
     */
    private final DataReferences external;

    /**
     * The keys that decrypt protected tracks.
     */
    private final DecryptionKeys keys;

    /**
     * The cipher of each key identifier a protected sample used, by the identity of the identifier array of its
     * sample description or sample group.
     */
    private final Map<byte[], CencCipher> ciphers = new IdentityHashMap<>();

    /**
     * The cipher of each key identifier, by its bytes, shared by the identifier arrays of every sample group that
     * names it (the groups of each fragment of a file whose keys rotate repeat the identifiers).
     */
    private final Map<ByteBuffer, CencCipher> ciphersById = new HashMap<>();

    /**
     * The subsample map of one key of a multi-key sample, the subsamples of the other keys made clear.
     */
    private int[] multiKeyPairs = new int[32];

    /**
     * The buffer protected samples are decrypted into by {@link #readSample(SampleInfo)}, allocated on first use.
     */
    private byte[] plain;

    /**
     * Whether a track keeps samples both in the file and in a file a data reference names, so that a source read
     * once cannot be told which of its bytes are no longer needed.
     */
    private final boolean mixedSources;

    /**
     * The source size sampled at construction, or {@code -1} when unknown.
     */
    private final long sourceSize;

    /**
     * The public track descriptions, in file order.
     */
    private final List<Track> tracks;

    /**
     * The container-level metadata tags.
     */
    private final Map<String, String> metadata;

    /**
     * The presentation duration in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * Whether the file stores the {@code moov} before its first {@code mdat}.
     */
    private final boolean fastStart;

    /**
     * The tracks that have samples, indexed by the values in {@link #heap}.
     */
    private final Mp4Track[] active;

    /**
     * Every track, in file order.
     */
    private final Mp4Track[] allTracks;

    /**
     * The raw bytes of the samples read last from each track that a scalable or multiview track references,
     * by track and sample index, so that a forward-only source can rebuild the access units of the
     * referencing track; empty for files without such tracks.
     */
    private final Map<Mp4Track, LinkedHashMap<Integer, byte[]>> referencedSamples = new HashMap<>();

    /**
     * The tracks whose samples the access unit being rebuilt by implicit reconstruction already holds, so
     * that each track contributes its sample once however many references name it.
     */
    private final List<Mp4Track> reconstructed = new ArrayList<>();

    /**
     * The most samples {@link #referencedSamples} keeps per track.
     */
    private static final int REFERENCED_SAMPLES = 128;

    /**
     * The chapters: the Nero {@code chpl} box, or else the first QuickTime chapter track.
     */
    private final List<Chapter> chapters;

    /**
     * The cover pictures of the {@code covr} items.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The timecode of the first timecode track, or {@code null}.
     */
    private final Timecode timecode;

    /**
     * The end of the furthest sample, past which the read-ahead window never reads.
     */
    private long dataEnd;

    /**
     * Whether the source reads its input once, front to back, and is told which bytes the demux
     * no longer needs.
     */
    private final boolean forwardOnly;

    /**
     * The highest offset a forward-only source was told it will not be read below again; a seek
     * that would read a sample below it is refused unless the source still holds the bytes.
     */
    private long released;

    /**
     * The offset of the next top-level box to walk for {@code moof} boxes, or {@code -1} when
     * every fragment is indexed; only a forward-only source indexes fragments as the demux
     * reaches them.
     */
    private long fragmentScan;

    /**
     * Every track of the movie, which the fragments indexed after construction extend, or
     * {@code null} when every fragment was indexed at construction.
     */
    private final List<Mp4Track> fragmentTracks;

    /**
     * The compressed boxes walked so far, which map the offsets the file stores to offsets in the
     * file, or {@code null} while none was met.
     */
    private CompressedBoxes compression;

    /**
     * The index of the next sample of each active track.
     */
    private final int[] cursors;

    /**
     * The sample description whose codec configuration the packets of each active track last
     * established: the track's own until a sample of another description is returned.
     */
    private final int[] descriptions;

    /**
     * The movie timescale, in which the edit lists of the tracks count.
     */
    private final long movieTimescale;

    /**
     * A binary min-heap of active track indices ordered by the file offset of their next sample.
     */
    private final int[] heap;

    /**
     * The number of entries in {@link #heap}.
     */
    private int heapSize;

    /**
     * The identifiers of the WebVTT tracks, whose samples are split into one packet per cue.
     */
    private final Set<Integer> webVttTracks;

    /**
     * The identifiers of the QuickTime text tracks, whose samples are converted to 3GPP timed text.
     */
    private final Set<Integer> quickTimeTextTracks;

    /**
     * The identifiers of the QuickTime closed caption tracks, whose samples are converted to {@code cc_data}
     * constructs.
     */
    private final Set<Integer> captionTracks;

    /**
     * The cue packets of a WebVTT sample not returned yet.
     */
    private final ArrayDeque<Packet> pendingCues = new ArrayDeque<>();

    /**
     * The read-ahead window, allocated on first use.
     */
    private byte[] window;

    /**
     * The array behind the source when it is in memory, read in place instead of through
     * {@link #window}, or {@code null}.
     */
    private final ArrayBacked backing;

    /**
     * The segment behind the source when it is a native memory segment, copied from directly
     * with one bulk copy per sample instead of through {@link #window}, or {@code null}.
     */
    private final MemorySegment segment;

    /**
     * The buffer that receives samples too large for {@link #window} in
     * {@link #readSample(SampleInfo)}, allocated on first use.
     */
    private byte[] scratch;

    /**
     * The absolute offset of {@code window[0]}.
     */
    private long windowStart;

    /**
     * The number of valid bytes in {@link #window}.
     */
    private int windowLength;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not an ISO base media file or is corrupt
     * @throws MediaException.Unsupported if a track is encrypted
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public Mp4Demuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not an ISO base media file or is corrupt
     * @throws MediaException.Unsupported if a track is encrypted
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public Mp4Demuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits(), new DataReferences(options.dataReferences()), options.decryptionKeys());
    }

    private Mp4Demuxer(MediaSource source, MediaLimits limits, DataReferences external, DecryptionKeys keys) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.external = external;
        this.keys = keys;
        this.backing = ArrayBacked.of(source);
        var segmentBacked = SegmentBacked.of(source);
        this.segment = segmentBacked != null ? segmentBacked.segment() : null;
        this.forwardOnly = ForwardOnlySource.isForwardOnly(source);
        try {
            this.sourceSize = source.size();
            var scan = TopLevelScan.scan(source, forwardOnly,
                    SegmentBacked.isInMemory(source) ? 0 : Math.min(PREFETCH_BUDGET, limits.maxBytes()));
            this.fastStart = scan.isFastStart();
            var moov = scan.moovPayload;
            scan.moovPayload = null;
            var compressedMovie = scan.moovOffset < 0 && scan.compressedMoov != null;
            if (compressedMovie) {
                var box = scan.compressedMoov;
                var payload = scan.compressedMoovPayload;
                scan.compressedMoovPayload = null;
                moov = CompressedBoxes.inflate(payload != null ? payload : readBox(box[0], (int) box[1], box[2], "!mov"),
                        "!mov", limits);
            } else if (scan.moovOffset < 0) {
                throw new MediaException.Malformed("no moov box");
            }
            var parser = new MovieParser(limits, external);
            parser.readsProtection = !keys.equals(DecryptionKeys.none());
            var movie = moov != null ? moov : readBox(scan.moovOffset, scan.moovHeader, scan.moovSize, "moov");
            // the sample auxiliary information of a progressive file usually lies in its moov, already in memory
            var moviePayload = compressedMovie ? -1 : scan.moovOffset + scan.moovHeader;
            parser.auxSource = (offset, length) -> moviePayload >= 0 && inPayload(movie, moviePayload, offset, length)
                    ? Arrays.copyOfRange(movie, (int) (offset - moviePayload), (int) (offset - moviePayload) + length)
                    : readAuxiliary(offset, length);
            parser.parse(BoxCursor.of(movie, BoxTypes.MOOV));
            checkKeys(parser.tracks);
            var movieSamples = new int[parser.tracks.size()];
            for (var index = 0; index < movieSamples.length; index++) {
                var track = parser.tracks.get(index);
                movieSamples[index] = track.samples.count;
                var samples = track.samples;
                track.nextFragmentDts = samples.count == 0 ? 0
                        : samples.dts[samples.count - 1] + Integer.toUnsignedLong(samples.durations[samples.count - 1]);
            }
            // the compressed boxes are added to the map in file order, each fragment parsed after the boxes before it
            var indexes = scan.compressedIndexes;
            var nextIndex = 0;
            var movieAdded = !compressedMovie;
            for (var index = 0; index <= scan.fragments.size(); index++) {
                var fragment = index < scan.fragments.size() ? scan.fragments.get(index) : null;
                var position = fragment != null ? fragment[0] : Long.MAX_VALUE;
                while (true) {
                    var indexAt = nextIndex < indexes.size() ? indexes.get(nextIndex)[0] : Long.MAX_VALUE;
                    var movieAt = movieAdded ? Long.MAX_VALUE : scan.compressedMoov[0];
                    var next = Math.min(indexAt, movieAt);
                    if (next == Long.MAX_VALUE || next > position) {
                        break;
                    }
                    if (movieAt < indexAt) {
                        compressed().add(movieAt, scan.compressedMoov[2], CompressedBoxes.boxSize(moov.length));
                        movieAdded = true;
                    } else {
                        addCompressedIndex(indexes.get(nextIndex++));
                    }
                }
                if (fragment != null) {
                    parseFragment(fragment, scan.fragmentPayloads.set(index, null), parser.tracks);
                }
            }
            if (compression != null) {
                for (var index = 0; index < movieSamples.length; index++) {
                    compression.relocate(parser.tracks.get(index).samples, 0, movieSamples[index]);
                }
            }
            var lazy = forwardOnly && parser.fragmented && scan.next >= 0;
            if (lazy && parser.tracks.stream().anyMatch(track -> !track.hasSimpleEdits())) {
                indexFragments(scan.next, parser.tracks);
                lazy = false;
            }
            this.fragmentScan = lazy ? scan.next : -1;
            this.fragmentTracks = lazy ? parser.tracks : null;
            var publicTracks = new ArrayList<Track>();
            var activeTracks = new ArrayList<Mp4Track>();
            var gaplessUsed = false;
            var chapterIds = new HashSet<Integer>();
            for (var track : parser.tracks) {
                if (track.chapterTracks != null) {
                    for (var id : track.chapterTracks) {
                        chapterIds.add(id);
                    }
                }
            }
            var webVtt = new HashSet<Integer>();
            var quickTimeText = new HashSet<Integer>();
            var captions = new HashSet<Integer>();
            mergeLayerConfigs(parser.tracks);
            for (var track : parser.tracks) {
                track.selectBaseEntry();
                if (track.entry != null && (track.entry.codec().isSubtitle() || track.entry.codec() == Codec.TTML)
                        && chapterIds.contains(track.fileId)) {
                    track.entry = track.entry.withCodec(Codec.DATA);
                }
                refineMpegAudio(track);
                var audio = track.kind() == TrackKind.AUDIO;
                track.resolveTiming(parser.movieTimescale, audio && !gaplessUsed ? parser.gapless : null, limits);
                gaplessUsed |= audio;
                if (lazy && track.durationFromSamples) {
                    track.duration = -1;
                }
                var described = track.toTrack();
                if (described instanceof AudioTrack sound && AacExtensions.mayRefine(sound)) {
                    described = AacExtensions.refine(sound, firstSample(track));
                }
                track.publicConfig = described.codecConfig().orElse(null);
                publicTracks.add(described);
                if (described.codec() == Codec.WEBVTT) {
                    webVtt.add(described.id());
                }
                if (described.codec() == Codec.MOV_TEXT && track.entry.format() == SampleEntry.TEXT) {
                    quickTimeText.add(described.id());
                }
                if (described.codec() == Codec.CEA_608 || described.codec() == Codec.CEA_708) {
                    captions.add(described.id());
                }
                if (track.samples.count > 0 || (lazy && track.entry != null)) {
                    activeTracks.add(track);
                }
            }
            this.tracks = Collections.unmodifiableList(publicTracks);
            this.webVttTracks = Set.copyOf(webVtt);
            this.quickTimeTextTracks = Set.copyOf(quickTimeText);
            this.captionTracks = Set.copyOf(captions);
            this.metadata = Collections.unmodifiableMap(buildMetadata(scan.fileType, parser.metadata));
            this.durationMicros = computeDuration(parser, !scan.fragments.isEmpty());
            this.active = activeTracks.toArray(new Mp4Track[0]);
            this.dataEnd = dataEnd(active);
            this.cursors = new int[active.length];
            this.heap = new int[active.length];
            this.descriptions = new int[active.length];
            this.movieTimescale = parser.movieTimescale;
            this.allTracks = parser.tracks.toArray(new Mp4Track[0]);
            for (var track : allTracks) {
                if (isLayered(track)) {
                    for (var id : track.scalableTracks) {
                        var referenced = trackOf(id);
                        if (referenced != null && referenced != track) {
                            referencedSamples.computeIfAbsent(referenced, ignored -> new LinkedHashMap<>());
                        }
                    }
                }
            }
            var annotations = parser.movieMetadata.annotations;
            if (!parser.movieMetadata.neroChapters) {
                readChapterTrack(annotations);
                this.chapters = annotations.chapters(durationMicros);
            } else {
                this.chapters = completeNeroChapters(annotations.chapters(durationMicros));
            }
            this.pictures = annotations.pictures();
            var tracked = readTimecode();
            this.timecode = tracked != null ? tracked : TimecodeTags.parse(TimecodeTags.lookup(metadata,
                    "timecode"), TimecodeTags.firstVideo(tracks));
            this.mixedSources = Arrays.stream(active).anyMatch(Mp4Demuxer::hasMixedSources);
            rewind();
        } catch (RuntimeException exception) {
            try {
                external.close();
            } catch (RuntimeException closing) {
                exception.addSuppressed(closing);
            }
            if (exception instanceof MediaException failure) {
                throw failure;
            }
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Rejects protected tracks when no key can decrypt them, and marks the tracks whose samples are decrypted.
     *
     * @param tracks every track of the movie
     * @throws MediaException.Unsupported if a track is protected and the keys are {@link DecryptionKeys#none()}
     */
    private void checkKeys(List<Mp4Track> tracks) {
        for (var track : tracks) {
            var protection = track.protection();
            if (protection == null) {
                continue;
            }
            if (keys.equals(DecryptionKeys.none())) {
                var kind = track.entry != null ? track.entry.kind() : TrackKind.DATA;
                throw new MediaException.Unsupported("mp4 encryption", "the " + kind.name().toLowerCase(Locale.ROOT)
                        + " track " + track.fileId + " is encrypted ('" + FourCc.printable(protection.scheme().fourCc())
                        + "') and no decryption key was given");
            }
            track.encrypted = true;
        }
    }

    /**
     * Decrypts a protected sample in place, as its sample description and its auxiliary information describe.
     *
     * @param track the sample's track
     * @param index the index of the sample in the track's samples
     * @param data the array holding the sample
     * @param offset the index of its first byte
     * @param length its size in bytes
     * @throws MediaException.Malformed if the sample's initialization vector or subsample map is missing or does
     * not fit the sample
     * @throws MediaException.Unsupported if the keys do not know the sample's key
     */
    private void decrypt(Mp4Track track, int index, byte[] data, int offset, int length) {
        var samples = track.samples;
        var description = samples.description(index);
        var entry = description < track.entries.length ? track.entries[description] : null;
        if (entry == null || entry.protection() == null) {
            return;
        }
        var protection = entry.protection();
        var cenc = track.cenc;
        var record = samples.crypto(index);
        var params = cenc == null ? protection.defaults() : cenc.params(record, protection.defaults());
        if (params == null) {
            throw new MediaException.Malformed("protected sample " + index + " of track " + track.fileId
                    + " has neither track defaults (tenc) nor a sample encryption group (seig)");
        }
        if (!params.isProtected()) {
            return;
        }
        if (params.multiKey()) {
            decryptMultiKey(track, index, protection.scheme(), params, record, data, offset, length);
            return;
        }
        byte[] iv;
        int ivOffset;
        int ivLength;
        if (params.ivSize() > 0) {
            if (record < 0 || !cenc.hasInfo[record] || cenc.ivLength[record] != params.ivSize()) {
                throw new MediaException.Malformed("protected sample " + index + " of track " + track.fileId
                        + " has no initialization vector");
            }
            iv = cenc.ivs;
            ivOffset = cenc.ivOffset[record];
            ivLength = params.ivSize();
        } else if (params.constantIv() != null) {
            iv = params.constantIv();
            ivOffset = 0;
            ivLength = iv.length;
        } else {
            throw new MediaException.Malformed("protected track " + track.fileId + " declares no initialization vector");
        }
        var hasSubsamples = record >= 0 && cenc.hasInfo[record] && cenc.subsampleCount[record] > 0;
        cipher(params.keyId()).decrypt(protection.scheme(), data, offset, length, iv, ivOffset, ivLength,
                hasSubsamples ? cenc.subsamples : null, hasSubsamples ? cenc.subsampleOffset[record] : 0,
                hasSubsamples ? cenc.subsampleCount[record] : 0, params.crypt(), params.skip());
    }

    /**
     * Decrypts a sample protected with several keys (ISO/IEC 23001-7:2023): each key decrypts the protected bytes
     * of the subsamples that name it, as one sample whose other subsamples are clear, from its own initialization
     * vector (the sample's vector for that key, or the key's constant one).
     */
    private void decryptMultiKey(Mp4Track track, int index, CencScheme scheme, CencParams params, int record,
                                 byte[] data, int offset, int length) {
        var cenc = track.cenc;
        if (record < 0 || !cenc.hasInfo[record] || cenc.multiIvCount[record] < 0) {
            throw new MediaException.Malformed("multi-key sample " + index + " of track " + track.fileId
                    + " has no multi-key auxiliary information");
        }
        var keys = params.keys();
        var pairs = cenc.subsampleCount[record];
        var first = cenc.subsampleOffset[record];
        if (pairs == 0) {
            throw new MediaException.Malformed("multi-key sample " + index + " of track " + track.fileId
                    + " has no subsample map");
        }
        if (multiKeyPairs.length < 2 * pairs) {
            multiKeyPairs = new int[2 * pairs];
        }
        for (var key = 1; key <= keys.size(); key++) {
            var used = false;
            for (var pair = 0; pair < pairs; pair++) {
                var clear = cenc.subsamples[first + 2 * pair];
                var protectedBytes = cenc.subsamples[first + 2 * pair + 1];
                var own = cenc.subsampleKeys[first / 2 + pair] == key;
                used |= own && protectedBytes != 0;
                multiKeyPairs[2 * pair] = own ? clear : clear + protectedBytes;
                multiKeyPairs[2 * pair + 1] = own ? protectedBytes : 0;
            }
            if (!used) {
                continue;
            }
            var description = keys.get(key - 1);
            byte[] iv = null;
            var ivOffset = 0;
            var ivLength = 0;
            var position = cenc.ivOffset[record];
            for (var vector = 0; vector < cenc.multiIvCount[record]; vector++) {
                var named = (cenc.ivs[position] & 0xFF) << 8 | cenc.ivs[position + 1] & 0xFF;
                var size = keys.get(named - 1).ivSize();
                if (named == key) {
                    iv = cenc.ivs;
                    ivOffset = position + 2;
                    ivLength = size;
                }
                position += 2 + size;
            }
            if (iv == null || ivLength == 0) {
                if (description.constantIv() == null) {
                    throw new MediaException.Malformed("multi-key sample " + index + " of track " + track.fileId
                            + " has no initialization vector for key " + key);
                }
                iv = description.constantIv();
                ivOffset = 0;
                ivLength = iv.length;
            }
            cipher(description.keyId()).decrypt(scheme, data, offset, length, iv, ivOffset, ivLength,
                    multiKeyPairs, 0, pairs, params.crypt(), params.skip());
        }
    }

    /**
     * Returns the cipher of a key identifier, asking the keys for it the first time.
     *
     * @param keyId the key identifier
     * @return the cipher
     * @throws MediaException.Unsupported if the keys do not know the key
     */
    private CencCipher cipher(byte[] keyId) {
        var cipher = ciphers.get(keyId);
        if (cipher != null) {
            return cipher;
        }
        var id = ByteBuffer.wrap(keyId);
        cipher = ciphersById.get(id);
        if (cipher == null) {
            var key = keys.find(keyId.clone()).orElseThrow(() -> new MediaException.Unsupported("mp4 encryption",
                    "no decryption key for key ID " + HexFormat.of().formatHex(keyId)));
            if (key.length != 16) {
                throw new MediaException.Unsupported("mp4 encryption", "the key of key ID "
                        + HexFormat.of().formatHex(keyId) + " is " + key.length + " bytes, not 16");
            }
            cipher = new CencCipher(key);
            ciphersById.put(id, cipher);
        }
        ciphers.put(keyId, cipher);
        return cipher;
    }

    /**
     * Returns whether a range of the file lies inside a box payload held in memory.
     *
     * @param payload the box payload held in memory
     * @param payloadStart the offset of the payload in the file
     * @param offset the offset of the range
     * @param length the length of the range
     * @return {@code true} when the payload holds the whole range
     */
    private static boolean inPayload(byte[] payload, long payloadStart, long offset, int length) {
        return offset >= payloadStart && offset - payloadStart + length <= payload.length;
    }

    /**
     * Reads sample auxiliary information from the source.
     *
     * @param offset the absolute offset
     * @param length the number of bytes
     * @return the bytes
     * @throws MediaException.Malformed if they lie outside the file
     * @throws MediaException.LimitExceeded if they exceed the allocation limit
     */
    private byte[] readAuxiliary(long offset, int length) {
        LimitChecks.checkAllocation(limits, length);
        if (offset < 0 || (sourceSize >= 0 && offset + length > sourceSize)) {
            throw new MediaException.Malformed("sample auxiliary information at offset " + offset
                    + " runs past the end of the file");
        }
        var bytes = new byte[length];
        source.readFully(offset, bytes, 0, length);
        return bytes;
    }

    /**
     * Closes the files the data references of the movie name; the source of the movie itself is not closed.
     */
    @Override
    public void close() {
        external.close();
    }

    /**
     * Returns the source holding a sample, when it is not the file: a file a data reference names.
     *
     * @param track the track of the sample
     * @param index the index of the sample in {@code track.samples}
     * @return the source, or {@code null} for a sample in the file
     */
    private static MediaSource sourceOf(Mp4Track track, int index) {
        var sources = track.dataSources;
        if (sources == null) {
            return null;
        }
        var description = track.samples.description(index);
        return description < sources.length ? sources[description] : null;
    }

    /**
     * Returns whether a track has samples both in the file and in another one.
     *
     * @param track the track to check
     * @return whether its sources are mixed
     */
    private static boolean hasMixedSources(Mp4Track track) {
        if (track.dataSources == null) {
            return false;
        }
        var inFile = false;
        var outside = false;
        for (var index = 0; index < track.samples.count; index++) {
            if (sourceOf(track, index) == null) {
                inFile = true;
            } else {
                outside = true;
            }
        }
        return inFile && outside;
    }

    /**
     * Reads a sample from a file a data reference names.
     *
     * @param from the file
     * @param offset the offset of the sample in it
     * @param size the size of the sample
     * @return the sample
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private byte[] readExternal(MediaSource from, long offset, int size) {
        checkExternal(from, offset, size);
        var data = new byte[size];
        from.readFully(offset, data, 0, size);
        return data;
    }

    /**
     * Checks that a sample of a file a data reference names lies inside it and fits the allocation limit.
     *
     * @param from the file
     * @param offset the offset of the sample in it
     * @param size the size of the sample
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private void checkExternal(MediaSource from, long offset, int size) {
        LimitChecks.checkAllocation(limits, size);
        var length = from.size();
        if (length >= 0 && (offset > length || size > length - offset)) {
            throw new MediaException.Malformed("sample of " + size + " bytes at offset " + offset
                    + " runs past the end of the " + length + " byte referenced file");
        }
    }

    /**
     * Turns the samples of the first QuickTime chapter track into chapters: each sample is a
     * 16-bit big-endian length followed by the title in UTF-8, or in UTF-16 after a byte order
     * mark, and spans the sample's presentation time.
     *
     * @param annotations receives the chapters
     */
    private void readChapterTrack(Annotations annotations) {
        for (var track : allTracks) {
            if (track.chapterTracks == null) {
                continue;
            }
            for (var id : track.chapterTracks) {
                for (var candidate : allTracks) {
                    if (candidate.fileId != id || candidate.kind() != TrackKind.DATA || candidate.samples.count == 0) {
                        continue;
                    }
                    var samples = candidate.samples;
                    var timeBase = new Rational(1, candidate.timescale);
                    for (var index = 0; index < samples.count && index < 4096; index++) {
                        var size = samples.sizes[index];
                        if (size < 2 || size > 64 * 1024) {
                            continue;
                        }
                        var bytes = new byte[size];
                        var from = Objects.requireNonNullElse(sourceOf(candidate, index), source);
                        if (SourceBytes.readAvailable(from, samples.offsets[index], bytes, 0, bytes.length) < size) {
                            continue;
                        }
                        var length = Math.min(size - 2, ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
                        String title;
                        if (length >= 2 && (bytes[2] & 0xFF) == 0xFE && (bytes[3] & 0xFF) == 0xFF) {
                            title = new String(bytes, 4, length - 2, StandardCharsets.UTF_16BE);
                        } else if (length >= 2 && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0xFE) {
                            title = new String(bytes, 4, length - 2, StandardCharsets.UTF_16LE);
                        } else {
                            title = new String(bytes, 2, length, StandardCharsets.UTF_8);
                        }
                        var start = timeBase.toMicros(Math.max(0, samples.pts(index) + candidate.shift));
                        var end = start + timeBase.toMicros(Integer.toUnsignedLong(samples.durations[index]));
                        annotations.addChapter(start, end, title);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Returns the chapters of a Nero {@code chpl} box, or those of the QuickTime chapter track when they complete
     * chapters or titles the box had to cut ({@link Mp4ChapterTrack#supersedes(List, List)}).
     *
     * @param nero the chapters of the {@code chpl} box
     * @return the chapters to report
     */
    private List<Chapter> completeNeroChapters(List<Chapter> nero) {
        if (!Mp4ChapterTrack.mayBeCut(nero)) {
            return nero;
        }
        var track = new Annotations();
        readChapterTrack(track);
        var chapters = track.chapters(durationMicros);
        return Mp4ChapterTrack.supersedes(chapters, nero) ? chapters : nero;
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    @Override
    public Optional<Timecode> timecode() {
        return Optional.ofNullable(timecode);
    }

    /**
     * Reads the timecode of the first QuickTime timecode track: the frame number of its first sample, counted as
     * its {@code tmcd} sample entry describes.
     *
     * @return the timecode, or {@code null} when there is no timecode track with a readable first sample
     */
    private Timecode readTimecode() {
        for (var track : allTracks) {
            if (track.handler != BoxTypes.TMCD || track.entries == null || track.entries.length == 0
                    || track.entries[0] == null || track.entries[0].format() != BoxTypes.TMCD
                    || track.samples.count == 0) {
                continue;
            }
            var size = track.samples.sizes[0];
            if (size < 4 || size > 64) {
                continue;
            }
            var sample = new byte[size];
            var from = Objects.requireNonNullElse(sourceOf(track, 0), source);
            if (SourceBytes.readAvailable(from, track.samples.offsets[0], sample, 0, size) < size) {
                continue;
            }
            var timecode = TimecodeTrack.read(track.entries[0].config(), sample);
            if (timecode != null) {
                return timecode;
            }
        }
        return null;
    }

    /**
     * Returns whether the packets of a track are converted from the stored samples, so that their sizes differ
     * from the sample table's: the samples of QuickTime text tracks, returned as 3GPP timed text.
     *
     * @param trackId the identifier of the track
     * @return {@code true} for a QuickTime text track
     */
    boolean convertsSamples(int trackId) {
        return quickTimeTextTracks.contains(trackId) || captionTracks.contains(trackId);
    }

    /**
     * Returns the number of samples of a track, as the sample tables and fragment runs index
     * them, without reading the samples: each media sample once, whatever its edit list presents.
     *
     * @param trackId the identifier of the track
     * @return the sample count, or {@code -1} for an unknown track
     */
    public long sampleCount(int trackId) {
        loadAllFragments();
        for (var track : allTracks) {
            if (track.id == trackId) {
                return track.media.count;
            }
        }
        return -1;
    }

    /**
     * Returns the total size of the samples of a track, as the sample tables and fragment runs
     * index them, without reading the samples.
     *
     * @param trackId the identifier of the track
     * @return the size in bytes, or {@code -1} for an unknown track
     */
    public long sampleBytes(int trackId) {
        loadAllFragments();
        for (var track : allTracks) {
            if (track.id == trackId) {
                var total = 0L;
                for (var index = 0; index < track.media.count; index++) {
                    total += Math.max(0, track.media.sizes[index]);
                }
                return total;
            }
        }
        return -1;
    }

    /**
     * Returns the map of the compressed boxes, creating it when the first one is met.
     *
     * @return the map
     */
    private CompressedBoxes compressed() {
        if (compression == null) {
            compression = new CompressedBoxes();
        }
        return compression;
    }

    /**
     * Adds a compressed segment index box ({@code !six}, {@code !ssx}) to the map of the compressed boxes;
     * the demuxer ignores segment indexes, but the size of their uncompressed forms moves the offsets after
     * them. A box that runs past the end of the file is left out, as no offset lies after it.
     *
     * @param box the offset, header size, size and type of the box
     * @throws MediaException.Malformed if the box is damaged or is not a gzip stream
     */
    private void addCompressedIndex(long[] box) {
        if (sourceSize >= 0 && box[0] + box[2] > sourceSize) {
            return;
        }
        var name = (int) box[3] == BoxTypes.COMPRESSED_SIDX ? "!six" : "!ssx";
        var payload = CompressedBoxes.inflate(readBox(box[0], (int) box[1], box[2], name), name, limits);
        compressed().add(box[0], box[2], CompressedBoxes.boxSize(payload.length));
    }

    /**
     * Indexes the samples of a movie fragment box, {@code moof} or its compressed form {@code !mof}.
     *
     * <p>The offsets of a fragment count in the file with every compressed box uncompressed, so once
     * a compressed box has been met the fragment is parsed at its offset in that file and the offsets
     * of its samples are mapped back into the file.
     *
     * @param box the offset, header size, size and type of the box
     * @param payload the prefetched payload of the box, or {@code null} to read it
     * @param tracks every track of the movie
     * @throws MediaException.Malformed if the box is corrupt or a compressed one is not a gzip stream
     * @throws MediaException.LimitExceeded if the box exceeds the byte limit
     */
    private void parseFragment(long[] box, byte[] payload, List<Mp4Track> tracks) {
        var position = box[0];
        var packed = (int) box[3] == BoxTypes.COMPRESSED_MOOF;
        var name = packed ? "!mof" : "moof";
        var data = payload != null ? payload : readBox(position, (int) box[1], box[2], name);
        if (!packed && compression == null) {
            var payloadStart = position + box[1];
            var held = data;
            FragmentParser.parse(BoxCursor.of(data, BoxTypes.MOOF), position, tracks,
                    (offset, length) -> inPayload(held, payloadStart, offset, length) ? Arrays.copyOfRange(held,
                            (int) (offset - payloadStart), (int) (offset - payloadStart) + length)
                            : readAuxiliary(offset, length));
            return;
        }
        if (packed) {
            data = CompressedBoxes.inflate(data, name, limits);
        }
        var map = compressed();
        var start = map.uncompressed(position);
        if (packed) {
            map.add(position, box[2], CompressedBoxes.boxSize(data.length));
        }
        var counts = new int[tracks.size()];
        for (var index = 0; index < counts.length; index++) {
            counts[index] = tracks.get(index).samples.count;
        }
        var inflated = data;
        var inflatedStart = start + CompressedBoxes.boxSize(data.length) - data.length;
        FragmentParser.parse(BoxCursor.of(data, BoxTypes.MOOF), start, tracks, (offset, length) -> {
            if (!inPayload(inflated, inflatedStart, offset, length)) {
                throw new MediaException.Malformed("sample auxiliary information outside a compressed movie fragment");
            }
            var from = (int) (offset - inflatedStart);
            return Arrays.copyOfRange(inflated, from, from + length);
        });
        for (var index = 0; index < counts.length; index++) {
            var samples = tracks.get(index).samples;
            map.relocate(samples, counts[index], samples.count);
        }
    }

    /**
     * Handles a top-level box of a forward-only source that the top-level scan stopped before: a movie
     * fragment is indexed and a compressed segment index added to the map of the compressed boxes.
     *
     * @param position the offset of the box
     * @param box the header size, the size and the type of the box, as {@link TopLevelScan#readHeader} reads them
     * @param tracks every track of the movie
     */
    private void walkLater(long position, long[] box, List<Mp4Track> tracks) {
        var type = (int) box[2];
        var entry = new long[]{position, box[0], box[1], type};
        if (type == BoxTypes.MOOF || type == BoxTypes.COMPRESSED_MOOF) {
            parseFragment(entry, null, tracks);
        } else if (type == BoxTypes.COMPRESSED_SIDX || type == BoxTypes.COMPRESSED_SSIX) {
            addCompressedIndex(entry);
        }
    }

    /**
     * Reads the payload of a top-level box into memory.
     *
     * @param offset the absolute offset of the box
     * @param headerSize the size of the box header
     * @param size the total size of the box
     * @param name the box name, for diagnostics
     * @return the payload
     */
    private byte[] readBox(long offset, int headerSize, long size, String name) {
        var length = size - headerSize;
        LimitChecks.checkAllocation(limits, length);
        if (sourceSize >= 0 && offset + size > sourceSize) {
            throw new MediaException.Malformed(name + " box at offset " + offset + " runs past the end of the file");
        }
        var payload = new byte[(int) length];
        source.readFully(offset + headerSize, payload, 0, payload.length);
        return payload;
    }

    /**
     * Distinguishes MP3 from MP2 in an MPEG audio track whose sample description does not say
     * which layer it carries, by reading the header of the first frame.
     *
     * @param track the track to refine
     */
    private void refineMpegAudio(Mp4Track track) {
        if (track.entry.codec() != Codec.MP3 || track.samples.count == 0 || track.samples.sizes[0] < 4) {
            return;
        }
        var header = new byte[4];
        var from = Objects.requireNonNullElse(sourceOf(track, 0), source);
        if (track.encrypted) {
            header = firstSample(track);
            if (header == null || header.length < 4) {
                return;
            }
        } else if (SourceBytes.readAvailable(from, track.samples.offsets[0], header, 0, header.length) < 4) {
            return;
        }
        var parsed = AudioConfigs.parseMpegHeader(header, 0, 4);
        if (parsed != null && parsed.layer() != 3) {
            track.entry = track.entry.withCodec(Codec.MP2);
        }
    }

    /**
     * Reads the first sample of a track for {@link AacExtensions#refine}.
     *
     * @param track the track whose first sample is read
     * @return the sample, or {@code null} when the track has none or it cannot be read whole
     */
    private byte[] firstSample(Mp4Track track) {
        if (track.samples.count == 0) {
            return null;
        }
        var size = track.samples.sizes[0];
        if (size <= 0 || size > Math.min(limits.maxBytes(), MAX_AAC_SAMPLE)) {
            return null;
        }
        var sample = new byte[size];
        var from = Objects.requireNonNullElse(sourceOf(track, 0), source);
        if (SourceBytes.readAvailable(from, track.samples.offsets[0], sample, 0, sample.length) < size) {
            return null;
        }
        if (track.encrypted) {
            try {
                decrypt(track, 0, sample, 0, size);
            } catch (MediaException exception) {
                return null;
            }
        }
        return sample;
    }

    /**
     * Combines the file type brands and the movie metadata.
     *
     * @param fileType the {@code ftyp} payload, or {@code null}
     * @param movie the movie metadata
     * @return the tags, brands first
     */
    private static Map<String, String> buildMetadata(byte[] fileType, Map<String, String> movie) {
        var result = new LinkedHashMap<String, String>();
        if (fileType != null && fileType.length >= 8) {
            result.put("major_brand", FourCc.printable(ByteArrays.i32be(fileType, 0)));
            result.put("minor_version", Long.toString(ByteArrays.i32be(fileType, 4) & 0xFFFFFFFFL));
            var brands = new StringBuilder();
            for (var index = 8; index + 4 <= fileType.length; index += 4) {
                brands.append(FourCc.printable(ByteArrays.i32be(fileType, index)));
            }
            result.put("compatible_brands", brands.toString());
        }
        result.putAll(movie);
        return result;
    }

    /**
     * Computes the presentation duration.
     *
     * @param parser the parsed movie
     * @param fragmented whether the file has movie fragments
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long computeDuration(MovieParser parser, boolean fragmented) {
        if (parser.movieTimescale > 0) {
            if (!fragmented && parser.movieDuration > 0) {
                return Mp4Track.rescale(parser.movieDuration, parser.movieTimescale, 1_000_000);
            }
            if (fragmented && parser.fragmentDuration > 0) {
                return Mp4Track.rescale(parser.fragmentDuration, parser.movieTimescale, 1_000_000);
            }
        }
        var longest = -1L;
        for (var track : parser.tracks) {
            if (track.duration >= 0 && track.kind() != TrackKind.DATA) {
                longest = Math.max(longest, Mp4Track.rescale(track.duration, track.timescale, 1_000_000));
            }
        }
        return longest;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MP4;
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
     * <p>The tags of a track are the {@code name} of its {@code udta}, under {@code "title"}, and the tags of the
     * {@code meta} box and text atoms of that {@code udta}.
     */
    @Override
    public Map<String, String> trackMetadata(int trackId) {
        var track = publicTrack(trackId);
        if (track == null || track.name == null && track.userTags.isEmpty()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, String>();
        if (track.name != null) {
            result.put(StandardTag.TITLE.key(), track.name);
        }
        track.userTags.forEach(result::putIfAbsent);
        return Collections.unmodifiableMap(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A track is {@link TrackDisposition#DEFAULT} when its {@code tkhd} flags it as enabled; its other
     * dispositions are those of the {@code kind} boxes of its {@code udta} in the DASH role scheme.
     */
    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        var track = publicTrack(trackId);
        if (track == null) {
            return Set.of();
        }
        var result = EnumSet.noneOf(TrackDisposition.class);
        result.addAll(track.kinds);
        if (track.enabled) {
            result.add(TrackDisposition.DEFAULT);
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Returns the track with a public identifier.
     *
     * @param trackId the identifier the public track carries
     * @return the track, or {@code null} when the file has none
     */
    private Mp4Track publicTrack(int trackId) {
        for (var track : allTracks) {
            if (track.id == trackId) {
                return track;
            }
        }
        return null;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    /**
     * Returns whether the file stores its {@code moov} before the first {@code mdat}, so that a
     * progressive download can start playing before the whole file arrived.
     *
     * @return {@code true} for a fast-start file
     */
    public boolean isFastStart() {
        return fastStart;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}: every sample of every track, fragments included, is indexed
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each track restarts independently at the last sync sample ({@code stss}, or a fragment
     * sample without the non-sync flag) whose presentation time, edit list applied, is at or
     * before {@code micros}; a track whose every sample ends at or before {@code micros} returns
     * nothing more. The samples of all tracks are then returned in file-offset order again.
     *
     * <p>Over a source that reads its input once, a seek whose restart samples lie in bytes the
     * source no longer holds is refused with {@link MediaException.Unsupported}, and the demuxer
     * stays where it was.
     *
     * @implNote This implementation seeks in the sample index built at construction, which holds
     * the samples of every movie fragment as well, so the fragment indexes {@code tfra} and
     * {@code sidx} are never needed.
     */
    @Override
    public void seek(long micros) {
        try {
            loadAllFragments();
            var starts = new int[active.length];
            for (var index = 0; index < active.length; index++) {
                starts[index] = startSample(active[index], micros);
            }
            if (forwardOnly && !mixedSources) {
                var lowest = Long.MAX_VALUE;
                for (var index = 0; index < active.length; index++) {
                    var track = active[index];
                    if (starts[index] < track.samples.count && sourceOf(track, starts[index]) == null) {
                        lowest = Math.min(lowest, track.samples.offsets[starts[index]]);
                    }
                }
                if (lowest < released && !isHeld(lowest)) {
                    throw new MediaException.Unsupported("seek", "seeking to " + micros + " us needs the bytes at"
                            + " offset " + lowest + ", which a source read once no longer holds");
                }
            }
            pendingCues.clear();
            System.arraycopy(starts, 0, cursors, 0, starts.length);
            resetDescriptions();
            heapSize = 0;
            for (var index = 0; index < active.length; index++) {
                if (cursors[index] < active[index].samples.count) {
                    heapPush(index);
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Returns whether a source read once still holds the bytes from an offset on, which it may keep after the
     * demux let them go; the bytes it holds are contiguous up to the last one read.
     *
     * @param offset the offset of the first byte needed
     * @return {@code true} when the byte at {@code offset} can be read again
     */
    private boolean isHeld(long offset) {
        try {
            return source.read(offset, new byte[1], 0, 1) == 1;
        } catch (MediaException.Unsupported released) {
            return false;
        }
    }

    /**
     * Finds the sample a track restarts at for a seek.
     *
     * @param track the track searched
     * @param micros the seek target in microseconds
     * @return the sample index, or the sample count when the track ends at or before the target
     */
    private static int startSample(Mp4Track track, long micros) {
        var samples = track.samples;
        var target = SeekTimes.floor(micros, new Rational(1, track.timescale));
        var end = Long.MIN_VALUE;
        for (var index = 0; index < samples.count; index++) {
            end = Math.max(end, samples.pts(index) + track.shift + Integer.toUnsignedLong(samples.durations[index]));
        }
        if (end <= target) {
            return samples.count;
        }
        var low = 0;
        var high = samples.count;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (samples.dts[middle] + track.shift - track.compositionShift > target) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        for (var index = low - 1; index >= 0; index--) {
            if (samples.isKeyframe(index) && samples.pts(index) + track.shift <= target) {
                return index;
            }
        }
        for (var index = 0; index < samples.count; index++) {
            if (samples.isKeyframe(index)) {
                return index;
            }
        }
        return 0;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                var pending = pendingCues.pollFirst();
                if (pending != null) {
                    return pending;
                }
                var packet = nextPacket();
                if (packet != null && !quickTimeTextTracks.isEmpty() && quickTimeTextTracks.contains(packet.trackId())) {
                    return packet.withData(QuickTimeText.sample(packet.data()));
                }
                if (packet != null && !captionTracks.isEmpty() && captionTracks.contains(packet.trackId())) {
                    return packet.withData(QuickTimeCaptions.toCcData(packet.data()));
                }
                if (packet == null || webVttTracks.isEmpty() || !webVttTracks.contains(packet.trackId())) {
                    return packet;
                }
                for (var cue : WebVttSamples.decode(packet.data())) {
                    var sideData = WebVttSamples.sideData(PacketSideData.NONE, cue.identifier(), cue.settings());
                    pendingCues.addLast(new Packet(packet.trackId(), cue.payload().getBytes(StandardCharsets.UTF_8),
                            packet.pts(), packet.dts(), packet.duration(), KEY_FLAGS, sideData));
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Returns the sample with the lowest file offset among the next samples of every track.
     *
     * @return the packet, or {@code null} when every sample was returned
     */
    private Packet nextPacket() {
        loadFragments();
        if (heapSize == 0) {
            return null;
        }
        var trackIndex = heap[0];
        var index = advance();
        var track = active[trackIndex];
        var samples = track.samples;
        var elsewhere = sourceOf(track, index);
        var data = elsewhere != null ? readExternal(elsewhere, samples.offsets[index], samples.sizes[index])
                : readSample(samples.offsets[index], samples.sizes[index]);
        if (track.encrypted) {
            decrypt(track, index, data, 0, data.length);
        }
        var held = referencedSamples.get(track);
        if (held != null) {
            held.put(index, data);
            if (held.size() > REFERENCED_SAMPLES) {
                held.pollFirstEntry();
            }
        }
        if (isLayered(track)) {
            reconstructed.clear();
            reconstructed.add(track);
            data = reconstruct(track, samples.dts[index], data, 0);
        }
        releaseConsumed();
        var decode = samples.dts[index] + track.shift;
        var pts = decode + samples.ctsOffsets[index];
        var dts = decode - track.compositionShift;
        var duration = Integer.toUnsignedLong(samples.durations[index]);
        var config = configChange(trackIndex, index);
        var trim = track.trimOf(index);
        if (config == null && trim < 0 && !samples.isDiscarded(index)) {
            return new Packet(track.id, data, pts, dts, duration, samples.isKeyframe(index));
        }
        var flags = samples.isKeyframe(index) ? samples.isDiscarded(index) ? KEY_DISCARD_FLAGS : KEY_FLAGS
                : samples.isDiscarded(index) ? DISCARD_FLAGS : Set.<PacketFlag>of();
        var sideData = PacketSideData.builder().newCodecConfig(config);
        if (trim >= 0) {
            sideData.discard(track.trimAmounts[trim * 2], track.trimAmounts[trim * 2 + 1]);
        }
        return new Packet(track.id, data, pts, dts, duration, flags, sideData.build());
    }

    /**
     * Returns whether a track is an H.264 track that extends the samples of the tracks a {@code tref/scal}
     * box names: a scalable or multiview layer stored in a track of its own (ISO/IEC 14496-15 clauses 7
     * and 8).
     *
     * @param track the track to classify
     * @return {@code true} when its samples are reconstructed into whole access units
     */
    private static boolean isLayered(Mp4Track track) {
        return track.scalableTracks != null && track.scalableTracks.length > 0 && track.entry != null
                && track.entry.codec() == Codec.H264;
    }

    /**
     * Returns the track with a file track identifier.
     *
     * @param fileId the {@code track_ID}
     * @return the track, or {@code null} when the file has none
     */
    private Mp4Track trackOf(int fileId) {
        for (var track : allTracks) {
            if (track.fileId == fileId) {
                return track;
            }
        }
        return null;
    }

    /**
     * Gives every sample description of the layered H.264 tracks the parameter sets of the tracks they
     * extend, directly or through other layered tracks, so that the configuration of a layered track
     * decodes its reconstructed access units on its own.
     *
     * @param tracks every track of the movie
     */
    private static void mergeLayerConfigs(List<Mp4Track> tracks) {
        for (var track : tracks) {
            if (track.scalableTracks == null || track.entries == null) {
                continue;
            }
            var configs = new ArrayList<byte[]>();
            collectLayerConfigs(track, tracks, configs, new HashSet<>());
            if (configs.isEmpty()) {
                continue;
            }
            for (var index = 0; index < track.entries.length; index++) {
                var entry = track.entries[index];
                if (entry == null || entry.codec() != Codec.H264) {
                    continue;
                }
                var extensions = new ArrayList<>(configs.subList(1, configs.size()));
                extensions.add(entry.config());
                track.entries[index] = entry.withConfig(NalRecords.mergeAvc(configs.getFirst(), extensions));
            }
        }
    }

    /**
     * Adds the H.264 configurations of the tracks a track references through {@code tref/scal} to a list,
     * the tracks they reference first, each track once.
     *
     * @param track the referencing track
     * @param tracks every track of the movie
     * @param configs receives the configurations
     * @param visited the file identifiers of the tracks already added or being added
     */
    private static void collectLayerConfigs(Mp4Track track, List<Mp4Track> tracks, List<byte[]> configs,
                                            Set<Integer> visited) {
        visited.add(track.fileId);
        for (var id : track.scalableTracks) {
            if (visited.contains(id)) {
                continue;
            }
            for (var referenced : tracks) {
                if (referenced.fileId != id || referenced.entries == null || referenced.entries.length == 0) {
                    continue;
                }
                if (referenced.scalableTracks != null) {
                    collectLayerConfigs(referenced, tracks, configs, visited);
                }
                visited.add(id);
                var entry = referenced.entries[0];
                if (entry != null && entry.codec() == Codec.H264 && entry.config() != null) {
                    configs.add(entry.config());
                }
            }
        }
    }

    /**
     * Rebuilds the access unit of a sample of a layered H.264 track (ISO/IEC 14496-15): for implicit
     * reconstruction, the time-aligned samples of the referenced tracks followed by the sample; otherwise
     * the sample with each extractor replaced by the bytes it names in a referenced track's sample and each
     * aggregator replaced by the NAL units it holds.
     *
     * <p>Data that cannot be found, such as the sample of a referenced track that a forward-only source has
     * already passed, is left out, so that the decoder sees the access unit damaged. Implicit reconstruction
     * takes the sample of each track in {@link #reconstructed} once.
     *
     * @param track the layered track
     * @param dts the decoding time of the sample, in the track's timescale
     * @param data the sample
     * @param depth the nesting of referenced layered tracks, which ends at 8
     * @return the access unit
     */
    private byte[] reconstruct(Mp4Track track, long dts, byte[] data, int depth) {
        var config = track.entry.config();
        var lengthSize = config != null && config.length > 4 ? (config[4] & 3) + 1 : 4;
        var out = new java.io.ByteArrayOutputStream(data.length + 64);
        if (track.entry.implicitReconstruction()) {
            for (var id : track.scalableTracks) {
                var referenced = trackOf(id);
                if (referenced == null || reconstructed.contains(referenced)) {
                    continue;
                }
                reconstructed.add(referenced);
                var time = Mp4Track.rescale(dts, track.timescale, referenced.timescale);
                var sample = alignedSample(referenced, time, 0);
                if (sample != null) {
                    out.writeBytes(isLayered(referenced) && depth < 8
                            ? reconstruct(referenced, time, sample, depth + 1) : sample);
                }
            }
            out.writeBytes(data);
            return out.toByteArray();
        }
        if (!containsLayerUnits(data, lengthSize)) {
            return data;
        }
        resolveUnits(track, dts, data, 0, data.length, lengthSize, out, depth);
        return out.toByteArray();
    }

    /**
     * Returns whether a sample holds an extractor or an aggregator (NAL unit types 31 and 30).
     *
     * @param data the sample
     * @param lengthSize the size of the NAL unit length prefixes
     * @return {@code true} when a NAL unit of either type is present
     */
    private static boolean containsLayerUnits(byte[] data, int lengthSize) {
        var position = 0;
        while (position + lengthSize < data.length) {
            var length = readLength(data, position, lengthSize);
            position += lengthSize;
            if (length <= 0 || length > data.length - position) {
                return false;
            }
            var type = data[position] & 0x1F;
            if (type == 30 || type == 31) {
                return true;
            }
            position += length;
        }
        return false;
    }

    /**
     * Copies the length-prefixed NAL units of a range of a sample, resolving extractors and unwrapping
     * aggregators.
     *
     * @param track the layered track
     * @param dts the decoding time of the sample
     * @param data the sample
     * @param from the start of the range
     * @param to the end of the range
     * @param lengthSize the size of the NAL unit length prefixes
     * @param out receives the access unit
     * @param depth the nesting of aggregators, which ends at 8
     */
    private void resolveUnits(Mp4Track track, long dts, byte[] data, int from, int to, int lengthSize,
                              java.io.ByteArrayOutputStream out, int depth) {
        var position = from;
        while (position + lengthSize <= to) {
            var length = readLength(data, position, lengthSize);
            var start = position + lengthSize;
            if (length < 0 || length > to - start) {
                return;
            }
            var type = length > 0 ? data[start] & 0x1F : 0;
            if (type == 30 && length > 4 && depth < 8) {
                resolveUnits(track, dts, data, start + 4, start + length, lengthSize, out, depth + 1);
            } else if (type == 31 && length >= 6 + 2 * lengthSize) {
                extract(track, dts, data, start, lengthSize, out);
            } else {
                out.write(data, position, lengthSize + length);
            }
            position = start + length;
        }
    }

    /**
     * Copies the bytes an extractor names: {@code data_length} bytes from {@code data_offset} of the sample
     * of the referenced track at the extractor's sample offset from the time-aligned one, or, when
     * {@code data_length} is 0, the whole NAL unit whose length field starts at {@code data_offset}; a
     * range that runs past the end of the sample stops there.
     *
     * @param track the layered track
     * @param dts the decoding time of the sample holding the extractor
     * @param data the sample
     * @param start the index of the extractor's NAL unit header
     * @param lengthSize the size of the NAL unit length prefixes, which sizes the offset fields
     * @param out receives the bytes
     */
    private void extract(Mp4Track track, long dts, byte[] data, int start, int lengthSize,
                         java.io.ByteArrayOutputStream out) {
        var reference = data[start + 4] & 0xFF;
        var sampleOffset = data[start + 5];
        var offset = readLength(data, start + 6, lengthSize);
        var length = readLength(data, start + 6 + lengthSize, lengthSize);
        if (reference < 1 || reference > track.scalableTracks.length) {
            return;
        }
        var referenced = trackOf(track.scalableTracks[reference - 1]);
        if (referenced == null || referenced == track) {
            return;
        }
        var sample = alignedSample(referenced, Mp4Track.rescale(dts, track.timescale, referenced.timescale),
                sampleOffset);
        if (sample == null || offset < 0 || offset >= sample.length) {
            return;
        }
        long end;
        if (length == 0) {
            var unit = readLength(sample, offset, lengthSize);
            end = unit < 0 ? sample.length : (long) offset + lengthSize + unit;
        } else {
            end = (long) offset + length;
        }
        out.write(sample, offset, (int) (Math.min(sample.length, end) - offset));
    }

    /**
     * Returns a sample of a referenced track: the one whose decoding time is the latest at or before a time,
     * moved by an offset in samples.
     *
     * @param track the referenced track
     * @param time the decoding time, in the track's timescale
     * @param sampleOffset the offset in samples
     * @return the sample, or {@code null} when there is no such sample or it can no longer be read
     */
    private byte[] alignedSample(Mp4Track track, long time, int sampleOffset) {
        var samples = track.samples;
        var low = 0;
        var high = samples.count - 1;
        var found = -1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            if (samples.dts[middle] <= time) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        var index = found + sampleOffset;
        if (found < 0 || index < 0 || index >= samples.count) {
            return null;
        }
        var held = referencedSamples.get(track);
        var cached = held != null ? held.get(index) : null;
        if (cached != null) {
            return cached;
        }
        var elsewhere = sourceOf(track, index);
        byte[] data;
        if (elsewhere != null) {
            data = readExternal(elsewhere, samples.offsets[index], samples.sizes[index]);
        } else if (forwardOnly) {
            return null;
        } else {
            data = readDirectSample(samples.offsets[index], samples.sizes[index]);
        }
        if (track.encrypted) {
            decrypt(track, index, data, 0, data.length);
        }
        return data;
    }

    /**
     * Reads the bytes of a sample anywhere in a seekable source without disturbing the read-ahead window.
     *
     * @param offset the absolute offset of the sample
     * @param size the sample size in bytes
     * @return the sample
     */
    private byte[] readDirectSample(long offset, int size) {
        if (backing != null || segment != null || inWindow(offset, size)) {
            return readSample(offset, size);
        }
        LimitChecks.checkAllocation(limits, size);
        var data = new byte[size];
        source.readFully(offset, data, 0, size);
        return data;
    }

    /**
     * Reads a big-endian NAL unit length or extractor field.
     *
     * @param data the bytes
     * @param position the index of the first byte
     * @param size the field size, 1 to 4 bytes
     * @return the value, or -1 when the field runs past the end
     */
    private static int readLength(byte[] data, int position, int size) {
        if (position + size > data.length) {
            return -1;
        }
        var value = 0L;
        for (var index = 0; index < size; index++) {
            value = (value << 8) | (data[position + index] & 0xFF);
        }
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    /**
     * Returns the codec configuration a sample establishes when its sample description differs
     * from the one the track's previous packets established, and records it.
     *
     * @param trackIndex the active index of the sample's track
     * @param index the index of the sample in its track
     * @return the configuration to signal, or {@code null} when it does not change
     * @throws MediaException.Malformed if the sample's description failed to parse
     * @throws MediaException.Unsupported if the sample's description cannot follow the track's
     */
    private byte[] configChange(int trackIndex, int index) {
        var track = active[trackIndex];
        var description = track.samples.description(index);
        var current = descriptions[trackIndex];
        if (description == current) {
            return null;
        }
        var next = track.configOf(description);
        var previous = track.configOf(current);
        descriptions[trackIndex] = description;
        return Arrays.equals(previous, next) ? null : next;
    }

    /**
     * Makes every active track's own sample description the established one, as it is for a
     * decoder created from the track after a seek.
     */
    private void resetDescriptions() {
        for (var index = 0; index < active.length; index++) {
            descriptions[index] = active[index].baseDescription;
        }
    }

    /**
     * Makes the demuxer return the media samples of a track whose edit list was expanded into a
     * presentation index: every sample once, in decoding order, with its media timestamps (the
     * composition shift applied, the edit list not), for a writer that copies the edit list
     * itself; then rewinds.
     *
     * @param trackId the identifier of the track
     * @return the track's edit list as {@link Mp4Track#editsInMediaUnits(long)} returns it, or
     * {@code null} when the track's edit list was not expanded
     */
    long[][] useMediaSamples(int trackId) {
        for (var track : active) {
            if (track.id == trackId && track.expanded) {
                track.samples = track.media;
                track.expanded = false;
                track.shift = 0;
                track.compositionShift = track.mediaCompositionShift;
                track.trimIndex = new int[0];
                track.trimAmounts = new long[0];
                dataEnd = Math.max(dataEnd, dataEnd(active));
                rewind();
                return track.editsInMediaUnits(movieTimescale);
            }
        }
        return null;
    }

    /**
     * Consumes the sample at the top of the heap.
     *
     * @return the index of the consumed sample within its track, whose active index was at the top
     * of the heap
     */
    private int advance() {
        var trackIndex = heap[0];
        var index = cursors[trackIndex]++;
        if (cursors[trackIndex] == active[trackIndex].samples.count) {
            heap[0] = heap[--heapSize];
        }
        if (heapSize > 0) {
            siftDown(0);
        }
        return index;
    }

    /**
     * Describes one sample without its bytes: the fields of the {@link Packet} that
     * {@link #read()} would return for it, except the data.
     */
    static final class SampleInfo {
        /**
         * The identifier of the sample's track.
         */
        int trackId;

        /**
         * The sample size in bytes.
         */
        int size;

        /**
         * The presentation timestamp.
         */
        long pts;

        /**
         * The decoding timestamp.
         */
        long dts;

        /**
         * The duration.
         */
        long duration;

        /**
         * Whether the sample is a sync sample.
         */
        boolean keyframe;

        /**
         * The codec configuration the sample establishes, or {@code null} when it keeps the one
         * of the track's previous sample.
         */
        byte[] newConfig;

        /**
         * The array holding the sample bytes, filled by {@link #readSample(SampleInfo)} only.
         */
        byte[] data;

        /**
         * The index of the first sample byte in {@link #data}.
         */
        int dataOffset;
    }

    /**
     * Advances like {@link #read()} without reading the sample bytes.
     *
     * @param info the holder that receives the sample's description
     * @return {@code false} when every sample was returned
     */
    boolean nextSample(SampleInfo info) {
        loadFragments();
        if (heapSize == 0) {
            return false;
        }
        var trackIndex = heap[0];
        var track = active[trackIndex];
        var index = advance();
        var samples = track.samples;
        var decode = samples.dts[index] + track.shift;
        info.newConfig = configChange(trackIndex, index);
        info.trackId = track.id;
        info.size = samples.sizes[index];
        info.pts = decode + samples.ctsOffsets[index];
        info.dts = decode - track.compositionShift;
        info.duration = Integer.toUnsignedLong(samples.durations[index]);
        info.keyframe = samples.isKeyframe(index);
        return true;
    }

    /**
     * Advances like {@link #read()} and exposes the sample bytes in a buffer of this
     * demuxer instead of a new array.
     *
     * <p>The bytes stay valid until the next read. The checks, the exceptions and the order of
     * the samples are those of {@link #read()}.
     *
     * @param info the holder that receives the sample's description and bytes
     * @return {@code false} when every sample was returned
     * @throws MediaException.Malformed if a sample lies outside the file
     * @throws MediaException.LimitExceeded if a sample exceeds the allocation limit
     * @throws MediaException.Io if reading the source fails
     */
    boolean readSample(SampleInfo info) {
        try {
            loadFragments();
            if (heapSize == 0) {
                return false;
            }
            var trackIndex = heap[0];
            var track = active[trackIndex];
            var index = advance();
            var samples = track.samples;
            var elsewhere = sourceOf(track, index);
            if (elsewhere != null) {
                locateExternal(elsewhere, samples.offsets[index], samples.sizes[index], info);
            } else {
                locateSample(samples.offsets[index], samples.sizes[index], info);
            }
            if (track.encrypted) {
                var size = samples.sizes[index];
                if (plain == null || plain.length < size) {
                    plain = new byte[Math.max(size, plain == null ? 0 : plain.length * 2)];
                }
                System.arraycopy(info.data, info.dataOffset, plain, 0, size);
                decrypt(track, index, plain, 0, size);
                info.data = plain;
                info.dataOffset = 0;
            }
            releaseConsumed();
            info.newConfig = configChange(trackIndex, index);
            var decode = samples.dts[index] + track.shift;
            info.trackId = track.id;
            info.size = samples.sizes[index];
            info.pts = decode + samples.ctsOffsets[index];
            info.dts = decode - track.compositionShift;
            info.duration = Integer.toUnsignedLong(samples.durations[index]);
            info.keyframe = samples.isKeyframe(index);
            if (!quickTimeTextTracks.isEmpty() && quickTimeTextTracks.contains(track.id)) {
                var converted = QuickTimeText.sample(Arrays.copyOfRange(info.data, info.dataOffset,
                        info.dataOffset + info.size));
                info.data = converted;
                info.dataOffset = 0;
                info.size = converted.length;
            } else if (!captionTracks.isEmpty() && captionTracks.contains(track.id)) {
                var converted = QuickTimeCaptions.toCcData(Arrays.copyOfRange(info.data, info.dataOffset,
                        info.dataOffset + info.size));
                info.data = converted;
                info.dataOffset = 0;
                info.size = converted.length;
            }
            return true;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ISO base media file: " + exception, exception);
        }
    }

    /**
     * Reads one sample of a file a data reference names into {@link #scratch}.
     *
     * @param from the file
     * @param offset the offset of the sample in it
     * @param size the size in bytes
     * @param info the holder that receives the array and the index of the bytes
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private void locateExternal(MediaSource from, long offset, int size, SampleInfo info) {
        checkExternal(from, offset, size);
        if (scratch == null || scratch.length < size) {
            scratch = new byte[size];
        }
        from.readFully(offset, scratch, 0, size);
        info.data = scratch;
        info.dataOffset = 0;
    }

    /**
     * Makes the bytes of one sample available in {@link #window} or {@link #scratch}, with the
     * checks of {@link #readSample(long, int)}.
     *
     * @param offset the absolute file offset
     * @param size the size in bytes
     * @param info the holder that receives the array and the index of the bytes
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private void locateSample(long offset, int size, SampleInfo info) {
        LimitChecks.checkAllocation(limits, size);
        if (sourceSize >= 0 && (offset > sourceSize || size > sourceSize - offset)) {
            throw new MediaException.Malformed("sample of " + size + " bytes at offset " + offset
                    + " runs past the end of the " + sourceSize + " byte file");
        }
        if (size == 0) {
            info.data = EMPTY;
            info.dataOffset = 0;
            return;
        }
        if (backing != null) {
            info.data = backing.array();
            info.dataOffset = backing.arrayOffset() + (int) offset;
            return;
        }
        if (segment != null) {
            if (scratch == null || scratch.length < size) {
                scratch = new byte[size];
            }
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, scratch, 0, size);
            info.data = scratch;
            info.dataOffset = 0;
            return;
        }
        if (size > WINDOW_MAX_SAMPLE && !inWindow(offset, size)) {
            if (scratch == null || scratch.length < size) {
                scratch = new byte[size];
            }
            readDirect(offset, scratch, size);
            info.data = scratch;
            info.dataOffset = 0;
            return;
        }
        if (window == null) {
            window = new byte[WINDOW_SIZE];
        }
        if (offset < windowStart || offset + size > windowStart + windowLength) {
            fillWindow(offset, size);
        }
        info.data = window;
        info.dataOffset = (int) (offset - windowStart);
    }

    /**
     * Moves back to the first sample, so that {@link #read()} and
     * {@link #nextSample(SampleInfo)} start over.
     */
    void rewind() {
        Arrays.fill(cursors, 0);
        resetDescriptions();
        heapSize = 0;
        for (var index = 0; index < active.length; index++) {
            if (active[index].samples.count > 0) {
                heapPush(index);
            }
        }
    }

    /**
     * Tells a source that reads its input once that no sample before the next one is read
     * again: the next sample of every track lies at or after the offset of the one at the top of
     * the heap, and the read-ahead window never reads backwards.
     */
    private void releaseConsumed() {
        if (forwardOnly && !mixedSources) {
            var next = Long.MAX_VALUE;
            if (external.isEmpty()) {
                next = heapSize > 0 ? active[heap[0]].samples.offsets[cursors[heap[0]]] : Long.MAX_VALUE;
            } else {
                for (var index = 0; index < active.length; index++) {
                    var track = active[index];
                    if (cursors[index] < track.samples.count && sourceOf(track, cursors[index]) == null) {
                        next = Math.min(next, track.samples.offsets[cursors[index]]);
                    }
                }
            }
            var watermark = fragmentScan >= 0 ? Math.min(next, fragmentScan) : next;
            released = Math.max(released, watermark);
            ForwardOnlySource.release(source, watermark);
        }
    }

    /**
     * Indexes every fragment from a top-level offset on, at construction, for a forward-only
     * source whose edit lists need the whole sample index.
     *
     * @param from the offset of the first top-level box after the ones already walked
     * @param tracks every track of the movie
     * @throws MediaException.Malformed if a {@code moof} is corrupt
     */
    private void indexFragments(long from, List<Mp4Track> tracks) {
        var position = from;
        while (position >= 0) {
            var box = TopLevelScan.readHeader(source, position, sourceSize);
            if (box == null || box[1] < 0 || (sourceSize >= 0 && position + box[1] > sourceSize)) {
                return;
            }
            walkLater(position, box, tracks);
            position += box[1];
        }
    }

    /**
     * Indexes the next fragments of a forward-only source while every indexed sample has been
     * returned.
     *
     * <p>The samples of a fragment lie in the {@code mdat} that follows its {@code moof}, after
     * every sample of the fragments before it, so returning the samples of the indexed fragments
     * before indexing the next one keeps the order of a demux that indexed every fragment at
     * construction.
     */
    private void loadFragments() {
        while (heapSize == 0 && fragmentScan >= 0) {
            loadFragment();
        }
    }

    /**
     * Indexes every remaining fragment of a forward-only source.
     */
    private void loadAllFragments() {
        while (fragmentScan >= 0) {
            loadFragment();
        }
    }

    /**
     * Walks the top-level boxes from {@link #fragmentScan} to the next {@code moof}, indexes its
     * samples, and puts the tracks that gained samples back in the heap.
     *
     * <p>The header of the {@code mdat} that follows the {@code moof} is read with it, so the
     * walk resumes after the media data of the fragment and the bytes before its first sample are
     * not kept while the samples are read.
     *
     * @throws MediaException.Malformed if the {@code moof} is corrupt, or a compressed box is not a
     * gzip stream
     */
    private void loadFragment() {
        while (fragmentScan >= 0) {
            var position = fragmentScan;
            var box = TopLevelScan.readHeader(source, position, sourceSize);
            if (box == null || box[1] < 0 || (sourceSize >= 0 && position + box[1] > sourceSize)) {
                fragmentScan = -1;
            } else {
                fragmentScan = position + box[1];
            }
            if (box == null || box[1] < 0) {
                continue;
            }
            if ((int) box[2] != BoxTypes.MOOF && (int) box[2] != BoxTypes.COMPRESSED_MOOF) {
                walkLater(position, box, fragmentTracks);
                continue;
            }
            var counts = new int[active.length];
            for (var index = 0; index < active.length; index++) {
                counts[index] = active[index].samples.count;
            }
            walkLater(position, box, fragmentTracks);
            if (fragmentScan >= 0) {
                var data = TopLevelScan.readHeader(source, fragmentScan, sourceSize);
                if (data != null && (int) data[2] == BoxTypes.MDAT) {
                    fragmentScan = data[1] < 0 || (sourceSize >= 0 && fragmentScan + data[1] > sourceSize)
                            ? -1 : fragmentScan + data[1];
                }
            }
            for (var index = 0; index < active.length; index++) {
                var samples = active[index].samples;
                for (var sample = counts[index]; sample < samples.count; sample++) {
                    if (sourceOf(active[index], sample) == null) {
                        dataEnd = Math.max(dataEnd, samples.offsets[sample] + Math.max(0, samples.sizes[sample]));
                    }
                }
                var wasEmpty = cursors[index] >= counts[index];
                if (wasEmpty && cursors[index] < samples.count) {
                    heapPush(index);
                }
            }
            return;
        }
    }

    /**
     * Reads the bytes of one sample.
     *
     * @param offset the absolute file offset
     * @param size the size in bytes
     * @return the sample bytes
     * @throws MediaException.Malformed if the sample lies outside the file
     * @throws MediaException.LimitExceeded if the sample exceeds the allocation limit
     */
    private byte[] readSample(long offset, int size) {
        LimitChecks.checkAllocation(limits, size);
        if (sourceSize >= 0 && (offset > sourceSize || size > sourceSize - offset)) {
            throw new MediaException.Malformed("sample of " + size + " bytes at offset " + offset
                    + " runs past the end of the " + sourceSize + " byte file");
        }
        if (backing != null) {
            var from = backing.arrayOffset() + (int) offset;
            return Arrays.copyOfRange(backing.array(), from, from + size);
        }
        var data = new byte[size];
        if (segment != null) {
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, data, 0, size);
            return data;
        }
        if (size == 0) {
            return data;
        }
        if (size > WINDOW_MAX_SAMPLE && !inWindow(offset, size)) {
            readDirect(offset, data, size);
            return data;
        }
        if (window == null) {
            window = new byte[WINDOW_SIZE];
        }
        if (offset < windowStart || offset + size > windowStart + windowLength) {
            fillWindow(offset, size);
        }
        System.arraycopy(window, (int) (offset - windowStart), data, 0, size);
        return data;
    }

    /**
     * Reads a sample that does not fit the read-ahead window straight into an array, taking the
     * bytes of its start that the window already holds and reading only the rest, so the source
     * is never read backwards.
     *
     * <p>When the source ends before the sample does, the whole sample is read again through
     * {@link MediaSource#readFully(long, byte[], int, int)}, which fails exactly as a direct read
     * would.
     *
     * @param offset the absolute offset of the sample
     * @param target the destination, at least {@code size} bytes long
     * @param size the sample size in bytes
     * @throws MediaException.Malformed if the source ends inside the sample
     */
    private void readDirect(long offset, byte[] target, int size) {
        var held = 0;
        if (window != null && offset >= windowStart && offset < windowStart + windowLength) {
            held = (int) Math.min(size, windowStart + windowLength - offset);
            System.arraycopy(window, (int) (offset - windowStart), target, 0, held);
        }
        var done = held;
        while (done < size) {
            var read = source.read(offset + done, target, done, size - done);
            if (read <= 0) {
                source.readFully(offset, target, 0, size);
                return;
            }
            done += read;
        }
    }

    /**
     * Returns whether a range of the file is buffered in the read-ahead window.
     *
     * @param offset the absolute offset of the first byte
     * @param size the number of bytes
     * @return {@code true} when every byte of the range is in the window
     */
    private boolean inWindow(long offset, int size) {
        return window != null && offset >= windowStart && offset + size <= windowStart + windowLength;
    }

    /**
     * Returns the end of the furthest sample of some tracks.
     *
     * @param tracks every track of the movie
     * @return the largest sample offset plus size, or {@code 0} when no track has a sample
     */
    private static long dataEnd(Mp4Track[] tracks) {
        var end = 0L;
        for (var track : tracks) {
            var samples = track.samples;
            for (var index = 0; index < samples.count; index++) {
                if (sourceOf(track, index) == null) {
                    end = Math.max(end, samples.offsets[index] + Math.max(0, samples.sizes[index]));
                }
            }
        }
        return end;
    }

    /**
     * Refills the read-ahead window starting at an offset.
     *
     * <p>Bytes of the old window from {@code offset} on move to the front instead of being read
     * again, so samples that straddle two windows never make the source go backwards. The window
     * stops at the end of the furthest sample, so the boxes after the media data (a trailing
     * {@code moov}, already read) are not read again.
     *
     * @param offset the absolute offset of the first byte to buffer
     * @param needed the number of bytes that must be buffered
     * @throws MediaException.Malformed if the source ends first
     */
    private void fillWindow(long offset, int needed) {
        var keep = 0;
        if (offset >= windowStart && offset < windowStart + windowLength) {
            keep = (int) (windowStart + windowLength - offset);
            System.arraycopy(window, (int) (offset - windowStart), window, 0, keep);
        }
        windowStart = offset;
        windowLength = keep;
        var wanted = (int) Math.min(WINDOW_SIZE, (sourceSize >= 0 ? Math.min(sourceSize, dataEnd) : dataEnd) - offset);
        if (wanted < needed) {
            wanted = sourceSize >= 0 ? (int) Math.min(WINDOW_SIZE, sourceSize - offset) : WINDOW_SIZE;
        }
        while (windowLength < wanted) {
            var read = source.read(offset + windowLength, window, windowLength, wanted - windowLength);
            if (read <= 0) {
                break;
            }
            windowLength += read;
        }
        if (windowLength < needed) {
            throw new MediaException.Malformed("sample at offset " + offset + " runs past the end of the input");
        }
    }

    /**
     * Returns whether heap entry {@code a} orders before entry {@code b}.
     *
     * @param a an active track index
     * @param b an active track index
     * @return {@code true} when the next sample of {@code a} lies earlier in the file, or at the
     * same offset with a lower track index; when the two samples are in different files (one of them named by a
     * data reference), whose offsets do not compare, when it decodes earlier, or at the same time with a lower
     * track index
     */
    private boolean before(int a, int b) {
        var trackA = active[a];
        var trackB = active[b];
        var indexA = cursors[a];
        var indexB = cursors[b];
        if (!external.isEmpty() && sourceOf(trackA, indexA) != sourceOf(trackB, indexB)) {
            var decodeA = trackA.samples.dts[indexA] + trackA.shift - trackA.compositionShift;
            var decodeB = trackB.samples.dts[indexB] + trackB.shift - trackB.compositionShift;
            var timeA = decodeA / (double) trackA.timescale;
            var timeB = decodeB / (double) trackB.timescale;
            return timeA < timeB || (timeA == timeB && a < b);
        }
        var offsetA = trackA.samples.offsets[indexA];
        var offsetB = trackB.samples.offsets[indexB];
        return offsetA < offsetB || (offsetA == offsetB && a < b);
    }

    /**
     * Inserts an active track index into the heap.
     *
     * @param trackIndex the index
     */
    private void heapPush(int trackIndex) {
        var position = heapSize++;
        heap[position] = trackIndex;
        while (position > 0) {
            var parent = (position - 1) >>> 1;
            if (!before(heap[position], heap[parent])) {
                break;
            }
            var swap = heap[parent];
            heap[parent] = heap[position];
            heap[position] = swap;
            position = parent;
        }
    }

    /**
     * Restores the heap order below a position.
     *
     * @param position the position whose entry may be out of order
     */
    private void siftDown(int position) {
        while (true) {
            var left = 2 * position + 1;
            if (left >= heapSize) {
                return;
            }
            var smallest = left + 1 < heapSize && before(heap[left + 1], heap[left]) ? left + 1 : left;
            if (!before(heap[smallest], heap[position])) {
                return;
            }
            var swap = heap[smallest];
            heap[smallest] = heap[position];
            heap[position] = swap;
            position = smallest;
        }
    }
}
