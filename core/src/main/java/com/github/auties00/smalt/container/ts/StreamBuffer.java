package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Buffers the bytes of an elementary stream across PES packets, remembering where each PES
 * packet started and the timestamps it carried.
 *
 * <p>Stream positions count the bytes appended since the buffer was created. A frame or access
 * unit that starts at a position takes its timestamps from the PES packet in which that position
 * lies, when that packet carried timestamps no earlier unit took (ISO/IEC 13818-1 clause 2.4.3.7:
 * a PTS applies to the first access unit that starts in the PES packet). Instances are not
 * thread-safe.
 */
final class StreamBuffer {
    /**
     * Records where a PES packet started in the stream.
     */
    static final class Marker {
        /**
         * The stream position of the first payload byte.
         */
        final long position;

        /**
         * The presentation time in 90 kHz ticks on the continuous timeline, or
         * {@link TsClock#NONE}.
         */
        final long pts;

        /**
         * The decoding time in 90 kHz ticks on the continuous timeline, or {@link TsClock#NONE}.
         */
        final long dts;

        /**
         * The offset of the transport packet that started the PES packet.
         */
        final long fileOffset;

        /**
         * Whether a transport packet of the PES packet was lost or damaged.
         */
        final boolean corrupt;

        /**
         * Whether the adaptation field flagged a random access point.
         */
        final boolean randomAccess;

        /**
         * Whether a unit already took the timestamps.
         */
        boolean taken;

        /**
         * Constructs a marker.
         *
         * @param position the stream position of the first payload byte
         * @param pts the presentation time, or {@link TsClock#NONE}
         * @param dts the decoding time, or {@link TsClock#NONE}
         * @param fileOffset the offset of the starting transport packet
         * @param corrupt whether the PES packet is damaged
         * @param randomAccess whether a random access point is flagged
         */
        Marker(long position, long pts, long dts, long fileOffset, boolean corrupt, boolean randomAccess) {
            this.position = position;
            this.pts = pts;
            this.dts = dts;
            this.fileOffset = fileOffset;
            this.corrupt = corrupt;
            this.randomAccess = randomAccess;
        }

        /**
         * Returns whether the PES packet carried timestamps that no unit took yet.
         *
         * @return {@code true} when the timestamps are available
         */
        boolean hasTimestamps() {
            return !taken && pts != TsClock.NONE;
        }
    }

    /**
     * The largest number of bytes buffered before the buffer is emptied as unusable.
     */
    private final int capacity;

    /**
     * The buffered bytes, from {@link #start} to {@link #end}.
     */
    private byte[] data = new byte[4096];

    /**
     * The index of the first buffered byte.
     */
    private int start;

    /**
     * The index after the last buffered byte.
     */
    private int end;

    /**
     * The stream position of {@code data[start]}.
     */
    private long consumed;

    /**
     * The markers of the PES packets that may still hold buffered bytes, oldest first.
     */
    private final ArrayDeque<Marker> markers = new ArrayDeque<>();

    /**
     * Constructs a buffer.
     *
     * @param capacity the largest number of bytes held
     */
    StreamBuffer(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Appends the payload of a PES packet.
     *
     * <p>When the buffer would exceed its capacity, what it holds is discarded first: no frame of
     * that size exists in a valid stream.
     *
     * @param pes the PES packet
     * @param pts the presentation time on the continuous timeline, or {@link TsClock#NONE}
     * @param dts the decoding time on the continuous timeline, or {@link TsClock#NONE}
     * @return {@code true} when buffered bytes were discarded to make room
     */
    boolean append(ElementaryParser.Pes pes, long pts, long dts) {
        var discarded = false;
        if ((long) end - start + pes.length() > capacity) {
            skip(end - start);
            discarded = true;
            if (pes.length() > capacity) {
                return true;
            }
        }
        markers.add(new Marker(consumed + (end - start), pts, dts, pes.fileOffset(), pes.corrupt() || discarded,
                pes.randomAccess()));
        if (end + pes.length() > data.length) {
            var length = end - start;
            if (length + pes.length() > data.length) {
                var grown = Math.max(data.length * 2L, (long) length + pes.length());
                var target = new byte[(int) Math.min(grown, Math.max(capacity, length + pes.length()))];
                System.arraycopy(data, start, target, 0, length);
                data = target;
            } else {
                System.arraycopy(data, start, data, 0, length);
            }
            start = 0;
            end = length;
        }
        System.arraycopy(pes.data(), pes.offset(), data, end, pes.length());
        end += pes.length();
        return discarded;
    }

    /**
     * Returns the array holding the buffered bytes.
     *
     * <p>The array changes when bytes are appended.
     *
     * @return the array
     */
    byte[] array() {
        return data;
    }

    /**
     * {@return the index in {@link #array()} of the first buffered byte}
     */
    int start() {
        return start;
    }

    /**
     * Returns the number of buffered bytes.
     *
     * @return the count
     */
    int available() {
        return end - start;
    }

    /**
     * {@return the stream position of the first buffered byte}
     */
    long position() {
        return consumed;
    }

    /**
     * Discards buffered bytes from the front.
     *
     * @param count the number of bytes, at most {@link #available()}
     */
    void skip(int count) {
        if (count < 0 || count > end - start) {
            throw new MediaException.Malformed("invalid stream buffer skip of " + count + " bytes");
        }
        start += count;
        consumed += count;
        if (start == end) {
            start = 0;
            end = 0;
        }
        while (markers.size() > 1) {
            var iterator = markers.iterator();
            iterator.next();
            if (iterator.next().position <= consumed) {
                markers.removeFirst();
            } else {
                break;
            }
        }
    }

    /**
     * Returns a copy of buffered bytes.
     *
     * @param from the index relative to the first buffered byte
     * @param length the number of bytes
     * @return the bytes
     */
    byte[] copy(int from, int length) {
        return Arrays.copyOfRange(data, start + from, start + from + length);
    }

    /**
     * Returns the marker of the PES packet holding a stream position.
     *
     * @param position the stream position, not before {@link #position()} unless no later marker
     * exists
     * @return the marker, or {@code null} when no PES packet was appended
     */
    Marker markerAt(long position) {
        Marker found = null;
        for (var marker : markers) {
            if (marker.position > position) {
                break;
            }
            found = marker;
        }
        return found != null ? found : markers.peekFirst();
    }

    /**
     * Returns whether a damaged PES packet contributed bytes to a stream range.
     *
     * @param from the first stream position of the range
     * @param to the stream position after the range
     * @return {@code true} when a marker of a damaged PES packet lies in the range, or the range
     * starts in one
     */
    boolean corrupt(long from, long to) {
        var first = markerAt(from);
        if (first != null && first.corrupt) {
            return true;
        }
        for (var marker : markers) {
            if (marker.position > from && marker.position < to && marker.corrupt) {
                return true;
            }
        }
        return false;
    }
}
