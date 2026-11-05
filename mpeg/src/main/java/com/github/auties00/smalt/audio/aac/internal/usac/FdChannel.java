/* Ports the ISO/IEC 23003-3:2019 reference software (usac_fd_dec.c, usac_arith_dec.c applyScaleFactorsAndNf,
   usac_tns.c, huffdec2.c hufffac and getmask). Copyright Sony Corporation, Fraunhofer IIS, VoiceAge Corp. and
   ISO/IEC 2008; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003).
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.Codebooks;

import java.util.Arrays;

/**
 * Parses and dequantizes the frequency domain channel stream of one USAC channel ({@code fd_channel_stream},
 * ISO/IEC 23003-3 subclause 5.3.2) and holds the window state the synthesis needs.
 *
 * <p>Instances are not thread-safe.
 */
final class FdChannel {
    /**
     * The window sequence of a long window.
     */
    static final int ONLY_LONG = 0;

    /**
     * The window sequence of a start window.
     */
    static final int LONG_START = 1;

    /**
     * The window sequence of eight short windows.
     */
    static final int EIGHT_SHORT = 2;

    /**
     * The window sequence of a stop window.
     */
    static final int LONG_STOP = 3;

    /**
     * The window sequence of a stop-start window, which a coded start window becomes after short windows.
     */
    static final int STOP_START = 4;

    /**
     * The scalefactor offset ({@code SF_OFFSET}).
     */
    private static final int SF_OFFSET = 100;

    /**
     * The most scalefactor bands of a long window.
     */
    static final int MAX_BANDS = 64;

    private final int frameLength;
    private final int tableIndex;
    private final int[] longOffsets;
    private final int[] shortOffsets;

    int windowSequence;
    int windowShape;
    int maxSfb;
    int groupCount;
    final int[] groupLength = new int[8];

    /**
     * The scalefactor of each window and band, {@code window * MAX_BANDS + band}.
     */
    final int[] scalefactors = new int[8 * MAX_BANDS];

    int globalGain;
    int noiseLevel;

    final int[] tnsFilters = new int[8];
    final int[] tnsResolution = new int[8];
    final int[][] tnsStart = new int[8][4];
    final int[][] tnsStop = new int[8][4];
    final int[][] tnsOrder = new int[8][4];
    final int[][] tnsDirection = new int[8][4];
    final int[][][] tnsCoefficients = new int[8][4][32];

    /**
     * The quantized lines of the frame, window after window.
     */
    final int[] quantized = new int[1024];

    /**
     * The dequantized spectrum, window after window.
     */
    final float[] spectrum = new float[1024];

    /**
     * Whether forward aliasing cancellation data follows the spectrum.
     */
    boolean facPresent;

    /**
     * The decoded FAC data: the gain index then the quantized coefficients.
     */
    final int[] facData = new int[1 + 128];

    boolean twPresent;
    final int[] twRatio = new int[16];

    private final float[] tnsBuffer = new float[1024];
    private final float[] lpc = new float[17];
    private final float[] lpcScratch = new float[17];
    private final float[] reflection = new float[17];
    private final float[] state = new float[16];

    /**
     * Constructs the stream of a channel.
     *
     * @param frameLength the core frame length, 768 or 1024
     * @param tableIndex the sampling table index of the band tables
     */
    FdChannel(int frameLength, int tableIndex) {
        this.frameLength = frameLength;
        this.tableIndex = tableIndex;
        this.longOffsets = UsacTables.longOffsets(tableIndex, frameLength);
        this.shortOffsets = UsacTables.shortOffsets(tableIndex, frameLength);
    }

    /**
     * {@return whether the frame has eight short windows}
     */
    boolean shortWindows() {
        return windowSequence == EIGHT_SHORT;
    }

    /**
     * {@return the number of windows of the frame}
     */
    int windowCount() {
        return shortWindows() ? 8 : 1;
    }

    /**
     * {@return the number of lines of each window}
     */
    int windowLength() {
        return shortWindows() ? frameLength / 8 : frameLength;
    }

    /**
     * {@return the band offsets of the frame's windows}
     */
    int[] offsets() {
        return shortWindows() ? shortOffsets : longOffsets;
    }

