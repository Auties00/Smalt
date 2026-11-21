package com.github.auties00.smalt.container.dts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.DtsFramesDemuxer;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads raw DTS elementary streams ({@code .dts}) and DTS-HD files ({@code .dtshd}): DTS frames back to back, as
 * ETSI TS 102 114 defines them, the latter wrapped in the chunks of the DTS-HD file format, whose {@code STRMDATA}
 * chunk holds the frames.
 *
 * <p>Every packet is one access unit, a core frame with the extension substream frames that follow it, or one
 * extension substream frame in a stream without core (DTS-HD Master Audio without core, DTS Express), in the 16-bit
 * big-endian form. The single {@link Codec#DTS} track declares the sample rate and layout of the richest
 * presentation the first unit describes (the extension substream's asset when there is one); timestamps count core
 * samples, or reference clock periods of the extension substream in a stream without core. Bytes between units
 * that start no valid frame are skipped. A raw stream stored in another form of DTS audio CDs (16-bit
 * little-endian words, or 14 bits of the bitstream in each 16-bit word of either byte order) is read too, its
 * packets converted to the 16-bit big-endian form.
 *
 * <p>For a file of known size up to 64 MiB the duration is exact: the units are walked once at construction, which
 * also indexes every sixteenth unit for {@link #seek(long)}; a larger file estimates the duration and the seek
 * position from the bit rate of the first unit.
 *
 * <p>Instances are not thread-safe.
 */
public final class DtsDemuxer implements Demuxer {
    /**
     * The largest file walked at construction for an exact duration and seek index.
     */
    private static final long MAX_WALK = 64L << 20;

    /**
     * The largest number of bytes searched for the first frame.
     */
    private static final int MAX_LEADING = 64 << 10;

    /**
     * The magic of the DTS-HD file format ({@code DTSHDHDR}).
     */
    private static final long DTSHD_MAGIC = 0x4454534844484452L;

    /**
     * The identifier of the chunk holding the frames ({@code STRMDATA}).
     */
    private static final long STREAM_DATA = 0x5354524D44415441L;

    /**
     * The number of header bytes read to size a frame.
     */
    private static final int HEADER = 16;

    /**
     * The demuxer of a raw stream stored in another form than 16-bit big-endian words, or {@code null}.
     */
    private final Demuxer repacked;

    /**
     * The source of the frames: the STRMDATA chunk of a DTS-HD file, else the whole input.
     */
    private final MediaSource source;

    /**
     * The reader of the frames.
     */
    private final SourceReader reader;

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The track.
     */
    private final AudioTrack track;

    /**
     * The track, as the list {@link #tracks()} returns.
     */
    private final List<Track> tracks;

    /**
     * The offset of the first access unit.
     */
    private final long start;

    /**
     * The duration of one access unit in reference clock periods.
     */
    private final int unitSamples;

    /**
     * The bit rate of the first access unit, which estimates the duration and seek offsets past the index.
     */
    private final long bitrate;

    /**
     * The offsets of every sixteenth access unit, {@code null} beyond the indexed size.
     */
    private final SeekIndex index;

    /**
     * The number of access units of an indexed file, {@code -1} otherwise.
     */
    private final long units;

    /**
     * The header bytes of the frame being sized.
     */
    private final byte[] header = new byte[HEADER];

    /**
     * The first bytes of the extension substream frame being sized.
     */
    private final byte[] substreamHeader = new byte[4096];

    /**
     * The timestamp of the next access unit.
     */
    private long timestamp;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no valid DTS frame starts the stream
     * @throws MediaException.Io if reading fails
     */
    public DtsDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no valid DTS frame starts the stream
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public DtsDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        this.limits = options.limits();
        if (startsRepacked(source)) {
            this.repacked = new DtsFramesDemuxer(source, 0, source.size(), limits, ContainerFormat.DTS);
            this.source = source;
            this.reader = null;
            this.track = (AudioTrack) repacked.tracks().getFirst();
            this.tracks = repacked.tracks();
            this.start = 0;
            this.unitSamples = 0;
            this.bitrate = 1;
            this.index = null;
            this.units = -1;
            return;
        }
        this.repacked = null;
        try {
            var probe = new SourceReader(source, 4096);
            var dataStart = 0L;
            var dataEnd = source.size();
            var chunked = false;
            if (probe.remaining(16) >= 16 && probe.u64be() == DTSHD_MAGIC) {
                chunked = true;
                probe.seek(0);
                var found = false;
                while (probe.remaining(16) >= 16) {
                    var id = probe.u64be();
                    var size = probe.u64be();
                    if (size < 0) {
                        throw new MediaException.Malformed("invalid DTS-HD chunk size");
                    }
                    if (id == STREAM_DATA) {
                        dataStart = probe.position();
                        dataEnd = source.size() < 0 ? dataStart + size : Math.min(source.size(), dataStart + size);
                        found = true;
                        break;
                    }
                    probe.seek(probe.position() + size);
                }
                if (!found) {
                    throw new MediaException.Malformed("DTS-HD file without a STRMDATA chunk");
                }
            }
            this.source = chunked || dataStart > 0
                    ? new SlicedSource(source, dataStart, dataEnd < 0 ? -1 : dataEnd - dataStart) : source;
            this.reader = new SourceReader(this.source, 16 * 1024);
            var first = findUnit(0, MAX_LEADING);
            if (first < 0) {
                throw new MediaException.Malformed("no DTS frame found");
            }
            this.start = first;
            reader.seek(first);
            var unit = readUnit();
            if (unit == null) {
                throw new MediaException.Malformed("truncated DTS frame");
            }
            var described = describe(unit);
            LimitChecks.checkChannels(limits, described.layout().map(ChannelLayout::channels).orElse(2));
            this.unitSamples = unitSamples(unit);
            var clockRate = described.timeBase().denominator();
            this.bitrate = Math.max(1, unit.length * 8L * clockRate / Math.max(1, unitSamples));
            var size = this.source.size();
            if (size >= 0 && size - first <= MAX_WALK && ForwardOnlySource.isCheapToReach(this.source, size)) {
                this.index = new SeekIndex();
                this.units = walk();
            } else {
                this.index = null;
                this.units = -1;
            }
            long duration;
            if (units >= 0) {
                duration = units * unitSamples;
            } else if (size >= 0) {
                duration = (size - first) * 8 * clockRate / bitrate;
            } else {
                duration = -1;
            }
            this.track = described.toBuilder().duration(duration).build();
            this.tracks = List.of(track);
            reader.seek(first);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DTS stream", exception);
        }
    }

    /**
     * Returns whether an input starts with a core frame in another form than 16-bit big-endian words.
     *
     * @param source the input
     * @return {@code true} when it does
     */
    private static boolean startsRepacked(MediaSource source) {
        var lead = new byte[16];
        var read = 0;
        while (read < lead.length) {
            var count = source.read(read, lead, read, lead.length - read);
            if (count <= 0) {
                break;
            }
            read += count;
        }
        return DtsFramesDemuxer.startsWithRepackedFrame(java.util.Arrays.copyOf(lead, read));
    }

    /**
     * Returns whether bytes start a DTS-HD file or a raw DTS stream: the {@code DTSHDHDR} magic, a core frame or
     * extension substream frame followed by another one or by the end of the bytes, or a core frame stored in
     * another form of DTS audio CDs.
     *
     * @param header the leading bytes of the input
     * @return {@code true} when the bytes look like a DTS stream
     */
    public static boolean matches(byte[] header) {
        if (header.length >= 8 && readLong(header, 0) == DTSHD_MAGIC) {
            return true;
        }
        if (DtsFramesDemuxer.startsWithRepackedFrame(header)) {
            return true;
        }
        var length = frameLength(header, 0, header.length);
        if (length <= 0) {
            return false;
        }
        if (length + 4 <= header.length) {
            return DtsConfigs.isSync(header, length) || DtsConfigs.isSubstreamSync(header, length);
        }
        return length <= header.length || header.length >= 512;
    }

    /**
     * Returns the length of the frame that starts at an index, or {@code -1} when none starts there.
     */
    private static int frameLength(byte[] data, int offset, int available) {
        if (available < DtsConfigs.HEADER_SIZE + 1) {
            return -1;
        }
        if (DtsConfigs.isSync(data, offset)) {
            var size = DtsConfigs.frameSize(data, offset);
            return size >= DtsConfigs.MIN_FRAME_SIZE && DtsConfigs.sampleRate(data, offset) > 0
                    && DtsConfigs.layout(data, offset) != null ? size : -1;
        }
        if (DtsConfigs.isSubstreamSync(data, offset)) {
            var size = DtsConfigs.substreamSize(data, offset, available);
            return size > 0 ? size : -1;
        }
        return -1;
    }

    /**
     * Reads the frame header at the reader's position without consuming it.
     *
     * @return the frame length, or {@code -1} when no valid frame starts there
     */
    private int peekFrame() {
        var position = reader.position();
        var available = (int) Math.min(HEADER, reader.remaining(HEADER));
        if (available < DtsConfigs.HEADER_SIZE + 1) {
            return -1;
        }
        reader.readFully(header, 0, available);
        reader.seek(position);
        if (DtsConfigs.isSubstreamSync(header, 0)) {
            var length = (int) Math.min(substreamHeader.length, reader.remaining(substreamHeader.length));
            reader.readFully(substreamHeader, 0, length);
            reader.seek(position);
            var size = DtsConfigs.substreamSize(substreamHeader, 0, length);
            return size > 0 ? size : -1;
        }
        return frameLength(header, 0, available);
    }

    /**
     * Searches for the first offset at which a frame is followed by another one or by the end of the input.
     *
     * @return the offset, or {@code -1} when none is found
     */
    private long findUnit(long from, long limit) {
        var position = from;
        var end = from + limit;
        while (position < end) {
            reader.seek(position);
            if (reader.remaining(4) < 4) {
                return -1;
            }
            var length = peekFrame();
            if (length > 0) {
                var next = position + length;
                if (source.size() >= 0 && next >= source.size()) {
                    if (next == source.size()) {
                        return position;
                    }
                } else {
                    reader.seek(next);
                    if (reader.remaining(4) < 4 || peekFrame() > 0) {
                        reader.seek(position);
                        return position;
                    }
                }
            }
            position++;
        }
        return -1;
    }

    /**
     * Reads the access unit at the reader's position: a core frame with the extension substream frames after it,
     * or an extension substream frame alone.
     *
     * @return the unit, or {@code null} when no complete frame starts there
     */
    private byte[] readUnit() {
        var unitStart = reader.position();
        var length = peekFrame();
        if (length < 0 || reader.remaining(length) < length) {
            return null;
        }
        var core = DtsConfigs.isSync(header, 0);
        var total = (long) length;
        reader.skip(length);
        if (core) {
            while (true) {
                var next = peekFrame();
                if (next < 0 || !DtsConfigs.isSubstreamSync(header, 0) || reader.remaining(next) < next) {
                    break;
                }
                total += next;
                reader.skip(next);
            }
        }
        LimitChecks.checkAllocation(limits, total);
        var unit = new byte[(int) total];
        reader.seek(unitStart);
        reader.readFully(unit, 0, unit.length);
        return unit;
    }

    /**
     * Describes the track from the first access unit.
     */
    private static AudioTrack describe(byte[] unit) {
        if (DtsConfigs.isSync(unit, 0)) {
            var rate = DtsConfigs.sampleRate(unit, 0);
            var layout = DtsConfigs.layout(unit, 0);
            var output = DtsConfigs.hasX96(unit, 0) ? 2 * rate : rate;
            var core = DtsConfigs.frameSize(unit, 0);
            if (core + 4 <= unit.length && DtsConfigs.isSubstreamSync(unit, core)) {
                var substream = DtsConfigs.describeSubstream(unit, core, unit.length - core);
                if (substream != null) {
                    output = Math.max(output, substream.sampleRate());
                    var asset = DtsConfigs.layoutOfMask(substream.speakerMask());
                    if (asset != null) {
                        layout = asset;
                    }
                }
            }
            return AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(rate)).sampleRate(output)
                    .layout(layout).build();
        }
        var substream = DtsConfigs.describeSubstream(unit, 0, unit.length);
        if (substream == null) {
            throw new MediaException.Malformed("DTS-HD extension substream without static fields");
        }
        var builder = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(substream.clockRate()))
                .sampleRate(substream.sampleRate());
        var layout = DtsConfigs.layoutOfMask(substream.speakerMask());
        if (layout != null) {
            builder.layout(layout);
        }
        return builder.build();
    }

    /**
     * {@return the timestamp increment of an access unit: its core samples, or the extension substream frame
     * duration in reference clock periods}
     */
    private static int unitSamples(byte[] unit) {
        if (DtsConfigs.isSync(unit, 0)) {
            return DtsConfigs.samples(unit, 0);
        }
        var substream = DtsConfigs.describeSubstream(unit, 0, unit.length);
        return substream != null ? substream.frameDuration() : 512;
    }

    /**
     * Walks the access units of the file, indexing every sixteenth.
     *
     * @return the number of complete access units
     */
    private long walk() {
        var count = 0L;
        reader.seek(start);
        while (true) {
            var unitStart = reader.position();
            var unit = reader.remaining(4) < 4 ? null : readUnit();
            if (unit == null) {
                if (reader.remaining(4) < 4) {
                    break;
                }
                var found = findUnit(unitStart + 1, MAX_LEADING);
                if (found < 0) {
                    break;
                }
                reader.seek(found);
                continue;
            }
            if (count % 16 == 0) {
                index.add(count, unitStart, 0, 0);
            }
            reader.seek(unitStart + unit.length);
            count++;
        }
        return count;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link ContainerFormat#DTS}
     */
    @Override
    public ContainerFormat format() {
        return ContainerFormat.DTS;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public long durationMicros() {
        if (repacked != null) {
            return repacked.durationMicros();
        }
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a stream of known size
     */
    @Override
    public boolean isSeekable() {
        if (repacked != null) {
            return repacked.isSeekable();
        }
        return source.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every access unit is a key frame. A walked file seeks to the exact unit holding the target; a larger one
     * estimates the byte position from the bit rate and resynchronizes on the next unit there, so the timestamps
     * that follow are estimates.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (repacked != null) {
            repacked.seek(micros);
            return;
        }
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "a DTS stream of unknown size cannot seek");
        }
        var position = reader.position();
        var current = timestamp;
        try {
            locate(micros);
        } catch (RuntimeException exception) {
            reader.seek(position);
            timestamp = current;
            if (exception instanceof MediaException media) {
                throw media;
            }
            throw new MediaException.Malformed("invalid DTS stream", exception);
        }
    }

    private void locate(long micros) {
        var clockRate = track.timeBase().denominator();
        var target = Math.max(0, micros) * clockRate / 1_000_000 / unitSamples;
        if (index != null) {
            if (target >= units || index.size() == 0) {
                reader.seek(source.size());
                timestamp = units * unitSamples;
                return;
            }
            var entry = Math.max(0, index.floor(target));
            var unit = index.timestamp(entry);
            reader.seek(index.offset(entry));
            while (unit < target) {
                var position = reader.position();
                var bytes = readUnit();
                if (bytes == null) {
                    break;
                }
                reader.seek(position + bytes.length);
                unit++;
            }
            timestamp = unit * unitSamples;
            return;
        }
        var position = start + target * unitSamples * bitrate / 8 / clockRate;
        var found = findUnit(Math.min(position, source.size()), MAX_LEADING);
        if (found < 0) {
            reader.seek(source.size());
            return;
        }
        reader.seek(found);
        timestamp = (found - start) * 8 * clockRate / bitrate / unitSamples * unitSamples;
    }

    @Override
    public Packet read() {
        if (repacked != null) {
            return repacked.read();
        }
        try {
            while (true) {
                if (reader.remaining(4) < 4) {
                    return null;
                }
                var position = reader.position();
                var unit = readUnit();
                if (unit != null) {
                    var packet = new Packet(track.id(), unit, timestamp, timestamp, unitSamples, true);
                    timestamp += unitSamples;
                    reader.seek(position + unit.length);
                    ForwardOnlySource.release(source, reader.position());
                    return packet;
                }
                var found = findUnit(position + 1, MAX_LEADING);
                if (found < 0) {
                    reader.seek(source.size() >= 0 ? source.size() : Long.MAX_VALUE / 4);
                    return null;
                }
                reader.seek(found);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DTS stream", exception);
        }
    }

    private static long readLong(byte[] data, int offset) {
        var value = 0L;
        for (var i = 0; i < 8; i++) {
            value = value << 8 | data[offset + i] & 0xFF;
        }
        return value;
    }
}
