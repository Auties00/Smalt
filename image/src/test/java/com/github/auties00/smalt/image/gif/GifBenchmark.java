package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.testkit.image.RasterBenchmarkInputs;
import com.github.auties00.smalt.io.MediaSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Measures single-threaded decoding and compositing of every frame of a 300-frame 480x270
 * animated GIF. Compare with {@code ffmpeg -threads 1 -benchmark -i <file> -f null -}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class GifBenchmark {
    private byte[] data;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        data = Files.readAllBytes(RasterBenchmarkInputs.gif());
    }

    @Benchmark
    public int decodeAllFrames() {
        var checksum = 0;
        try (var reader = GifDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
            for (var current = reader.read(); current != null; current = reader.read()) {
                checksum += current.picture().pixels()[checksum & 0xFFFF];
            }
        }
        return checksum;
    }
}
