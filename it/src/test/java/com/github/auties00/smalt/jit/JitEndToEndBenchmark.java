package com.github.auties00.smalt.jit;

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
 * Times one round of each {@link JitWorkload} workload (a whole clip decoded or encoded, a set of
 * images or capture frames converted, or a few seconds of audio encoded, decoded or resampled),
 * for A/B comparisons of kernel changes that affect how
 * the JIT compiles the Vector API. Pass the workload specifications of {@link JitWorkload} as the
 * {@code spec} parameter, for example
 * {@code -p spec=h264dec:threads=1:input=clip.h264}; {@code -prof gc} reports the allocation
 * per round, which is large whenever a kernel runs its allocating Vector API fallback.
 *
 * <p>CI runs it on the synthetic workloads of the {@code hot-paths} row of
 * {@code tools/bench/benchmarks.tsv}: timed in the weekly A/B, and gated on the bytes allocated
 * per round on every pull request. The video and audio workloads keep their codec open across
 * rounds, so a round's allocation is what the codec allocates per batch of frames or samples; the
 * image workloads code whole images in every round.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 8, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class JitEndToEndBenchmark {
    @Param({"h264dec:threads=1"})
    public String spec;

    private JitWorkload.Workload workload;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        workload = JitWorkload.create(spec);
    }

    @Benchmark
    public int round() {
        return workload.round();
    }
}
