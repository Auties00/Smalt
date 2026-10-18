package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaLimits;
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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * A/B of the LZW decoder against {@link BaselineLzwDecoder} on every strip of the libtiff-written
 * {@code lzw-2.tif} that {@code TiffBenchmark} leaves in {@code target/tiff-bench} (run it first).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class LzwBenchmark {
    private byte[][] strips;
    private byte[] chunk;
    private final LzwDecoder decoder = new LzwDecoder();
    private final BaselineLzwDecoder baseline = new BaselineLzwDecoder();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        var data = Files.readAllBytes(Path.of("target", "tiff-bench", "lzw-2.tif"));
        var directory = TiffFile.open(MediaSource.of(data), MediaLimits.DEFAULT).pages().getFirst();
        var offsets = directory.longs(TiffTags.STRIP_OFFSETS);
        var counts = directory.longs(TiffTags.STRIP_BYTE_COUNTS);
        strips = new byte[offsets.length][];
        for (var i = 0; i < strips.length; i++) {
            strips[i] = Arrays.copyOfRange(data, (int) offsets[i], (int) (offsets[i] + counts[i]));
        }
        chunk = new byte[(int) (directory.longValue(TiffTags.IMAGE_WIDTH, 0) * 3 * directory.longValue(TiffTags.ROWS_PER_STRIP, 0))];
    }

    @Benchmark
    public int candidate() {
        var total = 0;
        for (var strip : strips) {
            total += decoder.decode(strip, 0, strip.length, chunk, 0, chunk.length);
        }
        return total;
    }

    @Benchmark
    public int baseline() {
        var total = 0;
        for (var strip : strips) {
            total += baseline.decode(strip, 0, strip.length, chunk, 0, chunk.length);
        }
        return total;
    }
}