    /**
     * {@return the number of bands of the frame's windows}
     */
    int bandCount() {
        return offsets().length - 1;
    }

    /**
     * Reads {@code ics_info} and maps the coded window sequence.
     *
     * @param reader the bit source
     * @param lastSequence the window sequence of the previous frame
     * @throws MediaException.Malformed if {@code max_sfb} exceeds the bands of the window
     */
    void readInfo(AacBitReader reader, int lastSequence) {
        var coded = reader.read(2);
        windowShape = reader.read(1);
        if (coded == EIGHT_SHORT) {
            maxSfb = reader.read(4);
            var grouping = reader.read(7);
            groupCount = 0;
            var length = 1;
            for (var i = 0; i < 7; i++) {
                if ((grouping >> (6 - i) & 1) != 0) {
                    length++;
                } else {
                    groupLength[groupCount++] = length;
                    length = 1;
                }
            }
            groupLength[groupCount++] = length;
        } else {
            maxSfb = reader.read(6);
            groupCount = 1;
            groupLength[0] = 1;
        }
        windowSequence = mapSequence(coded, lastSequence);
        if (maxSfb > bandCount()) {
            throw new MediaException.Malformed("USAC max_sfb " + maxSfb + " exceeds " + bandCount() + " bands");
        }
    }

    /**
     * Copies the window information of the other channel of a common window.
     *
     * @param other the first channel of the pair
     * @param sfb the {@code max_sfb} of this channel
     */
    void copyInfo(FdChannel other, int sfb) {
        windowSequence = other.windowSequence;
        windowShape = other.windowShape;
        groupCount = other.groupCount;
        System.arraycopy(other.groupLength, 0, groupLength, 0, 8);
        maxSfb = sfb;
        if (maxSfb > bandCount()) {
            throw new MediaException.Malformed("USAC max_sfb " + maxSfb + " exceeds " + bandCount() + " bands");
        }
    }

    /**
     * Maps a coded window sequence, turning a start window that follows short windows into a stop-start window
     * ({@code usacMapWindowSequences}).
     */
    static int mapSequence(int coded, int last) {
        if (coded == LONG_START && (last == LONG_START || last == EIGHT_SHORT || last == STOP_START)) {
            return STOP_START;
        }
        return coded;
    }

    /**
     * Reads {@code tw_data}.
     *
     * @param reader the bit source
     */
    void readTimeWarping(AacBitReader reader) {
        twPresent = reader.readFlag();
        if (twPresent) {
            for (var i = 0; i < 16; i++) {
                twRatio[i] = reader.read(3);
            }
        }
    }

    /**
     * Copies the time warping data of a common time warp.
     *
     * @param other the first channel of the pair
     */
    void copyTimeWarping(FdChannel other) {
        twPresent = other.twPresent;
        System.arraycopy(other.twRatio, 0, twRatio, 0, 16);
    }

    /**
     * Reads the scalefactors ({@code scale_factor_data}): every band below {@code max_sfb} carries one, the
     * first of the frame being the global gain.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if a scalefactor leaves its range
     */
    void readScalefactors(AacBitReader reader) {
        Arrays.fill(scalefactors, 0);
        var factor = globalGain;
        var window = 0;
        for (var group = 0; group < groupCount; group++) {
            var base = window * MAX_BANDS;
            for (var band = 0; band < maxSfb; band++) {
                if (group != 0 || band != 0) {
                    factor += Codebooks.decodeScalefactor(reader);
                }
                if (factor < 0 || factor >= 256) {
                    throw new MediaException.Malformed("USAC scalefactor " + factor + " out of range");
                }
                scalefactors[base + band] = factor;
            }
            for (var w = 1; w < groupLength[group]; w++) {
                System.arraycopy(scalefactors, base, scalefactors, base + w * MAX_BANDS, maxSfb);
            }
            window += groupLength[group];
        }
    }

    /**
     * Clears the TNS filters.
     */
    void clearTns() {
        Arrays.fill(tnsFilters, 0);
    }

