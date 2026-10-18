package com.github.auties00.smalt.aot.internal;

/**
 * Keeps the results of training rounds observable, so that the JIT compiler cannot discard the
 * work that produced them.
 */
final class Blackhole {
    /**
     * The accumulated results, written by every workload; its value has no meaning.
     */
    private static volatile long sink;

    /**
     * Prevents instantiation.
     */
    private Blackhole() {
        throw new AssertionError();
    }

    /**
     * Consumes a result.
     *
     * @param value the result
     */
    static void consume(long value) {
        sink += value;
    }
}
