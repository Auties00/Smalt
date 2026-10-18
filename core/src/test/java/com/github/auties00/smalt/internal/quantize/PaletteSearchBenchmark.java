package com.github.auties00.smalt.internal.quantize;

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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures the nearest-color search (scalar kernel, vector kernel, and the palette's cached cell
 * search) over the dithered colors of a smooth 640x480 gradient with noise.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
public class PaletteSearchBenchmark {
    @Param({"scalar", "vector", "palette"})
    public String search;

    private int[] queries;
    private int[] palette;
    private int[] reds;
    private int[] greens;
    private int[] blues;
    private PaletteSearchKernel kernel;

    @Setup(Level.Trial)
    public void setup() {
        var width = 640;
        var height = 480;
        var pixels = new int[width * height];
        var random = new Random(1);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var r = Math.clamp(x * 255 / width + random.nextInt(17) - 8, 0, 255);
                var g = Math.clamp(y * 255 / height + random.nextInt(17) - 8, 0, 255);
                var b = Math.clamp((x + y) * 255 / (width + height) + random.nextInt(17) - 8, 0, 255);
                pixels[y * width + x] = r | g << 8 | b << 16 | 0xFF00_0000;
            }
        }
        queries = pixels;
        palette = MedianCut.quantize(pixels, pixels.length, 255);
        var padded = (palette.length + 15) / 16 * 16;
        reds = new int[padded];
        greens = new int[padded];
        blues = new int[padded];
        java.util.Arrays.fill(reds, PaletteSearchKernel.SENTINEL);
        java.util.Arrays.fill(greens, PaletteSearchKernel.SENTINEL);
        java.util.Arrays.fill(blues, PaletteSearchKernel.SENTINEL);
        for (var i = 0; i < palette.length; i++) {
            reds[i] = palette[i] & 0xFF;
            greens[i] = (palette[i] >>> 8) & 0xFF;
            blues[i] = palette[i] >>> 16;
        }
        kernel = search.equals("scalar") ? new ScalarPaletteSearchKernel() : new VectorPaletteSearchKernel();
    }

    @Benchmark
    public int nearest() {
        var sum = 0;
        if (search.equals("palette")) {
            var mapped = new Palette(palette);
            for (var query : queries) {
                sum += mapped.nearest(query);
            }
            return sum;
        }
        var count = reds.length;
        for (var query : queries) {
            sum += kernel.nearest(query & 0xFF, (query >>> 8) & 0xFF, (query >>> 16) & 0xFF, reds, greens, blues, count);
        }
        return sum;
    }
}
