package com.github.auties00.smalt.container.mpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits an MPEG audio elementary stream (MP3, MP2, MP1) into frames.
 *
 * <p>The stream may start with any number of ID3v2 tags (versions 2.2 to 2.4, with or without a
 * footer), which are skipped and whose text frames become {@link #metadata()}, and may end with
 * ID3v1, APE, Lyrics3v2 or appended ID3v2 tags, which are excluded from the audio. The first
 * frame is found by scanning up to {@value #INITIAL_SYNC_WINDOW} bytes for a header followed by
 * three compatible headers at the predicted positions (a stream that starts exactly after its
 * tags may be shorter); garbage between frames is skipped the same way, and a new format (sample
 * rate, version or layer) found after garbage replaces the old one. Free-format streams are
 * supported. A final frame cut short by the end of the file is still returned, shorter than its
 * header says, and decodes with the missing bytes treated as zeros.
 *
 * <p>The single {@link AudioTrack} has codec {@link Codec#MP3} for Layer III and
 * {@link Codec#MP2} for Layers I and II, the time base {@code 1/sampleRate} of the first frame,
 * and a mono or stereo layout. Every packet is one whole frame, header included, with
 * {@link Packet#pts()} equal to the number of samples the decoder output before it (converted to
 * the track rate if the stream changes rate) and {@link Packet#duration()} equal to the frame's
 * sample count.
 *
 * <p>A first frame carrying a Xing, Info or VBRI header is a silent tag frame: it is never
 * returned as a packet, and its frame count gives the exact duration. When the Xing or Info frame
 * also carries a LAME tag (encoder string starting with {@code LAME}, {@code Lavf} or
 * {@code Lavc}), the encoder delay {@code d} and padding {@code p} it records define gapless
 * playback: {@link AudioTrack#skipSamples()} is {@code d + 529} (the encoder delay plus the 529
 * samples of decoder delay of the MPEG synthesis filterbank) and {@link AudioTrack#duration()} is
 * {@code frames * samplesPerFrame - d - p}, the number of samples of the original signal. A reader
 * reproduces the original signal exactly by dropping the first {@code skipSamples} decoded
 * samples and keeping the next {@code duration} samples. Without a LAME tag, {@code skipSamples}
 * is zero and the duration is the frame count times the frame length.
 *
 * <p>{@link AudioTrack#duration()} is always exact when it is not {@code -1}, because readers
 * trim with it. Without a frame count from a Xing, Info or VBRI frame, a file of at most
 * {@value #SCAN_LIMIT} bytes is scanned frame by frame at construction to count its samples; a
 * larger file or a source of unknown size reports {@code -1}, and {@link #durationMicros()}
 * then returns an estimate from the first frame's bitrate and the payload size, or {@code -1}
 * when the payload size is unknown.
 *
 * <p>When that frame-by-frame count runs over a source that is not in memory, it also keeps the
 * packets of the frames it passes, up to {@value #MAX_KEPT_BYTES} bytes of them, and
 * {@link #read()} returns those before reading on from the first frame the count did not
 * keep: audio of up to that size is read once, and a larger file is read again only from where
 * the kept packets end.
 *
 * <p>ID3v2 {@code APIC} and {@code CHAP} frames become {@link #attachedPictures()} and
 * {@link #chapters()}.
 *
 * <p>A stream of known size can seek. A stream of at most {@value #SCAN_LIMIT} bytes is indexed
 * frame by frame on the first {@link #seek(long)} (every sixteenth frame header with its exact
 * timestamp), so the next packet is exactly the frame holding the target sample. A larger stream
 * jumps to the byte position the Xing seek table gives for the target, or the one the bitrate of
 * the first frame gives without a table, and resynchronizes there; its timestamps after the seek
 * are then estimated from that position, rounded to whole frames.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every frame which
 * bytes are no longer needed. Its trailing tags and the frame-by-frame count of a file without a
 * tag frame are read only when the whole input fits the source's memory budget; otherwise the
 * duration is the estimate above and the ID3v1 and APE tags are not read.
 *
 * @implNote This implementation keeps the count's packets because an exact duration without a
 * tag frame needs every frame header, and frames are at most 8 KiB apart, so the count reads the
 * whole file whatever it skips; reading the frames a second time would fetch a network source
 * twice. The kept packets are the ones {@link #read()} would allocate anyway, so the total
 * allocation is unchanged; only their lifetime grows, bounded by the budget (or
 * {@link MediaLimits#maxBytes()} when smaller). An in-memory source keeps nothing, since reading
 * it again costs no input.
 */
public final class MpegAudioDemuxer implements Demuxer {
    /**
     * The number of bytes after the leading tags searched for the first frame.
     */
    static final int INITIAL_SYNC_WINDOW = 1 << 20;

    /**
     * The largest payload whose frames are counted at construction when no tag frame gives the
     * duration.
     */
    static final long SCAN_LIMIT = 64L << 20;

    /**
     * The identifier of the single track.
     */
    private static final int TRACK_ID = 0;

    /**
     * The decoder delay of the MPEG synthesis filterbank that LAME tags assume, in samples.
     */
    private static final int DECODER_DELAY = 529;

    /**
     * The largest number of bytes of packets the construction-time frame count keeps for
     * {@link #read()}, counted as frame sizes plus {@link #PACKET_OVERHEAD} per packet.
     */
    private static final long MAX_KEPT_BYTES = 8L << 20;

    /**
     * The heap size charged to the kept-packet budget for each packet beyond its frame: the
     * packet and array headers and the queue slot.
     */
    private static final int PACKET_OVERHEAD = 64;

    /**
     * The largest initial capacity of the queue of kept packets.
     *
     * @implNote This implementation caps it at 16384 slots (64 KiB of references), more than the
     * frames of the 8 MiB budget at typical bitrates, so a crafted stream of tiny frames cannot
     * make the queue allocate much ahead of the packets it actually keeps; the queue still grows
     * past it when needed.
     */
    private static final int MAX_EXPECTED_KEPT = 1 << 14;

    /**
     * The source being demuxed.
     */
    private final MediaSource source;

    /**
     * The frame cursor that produces packets.
     */
    private final FrameCursor cursor;

    /**
     * The only track.
     */
    private final AudioTrack track;

    /**
     * The tracks, a list holding {@link #track}.
     */
    private final List<Track> tracks;

    /**
     * The tags read from ID3v2 and ID3v1.
     */
    private final Map<String, String> metadata;

    /**
     * The presentation duration in microseconds, exact or estimated, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The sampling rate of the track's time base.
     */
    private final int trackRate;

    /**
     * The timestamp, in track units, where the current rate segment starts.
     */
    private long segmentBase;

    /**
     * The number of samples output in the current rate segment.
     */
    private long segmentSamples;

    /**
     * The sampling rate of the current rate segment.
     */
    private int segmentRate;

    /**
     * Whether the cursor holds a frame that was located during construction but not returned yet.
     */
    private boolean pendingFirst;

    /**
     * The packets the construction-time frame count kept and {@link #read()} has not
     * returned; replaced by a queue sized for the count when packets are kept.
     */
    private ArrayDeque<Packet> kept;

    /**
     * Whether the end of the stream is known to lie right after the packets in {@link #kept}.
     */
    private boolean finished;

    /**
     * Whether {@link #read()} starts from the packets in {@link #kept}: set when the
     * construction-time count kept packets or found the end, so the other demuxes test one flag.
     */
    private boolean replaying;

    /**
     * The first byte of the audio region, after the leading tags.
     */
    private long regionStart;

    /**
     * The offset of the first audio frame, after any tag frame.
     */
    private long firstAudio = -1;

    /**
     * The reference header of the cursor at the first audio frame.
     */
    private int firstReference;

    /**
     * The free-format frame size of the cursor at the first audio frame.
     */
    private int firstFreeFormat;

    /**
     * The end of the audio region.
     */
    private long regionEnd;

    /**
     * The Xing, Info or VBRI tag of the stream, or {@code null}.
     */
    private VbrInfo vbr;

    /**
     * The index of every sixteenth frame built on the first exact seek, or {@code null}: the
     * frame timestamp in the track's time base, its offset, the cursor's reference header
     * (state) and free-format size (extra) there.
     */
    private SeekIndex index;

    /**
     * The cursor end at the end of the walk that built {@link #index}.
     */
    private long indexEnd;

    /**
     * The total number of samples the walk that built {@link #index} counted, in the track's
     * time base.
     */
    private long indexSamples;

    /**
     * The chapters of the ID3v2 tags.
     */
    private final List<Chapter> chapters;

    /**
     * The pictures of the ID3v2 tags.
     */
    private final List<AttachedPicture> pictures;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no MPEG audio frame is found
     * @throws MediaException.Io if reading the source fails
     */
    public MpegAudioDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no MPEG audio frame is found
     * @throws MediaException.Io if reading the source fails
     */
    public MpegAudioDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private MpegAudioDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        this.kept = new ArrayDeque<>(0);
        try {
            var size = source.size();
            var end = size < 0 ? Long.MAX_VALUE : size;
            var tags = new LinkedHashMap<String, String>();
            var annotations = new Annotations();
            var start = Id3v2.skip(source, 0, end, tags, annotations);
            if (size >= 0 && ForwardOnlySource.isCheapToReach(source, end)) {
                var tail = new TrailingTags.Tail(source, start);
                tail.load(end);
                readId3v1(tail, start, end, tags);
                end = TrailingTags.audioEnd(tail, start, end);
            }
            this.metadata = Collections.unmodifiableMap(tags);
            var cursor = new FrameCursor(source, start, end);
            if (!cursor.next()) {
                throw new MediaException.Malformed("no MPEG audio frame found");
            }
            var first = new byte[cursor.frameLength];
            if (!cursor.copy(cursor.frameOffset, first, first.length)) {
                throw new MediaException.Malformed("truncated first MPEG audio frame");
            }
            var header = cursor.frameHeader;
            var firstOffset = cursor.frameOffset;
            var info = VbrInfo.parse(header, first);
            var pending = true;
            if (info != null) {
                pending = cursor.next();
            }
            this.vbr = info;
            this.regionStart = start;
            this.regionEnd = end;
            if (pending) {
                this.firstAudio = cursor.frameOffset;
                this.firstReference = cursor.reference();
                this.firstFreeFormat = cursor.freeFormatBytes();
            }
            LimitChecks.checkChannels(limits, MpegAudioHeader.channels(header));
            this.trackRate = MpegAudioHeader.sampleRate(header);
            this.segmentRate = trackRate;
            var samplesPerFrame = MpegAudioHeader.samplesPerFrame(header);
            long skip = 0;
            long duration = -1;
            if (info != null && info.frames() >= 0) {
                var total = info.frames() * samplesPerFrame;
                if (info.hasGapless()) {
                    skip = info.delay() + DECODER_DELAY;
                    duration = Math.max(0, total - info.delay() - info.padding());
                } else {
                    duration = total;
                }
            } else if (!pending) {
                duration = 0;
            } else if (size >= 0 && end - start <= SCAN_LIMIT && ForwardOnlySource.isCheapToReach(source, end)) {
                var budget = SegmentBacked.isInMemory(source) ? 0 : Math.min(MAX_KEPT_BYTES, limits.maxBytes());
                if (budget > 0) {
                    this.kept = new ArrayDeque<>(expectedKept(cursor.frameLength, end - cursor.frameOffset, budget));
                }
                var walk = new FrameCursor(cursor);
                duration = countSamples(walk, MpegAudioHeader.samplesPerFrame(cursor.frameHeader),
                        MpegAudioHeader.sampleRate(cursor.frameHeader), cursor, info == null ? first : null, budget);
                pending = pendingFirst;
            }
            this.durationMicros = duration >= 0
                    ? Rational.timeBaseOf(trackRate).toMicros(duration)
                    : estimateMicros(header, cursor.freeFormatBytes(), cursor.end() - firstOffset);
            var layout = MpegAudioHeader.isMono(header) ? ChannelLayout.MONO : ChannelLayout.STEREO;
            var codec = MpegAudioHeader.layer(header) == 3 ? Codec.MP3 : Codec.MP2;
            this.track = AudioTrack.builder().id(TRACK_ID).codec(codec).timeBase(Rational.timeBaseOf(trackRate))
                    .duration(duration)
                    .sampleRate(trackRate)
                    .layout(layout)
                    .skipSamples(skip)
                    .build();
            this.tracks = List.of(track);
            this.cursor = cursor;
            this.pendingFirst = pending;
            this.chapters = annotations.chapters(durationMicros);
            this.pictures = annotations.pictures();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt MPEG audio stream", exception);
        }
    }

    /**
     * Estimates how many packets the construction-time frame count keeps, so that the queue
     * holding them is allocated once at about the right size instead of growing step by step.
     *
     * <p>The estimate assumes every frame is as long as the first, adds an eighth for variable
     * bitrates, and is capped at {@value #MAX_EXPECTED_KEPT}.
     *
     * @param frameLength the length of the first frame
     * @param payload     the bytes from the first frame to the end of the audio
     * @param budget      the bytes of packets that may be kept
     * @return the initial queue capacity
     */
    private static int expectedKept(int frameLength, long payload, long budget) {
        var length = Math.max(1, frameLength);
        var frames = Math.min(budget / (length + PACKET_OVERHEAD), payload / length + 1);
        return (int) Math.min(MAX_EXPECTED_KEPT, frames + frames / 8);
    }

    /**
     * Counts the decoded samples of every frame from the cursor's current frame to the end,
     * keeping the packets of the frames visited first.
     *
     * <p>The pending frame of {@code reader} and then every frame the count visits are kept,
     * with exactly the packets {@link #read()} would return for them, while the packets fit
     * {@code budget} and every frame can be copied whole; keeping stops at the first frame that
     * fails either test. {@code reader} is then moved to the state the count had after the last
     * kept frame, so {@link #read()} continues with the first frame not kept; when every
     * frame up to the end is kept, the demuxer is marked finished instead, since
     * {@link #read()} would meet the same end.
     *
     * @param walk       a copy of the reading cursor, positioned after its current frame
     * @param firstFrame the samples of the frame the cursor points at
     * @param firstRate  the sampling rate of that frame
     * @param reader     the reading cursor, holding the pending frame
     * @param firstData  the bytes of the pending frame, or {@code null} to copy them
     * @param budget     the bytes of packets that may be kept, {@code 0} to keep none
     * @return the sample count converted to the track rate
     */
    private long countSamples(FrameCursor walk, long firstFrame, int firstRate, FrameCursor reader, byte[] firstData,
                              long budget) {
        pendingFirst = true;
        var used = budget > 0 ? 0L : -1L;
        if (used >= 0) {
            used = keep(reader, firstData, used, budget);
            if (used >= 0) {
                pendingFirst = false;
            }
        }
        var resumePosition = walk.position();
        var resumeReference = walk.reference();
        var resumeFreeFormat = walk.freeFormatBytes();
        var resumeEnd = walk.end();
        long base = 0;
        long samples = firstFrame;
        var rate = firstRate;
        while (walk.next()) {
            var frameRate = MpegAudioHeader.sampleRate(walk.frameHeader);
            if (frameRate != rate) {
                base += Rational.timeBaseOf(rate).rescale(samples, Rational.timeBaseOf(trackRate));
                samples = 0;
                rate = frameRate;
            }
            samples += MpegAudioHeader.samplesPerFrame(walk.frameHeader);
            if (used >= 0) {
                used = keep(walk, null, used, budget);
                if (used >= 0) {
                    resumePosition = walk.position();
                    resumeReference = walk.reference();
                    resumeFreeFormat = walk.freeFormatBytes();
                    resumeEnd = walk.end();
                } else {
                    reader.copyFrom(walk);
                    reader.restore(resumePosition, resumeReference, resumeFreeFormat, resumeEnd);
                }
            }
        }
        finished = used >= 0;
        replaying = finished || !kept.isEmpty();
        return base + Rational.timeBaseOf(rate).rescale(samples, Rational.timeBaseOf(trackRate));
    }

    /**
     * Queues the packet of a cursor's current frame, unless it does not fit the budget or
     * cannot be copied whole.
     *
     * @param cursor the cursor holding the frame
     * @param data   the bytes of the frame, or {@code null} to copy them from the cursor
     * @param used   the bytes of the budget already used
     * @param budget the bytes of packets that may be kept
     * @return the bytes of the budget used after keeping the frame, or {@code -1} when keeping
     *         stops
     */
    private long keep(FrameCursor cursor, byte[] data, long used, long budget) {
        var cost = used + cursor.frameLength + PACKET_OVERHEAD;
        if (cost > budget) {
            return -1;
        }
        if (data == null) {
            data = new byte[cursor.frameLength];
            if (!cursor.copy(cursor.frameOffset, data, data.length)) {
                return -1;
            }
        }
        kept.add(packet(data, cursor.frameHeader));
        return cost;
    }

    /**
     * Builds the packet of a frame and advances the timestamps past it.
     *
     * @param data   the bytes of the frame
     * @param header the frame header
     * @return the packet
     */
    private Packet packet(byte[] data, int header) {
        var rate = MpegAudioHeader.sampleRate(header);
        if (rate != segmentRate) {
            segmentBase += Rational.timeBaseOf(segmentRate).rescale(segmentSamples, Rational.timeBaseOf(trackRate));
            segmentSamples = 0;
            segmentRate = rate;
        }
        var pts = segmentBase + Rational.timeBaseOf(segmentRate)
                .rescale(segmentSamples, Rational.timeBaseOf(trackRate));
        segmentSamples += MpegAudioHeader.samplesPerFrame(header);
        var next = segmentBase + Rational.timeBaseOf(segmentRate)
                .rescale(segmentSamples, Rational.timeBaseOf(trackRate));
        return new Packet(TRACK_ID, data, pts, pts, next - pts, true);
    }

    /**
     * Estimates the duration from the bitrate of one frame and the payload size.
     *
     * @param header          the frame header
     * @param freeFormatBytes the free-format frame size, or {@code 0}
     * @param payload         the payload size in bytes, or a huge value when unknown
     * @return the estimate in microseconds, or {@code -1} when the payload size is unknown
     */
    private static long estimateMicros(int header, int freeFormatBytes, long payload) {
        if (payload <= 0 || payload > Long.MAX_VALUE / 2) {
            return -1;
        }
        var frameBytes = MpegAudioHeader.frameBytes(header, freeFormatBytes);
        if (frameBytes <= 0) {
            return -1;
        }
        var frames = (double) payload / frameBytes;
        return Math.round(frames * MpegAudioHeader.samplesPerFrame(header) * 1_000_000.0 / MpegAudioHeader.sampleRate(header));
    }

    /**
     * Adds the fields of an ID3v1 tag at the end of the source to the metadata, without replacing
     * fields the ID3v2 tag already set.
     *
     * @param tail     the tail window over the source, holding its end
     * @param start    the offset of the audio start
     * @param end      the source size
     * @param metadata the metadata map
     */
    private static void readId3v1(TrailingTags.Tail tail, long start, long end, Map<String, String> metadata) {
        if (end - start < 128) {
            return;
        }
        var tag = new byte[128];
        if (!tail.read(end - 128, tag, 128)) {
            return;
        }
        if (tag[0] != 'T' || tag[1] != 'A' || tag[2] != 'G') {
            return;
        }
        putField(metadata, "TIT2", tag, 3, 30);
        putField(metadata, "TPE1", tag, 33, 30);
        putField(metadata, "TALB", tag, 63, 30);
        putField(metadata, "TYER", tag, 93, 4);
        if (tag[125] == 0 && tag[126] != 0) {
            putField(metadata, "COMM", tag, 97, 28);
            metadata.putIfAbsent("TRCK", Integer.toString(tag[126] & 0xFF));
        } else {
            putField(metadata, "COMM", tag, 97, 30);
        }
    }

    /**
     * Adds one fixed-width ISO-8859-1 ID3v1 field, trimmed of padding, unless the key is present.
     *
     * @param metadata the metadata map
     * @param key      the ID3v2-style key
     * @param tag      the tag bytes
     * @param offset   the field offset
     * @param length   the field width
     */
    private static void putField(Map<String, String> metadata, String key, byte[] tag, int offset, int length) {
        var end = offset;
        while (end < offset + length && tag[end] != 0) {
            end++;
        }
        var value = new String(tag, offset, end - offset, StandardCharsets.ISO_8859_1).strip();
        if (!value.isEmpty()) {
            metadata.putIfAbsent(key, value);
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MPEG_AUDIO;
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
     * @throws MediaException.Malformed if the stream is corrupt beyond resynchronization
     */
    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a stream of known size
     */
    @Override
    public boolean isSeekable() {
        return firstAudio >= 0 && regionEnd != Long.MAX_VALUE;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "an MPEG audio stream of unknown size cannot seek");
        }
        try {
            var target = micros <= 0 ? 0 : micros > Long.MAX_VALUE / trackRate ? Long.MAX_VALUE / 4
                    : micros * trackRate / 1_000_000;
            if (regionEnd - regionStart <= SCAN_LIMIT) {
                seekExactly(target);
            } else {
                seekEstimated(target);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt MPEG audio stream", exception);
        }
    }

    /**
     * Seeks through the frame index, building it on first use.
     *
     * @param target the target in the track's time base
     */
    private void seekExactly(long target) {
        if (index == null) {
            buildIndex();
        }
        replaying = false;
        finished = false;
        kept.clear();
        pendingFirst = false;
        if (index.size() == 0 || target >= indexSamples) {
            cursor.restore(indexEnd, firstReference, firstFreeFormat, indexEnd);
            return;
        }
        var entry = Math.max(0, index.floor(target));
        var position = index.offset(entry);
        var reference = index.state(entry);
        var freeFormat = index.extra(entry);
        var pts = index.timestamp(entry);
        cursor.restore(position, reference, freeFormat, indexEnd);
        while (true) {
            var savedPosition = cursor.position();
            var savedReference = cursor.reference();
            var savedFreeFormat = cursor.freeFormatBytes();
            var savedEnd = cursor.end();
            if (!cursor.next()) {
                return;
            }
            var rate = MpegAudioHeader.sampleRate(cursor.frameHeader);
            var next = pts + Rational.timeBaseOf(rate).rescale(MpegAudioHeader.samplesPerFrame(cursor.frameHeader),
                    Rational.timeBaseOf(trackRate));
            if (next > target) {
                cursor.restore(savedPosition, savedReference, savedFreeFormat, savedEnd);
                startSegment(pts, rate);
                return;
            }
            pts = next;
        }
    }

    /**
     * Restarts the timestamp segments at a frame.
     *
     * @param pts  the timestamp of the next frame, in the track's time base
     * @param rate the sampling rate of the next frame
     */
    private void startSegment(long pts, int rate) {
        segmentBase = pts;
        segmentSamples = 0;
        segmentRate = rate;
    }

    /**
     * Walks every frame from the first audio frame, indexing every sixteenth one.
     */
    private void buildIndex() {
        var walk = new FrameCursor(source, regionStart, regionEnd);
        walk.restore(firstAudio, firstReference, firstFreeFormat, regionEnd);
        var built = new SeekIndex();
        var base = 0L;
        var samples = 0L;
        var rate = trackRate;
        var count = 0L;
        while (true) {
            var position = walk.position();
            var reference = walk.reference();
            var freeFormat = walk.freeFormatBytes();
            if (!walk.next()) {
                break;
            }
            var frameRate = MpegAudioHeader.sampleRate(walk.frameHeader);
            if (frameRate != rate) {
                base += Rational.timeBaseOf(rate).rescale(samples, Rational.timeBaseOf(trackRate));
                samples = 0;
                rate = frameRate;
            }
            var pts = base + Rational.timeBaseOf(rate).rescale(samples, Rational.timeBaseOf(trackRate));
            if (count % 16 == 0) {
                var compatible = reference != 0 && MpegAudioHeader.isCompatible(reference, walk.frameHeader);
                built.add(pts, compatible ? position : walk.frameOffset, compatible ? reference : walk.reference(),
                        compatible ? freeFormat : walk.freeFormatBytes());
            }
            samples += MpegAudioHeader.samplesPerFrame(walk.frameHeader);
            count++;
        }
        indexSamples = base + Rational.timeBaseOf(rate).rescale(samples, Rational.timeBaseOf(trackRate));
        indexEnd = walk.end();
        index = built;
    }

    /**
     * Jumps to the byte position the Xing seek table or the first frame's bitrate gives for a
     * target, estimating the timestamps from there.
     *
     * @param target the target in the track's time base
     */
    private void seekEstimated(long target) {
        var samplesPerFrame = MpegAudioHeader.samplesPerFrame(firstReference);
        var payload = regionEnd - firstAudio;
        var total = vbr != null && vbr.frames() > 0 ? vbr.frames() * samplesPerFrame : -1L;
        long position;
        if (total > 0 && vbr.toc() != null) {
            var bytes = vbr.bytes() > 0 ? vbr.bytes() : payload;
            var percent = Math.min(99.999, 100.0 * target / total);
            var lower = (int) percent;
            var a = vbr.toc()[lower] & 0xFF;
            var b = lower < 99 ? vbr.toc()[lower + 1] & 0xFF : 256;
            var fraction = (a + (b - a) * (percent - lower)) / 256.0;
            position = firstAudio + (long) (fraction * bytes);
        } else {
            var frameBytes = Math.max(1, MpegAudioHeader.frameBytes(firstReference, firstFreeFormat));
            position = firstAudio + target / samplesPerFrame * frameBytes;
        }
        position = Math.clamp(position, firstAudio, regionEnd);
        replaying = false;
        finished = false;
        kept.clear();
        pendingFirst = false;
        if (position == firstAudio) {
            cursor.restore(firstAudio, firstReference, firstFreeFormat, regionEnd);
            startSegment(0, MpegAudioHeader.sampleRate(firstReference));
            return;
        }
        cursor.restore(position, firstReference, firstFreeFormat, regionEnd);
        if (!cursor.next()) {
            return;
        }
        var found = cursor.frameOffset;
        var estimate = (double) (found - firstAudio) / Math.max(1, payload);
        long frames;
        if (total > 0 && vbr.toc() != null) {
            var bytes = vbr.bytes() > 0 ? vbr.bytes() : payload;
            var fraction = (double) (found - firstAudio) / bytes * 256.0;
            var percent = 0;
            while (percent < 99 && (vbr.toc()[percent + 1] & 0xFF) <= fraction) {
                percent++;
            }
            var a = vbr.toc()[percent] & 0xFF;
            var b = percent < 99 ? vbr.toc()[percent + 1] & 0xFF : 256;
            var within = b > a ? (fraction - a) / (b - a) : 0;
            frames = Math.round((percent + within) / 100.0 * total / samplesPerFrame);
        } else {
            var frameBytes = Math.max(1, MpegAudioHeader.frameBytes(firstReference, firstFreeFormat));
            frames = total > 0 ? Math.round(estimate * total / samplesPerFrame) : (found - firstAudio) / frameBytes;
        }
        cursor.restore(found, cursor.reference(), cursor.freeFormatBytes(), cursor.end());
        startSegment(frames * samplesPerFrame, MpegAudioHeader.sampleRate(cursor.reference()));
    }

    @Override
    public Packet read() {
        try {
            if (replaying) {
                var packet = kept.poll();
                if (packet != null || finished) {
                    return packet;
                }
                replaying = false;
            }
            if (pendingFirst) {
                pendingFirst = false;
            } else if (!cursor.next()) {
                return null;
            }
            var data = new byte[cursor.frameLength];
            if (!cursor.copy(cursor.frameOffset, data, data.length)) {
                return null;
            }
            ForwardOnlySource.release(source, cursor.lowestRead());
            return packet(data, cursor.frameHeader);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt MPEG audio stream", exception);
        }
    }
}
