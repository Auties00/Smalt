package com.github.auties00.smalt.aot.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the DSD and DST streams the training workload of the DST decoder decodes, deterministically: DSD from a
 * second-order sigma-delta modulator of synthetic tones, and DST frames (ISO/IEC 14496-3 subpart 10) coding it.
 *
 * <p>Each DST frame has one prediction filter and one probability table per channel, mapped over the whole frame:
 * the filter's coefficients are the scaled autocorrelation of the channel's bits in the frame, the table's entries
 * the measured probability of each residual given the prediction's magnitude. The first channel's filter and table
 * are stored plainly, the others Rice coded, so the frames exercise both codings of the frame header.
 */
final class SyntheticDst {
    /**
     * The number of filter coefficients.
     */
    private static final int ORDER = 32;

    /**
     * The number of probability table entries.
     */
    private static final int TABLE = 48;

    /**
     * The bits of the arithmetic coder's probabilities.
     */
    private static final int PBITS = 8;

    /**
     * The bits of the arithmetic coder's interval.
     */
    private static final int ABITS = PBITS + 4;

    /**
     * The arithmetic coder's interval of one.
     */
    private static final int ONE = 1 << ABITS;

    /**
     * The arithmetic coder's interval of one half.
     */
    private static final int HALF = 1 << (ABITS - 1);

    /**
     * The prediction of the first coefficient of a Rice-coded filter (method 0) and of a Rice-coded table (method
     * 0): the previous value.
     */
    private static final int RICE_TAP = -8;

    /**
     * The probability of the decoder's initial dummy decision, by the first filter coefficient: the 7-bit reversal of
     * its low bits plus one; a copy of the decoder's table.
     */
    private static final int[] REVERSE = {
            1, 65, 33, 97, 17, 81, 49, 113, 9, 73, 41, 105, 25, 89, 57, 121,
            5, 69, 37, 101, 21, 85, 53, 117, 13, 77, 45, 109, 29, 93, 61, 125,
            3, 67, 35, 99, 19, 83, 51, 115, 11, 75, 43, 107, 27, 91, 59, 123,
            7, 71, 39, 103, 23, 87, 55, 119, 15, 79, 47, 111, 31, 95, 63, 127,
            2, 66, 34, 98, 18, 82, 50, 114, 10, 74, 42, 106, 26, 90, 58, 122,
            6, 70, 38, 102, 22, 86, 54, 118, 14, 78, 46, 110, 30, 94, 62, 126,
            4, 68, 36, 100, 20, 84, 52, 116, 12, 76, 44, 108, 28, 92, 60, 124,
            8, 72, 40, 104, 24, 88, 56, 120, 16, 80, 48, 112, 32, 96, 64, 128};

    /**
     * Prevents instantiation.
     */
    private SyntheticDst() {
        throw new AssertionError();
    }

    /**
     * Returns DSD of synthetic tones: a second-order sigma-delta modulation of a sum of two sines per channel.
     *
     * @param channels the number of channels
     * @param rate the DSD rate in bits per second per channel
     * @param bytes the number of bytes per channel
     * @return the DSD, one byte of each channel in turn, most significant bit first
     */
    static byte[] dsd(int channels, int rate, int bytes) {
        var out = new byte[bytes * channels];
        for (var c = 0; c < channels; c++) {
            var first = 0.0;
            var second = 0.0;
            var output = 1.0;
            for (var n = 0; n < bytes * 8; n++) {
                var t = (double) n / rate;
                var x = 0.3 * StrictMath.sin(2 * StrictMath.PI * (440 + 110 * c) * t)
                        + 0.15 * StrictMath.sin(2 * StrictMath.PI * 3150 * t);
                first += x - output;
                second += first - output;
                output = second >= 0 ? 1 : -1;
                if (output > 0) {
                    out[(n >>> 3) * channels + c] |= (byte) (0x80 >>> (n & 7));
                }
            }
        }
        return out;
    }

