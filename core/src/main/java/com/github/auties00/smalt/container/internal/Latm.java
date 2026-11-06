package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Reads AAC in LOAS/LATM framing (ISO/IEC 14496-3 clause 1.7): the {@code AudioSyncStream} frames
 * of LOAS and the {@code AudioMuxElement} each one holds.
 *
 * <p>A frame is the 11-bit sync word {@code 0x2B7}, a 13-bit length and that many bytes of one
 * {@code AudioMuxElement} with in-band configuration. The {@code StreamMuxConfig} may declare any number of
 * programs and layers, with the same time framing for all streams or chunked payloads, and every stream with
 * {@code frameLengthType} 0 (lengths in {@code PayloadLengthInfo}) or 1 (a fixed frame length); the payloads of
 * the first stream, program 0 and layer 0, are the ones returned. Its {@code AudioSpecificConfig} is copied
 * byte aligned, a program config element included (channel configuration zero), and must be of AAC Main, LC,
 * SSR or LTP, alone or under HE-AAC or HE-AAC v2. Not supported: {@code audioMuxVersionA} 1, whose syntax the
 * standard leaves to be defined, and streams with the CELP or HVXC frame length types (3 to 7), whose payload
 * lengths come from the tables of those codecs.
 */
public final class Latm {
    /**
     * The largest number of streams a {@code StreamMuxConfig} declares: 16 programs of 8 layers.
     */
    private static final int MAX_STREAMS = 128;

    /**
     * Prevents instantiation.
     */
    private Latm() {
        throw new AssertionError();
    }

