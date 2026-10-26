package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the AVIF builder resets, the range checks of {@code build()} and the animation writer
 * factory.
 */
@DisplayName("AVIF encoder API")
class AvifEncoderApiTest {
    private static RgbaPicture gradient(int width, int height, int phase) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = y * picture.stride() + x * 4;
                pixels[i] = (byte) (x * 4 + phase);
                pixels[i + 1] = (byte) (y * 4);
                pixels[i + 2] = (byte) ((x + y) * 2);
                pixels[i + 3] = (byte) 255;
            }
        }
        return picture;
    }

    @Nested
    @DisplayName("AvifEncoderOptions.Builder")
    class BuilderResets {
        @Test
        @DisplayName("noMetadata() clears Exif and XMP like noExif() and noXmp()")
        void noMetadata() {
            var exif = "Exif\0\0II*\0".getBytes(StandardCharsets.ISO_8859_1);
            var xmp = "<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8);
            var tagged = AvifEncoderOptions.builder().exif(exif).xmp(xmp).build();
            var cleared = tagged.toBuilder().noMetadata().build();
            assertEquals(Optional.empty(), cleared.exif());
            assertEquals(Optional.empty(), cleared.xmp());
            assertEquals(tagged.toBuilder().noExif().noXmp().build(), cleared);
            assertEquals(AvifEncoderOptions.defaults(), cleared);
        }

        @Test
        @DisplayName("automaticBitDepth() restores the default and build() rejects a depth of 0")
        void automaticBitDepth() {
            var deep = AvifEncoderOptions.forArchive();
            assertEquals(OptionalInt.of(10), deep.bitDepth());
            var automatic = deep.toBuilder().automaticBitDepth().build();
            assertEquals(OptionalInt.empty(), automatic.bitDepth());
            var failure = assertThrows(IllegalArgumentException.class, () -> deep.toBuilder().bitDepth(0).build());
            assertEquals("bit depth 0 is not 8, 10 or 12", failure.getMessage());
        }

        @Test
        @DisplayName("automaticGridTileSize() restores the default and build() rejects a side of 0")
        void automaticGridTileSize() {
            var gridded = AvifEncoderOptions.builder().gridTileSize(512).build();
            assertEquals(OptionalInt.of(512), gridded.gridTileSize());
            var automatic = gridded.toBuilder().automaticGridTileSize().build();
            assertEquals(OptionalInt.empty(), automatic.gridTileSize());
            assertEquals(AvifEncoderOptions.defaults(), automatic);
            assertThrows(IllegalArgumentException.class, () -> AvifEncoderOptions.builder().gridTileSize(0).build());
            assertThrows(IllegalArgumentException.class, () -> AvifEncoderOptions.builder().gridTileSize(513).build());
        }

        @Test
        @DisplayName("automaticTiles() restores the default and build() rejects a negative logarithm")
        void automaticTiles() {
            var tiled = AvifEncoderOptions.builder().tiles(1, 0).build();
            assertEquals(OptionalInt.of(1), tiled.tileColumnsLog2());
            assertEquals(OptionalInt.of(0), tiled.tileRowsLog2());
            assertEquals(AvifEncoderOptions.defaults(), tiled.toBuilder().automaticTiles().build());
            assertThrows(IllegalArgumentException.class, () -> AvifEncoderOptions.builder().tiles(-1, -1).build());
        }

        @Test
        @DisplayName("setters reject null where none is documented and build() checks the ranges")
        void setters() {
            var builder = AvifEncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.color(null));
            assertThrows(NullPointerException.class, () -> builder.orientation(null));
            assertThrows(NullPointerException.class, () -> builder.limits(null));
            builder.quality(101);
            var failure = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("quality 101 is not in [0, 100]", failure.getMessage());
        }
    }

    @Nested
    @DisplayName("AvifEncoder.openAnimationWriter")
    class AnimationWriterFactory {
        private static byte[] encode(AvifEncoderOptions options) throws Exception {
            var out = new ByteArrayOutputStream();
            var sink = MediaSink.of(out);
            try (var animation = AvifEncoder.openAnimationWriter(64, 48, options, sink)) {
                for (var i = 0; i < 3; i++) {
                    animation.write(gradient(64, 48, i * 8), 40_000);
                }
                animation.finish();
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("writes the same file for every thread count")
        void sameFile() throws Exception {
            var options = AvifEncoderOptions.lossy(50).toBuilder().speed(10).threads(1).build();
            assertArrayEquals(encode(options), encode(options.toBuilder().threads(0).build()));
        }

        @Test
        @DisplayName("rejects an empty canvas and a canvas over the limits")
        void rejects() {
            var sink = MediaSink.of(new ByteArrayOutputStream());
            assertThrows(IllegalArgumentException.class,
                    () -> AvifEncoder.openAnimationWriter(0, 48, AvifEncoderOptions.defaults(), sink));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> AvifEncoder.openAnimationWriter(MediaLimits.DEFAULT.maxDimension() + 1, 48,
                            AvifEncoderOptions.defaults(), sink));
        }
    }

    @Test
    @DisplayName("channel pictures are coded as their RGBA conversion of their own depth")
    void channelPictures() {
        var cmyk = com.github.auties00.smalt.image.Channel8Picture.allocate(24, 16,
                com.github.auties00.smalt.image.PixelLayout.CMYK);
        var deep = com.github.auties00.smalt.image.Channel16Picture.allocate(24, 16,
                com.github.auties00.smalt.image.PixelLayout.CMYKA);
        for (var i = 0; i < cmyk.pixels().length; i++) {
            cmyk.pixels()[i] = (byte) (i * 7);
        }
        for (var i = 0; i < deep.pixels().length; i++) {
            deep.pixels()[i] = (short) (i * 977);
        }
        var options = AvifEncoderOptions.lossless();
        assertArrayEquals(AvifEncoder.encode(com.github.auties00.smalt.image.ops.ImageOps.toRgba(cmyk), options),
                AvifEncoder.encode(cmyk, options));
        assertArrayEquals(AvifEncoder.encode(com.github.auties00.smalt.image.ops.ImageOps.toRgba16(deep), options),
                AvifEncoder.encode(deep, options));
    }
}
