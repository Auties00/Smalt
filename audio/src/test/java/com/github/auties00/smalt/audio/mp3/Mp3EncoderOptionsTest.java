package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3EncoderOptionsTest {
    @Test
    @DisplayName("defaults to 128 kbit/s constant bitrate joint stereo at 44.1 kHz with the reservoir")
    void defaults() {
        var options = Mp3EncoderOptions.defaults();
        assertEquals(44100, options.sampleRate());
        assertEquals(ChannelLayout.STEREO, options.layout());
        assertEquals(Mp3BitrateMode.CBR, options.bitrateMode());
        assertEquals(128_000, options.bitrate());
        assertEquals(Mp3StereoMode.JOINT_STEREO, options.stereoMode());
        assertTrue(options.bitReservoir());
        assertFalse(options.crc());
        assertTrue(options.bandwidth().isEmpty());
        assertEquals(17000, options.effectiveBandwidth());
    }

    @Test
    @DisplayName("presets select the bitrate mode")
    void presets() {
        assertEquals(Mp3BitrateMode.VBR, Mp3EncoderOptions.ofVariableBitrate(0).bitrateMode());
        assertEquals(0, Mp3EncoderOptions.ofVariableBitrate(0).vbrQuality());
        assertEquals(Mp3BitrateMode.ABR, Mp3EncoderOptions.ofAverageBitrate(150_000).bitrateMode());
        assertEquals(320_000, Mp3EncoderOptions.ofConstantBitrate(320_000).bitrate());
        assertFalse(Mp3EncoderOptions.forRealtime().bitReservoir());
        assertEquals(ChannelLayout.MONO, Mp3EncoderOptions.of(16000, ChannelLayout.MONO).layout());
    }

    @Test
    @DisplayName("rejects rates, layouts, bitrates and qualities Layer III cannot code")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.of(96000, ChannelLayout.STEREO));
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.of(44100, ChannelLayout.SURROUND_5_1));
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.ofConstantBitrate(129_000));
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.ofConstantBitrate(8_000));
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.defaults().toBuilder()
                .format(22050, ChannelLayout.STEREO).bitrate(320_000).build());
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.ofVariableBitrate(10));
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.ofAverageBitrate(400_000));
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.defaults().toBuilder().bandwidth(500)
                .build());
        assertEquals(8_000, Mp3EncoderOptions.builder().format(8000, ChannelLayout.MONO).bitrate(8_000).build().bitrate());
    }

    @Test
    @DisplayName("the builder's cbr, vbr and abr keep the input format and select the bitrate mode")
    void builderModes() {
        assertEquals(Mp3EncoderOptions.ofConstantBitrate(192_000), Mp3EncoderOptions.builder().cbr(192_000).build());
        assertEquals(Mp3EncoderOptions.ofVariableBitrate(4), Mp3EncoderOptions.builder().vbr(4).build());
        assertEquals(Mp3EncoderOptions.ofAverageBitrate(150_000), Mp3EncoderOptions.builder().abr(150_000).build());
        var cbr = Mp3EncoderOptions.builder().format(22050, ChannelLayout.MONO).cbr(144_000).build();
        assertEquals(22050, cbr.sampleRate());
        assertEquals(ChannelLayout.MONO, cbr.layout());
        assertEquals(Mp3BitrateMode.CBR, cbr.bitrateMode());
        assertEquals(144_000, cbr.bitrate());
        var vbr = Mp3EncoderOptions.builder().vbr(7).format(16000, ChannelLayout.MONO).build();
        assertEquals(Mp3BitrateMode.VBR, vbr.bitrateMode());
        assertEquals(7, vbr.vbrQuality());
        assertEquals(16000, vbr.sampleRate());
        var abr = Mp3EncoderOptions.builder().format(24000, ChannelLayout.STEREO).abr(100_000).build();
        assertEquals(Mp3BitrateMode.ABR, abr.bitrateMode());
        assertEquals(100_000, abr.bitrate());
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.builder().cbr(0).build());
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.builder().abr(-1).build());
        assertThrows(IllegalArgumentException.class, () -> Mp3EncoderOptions.builder().vbr(10).build());
        assertThrows(IllegalArgumentException.class,
                () -> Mp3EncoderOptions.builder().format(22050, ChannelLayout.STEREO).cbr(320_000).build());
        assertThrows(IllegalArgumentException.class,
                () -> Mp3EncoderOptions.builder().format(8000, ChannelLayout.MONO).abr(200_000).build());
    }

    @Test
    @DisplayName("withers and builders round trip, and equal options are equal")
    void withers() {
        var options = Mp3EncoderOptions.defaults().toBuilder().crc(true).stereoMode(Mp3StereoMode.STEREO).bandwidth(15000)
                .bitReservoir(false).vbrQuality(5).bitrateMode(Mp3BitrateMode.VBR).build();
        assertTrue(options.crc());
        assertEquals(Mp3StereoMode.STEREO, options.stereoMode());
        assertEquals(15000, options.effectiveBandwidth());
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        assertNotEquals(options, options.toBuilder().automaticBandwidth().build());
        assertEquals(16500, options.toBuilder().automaticBandwidth().build().effectiveBandwidth());
        assertTrue(options.toString().contains("VBR"));
    }

    @Test
    @DisplayName("the automatic bandwidth grows with the bitrate per channel and stays below Nyquist")
    void bandwidth() {
        var previous = 0;
        for (var kbps : new int[]{32, 64, 96, 128, 192, 320}) {
            var hertz = Mp3EncoderOptions.ofConstantBitrate(kbps * 1000).effectiveBandwidth();
            assertTrue(hertz >= previous);
            previous = hertz;
        }
        assertTrue(Mp3EncoderOptions.builder().format(8000, ChannelLayout.MONO).bitrate(64_000).build().effectiveBandwidth() <= 3800);
    }
}
