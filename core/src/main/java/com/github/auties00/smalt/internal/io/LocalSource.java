package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

/**
 * Tells whether the bytes of a {@link MediaSource} are local, so that a parser can choose between
 * reading an input twice and buffering what it read once.
 *
 * <p>Reading a local source again costs at most a read from memory or from a local file, which
 * the operating system usually serves from its cache; reading any other source again, such as an
 * HTTP resource fetched with range requests, may transfer every byte a second time. A source that
 * does not implement this interface is treated as not local.
 */
public interface LocalSource {
    /**
     * Returns whether the bytes of this source are in memory or in a local file.
     *
     * @return {@code true} when reading the source again costs no network transfer
     */
    boolean isLocal();

    /**
     * Returns whether one range of this source is in memory or in a local file.
     *
     * <p>A source whose bytes arrive over time, such as a forward-only stream that keeps what it
     * already read, is local over the ranges it holds and not local past them.
     *
     * @implSpec
     * The default implementation returns {@link #isLocal()}.
     *
     * @param from the offset of the first byte of the range; non-negative
     * @param to   the offset just past the last byte of the range; at least {@code from}
     * @return {@code true} when reading the range again costs no network transfer
     */
    default boolean isLocal(long from, long to) {
        return isLocal();
    }

    /**
     * Returns whether a source is local.
     *
     * @param source the source
     * @return {@code true} when the source implements this interface and reports that it is local
     */
    static boolean isLocal(MediaSource source) {
        return source instanceof LocalSource local && local.isLocal();
    }

    /**
     * Returns whether one range of a source is local.
     *
     * @param source the source
     * @param from   the offset of the first byte of the range; non-negative
     * @param to     the offset just past the last byte of the range; at least {@code from}
     * @return {@code true} when the source implements this interface and reports that the range
     *         is local
     */
    static boolean isLocal(MediaSource source, long from, long to) {
        return source instanceof LocalSource local && local.isLocal(from, to);
    }
}
