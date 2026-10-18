package com.github.auties00.smalt.aot.internal;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Counts the distinct methods of the tracked packages that the JIT compiler has compiled at the
 * highest tier, a training run's measure of its coverage.
 *
 * <p>The count comes from the Flight Recorder's {@code jdk.Compilation} events, streamed in this
 * JVM; {@link #sync()} waits until the events of the compilations finished so far have been
 * delivered.
 */
public final class CompilationWatch implements AutoCloseable {
    /**
     * The highest compilation tier, the optimizing compiler's.
     */
    private static final int HIGHEST_TIER = 4;

    /**
     * The longest {@link #sync()} waits, in nanoseconds.
     */
    private static final long SYNC_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * The stream of compilation events.
     */
    private final RecordingStream stream;

    /**
     * The prefixes of the class names whose compilations count, each a package name and a dot.
     */
    private final List<String> prefixes;

    /**
     * The methods compiled at the highest tier so far, as class, name and descriptor.
     */
    private final Set<String> compiled = ConcurrentHashMap.newKeySet();

    /**
     * When the stream last delivered its buffered events, from {@link System#nanoTime()}.
     */
    private volatile long lastFlush;

    /**
     * Starts watching the compilations of a stream.
     *
     * @param stream the stream, not yet started
     * @param packages the tracked packages
     */
    private CompilationWatch(RecordingStream stream, List<String> packages) {
        this.stream = stream;
        this.prefixes = packages.stream().map(name -> name + ".").toList();
        stream.enable("jdk.Compilation").withThreshold(Duration.ZERO);
        stream.onEvent("jdk.Compilation", this::onCompilation);
        stream.onFlush(() -> lastFlush = System.nanoTime());
        stream.startAsync();
    }

    /**
     * Starts watching compilations.
     *
     * @param packages the tracked packages: a method counts when its class is in one of them or in
     * one of their subpackages
     * @return the watch
     * @throws IllegalStateException if the Flight Recorder cannot stream in this JVM
     * @throws SecurityException if the caller may not record
     */
    public static CompilationWatch start(List<String> packages) {
        return new CompilationWatch(new RecordingStream(), List.copyOf(packages));
    }

    /**
     * Records a compilation event when it is a successful compilation of a tracked method at the
     * highest tier.
     *
     * @param event the {@code jdk.Compilation} event
     */
    private void onCompilation(RecordedEvent event) {
        if (event.getInt("compileLevel") != HIGHEST_TIER || !event.getBoolean("succeded")) {
            return;
        }
        if (!(event.getValue("method") instanceof RecordedMethod method)) {
            return;
        }
        var type = method.getType().getName();
        for (var prefix : prefixes) {
            if (type.startsWith(prefix)) {
                compiled.add(type + "::" + method.getName() + method.getDescriptor());
                return;
            }
        }
    }

    /**
     * Waits, for at most five seconds, until the stream has delivered the events recorded before
     * this method was invoked.
     */
    public void sync() {
        var since = System.nanoTime();
        var limit = since + SYNC_LIMIT_NANOS;
        while (lastFlush <= since && System.nanoTime() < limit) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Returns the number of distinct tracked methods compiled at the highest tier so far.
     *
     * @return the count
     */
    public int count() {
        return compiled.size();
    }

    /**
     * Stops watching and closes the stream.
     */
    @Override
    public void close() {
        stream.close();
    }
}
