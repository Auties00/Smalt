package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.HexFormat;

/**
 * Child-JVM entry point of {@link SharedPoolEncoderTest}: encodes its sequence with four threads
 * and prints the module's thread count, the stream hash and the pool threads seen afterwards, so
 * the test can run it under {@code -Dsmalt.threads=1}.
 */
public final class LoneCallerEncode {
    private LoneCallerEncode() {
    }

    public static void main(String[] args) throws Exception {
        var hash = SharedPoolEncoderTest.hash(SharedPoolEncoderTest.encode(
                SharedPoolEncoderTest.config(H264EncoderSpeed.BALANCED, 4), SharedPoolEncoderTest.pictures(), 4));
        var workers = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("smalt-worker-"))
                .count();
        System.out.println("THREADS=" + ParallelBands.THREADS + " HASH=" + HexFormat.of().formatHex(hash)
                + " WORKERS=" + workers);
    }
}
