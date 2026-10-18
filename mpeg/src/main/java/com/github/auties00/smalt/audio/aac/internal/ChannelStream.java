package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Parses one AAC {@code individual_channel_stream} and holds its decoded spectrum.
 *
 * <p>An instance is reused frame after frame. {@link #parse} reads the side information
 * (window sequence, sections, scalefactors, pulses, TNS) and the Huffman-coded spectral data,
 * then inverse-quantizes the coded bands into {@link #spectrum()}. Noise and intensity bands
 * are left at zero for the element decoder to fill; TNS is applied separately by
 * {@link #applyTns()} because coupling may have to happen before it.
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
     * The largest TNS filter order of long windows in the Low Complexity profile.
     */
    private static final int TNS_MAX_ORDER_LONG = 12;

    /**
     * The largest TNS filter order of short windows in the Low Complexity profile.
     */
    private static final int TNS_MAX_ORDER_SHORT = 7;

    /**
     * The stride of the TNS coefficient array: one more than the largest order.
     */
    private static final int TNS_STRIDE = 13;

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
     * Allocates a stream decoder.
     *
     * @param frameLength the number of spectral lines per frame, 1024 or 960
     * @param tableIndex the sampling table index
     * @param kernel the inverse quantization kernel
     */
    public ChannelStream(int frameLength, int tableIndex, AacKernel kernel) {
        this.kernel = kernel;
        this.frameLength = frameLength;
        this.shortLength = frameLength / 8;
        this.tableIndex = tableIndex;
        this.longOffsets = SamplingTables.longOffsets(tableIndex, frameLength);
        this.shortOffsets = SamplingTables.shortOffsets(tableIndex, frameLength);
        this.quant = new int[frameLength];
        this.spectrum = new float[frameLength];
    }

    /**
     * Reads an {@code ics_info} element.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if the reserved bit is set, too many bands are declared,
     * or prediction is signaled
     */
    public void readInfo(AacBitReader reader) {
        if (reader.readFlag()) {
            throw new MediaException.Malformed("AAC ics_info reserved bit is set");
        }
        windowSequence = reader.read(2);
        windowShape = reader.read(1);
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
            if (reader.readFlag()) {
                throw new MediaException.Malformed("AAC Low Complexity stream signals prediction");
            }
            if (maxBands > longOffsets.length - 1) {
                throw new MediaException.Malformed("AAC max_sfb " + maxBands + " exceeds " + (longOffsets.length - 1) + " long bands");
            }
        }
    }

    /**
     * Copies the {@code ics_info} of the other channel of a pair with a common window.
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
    }

    /**
     * Parses an {@code individual_channel_stream} and inverse-quantizes its coded bands.
     *
     * @param reader the bit source
     * @param commonWindow whether {@code ics_info} was already read for a channel pair
     * @param intensityAllowed whether intensity stereo codebooks may appear
     * @throws MediaException.Malformed if the stream is invalid
     * @throws MediaException.Unsupported if gain control data is present
     */
    public void parse(AacBitReader reader, boolean commonWindow, boolean intensityAllowed) {
        globalGain = reader.read(8);
        if (!commonWindow) {
            readInfo(reader);
        }
        readSections(reader, intensityAllowed);
        readScalefactors(reader);
        pulseCount = 0;
        tnsPresent = false;
        if (reader.readFlag()) {
            readPulses(reader);
        }
        if (reader.readFlag()) {
            readTns(reader);
        }
        if (reader.readFlag()) {
            throw new MediaException.Unsupported("aac gain control", "AAC SSR gain control is not supported");
        }
        reader.checkNotOverrun();
        readSpectralData(reader);
        reader.checkNotOverrun();
        dequantize();
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
        for (var group = 0; group < groupCount; group++) {
            var band = 0;
            var base = group * MAX_BANDS;
            while (band < maxBands) {
                var codebook = reader.read(4);
                if (codebook == Codebooks.RESERVED_HCB) {
                    throw new MediaException.Malformed("AAC section uses the reserved codebook 12");
                }
                if (!intensityAllowed && codebook >= Codebooks.INTENSITY_HCB2) {
                    throw new MediaException.Malformed("AAC intensity stereo outside the second channel of a pair");
                }
                var length = 0;
                int increment;
                while ((increment = reader.read(lengthBits)) == escape) {
                    length += escape;
                    if (reader.overrun()) {
                        throw new MediaException.Malformed("AAC section data truncated");
                    }
                }
                length += increment;
                if (band + length > maxBands) {
                    throw new MediaException.Malformed("AAC section exceeds max_sfb");
                }
                Arrays.fill(bandTypes, base + band, base + band + length, codebook);
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
        var maxOrder = shortWindow ? TNS_MAX_ORDER_SHORT : TNS_MAX_ORDER_LONG;
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
                    v0 = v0 < 0 ? -readEscape(reader) : readEscape(reader);
                }
                if (v1 == Codebooks.ESC_FLAG || v1 == -Codebooks.ESC_FLAG) {
                    v1 = v1 < 0 ? -readEscape(reader) : readEscape(reader);
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
     * Reads an escape sequence: a prefix of {@code N} one bits, a zero, and an
     * {@code N + 4}-bit word.
     *
     * @param reader the bit source
     * @return the magnitude, {@code 2^(N+4)} plus the word
     * @throws MediaException.Malformed if the prefix is longer than eight bits
     */
    private static int readEscape(AacBitReader reader) {
        var bits = 4;
        while (reader.readBit() != 0) {
            if (++bits > 12) {
                throw new MediaException.Malformed("AAC escape sequence longer than 13 bits");
            }
        }
        return (1 << bits) + reader.read(bits);
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
        if (!tnsPresent) {
            return;
        }
        var shortWindow = windowCount == 8;
        var offsets = shortWindow ? shortOffsets : longOffsets;
        var bandCount = offsets.length - 1;
        var windowLength = shortWindow ? shortLength : frameLength;
        var limit = Math.min(SamplingTables.tnsMaxBands(tableIndex, shortWindow), maxBands);
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
                filterAllPole(line, increment, size, entry * TNS_STRIDE, order);
            }
        }
    }

    /**
     * Runs one TNS all-pole filter over a run of spectral lines.
     *
     * @param line the first line
     * @param increment 1 to filter upwards, -1 downwards
     * @param size the number of lines
     * @param base the index of {@code a[0]} in {@link #tnsCoefficients}
     * @param order the filter order
     */
    private void filterAllPole(int line, int increment, int size, int base, int order) {
        var state = tnsState;
        Arrays.fill(state, 0, order, 0);
        var lpc = tnsCoefficients;
        for (var m = 0; m < size; m++) {
            var y = spectrum[line];
            for (var i = 0; i < order; i++) {
                y -= lpc[base + i + 1] * state[i];
            }
            for (var i = order - 1; i > 0; i--) {
                state[i] = state[i - 1];
            }
            state[0] = y;
            spectrum[line] = y;
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
