package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.io.MediaSource;

/**
 * Exposes the array behind an in-memory {@link MediaSource}, so a parser can read the bytes in
 * place instead of copying them into a buffer of its own first.
 *
 * <p>Source offset {@code p} is {@code array()[arrayOffset() + p]}, for {@code p} below
 * {@link MediaSource#size()}. A parser must never write into the array and must never hand it,
 * or a view of it, to its caller: every payload it returns is still a copy.
 */
public interface ArrayBacked {
    /**
     * Returns the backing array.
     *
     * @return the array, shared with the source
     */
    byte[] array();

    /**
     * Returns the index in {@link #array()} of source offset zero.
     *
     * @return the offset
     */
    int arrayOffset();

    /**
     * Returns the array behind a source, when there is one.
     *
     * @param source the source
     * @return the source as an {@link ArrayBacked}, or {@code null} when it is not array backed
     */
    static ArrayBacked of(MediaSource source) {
        return source instanceof ArrayBacked backed ? backed : null;
    }
}
