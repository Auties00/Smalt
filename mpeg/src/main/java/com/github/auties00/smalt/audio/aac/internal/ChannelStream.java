package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Parses one AAC {@code individual_channel_stream} and holds its decoded spectrum.
 *
 * <p>An instance is reused frame after frame. {@link #parse} reads the side information
 * (window sequence, prediction or long term prediction data, sections, scalefactors, pulses, TNS,
 * SSR gain control) and the Huffman-coded spectral data, then inverse-quantizes the coded bands
 * into {@link #spectrum()}. Noise and intensity bands are left at zero for the element decoder to
 * fill; TNS is applied separately by {@link #applyTns()} because prediction and coupling may have
 * to happen before it.
 *
 * <p>The object type selects the syntax: AAC Main reads prediction data in {@code ics_info}, AAC
 * LTP, ER AAC LTP and AAC-LD read long term prediction data (AAC-LD with its 10-bit lag that may repeat
 * the previous one), AAC SSR reads gain control data and uses the TNS limits of the PQF filterbank.
 * The error resilient object types (ER AAC LC, ER AAC LTP, AAC-LD, AAC-ELD) read their fields in the
 * order of their error sensitivity categories (ISO/IEC 14496-3 subclause 4.5.2.4, {@code epConfig} 0):
 * in a channel pair with a common window each channel's long term prediction data precedes its global
 * gain, and the TNS data follows the gain control flag, after the reordering lengths and the RVLC
 * codewords (AAC-ELD reads it right after the flag). AAC-ELD codes {@code max_sfb} alone as its
 * {@code ics_info} and has neither pulses nor gain control. The error resilience tools of the
 * configuration replace the section data with virtual codebooks of codebook 11, the scalefactors with
 * reversible variable length codes ({@link ReversibleScalefactors}) and the spectral data with reordered
 * codewords ({@link CodewordReordering}).
 *
 * <p>Band arrays are indexed {@code group * MAX_BANDS + band}. Short-window spectra keep window
 * {@code w} at lines {@code w * shortLength} to {@code (w + 1) * shortLength}.
 */
public final class ChannelStream {
    /**
     * The window sequence of a single long window.
     */
    public static final int ONLY_LONG_SEQUENCE = 0;

    /**
     * The window sequence of the long window that starts a transition to short windows.
     */
    public static final int LONG_START_SEQUENCE = 1;

    /**
     * The window sequence of eight short windows.
     */
    public static final int EIGHT_SHORT_SEQUENCE = 2;

    /**
     * The window sequence of the long window that ends a transition from short windows.
     */
    public static final int LONG_STOP_SEQUENCE = 3;

    /**
     * The stride of band-indexed arrays.
     */
    public static final int MAX_BANDS = 64;

    /**
     * The number of long-window bands long term prediction covers ({@code MAX_LTP_LONG_SFB}).
     */
    public static final int LTP_MAX_BANDS = 40;

    /**
     * The largest TNS filter order of long windows in every object type but AAC Main.
     */
    private static final int TNS_MAX_ORDER_LONG = 12;

    /**
     * The largest TNS filter order of long windows in AAC Main.
     */
    private static final int TNS_MAX_ORDER_LONG_MAIN = 20;

    /**
     * The largest TNS filter order of short windows.
     */
    private static final int TNS_MAX_ORDER_SHORT = 7;

    /**
     * The stride of the TNS coefficient array: one more than the largest order.
     */
    private static final int TNS_STRIDE = TNS_MAX_ORDER_LONG_MAIN + 1;

    /**
     * The LTP coefficient of each {@code ltp_coef} code (ISO/IEC 14496-3 Table 4.152).
     */
    private static final float[] LTP_COEFFICIENTS = {
            0.570829f, 0.696616f, 0.813004f, 0.911304f, 0.984900f, 1.067894f, 1.194601f, 1.369533f
    };

    /**
     * The inverse quantization kernel.
     */
    private final AacKernel kernel;

    /**
     * The number of spectral lines of a long window.
     */
    private final int frameLength;

    /**
     * The number of spectral lines of a short window.
     */
    private final int shortLength;

    /**
     * The sampling table index.
     */
    private final int tableIndex;

    /**
     * The core audio object type, which selects the syntax.
     */
    private final int objectType;

    /**
     * Whether the fields are read in error resilient order.
     */
    private final boolean errorResilient;

    /**
     * Whether the object type is AAC-LD.
     */
    private final boolean lowDelay;

    /**
     * Whether the object type is AAC-ELD.
     */
    private final boolean enhancedLowDelay;

    /**
     * Whether section data codes virtual codebooks ({@code aacSectionDataResilienceFlag}).
     */
    private final boolean virtualCodebooks;

    /**
     * The RVLC scalefactor reader, or {@code null} without {@code aacScalefactorDataResilienceFlag}.
     */
    private final ReversibleScalefactors reversibleScalefactors;

    /**
     * The codeword reordering decoder, or {@code null} without {@code aacSpectralDataResilienceFlag}.
     */
    private final CodewordReordering reordering;

    /**
     * The codebook of each band as coded, virtual codebooks 16 to 31 included.
     */
    private final int[] sectionCodebooks = new int[8 * MAX_BANDS];

    /**
     * The length of the reordered spectral data, in bits.
     */
    private int reorderedBits;

    /**
     * The longest codeword of the reordered spectral data, in bits.
     */
    private int longestCodeword;

    /**
     * The long-window band offsets.
     */
    private final int[] longOffsets;

    /**
     * The short-window band offsets.
     */
    private final int[] shortOffsets;

    /**
     * The window sequence of the current frame.
     */
    private int windowSequence;

    /**
     * The window shape of the current frame.
     */
    private int windowShape;

    /**
     * The number of transmitted bands per window group.
     */
    private int maxBands;

    /**
     * The number of windows: 1 or 8.
     */
    private int windowCount;

    /**
     * The number of window groups.
     */
    private int groupCount;

    /**
     * The number of windows in each group.
     */
    private final int[] groupLengths = new int[8];

    /**
     * The codebook of each band.
     */
    private final int[] bandTypes = new int[8 * MAX_BANDS];

    /**
     * The scalefactor, intensity position or noise energy of each band.
     */
    private final int[] bandValues = new int[8 * MAX_BANDS];

    /**
     * The global gain.
     */
    private int globalGain;

    /**
     * Whether {@code ics_info} sets {@code predictor_data_present}.
     */
    private boolean predictorPresent;

    /**
     * The AAC Main predictor reset group, from 1 to 30, or 0 when no group is reset.
     */
    private int predictorResetGroup;

    /**
     * The AAC Main {@code prediction_used} flag of each band.
     */
    private final boolean[] predictionUsed = new boolean[MAX_BANDS];

    /**
     * Whether long term prediction data is present.
     */
    private boolean ltpPresent;

    /**
     * The long term prediction lag, from 0 to 2047.
     */
    private int ltpLag;

    /**
     * The long term prediction coefficient.
     */
    private float ltpCoefficient;

    /**
     * The {@code ltp_long_used} flag of each band.
     */
    private final boolean[] ltpUsed = new boolean[LTP_MAX_BANDS];

    /**
     * The number of gain-controlled PQF bands ({@code max_band}), 0 without gain control data.
     */
    private int gainBands;

    /**
     * The number of gain changes, indexed {@code band * 8 + window}.
     */
    private final int[] gainCounts = new int[32];

    /**
     * The gain change level exponent ({@code AdjLev(alevcode)}), indexed
     * {@code (band * 8 + window) * 8 + change}.
     */
    private final int[] gainLevels = new int[256];

    /**
     * The gain change location in samples ({@code AdjLoc(aloccode)}), indexed like
     * {@link #gainLevels}.
     */
    private final int[] gainLocations = new int[256];

    /**
     * The number of pulses, 0 when the frame has none.
     */
    private int pulseCount;

    /**
     * The spectral line of each pulse.
     */
    private final int[] pulseOffsets = new int[4];

    /**
     * The amplitude of each pulse.
     */
    private final int[] pulseAmplitudes = new int[4];

    /**
     * Whether the frame carries TNS data.
     */
    private boolean tnsPresent;

    /**
     * The number of TNS filters of each window.
     */
    private final int[] tnsFilterCounts = new int[8];

    /**
     * The band length of each TNS filter, indexed {@code window * 4 + filter}.
     */
    private final int[] tnsLengths = new int[32];

    /**
     * The order of each TNS filter.
     */
    private final int[] tnsOrders = new int[32];

    /**
     * Whether each TNS filter runs downwards in frequency.
     */
    private final boolean[] tnsDownward = new boolean[32];

    /**
     * The LPC coefficients {@code a[1..order]} of each TNS filter, {@link #TNS_STRIDE} per filter.
     */
    private final float[] tnsCoefficients = new float[32 * TNS_STRIDE];

    /**
     * The reflection coefficients of the filter being converted.
     */
    private final float[] parcor = new float[TNS_STRIDE];

    /**
     * The scratch LPC coefficients of the filter being converted.
     */
    private final float[] lpcScratch = new float[TNS_STRIDE];

    /**
     * The TNS filter state.
     */
    private final float[] tnsState = new float[TNS_STRIDE];

    /**
     * The quantized spectral values.
     */
    private final int[] quant;

    /**
     * The spectral values.
     */
    private final float[] spectrum;

    /**
     * Allocates a stream decoder without error resilience tools.
     *
     * @param frameLength the number of spectral lines per frame, 1024 or 960
     * @param tableIndex the sampling table index
     * @param objectType the core audio object type, one of the core {@code AOT_*} constants of
     * {@link AudioSpecificConfig}
     * @param kernel the inverse quantization kernel
     */
    public ChannelStream(int frameLength, int tableIndex, int objectType, AacKernel kernel) {
        this(frameLength, tableIndex, objectType, 0, kernel);
    }

    /**
     * Allocates a stream decoder.
     *
     * @param frameLength the number of spectral lines per frame: 1024 or 960, or 512 or 480 for the low
     * delay object types
     * @param tableIndex the sampling table index
     * @param objectType the core audio object type, one of the core {@code AOT_*} constants of
     * {@link AudioSpecificConfig}
     * @param resilience the error resilience tools, a combination of the flags of
     * {@link AudioSpecificConfig#resilience()}
     * @param kernel the inverse quantization kernel
     */
    public ChannelStream(int frameLength, int tableIndex, int objectType, int resilience, AacKernel kernel) {
        this.kernel = kernel;
        this.frameLength = frameLength;
        this.shortLength = frameLength / 8;
        this.tableIndex = tableIndex;
        this.objectType = objectType;
        this.errorResilient = objectType == AudioSpecificConfig.AOT_ER_LC || objectType == AudioSpecificConfig.AOT_ER_LTP
                || objectType == AudioSpecificConfig.AOT_ER_LD || objectType == AudioSpecificConfig.AOT_ER_ELD;
        this.lowDelay = objectType == AudioSpecificConfig.AOT_ER_LD;
        this.enhancedLowDelay = objectType == AudioSpecificConfig.AOT_ER_ELD;
        this.virtualCodebooks = errorResilient && (resilience & AudioSpecificConfig.VIRTUAL_CODEBOOKS) != 0;
        this.reversibleScalefactors = errorResilient && (resilience & AudioSpecificConfig.RVLC_SCALEFACTORS) != 0
                ? new ReversibleScalefactors() : null;
        this.reordering = errorResilient && (resilience & AudioSpecificConfig.CODEWORD_REORDERING) != 0
                ? new CodewordReordering() : null;
        this.longOffsets = SamplingTables.longOffsets(tableIndex, frameLength);
        this.shortOffsets = SamplingTables.shortOffsets(tableIndex, frameLength);
        this.quant = new int[frameLength];
        this.spectrum = new float[frameLength];
    }

    /**
     * Reads an {@code ics_info} element.
     *
     * <p>For the common {@code ics_info} of a channel pair, {@code partner} is the second channel:
     * its long term prediction data, which this element carries outside the error resilient
     * syntax, is read into it. The partner's other fields are set by {@link #copyInfo}.
     *
     * @param reader the bit source
     * @param partner the second channel of a pair sharing this {@code ics_info}, or {@code null}
     * @throws MediaException.Malformed if the reserved bit is set, too many bands are declared,
     * prediction is signaled outside AAC Main and LTP, the predictor reset group is invalid, or an
     * AAC-LD frame has short windows
     */
    public void readInfo(AacBitReader reader, ChannelStream partner) {
        predictorPresent = false;
        predictorResetGroup = 0;
        ltpPresent = false;
        if (partner != null) {
            partner.ltpPresent = false;
        }
        if (enhancedLowDelay) {
            windowSequence = ONLY_LONG_SEQUENCE;
            windowShape = Windows.SINE;
            maxBands = reader.read(6);
            windowCount = 1;
            groupCount = 1;
            groupLengths[0] = 1;
            if (maxBands > longOffsets.length - 1) {
                throw new MediaException.Malformed("AAC max_sfb " + maxBands + " exceeds " + (longOffsets.length - 1) + " long bands");
            }
            return;
        }
        if (reader.readFlag()) {
            throw new MediaException.Malformed("AAC ics_info reserved bit is set");
        }
        windowSequence = reader.read(2);
        windowShape = reader.read(1);
        if (lowDelay && windowSequence != ONLY_LONG_SEQUENCE) {
            throw new MediaException.Malformed("AAC-LD frame with window sequence " + windowSequence);
        }
        if (windowSequence == EIGHT_SHORT_SEQUENCE) {
            maxBands = reader.read(4);
            var grouping = reader.read(7);
            windowCount = 8;
            groupCount = 1;
            groupLengths[0] = 1;
            for (var i = 6; i >= 0; i--) {
                if ((grouping >>> i & 1) != 0) {
                    groupLengths[groupCount - 1]++;
                } else {
                    groupLengths[groupCount++] = 1;
                }
            }
            if (maxBands > shortOffsets.length - 1) {
                throw new MediaException.Malformed("AAC max_sfb " + maxBands + " exceeds " + (shortOffsets.length - 1) + " short bands");
            }
        } else {
            maxBands = reader.read(6);
            windowCount = 1;
            groupCount = 1;
            groupLengths[0] = 1;
            if (maxBands > longOffsets.length - 1) {
                throw new MediaException.Malformed("AAC max_sfb " + maxBands + " exceeds " + (longOffsets.length - 1) + " long bands");
            }
            predictorPresent = reader.readFlag();
            if (predictorPresent) {
                readPredictorData(reader, partner);
            }
        }
    }

    /**
     * Reads the prediction data of {@code ics_info} after {@code predictor_data_present}.
     *
     * @param reader the bit source
     * @param partner the second channel of a pair sharing the {@code ics_info}, or {@code null}
     * @throws MediaException.Malformed if the object type has no prediction or the reset group is
     * 0 or 31
     */
    private void readPredictorData(AacBitReader reader, ChannelStream partner) {
        switch (objectType) {
            case AudioSpecificConfig.AOT_MAIN -> {
                if (reader.readFlag()) {
                    predictorResetGroup = reader.read(5);
                    if (predictorResetGroup == 0 || predictorResetGroup == 31) {
                        throw new MediaException.Malformed("invalid AAC predictor reset group " + predictorResetGroup);
                    }
                }
                var bands = Math.min(maxBands, SamplingTables.predictionMaxBands(tableIndex));
                for (var band = 0; band < bands; band++) {
                    predictionUsed[band] = reader.readFlag();
                }
                Arrays.fill(predictionUsed, bands, MAX_BANDS, false);
            }
            case AudioSpecificConfig.AOT_LTP, AudioSpecificConfig.AOT_ER_LTP, AudioSpecificConfig.AOT_ER_LD -> {
                if (errorResilient && partner != null) {
                    return;
                }
                readLtp(reader, maxBands);
                if (partner != null) {
                    partner.readLtp(reader, maxBands);
                }
            }
            default -> throw new MediaException.Malformed("AAC stream of object type " + objectType + " signals prediction");
        }
    }

    /**
     * Reads {@code ltp_data_present} and, when set, the {@code ltp_data} of a long window.
     *
     * <p>AAC-LD codes a 10-bit lag behind {@code ltp_lag_update}; without it the lag of the previous
     * frame applies.
     *
     * @param reader the bit source
     * @param bands the {@code max_sfb} of the window
     */
    private void readLtp(AacBitReader reader, int bands) {
        ltpPresent = reader.readFlag();
        if (!ltpPresent) {
            return;
        }
        if (lowDelay) {
            if (reader.readFlag()) {
                ltpLag = reader.read(10);
            }
        } else {
            ltpLag = reader.read(11);
        }
        ltpCoefficient = LTP_COEFFICIENTS[reader.read(3)];
        var used = Math.min(bands, LTP_MAX_BANDS);
        for (var band = 0; band < used; band++) {
            ltpUsed[band] = reader.readFlag();
        }
        Arrays.fill(ltpUsed, used, LTP_MAX_BANDS, false);
    }

    /**
     * Copies the {@code ics_info} of the other channel of a pair with a common window, except the
     * long term prediction data, which each channel has its own.
     *
     * @param other the channel whose information is shared
     */
    public void copyInfo(ChannelStream other) {
        windowSequence = other.windowSequence;
        windowShape = other.windowShape;
        maxBands = other.maxBands;
        windowCount = other.windowCount;
        groupCount = other.groupCount;
        System.arraycopy(other.groupLengths, 0, groupLengths, 0, 8);
        predictorPresent = other.predictorPresent;
        predictorResetGroup = other.predictorResetGroup;
        System.arraycopy(other.predictionUsed, 0, predictionUsed, 0, MAX_BANDS);
    }

    /**
     * Parses an {@code individual_channel_stream} and inverse-quantizes its coded bands.
     *
     * @param reader the bit source
     * @param commonWindow whether {@code ics_info} was already read for a channel pair
     * @param intensityAllowed whether intensity stereo codebooks may appear
     * @throws MediaException.Malformed if the stream is invalid, including gain control data
     * outside AAC SSR
     */
    public void parse(AacBitReader reader, boolean commonWindow, boolean intensityAllowed) {
        parse(reader, reader, reader, reader, commonWindow, intensityAllowed);
    }

    /**
     * Parses an error resilient {@code individual_channel_stream} whose error sensitivity categories
     * come from separate sources, as {@code epConfig} 1 stores them, and inverse-quantizes its coded
     * bands.
     *
     * <p>The side information (long term prediction, global gain, {@code ics_info}, sections,
     * scalefactors or the RVLC side information, pulses, the TNS and gain control flags and the
     * reordering lengths) comes from {@code main}, the RVLC codewords and escapes from
     * {@code reversible}, the TNS data from {@code tns} and the spectral data from {@code spectral}.
     * With one source for every category this reads the payload of {@code epConfig} 0.
     *
     * @param main the source of the side information
     * @param reversible the source of the RVLC codewords
     * @param tns the source of the TNS data
     * @param spectral the source of the spectral data
     * @param commonWindow whether {@code ics_info} was already read for a channel pair
     * @param intensityAllowed whether intensity stereo codebooks may appear
     * @throws MediaException.Malformed if the stream is invalid, including gain control data
     * outside AAC SSR
     */
    public void parse(AacBitReader main, AacBitReader reversible, AacBitReader tns, AacBitReader spectral,
                      boolean commonWindow, boolean intensityAllowed) {
        if (errorResilient && commonWindow && predictorPresent) {
            readLtp(main, maxBands);
        }
        globalGain = main.read(8);
        if (!commonWindow) {
            readInfo(main, null);
        }
        readSections(main, intensityAllowed);
        if (reversibleScalefactors != null) {
            reversibleScalefactors.readSideInfo(main, windowCount == 8, hasNoiseBands());
        } else {
            readScalefactors(main);
        }
        pulseCount = 0;
        tnsPresent = false;
        if (!enhancedLowDelay && main.readFlag()) {
            readPulses(main);
        }
        var tnsData = main.readFlag();
        if (tnsData && !errorResilient) {
            readTns(main);
        }
        gainBands = 0;
        if (!enhancedLowDelay && main.readFlag()) {
            if (objectType != AudioSpecificConfig.AOT_SSR) {
                throw new MediaException.Malformed("AAC gain control data outside AAC SSR");
            }
            readGainControl(main);
        }
        if (errorResilient) {
            if (tnsData && enhancedLowDelay) {
                readTns(tns);
            }
            if (reordering != null) {
                reorderedBits = main.read(14);
                longestCodeword = main.read(6);
            }
            main.checkNotOverrun();
            if (reversibleScalefactors != null) {
                reversibleScalefactors.decode(reversible, globalGain, bandTypes, bandValues, groupCount, maxBands);
                reversible.checkNotOverrun();
            }
            if (tnsData && !enhancedLowDelay) {
                readTns(tns);
            }
            tns.checkNotOverrun();
        }
        main.checkNotOverrun();
        if (reordering != null) {
            reordering.decode(spectral, reorderedBits, longestCodeword, sectionCodebooks, bandOffsets(), maxBands,
                    groupLengths, groupCount, windowCount, windowLength(), quant);
            applyPulses();
        } else {
            readSpectralData(spectral);
        }
        spectral.checkNotOverrun();
        dequantize();
    }

    /**
     * Returns whether a transmitted band uses the noise codebook.
     *
     * @return {@code true} when the stream has a noise band
     */
    private boolean hasNoiseBands() {
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                if (bandTypes[group * MAX_BANDS + band] == Codebooks.NOISE_HCB) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads the section data: the codebook of every band.
     *
     * @param reader the bit source
     * @param intensityAllowed whether intensity codebooks may appear
     * @throws MediaException.Malformed if a section is invalid
     */
    private void readSections(AacBitReader reader, boolean intensityAllowed) {
        var lengthBits = windowCount == 8 ? 3 : 5;
        var escape = (1 << lengthBits) - 1;
        var codebookBits = virtualCodebooks ? 5 : 4;
        for (var group = 0; group < groupCount; group++) {
            var band = 0;
            var base = group * MAX_BANDS;
            while (band < maxBands) {
                var codebook = reader.read(codebookBits);
                if (codebook == Codebooks.RESERVED_HCB) {
                    throw new MediaException.Malformed("AAC section uses the reserved codebook 12");
                }
                if (!intensityAllowed && (codebook == Codebooks.INTENSITY_HCB2 || codebook == Codebooks.INTENSITY_HCB)) {
                    throw new MediaException.Malformed("AAC intensity stereo outside the second channel of a pair");
                }
                var length = 0;
                if (virtualCodebooks && (codebook == Codebooks.ESC_HCB || codebook >= 16)) {
                    length = 1;
                } else {
                    int increment;
                    while ((increment = reader.read(lengthBits)) == escape) {
                        length += escape;
                        if (reader.overrun()) {
                            throw new MediaException.Malformed("AAC section data truncated");
                        }
                    }
                    length += increment;
                }
                if (band + length > maxBands) {
                    throw new MediaException.Malformed("AAC section exceeds max_sfb");
                }
                Arrays.fill(sectionCodebooks, base + band, base + band + length, codebook);
                Arrays.fill(bandTypes, base + band, base + band + length, codebook >= 16 ? Codebooks.ESC_HCB : codebook);
                band += length;
                if (reader.overrun()) {
                    throw new MediaException.Malformed("AAC section data truncated");
                }
            }
        }
    }

    /**
     * Reads the scalefactors, intensity positions and noise energies.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if a value leaves its range
     */
    private void readScalefactors(AacBitReader reader) {
        var scalefactor = globalGain;
        var position = 0;
        var noiseEnergy = globalGain - 90;
        var firstNoise = true;
        for (var group = 0; group < groupCount; group++) {
            var base = group * MAX_BANDS;
            for (var band = 0; band < maxBands; band++) {
                var index = base + band;
                switch (bandTypes[index]) {
                    case Codebooks.ZERO_HCB -> bandValues[index] = 0;
                    case Codebooks.NOISE_HCB -> {
                        if (firstNoise) {
                            firstNoise = false;
                            noiseEnergy += reader.read(9) - 256;
                        } else {
                            noiseEnergy += Codebooks.decodeScalefactor(reader);
                        }
                        if (noiseEnergy < Dequantizer.MIN_EXPONENT || noiseEnergy > Dequantizer.MAX_EXPONENT) {
                            throw new MediaException.Malformed("AAC noise energy " + noiseEnergy + " out of range");
                        }
                        bandValues[index] = noiseEnergy;
                    }
                    case Codebooks.INTENSITY_HCB, Codebooks.INTENSITY_HCB2 -> {
                        position += Codebooks.decodeScalefactor(reader);
                        if (-position < Dequantizer.MIN_EXPONENT || -position > Dequantizer.MAX_EXPONENT) {
                            throw new MediaException.Malformed("AAC intensity position " + position + " out of range");
                        }
                        bandValues[index] = position;
                    }
                    default -> {
                        scalefactor += Codebooks.decodeScalefactor(reader);
                        if (scalefactor < 0 || scalefactor > 255) {
                            throw new MediaException.Malformed("AAC scalefactor " + scalefactor + " out of range");
                        }
                        bandValues[index] = scalefactor;
                    }
                }
            }
        }
    }

    /**
     * Reads the pulse data of a long window.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if pulses appear in short windows or leave the spectrum
     */
    private void readPulses(AacBitReader reader) {
        if (windowCount == 8) {
            throw new MediaException.Malformed("AAC pulse data in short windows");
        }
        pulseCount = reader.read(2) + 1;
        var startBand = reader.read(6);
        if (startBand >= longOffsets.length - 1) {
            throw new MediaException.Malformed("AAC pulse start band " + startBand + " out of range");
        }
        var line = longOffsets[startBand];
        for (var i = 0; i < pulseCount; i++) {
            line += reader.read(5);
            if (line >= frameLength) {
                throw new MediaException.Malformed("AAC pulse beyond the spectrum");
            }
            pulseOffsets[i] = line;
            pulseAmplitudes[i] = reader.read(4);
        }
    }

    /**
     * Reads the TNS data and converts every filter to LPC coefficients.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if a filter order exceeds the profile limit
     */
    private void readTns(AacBitReader reader) {
        tnsPresent = true;
        var shortWindow = windowCount == 8;
        var maxOrder = shortWindow ? TNS_MAX_ORDER_SHORT
                : objectType == AudioSpecificConfig.AOT_MAIN ? TNS_MAX_ORDER_LONG_MAIN : TNS_MAX_ORDER_LONG;
        for (var window = 0; window < windowCount; window++) {
            var filters = reader.read(shortWindow ? 1 : 2);
            tnsFilterCounts[window] = filters;
            if (filters == 0) {
                continue;
            }
            var resolution = reader.read(1) + 3;
            for (var filter = 0; filter < filters; filter++) {
                var entry = window * 4 + filter;
                tnsLengths[entry] = reader.read(shortWindow ? 4 : 6);
                var order = reader.read(shortWindow ? 3 : 5);
                if (order > maxOrder) {
                    throw new MediaException.Malformed("AAC TNS filter order " + order + " exceeds " + maxOrder);
                }
                tnsOrders[entry] = order;
                if (order == 0) {
                    continue;
                }
                tnsDownward[entry] = reader.readFlag();
                var bits = resolution - reader.read(1);
                var positive = ((1 << (resolution - 1)) - 0.5) / (Math.PI / 2);
                var negative = ((1 << (resolution - 1)) + 0.5) / (Math.PI / 2);
                for (var i = 0; i < order; i++) {
                    var coefficient = reader.readSigned(bits);
                    parcor[i] = (float) Math.sin(coefficient / (coefficient >= 0 ? positive : negative));
                }
                var lpc = tnsCoefficients;
                var base = entry * TNS_STRIDE;
                lpc[base] = 1;
                for (var m = 1; m <= order; m++) {
                    for (var i = 1; i < m; i++) {
                        lpcScratch[i] = lpc[base + i] + parcor[m - 1] * lpc[base + m - i];
                    }
                    for (var i = 1; i < m; i++) {
                        lpc[base + i] = lpcScratch[i];
                    }
                    lpc[base + m] = parcor[m - 1];
                }
            }
        }
    }

    /**
     * Reads the SSR gain control data of every gain-controlled PQF band.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if a gain change location is reserved or the locations of a
     * window do not increase
     */
    private void readGainControl(AacBitReader reader) {
        gainBands = reader.read(2);
        var windows = switch (windowSequence) {
            case ONLY_LONG_SEQUENCE -> 1;
            case EIGHT_SHORT_SEQUENCE -> 8;
            default -> 2;
        };
        for (var band = 1; band <= gainBands; band++) {
            for (var window = 0; window < windows; window++) {
                var entry = band * 8 + window;
                var count = reader.read(3);
                gainCounts[entry] = count;
                var bits = gainLocationBits(window);
                var previous = -1;
                for (var change = 0; change < count; change++) {
                    gainLevels[entry * 8 + change] = reader.read(4) - 4;
                    var code = reader.read(bits);
                    if (bits == 4 && code >= 14) {
                        throw new MediaException.Malformed("reserved AAC gain control location " + code);
                    }
                    var location = 8 * code;
                    if (location <= previous) {
                        throw new MediaException.Malformed("AAC gain control locations do not increase");
                    }
                    gainLocations[entry * 8 + change] = location;
                    previous = location;
                }
            }
        }
    }

    /**
     * Returns the width of {@code aloccode} in a window of the current window sequence.
     *
     * @param window the window index
     * @return 2, 4 or 5 bits
     */
    private int gainLocationBits(int window) {
        return switch (windowSequence) {
            case ONLY_LONG_SEQUENCE -> 5;
            case EIGHT_SHORT_SEQUENCE -> 2;
            case LONG_START_SEQUENCE -> window == 0 ? 4 : 2;
            default -> window == 0 ? 4 : 5;
        };
    }

    /**
     * Reads the Huffman-coded spectral data into {@link #quant}.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if an escape sequence is too long
     */
    private void readSpectralData(AacBitReader reader) {
        var offsets = windowCount == 8 ? shortOffsets : longOffsets;
        var windowLength = windowCount == 8 ? shortLength : frameLength;
        var firstWindow = 0;
        for (var group = 0; group < groupCount; group++) {
            var base = group * MAX_BANDS;
            var lastWindow = firstWindow + groupLengths[group];
            for (var band = 0; band < maxBands; band++) {
                var codebook = bandTypes[base + band];
                if (codebook == Codebooks.ZERO_HCB || codebook > Codebooks.ESC_HCB) {
                    continue;
                }
                var table = Codebooks.spectrumTable(codebook);
                for (var window = firstWindow; window < lastWindow; window++) {
                    var start = window * windowLength + offsets[band];
                    var end = window * windowLength + offsets[band + 1];
                    decodeRun(reader, table, codebook, start, end);
                }
            }
            firstWindow = lastWindow;
        }
        applyPulses();
    }

    /**
     * Adds the pulses to the quantized values.
     */
    private void applyPulses() {
        for (var i = 0; i < pulseCount; i++) {
            var line = pulseOffsets[i];
            quant[line] += quant[line] > 0 ? pulseAmplitudes[i] : -pulseAmplitudes[i];
        }
    }

    /**
     * Decodes the Huffman-coded values of one band of one window.
     *
     * <p>The reader's bit cache is held in local variables for the whole run and written back
     * only around the rare slow paths (a refill near the end of the input, escape sequences),
     * which keeps the codeword-to-codeword dependency chain in registers.
     *
     * @param reader the bit source
     * @param table the decoding table of the codebook
     * @param codebook the codebook, from 1 to 11
     * @param start the first line
     * @param end the line after the last
     * @throws MediaException.Malformed if an escape prefix is longer than eight bits
     */
    private void decodeRun(AacBitReader reader, int[] table, int codebook, int start, int end) {
        var values = quant;
        var data = reader.data;
        var limit = reader.end - 8;
        var cache = reader.cache;
        var count = reader.count;
        var next = reader.next;
        var quads = codebook <= 4;
        var step = quads ? 4 : 2;
        var unsigned = Codebooks.isUnsigned(codebook);
        var escape = codebook == Codebooks.ESC_HCB;
        for (var k = start; k < end; k += step) {
            if (count < 32) {
                if (next <= limit) {
                    cache |= ((long) AacBitReader.LONG_BE.get(data, next)) >>> count;
                    var bytes = (63 - count) >>> 3;
                    next += bytes;
                    count += bytes << 3;
                } else {
                    reader.cache = cache;
                    reader.count = count;
                    reader.next = next;
                    reader.ensure(32);
                    cache = reader.cache;
                    count = reader.count;
                    next = reader.next;
                }
            }
            var entry = table[(int) (cache >>> (64 - Codebooks.ROOT_BITS))];
            if (entry < 0) {
                var subBits = entry & 31;
                var base = (entry & 0x7FFFFFFF) >>> 5;
                entry = table[base + ((int) (cache >>> (64 - Codebooks.ROOT_BITS - subBits)) & ((1 << subBits) - 1))];
            }
            var length = entry & 31;
            cache <<= length;
            count -= length;
            var payload = entry >>> 5;
            if (!unsigned) {
                values[k] = payload << 27 >> 27;
                values[k + 1] = payload << 22 >> 27;
                if (quads) {
                    values[k + 2] = payload << 17 >> 27;
                    values[k + 3] = payload << 12 >> 27;
                }
                continue;
            }
            var nonZero = payload >>> 20;
            var signs = (int) (cache >>> 32) & ~(-1 >>> nonZero);
            cache <<= nonZero;
            count -= nonZero;
            var v0 = payload & 31;
            var v1 = payload >>> 5 & 31;
            var mask = -((v0 | -v0) >>> 31);
            var sign = (signs >> 31) & mask;
            v0 = (v0 ^ sign) - sign;
            signs <<= mask & 1;
            mask = -((v1 | -v1) >>> 31);
            sign = (signs >> 31) & mask;
            v1 = (v1 ^ sign) - sign;
            signs <<= mask & 1;
            if (quads) {
                var v2 = payload >>> 10 & 31;
                var v3 = payload >>> 15 & 31;
                mask = -((v2 | -v2) >>> 31);
                sign = (signs >> 31) & mask;
                v2 = (v2 ^ sign) - sign;
                signs <<= mask & 1;
                sign = signs >> 31 & -((v3 | -v3) >>> 31);
                v3 = (v3 ^ sign) - sign;
                values[k + 2] = v2;
                values[k + 3] = v3;
            } else if (escape && (v0 == Codebooks.ESC_FLAG || v0 == -Codebooks.ESC_FLAG
                    || v1 == Codebooks.ESC_FLAG || v1 == -Codebooks.ESC_FLAG)) {
                reader.cache = cache;
                reader.count = count;
                reader.next = next;
                if (v0 == Codebooks.ESC_FLAG || v0 == -Codebooks.ESC_FLAG) {
                    v0 = v0 < 0 ? -Codebooks.readEscape(reader) : Codebooks.readEscape(reader);
                }
                if (v1 == Codebooks.ESC_FLAG || v1 == -Codebooks.ESC_FLAG) {
                    v1 = v1 < 0 ? -Codebooks.readEscape(reader) : Codebooks.readEscape(reader);
                }
                cache = reader.cache;
                count = reader.count;
                next = reader.next;
            }
            values[k] = v0;
            values[k + 1] = v1;
        }
        reader.cache = cache;
        reader.count = count;
        reader.next = next;
    }

    /**
     * Inverse-quantizes every band coded with a spectrum codebook.
     */
    private void dequantize() {
        Arrays.fill(spectrum, 0, frameLength, 0);
        var offsets = windowCount == 8 ? shortOffsets : longOffsets;
        var windowLength = windowCount == 8 ? shortLength : frameLength;
        var firstWindow = 0;
        for (var group = 0; group < groupCount; group++) {
            var base = group * MAX_BANDS;
            var lastWindow = firstWindow + groupLengths[group];
            for (var band = 0; band < maxBands; band++) {
                var codebook = bandTypes[base + band];
                if (codebook == Codebooks.ZERO_HCB || codebook > Codebooks.ESC_HCB) {
                    continue;
                }
                var gain = Dequantizer.scalefactorGain(bandValues[base + band]);
                for (var window = firstWindow; window < lastWindow; window++) {
                    var lineBase = window * windowLength;
                    kernel.dequantize(quant, spectrum, lineBase + offsets[band], lineBase + offsets[band + 1], gain);
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Applies the TNS synthesis filters to the spectrum, if the frame carries TNS data.
     */
    public void applyTns() {
        if (tnsPresent) {
            filterTns(spectrum, false);
        }
    }

    /**
     * Applies the TNS analysis filters of the current frame to a spectrum of the same window
     * structure, if the frame carries TNS data.
     *
     * <p>The analysis filters invert the synthesis filters of {@link #applyTns()}; long term
     * prediction runs its estimated spectrum through them before adding it.
     *
     * @param target the spectrum to filter in place
     */
    public void applyTnsAnalysis(float[] target) {
        if (tnsPresent) {
            filterTns(target, true);
        }
    }

    /**
     * Runs every TNS filter of the current frame over a spectrum.
     *
     * @param target the spectrum to filter in place
     * @param analysis whether to run the all-zero analysis filters instead of the all-pole
     * synthesis filters
     */
    private void filterTns(float[] target, boolean analysis) {
        var shortWindow = windowCount == 8;
        var offsets = shortWindow ? shortOffsets : longOffsets;
        var bandCount = offsets.length - 1;
        var windowLength = shortWindow ? shortLength : frameLength;
        var bandLimit = objectType == AudioSpecificConfig.AOT_SSR
                ? SamplingTables.tnsMaxBandsPqf(tableIndex, shortWindow)
                : lowDelay || enhancedLowDelay ? SamplingTables.tnsMaxBandsLowDelay(tableIndex, frameLength)
                : SamplingTables.tnsMaxBands(tableIndex, shortWindow);
        var limit = Math.min(bandLimit, maxBands);
        for (var window = 0; window < windowCount; window++) {
            var bottom = bandCount;
            for (var filter = 0; filter < tnsFilterCounts[window]; filter++) {
                var entry = window * 4 + filter;
                var top = bottom;
                bottom = Math.max(top - tnsLengths[entry], 0);
                var order = tnsOrders[entry];
                if (order == 0) {
                    continue;
                }
                var start = offsets[Math.min(bottom, limit)];
                var end = offsets[Math.min(top, limit)];
                var size = end - start;
                if (size <= 0) {
                    continue;
                }
                var increment = 1;
                var line = window * windowLength + start;
                if (tnsDownward[entry]) {
                    increment = -1;
                    line = window * windowLength + end - 1;
                }
                if (analysis) {
                    filterAllZero(target, line, increment, size, entry * TNS_STRIDE, order);
                } else {
                    filterAllPole(target, line, increment, size, entry * TNS_STRIDE, order);
                }
            }
        }
    }

    /**
     * Runs one TNS all-pole filter over a run of spectral lines.
     *
     * @param target the spectrum
     * @param line the first line
     * @param increment 1 to filter upwards, -1 downwards
     * @param size the number of lines
     * @param base the index of {@code a[0]} in {@link #tnsCoefficients}
     * @param order the filter order
     */
    private void filterAllPole(float[] target, int line, int increment, int size, int base, int order) {
        var state = tnsState;
        Arrays.fill(state, 0, order, 0);
        var lpc = tnsCoefficients;
        for (var m = 0; m < size; m++) {
            var y = target[line];
            for (var i = 0; i < order; i++) {
                y -= lpc[base + i + 1] * state[i];
            }
            for (var i = order - 1; i > 0; i--) {
                state[i] = state[i - 1];
            }
            state[0] = y;
            target[line] = y;
            line += increment;
        }
    }

    /**
     * Runs one TNS all-zero (analysis) filter, {@code y(n) = x(n) + a[1] x(n-1) + ... + a[order] x(n-order)},
     * over a run of spectral lines.
     *
     * @param target the spectrum
     * @param line the first line
     * @param increment 1 to filter upwards, -1 downwards
     * @param size the number of lines
     * @param base the index of {@code a[0]} in {@link #tnsCoefficients}
     * @param order the filter order
     */
    private void filterAllZero(float[] target, int line, int increment, int size, int base, int order) {
        var state = tnsState;
        Arrays.fill(state, 0, order, 0);
        var lpc = tnsCoefficients;
        for (var m = 0; m < size; m++) {
            var x = target[line];
            var y = x;
            for (var i = 0; i < order; i++) {
                y += lpc[base + i + 1] * state[i];
            }
            for (var i = order - 1; i > 0; i--) {
                state[i] = state[i - 1];
            }
            state[0] = x;
            target[line] = y;
            line += increment;
        }
    }

    /**
     * Returns the window sequence.
     *
     * @return one of the {@code *_SEQUENCE} constants
     */
    public int windowSequence() {
        return windowSequence;
    }

    /**
     * Returns whether {@code ics_info} sets {@code predictor_data_present}.
     *
     * @return {@code true} when prediction data is present in a long window
     */
    public boolean predictorPresent() {
        return predictorPresent;
    }

    /**
     * Returns the AAC Main predictor reset group of the frame.
     *
     * @return the group from 1 to 30, or 0 when no group is reset
     */
    public int predictorResetGroup() {
        return predictorResetGroup;
    }

    /**
     * Returns whether AAC Main prediction is enabled in a band.
     *
     * @param band the long-window band
     * @return {@code prediction_used}, {@code false} beyond {@code max_sfb}
     */
    public boolean predictionUsed(int band) {
        return predictionUsed[band];
    }

    /**
     * Returns whether long term prediction data is present.
     *
     * @return {@code ltp_data_present}
     */
    public boolean ltpPresent() {
        return ltpPresent;
    }

    /**
     * Returns the long term prediction lag.
     *
     * @return the lag in samples, from 0 to 2047
     */
    public int ltpLag() {
        return ltpLag;
    }

    /**
     * Returns the long term prediction coefficient.
     *
     * @return the coefficient of {@code ltp_coef}
     */
    public float ltpCoefficient() {
        return ltpCoefficient;
    }

    /**
     * Returns whether long term prediction is enabled in a band.
     *
     * @param band the long-window band, below {@link #LTP_MAX_BANDS}
     * @return {@code ltp_long_used}, {@code false} beyond {@code max_sfb}
     */
    public boolean ltpUsed(int band) {
        return ltpUsed[band];
    }

    /**
     * Returns the number of gain changes of a PQF band in a window.
     *
     * @param band the PQF band, from 1 to 3
     * @param window the window index
     * @return {@code adjust_num}, 0 for a band without gain control
     */
    public int gainChanges(int band, int window) {
        return band <= gainBands ? gainCounts[band * 8 + window] : 0;
    }

    /**
     * Returns the level exponent of a gain change, the gain being {@code 2^exponent}.
     *
     * @param band the PQF band, from 1 to 3
     * @param window the window index
     * @param change the change index, below {@link #gainChanges}
     * @return {@code AdjLev(alevcode)}, from -4 to 11
     */
    public int gainLevel(int band, int window, int change) {
        return gainLevels[(band * 8 + window) * 8 + change];
    }

    /**
     * Returns the location of a gain change.
     *
     * @param band the PQF band, from 1 to 3
     * @param window the window index
     * @param change the change index, below {@link #gainChanges}
     * @return {@code AdjLoc(aloccode)}, in band samples
     */
    public int gainLocation(int band, int window, int change) {
        return gainLocations[(band * 8 + window) * 8 + change];
    }

    /**
     * Returns the window shape.
     *
     * @return {@link Windows#SINE} or {@link Windows#KBD}
     */
    public int windowShape() {
        return windowShape;
    }

    /**
     * Returns the number of transmitted bands per group.
     *
     * @return {@code max_sfb}
     */
    public int maxBands() {
        return maxBands;
    }

    /**
     * Returns the number of window groups.
     *
     * @return the group count
     */
    public int groupCount() {
        return groupCount;
    }

    /**
     * Returns the number of windows of a group.
     *
     * @param group the group index
     * @return the window count
     */
    public int groupLength(int group) {
        return groupLengths[group];
    }

    /**
     * Returns the codebook of a band.
     *
     * @param index the band index, {@code group * MAX_BANDS + band}
     * @return the codebook
     */
    public int bandType(int index) {
        return bandTypes[index];
    }

    /**
     * Returns the scalefactor, intensity position or noise energy of a band.
     *
     * @param index the band index, {@code group * MAX_BANDS + band}
     * @return the value
     */
    public int bandValue(int index) {
        return bandValues[index];
    }

    /**
     * Returns the band offsets of the current window size, including the end offset.
     *
     * @return the offsets; shared and not to be modified
     */
    public int[] bandOffsets() {
        return windowCount == 8 ? shortOffsets : longOffsets;
    }

    /**
     * Returns the number of spectral lines of one window of the current frame.
     *
     * @return the frame length for long windows, an eighth of it for short windows
     */
    public int windowLength() {
        return windowCount == 8 ? shortLength : frameLength;
    }

    /**
     * Returns the spectrum.
     *
     * @return the spectral values; the array is owned by this stream
     */
    public float[] spectrum() {
        return spectrum;
    }

    /**
     * Returns whether the window structure matches that of another stream.
     *
     * @param other the other stream
     * @return {@code true} when both use the same window sequence and grouping
     */
    public boolean sameWindows(ChannelStream other) {
        if (windowCount != other.windowCount || groupCount != other.groupCount) {
            return false;
        }
        for (var group = 0; group < groupCount; group++) {
            if (groupLengths[group] != other.groupLengths[group]) {
                return false;
            }
        }
        return true;
    }
}
