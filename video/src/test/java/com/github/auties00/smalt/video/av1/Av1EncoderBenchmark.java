package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
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
 * Measures the time to encode one frame of natural video with the real-time AV1 encoder, at the
 * CIF (foreman), 480p and 720p (johnny) sizes, with one thread and with four threads, over one tile
 * (superblock rows in a wavefront) or four tile columns. Frames cycle forward and backward through
 * the first 60 pictures of the clip so the content stays continuous.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xmx1g", "--add-modules", "jdk.incubator.vector"})
public class Av1EncoderBenchmark {
    @Param({"288", "480", "720"})
    public int size;

    @Param({"1", "4"})
    public int threads;

    @Param({"0", "2"})
    public int tileColumnsLog2;

    @Param({"FASTER"})
    public String speed;

    private List<YuvPicture> frames;

    private Av1Encoder encoder;

    private long pts;

    @Setup(Level.Trial)
    public void prepare() throws IOException {
        var width = size == 720 ? 1280 : size == 480 ? 640 : 352;
        var height = size == 720 ? 720 : size == 480 ? 480 : 288;
        var name = size == 720 ? "johnny720.yuv" : size == 480 ? "johnny480.yuv" : "foreman.yuv";
        var clip = Vp9TestVideo.clip(name);
        frames = clip != null ? Vp9TestVideo.read(clip, width, height, 0, 60)
                : Vp9TestVideo.synthesize(width, height, 60);
        var bitrate = size == 720 ? 1_200_000 : size == 480 ? 600_000 : 300_000;
        encoder = new Av1Encoder(width, height, Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate)
                .speed(Av1EncoderSpeed.valueOf(speed)).dropFrames(false).threads(threads)
                .tileColumnsLog2(tileColumnsLog2).timeBase(new Rational(1, 30)).build());
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
    @Fork(value = 3, jvmArgsAppend = {"-Xmx1g", "--add-modules", "jdk.incubator.vector", "-Dsmalt.simd=false"})
    public Object encodeFrameScalar() {
        return encodeFrame();
    }
}
