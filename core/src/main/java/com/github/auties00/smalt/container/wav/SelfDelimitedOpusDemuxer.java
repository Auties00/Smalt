package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Map;

/**
 * Reads Opus packets stored back to back in the self-delimiting framing of RFC 6716 Appendix B, the
 * layout of a WAVE {@code data} chunk of format tag {@code 0x704F}.
 *
 * <p>A self-delimited packet repeats the length of the frame a standard packet leaves implicit: the
 * single frame of code 0, the two equal frames of code 1, the second frame of code 2, the frames of a
 * constant-bitrate code 3 packet, and the last frame of a variable-bitrate one. Each packet is returned
 * in the standard framing of RFC 6716 section 3, without that length, and is checked against the
 * packet rules of section 3.4: frames of at most 1275 bytes, at least one frame and at most 120 ms per
 * packet. A packet that breaks them raises {@link MediaException.Malformed}, since the framing carries no
 * sync word to recover on; a packet cut short by the end of the data is dropped.
 *
 * <p>The track's {@code OpusHead} is the format's extra bytes when they hold one, and is otherwise
 * built from the channel count with a pre-skip of zero (one or two channels with mapping family 0,
 * three to eight with the Vorbis mapping of family 1). Timestamps count 48 kHz samples from
 * {@code -preSkip}, so the decoder's own pre-skip removal starts the output at zero, as Ogg Opus does.
 * The duration is exact when the data is at most 64 MiB, from a walk over the packet headers that
 * also indexes every sixteenth packet, and counts the packets before the first one that breaks the
 * rules; a larger stream is walked on its first seek.
 *
 * <p>Instances are not thread-safe.
 */
final class SelfDelimitedOpusDemuxer implements Demuxer {
    /**
     * The largest data walked at construction for an exact duration and seek index.
     */
    private static final long MAX_WALK = 64L << 20;

    /**
     * The largest frame, in bytes (RFC 6716 section 3.4, rule R2).
     */
    private static final int MAX_FRAME = 1275;

    /**
     * The longest packet, in 48 kHz samples (120 ms).
     */
    private static final int MAX_SAMPLES = 5760;

    /**
     * The largest packet header: TOC, frame count, padding length and 48 two-byte frame lengths.
     */
    private static final int MAX_HEADER = 2 + 64 + 2 * 48;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The offset of the first packet.
     */
    private final long dataStart;

    /**
     * The offset of the end of the packets, or {@link Long#MAX_VALUE} when unknown.
     */
    private final long dataEnd;

    /**
     * The window over the packets.
     */
    private final ReadAheadCache cache;

    /**
     * The pre-skip, in 48 kHz samples.
     */
    private final int preSkip;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The header bytes of the packet being parsed.
     */
    private final byte[] header = new byte[MAX_HEADER];

    /**
     * The index of every sixteenth packet, or {@code null} before the walk.
     */
    private SeekIndex index;

    /**
     * The number of samples of every packet, counted by the walk, or {@code -1} before it.
     */
    private long walkedSamples = -1;

    /**
     * The offset of the next packet.
     */
    private long position;

    /**
     * The timestamp of the next packet.
     */
    private long timestamp;

    /**
     * The timestamp before which packets are dropped after a seek.
     */
    private long skipBefore = Long.MIN_VALUE;

    /**
     * The length of the packet {@link #parse} parsed, in the self-delimiting framing.
     */
    private int storedLength;

    /**
     * The length of the packet {@link #parse} parsed, in the standard framing.
     */
    private int standardLength;

    /**
     * The index of the self-delimiting length field the standard framing drops.
     */
    private int droppedAt;

    /**
     * The size of the dropped length field, 1 or 2.
     */
    private int droppedSize;

    /**
     * The number of samples of the packet {@link #parse} parsed.
     */
    private int samples;

