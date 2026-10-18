package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the real-time VP8 encoder: the time to encode one frame of a running call-like
 * stream (speed 6, one-pass CBR, error resilient), at 640x480 and 1280x720, on one thread and on
 * four. The frames are the first 60 of the cached Xiph clip played forwards and backwards, so the
 * motion never jumps; the bitrates match the libvpx comparison runs of the report.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xmx2g", "--add-modules", "jdk.incubator.vector"})
public class Vp8RtEncodeBenchmark {
    @Param({"480", "720"})
    public int size;

    @Param({"1", "4"})
    public int threads;

    @Param({"6"})
    public int speed;

    private List<YuvPicture> frames;
    private Vp8Encoder encoder;
    private long pts;

    @Setup(Level.Trial)
    public void prepare() throws IOException {
        var width = size == 720 ? 1280 : 640;
        var height = size == 720 ? 720 : 480;
        var clip = RtTestVideo.clip(size == 720 ? "johnny720.yuv" : "johnny480.yuv");
        frames = clip != null ? RtTestVideo.read(clip, width, height, 0, 60) : RtTestVideo.synthesize(width, height, 60);
        encoder = Vp8Setup.of(width, height, new Rational(30, 1), new Rational(1, 30),
                size == 720 ? 1_200_000 : 600_000, 4, 63, 0, speed, true, 1, false, 1000, 500, 600, 1, threads).encoder();
        pts = 0;
    }

    @TearDown(Level.Trial)
    public void close() {
        encoder.close();
    }

    @Benchmark
    public Object encodeFrame() {
        var period = 2 * frames.size() - 2;
        var phase = (int) (pts % period);
        var picture = frames.get(phase < frames.size() ? phase : period - phase);
        return encoder.encodeFrame(new VideoFrame(picture, pts++, 1, false)).orElse(null);
    }
}
