/* Ports libxaac (decoder/ixheaacd_rev_vlc.c: the RVLC and escape codeword tables, the forward and backward
   decoding orders, the error detection and the concealment strategies).
   Copyright (C) 2018 The Android Open Source Project, Ittiam Systems; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Reads the reversible variable length scalefactors of the error resilient object types (ISO/IEC 14496-3
 * subclause 4.6.2.3.2, {@code aacScalefactorDataResilienceFlag}), concealing corrupt codewords.
 *
 * <p>The side information ({@code rvlc_scale_factor_data}) takes the place of {@code scale_factor_data};
 * the codewords ({@code rvlc_code_sf}) and the escapes ({@code rvlc_esc}) follow later in the channel
 * stream, so a stream is read in two calls: {@link #readSideInfo} and {@link #decode}.
 *
 * <p>The codewords are decoded forwards from the global gain and, unless the forward decoding fills the
 * declared lengths exactly and ends on the reverse start values ({@code rev_global_gain},
 * {@code dpcm_noise_last_position} and the last intensity position), backwards from those values. A
 * decoding stops at an invalid codeword, a codeword crossing the end of the codeword bits, a missing escape
 * or a value out of range. The values are then concealed:
 * <ul>
 * <li>bands only one direction decoded take that direction's value;</li>
 * <li>bands both directions decoded where the two decodings disagree on the position of the error take the
 * quieter of the two values and, when the previous frame was decoded without error with the same window
 * length and {@code sf_concealment} allows it, of that frame's value for a band of the same kind;</li>
 * <li>bands neither direction reached take the previous frame's value under the same conditions, or else
 * the quieter of the nearest values decoded on either side;</li>
 * <li>when both directions decode every band but disagree with the start values, each band takes the
 * quieter of the two values and of the previous frame's when it may be used, or else every band of a kind
 * takes the direction whose values of that kind are quieter in sum.</li>
 * </ul>
 * The quieter value is the smaller scalefactor or noise energy and the larger intensity position.
 * Concealed values are clamped into their ranges.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows the strategies of libxaac (bidirectional estimation with and without
 * the previous frame, statistical estimation and predictive interpolation), keeping the previous frame's
 * values that libxaac declares but never fills, and takes the larger intensity position as the quieter one
 * where libxaac takes the smaller.
 */
final class ReversibleScalefactors {
    /**
     * The longest scalefactor codeword, in bits.
     */
    private static final int MAX_CODEWORD = 9;

    /**
     * The longest escape codeword, in bits.
     */
    private static final int MAX_ESCAPE = 20;

    /**
     * The marker of a codeword that does not decode.
     */
    private static final int INVALID = Integer.MIN_VALUE;

    /**
     * The number of band values of a stream, eight groups of {@link ChannelStream#MAX_BANDS}.
     */
    private static final int VALUES = 8 * ChannelStream.MAX_BANDS;

    /**
     * The kind of a scalefactor band.
     */
    private static final int SCALEFACTOR = 0;

    /**
     * The kind of a noise band.
     */
    private static final int NOISE = 1;

    /**
     * The kind of an intensity band.
     */
    private static final int INTENSITY = 2;

    /**
     * The scalefactor codewords as {code, length, value} triples, the value being the coded difference plus 7.
     */
    private static final int[][] CODEWORDS = sorted(new int[][]{
            {0, 1, 7}, {5, 3, 6}, {7, 3, 8}, {9, 4, 5}, {17, 5, 4}, {27, 5, 9}, {33, 6, 3}, {51, 6, 10},
            {65, 7, 0}, {107, 7, 11}, {99, 7, 14}, {129, 8, 2}, {195, 8, 12}, {257, 9, 1}, {427, 9, 13}
    });

    /**
     * The escape codewords as {code, length, value} triples, the value being the escape from 0 to 53.
     */
    private static final int[][] ESCAPES = sorted(escapeCodes());

    /**
     * The escape values of the current stream, in coding order.
     */
    private final int[] escapes = new int[VALUES];

