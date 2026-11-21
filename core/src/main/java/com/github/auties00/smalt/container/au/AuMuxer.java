package com.github.auties00.smalt.container.au;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes Sun/NeXT audio files ({@code .au}) from the packets of one PCM or ADPCM track.
 *
 * <p>The track must be an {@link AudioTrack} that declares its sample rate and channel layout, with one of the codecs
 * {@link #supports(Codec)} accepts. AU stores big-endian samples: little-endian integers and floats are written with
 * their bytes swapped and unsigned 8-bit samples as signed ones (the sign bit flipped), so the file declares the
 * big-endian encoding of the same sample values. G.726 codes of 3, 4 and 5 bits become the G.723 3-bit, G.721 and
 * G.723 5-bit encodings, their codes repacked from the least significant bit of each byte as AU stores them; G.722 is
 * written as it is. AU has no channel positions, so only the channel count of the layout is stored. Packets are
 * appended in the order they arrive; their timestamps are ignored.
 *
 * <p>The tags become the annotation, one {@code key=value} line per entry: {@code Title}, {@code Artist},
 * {@code Album}, {@code Track}, {@code Genre} and {@code Comment} for those standard tags and the key as given for
 * the others, with line breaks in values replaced by spaces. The annotation ends with at least one zero byte and pads
 * the header to a multiple of eight bytes.
 *
 * <p>On a seekable sink {@link #finish()} writes the size of the samples into the header; on a streaming sink, or
 * when the samples reach 4 GiB, the size stays {@code 0xFFFFFFFF}, the value of an unknown size, and readers read
 * the samples to the end of the file. The sink is written from its position at construction and is never closed by
 * the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class AuMuxer implements Muxer {
    /**
     * The value of an unknown data size.
     */
    private static final long UNKNOWN_SIZE = 0xFFFFFFFFL;

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
     * The encoding field of the track's codec.
     */
    private int encoding;

    /**
     * The size of a sample of a little-endian codec whose bytes are swapped, 1 for a codec whose sign bit is flipped,
     * or 0 for a codec written as it is.
     */
    private int conversion;

    /**
     * The size of one frame in bytes for PCM, or 0 for ADPCM.
     */
    private int blockAlign;

    /**
     * The G.726 code size, or 0.
     */
    private int codeSize;

    /**
     * The annotation, padded.
     */
    private byte[] annotation = new byte[8];

    /**
     * The converted samples of the current packet.
     */
    private byte[] converted = new byte[0];

    /**
     * The number of sample bytes written.
     */
    private long dataSize;

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
    public AuMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
        this.base = output.position();
    }

    /**
     * Returns whether AU files can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for the signed 16, 24 and 32-bit integers and the 32 and 64-bit floats of either byte order,
     * signed and unsigned 8-bit integers, A-law, mu-law, G.722 and G.726; {@code false} for 64-bit integers, which AU
     * has no encoding for
     */
    public static boolean supports(Codec codec) {
        return encoding(Objects.requireNonNull(codec, "codec"), 4) > 0;
    }

    /**
     * Returns the encoding field of a codec.
     *
     * @param codec the codec of the track
     * @param codeSize the G.726 code size
     * @return the encoding, or 0 when AU cannot carry the codec
     */
    private static int encoding(Codec codec, int codeSize) {
        return switch (codec) {
            case PCM_MULAW -> 1;
            case PCM_S8, PCM_U8 -> 2;
            case PCM_S16BE, PCM_S16LE -> 3;
            case PCM_S24BE, PCM_S24LE -> 4;
            case PCM_S32BE, PCM_S32LE -> 5;
            case PCM_F32BE, PCM_F32LE -> 6;
            case PCM_F64BE, PCM_F64LE -> 7;
            case ADPCM_G722 -> 24;
            case ADPCM_G726 -> switch (codeSize) {
                case 3 -> 25;
                case 4 -> 23;
                case 5 -> 26;
                default -> 0;
            };
            case PCM_ALAW -> 27;
            default -> 0;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not an audio track of a codec {@link #supports(Codec)} accepts,
     * declares no sample rate or layout, is G.722 or G.726 of more than one channel, or is G.726 of 2-bit codes, which
     * AU has no encoding for
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("an AU file carries one track");
        }
        if (!(track instanceof AudioTrack audio) || !supports(track.codec())) {
            throw new IllegalArgumentException("AU cannot carry " + track.codec().displayName());
        }
        if (audio.sampleRate() <= 0 || audio.layout().isEmpty()) {
            throw new IllegalArgumentException("an AU track needs its sample rate and channel layout");
        }
        var codec = audio.codec();
        var channels = audio.layout().get().channels();
        if ((codec == Codec.ADPCM_G722 || codec == Codec.ADPCM_G726) && channels != 1) {
            throw new IllegalArgumentException("AU carries " + codec.displayName() + " of one channel only");
        }
        var bits = codec == Codec.ADPCM_G726 ? audio.bitsPerSample() : 0;
        var encoding = encoding(codec, bits);
        if (encoding == 0) {
            throw new IllegalArgumentException("AU cannot carry G.726 of " + bits + "-bit codes");
        }
        this.encoding = encoding;
        this.codeSize = bits;
        this.conversion = switch (codec) {
            case PCM_U8 -> 1;
            case PCM_S16LE, PCM_S24LE, PCM_S32LE, PCM_F32LE, PCM_F64LE -> codec.pcmBitsPerSample().orElseThrow() / 8;
            default -> 0;
        };
        this.blockAlign = codec.isPcm() ? codec.pcmBitsPerSample().orElseThrow() / 8 * channels : 0;
        this.track = audio;
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
     * <p>The tags become the annotation, as the class description states; pictures, chapters and the XMP packet are
     * not stored.
     *
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        var text = new StringBuilder();
        for (var entry : tags.entries()) {
            var key = StandardTag.fromName(entry.key()).map(AuMuxer::annotationKey).orElse(entry.key());
            var value = entry.value().replace('\r', ' ').replace('\n', ' ');
            text.append(key.replace('\n', ' ').replace('=', ' ')).append('=').append(value).append('\n');
        }
        var bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        var length = bytes.length + 1;
        length += -(24 + length) & 7;
        var padded = new byte[Math.max(length, 8)];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\n') {
            padded[bytes.length - 1] = 0;
        }
        this.annotation = padded;
    }

    /**
     * Returns the annotation key of a standard tag.
     *
     * @param tag the standard tag to name
     * @return the key
     */
    private static String annotationKey(StandardTag tag) {
        return switch (tag) {
            case TITLE -> "Title";
            case ARTIST -> "Artist";
            case ALBUM -> "Album";
            case TRACK -> "Track";
            case GENRE -> "Genre";
            case COMMENT -> "Comment";
            default -> tag.key();
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track or does not hold whole PCM frames
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the codec configuration
     * @throws MediaException.Io {@inheritDoc}
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
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "AU");
        var data = packet.data();
        if (blockAlign > 0 && data.length % blockAlign != 0) {
            throw new IllegalArgumentException("a packet of " + data.length + " bytes does not hold whole frames of "
                    + blockAlign + " bytes");
        }
        if (!started) {
            writeHeader();
        }
        if (conversion == 0 && codeSize == 0) {
            output.write(ByteBuffer.wrap(data));
        } else {
            if (converted.length < data.length) {
                converted = new byte[Math.max(data.length, 2 * converted.length)];
            }
            System.arraycopy(data, 0, converted, 0, data.length);
            if (codeSize > 0) {
                G726Packing.toLsbFirst(converted, data.length, codeSize);
            } else if (conversion == 1) {
                for (var index = 0; index < data.length; index++) {
                    converted[index] ^= (byte) 0x80;
                }
            } else {
                for (var sample = 0; sample < data.length; sample += conversion) {
                    for (var index = 0; index < conversion; index++) {
                        converted[sample + index] = data[sample + conversion - 1 - index];
                    }
                }
            }
            output.write(ByteBuffer.wrap(converted, 0, data.length));
        }
        dataSize += data.length;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes a header with no samples.
     *
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Io {@inheritDoc}
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
        if (!output.isSeekable() || dataSize >= UNKNOWN_SIZE) {
            return;
        }
        var end = output.position();
        var field = new ByteWriter(4);
        field.u32be(dataSize);
        output.position(base + 8);
        field.writeTo(output);
        output.position(end);
    }

    /**
     * Marks the muxer finished; an unfinished file keeps the unknown size of its header.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the header and the annotation.
     */
    private void writeHeader() {
        started = true;
        var header = new ByteWriter(24 + annotation.length);
        header.fourCc(".snd");
        header.u32be(24 + annotation.length);
        header.u32be(UNKNOWN_SIZE);
        header.u32be(encoding);
        header.u32be(track.sampleRate());
        header.u32be(track.layout().orElseThrow().channels());
        header.bytes(annotation);
        output.position(base);
        header.writeTo(output);
    }
}
