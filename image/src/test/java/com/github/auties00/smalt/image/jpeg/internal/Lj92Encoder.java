package com.github.auties00.smalt.image.jpeg.internal;

import java.io.ByteArrayOutputStream;

/**
 * Encodes lossless JPEG (ITU-T T.81 process 14) streams for the tests: any predictor, one to four
 * interleaved components, a restart interval and a point transform, with a fixed Huffman table in
 * which every difference category has a 5-bit code.
 */
final class Lj92Encoder {
    private Lj92Encoder() {
    }

    static byte[] encode(int[] samples, int width, int height, int components, int precision, int predictor,
                                int restart, int transform) {
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        marker(out, 0xC3);
        u16(out, 8 + 3 * components);
        out.write(precision);
        u16(out, height);
        u16(out, width);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(c + 1);
            out.write(0x11);
            out.write(0);
        }
        marker(out, 0xC4);
        u16(out, 2 + 17 + 17);
        out.write(0);
        for (var length = 1; length <= 16; length++) {
            out.write(length == 5 ? 17 : 0);
        }
        for (var value = 0; value <= 16; value++) {
            out.write(value);
        }
        if (restart > 0) {
            marker(out, 0xDD);
            u16(out, 4);
            u16(out, restart);
        }
        marker(out, 0xDA);
        u16(out, 6 + 2 * components);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(c + 1);
            out.write(0);
        }
        out.write(predictor);
        out.write(0);
        out.write(transform);
        var bits = new Bits(out);
        var stride = width * components;
        var shifted = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            shifted[i] = samples[i] >> transform;
        }
        var initial = 1 << (precision - transform - 1);
        var mcu = 0;
        var restartIndex = 0;
        var restartRow = -1;
        var restartColumn = -1;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                if (restart > 0 && mcu > 0 && mcu % restart == 0) {
                    bits.flush();
                    marker(out, 0xD0 + (restartIndex++ & 7));
                    restartRow = row;
                    restartColumn = column;
                }
                for (var c = 0; c < components; c++) {
                    var index = row * stride + column * components + c;
                    int prediction;
                    var fresh = row == restartRow && column == restartColumn;
                    if ((row == 0 && column == 0) || fresh) {
                        prediction = initial;
                    } else if (row == 0 || (row == restartRow && column > restartColumn)) {
                        prediction = shifted[index - components];
                    } else if (column == 0 || (row == restartRow + 1 && restartRow >= 0 && column == 0)) {
                        prediction = shifted[index - stride];
                    } else {
                        var a = shifted[index - components];
                        var b = shifted[index - stride];
                        var cc = shifted[index - stride - components];
                        prediction = switch (predictor) {
                            case 1 -> a;
                            case 2 -> b;
                            case 3 -> cc;
                            case 4 -> a + b - cc;
                            case 5 -> a + ((b - cc) >> 1);
                            case 6 -> b + ((a - cc) >> 1);
                            default -> (a + b) >> 1;
                        };
                    }
                    var difference = (shifted[index] - prediction) & 0xFFFF;
                    if (difference >= 32768) {
                        difference -= 65536;
                    }
                    if (difference == -32768) {
                        bits.write(16, 5);
                        continue;
                    }
                    var magnitude = Math.abs(difference);
                    var category = magnitude == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(magnitude);
                    bits.write(category, 5);
                    if (category > 0) {
                        var value = difference >= 0 ? difference : difference + (1 << category) - 1;
                        bits.write(value, category);
                    }
                }
                mcu++;
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    private static void marker(ByteArrayOutputStream out, int marker) {
        out.write(0xFF);
        out.write(marker);
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }

    private static final class Bits {
        private final ByteArrayOutputStream out;
        private long buffer;
        private int count;

        Bits(ByteArrayOutputStream out) {
            this.out = out;
        }

        void write(int value, int bits) {
            buffer = (buffer << bits) | (value & ((1L << bits) - 1));
            count += bits;
            while (count >= 8) {
                var b = (int) (buffer >>> (count - 8)) & 0xFF;
                out.write(b);
                if (b == 0xFF) {
                    out.write(0);
                }
                count -= 8;
            }
        }

        void flush() {
            if (count > 0) {
                write((1 << (8 - count)) - 1, 8 - count);
            }
            buffer = 0;
            count = 0;
        }
    }
}
