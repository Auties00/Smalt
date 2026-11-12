/* Ports libmpcdec r475 (mpc_demux.c, streaminfo.c). Copyright (c) 2005-2009, The Musepack Development Team;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.container.musepack;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekIndex;
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
 * Reads Musepack files ({@code .mpc}): stream version 7 ({@code "MP+"}), a header and bit-packed frames, and stream
 * version 8 ({@code "MPCK"}), a sequence of keyed packets.
 *
 * <p>The single track is {@link Codec#MUSEPACK7} or {@link Codec#MUSEPACK8}, with the configuration that codec
 * describes, the stream's sample rate and channels (stereo for version 7) and its length in samples, the decoder's
 * delay and the stream's leading silence excluded (for version 7 at most the frames' samples less the delay, which
 * is all the decoder outputs). Packets are timestamped in samples of the decoded output, so the
 * first ones start before zero: version 7 packets are single frames of 1152 samples, version 8 packets are audio
 * packets. Every packet is a key frame, although a version 7 frame depends on the frames before it: a decode that
 * starts at one converges after a few frames. APEv2 and ID3v1 tags at the end and an ID3v2
 * tag at the start become {@link #metadata()}.
 *
 * <p>A file of known size seeks to the packet holding the target sample (a version 7 stream to the frame, whose
 * decode converges after a few frames), walking the frames or packets once to index them.
 *
 * <p>Instances are not thread-safe.
 */
public final class MusepackDemuxer implements Demuxer {
    /**
     * The delay of the decoder's synthesis filter bank, in samples.
     */
    private static final int SYNTH_DELAY = 481;

    private static final int FRAME_LENGTH = 1152;

    private static final int[] SAMPLE_RATES = {44100, 48000, 37800, 32000};

    private static final int SV7_HEADER = 28;

    private final MediaSource source;

    private final MediaLimits limits;

    private final SourceReader reader;

    private final AudioTrack track;

    private final List<Track> tracks;

    private final Map<String, String> metadata;

    private final List<AttachedPicture> pictures;

    private final boolean sv8;

    private final long headerPosition;

    private final long streamEnd;

    private final long totalFrames;

    private final int framesPerPacket;

    private final long delay;

    private final long firstPacket;

    /**
     * The bit position of the next version 7 frame's length field, from the header, or the byte offset of the next
     * version 8 packet.
     */
    private long position;

    private long index;

    private SeekIndex seekIndex;

    private final byte[] word = new byte[4];

