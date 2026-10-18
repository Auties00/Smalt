package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;


/**
 * Splits an AAC stream in LOAS/LATM framing ({@code stream_type 0x11}, ISO/IEC 14496-3 clause
 * 1.7) into raw access units.
 *
 * <p>Each {@code AudioSyncStream} frame (the 11-bit sync word {@code 0x2B7} and a 13-bit length)
 * holds one {@code AudioMuxElement} with in-band configuration. The parser supports the layout
 * broadcast and every common encoder use: {@code audioMuxVersion} 0 or 1 (not
 * {@code audioMuxVersionA} 1), one program with one layer, all streams with the same time framing, and
 * {@code frameLengthType} 0 (payload lengths in {@code PayloadLengthInfo}). The
 * {@code AudioSpecificConfig} of the {@code StreamMuxConfig} becomes the track configuration,
 * byte aligned; an object type other than AAC Main, LC, SSR, LTP, HE-AAC or HE-AAC v2, or a
 * channel configuration of zero (a program config element), is not supported, and such a stream
 * is not exposed. Every subframe becomes a packet of 1024 samples of the core rate. Elements
 * that reuse the configuration before one was seen are skipped.
 */
final class LatmParser extends FrameParser {
    /**
     * The configuration last seen in band, or {@code null}.
     */
    private Config config;

    /**
     * Whether the track was just configured and its first payload must still be decoded to
     * detect HE-AAC.
     */
    private boolean refinePending;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    LatmParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    /**
     * Holds the fields of a {@code StreamMuxConfig} the parser needs to read the payloads.
     *
     * @param audioSpecificConfig the {@code AudioSpecificConfig}, byte aligned
     * @param version {@code audioMuxVersion}
     * @param subframes {@code numSubFrames + 1}
     * @param otherData whether other data follows the payload
     * @param otherDataBits the length of the other data of version 1 elements
     * @param crc whether a CRC follows the configuration
     * @param coreRate the core sampling rate
     * @param channels the channel configuration
     */
    private record Config(byte[] audioSpecificConfig, int version, int subframes, boolean otherData,
                         long otherDataBits, boolean crc, int coreRate, int channels) {
    }

    @Override
    ElementaryParser fresh() {
        var parser = inheritTiming(new LatmParser(entry, limits));
        return parser;
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == 0x56;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        if (available < 2) {
            return 0;
        }
        if ((data[offset + 1] & 0xE0) != 0xE0) {
            return -1;
        }
        if (available < 3) {
            return 0;
        }
        var length = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
        return length > 0 ? length + 3 : -1;
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var bits = new MsbBitReader(data, offset + 3, length - 3);
        try {
            if (!bits.readFlag()) {
                var parsed = readStreamMuxConfig(bits);
                if (parsed == null) {
                    return;
                }
                if (track == null) {
                    configure(parsed);
                }
                config = parsed;
            } else if (config == null) {
                return;
            }
            var current = config;
            var payloads = new byte[current.subframes][];
            for (var index = 0; index < current.subframes; index++) {
                var size = 0;
                while (true) {
                    var value = bits.read(8);
                    size += value;
                    if (value != 255) {
                        break;
                    }
                }
                if (size > length) {
                    return;
                }
                var payload = new byte[size];
                for (var position = 0; position < size; position++) {
                    payload[position] = (byte) bits.read(8);
                }
                if (bits.overrun()) {
                    return;
                }
                payloads[index] = payload;
            }
            for (var payload : payloads) {
                if (refinePending) {
                    refinePending = false;
                    track = AacExtensions.refine((AudioTrack) track, payload);
                }
                output.emit(payload, 1024, 1024);
            }
        } catch (RuntimeException exception) {
            // a damaged element loses its payloads; the stream continues at the next sync word
        }
    }

