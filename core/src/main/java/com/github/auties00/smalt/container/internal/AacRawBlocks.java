package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Finds where the raw data blocks of an unframed AAC stream end by walking their syntax (ISO/IEC
 * 14496-3 subclause 4.4.2, {@code raw_data_block}) without decoding them, and reads the ADIF header
 * that may open such a stream (subclause 1.A.2).
 *
 * <p>A raw data block carries no length: it ends with the byte that holds its {@code ID_END} element,
 * which only a parse of every element, the Huffman-coded scalefactors and spectral data included, can
 * find. The general audio object types of the element syntax are walked: AAC Main (with prediction
 * data), Low Complexity, Scalable Sample Rate (with gain control data) and Long Term Prediction, each
 * also as the core of HE-AAC and HE-AAC v2, whose extension data travels in fill elements. Every
 * element type is walked: single channel, channel pair, coupling channel, LFE, data stream, program
 * configuration and fill elements.
 *
 * <p>Instances are not thread-safe.
 */
public final class AacRawBlocks {
    /**
     * The audio object type of AAC Main.
     */
    public static final int AOT_MAIN = 1;

    /**
     * The audio object type of AAC Low Complexity.
     */
    public static final int AOT_LC = 2;

    /**
     * The audio object type of AAC Scalable Sample Rate.
     */
    public static final int AOT_SSR = 3;

    /**
     * The audio object type of AAC Long Term Prediction.
     */
    public static final int AOT_LTP = 4;

    private static final int ID_SCE = 0;
    private static final int ID_CPE = 1;
    private static final int ID_CCE = 2;
    private static final int ID_LFE = 3;
    private static final int ID_DSE = 4;
    private static final int ID_PCE = 5;
    private static final int ID_FIL = 6;

    private static final int ONLY_LONG_SEQUENCE = 0;
    private static final int LONG_START_SEQUENCE = 1;
    private static final int EIGHT_SHORT_SEQUENCE = 2;

    private static final int ZERO_HCB = 0;
    private static final int RESERVED_HCB = 12;
    private static final int NOISE_HCB = 13;

    /**
     * The largest number of scalefactor bands of a window.
     */
    private static final int MAX_BANDS = 64;

    /**
     * The highest band Long Term Prediction flags in a long window.
     */
    private static final int MAX_LTP_LONG_SFB = 40;

    /**
     * The highest band AAC Main prediction flags, per sampling frequency index (the {@code PRED_SFB_MAX} of the
     * prediction tool, ISO/IEC 13818-7).
     */
    private static final int[] PRED_SFB_MAX = {33, 33, 38, 40, 40, 40, 41, 41, 37, 37, 37, 34, 34};

    /**
     * The number of bits the first level of a decoding table is indexed by.
     */
    private static final int ROOT_BITS = 8;

    /**
     * The decoding table of the scalefactor codebook; each leaf holds the codeword length.
     */
    private static final int[] SCALEFACTOR_TABLE;

    /**
     * The decoding tables of the spectrum codebooks, indexed by codebook number. Each leaf holds the number of bits
     * the codeword and its sign bits take in its low five bits, and the escape flags of its two values in bits 5
     * and 6.
     */
    private static final int[][] SPECTRUM_TABLES = new int[12][];

    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * Signals a block that runs past the bytes available; preallocated, without a stack trace.
     */
    private static final Truncated TRUNCATED = new Truncated();

