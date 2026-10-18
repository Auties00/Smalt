package com.github.auties00.smalt.capture.internal.macos;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps the keys stored in native memory to the Java objects native callbacks are for.
 *
 * <p>Native code cannot hold a Java reference, so every object a callback must reach (a capture
 * session receiving frames, a completion waiting for a block) is registered here and the key is
 * stored where the callback can read it: in an instance variable of a delegate object, or in the
 * captured word of a block literal. A callback resolves its key with {@link #lookup(long, Class)}; once
 * the object is {@linkplain #unregister(long) unregistered}, a late callback finds nothing and does
 * nothing, so no callback ever reaches an object that was torn down. Keys are never reused and
 * {@code 0} is never issued, so a cleared key reads as "no target". The registry is thread-safe.
 */
final class CallbackRegistry {
    /**
     * The registered objects by key.
     */
    private final ConcurrentHashMap<Long, Object> targets;

    /**
     * The last key issued.
     */
    private final AtomicLong lastKey;

    /**
     * Creates an empty registry.
     */
    CallbackRegistry() {
        this.targets = new ConcurrentHashMap<>();
        this.lastKey = new AtomicLong();
    }

    /**
     * Registers an object.
     *
     * @param target the object
     * @return its key, positive and never issued before
     * @throws NullPointerException if {@code target} is {@code null}
     */
    long register(Object target) {
        Objects.requireNonNull(target, "target");
        var key = lastKey.incrementAndGet();
        targets.put(key, target);
        return key;
    }

    /**
     * Returns the object registered under a key if it has a type.
     *
     * @param key the key read from native memory
     * @param type the expected type
     * @param <T> the expected type
     * @return the object, or {@code null} when the key is unknown, unregistered or of another type
     */
    <T> T lookup(long key, Class<T> type) {
        var target = targets.get(key);
        return type.isInstance(target) ? type.cast(target) : null;
    }

    /**
     * Unregisters a key; unknown keys are ignored.
     */
    void unregister(long key) {
        targets.remove(key);
    }

    /**
     * Returns the number of registered objects.
     *
     * @return the count
     */
    int size() {
        return targets.size();
    }
}
