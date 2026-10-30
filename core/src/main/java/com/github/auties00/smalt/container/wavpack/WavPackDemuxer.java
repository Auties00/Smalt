package com.github.auties00.smalt.container.wavpack;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads WavPack files ({@code .wv}): a sequence of {@code "wvpk"} blocks, each a 32-byte header and its metadata
 * sub-blocks, optionally with the matching correction file ({@code .wvc}) of a hybrid stream.
 *
 * <p>The single {@link Codec#WAVPACK} track has no configuration: every packet is one frame, the complete blocks
 * of every channel from the block flagged initial to the one flagged final, timestamped in samples from the first
 * block's index (a DSD stream's samples are its bytes, eight DSD samples per channel). Blocks without audio (the
 * wrapper of the original file, the MD5 signature) join the packet of the frame after them, or of the last frame
 * when they end the file. The sample rate, the channel count and mask, the
 * sample size (bytes per sample times 8 minus the block's shift, 32 for floating point, 24 for DSD, whose rate is
 * the DSD rate divided by 8) and the length come from the first audio block; a file whose first block does not
 * declare its total length is walked once to measure it. Data before the first block (up to 1 MiB) is skipped, and
 * the APEv2 and ID3v1 tags at the end become {@link #metadata()}.
 *
 * <p>With a correction file, each packet carries the complete correction blocks of its frame under the side data
 * key {@code "wavpack:correction"}; correction blocks are matched to frames by block index, and a frame without
 * one carries none.
 *
 * <p>Every packet is a key frame: seeking walks the block headers once to index the frames and positions the
 * reader at the frame holding the target sample.
 *
 * <p>Instances are not thread-safe.
 */
public final class WavPackDemuxer implements Demuxer {
    private static final int HEADER = 32;

    private static final int MAX_LEADING_JUNK = 1 << 20;

    private static final int[] SAMPLE_RATES = {6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100,
            48000, 64000, 88200, 96000, 192000};

    private static final int MONO_FLAG = 4;
    private static final int FLOAT_DATA = 0x80;
    private static final int INITIAL_BLOCK = 0x800;
    private static final int FINAL_BLOCK = 0x1000;
    private static final int DSD_FLAG = 0x80000000;

    private final MediaSource source;

    private final MediaLimits limits;

    private final SourceReader reader;

    private final SourceReader correction;

    private final AudioTrack track;

    private final List<Track> tracks;

    private final Map<String, String> metadata;

    private final List<AttachedPicture> pictures;

    private final long first;

    private final long initialIndex;

    private final byte[] header = new byte[HEADER];

    private final byte[] scratchHeader = new byte[HEADER];

    private SeekIndex index;

    private boolean finished;

    /**
     * Constructs a demuxer over a WavPack file with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input holds no WavPack block
     * @throws MediaException.Io if reading fails
     */
    public WavPackDemuxer(MediaSource source) {
        this(source, null, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a WavPack file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input holds no WavPack block
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits of {@code options}
     * @throws MediaException.Io if reading fails
     */
    public WavPackDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, null, options);
    }

    /**
     * Constructs a demuxer over a hybrid WavPack file and its correction file.
     *
     * @param source the input, not closed by this demuxer
     * @param correction the correction file ({@code .wvc}), not closed by this demuxer, or {@code null} for none
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input holds no WavPack block
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits of {@code options}
     * @throws MediaException.Io if reading fails
     */
    public WavPackDemuxer(MediaSource source, MediaSource correction, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.limits = options.limits();
        try {
            var size = source.size();
            var tags = new LinkedHashMap<String, String>();
            var annotations = new Annotations();
            var audioEnd = size;
            if (size >= 0 && ForwardOnlySource.isCheapToReach(source, size)) {
                var tail = new TrailingTags.Tail(source, 0);
                tail.load(size);
                var trailing = TrailingTags.regions(tail, 0, size);
                audioEnd = trailing.isEmpty() ? size : trailing.getFirst().offset();
                TrailingTags.readMetadata(tail, List.of(), trailing, tags, annotations,
                        com.github.auties00.smalt.container.mpeg.internal.Id3v2.Merge.REPLACE);
            }
            this.source = audioEnd == size ? source : new SlicedSource(source, 0, audioEnd);
            this.metadata = Collections.unmodifiableMap(tags);
            this.pictures = annotations.pictures();
            this.reader = new SourceReader(this.source, 64 * 1024);
            this.correction = correction == null ? null : new SourceReader(correction, 64 * 1024);
            this.first = findFirst(reader);
            if (first < 0) {
                throw new MediaException.Malformed("not a WavPack file");
            }
            reader.seek(first);
            this.track = describe();
            this.tracks = List.of(track);
            this.initialIndex = blockIndex(header);
            reader.seek(first);
            if (this.correction != null) {
                var start = findFirst(this.correction);
                this.correction.seek(Math.max(start, 0));
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WavPack file", exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are a WavPack block header.
     *
     * @param header the leading bytes
     * @return {@code true} for the {@code "wvpk"} magic followed by a supported stream version
     */
    public static boolean matches(byte[] header) {
        if (header.length < 10 || header[0] != 'w' || header[1] != 'v' || header[2] != 'p' || header[3] != 'k') {
            return false;
        }
        var version = (header[8] & 0xFF) | (header[9] & 0xFF) << 8;
        return version >= 0x402 && version <= 0x410;
    }

    /**
     * Finds the first block header within {@value #MAX_LEADING_JUNK} bytes of the start.
     */
    private static long findFirst(SourceReader reader) {
        reader.seek(0);
        var window = new byte[4];
        var position = 0L;
        while (position < MAX_LEADING_JUNK) {
            reader.seek(position);
            if (reader.remaining(HEADER) < HEADER) {
                return -1;
            }
            reader.readFully(window, 0, 4);
            if (window[0] == 'w' && window[1] == 'v' && window[2] == 'p' && window[3] == 'k') {
                return position;
            }
            position++;
        }
        return -1;
    }

    private static int i32(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | data[at + 3] << 24;
    }

    private static long blockIndex(byte[] header) {
        return (i32(header, 16) & 0xFFFFFFFFL) | (long) (header[10] & 0xFF) << 32;
    }

    private static long totalSamples(byte[] header) {
        var low = i32(header, 12);
        if (low == -1) {
            return -1;
        }
        return (low & 0xFFFFFFFFL) | (long) (header[11] & 0xFF) << 32;
    }

    /**
     * Reads the block header at the reader's position into {@code target}.
     *
     * @return {@code false} at the end of the blocks
     */
    private static boolean readHeader(SourceReader reader, byte[] target) {
        if (reader.remaining(HEADER) < HEADER) {
            return false;
        }
        reader.readFully(target, 0, HEADER);
        if (target[0] != 'w' || target[1] != 'v' || target[2] != 'p' || target[3] != 'k') {
            return false;
        }
        var size = i32(target, 4) & 0xFFFFFFFFL;
        if (size < HEADER - 8 || (size & 1) != 0) {
            throw new MediaException.Malformed("WavPack block of size " + size);
        }
        return true;
    }

    private static long blockSize(byte[] header) {
        return (i32(header, 4) & 0xFFFFFFFFL) + 8;
    }

    /**
     * Describes the track from the first audio block, leaving {@link #header} holding its header.
     */
    private AudioTrack describe() {
        while (true) {
            var start = reader.position();
            if (!readHeader(reader, header)) {
                throw new MediaException.Malformed("WavPack file without audio");
            }
            var size = blockSize(header);
            if (i32(header, 20) == 0) {
                reader.seek(start + size);
                continue;
            }
            var body = reader.bytes(size - HEADER, limits.maxBytes());
            var flags = i32(header, 24);
            var channels = 0;
            long mask = 0;
            var rate = 0;
            var multiplier = 1;
            var at = 0;
            while (body.length - at >= 2) {
                var id = body[at++] & 0xFF;
                var length = (body[at++] & 0xFF) << 1;
                if ((id & 0x80) != 0) {
                    if (body.length - at < 2) {
                        break;
                    }
                    length += (body[at++] & 0xFF) << 9;
                    length += (body[at++] & 0xFF) << 17;
                    id &= ~0x80;
                }
                var data = at;
                var odd = (id & 0x40) != 0;
                id &= ~0x40;
                var actual = odd ? length - 1 : length;
                if (body.length - at < length || actual < 0) {
                    break;
                }
                switch (id) {
                    case 0xd -> {
                        if (actual >= 6) {
                            channels = ((body[data] & 0xFF) | (body[data + 2] & 0xF) << 8) + 1;
                            mask = (body[data + 3] & 0xFF) | (body[data + 4] & 0xFF) << 8 | (body[data + 5] & 0xFF) << 16;
                            if (actual == 7) {
                                mask |= (long) (body[data + 6] & 0xFF) << 24;
                            }
                        } else if (actual > 0) {
                            channels = body[data] & 0xFF;
                            for (var i = 1; i < actual; i++) {
                                mask |= (long) (body[data + i] & 0xFF) << (8 * (i - 1));
                            }
                        }
                    }
                    case 0x27 -> {
                        if (actual == 3 || actual == 4) {
                            rate = (body[data] & 0xFF) | (body[data + 1] & 0xFF) << 8 | (body[data + 2] & 0xFF) << 16;
                            if (actual == 4) {
                                rate |= (body[data + 3] & 0x7F) << 24;
                            }
                        }
                    }
                    case 0xe -> {
                        if (actual >= 1 && (body[data] & 0xFF) <= 31) {
                            multiplier = 1 << (body[data] & 0xFF);
                        }
                    }
                    default -> {
                    }
                }
                at += length;
            }
            if (rate == 0) {
                var code = (flags >>> 23) & 0xF;
                rate = code == 15 ? 44100 : SAMPLE_RATES[code];
            }
            var dsd = (flags & DSD_FLAG) != 0;
            if (dsd) {
                rate *= multiplier;
            }
            if (channels == 0) {
                channels = (flags & MONO_FLAG) != 0 ? 1 : 2;
                mask = channels == 1 ? 4 : 3;
            }
            LimitChecks.checkChannels(limits, channels);
            var bits = dsd ? 24 : (flags & FLOAT_DATA) != 0 ? 32
                    : Math.max(((flags & 3) + 1) * 8 - ((flags >>> 13) & 0x1F), 1);
            var total = totalSamples(header);
            var startIndex = blockIndex(header);
            long duration = total >= 0 && startIndex == 0 ? total : -1;
            reader.seek(start);
            if (duration < 0) {
                duration = measure(start);
                reader.seek(start);
            }
            return AudioTrack.builder().codec(Codec.WAVPACK).timeBase(Rational.timeBaseOf(rate))
                    .duration(duration)
                    .sampleRate(rate)
                    .layout(layoutOf(channels, mask))
                    .bitsPerSample(bits)
                    .build();
        }
    }

    /**
     * Measures the length by walking the block headers, or returns -1 when the source cannot be walked cheaply.
     */
    private long measure(long start) {
        if (source.size() < 0 || !ForwardOnlySource.isCheapToReach(source, source.size())) {
            return -1;
        }
        var scan = new SourceReader(source, 64 * 1024);
        scan.seek(start);
        var scratch = new byte[HEADER];
        long firstIndex = -1;
        long end = -1;
        while (readHeader(scan, scratch)) {
            var samples = i32(scratch, 20) & 0xFFFFFFFFL;
            if (samples != 0) {
                var blockStart = blockIndex(scratch);
                if (firstIndex < 0) {
                    firstIndex = blockStart;
                }
                end = Math.max(end, blockStart + samples);
            }
            scan.seek(scan.position() - HEADER + blockSize(scratch));
        }
        return firstIndex < 0 ? -1 : end - firstIndex;
    }

    private static ChannelLayout layoutOf(int channels, long mask) {
        if (mask == 0) {
            return ChannelLayout.defaultFor(channels);
        }
        var speakers = new ArrayList<Speaker>(channels);
        var positions = Speaker.values();
        for (var bit = 0; bit <= Speaker.TOP_BACK_RIGHT.ordinal() && speakers.size() < channels; bit++) {
            if ((mask & (1L << bit)) != 0) {
                speakers.add(positions[bit]);
            }
        }
        while (speakers.size() < channels) {
            speakers.add(Speaker.UNKNOWN);
        }
        return new ChannelLayout(speakers);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.WAVPACK;
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
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    @Override
    public long durationMicros() {
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    @Override
    public Packet read() {
        try {
            if (finished) {
                return null;
            }
            var prefix = -1L;
            while (true) {
                var start = reader.position();
                if (!readHeader(reader, header)) {
                    finished = true;
                    return null;
                }
                var size = blockSize(header);
                var samples = i32(header, 20) & 0xFFFFFFFFL;
                if (samples == 0 || (i32(header, 24) & INITIAL_BLOCK) == 0) {
                    if (samples == 0 && prefix < 0) {
                        prefix = start;
                    }
                    reader.seek(start + size);
                    continue;
                }
                var frameIndex = blockIndex(header);
                var frameEnd = frameEnd(start, frameIndex);
                if (frameEnd < 0) {
                    finished = true;
                    return null;
                }
                frameEnd = trailingEnd(frameEnd);
                var packetStart = prefix >= 0 ? prefix : start;
                LimitChecks.checkAllocation(limits, frameEnd - packetStart);
                reader.seek(packetStart);
                var data = new byte[(int) (frameEnd - packetStart)];
                reader.readFully(data, 0, data.length);
                var pts = frameIndex - initialIndex;
                var packet = new Packet(track.id(), data, pts, pts, samples, true);
                if (correction != null) {
                    var blocks = correctionBlocks(frameIndex);
                    if (blocks != null) {
                        packet = packet.withSideData(PacketSideData.builder().extra("wavpack:correction", blocks)
                                .build());
                    }
                }
                ForwardOnlySource.release(source, reader.position());
                return packet;
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WavPack block", exception);
        }
    }

    /**
     * Returns the end of the frame starting at {@code start}: the end of its final block, or -1 when the input
     * ends within it.
     */
    private long frameEnd(long start, long frameIndex) {
        var position = start;
        var scratch = scratchHeader;
        var firstBlock = true;
        while (true) {
            reader.seek(position);
            if (!readHeader(reader, scratch)) {
                return firstBlock ? -1 : position;
            }
            var flags = i32(scratch, 24);
            if (!firstBlock && (blockIndex(scratch) != frameIndex || (flags & INITIAL_BLOCK) != 0)) {
                return position;
            }
            var size = blockSize(scratch);
            if (reader.remaining(size - HEADER) < size - HEADER) {
                return firstBlock ? -1 : position;
            }
            position += size;
            if ((flags & FINAL_BLOCK) != 0) {
                return position;
            }
            firstBlock = false;
        }
    }

    /**
     * Returns the end of the blocks without audio that follow a frame when only such blocks follow it up to the end
     * of the file, as the blocks that end a file (its MD5 signature, the trailer of the original file) do, or
     * {@code end} otherwise.
     */
    private long trailingEnd(long end) {
        var scratch = scratchHeader;
        var position = end;
        while (true) {
            reader.seek(position);
            if (!readHeader(reader, scratch)) {
                return position;
            }
            var size = blockSize(scratch);
            if (i32(scratch, 20) != 0 || reader.remaining(size - HEADER) < size - HEADER) {
                return end;
            }
            position += size;
        }
    }

    /**
     * Reads the correction blocks of the frame at {@code frameIndex}, skipping earlier ones.
     */
    private byte[] correctionBlocks(long frameIndex) {
        var out = new java.io.ByteArrayOutputStream();
        var scratch = scratchHeader;
        while (true) {
            var start = correction.position();
            if (!readHeader(correction, scratch)) {
                correction.seek(start);
                break;
            }
            var blockStart = blockIndex(scratch);
            var size = blockSize(scratch);
            if (blockStart > frameIndex) {
                correction.seek(start);
                break;
            }
            if (blockStart < frameIndex) {
                correction.seek(start + size);
                continue;
            }
            if (correction.remaining(size - HEADER) < size - HEADER) {
                break;
            }
            LimitChecks.checkAllocation(limits, out.size() + size);
            out.write(scratch, 0, HEADER);
            var body = new byte[(int) (size - HEADER)];
            correction.readFully(body, 0, body.length);
            out.write(body, 0, body.length);
            if ((i32(scratch, 24) & FINAL_BLOCK) != 0) {
                break;
            }
        }
        return out.size() == 0 ? null : out.toByteArray();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a file of known size
     */
    @Override
    public boolean isSeekable() {
        return source.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The next packet is the frame holding the target sample, and none for a target at or past the end; the
     * frames are indexed on the first seek by walking the block headers.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "a WavPack file of unknown size cannot seek");
        }
        try {
            if (index == null) {
                var built = new SeekIndex();
                var scan = new SourceReader(source, 64 * 1024);
                scan.seek(first);
                var scratch = new byte[HEADER];
                while (true) {
                    var start = scan.position();
                    if (!readHeader(scan, scratch)) {
                        break;
                    }
                    var flags = i32(scratch, 24);
                    if (i32(scratch, 20) != 0 && (flags & INITIAL_BLOCK) != 0) {
                        built.add(blockIndex(scratch) - initialIndex, start, 0, 0);
                    }
                    scan.seek(start + blockSize(scratch));
                }
                index = built;
            }
            var target = micros <= 0 ? 0 : Rational.MICROSECONDS.rescale(micros, track.timeBase());
            if (index.size() == 0 || track.duration() > 0 && target >= track.duration()) {
                finished = true;
                return;
            }
            var entry = Math.max(0, index.floor(target));
            reader.seek(index.offset(entry));
            if (correction != null) {
                correction.seek(Math.max(findFirst(correction), 0));
            }
            finished = false;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WavPack file", exception);
        }
    }
}