    /**
     * Constructs a demuxer over a Musepack file with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not a valid Musepack file
     * @throws MediaException.Unsupported if the input is a Musepack stream version other than 7 and 8
     * @throws MediaException.Io if reading fails
     */
    public MusepackDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a Musepack file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not a valid Musepack file
     * @throws MediaException.Unsupported if the input is a Musepack stream version other than 7 and 8
     * @throws MediaException.Io if reading fails
     */
    public MusepackDemuxer(MediaSource source, DemuxerOptions options) {
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
            var audioEnd = size < 0 ? Long.MAX_VALUE : size;
            if (size >= 0 && ForwardOnlySource.isCheapToReach(source, size)) {
                var tail = new TrailingTags.Tail(source, 0);
                tail.load(size);
                var trailing = TrailingTags.regions(tail, 0, size);
                audioEnd = trailing.isEmpty() ? size : trailing.getFirst().offset();
                TrailingTags.readMetadata(tail, List.of(), trailing, tags, annotations, Id3v2.Merge.FILL);
            }
            this.metadata = Collections.unmodifiableMap(tags);
            this.pictures = annotations.pictures();
            this.headerPosition = leading.end();
            this.streamEnd = audioEnd;
            reader.seek(headerPosition);
            if (reader.remaining(4) < 4) {
                throw new MediaException.Malformed("not a Musepack file");
            }
            var magic = new byte[4];
            reader.readFully(magic, 0, 4);
            AudioTrack.Builder builder;
            if (magic[0] == 'M' && magic[1] == 'P' && magic[2] == '+') {
                if ((magic[3] & 15) != 7) {
                    throw new MediaException.Unsupported("musepack", "Musepack stream version " + (magic[3] & 15));
                }
                this.sv8 = false;
                reader.seek(headerPosition);
                var config = reader.bytes(SV7_HEADER, SV7_HEADER);
                if (config.length < SV7_HEADER) {
                    throw new MediaException.Malformed("truncated Musepack header");
                }
                var frames = u32(config, 4);
                var flags = u32(config, 8);
                var gapless = u32(config, 20);
                var maxBand = (int) ((flags >>> 24) & 63);
                var rate = SAMPLE_RATES[(int) ((flags >>> 16) & 3)];
                var lastFrameSamples = (gapless >>> 20) & 2047;
                var isGapless = (gapless >>> 31) != 0;
                if (maxBand == 0 || maxBand >= 32) {
                    throw new MediaException.Malformed("Musepack maximum band " + maxBand);
                }
                if (lastFrameSamples == 0) {
                    lastFrameSamples = FRAME_LENGTH;
                } else if (lastFrameSamples > FRAME_LENGTH) {
                    throw new MediaException.Malformed("Musepack last frame of " + lastFrameSamples + " samples");
                }
                var samples = frames * FRAME_LENGTH - (isGapless ? FRAME_LENGTH - lastFrameSamples : SYNTH_DELAY);
                this.totalFrames = frames;
                this.framesPerPacket = 1;
                this.delay = SYNTH_DELAY;
                this.firstPacket = 200;
                builder = AudioTrack.builder().codec(Codec.MUSEPACK7).codecConfig(config).sampleRate(rate)
                        .timeBase(Rational.timeBaseOf(rate)).layout(ChannelLayout.STEREO)
                        .duration(Math.max(Math.min(samples, frames * FRAME_LENGTH - SYNTH_DELAY), 0));
            } else if (magic[0] == 'M' && magic[1] == 'P' && magic[2] == 'C' && magic[3] == 'K') {
                this.sv8 = true;
                byte[] header = null;
                var at = headerPosition + 4;
                long audio = -1;
                while (audio < 0) {
                    reader.seek(at);
                    var block = block(at);
                    if (block == null) {
                        throw new MediaException.Malformed("Musepack stream without audio");
                    }
                    var key = block[0];
                    var payload = block[1];
                    var length = block[2];
                    if (key == ('A' << 8 | 'P')) {
                        audio = at;
                    } else if (key == ('S' << 8 | 'H')) {
                        if (length > 64) {
                            throw new MediaException.Malformed("Musepack stream header of " + length + " bytes");
                        }
                        reader.seek(payload);
                        header = reader.bytes(length, 64);
                    } else if (key == ('S' << 8 | 'E')) {
                        throw new MediaException.Malformed("Musepack stream without audio");
                    }
                    at = payload + length;
                }
                if (header == null) {
                    throw new MediaException.Malformed("Musepack stream without a stream header");
                }
                var info = StreamHeader.parse(header);
                this.totalFrames = -1;
                this.framesPerPacket = 1 << info.blockPower;
                this.delay = SYNTH_DELAY + info.leadingSilence;
                this.firstPacket = audio;
                LimitChecks.checkChannels(limits, info.channels);
                builder = AudioTrack.builder().codec(Codec.MUSEPACK8).codecConfig(header).sampleRate(info.rate)
                        .timeBase(Rational.timeBaseOf(info.rate)).layout(ChannelLayout.defaultFor(info.channels))
                        .duration(Math.max(info.samples - info.leadingSilence, 0));
            } else {
                throw new MediaException.Malformed("not a Musepack file");
            }
            this.track = builder.build();
            this.tracks = List.of(track);
            this.position = firstPacket;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Musepack file", exception);
        }
    }

    /**
     * The fields of a version 8 stream header.
     */
    record StreamHeader(long samples, long leadingSilence, int rate, int maxBand, int channels, boolean midSide,
                        int blockPower) {
        static StreamHeader parse(byte[] header) {
            var bits = new long[]{32};
            if (header.length < 6 || (header[4] & 0xFF) != 8) {
                throw new MediaException.Unsupported("musepack", "Musepack stream header version "
                        + (header.length > 4 ? header[4] & 0xFF : -1));
            }
            bits[0] = 40;
            var samples = varint(header, bits);
            var silence = varint(header, bits);
            var rateIndex = (int) read(header, bits, 3);
            var maxBand = (int) read(header, bits, 5) + 1;
            var channels = (int) read(header, bits, 4) + 1;
            var midSide = read(header, bits, 1) != 0;
            var power = (int) read(header, bits, 3) * 2;
            if (rateIndex >= SAMPLE_RATES.length || maxBand >= 32 || channels > 2 || silence > samples) {
                throw new MediaException.Malformed("invalid Musepack stream header");
            }
            return new StreamHeader(samples, silence, SAMPLE_RATES[rateIndex], maxBand, channels, midSide, power);
        }

        private static long read(byte[] data, long[] bit, int count) {
            var value = 0L;
            for (var i = 0; i < count; i++) {
                var at = bit[0]++;
                var index = (int) (at >>> 3);
                if (index >= data.length) {
                    throw new MediaException.Malformed("truncated Musepack stream header");
                }
                value = value << 1 | ((data[index] >>> (7 - (at & 7))) & 1);
            }
            return value;
        }

        private static long varint(byte[] data, long[] bit) {
            var value = 0L;
            for (var i = 0; i < 9; i++) {
                var b = read(data, bit, 8);
                value = value << 7 | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new MediaException.Malformed("invalid Musepack size");
        }
    }

    /**
     * Returns whether the leading bytes of a file are a Musepack header.
     *
     * @param header the leading bytes
     * @return {@code true} for {@code "MP+"} with stream version 7 and for {@code "MPCK"}
     */
    public static boolean matches(byte[] header) {
        if (header.length < 4 || header[0] != 'M' || header[1] != 'P') {
            return false;
        }
        return header[2] == '+' && (header[3] & 15) == 7 || header[2] == 'C' && header[3] == 'K';
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }

    /**
     * Reads the version 8 packet header at {@code at}.
     *
     * @return the key, the offset of the payload and its length, or {@code null} at the end of the stream
     */
    private long[] block(long at) {
        reader.seek(at);
        if (at + 3 > streamEnd || reader.remaining(3) < 3) {
            return null;
        }
        var key = reader.u8() << 8 | reader.u8();
        if ((key >>> 8) < 'A' || (key >>> 8) > 'Z' || (key & 0xFF) < 'A' || (key & 0xFF) > 'Z') {
            throw new MediaException.Malformed("invalid Musepack packet key");
        }
        long size = 0;
        var headerSize = 2;
        for (var i = 0; ; i++) {
            if (i == 9 || reader.remaining(1) < 1) {
                throw new MediaException.Malformed("invalid Musepack packet size");
            }
            var b = reader.u8();
            headerSize++;
            size = size << 7 | (b & 0x7F);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        if (size < headerSize) {
            throw new MediaException.Malformed("Musepack packet of " + size + " bytes");
        }
        return new long[]{key, at + headerSize, size - headerSize};
    }

    /**
     * Reads {@code count} bits, at most 32, of the version 7 stream from bit {@code bit} after the header position.
     */
    private long sv7Bits(long bit, int count) {
        var value = 0L;
        var remaining = count;
        var at = bit;
        while (remaining > 0) {
            var wordIndex = at >>> 5;
            var offset = headerPosition + wordIndex * 4;
            reader.seek(offset);
            if (reader.remaining(4) < 4) {
                return -1;
            }
            reader.readFully(word, 0, 4);
            var current = u32(word, 0);
            var inWord = (int) (at & 31);
            var take = Math.min(remaining, 32 - inWord);
            var chunk = (current >>> (32 - inWord - take)) & ((1L << take) - 1);
            value = value << take | chunk;
            remaining -= take;
            at += take;
        }
        return value;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MUSEPACK;
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
            return sv8 ? readSv8() : readSv7();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Musepack frame", exception);
        }
    }

    private Packet readSv7() {
        if (index >= totalFrames) {
            return null;
        }
        var length = sv7Bits(position, 20);
        if (length < 0) {
            return null;
        }
        var start = position;
        var bits = 20 + length + (index == totalFrames - 1 ? 11 : 0);
        var end = start + bits;
        var firstWord = start >>> 5;
        var lastWord = (end + 31) >>> 5;
        var words = lastWord - firstWord + 1;
        LimitChecks.checkAllocation(limits, words * 4 + 1);
        var data = new byte[(int) (1 + words * 4)];
        data[0] = (byte) (start & 31);
        reader.seek(headerPosition + firstWord * 4);
        var available = (int) Math.min(words * 4, Math.max(reader.remaining(words * 4), 0));
        if (available < (lastWord - firstWord) * 4) {
            return null;
        }
        reader.readFully(data, 1, available);
        var pts = index * FRAME_LENGTH - delay;
        index++;
        position = start + 20 + length;
        ForwardOnlySource.release(source, headerPosition + firstWord * 4);
        return new Packet(track.id(), data, pts, pts, FRAME_LENGTH, true);
    }

    private Packet readSv8() {
        while (true) {
            var block = block(position);
            if (block == null) {
                return null;
            }
            var key = (int) block[0];
            var payload = block[1];
            var length = block[2];
            position = payload + length;
            if (key == ('S' << 8 | 'E')) {
                position = streamEnd;
                return null;
            }
            if (key != ('A' << 8 | 'P')) {
                continue;
            }
            LimitChecks.checkAllocation(limits, length);
            reader.seek(payload);
            if (reader.remaining(length) < length) {
                return null;
            }
            var data = reader.bytes(length, Integer.MAX_VALUE);
            var pts = index * framesPerPacket * FRAME_LENGTH - delay;
            index++;
            ForwardOnlySource.release(source, position);
            return new Packet(track.id(), data, pts, pts, (long) framesPerPacket * FRAME_LENGTH, true);
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
     * <p>The next packet is the one holding the target sample, and none for a target at or past the end of the last
     * packet; the packets are indexed on the first seek.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "a Musepack file of unknown size cannot seek");
        }
        try {
            if (seekIndex == null) {
                seekIndex = buildIndex();
            }
            var target = micros <= 0 ? 0 : Rational.MICROSECONDS.rescale(micros, track.timeBase());
            var count = seekIndex.size();
            var end = count == 0 ? 0 : (long) count * framesPerPacket * FRAME_LENGTH - delay;
            if (target >= end) {
                index = sv8 ? Long.MAX_VALUE / 2 : totalFrames;
                position = sv8 ? streamEnd : position;
                return;
            }
            var entry = Math.max(0, seekIndex.floor(target));
            position = seekIndex.offset(entry);
            index = seekIndex.state(entry);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Musepack file", exception);
        }
    }

    private SeekIndex buildIndex() {
        var built = new SeekIndex();
        if (sv8) {
            var at = firstPacket;
            var count = 0;
            while (true) {
                var block = block(at);
                if (block == null || block[0] == ('S' << 8 | 'E')) {
                    break;
                }
                if (block[0] == ('A' << 8 | 'P')) {
                    built.add((long) count * framesPerPacket * FRAME_LENGTH - delay, at, count, 0);
                    count++;
                }
                at = block[1] + block[2];
            }
        } else {
            var at = firstPacket;
            for (var i = 0; i < totalFrames; i++) {
                built.add((long) i * FRAME_LENGTH - delay, at, i, 0);
                var length = sv7Bits(at, 20);
                if (length < 0) {
                    break;
                }
                at += 20 + length;
            }
        }
        return built;
    }
}
