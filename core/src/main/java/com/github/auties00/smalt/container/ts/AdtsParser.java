package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits an AAC stream in ADTS framing ({@code stream_type 0x0F}, ISO/IEC 13818-7 clause 6.2)
 * into raw data blocks.
 *
 * <p>The track's configuration is an {@code AudioSpecificConfig} synthesized from the first
 * header (object type, sampling frequency index, channel configuration), with the
 * backward-compatible signaling of HE-AAC added when the first frame decodes to twice the core
 * rate through the registered AAC decoder, as the ADTS demuxer does. Timestamps count samples of
 * the core rate. Each packet is the payload of one ADTS frame, header and CRC words removed; a
 * frame of several raw data blocks stays one packet lasting {@code 1024} samples per block. Frames
 * whose version, profile, sampling frequency or channel configuration differ from the first one
 * are skipped as garbage.
 */
final class AdtsParser extends FrameParser {
    /**
     * The bits of the second and third header bytes that every frame of one stream shares: the
     * MPEG version, layer, profile and sampling frequency index, plus the top bit of the channel
     * configuration.
     */
    private static final int STREAM_MASK = 0x0EFD;

    /**
     * The shared header bits of the configured stream, or {@code -1}.
     */
    private int reference = -1;

    /**
     * The channel configuration of the configured stream.
     */
    private int channels;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    AdtsParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = inheritTiming(new AdtsParser(entry, limits));
        parser.reference = reference;
        parser.channels = channels;
        return parser;
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == (byte) 0xFF;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        if (available < 2) {
            return 0;
        }
        if ((data[offset + 1] & 0xF6) != 0xF0) {
            return -1;
        }
        if (available < AdtsHeader.SIZE) {
            return 0;
        }
        var header = AdtsHeader.parse(data, offset);
        if (header == null) {
            return -1;
        }
        var shared = ((data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF)) & STREAM_MASK;
        if (reference >= 0 && (shared != reference || header.channelConfiguration() != channels)) {
            return -1;
        }
        return header.frameLength();
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var header = AdtsHeader.parse(data, offset);
        var payload = Arrays.copyOfRange(data, offset + header.headerSize(), offset + length);
        if (track == null) {
            configure(header, data, offset, payload);
        }
        var samples = 1024 * header.blocks();
        output.emit(payload, samples, samples);
    }

    /**
     * Describes the stream from its first frame.
     *
     * @param header the first header
     * @param data the bytes
     * @param offset the index of the first header in {@code data}
     * @param payload the first raw data block
     */
    private void configure(AdtsHeader header, byte[] data, int offset, byte[] payload) {
        var rate = header.sampleRate();
        var channelConfiguration = header.channelConfiguration();
        var count = channelConfiguration == 7 ? 8 : channelConfiguration;
        var described = AudioTrack.builder()
                .id(pid())
                .codec(Codec.AAC)
                .timeBase(Rational.timeBaseOf(rate))
                .codecConfig(header.audioSpecificConfig())
                .language(entry.language())
                .sampleRate(rate)
                .layout(count > 0 ? ChannelLayout.defaultFor(count) : null)
                .build();
        if (header.profile() == 1 && rate <= AacExtensions.MAX_IMPLICIT_CORE_RATE) {
            described = AacExtensions.refine(described, payload);
        }
        this.track = described;
        this.timestampRate = rate;
        this.reference = ((data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF)) & STREAM_MASK;
        this.channels = channelConfiguration;
    }
}
