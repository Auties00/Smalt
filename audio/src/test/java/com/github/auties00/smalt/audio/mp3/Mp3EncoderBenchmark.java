package com.github.auties00.smalt.audio.mp3;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures encoding 30 s of synthetic stereo 44.1 kHz music at 128 kbit/s CBR and at VBR quality
 * 2, with the scalar or the Vector API kernel; compare with
 * {@code ffmpeg -threads 1 -f f32le -ar 44100 -ac 2 -i <raw> -c:a libmp3lame -b:a 128k -f null -}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class Mp3EncoderBenchmark {
    @Param({"cbr128", "vbr2"})
    public String mode;

    @Param({"scalar", "vector"})
    public String kernel;

    private float[][] input;
    private Mp3EncoderOptions options;

    @Setup
    public void setup() {
        input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 1152 * 1148, 7);
        options = mode.equals("cbr128") ? Mp3EncoderOptions.ofConstantBitrate(128_000) : Mp3EncoderOptions.ofVariableBitrate(2);
    }

    @Benchmark
    public long encode() {
        var bytes = new long[1];
        var engine = new Mp3FrameEncoder(options,
                kernel.equals("scalar") ? new ScalarMp3EncoderKernel() : VectorMp3EncoderKernel.create(),
                frame -> bytes[0] += frame.length);
        var granule = new float[2][576];
        for (var offset = 0; offset + 576 <= input[0].length; offset += 576) {
            System.arraycopy(input[0], offset, granule[0], 0, 576);
            System.arraycopy(input[1], offset, granule[1], 0, 576);
            engine.push(granule, 0);
        }
        engine.finish();
        return bytes[0];
    }
}