    /**
     * Constructs a demuxer over the packets of a data chunk.
     *
     * @param source the input, not closed by this demuxer
     * @param dataStart the offset of the first packet
     * @param dataEnd the offset of the end of the packets, or {@code -1} when unknown
     * @param channels the channel count of the format
     * @param inputRate the sampling rate of the format, informational
     * @param extra the extra bytes of the format
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the first packet is invalid or the {@code OpusHead} is malformed
     * @throws MediaException.Unsupported if the channel count is above 8 and the format holds no {@code OpusHead}
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    SelfDelimitedOpusDemuxer(MediaSource source, long dataStart, long dataEnd, int channels, long inputRate,
                             byte[] extra, MediaLimits limits) {
        this.source = source;
        this.dataStart = dataStart;
        this.dataEnd = dataEnd < 0 ? Long.MAX_VALUE : dataEnd;
        this.cache = new ReadAheadCache(source, 64 * 1024, MAX_HEADER, this.dataEnd);
        byte[] head;
        if (extra.length >= 19 && extra[0] == 'O' && extra[1] == 'p' && extra[2] == 'u' && extra[3] == 's'
                && extra[4] == 'H' && extra[5] == 'e' && extra[6] == 'a' && extra[7] == 'd') {
            head = extra;
        } else if (channels <= 8) {
            head = AudioConfigs.opusHead(channels, 0, inputRate, false);
        } else {
            throw new MediaException.Unsupported("opus " + channels + "ch",
                    channels + "-channel Opus in WAVE without an OpusHead has no channel mapping");
        }
        var config = AudioConfigs.parseOpusHead(head);
        LimitChecks.checkChannels(limits, config.channels());
        this.preSkip = config.preSkip();
        if (!parse(dataStart)) {
            throw new MediaException.Malformed("WAVE Opus data without a self-delimited packet");
        }
        var size = dataEnd - dataStart;
        if (dataEnd >= 0 && size <= MAX_WALK && ForwardOnlySource.isCheapToReach(source, dataEnd)) {
            walk();
        }
        this.track = AudioTrack.builder()
                .codec(Codec.OPUS)
                .timeBase(Rational.timeBaseOf(48_000))
                .duration(walkedSamples < 0 ? -1 : Math.max(0, walkedSamples - preSkip))
                .codecConfig(head)
                .sampleRate(48_000)
                .layout(ChannelLayout.defaultFor(config.channels()))
                .build();
        this.tracks = List.of(track);
        this.position = dataStart;
        this.timestamp = -preSkip;
    }

    /**
     * Reads a self-delimiting length field.
     *
     * @param offset the index of the field in {@link #header}
     * @param available the number of header bytes read
     * @return the length in its low 16 bits and the field size above them, or {@code -1} when the header is short
     */
    private int length(int offset, int available) {
        if (offset >= available) {
            return -1;
        }
        var first = header[offset] & 0xFF;
        if (first < 252) {
            return 1 << 16 | first;
        }
        if (offset + 1 >= available) {
            return -1;
        }
        return 2 << 16 | (first + 4 * (header[offset + 1] & 0xFF));
    }

    /**
     * Parses the header of the packet at an offset into {@link #storedLength}, {@link #standardLength},
     * {@link #droppedAt}, {@link #droppedSize} and {@link #samples}.
     *
     * @param at the offset of the packet
     * @return {@code false} at the end of the data, or when the packet is cut short by it
     * @throws MediaException.Malformed if the packet breaks the framing rules
     */
    private boolean parse(long at) {
        var available = cache.readUpTo(at, header, 0, MAX_HEADER);
        if (available <= 0) {
            return false;
        }
        var toc = header[0] & 0xFF;
        var code = toc & 3;
        int position;
        long body;
        int frames;
        switch (code) {
            case 0, 1 -> {
                var field = length(1, available);
                if (field < 0) {
                    return false;
                }
                var frame = field & 0xFFFF;
                frames = code == 0 ? 1 : 2;
                droppedAt = 1;
                droppedSize = field >>> 16;
                position = 1 + droppedSize;
                body = (long) frame * frames;
                checkFrame(frame);
            }
            case 2 -> {
                var first = length(1, available);
                var second = first < 0 ? -1 : length(1 + (first >>> 16), available);
                if (second < 0) {
                    return false;
                }
                frames = 2;
                droppedAt = 1 + (first >>> 16);
                droppedSize = second >>> 16;
                position = droppedAt + droppedSize;
                checkFrame(first & 0xFFFF);
                checkFrame(second & 0xFFFF);
                body = (first & 0xFFFF) + (second & 0xFFFF);
            }
            default -> {
                if (available < 2) {
                    return false;
                }
                var count = header[1] & 0xFF;
                frames = count & 0x3F;
                if (frames == 0) {
                    throw new MediaException.Malformed("self-delimited Opus packet of zero frames");
                }
                position = 2;
                long padding = 0;
                if ((count & 0x40) != 0) {
                    while (true) {
                        if (position >= 2 + 64) {
                            throw new MediaException.Malformed("invalid self-delimited Opus padding");
                        }
                        if (position >= available) {
                            return false;
                        }
                        var value = header[position++] & 0xFF;
                        padding += value == 255 ? 254 : value;
                        if (value != 255) {
                            break;
                        }
                    }
                }
                body = padding;
                if ((count & 0x80) == 0) {
                    var field = length(position, available);
                    if (field < 0) {
                        return false;
                    }
                    checkFrame(field & 0xFFFF);
                    droppedAt = position;
                    droppedSize = field >>> 16;
                    body += (long) (field & 0xFFFF) * frames;
                    position += droppedSize;
                } else {
                    for (var frame = 0; frame < frames; frame++) {
                        var field = length(position, available);
                        if (field < 0) {
                            return false;
                        }
                        checkFrame(field & 0xFFFF);
                        if (frame == frames - 1) {
                            droppedAt = position;
                            droppedSize = field >>> 16;
                        }
                        body += field & 0xFFFF;
                        position += field >>> 16;
                    }
                }
            }
        }
        var duration = frameSamples(toc) * frames;
        if (duration > MAX_SAMPLES) {
            throw new MediaException.Malformed("self-delimited Opus packet longer than 120 ms");
        }
        var total = position + body;
        if (total > dataEnd - at) {
            return false;
        }
        storedLength = (int) total;
        standardLength = storedLength - droppedSize;
        samples = duration;
        return true;
    }

