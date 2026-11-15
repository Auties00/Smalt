/* Ports the ER BSAC decoder of the ISO/IEC 14496-3 reference software as distributed with the ISO/IEC 23003-3
   reference software (sam_decode_frame.c, sam_decoder.c, sam_decode_bsac.c, sam_fadecode.c, sam_bitbuffer.c and
   sam_tns.c of src_tf), developed by Y.B. Thomas Kim and S.H. Park (Samsung AIT). ISO/IEC gives users of
   the MPEG-2 AAC/MPEG-4 Audio standards free license to this software module or modifications thereof for use in
   hardware or software products claiming conformance to the MPEG-2 AAC/MPEG-4 Audio standards. Copyright (c) 1999.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes the bit-sliced arithmetic coding of ER BSAC (audio object type 22, ISO/IEC 14496-3 subclause 4.5.2.6)
 * into spectra: the base element of a frame and its extension elements (further channels and the spectral band
 * replication payloads of ISO/IEC 14496-3 subclause 4.4.2.8), with dequantization, M/S and intensity stereo,
 * perceptual noise substitution and TNS.
 *
 * <p>A frame is the concatenation of the access units of every elementary stream of the stream, in stream order.
 * The decoder reproduces the reference decoder, including its handling of a frame decoded to fewer layers than it
 * codes, of the arithmetic decoder states it keeps across layers and of the noise generator state it keeps across
 * frames.
 */
final class BsacDecoder {
    /**
     * The speaker type of a single front center channel ({@code CENTER_FRONT}).
     */
    static final int CENTER_FRONT = 0;

    /**
     * The speaker type of the front left and right channels ({@code LR_FRONT}).
     */
    static final int LR_FRONT = 1;

    /**
     * The speaker type of a single rear surround channel ({@code REAR_SUR}).
     */
    static final int REAR_SURROUND = 2;

    /**
     * The speaker type of the left and right surround channels ({@code LR_SUR}).
     */
    static final int LR_SURROUND = 3;

    /**
     * The speaker type of the low frequency effects channel ({@code LFE}).
     */
    static final int LOW_FREQUENCY = 4;

    /**
     * The speaker type of the left and right outside channels ({@code LR_OUTSIDE}).
     */
    static final int LR_OUTSIDE = 5;

    /**
     * The number of layer slots of the reference decoder ({@code MAX_LAYER}).
     */
    private static final int MAX_LAYER = 100;

    /**
     * The offset of the coded scalefactors ({@code SF_OFFSET}).
     */
    private static final int SF_OFFSET = 100;

    /**
     * The maximum length of the side information of the first coding band ({@code MAX_CBAND0_SI_LEN}).
     */
    private static final int MAX_CBAND0_SI_LEN = 11;

    /**
     * The size of the layer buffer in bytes.
     */
    private static final int LAYER_BUFFER_SIZE = 4096;

    /**
     * The number of entries of the inverse quantization and scale tables.
     */
    private static final int TABLE_SIZE = 128;

    /**
     * The maximum length of the side information of a coding band by its {@code cband_si_type}.
     */
    private static final int[] MAX_CBAND_SI_LENGTH = {
            6, 5, 6, 5, 6, 6, 5, 6, 5, 6, 5, 6, 8, 6, 5, 6, 8, 9, 6, 5, 6, 8, 10, 8, 10, 9, 10, 10, 12, 12, 12, 12
    };

    /**
     * The coding band side information model of a {@code cband_si_type}, after the first coding band.
     */
    private static final int[] CBAND_SI_MODEL = {
            0, 1, 0, 1, 2, 0, 1, 2, 3, 0, 1, 2, 4, 0, 1, 2, 4, 5, 0, 1, 2, 4, 6, 4, 6, 5, 6, 6, 6, 6, 6, 6
    };

    /**
     * The highest model index of the first coding band by {@code cband_si_type}.
     */
    private static final int[] MAX_MODEL0_INDEX = {
            6, 6, 8, 8, 8, 10, 10, 10, 10, 12, 12, 12, 12, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 16, 16, 17, 17,
            18, 19, 20, 21, 22
    };

    /**
     * The highest model index of the other coding bands by {@code cband_si_type}.
     */
    private static final int[] MAX_MODEL_INDEX = {
            4, 6, 4, 6, 8, 4, 6, 8, 10, 4, 6, 8, 12, 4, 6, 8, 12, 14, 4, 6, 8, 12, 15, 12, 16, 14, 17, 18, 19, 20,
            21, 22
    };

    /**
     * The smallest probability of a binary symbol by the number of bits left in the layer, below 14.
     */
    private static final int[] MIN_FREQUENCY = {
            16384, 8192, 4096, 2048, 1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1, 0
    };

    /**
     * The lower bounds of the code range by the number of bits the next symbol reads.
     */
    private static final long[] HALF = {
            0x20000000L, 0x10000000L, 0x08000000L, 0x04000000L, 0x02000000L, 0x01000000L, 0x00800000L,
            0x00400000L, 0x00200000L, 0x00100000L, 0x00080000L, 0x00040000L, 0x00020000L, 0x00010000L,
            0x00008000L, 0x00004000L
    };

    /**
     * The number of channels of each speaker type.
     */
    private static final int[] CHANNELS_OF_TYPE = {1, 2, 1, 2, 1, 2};

    /**
     * The mask of the low bits of a byte by count.
     */
    private static final int[] MASK = {0x0, 0x1, 0x3, 0x7, 0xf, 0x1f, 0x3f, 0x7f, 0xff};

    /**
     * The mean energy of the noise generator ({@code MEAN_NRG}).
     */
    private static final double MEAN_ENERGY = 1.5625e+18;

    /**
     * The frame length, 1024 or 960.
     */
    private final int frameLength;

    /**
     * The core sampling frequency in hertz.
     */
    private final int sampleRate;

    /**
     * The long window scalefactor band offsets.
     */
    private final int[] longOffsets;

    /**
     * The short window scalefactor band offsets.
     */
    private final int[] shortOffsets;

    /**
     * The number of long window scalefactor bands.
     */
    private final int longTop;

    /**
     * The number of short window scalefactor bands.
     */
    private final int shortTop;

    /**
     * The highest band TNS filters in a long and a short window.
     */
    private final int tnsMaxBandsLong;

    /**
     * The highest band TNS filters in a short window.
     */
    private final int tnsMaxBandsShort;

    /**
     * The highest TNS order of a long window.
     */
    private final int tnsMaxOrderLong;

    /**
     * The number of channels of the base element.
     */
    private final int baseChannels;

    /**
     * The decoded layer requested, or -1 for every coded layer.
     */
    private final int targetLayer;

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The average number of bits of each layer per channel ({@code sam_scale_bits_dec}).
     */
    private final int[] scaleBits = new int[MAX_LAYER];

    /**
     * The scale of each scalefactor from 0 to {@link #TABLE_SIZE}.
     */
    private final double[] scales = new double[TABLE_SIZE];

    /**
     * The inverse quantization of each value from 0 to {@link #TABLE_SIZE}.
     */
    private final double[] inverseQuantization = new double[TABLE_SIZE];

    /**
     * The frame being decoded.
     */
    private byte[] input = new byte[0];

    /**
     * The number of valid bytes of {@link #input}.
     */
    private int inputLength;

    /**
     * The read position in {@link #input}, in bits.
     */
    private long inputPosition;

    /**
     * The position of the current element in {@link #input}, in bits.
     */
    private long elementStart;

    /**
     * The layer buffer, into which the layers of an element are rearranged.
     */
    private final byte[] layerBuffer = new byte[LAYER_BUFFER_SIZE];

    /**
     * The byte position in the layer buffer; kept across elements and frames as the reference keeps it.
     */
    private int layerByte = LAYER_BUFFER_SIZE - 1;

    /**
     * The number of bits left in the current byte of the layer buffer.
     */
    private int layerBit;

    /**
     * The current code value of the arithmetic decoder.
     */
    private long value;

    /**
     * The current code range of the arithmetic decoder.
     */
    private long range;

    /**
     * The number of bits the next symbol shifts in.
     */
    private int codewordLength = 30;

    /**
     * The symbol decoded last.
     */
    private int symbol;

    /**
     * The stored code values of the arithmetic decoders of each layer.
     */
    private final long[] storedValues = new long[MAX_LAYER];

    /**
     * The stored code ranges of the arithmetic decoders of each layer.
     */
    private final long[] storedRanges = new long[MAX_LAYER];

    /**
     * The stored codeword lengths of the arithmetic decoders of each layer.
     */
    private final int[] storedLengths = new int[MAX_LAYER];

    /**
     * The state of the noise generator, kept across frames.
     */
    private int noiseState;

    /**
     * The noise generator states of the bands, for correlated noise, kept across frames.
     */
    private final int[] noiseStates = new int[136];

    /**
     * The number of elements of the last frame.
     */
    private int elementCount;

    /**
     * The speaker type of each element.
     */
    private int[] elementTypes = new int[4];

    /**
     * The first channel of each element.
     */
    private int[] elementChannels = new int[4];

    /**
     * The window sequence of each element.
     */
    private int[] windowSequences = new int[4];

    /**
     * The window shape of each element.
     */
    private int[] windowShapes = new int[4];

    /**
     * The spectra of the decoded channels.
     */
    private double[][] spectra = new double[0][];

    /**
     * The number of channels of the last frame.
     */
    private int channelCount;

    /**
     * The number of spectral band replication payloads of the last frame.
     */
    private int sbrCount;

    /**
     * The element of each spectral band replication payload.
     */
    private int[] sbrElements = new int[4];

    /**
     * The bit position of each spectral band replication payload in the frame.
     */
    private long[] sbrPositions = new long[4];

    /**
     * The number of bits of each spectral band replication payload.
     */
    private int[] sbrBits = new int[4];