    /**
     * Reads {@code tns_data}.
     *
     * @param reader the bit source
     */
    void readTns(AacBitReader reader) {
        var isShort = shortWindows();
        var windows = windowCount();
        var bands = bandCount();
        for (var w = 0; w < windows; w++) {
            var filters = reader.read(isShort ? 1 : 2);
            tnsFilters[w] = filters;
            if (filters == 0) {
                continue;
            }
            var resolution = reader.read(1) + 3;
            tnsResolution[w] = resolution;
            var top = bands;
            for (var f = 0; f < filters; f++) {
                tnsStop[w][f] = top;
                top = Math.max(0, top - reader.read(isShort ? 4 : 6));
                tnsStart[w][f] = top;
                var order = reader.read(isShort ? 3 : 4);
                tnsOrder[w][f] = order;
                if (order == 0) {
                    continue;
                }
                tnsDirection[w][f] = reader.read(1);
                var compress = reader.read(1);
                var bits = resolution - compress;
                for (var i = 0; i < order; i++) {
                    var value = reader.read(bits);
                    if ((value & 1 << (bits - 1)) != 0) {
                        value -= 1 << bits;
                    }
                    tnsCoefficients[w][f][i] = value;
                }
            }
        }
    }

    /**
     * Copies the TNS data of a common TNS.
     *
     * @param other the first channel of the pair
     */
    void copyTns(FdChannel other) {
        System.arraycopy(other.tnsFilters, 0, tnsFilters, 0, 8);
        System.arraycopy(other.tnsResolution, 0, tnsResolution, 0, 8);
        for (var w = 0; w < 8; w++) {
            for (var f = 0; f < 4; f++) {
                tnsStart[w][f] = other.tnsStart[w][f];
                tnsStop[w][f] = other.tnsStop[w][f];
                tnsOrder[w][f] = other.tnsOrder[w][f];
                tnsDirection[w][f] = other.tnsDirection[w][f];
                System.arraycopy(other.tnsCoefficients[w][f], 0, tnsCoefficients[w][f], 0, 32);
            }
        }
    }

    /**
     * Reads the arithmetic coded spectrum ({@code ac_spectral_data}) and dequantizes it with the scalefactors
     * and noise filling.
     *
     * @param reader the bit source
     * @param coder the channel's arithmetic decoder
     * @param independent whether the frame is independent, which resets the coder context
     * @param noiseFilling whether the configuration enables noise filling
     * @param seed the noise filling random state, updated
     */
    void readSpectrum(AacBitReader reader, ArithmeticDecoder coder, boolean independent, boolean noiseFilling,
                      int[] seed) {
        var reset = independent || reader.readFlag();
        var window = windowLength();
        var windows = windowCount();
        coder.prepare(window, reset);
        Arrays.fill(quantized, 0);
        var lines = maxSfb > 0 ? offsets()[maxSfb] : 0;
        if (lines > 0) {
            for (var w = 0; w < windows; w++) {
                coder.decode(reader, quantized, w * window, lines / 2, window / 2);
            }
        } else {
            coder.markEmpty(window);
        }
        dequantize(noiseFilling, seed);
    }

