package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VorbisEncoderOptions")
class VorbisEncoderOptionsTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rate low", "rate high", "quality low", "quality high", "quality NaN", "bitrate"})
    @DisplayName("the builder stores values and build() rejects out-of-range ones")
    void rejectsInBuild(String field) {
        var builder = switch (field) {
            case "rate low" -> VorbisEncoderOptions.builder().format(0, ChannelLayout.STEREO);
            case "rate high" -> VorbisEncoderOptions.builder().format(200_001, ChannelLayout.STEREO);
            case "quality low" -> VorbisEncoderOptions.builder().quality(-1.5f);
            case "quality high" -> VorbisEncoderOptions.builder().quality(10.5f);
            case "quality NaN" -> VorbisEncoderOptions.builder().quality(Float.NaN);
            default -> VorbisEncoderOptions.builder().nominalBitrate(0);
        };
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    @DisplayName("quality(q) clears a nominal bitrate set before it")
    void qualityClearsBitrate() {
        var options = VorbisEncoderOptions.builder().nominalBitrate(128_000).quality(5).build();
        assertEquals(OptionalInt.empty(), options.nominalBitrate());
        assertEquals(5, options.quality());
        assertEquals(OptionalInt.of(128_000), VorbisEncoderOptions.ofNominalBitrate(128_000).nominalBitrate());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"average zero", "minimum negative", "maximum zero", "reservoir zero", "minimum above maximum",
            "minimum above average", "maximum below average", "bias negative", "bias above one", "bias NaN",
            "damping zero", "damping infinite", "damping NaN", "average without a setting"})
    @DisplayName("build() rejects inconsistent bitrate management settings")
    void rejectsManagement(String field) {
        var builder = VorbisEncoderOptions.builder();
        switch (field) {
            case "average zero" -> builder.averageBitrate(0);
            case "minimum negative" -> builder.minimumBitrate(-1);
            case "maximum zero" -> builder.maximumBitrate(0);
            case "reservoir zero" -> builder.maximumBitrate(128_000).reservoirBits(0);
            case "minimum above maximum" -> builder.minimumBitrate(160_000).maximumBitrate(128_000);
            case "minimum above average" -> builder.averageBitrate(96_000).minimumBitrate(128_000);
            case "maximum below average" -> builder.averageBitrate(128_000).maximumBitrate(96_000);
            case "bias negative" -> builder.maximumBitrate(128_000).reservoirBias(-0.1);
            case "bias above one" -> builder.maximumBitrate(128_000).reservoirBias(1.5);
            case "bias NaN" -> builder.maximumBitrate(128_000).reservoirBias(Double.NaN);
            case "damping zero" -> builder.averageBitrate(128_000).averageDamping(0);
            case "damping infinite" -> builder.averageBitrate(128_000).averageDamping(Double.POSITIVE_INFINITY);
            case "damping NaN" -> builder.averageBitrate(128_000).averageDamping(Double.NaN);
            default -> builder.format(96_000, ChannelLayout.STEREO).averageBitrate(128_000);
        }
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    @DisplayName("quality, nominal and average bitrates replace each other; limits combine with each")
    void rateControlModes() {
        var average = VorbisEncoderOptions.builder().nominalBitrate(96_000).averageBitrate(128_000).build();
        assertEquals(OptionalInt.empty(), average.nominalBitrate());
        assertEquals(OptionalInt.of(128_000), average.averageBitrate());
        assertTrue(average.isBitrateManaged());
        var nominal = average.toBuilder().nominalBitrate(112_000).build();
        assertEquals(OptionalInt.empty(), nominal.averageBitrate());
        assertFalse(nominal.isBitrateManaged());
        var quality = average.toBuilder().quality(6).build();
        assertEquals(OptionalInt.empty(), quality.averageBitrate());
        assertFalse(quality.isBitrateManaged());
        var limited = quality.toBuilder().minimumBitrate(64_000).maximumBitrate(256_000).build();
        assertTrue(limited.isBitrateManaged());
        assertEquals(6, limited.quality());
        assertEquals(OptionalInt.of(64_000), limited.minimumBitrate());
        assertEquals(OptionalInt.of(256_000), limited.maximumBitrate());
        var cleared = limited.toBuilder().noMinimumBitrate().noMaximumBitrate().build();
        assertEquals(quality, cleared);
        assertFalse(cleared.isBitrateManaged());
    }

    @Test
    @DisplayName("the reservoir settings default to automatic, 0.1 and 1.5 s and survive toBuilder()")
    void reservoirSettings() {
        var defaults = VorbisEncoderOptions.ofAverageBitrate(128_000);
        assertEquals(OptionalInt.empty(), defaults.reservoirBits());
        assertEquals(VorbisEncoderOptions.DEFAULT_RESERVOIR_BIAS, defaults.reservoirBias());
        assertEquals(VorbisEncoderOptions.DEFAULT_AVERAGE_DAMPING, defaults.averageDamping());
        var custom = defaults.toBuilder().reservoirBits(50_000).reservoirBias(0.75).averageDamping(3).build();
        assertEquals(OptionalInt.of(50_000), custom.reservoirBits());
        assertEquals(0.75, custom.reservoirBias());
        assertEquals(3, custom.averageDamping());
        assertEquals(custom, custom.toBuilder().build());
        assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
        assertNotEquals(defaults, custom);
        assertEquals(defaults, custom.toBuilder().automaticReservoirBits().reservoirBias(0.1).averageDamping(1.5).build());
        assertTrue(custom.toString().contains("averageBitrate=128000"), custom.toString());
        assertTrue(custom.toString().contains("reservoirBits=50000"), custom.toString());
    }

    @Test
    @DisplayName("ofBitrateLimits chooses the settings halfway between the limits")
    void bitrateLimits() {
        var options = VorbisEncoderOptions.ofBitrateLimits(96_000, 160_000);
        assertEquals(OptionalInt.of(128_000), options.nominalBitrate());
        assertEquals(OptionalInt.of(96_000), options.minimumBitrate());
        assertEquals(OptionalInt.of(160_000), options.maximumBitrate());
        assertEquals(OptionalInt.empty(), options.averageBitrate());
        assertThrows(IllegalArgumentException.class, () -> VorbisEncoderOptions.ofBitrateLimits(160_000, 96_000));
    }

    @Test
    @DisplayName("the registry rejects a bitrate for a format only the quality settings cover")
    void registryRejectsUncodableBitrate() {
        var quality = AudioEncoderOptions.builder().format(96_000, ChannelLayout.STEREO).build();
        assertEquals(96_000, AudioEncoders.create(Codec.VORBIS, quality).sampleRate());
        var bitrate = AudioEncoderOptions.builder().format(96_000, ChannelLayout.STEREO).bitrate(128_000).build();
        assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.VORBIS, bitrate));
    }
}
