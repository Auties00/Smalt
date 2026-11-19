package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;

import static com.github.auties00.smalt.image.avif.AvifStructureTest.encode;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.still;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a reduced-size hint decodes the smallest qualifying thumbnail item ({@code thmb}
 * reference) of an AVIF still image instead of the image, as the decoder documents, on files built
 * box by box around AV1 stills encoded by ffmpeg (libaom) and on the AVIF test file with thumbnails.
 */
@DisplayName("AVIF thumbnails")
class AvifThumbnailTest {
    private static final AvifStructureTest.Coded FULL = encode("scale=128:96", 128, 96, "yuv420p");
    private static final AvifStructureTest.Coded HALF = encode("scale=64:48", 64, 48, "yuv420p");
    private static final AvifStructureTest.Coded QUARTER = encode("scale=32:24", 32, 24, "yuv420p");
    private static final AvifStructureTest.Coded SQUARE = encode("scale=48:48", 48, 48, "yuv420p");

    /** Builds the file: the full image and the given thumbnails of it. */
    private static byte[] file(AvifStructureTest.Coded... thumbnails) {
        var builder = new HeifBuilder();
        var primary = still(builder, FULL);
        for (var thumbnail : thumbnails) {
            builder.reference("thmb", still(builder, thumbnail), primary);
        }
        builder.primary(primary);
        return builder.build();
    }

    private static Picture alone(AvifStructureTest.Coded coded) {
        var builder = new HeifBuilder();
        builder.primary(still(builder, coded));
        return AvifDecoder.decode(MediaSource.of(builder.build())).picture();
    }

    @ParameterizedTest(name = "a hint of {0}x{1} decodes {2}x{3}")
    @CsvSource({"40, 30, 64, 48", "20, 10, 32, 24", "64, 48, 64, 48", "65, 20, 128, 96", "0, 30, 64, 48",
            "200, 200, 128, 96"})
    @DisplayName("decode the smallest thumbnail at least as large as the hint, and the image otherwise")
    void smallest(int minWidth, int minHeight, int width, int height) {
        var data = file(HALF, QUARTER);
        var decoded = AvifDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(minWidth, minHeight));
        assertEquals(width, decoded.picture().width());
        assertEquals(height, decoded.picture().height());
        assertEquals(128, decoded.sourceWidth());
        assertEquals(96, decoded.sourceHeight());
        var expected = width == 64 ? alone(HALF) : width == 32 ? alone(QUARTER) : alone(FULL);
        AvifConformanceTest.assertPlanesEqual(AvifReference.Planes.of(expected),
                AvifReference.Planes.of(decoded.picture()), "decoded picture");
    }

    @Test
    @DisplayName("skip thumbnails of another aspect ratio or orientation, and corrupt ones")
    void skipped() {
        var builder = new HeifBuilder();
        var primary = still(builder, FULL);
        builder.reference("thmb", still(builder, SQUARE), primary);
        builder.reference("thmb", still(builder, HALF, essential(HeifBuilder.irot(2))), primary);
        var corrupt = builder.item("av01", new byte[]{0x12, 0, 0x0A, 0x0B}, plain(HeifBuilder.ispe(32, 24)),
                essential(HeifBuilder.av1C(QUARTER.config())));
        builder.reference("thmb", corrupt, primary).primary(primary);
        var decoded = AvifDecoder.decode(MediaSource.of(builder.build()), ImageDecoderOptions.ofMinimumSize(16, 12));
        assertEquals(128, decoded.picture().width());
        AvifConformanceTest.assertPlanesEqual(AvifReference.Planes.of(alone(FULL)),
                AvifReference.Planes.of(decoded.picture()), "full image");
        assertEquals(128, AvifDecoder.decode(MediaSource.of(builder.build())).picture().width());
    }

    @Test
    @DisplayName("decode the thumbnail of Microsoft's Tomsk_with_thumbnails.avif under a hint")
    void tomsk() throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed("avif-Microsoft-Tomsk_with_thumbnails.avif"));
        var full = AvifDecoder.decode(MediaSource.of(data));
        var reduced = AvifDecoder.decode(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(160, 90));
        assertEquals(full.picture().width(), reduced.sourceWidth());
        assertTrue(reduced.picture().width() < full.picture().width(), "a thumbnail is decoded");
        assertTrue(reduced.picture().width() >= 160 && reduced.picture().height() >= 90);
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var thumbnail = file.thumbnailsOf(file.primaryItem().id()).stream()
                .filter(item -> item.property(HeifProperty.SpatialExtent.class).width() == reduced.picture().width())
                .findFirst().orElseThrow();
        var alone = new HeifBuilder();
        var extent = thumbnail.property(HeifProperty.SpatialExtent.class);
        alone.primary(alone.item("av01", file.readItem(source, thumbnail, MediaLimits.DEFAULT),
                plain(HeifBuilder.ispe((int) extent.width(), (int) extent.height())),
                essential(HeifBuilder.av1C(thumbnail.property(HeifProperty.Av1Configuration.class).record()))));
        AvifConformanceTest.assertPlanesEqual(
                AvifReference.Planes.of(AvifDecoder.decode(MediaSource.of(alone.build())).picture()),
                AvifReference.Planes.of(reduced.picture()), "thumbnail");
    }
}