    /**
     * Holds the fields of a {@code StreamMuxConfig} needed to read the payloads.
     *
     * @param audioSpecificConfig the {@code AudioSpecificConfig} of the first stream, byte aligned
     * @param version {@code audioMuxVersion}
     * @param subframes {@code numSubFrames + 1}
     * @param otherData whether other data follows the payload
     * @param otherDataBits the length of the other data
     * @param crc whether a CRC follows the configuration
     * @param coreRate the core sampling rate of the first stream
     * @param channels the channel configuration of the first stream; {@code 0} when a program config element
     * declares its channels
     * @param declaredChannels the number of channels of the first stream: those of its channel configuration or
     * of its program config element
     * @param sameTimeFraming {@code allStreamsSameTimeFraming}
     * @param frameLengthTypes the {@code frameLengthType} of every stream, by stream index
     * @param frameLengths the payload length in bytes of every stream of {@code frameLengthType} 1, by stream
     * index
     */
    public record Config(byte[] audioSpecificConfig, int version, int subframes, boolean otherData,
                         long otherDataBits, boolean crc, int coreRate, int channels, int declaredChannels,
                         boolean sameTimeFraming, int[] frameLengthTypes, int[] frameLengths) {
        /**
         * Returns the number of streams.
         *
         * @return the number of program and layer pairs
         */
        public int streams() {
            return frameLengthTypes.length;
        }
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
        try {
            return streamMuxConfig(bits);
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * Reads a {@code StreamMuxConfig}.
     *
     * @param bits the reader, after {@code useSameStreamMux}
     * @return the configuration, or {@code null} for an unsupported layout
     * @throws MediaException.Malformed if a program config element is truncated
     */
    private static Config streamMuxConfig(MsbBitReader bits) {
        var version = bits.read(1);
        if (version == 1 && bits.read(1) == 1) {
            return null;
        }
        if (version == 1) {
            latmValue(bits);
        }
        var sameTimeFraming = bits.readFlag();
        var subframes = bits.read(6) + 1;
        var programs = bits.read(4) + 1;
        var types = new ArrayList<Integer>();
        var lengths = new ArrayList<Integer>();
        Asc first = null;
        for (var program = 0; program < programs; program++) {
            var layers = bits.read(3) + 1;
            var objectType = 0;
            for (var layer = 0; layer < layers; layer++) {
                var useSameConfig = (program != 0 || layer != 0) && bits.readFlag();
                var previousObjectType = objectType;
                var layerObjectType = objectType;
                if (!useSameConfig) {
                    long ascBits = -1;
                    if (version == 1) {
                        ascBits = latmValue(bits);
                    }
                    var start = bits.bitPosition();
                    Asc asc;
                    if (program == 0 && layer == 0) {
                        asc = readAudioSpecificConfig(bits, start);
                        if (asc == null) {
                            return null;
                        }
                        first = asc;
                    } else if (ascBits >= 0) {
                        asc = new Asc(null, 0, 0, 0, peekObjectType(bits));
                    } else {
                        asc = readAudioSpecificConfig(bits, start);
                        if (asc == null) {
                            return null;
                        }
                    }
                    layerObjectType = asc.objectType;
                    if (ascBits >= 0) {
                        var used = bits.bitPosition() - start;
                        if (used > ascBits) {
                            return null;
                        }
                        bits.skip(ascBits - used);
                    }
                }
                var frameLengthType = bits.read(3);
                var frameLength = 0;
                switch (frameLengthType) {
                    case 0 -> {
                        bits.read(8);
                        if (!sameTimeFraming && layer > 0 && (layerObjectType == 6 || layerObjectType == 20)
                                && (previousObjectType == 8 || previousObjectType == 24)) {
                            bits.read(6);
                        }
                    }
                    case 1 -> frameLength = bits.read(9) + 20;
                    // TODO: CELP and HVXC streams (frame length types 3 to 7), whose payload lengths come from the
                    // frame length tables of those codecs; Smalt has no CELP or HVXC decoder
                    case 3, 4, 5 -> {
                        bits.read(6);
                        return null;
                    }
                    case 6, 7 -> {
                        bits.read(1);
                        return null;
                    }
                    default -> {
                        return null;
                    }
                }
                if (types.size() >= MAX_STREAMS) {
                    return null;
                }
                types.add(frameLengthType);
                lengths.add(frameLength);
                objectType = layerObjectType;
            }
        }
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
        if (bits.overrun() || first == null) {
            return null;
        }
        var typeArray = types.stream().mapToInt(Integer::intValue).toArray();
        var lengthArray = lengths.stream().mapToInt(Integer::intValue).toArray();
        return new Config(first.bytes, version, subframes, otherData, otherDataBits, crc, first.coreRate,
                first.channels, first.declaredChannels, sameTimeFraming, typeArray, lengthArray);
    }

    /**
     * Returns the length of the syntax of an {@code AudioSpecificConfig} a {@code StreamMuxConfig} of
     * {@code audioMuxVersion} 0 carries, which a reader finds by parsing it: the object types, rates, channel
     * configuration and {@code GASpecificConfig}, a program config element included.
     *
     * @param config the configuration, byte aligned
     * @return the length in bits, at most {@code 8 * config.length}, or {@code -1} when the configuration is not of a
     * General Audio object type this class reads, alone or under HE-AAC or HE-AAC v2, or is truncated
     */
    public static long audioSpecificConfigBits(byte[] config) {
        var bits = new MsbBitReader(config);
        try {
            if (readAudioSpecificConfig(bits, 0) == null || bits.overrun()) {
                return -1;
            }
        } catch (MediaException exception) {
            return -1;
        }
        return bits.bitPosition();
    }

    /**
     * Reads the payloads of the subframes of an {@code AudioMuxElement}, after its configuration, and returns
     * those of the first stream.
     *
     * <p>With the same time framing, each subframe holds one payload of every stream. Otherwise each subframe
     * holds chunks of the streams in any order, and a payload of the first stream ends with the chunk whose
     * {@code AuEndFlag} is set; a payload still open at the end of the element is dropped.
     *
     * @param bits the reader, after {@code useSameStreamMux} and the configuration it announces
     * @param config the configuration in force
     * @param limit the largest payload accepted, in bytes
     * @return the payloads of the first stream in order, or {@code null} when the element is damaged
     */
    public static byte[][] readPayloads(MsbBitReader bits, Config config, int limit) {
        var payloads = new ArrayList<byte[]>(config.subframes());
        var streams = config.streams();
        var sizes = new int[streams];
        var pending = new byte[0];
        for (var subframe = 0; subframe < config.subframes(); subframe++) {
            if (config.sameTimeFraming()) {
                for (var stream = 0; stream < streams; stream++) {
                    sizes[stream] = config.frameLengthTypes()[stream] == 0 ? slotLength(bits)
                            : config.frameLengths()[stream];
                }
                for (var stream = 0; stream < streams; stream++) {
                    if (sizes[stream] > limit) {
                        return null;
                    }
                    if (stream == 0) {
                        payloads.add(readBytes(bits, sizes[stream]));
                    } else {
                        bits.skip(8L * sizes[stream]);
                    }
                }
            } else {
                var chunks = bits.read(4) + 1;
                var indexes = new int[chunks];
                var chunkSizes = new int[chunks];
                var ends = new boolean[chunks];
                for (var chunk = 0; chunk < chunks; chunk++) {
                    var index = bits.read(4);
                    if (index >= streams) {
                        return null;
                    }
                    indexes[chunk] = index;
                    if (config.frameLengthTypes()[index] == 0) {
                        chunkSizes[chunk] = slotLength(bits);
                        ends[chunk] = bits.readFlag();
                    } else {
                        chunkSizes[chunk] = config.frameLengths()[index];
                        ends[chunk] = true;
                    }
                }
                for (var chunk = 0; chunk < chunks; chunk++) {
                    if (chunkSizes[chunk] > limit) {
                        return null;
                    }
                    if (indexes[chunk] != 0) {
                        bits.skip(8L * chunkSizes[chunk]);
                        continue;
                    }
                    var bytes = readBytes(bits, chunkSizes[chunk]);
                    if (pending.length + bytes.length > limit) {
                        return null;
                    }
                    var joined = Arrays.copyOf(pending, pending.length + bytes.length);
                    System.arraycopy(bytes, 0, joined, pending.length, bytes.length);
                    pending = joined;
                    if (ends[chunk]) {
                        payloads.add(pending);
                        pending = new byte[0];
                    }
                }
            }
            if (bits.overrun()) {
                return null;
            }
        }
        return payloads.toArray(byte[][]::new);
    }

    /**
     * Reads a {@code MuxSlotLengthBytes} field.
     *
     * @param bits the reader
     * @return the payload length in bytes
     */
    private static int slotLength(MsbBitReader bits) {
        var size = 0;
        while (true) {
            var value = bits.read(8);
            size += value;
            if (value != 255 || bits.overrun()) {
                return size;
            }
        }
    }

    /**
     * Reads bytes at any bit position.
     *
     * @param bits the reader
     * @param count the number of bytes
     * @return the bytes
     */
    private static byte[] readBytes(MsbBitReader bits, int count) {
        var bytes = new byte[count];
        for (var position = 0; position < count; position++) {
            bytes[position] = (byte) bits.read(8);
        }
        return bytes;
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
     * @param bytes the configuration, byte aligned, or {@code null} when only its object type was read
     * @param coreRate the core sampling rate
     * @param channels the channel configuration
     * @param declaredChannels the number of channels of the channel configuration or program config element
     * @param objectType the audio object type of the core
     */
    private record Asc(byte[] bytes, int coreRate, int channels, int declaredChannels, int objectType) {
    }

    /**
     * Returns the audio object type an {@code AudioSpecificConfig} starts with, without consuming it.
     *
     * @param bits the reader, at the configuration
     * @return the object type
     */
    private static int peekObjectType(MsbBitReader bits) {
        var type = bits.peek(5);
        if (type != 31) {
            return type;
        }
        return 32 + (bits.peek(11) & 0x3F);
    }

    /**
     * Reads an {@code AudioSpecificConfig} of a General Audio object type (ISO/IEC 14496-3 clause
     * 1.6.2.1) and copies its bits into a byte-aligned configuration.
     *
     * @param bits the reader
     * @param start the bit position of the configuration, to which the byte alignment of a program config
     * element is relative
     * @return the configuration, or {@code null} for an unsupported object type or channel
     * configuration
     * @throws MediaException.Malformed if a program config element is truncated
     */
    private static Asc readAudioSpecificConfig(MsbBitReader bits, long start) {
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
        if (objectType < 1 || objectType > 4 || channels > 7 || coreRate <= 0) {
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
        var declared = channels == 7 ? 8 : channels;
        if (channels == 0) {
            writer.write(bits.read(10), 10);
            declared = AacRawBlocks.copyProgramConfig(bits, writer, start);
            if (declared == 0) {
                return null;
            }
        }
        if (extensionFlag == 1) {
            writer.write(bits.read(1), 1);
        }
        writer.alignWithZeros();
        return new Asc(writer.toByteArray(), coreRate, channels, declared, objectType);
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
