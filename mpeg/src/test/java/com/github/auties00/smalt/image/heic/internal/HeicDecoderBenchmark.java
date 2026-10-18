package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Times HEIC decodes of phone photos from pillow_heif's test images: a 12 MP iPhone photo
 * ({@code pug.heic}, 4032x3024 as a 8x6 grid of 512x512 tiles) and a 5 MP Xiaomi photo
 * ({@code xiaomi.heic}, 2592x1944 as a 6x4 grid of 512x512 tiles), to 8-bit pictures.
 *
 * <p>{@link #decode()} is the decoder as shipped, whose grid tiles decode in parallel;
 * {@link #tiles()} decodes every tile on the calling thread without assembling the picture, so
 * {@code decode()} against it is the gain of tile parallelism. Compare with
 * {@code ffmpeg -benchmark -i <file> -map 0:g:0 -frames:v 1 -f null -} (the stream group selects
 * the stitched grid; ffmpeg runs one decoder thread per tile stream).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class HeicDecoderBenchmark {
    @Param({"tests/images/heif_other/pug.heic", "tests/images/heif_special/xiaomi.heic"})
    public String file;

    private byte[] data;

    @Setup
    public void setUp() throws Exception {
        var sha = switch (file) {
            case "tests/images/heif_other/pug.heic" -> "1ddce96766efbfa0f6bdf634bdab26bdeff831393df8d67f4cdc7b7a7afebc67";
            default -> "cb5c7d9131931df4abd9bfe1c3de47617b68d1fae54a1c367fc5981ffea6aeed";
        };
        data = Files.readAllBytes(Conformance.fetch("heic-pillow-" + file.replace('/', '-'),
                "https://raw.githubusercontent.com/bigcat88/pillow_heif/b16be1196dfa465a342d68894696e685ca3655cb/" + file,
                sha));
    }

    @Benchmark
    public Picture decode() {
        return HeicDecoder.decode(MediaSource.of(data)).picture();
    }

    @Benchmark
    public int tiles() {
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT, false);
        var decoder = new ItemDecoder(MediaLimits.DEFAULT, 1);
        var sum = 0;
        for (var id : file.primaryItem().references("dimg")) {
            var tile = file.item(id);
            var config = tile.property(HeifProperty.HevcConfiguration.class).record();
            var frame = decoder.decodeItem(List.of(config), List.of(file.readItem(source, tile, MediaLimits.DEFAULT)));
            sum += frame.y[0];
        }
        decoder.close();
        return sum;
    }
}
