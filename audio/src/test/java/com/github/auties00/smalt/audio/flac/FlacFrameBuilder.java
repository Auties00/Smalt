package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.internal.header.Crc16;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A deliberately literal FLAC frame writer used as a test oracle: every coding decision
 * (predictor, wasted bits, residual method, partition order, Rice parameter or escape width) is
 * given explicitly, so decoder tests can reach each syntax element with known samples.
 */
final class FlacFrameBuilder {
    private FlacFrameBuilder() {
        throw new AssertionError();
    }

    /** A subframe predictor. */
    record Predictor(int kind, int order, int precision, int shift, int[] coefficients) {
        static Predictor constant() {
            return new Predictor(0, 0, 0, 0, new int[0]);
        }

        static Predictor verbatim() {
            return new Predictor(1, 0, 0, 0, new int[0]);
        }

        static Predictor fixed(int order) {
            return new Predictor(2, order, 0, 0, new int[0]);
        }

        static Predictor lpc(int precision, int shift, int... coefficients) {
            return new Predictor(3, coefficients.length, precision, shift, coefficients);
        }
    }

    /**
     * The residual coding: method 0 (4-bit parameters) or 1 (5-bit), the partition order, and
     * either a fixed parameter, an escape width (every partition escaped), or automatic
     * parameters ({@code parameter < 0} and {@code escapeBits < 0}).
     */
    record Rice(int method, int partitionOrder, int parameter, int escapeBits) {
        static Rice auto(int method, int partitionOrder) {
            return new Rice(method, partitionOrder, -1, -1);
        }
    }

    /** One subframe's coding. */
    record Subframe(Predictor predictor, int wasted, Rice rice) {
        static Subframe of(Predictor predictor) {
            return new Subframe(predictor, 0, Rice.auto(0, 0));
        }
    }

    /**
     * Builds a frame.
     *
     * @param variable     whether to set the variable blocking bit ({@code number} is then a sample
     *                     number)
     * @param number       the frame or sample number
     * @param codedRate    the sample rate written in the header, 0 to defer to STREAMINFO
     * @param codedBits    the sample size written in the header, 0 to defer to STREAMINFO
     * @param bits         the actual sample size
     * @param assignment   the channel assignment code
     * @param channels     the channel samples (left and right for assignments 8 to 10)
     * @param blockSize    the number of samples
     * @param subframes    the coding of each subframe
     */
    static byte[] frame(boolean variable, long number, int codedRate, int codedBits, int bits, int assignment,
                        long[][] channels, int blockSize, Subframe... subframes) {
        var writer = new MsbBitWriter();
        writer.write(variable ? 0xFFF9 : 0xFFF8, 16);
        var blockCode = blockCode(blockSize);
        var rateCode = rateCode(codedRate);
        writer.write(blockCode, 4);
        writer.write(rateCode, 4);
        writer.write(assignment, 4);
        writer.write(sizeCode(codedBits), 3);
        writer.write(0, 1);
        for (var b : number(number)) {
            writer.write(b & 0xFF, 8);
        }
        if (blockCode == 6) {
            writer.write(blockSize - 1, 8);
        } else if (blockCode == 7) {
            writer.write(blockSize - 1, 16);
        }
        if (rateCode == 12) {
            writer.write(codedRate / 1000, 8);
        } else if (rateCode == 13) {
            writer.write(codedRate, 16);
        } else if (rateCode == 14) {
            writer.write(codedRate / 10, 16);
        }
        var headerBytes = writer.toByteArray();
        writer.write(Crc16.crc8(headerBytes, 0, headerBytes.length), 8);
        var coded = decorrelate(assignment, channels, blockSize);
        for (var c = 0; c < coded.length; c++) {
            var subframeBits = bits + (isSide(assignment, c) ? 1 : 0);
            writeSubframe(writer, coded[c], blockSize, subframeBits, subframes[c]);
        }
        writer.alignWithZeros();
        var body = writer.toByteArray();
        var crc = Crc16.crc16(0, body, 0, body.length);
        var frame = Arrays.copyOf(body, body.length + 2);
        frame[body.length] = (byte) (crc >>> 8);
        frame[body.length + 1] = (byte) crc;
        return frame;
    }