    /**
     * The values of the forward decoding, by band index.
     */
    private final int[] forward = new int[VALUES];

    /**
     * The values of the backward decoding, by band index.
     */
    private final int[] backward = new int[VALUES];

    /**
     * The sum of the forward values of each band kind, for the statistical concealment.
     */
    private final long[] forwardSums = new long[3];

    /**
     * The sum of the backward values of each band kind, for the statistical concealment.
     */
    private final long[] backwardSums = new long[3];

    /**
     * The values of the last frame decoded without error, by band index.
     */
    private final int[] previous = new int[VALUES];

    /**
     * The codebooks of the last frame decoded without error, by band index; {@code ZERO_HCB} past its bands.
     */
    private final int[] previousTypes = new int[VALUES];

    /**
     * Whether {@link #previous} holds the frame before the current one, decoded without error.
     */
    private boolean previousValid;

    /**
     * Whether the frame of {@link #previous} has short windows.
     */
    private boolean previousShort;

    /**
     * Whether the frame has short windows.
     */
    private boolean shortWindow;

    /**
     * Whether the previous frame's values may conceal this frame's ({@code sf_concealment}).
     */
    private boolean concealmentAllowed;

    /**
     * Whether the last decoded frame was concealed.
     */
    private boolean concealed;

    /**
     * The reverse global gain ({@code rev_global_gain}), the last scalefactor.
     */
    private int reverseGlobalGain;

    /**
     * The number of codeword bits ({@code length_of_rvlc_sf}), without the last noise position; 0 when the
     * coded length is shorter than that position.
     */
    private int codewordBits;

    /**
     * The number of escape bits ({@code length_of_rvlc_escapes}), 0 without escapes.
     */
    private int escapeBits;

    /**
     * The first noise energy difference ({@code dpcm_noise_nrg}).
     */
    private int firstNoise;

    /**
     * The last noise energy offset ({@code dpcm_noise_last_position}).
     */
    private int lastNoise;

    /**
     * Whether the stream has noise bands.
     */
    private boolean noise;

    /**
     * The number of escapes decoded from {@code rvlc_esc}.
     */
    private int escapeCount;

    /**
     * The bit position of the next codeword bit of the decoding in progress.
     */
    private long cursor;

    /**
     * The index of the next escape of the decoding in progress.
     */
    private int escapeCursor;

    /**
     * The bit source of the decoding in progress.
     */
    private AacBitReader source;

    /**
     * Reads {@code rvlc_scale_factor_data}.
     *
     * @param reader the bit source
     * @param shortWindow whether the frame has short windows, which widens {@code length_of_rvlc_sf}
     * @param noiseBands whether any band uses the noise codebook
     */
    void readSideInfo(AacBitReader reader, boolean shortWindow, boolean noiseBands) {
        this.shortWindow = shortWindow;
        concealmentAllowed = reader.readFlag();
        reverseGlobalGain = reader.read(8);
        codewordBits = reader.read(shortWindow ? 11 : 9);
        noise = noiseBands;
        firstNoise = noise ? reader.read(9) : 0;
        escapeBits = reader.readFlag() ? reader.read(8) : 0;
        if (noise) {
            lastNoise = reader.read(9);
            codewordBits = Math.max(0, codewordBits - 9);
        }
    }

    /**
     * {@return whether the last call to {@link #decode} concealed an error}
     */
    boolean concealed() {
        return concealed;
    }

