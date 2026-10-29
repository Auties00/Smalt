package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Writes RIFF WAVE files, switching to RF64 for files of 4 GiB and more, from the packets of one
 * PCM track.
 *
 * <p>The track must be an {@link AudioTrack} that declares its sample rate and channel layout,
 * with one of the codecs {@link #supports(Codec)} accepts: unsigned 8-bit, signed 16, 24, 32 and
 * 64-bit little-endian integers, 32 and 64-bit little-endian floats ({@code WAVE_FORMAT_IEEE_FLOAT}),
 * A-law and mu-law. The {@code fmt } chunk uses {@code WAVE_FORMAT_EXTENSIBLE} when the plain
 * format cannot describe the stream: integer samples wider than 16 bits, more than two channels,
 * or a layout other than mono or stereo; its channel mask has one bit per {@link Speaker} of the
 * layout when the speakers are positioned and in mask order, and is zero otherwise. A layout that is
 * exactly the Furse-Malham B-format of {@link com.github.auties00.smalt.audio.Ambisonics#ofFurseMalham(int)}
 * gets the Ambisonic B-format sub-format GUID instead, with a zero mask; other ambisonic layouts, which
 * that sub-format cannot describe, are written as channels without positions. Float,
 * A-law and mu-law files carry a {@code fact} chunk with the sample count. Packets are appended
 * to the {@code data} chunk in the order they arrive; their timestamps are ignored, so a gap
 * between packets is not filled.
 *
 * <p>On a seekable sink the header is written with the first packet and its sizes are patched by
 * {@link #finish()}; under {@link WavMuxerOptions.Rf64#AUTO} a {@code JUNK} chunk reserves the
 * room of the {@code ds64} chunk, which replaces it, together with an {@code RF64} form, when the
 * file reaches 4 GiB. On a streaming sink the RIFF and {@code data} sizes are {@code 0xFFFFFFFF},
 * the value readers take as "until the end of the file", and the {@code fact} count is zero;
 * such a file can be read but its sizes are never corrected. An odd-sized {@code data} chunk is
 * followed by a pad byte. The sink is written from its position at construction and is never
 * closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class WavMuxer implements Muxer {
    /**
     * The largest RIFF size a 32-bit field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The size of the {@code ds64} chunk body, without its table.
     */
    private static final int DS64_SIZE = 28;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The configuration.
     */
    private final WavMuxerOptions options;

    /**
     * The sink position at construction, where the file starts.
     */
    private final long base;

    /**
     * The largest RIFF size written without RF64; {@link #U32_MAX} outside tests.
     */
    private final long riffLimit;

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
     * The file offset of the {@code fact} sample count, or {@code -1}.
     */
    private int factOffset = -1;

    /**
     * The file offset of the {@code JUNK} or {@code ds64} chunk, or {@code -1}.
     */
    private int reservedOffset = -1;

    /**
     * The number of sample bytes written.
     */
    private long dataSize;

    /**
     * The tags of {@link #setTags(MediaTags)}, encoded into chunks before {@code data} when the header is written.
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
     * Constructs a muxer writing to a sink with {@link WavMuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public WavMuxer(MediaSink output) {
        this(output, WavMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the configuration
     * @throws MediaException.Unsupported if the options ask for RF64 and the sink is not seekable
     * @throws MediaException.Io if the sink position cannot be read
     */
    public WavMuxer(MediaSink output, WavMuxerOptions options) {
        this(output, options, U32_MAX);
    }

    /**
     * Constructs a muxer with a lowered RF64 threshold, so tests reach the RF64 path with small
     * files.
     *
     * @param output the sink
     * @param options the configuration
     * @param riffLimit the largest RIFF size written without RF64
     */
    WavMuxer(MediaSink output, WavMuxerOptions options, long riffLimit) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        if (options.rf64Policy() == WavMuxerOptions.Rf64.ALWAYS && !output.isSeekable()) {
            throw new MediaException.Unsupported("non-seekable output",
                    "an RF64 header is patched after the samples are written, so it needs a seekable output");
        }
        this.riffLimit = riffLimit;
        this.base = output.position();
    }

    /**
     * Returns whether WAV files can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for {@link Codec#PCM_U8}, {@link Codec#PCM_S16LE}, {@link Codec#PCM_S24LE},
     * {@link Codec#PCM_S32LE}, {@link Codec#PCM_S64LE}, {@link Codec#PCM_F32LE}, {@link Codec#PCM_F64LE},
     * {@link Codec#PCM_ALAW} and {@link Codec#PCM_MULAW}
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case PCM_U8, PCM_S16LE, PCM_S24LE, PCM_S32LE, PCM_S64LE, PCM_F32LE, PCM_F64LE, PCM_ALAW, PCM_MULAW -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not an audio track of a codec {@link #supports(Codec)} accepts,
     * declares no sample rate or layout, or has more than 65535 channels
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("a WAV file carries one track");
        }
        if (!(track instanceof AudioTrack audio) || !supports(track.codec())) {
            throw new IllegalArgumentException("WAV cannot carry " + track.codec().displayName());
        }
        if (audio.sampleRate() <= 0 || audio.layout().isEmpty()) {
            throw new IllegalArgumentException("a WAV track needs its sample rate and channel layout");
        }
        var channels = audio.layout().get().channels();
        if (channels > 0xFFFF) {
            throw new IllegalArgumentException(channels + " channels do not fit a WAV header");
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
     * <p>The tags become a {@code LIST/INFO} chunk for the values it can hold and, when pictures
     * or other keys need it, an {@code id3 } chunk with every tag, and the Broadcast Wave,
     * {@code iXML}, {@code axml}, {@code chna} and {@code smpl} keys their own chunks, the XMP packet a {@code _PMX}
     * chunk, as
     * {@link com.github.auties00.smalt.metadata.TagEditor} writes them. The chapters become cue points:
     * a {@code cue } chunk and a {@code LIST adtl} chunk holding a {@code labl} title and an {@code ltxt}
     * length per chapter. Every chunk is written before {@code data}.
     *
     * @throws IllegalArgumentException if the value of a {@code chna}, {@code smpl}, {@code umid}, loudness or
     * {@code time_reference} key is malformed
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        MuxerTags.riffChunks(tags, false, 0);
        this.tags = tags;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track or does not hold whole frames
     * @throws IllegalStateException if no track was declared, the muxer is finished, or the
     * {@link WavMuxerOptions.Rf64#NEVER} policy forbids the size the packet would reach
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
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "WAV");
        if (packet.size() % blockAlign != 0) {
            throw new IllegalArgumentException("a packet of " + packet.size() + " bytes does not hold whole frames of "
                    + blockAlign + " bytes");
        }
        if (!started) {
            writeHeader();
        }
        var end = dataSize + packet.size();
        if (options.rf64Policy() == WavMuxerOptions.Rf64.NEVER && headerSize - 8L + end + (end & 1) > riffLimit) {
            throw new IllegalStateException("the WAV file would exceed 4 GiB; use an RF64 policy other than NEVER");
        }
        output.write(ByteBuffer.wrap(packet.data()));
        dataSize = end;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes a header with an empty {@code data} chunk.
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
        var riffSize = headerSize - 8L + dataSize + (dataSize & 1);
        var frames = dataSize / blockAlign;
        var policy = options.rf64Policy();
        var large = riffSize > riffLimit || dataSize > riffLimit;
        if (policy == WavMuxerOptions.Rf64.ALWAYS || policy == WavMuxerOptions.Rf64.AUTO && large) {
            var form = new ByteWriter(8);
            form.fourCc("RF64");
            form.u32le(U32_MAX);
            writeAt(0, form);
            var ds64 = new ByteWriter(8 + DS64_SIZE);
            ds64.fourCc("ds64");
            ds64.u32le(DS64_SIZE);
            ds64.u64le(riffSize);
            ds64.u64le(dataSize);
            ds64.u64le(frames);
            ds64.u32le(0);
            writeAt(reservedOffset, ds64);
            patch32(headerSize - 4, U32_MAX);
            if (factOffset >= 0) {
                patch32(factOffset, Math.min(frames, U32_MAX));
            }
        } else {
            patch32(4, riffSize);
            patch32(headerSize - 4, dataSize);
            if (factOffset >= 0) {
                patch32(factOffset, frames);
            }
        }
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
     * Writes the header up to the {@code data} chunk's samples.
     */
    private void writeHeader() {
        started = true;
        var seekable = output.isSeekable();
        var policy = options.rf64Policy();
        var header = new ByteWriter(128);
        if (policy == WavMuxerOptions.Rf64.ALWAYS) {
            header.fourCc("RF64");
            header.u32le(U32_MAX);
            header.fourCc("WAVE");
            reservedOffset = header.size();
            header.fourCc("ds64");
            header.u32le(DS64_SIZE);
            header.zeros(DS64_SIZE);
        } else {
            header.fourCc("RIFF");
            header.u32le(seekable ? 0 : U32_MAX);
            header.fourCc("WAVE");
            if (policy == WavMuxerOptions.Rf64.AUTO && seekable) {
                reservedOffset = header.size();
                header.fourCc("JUNK");
                header.u32le(DS64_SIZE);
                header.zeros(DS64_SIZE);
            }
        }
        writeFormat(header);
        var codec = track.codec();
        if (codec == Codec.PCM_F32LE || codec == Codec.PCM_F64LE || codec == Codec.PCM_ALAW || codec == Codec.PCM_MULAW) {
            header.fourCc("fact");
            header.u32le(4);
            factOffset = header.size();
            header.u32le(0);
        }
        for (var chunk : MuxerTags.riffChunks(tags, false, track.sampleRate())) {
            header.bytes(chunk);
        }
        header.fourCc("data");
        header.u32le(seekable || policy == WavMuxerOptions.Rf64.ALWAYS ? 0 : U32_MAX);
        headerSize = header.size();
        output.position(base);
        header.writeTo(output);
    }

    /**
     * Writes the {@code fmt } chunk.
     *
     * @param header the header being built
     */
    private void writeFormat(ByteWriter header) {
        var codec = track.codec();
        var layout = track.layout().orElseThrow();
        var channels = layout.channels();
        var bits = codec.pcmBitsPerSample().orElseThrow();
        var tag = switch (codec) {
            case PCM_F32LE, PCM_F64LE -> WaveFormat.FLOAT;
            case PCM_ALAW -> WaveFormat.ALAW;
            case PCM_MULAW -> WaveFormat.MULAW;
            default -> WaveFormat.PCM;
        };
        var standardLayout = channels <= 2 && layout.equals(ChannelLayout.defaultFor(channels));
        var bFormat = tag != WaveFormat.ALAW && tag != WaveFormat.MULAW && WaveFormat.isAmbisonicBFormat(layout);
        var extensible = tag != WaveFormat.ALAW && tag != WaveFormat.MULAW
                && (channels > 2 || !standardLayout || tag == WaveFormat.PCM && bits > 16);
        var subFormat = bFormat ? WaveFormat.SubFormat.AMBISONIC
                : extensible ? WaveFormat.SubFormat.STANDARD : WaveFormat.SubFormat.NONE;
        var format = new WaveFormat(tag, channels, track.sampleRate(), (long) track.sampleRate() * blockAlign, blockAlign,
                bits, bits, extensible && !bFormat ? WaveFormat.channelMask(layout) : 0, subFormat, new byte[0]);
        header.fourCc("fmt ");
        header.u32le(format.size(tag != WaveFormat.PCM));
        format.write(header, tag != WaveFormat.PCM);
    }

    /**
     * Rewrites a 32-bit little-endian field of the header.
     *
     * @param offset the field offset relative to the start of the file
     * @param value the field value, an unsigned 32-bit integer
     */
    private void patch32(int offset, long value) {
        var field = new ByteWriter(4);
        field.u32le(value);
        writeAt(offset, field);
    }

    /**
     * Writes assembled bytes at an offset relative to the start of the file.
     *
     * @param offset the offset relative to the start of the file
     * @param bytes the bytes to write, from their first
     */
    private void writeAt(long offset, ByteWriter bytes) {
        output.position(base + offset);
        bytes.writeTo(output);
    }
}
