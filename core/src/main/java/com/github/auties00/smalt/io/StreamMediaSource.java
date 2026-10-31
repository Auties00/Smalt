package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.LocalSource;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Implements {@link MediaSource} over an {@link InputStream}, which is read once, front to back.
 *
 * <p>The bytes taken from the stream are kept in chunks, so that a read at any offset already
 * taken is served without touching the stream again: in memory while the kept chunks fit the
 * {@linkplain StreamSourceOptions#memoryBudget() memory budget}, then in an encrypted
 * {@link SpillFile}. The offset up to which the stream has been read is the frontier. A read
 * below the frontier copies from the kept chunks without locking, so such reads run
 * concurrently; a read past it takes more from the stream under a lock, at most up to the end of
 * the read, and only then copies.
 *
 * <p>Parsers raise the watermark through {@link ForwardOnlySource}: every chunk that lies wholly
 * below it is dropped, and a chunk taken from the stream wholly below it is never kept. A read
 * that reaches a dropped chunk fails with {@link MediaException.Unsupported}. The kept ranges are
 * {@linkplain LocalSource#isLocal(long, long) local}; the bytes past the frontier are not.
 *
 * <p>A stream that ends before its declared length fails every read past its end with
 * {@link MediaException.Io} caused by an {@link EOFException}; bytes past the declared length are
 * never read. A stream of unknown length ends where the stream does.
 */
final class StreamMediaSource implements MediaSource, LocalSource, ForwardOnlySource {
    /**
     * The largest chunk size: 64 KiB.
     */
    private static final int MAX_CHUNK_SHIFT = 16;

    /**
     * The smallest chunk size: 4 KiB.
     */
    private static final int MIN_CHUNK_SHIFT = 12;

    /**
     * The format name of the failure raised by a read of released bytes.
     */
    private static final String RELEASED = "released stream bytes";

    /**
     * Marks a chunk kept in the spill file.
     *
     * @param slot the slot of the spill file that holds the chunk
     */
    private record Spilled(long slot) {
    }

    /**
     * The kept chunks, published as one immutable pair of base index and entry array.
     *
     * <p>Entry {@code i} describes chunk {@code base + i}: a {@code byte[]} in memory, a
     * {@link Spilled} slot, or {@code null} once dropped. The entries are written only under the
     * pull lock, before the frontier that makes them readable is published.
     *
     * @param base the index of the chunk of entry zero
     * @param entries the entry of each chunk from {@code base} on
     */
    private record Directory(long base, Object[] entries) {
    }

    /**
     * Opens the stream on first use, or {@code null} when the stream was given.
     */
    private final Supplier<? extends InputStream> supplier;

    /**
     * Whether {@link #close()} closes the stream.
     */
    private final boolean ownsStream;

    /**
     * The declared length, or {@code -1} when unknown.
     */
    private final long length;

    /**
     * The options that set the memory budget and the spill directory.
     */
    private final StreamSourceOptions options;

    /**
     * Whether a read of any byte below the watermark fails even while its chunk is still kept;
     * used by tests to catch a parser that releases too early.
     */
    private final boolean strict;

    /**
     * The base-two logarithm of the chunk size.
     */
    private final int chunkShift;

    /**
     * The chunk size in bytes.
     */
    private final int chunkSize;

    /**
     * Serializes reads from the stream, commits, drops and {@link #close()}.
     */
    private final Object lock;

    /**
     * The free slots of the spill file, guarded by {@link #lock}.
     */
    private final ArrayDeque<Long> freeSlots;

    /**
     * The stream, or {@code null} before the supplier is called; guarded by {@link #lock}.
     */
    private InputStream stream;

    /**
     * The number of bytes taken from the stream: every byte below it is readable unless it
     * was dropped.
     */
    private volatile long frontier;

    /**
     * The offset below which no read starts again.
     */
    private volatile long watermark;

    /**
     * The kept chunks.
     */
    private volatile Directory directory;

    /**
     * The number of leading chunks dropped, guarded by {@link #lock}.
     */
    private long dropped;

    /**
     * The chunk being filled at the frontier, or {@code null} when the next byte starts a new
     * one; guarded by {@link #lock}.
     */
    private byte[] tail;

    /**
     * The bytes of the full chunks kept in memory, guarded by {@link #lock}.
     */
    private long memoryUsed;

    /**
     * The spill file, created on first spill under {@link #lock}.
     */
    private volatile SpillFile spill;

    /**
     * The next unused slot of the spill file, guarded by {@link #lock}.
     */
    private long nextSlot;

    /**
     * Whether the stream has ended or the declared length was reached.
     */
    private volatile boolean ended;

    /**
     * The failure that stopped reading the stream, rethrown by every later read past the
     * frontier, or {@code null}.
     */
    private volatile RuntimeException failure;

    /**
     * Whether {@link #close()} already ran.
     */
    private volatile boolean closed;

    /**
     * Constructs a source over a stream or a stream supplier.
     *
     * @param stream the stream, or {@code null} when {@code supplier} opens it
     * @param supplier the supplier of the stream, or {@code null} when {@code stream} is given
     * @param length the declared length, or {@code -1} when unknown
     * @param options the memory budget and spill directory
     * @param strict whether a read below the watermark always fails
     * @throws IllegalArgumentException if {@code length} is below {@code -1}
     */
    StreamMediaSource(InputStream stream, Supplier<? extends InputStream> supplier, long length,
                      StreamSourceOptions options, boolean strict) {
        this(stream, supplier, stream == null, length, options, strict);
    }

    /**
     * Constructs a source over a stream or a stream supplier, stating whether {@link #close()} closes the stream.
     *
     * @param stream the stream, or {@code null} when {@code supplier} opens it
     * @param supplier the supplier of the stream, or {@code null} when {@code stream} is given
     * @param ownsStream whether {@link #close()} closes the stream
     * @param length the declared length, or {@code -1} when unknown
     * @param options the memory budget and spill directory
     * @param strict whether a read below the watermark always fails
     * @throws IllegalArgumentException if {@code length} is below {@code -1}
     */
    StreamMediaSource(InputStream stream, Supplier<? extends InputStream> supplier, boolean ownsStream, long length,
                      StreamSourceOptions options, boolean strict) {
        if (stream == null) {
            Objects.requireNonNull(supplier, "supplier");
        }
        if (length < -1) {
            throw new IllegalArgumentException("length " + length + " is below -1");
        }
        this.stream = stream;
        this.supplier = supplier;
        this.ownsStream = ownsStream;
        this.length = length;
        this.options = Objects.requireNonNull(options, "options");
        this.strict = strict;
        var shift = 63 - Long.numberOfLeadingZeros(Math.max(1, options.memoryBudget() / 4));
        this.chunkShift = Math.clamp(shift, MIN_CHUNK_SHIFT, MAX_CHUNK_SHIFT);
        this.chunkSize = 1 << chunkShift;
        this.lock = new Object();
        this.freeSlots = new ArrayDeque<>();
        this.directory = new Directory(0, new Object[16]);
        this.ended = length == 0;
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (length == 0) {
            return 0;
        }
        var end = endOf(position, length);
        if (end <= position) {
            return -1;
        }
        if (end > frontier && !ended) {
            synchronized (lock) {
                pull(end, null, 0, 0, 0);
            }
        }
        var available = Math.min(end, frontier);
        if (available <= position) {
            var failure = this.failure;
            if (failure != null) {
                throw failure;
            }
            return -1;
        }
        copy(position, buffer, offset, (int) (available - position));
        return (int) (available - position);
    }

    @Override
    public int readOnce(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        var end = endOf(position, length);
        if (end <= position) {
            release(position);
            return 0;
        }
        synchronized (lock) {
            var kept = (int) Math.max(0, Math.min(end, frontier) - position);
            if (kept > 0) {
                copy(position, buffer, offset, kept);
            }
            raise(end);
            if (position + kept < end) {
                pull(end, buffer, offset, position, (int) (end - position));
            }
            var done = (int) Math.max(0, Math.min(end, frontier) - position);
            var failure = this.failure;
            if (done < end - position && failure != null) {
                throw failure;
            }
            return done;
        }
    }

    /**
     * Returns the end of a read, clamped to the declared length.
     *
     * @param position the offset of the read
     * @param length the length of the read
     * @return the offset just past the last byte the read may return
     */
    private long endOf(long position, int length) {
        var end = length > Long.MAX_VALUE - position ? Long.MAX_VALUE : position + length;
        return this.length >= 0 ? Math.min(end, Math.max(position, this.length)) : end;
    }

    /**
     * Copies kept bytes, which lie below the frontier, into an array.
     *
     * @param position the offset of the first byte
     * @param buffer the destination
     * @param offset the index of the first byte written
     * @param count the number of bytes, all below the frontier
     * @throws MediaException.Unsupported if a byte was released
     * @throws IllegalStateException if this source is closed
     */
    private void copy(long position, byte[] buffer, int offset, int count) {
        if (closed) {
            throw new IllegalStateException("the stream source is closed");
        }
        if (strict && position < watermark) {
            throw released(position);
        }
        var directory = this.directory;
        var mask = chunkSize - 1;
        while (count > 0) {
            var chunk = position >>> chunkShift;
            var within = (int) (position & mask);
            var part = Math.min(count, chunkSize - within);
            var index = chunk - directory.base();
            var entry = index >= 0 && index < directory.entries().length ? directory.entries()[(int) index] : null;
            switch (entry) {
                case byte[] data -> System.arraycopy(data, within, buffer, offset, part);
                case Spilled(var slot) -> spill.read(slot, chunk, within, buffer, offset, part);
                case null, default -> throw released(position);
            }
            position += part;
            offset += part;
            count -= part;
        }
    }

    /**
     * Returns the failure of a read of released bytes.
     *
     * @param position the offset read
     * @return the failure
     */
    private MediaException released(long position) {
        return new MediaException.Unsupported(RELEASED, "offset " + position + " of a stream source was read after"
                + " its parser released every byte below " + watermark + "; a stream is read once, so it cannot"
                + " seek back to released bytes");
    }

    /**
     * Takes bytes from the stream until the frontier reaches {@code target} or the stream ends,
     * copying the bytes of a sink range into the sink as they arrive.
     *
     * <p>A failure of the stream, an end before the declared length, or a memory-only source
     * exceeding its budget stops reading: it is recorded in {@link #failure} for the read that
     * cannot be served, while the bytes taken so far stay readable. Must be called while holding
     * {@link #lock}.
     *
     * @param target the offset the frontier must reach
     * @param sink the array that receives the sink range, or {@code null}
     * @param sinkOffset the index in {@code sink} of the byte at {@code sinkStart}
     * @param sinkStart the offset of the first byte of the sink range
     * @param sinkLength the length of the sink range
     * @throws IllegalStateException if this source is closed
     */
    private void pull(long target, byte[] sink, int sinkOffset, long sinkStart, int sinkLength) {
        if (closed) {
            throw new IllegalStateException("the stream source is closed");
        }
        if (failure != null) {
            return;
        }
        var stream = open();
        if (stream == null) {
            return;
        }
        var mask = chunkSize - 1;
        while (frontier < target && !ended) {
            var position = frontier;
            var within = (int) (position & mask);
            if (tail == null) {
                tail = new byte[chunkSize];
                publish(position >>> chunkShift, tail);
            }
            var want = chunkSize - within;
            if (length >= 0) {
                want = (int) Math.min(want, length - position);
            }
            int read;
            try {
                read = stream.read(tail, within, want);
            } catch (IOException exception) {
                fail(new MediaException.Io("cannot read the stream at offset " + position, exception));
                return;
            }
            if (read < 0) {
                ended = true;
                if (length >= 0 && position < length) {
                    fail(new MediaException.Io("the stream ended after " + position + " bytes, before its"
                            + " declared length of " + length + " bytes",
                            new EOFException("stream ended at " + position + " of " + length + " bytes")));
                }
                return;
            }
            if (sink != null) {
                var from = Math.max(position, sinkStart);
                var to = Math.min(position + read, sinkStart + sinkLength);
                if (from < to) {
                    System.arraycopy(tail, within + (int) (from - position), sink,
                            sinkOffset + (int) (from - sinkStart), (int) (to - from));
                }
            }
            frontier = position + read;
            if (length >= 0 && frontier == length) {
                ended = true;
            }
            if (((position + read) & mask) == 0 && !commit((position + read - 1) >>> chunkShift)) {
                return;
            }
        }
    }

    /**
     * Returns the stream, calling the supplier on first use.
     *
     * <p>A supplier that fails with an {@link UncheckedIOException} records a
     * {@link MediaException.Io}, one that returns {@code null} a {@link NullPointerException}, in
     * {@link #failure}. Must be called while holding {@link #lock}.
     *
     * @return the stream, or {@code null} when the supplier failed
     */
    private InputStream open() {
        if (stream == null) {
            try {
                stream = supplier.get();
            } catch (UncheckedIOException exception) {
                fail(new MediaException.Io("cannot open the stream", exception.getCause()));
                return null;
            }
            if (stream == null) {
                fail(new NullPointerException("the stream supplier returned null"));
            }
        }
        return stream;
    }

    /**
     * Records the failure that stops reading the stream.
     *
     * @param exception the failure
     */
    private void fail(RuntimeException exception) {
        failure = exception;
    }

    /**
     * Stores the entry of a chunk, growing and compacting the directory when needed.
     *
     * <p>Must be called while holding {@link #lock}.
     *
     * @param chunk the chunk index
     * @param entry the chunk's bytes, its spill slot, or {@code null} once dropped
     */
    private void publish(long chunk, Object entry) {
        var directory = this.directory;
        var index = chunk - directory.base();
        if (index >= directory.entries().length) {
            var base = Math.max(directory.base(), Math.min(dropped, chunk));
            var live = (int) (chunk - base + 1);
            var entries = new Object[Math.max(16, Integer.highestOneBit(live) * 2)];
            var keep = (int) Math.min(live, directory.base() + directory.entries().length - base);
            if (keep > 0) {
                System.arraycopy(directory.entries(), (int) (base - directory.base()), entries, 0, keep);
            }
            directory = new Directory(base, entries);
            index = chunk - base;
        }
        if (index >= 0) {
            directory.entries()[(int) index] = entry;
        }
        this.directory = directory;
    }

    /**
     * Commits the full chunk at the frontier: drops it when it lies below the watermark, keeps
     * it in memory while the budget allows, and otherwise spills it or, for a memory-only source,
     * fails.
     *
     * <p>A memory-only source over its budget, or a spill file that cannot be created or
     * written, records its failure in {@link #failure} and keeps the chunk in memory, so the
     * bytes taken so far stay readable. Must be called while holding {@link #lock}.
     *
     * @param chunk the index of the chunk, the one {@link #tail} holds
     * @return {@code false} when the commit failed and reading the stream must stop
     */
    private boolean commit(long chunk) {
        var full = tail;
        tail = null;
        if ((chunk + 1) << chunkShift <= watermark) {
            publish(chunk, null);
            return true;
        }
        memoryUsed += chunkSize;
        if (memoryUsed <= options.memoryBudget()) {
            return true;
        }
        if (options.isMemoryOnly()) {
            fail(new MediaException.LimitExceeded("the stream source would keep more than its memory budget of "
                    + options.memoryBudget() + " bytes; raise StreamSourceOptions.memoryBudget or allow spilling"
                    + " to disk"));
            return false;
        }
        try {
            if (spill == null) {
                spill = SpillFile.create(options.spillDirectory(), chunkSize);
            }
            var slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.poll();
            spill.write(slot, chunk, full, chunkSize);
            publish(chunk, new Spilled(slot));
            memoryUsed -= chunkSize;
            return true;
        } catch (MediaException exception) {
            fail(exception);
            return false;
        }
    }

    @Override
    public void release(long position) {
        if (position <= watermark) {
            return;
        }
        synchronized (lock) {
            raise(position);
        }
    }

    /**
     * Raises the watermark and drops every kept chunk that lies wholly below it.
     *
     * <p>Must be called while holding {@link #lock}.
     *
     * @param position the new watermark
     */
    private void raise(long position) {
        if (position <= watermark) {
            return;
        }
        watermark = position;
        var below = position >>> chunkShift;
        var directory = this.directory;
        var committed = frontier >>> chunkShift;
        for (var chunk = Math.max(dropped, directory.base()); chunk < Math.min(below, committed); chunk++) {
            var index = (int) (chunk - directory.base());
            if (index >= directory.entries().length) {
                break;
            }
            switch (directory.entries()[index]) {
                case byte[] _ -> memoryUsed -= chunkSize;
                case Spilled(var slot) -> freeSlots.add(slot);
                case null, default -> {
                }
            }
            directory.entries()[index] = null;
        }
        dropped = Math.max(dropped, Math.min(below, committed));
    }

    @Override
    public boolean fitsInMemory(long end) {
        return end <= frontier || end - watermark <= options.memoryBudget();
    }

    @Override
    public boolean keeps(long from, long to) {
        if (from >= to) {
            return true;
        }
        if (from < 0 || to > frontier || strict && from < watermark) {
            return false;
        }
        var directory = this.directory;
        for (var chunk = from >>> chunkShift; chunk <= (to - 1) >>> chunkShift; chunk++) {
            var index = chunk - directory.base();
            if (index < 0 || index >= directory.entries().length || directory.entries()[(int) index] == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code false}: the bytes past the frontier come from the stream.
     *
     * @return {@code false}
     */
    @Override
    public boolean isLocal() {
        return false;
    }

    /**
     * Returns whether a range is kept: below the frontier and not released.
     *
     * @param from the offset of the first byte of the range
     * @param to the offset just past the last byte of the range
     * @return {@code true} when every byte of the range is kept
     */
    @Override
    public boolean isLocal(long from, long to) {
        return to <= frontier && from >= watermark;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            directory = new Directory(0, new Object[0]);
            tail = null;
            memoryUsed = 0;
            RuntimeException failure = null;
            if (spill != null) {
                try {
                    spill.close();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                spill = null;
            }
            if (ownsStream && stream != null) {
                try {
                    stream.close();
                } catch (IOException exception) {
                    var closeFailure = new MediaException.Io("cannot close the stream", exception);
                    if (failure != null) {
                        failure.addSuppressed(closeFailure);
                    } else {
                        failure = closeFailure;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
