package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @DisplayName("Jp2EncoderOptions codestream settings")
    class CodestreamSettings {
        static Jp2EncoderOptions everything() {
            return Jp2EncoderOptions.builder().imageOrigin(100, 50).tileOrigin(90, 40).tileSize(64, 32)
                    .precinctSizes(new int[]{128, 64}, new int[]{64, 32})
                    .progressionChanges(List.of(new Jp2ProgressionChange(0, 0, 1, 3, 3, Jp2ProgressionOrder.CPRL)))
                    .tilePartDivision(Jp2TilePartDivision.LAYER).tlmMarkers(true).pltMarkers(true)
                    .regionOfInterest(new Jp2RegionOfInterest.Rectangle(1, 2, 3, 4)).guardBits(4)
                    .maxCodestreamBytes(100_000).maxComponentBytes(40_000)
                    .exif(ExifData.EMPTY).xmp(XmpPacket.empty()).iptc(IptcData.of(List.of())).build();
        }

        @Test
        @DisplayName("toBuilder() keeps every setting, and every setting takes part in equals")
        void roundTrip() {
            var options = everything();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertEquals(100, options.imageOriginX());
            assertEquals(40, options.tileOriginY());
            assertArrayEquals(new int[]{128, 64}, options.precinctWidths());
            assertEquals(128, options.precinctWidth());
            assertEquals(Optional.of(Jp2TilePartDivision.LAYER), options.tilePartDivision());
            assertEquals(4, options.guardBits());
            assertFalse(options.isLossless());
            for (var changed : List.of(options.toBuilder().imageOrigin(101, 50), options.toBuilder().tileOrigin(0, 40),
                    options.toBuilder().precinctSize(128, 64), options.toBuilder().progressionChanges(List.of()),
                    options.toBuilder().noTilePartDivision(), options.toBuilder().tlmMarkers(false),
                    options.toBuilder().pltMarkers(false), options.toBuilder().noRegionOfInterest(),
                    options.toBuilder().guardBits(2), options.toBuilder().noMaxCodestreamBytes(),
                    options.toBuilder().noMaxComponentBytes(), options.toBuilder().noExif(), options.toBuilder().noXmp(),
                    options.toBuilder().noIptc())) {
                assertNotEquals(options, changed.build());
            }
            var reset = options.toBuilder().imageOrigin(0, 0).tileOrigin(0, 0).noTiles().maximalPrecincts()
                    .progressionChanges(List.of()).noTilePartDivision().tlmMarkers(false).pltMarkers(false)
                    .noRegionOfInterest().guardBits(2).noMaxCodestreamBytes().noMaxComponentBytes().noExif().noXmp()
                    .noIptc().build();
            assertEquals(Jp2EncoderOptions.defaults(), reset);
        }

        @Test
        @DisplayName("build() rejects settings a codestream cannot carry")
        void rejected() {
            var builder = Jp2EncoderOptions.builder();
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().imageOrigin(-1, 0).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().imageOrigin(0xFFFF_FFFFL, 0)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().tileOrigin(0, -1).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().guardBits(8).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().maxComponentBytes(-1)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().maxCodestreamBytes(0)
                    .build());
            assertEquals(OptionalLong.empty(), Jp2EncoderOptions.builder().maxCodestreamBytes(0).noMaxCodestreamBytes()
                    .build().maxCodestreamBytes());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder()
                    .precinctSizes(new int[]{64, 32}, new int[]{64}).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder()
                    .precinctSizes(new int[]{64, 3}, new int[]{64, 4}).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder()
                    .progressionChanges(Collections.nCopies(256, new Jp2ProgressionChange(0, 0, 1, 1, 1,
                            Jp2ProgressionOrder.LRCP))).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.builder().highThroughput(true)
                    .regionOfInterest(new Jp2RegionOfInterest.Component(0, 1)).build());
            assertThrows(NullPointerException.class, () -> builder.regionOfInterest(null));
            assertThrows(NullPointerException.class, () -> builder.tilePartDivision(null));
            assertThrows(IllegalArgumentException.class, () -> new Jp2ProgressionChange(2, 0, 1, 2, 1,
                    Jp2ProgressionOrder.LRCP));
            assertThrows(IllegalArgumentException.class, () -> new Jp2ProgressionChange(0, 0, 0, 1, 1,
                    Jp2ProgressionOrder.LRCP));
            assertThrows(IllegalArgumentException.class, () -> new Jp2ProgressionChange(0, 4, 1, 1, 4,
                    Jp2ProgressionOrder.LRCP));
            assertThrows(IllegalArgumentException.class, () -> new Jp2RegionOfInterest.Component(0, 38));
            assertThrows(IllegalArgumentException.class, () -> new Jp2RegionOfInterest.Component(16384, 1));
            assertThrows(IllegalArgumentException.class, () -> new Jp2RegionOfInterest.Rectangle(0, 0, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new Jp2RegionOfInterest.Rectangle(-1, 0, 1, 1));
        }

        @Test
        @DisplayName("the profile presets meet their profile, and settings that break it are rejected")
        void profiles() {
            var cinema = Jp2EncoderOptions.forCinema2k(24);
            assertEquals(Optional.of(Jp2Profile.CINEMA_2K), cinema.profile());
            assertEquals(OptionalLong.of(1302083), cinema.maxCodestreamBytes());
            assertEquals(OptionalLong.of(1041666), cinema.maxComponentBytes());
            assertEquals(OptionalLong.of(651041), Jp2EncoderOptions.forCinema2k(48).maxCodestreamBytes());
            assertEquals(1, cinema.guardBits());
            assertEquals(2, Jp2EncoderOptions.forCinema4k().guardBits());
            assertEquals(2, Jp2EncoderOptions.forCinema4k().progressionChanges().size());
            assertTrue(Jp2EncoderOptions.forImf(Jp2Profile.IMF_8K_REVERSIBLE, 0, 0, 0).isLossless());
            assertEquals(4, Jp2EncoderOptions.forImf(Jp2Profile.IMF_8K_REVERSIBLE, 0, 0, 0).decompositionLevels());
            assertEquals(5, Jp2EncoderOptions.forImf(Jp2Profile.IMF_8K, 0, 0, 0).decompositionLevels());
            assertEquals(OptionalLong.of(51_200_000_000L / 8 / 60), Jp2EncoderOptions.forImf(Jp2Profile.IMF_8K, 11,
                    9, 60).maxCodestreamBytes());
            assertEquals(OptionalLong.empty(), Jp2EncoderOptions.forImf(Jp2Profile.IMF_8K, 11, 9, 0)
                    .maxCodestreamBytes());
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.forCinema2k(30));
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.forImf(Jp2Profile.CINEMA_4K, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K, 2, 2, 0));
            assertThrows(IllegalArgumentException.class, () -> Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K, 12, 0, 0));
            for (var broken : List.of(cinema.toBuilder().guardBits(2), cinema.toBuilder().reversible(true),
                    cinema.toBuilder().codeBlockSize(64, 64), cinema.toBuilder().tlmMarkers(false),
                    cinema.toBuilder().tileSize(512, 512), cinema.toBuilder().compressionRatios(10, 5),
                    cinema.toBuilder().precinctSize(256, 256), cinema.toBuilder().imageOrigin(1, 0),
                    cinema.toBuilder().noMaxCodestreamBytes(), cinema.toBuilder().progressionOrder(Jp2ProgressionOrder.LRCP),
                    Jp2EncoderOptions.forCinema4k().toBuilder().progressionChanges(List.of()),
                    Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K, 0, 0, 0).toBuilder().reversible(true),
                    Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K, 0, 0, 0).toBuilder().decompositionLevels(6)
                            .precinctSizes(new int[]{256, 256, 256, 256, 256, 256},
                                    new int[]{256, 256, 256, 256, 256, 256}))) {
                assertThrows(IllegalArgumentException.class, broken::build);
            }
            assertEquals(Jp2EncoderOptions.defaults(), cinema.toBuilder().noProfile().reversible(true)
                    .compressionRatios(0).decompositionLevels(5).codeBlockSize(64, 64).maximalPrecincts()
                    .progressionOrder(Jp2ProgressionOrder.LRCP).guardBits(2).noTilePartDivision().tlmMarkers(false)
                    .noMaxCodestreamBytes().noMaxComponentBytes().build());
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
