package com.github.auties00.smalt.container.aiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Audio Interchange File Format files, both AIFF and AIFF-C.
 *
 * <p>The {@code COMM} chunk gives the channel count, frame count, sample size and the sampling
 * rate as an 80-bit IEEE 754 extended float, rounded to the nearest integer. AIFF samples are
 * big-endian two's-complement integers, left-justified in {@code ceil(bits / 8)} bytes, of 1 to 32 bits or of
 * exactly 64 bits in eight bytes. AIFF-C
 * adds a compression type; {@code NONE}/{@code twos} (big-endian integers), {@code sowt}
 * (little-endian integers), {@code in24}, {@code in32}, {@code raw } (unsigned 8-bit),
 * {@code fl32}/{@code FL32}, {@code fl64}/{@code FL64}, {@code alaw}/{@code ALAW} and
 * {@code ulaw}/{@code ULAW} are decodable; any other type raises
 * {@link MediaException.Unsupported} naming it.
 *
 * <p>Samples start at the {@code SSND} chunk's data offset; the frame count is the smaller of the
 * {@code COMM} count and what the {@code SSND} chunk holds. Files written by a writer that could
 * not seek back (a zero {@code COMM} frame count and a minimal {@code SSND} size) are read to the
 * end of the source. AIFF has no channel mask, so the
 * track uses {@link ChannelLayout#defaultFor(int)}. {@code NAME}, {@code AUTH}, {@code (c) } and
 * {@code ANNO} chunks and an embedded {@code ID3 } tag become {@link #metadata()}.
 *
 * <p>Packets hold as many whole frames as fit in 4096 bytes (at least one), with timestamps in
 * samples. A source that is not in memory is read in blocks of {@value #READ_BLOCK} bytes that
 * never extend past the sound data, rather than once per packet.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every packet which
 * bytes are no longer needed, and the chunks after {@code SSND} are read only when they lie within
 * the source's memory budget.
 */
public final class AiffDemuxer implements Demuxer {
    /**
     * The target packet size, in bytes.
     */
    private static final int PACKET_BYTES = 4096;

    /**
     * The largest text chunk decoded into metadata.
     */
    private static final int MAX_TEXT_SIZE = 64 * 1024;

    /**
     * The size of the blocks the sound data of a source that is not in memory is read in.
     *
     * @implNote This implementation reads 256 KiB at a time, 64 packets of 4 KiB, which turns one positional read per
     * packet into one per 64 packets for a file source at the cost of one copy from the block into each packet.
     */
    private static final int READ_BLOCK = 256 * 1024;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The container metadata.
     */
    private final Map<String, String> metadata;

    /**
     * The size of one interleaved frame in bytes.
     */
    private final int frameSize;

    /**
     * The number of frames per packet.
     */
    private final int packetFrames;

    /**
     * The offset of the first sample byte.
     */
    private final long dataStart;

    /**
     * The total number of frames.
     */
    private final long totalFrames;

    /**
     * The read-ahead window over the sound data.
     */
    private final ReadAheadCache data;

    /**
     * The index of the first frame of the next packet.
     */
    private long frameIndex;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a valid AIFF file
     * @throws MediaException.Unsupported if the file uses an unsupported compression type
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AiffDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a valid AIFF file
     * @throws MediaException.Unsupported if the file uses an unsupported compression type
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AiffDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private AiffDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        try {
            var reader = new SourceReader(source, 4096);
            if (reader.fourCc() != 0x464F524D) {
                throw new MediaException.Malformed("not an IFF FORM file");
            }
            reader.skip(4);
            var form = reader.fourCc();
            var aifc = form == 0x41494643;
            if (!aifc && form != 0x41494646) {
                throw new MediaException.Malformed("IFF file is not of form AIFF or AIFC");
            }
            var tags = new LinkedHashMap<String, String>();
            var size = source.size();
            var channels = -1;
            var frames = 0L;
            var bits = 0;
            var sampleRate = 0;
            var compression = 0x4E4F4E45;
            var soundStart = -1L;
            var soundEnd = -1L;
            var streaming = false;
            while (size < 0 ? !reader.atEnd() : size - reader.position() >= 8) {
                var id = reader.fourCc();
                var chunkSize = reader.u32be();
                var body = reader.position();
                switch (id) {
                    case 0x434F4D4D -> {
                        if (chunkSize < 18) {
                            throw new MediaException.Malformed("COMM chunk of " + chunkSize + " bytes is too small");
                        }
                        channels = reader.u16be();
                        frames = reader.u32be();
                        bits = (short) reader.u16be();
                        sampleRate = extendedToRate(reader);
                        if (aifc && chunkSize >= 22) {
                            compression = reader.fourCc();
                        }
                    }
                    case 0x53534E44 -> {
                        if (chunkSize != 0 && chunkSize < 8) {
                            throw new MediaException.Malformed("SSND chunk of " + chunkSize + " bytes is too small");
                        }
                        var offset = reader.u32be();
                        reader.skip(4);
                        soundStart = body + 8 + offset;
                        if (chunkSize == 0) {
                            // a writer that could not seek back leaves the SSND size at zero
                            soundEnd = size;
                            streaming = true;
                        } else {
                            soundEnd = size < 0 ? body + chunkSize : Math.min(size, body + chunkSize);
                        }
                    }
                    case 0x4E414D45 -> text(reader, chunkSize, "NAME", tags);
                    case 0x41555448 -> text(reader, chunkSize, "AUTH", tags);
                    case 0x28632920 -> text(reader, chunkSize, "(c) ", tags);
                    case 0x414E4E4F -> text(reader, chunkSize, "ANNO", tags);
                    case 0x49443320 -> {
                        if (size < 0 || chunkSize <= size - body) {
                            Id3v2.skip(source, body, body + chunkSize, tags);
                        }
                    }
                    default -> {
                    }
                }
                if (streaming || size >= 0 && chunkSize > size - body) {
                    break;
                }
                if (soundStart >= 0 && channels >= 0
                        && !ForwardOnlySource.isCheapToReach(source, body + chunkSize + (chunkSize & 1) + 8)) {
                    break;
                }
                reader.seek(body + chunkSize + (chunkSize & 1));
            }
            if (channels < 0) {
                throw new MediaException.Malformed("AIFF file without a COMM chunk");
            }
            if (soundStart < 0) {
                throw new MediaException.Malformed("AIFF file without an SSND chunk");
            }
            LimitChecks.checkChannels(limits, channels);
            if (bits < 1 || bits > 64) {
                throw new MediaException.Malformed("invalid AIFF sample size " + bits);
            }
            var codec = codec(compression, bits);
            this.frameSize = codec.pcmBitsPerSample().orElseThrow() / 8 * channels;
            this.packetFrames = Math.max(1, PACKET_BYTES / frameSize);
            LimitChecks.checkAllocation(limits, (long) packetFrames * frameSize);
            this.dataStart = soundStart;
            var available = soundEnd < 0 ? Long.MAX_VALUE : soundEnd < soundStart ? 0 : (soundEnd - soundStart) / frameSize;
            this.totalFrames = frames == 0 ? available : Math.min(frames, available);
            var limit = totalFrames > (Long.MAX_VALUE - soundStart) / frameSize ? Long.MAX_VALUE : soundStart + totalFrames * frameSize;
            this.data = new ReadAheadCache(source, READ_BLOCK, 0, limit);
            this.metadata = Collections.unmodifiableMap(tags);
            var validBits = switch (codec) {
                case PCM_ALAW, PCM_MULAW -> 8;
                case PCM_F32BE -> 32;
                case PCM_F64BE -> 64;
                default -> bits;
            };
            this.track = AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf(sampleRate))
                    .duration(totalFrames == Long.MAX_VALUE ? -1 : totalFrames)
                    .sampleRate(sampleRate)
                    .layout(ChannelLayout.defaultFor(channels))
                    .bitsPerSample(validBits)
                    .build();
            this.tracks = List.of(track);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AIFF file", exception);
        }
    }

    /**
     * Reads an 80-bit IEEE 754 extended float sampling rate.
     *
     * @param reader the reader positioned at the value
     * @return the rate rounded to the nearest integer
     * @throws MediaException.Malformed if the rate is not a positive value that fits an {@code int}
     */
    static int extendedToRate(SourceReader reader) {
        var signAndExponent = reader.u16be();
        var mantissa = reader.u64be();
        var exponent = signAndExponent & 0x7FFF;
        if ((signAndExponent & 0x8000) != 0 || exponent == 0x7FFF || mantissa == 0) {
            throw new MediaException.Malformed("invalid AIFF sample rate");
        }
        var value = Math.scalb((double) (mantissa >>> 1), exponent - 16383 - 62);
        var rate = Math.round(value);
        if (rate < 1 || rate > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid AIFF sample rate " + value);
        }
        return (int) rate;
    }

    /**
     * Maps a compression type and sample size to a codec.
     *
     * @param compression the AIFF-C compression type, {@code NONE} for plain AIFF
     * @param bits the declared sample size
     * @return the codec
     * @throws MediaException.Unsupported if the combination is not decodable
     */
    private static Codec codec(int compression, int bits) {
        return switch (compression) {
            case 0x4E4F4E45, 0x74776F73 -> switch ((bits + 7) / 8) {
                case 1 -> Codec.PCM_S8;
                case 2 -> Codec.PCM_S16BE;
                case 3 -> Codec.PCM_S24BE;
                case 4 -> Codec.PCM_S32BE;
                case 8 -> Codec.PCM_S64BE;
                default -> throw new MediaException.Unsupported("pcm_s" + bits + "be", bits + "-bit AIFF samples are not supported");
            };
            case 0x736F7774 -> switch ((bits + 7) / 8) {
                case 1 -> Codec.PCM_S8;
                case 2 -> Codec.PCM_S16LE;
                case 3 -> Codec.PCM_S24LE;
                case 4 -> Codec.PCM_S32LE;
                case 8 -> Codec.PCM_S64LE;
                default -> throw new MediaException.Unsupported("pcm_s" + bits + "le", bits + "-bit AIFF samples are not supported");
            };
            case 0x696E3234 -> Codec.PCM_S24BE;
            case 0x696E3332 -> Codec.PCM_S32BE;
            case 0x72617720 -> Codec.PCM_U8;
            case 0x666C3332, 0x464C3332 -> Codec.PCM_F32BE;
            case 0x666C3634, 0x464C3634 -> Codec.PCM_F64BE;
            case 0x616C6177, 0x414C4157 -> Codec.PCM_ALAW;
            case 0x756C6177, 0x554C4157 -> Codec.PCM_MULAW;
            default -> {
                var name = new String(new byte[]{(byte) (compression >>> 24), (byte) (compression >>> 16),
                        (byte) (compression >>> 8), (byte) compression}, StandardCharsets.ISO_8859_1);
                throw new MediaException.Unsupported("aifc compression '" + name + "'",
                        "AIFF-C compression type '" + name + "' is not supported");
            }
        };
    }

    /**
     * Decodes a text chunk into metadata.
     *
     * @param reader the reader positioned at the chunk body
     * @param chunkSize the chunk body size
     * @param key the metadata key
     * @param tags receives the text
     */
    private static void text(SourceReader reader, long chunkSize, String key, Map<String, String> tags) {
        if (chunkSize == 0 || chunkSize > MAX_TEXT_SIZE || chunkSize > reader.remaining()) {
            return;
        }
        var bytes = reader.bytes(chunkSize, MAX_TEXT_SIZE);
        var end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        var value = new String(bytes, 0, end, StandardCharsets.ISO_8859_1).strip();
        if (!value.isEmpty()) {
            tags.merge(key, value, (previous, next) -> previous + "; " + next);
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.AIFF;
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
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * Returns {@code true}: every sample frame of an AIFF file is at a computable offset.
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every packet of uncompressed audio is a key frame, so the next packet starts at the
     * sample frame displayed at {@code micros}.
     */
    @Override
    public void seek(long micros) {
        frameIndex = Math.min(totalFrames, frameAt(micros, track.sampleRate()));
    }

    /**
     * Converts a presentation time to the index of the sample frame displayed at it.
     *
     * @param micros the time in microseconds
     * @param sampleRate the sampling rate in hertz
     * @return the frame index, rounded down, {@code 0} for a negative time
     */
    private static long frameAt(long micros, int sampleRate) {
        if (micros <= 0) {
            return 0;
        }
        return BigInteger.valueOf(micros)
                .multiply(BigInteger.valueOf(sampleRate))
                .divide(BigInteger.valueOf(1_000_000))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    @Override
    public Packet read() {
        try {
            if (frameIndex >= totalFrames) {
                return null;
            }
            var frames = (int) Math.min(packetFrames, totalFrames - frameIndex);
            var data = new byte[frames * frameSize];
            var position = dataStart + frameIndex * frameSize;
            var done = this.data.readUpTo(position, data, 0, data.length);
            if (done < data.length) {
                frames = done / frameSize;
                if (frames == 0) {
                    frameIndex = totalFrames;
                    return null;
                }
                data = Arrays.copyOf(data, frames * frameSize);
            }
            var packet = new Packet(track.id(), data, frameIndex, frameIndex, frames, true);
            frameIndex += frames;
            ForwardOnlySource.release(source, dataStart + frameIndex * frameSize);
            return packet;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AIFF data", exception);
        }
    }
}
