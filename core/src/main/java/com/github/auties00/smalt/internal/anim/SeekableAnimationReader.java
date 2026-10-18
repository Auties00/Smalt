package com.github.auties00.smalt.internal.anim;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adds seeking to an animation reader that can be reopened from the start of its input.
 *
 * <p>A seek forwards composites the frames between the current position and the target; a seek
 * backwards reopens the animation and composites from its first frame. The frame displayed at the
 * target is composited during the seek and returned by the next {@link #read()}.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation always composites from the first frame on a backward seek.
 * Starting from the closest earlier frame that covers the whole canvas without blending (a key
 * frame of the animation) would need an index of the frame headers, which the readers do not
 * keep.
 */
public final class SeekableAnimationReader implements AnimationReader {
    // TODO: start a backward seek at the closest earlier frame that does not depend on its predecessors

    /**
     * Opens the animation from its start.
     */
    private final Supplier<AnimationReader> opener;

    /**
     * The reader currently composited.
     */
    private AnimationReader delegate;

    /**
     * The timestamp of the next frame {@link #delegate} composites.
     */
    private long nextTimestamp;

    /**
     * The frame a seek composited and the next {@link #read()} returns, or {@code null}.
     */
    private AnimationFrame pending;

    /**
     * Whether a seek reached the end, so that the next {@link #read()} returns {@code null}.
     */
    private boolean exhausted;

    /**
     * Constructs a reader.
     *
     * @param opener  reopens the animation from its start
     * @param initial the reader already opened by {@code opener}
     */
    private SeekableAnimationReader(Supplier<AnimationReader> opener, AnimationReader initial) {
        this.opener = opener;
        this.delegate = initial;
    }

    /**
     * Opens an animation and makes it seekable.
     *
     * @param opener opens the animation from its start; called once now and again on every
     *               backward seek
     * @return the reader
     * @throws NullPointerException if {@code opener} is {@code null} or returns {@code null}
     */
    public static AnimationReader of(Supplier<AnimationReader> opener) {
        Objects.requireNonNull(opener, "opener");
        return new SeekableAnimationReader(opener, Objects.requireNonNull(opener.get(), "reader"));
    }

    @Override
    public int width() {
        return delegate.width();
    }

    @Override
    public int height() {
        return delegate.height();
    }

    @Override
    public int loopCount() {
        return delegate.loopCount();
    }

    @Override
    public int frameCount() {
        return delegate.frameCount();
    }

    @Override
    public AnimationFrame read() {
        if (pending != null) {
            var frame = pending;
            pending = null;
            return frame;
        }
        if (exhausted) {
            return null;
        }
        var frame = delegate.read();
        if (frame != null) {
            nextTimestamp = frame.timestampMicros() + frame.durationMicros();
        } else {
            exhausted = true;
        }
        return frame;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed     if a frame composited while seeking is corrupt
     * @throws MediaException.LimitExceeded if the frame count exceeds the limits
     */
    @Override
    public void seek(long micros) {
        pending = null;
        if (micros < nextTimestamp || exhausted) {
            AnimationReader reopened;
            try {
                reopened = Objects.requireNonNull(opener.get(), "reader");
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("cannot reopen the animation", exception);
            }
            delegate.close();
            delegate = reopened;
            nextTimestamp = 0;
            exhausted = false;
        }
        while (true) {
            var frame = delegate.read();
            if (frame == null) {
                exhausted = true;
                return;
            }
            nextTimestamp = frame.timestampMicros() + frame.durationMicros();
            if (nextTimestamp > micros) {
                pending = frame;
                return;
            }
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
