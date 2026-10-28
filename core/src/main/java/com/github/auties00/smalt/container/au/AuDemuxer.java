package com.github.auties00.smalt.container.au;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
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
 * Reads Sun/NeXT audio files ({@code .au}, {@code .snd}), magic {@code .snd}, and their little-endian variant, magic
 * {@code dns.}.
 *
 * <p>The 24-byte header gives the offset of the samples, their size ({@code 0xFFFFFFFF} when unknown, in which case
 * they extend to the end of the file, as does a size larger than the file), the encoding, the sampling rate and the
 * channel count; the bytes between the header and the samples are the annotation. The encodings read are:
 * <table class="striped">
 * <caption>Encodings</caption>
 * <thead><tr><th scope="col">Encoding</th><th scope="col">Codec</th></tr></thead>
 * <tbody>
 * <tr><td>1, 8-bit mu-law</td><td>{@link Codec#PCM_MULAW}</td></tr>
 * <tr><td>2, 8-bit linear</td><td>{@link Codec#PCM_S8}</td></tr>
 * <tr><td>3, 4 and 5, 16, 24 and 32-bit linear</td><td>{@link Codec#PCM_S16BE}, {@link Codec#PCM_S24BE},
 * {@link Codec#PCM_S32BE}, or their little-endian codecs in a little-endian file</td></tr>
 * <tr><td>6 and 7, 32 and 64-bit floats</td><td>{@link Codec#PCM_F32BE}, {@link Codec#PCM_F64BE}, or their
 * little-endian codecs in a little-endian file</td></tr>
 * <tr><td>23, 25 and 26, G.721 and G.723 ADPCM of 4, 3 and 5 bits</td><td>{@link Codec#ADPCM_G726} of that code
 * size, one channel</td></tr>
 * <tr><td>24, G.722 ADPCM</td><td>{@link Codec#ADPCM_G722}, one channel</td></tr>
 * <tr><td>27, 8-bit A-law</td><td>{@link Codec#PCM_ALAW}</td></tr>
 * </tbody>
 * </table>
 * Any other encoding raises {@link MediaException.Unsupported} naming it. The file stores G.721 and G.723 codes from
 * the least significant bit of each byte; the packets hold them from the most significant bit, as
 * {@link Codec#ADPCM_G726} defines. AU has no channel positions, so the track uses
 * {@link ChannelLayout#defaultFor(int)}.
 *
 * <p>The annotation, up to its first zero byte, becomes {@link #metadata()}: when every line of it is of the form
 * {@code key=value}, each line under its key, and otherwise the whole text under {@code comment}.
 *
 * <p>Packets hold the largest power of two of samples that lasts at most 100 ms, in whole frames (a sample of every
 * channel) for PCM and in whole units for ADPCM (a byte of G.722 or of 4-bit codes, the three or five bytes of eight
 * 3 or 5-bit codes), with timestamps in samples. A seek lands on the requested sample for PCM, and on the packet
 * that holds it for ADPCM, so that the packets read after it are those of a read from the start. A source that
 * reads its input once, front to back, is told after every packet which bytes are no longer needed.
 *
 * <p>Instances are not thread-safe.
 */
public final class AuDemuxer implements Demuxer {
    /**
     * The size of the blocks a source that is not in memory is read in.
     */
    private static final int READ_BLOCK = 64 * 1024;

    /**
     * The largest annotation decoded into metadata, in bytes.
     */
    private static final int MAX_ANNOTATION = 64 * 1024;

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
     * The decoded annotation.
     */
    private final Map<String, String> metadata;

    /**
     * The offset of the first sample byte.
     */
    private final long dataStart;

    /**
     * The number of units in the file, or {@link Long#MAX_VALUE} when the samples extend to an unknown end.
     */
    private final long totalUnits;

    /**
     * The size of a unit in bytes: a frame of PCM, or the bytes of a whole number of ADPCM codes.
     */
    private final int unitBytes;

    /**
     * The samples per channel of a unit.
     */
    private final int unitSamples;

    /**
     * The number of units per packet.
     */
    private final int packetUnits;

    /**
     * The G.726 code size in bits, or 0 for the other encodings.
     */
    private final int codeSize;

    /**
     * The read-ahead window over the samples.
     */
    private final ReadAheadCache data;

    /**
     * The index of the unit of the next packet.
     */
    private long unitIndex;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a valid AU file
     * @throws MediaException.Unsupported if the file uses an encoding this demuxer cannot read
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AuDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a valid AU file
     * @throws MediaException.Unsupported if the file uses an encoding this demuxer cannot read
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AuDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private AuDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        try {
            var reader = new SourceReader(source, 4096);
            var magic = reader.fourCc();
            boolean little;
            if (magic == 0x2E736E64) {
                little = false;
            } else if (magic == 0x646E732E) {
                little = true;
            } else {
                throw new MediaException.Malformed("not an AU file");
            }
            var offset = little ? reader.u32le() : reader.u32be();
            var size = little ? reader.u32le() : reader.u32be();
            var encoding = little ? reader.u32le() : reader.u32be();
            var rate = little ? reader.u32le() : reader.u32be();
            var channels = little ? reader.u32le() : reader.u32be();
            if (offset < 24) {
                throw new MediaException.Malformed("AU header of " + offset + " bytes is too small");
            }
            if (rate == 0 || rate > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("invalid AU sample rate " + rate);
            }
            if (channels == 0 || channels > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("invalid AU channel count " + channels);
            }
            LimitChecks.checkChannels(limits, (int) channels);
            var annotation = annotation(reader, offset - 24);
            this.metadata = Collections.unmodifiableMap(metadata(annotation));
            var codec = codec(encoding, little);
            var layout = ChannelLayout.defaultFor((int) channels);
            int bits;
            switch (codec) {
                case ADPCM_G726, ADPCM_G722 -> {
                    if (channels != 1) {
                        throw new MediaException.Unsupported("au encoding " + encoding + " of " + channels
                                + " channels", "AU ADPCM of more than one channel is not supported");
                    }
                    bits = codec == Codec.ADPCM_G722 ? 0 : switch ((int) encoding) {
                        case 25 -> 3;
                        case 26 -> 5;
                        default -> 4;
                    };
                    this.codeSize = bits;
                    this.unitBytes = bits == 3 || bits == 5 ? bits : 1;
                    this.unitSamples = codec == Codec.ADPCM_G722 ? 2 : 8 * unitBytes / Math.max(bits, 1);
                }
                default -> {
                    bits = switch (codec) {
                        case PCM_MULAW, PCM_ALAW -> 8;
                        default -> codec.pcmBitsPerSample().orElseThrow();
                    };
                    this.codeSize = 0;
                    this.unitBytes = Math.multiplyExact(codec.pcmBitsPerSample().orElseThrow() / 8, (int) channels);
                    this.unitSamples = 1;
                }
            }
            var targetSamples = Integer.highestOneBit((int) Math.max(1, rate / 10));
            this.packetUnits = Math.max(1, Math.min(targetSamples / unitSamples, (1 << 20) / unitBytes));
            LimitChecks.checkAllocation(limits, (long) packetUnits * unitBytes);
            this.dataStart = offset;
            var fileSize = source.size();
            long dataEnd;
            if (size == 0xFFFFFFFFL) {
                dataEnd = fileSize;
            } else {
                dataEnd = fileSize < 0 ? offset + size : Math.min(fileSize, offset + size);
            }
            this.totalUnits = dataEnd < 0 ? Long.MAX_VALUE : Math.max(0, dataEnd - offset) / unitBytes;
            var limit = totalUnits == Long.MAX_VALUE ? Long.MAX_VALUE : offset + totalUnits * unitBytes;
            this.data = new ReadAheadCache(source, READ_BLOCK, 0, limit);
            this.track = AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf((int) rate))
                    .duration(totalUnits == Long.MAX_VALUE ? -1 : totalUnits * unitSamples)
                    .sampleRate((int) rate)
                    .layout(layout)
                    .bitsPerSample(bits)
                    .build();
            this.tracks = List.of(track);
            this.unitIndex = 0;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AU file", exception);
        }
    }

    /**
     * Returns the codec of an encoding.
     *
     * @param encoding the encoding field
     * @param little whether the file is little-endian
     * @return the codec
     * @throws MediaException.Unsupported if the encoding is not one the class description lists
     */
    private static Codec codec(long encoding, boolean little) {
        return switch ((int) Math.min(encoding, Integer.MAX_VALUE)) {
            case 1 -> Codec.PCM_MULAW;
            case 2 -> Codec.PCM_S8;
            case 3 -> little ? Codec.PCM_S16LE : Codec.PCM_S16BE;
            case 4 -> little ? Codec.PCM_S24LE : Codec.PCM_S24BE;
            case 5 -> little ? Codec.PCM_S32LE : Codec.PCM_S32BE;
            case 6 -> little ? Codec.PCM_F32LE : Codec.PCM_F32BE;
            case 7 -> little ? Codec.PCM_F64LE : Codec.PCM_F64BE;
            case 23, 25, 26 -> Codec.ADPCM_G726;
            case 24 -> Codec.ADPCM_G722;
            case 27 -> Codec.PCM_ALAW;
            default -> throw new MediaException.Unsupported("au encoding " + encoding,
                    "AU encoding " + encoding + " is not supported");
        };
    }

    /**
     * Reads the annotation, up to its first zero byte.
     *
     * @param reader the reader, after the 24-byte header
     * @param length the number of annotation bytes
     * @return the text, empty when there is none or it is too large to decode
     */
    private static String annotation(SourceReader reader, long length) {
        if (length == 0 || length > MAX_ANNOTATION || length > reader.remaining()) {
            return "";
        }
        var bytes = reader.bytes(length, MAX_ANNOTATION);
        var end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8).strip();
    }

    /**
     * Decodes an annotation into metadata.
     *
     * @param annotation the text
     * @return the entries, in order
     */
    static Map<String, String> metadata(String annotation) {
        var entries = new LinkedHashMap<String, String>();
        if (annotation.isEmpty()) {
            return entries;
        }
        var keyed = true;
        for (var line : annotation.split("\n")) {
            var entry = line.strip();
            if (entry.isEmpty()) {
                continue;
            }
            var equals = entry.indexOf('=');
            if (equals <= 0) {
                keyed = false;
                break;
            }
            entries.merge(entry.substring(0, equals).strip(), entry.substring(equals + 1).strip(),
                    (previous, next) -> previous + "; " + next);
        }
        if (!keyed || entries.isEmpty()) {
            entries.clear();
            entries.put("comment", annotation);
        }
        return entries;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.AU;
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
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public void seek(long micros) {
        var sample = micros <= 0 ? 0 : BigInteger.valueOf(micros).multiply(BigInteger.valueOf(track.sampleRate()))
                .divide(BigInteger.valueOf(1_000_000)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        var unit = sample / unitSamples;
        // ADPCM restarts on the packet grid, so the packets after a seek are packets of a linear read
        unitIndex = unit >= totalUnits ? totalUnits : unitSamples == 1 ? unit : unit - unit % packetUnits;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        try {
            if (unitIndex >= totalUnits) {
                return null;
            }
            var units = (int) Math.min(packetUnits, totalUnits - unitIndex);
            var bytes = new byte[units * unitBytes];
            var done = data.readUpTo(dataStart + unitIndex * unitBytes, bytes, 0, bytes.length);
            if (done < bytes.length) {
                units = done / unitBytes;
                if (units == 0) {
                    unitIndex = totalUnits;
                    return null;
                }
                bytes = Arrays.copyOf(bytes, units * unitBytes);
            }
            if (codeSize > 0) {
                G726Packing.toMsbFirst(bytes, bytes.length, codeSize);
            }
            var start = unitIndex * unitSamples;
            var packet = new Packet(track.id(), bytes, start, start, (long) units * unitSamples, true);
            unitIndex += units;
            ForwardOnlySource.release(source, dataStart + unitIndex * unitBytes);
            return packet;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid AU data", exception);
        }
    }
}
