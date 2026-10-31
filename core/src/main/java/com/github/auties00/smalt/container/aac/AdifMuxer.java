package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.AacRawBlocks;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes AAC elementary streams in the Audio Data Interchange Format (ISO/IEC 14496-3 subclause 1.A.2) from the
 * packets of one AAC track: an ADIF header followed by the raw data blocks back to back.
 *
 * <p>The header declares a variable-rate stream (no buffer fullness) and one program configuration element built
 * from the track's {@code AudioSpecificConfig}: its object type (the profile, so only object types 1 to 4, AAC Main,
 * LC, SSR and LTP, are representable), its sampling frequency index, and the channel elements of its channel
 * configuration, front, back and LFE as ISO/IEC 14496-3 table 1.19 places them, with the instance tags the first
 * block's elements carry when they are the elements of that configuration (and numbered from zero per element type
 * otherwise); the element of a configuration of channel configuration 0 is copied. A configuration that signals SBR
 * or PS explicitly (object type 5 or 29) is written as its core object type at the core sampling rate, the implicit
 * signaling decoders recognize. A sampling rate without an index, channel configurations above 7, 960-sample frames
 * and the other object types cannot be expressed and are rejected. On a seekable sink {@link #finish()} writes the
 * peak bit rate of the blocks into the header's bit rate field; on a streaming sink the field stays 0, the value of
 * an unknown bit rate.
 *
 * <p>Each packet must be one raw AAC access unit (a {@code raw_data_block}), as the MP4, Matroska, ADTS and LOAS
 * demuxers produce; timestamps are ignored, and a stream whose configuration changes cannot be written. The sink is
 * written from its position at construction and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class AdifMuxer implements Muxer {
    /**
     * The largest value of the 23-bit bit rate field.
     */
    private static final int MAX_BITRATE = (1 << 23) - 1;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The sink position at construction, where the stream starts.
     */
    private final long base;

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * The track's {@code AudioSpecificConfig}.
     */
    private byte[] config;

    /**
     * The core object type of the configuration, from 1 to 4.
     */
    private int objectType;

    /**
     * The sampling frequency index of the core rate.
     */
    private int samplingIndex;

    /**
     * The core sampling rate, in hertz.
     */
    private int coreRate;

    /**
     * The channel configuration, from 0 to 7.
     */
    private int channelConfiguration;

    /**
     * The channel elements of the first block, as {@link AacRawBlocks#channelElements} returns them, or
     * {@code null}.
     */
    private int[] firstElements;

    /**
     * The size of the largest block, in bytes.
     */
    private int largestBlock;

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
    public AdifMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
        this.base = output.position();
    }

    /**
     * Returns whether ADIF can carry a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#AAC} only
     */
    public static boolean supports(Codec codec) {
        return Objects.requireNonNull(codec, "codec") == Codec.AAC;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not AAC, has no {@code AudioSpecificConfig}, or its
     * configuration cannot be expressed in an ADIF header
     * @throws IllegalStateException if a track was already declared, a packet was already written or the muxer is
     * finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("an ADIF stream carries one track");
        }
        if (!(track instanceof AudioTrack) || track.codec() != Codec.AAC) {
            throw new IllegalArgumentException("ADIF cannot carry " + track.codec().displayName());
        }
        var config = track.codecConfig()
                .orElseThrow(() -> new IllegalArgumentException("an ADIF track needs its AudioSpecificConfig"));
        parseConfig(config);
        this.config = config;
        if (channelConfiguration == 0) {
            try {
                copyProgramConfig(new MsbBitWriter(64));
            } catch (MediaException exception) {
                throw new IllegalArgumentException("invalid program config element in the AudioSpecificConfig",
                        exception);
            }
        }
        this.track = track;
    }

    /**
     * Reads the fields of an {@code AudioSpecificConfig} the header needs.
     *
     * @param config the configuration
     * @throws IllegalArgumentException if the configuration cannot be expressed in an ADIF header
     */
    private void parseConfig(byte[] config) {
        var reader = new MsbBitReader(config);
        var type = objectType(reader);
        var index = reader.read(4);
        if (index == AacRates.ESCAPE) {
            throw new IllegalArgumentException("ADIF cannot carry a sampling rate without a sampling frequency index");
        }
        var channels = reader.read(4);
        if (type == 5 || type == 29) {
            if (reader.read(4) == AacRates.ESCAPE) {
                reader.skip(24);
            }
            type = objectType(reader);
        }
        if (!AacRawBlocks.supports(type)) {
            throw new IllegalArgumentException("ADIF cannot carry AAC object type " + type);
        }
        if (channels > 7) {
            throw new IllegalArgumentException("ADIF cannot carry channel configuration " + channels);
        }
        if (reader.readFlag()) {
            throw new IllegalArgumentException("ADIF cannot carry 960-sample frames");
        }
        if (reader.overrun() || AacRates.rate(index) == 0) {
            throw new IllegalArgumentException("invalid AudioSpecificConfig");
        }
        this.objectType = type;
        this.samplingIndex = index;
        this.coreRate = AacRates.rate(index);
        this.channelConfiguration = channels;
    }

    /**
     * Reads an audio object type with its escape.
     *
     * @param reader the reader positioned at the object type
     * @return the object type
     */
    private static int objectType(MsbBitReader reader) {
        var type = reader.read(5);
        return type == 31 ? 32 + reader.read(6) : type;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track
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
        ConfigChanges.reject(packet, config, "ADIF");
        var data = packet.data();
        if (!started) {
            try {
                firstElements = new AacRawBlocks(objectType, samplingIndex, 1024)
                        .channelElements(data, 0, data.length);
            } catch (MediaException exception) {
                firstElements = null;
            }
            writeHeader(0);
        }
        largestBlock = Math.max(largestBlock, data.length);
        output.write(ByteBuffer.wrap(data));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes the header alone.
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
            writeHeader(0);
        }
        finished = true;
        if (largestBlock == 0 || !output.isSeekable()) {
            return;
        }
        var end = output.position();
        output.position(base);
        var peak = (long) largestBlock * 8 * coreRate / 1024;
        writeHeader((int) Math.min(peak, MAX_BITRATE));
        output.position(end);
    }

    /**
     * Marks the muxer finished; an unfinished stream keeps the header as written.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the ADIF header.
     *
     * @param bitrate the value of the bit rate field, 0 when unknown
     */
    private void writeHeader(int bitrate) {
        started = true;
        var writer = new MsbBitWriter(64);
        writer.write(0x41444946, 32);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(bitrate, 23);
        writer.write(0, 4);
        writer.write(0, 4);
        writer.write(objectType - 1, 2);
        writer.write(samplingIndex, 4);
        if (channelConfiguration == 0) {
            copyProgramConfig(writer);
        } else {
            writeProgramConfig(writer);
        }
        writer.alignWithZeros();
        output.write(ByteBuffer.wrap(writer.toByteArray()));
    }

    /**
     * Copies the program config element of an {@code AudioSpecificConfig} of channel configuration 0, from the
     * fields after its sampling frequency index.
     *
     * @param writer the header, after the element's sampling frequency index
     */
    private void copyProgramConfig(MsbBitWriter writer) {
        var reader = new MsbBitReader(config);
        var type = objectType(reader);
        reader.skip(4 + 4);
        if (type == 5 || type == 29) {
            if (reader.read(4) == AacRates.ESCAPE) {
                reader.skip(24);
            }
            objectType(reader);
        }
        reader.skip(1);
        if (reader.readFlag()) {
            reader.skip(14);
        }
        reader.skip(1 + 4 + 2 + 4);
        AacRawBlocks.copyProgramConfig(reader, writer, 0);
    }

    /**
     * Writes the fields of the program config element of the channel configuration after its sampling frequency
     * index, with the instance tags of the first block's elements when they are the configuration's.
     *
     * @param writer the header, after the element's sampling frequency index
     */
    private void writeProgramConfig(MsbBitWriter writer) {
        var types = switch (channelConfiguration) {
            case 1 -> new int[]{0};
            case 2 -> new int[]{1};
            case 3 -> new int[]{0, 1};
            case 4 -> new int[]{0, 1, 0};
            case 5 -> new int[]{0, 1, 1};
            case 6 -> new int[]{0, 1, 1, 3};
            default -> new int[]{0, 1, 1, 1, 3};
        };
        var front = switch (channelConfiguration) {
            case 1, 2 -> 1;
            case 7 -> 3;
            default -> 2;
        };
        var back = channelConfiguration >= 4 ? 1 : 0;
        var lfe = channelConfiguration >= 6 ? 1 : 0;
        var tags = new int[types.length];
        var matches = firstElements != null && firstElements.length == types.length;
        for (var index = 0; matches && index < types.length; index++) {
            matches = firstElements[index] >>> 4 == types[index];
        }
        var counters = new int[4];
        for (var index = 0; index < types.length; index++) {
            tags[index] = matches ? firstElements[index] & 15 : counters[types[index]]++;
        }
        writer.write(front, 4);
        writer.write(0, 4);
        writer.write(back, 4);
        writer.write(lfe, 2);
        writer.write(0, 3);
        writer.write(0, 4);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(0, 1);
        for (var index = 0; index < front + back; index++) {
            writer.write(types[index], 1);
            writer.write(tags[index], 4);
        }
        if (lfe == 1) {
            writer.write(tags[types.length - 1], 4);
        }
        writer.alignWithZeros();
        writer.write(0, 8);
    }
}
