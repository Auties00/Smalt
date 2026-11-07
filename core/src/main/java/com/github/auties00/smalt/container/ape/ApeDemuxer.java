/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/APEHeader.cpp). Copyright (c) 2000 - 2026 Matthew T. Ashland;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.container.ape;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Monkey's Audio files ({@code .ape}): a header (the descriptor and header of version 3.98 and later, the
 * header of earlier versions), a seek table and the frames, optionally after an ID3v2 tag and followed by APEv2 and
 * ID3v1 tags.
 *
 * <p>The single {@link Codec#APE} track carries the header as its configuration, the file's sample rate, sample size
 * and channel count (with the default layout of the count), and its length in samples. Every packet is one frame,
 * in the form {@link Codec#APE} describes, timestamped in samples; frames are coded independently, so every packet
 * is a key frame. The APEv2 and ID3v1 tags at the end and an ID3v2 tag at the start become {@link #metadata()}.
 *
 * <p>A file of known size seeks to the frame holding the target sample through its seek table.
 *
 * <p>Instances are not thread-safe.
 */
public final class ApeDemuxer implements Demuxer {
    private static final int MAX_JUNK = 1 << 20;

    private static final int FLAG_8_BIT = 1;
    private static final int FLAG_HAS_PEAK_LEVEL = 4;
    private static final int FLAG_24_BIT = 8;
    private static final int FLAG_HAS_SEEK_ELEMENTS = 16;
    private static final int FLAG_CREATE_WAV_HEADER = 32;

    private final MediaSource source;

    private final MediaLimits limits;

    private final SourceReader reader;

    private final AudioTrack track;

    private final List<Track> tracks;

    private final Map<String, String> metadata;

    private final List<AttachedPicture> pictures;

    private final int version;

    private final long[] seekBytes;

    private final byte[] seekBits;

    private final int totalFrames;

    private final int blocksPerFrame;

    private final int finalFrameBlocks;

    private final long dataEnd;

    private int frame;

    /**
     * Constructs a demuxer over a Monkey's Audio file with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not a valid Monkey's Audio file
     * @throws MediaException.Io if reading fails
     */
    public ApeDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a Monkey's Audio file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not a valid Monkey's Audio file
     * @throws MediaException.LimitExceeded if the channel count or the seek table exceeds the limits of
     * {@code options}
     * @throws MediaException.Io if reading fails
     */
    public ApeDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.source = source;
        this.limits = options.limits();
        this.reader = new SourceReader(source, 64 * 1024);
        try {
            var size = source.size();
            var tags = new LinkedHashMap<String, String>();
            var annotations = new Annotations();
            var leading = Id3v2.read(source, 0, size < 0 ? Long.MAX_VALUE : size, tags, annotations);
            var audioEnd = size;
            if (size >= 0 && ForwardOnlySource.isCheapToReach(source, size)) {
                var tail = new TrailingTags.Tail(source, 0);
                tail.load(size);
                var trailing = TrailingTags.regions(tail, 0, size);
                audioEnd = trailing.isEmpty() ? size : trailing.getFirst().offset();
                TrailingTags.readMetadata(tail, List.of(), trailing, tags, annotations,
                        com.github.auties00.smalt.container.mpeg.internal.Id3v2.Merge.FILL);
            }
            this.metadata = Collections.unmodifiableMap(tags);
            this.pictures = annotations.pictures();
            var junk = find(leading.end());
            if (junk < 0) {
                throw new MediaException.Malformed("not a Monkey's Audio file");
            }
            reader.seek(junk + 4);
            this.version = reader.u16le();
            byte[] config;
            int level;
            int flags;
            int channels;
            int bits;
            long rate;
            long seekElements;
            long terminating;
            long frameDataBytes = -1;
            long tableStart;
            long blocks;
            long finalBlocks;
            long frames;
            long dataStart = -1;
            if (version >= 3980) {
                reader.seek(junk + 8);
                var descriptorBytes = reader.u32le();
                var headerBytes = reader.u32le();
                var seekTableBytes = reader.u32le();
                var headerDataBytes = reader.u32le();
                var frameLow = reader.u32le();
                var frameHigh = reader.u32le();
                terminating = reader.u32le();
                frameDataBytes = frameHigh << 32 | frameLow;
                if (descriptorBytes < 52 || descriptorBytes > 4096 || headerBytes < 24 || headerBytes > 4096) {
                    throw new MediaException.Malformed("Monkey's Audio descriptor of " + descriptorBytes + " bytes");
                }
                reader.seek(junk);
                config = reader.bytes(descriptorBytes + 24, 8192);
                var at = (int) descriptorBytes;
                level = u16(config, at);
                flags = u16(config, at + 2);
                blocks = u32(config, at + 4);
                finalBlocks = u32(config, at + 8);
                frames = u32(config, at + 12);
                bits = u16(config, at + 16);
                channels = u16(config, at + 18);
                rate = u32(config, at + 20);
                seekElements = seekTableBytes / 4;
                tableStart = junk + descriptorBytes + headerBytes;
                dataStart = tableStart + seekTableBytes + headerDataBytes;
            } else {
                reader.seek(junk);
                config = reader.bytes(32, 32);
                level = u16(config, 6);
                flags = u16(config, 8);
                channels = u16(config, 10);
                rate = u32(config, 12);
                var headerBytes = u32(config, 16);
                terminating = u32(config, 20);
                frames = u32(config, 24);
                finalBlocks = u32(config, 28);
                var at = junk + 32;
                if ((flags & FLAG_HAS_PEAK_LEVEL) != 0) {
                    at += 4;
                }
                if ((flags & FLAG_HAS_SEEK_ELEMENTS) != 0) {
                    reader.seek(at);
                    seekElements = reader.u32le();
                    at += 4;
                } else {
                    seekElements = frames;
                }
                if ((flags & FLAG_CREATE_WAV_HEADER) == 0) {
                    at += headerBytes;
                }
                tableStart = at;
                bits = (flags & FLAG_8_BIT) != 0 ? 8 : (flags & FLAG_24_BIT) != 0 ? 24 : 16;
                blocks = version >= 3900 || version >= 3800 && level == 4000 ? 73728 : 9216;
                if (version >= 3950) {
                    blocks = 73728 * 4;
                }
            }
            if (channels < 1 || channels > 32) {
                throw new MediaException.Malformed("Monkey's Audio file of " + channels + " channels");
            }
            LimitChecks.checkChannels(limits, channels);
            if (rate <= 0 || rate > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("Monkey's Audio sample rate " + rate);
            }
            if (blocks <= 0 || blocks > 10_000_000L || finalBlocks > blocks) {
                throw new MediaException.Malformed("Monkey's Audio frames of " + blocks + " samples");
            }
            if (frames > Integer.MAX_VALUE / 2 || seekElements < frames || seekElements > Integer.MAX_VALUE / 8) {
                throw new MediaException.Malformed("Monkey's Audio seek table of " + seekElements + " entries for "
                        + frames + " frames");
            }
            LimitChecks.checkAllocation(limits, seekElements * 9);
            this.totalFrames = (int) frames;
            this.blocksPerFrame = (int) blocks;
            this.finalFrameBlocks = (int) finalBlocks;
            this.seekBytes = new long[totalFrames];
            reader.seek(tableStart);
            if (reader.remaining(seekElements * 4) < seekElements * 4) {
                throw new MediaException.Malformed("truncated Monkey's Audio seek table");
            }
            long add = 0;
            long previous = 0;
            for (var i = 0; i < seekElements; i++) {
                var current = reader.u32le();
                if (i < totalFrames) {
                    if (current < previous) {
                        add += 1L << 32;
                    }
                    seekBytes[i] = add + current + junk;
                    previous = current;
                }
            }
            if (version <= 3800) {
                this.seekBits = new byte[totalFrames];
                if (reader.remaining(seekElements) < seekElements) {
                    throw new MediaException.Malformed("truncated Monkey's Audio seek bit table");
                }
                reader.readFully(seekBits, 0, totalFrames);
            } else {
                this.seekBits = null;
            }
            long end;
            if (size >= 0) {
                end = audioEnd - terminating;
            } else if (frameDataBytes >= 0 && totalFrames > 0) {
                end = dataStart + frameDataBytes;
            } else {
                end = Long.MAX_VALUE;
            }
            this.dataEnd = end;
            for (var i = 1; i < totalFrames; i++) {
                if (seekBytes[i] < seekBytes[i - 1]) {
                    throw new MediaException.Malformed("Monkey's Audio seek table out of order");
                }
            }
            var duration = totalFrames == 0 ? 0 : (long) (totalFrames - 1) * blocksPerFrame + finalFrameBlocks;
            this.track = AudioTrack.builder().codec(Codec.APE).timeBase(Rational.timeBaseOf(rate))
                    .duration(duration)
                    .sampleRate((int) rate)
                    .layout(ChannelLayout.defaultFor(channels))
                    .bitsPerSample(bits)
                    .codecConfig(config)
                    .build();
            this.tracks = List.of(track);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Monkey's Audio file", exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are a Monkey's Audio header.
     *
     * @param header the leading bytes
     * @return {@code true} for the {@code "MAC "} magic (or {@code "MACF"}, of floating-point files) followed by a
     * file version from 3.20 to 99.99
     */
    public static boolean matches(byte[] header) {
        if (header.length < 6 || header[0] != 'M' || header[1] != 'A' || header[2] != 'C'
                || header[3] != ' ' && header[3] != 'F') {
            return false;
        }
        var version = (header[4] & 0xFF) | (header[5] & 0xFF) << 8;
        return version >= 3200 && version <= 99_990;
    }

    /**
     * Finds the {@code "MAC "} magic within {@value #MAX_JUNK} bytes of {@code start}.
     */
    private long find(long start) {
        var window = new byte[4];
        for (var position = start; position < start + MAX_JUNK; position++) {
            reader.seek(position);
            if (reader.remaining(8) < 8) {
                return -1;
            }
            reader.readFully(window, 0, 4);
            if (window[0] == 'M' && window[1] == 'A' && window[2] == 'C' && (window[3] == ' ' || window[3] == 'F')) {
                return position;
            }
        }
        return -1;
    }

    private static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.APE;
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
        return track.timeBase().toMicros(track.duration());
    }

    @Override
    public Packet read() {
        try {
            if (frame >= totalFrames) {
                return null;
            }
            var seek = seekBytes[frame];
            var next = frame + 1 < totalFrames ? seekBytes[frame + 1] : dataEnd;
            long start;
            long skipBits;
            long length;
            if (seekBits == null) {
                var remainder = (seek - seekBytes[0]) % 4;
                start = seek - remainder;
                skipBits = remainder * 8;
                length = next - seek + remainder + 4;
            } else {
                start = seek;
                skipBits = seekBits[frame] & 0xFF;
                length = next - seek + 8;
            }
            if (source.size() >= 0 && start >= source.size()) {
                // a truncated file: the seek table names frames past its end
                return null;
            }
            if (next < seek || skipBits > 31) {
                throw new MediaException.Malformed("Monkey's Audio frame " + frame + " out of order");
            }
            if (next == Long.MAX_VALUE) {
                length = 16L << 20;
            }
            LimitChecks.checkAllocation(limits, length + 8);
            reader.seek(start);
            var available = (int) Math.min(length, Math.max(reader.remaining(length), 0));
            if (available < length - 4 - (seekBits == null ? 0 : 4) && next != Long.MAX_VALUE) {
                return null;
            }
            var data = new byte[8 + available];
            var blocks = frame + 1 < totalFrames ? blocksPerFrame : finalFrameBlocks;
            data[0] = (byte) blocks;
            data[1] = (byte) (blocks >>> 8);
            data[2] = (byte) (blocks >>> 16);
            data[3] = (byte) (blocks >>> 24);
            data[4] = (byte) skipBits;
            reader.readFully(data, 8, available);
            var pts = (long) frame * blocksPerFrame;
            frame++;
            ForwardOnlySource.release(source, Math.min(reader.position(), next - 3));
            return new Packet(track.id(), data, pts, pts, blocks, true);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Monkey's Audio frame", exception);
        }
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
     * <p>The next packet is the frame holding the target sample, and none for a target at or past the end.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown, or the source is a stream read once
     * and the target frame is before the next one
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "a Monkey's Audio file of unknown size cannot seek");
        }
        var target = micros <= 0 ? 0 : Rational.MICROSECONDS.rescale(micros, track.timeBase());
        var next = target >= track.duration() ? totalFrames : (int) (target / blocksPerFrame);
        if (next < frame && ForwardOnlySource.isForwardOnly(source)) {
            throw new MediaException.Unsupported("seek", "a Monkey's Audio stream read once cannot seek back");
        }
        frame = next;
    }
}
