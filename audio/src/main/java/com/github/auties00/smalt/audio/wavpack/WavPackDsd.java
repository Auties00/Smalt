/* Ports WavPack 5.8.1 (src/unpack_dsd.c). Copyright (c) 2013 - 2025 David Bryant; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.wavpack;

/**
 * Decodes the DSD (1-bit) blocks of a WavPack stream into bytes of eight DSD samples, most significant first: the
 * uncompressed mode, the fast mode of byte probabilities and the high mode of its adaptive bit predictor.
 *
 * <p>Instances are not thread-safe.
 */
final class WavPackDsd {
    private static final int MAX_HISTORY_BITS = 5;
    private static final int MAX_BYTES_PER_BIN = 1280;
    private static final int PTABLE_BITS = 8;
    private static final int PTABLE_BINS = 1 << PTABLE_BITS;
    private static final int PTABLE_MASK = PTABLE_BINS - 1;
    private static final int UP = 0x010000fe;
    private static final int DOWN = 0x00010000;
    private static final int DECAY = 8;
    private static final int PRECISION = 20;
    private static final int VALUE_ONE = 1 << PRECISION;
    private static final int PRECISION_USE = 12;
    private static final int RATE_S = 20;

    private byte[] data;

    private int position;

    private int end;

    private int mode;

    private boolean ready;

    /**
     * The binary logarithm of the DSD rate over the byte rate of the block header, or -1 before a DSD block.
     */
    private int multiplierShift = -1;

    private int historyBins;

    private int[][] probabilities = new int[0][];

    private int[][] summed = new int[0][];

    private int[][] lookup = new int[0][];

    private int p0;

    private int p1;

    private int low;

    private int high;

    private int value;

    private final int[] ptable = new int[PTABLE_BINS];

    private final int[][] filters = new int[2][7];

    private final int[] factor = new int[2];

    private final int[] filterValue = new int[2];

    private final int[] filterByte = new int[2];

    /**
     * Clears the state of the previous block.
     */
    void reset() {
        ready = false;
        data = null;
    }

    /**
     * {@return whether a DSD block's data was read}
     */
    boolean ready() {
        return ready;
    }

    /**
     * {@return the DSD rate over the rate of the header's sample rate field, 1 at least, or 0 before a DSD block}
     */
    int multiplier() {
        return multiplierShift < 0 ? 0 : 1 << multiplierShift;
    }

    /**
     * Reads the DSD sub-block of a block ({@code init_dsd_block}).
     *
     * @return whether the sub-block is valid
     */
    boolean init(byte[] data, int at, int length, int flags, int blockSamples) {
        if (length < 2) {
            return false;
        }
        this.data = data;
        this.position = at;
        this.end = at + length;
        var shift = data[position++] & 0xFF;
        if (shift > 31) {
            return false;
        }
        multiplierShift = shift;
        mode = data[position++] & 0xFF;
        var mono = (flags & WavPackStream.MONO_DATA) != 0;
        if (mode == 0) {
            if ((long) end - position != (long) blockSamples * (mono ? 1 : 2)) {
                return false;
            }
            ready = true;
            return true;
        }
        if (mode == 1) {
            return initFast();
        }
        if (mode == 3) {
            return initHigh(mono);
        }
        return false;
    }

