package com.github.auties00.smalt.audio.voice.internal.aecm;

import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Computes the 16-bit fixed-point real-valued FFT of the mobile echo canceller with the exact
 * results of WebRTC's signal processing library.
 *
 * <p>The transform has {@value #SIZE} points ({@code 2^}{@value #ORDER}). The forward
 * transform halves the data with rounding at every stage; the inverse transform scales each
 * stage by {@code 1}, {@code 1/2} or {@code 1/4} depending on the peak magnitude of the data and
 * returns the total number of halvings, so callers can restore the level. An instance owns its
 * scratch buffer and is not thread-safe.
 *
 * @implNote This implementation ports the generic C paths of WebRTC's
 * {@code common_audio/signal_processing} (webrtc-audio-processing 2.1): {@code real_fft.c}
 * ({@code WebRtcSpl_RealForwardFFT}, {@code WebRtcSpl_RealInverseFFT}), {@code complex_fft.c}
 * in its high-accuracy mode ({@code WebRtcSpl_ComplexFFT} and {@code WebRtcSpl_ComplexIFFT}
 * with {@code mode == 1}), {@code complex_bit_reverse.c} and the sine table of
 * {@code complex_fft_tables.h}. The bit reversal computes the swapped index pairs instead of
 * reading the reference's precomputed pair table; both perform the same swaps.
 */
final class AecmFft {
    /**
     * The base-2 logarithm of the transform size.
     */
    static final int ORDER = 7;

    /**
     * The number of real input samples of the forward transform.
     */
    static final int SIZE = 1 << ORDER;

    /**
     * The sine of {@code 2 * pi * k / 1024} in Q15 for {@code k} in {@code [0, 1024)}, the twiddle
     * source of the complex transforms ({@code kSinTable1024}).
     */
    private static final short[] SIN_TABLE_1024 = {
            0, 201, 402, 603, 804, 1005, 1206, 1406, 1607, 1808, 2009, 2209,
            2410, 2610, 2811, 3011, 3211, 3411, 3611, 3811, 4011, 4210, 4409, 4608,
            4807, 5006, 5205, 5403, 5601, 5799, 5997, 6195, 6392, 6589, 6786, 6982,
            7179, 7375, 7571, 7766, 7961, 8156, 8351, 8545, 8739, 8932, 9126, 9319,
            9511, 9703, 9895, 10087, 10278, 10469, 10659, 10849, 11038, 11227, 11416, 11604,
            11792, 11980, 12166, 12353, 12539, 12724, 12909, 13094, 13278, 13462, 13645, 13827,
            14009, 14191, 14372, 14552, 14732, 14911, 15090, 15268, 15446, 15623, 15799, 15975,
            16150, 16325, 16499, 16672, 16845, 17017, 17189, 17360, 17530, 17699, 17868, 18036,
            18204, 18371, 18537, 18702, 18867, 19031, 19194, 19357, 19519, 19680, 19840, 20000,
            20159, 20317, 20474, 20631, 20787, 20942, 21096, 21249, 21402, 21554, 21705, 21855,
            22004, 22153, 22301, 22448, 22594, 22739, 22883, 23027, 23169, 23311, 23452, 23592,
            23731, 23869, 24006, 24143, 24278, 24413, 24546, 24679, 24811, 24942, 25072, 25201,
            25329, 25456, 25582, 25707, 25831, 25954, 26077, 26198, 26318, 26437, 26556, 26673,
            26789, 26905, 27019, 27132, 27244, 27355, 27466, 27575, 27683, 27790, 27896, 28001,
            28105, 28208, 28309, 28410, 28510, 28608, 28706, 28802, 28897, 28992, 29085, 29177,
            29268, 29358, 29446, 29534, 29621, 29706, 29790, 29873, 29955, 30036, 30116, 30195,
            30272, 30349, 30424, 30498, 30571, 30643, 30713, 30783, 30851, 30918, 30984, 31049,
            31113, 31175, 31236, 31297, 31356, 31413, 31470, 31525, 31580, 31633, 31684, 31735,
            31785, 31833, 31880, 31926, 31970, 32014, 32056, 32097, 32137, 32176, 32213, 32249,
            32284, 32318, 32350, 32382, 32412, 32441, 32468, 32495, 32520, 32544, 32567, 32588,
            32609, 32628, 32646, 32662, 32678, 32692, 32705, 32717, 32727, 32736, 32744, 32751,
            32757, 32761, 32764, 32766, 32767, 32766, 32764, 32761, 32757, 32751, 32744, 32736,
            32727, 32717, 32705, 32692, 32678, 32662, 32646, 32628, 32609, 32588, 32567, 32544,
            32520, 32495, 32468, 32441, 32412, 32382, 32350, 32318, 32284, 32249, 32213, 32176,
            32137, 32097, 32056, 32014, 31970, 31926, 31880, 31833, 31785, 31735, 31684, 31633,
            31580, 31525, 31470, 31413, 31356, 31297, 31236, 31175, 31113, 31049, 30984, 30918,
            30851, 30783, 30713, 30643, 30571, 30498, 30424, 30349, 30272, 30195, 30116, 30036,
            29955, 29873, 29790, 29706, 29621, 29534, 29446, 29358, 29268, 29177, 29085, 28992,
            28897, 28802, 28706, 28608, 28510, 28410, 28309, 28208, 28105, 28001, 27896, 27790,
            27683, 27575, 27466, 27355, 27244, 27132, 27019, 26905, 26789, 26673, 26556, 26437,
            26318, 26198, 26077, 25954, 25831, 25707, 25582, 25456, 25329, 25201, 25072, 24942,
            24811, 24679, 24546, 24413, 24278, 24143, 24006, 23869, 23731, 23592, 23452, 23311,
            23169, 23027, 22883, 22739, 22594, 22448, 22301, 22153, 22004, 21855, 21705, 21554,
            21402, 21249, 21096, 20942, 20787, 20631, 20474, 20317, 20159, 20000, 19840, 19680,
            19519, 19357, 19194, 19031, 18867, 18702, 18537, 18371, 18204, 18036, 17868, 17699,
            17530, 17360, 17189, 17017, 16845, 16672, 16499, 16325, 16150, 15975, 15799, 15623,
            15446, 15268, 15090, 14911, 14732, 14552, 14372, 14191, 14009, 13827, 13645, 13462,
            13278, 13094, 12909, 12724, 12539, 12353, 12166, 11980, 11792, 11604, 11416, 11227,
            11038, 10849, 10659, 10469, 10278, 10087, 9895, 9703, 9511, 9319, 9126, 8932,
            8739, 8545, 8351, 8156, 7961, 7766, 7571, 7375, 7179, 6982, 6786, 6589,
            6392, 6195, 5997, 5799, 5601, 5403, 5205, 5006, 4807, 4608, 4409, 4210,
            4011, 3811, 3611, 3411, 3211, 3011, 2811, 2610, 2410, 2209, 2009, 1808,
            1607, 1406, 1206, 1005, 804, 603, 402, 201, 0, -201, -402, -603,
            -804, -1005, -1206, -1406, -1607, -1808, -2009, -2209, -2410, -2610, -2811, -3011,
            -3211, -3411, -3611, -3811, -4011, -4210, -4409, -4608, -4807, -5006, -5205, -5403,
            -5601, -5799, -5997, -6195, -6392, -6589, -6786, -6982, -7179, -7375, -7571, -7766,
            -7961, -8156, -8351, -8545, -8739, -8932, -9126, -9319, -9511, -9703, -9895, -10087,
            -10278, -10469, -10659, -10849, -11038, -11227, -11416, -11604, -11792, -11980, -12166, -12353,
            -12539, -12724, -12909, -13094, -13278, -13462, -13645, -13827, -14009, -14191, -14372, -14552,
            -14732, -14911, -15090, -15268, -15446, -15623, -15799, -15975, -16150, -16325, -16499, -16672,
            -16845, -17017, -17189, -17360, -17530, -17699, -17868, -18036, -18204, -18371, -18537, -18702,
            -18867, -19031, -19194, -19357, -19519, -19680, -19840, -20000, -20159, -20317, -20474, -20631,
            -20787, -20942, -21096, -21249, -21402, -21554, -21705, -21855, -22004, -22153, -22301, -22448,
            -22594, -22739, -22883, -23027, -23169, -23311, -23452, -23592, -23731, -23869, -24006, -24143,
            -24278, -24413, -24546, -24679, -24811, -24942, -25072, -25201, -25329, -25456, -25582, -25707,
            -25831, -25954, -26077, -26198, -26318, -26437, -26556, -26673, -26789, -26905, -27019, -27132,
            -27244, -27355, -27466, -27575, -27683, -27790, -27896, -28001, -28105, -28208, -28309, -28410,
            -28510, -28608, -28706, -28802, -28897, -28992, -29085, -29177, -29268, -29358, -29446, -29534,
            -29621, -29706, -29790, -29873, -29955, -30036, -30116, -30195, -30272, -30349, -30424, -30498,
            -30571, -30643, -30713, -30783, -30851, -30918, -30984, -31049, -31113, -31175, -31236, -31297,
            -31356, -31413, -31470, -31525, -31580, -31633, -31684, -31735, -31785, -31833, -31880, -31926,
            -31970, -32014, -32056, -32097, -32137, -32176, -32213, -32249, -32284, -32318, -32350, -32382,
            -32412, -32441, -32468, -32495, -32520, -32544, -32567, -32588, -32609, -32628, -32646, -32662,
            -32678, -32692, -32705, -32717, -32727, -32736, -32744, -32751, -32757, -32761, -32764, -32766,
            -32767, -32766, -32764, -32761, -32757, -32751, -32744, -32736, -32727, -32717, -32705, -32692,
            -32678, -32662, -32646, -32628, -32609, -32588, -32567, -32544, -32520, -32495, -32468, -32441,
            -32412, -32382, -32350, -32318, -32284, -32249, -32213, -32176, -32137, -32097, -32056, -32014,
            -31970, -31926, -31880, -31833, -31785, -31735, -31684, -31633, -31580, -31525, -31470, -31413,
            -31356, -31297, -31236, -31175, -31113, -31049, -30984, -30918, -30851, -30783, -30713, -30643,
            -30571, -30498, -30424, -30349, -30272, -30195, -30116, -30036, -29955, -29873, -29790, -29706,
            -29621, -29534, -29446, -29358, -29268, -29177, -29085, -28992, -28897, -28802, -28706, -28608,
            -28510, -28410, -28309, -28208, -28105, -28001, -27896, -27790, -27683, -27575, -27466, -27355,
            -27244, -27132, -27019, -26905, -26789, -26673, -26556, -26437, -26318, -26198, -26077, -25954,
            -25831, -25707, -25582, -25456, -25329, -25201, -25072, -24942, -24811, -24679, -24546, -24413,
            -24278, -24143, -24006, -23869, -23731, -23592, -23452, -23311, -23169, -23027, -22883, -22739,
            -22594, -22448, -22301, -22153, -22004, -21855, -21705, -21554, -21402, -21249, -21096, -20942,
            -20787, -20631, -20474, -20317, -20159, -20000, -19840, -19680, -19519, -19357, -19194, -19031,
            -18867, -18702, -18537, -18371, -18204, -18036, -17868, -17699, -17530, -17360, -17189, -17017,
            -16845, -16672, -16499, -16325, -16150, -15975, -15799, -15623, -15446, -15268, -15090, -14911,
            -14732, -14552, -14372, -14191, -14009, -13827, -13645, -13462, -13278, -13094, -12909, -12724,
            -12539, -12353, -12166, -11980, -11792, -11604, -11416, -11227, -11038, -10849, -10659, -10469,
            -10278, -10087, -9895, -9703, -9511, -9319, -9126, -8932, -8739, -8545, -8351, -8156,
            -7961, -7766, -7571, -7375, -7179, -6982, -6786, -6589, -6392, -6195, -5997, -5799,
            -5601, -5403, -5205, -5006, -4807, -4608, -4409, -4210, -4011, -3811, -3611, -3411,
            -3211, -3011, -2811, -2610, -2410, -2209, -2009, -1808, -1607, -1406, -1206, -1005,
            -804, -603, -402, -201
    };

    /**
     * The interleaved real and imaginary parts of the complex transform, {@code 2 * SIZE} values.
     */
    private final short[] complex = new short[2 * SIZE];

    /**
     * Creates a transform with its own scratch buffer ({@code WebRtcSpl_CreateRealFFT} of order
     * {@value #ORDER}).
     */
    AecmFft() {
    }

    /**
     * Computes the forward transform of {@value #SIZE} real samples
     * ({@code WebRtcSpl_RealForwardFFT}).
     *
     * @param in       the real input samples
     * @param inOffset the index of the first input sample
     * @param out      receives {@code SIZE + 2} values: the interleaved real and imaginary parts of
     *                 bins {@code 0} through {@code SIZE / 2}
     */
    void forward(short[] in, int inOffset, short[] out) {
        var buffer = complex;
        for (int i = 0, j = 0; i < SIZE; i++, j += 2) {
            buffer[j] = in[inOffset + i];
            buffer[j + 1] = 0;
        }
        bitReverse(buffer);
        complexFft(buffer);
        System.arraycopy(buffer, 0, out, 0, SIZE + 2);
    }

    /**
     * Computes the inverse transform of a conjugate-symmetric spectrum given by its first half
     * ({@code WebRtcSpl_RealInverseFFT}).
     *
     * @param in  the interleaved real and imaginary parts of bins {@code 0} through
     *            {@code SIZE / 2}, {@code SIZE + 2} values
     * @param out receives the {@value #SIZE} real output samples
     * @return the number of halvings the inverse transform applied
     */
    int inverse(short[] in, short[] out) {
        var buffer = complex;
        System.arraycopy(in, 0, buffer, 0, SIZE + 2);
        for (var i = SIZE + 2; i < 2 * SIZE; i += 2) {
            buffer[i] = in[2 * SIZE - i];
            buffer[i + 1] = (short) -in[2 * SIZE - i + 1];
        }
        bitReverse(buffer);
        var result = complexIfft(buffer);
        for (int i = 0, j = 0; i < SIZE; i++, j += 2) {
            out[i] = buffer[j];
        }
        return result;
    }

    /**
     * Reorders the complex values into bit-reversed index order
     * ({@code WebRtcSpl_ComplexBitReverse}).
     *
     * @param data the interleaved complex values
     */
    private static void bitReverse(short[] data) {
        for (var m = 1; m < SIZE; m++) {
            var reversed = Integer.reverse(m) >>> (32 - ORDER);
            if (reversed <= m) {
                continue;
            }
            var real = data[2 * m];
            var imag = data[2 * m + 1];
            data[2 * m] = data[2 * reversed];
            data[2 * m + 1] = data[2 * reversed + 1];
            data[2 * reversed] = real;
            data[2 * reversed + 1] = imag;
        }
    }

    /**
     * Computes the in-place decimation-in-time forward transform of bit-reversed complex data,
     * halving with rounding at every stage ({@code WebRtcSpl_ComplexFFT}, high-accuracy mode).
     *
     * @param frfi the interleaved complex values
     */
    private static void complexFft(short[] frfi) {
        var l = 1;
        var k = 10 - 1;
        while (l < SIZE) {
            var istep = l << 1;
            for (var m = 0; m < l; ++m) {
                var j = m << k;
                int wr = SIN_TABLE_1024[j + 256];
                int wi = (short) -SIN_TABLE_1024[j];
                for (var i = m; i < SIZE; i += istep) {
                    j = i + l;
                    var tr32 = wr * frfi[2 * j] - wi * frfi[2 * j + 1] + 1;
                    var ti32 = wr * frfi[2 * j + 1] + wi * frfi[2 * j] + 1;
                    tr32 >>= 1;
                    ti32 >>= 1;
                    var qr32 = frfi[2 * i] * (1 << 14);
                    var qi32 = frfi[2 * i + 1] * (1 << 14);
                    frfi[2 * j] = (short) ((qr32 - tr32 + 16384) >> 15);
                    frfi[2 * j + 1] = (short) ((qi32 - ti32 + 16384) >> 15);
                    frfi[2 * i] = (short) ((qr32 + tr32 + 16384) >> 15);
                    frfi[2 * i + 1] = (short) ((qi32 + ti32 + 16384) >> 15);
                }
            }
            --k;
            l = istep;
        }
    }

    /**
     * Computes the in-place decimation-in-time inverse transform of bit-reversed complex data,
     * scaling each stage by the peak magnitude ({@code WebRtcSpl_ComplexIFFT}, high-accuracy
     * mode).
     *
     * @param frfi the interleaved complex values
     * @return the number of halvings applied
     */
    private static int complexIfft(short[] frfi) {
        var scale = 0;
        var l = 1;
        var k = 10 - 1;
        while (l < SIZE) {
            var shift = 0;
            var round2 = 8192;
            int peak = FixedPointMath.maxAbsValueW16(frfi, 0, 2 * SIZE);
            if (peak > 13573) {
                shift++;
                scale++;
                round2 <<= 1;
            }
            if (peak > 27146) {
                shift++;
                scale++;
                round2 <<= 1;
            }
            var istep = l << 1;
            for (var m = 0; m < l; ++m) {
                var j = m << k;
                int wr = SIN_TABLE_1024[j + 256];
                int wi = SIN_TABLE_1024[j];
                for (var i = m; i < SIZE; i += istep) {
                    j = i + l;
                    var tr32 = wr * frfi[2 * j] - wi * frfi[2 * j + 1] + 1;
                    var ti32 = wr * frfi[2 * j + 1] + wi * frfi[2 * j] + 1;
                    tr32 >>= 1;
                    ti32 >>= 1;
                    var qr32 = frfi[2 * i] * (1 << 14);
                    var qi32 = frfi[2 * i + 1] * (1 << 14);
                    frfi[2 * j] = (short) ((qr32 - tr32 + round2) >> (shift + 14));
                    frfi[2 * j + 1] = (short) ((qi32 - ti32 + round2) >> (shift + 14));
                    frfi[2 * i] = (short) ((qr32 + tr32 + round2) >> (shift + 14));
                    frfi[2 * i + 1] = (short) ((qi32 + ti32 + round2) >> (shift + 14));
                }
            }
            --k;
            l = istep;
        }
        return scale;
    }
}
