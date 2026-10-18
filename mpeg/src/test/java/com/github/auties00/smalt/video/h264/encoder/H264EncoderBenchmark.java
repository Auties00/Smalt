package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
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
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end encoding throughput: 300 frames of the Xiph "stockholm" clip (720p, and the same
 * clip upscaled to 1080p) at 1 and 3 Mbps, for every speed and 1 or 4 threads. Frames are read
 * from the raw file during the run (from the page cache, a memory copy) to keep the heap small;
 * the matching libx264 figures come from {@code bench-x264.sh} on the same files.
 *
 * <p>Run {@code prepare-bench.sh} first to create {@code target/seq/stockholm720.yuv} and
 * {@code target/seq/stockholm1080.yuv}; the directory can be changed with
 * {@code -Dh264.bench.dir}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
@State(Scope.Benchmark)
public class H264EncoderBenchmark {
    private static final int FRAMES = 300;

    @Param({"720p", "1080p"})
    public String size;

    @Param({"FASTEST", "FAST", "BALANCED", "SLOW"})
    public H264EncoderSpeed speed;

    @Param({"1", "4"})
    public int threads;

    private int width;
    private int height;
    private int bitrate;
    private RandomAccessFile input;
    private YuvPicture picture;
    private byte[] frame;

    @Setup(Level.Trial)
    public void open() throws IOException {
        width = size.equals("720p") ? 1280 : 1920;
        height = size.equals("720p") ? 720 : 1080;
        bitrate = size.equals("720p") ? 1_000_000 : 3_000_000;
        var file = Path.of(System.getProperty("h264.bench.dir", "target/seq"), "stockholm" + height + ".yuv");
        if (!Files.isRegularFile(file) || Files.size(file) < (long) FRAMES * width * height * 3 / 2) {
            throw new IllegalStateException("missing " + file + ": run prepare-bench.sh");
        }
        input = new RandomAccessFile(file.toFile(), "r");
        picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        frame = new byte[width * height * 3 / 2];
    }

    @TearDown(Level.Trial)
    public void close() throws IOException {
        input.close();
    }

    /**
     * Encodes the 300 frames with the configuration a message upload uses (Main profile, ABR with
     * a 2-second VBV, keyframes every 2 seconds), except that FASTEST uses Constrained Baseline as
     * a real-time call would.
     */
    @Benchmark
    public long encode() throws IOException {
        var profile = speed == H264EncoderSpeed.FASTEST ? H264Profile.CONSTRAINED_BASELINE : H264Profile.MAIN;
        var config = H264Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), profile, 0,
                H264RateControl.AVERAGE_BITRATE, bitrate, 2 * bitrate, 2 * bitrate, 23, 60, speed,
                ColorInfo.BT709_LIMITED, threads);
        long bytes = 0;
        input.seek(0);
        try (var encoder = config.encoder()) {
            for (var i = 0; i < FRAMES; i++) {
                input.readFully(frame);
                System.arraycopy(frame, 0, picture.y(), 0, width * height);
                System.arraycopy(frame, width * height, picture.u(), 0, width * height / 4);
                System.arraycopy(frame, width * height * 5 / 4, picture.v(), 0, width * height / 4);
                var sizes = new long[1];
                encoder.encode(new VideoFrame(picture, i, 1, false), packet -> sizes[0] += packet.size());
                bytes += sizes[0];
            }
        }
        return bytes;
    }
}