    /** Builds a codec configuration: "fLaC" plus a last STREAMINFO block. */
    static byte[] config(int minBlock, int maxBlock, int rate, int channels, int bits, long total) {
        var data = new byte[4 + 4 + 34];
        data[0] = 'f';
        data[1] = 'L';
        data[2] = 'a';
        data[3] = 'C';
        data[4] = (byte) 0x80;
        data[7] = 34;
        var body = 8;
        data[body] = (byte) (minBlock >>> 8);
        data[body + 1] = (byte) minBlock;
        data[body + 2] = (byte) (maxBlock >>> 8);
        data[body + 3] = (byte) maxBlock;
        var packed = (long) rate << 44 | (long) (channels - 1) << 41 | (long) (bits - 1) << 36 | total;
        for (var i = 0; i < 8; i++) {
            data[body + 10 + i] = (byte) (packed >>> (56 - 8 * i));
        }
        return data;
    }

    /** Concatenates a configuration and frames into a native FLAC file. */
    static byte[] file(byte[] config, byte[]... frames) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(config);
        for (var frame : frames) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static boolean isSide(int assignment, int channel) {
        return assignment == 8 && channel == 1 || assignment == 9 && channel == 0 || assignment == 10 && channel == 1;
    }

    private static long[][] decorrelate(int assignment, long[][] channels, int blockSize) {
        if (assignment < 8) {
            return channels;
        }
        var left = channels[0];
        var right = channels[1];
        var side = new long[blockSize];
        for (var i = 0; i < blockSize; i++) {
            side[i] = left[i] - right[i];
        }
        return switch (assignment) {
            case 8 -> new long[][]{left, side};
            case 9 -> new long[][]{side, right};
            default -> {
                var mid = new long[blockSize];
                for (var i = 0; i < blockSize; i++) {
                    mid[i] = (left[i] + right[i]) >> 1;
                }
                yield new long[][]{mid, side};
            }
        };
    }