    /**
     * Reads {@code rvlc_code_sf} and {@code rvlc_esc} and decodes every band value, concealing errors as the
     * class documentation describes.
     *
     * <p>The reader is left after the escapes whatever the codewords hold; a reader that cannot reach that
     * position is left overrun for the caller to detect.
     *
     * @param reader the bit source, at the first codeword
     * @param globalGain the global gain
     * @param bandTypes the codebook of each band, indexed {@code group * MAX_BANDS + band}
     * @param bandValues receives the scalefactor, intensity position or noise energy of each band
     * @param groups the number of window groups
     * @param bands the number of transmitted bands per group
     * @return {@code true} when the values were concealed
     */
    boolean decode(AacBitReader reader, int globalGain, int[] bandTypes, int[] bandValues, int groups, int bands) {
        source = reader;
        var codewordStart = reader.bitPosition();
        var codewordEnd = codewordStart + codewordBits;
        readEscapes(codewordEnd);
        reader.seek(codewordEnd + escapeBits);
        var count = groups * bands;
        var intensity = false;
        var scalefactors = false;
        var firstNoiseBand = -1;
        for (var p = 0; p < count; p++) {
            var type = bandTypes[index(p, bands)];
            if (type == Codebooks.INTENSITY_HCB || type == Codebooks.INTENSITY_HCB2) {
                intensity = true;
            } else if (type == Codebooks.NOISE_HCB) {
                if (firstNoiseBand < 0) {
                    firstNoiseBand = p;
                }
            } else if (type != Codebooks.ZERO_HCB) {
                scalefactors = true;
            }
        }
        var noiseStart = globalGain + firstNoise - 90 - 256;
        var noiseEnd = reverseGlobalGain + lastNoise - 90 - 256;

        cursor = codewordStart;
        escapeCursor = 0;
        var scalefactor = globalGain;
        var position = 0;
        var noiseEnergy = noiseStart;
        var forwardStop = count;
        for (var p = 0; p < count; p++) {
            var index = index(p, bands);
            var type = bandTypes[index];
            if (type == Codebooks.ZERO_HCB) {
                forward[index] = 0;
                continue;
            }
            var kind = kind(type);
            if (kind == NOISE && p == firstNoiseBand) {
                forward[index] = noiseEnergy;
                if (!inRange(NOISE, noiseEnergy)) {
                    forwardStop = p;
                    break;
                }
                continue;
            }
            var difference = forwardDifference(codewordEnd);
            if (difference == INVALID) {
                forwardStop = p;
                break;
            }
            int value;
            if (kind == SCALEFACTOR) {
                value = scalefactor += difference;
            } else if (kind == NOISE) {
                value = noiseEnergy += difference;
            } else {
                value = position += difference;
            }
            if (!inRange(kind, value)) {
                forwardStop = p;
                break;
            }
            forward[index] = value;
        }
        var lastPosition = 0;
        if (forwardStop == count && intensity) {
            lastPosition = forwardDifference(codewordEnd);
        }
        var forwardConsistent = forwardStop == count && lastPosition != INVALID && cursor == codewordEnd
                && escapeCursor == escapeCount
                && (!scalefactors || scalefactor == reverseGlobalGain)
                && (firstNoiseBand < 0 || noiseEnergy == noiseEnd)
                && (!intensity || position == lastPosition);
        if (forwardConsistent) {
            for (var p = 0; p < count; p++) {
                var index = index(p, bands);
                bandValues[index] = forward[index];
            }
            remember(bandTypes, bandValues, groups, bands);
            concealed = false;
            return false;
        }

        cursor = codewordEnd - 1;
        escapeCursor = escapeCount - 1;
        var backwardStart = count;
        var reversePosition = 0;
        if (intensity) {
            reversePosition = backwardDifference(codewordStart);
        }
        if (reversePosition != INVALID) {
            scalefactor = reverseGlobalGain;
            noiseEnergy = noiseEnd;
            position = reversePosition;
            for (var p = count - 1; p >= 0; p--) {
                var index = index(p, bands);
                var type = bandTypes[index];
                if (type == Codebooks.ZERO_HCB) {
                    backward[index] = 0;
                    backwardStart = p;
                    continue;
                }
                var kind = kind(type);
                var value = kind == SCALEFACTOR ? scalefactor : kind == NOISE ? noiseEnergy : position;
                if (!inRange(kind, value)) {
                    break;
                }
                backward[index] = value;
                backwardStart = p;
                if (kind == NOISE && p == firstNoiseBand) {
                    continue;
                }
                var difference = backwardDifference(codewordStart);
                if (difference == INVALID) {
                    break;
                }
                if (kind == SCALEFACTOR) {
                    scalefactor -= difference;
                } else if (kind == NOISE) {
                    noiseEnergy -= difference;
                } else {
                    position -= difference;
                }
            }
        }

        var usePrevious = previousValid && concealmentAllowed && previousShort == shortWindow;
        if (forwardStop == count && backwardStart == 0) {
            if (usePrevious) {
                for (var p = 0; p < count; p++) {
                    var index = index(p, bands);
                    bandValues[index] = estimate(bandTypes[index], forward[index], backward[index], index, true);
                }
            } else {
                var forwardSums = this.forwardSums;
                var backwardSums = this.backwardSums;
                Arrays.fill(forwardSums, 0);
                Arrays.fill(backwardSums, 0);
                for (var p = 0; p < count; p++) {
                    var index = index(p, bands);
                    var type = bandTypes[index];
                    if (type != Codebooks.ZERO_HCB) {
                        forwardSums[kind(type)] += forward[index];
                        backwardSums[kind(type)] += backward[index];
                    }
                }
                for (var p = 0; p < count; p++) {
                    var index = index(p, bands);
                    var type = bandTypes[index];
                    if (type == Codebooks.ZERO_HCB) {
                        bandValues[index] = 0;
                        continue;
                    }
                    var kind = kind(type);
                    var useForward = kind == INTENSITY ? forwardSums[kind] > backwardSums[kind]
                            : forwardSums[kind] < backwardSums[kind];
                    bandValues[index] = useForward ? forward[index] : backward[index];
                }
            }
        } else if (backwardStart <= forwardStop) {
            for (var p = 0; p < count; p++) {
                var index = index(p, bands);
                var type = bandTypes[index];
                if (p < backwardStart) {
                    bandValues[index] = forward[index];
                } else if (p >= forwardStop) {
                    bandValues[index] = backward[index];
                } else {
                    bandValues[index] = estimate(type, forward[index], backward[index], index, usePrevious);
                }
            }
        } else {
            for (var p = 0; p < count; p++) {
                var index = index(p, bands);
                var type = bandTypes[index];
                if (p < forwardStop) {
                    bandValues[index] = forward[index];
                } else if (p >= backwardStart) {
                    bandValues[index] = backward[index];
                } else if (type == Codebooks.ZERO_HCB) {
                    bandValues[index] = 0;
                } else if (usePrevious && kind(previousTypes[index]) == kind(type)
                        && previousTypes[index] != Codebooks.ZERO_HCB) {
                    bandValues[index] = previous[index];
                } else {
                    var kind = kind(type);
                    var before = nearestForward(kind, forwardStop, bandTypes, bands, globalGain, noiseStart);
                    var after = nearestBackward(kind, backwardStart, count, bandTypes, bands, noiseEnd,
                            reversePosition == INVALID ? 0 : reversePosition);
                    bandValues[index] = quieter(kind, before, after);
                }
            }
        }
        for (var p = 0; p < count; p++) {
            var index = index(p, bands);
            var type = bandTypes[index];
            bandValues[index] = type == Codebooks.ZERO_HCB ? 0 : clamp(kind(type), bandValues[index]);
        }
        previousValid = false;
        previousShort = shortWindow;
        concealed = true;
        return true;
    }

