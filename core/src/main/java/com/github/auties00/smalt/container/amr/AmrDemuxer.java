package com.github.auties00.smalt.container.amr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads AMR storage files as defined by RFC 4867 section 5: the magic {@code "#!AMR\n"}
 * (AMR-NB, 8 kHz) or {@code "#!AMR-WB\n"} (AMR-WB, 16 kHz) followed by speech frames.
 *
 * <p>Every frame is a one-byte header ({@code P FT(4) Q P(2)}) followed by the frame's class
 * A/B/C bits packed into the number of bytes 3GPP TS 26.101 (AMR-NB) and TS 26.201 (AMR-WB)
 * assign to its frame type. Each packet carries exactly one frame, header byte included, lasts
 * 20 ms (160 samples at 8 kHz or 320 at 16 kHz), and is timestamped in samples. The multichannel
 * variants ({@code "#!AMR_MC1.0\n"}, {@code "#!AMR-WB_MC1.0\n"}) raise
 * {@link MediaException.Unsupported}. Frames of a reserved type carry no speech bits and are
 * returned as their bare header byte; a frame cut short by the end of the file is dropped.
 *
 * <p>The duration is computed exactly by walking the frame headers once at construction. When
 * the source is not in memory, the walk also keeps the packets of the frames it passes, up to
 * {@value #MAX_KEPT_BYTES} bytes of them, and {@link #read()} returns those before reading
 * on from the first frame the walk did not keep, so a file of up to that size is read once.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every frame which
 * bytes are no longer needed, and its frames are counted at construction only when the whole
 * input fits the source's memory budget.
 *
 * @implNote This implementation keeps the walk's packets because the walk reads every byte of the file anyway (frames
 * are a few dozen bytes apart), and reading the frames a second time would fetch a network source twice. The kept
 * packets are the ones {@link #read()} would allocate anyway, so the total allocation is unchanged; only their lifetime
 * grows, bounded by the budget (or {@link MediaLimits#maxBytes()} when smaller). An in-memory source keeps nothing,
 * since reading it again costs no input.
 */
public final class AmrDemuxer implements Demuxer {
    /**
     * The largest number of bytes of packets the construction-time walk keeps for
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
     * @implNote This implementation caps it at 16384 slots (64 KiB of references), more than the frames of a few
     * minutes of speech, so a crafted file cannot make the queue allocate much ahead of the packets it actually keeps;
     * the queue still grows past it when needed.
     */
    private static final int MAX_EXPECTED_KEPT = 1 << 14;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * Whether the file is AMR-WB.
     */
    private final boolean wideband;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The buffered reader over the frames.
     */
    private final SourceReader reader;

    /**
     * The number of samples of one frame.
     */
    private final int frameSamples;

    /**
     * The timestamp of the next packet.
     */
    private long timestamp;

    /**
     * Whether the end of the frames was reached, or is known to lie right after the packets in
     * {@link #kept}.
     */
    private boolean finished;

    /**
     * Whether {@link #read()} starts from the packets in {@link #kept}: set when the
     * construction-time walk kept packets or found the end, so the other demuxes test one flag.
     */
    private boolean replaying;

    /**
     * The packets the construction-time walk kept and {@link #read()} has not returned.
     */
    private final ArrayDeque<Packet> kept;

    /**
     * The size of the storage file magic, where the first frame starts.
     */
    private int headerSize;

    /**
     * The index of every sixteenth frame, built on the first seek, or {@code null}.
     */
    private SeekIndex index;

    /**
     * The number of whole frames the walk that built {@link #index} counted.
     */
    private long indexFrames;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file does not start with an AMR magic
     * @throws MediaException.Unsupported if the file is a multichannel AMR file
     * @throws MediaException.Io if reading fails
     */
    public AmrDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file does not start with an AMR magic
     * @throws MediaException.Unsupported if the file is a multichannel AMR file
     * @throws MediaException.Io if reading fails
     */
    public AmrDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private AmrDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        try {
            var magic = new byte[15];
            var read = 0;
            while (read < magic.length) {
                var count = source.read(read, magic, read, magic.length - read);
                if (count <= 0) {
                    break;
                }
                read += count;
            }
            int headerSize;
            if (startsWith(magic, read, "#!AMR-WB_MC1.0\n") || startsWith(magic, read, "#!AMR_MC1.0\n")) {
                throw new MediaException.Unsupported("amr multichannel", "multichannel AMR storage files are not supported");
            } else if (startsWith(magic, read, "#!AMR-WB\n")) {
                wideband = true;
                headerSize = 9;
            } else if (startsWith(magic, read, "#!AMR\n")) {
                wideband = false;
                headerSize = 6;
            } else {
                throw new MediaException.Malformed("not an AMR storage file");
            }
            this.headerSize = headerSize;
            var sampleRate = wideband ? 16000 : 8000;
            this.frameSamples = wideband ? 320 : 160;
            var budget = SegmentBacked.isInMemory(source) ? 0 : Math.min(MAX_KEPT_BYTES, limits.maxBytes());
            this.kept = new ArrayDeque<>(expectedKept(magic, read, headerSize, budget));
            this.reader = new SourceReader(source, 4096);
            var frames = countFrames(headerSize, budget);
            this.track = AudioTrack.builder().codec(wideband ? Codec.AMR_WB : Codec.AMR_NB)
                    .timeBase(Rational.timeBaseOf(sampleRate))
                    .duration(frames < 0 ? -1 : frames * frameSamples)
                    .sampleRate(sampleRate)
                    .layout(ChannelLayout.MONO)
                    .build();
            this.tracks = List.of(track);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AMR file", exception);
        }
    }

    /**
     * Returns whether the first bytes read match a magic string.
     *
     * @param data the bytes read
     * @param count the number of valid bytes
     * @param magic the ASCII signature expected at offset zero
     * @return {@code true} on a match
     */
    private static boolean startsWith(byte[] data, int count, String magic) {
        if (count < magic.length()) {
            return false;
        }
        for (var i = 0; i < magic.length(); i++) {
            if (data[i] != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Estimates how many packets the construction-time walk keeps, so that the queue holding them
     * is allocated once at about the right size instead of growing step by step.
     *
     * <p>The estimate assumes every frame has the type of the first, adds an eighth for frames of
     * other types, and is capped at {@value #MAX_EXPECTED_KEPT}.
     *
     * @param magic the leading bytes of the file
     * @param read the number of valid bytes in {@code magic}
     * @param headerSize the size of the magic
     * @param budget the bytes of packets that may be kept, {@code 0} to keep none
     * @return the initial queue capacity, {@code 0} when nothing is kept or the size is unknown
     */
    private int expectedKept(byte[] magic, int read, int headerSize, long budget) {
        var size = source.size();
        if (budget <= 0 || size < 0 || read <= headerSize) {
            return 0;
        }
        var frameBytes = 1 + payloadSize(magic[headerSize] & 0xFF);
        var frames = Math.min(budget / (frameBytes + PACKET_OVERHEAD), (size - headerSize) / frameBytes + 1);
        return (int) Math.min(MAX_EXPECTED_KEPT, frames + frames / 8);
    }

    /**
     * Counts the complete frames after the magic, keeping the packets of the frames visited
     * first, and positions {@link #reader} at the first frame not kept.
     *
     * <p>A frame is kept, with exactly the packet {@link #read()} would return for it, while
     * the packets fit {@code budget} and every byte of the frame can be read; keeping stops at
     * the first frame that fails either test. When every frame up to the end is kept, the
     * demuxer is marked finished, since {@link #read()} would meet the same end.
     *
     * @param headerSize the size of the magic
     * @param budget the bytes of packets that may be kept, {@code 0} to keep none
     * @return the frame count, or {@code -1} when the source size is unknown
     */
    private long countFrames(int headerSize, long budget) {
        var resume = (long) headerSize;
        if (source.size() < 0 || !ForwardOnlySource.isCheapToReach(source, source.size())) {
            reader.seek(resume);
            return -1;
        }
        var scan = new SourceReader(source, 16 * 1024);
        scan.seek(headerSize);
        var frames = 0L;
        var used = budget > 0 ? 0L : -1L;
        while (!scan.atEnd()) {
            var header = scan.u8();
            var size = payloadSize(header);
            if (scan.remaining(size) < size) {
                break;
            }
            var start = scan.position();
            if (used >= 0) {
                used = keep(scan, header, size, used, budget);
                if (used >= 0) {
                    resume = start + size;
                }
            }
            scan.seek(start + size);
            frames++;
        }
        finished = used >= 0;
        replaying = finished || !kept.isEmpty();
        reader.seek(resume);
        return frames;
    }

    /**
     * Reads the frame the walk stands in and queues its packet, unless it does not fit the
     * budget or cannot be read whole.
     *
     * @param scan the walk's reader, positioned after the frame header
     * @param header the frame header
     * @param size the payload size
     * @param used the bytes of the budget already used
     * @param budget the bytes of packets that may be kept
     * @return the bytes of the budget used after keeping the frame, or {@code -1} when keeping stops
     */
    private long keep(SourceReader scan, int header, int size, long used, long budget) {
        var cost = used + 1 + size + PACKET_OVERHEAD;
        if (cost > budget) {
            return -1;
        }
        var data = new byte[1 + size];
        data[0] = (byte) header;
        try {
            scan.readFully(data, 1, size);
        } catch (MediaException.Malformed truncated) {
            return -1;
        }
        kept.add(new Packet(0, data, timestamp, timestamp, frameSamples, true));
        timestamp += frameSamples;
        return cost;
    }

    /**
     * Returns the payload size of a frame from its header byte.
     *
     * @param header the frame header byte
     * @return the number of bytes following the header
     */
    private int payloadSize(int header) {
        return AmrConfigs.payloadSize(wideband, header >> 3);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.AMR;
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
     * @return {@code true} for a file of known size
     */
    @Override
    public boolean isSeekable() {
        return source.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every frame is a key frame lasting 20 milliseconds; the file is indexed on the first
     * seek by walking its frame headers, so the next packet is exactly the frame holding the
     * target sample.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "an AMR file of unknown size cannot seek");
        }
        try {
            if (index == null) {
                var built = new SeekIndex();
                var scan = new SourceReader(source, 16 * 1024);
                scan.seek(headerSize);
                var frames = 0L;
                while (!scan.atEnd()) {
                    var start = scan.position();
                    var size = payloadSize(scan.u8());
                    if (scan.remaining(size) < size) {
                        break;
                    }
                    if (frames % 16 == 0) {
                        built.add(frames, start, 0, 0);
                    }
                    scan.seek(start + 1 + size);
                    frames++;
                }
                indexFrames = frames;
                index = built;
            }
            var target = micros <= 0 ? 0 : micros / 20_000;
            replaying = false;
            kept.clear();
            if (target >= indexFrames || index.size() == 0) {
                finished = true;
                return;
            }
            var entry = Math.max(0, index.floor(target));
            var frame = index.timestamp(entry);
            var position = index.offset(entry);
            while (frame < target) {
                reader.seek(position);
                position += 1 + payloadSize(reader.u8());
                frame++;
            }
            reader.seek(position);
            timestamp = frame * frameSamples;
            finished = false;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AMR file", exception);
        }
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
            if (finished || reader.atEnd()) {
                finished = true;
                return null;
            }
            var header = reader.u8();
            var size = payloadSize(header);
            if (reader.remaining(size) < size) {
                finished = true;
                return null;
            }
            var data = new byte[1 + size];
            data[0] = (byte) header;
            try {
                reader.readFully(data, 1, size);
            } catch (MediaException.Malformed truncated) {
                finished = true;
                return null;
            }
            var packet = new Packet(track.id(), data, timestamp, timestamp, frameSamples, true);
            timestamp += frameSamples;
            ForwardOnlySource.release(source, reader.position());
            return packet;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AMR frame", exception);
        }
    }
}