    /**
     * Checks a frame length against RFC 6716 rule R2.
     *
     * @param length the frame length
     * @throws MediaException.Malformed if it exceeds 1275 bytes
     */
    private static void checkFrame(int length) {
        if (length > MAX_FRAME) {
            throw new MediaException.Malformed("Opus frame of " + length + " bytes");
        }
    }

    /**
     * Returns the duration of one frame of a TOC byte's configuration (RFC 6716 section 3.1).
     *
     * @param toc the TOC byte
     * @return the duration in 48 kHz samples
     */
    private static int frameSamples(int toc) {
        var config = toc >> 3;
        if (config < 12) {
            return (config & 3) == 3 ? 2880 : 480 << (config & 3);
        }
        if (config < 16) {
            return (config & 1) == 0 ? 480 : 960;
        }
        return 120 << (config & 3);
    }

    /**
     * Walks the packet headers of the data, counting the samples and indexing every sixteenth packet.
     */
    private void walk() {
        var walkIndex = new SeekIndex();
        var at = dataStart;
        var time = (long) -preSkip;
        var count = 0L;
        try {
            while (at < dataEnd && parse(at)) {
                if (count % 16 == 0) {
                    walkIndex.add(time, at, 0, 0);
                }
                time += samples;
                at += storedLength;
                count++;
            }
        } catch (MediaException.Malformed exception) {
            // the packets before the damage stay readable; read() reports it when it gets there
        }
        index = walkIndex;
        walkedSamples = time + preSkip;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.WAV;
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
     * @return {@code true} when the end of the data is known
     */
    @Override
    public boolean isSeekable() {
        return dataEnd != Long.MAX_VALUE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every packet is a key frame for this demuxer; the next packet is the one that holds the sample
     * presented at {@code micros}, with its exact timestamp.
     *
     * @throws MediaException.Unsupported if the end of the data is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "Opus in a WAVE file of unknown size cannot seek");
        }
        if (index == null) {
            walk();
        }
        var target = micros <= 0 ? -preSkip : micros / 1_000_000 * 48_000 + micros % 1_000_000 * 48_000 / 1_000_000;
        var entry = index.size() == 0 ? -1 : Math.max(0, index.floor(target));
        if (entry < 0) {
            position = dataEnd;
            timestamp = walkedSamples - preSkip;
        } else {
            position = index.offset(entry);
            timestamp = index.timestamp(entry);
        }
        skipBefore = target;
    }

    @Override
    public Packet read() {
        try {
            while (position < dataEnd && parse(position)) {
                var at = position;
                var time = timestamp;
                position += storedLength;
                timestamp += samples;
                if (time + samples <= skipBefore) {
                    continue;
                }
                var data = new byte[standardLength];
                cache.readUpTo(at, data, 0, droppedAt);
                cache.readUpTo(at + droppedAt + droppedSize, data, droppedAt, standardLength - droppedAt);
                ForwardOnlySource.release(source, position);
                return new Packet(track.id(), data, time, time, samples, true);
            }
            return null;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WAVE Opus data", exception);
        }
    }
}