    /**
     * Decodes {@code rvlc_esc} up to the first invalid escape or the end of the escape bits.
     *
     * @param escapeStart the bit position of the first escape
     */
    private void readEscapes(long escapeStart) {
        escapeCount = 0;
        var escapeEnd = escapeStart + escapeBits;
        cursor = escapeStart;
        while (cursor < escapeEnd && escapeCount < escapes.length) {
            var value = codeword(ESCAPES, MAX_ESCAPE, escapeEnd, 1);
            if (value == INVALID) {
                return;
            }
            escapes[escapeCount++] = value;
        }
    }

    /**
     * Decodes the next difference forwards, resolving an escape.
     *
     * @param end the bit position after the last codeword bit
     * @return the difference, or {@link #INVALID}
     */
    private int forwardDifference(long end) {
        var value = codeword(CODEWORDS, MAX_CODEWORD, end, 1);
        if (value == INVALID) {
            return INVALID;
        }
        var difference = value - 7;
        if (difference != 7 && difference != -7) {
            return difference;
        }
        if (escapeCursor >= escapeCount) {
            return INVALID;
        }
        var escape = escapes[escapeCursor++];
        return difference < 0 ? difference - escape : difference + escape;
    }

    /**
     * Decodes the next difference backwards, resolving an escape from the last one.
     *
     * @param start the bit position of the first codeword bit
     * @return the difference, or {@link #INVALID}
     */
    private int backwardDifference(long start) {
        var value = codeword(CODEWORDS, MAX_CODEWORD, start - 1, -1);
        if (value == INVALID) {
            return INVALID;
        }
        var difference = value - 7;
        if (difference != 7 && difference != -7) {
            return difference;
        }
        if (escapeCursor < 0) {
            return INVALID;
        }
        var escape = escapes[escapeCursor--];
        return difference < 0 ? difference - escape : difference + escape;
    }

