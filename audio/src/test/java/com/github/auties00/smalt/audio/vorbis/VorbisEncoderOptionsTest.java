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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    @DisplayName("the registry rejects a bitrate for a format only the quality settings cover")
    void registryRejectsUncodableBitrate() {
        var quality = AudioEncoderOptions.builder().format(96_000, ChannelLayout.STEREO).build();
        assertEquals(96_000, AudioEncoders.create(Codec.VORBIS, quality).sampleRate());
        var bitrate = AudioEncoderOptions.builder().format(96_000, ChannelLayout.STEREO).bitrate(128_000).build();
        assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.VORBIS, bitrate));
    }
}