    static {
        var lengths = AacHuffmanTables.SCALEFACTOR_LENGTHS;
        var info = new int[lengths.length];
        for (var symbol = 0; symbol < lengths.length; symbol++) {
            info[symbol] = lengths[symbol];
        }
        SCALEFACTOR_TABLE = decodingTable(AacHuffmanTables.SCALEFACTOR_CODES, lengths, info);
        int[] largest = {0, 1, 1, 2, 2, 4, 4, 7, 7, 12, 12, 16};
        for (var codebook = 1; codebook <= 11; codebook++) {
            var codes = AacHuffmanTables.SPECTRUM_CODES[codebook];
            var bits = AacHuffmanTables.SPECTRUM_LENGTHS[codebook];
            var unsigned = codebook >= 3 && codebook != 5 && codebook != 6;
            var modulus = unsigned ? largest[codebook] + 1 : 2 * largest[codebook] + 1;
            var offset = unsigned ? 0 : largest[codebook];
            var entries = new int[codes.length];
            for (var symbol = 0; symbol < codes.length; symbol++) {
                int[] values;
                if (codebook <= 4) {
                    values = new int[]{symbol / 27 - offset, symbol / 9 % 3 - offset, symbol / 3 % 3 - offset,
                            symbol % 3 - offset};
                } else {
                    values = new int[]{symbol / modulus - offset, symbol % modulus - offset};
                }
                var signs = 0;
                if (unsigned) {
                    for (var value : values) {
                        signs += value != 0 ? 1 : 0;
                    }
                }
                var escapes = 0;
                if (codebook == 11) {
                    escapes = (values[0] == 16 ? 0x20 : 0) | (values[1] == 16 ? 0x40 : 0);
                }
                entries[symbol] = escapes | (bits[symbol] + signs);
            }
            SPECTRUM_TABLES[codebook] = decodingTable(codes, bits, entries);
        }
    }

    /**
     * The object type of the core coder.
     */
    private final int objectType;

    /**
     * The scalefactor band offsets of long windows, the end offset included.
     */
    private final int[] longOffsets;

    /**
     * The scalefactor band offsets of short windows, the end offset included.
     */
    private final int[] shortOffsets;

    /**
     * The highest band AAC Main prediction flags.
     */
    private final int predictionBands;

    /**
     * The codebook of every band of the channel stream being walked, by group and band.
     */
    private final int[] bandTypes = new int[8 * MAX_BANDS];

    /**
     * The number of windows of every group of the channel stream being walked.
     */
    private final int[] groupLengths = new int[8];

    /**
     * The bytes being walked.
     */
    private byte[] data;

    /**
     * The index after the last byte available.
     */
    private int limit;

    /**
     * The position of the next bit, as a bit index into {@link #data}.
     */
    private long bit;

    private int windowSequence;
    private int windowCount;
    private int groupCount;
    private int maxBands;

    /**
     * Constructs a walker for a stream.
     *
     * @param objectType the audio object type of the core coder, from {@link #AOT_MAIN} to {@link #AOT_LTP}
     * @param samplingIndex the sampling frequency index of the core rate, from 0 to 12; an explicit rate is
     * mapped by {@link #samplingIndexFor(int)}
     * @param frameLength the number of samples of a frame, 1024 or 960
     * @throws MediaException.Unsupported if the object type has no {@code raw_data_block} element syntax
     * @throws MediaException.Malformed if the sampling frequency index or frame length is invalid
     */
    public AacRawBlocks(int objectType, int samplingIndex, int frameLength) {
        if (objectType < AOT_MAIN || objectType > AOT_LTP) {
            throw new MediaException.Unsupported("aac object type " + objectType,
                    "raw data blocks of AAC object type " + objectType + " are not supported");
        }
        if (samplingIndex < 0 || samplingIndex > 12 || frameLength != 1024 && frameLength != 960) {
            throw new MediaException.Malformed("invalid AAC sampling index " + samplingIndex + " or frame length "
                    + frameLength);
        }
        this.objectType = objectType;
        this.longOffsets = truncate(LONG_OFFSETS[samplingIndex], frameLength);
        this.shortOffsets = truncate(SHORT_OFFSETS[samplingIndex], frameLength / 8);
        this.predictionBands = PRED_SFB_MAX[samplingIndex];
    }

    /**
     * {@return the scalefactor band offsets of long windows, the end offset included; the array must not be modified}
     */
    int[] longOffsets() {
        return longOffsets;
    }

    /**
     * {@return the scalefactor band offsets of short windows, the end offset included; the array must not be modified}
     */
    int[] shortOffsets() {
        return shortOffsets;
    }

    /**
     * {@return the highest band AAC Main prediction flags}
     */
    int predictionBands() {
        return predictionBands;
    }

    /**
     * Returns whether raw data blocks of an object type can be walked.
     *
     * @param objectType the audio object type of the core coder
     * @return {@code true} for AAC Main, Low Complexity, Scalable Sample Rate and Long Term Prediction
     */
    public static boolean supports(int objectType) {
        return objectType >= AOT_MAIN && objectType <= AOT_LTP;
    }