    /**
     * Whether each spectral band replication payload starts with a CRC.
     */
    private boolean[] sbrCrc = new boolean[4];

    /**
     * The quantized samples of the two channels of an element.
     */
    private final int[][] samples = new int[2][1024];

    /**
     * The quantized samples of the two channels of an element in coding order.
     */
    private final int[][] sampleBuffer = new int[2][1024];

    /**
     * The scalefactors of the two channels of an element.
     */
    private final int[][] scalefactors = new int[2][128];

    /**
     * Whether the sign of each sample is decoded.
     */
    private final int[][] signCoded = new int[2][1024];

    /**
     * The bits of each sample decoded so far.
     */
    private final int[][] codedSampleBits = new int[2][1024];

    /**
     * The bit plane each sample is decoded down to.
     */
    private final int[][] currentPlanes = new int[2][1024];

    /**
     * The signed vectors of four samples decoded so far.
     */
    private final int[][] signVectors = new int[2][256];

    /**
     * The vectors of four samples decoded at the current bit plane.
     */
    private final int[][] planeVectors = new int[2][256];

    /**
     * The model index of each coding band of each channel and window group.
     */
    private final int[][][] modelIndices = new int[2][8][32];

    /**
     * The significance of each coding band of each channel and window group.
     */
    private final int[][][] bandPlanes = new int[2][8][32];

    /**
     * The M/S flags: the first entry tells whether M/S applies, the others each band.
     */
    private final int[] msMask = new int[129];

    /**
     * The intensity stereo flags of each band: 0 off, 1 in phase, 2 out of phase.
     */
    private final int[] intensity = new int[128];

    /**
     * Whether the stereo side information of each band is decoded.
     */
    private final int[] stereoCoded = new int[128];

    /**
     * The noise substitution flags of each band of the two channels.
     */
    private final int[][] noiseFlags = new int[2][128];

    /**
     * The noise substitution modes of each band: 1 when the noise of the two channels is correlated.
     */
    private final int[] noiseModes = new int[128];

    /**
     * The window group lengths of an element.
     */
    private final int[] groupLengths = new int[8];

    /**
     * The scalefactor band offsets of each window group, in coding order.
     */
    private final int[][] groupOffsets = new int[8][52];

    /**
     * The TNS data of the two channels.
     */
    private final Tns[] tns = {new Tns(), new Tns()};

    /**
     * The LPC coefficients of a TNS filter.
     */
    private final double[] lpc = new double[32];

    /**
     * The reflection coefficients of a TNS filter.
     */
    private final double[] reflection = new double[32];

    /**
     * The scratch of the LPC conversion.
     */
    private final double[] lpcScratch = new double[32];

    /**
     * The state of a TNS filter.
     */
    private final double[] tnsState = new double[32];

    private final int[] layerSfb = new int[MAX_LAYER + 1];
    private final int[] layerReg = new int[MAX_LAYER + 1];
    private final int[] layerMaxFreq = new int[MAX_LAYER + 1];
    private final int[] layerMaxCband = new int[MAX_LAYER + 1];
    private final int[] layerMaxQband = new int[MAX_LAYER + 1];
    private final int[] layerBitFlush = new int[MAX_LAYER + 1];
    private final int[] layerBufferOffsets = new int[MAX_LAYER + 1];
    private final int[] layerSideInfoLengths = new int[MAX_LAYER + 1];
    private final int[] layerExtraLengths = new int[MAX_LAYER + 1];
    private final int[] layerCodewordOffsets = new int[MAX_LAYER + 1];
    private final int[] scaleBitsOfElement = new int[MAX_LAYER + 1];
    private final int[] lastFreq = new int[8];
    private final int[] initMaxFreq = new int[8];
    private final int[] endFreqOfReg = new int[MAX_LAYER + 1];
    private final int[] endCbandOfReg = new int[8];
    private final int[][] startFreq = new int[2][8];
    private final int[][] startQband = new int[2][8];
    private final int[][] startCband = new int[2][8];
    private final int[][] endFreq = new int[2][8];
    private final int[][] endCband = new int[2][8];
    private final int[][] endQband = new int[2][8];
    private final int[][] sFreq = new int[2][8];
    private final int[][] eFreq = new int[2][8];
    private final int[] groupBase = new int[8];
    private final int[] maxScalefactor = new int[2];
    private final int[] cbandSiType = new int[2];
    private final int[] scfModel0 = new int[2];
    private final int[] scfModel1 = new int[2];
    private final int[] maxSfbSiLength = new int[2];
    private final int[] pnsPcmFlag = new int[2];
    private final int[] maxPnsEnergy = new int[2];
    private final int[] tnsPresent = new int[2];

    /**
     * The TNS data of one channel ({@code sam_TNS_frame_info}).
     */
    private static final class Tns {
        /**
         * The number of filters of each window.
         */
        final int[] filters = new int[8];

        /**
         * The coefficient resolution of each window, 3 or 4 bits.
         */
        final int[] resolution = new int[8];

        /**
         * The start band of each filter, three per window.
         */
        final int[] start = new int[24];

        /**
         * The stop band of each filter.
         */
        final int[] stop = new int[24];

        /**
         * The order of each filter.
         */
        final int[] order = new int[24];

        /**
         * Whether each filter runs downward.
         */
        final boolean[] downward = new boolean[24];

        /**
         * The coefficients of each filter, 32 per filter.
         */
        final int[] coefficients = new int[24 * 32];
    }

    /**
     * Creates a decoder.
     *
     * @param config the stream configuration, of object type 22
     * @param targetLayer the highest layer to decode, or -1 for every layer
     * @param limits the resource limits
     */
    BsacDecoder(AudioSpecificConfig config, int targetLayer, MediaLimits limits) {
        this.frameLength = config.frameLength();
        this.sampleRate = config.sampleRate();
        this.targetLayer = targetLayer;
        this.limits = limits;
        this.baseChannels = config.channelConfiguration() == 1 ? 1 : 2;
        var tableIndex = config.tableIndex();
        this.longOffsets = SamplingTables.longOffsets(tableIndex, frameLength);
        this.shortOffsets = SamplingTables.shortOffsets(tableIndex, frameLength);
        this.longTop = longOffsets.length - 1;
        this.shortTop = shortOffsets.length - 1;
        this.tnsMaxBandsLong = SamplingTables.tnsMaxBands(tableIndex, false);
        this.tnsMaxBandsShort = SamplingTables.tnsMaxBands(tableIndex, true);
        this.tnsMaxOrderLong = sampleRate <= 32000 ? 12 : 20;
        for (var i = 0; i < TABLE_SIZE; i++) {
            scales[i] = StrictMath.pow(2.0, 0.25 * i);
            inverseQuantization[i] = StrictMath.pow(i, 4.0 / 3.0);
        }
        for (var layer = 0; layer < MAX_LAYER; layer++) {
            var layerBitRate = (layer + 16) * 1000;
            var average = (int) ((double) layerBitRate * (double) frameLength / (double) sampleRate);
            scaleBits[layer] = (average / 8) * 8;
        }
    }

    /**
     * Decodes a frame.
     *
     * @param data the array holding the frame
     * @param length the number of bytes of the frame
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.LimitExceeded if the frame holds more channels than the limits allow
     */
    void decode(byte[] data, int length) {
        input = data;
        inputLength = length;
        inputPosition = 0;
        elementCount = 0;
        channelCount = 0;
        sbrCount = 0;
        var available = decodeElement(baseChannels, false, 0);
        if (available <= 0) {
            return;
        }
        var syncWord = searchSync(available);
        available = remaining;
        var extensionType = foundType;
        var bitCount = 8;
        while (available > 8 && syncWord == 0x0f) {
            switch (extensionType) {
                case 0x0F -> {
                    available = decodeElement(0, true, available);
                    bitCount += lastFrameLength;
                    available += layerPosition() - lastFrameLength;
                }
                case 0x00, 0x01 -> {
                    var bits = readSbrPayload(extensionType == 0x01);
                    available -= bits;
                    bitCount += bits;
                }
                case 0x0E, 0x0D -> {
                    available = decodeElement(0, true, available);
                    bitCount += lastFrameLength;
                    if (extensionType == 0x0E) {
                        available += layerPosition() - lastFrameLength;
                    }
                    var bits = readSbrPayload(extensionType == 0x0D);
                    available -= bits;
                    bitCount += bits;
                }
                default -> {
                    var count = (int) readInput(8);
                    available -= 8;
                    if (count == 0xff) {
                        count += (int) readInput(8) - 1;
                        available -= 8;
                    }
                    skipInput(8L * (count - 1));
                    available -= 8 * (count - 1);
                }
            }
            if (available > 8) {
                var rest = bitCount % 8;
                if (rest != 0) {
                    skipInput(rest);
                    available -= rest;
                }
                extensionType = (int) readInput(4);
                bitCount = 4;
                available -= 4;
            }
        }
    }

    /**
     * Returns the number of elements of the last frame.
     *
     * @return the element count
     */
    int elementCount() {
        return elementCount;
    }

    /**
     * Returns the speaker type of an element of the last frame.
     *
     * @param element the element index
     * @return one of the speaker type constants
     */
    int elementType(int element) {
        return elementTypes[element];
    }

    /**
     * Returns the first channel of an element of the last frame.
     *
     * @param element the element index
     * @return the channel index
     */
    int elementChannel(int element) {
        return elementChannels[element];
    }

    /**
     * Returns the window sequence of an element of the last frame.
     *
     * @param element the element index
     * @return one of the window sequence constants of {@link ChannelStream}
     */
    int windowSequence(int element) {
        return windowSequences[element];
    }

    /**
     * Returns the window shape of an element of the last frame.
     *
     * @param element the element index
     * @return 0 for the sine window, 1 for the Kaiser-Bessel derived window
     */
    int windowShape(int element) {
        return windowShapes[element];
    }

