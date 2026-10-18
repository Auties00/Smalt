package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Measures encoding of a 4032x3024 photo-like picture at q92 4:2:0, from RGBA (the image upload
 * path) and from a 4:2:0 planar picture (the pass-through path), with optimized and standard
 * Huffman tables. Compare with {@code ffmpeg -threads 1 -benchmark -c:v mjpeg} on the same pixels.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class JpegEncodeBenchmark {
    private RgbaPicture rgba;
    private YuvPicture yuv;

    @Setup
    public void setup() throws IOException {
        var rgb = Files.readAllBytes(JpegBenchmarkInputs.ensure("photo.rgb"));
        rgba = JpegTestSupport.rgba(JpegBenchmarkInputs.WIDTH, JpegBenchmarkInputs.HEIGHT, rgb);
        var jpeg = Files.readAllBytes(JpegBenchmarkInputs.ensure("photo_q92_420.jpg"));
        yuv = (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
    }

    @Benchmark
    public byte[] rgbaOptimized() {
        return JpegEncoder.encode(rgba, JpegEncoderOptions.ofQuality(92));
    }

    @Benchmark
    public byte[] rgbaStandard() {
        return JpegEncoder.encode(rgba, JpegEncoderOptions.builder().quality(92).chromaFormat(ChromaFormat.YUV420)
                .optimizeHuffman(false).build());
    }

    @Benchmark
    public byte[] yuvOptimized() {
        return JpegEncoder.encode(yuv, JpegEncoderOptions.ofQuality(92));
    }
}
