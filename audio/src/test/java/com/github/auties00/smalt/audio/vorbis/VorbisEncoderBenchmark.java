package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
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
 * Measures encoding 30 s of synthetic stereo 44.1 kHz music at a quality, in 4096-frame blocks;
 * compare with
 * {@code ffmpeg -threads 1 -f f32le -ar 44100 -ac 2 -i <raw> -c:a libvorbis -q:a <quality> -f null -}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class VorbisEncoderBenchmark {
    @Param({"0", "4", "8"})
    public float quality;

    private AudioBuffer[] blocks;

    @Setup
    public void setup() {
        var input = VorbisEncoding.signal(VorbisEncoding.Shape.MUSIC, 2, 44100, 44100 * 30, 7);
        var count = (input[0].length + 4095) / 4096;
        blocks = new AudioBuffer[count];
        for (var b = 0; b < count; b++) {
            var length = Math.min(4096, input[0].length - 4096 * b);
            var planes = new float[2][length];
            System.arraycopy(input[0], 4096 * b, planes[0], 0, length);
            System.arraycopy(input[1], 4096 * b, planes[1], 0, length);
            blocks[b] = new AudioBuffer(ChannelLayout.STEREO, 44100, planes, length);
        }
    }

    @Benchmark
    public long encode() {
        var bytes = new long[1];
        var encoder = new VorbisEncoder(VorbisEncoderOptions.ofQuality(quality));
        for (var block : blocks) {
            encoder.encode(block, packet -> bytes[0] += packet.size());
        }
        encoder.flush(packet -> bytes[0] += packet.size());
        return bytes[0];
    }
}
