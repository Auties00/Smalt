package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("FlacEncoderOptions")
class FlacEncoderOptionsTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("ofCompressionLevel(level) equals a builder set to that level")
    void ofCompressionLevel(int level) {
        assertEquals(FlacEncoderOptions.builder().compressionLevel(level).build(), FlacEncoderOptions.ofCompressionLevel(level));
    }

    @Test
    @DisplayName("ofCompressionLevel rejects levels outside 0 to 8")
    void ofCompressionLevelRange() {
        assertThrows(IllegalArgumentException.class, () -> FlacEncoderOptions.ofCompressionLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> FlacEncoderOptions.ofCompressionLevel(9));
    }

    @Test
    @DisplayName("Builder.automaticBlockSize() returns to the block size that follows the predictor order")
    void automaticBlockSize() {
        var fixed = FlacEncoderOptions.defaults().toBuilder().blockSize(2048).build();
        assertEquals(2048, fixed.blockSize());
        var automatic = fixed.toBuilder().automaticBlockSize().build();
        assertEquals(4096, automatic.blockSize());
        assertEquals(FlacEncoderOptions.defaults(), automatic);
        assertEquals(1152, fixed.toBuilder().maxLpcOrder(0).automaticBlockSize().build().blockSize());
    }

    @Test
    @DisplayName("Builder.noExpectedTotalSamples() forgets the expected stream length")
    void noExpectedTotalSamples() {
        var known = FlacEncoderOptions.builder().expectedTotalSamples(44_100).build();
        assertEquals(OptionalLong.of(44_100), known.expectedTotalSamples());
        var unknown = known.toBuilder().noExpectedTotalSamples().build();
        assertEquals(OptionalLong.empty(), unknown.expectedTotalSamples());
        assertEquals(FlacEncoderOptions.defaults(), unknown);
    }

    @Test
    @DisplayName("the builder stores values and build() checks the ranges")
    void validationInBuild() {
        var builder = FlacEncoderOptions.builder().blockSize(8).maxLpcOrder(40).qlpPrecision(30).padding(-1)
                .partitionOrders(4, 2).bitsPerSample(40).expectedTotalSamples(-5);
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> FlacEncoderOptions.builder().blockSize(8).build());
        assertThrows(IllegalArgumentException.class, () -> FlacEncoderOptions.builder().partitionOrders(4, 2).build());
        assertThrows(IllegalArgumentException.class, () -> FlacEncoderOptions.builder().apodizations(List.of()).build());
        assertThrows(IllegalArgumentException.class,
                () -> FlacEncoderOptions.builder().seekPointInterval(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                () -> FlacEncoderOptions.builder().format(44_100, ChannelLayout.defaultFor(9)).build());
        assertThrows(IllegalArgumentException.class, () -> FlacEncoderOptions.builder().compressionLevel(9));
    }
}
