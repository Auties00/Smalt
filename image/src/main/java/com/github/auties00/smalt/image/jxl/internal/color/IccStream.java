/* Ports jxl-oxide 0.12.6 (crates/jxl-color: icc/decode.rs) and libjxl (lib/jxl/icc_codec.cc). Copyright Wonwoo Choi
   and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads the ICC profile a JPEG XL codestream stores after its image header (ISO/IEC 18181-1
 * annex B): an entropy-coded byte stream whose bytes are then expanded by the ICC prediction
 * commands into the original profile.
 *
 * <p>Every method is a pure function of its arguments and the bitstream.
 *
 * @implNote This implementation keeps the reference decoder's 41 byte contexts, header prediction,
 * tag table commands and shuffled and predicted numeric data commands.
 */
public final class IccStream {
    /**
     * The largest encoded or decoded profile accepted, in bytes.
     */
    private static final long MAX_SIZE = 1 << 28;

    /**
     * The tags a tag command can name by index.
     */
    private static final String[] COMMON_TAGS = {
            "rTRC", "rXYZ", "cprt", "wtpt", "bkpt", "rXYZ", "gXYZ", "bXYZ", "kXYZ", "rTRC",
            "gTRC", "bTRC", "kTRC", "chad", "desc", "chrm", "dmnd", "dmdd", "lumi",
    };

    /**
     * The tag type signatures a data command can name by index.
     */
    private static final String[] COMMON_DATA = {"XYZ ", "desc", "text", "mluc", "para", "curv", "sf32", "gbd "};

    /**
     * Prevents instantiation.
     */
    private IccStream() {
        throw new AssertionError();
    }

    /**
     * Reads and expands the ICC profile.
     *
     * @param br the bitstream, positioned after the image header
     * @param maxBytes the largest profile accepted
     * @return the profile
     * @throws MediaException.Malformed if the stream is invalid
     * @throws MediaException.LimitExceeded if the profile exceeds {@code maxBytes}
     */
    public static byte[] read(JxlBitReader br, long maxBytes) {
        return decode(readEncoded(br, maxBytes), maxBytes);
    }

    /**
     * Reads the entropy-coded bytes of the profile stream.
     *
     * @param br the bitstream
     * @param maxBytes the largest stream accepted
     * @return the encoded stream
     * @throws MediaException.Malformed if the stream is invalid
     * @throws MediaException.LimitExceeded if the stream exceeds {@code maxBytes}
     */
    private static byte[] readEncoded(JxlBitReader br, long maxBytes) {
        var size = br.readU64();
        if (size < 0 || size > MAX_SIZE) {
            throw new MediaException.Malformed("encoded ICC profile too large");
        }
        if (size > maxBytes) {
            throw new MediaException.LimitExceeded("encoded ICC profile of " + size + " bytes exceeds the limits");
        }
        var decoder = EntropyDecoder.read(br, 41);
        decoder.begin(br);
        var headerLength = (int) Math.min(size, 18);
        var header = new byte[headerLength];
        var b1 = 0;
        var b2 = 0;
        for (var i = 0; i < headerLength; i++) {
            var sym = decoder.readUint(br, context(i, b1, b2));
            if (sym < 0 || sym >= 256) {
                throw new MediaException.Malformed("ICC byte out of range");
            }
            header[i] = (byte) sym;
            b2 = b1;
            b1 = sym;
        }
        var cursor = new int[]{0};
        var outputSize = varint(header, headerLength, cursor);
        var commandsSize = varint(header, headerLength, cursor);
        if (cursor[0] + commandsSize > size) {
            throw new MediaException.Malformed("invalid ICC commands size");
        }
        if (outputSize > MAX_SIZE || outputSize > maxBytes) {
            throw new MediaException.LimitExceeded("ICC profile of " + outputSize + " bytes exceeds the limits");
        }
        if (outputSize + 65536 < size) {
            throw new MediaException.Malformed("ICC output size far smaller than its stream");
        }
        if (size > br.remainingBits()) {
            throw new MediaException.Malformed("truncated ICC profile");
        }
        var encoded = new byte[(int) size];
        System.arraycopy(header, 0, encoded, 0, headerLength);
        for (var i = headerLength; i < encoded.length; i++) {
            var sym = decoder.readUint(br, context(i, b1, b2));
            if (sym < 0 || sym >= 256) {
                throw new MediaException.Malformed("ICC byte out of range");
            }
            encoded[i] = (byte) sym;
            b2 = b1;
            b1 = sym;
        }
        decoder.checkFinalState();
        return encoded;
    }

