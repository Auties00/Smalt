/* Ports lzokay (lzokay.cpp, decompress). Copyright (c) 2018 Jack Andersen; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.internal.compress;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decompresses LZO1X streams held in memory, the format of {@code lzo1x_1_compress} and the other LZO1X
 * compressors, which end with the {@code M4} instruction of distance zero.
 *
 * <p>The decompressed size is not stored, so the output grows within the allocation limit. Bytes after the end
 * instruction are ignored.
 */
public final class Lzo1xDecoder {
    private Lzo1xDecoder() {
    }

    /**
     * Decompresses one LZO1X stream.
     *
     * @param data the compressed bytes
     * @param offset the index of the first compressed byte
     * @param length the number of compressed bytes
     * @param limits the bound of the decompressed size
     * @return the decompressed bytes
     * @throws MediaException.Malformed if the stream is truncated, has no end instruction or copies from before its
     * start
     * @throws MediaException.LimitExceeded if the decompressed bytes exceed the allocation limit
     */
    public static byte[] decode(byte[] data, int offset, int length, MediaLimits limits) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("range [" + offset + ", " + offset + " + " + length + ") out of "
                    + data.length);
        }
        if (length < 3) {
            throw new MediaException.Malformed("lzo1x: stream is truncated");
        }
        var end = offset + length;
        var in = offset;
        var output = new Output(length, limits);
        var state = 0;
        var first = data[in] & 0xFF;
        if (first >= 22) {
            in++;
            in = output.literals(data, in, end, first - 17);
            state = 4;
        } else if (first >= 18) {
            in++;
            state = first - 17;
            in = output.literals(data, in, end, state);
        }
        while (true) {
            var instruction = next(data, in++, end);
            int distance;
            int count;
            int nextState;
            if ((instruction & 0xC0) != 0) {
                distance = (next(data, in++, end) << 3) + ((instruction >>> 2) & 7) + 1;
                count = (instruction >>> 5) + 1;
                nextState = instruction & 3;
            } else if ((instruction & 0x20) != 0) {
                count = (instruction & 0x1F) + 2;
                if (count == 2) {
                    var zerosStart = in;
                    while (next(data, in, end) == 0) {
                        in++;
                    }
                    count += extendedLength(in - zerosStart, 31, next(data, in++, end));
                }
                var word = next(data, in, end) | (next(data, in + 1, end) << 8);
                in += 2;
                distance = (word >>> 2) + 1;
                nextState = word & 3;
            } else if ((instruction & 0x10) != 0) {
                count = (instruction & 7) + 2;
                if (count == 2) {
                    var zerosStart = in;
                    while (next(data, in, end) == 0) {
                        in++;
                    }
                    count += extendedLength(in - zerosStart, 7, next(data, in++, end));
                }
                var word = next(data, in, end) | (next(data, in + 1, end) << 8);
                in += 2;
                distance = ((instruction & 8) << 11) + (word >>> 2);
                nextState = word & 3;
                if (distance == 0) {
                    if (count != 3) {
                        throw new MediaException.Malformed("lzo1x: malformed end instruction");
                    }
                    return output.toArray();
                }
                distance += 16384;
            } else if (state == 0) {
                count = instruction + 3;
                if (count == 3) {
                    var zerosStart = in;
                    while (next(data, in, end) == 0) {
                        in++;
                    }
                    count += extendedLength(in - zerosStart, 15, next(data, in++, end));
                }
                in = output.literals(data, in, end, count);
                state = 4;
                continue;
            } else if (state != 4) {
                distance = (instruction >>> 2) + (next(data, in++, end) << 2) + 1;
                count = 2;
                nextState = instruction & 3;
            } else {
                distance = (instruction >>> 2) + (next(data, in++, end) << 2) + 2049;
                count = 3;
                nextState = instruction & 3;
            }
            output.copy(distance, count);
            in = output.literals(data, in, end, nextState);
            state = nextState;
        }
    }

    /**
     * Returns the byte at an index of the input.
     *
     * @throws MediaException.Malformed if the index is at or past the end
     */
    private static int next(byte[] data, int index, int end) {
        if (index >= end) {
            throw new MediaException.Malformed("lzo1x: stream is truncated");
        }
        return data[index] & 0xFF;
    }

    /**
     * Returns the extra length of an instruction whose length field is zero: 255 for every zero byte, the base and
     * the final non-zero byte.
     *
     * @throws MediaException.Malformed if the length overflows an {@code int}
     */
    private static int extendedLength(int zeros, int base, int last) {
        var length = zeros * 255L + base + last;
        if (length > Integer.MAX_VALUE - 16) {
            throw new MediaException.Malformed("lzo1x: length overflows");
        }
        return (int) length;
    }

    /**
     * The growing output of a decompression.
     */
    private static final class Output {
        private final MediaLimits limits;
        private byte[] bytes;
        private int size;

        Output(int compressed, MediaLimits limits) {
            this.limits = limits;
            this.bytes = new byte[(int) Math.max(64, Math.min(compressed * 4L, 1 << 20))];
        }

        /**
         * Copies literal bytes from the input.
         *
         * @return the index of the input after them
         */
        int literals(byte[] data, int in, int end, int count) {
            if (count > end - in) {
                throw new MediaException.Malformed("lzo1x: stream is truncated");
            }
            ensure(count);
            System.arraycopy(data, in, bytes, size, count);
            size += count;
            return in + count;
        }

        /**
         * Copies bytes from earlier output, overlapping when the distance is shorter than the count.
         */
        void copy(int distance, int count) {
            if (distance > size) {
                throw new MediaException.Malformed("lzo1x: match reaches before the start of the output");
            }
            ensure(count);
            var from = size - distance;
            if (distance >= count) {
                System.arraycopy(bytes, from, bytes, size, count);
            } else {
                for (var i = 0; i < count; i++) {
                    bytes[size + i] = bytes[from + i];
                }
            }
            size += count;
        }

        private void ensure(int more) {
            var needed = (long) size + more;
            if (needed > bytes.length) {
                LimitChecks.checkAllocation(limits, needed);
                var cap = Math.min(limits.maxBytes(), Integer.MAX_VALUE - 16L);
                bytes = Arrays.copyOf(bytes, (int) Math.max(needed, Math.min(bytes.length * 2L, cap)));
            }
        }

        byte[] toArray() {
            return Arrays.copyOf(bytes, size);
        }
    }
}