    /**
     * Codes DSD as DST frames.
     *
     * @param dsd the DSD, one byte of each channel in turn, a whole number of frames
     * @param channels the number of channels
     * @param frameBytes the bytes of a frame per channel, the DSD rate divided by 600
     * @return the frames
     */
    static List<byte[]> encode(byte[] dsd, int channels, int frameBytes) {
        var frames = new ArrayList<byte[]>();
        var size = frameBytes * channels;
        for (var start = 0; start + size <= dsd.length; start += size) {
            frames.add(frame(dsd, start, channels, frameBytes));
        }
        return frames;
    }

    /**
     * Codes one DST frame.
     *
     * @param dsd the DSD
     * @param start the index of the frame's first byte
     * @param channels the number of channels
     * @param frameBytes the bytes of the frame per channel
     * @return the frame
     */
    private static byte[] frame(byte[] dsd, int start, int channels, int frameBytes) {
        var bitsPerChannel = frameBytes * 8;
        var coefficients = new int[channels][];
        for (var c = 0; c < channels; c++) {
            coefficients[c] = filter(dsd, start, channels, c, bitsPerChannel);
        }
        var tables = new short[channels][];
        for (var c = 0; c < channels; c++) {
            tables[c] = filterTable(coefficients[c]);
        }
        // the first pass measures the residuals of each prediction magnitude, the second codes them
        var zeros = new long[channels][TABLE];
        var totals = new long[channels][TABLE];
        predict(dsd, start, channels, bitsPerChannel, tables, (c, index, residual) -> {
            totals[c][index]++;
            if (residual == 0) {
                zeros[c][index]++;
            }
        });
        var probabilities = new int[channels][TABLE];
        for (var c = 0; c < channels; c++) {
            for (var index = 0; index < TABLE; index++) {
                var p = totals[c][index] == 0 ? 64 : (int) Math.round(256.0 * zeros[c][index] / totals[c][index]);
                probabilities[c][index] = Math.clamp(p, 1, 128);
            }
        }
        var header = new BitWriter(false);
        header.write(1, 1);
        // the segmentation: the same for the tables as for the filters, one segment shared by every channel
        header.write(1, 1);
        header.write(1, 1);
        header.write(1, 1);
        // the mapping: the same for the tables, one filter per channel, then no half-probability start
        header.write(1, 1);
        header.write(0, 1);
        var count = 1;
        for (var c = 1; c < channels; c++) {
            header.write(count, log2RoundUp(count));
            count++;
        }
        for (var c = 0; c < channels; c++) {
            header.write(0, 1);
        }
        for (var c = 0; c < channels; c++) {
            header.write(ORDER - 1, 7);
            writeValues(header, coefficients[c], c > 0, 9, false);
        }
        for (var c = 0; c < channels; c++) {
            header.write(TABLE - 1, 6);
            writeValues(header, probabilities[c], c > 0, 7, true);
        }
        var coder = new Arithmetic();
        coder.encode(0, REVERSE[(coefficients[0][0] + (1 << 9)) & 127]);
        predict(dsd, start, channels, bitsPerChannel, tables,
                (c, index, residual) -> coder.encode(residual, probabilities[c][index]));
        var bits = coder.finish();
        var frame = new BitWriter(false);
        var headerBits = header.bits();
        var headerBytes = header.bytes();
        for (var bit = 0L; bit < headerBits; bit++) {
            frame.write(headerBytes[(int) (bit >>> 3)] >>> (7 - (int) (bit & 7)), 1);
        }
        for (var bit : bits) {
            frame.write(bit, 1);
        }
        return frame.bytes();
    }