    /**
     * Applies the scalefactors and the noise filling ({@code applyScaleFactorsAndNf}).
     */
    private void dequantize(boolean noiseFilling, int[] seed) {
        Arrays.fill(spectrum, 0);
        var noise = 0f;
        var noiseStart = 0;
        if (noiseFilling) {
            var level = noiseLevel >> 5;
            noise = level > 0 ? (float) StrictMath.pow(2.0f, (level - 14.0f) / 3.0f) : 0f;
            noiseStart = frameLength == 768 ? (shortWindows() ? 15 : 120) : (shortWindows() ? 20 : 160);
        }
        var offsets = offsets();
        var window = windowLength();
        var first = 0;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxSfb; band++) {
                var start = offsets[band];
                var end = offsets[band + 1];
                var factor = (float) (scalefactors[first * MAX_BANDS + band] - SF_OFFSET);
                var empty = true;
                if (noiseFilling) {
                    for (var w = 0; w < groupLength[group] && empty; w++) {
                        var base = (first + w) * window;
                        for (var i = start; i < end; i++) {
                            if (quantized[base + i] != 0) {
                                empty = false;
                                break;
                            }
                        }
                    }
                    if (empty) {
                        factor += (noiseLevel & 0x1F) - 16;
                    }
                }
                var gain = (float) StrictMath.pow(2.0f, 0.25f * factor);
                var fill = noiseFilling && start >= noiseStart;
                for (var w = 0; w < groupLength[group]; w++) {
                    var base = (first + w) * window;
                    for (var i = start; i < end; i++) {
                        spectrum[base + i] = inverseQuantize(quantized[base + i], noise, fill, seed) * gain;
                    }
                }
            }
            first += groupLength[group];
        }
    }

    /**
     * Inverse quantizes one line, replacing a zero by a random-sign noise value when filling
     * ({@code esc_iquant}).
     */
    static float inverseQuantize(int q, float noise, boolean fill, int[] seed) {
        if (fill && q == 0) {
            seed[0] = seed[0] * 69069 + 5;
            return (seed[0] & 0x10000) != 0 ? -noise : noise;
        }
        if (q > 0) {
            return power(Math.min(q, 8191));
        }
        return -power(-q);
    }

    /**
     * {@return {@code q} to the power of 4/3 as the decoder's table holds it, rounded to a float}
     */
    static float power(int q) {
        return q < POWERS.length ? POWERS[q] : (float) StrictMath.pow((float) q, 4.0f / 3.0f);
    }

    private static final float[] POWERS = new float[1024];

    static {
        for (var i = 0; i < POWERS.length; i++) {
            POWERS[i] = (float) StrictMath.pow(i, 4.0 / 3.0);
        }
    }

    /**
     * Applies the TNS synthesis filters ({@code usac_tns_decode_subblock}).
     */
    void applyTns() {
        var isShort = shortWindows();
        var windows = windowCount();
        var window = windowLength();
        var offsets = offsets();
        var maxBands = UsacTables.tnsMaxBands(tableIndex, isShort);
        var maxOrder = isShort ? 7 : 15;
        for (var w = 0; w < windows; w++) {
            var filters = tnsFilters[w];
            if (filters == 0) {
                continue;
            }
            var base = w * window;
            System.arraycopy(spectrum, base, tnsBuffer, 0, window);
            for (var f = 0; f < filters; f++) {
                var order = Math.min(tnsOrder[w][f], maxOrder);
                if (order == 0) {
                    continue;
                }
                decodeCoefficients(order, tnsResolution[w], tnsCoefficients[w][f]);
                var start = offsets[Math.min(Math.min(tnsStart[w][f], maxBands), maxSfb)];
                var stop = offsets[Math.min(Math.min(tnsStop[w][f], maxBands), maxSfb)];
                var size = stop - start;
                if (size <= 0) {
                    continue;
                }
                filter(tnsBuffer, start, size, tnsDirection[w][f] != 0 ? -1 : 1, order);
            }
            System.arraycopy(tnsBuffer, 0, spectrum, base, window);
        }
    }

    private void decodeCoefficients(int order, int resolution, int[] coefficients) {
        var quantization = (float) (((1 << (resolution - 1)) - 0.5) / (3.14159265358979323846f / 2.0f));
        var quantizationNegative = (float) (((1 << (resolution - 1)) + 0.5) / (3.14159265358979323846f / 2.0f));
        for (var i = 0; i < order; i++) {
            var c = coefficients[i];
            reflection[i + 1] = (float) StrictMath.sin(c / (c >= 0 ? quantization : quantizationNegative));
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

    private void filter(float[] data, int start, int size, int increment, int order) {
        Arrays.fill(state, 0, order, 0f);
        var index = increment < 0 ? start + size - 1 : start;
        for (var i = 0; i < size; i++) {
            var y = data[index];
            for (var j = 0; j < order; j++) {
                y -= lpc[j + 1] * state[j];
            }
            for (var j = order - 1; j > 0; j--) {
                state[j] = state[j - 1];
            }
            state[0] = y;
            data[index] = y;
            index += increment;
        }
    }
}