    /**
     * Decodes one codeword at {@link #cursor}, moving the cursor past it.
     *
     * <p>The scalefactor codewords are palindromes, so the same table decodes them in either direction.
     *
     * @param codes the codewords, sorted by length
     * @param maxBits the longest codeword
     * @param limit the bit position the codeword may not reach
     * @param step 1 to read forwards, -1 to read backwards
     * @return the codeword's value, or {@link #INVALID} when no codeword matches before the limit
     */
    private int codeword(int[][] codes, int maxBits, long limit, int step) {
        var code = 0;
        var next = 0;
        for (var length = 1; length <= maxBits; length++) {
            if (cursor == limit) {
                return INVALID;
            }
            code = code << 1 | source.bitAt(cursor);
            cursor += step;
            while (next < codes.length && codes[next][1] < length) {
                next++;
            }
            for (var i = next; i < codes.length && codes[i][1] == length; i++) {
                if (codes[i][0] == code) {
                    return codes[i][2];
                }
            }
        }
        return INVALID;
    }

    /**
     * Returns the concealed value of a band both directions decoded.
     *
     * @param type the band's codebook
     * @param forwardValue the forward value
     * @param backwardValue the backward value
     * @param index the band index
     * @param usePrevious whether the previous frame's value may take part
     * @return the value
     */
    private int estimate(int type, int forwardValue, int backwardValue, int index, boolean usePrevious) {
        if (type == Codebooks.ZERO_HCB) {
            return 0;
        }
        var kind = kind(type);
        var value = quieter(kind, forwardValue, backwardValue);
        if (usePrevious && previousTypes[index] != Codebooks.ZERO_HCB && kind(previousTypes[index]) == kind) {
            value = quieter(kind, value, previous[index]);
        }
        return value;
    }

    /**
     * Returns the last forward value of a kind before a band, or the forward start value of that kind.
     */
    private int nearestForward(int kind, int stop, int[] bandTypes, int bands, int globalGain, int noiseStart) {
        for (var p = stop - 1; p >= 0; p--) {
            var index = index(p, bands);
            var type = bandTypes[index];
            if (type != Codebooks.ZERO_HCB && kind(type) == kind) {
                return forward[index];
            }
        }
        return kind == SCALEFACTOR ? globalGain : kind == NOISE ? noiseStart : 0;
    }