    /**
     * Returns the prediction filter of a channel: its bits' autocorrelation at lags 1 to {@link #ORDER}, scaled
     * to 9-bit coefficients.
     *
     * @param dsd the DSD
     * @param start the index of the frame's first byte
     * @param channels the number of channels
     * @param channel the channel
     * @param bits the bits of the frame per channel
     * @return the coefficients
     */
    private static int[] filter(byte[] dsd, int start, int channels, int channel, int bits) {
        var coefficients = new int[ORDER];
        for (var lag = 1; lag <= ORDER; lag++) {
            var sum = 0L;
            for (var n = lag; n < bits; n++) {
                sum += sign(dsd, start, channels, channel, n) * sign(dsd, start, channels, channel, n - lag);
            }
            coefficients[lag - 1] = Math.clamp(Math.round(160.0 * sum / (bits - lag)), -256, 255);
        }
        return coefficients;
    }

    /**
     * {@return a DSD bit as +1 or -1}
     *
     * @param dsd the DSD
     * @param start the index of the frame's first byte
     * @param channels the number of channels
     * @param channel the channel
     * @param n the bit of the channel in the frame
     */
    private static int sign(byte[] dsd, int start, int channels, int channel, int n) {
        return (dsd[start + (n >>> 3) * channels + channel] >>> (7 - (n & 7)) & 1) * 2 - 1;
    }

    /**
     * Returns the prediction of every value of eight history bits for each byte of the history, as the decoder
     * tabulates a filter.
     *
     * @param coefficients the filter
     * @return the table: 16 blocks of 256 values
     */
    private static short[] filterTable(int[] coefficients) {
        var table = new short[16 * 256];
        for (var t = 0; t < 16; t++) {
            var k = Math.max(0, Math.min(8, coefficients.length - t * 8));
            for (var i = 0; i < 256; i++) {
                var value = 0;
                for (var j = 0; j < k; j++) {
                    value += (((i >> j) & 1) * 2 - 1) * coefficients[t * 8 + j];
                }
                table[t * 256 + i] = (short) value;
            }
        }
        return table;
    }

    /**
     * Receives the residual of one bit.
     */
    @FunctionalInterface
    private interface Residuals {
        /**
         * Receives a residual.
         *
         * @param channel the channel
         * @param index the probability table entry of the prediction's magnitude
         * @param residual the residual, 0 or 1
         */
        void accept(int channel, int index, int residual);
    }

    /**
     * Predicts every bit of a frame as the decoder does and hands out its residual: the bit exclusive-or the
     * prediction's sign.
     *
     * @param dsd the DSD
     * @param start the index of the frame's first byte
     * @param channels the number of channels
     * @param bits the bits of the frame per channel
     * @param tables the filter table of each channel
     * @param residuals receives the residuals, bit by bit and channel by channel within a bit
     */
    private static void predict(byte[] dsd, int start, int channels, int bits, short[][] tables, Residuals residuals) {
        var low = new long[channels];
        var high = new long[channels];
        java.util.Arrays.fill(low, 0xAAAAAAAAAAAAAAAAL);
        java.util.Arrays.fill(high, 0xAAAAAAAAAAAAAAAAL);
        var blocks = (ORDER + 7) >>> 3;
        for (var n = 0; n < bits; n++) {
            for (var c = 0; c < channels; c++) {
                var predict = 0;
                for (var t = 0; t < blocks; t++) {
                    var history = t < 8 ? low[c] >>> (8 * t) : high[c] >>> (8 * (t - 8));
                    predict += tables[c][t * 256 + (int) (history & 0xFF)];
                }
                var prediction = (short) predict;
                var value = dsd[start + (n >>> 3) * channels + c] >>> (7 - (n & 7)) & 1;
                var residual = value ^ ((prediction & 0xFFFF) >>> 15);
                residuals.accept(c, Math.min(Math.abs((int) prediction) >> 3, TABLE - 1), residual);
                high[c] = (high[c] << 1) | (low[c] >>> 63);
                low[c] = (low[c] << 1) | value;
            }
        }
    }

