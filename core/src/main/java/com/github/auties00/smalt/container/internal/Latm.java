package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Reads AAC in LOAS/LATM framing (ISO/IEC 14496-3 clause 1.7): the {@code AudioSyncStream} frames
 * of LOAS and the {@code AudioMuxElement} each one holds.
 *
 * <p>A frame is the 11-bit sync word {@code 0x2B7}, a 13-bit length and that many bytes of one
 * {@code AudioMuxElement} with in-band configuration. The {@code StreamMuxConfig} may declare any number of
 * programs and layers, with the same time framing for all streams or chunked payloads, and every stream with
 * {@code frameLengthType} 0 (lengths in {@code PayloadLengthInfo}), 1 (a fixed frame length) or 3 to 7 (the CELP
 * and HVXC payloads, whose lengths in bits come from the frame length tables of ISO/IEC 14496-3 Tables 1.46 to
 * 1.48); the payloads of the first stream, program 0 and layer 0, are the ones returned. Its
 * {@code AudioSpecificConfig} is copied byte aligned, a program config element included (channel configuration
 * zero), and must be of AAC Main, LC, SSR or LTP, alone or under HE-AAC or HE-AAC v2, of CELP or ER CELP, or of HVXC
 * or ER HVXC. An ER CELP or ER HVXC stream may apply the error protection tool ({@code epConfig} 2 or 3, its
 * {@code ErrorProtectionSpecificConfig} copied with the configuration), whose protected payloads take their lengths
 * from {@code PayloadLengthInfo} or a fixed frame length, not from the CELP and HVXC frame length tables;
 * {@code epConfig} 1 is rejected, as subclause 1.7.1 excludes it from LATM. A payload whose length is not a whole
 * number of bytes is returned with its last byte padded with zero bits. A stream keeps the codec its first
 * configuration names: the callers skip the frames of a later configuration of another codec. Not supported:
 * {@code audioMuxVersionA} 1, whose syntax the standard leaves to be defined.
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
     * @param frameLengths the payload lengths in bits of every stream by stream index, indexed by
     * {@code MuxSlotLengthCoded} for {@code frameLengthType} 3, 5 and 7 and all four equal for the fixed lengths of
     * {@code frameLengthType} 1, 4 and 6; {@code null} for {@code frameLengthType} 0
     * @param objectType the audio object type of the first stream: 1 to 4 for AAC, 8 or 24 for CELP, 9 or 25 for
     * HVXC
     * @param frameSamples the samples one payload of the first stream codes at {@code coreRate}
     */
    public record Config(byte[] audioSpecificConfig, int version, int subframes, boolean otherData,
                         long otherDataBits, boolean crc, int coreRate, int channels, int declaredChannels,
                         boolean sameTimeFraming, int[] frameLengthTypes, int[][] frameLengths, int objectType,
                         int frameSamples) {
        /**
         * Returns the number of streams.
         *
         * @return the number of program and layer pairs
         */
        public int streams() {
            return frameLengthTypes.length;
        }

        /**
         * Returns the codec of the first stream.
         *
         * @return {@link Codec#CELP} for CELP (8) and ER CELP (24), {@link Codec#HVXC} for HVXC (9) and ER HVXC
         * (25), {@link Codec#AAC} otherwise
         */
        public Codec codec() {
            return switch (objectType) {
                case 8, 24 -> Codec.CELP;
                case 9, 25 -> Codec.HVXC;
                default -> Codec.AAC;
            };
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
        var lengths = new ArrayList<int[]>();
        Asc first = null;
        Asc previous = null;
        for (var program = 0; program < programs; program++) {
            var layers = bits.read(3) + 1;
            var objectType = 0;
            for (var layer = 0; layer < layers; layer++) {
                var useSameConfig = (program != 0 || layer != 0) && bits.readFlag();
                var previousObjectType = objectType;
                var layerObjectType = objectType;
                var asc = previous;
                if (!useSameConfig) {
                    long ascBits = -1;
                    if (version == 1) {
                        ascBits = latmValue(bits);
                    }
                    var start = bits.bitPosition();
                    if (program == 0 && layer == 0) {
                        asc = readAudioSpecificConfig(bits, start);
                        if (asc == null || asc.frameSamples == 0) {
                            return null;
                        }
                        first = asc;
                    } else if (ascBits >= 0) {
                        asc = new Asc(null, 0, 0, 0, peekObjectType(bits), 0, false);
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
                if (asc.errorProtected() && frameLengthType >= 3) {
                    return null;
                }
                int[] frameLength;
                switch (frameLengthType) {
                    case 0 -> {
                        bits.read(8);
                        if (!sameTimeFraming && layer > 0 && (layerObjectType == 6 || layerObjectType == 20)
                                && (previousObjectType == 8 || previousObjectType == 24)) {
                            bits.read(6);
                        }
                        frameLength = null;
                    }
                    case 1 -> frameLength = fixed(8 * (bits.read(9) + 20));
                    case 3, 4, 5 -> {
                        frameLength = celpLengths(frameLengthType, bits.read(6), layer);
                        if (frameLength == null || (layerObjectType != 8 && layerObjectType != 24)) {
                            return null;
                        }
                    }
                    case 6, 7 -> {
                        frameLength = hvxcLengths(frameLengthType, bits.read(1));
                        if (layerObjectType != 9 && layerObjectType != 25) {
                            return null;
                        }
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
                previous = asc;
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
        var lengthArray = lengths.toArray(int[][]::new);
        return new Config(first.bytes, version, subframes, otherData, otherDataBits, crc, first.coreRate,
                first.channels, first.declaredChannels, sameTimeFraming, typeArray, lengthArray, first.objectType,
                first.frameSamples);
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
     * Returns the samples one access unit of a stream codes, as the frame lengths of LATM take them.
     *
     * @param config the {@code AudioSpecificConfig}, byte aligned
     * @return 1024 for an AAC configuration, the frame samples of a CELP base layer, 160 for an HVXC base layer,
     * {@code 0} for a CELP or HVXC enhancement layer, or {@code -1} when {@link #audioSpecificConfigBits(byte[])}
     * rejects the configuration
     */
    public static int audioSpecificConfigFrameSamples(byte[] config) {
        try {
            var asc = readAudioSpecificConfig(new MsbBitReader(config), 0);
            return asc == null ? -1 : asc.frameSamples();
        } catch (MediaException exception) {
            return -1;
        }
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
        var sizes = new long[streams];
        var pending = new byte[0];
        for (var subframe = 0; subframe < config.subframes(); subframe++) {
            if (config.sameTimeFraming()) {
                for (var stream = 0; stream < streams; stream++) {
                    sizes[stream] = payloadBits(bits, config, stream);
                }
                for (var stream = 0; stream < streams; stream++) {
                    if (sizes[stream] > 8L * limit) {
                        return null;
                    }
                    if (stream == 0) {
                        payloads.add(readBits(bits, sizes[stream]));
                    } else {
                        bits.skip(sizes[stream]);
                    }
                }
            } else {
                var chunks = bits.read(4) + 1;
                var indexes = new int[chunks];
                var chunkSizes = new long[chunks];
                var ends = new boolean[chunks];
                for (var chunk = 0; chunk < chunks; chunk++) {
                    var index = bits.read(4);
                    if (index >= streams) {
                        return null;
                    }
                    indexes[chunk] = index;
                    chunkSizes[chunk] = payloadBits(bits, config, index);
                    ends[chunk] = config.frameLengthTypes()[index] != 0 || bits.readFlag();
                }
                for (var chunk = 0; chunk < chunks; chunk++) {
                    if (chunkSizes[chunk] > 8L * limit) {
                        return null;
                    }
                    if (indexes[chunk] != 0) {
                        bits.skip(chunkSizes[chunk]);
                        continue;
                    }
                    var bytes = readBits(bits, chunkSizes[chunk]);
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
     * Reads the length of the next payload of a stream from {@code PayloadLengthInfo}.
     *
     * @param bits the reader
     * @param config the configuration in force
     * @param stream the stream index
     * @return the payload length in bits
     */
    private static long payloadBits(MsbBitReader bits, Config config, int stream) {
        var lengths = config.frameLengths()[stream];
        return switch (config.frameLengthTypes()[stream]) {
            case 0 -> 8L * slotLength(bits);
            case 3, 5, 7 -> lengths[bits.read(2)];
            default -> lengths[0];
        };
    }

    /**
     * {@return the four payload lengths of a fixed length stream, indexed like {@code MuxSlotLengthCoded}}
     *
     * @param bits the length in bits
     */
    private static int[] fixed(int bits) {
        return new int[]{bits, bits, bits, bits};
    }

    /**
     * The CELP layer 0 frame lengths in bits per {@code CELPframeLengthTableIndex}: the fixed rate of
     * {@code frameLengthType} 4, then the two rates of {@code frameLengthType} 3 (ISO/IEC 14496-3 Table 1.47). The
     * first rate of {@code frameLengthType} 3 is also the active frame rate of {@code frameLengthType} 5.
     */
    private static final int[][] CELP_BASE_LENGTHS = {{154, 156, 134}, {170, 172, 150}, {186, 188, 166},
            {147, 149, 127}, {156, 158, 136}, {165, 167, 145}, {114, 116, 94}, {120, 122, 100}, {126, 128, 106},
            {132, 134, 112}, {138, 140, 118}, {142, 144, 122}, {146, 148, 126}, {154, 156, 134}, {166, 168, 146},
            {174, 176, 154}, {182, 184, 162}, {190, 192, 170}, {198, 200, 178}, {206, 208, 186}, {210, 212, 190},
            {214, 216, 194}, {110, 112, 90}, {114, 116, 94}, {118, 120, 98}, {120, 122, 100}, {122, 124, 102},
            {186, 188, 166}, {218, 220, 174}, {230, 232, 186}, {242, 244, 198}, {254, 256, 210}, {266, 268, 222},
            {278, 280, 234}, {286, 288, 242}, {294, 296, 250}, {318, 320, 276}, {342, 344, 298}, {358, 360, 314},
            {374, 376, 330}, {390, 392, 346}, {406, 408, 362}, {422, 424, 378}, {136, 138, 92}, {142, 144, 98},
            {148, 150, 104}, {154, 156, 110}, {160, 162, 116}, {166, 168, 122}, {170, 172, 126}, {174, 176, 130},
            {186, 188, 142}, {198, 200, 154}, {206, 208, 162}, {214, 216, 170}, {222, 224, 178}, {230, 232, 186},
            {238, 240, 194}, {216, 218, 172}, {160, 162, 116}, {280, 282, 238}, {338, 340, 296}};

    /**
     * The CELP layer 1 to 5 frame lengths in bits per {@code CELPframeLengthTableIndex}, the fixed rate of
     * {@code frameLengthType} 4 and the active frame rate of {@code frameLengthType} 5 (ISO/IEC 14496-3 Table 1.48).
     */
    private static final int[] CELP_ENHANCEMENT_LENGTHS = {80, 60, 40, 20, 368, 416, 464, 496, 284, 320, 356, 380,
            200, 224, 248, 264, 116, 128, 140, 148};

    /**
     * Returns the payload lengths of a CELP stream (ISO/IEC 14496-3 Tables 1.47 and 1.48), indexed by
     * {@code MuxSlotLengthCoded}.
     *
     * @param frameLengthType 3 (one of two rates, fine rate control), 4 (a fixed rate) or 5 (one of four rates,
     * silence compression)
     * @param index {@code CELPframeLengthTableIndex}
     * @param layer the layer of the stream in its program, 0 for the CELP base layer
     * @return the lengths in bits, or {@code null} for a reserved index or a frame length type the enhancement
     * layer table does not define
     */
    private static int[] celpLengths(int frameLengthType, int index, int layer) {
        if (layer == 0) {
            if (index >= CELP_BASE_LENGTHS.length) {
                return null;
            }
            var row = CELP_BASE_LENGTHS[index];
            return switch (frameLengthType) {
                case 4 -> fixed(row[0]);
                case 3 -> new int[]{row[1], row[2], 0, 0};
                default -> new int[]{row[1], index < 28 ? 23 : 40, 8, 2};
            };
        }
        if (index >= CELP_ENHANCEMENT_LENGTHS.length || frameLengthType == 3) {
            return null;
        }
        var length = CELP_ENHANCEMENT_LENGTHS[index];
        return frameLengthType == 4 ? fixed(length) : new int[]{length, index < 4 ? 0 : 21, 0, 0};
    }

    /**
     * Returns the payload lengths of an HVXC stream (ISO/IEC 14496-3 Table 1.46), indexed by
     * {@code MuxSlotLengthCoded}.
     *
     * @param frameLengthType 6 (a fixed rate) or 7 (one of four rates)
     * @param index {@code HVXCframeLengthTableIndex}
     * @return the lengths in bits
     */
    private static int[] hvxcLengths(int frameLengthType, int index) {
        if (frameLengthType == 6) {
            return fixed(index == 0 ? 40 : 80);
        }
        return index == 0 ? new int[]{40, 28, 2, 0} : new int[]{80, 40, 25, 3};
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
     * Reads bits at any bit position into bytes, the last one padded with zero bits.
     *
     * @param bits the reader
     * @param count the number of bits, below {@code 8L * Integer.MAX_VALUE}
     * @return the bytes
     */
    private static byte[] readBits(MsbBitReader bits, long count) {
        var bytes = new byte[(int) ((count + 7) >>> 3)];
        var whole = (int) (count >>> 3);
        for (var position = 0; position < whole; position++) {
            bytes[position] = (byte) bits.read(8);
        }
        var rest = (int) (count & 7);
        if (rest > 0) {
            bytes[whole] = (byte) (bits.read(rest) << (8 - rest));
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
     * @param frameSamples the samples one access unit codes at {@code coreRate}
     * @param errorProtected whether the stream applies the error protection tool ({@code epConfig} 2 or 3)
     */
    private record Asc(byte[] bytes, int coreRate, int channels, int declaredChannels, int objectType,
                       int frameSamples, boolean errorProtected) {
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
        if (objectType == 8 || objectType == 24) {
            var protection = new boolean[1];
            var frameSamples = copyCelpSpecificConfig(bits, writer, objectType, protection);
            if (frameSamples < 0) {
                return null;
            }
            writer.alignWithZeros();
            return new Asc(writer.toByteArray(), coreRate, channels, 1, objectType, frameSamples, protection[0]);
        }
        if (objectType == 9 || objectType == 25) {
            var protection = new boolean[1];
            var frameSamples = copyHvxcSpecificConfig(bits, writer, objectType, protection);
            if (frameSamples < 0) {
                return null;
            }
            writer.alignWithZeros();
            return new Asc(writer.toByteArray(), coreRate, channels, 1, objectType, frameSamples, protection[0]);
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
        return new Asc(writer.toByteArray(), coreRate, channels, declared, objectType, 1024, false);
    }

    /**
     * Copies a {@code CelpSpecificConfig}, or an {@code ErrorResilientCelpSpecificConfig} followed by its
     * {@code epConfig} and, for {@code epConfig} 2 and 3, its {@code ErrorProtectionSpecificConfig} and
     * {@code directMapping} (ISO/IEC 14496-3 Tables 1.15, 3.2 and 3.3 and subclause 1.8.2.1).
     *
     * @param bits the reader
     * @param writer the configuration being written
     * @param objectType 8 for CELP, 24 for ER CELP
     * @param protection receives in its first entry whether the stream applies the error protection tool
     * @return the samples of a frame of a base layer, {@code 0} for an enhancement layer, or {@code -1} for
     * {@code epConfig} 1, a reserved configuration or a reserved error protection configuration
     */
    private static int copyCelpSpecificConfig(MsbBitReader bits, MsbBitWriter writer, int objectType,
                                              boolean[] protection) {
        if (copy(bits, writer, 1) == 0) {
            copy(bits, writer, 3);
            return objectType == 24 && !copyErrorProtection(bits, writer, protection) ? -1 : 0;
        }
        var rpe = copy(bits, writer, 1);
        var wideband = copy(bits, writer, 1);
        copy(bits, writer, objectType == 24 ? 2 : 1);
        int frameSamples;
        if (rpe == 1) {
            frameSamples = AudioConfigs.celpRpeFrameSamples(copy(bits, writer, 3));
        } else {
            frameSamples = AudioConfigs.celpMpeFrameSamples(copy(bits, writer, 5), wideband == 1);
            copy(bits, writer, 3);
        }
        if (objectType == 24 && !copyErrorProtection(bits, writer, protection)) {
            return -1;
        }
        return frameSamples > 0 ? frameSamples : -1;
    }

    /**
     * Copies an {@code HvxcSpecificConfig}, or an {@code ErrorResilientHvxcSpecificConfig} followed by its
     * {@code epConfig} and, for {@code epConfig} 2 and 3, its {@code ErrorProtectionSpecificConfig} and
     * {@code directMapping} (ISO/IEC 14496-3 Tables 2.1 and 2.19 and subclause 1.8.2.1).
     *
     * @param bits the reader
     * @param writer the configuration being written
     * @param objectType 9 for HVXC, 25 for ER HVXC
     * @param protection receives in its first entry whether the stream applies the error protection tool
     * @return 160, the samples of a frame of a base layer, {@code 0} for an enhancement layer, or {@code -1} for
     * {@code epConfig} 1, the reserved {@code HVXCrateMode} or a reserved error protection configuration
     */
    private static int copyHvxcSpecificConfig(MsbBitReader bits, MsbBitWriter writer, int objectType,
                                              boolean[] protection) {
        var base = copy(bits, writer, 1);
        if (base == 1) {
            copy(bits, writer, 1);
            if (copy(bits, writer, 2) == 3) {
                return -1;
            }
            if (copy(bits, writer, 1) == 1) {
                copy(bits, writer, 1);
            }
        }
        if (objectType == 25 && !copyErrorProtection(bits, writer, protection)) {
            return -1;
        }
        return bits.overrun() ? -1 : base == 1 ? 160 : 0;
    }

    /**
     * Copies the {@code epConfig} of an error resilient configuration, followed for {@code epConfig} 2 and 3 by its
     * {@code ErrorProtectionSpecificConfig} and for 3 by {@code directMapping}.
     *
     * @param bits the reader
     * @param writer the configuration being written
     * @param protection receives in its first entry whether the stream applies the error protection tool
     * @return {@code false} for {@code epConfig} 1 or a reserved error protection configuration
     */
    private static boolean copyErrorProtection(MsbBitReader bits, MsbBitWriter writer, boolean[] protection) {
        var epConfig = copy(bits, writer, 2);
        if (epConfig == 1) {
            return false;
        }
        if (epConfig >= 2) {
            protection[0] = true;
            if (!copyErrorProtectionSpecificConfig(bits, writer)) {
                return false;
            }
            if (epConfig == 3) {
                copy(bits, writer, 1);
            }
        }
        return !bits.overrun();
    }

    /**
     * Copies an {@code ErrorProtectionSpecificConfig} (ISO/IEC 14496-3 subclause 1.8.2.1): the pre-defined sets of
     * classes with their length, rate and CRC fields, their output order and the header protection.
     *
     * @param bits the reader
     * @param writer the configuration being written
     * @return {@code false} for a reserved value or a truncated configuration
     */
    private static boolean copyErrorProtectionSpecificConfig(MsbBitReader bits, MsbBitWriter writer) {
        var sets = copy(bits, writer, 8);
        var interleaveType = copy(bits, writer, 2);
        copy(bits, writer, 3);
        var concatenated = copy(bits, writer, 3);
        if (sets == 0 || interleaveType == 3 || concatenated == 0) {
            return false;
        }
        for (var set = 0; set < sets && !bits.overrun(); set++) {
            var classes = copy(bits, writer, 6);
            for (var c = 0; c < classes && !bits.overrun(); c++) {
                var lengthEscape = copy(bits, writer, 1);
                var rateEscape = copy(bits, writer, 1);
                var crcEscape = copy(bits, writer, 1);
                if (concatenated != 1) {
                    copy(bits, writer, 1);
                }
                var fecType = copy(bits, writer, 2);
                if (fecType == 3) {
                    return false;
                }
                if (fecType == 0) {
                    copy(bits, writer, 1);
                }
                if (interleaveType == 2) {
                    copy(bits, writer, 2);
                }
                copy(bits, writer, 1);
                copy(bits, writer, lengthEscape == 1 ? 4 : 16);
                if (rateEscape == 0) {
                    copy(bits, writer, fecType != 0 ? 7 : 5);
                }
                if (crcEscape == 0) {
                    copy(bits, writer, 5);
                }
            }
            if (copy(bits, writer, 1) == 1) {
                for (var c = 0; c < classes; c++) {
                    copy(bits, writer, 6);
                }
            }
        }
        if (copy(bits, writer, 1) == 1) {
            copy(bits, writer, 10);
        }
        return !bits.overrun();
    }

    /**
     * Copies a field.
     *
     * @param bits the reader
     * @param writer the configuration being written
     * @param count the width of the field
     * @return the field
     */
    private static int copy(MsbBitReader bits, MsbBitWriter writer, int count) {
        var value = bits.read(count);
        writer.write(value, count);
        return value;
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
