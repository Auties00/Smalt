package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.testkit.image.RasterBenchmarkInputs;
import com.github.auties00.smalt.io.MediaSource;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures TIFF decoding and encoding of a 2048x1536 photograph-like RGB image (a crop of the
 * noisy Mandelbrot raster benchmark input) in 8-row strips, as libtiff's tiffcp writes it with each
 * compression; the files are kept in {@code target/tiff-bench} for the libtiff and ffmpeg CLI
 * comparison. Run single-threaded with {@code -jvmArgsAppend -Dsmalt.threads=1}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class TiffBenchmark {
    @Param({"lzw:2", "zip:2", "lzw", "packbits", "none"})
    public String kind;

    private byte[] data;
    private RgbaPicture picture;
    private TiffEncoderOptions options;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        var png = Files.readAllBytes(RasterBenchmarkInputs.png("rgb-noise"));
        var full = PngDecoder.decode(MediaSource.of(png), ImageDecoderOptions.defaults()).picture();
        picture = (RgbaPicture) ImageOps.crop(full, 0, 0, 2048, 1536);
        var base = TiffEncoder.encode(picture, TiffEncoderOptions.uncompressed().toBuilder().strips(8).build());
        data = LibTiff.tiffcp(base, List.of("-c", kind, "-r", "8"));
        var directory = Path.of("target", "tiff-bench");
        Files.createDirectories(directory);
        Files.write(directory.resolve(kind.replace(':', '-') + ".tif"), data);
        options = switch (kind) {
            case "lzw:2" -> TiffEncoderOptions.defaults();
            case "zip:2" -> TiffEncoderOptions.deflate();
            case "lzw" -> TiffEncoderOptions.builder().predictor(TiffPredictor.NONE).build();
            case "packbits" -> TiffEncoderOptions.uncompressed().toBuilder().compression(TiffCompression.PACKBITS)
                    .build();
            default -> TiffEncoderOptions.uncompressed();
        };
    }

    @Benchmark
    public Object decode() {
        return TiffDecoder.decode(MediaSource.of(data));
    }

    @Benchmark
    public byte[] encode() {
        return TiffEncoder.encode(picture, options);
    }
}
