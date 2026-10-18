package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Times whole AVIF decodes of the AOM conformance files: the 6400x2880 grid of twenty 1280x720
 * tiles ({@code Summer_in_Tomsk_720p_5x4_grid}), a 3840x2160 single item
 * ({@code Summer_Nature_4k}) and a 3840x2160 item with a 3840x2160 alpha item
 * ({@code bbb_alpha_inverted}), to 8-bit pictures.
 *
 * <p>{@link #decode()} is the decoder as shipped, whose grid tiles decode in parallel;
 * {@link #baseline()} decodes the grid tiles one after the other, each with the AV1 decoder's own
 * frame-level threading, so one invocation gives the gain of tile parallelism (single items take
 * the same path in both, which bounds the noise of a run). Compare with {@code ffmpeg -benchmark -c:v libdav1d -i <file> -map 0:g:0 -f null -}
 * (the stream group selects the stitched grid).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class AvifDecoderBenchmark {
    @Param({"Microsoft/Summer_in_Tomsk_720p_5x4_grid.avif", "Microsoft/Summer_Nature_4k.avif",
            "Microsoft/bbb_alpha_inverted.avif"})
    public String file;

    private byte[] data;

    @Setup
    public void setUp() throws Exception {
        var sha = switch (file) {
            case "Microsoft/Summer_in_Tomsk_720p_5x4_grid.avif" -> "c9148149b108f5fbce7300903bd59937bfc4c677f2ce359668ad664adb18ec2f";
            case "Microsoft/Summer_Nature_4k.avif" -> "bae6a3b420018fc725e862593d1c78c8261c9f734f4ce41a69c1a9906296e302";
            default -> "83d68084a93f043a89d10373f0ca26dafc988eec811b8076cabb2ff1580c1817";
        };
        var name = "avif-" + file.replace('/', '-');
        data = Files.readAllBytes(Conformance.fetch(name,
                "https://raw.githubusercontent.com/AOMediaCodec/av1-avif/bf4c18d1f3971069b75e87d6ee469790589f4f09/testFiles/"
                        + file, sha));
    }

    @Benchmark
    public Picture decode() {
        return AvifDecoder.decode(MediaSource.of(data)).picture();
    }

    @Benchmark
    public Picture baseline() {
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var plan = StillPlan.of(source, file, MediaLimits.DEFAULT);
        var decoder = new ItemDecoder(MediaLimits.DEFAULT, true);
        Canvas canvas;
        if (plan.grid() == null) {
            var frame = decode(decoder, source, file, plan.colorTiles().getFirst());
            canvas = new Canvas(MediaLimits.DEFAULT, frame.w, frame.h, SequenceHeader.chromaFormat(frame.layout), 8, false, plan.hasAlpha());
            canvas.writeColor(frame, 0, 0, frame.w, frame.h, true);
            frame.release();
        } else {
            var grid = plan.grid();
            var extent = plan.colorTiles().getFirst().property(HeifProperty.SpatialExtent.class);
            var tileWidth = (int) extent.width();
            var tileHeight = (int) extent.height();
            var width = (int) grid.outputWidth();
            var height = (int) grid.outputHeight();
            canvas = new Canvas(MediaLimits.DEFAULT, width, height, com.github.auties00.smalt.image.ChromaFormat.YUV420,
                    8, false, false);
            for (var index = 0; index < plan.colorTiles().size(); index++) {
                var frame = decode(decoder, source, file, plan.colorTiles().get(index));
                var x = (index % grid.columns()) * tileWidth;
                var y = (index / grid.columns()) * tileHeight;
                canvas.writeColor(frame, x, y, Math.min(tileWidth, width - x), Math.min(tileHeight, height - y), true);
                frame.release();
            }
        }
        if (plan.hasAlpha()) {
            var frame = decode(decoder, source, file, plan.alphaTiles().getFirst());
            canvas.writeAlpha(frame, 0, 0, canvas.width, canvas.height, !frame.seqHdr.colorRange);
            frame.release();
        }
        return canvas.toPicture(com.github.auties00.smalt.image.ColorInfo.JPEG);
    }

    private static Av1Picture decode(ItemDecoder decoder, MediaSource source,
                                                                               HeifFile file,
                                                                               com.github.auties00.smalt.internal.heif.HeifItem item) {
        return decoder.decodeItem(item.property(HeifProperty.Av1Configuration.class),
                file.readItem(source, item, MediaLimits.DEFAULT), 0, HeifProperty.LayerSelector.ALL_LAYERS);
    }
}