    /**
     * Reads a {@code StreamMuxConfig} (ISO/IEC 14496-3 clause 1.7.3.1).
     *
     * @param bits the reader, after {@code useSameStreamMux}
     * @return the configuration, or {@code null} for an unsupported layout
     */
    private static Config readStreamMuxConfig(MsbBitReader bits) {
        var version = bits.read(1);
        if (version == 1 && bits.read(1) == 1) {
            return null;
        }
        if (version == 1) {
            latmValue(bits);
        }
        var sameTimeFraming = bits.readFlag();
        var subframes = bits.read(6) + 1;
        var programs = bits.read(4);
        var layers = bits.read(3);
        if (!sameTimeFraming || programs != 0 || layers != 0) {
            return null;
        }
        long ascBits = -1;
        if (version == 1) {
            ascBits = latmValue(bits);
        }
        var start = bits.bitPosition();
        var asc = readAudioSpecificConfig(bits);
        if (asc == null) {
            return null;
        }
        if (ascBits >= 0) {
            var used = bits.bitPosition() - start;
            if (used > ascBits) {
                return null;
            }
            bits.skip(ascBits - used);
        }
        var frameLengthType = bits.read(3);
        if (frameLengthType != 0) {
            return null;
        }
        bits.read(8);
        var otherData = bits.readFlag();
        long otherDataBits = 0;
        if (otherData) {
            if (version == 1) {
                otherDataBits = latmValue(bits);
            } else {
                boolean escape;
                do {
                    escape = bits.readFlag();
                    otherDataBits = (otherDataBits << 8) + bits.read(8);
                } while (escape && otherDataBits < (1L << 40));
            }
        }
        var crc = bits.readFlag();
        if (crc) {
            bits.read(8);
        }
        if (bits.overrun()) {
            return null;
        }
        return new Config(asc.bytes, version, subframes, otherData, otherDataBits, crc, asc.coreRate, asc.channels);
    }

    /**
     * Reads a {@code LatmGetValue()} field.
     *
     * @param bits the reader
     * @return the value
     */
    private static long latmValue(MsbBitReader bits) {
        var bytes = bits.read(2) + 1;
        var value = 0L;
        for (var index = 0; index < bytes; index++) {
            value = (value << 8) | bits.read(8);
        }
        return value;
    }

    /**
     * Holds an {@code AudioSpecificConfig} read from a bit stream.
     *
     * @param bytes the configuration, byte aligned
     * @param coreRate the core sampling rate
     * @param channels the channel configuration
     */
    private record Asc(byte[] bytes, int coreRate, int channels) {
    }

    /**
     * Reads an {@code AudioSpecificConfig} of a General Audio object type (ISO/IEC 14496-3 clause
     * 1.6.2.1) and copies its bits into a byte-aligned configuration.
     *
     * @param bits the reader
     * @return the configuration, or {@code null} for an unsupported object type or channel
     * configuration
     */
    private static Asc readAudioSpecificConfig(MsbBitReader bits) {
        var writer = new MsbBitWriter(16);
        var objectType = copyObjectType(bits, writer);
        var frequency = bits.read(4);
        writer.write(frequency, 4);
        int coreRate;
        if (frequency == 15) {
            coreRate = bits.read(24);
            writer.write(coreRate, 24);
        } else {
            coreRate = AacRates.rate(frequency);
            if (coreRate == 0) {
                return null;
            }
        }
        var channels = bits.read(4);
        writer.write(channels, 4);
        if (objectType == 5 || objectType == 29) {
            var extension = bits.read(4);
            writer.write(extension, 4);
            if (extension == 15) {
                writer.write(bits.read(24), 24);
            }
            objectType = copyObjectType(bits, writer);
        }
        if (objectType < 1 || objectType > 4 || channels == 0 || channels > 7 || coreRate <= 0) {
            return null;
        }
        writer.write(bits.read(1), 1);
        var dependsOnCoreCoder = bits.read(1);
        writer.write(dependsOnCoreCoder, 1);
        if (dependsOnCoreCoder == 1) {
            writer.write(bits.read(14), 14);
        }
        var extensionFlag = bits.read(1);
        writer.write(extensionFlag, 1);
        if (extensionFlag == 1) {
            writer.write(bits.read(1), 1);
        }
        writer.alignWithZeros();
        return new Asc(writer.toByteArray(), coreRate, channels);
    }

    /**
     * Copies an audio object type with its escape.
     *
     * @param bits the reader
     * @param writer the configuration being written
     * @return the object type
     */
    private static int copyObjectType(MsbBitReader bits, MsbBitWriter writer) {
        var type = bits.read(5);
        writer.write(type, 5);
        if (type == 31) {
            var escaped = bits.read(6);
            writer.write(escaped, 6);
            return 32 + escaped;
        }
        return type;
    }

    /**
     * Describes the stream from its first configuration.
     *
     * <p>Whether the stream is HE-AAC is decided on the first payload, which
     * {@link AacExtensions#refine(AudioTrack, byte[])} decodes.
     *
     * @param parsed the configuration
     */
    private void configure(Config parsed) {
        var count = parsed.channels == 7 ? 8 : parsed.channels;
        var described = AudioTrack.builder()
                .id(pid())
                .codec(Codec.AAC)
                .timeBase(Rational.timeBaseOf(parsed.coreRate))
                .codecConfig(parsed.audioSpecificConfig)
                .language(entry.language())
                .sampleRate(parsed.coreRate)
                .layout(ChannelLayout.defaultFor(count))
                .build();
        track = described;
        timestampRate = parsed.coreRate;
        refinePending = true;
    }
}
