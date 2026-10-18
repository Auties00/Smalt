package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterBenchmarkInputs;
import com.github.auties00.smalt.io.MediaSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Measures single-threaded PNG decoding of 4000x3000 images and PNG encoding of the 4000x3000
 * RGBA image at level 6 with adaptive filtering (run the encode with {@code -p kind=rgba}).
 * Compare with {@code bench-ffmpeg.py}, which times the ffmpeg CLI with an input and output
 * {@code -threads 1} on the same files.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class PngBenchmark {
    @Param({"rgba", "rgb", "rgba-noise", "rgb-noise"})
    public String kind;

    private byte[] data;
    private RgbaPicture picture;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        data = Files.readAllBytes(RasterBenchmarkInputs.png(kind));
        picture = (RgbaPicture) PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
    }

    @Benchmark
    public Object decode() {
        return PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults());
    }

    @Benchmark
    public byte[] encodeLevel6Adaptive() {
        return PngEncoder.encode(picture, PngEncoderOptions.defaults());
    }
}