    /**
     * Returns the first backward value of a kind from a band on, or the backward start value of that kind.
     */
    private int nearestBackward(int kind, int start, int count, int[] bandTypes, int bands, int noiseEnd,
                                int lastPosition) {
        for (var p = start; p < count; p++) {
            var index = index(p, bands);
            var type = bandTypes[index];
            if (type != Codebooks.ZERO_HCB && kind(type) == kind) {
                return backward[index];
            }
        }
        return kind == SCALEFACTOR ? reverseGlobalGain : kind == NOISE ? noiseEnd : lastPosition;
    }

    /**
     * Keeps the values of a frame decoded without error for concealing the next one.
     */
    private void remember(int[] bandTypes, int[] bandValues, int groups, int bands) {
        Arrays.fill(previousTypes, Codebooks.ZERO_HCB);
        for (var group = 0; group < groups; group++) {
            var base = group * ChannelStream.MAX_BANDS;
            System.arraycopy(bandTypes, base, previousTypes, base, bands);
            System.arraycopy(bandValues, base, previous, base, bands);
        }
        previousValid = true;
        previousShort = shortWindow;
    }

    /**
     * Returns the band index of a position in coding order.
     */
    private static int index(int position, int bands) {
        return position / bands * ChannelStream.MAX_BANDS + position % bands;
    }

    /**
     * Returns the kind of a band codebook other than {@code ZERO_HCB}.
     */
    private static int kind(int type) {
        return type == Codebooks.NOISE_HCB ? NOISE
                : type == Codebooks.INTENSITY_HCB || type == Codebooks.INTENSITY_HCB2 ? INTENSITY : SCALEFACTOR;
    }

    /**
     * Returns the quieter of two values of a kind: the smaller scalefactor or noise energy, the larger
     * intensity position.
     */
    private static int quieter(int kind, int a, int b) {
        return kind == INTENSITY ? Math.max(a, b) : Math.min(a, b);
    }

    /**
     * Returns whether a value lies in the range of its kind.
     */
    private static boolean inRange(int kind, int value) {
        return clamp(kind, value) == value;
    }

    /**
     * Clamps a value into the range of its kind.
     */
    private static int clamp(int kind, int value) {
        return switch (kind) {
            case SCALEFACTOR -> Math.clamp(value, 0, 255);
            case NOISE -> Math.clamp(value, Dequantizer.MIN_EXPONENT, Dequantizer.MAX_EXPONENT);
            default -> Math.clamp(value, -Dequantizer.MAX_EXPONENT, -Dequantizer.MIN_EXPONENT);
        };
    }

    /**
     * Sorts codewords by length.
     *
     * @param codes the codewords as {code, length, value} triples
     * @return the same array, sorted
     */
    private static int[][] sorted(int[][] codes) {
        Arrays.sort(codes, Comparator.comparingInt(code -> code[1]));
        return codes;
    }

    /**
     * Returns the escape codewords ({@code rvlc_esc}) as {code, length, value} triples.
     *
     * @return the codewords
     */
    private static int[][] escapeCodes() {
        var codes = new int[54][];
        int[][] fixed = {
                {2, 2, 0}, {0, 2, 1}, {6, 3, 2}, {2, 3, 3}, {14, 4, 4}, {31, 5, 5}, {15, 5, 6}, {13, 5, 7},
                {61, 6, 8}, {29, 6, 9}, {25, 6, 10}, {24, 6, 11}, {120, 7, 12}, {56, 7, 13}, {242, 8, 14},
                {114, 8, 15}, {486, 9, 16}, {230, 9, 17}, {974, 10, 18}, {463, 10, 19}, {1950, 11, 20},
                {1951, 11, 21}, {925, 11, 22}, {1848, 12, 23}, {7399, 14, 24}, {3698, 13, 25}, {14797, 15, 26}
        };
        System.arraycopy(fixed, 0, codes, 0, fixed.length);
        var next = fixed.length;
        for (var value = 27; value <= 48; value++) {
            codes[next++] = new int[]{473503 - (48 - value), 20, value};
        }
        for (var value = 49; value <= 53; value++) {
            codes[next++] = new int[]{236740 - (53 - value), 19, value};
        }
        return codes;
    }
}
