package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the JPEG 2000 preset, setters and resets named after their meaning, the color space code
 * lookup, and the channel encoder overloads.
 */
@DisplayName("JPEG 2000 options")
class Jp2OptionsTest {
    static RgbaPicture picture() {
        var picture = RgbaPicture.allocate(19, 11, false);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte) (i * 3);
            pixels[i + 1] = (byte) (i >> 1);
            pixels[i + 2] = (byte) (255 - i);
            pixels[i + 3] = (byte) 0xFF;
        }
        return picture;
    }

    @Nested
    @DisplayName("Jp2EncoderOptions")
    class Encoder {
        @Test
        @DisplayName("ofPsnr(db) targets one lossy layer")
        void ofPsnr() {
            assertEquals(Jp2EncoderOptions.builder().reversible(false).psnrTargets(38).build(),
                    Jp2EncoderOptions.ofPsnr(38));
            assertArrayEquals(new double[]{38}, Jp2EncoderOptions.ofPsnr(38).psnrTargets());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.ofPsnr(0));
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.ofPsnr(Double.POSITIVE_INFINITY));
        }

        @Test
        @DisplayName("psnrTargets replaces compression ratios")
        void psnrTargets() {
            var options = Jp2EncoderOptions.lossy(20).toBuilder().psnrTargets(30, 40).build();
            assertArrayEquals(new double[]{30, 40}, options.psnrTargets());
            assertEquals(0, options.compressionRatios().length);
            assertEquals(Jp2EncoderOptions.lossy(20).toBuilder().psnrTargets(30, 40).build(), options);
            assertEquals(0, Jp2EncoderOptions.lossy(20).psnrTargets().length);
        }

        @Test
        @DisplayName("noTiles() codes one tile and tileSize(0, 0) is rejected")
        void noTiles() {
            var options = Jp2EncoderOptions.builder().tileSize(64, 64).noTiles().build();
            assertEquals(0, options.tileWidth());
            assertEquals(0, options.tileHeight());
            assertEquals(Jp2EncoderOptions.defaults(), options);
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().tileSize(0, 0).build());
        }

        @Test
        @DisplayName("maximalPrecincts() resets the precinct size and precinctSize(0, 0) is rejected")
        void maximalPrecincts() {
            var options = Jp2EncoderOptions.builder().precinctSize(64, 64).maximalPrecincts().build();
            assertEquals(0, options.precinctWidth());
            assertEquals(Jp2EncoderOptions.defaults(), options);
            assertThrows(IllegalArgumentException.class,
                    () -> Jp2EncoderOptions.builder().precinctSize(0, 0).build());
        }
    }

    @Nested
    @DisplayName("Jp2DecoderOptions and Jp2ColorSpace")
    class DecoderAndColorSpace {
        @Test
        @DisplayName("allQualityLayers() decodes every layer and qualityLayers(0) is rejected")
        void allQualityLayers() {
            var options = Jp2DecoderOptions.builder().qualityLayers(2).allQualityLayers().build();
            assertTrue(options.qualityLayers().isEmpty());
            assertEquals(Jp2DecoderOptions.defaults(), options);
            assertEquals(2, Jp2DecoderOptions.builder().qualityLayers(2).build().qualityLayers().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> Jp2DecoderOptions.builder().qualityLayers(0).build());
        }

        @ParameterizedTest
        @EnumSource(Jp2ColorSpace.class)
        @DisplayName("of(code()) round-trips every enumerated color space")
        void codes(Jp2ColorSpace space) {
            if (space == Jp2ColorSpace.UNSPECIFIED || space == Jp2ColorSpace.ICC) {
                assertEquals(-1, space.code());
            }
            if (space.code() >= 0) {
                assertEquals(space, Jp2ColorSpace.of(space.code()));
            }
        }

        @Test
        @DisplayName("of maps an unknown code to UNSPECIFIED")
        void unknown() {
            assertEquals(Jp2ColorSpace.UNSPECIFIED, Jp2ColorSpace.of(9_999));
        }
    }

    @Nested
    @DisplayName("Jp2Encoder channel overloads")
    class ChannelOverloads {
        @Test
        @DisplayName("encode(image) and encode(image, options, sink) write what encode(image, options) returns")
        void overloads() {
            var image = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(picture())));
            assertArrayEquals(Jp2Encoder.encode(image, Jp2EncoderOptions.defaults()), Jp2Encoder.encode(image));
            var options = Jp2EncoderOptions.lossy(8);
            var sink = MediaSink.memory();
            Jp2Encoder.encode(image, options, sink);
            assertArrayEquals(Jp2Encoder.encode(image, options), sink.toByteArray());
        }
    }
}
