package com.github.auties00.smalt.image.raw.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;

/**
 * Writes Samsung's three compressed sensor codings from random choices and works out the photosites they stand for,
 * apart from the decoder: the rows of a color are lines of one linear plane here, and every width code, prediction
 * mode and quantizer field is used, the ends of rows and the limits of the bit depth included.
 */
final class SrwCoding {
    static final int[][] CODES = {
            {0b110100, 6}, {0b11011, 5}, {0b1111, 4}, {0b1100, 4}, {0b000, 3}, {0b10, 2}, {0b01, 2}, {0b001, 3},
            {0b1110, 4}, {0b1101010, 7}, {0b11010110, 8}, {0b110101110, 9}, {0b1101011110, 10}
    };

    /** The strip, the offsets of a 32770 file's coded rows, and the photosites. */
    record Coded(byte[] strip, int[] offsets, short[] samples) {
    }

    private SrwCoding() {
    }

    /** Collects bits into 32-bit little-endian words, most significant bit first. */
    private static final class Words {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long word;
        private int count;

        void write(int value, int bits) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                word = word << 1 | (value >> bit & 1);
                if (++count == 32) {
                    flush();
                }
            }
        }

        private void flush() {
            for (var shift = 0; shift < 32; shift += 8) {
                out.write((int) (word >> shift) & 0xFF);
            }
            word = 0;
            count = 0;
        }

        void pad(int alignment) {
            if (count != 0) {
                word <<= 32 - count;
                flush();
            }
            while (out.size() % alignment != 0) {
                out.write(0);
            }
        }

        int size() {
            return out.size();
        }

        void header(byte[] header) {
            out.writeBytes(header);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** {@return a compression 32772 strip of random differences} */
    static Coded prefixed(int width, int height, int depth, long seed) {
        var random = new SplittableRandom(seed);
        var largest = (1 << depth) - 1;
        var samples = new short[width * height];
        var out = new ByteArrayOutputStream();
        var buffer = 0L;
        var count = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var prediction = x >= 2 ? samples[y * width + x - 2] & 0xFFFF
                        : y >= 2 ? samples[(y - 2) * width + x] & 0xFFFF : 0;
                var category = random.nextInt(4) == 0 ? random.nextInt(13) : random.nextInt(6);
                var code = CODES[category];
                var magnitude = category == 0 ? 0 : (1 << category - 1) + random.nextInt(1 << category - 1);
                var negative = random.nextBoolean();
                var difference = negative ? -magnitude : magnitude;
                buffer = buffer << code[1] | code[0];
                count += code[1];
                if (category != 0) {
                    var bits = negative ? difference + (1 << category) - 1 : difference;
                    buffer = buffer << category | bits;
                    count += category;
                }
                while (count >= 8) {
                    out.write((int) (buffer >> count - 8) & 0xFF);
                    count -= 8;
                }
                samples[y * width + x] = (short) Math.clamp(prediction + difference, 0, largest);
            }
        }
        if (count > 0) {
            out.write((int) (buffer << 8 - count) & 0xFF);
        }
        return new Coded(out.toByteArray(), null, samples);
    }

    /** {@return a 32773 strip of the given bits after its header} */
    static byte[] strip(int width, int height, int depth, boolean quantized, String bits) {
        var words = new Words();
        words.header(header(width, height, depth, quantized, 128));
        for (var index = 0; index < bits.length(); index++) {
            words.write(bits.charAt(index) - '0', 1);
        }
        words.pad(16);
        return words.bytes();
    }

    private static byte[] header(int width, int height, int depth, boolean quantized, int rowStart) {
        var header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) (quantized ? 'E' : 'K')).put((byte) (0x5F + depth)).put((byte) 0x33).put((byte) 0x56);
        header.putShort((short) height).putShort((short) width);
        header.put((byte) 0).put((byte) (quantized ? 0 : 5)).putShort((short) 0);
        header.putShort((short) rowStart).put((byte) 2).put((byte) 1);
        return header.array();
    }

    /** {@return a 32773 strip} */
    static Coded modal(int width, int height, int depth, boolean quantized, int rowStart, long seed) {
        return blocks(width, height, depth, true, quantized, rowStart, header(width, height, depth, quantized, rowStart),
                seed);
    }

    /** {@return a 32770 strip with its row offsets} */
    static Coded interleaved(int width, int height, long seed) {
        var header = new byte[16];
        header[12] = (byte) 0x80;
        return blocks(width, height, 12, false, false, 128, header, seed);
    }

    private static Coded blocks(int width, int height, int depth, boolean modal, boolean quantized, int rowStart,
                                byte[] header, long seed) {
        var random = new SplittableRandom(seed);
        var largest = (1 << depth) - 1;
        var blocks = (width + 15) / 16;
        var line = blocks * 8;
        // three planes: the first runs' rows, and the second runs' rows of each row parity
        var planes = new int[3][(height + 2) * line];
        if (modal) {
            // what the converter reads one sample before the start of the second runs' planes
            planes[1][2 * line - 1] = 1024;
            planes[2][2 * line - 1] = 1024;
        }
        var samples = new short[width * height];
        var words = new Words();
        words.header(header);
        var offsets = new int[height];
        for (var y = 0; y < height; y++) {
            offsets[y] = words.size();
            var widths = new int[4];
            java.util.Arrays.fill(widths, y < 2 ? 7 : 4);
            var left = new int[]{rowStart, rowStart};
            var mode = 7;
            var quantizer = 0;
            for (var block = 0; block < blocks; block++) {
                if (quantized && block % 4 == 0) {
                    var field = block == 0 ? 3 : random.nextInt(8) == 0 ? 1 + random.nextInt(3) : 0;
                    words.write(field, 2);
                    switch (field) {
                        case 1 -> quantizer -= 2;
                        case 2 -> quantizer += 2;
                        case 3 -> {
                            quantizer = random.nextInt(4);
                            words.write(quantizer, 12);
                        }
                        default -> {
                        }
                    }
                }
                if (modal) {
                    var next = y < 2 ? 7 : random.nextInt(8);
                    if (next == mode && random.nextBoolean()) {
                        words.write(1, 1);
                    } else {
                        words.write(0, 1);
                        words.write(next, 3);
                        mode = next;
                    }
                } else {
                    var up = random.nextBoolean();
                    words.write(up ? 1 : 0, 1);
                    mode = up ? 3 : 7;
                }
                if (quantized) {
                    words.write(0, 1);
                }
                var chosen = new int[4];
                var codes = new int[4];
                for (var index = 0; index < 4; index++) {
                    chosen[index] = switch (random.nextInt(4)) {
                        case 0 -> widths[index];
                        case 1 -> Math.min(widths[index] + 1, 15);
                        case 2 -> Math.max(widths[index] - 1, 0);
                        default -> random.nextInt(depth + 1);
                    };
                    codes[index] = chosen[index] == widths[index] && random.nextBoolean() ? 0
                            : chosen[index] == widths[index] + 1 ? 1 : chosen[index] == widths[index] - 1 ? 2 : 3;
                    words.write(codes[index], 2);
                }
                for (var index = 0; index < 4; index++) {
                    if (codes[index] == 3) {
                        words.write(chosen[index], 4);
                    }
                    widths[index] = chosen[index];
                }
                var plane0 = planes[0];
                var plane1 = planes[1 + (y & 1)];
                var last = new int[2];
                for (var run = 0; run < 2; run++) {
                    var plane = run == 0 ? plane0 : plane1;
                    var row = run == 0 ? y + 2 : y / 2 + 2;
                    var referenceRow = row - 1;
                    for (var part = 0; part < 2; part++) {
                        var bits = chosen[2 * run + part];
                        for (var sample = 0; sample < 4; sample++) {
                            var j = block * 8 + part * 4 + sample;
                            var difference = bits == 0 ? 0 : random.nextInt(1 << bits) - (1 << bits - 1);
                            if (bits != 0) {
                                words.write(difference & (1 << bits) - 1, bits);
                            }
                            var at = referenceRow * line + j;
                            var prediction = switch (mode) {
                                case 0 -> plane[at - 2];
                                case 1 -> plane[at - 1];
                                case 2 -> (plane[at - 1] + plane[at] + 1) >> 1;
                                case 3 -> !modal && y == 1 && run == 1 ? planes[1][2 * line + j] : plane[at];
                                case 4 -> (plane[at] + plane[at + 1] + 1) >> 1;
                                case 5 -> plane[at + 1];
                                case 6 -> plane[at + 2];
                                default -> left[run];
                            };
                            var value = Math.clamp(prediction + (2 * quantizer + 1) * difference + quantizer, 0,
                                    largest);
                            plane[row * line + j] = value;
                            last[run] = value;
                            int target;
                            int column;
                            if (modal) {
                                target = y;
                                column = (run ^ y & 1) + 2 * j;
                            } else {
                                target = y - (y & 1) + run;
                                column = (y & 1) + 2 * j;
                            }
                            if (target < height && column < width) {
                                samples[target * width + column] = (short) value;
                            }
                        }
                    }
                }
                left[0] = last[0];
                left[1] = last[1];
            }
            words.pad(modal ? 16 : 8);
        }
        return new Coded(words.bytes(), offsets, samples);
    }
}
