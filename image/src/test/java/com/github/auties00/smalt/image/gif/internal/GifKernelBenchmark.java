package com.github.auties00.smalt.image.gif.internal;

import com.github.auties00.smalt.image.gif.GifEncoderBenchmark;
import com.github.auties00.smalt.internal.quantize.MedianCut;
import com.github.auties00.smalt.internal.quantize.Palette;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures LZW compression of a quantized natural frame in isolation. The nearest-color search
 * is measured by core's {@code internal.quantize.PaletteSearchBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
public class GifKernelBenchmark {
    private byte[] indices;
    private GifLzwEncoder lzw;

    @Setup(Level.Trial)
    public void setup() throws java.io.IOException {
        var frame = GifEncoderBenchmark.frames().getFirst();
        var pixels = new int[frame.width() * frame.height()];
        var p = frame.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (p[i * 4] & 0xFF) | (p[i * 4 + 1] & 0xFF) << 8 | (p[i * 4 + 2] & 0xFF) << 16 | 0xFF00_0000;
        }
        var mapped = new Palette(MedianCut.quantize(pixels, pixels.length, 255));
        indices = new byte[pixels.length];
        for (var i = 0; i < pixels.length; i++) {
            indices[i] = (byte) mapped.indexOf(pixels[i] & 0xFFFFFF);
        }
        lzw = new GifLzwEncoder();
    }

    @Benchmark
    public int lzw() {
        var out = new GifOutput(indices.length);
        lzw.encode(indices, 0, indices.length, 8, out);
        return out.size();
    }
}
