package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Feeds mutated images carrying every kind of metadata to the reader and the editor: each call
 * must either succeed or raise a {@link MediaException} that does not hide a runtime failure, in
 * bounded time.
 */
@DisplayName("Image metadata robustness")
class ImageMetadataRobustnessTest {
    static byte[] rich(byte[] image, boolean jpeg) {
        var exif = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Ada"))
                .with(ExifData.Directory.GPS, 0x0001, new ExifValue.Ascii("N"))
                .with(ExifData.Directory.GPS, 0x0002, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{45, 1, 2},
                        new long[]{1, 1, 1}))
                .withThumbnail(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9})
                .withOrientation(Orientation.ROTATE_90);
        var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Title").with(XmpPacket.PHOTOSHOP, "City", "Milano")
                .with(XmpPacket.EXIF, "GPSLatitude", "45,1N");
        var edit = ImageMetadataEdit.keepAll().toBuilder().exif(exif).xmp(xmp).colorProfile(ColorProfile.displayP3())
                .build();
        if (jpeg) {
            edit = edit.toBuilder().iptc(IptcData.of(List.of(new IptcData.DataSet(2, 90, "Milano".getBytes()),
                    new IptcData.DataSet(2, 25, "kw".getBytes())))).comments(List.of("comment")).build();
        }
        return ImageMetadataEditor.apply(image, edit);
    }

    static Stream<Arguments> images() {
        return Stream.of(
                Arguments.of("jpeg", (Supplier<byte[]>) () -> rich(MetadataSupport.jpeg(), true)),
                Arguments.of("png", (Supplier<byte[]>) () -> rich(MetadataSupport.png(), false)),
                Arguments.of("webp", (Supplier<byte[]>) () -> rich(MetadataSupport.webp(), false)),
                Arguments.of("gif", (Supplier<byte[]>) () -> ImageMetadataEditor.apply(MetadataSupport.gif(),
                        ImageMetadataEdit.keepAll().toBuilder().xmp(XmpPacket.empty()
                                .with(XmpPacket.DC, "title", "Gif"))
                                .comments(List.of("comment")).build())),
                Arguments.of("avif", (Supplier<byte[]>) () -> {
                    var avif = com.github.auties00.smalt.testkit.Fixtures.read(com.github.auties00.smalt.testkit.Fixtures.testImage(
                            "avif", 32, 32, List.of("-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8")));
                    var exif = ExifData.EMPTY.with(ExifData.Directory.GPS, 0x0001, new ExifValue.Ascii("N"));
                    return ImageMetadataEditor.apply(avif, ImageMetadataEdit.keepAll().toBuilder().exif(exif)
                            .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Avif")).build());
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    @DisplayName("mutated images raise nothing but MediaException")
    void mutations(String name, Supplier<byte[]> fixture) {
        var image = fixture.get();
        var random = new Random(name.hashCode());
        var limits = MediaLimits.builder().maxBytes(16 << 20).build();
        var metadataEnd = Math.min(image.length, 8192);
        for (var round = 0; round < 300; round++) {
            var mutated = image.clone();
            var region = random.nextInt(4) == 0 ? mutated.length : metadataEnd;
            for (var flips = 0; flips < 1 + random.nextInt(10); flips++) {
                mutated[random.nextInt(region)] = random.nextInt(3) == 0 ? (byte) 0xFF : (byte) random.nextInt(256);
            }
            if (random.nextInt(5) == 0) {
                mutated = Arrays.copyOf(mutated, 1 + random.nextInt(mutated.length - 1));
            }
            var input = mutated;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                attempt(() -> ImageMetadata.read(MediaSource.of(input), limits));
                attempt(() -> ImageMetadataEditor.apply(input, ImageMetadataEdit.stripAll().toBuilder().limits(limits)
                        .build()));
                attempt(() -> ImageMetadataEditor.apply(input, ImageMetadataEdit.stripLocation().toBuilder()
                        .limits(limits).build()));
                attempt(() -> ImageMetadataEditor.apply(input, ImageMetadataEdit.keepAll().toBuilder().limits(limits)
                        .orientation(Orientation.ROTATE_180).comments(List.of("x")).build()));
            });
        }
    }

    /** Runs an operation that may reject its input, failing on a runtime failure hidden behind the guard. */
    static void attempt(Runnable operation) {
        try {
            operation.run();
        } catch (MediaException expected) {
            if (expected.getCause() instanceof RuntimeException cause && !(cause instanceof MediaException)) {
                throw new AssertionError("an unexpected runtime exception reached the entry point", cause);
            }
        }
    }
}
