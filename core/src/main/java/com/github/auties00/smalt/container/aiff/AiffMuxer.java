package com.github.auties00.smalt.container.aiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Writes AIFF and AIFF-C files from the packets of one PCM track.
 *
 * <p>The track must be an {@link AudioTrack} that declares its sample rate and channel layout,
 * with one of the codecs {@link #supports(Codec)} accepts. Signed big-endian integers (8, 16, 24
 * and 32-bit) produce a plain AIFF file; every other format produces AIFF-C with a {@code FVER}
 * chunk and the compression type that names it: {@code sowt} for little-endian integers,
 * {@code raw } for unsigned 8-bit, {@code fl32} and {@code fl64} for big-endian floats,
 * {@code alaw} and {@code ulaw} for G.711 (declared with a 16-bit sample size, as QuickTime
 * does). AIFF has no channel mask, so only the channel count of the layout is stored. Packets are
 * appended to the {@code SSND} chunk in the order they arrive; their timestamps are ignored.
 *
 * <p>The {@code COMM} chunk precedes the sound data and states the frame count, so on a seekable
 * sink the header is written with the first packet and its counts and sizes are patched by
 * {@link #finish()}. On a streaming sink the frame count and the {@code FORM} and {@code SSND}
 * sizes are zero, the convention of writers that cannot seek back, which {@link AiffDemuxer}
 * resolves by reading the sound data to the end of the file; a reader without that convention
 * cannot read such a file. An odd-sized chunk is followed by a pad byte. The sink is written from
 * its position at construction and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class AiffMuxer implements Muxer {
    /**
     * The largest size a 32-bit chunk field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The AIFF-C version timestamp of the {@code FVER} chunk, for the 1991 specification.
     */
    private static final long AIFC_VERSION_1 = 0xA2805140L;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The sink position at construction, where the file starts.
     */
    private final long base;

    /**
     * The declared track, or {@code null}.
     */
    private AudioTrack track;

    /**
     * The size of one interleaved frame in bytes.
     */
    private int blockAlign;

    /**
     * The size of the header up to the first sample.
     */
    private int headerSize;

    /**
     * The file offset of the {@code COMM} frame count.
     */
    private int framesOffset;

    /**
     * The number of sample bytes written.
     */
    private long dataSize;

    /**
     * The tags of {@link #setTags(MediaTags)}, encoded into chunks before {@code SSND} when the header is written.
     */
    private MediaTags tags = MediaTags.empty();

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public AiffMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
        this.base = output.position();
    }

    /**
     * Returns whether AIFF files can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for the signed 8, 16, 24 and 32-bit integer formats of either byte order, unsigned 8-bit,
     * big-endian 32 and 64-bit floats, A-law and mu-law; {@code false} for 64-bit integers, wider than the 32-bit
     * sample size AIFF allows
     */
    public static boolean supports(Codec codec) {
        return compression(Objects.requireNonNull(codec, "codec")) != null;
    }

    /**
     * Returns the AIFF-C compression type of a codec.
     *
     * @param codec a codec {@link #supports(Codec)} accepts
     * @return {@code NONE} for the formats of plain AIFF, the AIFF-C type otherwise, or {@code null} when AIFF cannot
     * carry the codec
     */
    private static String compression(Codec codec) {
        return switch (codec) {
            case PCM_S8, PCM_S16BE, PCM_S24BE, PCM_S32BE -> "NONE";
            case PCM_S16LE, PCM_S24LE, PCM_S32LE -> "sowt";
            case PCM_U8 -> "raw ";
            case PCM_F32BE -> "fl32";
            case PCM_F64BE -> "fl64";
            case PCM_ALAW -> "alaw";
            case PCM_MULAW -> "ulaw";
            default -> null;
        };
    }

    /**
     * Returns the human-readable name AIFF-C stores next to a compression type.
     *
     * @param compression the compression type
     * @return the name
     */
    private static String compressionName(String compression) {
        return switch (compression) {
            case "NONE" -> "not compressed";
            case "fl32" -> "32-bit floating point";
            case "fl64" -> "64-bit floating point";
            case "alaw" -> "ALaw 2:1";
            case "ulaw" -> "uLaw 2:1";
            default -> "";
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not an audio track of a codec {@link #supports(Codec)} accepts,
     * declares no sample rate or layout, or has more than 32767 channels
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("an AIFF file carries one track");
        }
        if (!(track instanceof AudioTrack audio) || !supports(track.codec())) {
            throw new IllegalArgumentException("AIFF cannot carry " + track.codec().displayName());
        }
        if (audio.sampleRate() <= 0 || audio.layout().isEmpty()) {
            throw new IllegalArgumentException("an AIFF track needs its sample rate and channel layout");
        }
        var channels = audio.layout().get().channels();
        if (channels > Short.MAX_VALUE) {
            throw new IllegalArgumentException(channels + " channels do not fit an AIFF header");
        }
        this.track = audio;
        this.blockAlign = audio.codec().pcmBitsPerSample().orElseThrow() / 8 * channels;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags become {@code NAME}, {@code AUTH}, {@code (c) } and {@code ANNO} chunks for the
     * values they can hold and, when pictures or other keys need it, an {@code ID3 } chunk with every
     * tag. The chapters become the markers of a {@code MARK} chunk, named by their titles, and the
     * instrument keys an {@code INST} chunk (whose loops add markers), as
     * {@link com.github.auties00.smalt.metadata.TagEditor} writes them. Every chunk is written before
     * {@code SSND}.
     *
     * @throws IllegalArgumentException if the value of an instrument key is malformed
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        MuxerTags.riffChunks(tags, true, 0);
        this.tags = tags;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track or does not hold whole frames
     * @throws IllegalStateException if no track was declared, the muxer is finished, or the file would exceed the 4 GiB
     * an AIFF header can describe
     * @throws MediaException.Unsupported if the packet changes the codec configuration
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "AIFF");
        if (packet.size() % blockAlign != 0) {
            throw new IllegalArgumentException("a packet of " + packet.size() + " bytes does not hold whole frames of "
                    + blockAlign + " bytes");
        }
        if (!started) {
            writeHeader();
        }
        var end = dataSize + packet.size();
        if (headerSize - 8L + end + (end & 1) > U32_MAX) {
            throw new IllegalStateException("the AIFF file would exceed 4 GiB");
        }
        output.write(ByteBuffer.wrap(packet.data()));
        dataSize = end;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes a header with an empty {@code SSND} chunk.
     *
     * @throws IllegalStateException if no track was declared or the muxer is finished
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (!started) {
            writeHeader();
        }
        finished = true;
        if ((dataSize & 1) != 0) {
            output.write(new byte[1]);
        }
        if (!output.isSeekable()) {
            return;
        }
        var end = output.position();
        patch32(4, headerSize - 8L + dataSize + (dataSize & 1));
        patch32(framesOffset, dataSize / blockAlign);
        patch32(headerSize - 12, 8 + dataSize);
        output.position(end);
    }

    /**
     * Marks the muxer finished; an unfinished file keeps the sizes of its header as written.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the header up to the {@code SSND} chunk's samples.
     */
    private void writeHeader() {
        started = true;
        var compression = compression(track.codec());
        var plain = compression.equals("NONE");
        var header = new ByteWriter(96);
        header.fourCc("FORM");
        header.u32be(0);
        header.fourCc(plain ? "AIFF" : "AIFC");
        if (!plain) {
            header.fourCc("FVER");
            header.u32be(4);
            header.u32be(AIFC_VERSION_1);
        }
        var name = compressionName(compression).getBytes(StandardCharsets.ISO_8859_1);
        var nameSize = 1 + name.length + ((1 + name.length) & 1);
        header.fourCc("COMM");
        header.u32be(plain ? 18 : 22 + nameSize);
        header.u16be(track.layout().orElseThrow().channels());
        framesOffset = header.size();
        header.u32be(0);
        header.u16be(sampleSizeBits(track.codec()));
        writeExtended(header, track.sampleRate());
        if (!plain) {
            header.fourCc(compression);
            header.u8(name.length);
            header.bytes(name);
            if (((1 + name.length) & 1) != 0) {
                header.u8(0);
            }
        }
        for (var chunk : MuxerTags.riffChunks(tags, true, track.sampleRate())) {
            header.bytes(chunk);
        }
        var seekable = output.isSeekable();
        header.fourCc("SSND");
        header.u32be(seekable ? 8 : 0);
        header.u32be(0);
        header.u32be(0);
        headerSize = header.size();
        if (seekable) {
            header.patch32be(4, headerSize - 8);
        }
        output.position(base);
        header.writeTo(output);
    }

    /**
     * Writes an integer sampling rate as an 80-bit IEEE 754 extended float.
     *
     * @param header the header being built
     * @param rate the rate, positive
     */
    static void writeExtended(ByteWriter header, int rate) {
        var log = 31 - Integer.numberOfLeadingZeros(rate);
        header.u16be(16383 + log);
        header.u64be((long) rate << (63 - log));
    }

    /**
     * Returns the sample size the {@code COMM} chunk declares for a codec.
     *
     * @param codec a codec {@link #supports(Codec)} accepts
     * @return the size in bits
     */
    private static int sampleSizeBits(Codec codec) {
        return switch (codec) {
            case PCM_ALAW, PCM_MULAW -> 16;
            default -> codec.pcmBitsPerSample().orElseThrow();
        };
    }

    /**
     * Rewrites a 32-bit big-endian field of the header.
     *
     * @param offset the field offset relative to the start of the file
     * @param value the field value, an unsigned 32-bit integer
     */
    private void patch32(int offset, long value) {
        var field = new ByteWriter(4);
        field.u32be(value);
        output.position(base + offset);
        field.writeTo(output);
    }
}