    private boolean initFast() {
        if (position == end) {
            return false;
        }
        var historyBits = data[position++] & 0xFF;
        if (position == end || historyBits > MAX_HISTORY_BITS) {
            return false;
        }
        historyBins = 1 << historyBits;
        if (probabilities.length < historyBins) {
            probabilities = new int[historyBins][256];
            summed = new int[historyBins][256];
            lookup = new int[historyBins][];
        }
        var maxProbability = data[position++] & 0xFF;
        if (maxProbability < 0xff) {
            var bin = 0;
            var index = 0;
            while (bin < historyBins && position < end) {
                var code = data[position++] & 0xFF;
                if (code > maxProbability) {
                    var zeros = code - maxProbability;
                    while (bin < historyBins && zeros-- > 0) {
                        probabilities[bin][index] = 0;
                        if (++index == 256) {
                            index = 0;
                            bin++;
                        }
                    }
                } else if (code != 0) {
                    probabilities[bin][index] = code;
                    if (++index == 256) {
                        index = 0;
                        bin++;
                    }
                } else {
                    break;
                }
            }
            if (bin < historyBins || (position < end && data[position++] != 0)) {
                return false;
            }
        } else if (end - position > 256 * historyBins) {
            for (var bin = 0; bin < historyBins; bin++) {
                for (var i = 0; i < 256; i++) {
                    probabilities[bin][i] = data[position++] & 0xFF;
                }
            }
        } else {
            return false;
        }
        var total = 0;
        for (var bin = 0; bin < historyBins; bin++) {
            var sum = 0;
            for (var i = 0; i < 256; i++) {
                sum += probabilities[bin][i];
                summed[bin][i] = sum & 0xFFFF;
            }
            if (sum != 0) {
                total += sum;
                if (total > historyBins * MAX_BYTES_PER_BIN) {
                    return false;
                }
                var values = lookup[bin];
                if (values == null || values.length < sum) {
                    values = new int[Math.max(sum, values == null ? 0 : values.length * 2)];
                    lookup[bin] = values;
                }
                var at = 0;
                for (var i = 0; i < 256; i++) {
                    for (var c = probabilities[bin][i]; c > 0; c--) {
                        values[at++] = i;
                    }
                }
            }
        }
        if (end - position < 4) {
            return false;
        }
        for (var i = 0; i < 4; i++) {
            value = (value << 8) | (data[position++] & 0xFF);
        }
        p0 = p1 = 0;
        low = 0;
        high = 0xffffffff;
        ready = true;
        return true;
    }

    private void initPtable(int rateI, int rateS) {
        var v = 0x808000;
        var rate = rateI << 8;
        for (var c = (rate + 128) >> 8; c > 0; c--) {
            v += (DOWN - v) >> DECAY;
        }
        for (var i = 0; i < PTABLE_BINS / 2; i++) {
            ptable[i] = v;
            ptable[PTABLE_BINS - 1 - i] = 0x100ffff - v;
            if (v > 0x010000) {
                rate += (rate * rateS + 128) >> 8;
                for (var c = (rate + 64) >> 7; c > 0; c--) {
                    v += (DOWN - v) >> DECAY;
                }
            }
        }
    }

    private boolean initHigh(boolean mono) {
        if (end - position < (mono ? 13 : 20)) {
            return false;
        }
        var rateI = data[position++] & 0xFF;
        var rateS = data[position++] & 0xFF;
        if (rateS != RATE_S) {
            return false;
        }
        initPtable(rateI, rateS);
        for (var channel = 0; channel < (mono ? 1 : 2); channel++) {
            var filter = filters[channel];
            for (var f = 1; f <= 5; f++) {
                filter[f] = (data[position++] & 0xFF) << (PRECISION - 8);
            }
            filter[6] = 0;
            var raw = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8;
            position += 2;
            factor[channel] = (short) raw;
        }
        high = 0xffffffff;
        low = 0;
        for (var i = 0; i < 4; i++) {
            value = (value << 8) | (data[position++] & 0xFF);
        }
        ready = true;
        return true;
    }

    /**
     * Decodes the bytes of a DSD block ({@code unpack_dsd_samples}), interleaved for a stereo block.
     *
     * @param output receives the bytes, one per element
     * @param samples the block's samples per channel
     * @param flags the block's flags
     * @param crc the running checksum
     * @return the checksum after the block
     * @throws com.github.auties00.smalt.MediaException.Malformed if the data is corrupt
     */
    int unpack(int[] output, int samples, int flags, int crc) {
        var mono = (flags & WavPackStream.MONO_DATA) != 0;
        var total = mono ? samples : samples * 2;
        switch (mode) {
            case 0 -> {
                for (var i = 0; i < total; i++) {
                    var b = data[position++] & 0xFF;
                    output[i] = b;
                    crc += (crc << 1) + b;
                }
            }
            case 1 -> crc = decodeFast(output, total, mono, crc);
            default -> crc = decodeHigh(output, samples, !mono, crc);
        }
        return crc;
    }

