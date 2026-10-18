package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.io.MediaSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Measures decoding of a 4032x3024 photo-like JPEG (q92, 4:2:0, baseline and progressive) at
 * full size and at 1/8, the operations behind every received image and thumbnail. The inputs are
 * produced by {@link JpegBenchmarkInputs}; compare with {@code ffmpeg -threads 1 -benchmark}
 * decoding the same file to {@code -f null}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class JpegDecodeBenchmark {
    @Param({"photo_q92_420.jpg", "photo_q92_420_progressive.jpg"})
    public String input;

    private byte[] data;

    @Setup
    public void setup() throws IOException {
        data = Files.readAllBytes(JpegBenchmarkInputs.ensure(input));
    }

    @Benchmark
    public Object decodeFull() {
        return JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
    }

    @Benchmark
    public Object decodeEighth() {
        return JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(504, 378)).picture();
    }

    @Benchmark
    public Object decodeCoefficients() {
        return new JpegReader(data, 0, data.length, com.github.auties00.smalt.MediaLimits.DEFAULT).decodeCoefficients();
    }

    static Path path(String name) {
        return Path.of(System.getProperty("jpeg.bench.dir", "target/jpeg-bench")).resolve(name);
    }
}
