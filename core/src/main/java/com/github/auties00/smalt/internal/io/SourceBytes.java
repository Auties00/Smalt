package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reads a whole {@link MediaSource}, or as much of a range as it holds, into an array, as the
 * decoders of formats parsed in memory need.
 */
public final class SourceBytes {
    /**
     * The first capacity of the array that receives a source of unknown size.
     */
    private static final int CHUNK = 1 << 16;

    /**
     * Prevents instantiation.
     */
    private SourceBytes() {
        throw new AssertionError();
    }

    /**
     * Reads up to {@code length} bytes at a position, stopping early only where the source ends.
     *
     * <p>Unlike {@link MediaSource#readFully}, a short source is not an error: the count tells how
     * many bytes were stored. A negative position reads nothing.
     *
     * @param source the input
     * @param position the absolute offset of the first byte
     * @param target the destination
     * @param offset the index in {@code target} of the first byte stored
     * @param length the number of bytes wanted
     * @return the number of bytes stored at {@code target[offset]}, from 0 to {@code length}
     * @throws IndexOutOfBoundsException if the range of {@code target} is out of bounds
     * @throws MediaException.Io if reading fails
     */
    public static int readAvailable(MediaSource source, long position, byte[] target, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (position < 0) {
            return 0;
        }
        var done = 0;
        while (done < length) {
            var read = source.read(position + done, target, offset + done, length - done);
            if (read <= 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    /**
     * Reads a whole source into memory.
     *
     * <p>A source of unknown size is read into an array that doubles as it fills. With
     * {@code once}, the bytes are read as {@link ForwardOnlySource#readOnce} reads them, so a
     * source that reads its input once keeps none of them; the source then cannot be read again,
     * so only the last pass over a source passes {@code true}.
     *
     * @param source the input
     * @param limits the bound on the input size
     * @param once whether this is the last read of the source
     * @return the bytes
     * @throws MediaException.LimitExceeded if the input is larger than {@link MediaLimits#maxBytes()}
     * @throws MediaException.Malformed if the source ends before its declared size
     * @throws MediaException.Io if reading fails
     */
    public static byte[] readAll(MediaSource source, MediaLimits limits, boolean once) {
        var size = source.size();
        if (size >= 0) {
            LimitChecks.checkAllocation(limits, size);
            var data = new byte[(int) size];
            if (!once) {
                source.readFully(0, data, 0, data.length);
            } else if (ForwardOnlySource.readOnce(source, 0, data, 0, data.length) < data.length) {
                throw new MediaException.Malformed("unexpected end of input while reading " + data.length + " bytes");
            }
            return data;
        }
        var data = new byte[CHUNK];
        var length = 0;
        while (true) {
            if (length == data.length) {
                LimitChecks.checkAllocation(limits, (long) length + 1);
                var grown = Math.min((long) length * 2, Math.min(limits.maxBytes(), Integer.MAX_VALUE - 16));
                data = Arrays.copyOf(data, (int) grown);
            }
            if (once) {
                var read = ForwardOnlySource.readOnce(source, length, data, length, data.length - length);
                length += read;
                if (length < data.length) {
                    return Arrays.copyOf(data, length);
                }
                continue;
            }
            var read = source.read(length, data, length, data.length - length);
            if (read < 0) {
                return Arrays.copyOf(data, length);
            }
            length += read;
        }
    }
}