    /**
     * Returns the spectrum of a channel of the last frame, with short windows 128 lines apart.
     *
     * @param channel the channel index
     * @return the spectrum; valid until the next frame
     */
    double[] spectrum(int channel) {
        return spectra[channel];
    }

    /**
     * Returns the number of spectral band replication payloads of the last frame.
     *
     * @return the payload count
     */
    int sbrCount() {
        return sbrCount;
    }

    /**
     * Returns the element a spectral band replication payload belongs to.
     *
     * @param payload the payload index
     * @return the element index
     */
    int sbrElement(int payload) {
        return sbrElements[payload];
    }

    /**
     * Returns the bit position of a spectral band replication payload in the frame.
     *
     * @param payload the payload index
     * @return the position of its first bit
     */
    long sbrPosition(int payload) {
        return sbrPositions[payload];
    }

    /**
     * Returns the number of bits of a spectral band replication payload.
     *
     * @param payload the payload index
     * @return the bit count
     */
    int sbrBits(int payload) {
        return sbrBits[payload];
    }

    /**
     * Returns whether a spectral band replication payload starts with a CRC.
     *
     * @param payload the payload index
     * @return {@code true} for an {@code EXT_SBR_DATA_CRC} payload
     */
    boolean sbrCrc(int payload) {
        return sbrCrc[payload];
    }

    /**
     * The bits left after the last {@link #searchSync}.
     */
    private int remaining;

    /**
     * The extension type the last {@link #searchSync} found.
     */
    private int foundType;

    /**
     * The frame length of the last decoded element, in bits.
     */
    private int lastFrameLength;

    /**
     * Searches the zero code and extension sync byte that follow the base element ({@code search_sync}).
     *
     * @param available the bits left in the frame
     * @return the high nibble of the sync byte, 0x0f when an extension follows
     */
    private int searchSync(int available) {
        var position = layerPosition();
        long zeroCode;
        if (position >= 32) {
            inputPosition = position - 32;
            zeroCode = readInput(32);
        } else {
            inputPosition = 0;
            zeroCode = readInput(position);
        }
        var used = 0;
        var syncWord = 0;
        foundType = 0;
        while (used < available) {
            syncWord = (int) readInput(8);
            position += 8;
            used += 8;
            if (zeroCode == 0 && syncWord >= 0xf0) {
                foundType = syncWord & 0x0f;
                syncWord = (syncWord & 0xf0) >> 4;
                inputPosition = position;
                break;
            }
            zeroCode = ((zeroCode << 8) + syncWord) & 0xFFFFFFFFL;
        }
        remaining = available - used;
        return syncWord;
    }

    /**
     * Reads a spectral band replication payload of an extension ({@code extended_bsac_sbr_data}) and records it for
     * the last element.
     *
     * <p>The payload spans {@code 8 * cnt} bits from its count; the spectral band replication data starts right after
     * the count (and its escape), as ISO/IEC 14496-3 Table 4.46 specifies.
     *
     * @implNote This implementation does not skip the 4 bits after the count that the reference decoder
     * ({@code getExtensionPayload_BSAC}) drops, which would misalign the {@code bs_header_flag} that the conformance
     * streams send there, and keeps every payload of a frame where the reference keeps six.
     *
     * @param crc whether the payload starts with a CRC
     * @return the number of bits read, the byte alignment included
     */
    private int readSbrPayload(boolean crc) {
        var read = 4;
        var count = (int) readInput(4);
        var escape = 0;
        if (count == 15) {
            escape = (int) readInput(8);
            read += 8;
            count = escape + 14;
        }
        if (count > 0) {
            var bytes = escape != 0 ? count - 2 : count - 1;
            if (elementCount > 0 && bytes >= 0) {
                if (sbrCount == sbrElements.length) {
                    var grown = sbrCount * 2;
                    sbrElements = Arrays.copyOf(sbrElements, grown);
                    sbrPositions = Arrays.copyOf(sbrPositions, grown);
                    sbrBits = Arrays.copyOf(sbrBits, grown);
                    sbrCrc = Arrays.copyOf(sbrCrc, grown);
                }
                sbrElements[sbrCount] = elementCount - 1;
                sbrPositions[sbrCount] = inputPosition;
                sbrBits[sbrCount] = 8 * bytes + 4;
                sbrCrc[sbrCount] = crc;
                sbrCount++;
            }
            skipInput(8L * Math.max(0, bytes));
            read += 8 * Math.max(0, bytes);
        }
        if (read % 8 != 0) {
            skipInput(8 - read % 8);
            read += 8 - read % 8;
        }
        return read;
    }

