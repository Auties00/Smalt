package com.github.auties00.smalt.testkit.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A source over a byte array that delivers its bytes the way a hostile but valid source may: in
 * chunks of one byte or of pseudo-random sizes, with its length hidden, or failing with an injected
 * {@link MediaException.Io} at a chosen read.
 *
 * <p>Every behavior stays inside the {@link MediaSource} contract, so a parser that gives a different
 * result over this source than over {@link MediaSource#of(byte[])} has a bug. The chunk size of a read
 * depends only on the seed, the position and the requested length, never on the order of the reads,
 * so concurrent readers see the same deliveries as a sequential one. Reads are counted, from zero, in
 * the order they reach the source; reads of zero bytes are answered without being counted.
 *
 * <p>The source also checks the contract from the parser's side: it records reads after
 * {@link #close()}, and a negative position fails with {@link IndexOutOfBoundsException} as the
 * contract requires.
 *
 * <p>This class is thread-safe.
 */
public final class SimulatedSource implements MediaSource {
    /**
     * How a read is cut short.
     */
    public enum Chunking {
        /** Every read delivers as many bytes as are requested and available. */
        WHOLE,
        /** Every read delivers one byte. */
        ONE_BYTE,
        /** Every read delivers a pseudo-random number of bytes, from one to the requested length. */
        RANDOM
    }

    /**
     * Signals the failure the source injects; it is the cause of the {@link MediaException.Io} a read
     * throws.
     */
    public static final class InjectedFault extends IOException {
        private final long readIndex;

        /**
         * Constructs the fault of one read.
         *
         * @param readIndex the index of the failing read, from zero
         */
        public InjectedFault(long readIndex) {
            super("injected I/O fault at read " + readIndex);
            this.readIndex = readIndex;
        }

        /**
         * {@return the index of the failing read, from zero}
         */
        public long readIndex() {
            return readIndex;
        }
    }

    private final byte[] data;
    private final boolean sized;
    private final Chunking chunking;
    private final long seed;
    private final long failAt;
    private final boolean permanent;
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger faults = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger readsAfterClose = new AtomicInteger();
    private volatile long failArmed;

    private SimulatedSource(byte[] data, boolean sized, Chunking chunking, long seed, long failAt, boolean permanent) {
        this.data = Objects.requireNonNull(data, "data");
        this.sized = sized;
        this.chunking = Objects.requireNonNull(chunking, "chunking");
        this.seed = seed;
        this.failAt = failAt;
        this.permanent = permanent;
        this.failArmed = failAt;
    }

    /**
     * Returns a sized source that delivers whole reads and never fails; it counts the reads a parser
     * makes.
     *
     * @param data the bytes, not copied
     * @return the source
     */
    public static SimulatedSource of(byte[] data) {
        return new SimulatedSource(data, true, Chunking.WHOLE, 0, -1, false);
    }

    /**
     * Returns a source with the given delivery behavior that never fails.
     *
     * @param data the bytes, not copied
     * @param sized whether {@link #size()} reports the length; {@code false} reports {@code -1}
     * @param chunking how reads are cut short
     * @param seed the seed of the {@link Chunking#RANDOM} chunk sizes
     * @return the source
     */
    public static SimulatedSource of(byte[] data, boolean sized, Chunking chunking, long seed) {
        return new SimulatedSource(data, sized, chunking, seed, -1, false);
    }

    /**
     * Returns a sized source that delivers whole reads and fails the read of index {@code readIndex},
     * once or, when {@code permanent}, that read and every later one.
     *
     * @param data the bytes, not copied
     * @param readIndex the index of the first failing read, from zero
     * @param permanent whether every read from {@code readIndex} on fails
     * @return the source
     */
    public static SimulatedSource failing(byte[] data, long readIndex, boolean permanent) {
        return failing(data, Chunking.WHOLE, 0, readIndex, permanent);
    }

    /**
     * Returns a sized source that delivers reads cut as {@code chunking} says and fails the read of index
     * {@code readIndex}, once or, when {@code permanent}, that read and every later one.
     *
     * <p>A parser that buffers its input reads a whole source in a few large reads, so faults injected into
     * {@link Chunking#WHOLE} reads all land while it opens; cut reads spread the read indices over the whole
     * parse, packet reading included.
     *
     * @param data the bytes, not copied
     * @param chunking how reads are cut short
     * @param seed the seed of the {@link Chunking#RANDOM} chunk sizes
     * @param readIndex the index of the first failing read, from zero
     * @param permanent whether every read from {@code readIndex} on fails
     * @return the source
     */
    public static SimulatedSource failing(byte[] data, Chunking chunking, long seed, long readIndex,
                                          boolean permanent) {
        if (readIndex < 0) {
            throw new IllegalArgumentException("negative read index " + readIndex);
        }
        return new SimulatedSource(data, true, chunking, seed, readIndex, permanent);
    }

    /**
     * Makes the next counted read fail once, as if the network dropped at that point.
     */
    public void failNextRead() {
        failArmed = reads.get();
    }

    /**
     * Cancels a fault armed by {@link #failNextRead()} that no read has consumed yet.
     */
    public void disarm() {
        if (!permanent) {
            failArmed = -1;
        }
    }

    @Override
    public long size() {
        return sized ? data.length : -1;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (closes.get() > 0) {
            readsAfterClose.incrementAndGet();
        }
        if (length == 0) {
            return 0;
        }
        var index = reads.getAndIncrement();
        var armed = failArmed;
        if (armed >= 0 && (index == armed || permanent && index > armed)) {
            if (!permanent) {
                failArmed = -1;
            }
            faults.incrementAndGet();
            var fault = new InjectedFault(index);
            throw new MediaException.Io(fault.getMessage(), fault);
        }
        if (position >= data.length) {
            return -1;
        }
        var available = (int) Math.min(length, data.length - position);
        var count = switch (chunking) {
            case WHOLE -> available;
            case ONE_BYTE -> 1;
            case RANDOM -> randomChunk(position, length, available);
        };
        System.arraycopy(data, (int) position, buffer, offset, count);
        bytes.addAndGet(count);
        return count;
    }

    private int randomChunk(long position, int length, int available) {
        var mix = seed * 0x9E3779B97F4A7C15L ^ position * 0xC2B2AE3D27D4EB4FL ^ length * 0x165667B19E3779F9L;
        mix ^= mix >>> 29;
        mix *= 0xBF58476D1CE4E5B9L;
        mix ^= mix >>> 32;
        var bucket = (int) (mix & 3);
        var value = (mix >>> 2) & Long.MAX_VALUE;
        var limit = switch (bucket) {
            case 0 -> 1;
            case 1 -> Math.min(available, 7);
            case 2 -> Math.min(available, 509);
            default -> available;
        };
        return 1 + (int) (value % limit);
    }

    /**
     * {@return the number of reads of at least one byte made so far, failed ones included}
     */
    public long reads() {
        return reads.get();
    }

    /**
     * {@return the number of bytes delivered so far}
     */
    public long bytes() {
        return bytes.get();
    }

    /**
     * {@return the number of injected faults thrown so far}
     */
    public int faults() {
        return faults.get();
    }

    /**
     * {@return the number of calls to {@link #close()} so far}
     */
    public int closes() {
        return closes.get();
    }

    /**
     * {@return the number of reads made after the first {@link #close()}}
     */
    public int readsAfterClose() {
        return readsAfterClose.get();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    @Override
    public String toString() {
        return "SimulatedSource[" + data.length + " bytes, " + (sized ? "sized" : "unsized") + ", " + chunking
                + (chunking == Chunking.RANDOM ? " (seed " + seed + ")" : "")
                + (failAt >= 0 ? ", fails at read " + failAt + (permanent ? " and after" : " once") : "") + "]";
    }
}
