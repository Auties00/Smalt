package com.github.auties00.smalt.testkit.io;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.io.StreamSourceOptions;

import java.io.InputStream;
import java.util.Objects;

/**
 * A stream over a byte array whose reads deliver a pseudo-random, bounded number of bytes, and that can
 * fail with an injected {@link SimulatedSource.InjectedFault} from a chosen read on, as a network stream
 * that drops does.
 *
 * <p>{@link #source(boolean)} wraps it in {@link MediaSource#of(InputStream, long, StreamSourceOptions)}, the
 * source that reads its input once; a parser over that source must surface the fault as a
 * {@link com.github.auties00.smalt.MediaException.Io} caused by it. The chunk sizes depend only on the order of
 * the reads, so two streams over the same array deliver the same chunks. Reads of zero bytes are answered
 * without being counted.
 *
 * <p>This class is not thread-safe; the source that wraps it serializes its reads.
 */
public final class SimulatedStream extends InputStream {
    private final byte[] data;
    private final long failAt;
    private int position;
    private long state = 0x2545F4914F6CDD1DL;
    private long reads;

    private SimulatedStream(byte[] data, long failAt) {
        this.data = Objects.requireNonNull(data, "data");
        this.failAt = failAt;
    }

    /**
     * Returns a stream that never fails; it counts the reads made on it.
     *
     * @param data the bytes, not copied
     * @return the stream
     */
    public static SimulatedStream of(byte[] data) {
        return new SimulatedStream(data, -1);
    }

    /**
     * Returns a stream that fails the read of index {@code readIndex} and every later one.
     *
     * @param data the bytes, not copied
     * @param readIndex the index of the first failing read, from zero
     * @return the stream
     */
    public static SimulatedStream failing(byte[] data, long readIndex) {
        if (readIndex < 0) {
            throw new IllegalArgumentException("negative read index " + readIndex);
        }
        return new SimulatedStream(data, readIndex);
    }

    /**
     * Returns a source that reads this stream once, front to back, with the default stream options.
     *
     * @param sized whether the source is told the length of the stream; {@code false} gives it {@code -1}
     * @return the source, which closes this stream when it is closed
     */
    public MediaSource source(boolean sized) {
        return MediaSource.of(this, sized ? data.length : -1, StreamSourceOptions.defaults());
    }

    /**
     * {@return the number of reads of at least one byte made so far, failed ones included}
     */
    public long reads() {
        return reads;
    }

    @Override
    public int read() throws SimulatedSource.InjectedFault {
        var one = new byte[1];
        var count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws SimulatedSource.InjectedFault {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        var index = reads++;
        if (failAt >= 0 && index >= failAt) {
            throw new SimulatedSource.InjectedFault(index);
        }
        if (position >= data.length) {
            return -1;
        }
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        var limit = (int) Math.floorMod(state, 4099L) + 1;
        var count = Math.min(Math.min(length, limit), data.length - position);
        System.arraycopy(data, position, buffer, offset, count);
        position += count;
        return count;
    }

    @Override
    public String toString() {
        return "SimulatedStream[" + data.length + " bytes" + (failAt >= 0 ? ", fails from read " + failAt : "") + "]";
    }
}