    private void normalize() {
        while (((high ^ low) & 0xff000000) == 0 && position < end) {
            value = (value << 8) | (data[position++] & 0xFF);
            high = (high << 8) | 0xff;
            low <<= 8;
        }
    }

    private int decodeFast(int[] output, int total, boolean mono, int crc) {
        for (var i = 0; i < total; i++) {
            var sum = summed[p0][255];
            if (sum == 0) {
                throw corrupt();
            }
            var mult = Integer.divideUnsigned(high - low, sum);
            if (mult == 0) {
                if (end - position >= 4) {
                    for (var b = 0; b < 4; b++) {
                        value = (value << 8) | (data[position++] & 0xFF);
                    }
                }
                low = 0;
                high = 0xffffffff;
                mult = Integer.divideUnsigned(high, sum);
                if (mult == 0) {
                    throw corrupt();
                }
            }
            var index = Integer.divideUnsigned(value - low, mult);
            if (Integer.compareUnsigned(index, sum) >= 0) {
                throw corrupt();
            }
            var code = lookup[p0][index];
            output[i] = code;
            if (code != 0) {
                low += summed[p0][code - 1] * mult;
            }
            high = low + probabilities[p0][code] * mult - 1;
            crc += (crc << 1) + code;
            if (mono) {
                p0 = code & (historyBins - 1);
            } else {
                p0 = p1;
                p1 = code & (historyBins - 1);
            }
            normalize();
        }
        return crc;
    }

    private int decodeHigh(int[] output, int samples, boolean stereo, int crc) {
        var at = 0;
        for (var s = 0; s < samples; s++) {
            filterValue[0] = predict(0);
            if (stereo) {
                filterValue[1] = predict(1);
            }
            for (var bit = 0; bit < 8; bit++) {
                decodeBit(0);
                if (stereo) {
                    decodeBit(1);
                }
            }
            var first = filterByte[0] & 0xff;
            output[at++] = first;
            crc += (crc << 1) + first;
            factor[0] -= (factor[0] + 512) >> 10;
            if (stereo) {
                var second = filterByte[1] & 0xff;
                output[at++] = second;
                crc += (crc << 1) + second;
                factor[1] -= (factor[1] + 512) >> 10;
            }
        }
        return crc;
    }

    private int predict(int channel) {
        var f = filters[channel];
        return f[1] - f[5] + ((f[6] * factor[channel]) >> 2);
    }

    private void decodeBit(int channel) {
        var f = filters[channel];
        var entry = (filterValue[channel] >> (PRECISION - PRECISION_USE)) & PTABLE_MASK;
        var probability = ptable[entry];
        var split = low + ((high - low) >>> 8) * (probability >> 16);
        if (Integer.compareUnsigned(value, split) <= 0) {
            high = split;
            ptable[entry] = probability + ((UP - probability) >> DECAY);
            f[0] = -1;
        } else {
            low = split + 1;
            ptable[entry] = probability + ((DOWN - probability) >> DECAY);
            f[0] = 0;
        }
        normalize();
        var v = filterValue[channel] + f[6] * 8;
        filterByte[channel] = (filterByte[channel] << 1) | (f[0] & 1);
        factor[channel] += (((v ^ f[0]) >> 31) | 1) & ((v ^ (v - (f[6] * 16))) >> 31);
        f[1] += ((f[0] & VALUE_ONE) - f[1]) >> 6;
        f[2] += ((f[0] & VALUE_ONE) - f[2]) >> 4;
        f[3] += (f[2] - f[3]) >> 4;
        f[4] += (f[3] - f[4]) >> 4;
        v = (f[4] - f[5]) >> 4;
        f[5] += v;
        f[6] += (v - f[6]) >> 3;
        filterValue[channel] = f[1] - f[5] + ((f[6] * factor[channel]) >> 2);
    }

    private static com.github.auties00.smalt.MediaException corrupt() {
        return new com.github.auties00.smalt.MediaException.Malformed("corrupt WavPack DSD block");
    }
}
