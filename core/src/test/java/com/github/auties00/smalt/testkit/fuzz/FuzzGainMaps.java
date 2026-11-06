package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Optional;

/**
 * The fuzz driver of the gain map and auxiliary image entry points of an image decoder: it decodes the gain map,
 * renders the image with it (to PQ, or to HLG at half the headroom), lists the auxiliary images and decodes the
 * first ones, checking that every picture has a size, that the rendering keeps the base image's size, and that a
 * listed image decodes.
 */
public final class FuzzGainMaps {
    /** The auxiliary images decoded per input. */
    private static final int MAX_AUXILIARY = 4;

    private FuzzGainMaps() {
        throw new AssertionError();
    }

    /**
     * The entry points of a decoder.
     */
    public interface Decoder {
        /** Decodes the base image. */
        DecodedImage decode(MediaSource source, ImageDecoderOptions options);

        /** Decodes the gain map. */
        Optional<GainMap> gainMap(MediaSource source, ImageDecoderOptions options);

        /** Lists the auxiliary images. */
        List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits);

        /** Decodes an auxiliary image. */
        DecodedImage auxiliaryImage(MediaSource source, int index, ImageDecoderOptions options);
    }

    /**
     * Runs the gain map and auxiliary image steps on an input.
     *
     * @param target the name of the target
     * @param data the input
     * @param decoder the entry points
     * @return the run
     */
    public static FuzzRun run(String target, byte[] data, Decoder decoder) {
        var run = Fuzz.start(target);
        var options = ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build();
        var gainMap = run.value("gain map", () -> decoder.gainMap(MediaSource.of(data), options)).flatMap(map -> map);
        gainMap.ifPresent(map -> {
            check(map.image().width(), map.image().height(), "gain map");
            run.output();
            run.step("render", () -> {
                var base = decoder.decode(MediaSource.of(data), options);
                var picture = base.picture();
                if ((long) picture.width() * picture.height() > Fuzz.MAX_CONVERTED_PIXELS) {
                    return;
                }
                var hlg = run.sampled(data, 0, 2);
                var rendering = hlg ? GainMapOptions.builder().transfer(TransferCharacteristics.HLG)
                        .headroom(map.metadata().alternateHeadroom() / 2).build() : GainMapOptions.defaults();
                var rendered = GainMaps.apply(base, map, rendering).picture();
                if (!(rendered instanceof Rgba16Picture) || rendered.width() != picture.width()
                        || rendered.height() != picture.height()) {
                    throw new IllegalStateException("rendered " + rendered.width() + "x" + rendered.height()
                            + " from " + picture.width() + "x" + picture.height());
                }
                run.output();
            });
        });
        var listed = run.value("auxiliary images", () -> decoder.auxiliaryImages(MediaSource.of(data), Fuzz.LIMITS));
        listed.ifPresent(images -> {
            for (var index = 0; index < Math.min(images.size(), MAX_AUXILIARY); index++) {
                var image = images.get(index);
                if (image.index() != index) {
                    throw new IllegalStateException("auxiliary image " + index + " has index " + image.index());
                }
                var at = index;
                run.step("auxiliary image " + index, () -> {
                    var decoded = decoder.auxiliaryImage(MediaSource.of(data), at, options).picture();
                    check(decoded.width(), decoded.height(), "auxiliary image");
                    run.output();
                });
            }
        });
        return run;
    }

    /**
     * Checks that a picture has a size.
     *
     * @param width the width
     * @param height the height
     * @param what the picture, for the message
     */
    private static void check(int width, int height, String what) {
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException(what + " is " + width + "x" + height);
        }
    }
}