    /**
     * Decodes one element ({@code sam_decode_element}).
     *
     * @param channels the number of channels of the base element, unused for an extension element
     * @param extension whether the element is an extension element, which codes its speaker type
     * @param available the bits left in the frame, for an extension element
     * @return the bits left after the element, as the reference counts them
     */
    private int decodeElement(int channels, boolean extension, int available) {
        elementStart = inputPosition;
        Arrays.fill(msMask, 0);
        Arrays.fill(intensity, 0);
        Arrays.fill(noiseFlags[0], 0);
        Arrays.fill(noiseFlags[1], 0);
        Arrays.fill(noiseModes, 0);
        var stereoMode = 0;
        var intensityPresent = false;
        var frameBits = (int) readInput(11) * 8;
        lastFrameLength = frameBits;
        if (!extension) {
            available = frameBits;
        }
        if (frameBits == 0) {
            throw new MediaException.Malformed("BSAC element of zero length");
        }
        var type = channels == 1 ? CENTER_FRONT : LR_FRONT;
        if (extension) {
            type = (int) readInput(4);
            if (type >= CHANNELS_OF_TYPE.length) {
                throw new MediaException.Malformed("reserved BSAC channel configuration index " + type);
            }
            channels = CHANNELS_OF_TYPE[type];
        }
        readInput(4);
        var sbaMode = (int) readInput(1);
        var topLayer = (int) readInput(6);
        var baseSnf = (int) readInput(2) + 1;
        for (var ch = 0; ch < channels; ch++) {
            maxScalefactor[ch] = (int) readInput(8);
        }
        var baseBand = (int) readInput(5);
        for (var ch = 0; ch < channels; ch++) {
            cbandSiType[ch] = (int) readInput(5);
            scfModel0[ch] = (int) readInput(3);
            scfModel1[ch] = (int) readInput(3);
            maxSfbSiLength[ch] = (int) readInput(4);
        }
        readInput(1);
        var windowSequence = (int) readInput(2);
        var windowShape = (int) readInput(1);
        int maxSfb;
        var groupInfo = 0;
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            maxSfb = (int) readInput(4);
            groupInfo = (int) readInput(7);
        } else {
            maxSfb = (int) readInput(6);
        }
        var groups = 1;
        groupLengths[0] = 1;
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var b = 0; b < 7; b++) {
                if ((groupInfo & (0x40 >> b)) == 0) {
                    groupLengths[groups] = 1;
                    groups++;
                } else {
                    groupLengths[groups - 1]++;
                }
            }
        }
        if (maxSfb > (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? shortTop : longTop)) {
            throw new MediaException.Malformed("BSAC element declares " + maxSfb + " scalefactor bands");
        }
        var pnsPresent = readInput(1) != 0;
        var pnsStart = 63;
        if (pnsPresent) {
            pnsStart = (int) readInput(6);
        }
        if (channels == 2) {
            stereoMode = (int) readInput(2);
            if (stereoMode == 1) {
                msMask[0] = 1;
            } else if (stereoMode == 2) {
                msMask[0] = 2;
                Arrays.fill(msMask, 1, msMask.length, 1);
            } else if (stereoMode == 3) {
                msMask[0] = 1;
                intensityPresent = true;
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            tnsPresent[ch] = (int) readInput(1);
            if (tnsPresent[ch] != 0) {
                readTns(windowSequence, tns[ch]);
            }
            readInput(1);
        }
        var usedBits = (int) (inputPosition - elementStart);
        var target = targetLayer < 0 ? topLayer : targetLayer;
        decodeData(target, frameBits, sbaMode, topLayer, baseSnf, baseBand, maxSfb, windowSequence, groups,
                stereoMode, pnsPresent, pnsStart, usedBits, channels);
        var first = addElement(type, channels, windowSequence, windowShape);
        for (var ch = 0; ch < channels; ch++) {
            var spectrum = spectra[first + ch];
            dequantize(windowSequence, scalefactors[ch], groups, samples[ch], maxSfb, spectrum, ch);
        }
        if (msMask[0] != 0) {
            applyMidSide(windowSequence, groups, spectra[first], spectra[first + 1], maxSfb);
        }
        if (pnsPresent) {
            applyNoise(maxSfb, windowSequence, groups, first, channels);
        }
        if (channels == 2 && intensityPresent) {
            applyIntensity(windowSequence, groups, spectra[first], spectra[first + 1], maxSfb);
        }
        for (var ch = 0; ch < channels; ch++) {
            if (tnsPresent[ch] != 0) {
                applyTns(windowSequence, maxSfb, spectra[first + ch], tns[ch]);
            }
        }
        return available - layerPosition();
    }

    /**
     * Records an element of the frame and provides its spectra.
     *
     * @param type the speaker type
     * @param channels the number of channels
     * @param windowSequence the window sequence
     * @param windowShape the window shape
     * @return the first channel of the element
     * @throws MediaException.LimitExceeded if the frame holds more channels than the limits allow
     */
    private int addElement(int type, int channels, int windowSequence, int windowShape) {
        var first = channelCount;
        LimitChecks.checkChannels(limits, first + channels);
        if (elementCount == elementTypes.length) {
            var grown = elementCount * 2;
            elementTypes = Arrays.copyOf(elementTypes, grown);
            elementChannels = Arrays.copyOf(elementChannels, grown);
            windowSequences = Arrays.copyOf(windowSequences, grown);
            windowShapes = Arrays.copyOf(windowShapes, grown);
        }
        elementTypes[elementCount] = type;
        elementChannels[elementCount] = first;
        windowSequences[elementCount] = windowSequence;
        windowShapes[elementCount] = windowShape;
        elementCount++;
        if (spectra.length < first + channels) {
            var grown = Arrays.copyOf(spectra, first + channels);
            for (var i = spectra.length; i < grown.length; i++) {
                grown[i] = new double[1024];
            }
            spectra = grown;
        }
        channelCount = first + channels;
        return first;
    }

    /**
     * Reads the TNS data of a channel ({@code sam_get_tns}).
     *
     * @param windowSequence the window sequence
     * @param data receives the TNS data
     */
    private void readTns(int windowSequence, Tns data) {
        var shortWindow = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        var windows = shortWindow ? 8 : 1;
        var topBands = shortWindow ? shortTop : longTop;
        for (var w = 0; w < windows; w++) {
            var filters = (int) readInput(shortWindow ? 1 : 2);
            data.filters[w] = filters;
            if (filters == 0) {
                continue;
            }
            var resolution = (int) readInput(1) + 3;
            data.resolution[w] = resolution;
            var top = topBands;
            for (var f = 0; f < filters; f++) {
                var index = w * 3 + f;
                data.stop[index] = top;
                var length = (int) readInput(shortWindow ? 4 : 6);
                top -= length;
                data.start[index] = top;
                var order = (int) readInput(shortWindow ? 3 : 5);
                data.order[index] = order;
                if (order != 0) {
                    data.downward[index] = readInput(1) != 0;
                    var compress = (int) readInput(1);
                    var bits = resolution - compress;
                    var sign = 1 << (bits - 1);
                    for (var t = 0; t < order; t++) {
                        var coefficient = (int) readInput(bits);
                        data.coefficients[index * 32 + t] = (coefficient & sign) != 0 ? coefficient - (1 << bits)
                                : coefficient;
                    }
                }
            }
        }
    }

    /**
     * Decodes the bit-sliced data of an element and reorders the samples of short windows into window order
     * ({@code sam_decode_bsac_data}).
     */
    private void decodeData(int target, int frameBits, int sbaMode, int topLayer, int baseSnf, int baseBand,
                            int maxSfb, int windowSequence, int groups, int stereoMode, boolean pnsPresent,
                            int pnsStart, int usedBits, int channels) {
        int topBand;
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var w = 0; w < groups; w++) {
                groupOffsets[w][0] = 0;
                for (var sfb = 0; sfb < shortTop; sfb++) {
                    groupOffsets[w][sfb + 1] = shortOffsets[sfb + 1] * groupLengths[w];
                }
            }
            topBand = shortTop;
        } else {
            System.arraycopy(longOffsets, 0, groupOffsets[0], 0, longTop + 1);
            topBand = longTop;
        }
        decodeStream(target, frameBits, sbaMode, topLayer, baseSnf, baseBand, maxSfb, windowSequence, groups,
                stereoMode, pnsPresent, pnsStart, topBand, usedBits, channels);
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            var s = 0;
            for (var w = 0; w < groups; w++) {
                var length = groupLengths[w];
                for (var i = 0; i < 128; i += 4) {
                    var offset = 128 * s + i * length;
                    for (var k = 0; k < 4; k++) {
                        for (var b = 0; b < length; b++) {
                            samples[0][128 * (b + s) + i + k] = sampleBuffer[0][offset + 4 * b + k];
                            samples[1][128 * (b + s) + i + k] = sampleBuffer[1][offset + 4 * b + k];
                        }
                    }
                }
                s += length;
            }
        } else {
            System.arraycopy(sampleBuffer[0], 0, samples[0], 0, 1024);
            System.arraycopy(sampleBuffer[1], 0, samples[1], 0, 1024);
        }
    }

    /**
     * Rearranges the layers of an element into the layer buffer and decodes them ({@code sam_decode_bsac_stream}).
     */
    private void decodeStream(int target, int frameBits, int sbaMode, int topLayer, int baseSnf, int baseBand,
                              int maxSfb, int windowSequence, int groups, int stereoMode, boolean pnsPresent,
                              int pnsStart, int topBand, int usedBits, int nch) {
        if (target > topLayer) {
            target = topLayer;
        }
        var s = 0;
        for (var g = 0; g < groups; g++) {
            groupBase[g] = 128 * s;
            s += groupLengths[g];
        }
        for (var ch = 0; ch < nch; ch++) {
            for (var g = 0; g < groups; g++) {
                for (var i = 0; i < maxSfb; i++) {
                    scalefactors[ch][g * maxSfb + i] = 0;
                }
                Arrays.fill(bandPlanes[ch][g], 0);
                Arrays.fill(modelIndices[ch][g], 0);
            }
            Arrays.fill(signCoded[ch], 0);
            Arrays.fill(codedSampleBits[ch], 0);
            Arrays.fill(currentPlanes[ch], 0);
            Arrays.fill(sampleBuffer[ch], 0);
            Arrays.fill(signVectors[ch], 0);
            Arrays.fill(planeVectors[ch], 0);
        }
        Arrays.fill(stereoCoded, 0);
        pnsPcmFlag[0] = 1;
        pnsPcmFlag[1] = 1;
        maxPnsEnergy[0] = 0;
        maxPnsEnergy[1] = 0;
        if (nch == 1) {
            Arrays.fill(sampleBuffer[1], 0);
        }
        if (maxSfb == 0) {
            return;
        }
        if (usedBits % 8 != 0) {
            var k = 8 - usedBits % 8;
            readInput(k);
            usedBits += k;
        }
        var slayerSize = initializeLayers(windowSequence, groups, baseBand, topLayer, maxSfb, topBand);
        if (slayerSize == 0) {
            throw new MediaException.Malformed("BSAC element without base layer bands");
        }
        var totalLayers = topLayer + slayerSize;
        for (var i = 0; i <= topLayer; i++) {
            scaleBitsOfElement[i] = scaleBits[i] * nch;
        }
        Arrays.fill(layerSideInfoLengths, 0);
        for (var ch = 0; ch < nch; ch++) {
            for (var g = 0; g < groups; g++) {
                endCband[ch][g] = 0;
                endQband[ch][g] = 0;
            }
        }
        for (var layer = 0; layer < totalLayers; layer++) {
            var g = layerReg[layer];
            for (var ch = 0; ch < nch; ch++) {
                startQband[ch][g] = endQband[ch][g];
                startCband[ch][g] = endCband[ch][g];
                endQband[ch][g] = layerMaxQband[layer];
                endCband[ch][g] = layerMaxCband[layer];
            }
            for (var ch = 0; ch < nch; ch++) {
                for (var cband = startCband[ch][g]; cband < endCband[ch][g]; cband++) {
                    layerSideInfoLengths[layer] += cband <= 0 ? MAX_CBAND0_SI_LEN
                            : MAX_CBAND_SI_LENGTH[cbandSiType[ch]];
                }
                for (var qband = startQband[ch][g]; qband < endQband[ch][g]; qband++) {
                    layerSideInfoLengths[layer] += maxSfbSiLength[ch] + 5;
                }
            }
        }
        var flen = Math.max(frameBits, scaleBitsOfElement[topLayer]);
        for (var layer = totalLayers; layer < MAX_LAYER + 1; layer++) {
            layerBufferOffsets[layer] = flen;
        }
        var siOffset = flen - layerSideInfoLengths[totalLayers - 1];
        int layer;
        for (layer = totalLayers - 1; layer >= slayerSize; layer--) {
            layerBufferOffsets[layer] = Math.min(siOffset, scaleBitsOfElement[layer - slayerSize]);
            siOffset = layerBufferOffsets[layer] - layerSideInfoLengths[layer - 1];
        }
        for (; layer > 0; layer--) {
            layerBufferOffsets[layer] = siOffset;
            siOffset = layerBufferOffsets[layer] - layerSideInfoLengths[layer - 1];
        }
        layerBufferOffsets[0] = usedBits;
        if (usedBits > siOffset) {
            siOffset = usedBits - siOffset;
            for (layer = totalLayers - 1; layer >= slayerSize; layer--) {
                var cwLen = layerBufferOffsets[layer + 1] - layerBufferOffsets[layer] - layerSideInfoLengths[layer];
                if (cwLen >= siOffset) {
                    cwLen = siOffset;
                    siOffset = 0;
                } else {
                    siOffset -= cwLen;
                }
                for (var i = 1; i <= layer; i++) {
                    layerBufferOffsets[i] += cwLen;
                }
                if (siOffset == 0) {
                    break;
                }
            }
        } else {
            siOffset -= usedBits;
            for (layer = 1; layer < slayerSize; layer++) {
                layerBufferOffsets[layer] = layerBufferOffsets[layer - 1] + layerSideInfoLengths[layer - 1]
                        + siOffset / slayerSize;
                if (layer <= siOffset % slayerSize) {
                    layerBufferOffsets[layer]++;
                }
            }
        }
        for (layer = totalLayers - 1; layer >= 0; layer--) {
            if (layerBufferOffsets[layer] > frameBits) {
                layerBufferOffsets[layer] = frameBits;
            } else {
                break;
            }
        }
        var availableBits = frameBits;
        for (layer = target + slayerSize - 1; layer >= 0; layer--) {
            if (layerBufferOffsets[layer] > availableBits) {
                target--;
            } else {
                break;
            }
        }
        var decodedLayers = target + slayerSize;
        initLayerBuffer();
        setLayerPosition(usedBits);
        if (sbaMode != 0) {
            for (layer = 0; layer < decodedLayers; layer++) {
                layerCodewordOffsets[layer] = layerPosition();
                var cwLen = layerBufferOffsets[layer + 1] - layerBufferOffsets[layer];
                while (cwLen > 0) {
                    if (inputPosition - elementStart >= frameBits) {
                        break;
                    }
                    var length = Math.min(cwLen, 8);
                    putLayerBits((int) readInput(length), length);
                    cwLen -= length;
                }
                while (cwLen > 0) {
                    putLayerBits(0, 8);
                    cwLen -= 8;
                }
                if (layerBitFlush[layer] != 0) {
                    putLayerBits(0, 32);
                }
            }
        } else {
            var cwLen = availableBits - usedBits;
            while (cwLen > 0) {
                if (inputPosition - elementStart >= availableBits) {
                    break;
                }
                var length = Math.min(cwLen, 8);
                putLayerBits((int) readInput(length), length);
                cwLen -= length;
            }
            while (cwLen > 0) {
                putLayerBits(0, 8);
                cwLen -= 8;
            }
            putLayerBits(0, 32);
        }
        Arrays.fill(layerExtraLengths, 0);
        for (var ch = 0; ch < nch; ch++) {
            for (var g = 0; g < groups; g++) {
                startQband[ch][g] = 0;
                startCband[ch][g] = 0;
                startFreq[ch][g] = 0;
                endQband[ch][g] = 0;
                endCband[ch][g] = 0;
                endFreq[ch][g] = 0;
                sFreq[ch][g] = 0;
            }
        }
        var available = 0;
        if (sbaMode == 0) {
            initArithmetic(0);
            setArithmetic(0);
            setLayerPosition(usedBits);
        }
        var shortWindow = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        if (shortWindow) {
            for (layer = 0; layer < slayerSize; layer++) {
                for (var ch = 0; ch < nch; ch++) {
                    eFreq[ch][layerReg[layer]] = layerMaxFreq[layer];
                }
            }
        }
        for (layer = 0; layer < decodedLayers; layer++) {
            var g = layerReg[layer];
            for (var ch = 0; ch < nch; ch++) {
                startFreq[ch][g] = endFreq[ch][g];
                startQband[ch][g] = endQband[ch][g];
                startCband[ch][g] = endCband[ch][g];
                endQband[ch][g] = layerMaxQband[layer];
                endCband[ch][g] = layerMaxCband[layer];
                endFreq[ch][g] = layerMaxFreq[layer];
            }
            updateEndFrequencies(layer, slayerSize, shortWindow, nch);
            if (sbaMode != 0) {
                if (layer == 0 || layerBitFlush[layer - 1] != 0) {
                    initArithmetic(layer);
                    setArithmetic(layer);
                    setLayerPosition(layerCodewordOffsets[layer]);
                    available = layerBufferOffsets[layer + 1] - layerBufferOffsets[layer] - 1;
                } else {
                    available += layerBufferOffsets[layer + 1] - layerBufferOffsets[layer];
                }
            } else {
                available += layerBufferOffsets[layer + 1] - layerBufferOffsets[layer];
            }
            if (sbaMode != 0 && available <= 0) {
                for (var ch = 0; ch < nch; ch++) {
                    var base = groupBase[g];
                    for (var i = startFreq[ch][g]; i < endFreq[ch][g]; i++) {
                        currentPlanes[ch][base + i] = bandPlanes[ch][g][i / 32];
                    }
                }
                continue;
            }
            var cwLen = decodeCodingBandInfo(g, nch);
            for (var ch = 0; ch < nch; ch++) {
                for (var cband = startCband[ch][g]; cband < endCband[ch][g]; cband++) {
                    var index = modelIndices[ch][g][cband];
                    bandPlanes[ch][g][cband] = index >= 15 ? index - 7 : (index + 1) / 2;
                }
                var base = groupBase[g];
                for (var i = startFreq[ch][g]; i < endFreq[ch][g]; i++) {
                    currentPlanes[ch][base + i] = bandPlanes[ch][g][i / 32];
                }
            }
            available -= cwLen;
            cwLen = decodeScalefactorInfo(layer < slayerSize ? scfModel0 : scfModel1, stereoMode, maxSfb,
                    pnsPresent, pnsStart, g, nch);
            available -= cwLen;
            var minPlane = layer < slayerSize ? baseSnf : 1;
            cwLen = decodeSpectra(g, g + 1, startFreq, endFreq, minPlane, available, sbaMode, nch);
            available -= cwLen;
            if (sbaMode != 0 && layerBitFlush[layer] != 0 && available > 0) {
                layerExtraLengths[layer] = available;
                storeArithmetic(layer);
                layerCodewordOffsets[layer] = layerPosition();
            }
            if (sbaMode == 0 && available > 0) {
                cwLen = decodeSpectra(0, groups, sFreq, eFreq, 1, available, sbaMode, nch);
                available -= cwLen;
            }
        }
        for (var ch = 0; ch < nch; ch++) {
            for (var g = 0; g < groups; g++) {
                startFreq[ch][g] = 0;
            }
        }
        if (shortWindow) {
            for (layer = 0; layer < slayerSize; layer++) {
                for (var ch = 0; ch < nch; ch++) {
                    eFreq[ch][layerReg[layer]] = layerMaxFreq[layer];
                }
            }
        }
        if (sbaMode != 0) {
            for (layer = 0; layer < decodedLayers; layer++) {
                available = layerExtraLengths[layer];
                updateEndFrequencies(layer, slayerSize, shortWindow, nch);
                if (available <= 0) {
                    continue;
                }
                setArithmetic(layer);
                setLayerPosition(layerCodewordOffsets[layer]);
                var cwLen = decodeSpectra(0, groups, startFreq, eFreq, 1, available, sbaMode, nch);
                available -= cwLen;
                if (shortWindow) {
                    for (var ch = 0; ch < nch; ch++) {
                        System.arraycopy(eFreq[ch], 0, sFreq[ch], 0, groups);
                    }
                }
                for (var k = layer + 1; k < decodedLayers; k++) {
                    if (available <= 0) {
                        break;
                    }
                    for (var ch = 0; ch < nch; ch++) {
                        var g = layerReg[k];
                        sFreq[ch][g] = eFreq[ch][g];
                        eFreq[ch][g] = layerMaxFreq[k];
                    }
                    cwLen = decodeSpectra(0, groups, sFreq, eFreq, 1, available, sbaMode, nch);
                    available -= cwLen;
                }
            }
        }
        var used = layerPosition();
        if (used % 8 != 0) {
            getLayerBits(8 - used % 8);
        }
    }

    /**
     * Sets the end frequencies of the spectral data a layer decodes after its own bands.
     *
     * @param layer the layer index
     * @param slayerSize the number of base layers
     * @param shortWindow whether the element has short windows
     * @param nch the number of channels
     */
    private void updateEndFrequencies(int layer, int slayerSize, boolean shortWindow, int nch) {
        if (shortWindow) {
            if (layer >= slayerSize) {
                for (var ch = 0; ch < nch; ch++) {
                    eFreq[ch][layerReg[layer]] = layerMaxFreq[layer];
                }
            }
        } else {
            for (var ch = 0; ch < nch; ch++) {
                eFreq[ch][0] = layer >= slayerSize ? layerMaxFreq[layer] : layerMaxFreq[slayerSize - 1];
            }
        }
    }

    /**
     * Decodes the model indices of the coding bands of a layer ({@code decode_cband_si}).
     *
     * @param g the window group of the layer
     * @param nch the number of channels
     * @return the number of bits the symbols took
     */
    private int decodeCodingBandInfo(int g, int nch) {
        var length = 0;
        for (var ch = 0; ch < nch; ch++) {
            int model;
            int maxIndex;
            if (startCband[ch][g] == 0) {
                model = 7;
                maxIndex = MAX_MODEL0_INDEX[cbandSiType[ch]];
            } else {
                model = CBAND_SI_MODEL[cbandSiType[ch]];
                maxIndex = MAX_MODEL_INDEX[cbandSiType[ch]];
            }
            for (var cband = startCband[ch][g]; cband < endCband[ch][g]; cband++) {
                length += decodeSymbol(BsacTables.CODING_BAND[model]);
                modelIndices[ch][g][cband] = Math.min(symbol, maxIndex);
            }
        }
        return length;
    }

    /**
     * Decodes the scalefactors and the stereo and noise substitution side information of the bands of a layer
     * ({@code decode_scfband_si}).
     *
     * @param scfModel the scalefactor model of each channel
     * @param stereoMode the stereo mode
     * @param maxSfb the number of bands
     * @param pnsPresent whether noise substitution is present
     * @param pnsStart the first band noise substitution may apply to
     * @param g the window group
     * @param nch the number of channels
     * @return the number of bits the symbols took
     */
    private int decodeScalefactorInfo(int[] scfModel, int stereoMode, int maxSfb, boolean pnsPresent, int pnsStart,
                                      int g, int nch) {
        var length = 0;
        for (var ch = 0; ch < nch; ch++) {
            for (var sfb = startQband[ch][g]; sfb < endQband[ch][g]; sfb++) {
                var band = g * maxSfb + sfb;
                if (nch == 1) {
                    if (pnsPresent && sfb >= pnsStart) {
                        length += decodeSymbol(BsacTables.NOISE_FLAG);
                        noiseFlags[0][band] = symbol;
                    }
                } else if (stereoCoded[band] == 0) {
                    if (stereoMode != 2) {
                        var m = 0;
                        if (stereoMode == 1) {
                            length += decodeSymbol(BsacTables.MS_USED);
                            m = symbol;
                        } else if (stereoMode == 3) {
                            length += decodeSymbol(BsacTables.STEREO_INFO);
                            m = symbol;
                        }
                        switch (m) {
                            case 1 -> msMask[band + 1] = 1;
                            case 2 -> intensity[band] = 1;
                            case 3 -> intensity[band] = 2;
                            default -> {
                            }
                        }
                        if (pnsPresent && sfb >= pnsStart) {
                            length += decodeSymbol(BsacTables.NOISE_FLAG);
                            noiseFlags[0][band] = symbol;
                            length += decodeSymbol(BsacTables.NOISE_FLAG);
                            noiseFlags[1][band] = symbol;
                            if (stereoMode == 3 && intensity[band] == 2 && noiseFlags[0][band] != 0
                                    && noiseFlags[1][band] != 0) {
                                length += decodeSymbol(BsacTables.NOISE_MODE);
                                noiseModes[band] = symbol;
                            }
                        }
                    }
                    stereoCoded[band] = 1;
                }
                if (noiseFlags[ch][band] != 0) {
                    if (pnsPcmFlag[ch] == 1) {
                        length += decodeSymbol(BsacTables.NOISE_ENERGY);
                        maxPnsEnergy[ch] = symbol;
                        pnsPcmFlag[ch] = 0;
                    }
                    if (scfModel[ch] != 0) {
                        length += decodeSymbol(BsacTables.SCALEFACTOR[scfModel[ch]]);
                        scalefactors[ch][band] = maxPnsEnergy[ch] - symbol;
                    } else {
                        scalefactors[ch][band] = maxPnsEnergy[ch];
                    }
                } else if (intensity[band] != 0 && ch == 1) {
                    if (scfModel[ch] == 0) {
                        scalefactors[ch][band] = 0;
                    } else {
                        length += decodeSymbol(BsacTables.SCALEFACTOR[scfModel[ch]]);
                        var m = symbol;
                        scalefactors[ch][band] = (m % 2) != 0 ? -((m + 1) / 2) : m / 2;
                    }
                } else if (scfModel[ch] == 0) {
                    scalefactors[ch][band] = maxScalefactor[ch];
                } else {
                    length += decodeSymbol(BsacTables.SCALEFACTOR[scfModel[ch]]);
                    scalefactors[ch][band] = maxScalefactor[ch] - symbol;
                }
            }
        }
        return length;
    }

    /**
     * Returns the probability of a 1 bit of a sample whose more significant bits are decoded ({@code select_freq1}).
     */
    private static int selectFrequency1(int modelIndex, int plane, int codedBits, int available) {
        var distance = modelIndex >= 15 ? modelIndex - 7 - plane : (modelIndex + 1) / 2 - plane;
        var row = BsacTables.MODEL_OFFSET_ROW[modelIndex];
        var model = distance >= 4 ? modelOffset(row, 7) : modelOffset(row, distance + 3);
        model += codedBits > 15 ? 15 : codedBits - 1;
        return clampFrequency(model, available);
    }

    /**
     * Returns the probability of a 1 bit of a sample whose more significant bits are all zero
     * ({@code select_freq0}).
     */
    private static int selectFrequency0(int modelIndex, int plane, int signVector, int position, int planeVector,
                                        int available) {
        var distance = modelIndex >= 15 ? modelIndex - 7 - plane : (modelIndex + 1) / 2 - plane;
        var row = BsacTables.MODEL_OFFSET_ROW[modelIndex];
        var model = distance >= 3 ? modelOffset(row, 3) : modelOffset(row, distance);
        var flat = signVector * 32 + position * 8 + planeVector;
        if (signVector < 0 || planeVector < 0 || flat >= 16 * 32) {
            throw new MediaException.Malformed("BSAC sample vector out of range");
        }
        model += BsacTables.SMALL_STEP_OFFSET[flat >> 5][(flat >> 3) & 3][flat & 7];
        return clampFrequency(model, available);
    }

    /**
     * Returns an entry of a row of the model offsets.
     *
     * @param row the row, -1 for a model index without one
     * @param column the column; a negative one when the bit plane exceeds the significance
     * @return the model offset
     * @throws MediaException.Malformed if the entry does not exist
     */
    private static int modelOffset(int row, int column) {
        if (row < 0 || column < 0) {
            throw new MediaException.Malformed("BSAC sample exceeds the significance of its coding band");
        }
        return BsacTables.LARGE_STEP_OFFSET[row][column];
    }

    /**
     * Returns the probability of a spectral model, bounded when few bits are left in the layer.
     *
     * @param model the model number
     * @param available the bits left in the layer
     * @return the probability of a 0 out of 16384
     */
    private static int clampFrequency(int model, int available) {
        if (model >= 1016) {
            model = 1015;
        }
        var frequency = BsacTables.SPECTRUM[Math.max(0, model)];
        if (available < 14) {
            var floor = MIN_FREQUENCY[Math.max(0, available)];
            if (frequency < floor) {
                frequency = floor;
            } else if (frequency > 16384 - floor) {
                frequency = 16384 - floor;
            }
        }
        return frequency;
    }

    /**
     * Decodes bit-sliced spectral data ({@code decode_spectra}).
     *
     * @param startReg the first window group
     * @param endReg the window group after the last
     * @param from the first sample of each channel and window group
     * @param to the sample after the last of each channel and window group
     * @param minPlane the lowest bit plane to decode
     * @param available the bits available
     * @param sbaMode the segmented binary arithmetic coding mode
     * @param nch the number of channels
     * @return the number of bits the symbols took
     */
    private int decodeSpectra(int startReg, int endReg, int[][] from, int[][] to, int minPlane, int available,
                              int sbaMode, int nch) {
        var maxPlane = 0;
        for (var ch = 0; ch < nch; ch++) {
            for (var reg = startReg; reg < endReg; reg++) {
                var base = groupBase[reg];
                for (var i = from[ch][reg]; i < to[ch][reg]; i++) {
                    maxPlane = Math.max(maxPlane, currentPlanes[ch][base + i]);
                }
            }
        }
        var cwLen = available;
        for (var plane = maxPlane; plane >= minPlane; plane--) {
            if (cwLen <= 0) {
                return available - cwLen;
            }
            var half = 1 << (plane - 1);
            for (var reg = startReg; reg < endReg; reg++) {
                var base = groupBase[reg];
                var vectorBase = base / 4;
                for (var i = from[0][reg]; i < to[0][reg]; i++) {
                    for (var ch = 0; ch < nch; ch++) {
                        var index = base + i;
                        if (currentPlanes[ch][index] < plane) {
                            continue;
                        }
                        var vector = vectorBase + i / 4;
                        if (codedSampleBits[ch][index] == 0 || signCoded[ch][index] == 1) {
                            if (i % 4 == 0) {
                                signVectors[ch][vector] |= planeVectors[ch][vector];
                                planeVectors[ch][vector] = 0;
                            }
                            var length = sbaMode == 0 ? 100 : cwLen;
                            var modelIndex = modelIndices[ch][reg][i / 32];
                            var frequency = codedSampleBits[ch][index] != 0
                                    ? selectFrequency1(modelIndex, plane, codedSampleBits[ch][index], length)
                                    : selectFrequency0(modelIndex, plane, signVectors[ch][vector], i % 4,
                                    planeVectors[ch][vector], length);
                            cwLen -= decodeBinary(frequency);
                            var m = symbol;
                            if (m != 0) {
                                if (sampleBuffer[ch][index] >= 0) {
                                    sampleBuffer[ch][index] += half;
                                } else {
                                    sampleBuffer[ch][index] -= half;
                                }
                            }
                            planeVectors[ch][vector] = (planeVectors[ch][vector] << 1) | m;
                            codedSampleBits[ch][index] = (codedSampleBits[ch][index] << 1) | m;
                        }
                        if (codedSampleBits[ch][index] != 0 && signCoded[ch][index] == 0) {
                            if (cwLen <= 0) {
                                return available - cwLen;
                            }
                            cwLen -= decodeBinary(8192);
                            if (symbol != 0) {
                                sampleBuffer[ch][index] = -sampleBuffer[ch][index];
                            }
                            signCoded[ch][index] = 1;
                        }
                        currentPlanes[ch][index]--;
                        if (cwLen <= 0) {
                            return available - cwLen;
                        }
                    }
                }
            }
        }
        return available - cwLen;
    }

    /**
     * Computes the bands of each layer ({@code initialize_layer_data}).
     *
     * @param windowSequence the window sequence
     * @param groups the number of window groups
     * @param baseBand the bandwidth of the base layer
     * @param topLayer the top layer
     * @param maxSfb the number of bands
     * @param topBand the number of bands of the window size
     * @return the number of base layers
     */
    private int initializeLayers(int windowSequence, int groups, int baseBand, int topLayer, int maxSfb,
                                 int topBand) {
        var slayerSize = 0;
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var g = 0; g < groups; g++) {
                var init = baseBand * groupLengths[g] * 4;
                lastFreq[g] = groupOffsets[g][maxSfb];
                switch (sampleRate) {
                    case 48000, 44100 -> {
                        if (init % 32 >= 16) {
                            init = (init / 32) * 32 + 20;
                        } else if (init % 32 >= 4) {
                            init = (init / 32) * 32 + 8;
                        }
                    }
                    case 32000, 24000, 22050 -> init = (init / 16) * 16;
                    case 16000, 12000, 11025 -> init = (init / 32) * 32;
                    case 8000 -> init = (init / 64) * 64;
                    default -> {
                    }
                }
                init = Math.min(init, lastFreq[g]);
                initMaxFreq[g] = init;
                endFreqOfReg[g] = init;
                endCbandOfReg[g] = (init + 31) / 32;
                slayerSize += (init + 31) / 32;
            }
            var layer = 0;
            for (var g = 0; g < groups; g++) {
                for (var cband = 1; cband <= endCbandOfReg[g]; layer++, cband++) {
                    layerReg[layer] = g;
                    layerMaxFreq[layer] = Math.min(cband * 32, initMaxFreq[g]);
                    layerMaxCband[layer] = cband;
                }
            }
            layer = slayerSize;
            for (var g = 0; g < groups; g++) {
                for (var i = 0; i < groupLengths[g]; i++) {
                    layerReg[layer++] = g;
                }
            }
            for (layer = slayerSize + 8; layer < MAX_LAYER; layer++) {
                layerReg[layer] = layerReg[layer - 8];
            }
            for (layer = slayerSize; layer < MAX_LAYER; layer++) {
                var reg = layerReg[layer];
                endFreqOfReg[reg] += layerStep(endFreqOfReg[reg]);
                endFreqOfReg[reg] = Math.min(endFreqOfReg[reg], lastFreq[reg]);
                layerMaxFreq[layer] = endFreqOfReg[reg];
                layerMaxCband[layer] = (layerMaxFreq[layer] + 31) / 32;
            }
            for (layer = 0; layer < topLayer + slayerSize - 1; layer++) {
                layerBitFlush[layer] = layerMaxCband[layer] != layerMaxCband[layer + 1]
                        || layerReg[layer] != layerReg[layer + 1] ? 1 : 0;
            }
        } else {
            for (var layer = 0; layer < MAX_LAYER; layer++) {
                layerReg[layer] = 0;
            }
            lastFreq[0] = groupOffsets[0][maxSfb];
            var init = Math.min(baseBand * 32, lastFreq[0]);
            slayerSize = (init + 31) / 32;
            if (slayerSize == 0) {
                return 0;
            }
            for (var layer = 0; layer < slayerSize; layer++) {
                layerMaxFreq[layer] = Math.min((layer + 1) * 32, init);
                layerMaxCband[layer] = layer + 1;
            }
            for (var layer = slayerSize; layer < MAX_LAYER; layer++) {
                var freq = layerMaxFreq[layer - 1] + layerStep(layerMaxFreq[layer - 1]);
                layerMaxFreq[layer] = Math.min(freq, lastFreq[0]);
                layerMaxCband[layer] = (layerMaxFreq[layer] + 31) / 32;
            }
            for (var layer = 0; layer < topLayer + slayerSize - 1; layer++) {
                layerBitFlush[layer] = layerMaxCband[layer] != layerMaxCband[layer + 1] ? 1 : 0;
            }
        }
        for (var layer = Math.max(0, topLayer + slayerSize - 1); layer < MAX_LAYER + 1; layer++) {
            layerBitFlush[layer] = 1;
        }
        for (var layer = 0; layer < MAX_LAYER; layer++) {
            var endLayer = layer;
            while (layerBitFlush[endLayer] == 0) {
                endLayer++;
            }
            var reg = layerReg[layer];
            layerMaxQband[layer] = maxSfb;
            for (var sfb = 0; sfb < topBand; sfb++) {
                if (layerMaxFreq[endLayer] <= groupOffsets[reg][sfb + 1]) {
                    layerMaxQband[layer] = sfb + 1;
                    break;
                }
            }
            layerMaxQband[layer] = Math.min(layerMaxQband[layer], maxSfb);
        }
        return slayerSize;
    }

    /**
     * Returns how many spectral lines a layer adds after the base layers.
     *
     * @param end the end frequency of the previous layer
     * @return the number of lines
     */
    private int layerStep(int end) {
        return switch (sampleRate) {
            case 48000, 44100 -> (end + 8) % 32 != 0 ? 12 : 8;
            case 32000, 24000, 22050 -> 16;
            case 16000, 12000, 11025 -> 32;
            default -> 64;
        };
    }

    /**
     * Dequantizes the samples of a channel ({@code sam_dequantization}).
     */
    private void dequantize(int windowSequence, int[] factors, int groups, int[] quantized, int maxSfb,
                            double[] spectrum, int ch) {
        Arrays.fill(spectrum, -0.0);
        if (windowSequence != ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var sfb = 0; sfb < maxSfb; sfb++) {
                if (ch == 1 && intensity[sfb] != 0) {
                    continue;
                }
                var scale = scale(factors[sfb] - SF_OFFSET);
                for (var i = longOffsets[sfb]; i < longOffsets[sfb + 1]; i++) {
                    spectrum[i] = inverseQuantize(quantized[i]) * scale;
                }
            }
        } else {
            var s = 0;
            for (var w = 0; w < groups; w++) {
                for (var sfb = 0; sfb < maxSfb; sfb++) {
                    if (ch == 1 && intensity[w * maxSfb + sfb] != 0) {
                        continue;
                    }
                    var scale = scale(factors[w * maxSfb + sfb] - SF_OFFSET);
                    for (var b = 0; b < groupLengths[w]; b++) {
                        var k = (s + b) * 128;
                        for (var i = shortOffsets[sfb]; i < shortOffsets[sfb + 1]; i++) {
                            spectrum[k + i] = inverseQuantize(quantized[k + i]) * scale;
                        }
                    }
                }
                s += groupLengths[w];
            }
        }
    }

    /**
     * Returns the scale of a scalefactor ({@code sam_calc_scale}).
     *
     * @param factor the scalefactor minus its offset
     * @return {@code 2^(factor/4)}, 0 for the smallest scalefactor
     */
    private double scale(int factor) {
        if (factor >= 0 && factor < TABLE_SIZE) {
            return scales[factor];
        }
        return factor == -SF_OFFSET ? 0.0 : StrictMath.pow(2.0, 0.25 * factor);
    }

    /**
     * Returns the inverse quantization of a sample ({@code sam_iquant_exp}).
     *
     * @param q the quantized sample
     * @return {@code sign(q) |q|^(4/3)}
     */
    private double inverseQuantize(int q) {
        if (q > 0) {
            return q < TABLE_SIZE ? inverseQuantization[q] : StrictMath.pow(q, 4.0 / 3.0);
        }
        var magnitude = -q;
        return magnitude < TABLE_SIZE ? -inverseQuantization[magnitude] : -StrictMath.pow(magnitude, 4.0 / 3.0);
    }

    /**
     * Applies M/S stereo ({@code sam_ms_stereo}).
     */
    private void applyMidSide(int windowSequence, int groups, double[] left, double[] right, int maxSfb) {
        if (windowSequence != ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var sfb = 0; sfb < maxSfb; sfb++) {
                if (msMask[sfb + 1] == 0) {
                    continue;
                }
                for (var i = longOffsets[sfb]; i < longOffsets[sfb + 1]; i++) {
                    var l = left[i];
                    var r = right[i];
                    left[i] = l + r;
                    right[i] = l - r;
                }
            }
            return;
        }
        var s = 0;
        for (var w = 0; w < groups; w++) {
            for (var b = s; b < s + groupLengths[w]; b++) {
                for (var sfb = 0; sfb < maxSfb; sfb++) {
                    if (msMask[w * maxSfb + sfb + 1] == 0) {
                        continue;
                    }
                    for (var i = shortOffsets[sfb]; i < shortOffsets[sfb + 1]; i++) {
                        var l = left[b * 128 + i];
                        var r = right[b * 128 + i];
                        left[b * 128 + i] = l + r;
                        right[b * 128 + i] = l - r;
                    }
                }
            }
            s += groupLengths[w];
        }
    }

    /**
     * Fills the noise substituted bands of an element ({@code sam_pns}).
     */
    private void applyNoise(int maxSfb, int windowSequence, int groups, int first, int nch) {
        for (var ch = 0; ch < nch; ch++) {
            var spectrum = spectra[first + ch];
            if (windowSequence != ChannelStream.EIGHT_SHORT_SEQUENCE) {
                for (var sfb = 0; sfb < maxSfb; sfb++) {
                    if (noiseFlags[ch][sfb] == 0) {
                        continue;
                    }
                    fillNoise(spectrum, longOffsets[sfb], longOffsets[sfb + 1], noiseModes[sfb] != 0, sfb, sfb,
                            scalefactors[ch][sfb]);
                }
            } else {
                var s = 0;
                for (var w = 0; w < groups; w++) {
                    for (var b = 0; b < groupLengths[w]; b++, s++) {
                        for (var sfb = 0; sfb < maxSfb; sfb++) {
                            var band = w * maxSfb + sfb;
                            if (noiseFlags[ch][band] == 0) {
                                continue;
                            }
                            fillNoise(spectrum, s * 128 + shortOffsets[sfb], s * 128 + shortOffsets[sfb + 1],
                                    noiseModes[band] != 0, sfb, band, scalefactors[ch][band]);
                        }
                    }
                }
            }
        }
    }

    /**
     * Fills a band with noise of the energy its scalefactor gives.
     *
     * @param spectrum the spectrum that receives the noise
     * @param start the first line
     * @param end the line after the last
     * @param correlated whether the band reuses the noise state stored for it
     * @param readSlot the slot of the stored state a correlated band reads
     * @param writeSlot the slot the state of an uncorrelated band is stored into
     * @param factor the scalefactor
     */
    private void fillNoise(double[] spectrum, int start, int end, boolean correlated, int readSlot, int writeSlot,
                           int factor) {
        var size = end - start;
        var norm = 1.0 / StrictMath.sqrt(size * MEAN_ENERGY);
        var seed = correlated ? noiseStates[readSlot] : noiseState;
        if (!correlated) {
            noiseStates[writeSlot] = noiseState;
        }
        var energy = 0.0;
        for (var i = start; i < end; i++) {
            seed = 1664525 * seed + 1013904223;
            spectrum[i] = seed * norm;
            energy += spectrum[i] * spectrum[i];
        }
        if (correlated) {
            noiseStates[readSlot] = seed;
        } else {
            noiseState = seed;
        }
        var s = energy > 0 ? 1.0 / StrictMath.sqrt(energy) : 0.0;
        var scale = StrictMath.pow(2.0, 0.25 * factor);
        for (var i = start; i < end; i++) {
            spectrum[i] = spectrum[i] * s * scale;
        }
    }

    /**
     * Applies intensity stereo ({@code sam_intensity}).
     */
    private void applyIntensity(int windowSequence, int groups, double[] left, double[] right, int maxSfb) {
        if (windowSequence != ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var sfb = 0; sfb < maxSfb; sfb++) {
                if (intensity[sfb] == 0) {
                    continue;
                }
                var scale = (intensity[sfb] == 1 ? 1 : -1) * StrictMath.pow(0.5, 0.25 * scalefactors[1][sfb]);
                for (var i = longOffsets[sfb]; i < longOffsets[sfb + 1]; i++) {
                    right[i] = left[i] * scale;
                }
            }
            return;
        }
        var s = 0;
        for (var w = 0; w < groups; w++) {
            for (var b = 0; b < groupLengths[w]; b++, s++) {
                for (var sfb = 0; sfb < maxSfb; sfb++) {
                    var band = w * maxSfb + sfb;
                    if (intensity[band] == 0) {
                        continue;
                    }
                    var scale = (intensity[band] == 1 ? 1 : -1) * StrictMath.pow(0.5, 0.25 * scalefactors[1][band]);
                    for (var i = shortOffsets[sfb]; i < shortOffsets[sfb + 1]; i++) {
                        right[s * 128 + i] = left[s * 128 + i] * scale;
                    }
                }
            }
        }
    }

    /**
     * Applies the TNS filters of a channel ({@code sam_tns_data}).
     */
    private void applyTns(int windowSequence, int maxSfb, double[] spectrum, Tns data) {
        var shortWindow = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        var offsets = shortWindow ? shortOffsets : longOffsets;
        var maxBands = shortWindow ? tnsMaxBandsShort : tnsMaxBandsLong;
        var maxOrder = shortWindow ? 7 : tnsMaxOrderLong;
        var windows = shortWindow ? 8 : 1;
        for (var w = 0; w < windows; w++) {
            var base = w * 128;
            for (var f = 0; f < data.filters[w]; f++) {
                var index = w * 3 + f;
                var order = data.order[index];
                if (order > maxOrder) {
                    throw new MediaException.Malformed("BSAC TNS filter of order " + order);
                }
                if (order == 0) {
                    continue;
                }
                decodeTnsCoefficients(order, data.resolution[w], data.coefficients, index * 32);
                var start = Math.min(Math.min(data.start[index], maxBands), maxSfb);
                start = start > 0 ? offsets[start] : 0;
                var stop = Math.min(Math.min(data.stop[index], maxBands), maxSfb);
                stop = stop > 0 ? offsets[stop] : 0;
                var size = stop - start;
                if (size <= 0) {
                    continue;
                }
                Arrays.fill(tnsState, 0, order, 0);
                var downward = data.downward[index];
                var position = downward ? base + start + size - 1 : base + start;
                var increment = downward ? -1 : 1;
                for (var i = 0; i < size; i++) {
                    var y = spectrum[position];
                    for (var j = 0; j < order; j++) {
                        y -= lpc[j + 1] * tnsState[j];
                    }
                    for (var j = order - 1; j > 0; j--) {
                        tnsState[j] = tnsState[j - 1];
                    }
                    tnsState[0] = y;
                    spectrum[position] = y;
                    position += increment;
                }
            }
        }
    }

    /**
     * Converts the transmitted coefficients of a TNS filter into LPC coefficients ({@code sam_tns_decode_coef}).
     *
     * @param order the filter order
     * @param resolution the coefficient resolution, 3 or 4 bits
     * @param coefficients the transmitted coefficients
     * @param offset the index of the first coefficient
     */
    private void decodeTnsCoefficients(int order, int resolution, int[] coefficients, int offset) {
        var positive = ((1 << (resolution - 1)) - 0.5) / (StrictMath.PI / 2.0);
        var negative = ((1 << (resolution - 1)) + 0.5) / (StrictMath.PI / 2.0);
        for (var i = 0; i < order; i++) {
            var c = coefficients[offset + i];
            reflection[i + 1] = StrictMath.sin(c / (c >= 0 ? positive : negative));
        }
        lpc[0] = 1;
        for (var m = 1; m <= order; m++) {
            lpcScratch[0] = lpc[0];
            for (var i = 1; i < m; i++) {
                lpcScratch[i] = lpc[i] + reflection[m] * lpc[m - i];
            }
            lpcScratch[m] = reflection[m];
            System.arraycopy(lpcScratch, 0, lpc, 0, m + 1);
        }
    }

    /**
     * Reads bits of the frame, zeros past its end.
     *
     * @param count the number of bits, at most 32; none when not positive
     * @return the bits
     */
    private long readInput(int count) {
        if (count <= 0) {
            return 0;
        }
        var result = 0L;
        for (var i = 0; i < count; i++) {
            var position = inputPosition + i;
            var index = (int) (position >>> 3);
            var bit = index < inputLength && position >= 0 ? (input[index] >> (7 - (position & 7))) & 1 : 0;
            result = (result << 1) | bit;
        }
        inputPosition += count;
        return result;
    }

    /**
     * Skips bits of the frame.
     *
     * @param count the number of bits; none when not positive
     */
    private void skipInput(long count) {
        if (count > 0) {
            inputPosition += count;
        }
    }

    /**
     * Clears the layer buffer ({@code sam_init_layer_buf}).
     */
    private void initLayerBuffer() {
        layerByte = LAYER_BUFFER_SIZE - 1;
        layerBit = 0;
        Arrays.fill(layerBuffer, (byte) 0);
    }

    /**
     * Moves the layer buffer position ({@code sam_setRBitBufPos}).
     *
     * @param position the bit position
     */
    private void setLayerPosition(int position) {
        layerByte = position / 8;
        layerBit = 8 - position % 8;
    }

    /**
     * Returns the layer buffer position ({@code sam_getRBitBufPos}).
     *
     * @return the bit position
     */
    private int layerPosition() {
        return layerByte * 8 + 8 - layerBit;
    }

    /**
     * Reads bits of the layer buffer, zeros past its end ({@code sam_getbitsfrombuf}).
     *
     * @param count the number of bits, at most 32
     * @return the bits
     */
    private long getLayerBits(int count) {
        if (count <= 0) {
            return 0;
        }
        var result = 0L;
        var j = count;
        while (j > 0) {
            if (layerBit == 0) {
                layerBit = 8;
                layerByte++;
            }
            var k = Math.min(j, layerBit);
            var current = layerByte < LAYER_BUFFER_SIZE ? layerBuffer[layerByte] & MASK[layerBit] : 0;
            result |= (long) (current >> (layerBit - k)) << (j - k);
            layerBit -= k;
            j -= k;
        }
        return result;
    }

    /**
     * Appends bits to the layer buffer, dropping the ones past its end ({@code sam_putbits2buf}).
     *
     * @param bits the value, in its {@code count} low bits
     * @param count the number of bits, at most 32
     */
    private void putLayerBits(int bits, int count) {
        var j = count;
        while (j > 0) {
            var k = Math.min(j, layerBit);
            var current = bits >>> (j - k);
            if (layerByte < LAYER_BUFFER_SIZE) {
                layerBuffer[layerByte] |= (byte) ((current & MASK[k]) << (layerBit - k));
            }
            layerBit -= k;
            if (layerBit == 0) {
                layerBit = 8;
                layerByte++;
            }
            j -= k;
        }
    }

    /**
     * Resets the stored arithmetic decoder of a layer ({@code sam_initArDecode}).
     *
     * @param layer the layer whose decoder starts over
     */
    private void initArithmetic(int layer) {
        storedValues[layer] = 0;
        storedRanges[layer] = 1;
        storedLengths[layer] = 30;
    }

    /**
     * Stores the working arithmetic decoder into the slot of a layer ({@code sam_storeArDecode}).
     *
     * @param layer the layer whose slot receives the state
     */
    private void storeArithmetic(int layer) {
        storedValues[layer] = value;
        storedRanges[layer] = range;
        storedLengths[layer] = codewordLength;
    }

    /**
     * Makes the stored arithmetic decoder of a layer the working one ({@code sam_setArDecode}).
     *
     * @param layer the layer whose state is restored
     */
    private void setArithmetic(int layer) {
        value = storedValues[layer];
        range = storedRanges[layer];
        codewordLength = storedLengths[layer];
    }

    /**
     * Decodes a symbol of a cumulative frequency model ({@code sam_decode_symbol}) into {@link #symbol}.
     *
     * @param model the cumulative frequencies, out of 16384 and decreasing to 0
     * @return the number of bits the next symbol shifts in
     * @throws MediaException.Malformed if the arithmetic code is corrupt
     */
    private int decodeSymbol(int[] model) {
        shiftIn();
        var cumulative = value / range;
        var sym = 0;
        while (model[sym] > cumulative) {
            sym++;
        }
        symbol = sym;
        value -= range * model[sym];
        range *= sym > 0 ? model[sym - 1] - model[sym] : 16384 - model[sym];
        return narrow();
    }

    /**
     * Decodes a binary symbol ({@code sam_decode_symbol2}) into {@link #symbol}.
     *
     * @param frequency the probability of a 0 out of 16384
     * @return the number of bits the next symbol shifts in
     * @throws MediaException.Malformed if the arithmetic code is corrupt
     */
    private int decodeBinary(int frequency) {
        shiftIn();
        if (frequency * range <= value) {
            symbol = 1;
            value -= range * frequency;
            range *= 16384 - frequency;
        } else {
            symbol = 0;
            range *= frequency;
        }
        return narrow();
    }

    /**
     * Shifts the pending bits into the code value and scales the range to the frequency resolution.
     *
     * @throws MediaException.Malformed if the range is empty
     */
    private void shiftIn() {
        if (codewordLength != 0) {
            range <<= codewordLength;
            value = ((value << codewordLength) | getLayerBits(codewordLength)) & 0xFFFFFFFFL;
        }
        range >>= 14;
        if (range == 0) {
            throw new MediaException.Malformed("BSAC arithmetic code is corrupt");
        }
    }

    /**
     * Computes how many bits the next symbol shifts in.
     *
     * @return the count
     */
    private int narrow() {
        var length = 0;
        while (length < HALF.length && range < HALF[length]) {
            length++;
        }
        codewordLength = length;
        return length;
    }
}
