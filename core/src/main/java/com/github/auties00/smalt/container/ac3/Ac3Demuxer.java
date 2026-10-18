package com.github.auties00.smalt.container.ac3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads raw AC-3 ({@code .ac3}) and E-AC-3 ({@code .ec3}, {@code .eac3}) elementary streams: sync
 * frames back to back, as ATSC A/52 and its Annex E define them.
 *
 * <p>Every packet is one access unit: an AC-3 sync frame, or an independent frame of substream 0
 * (E-AC-3, or AC-3 carrying an E-AC-3 channel extension) with the frames that follow it up to the
 * next one, which hold its dependent substreams and the other programs. The single track declares
 * the codec, the sampling rate and the layout of program 0 with its channel extensions, as the
 * first access unit describes them; timestamps count samples, 256 per audio block. Bytes between
 * frames that do not start a valid frame are skipped, and the demuxer resynchronizes on the next
 * frame whose length leads to another sync word or to the end of the input.
 *
 * <p>For a file of known size up to 64 MiB the duration is exact: the frame headers are walked once
 * at construction, which also indexes every sixteenth access unit for {@link #seek(long)}; larger
 * files estimate the duration and the seek position from the bit rate of the first access unit.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every sync frame
 * which bytes are no longer needed, and its frames are counted at construction only when the
 * whole input fits the source's memory budget; otherwise the duration is estimated from the
 * bitrate.
 */
public final class Ac3Demuxer implements Demuxer {
    /**
     * The largest file walked at construction for an exact duration and seek index.
     */
    private static final long MAX_WALK = 64L << 20;

    /**
     * The largest number of bytes searched for the first frame.
     */
    private static final int MAX_LEADING = 64 << 10;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The buffered reader.
     */
    private final SourceReader reader;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The track.
     */
    private final AudioTrack track;

    /**
     * The track list.
     */
    private final List<Track> tracks;

    /**
     * The offset of the first frame.
     */
    private final long start;

    /**
     * The samples per channel of one access unit.
     */
    private final int unitSamples;

    /**
     * The bit rate of the first access unit, in bits per second.
     */
    private final long bitrate;

    /**
     * The index of every sixteenth access unit by unit number, or {@code null} when not walked.
     */
    private final SeekIndex index;

    /**
     * The number of access units the walk counted, or {@code -1}.
     */
    private final long units;

    /**
     * The timestamp of the next packet, in samples.
     */
    private long timestamp;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no valid sync frame starts the stream
     * @throws MediaException.Io if reading fails
     */
    public Ac3Demuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no valid sync frame starts the stream
     * @throws MediaException.Io if reading fails
     */
    public Ac3Demuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private Ac3Demuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        try {
            this.reader = new SourceReader(source, 16 * 1024);
            var first = findFrame(0, MAX_LEADING);
            if (first < 0) {
                throw new MediaException.Malformed("no AC-3 sync frame found");
            }
            this.start = first;
            reader.seek(first);
            var unit = readUnit();
            if (unit == null) {
                throw new MediaException.Malformed("truncated AC-3 sync frame");
            }
            var stream = Ac3Configs.describe(unit, 0, unit.length);
            if (stream == null || stream.sampleRate() <= 0) {
                throw new MediaException.Malformed("invalid AC-3 sync frame");
            }
            LimitChecks.checkChannels(limits, stream.layout().channels());
            this.unitSamples = stream.samples();
            this.bitrate = Math.max(1, stream.bitrate());
            var size = source.size();
            if (size >= 0 && size - first <= MAX_WALK && ForwardOnlySource.isCheapToReach(source, size)) {
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
                duration = (size - first) * 8 * stream.sampleRate() / bitrate;
            } else {
                duration = -1;
            }
            this.track = AudioTrack.builder()
                    .codec(stream.codec())
                    .timeBase(Rational.timeBaseOf(stream.sampleRate()))
                    .duration(duration)
                    .sampleRate(stream.sampleRate())
                    .layout(stream.layout())
                    .build();
            this.tracks = List.of(track);
            reader.seek(first);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AC-3 stream", exception);
        }
    }

    /**
     * Returns whether bytes start an AC-3 or E-AC-3 stream: a valid sync frame followed by another
     * one, or by the end of the bytes.
     *
     * @param header the leading bytes of the input
     * @return {@code true} when the bytes look like a raw AC-3 or E-AC-3 stream
     */
    public static boolean matches(byte[] header) {
        var length = Ac3Configs.frameLength(header, 0, header.length);
        if (length <= 0) {
            return false;
        }
        if (length + 6 <= header.length) {
            return Ac3Configs.parse(header, 0, length) != null
                    && Ac3Configs.frameLength(header, length, header.length - length) > 0;
        }
        return length <= header.length ? Ac3Configs.parse(header, 0, length) != null : header.length >= 512;
    }

    /**
     * Searches for the first offset at which a sync frame is followed by another one.
     *
     * @param from  the first offset searched
     * @param limit the largest number of bytes searched
     * @return the offset, or {@code -1} when none is found
     */
    private long findFrame(long from, long limit) {
        var position = from;
        var end = from + limit;
        var header = new byte[6];
        while (position < end) {
            reader.seek(position);
            if (reader.remaining(6) < 6) {
                return -1;
            }
            reader.readFully(header, 0, 6);
            var length = Ac3Configs.frameLength(header, 0, 6);
            if (length > 0) {
                var next = position + length;
                if (source.size() >= 0 && next >= source.size()) {
                    return next == source.size() ? position : -1;
                }
                reader.seek(next);
                if (reader.remaining(6) < 6) {
                    return position;
                }
                reader.readFully(header, 0, 6);
                if (Ac3Configs.frameLength(header, 0, 6) > 0) {
                    return position;
                }
            }
            position++;
        }
        return -1;
    }

    /**
     * Reads the header bytes of the frame at the reader's position without consuming them.
     *
     * <p>A frame counts only when it is followed by another sync word or by the end of the input,
     * which rejects the false sync words of damaged data.
     *
     * @param header receives six bytes
     * @return the frame length, or {@code -1} when no valid frame starts there or the input ends
     */
    private int peekFrame(byte[] header) {
        var position = reader.position();
        if (reader.remaining(6) < 6) {
            return -1;
        }
        reader.readFully(header, 0, 6);
        var length = Ac3Configs.frameLength(header, 0, 6);
        var remaining = reader.remaining(Math.max(length + 2 - 6, 0)) + 6;
        var valid = length > 0 && remaining >= length;
        if (valid && remaining >= length + 2) {
            reader.seek(position + length);
            valid = reader.u8() == 0x0B && reader.u8() == 0x77;
        }
        reader.seek(position);
        return valid ? length : -1;
    }

    /**
     * Returns whether the frame whose header bytes are given starts an access unit.
     *
     * @param header the six header bytes
     * @return {@code true} for an AC-3 frame or an independent E-AC-3 frame of substream 0
     */
    private static boolean startsUnit(byte[] header) {
        if ((header[5] & 0xFF) >> 3 <= 10) {
            return true;
        }
        return ((header[2] & 0xFF) >> 6) != 1 && ((header[2] >> 3) & 0x07) == 0;
    }

    /**
     * Reads the access unit at the reader's position.
     *
     * @return the unit, or {@code null} when no complete frame starts there
     */
    private byte[] readUnit() {
        var header = new byte[6];
        var unitStart = reader.position();
        var length = peekFrame(header);
        if (length < 0) {
            return null;
        }
        var total = (long) length;
        reader.skip(length);
        while (true) {
            var next = peekFrame(header);
            if (next < 0 || startsUnit(header)) {
                break;
            }
            total += next;
            reader.skip(next);
        }
        LimitChecks.checkAllocation(limits, total);
        var unit = new byte[(int) total];
        reader.seek(unitStart);
        reader.readFully(unit, 0, unit.length);
        return unit;
    }

    /**
     * Walks the access units of the file, indexing every sixteenth.
     *
     * @return the number of complete access units
     */
    private long walk() {
        var header = new byte[6];
        var count = 0L;
        reader.seek(start);
        while (true) {
            var unitStart = reader.position();
            var length = peekFrame(header);
            if (length < 0 || !startsUnit(header)) {
                if (reader.remaining(6) < 6) {
                    break;
                }
                var found = findFrame(unitStart + 1, MAX_LEADING);
                if (found < 0) {
                    break;
                }
                reader.seek(found);
                continue;
            }
            if (count % 16 == 0) {
                index.add(count, unitStart, 0, 0);
            }
            reader.skip(length);
            while (true) {
                var next = peekFrame(header);
                if (next < 0 || startsUnit(header)) {
                    break;
                }
                reader.skip(next);
            }
            count++;
        }
        return count;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.AC3;
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
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a stream of known size
     */
    @Override
    public boolean isSeekable() {
        return source.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every access unit is a key frame. A walked file seeks to the exact unit holding the
     * target; a larger one estimates the byte position from the bit rate and resynchronizes on
     * the next access unit there, so the timestamps that follow are estimates.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "an AC-3 stream of unknown size cannot seek");
        }
        try {
            var sampleRate = track.sampleRate();
            var target = Math.max(0, micros) * sampleRate / 1_000_000 / unitSamples;
            if (index != null) {
                if (target >= units || index.size() == 0) {
                    reader.seek(source.size());
                    timestamp = units * unitSamples;
                    return;
                }
                var entry = Math.max(0, index.floor(target));
                var unit = index.timestamp(entry);
                reader.seek(index.offset(entry));
                var header = new byte[6];
                while (unit < target) {
                    var length = peekFrame(header);
                    if (length < 0) {
                        break;
                    }
                    reader.skip(length);
                    while (true) {
                        var next = peekFrame(header);
                        if (next < 0 || startsUnit(header)) {
                            break;
                        }
                        reader.skip(next);
                    }
                    unit++;
                }
                timestamp = unit * unitSamples;
                return;
            }
            var position = start + target * unitSamples * bitrate / 8 / sampleRate;
            var found = findFrame(Math.min(position, source.size()), MAX_LEADING);
            var header = new byte[6];
            while (found >= 0) {
                reader.seek(found);
                if (peekFrame(header) > 0 && startsUnit(header)) {
                    break;
                }
                found = findFrame(found + 1, MAX_LEADING);
            }
            if (found < 0) {
                reader.seek(source.size());
                return;
            }
            reader.seek(found);
            timestamp = (found - start) * 8 * sampleRate / bitrate / unitSamples * unitSamples;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AC-3 stream", exception);
        }
    }

    @Override
    public Packet read() {
        try {
            var header = new byte[6];
            while (true) {
                if (reader.remaining(6) < 6) {
                    return null;
                }
                var length = peekFrame(header);
                if (length > 0 && startsUnit(header)) {
                    break;
                }
                var found = findFrame(reader.position() + 1, MAX_LEADING);
                if (found < 0) {
                    reader.seek(source.size() >= 0 ? source.size() : Long.MAX_VALUE / 4);
                    return null;
                }
                reader.seek(found);
            }
            var unit = readUnit();
            if (unit == null) {
                return null;
            }
            var packet = new Packet(track.id(), unit, timestamp, timestamp, unitSamples, true);
            timestamp += unitSamples;
            ForwardOnlySource.release(source, reader.position());
            return packet;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AC-3 stream", exception);
        }
    }
}
