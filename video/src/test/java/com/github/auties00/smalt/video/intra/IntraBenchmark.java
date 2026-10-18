package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResDecoderOptions;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures ProRes 422 HQ and DNxHR HQ decoding and encoding of 1920x1080 frames of natural video
 * (the Xiph {@code parkrun} sequence upscaled, or a synthetic scene without it), per frame, on one
 * thread and on the shared pool. The ffmpeg streams it decodes come from prores_ks and dnxhd.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IntraBenchmark {
    private static final int FRAMES = 4;

    @Param({"1", "0"})
    public int threads;

    private List<byte[]> prores;
    private List<byte[]> dnxhr;
    private List<Picture> pictures;
    private ProResDecoder proresDecoder;
    private DnxhdDecoder dnxhdDecoder;
    private ProResEncoder proresEncoder;
    private DnxhdEncoder dnxhdEncoder;

    static List<String> input() {
        var parkrun = Path.of(Conformance.directory().toString(), "720p50_parkrun_ter.y4m");
        if (Files.isRegularFile(parkrun)) {
            return List.of("-i", parkrun.toString());
        }
        return List.of("-f", "lavfi", "-i", "mandelbrot=size=1920x1080:rate=25");
    }

    static Path encoded(List<String> codec) {
        var output = new ArrayList<>(List.of("-vf", "scale=1920:1080:flags=bicubic", "-frames:v", Integer.toString(FRAMES)));
        output.addAll(codec);
        output.addAll(List.of("-an", "-f", "mov"));
        return Fixtures.ffmpeg("mov", input(), output);
    }

    @Setup
    public void setUp() {
        prores = new ArrayList<>();
        for (var packet : IntraTesting.demux(encoded(List.of("-c:v", "prores_ks", "-profile:v", "3",
                "-pix_fmt", "yuv422p10le"))).packets()) {
            prores.add(packet.data());
        }
        dnxhr = new ArrayList<>();
        for (var packet : IntraTesting.demux(encoded(List.of("-c:v", "dnxhd", "-profile:v", "dnxhr_hq",
                "-pix_fmt", "yuv422p"))).packets()) {
            dnxhr.add(packet.data());
        }
        pictures = new ArrayList<>();
        var decoder = new ProResDecoder();
        for (var frame : prores) {
            var picture = (com.github.auties00.smalt.image.Yuv16Picture) decoder.decodeFrame(frame, 0, frame.length);
            pictures.add(picture.toBuilder().luma(picture.y().clone(), picture.yStride())
                    .chroma(picture.u().clone(), picture.v().clone(), picture.uvStride()).build());
        }
        proresDecoder = new ProResDecoder(ProResDecoderOptions.builder().threads(threads).build());
        dnxhdDecoder = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(threads).build());
        proresEncoder = new ProResEncoder(1920, 1080, ProResEncoderOptions.of(ProResProfile.HQ).toBuilder()
                .threads(threads).build());
        dnxhdEncoder = new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                .threads(threads).build());
    }

    @Benchmark
    @OperationsPerInvocation(FRAMES)
    public int proresDecode() {
        var sum = 0;
        for (var frame : prores) {
            sum += proresDecoder.decodeFrame(frame, 0, frame.length).width();
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(FRAMES)
    public int dnxhrDecode() {
        var sum = 0;
        for (var frame : dnxhr) {
            sum += dnxhdDecoder.decodeFrame(frame, 0, frame.length).width();
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(FRAMES)
    public int proresEncode() {
        var sum = 0;
        for (var picture : pictures) {
            sum += proresEncoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).size();
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(FRAMES)
    public int dnxhrEncode() {
        var sum = 0;
        for (var picture : pictures) {
            sum += dnxhdEncoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).size();
        }
        return sum;
    }
}