    /**
     * Returns the sampling frequency index whose tables serve a rate, mapping a rate no index names to the nearest
     * standard one by the ranges of ISO/IEC 14496-3 subclause 4.5.4.
     *
     * @param rate the rate in hertz
     * @return the index, from 0 to 11
     */
    public static int samplingIndexFor(int rate) {
        int[] lowest = {92017, 75132, 55426, 46009, 37566, 27713, 23004, 18783, 13856, 11502, 9391};
        for (var index = 0; index < lowest.length; index++) {
            if (rate >= lowest[index]) {
                return index;
            }
        }
        return 11;
    }

    /**
     * Returns the length of the raw data block that starts at an index.
     *
     * <p>The block starts byte-aligned and ends, byte-aligned, after its {@code ID_END} element.
     *
     * @param data the bytes
     * @param offset the index of the block's first byte
     * @param length the number of bytes available from {@code offset}
     * @return the length in bytes, or {@code -1} when the block does not end within {@code length} bytes
     * @throws MediaException.Malformed if the bytes are not a valid raw data block
     */
    public int measure(byte[] data, int offset, int length) {
        this.data = data;
        this.limit = offset + length;
        this.bit = (long) offset << 3;
        var end = (long) limit << 3;
        try {
            while (true) {
                var id = read(3);
                switch (id) {
                    case ID_SCE, ID_LFE -> {
                        skip(4);
                        channelStream(false);
                    }
                    case ID_CPE -> channelPair();
                    case ID_CCE -> couplingChannel();
                    case ID_DSE -> dataStream();
                    case ID_PCE -> programConfig();
                    case ID_FIL -> fill();
                    default -> {
                        if (bit > end) {
                            return -1;
                        }
                        return (int) ((bit - ((long) offset << 3) + 7) >>> 3);
                    }
                }
                if (bit > end) {
                    return -1;
                }
            }
        } catch (Truncated exception) {
            return -1;
        } finally {
            this.data = null;
        }
    }

    /**
     * Describes an ADIF header.
     *
     * @param length the length of the header in bytes, up to the first raw data block
     * @param objectType the audio object type, the profile of the first program configuration plus one
     * @param samplingIndex the sampling frequency index of the first program configuration
     * @param bitrate the declared bitrate in bits per second, or 0 when unknown
     * @param channels the number of channels of the first program configuration
     * @param audioSpecificConfig an {@code AudioSpecificConfig} of channel configuration 0 that carries the first
     * program configuration element
     */
    public record Adif(int length, int objectType, int samplingIndex, int bitrate, int channels,
                       byte[] audioSpecificConfig) {
    }

    /**
     * Returns whether bytes start with the ADIF identifier {@code "ADIF"}.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes available
     * @return {@code true} when an ADIF header starts there
     */
    public static boolean isAdif(byte[] data, int offset, int length) {
        return length >= 4 && data[offset] == 'A' && data[offset + 1] == 'D' && data[offset + 2] == 'I'
                && data[offset + 3] == 'F';
    }

