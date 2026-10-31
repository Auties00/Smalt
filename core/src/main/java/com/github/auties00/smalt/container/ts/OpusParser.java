package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits an Opus stream ({@code stream_type 0x06} with the {@code "Opus"} registration
 * descriptor, as the Opus mapping for MPEG-2 transport streams defines it) into Opus packets.
 *
 * <p>Every access unit starts with an {@code opus_control_header}: the 11-bit prefix
 * {@code 0x3FF}, the start trim, end trim and control extension flags, the access unit size as a
 * run of {@code 0xFF} bytes plus a final byte, then the optional 13-bit trims and extension.
 * Packets are the access units without their control header. The track's {@code OpusHead} is
 * built from the {@code channel_config_code} of the DVB extension descriptor (tag {@code 0x7F},
 * extension tag {@code 0x80}): codes 1 to 8 are that many channels with the Vorbis channel
 * mapping of mapping family 1 above two channels, code {@code 0x80} is dual mono, and a missing
 * descriptor means stereo. The pre-skip is the start trim of the first access unit. An end trim
 * shortens the packet's {@link com.github.auties00.smalt.track.Packet#duration()}. Timestamps
 * count 48 kHz samples.
 */
final class OpusParser extends FrameParser {
    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    OpusParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = inheritTiming(new OpusParser(entry, limits));
        return parser;
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == 0x7F;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        var header = headerLength(data, offset, available);
        if (header <= 0) {
            return header;
        }
        return header + auSize(data, offset);
    }

    /**
     * Returns the length of the control header at an index.
     *
     * @param data the bytes
     * @param offset the index of the header
     * @param available the number of bytes available
     * @return the header length; {@code 0} when more bytes are needed; {@code -1} when no control
     * header starts there
     */
    private static int headerLength(byte[] data, int offset, int available) {
        if (available < 2) {
            return 0;
        }
        if ((data[offset + 1] & 0xE0) != 0xE0) {
            return -1;
        }
        var flags = data[offset + 1] & 0xFF;
        var position = 2;
        while (true) {
            if (position >= available) {
                return 0;
            }
            var value = data[offset + position++] & 0xFF;
            if (value != 0xFF) {
                break;
            }
            if (position > 64) {
                return -1;
            }
        }
        if ((flags & 0x10) != 0) {
            position += 2;
        }
        if ((flags & 0x08) != 0) {
            position += 2;
        }
        if ((flags & 0x04) != 0) {
            if (position >= available) {
                return 0;
            }
            position += 1 + (data[offset + position] & 0xFF);
        }
        return position <= available ? position : 0;
    }

    /**
     * Returns the access unit size a control header declares.
     *
     * @param data the bytes
     * @param offset the index of the header
     * @return the size of the Opus packet that follows the header
     */
    private static int auSize(byte[] data, int offset) {
        var size = 0;
        var position = offset + 2;
        while (true) {
            var value = data[position++] & 0xFF;
            size += value;
            if (value != 0xFF) {
                return size;
            }
        }
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var header = headerLength(data, offset, length);
        var flags = data[offset + 1] & 0xFF;
        var position = offset + 2;
        while ((data[position] & 0xFF) == 0xFF) {
            position++;
        }
        position++;
        var startTrim = 0;
        var endTrim = 0;
        if ((flags & 0x10) != 0) {
            startTrim = ((data[position] & 0x1F) << 8) | (data[position + 1] & 0xFF);
            position += 2;
        }
        if ((flags & 0x08) != 0) {
            endTrim = ((data[position] & 0x1F) << 8) | (data[position + 1] & 0xFF);
        }
        var packet = Arrays.copyOfRange(data, offset + header, offset + length);
        if (track == null) {
            configure(startTrim);
        }
        var samples = AudioConfigs.opusPacketSamples(packet);
        output.emit(packet, samples, Math.max(0, samples - endTrim));
    }

    /**
     * Describes the stream.
     *
     * @param preSkip the start trim of the first access unit
     */
    private void configure(int preSkip) {
        var code = entry.opusChannels();
        var channels = code == 0x80 ? 2 : code >= 1 && code <= 8 ? code : 2;
        var head = AudioConfigs.opusHead(channels, preSkip, 48_000, code == 0x80);
        timestampRate = 48_000;
        track = AudioTrack.builder()
                .id(trackId())
                .codec(Codec.OPUS)
                .timeBase(Rational.timeBaseOf(48_000))
                .codecConfig(head)
                .language(entry.language())
                .sampleRate(48_000)
                .layout(ChannelLayout.defaultFor(channels))
                .build();
    }
}
