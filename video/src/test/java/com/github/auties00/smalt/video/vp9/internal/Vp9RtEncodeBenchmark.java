package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9Setup;
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
 * Measures the real-time VP9 encoder per frame on natural video (Xiph's Johnny clip when cached,
 * synthetic motion otherwise), at the sizes and speed of a video call, with one and four threads.
 * The clip plays forwards then backwards so the encoder never sees a cut. The scalar variant runs
 * in forks with the Vector API kernels disabled.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xmx2g", "--add-modules", "jdk.incubator.vector"})
public class Vp9RtEncodeBenchmark {
    @Param({"480", "720"})
    public int size;

    @Param({"1", "4"})
    public int threads;

    @Param({"6"})
    public int speed;

    private List<YuvPicture> frames;
    private Vp9Encoder encoder;
    private long pts;

    @Setup(Level.Trial)
    public void prepare() throws IOException {
        var width = size == 720 ? 1280 : 640;
        var height = size == 720 ? 720 : 480;
        var clip = Vp9TestVideo.clip(size == 720 ? "johnny720.yuv" : "johnny480.yuv");
        frames = clip != null ? Vp9TestVideo.read(clip, width, height, 0, 60)
                : Vp9TestVideo.synthesize(width, height, 60);
        encoder = Vp9Setup.of(width, height, new Rational(30, 1),
                new Rational(1, 30), size == 720 ? 1_200_000 : 600_000, 2, 52, 0, speed, true, true, 1, false, 1000,
                500, 600, 6, threads).encoder();
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

    @Benchmark
    @Fork(value = 3, jvmArgsAppend = {"-Xmx2g", "--add-modules", "jdk.incubator.vector", "-Dsmalt.simd=false"})
    public Object encodeFrameScalar() {
        return encodeFrame();
    }
}