    /**
     * Parses an ADIF header.
     *
     * @param data the bytes
     * @param offset the index of the header's first byte, the {@code "ADIF"} identifier
     * @param length the number of bytes available
     * @return the header
     * @throws MediaException.Malformed if the bytes are not a complete ADIF header
     */
    public static Adif readAdif(byte[] data, int offset, int length) {
        if (!isAdif(data, offset, length)) {
            throw new MediaException.Malformed("not an ADIF header");
        }
        var reader = new MsbBitReader(data, offset, length);
        reader.skip(32);
        if (reader.readFlag()) {
            reader.skip(72);
        }
        reader.skip(2);
        var variableRate = reader.readFlag();
        var bitrate = reader.read(23);
        var programs = reader.read(4) + 1;
        var writer = new MsbBitWriter(64);
        var objectType = -1;
        var samplingIndex = -1;
        var channels = 0;
        for (var program = 0; program < programs; program++) {
            if (!variableRate) {
                reader.skip(20);
            }
            if (program == 0) {
                var tag = reader.read(4);
                objectType = reader.read(2) + 1;
                samplingIndex = reader.read(4);
                writer.write(objectType, 5);
                writer.write(samplingIndex, 4);
                writer.write(0, 4);
                writer.write(0, 3);
                writer.write(tag, 4);
                writer.write(objectType - 1, 2);
                writer.write(samplingIndex, 4);
                channels = copyProgramConfig(reader, writer);
                writer.alignWithZeros();
            } else {
                reader.skip(10);
                copyProgramConfig(reader, null);
            }
        }
        reader.alignToByte();
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated ADIF header");
        }
        if (samplingIndex > 12) {
            throw new MediaException.Malformed("ADIF sampling frequency index " + samplingIndex + " is reserved");
        }
        return new Adif((int) (reader.bitPosition() >>> 3), objectType, samplingIndex, bitrate, channels,
                writer.toByteArray());
    }

    /**
     * Copies the fields of a program configuration element that follow its sampling frequency index, re-emitting
     * the byte alignment before its comment relative to the writer's start.
     *
     * @param reader the reader, positioned after the element's sampling frequency index; its byte alignment is
     * relative to the reader's start
     * @param writer the writer, positioned after the element's sampling frequency index, or {@code null} to skip
     * the element
     * @return the number of channels the element declares: two per channel pair element, one per single channel and
     * LFE element
     * @throws MediaException.Malformed if the element is truncated
     */
    public static int copyProgramConfig(MsbBitReader reader, MsbBitWriter writer) {
        return copyProgramConfig(reader, writer, -1);
    }

    /**
     * Copies the fields of a program configuration element that follow its sampling frequency index, aligning the
     * reader before the comment relative to a bit position of the reader rather than to its start; an element of
     * an {@code AudioSpecificConfig} aligns relative to the start of that configuration (ISO/IEC 14496-3 clause
     * 4.4.1.1).
     *
     * @param reader the reader, positioned after the element's sampling frequency index
     * @param writer the writer, positioned after the element's sampling frequency index, or {@code null} to skip
     * the element; its byte alignment is relative to the writer's start
     * @param alignBase the bit position of the reader the reader's byte alignment is relative to, or {@code -1}
     * for the reader's start
     * @return the number of channels the element declares: two per channel pair element, one per single channel and
     * LFE element
     * @throws MediaException.Malformed if the element is truncated
     */
    public static int copyProgramConfig(MsbBitReader reader, MsbBitWriter writer, long alignBase) {
        var front = copy(reader, writer, 4);
        var side = copy(reader, writer, 4);
        var back = copy(reader, writer, 4);
        var lfe = copy(reader, writer, 2);
        var assoc = copy(reader, writer, 3);
        var coupling = copy(reader, writer, 4);
        for (var mixdown = 0; mixdown < 2; mixdown++) {
            if (copy(reader, writer, 1) != 0) {
                copy(reader, writer, 4);
            }
        }
        if (copy(reader, writer, 1) != 0) {
            copy(reader, writer, 3);
        }
        var channels = lfe;
        for (var element = 0; element < front + side + back; element++) {
            channels += (copy(reader, writer, 5) >>> 4) + 1;
        }
        for (var element = 0; element < lfe + assoc; element++) {
            copy(reader, writer, 4);
        }
        for (var element = 0; element < coupling; element++) {
            copy(reader, writer, 5);
        }
        if (alignBase < 0) {
            reader.alignToByte();
        } else {
            reader.skip((8 - ((reader.bitPosition() - alignBase) & 7)) & 7);
        }
        if (writer != null) {
            writer.alignWithZeros();
        }
        var comment = copy(reader, writer, 8);
        for (var index = 0; index < comment; index++) {
            copy(reader, writer, 8);
        }
        reader.checkNotOverrun();
        return channels;
    }

    /**
     * Copies one field.
     *
     * @param reader the source
     * @param writer the destination, or {@code null}
     * @param bits the field width
     * @return the field
     */
    private static int copy(MsbBitReader reader, MsbBitWriter writer, int bits) {
        var value = reader.read(bits);
        if (writer != null) {
            writer.write(value, bits);
        }
        return value;
    }

    /**
     * Walks a channel pair element.
     */
    private void channelPair() {
        skip(4);
        var common = read(1) != 0;
        if (common) {
            info(true);
            var mask = read(2);
            if (mask == 3) {
                throw new MediaException.Malformed("reserved AAC M/S mask mode");
            }
            if (mask == 1) {
                skip((long) groupCount * maxBands);
            }
        }
        channelStream(common);
        channelStream(common);
    }

    /**
     * Walks a coupling channel element.
     */
    private void couplingChannel() {
        skip(4);
        var independent = read(1) != 0;
        var targets = read(3) + 1;
        var lists = 0;
        for (var target = 0; target < targets; target++) {
            lists++;
            var pair = read(1) != 0;
            skip(4);
            if (pair && read(2) == 3) {
                lists++;
            }
        }
        skip(4);
        channelStream(false);
        for (var list = 1; list < lists; list++) {
            if (independent || read(1) != 0) {
                scalefactor();
                continue;
            }
            for (var group = 0; group < groupCount; group++) {
                for (var band = 0; band < maxBands; band++) {
                    if (bandTypes[group * MAX_BANDS + band] != ZERO_HCB) {
                        scalefactor();
                    }
                }
            }
        }
    }

    /**
     * Walks a data stream element.
     */
    private void dataStream() {
        skip(4);
        var align = read(1) != 0;
        var count = read(8);
        if (count == 255) {
            count += read(8);
        }
        if (align) {
            bit = (bit + 7) & ~7L;
        }
        skip(8L * count);
    }

    /**
     * Walks a fill element.
     */
    private void fill() {
        var count = read(4);
        if (count == 15) {
            count += read(8) - 1;
        }
        skip(8L * count);
    }

    /**
     * Walks a program configuration element.
     */
    private void programConfig() {
        skip(10);
        var front = read(4);
        var side = read(4);
        var back = read(4);
        var lfe = read(2);
        var assoc = read(3);
        var coupling = read(4);
        for (var mixdown = 0; mixdown < 2; mixdown++) {
            if (read(1) != 0) {
                skip(4);
            }
        }
        if (read(1) != 0) {
            skip(3);
        }
        skip(5L * (front + side + back) + 4L * (lfe + assoc) + 5L * coupling);
        bit = (bit + 7) & ~7L;
        skip(8L * read(8));
    }

    /**
     * Walks an {@code individual_channel_stream}.
     *
     * @param commonWindow whether the {@code ics_info} was read for a channel pair
     */
    private void channelStream(boolean commonWindow) {
        skip(8);
        if (!commonWindow) {
            info(false);
        }
        sections();
        scalefactors();
        if (read(1) != 0) {
            if (windowCount == 8) {
                throw new MediaException.Malformed("AAC pulse data in short windows");
            }
            var pulses = read(2) + 1;
            skip(6 + 9L * pulses);
        }
        if (read(1) != 0) {
            tns();
        }
        if (read(1) != 0) {
            gainControl();
        }
        spectralData();
    }

    /**
     * Walks an {@code ics_info}.
     *
     * @param commonWindow whether it is shared by the channels of a pair
     */
    private void info(boolean commonWindow) {
        if (read(1) != 0) {
            throw new MediaException.Malformed("AAC ics_info reserved bit is set");
        }
        windowSequence = read(2);
        skip(1);
        if (windowSequence == EIGHT_SHORT_SEQUENCE) {
            maxBands = read(4);
            var grouping = read(7);
            windowCount = 8;
            groupCount = 1;
            groupLengths[0] = 1;
            for (var window = 6; window >= 0; window--) {
                if ((grouping >>> window & 1) != 0) {
                    groupLengths[groupCount - 1]++;
                } else {
                    groupLengths[groupCount++] = 1;
                }
            }
            if (maxBands > shortOffsets.length - 1) {
                throw new MediaException.Malformed("AAC max_sfb " + maxBands + " exceeds the short bands");
            }
            return;
        }
        maxBands = read(6);
        windowCount = 1;
        groupCount = 1;
        groupLengths[0] = 1;
        if (maxBands > longOffsets.length - 1) {
            throw new MediaException.Malformed("AAC max_sfb " + maxBands + " exceeds the long bands");
        }
        if (read(1) == 0) {
            return;
        }
        switch (objectType) {
            case AOT_MAIN -> {
                if (read(1) != 0) {
                    skip(5);
                }
                skip(Math.min(maxBands, predictionBands));
            }
            case AOT_LTP -> {
                if (read(1) != 0) {
                    skip(14 + Math.min(maxBands, MAX_LTP_LONG_SFB));
                }
                if (commonWindow && read(1) != 0) {
                    skip(14 + Math.min(maxBands, MAX_LTP_LONG_SFB));
                }
            }
            default -> throw new MediaException.Malformed("AAC prediction data in a stream of object type "
                    + objectType);
        }
    }

    /**
     * Walks the section data, recording the codebook of every band.
     */
    private void sections() {
        var lengthBits = windowCount == 8 ? 3 : 5;
        var escape = (1 << lengthBits) - 1;
        var end = (long) limit << 3;
        for (var group = 0; group < groupCount; group++) {
            var band = 0;
            var base = group * MAX_BANDS;
            while (band < maxBands) {
                var codebook = read(4);
                var length = 0;
                int increment;
                while ((increment = read(lengthBits)) == escape) {
                    length += escape;
                    if (bit > end) {
                        throw TRUNCATED;
                    }
                }
                length += increment;
                if (bit > end) {
                    throw TRUNCATED;
                }
                if (codebook == RESERVED_HCB) {
                    throw new MediaException.Malformed("AAC section uses the reserved codebook 12");
                }
                if (length == 0 || band + length > maxBands) {
                    throw new MediaException.Malformed("AAC section of " + length + " bands at band " + band
                            + " of " + maxBands);
                }
                Arrays.fill(bandTypes, base + band, base + band + length, codebook);
                band += length;
            }
        }
    }

    /**
     * Walks the scalefactors, intensity positions and noise energies.
     */
    private void scalefactors() {
        var firstNoise = true;
        for (var group = 0; group < groupCount; group++) {
            var base = group * MAX_BANDS;
            for (var band = 0; band < maxBands; band++) {
                var codebook = bandTypes[base + band];
                if (codebook == ZERO_HCB) {
                    continue;
                }
                if (codebook == NOISE_HCB && firstNoise) {
                    firstNoise = false;
                    skip(9);
                } else {
                    scalefactor();
                }
            }
        }
    }

    /**
     * Walks the TNS data.
     */
    private void tns() {
        var shortWindow = windowCount == 8;
        for (var window = 0; window < windowCount; window++) {
            var filters = read(shortWindow ? 1 : 2);
            if (filters == 0) {
                continue;
            }
            var resolution = read(1) + 3;
            for (var filter = 0; filter < filters; filter++) {
                skip(shortWindow ? 4 : 6);
                var order = read(shortWindow ? 3 : 5);
                if (order != 0) {
                    skip(1);
                    var bits = resolution - read(1);
                    skip((long) order * bits);
                }
            }
        }
    }

    /**
     * Walks the gain control data of the Scalable Sample Rate object type.
     */
    private void gainControl() {
        var bands = read(2);
        int windows;
        int firstLocation;
        int otherLocation;
        switch (windowSequence) {
            case ONLY_LONG_SEQUENCE -> {
                windows = 1;
                firstLocation = 5;
                otherLocation = 5;
            }
            case LONG_START_SEQUENCE -> {
                windows = 2;
                firstLocation = 4;
                otherLocation = 2;
            }
            case EIGHT_SHORT_SEQUENCE -> {
                windows = 8;
                firstLocation = 2;
                otherLocation = 2;
            }
            default -> {
                windows = 2;
                firstLocation = 4;
                otherLocation = 5;
            }
        }
        for (var band = 0; band < bands; band++) {
            for (var window = 0; window < windows; window++) {
                var adjustments = read(3);
                skip((long) adjustments * (4 + (window == 0 ? firstLocation : otherLocation)));
            }
        }
    }

    /**
     * Walks the Huffman-coded spectral data.
     */
    private void spectralData() {
        var offsets = windowCount == 8 ? shortOffsets : longOffsets;
        var end = (long) limit << 3;
        for (var group = 0; group < groupCount; group++) {
            var base = group * MAX_BANDS;
            var windows = groupLengths[group];
            for (var band = 0; band < maxBands; band++) {
                var codebook = bandTypes[base + band];
                if (codebook == ZERO_HCB || codebook > 11) {
                    continue;
                }
                var lines = (offsets[band + 1] - offsets[band]) * windows;
                var count = codebook <= 4 ? lines >>> 2 : lines >>> 1;
                var table = SPECTRUM_TABLES[codebook];
                for (var codeword = 0; codeword < count; codeword++) {
                    var entry = decode(table);
                    bit += entry & 31;
                    if ((entry & 0x60) != 0) {
                        if ((entry & 0x20) != 0) {
                            escape();
                        }
                        if ((entry & 0x40) != 0) {
                            escape();
                        }
                    }
                }
                if (bit > end) {
                    throw TRUNCATED;
                }
            }
        }
    }

    /**
     * Skips an escape sequence: a prefix of {@code N} one bits, a zero, and an {@code N + 4}-bit word.
     */
    private void escape() {
        var prefix = 0;
        while (read(1) != 0) {
            if (++prefix > 8) {
                throw new MediaException.Malformed("AAC escape sequence longer than 13 bits");
            }
        }
        skip(prefix + 4);
    }

    /**
     * Skips a scalefactor codeword.
     */
    private void scalefactor() {
        bit += decode(SCALEFACTOR_TABLE) & 31;
    }

    /**
     * Decodes the next codeword of a table without consuming it.
     *
     * @param table the decoding table
     * @return the leaf entry
     * @throws MediaException.Malformed if the bits are not a codeword
     */
    private int decode(int[] table) {
        var word = peek();
        var entry = table[word >>> (32 - ROOT_BITS)];
        if (entry < 0) {
            var subBits = entry & 31;
            entry = table[((entry & 0x7FFFFFFF) >>> 5) + ((word << ROOT_BITS) >>> (32 - subBits))];
        }
        if (entry == 0) {
            throw new MediaException.Malformed("invalid AAC Huffman codeword");
        }
        return entry;
    }

    /**
     * Returns the next 32 bits without consuming them, with zeros past the bytes available.
     *
     * @return the bits, the next one in the sign bit
     */
    private int peek() {
        var index = (int) (bit >>> 3);
        long word;
        if (index + 8 <= limit) {
            word = (long) LONG_BE.get(data, index);
        } else {
            word = 0;
            for (var at = index; at < index + 8; at++) {
                word = word << 8 | (at < limit ? data[at] & 0xFF : 0);
            }
        }
        return (int) ((word << (bit & 7)) >>> 32);
    }

    /**
     * Reads a field.
     *
     * @param bits the width, from 1 to 25
     * @return the field
     */
    private int read(int bits) {
        var value = peek() >>> (32 - bits);
        bit += bits;
        return value;
    }

    /**
     * Skips bits.
     *
     * @param bits the number of bits
     */
    private void skip(long bits) {
        bit += bits;
    }

    /**
     * Builds a two-level decoding table.
     *
     * @param codes the codewords, right-aligned, by symbol
     * @param lengths the codeword lengths, by symbol
     * @param leaves the leaf entry of every symbol, nonzero
     * @return the table: {@code 2^ROOT_BITS} first-level entries, each a leaf or, with the sign bit set, the offset
     * of a second-level table shifted left by 5 and its index width
     */
    private static int[] decodingTable(int[] codes, byte[] lengths, int[] leaves) {
        var root = 1 << ROOT_BITS;
        var subBits = new int[root];
        for (var symbol = 0; symbol < codes.length; symbol++) {
            if (lengths[symbol] > ROOT_BITS) {
                var prefix = codes[symbol] >>> (lengths[symbol] - ROOT_BITS);
                subBits[prefix] = Math.max(subBits[prefix], lengths[symbol] - ROOT_BITS);
            }
        }
        var size = root;
        var offsets = new int[root];
        for (var prefix = 0; prefix < root; prefix++) {
            if (subBits[prefix] > 0) {
                offsets[prefix] = size;
                size += 1 << subBits[prefix];
            }
        }
        var table = new int[size];
        for (var prefix = 0; prefix < root; prefix++) {
            if (subBits[prefix] > 0) {
                table[prefix] = 0x80000000 | offsets[prefix] << 5 | subBits[prefix];
            }
        }
        for (var symbol = 0; symbol < codes.length; symbol++) {
            var length = lengths[symbol];
            var code = codes[symbol];
            if (length <= ROOT_BITS) {
                var first = code << (ROOT_BITS - length);
                Arrays.fill(table, first, first + (1 << (ROOT_BITS - length)), leaves[symbol]);
            } else {
                var prefix = code >>> (length - ROOT_BITS);
                var width = subBits[prefix];
                var suffix = code & ((1 << (length - ROOT_BITS)) - 1);
                var first = offsets[prefix] + (suffix << (width - (length - ROOT_BITS)));
                Arrays.fill(table, first, first + (1 << (width - (length - ROOT_BITS))), leaves[symbol]);
            }
        }
        return table;
    }

    /**
     * Keeps every band offset below {@code end} and closes the table with {@code end}; the 960- and 120-line
     * tables of ISO/IEC 14496-3 subclause 4.5.4 coincide with the 1024- and 128-line ones below their length.
     *
     * @param offsets the full-length offsets
     * @param end the transform length
     * @return the offsets
     */
    private static int[] truncate(int[] offsets, int end) {
        if (offsets[offsets.length - 1] == end) {
            return offsets;
        }
        var count = 0;
        while (offsets[count] < end) {
            count++;
        }
        var result = Arrays.copyOf(offsets, count + 1);
        result[count] = end;
        return result;
    }

    private static final int[] LONG_96 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144,
            156, 172, 188, 212, 240, 276, 320, 384, 448, 512, 576, 640, 704, 768, 832, 896, 960, 1024
    };

    private static final int[] LONG_64 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 100, 112, 124, 140, 156,
            172, 192, 216, 240, 268, 304, 344, 384, 424, 464, 504, 544, 584, 624, 664, 704, 744, 784, 824, 864,
            904, 944, 984, 1024
    };

    private static final int[] LONG_48 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176,
            196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768,
            800, 832, 864, 896, 928, 1024
    };

    private static final int[] LONG_32 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176,
            196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768,
            800, 832, 864, 896, 928, 960, 992, 1024
    };

    private static final int[] LONG_24 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124, 136, 148,
            160, 172, 188, 204, 220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552, 600, 652, 704, 768,
            832, 896, 960, 1024
    };

    private static final int[] LONG_16 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100, 112, 124, 136, 148, 160, 172, 184, 196, 212, 228,
            244, 260, 280, 300, 320, 344, 368, 396, 424, 456, 492, 532, 572, 616, 664, 716, 772, 832, 896, 960,
            1024
    };

    private static final int[] LONG_8 = {
            0, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156, 172, 188, 204, 220, 236, 252, 268, 288,
            308, 328, 348, 372, 396, 420, 448, 476, 508, 544, 580, 620, 664, 712, 764, 820, 880, 944, 1024
    };

    private static final int[] SHORT_96 = {0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 92, 128};

    private static final int[] SHORT_48 = {0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96, 112, 128};

    private static final int[] SHORT_24 = {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 64, 76, 92, 108, 128};

    private static final int[] SHORT_16 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 60, 72, 88, 108, 128};

    private static final int[] SHORT_8 = {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 60, 72, 88, 108, 128};

    /**
     * The long-window scalefactor band offsets of 1024-sample frames by sampling frequency index (ISO/IEC 14496-3
     * subclause 4.5.4).
     */
    private static final int[][] LONG_OFFSETS = {
            LONG_96, LONG_96, LONG_64, LONG_48, LONG_48, LONG_32, LONG_24, LONG_24, LONG_16, LONG_16, LONG_16,
            LONG_8, LONG_8
    };

    /**
     * The short-window scalefactor band offsets of 1024-sample frames by sampling frequency index (ISO/IEC 14496-3
     * subclause 4.5.4).
     */
    private static final int[][] SHORT_OFFSETS = {
            SHORT_96, SHORT_96, SHORT_96, SHORT_48, SHORT_48, SHORT_48, SHORT_24, SHORT_24, SHORT_16, SHORT_16,
            SHORT_16, SHORT_8, SHORT_8
    };

    /**
     * Signals a block that runs past the bytes available; one preallocated instance, without a stack trace.
     */
    private static final class Truncated extends RuntimeException {
        /**
         * Constructs the instance.
         */
        Truncated() {
            super("truncated AAC raw data block", null, false, false);
        }
    }
}
