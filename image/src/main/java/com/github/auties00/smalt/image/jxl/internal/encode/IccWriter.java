/* Ports libjxl 0.11.2 (lib/jxl/enc_icc_codec.cc: WriteICC, and the header prediction and byte contexts of
   lib/jxl/icc_codec_common.cc). Copyright the JPEG XL Project Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import java.io.ByteArrayOutputStream;

/**
 * Writes an embedded ICC profile (ISO/IEC 18181-1 E.4): the profile's header predicted, the rest copied by a
 * single command, all entropy coded with the 41 byte contexts.
 */
final class IccWriter {
    private IccWriter() {
        throw new AssertionError();
    }

    /**
     * Writes a profile.
     *
     * @param w the output
     * @param icc the profile bytes
     */
    static void write(JxlBitWriter w, byte[] icc) {
        var encoded = encode(icc);
        w.writeU64(encoded.length);
        var tokens = new Tokens(encoded.length);
        var b1 = 0;
        var b2 = 0;
        for (var i = 0; i < encoded.length; i++) {
            var sym = encoded[i] & 0xFF;
            tokens.add(context(i, b1, b2), sym);
            b2 = b1;
            b1 = sym;
        }
        var code = EntropyEncoder.build(tokens, 41, EntropyEncoder.Params.BEST);
        code.writeHeader(w);
        code.writeTokens(tokens, w);
    }

    /**
     * Returns the encoded profile stream: output size, commands size, commands, then data.
     *
     * @param icc the profile
     * @return the stream
     */
    static byte[] encode(byte[] icc) {
        var size = icc.length;
        var commands = new ByteArrayOutputStream();
        var data = new ByteArrayOutputStream(size);
        var headerSize = Math.min(size, 128);
        for (var i = 0; i < headerSize; i++) {
            data.write((icc[i] - predictHeader(i, size, icc)) & 0xFF);
        }
        if (size > 128) {
            varint(commands, 0);
            commands.write(1);
            varint(commands, size - 128);
            data.write(icc, 128, size - 128);
        }
        var out = new ByteArrayOutputStream(size + 16);
        varint(out, size);
        var c = commands.toByteArray();
        varint(out, c.length);
        out.writeBytes(c);
        out.writeBytes(data.toByteArray());
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while (value >= 128) {
            out.write((int) (value & 127) | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static int predictHeader(int index, long outputSize, byte[] header) {
        if (index < 4) {
            return (int) (outputSize >>> (8 * (3 - index))) & 0xFF;
        }
        if (index == 8) {
            return 4;
        }
        if (index >= 12 && index <= 23) {
            return "mntrRGB XYZ ".charAt(index - 12);
        }
        if (index >= 36 && index <= 39) {
            return "acsp".charAt(index - 36);
        }
        var h40 = index > 40 ? header[40] : 0;
        var h41 = index > 41 ? header[41] : 0;
        if (h40 == 'A') {
            if (index == 41 || index == 42) {
                return 'P';
            }
            if (index == 43) {
                return 'L';
            }
        }
        if (h40 == 'M') {
            if (index == 41) {
                return 'S';
            }
            if (index == 42) {
                return 'F';
            }
            if (index == 43) {
                return 'T';
            }
        }
        if (h40 == 'S' && h41 == 'G') {
            if (index == 42) {
                return 'I';
            }
            if (index == 43) {
                return ' ';
            }
        }
        if (h40 == 'S' && h41 == 'U') {
            if (index == 42) {
                return 'N';
            }
            if (index == 43) {
                return 'W';
            }
        }
        return switch (index) {
            case 70 -> 246;
            case 71 -> 214;
            case 73 -> 1;
            case 78 -> 211;
            case 79 -> 45;
            case 80, 81, 82, 83 -> header[4 + index - 80] & 0xFF;
            default -> 0;
        };
    }

    private static int context(int index, int b1, int b2) {
        if (index <= 128) {
            return 0;
        }
        int p1;
        if ((b1 >= 'a' && b1 <= 'z') || (b1 >= 'A' && b1 <= 'Z')) {
            p1 = 0;
        } else if ((b1 >= '0' && b1 <= '9') || b1 == '.' || b1 == ',') {
            p1 = 1;
        } else if (b1 <= 1) {
            p1 = 2 + b1;
        } else if (b1 <= 15) {
            p1 = 4;
        } else if (b1 >= 241 && b1 <= 254) {
            p1 = 5;
        } else if (b1 == 255) {
            p1 = 6;
        } else {
            p1 = 7;
        }
        int p2;
        if ((b2 >= 'a' && b2 <= 'z') || (b2 >= 'A' && b2 <= 'Z')) {
            p2 = 0;
        } else if ((b2 >= '0' && b2 <= '9') || b2 == '.' || b2 == ',') {
            p2 = 1;
        } else if (b2 <= 15) {
            p2 = 2;
        } else if (b2 >= 241) {
            p2 = 3;
        } else {
            p2 = 4;
        }
        return 1 + p1 + 8 * p2;
    }
}