    /**
     * Writes filter coefficients or probability table entries, plainly or Rice coded from the previous value.
     *
     * @param bits the output
     * @param values the values
     * @param rice whether to Rice code them
     * @param width the bits of a plainly stored value
     * @param probabilities whether the values are probabilities, stored less one
     */
    private static void writeValues(BitWriter bits, int[] values, boolean rice, int width, boolean probabilities) {
        bits.write(rice ? 1 : 0, 1);
        var offset = probabilities ? 1 : 0;
        if (!rice) {
            for (var value : values) {
                bits.write(value - offset, width);
            }
            return;
        }
        bits.write(0, 2);
        bits.write(values[0] - offset, width);
        var residuals = new int[values.length - 1];
        for (var index = 1; index < values.length; index++) {
            var x = RICE_TAP * values[index - 1];
            residuals[index - 1] = x >= 0 ? values[index] + (x + 4) / 8 : values[index] - (-x + 3) / 8;
        }
        var best = 0;
        var bestBits = Long.MAX_VALUE;
        for (var m = 0; m < 8; m++) {
            var total = 0L;
            for (var residual : residuals) {
                total += (Math.abs(residual) >>> m) + 1 + m + (residual != 0 ? 1 : 0);
            }
            if (total < bestBits) {
                bestBits = total;
                best = m;
            }
        }
        bits.write(best, 3);
        for (var residual : residuals) {
            var magnitude = Math.abs(residual);
            for (var run = magnitude >>> best; run > 0; run--) {
                bits.write(0, 1);
            }
            bits.write(1, 1);
            bits.write(magnitude & ((1 << best) - 1), best);
            if (magnitude != 0) {
                bits.write(residual < 0 ? 1 : 0, 1);
            }
        }
    }

    /**
     * {@return the number of bits of a value, as the decoder sizes a field that holds values below it}
     *
     * @param x the value
     */
    private static int log2RoundUp(long x) {
        var y = 0;
        while (x >= 1L << y) {
            y++;
        }
        return y;
    }

    /**
     * The DST arithmetic coder: the inverse of the decoder's, the code bits kept one per byte so a carry can
     * reach back into the bits already written. Instances are not thread-safe.
     */
    private static final class Arithmetic {
        /**
         * The code bits written, the first a zero that a carry never reaches.
         */
        private final ByteArrayOutputStream code = new ByteArrayOutputStream();

        /**
         * The code bits as an array, while a carry is being propagated.
         */
        private byte[] pending = new byte[1024];

        /**
         * The number of code bits.
         */
        private int length = 1;

        /**
         * The width of the interval.
         */
        private int range = ONE - 1;

        /**
         * The low end of the interval.
         */
        private int low;

        /**
         * Codes one decision.
         *
         * @param bit the decision, 0 or 1
         * @param p the probability of 0, out of 256
         */
        void encode(int bit, int p) {
            var share = ((range >>> PBITS) | ((range >>> (PBITS - 1)) & 1)) * p;
            var rest = range - share;
            if (bit == 0) {
                low += rest;
                range = share;
            } else {
                range = rest;
            }
            if (low >= ONE) {
                low -= ONE;
                carry();
            }
            while (range < HALF) {
                range <<= 1;
                emit((low >>> (ABITS - 1)) & 1);
                low = (low << 1) & (ONE - 1);
            }
        }

        /**
         * Adds one to the code written so far.
         */
        private void carry() {
            var index = length - 1;
            while (pending[index] == 1) {
                pending[index] = 0;
                index--;
            }
            if (index == 0) {
                throw new IllegalStateException("the DST code overflows");
            }
            pending[index] = 1;
        }

        /**
         * Appends a code bit.
         *
         * @param bit the bit
         */
        private void emit(int bit) {
            if (length == pending.length) {
                pending = java.util.Arrays.copyOf(pending, length * 2);
            }
            pending[length++] = (byte) bit;
        }

        /**
         * Writes the low end of the interval as the code's last bits and returns the code.
         *
         * @return the code bits, one per element
         */
        byte[] finish() {
            for (var k = ABITS - 1; k >= 0; k--) {
                emit((low >>> k) & 1);
            }
            code.write(pending, 0, length);
            return code.toByteArray();
        }
    }
}
