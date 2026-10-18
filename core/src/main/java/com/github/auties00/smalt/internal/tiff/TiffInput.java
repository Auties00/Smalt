package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * The bytes a TIFF structure is read from: a whole array, such as an EXIF block, or a source, such
 * as a TIFF or camera raw file.
 *
 * <p>Positions are absolute in the input. Implementations must be safe for the reads of one
 * parser; the factories return instances with the thread safety of what they wrap.
 */
public interface TiffInput {
    /**
     * {@return the number of bytes of the input, or {@code -1} when unknown}
     */
    long size();

    /**
     * Reads up to {@code length} bytes at a position, stopping early only where the input ends.
     *
     * @param position the absolute position of the first byte; a negative position reads nothing
     * @param target the destination
     * @param offset the index in {@code target} of the first byte stored
     * @param length the number of bytes wanted
     * @return the number of bytes stored, from 0 to {@code length}
     */
    int readAvailable(long position, byte[] target, int offset, int length);

    /**
     * Returns an input over a whole array, which is not copied.
     *
     * @param data the bytes
     * @return the input
     */
    static TiffInput of(byte[] data) {
        Objects.requireNonNull(data, "data");
        return new TiffInput() {
            @Override
            public long size() {
                return data.length;
            }

            @Override
            public int readAvailable(long position, byte[] target, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, target.length);
                if (position < 0 || position >= data.length) {
                    return 0;
                }
                var count = (int) Math.min(length, data.length - position);
                System.arraycopy(data, (int) position, target, offset, count);
                return count;
            }
        };
    }

    /**
     * Returns an input over a source, which is not closed by the input.
     *
     * @param source the source to read from
     * @return the input
     */
    static TiffInput of(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return new TiffInput() {
            @Override
            public long size() {
                return source.size();
            }

            @Override
            public int readAvailable(long position, byte[] target, int offset, int length) {
                return SourceBytes.readAvailable(source, position, target, offset, length);
            }
        };
    }
}
