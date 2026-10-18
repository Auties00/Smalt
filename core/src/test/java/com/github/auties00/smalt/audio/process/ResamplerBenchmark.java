package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures resampling 60 s of 44.1 kHz stereo, fed in 4096-frame blocks as a decoder would: to
 * 16 kHz mono after a stereo-to-mono mix (the voice-note path), and to 48 kHz stereo (the M4A
 * audio path). Compare with {@code ffmpeg -threads 1 -benchmark -f f32le -ar 44100 -ac 2 -i
 * in.raw -af "pan=mono|c0=0.5*c0+0.5*c1,aresample=16000" -f null -} and {@code -af
 * aresample=48000}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ResamplerBenchmark {
    private static final int RATE = 44100;
    private static final int FRAMES = 60 * RATE;
    private static final int BLOCK = 4096;

    private float[][] input;
    private float[][] block;
    private float[][] mono;
    private float[][] monoOut;
    private float[][] stereoOut;
    private ChannelMixer mixer;
    private Resampler toMono16k;
    private Resampler toStereo48k;

    @Setup
    public void setup() {
        var random = new SplittableRandom(1);
        input = new float[2][FRAMES];
        for (var i = 0; i < FRAMES; i++) {
            var tone = 0.3 * Math.sin(2 * Math.PI * 440 * i / RATE) + 0.2 * Math.sin(2 * Math.PI * 3000 * i / RATE);
            input[0][i] = (float) (tone + 0.05 * random.nextDouble(-1, 1));
            input[1][i] = (float) (tone * 0.8 + 0.05 * random.nextDouble(-1, 1));
        }
        block = new float[2][];
        mono = new float[1][BLOCK];
        mixer = new ChannelMixer(ChannelLayout.STEREO, ChannelLayout.MONO);
        toMono16k = new Resampler(RATE, 16000, 1, Resampler.Quality.STANDARD);
        toStereo48k = new Resampler(RATE, 48000, 2, Resampler.Quality.STANDARD);
        monoOut = new float[1][toMono16k.maxOutputFrames(BLOCK) + 64];
        stereoOut = new float[2][toStereo48k.maxOutputFrames(BLOCK) + 64];
    }

    @Benchmark
    public long downmixTo16kMono() {
        long total = 0;
        var slice = new float[2][BLOCK];
        for (var offset = 0; offset < FRAMES; offset += BLOCK) {
            var count = Math.min(BLOCK, FRAMES - offset);
            System.arraycopy(input[0], offset, slice[0], 0, count);
            System.arraycopy(input[1], offset, slice[1], 0, count);
            mixer.process(slice, mono, count);
            total += toMono16k.process(mono, count, monoOut, 0);
        }
        total += toMono16k.flush(monoOut, 0);
        return total;
    }

    @Benchmark
    public long stereoTo48k() {
        long total = 0;
        var slice = new float[2][BLOCK];
        for (var offset = 0; offset < FRAMES; offset += BLOCK) {
            var count = Math.min(BLOCK, FRAMES - offset);
            System.arraycopy(input[0], offset, slice[0], 0, count);
            System.arraycopy(input[1], offset, slice[1], 0, count);
            total += toStereo48k.process(slice, count, stereoOut, 0);
        }
        total += toStereo48k.flush(stereoOut, 0);
        return total;
    }

    /**
     * Writes the benchmark input as raw interleaved f32le, for the FFmpeg comparison.
     */
    public static void main(String[] args) throws java.io.IOException {
        var benchmark = new ResamplerBenchmark();
        benchmark.setup();
        var raw = java.nio.ByteBuffer.allocate(FRAMES * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < FRAMES; i++) {
            raw.putFloat(benchmark.input[0][i]);
            raw.putFloat(benchmark.input[1][i]);
        }
        java.nio.file.Files.write(java.nio.file.Path.of(args[0]), raw.array());
    }
}