    /**
     * Returns the context of an ICC stream byte from the two bytes before it.
     *
     * @param index the byte index
     * @param b1 the previous byte
     * @param b2 the byte before that
     * @return the context, from 0 to 40
     */
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

    /**
     * Reads a little-endian base-128 variable-length integer.
     *
     * @param data the bytes
     * @param end the index one past the last readable byte
     * @param cursor the read position, advanced
     * @return the value
     * @throws MediaException.Malformed if the integer runs past {@code end}
     */
    private static long varint(byte[] data, int end, int[] cursor) {
        var value = 0L;
        var shift = 0;
        while (shift < 63) {
            if (cursor[0] >= end) {
                throw new MediaException.Malformed("ICC stream too short");
            }
            var b = data[cursor[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return value;
    }

    /**
     * Predicts a header byte.
     *
     * @param index the byte index, below 128
     * @param outputSize the profile size
     * @param header the header bytes decoded so far
     * @return the predicted byte
     */
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

    /**
     * Interleaves the two halves of a byte run.
     *
     * @param bytes the array holding the run
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the shuffled bytes
     */
    private static byte[] shuffle2(byte[] bytes, int offset, int length) {
        var out = new byte[length];
        var height = length / 2;
        var odd = length % 2;
        var k = 0;
        for (var i = 0; i < height; i++) {
            out[k++] = bytes[offset + i];
            out[k++] = bytes[offset + i + height + odd];
        }
        if (odd != 0) {
            out[k] = bytes[offset + height];
        }
        return out;
    }

    /**
     * Interleaves the four quarters of a byte run.
     *
     * @param bytes the array holding the run
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the shuffled bytes
     */
    private static byte[] shuffle4(byte[] bytes, int offset, int length) {
        var out = new byte[length];
        var step = length / 4;
        var wideCount = length % 4;
        var k = 0;
        for (var i = 0; i < step; i++) {
            var base = i;
            for (var j = 0; j < wideCount; j++) {
                out[k++] = bytes[offset + base];
                base += step + 1;
            }
            for (var j = wideCount; j < 4; j++) {
                out[k++] = bytes[offset + base];
                base += step;
            }
        }
        for (var i = 1; i <= wideCount; i++) {
            out[k++] = bytes[offset + (step + 1) * i - 1];
        }
        return out;
    }

    /**
     * Expands an encoded profile stream into the profile.
     *
     * @param stream the encoded stream
     * @param maxBytes the largest profile accepted
     * @return the profile
     * @throws MediaException.Malformed if a command is invalid
     * @throws MediaException.LimitExceeded if the profile exceeds the limits
     */
    static byte[] decode(byte[] stream, long maxBytes) {
        var cursor = new int[]{0};
        var outputSize = varint(stream, stream.length, cursor);
        var commandsSize = varint(stream, stream.length, cursor);
        if (cursor[0] + commandsSize > stream.length) {
            throw new MediaException.Malformed("invalid ICC commands size");
        }
        if (outputSize > MAX_SIZE || outputSize > maxBytes) {
            throw new MediaException.LimitExceeded("ICC profile of " + outputSize + " bytes exceeds the limits");
        }
        var commandsStart = cursor[0];
        var commandsEnd = (int) (commandsStart + commandsSize);
        var dataPos = commandsEnd;
        var headerSize = (int) Math.min(outputSize, 128);
        if (stream.length - dataPos < headerSize) {
            throw new MediaException.Malformed("invalid ICC output size");
        }
        var out = new Output((int) outputSize);
        var header = new byte[headerSize];
        for (var i = 0; i < headerSize; i++) {
            var predicted = predictHeader(i, outputSize, header);
            header[i] = (byte) (predicted + stream[dataPos + i]);
            out.write(header[i] & 0xFF);
        }
        dataPos += headerSize;
        if (outputSize <= 128) {
            return out.toByteArray();
        }
        var commands = new int[]{commandsStart};
        var numTagsPlusOne = varint(stream, commandsEnd, commands);
        if (numTagsPlusOne > 0) {
            var numTags = numTagsPlusOne - 1;
            if ((outputSize - 128) / 12 < numTags) {
                throw new MediaException.Malformed("too many ICC tags");
            }
            out.writeInt((int) numTags);
            var prevStart = numTags * 12 + 128;
            var prevSize = 0L;
            while (true) {
                if (commands[0] >= commandsEnd) {
                    return finish(out, outputSize);
                }
                var command = stream[commands[0]++] & 0xFF;
                var code = command & 63;
                if (code == 0) {
                    break;
                }
                String tag;
                if (code == 1) {
                    if (stream.length - dataPos < 4) {
                        throw new MediaException.Malformed("ICC data stream too short");
                    }
                    tag = new String(stream, dataPos, 4, StandardCharsets.ISO_8859_1);
                    dataPos += 4;
                } else if (code <= 20) {
                    tag = COMMON_TAGS[code - 2];
                } else {
                    throw new MediaException.Malformed("invalid ICC tag code");
                }
                var start = (command & 64) == 0 ? prevStart + prevSize : varint(stream, commandsEnd, commands);
                long size;
                if ((command & 128) != 0) {
                    size = varint(stream, commandsEnd, commands);
                } else if (tag.equals("rXYZ") || tag.equals("gXYZ") || tag.equals("bXYZ") || tag.equals("kXYZ")
                        || tag.equals("wtpt") || tag.equals("bkpt") || tag.equals("lumi")) {
                    size = 20;
                } else {
                    size = prevSize;
                }
                start &= 0xFFFF_FFFFL;
                size &= 0xFFFF_FFFFL;
                if (start + size > outputSize) {
                    throw new MediaException.Malformed("ICC tag outside the profile");
                }
                prevStart = start;
                prevSize = size;
                out.writeTag(tag, start, size);
                if (code == 2) {
                    out.writeTag("gTRC", start, size);
                    out.writeTag("bTRC", start, size);
                } else if (code == 3) {
                    out.writeTag("gXYZ", start + size, size);
                    out.writeTag("bXYZ", start + size * 2, size);
                }
            }
        }
        while (commands[0] < commandsEnd) {
            var command = stream[commands[0]++] & 0xFF;
            switch (command) {
                case 1 -> {
                    var num = checkedLength(varint(stream, commandsEnd, commands), stream.length - dataPos);
                    out.write(stream, dataPos, num);
                    dataPos += num;
                }
                case 2, 3 -> {
                    var num = checkedLength(varint(stream, commandsEnd, commands), stream.length - dataPos);
                    var shuffled = command == 2 ? shuffle2(stream, dataPos, num) : shuffle4(stream, dataPos, num);
                    out.write(shuffled, 0, num);
                    dataPos += num;
                }
                case 4 -> {
                    if (commands[0] >= commandsEnd) {
                        throw new MediaException.Malformed("ICC stream too short");
                    }
                    var flags = stream[commands[0]++] & 0xFF;
                    var width = (flags & 3) + 1;
                    var order = (flags >>> 2) & 3;
                    if (width == 3 || order == 3) {
                        throw new MediaException.Malformed("invalid ICC predicted data command");
                    }
                    long stride = width;
                    if ((flags & 16) != 0) {
                        stride = varint(stream, commandsEnd, commands);
                        if (stride < width) {
                            throw new MediaException.Malformed("ICC predicted data stride below its width");
                        }
                    }
                    if (stride * 4 >= out.size()) {
                        throw new MediaException.Malformed("ICC predicted data stride too large");
                    }
                    var num = checkedLength(varint(stream, commandsEnd, commands), stream.length - dataPos);
                    byte[] bytes;
                    var bytesOffset = 0;
                    if (width == 1) {
                        bytes = stream;
                        bytesOffset = dataPos;
                    } else if (width == 2) {
                        bytes = shuffle2(stream, dataPos, num);
                    } else {
                        bytes = shuffle4(stream, dataPos, num);
                    }
                    dataPos += num;
                    var s = (int) stride;
                    for (var i = 0; i < num; i += width) {
                        var prev = new int[3];
                        for (var j = 0; j <= order; j++) {
                            var base = out.size() - s * (j + 1);
                            var v = 0;
                            for (var k = 0; k < width; k++) {
                                v = (v << 8) | out.get(base + k);
                            }
                            prev[j] = v;
                        }
                        var p = switch (order) {
                            case 0 -> prev[0];
                            case 1 -> 2 * prev[0] - prev[1];
                            default -> 3 * (prev[0] - prev[1]) + prev[2];
                        };
                        for (var j = 0; j < Math.min(width, num - i); j++) {
                            var value = (bytes[bytesOffset + i + j] & 0xFF) + (p >>> (8 * (width - 1 - j)));
                            out.write(value & 0xFF);
                        }
                    }
                }
                case 10 -> {
                    if (stream.length - dataPos < 12) {
                        throw new MediaException.Malformed("ICC data stream too short");
                    }
                    out.write(new byte[]{'X', 'Y', 'Z', ' ', 0, 0, 0, 0}, 0, 8);
                    out.write(stream, dataPos, 12);
                    dataPos += 12;
                }
                default -> {
                    if (command >= 16 && command <= 23) {
                        out.write(COMMON_DATA[command - 16].getBytes(StandardCharsets.ISO_8859_1), 0, 4);
                        out.write(new byte[4], 0, 4);
                    } else {
                        throw new MediaException.Malformed("invalid ICC command " + command);
                    }
                }
            }
            if (out.size() > outputSize) {
                throw new MediaException.Malformed("ICC profile larger than declared");
            }
        }
        return finish(out, outputSize);
    }

    /**
     * Checks a byte count against the data left.
     *
     * @param value the count
     * @param available the bytes left
     * @return the count as an {@code int}
     * @throws MediaException.Malformed if the count exceeds the data left
     */
    private static int checkedLength(long value, int available) {
        if (value < 0 || value > available) {
            throw new MediaException.Malformed("ICC data stream too short");
        }
        return (int) value;
    }

    /**
     * Returns the profile after checking its size.
     *
     * @param out the expanded bytes
     * @param outputSize the declared size
     * @return the profile
     * @throws MediaException.Malformed if the sizes differ
     */
    private static byte[] finish(Output out, long outputSize) {
        if (out.size() != outputSize) {
            throw new MediaException.Malformed("decoded ICC profile size mismatch");
        }
        return out.toByteArray();
    }

    /**
     * Accumulates the expanded profile.
     */
    private static final class Output {
        /**
         * The bytes written, with spare capacity.
         */
        private byte[] view;

        /**
         * The number of bytes written.
         */
        private int size;

        /**
         * Creates an empty output.
         *
         * @param capacity the expected size
         */
        Output(int capacity) {
            this.view = new byte[Math.max(16, Math.min(capacity, 1 << 20))];
        }

        /**
         * Appends a byte.
         *
         * @param value the byte
         */
        void write(int value) {
            ensure(1);
            view[size++] = (byte) value;
        }

        /**
         * Appends bytes.
         *
         * @param data the source
         * @param offset the index of the first byte
         * @param length the number of bytes
         */
        void write(byte[] data, int offset, int length) {
            ensure(length);
            System.arraycopy(data, offset, view, size, length);
            size += length;
        }

        /**
         * Appends a big-endian 32-bit value.
         *
         * @param value the 32-bit value to append
         */
        void writeInt(int value) {
            write(value >>> 24);
            write((value >>> 16) & 0xFF);
            write((value >>> 8) & 0xFF);
            write(value & 0xFF);
        }

        /**
         * Appends a tag table entry.
         *
         * @param tag the signature
         * @param start the data offset
         * @param size the data size
         */
        void writeTag(String tag, long start, long size) {
            write(tag.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
            writeInt((int) start);
            writeInt((int) size);
        }

        /**
         * Returns a byte written earlier.
         *
         * @param index the position of the byte, below the size written so far
         * @return the byte, from 0 to 255
         */
        int get(int index) {
            return view[index] & 0xFF;
        }

        /**
         * Grows the copy to fit more bytes.
         *
         * @param extra the bytes about to be written
         */
        private void ensure(int extra) {
            if (size + extra > view.length) {
                view = Arrays.copyOf(view, Math.max(view.length * 2, size + extra));
            }
        }

        /**
         * Returns the number of bytes written.
         *
         * @return the size
         */
        int size() {
            return size;
        }

        /**
         * Returns the bytes written.
         *
         * @return a copy
         */
        byte[] toByteArray() {
            return Arrays.copyOf(view, size);
        }
    }
}
