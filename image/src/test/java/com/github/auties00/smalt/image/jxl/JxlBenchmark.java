package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.io.MediaSource;
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
 * JMH benchmark of whole JPEG XL decodes to 8-bit RGBA, on conformance files: {@code bike}
 * (2048x2560 VarDCT photo), {@code progressive} (4064x2704 VarDCT with an LF frame and progressive
 * passes), {@code patches_lossless} (1600x1096 lossless modular with patches) and {@code bicycles}
 * (1024x631 VarDCT). Threading follows {@code smalt.threads} (pass {@code -jvmArgsAppend
 * -Dsmalt.threads=1} for one thread); {@code -jvmArgsAppend -Dsmalt.simd=false} selects the scalar
 * kernels. Run with {@code mvn -o -DskipTests test-compile exec:exec -Dexec.executable=java
 * -Dexec.args="--add-modules jdk.incubator.vector -cp %classpath org.openjdk.jmh.Main JxlBenchmark"}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx2g"})
public class JxlBenchmark {
    @State(Scope.Benchmark)
    public static class Input {
        @Param({"bike", "progressive", "patches_lossless", "bicycles"})
        public String name;

        byte[] file;

        @Setup(Level.Trial)
        public void setup() {
            file = JxlVectors.read(JxlVectors.find(name).input());
        }
    }

    @Benchmark
    public Object decode(Input input) {
        return JxlDecoder.decode(MediaSource.of(input.file)).picture();
    }
}
