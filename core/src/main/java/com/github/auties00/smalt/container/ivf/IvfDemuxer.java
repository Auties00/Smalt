package com.github.auties00.smalt.container.ivf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads IVF files, the simple container of the VP8, VP9 and AV1 reference tools and test vectors.
 *
 * <p>An IVF file holds one video track: a 32-byte header ({@code DKIF} signature, codec FourCC,
 * frame size and time base) followed by frames, each a 12-byte header (little-endian size and
 * 64-bit timestamp) and the frame data. FourCC {@code VP80}, {@code VP90} and {@code AV01} map to
 * {@link Codec#VP8}, {@link Codec#VP9} and {@link Codec#AV1}; any other FourCC yields
 * {@link Codec#UNKNOWN_VIDEO}. Packets carry the stored timestamps, in the header's time base, and
 * {@link Packet#UNKNOWN_DURATION} (IVF does not record durations). A packet is flagged as a keyframe
 * when its data starts a VP8 or VP9 key frame or, for AV1, carries a sequence header OBU. A truncated
 * last frame ends the stream.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every frame which
 * bytes are no longer needed.
 */
public final class IvfDemuxer implements Demuxer {
    /**
     * The size of the file header.
     */
    private static final int HEADER_SIZE = 32;

    /**
     * The size of a frame header.
     */
    private static final int FRAME_HEADER_SIZE = 12;

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The reader positioned at the next frame header.
     */
    private final SourceReader reader;

    /**
     * The video track.
     */
    private final VideoTrack track;

    /**
     * The track list.
     */
    private final List<Track> tracks;

    /**
     * Whether the end of the stream was reached.
     */
    private boolean finished;

    /**
     * The offset of the first frame header.
     */
    private long firstFrame;

    /**
     * The frame count of the file header.
     */
    private long declaredFrames;

    /**
     * The offsets of the frame headers, filled on the first seek, or {@code null}.
     */
    private long[] frameOffsets;

    /**
     * The timestamps matching {@link #frameOffsets}.
     */
    private long[] frameTimes;

    /**
     * Whether each frame of {@link #frameOffsets} is a key frame.
     */
    private boolean[] frameKeys;

    /**
     * The number of frames indexed.
     */
    private int frameCount;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not an IVF file
     * @throws MediaException.Io if reading fails
     */
    public IvfDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not an IVF file
     * @throws MediaException.Io if reading fails
     */
    public IvfDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private IvfDemuxer(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        try {
            this.reader = new SourceReader(source, 64 * 1024);
            if (reader.remaining(HEADER_SIZE) < HEADER_SIZE) {
                throw new MediaException.Malformed("truncated IVF header");
            }
            var magic = reader.fourCc();
            if (magic != fourCc("DKIF")) {
                throw new MediaException.Malformed("not an IVF file");
            }
            reader.u16le();
            var headerSize = reader.u16le();
            var codecTag = reader.fourCc();
            var width = reader.u16le();
            var height = reader.u16le();
            var rate = reader.u32le();
            var scale = reader.u32le();
            var frames = reader.u32le();
            reader.u32le();
            if (headerSize > HEADER_SIZE) {
                reader.skip(headerSize - HEADER_SIZE);
            }
            Codec codec;
            if (codecTag == fourCc("AV01")) {
                codec = Codec.AV1;
            } else if (codecTag == fourCc("VP90")) {
                codec = Codec.VP9;
            } else if (codecTag == fourCc("VP80")) {
                codec = Codec.VP8;
            } else {
                codec = Codec.UNKNOWN_VIDEO;
            }
            var timeBase = rate > 0 && scale > 0 ? new Rational(scale, rate) : new Rational(1, 1000);
            Optional<Rational> frameRate = rate > 0 && scale > 0 ? Optional.of(new Rational(rate, scale))
                    : Optional.empty();
            this.track = VideoTrack.builder().codec(codec).timeBase(timeBase).width(width).height(height)
                    .frameRate(frameRate.orElse(null))
                    .build();
            this.tracks = List.of(track);
            if (frames < 0) {
                throw new MediaException.Malformed("invalid IVF frame count");
            }
            this.firstFrame = reader.position();
            this.declaredFrames = frames;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid IVF file", exception);
        }
    }

    /**
     * Returns the big-endian integer value of a FourCC, as {@link SourceReader#fourCc()} reads it.
     *
     * @param tag the four characters
     * @return the value
     */
    private static int fourCc(String tag) {
        return (tag.charAt(0) << 24) | (tag.charAt(1) << 16) | (tag.charAt(2) << 8) | tag.charAt(3);
    }

    /**
     * Returns whether a stream starts with the IVF signature.
     *
     * @param header the first bytes of the stream
     * @return {@code true} for an IVF file
     */
    public static boolean matches(byte[] header) {
        return header.length >= 4 && header[0] == 'D' && header[1] == 'K' && header[2] == 'I' && header[3] == 'F';
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.IVF;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duration is the frame count of the file header times the frame period the header's
     * rate and scale give, which assumes a constant frame rate.
     *
     * @return the duration in microseconds, or {@code -1} when the header declares no frame count
     *         or rate
     */
    @Override
    public long durationMicros() {
        if (declaredFrames <= 0 || track.frameRate().isEmpty()) {
            return -1;
        }
        var rate = track.frameRate().get();
        return Math.round(declaredFrames * 1_000_000.0 * rate.denominator() / rate.numerator());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}: the frame headers are indexed on the first seek
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The first seek walks every frame header, reading the start of each frame to tell key
     * frames apart, and keeps the offsets, timestamps and key frame flags; the demuxer then moves
     * to the last key frame whose timestamp is at or before the target. A target after the last
     * frame's timestamp plus the last frame interval ends the input.
     */
    @Override
    public void seek(long micros) {
        try {
            if (frameOffsets == null) {
                indexFrames();
            }
            var target = SeekTimes.floor(micros, track.timeBase());
            if (frameCount == 0) {
                finished = true;
                return;
            }
            var last = frameTimes[frameCount - 1];
            var interval = frameCount > 1 ? Math.max(1, last - frameTimes[frameCount - 2]) : 1;
            if (target >= last + interval) {
                finished = true;
                return;
            }
            var chosen = -1;
            for (var index = 0; index < frameCount; index++) {
                if (frameTimes[index] > target) {
                    break;
                }
                if (frameKeys[index]) {
                    chosen = index;
                }
            }
            if (chosen < 0) {
                chosen = 0;
                while (chosen < frameCount - 1 && !frameKeys[chosen]) {
                    chosen++;
                }
            }
            reader.seek(frameOffsets[chosen]);
            finished = false;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid IVF frame", exception);
        }
    }

    /**
     * Walks the frame headers from the first frame and records every frame.
     */
    private void indexFrames() {
        var scan = new SourceReader(reader.source(), 64 * 1024);
        scan.seek(firstFrame);
        var offsets = new long[256];
        var times = new long[256];
        var keys = new boolean[256];
        var count = 0;
        var probe = new byte[64];
        while (scan.remaining(FRAME_HEADER_SIZE) >= FRAME_HEADER_SIZE) {
            var start = scan.position();
            var size = scan.u32le();
            var pts = scan.u64le();
            if (size > scan.remaining(size)) {
                break;
            }
            LimitChecks.checkFrames(limits, count + 1L);
            var head = (int) Math.min(size, track.codec() == Codec.AV1 ? Math.min(size, 1 << 16) : probe.length);
            var bytes = head <= probe.length ? probe : new byte[head];
            scan.readFully(bytes, 0, head);
            var keyframe = keyframe(track.codec(), head == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, head));
            scan.seek(scan.position() + (size - head));
            if (count == offsets.length) {
                offsets = java.util.Arrays.copyOf(offsets, count * 2);
                times = java.util.Arrays.copyOf(times, count * 2);
                keys = java.util.Arrays.copyOf(keys, count * 2);
            }
            offsets[count] = start;
            times[count] = pts;
            keys[count] = keyframe;
            count++;
        }
        frameOffsets = offsets;
        frameTimes = times;
        frameKeys = keys;
        frameCount = count;
    }

    @Override
    public Packet read() {
        try {
            if (finished || reader.remaining(FRAME_HEADER_SIZE) < FRAME_HEADER_SIZE) {
                finished = true;
                return null;
            }
            var size = reader.u32le();
            var pts = reader.u64le();
            if (size > Integer.MAX_VALUE - 16 || size > reader.remaining(size)) {
                finished = true;
                return null;
            }
            LimitChecks.checkAllocation(limits, size);
            var data = new byte[(int) size];
            reader.readFully(data, 0, data.length);
            ForwardOnlySource.release(reader.source(), reader.position());
            return new Packet(track.id(), data, pts, pts, Packet.UNKNOWN_DURATION, keyframe(track.codec(), data));
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid IVF frame", exception);
        }
    }

    /**
     * Returns whether a frame's data starts a key frame.
     *
     * @param codec the codec of the stream
     * @param data  the frame data
     * @return {@code true} for a key frame
     */
    private static boolean keyframe(Codec codec, byte[] data) {
        if (data.length == 0) {
            return false;
        }
        return switch (codec) {
            case VP8 -> (data[0] & 1) == 0;
            case VP9 -> vp9Keyframe(data[0] & 0xFF);
            case AV1 -> av1HasSequenceHeader(data);
            default -> false;
        };
    }

    /**
     * Returns whether the first byte of a VP9 frame starts a key frame.
     *
     * @param b the first byte
     * @return {@code true} for a key frame
     */
    private static boolean vp9Keyframe(int b) {
        if ((b >> 6) != 2) {
            return false;
        }
        var profile = ((b >> 5) & 1) | ((b >> 3) & 2);
        var showExistingBit = profile == 3 ? 2 : 3;
        if (((b >> showExistingBit) & 1) != 0) {
            return false;
        }
        return ((b >> (showExistingBit - 1)) & 1) == 0;
    }

    /**
     * Returns whether an AV1 temporal unit carries a sequence header OBU.
     *
     * @param data the temporal unit
     * @return {@code true} when one of its OBUs is a sequence header
     */
    private static boolean av1HasSequenceHeader(byte[] data) {
        var pos = 0;
        while (pos < data.length) {
            var header = data[pos] & 0xFF;
            var type = (header >> 3) & 15;
            var extension = (header & 4) != 0;
            var hasSize = (header & 2) != 0;
            if (type == 1) {
                return true;
            }
            pos += extension ? 2 : 1;
            if (!hasSize) {
                return false;
            }
            long size = 0;
            for (var shift = 0; pos < data.length && shift < 56; shift += 7) {
                var b = data[pos++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (size > data.length - pos) {
                return false;
            }
            pos += (int) size;
        }
        return false;
    }
}
