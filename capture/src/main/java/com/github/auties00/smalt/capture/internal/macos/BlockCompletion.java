package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receives the result of an Objective-C completion handler.
 *
 * <p>A completion is registered in the {@link CallbackRegistry} and its key captured in a block
 * literal; when the block is invoked, on whatever queue the framework chooses, the bridge fills
 * the completion and releases the thread waiting in {@link #await(long)}. The first result wins;
 * later invocations are ignored.
 */
final class BlockCompletion {
    /**
     * Released when the result arrives.
     */
    private final CountDownLatch done;

    /**
     * Set by the first result, so that later ones are ignored.
     */
    private final AtomicBoolean claimed;

    /**
     * The {@code BOOL} result, for {@code void (^)(BOOL)} handlers.
     */
    private volatile boolean flag;

    /**
     * The object result, retained by the bridge, for {@code void (^)(id, NSError *)} handlers.
     */
    private volatile MemorySegment object;

    /**
     * The error result, or {@code null}.
     */
    private volatile NsError error;

    /**
     * Creates a pending completion.
     */
    BlockCompletion() {
        this.done = new CountDownLatch(1);
        this.claimed = new AtomicBoolean();
        this.object = MemorySegment.NULL;
    }

    /**
     * Resolves the completion of a {@code void (^)(BOOL)} handler.
     *
     * @param value the {@code BOOL} argument
     */
    void completeFlag(boolean value) {
        if (!claimed.compareAndSet(false, true)) {
            return;
        }
        flag = value;
        done.countDown();
    }

    /**
     * Resolves the completion of a {@code void (^)(id, NSError *)} or
     * {@code void (^)(NSError *)} handler.
     *
     * @param retained the object argument, already retained, or {@code NULL}
     * @param failure  the error argument, or {@code null}
     * @return whether this call resolved the completion; when {@code false} the caller releases
     *         {@code retained}
     */
    boolean complete(MemorySegment retained, NsError failure) {
        if (!claimed.compareAndSet(false, true)) {
            return false;
        }
        object = retained;
        error = failure;
        done.countDown();
        return true;
    }

    /**
     * Waits for the result.
     *
     * @param timeoutMillis the longest wait in milliseconds
     * @return whether the result arrived in time
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    boolean await(long timeoutMillis) throws InterruptedException {
        return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the {@code BOOL} result.
     *
     * @return the value the handler received
     */
    boolean flag() {
        return flag;
    }

    /**
     * Returns the object result, which the caller now owns and must release.
     *
     * @return the retained object, or {@code NULL}
     */
    MemorySegment object() {
        return object;
    }

    /**
     * Returns the error result.
     *
     * @return the error, or {@code null} when the handler received none
     */
    NsError error() {
        return error;
    }
}
