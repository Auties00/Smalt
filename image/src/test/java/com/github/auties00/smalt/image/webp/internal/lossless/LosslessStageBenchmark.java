package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.image.webp.bench.StickerFixtures;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Stage benchmarks of the lossless encoder on a sticker's alpha plane coded as green pixels (the
 * {@code ALPH} payload's input): hash chain, LZ77 variants, cost-based trace-back and cache size
 * search. {@code BaselineLosslessStageBenchmark} runs the same stages on the pre-optimization
 * copy.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx2g"})
public class LosslessStageBenchmark {
    @Param({"photo", "cartoon"})
    public String input;

    int[] argb;
    HashChain chain;
    HashChain boxChain;
    BackwardRefs refs;
    BackwardRefs out;
    final EntropyMath.Stats stats = new EntropyMath.Stats();

    @Setup(Level.Trial)
    public void setup() {
        var picture = StickerFixtures.still(input);
        argb = new int[512 * 512];
        for (var i = 0; i < argb.length; i++) {
            argb[i] = (picture.pixels()[4 * i + 3] & 0xFF) << 8;
        }
        chain = new HashChain(argb.length);
        chain.fill(32, argb, 512, 512, false);
        refs = new BackwardRefs(256);
        BackwardReferences.lz77(512, 512, argb, 0, chain, refs);
        out = new BackwardRefs(256);
        boxChain = new HashChain(argb.length);
    }

    @Benchmark
    public HashChain hashChain() {
        chain.fill(32, argb, 512, 512, false);
        return chain;
    }

    @Benchmark
    public BackwardRefs lz77() {
        BackwardReferences.lz77(512, 512, argb, 0, chain, out);
        return out;
    }

    @Benchmark
    public BackwardRefs lz77Box() {
        BackwardReferences.lz77Box(512, 512, argb, 0, chain, boxChain, out);
        return out;
    }

    @Benchmark
    public BackwardRefs traceBackwards() {
        TraceBackwards.run(512, 512, argb, 0, chain, refs, out);
        return out;
    }

    @Benchmark
    public BackwardRefs traceBackwardsCache() {
        TraceBackwards.run(512, 512, argb, 6, chain, refs, out);
        return out;
    }

    @Benchmark
    public int cacheSize() {
        return BackwardReferences.calculateBestCacheSize(argb, 32, refs, BackwardReferences.MAX_COLOR_CACHE_BITS, stats);
    }
}