    private static void writeSubframe(MsbBitWriter writer, long[] input, int blockSize, int bits, Subframe subframe) {
        var predictor = subframe.predictor();
        var wasted = subframe.wasted();
        writer.write(0, 1);
        var type = switch (predictor.kind()) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 8 + predictor.order();
            default -> 31 + predictor.order();
        };
        writer.write(type, 6);
        var samples = new long[blockSize];
        for (var i = 0; i < blockSize; i++) {
            samples[i] = input[i] >> wasted;
            if (samples[i] << wasted != input[i]) {
                throw new IllegalArgumentException("sample " + i + " has fewer than " + wasted + " wasted bits");
            }
        }
        if (wasted > 0) {
            writer.write(1, 1);
            writeUnary(writer, wasted - 1);
            bits -= wasted;
        } else {
            writer.write(0, 1);
        }
        switch (predictor.kind()) {
            case 0 -> writeSigned(writer, samples[0], bits);
            case 1 -> {
                for (var i = 0; i < blockSize; i++) {
                    writeSigned(writer, samples[i], bits);
                }
            }
            case 2 -> {
                var order = predictor.order();
                for (var i = 0; i < order; i++) {
                    writeSigned(writer, samples[i], bits);
                }
                var residual = new long[blockSize];
                for (var i = order; i < blockSize; i++) {
                    long prediction = switch (order) {
                        case 0 -> 0;
                        case 1 -> samples[i - 1];
                        case 2 -> 2 * samples[i - 1] - samples[i - 2];
                        case 3 -> 3 * samples[i - 1] - 3 * samples[i - 2] + samples[i - 3];
                        default -> 4 * samples[i - 1] - 6 * samples[i - 2] + 4 * samples[i - 3] - samples[i - 4];
                    };
                    residual[i] = samples[i] - prediction;
                }
                writeResidual(writer, residual, blockSize, order, subframe.rice());
            }
            default -> {
                var order = predictor.order();
                for (var i = 0; i < order; i++) {
                    writeSigned(writer, samples[i], bits);
                }
                writer.write(predictor.precision() - 1, 4);
                writer.write(predictor.shift() & 0x1F, 5);
                for (var coefficient : predictor.coefficients()) {
                    writeSigned(writer, coefficient, predictor.precision());
                }
                var residual = new long[blockSize];
                for (var i = order; i < blockSize; i++) {
                    var sum = 0L;
                    for (var j = 0; j < order; j++) {
                        sum += predictor.coefficients()[j] * samples[i - 1 - j];
                    }
                    residual[i] = samples[i] - (sum >> predictor.shift());
                }
                writeResidual(writer, residual, blockSize, order, subframe.rice());
            }
        }
    }

    private static void writeResidual(MsbBitWriter writer, long[] residual, int blockSize, int order, Rice rice) {
        writer.write(rice.method(), 2);
        writer.write(rice.partitionOrder(), 4);
        var partitionSize = blockSize >> rice.partitionOrder();
        var escape = rice.method() == 0 ? 15 : 31;
        var parameterBits = rice.method() == 0 ? 4 : 5;
        for (var p = 0; p < 1 << rice.partitionOrder(); p++) {
            var start = p == 0 ? order : p * partitionSize;
            var end = (p + 1) * partitionSize;
            if (rice.escapeBits() >= 0) {
                writer.write(escape, parameterBits);
                writer.write(rice.escapeBits(), 5);
                for (var i = start; i < end; i++) {
                    if (rice.escapeBits() > 0) {
                        writeSigned(writer, residual[i], rice.escapeBits());
                    } else if (residual[i] != 0) {
                        throw new IllegalArgumentException("zero-width escape with a non-zero residual");
                    }
                }
                continue;
            }
            var parameter = rice.parameter();
            if (parameter < 0) {
                var sum = 0L;
                for (var i = start; i < end; i++) {
                    sum += fold(residual[i]);
                }
                var mean = end > start ? sum / (end - start) : 0;
                parameter = mean > 0 ? 63 - Long.numberOfLeadingZeros(mean) : 0;
                parameter = Math.min(parameter, escape - 1);
            }
            writer.write(parameter, parameterBits);
            for (var i = start; i < end; i++) {
                var folded = fold(residual[i]);
                writeUnary(writer, folded >>> parameter);
                if (parameter > 0) {
                    writer.writeLong(folded & ((1L << parameter) - 1), parameter);
                }
            }
        }
    }

    private static long fold(long value) {
        return value >= 0 ? value << 1 : ((-value) << 1) - 1;
    }

    private static void writeUnary(MsbBitWriter writer, long zeros) {
        while (zeros >= 31) {
            writer.write(0, 31);
            zeros -= 31;
        }
        writer.write(1, (int) zeros + 1);
    }

    private static void writeSigned(MsbBitWriter writer, long value, int bits) {
        if (bits == 0) {
            return;
        }
        var min = -(1L << (bits - 1));
        var max = (1L << (bits - 1)) - 1;
        if (value < min || value > max) {
            throw new IllegalArgumentException(value + " does not fit " + bits + " bits");
        }
        writer.writeLong(value & ((1L << bits) - 1), bits);
    }

    private static int blockCode(int blockSize) {
        return switch (blockSize) {
            case 192 -> 1;
            case 576 -> 2;
            case 1152 -> 3;
            case 2304 -> 4;
            case 4608 -> 5;
            case 256 -> 8;
            case 512 -> 9;
            case 1024 -> 10;
            case 2048 -> 11;
            case 4096 -> 12;
            case 8192 -> 13;
            case 16384 -> 14;
            case 32768 -> 15;
            default -> blockSize <= 256 ? 6 : 7;
        };
    }

    private static int rateCode(int rate) {
        return switch (rate) {
            case 0 -> 0;
            case 88200 -> 1;
            case 176400 -> 2;
            case 192000 -> 3;
            case 8000 -> 4;
            case 16000 -> 5;
            case 22050 -> 6;
            case 24000 -> 7;
            case 32000 -> 8;
            case 44100 -> 9;
            case 48000 -> 10;
            case 96000 -> 11;
            default -> rate % 1000 == 0 && rate / 1000 <= 255 ? 12 : rate <= 65535 ? 13 : 14;
        };
    }

    private static int sizeCode(int bits) {
        return switch (bits) {
            case 0 -> 0;
            case 8 -> 1;
            case 12 -> 2;
            case 16 -> 4;
            case 20 -> 5;
            case 24 -> 6;
            case 32 -> 7;
            default -> throw new IllegalArgumentException("sample size " + bits + " needs STREAMINFO");
        };
    }

    private static byte[] number(long value) {
        if (value < 0x80) {
            return new byte[]{(byte) value};
        }
        var extra = value < 1L << 11 ? 1 : value < 1L << 16 ? 2 : value < 1L << 21 ? 3 : value < 1L << 26 ? 4
                : value < 1L << 31 ? 5 : 6;
        var result = new byte[extra + 1];
        var lead = extra == 6 ? 0xFE : (0xFF << (7 - extra)) & 0xFF;
        result[0] = (byte) (lead | (int) (value >>> (6 * extra)));
        for (var i = 1; i <= extra; i++) {
            result[i] = (byte) (0x80 | (int) ((value >>> (6 * (extra - i))) & 0x3F));
        }
        return result;
    }
}
