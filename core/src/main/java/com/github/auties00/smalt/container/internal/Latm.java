package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;

/**
 * Reads AAC in LOAS/LATM framing (ISO/IEC 14496-3 clause 1.7): the {@code AudioSyncStream} frames
 * of LOAS and the {@code AudioMuxElement} each one holds.
 *
 * <p>A frame is the 11-bit sync word {@code 0x2B7}, a 13-bit length and that many bytes of one
 * {@code AudioMuxElement} with in-band configuration. The supported layout is the one broadcast and
 * every common encoder use: {@code audioMuxVersion} 0 or 1 (not {@code audioMuxVersionA} 1), one
 * program with one layer, all streams with the same time framing, and {@code frameLengthType} 0
 * (payload lengths in {@code PayloadLengthInfo}). The {@code AudioSpecificConfig} of the
 * {@code StreamMuxConfig} is copied byte aligned; an object type other than AAC Main, LC, SSR, LTP,
 * HE-AAC or HE-AAC v2, or a channel configuration of zero (a program config element), is not
 * supported.
 */
public final class Latm {
    /**
     * Prevents instantiation.
     */
    private Latm() {
        throw new AssertionError();
    }

    /**
     * Holds the fields of a {@code StreamMuxConfig} needed to read the payloads.
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
    public record Config(byte[] audioSpecificConfig, int version, int subframes, boolean otherData,
                         long otherDataBits, boolean crc, int coreRate, int channels) {
    }

    /**
     * Returns the length of the {@code AudioSyncStream} frame starting at an index.
     *
     * @param data the bytes
     * @param offset the index of the candidate sync word
     * @param available the number of bytes available from {@code offset}
     * @return the frame length, header included; {@code 0} when more bytes are needed to tell; {@code -1} when no
     * frame starts there
     */
    public static int frameLength(byte[] data, int offset, int available) {
        if (available < 1) {
            return 0;
        }
        if (data[offset] != 0x56) {
            return -1;
        }
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

    /**
     * Reads a {@code StreamMuxConfig} (ISO/IEC 14496-3 clause 1.7.3.1).
     *
     * @param bits the reader, after {@code useSameStreamMux}
     * @return the configuration, or {@code null} for an unsupported layout
     */
    public static Config readStreamMuxConfig(MsbBitReader bits) {
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
     * Reads the payloads of the subframes of an {@code AudioMuxElement}, after its configuration.
     *
     * @param bits the reader, after {@code useSameStreamMux} and the configuration it announces
     * @param config the configuration in force
     * @param limit the largest payload accepted, in bytes
     * @return one payload per subframe, or {@code null} when the element is damaged
     */
    public static byte[][] readPayloads(MsbBitReader bits, Config config, int limit) {
        var payloads = new byte[config.subframes()][];
        for (var index = 0; index < config.subframes(); index++) {
            var size = 0;
            while (true) {
                var value = bits.read(8);
                size += value;
                if (value != 255) {
                    break;
                }
            }
            if (size > limit) {
                return null;
            }
            var payload = new byte[size];
            for (var position = 0; position < size; position++) {
                payload[position] = (byte) bits.read(8);
            }
            if (bits.overrun()) {
                return null;
            }
            payloads[index] = payload;
        }
        return payloads;
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
}
