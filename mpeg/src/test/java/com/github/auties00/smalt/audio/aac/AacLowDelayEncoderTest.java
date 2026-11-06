package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the AAC-LD and AAC-ELD encoder: the error resilient configuration, the delay and the waveform through
 * this module's decoder, the conformance of the bitstream against ffmpeg's decoder (the same samples within the
 * 16-bit criterion of ISO/IEC 14496-26), the bitrate and the contract.
 */
class AacLowDelayEncoderTest {
    @TempDir
    Path directory;

    static AacEncoderOptions options(AacProfile profile, int rate, ChannelLayout layout, int bitrate, int frameLength) {
        return AacEncoderOptions.builder().format(rate, layout).bitrate(bitrate).profile(profile)
                .frameLength(frameLength).build();
    }

    @ParameterizedTest(name = "{0} {1} Hz {2} channels, {3}")
    @CsvSource({"LD, 48000, 2, 1024, B99100", "LD, 44100, 1, 960, BA0D00", "ELD, 48000, 2, 1024, F8E64000",
            "ELD, 32000, 1, 960, F8EA3000"})
    @DisplayName("writes the error resilient AudioSpecificConfig")
    void config(AacProfile profile, int rate, int channels, int frameLength, String expected) {
        var layout = channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
        var config = new AacEncoder(options(profile, rate, layout, 64000, frameLength)).codecConfig().orElseThrow();
        assertEquals(expected, HexFormat.of().withUpperCase().formatHex(config));
        var parsed = AudioSpecificConfig.parse(config);
        assertEquals(profile == AacProfile.LD ? AudioSpecificConfig.AOT_ER_LD : AudioSpecificConfig.AOT_ER_ELD,
                parsed.objectType());
        assertEquals(rate, parsed.sampleRate());
        assertEquals(channels, parsed.channelConfiguration());
        assertEquals(frameLength / 2, parsed.frameLength());
        assertEquals(0, parsed.resilience());
        assertEquals(0, parsed.epConfig());
        assertEquals(false, parsed.sbr());
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {
        @ParameterizedTest(name = "{0} {1} Hz, {2} channels at {3} bps, {4}")
        @CsvSource({"LD, 48000, 1, 64000, 1024", "LD, 48000, 2, 128000, 960", "ELD, 48000, 2, 128000, 1024",
                "ELD, 44100, 1, 64000, 960", "LD, 32000, 2, 96000, 1024", "ELD, 24000, 1, 32000, 1024",
                "ELD, 48000, 6, 320000, 1024", "LD, 16000, 1, 24000, 960"})
        @DisplayName("this decoder reproduces the input delay() samples late")
        void delay(AacProfile profile, int rate, int channels, int bitrate, int frameLength) {
            var layout = switch (channels) {
                case 1 -> ChannelLayout.MONO;
                case 2 -> ChannelLayout.STEREO;
                default -> ChannelLayout.SURROUND_5_1;
            };
            var options = options(profile, rate, layout, bitrate, frameLength);
            var input = HeAacEncoderTest.music(rate, channels, 2);
            var encoded = HeAacEncoderTest.encode(options, input);
            var samples = frameLength / 2;
            assertEquals(samples, encoded.encoder().frameSize());
            var delay = encoded.encoder().delay();
            assertEquals(profile == AacProfile.LD ? samples : 3 * samples / 4, delay);
            var decoded = HeAacEncoderTest.decode(encoded, rate);
            assertEquals(rate, decoded.sampleRate());
            assertEquals(layout, decoded.layout());
            assertEquals(encoded.packets().size() * (long) samples, decoded.frames());
            assertEquals(delay, HeAacEncoderTest.lag(input[0], decoded.planes()[0], delay));
            for (var c = 0; c < channels; c++) {
                if (layout.speakers().get(c) == Speaker.LOW_FREQUENCY) {
                    // the LFE channel is coded below 240 Hz only
                    continue;
                }
                var shifted = Arrays.copyOfRange(decoded.planes()[c], delay, decoded.planes()[c].length);
                var snr = TestSignals.segmentalSnr(new float[][]{input[c]}, new float[][]{shifted});
                assertTrue(snr > 8, "channel " + c + " segmental SNR " + snr);
            }
            var covered = encoded.packets().size() * (long) samples - delay;
            assertTrue(covered >= input[0].length && covered < input[0].length + samples, "covered " + covered);
        }

        @ParameterizedTest(name = "{0} at {1} bps")
        @CsvSource({"LD, 64000", "ELD, 96000", "LD, 160000", "ELD, 48000"})
        @DisplayName("constant bitrate meets the bitrate exactly over the stream")
        void constantBitrate(AacProfile profile, int bitrate) {
            var options = options(profile, 48000, ChannelLayout.STEREO, bitrate, 1024);
            var encoded = HeAacEncoderTest.encode(options, HeAacEncoderTest.music(48000, 2, 4));
            var seconds = encoded.packets().size() * 512.0 / 48000;
            assertEquals(bitrate, encoded.bytes() * 8 / seconds, bitrate * 0.02);
            for (var packet : encoded.packets()) {
                assertEquals(512, packet.duration());
            }
        }

        @Test
        @DisplayName("silence decodes to silence")
        void silence() {
            for (var profile : new AacProfile[]{AacProfile.LD, AacProfile.ELD}) {
                var encoded = HeAacEncoderTest.encode(options(profile, 48000, ChannelLayout.STEREO, 96000, 1024),
                        new float[2][24000]);
                for (var plane : HeAacEncoderTest.decode(encoded, 48000).planes()) {
                    for (var value : plane) {
                        assertEquals(0, value, 1e-6);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} Hz, {2} channels, {3}")
    @CsvSource({"LD, 48000, 2, 1024", "LD, 44100, 1, 960", "ELD, 48000, 2, 1024", "ELD, 32000, 1, 960",
            "ELD, 48000, 6, 1024"})
    @DisplayName("ffmpeg decodes the M4A file cleanly and as this decoder does, within the 16-bit criterion")
    void ffmpeg(AacProfile profile, int rate, int channels, int frameLength) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var layout = switch (channels) {
            case 1 -> ChannelLayout.MONO;
            case 2 -> ChannelLayout.STEREO;
            default -> ChannelLayout.SURROUND_5_1;
        };
        var options = options(profile, rate, layout, 48000 * channels, frameLength);
        var encoded = HeAacEncoderTest.encode(options, HeAacEncoderTest.music(rate, channels, 2));
        var test = new HeAacEncoderTest();
        test.directory = directory;
        var file = test.mp4("ld-" + profile + "-" + rate + "-" + channels, encoded, options);
        Oracle.assertDecodesCleanly(file);
        if (profile == AacProfile.LD && frameLength == 960) {
            // ffmpeg's 480-sample AAC-LD output is off the conformance reference waveforms (see
            // AacErrorResilientConformanceTest), against which this decoder is checked
            return;
        }
        var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), channels);
        var mine = HeAacEncoderTest.decode(encoded, rate);
        for (var c = 0; c < channels; c++) {
            var length = Math.min(reference[c].length, mine.planes()[c].length);
            assertTrue(length >= mine.planes()[c].length - frameLength, "ffmpeg decodes every frame");
            var error = HeAacConformanceTest.difference(Arrays.copyOf(reference[c], length),
                    Arrays.copyOf(mine.planes()[c], length));
            assertTrue(error[0] <= AacConformanceTest.RMS_LIMIT, "channel " + c + ": RMS " + error[0]);
            assertTrue(error[1] <= AacConformanceTest.PEAK_LIMIT, "channel " + c + ": peak " + error[1]);
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("rejects layouts outside channel configurations 1 to 7 and bitrates beyond the frame limit")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> options(AacProfile.LD, 48000,
                    ChannelLayout.SURROUND_7_1, 256000, 1024));
            assertThrows(IllegalArgumentException.class, () -> options(AacProfile.ELD, 48000,
                    ChannelLayout.STEREO, 2 * 6144 * 48000 / 512 + 1, 1024));
            assertThrows(IllegalArgumentException.class, () -> options(AacProfile.ELD, 48000,
                    ChannelLayout.STEREO, 64000, 512));
        }

        @Test
        @DisplayName("the codec registry codes real-time AAC as AAC-ELD where the layout allows it")
        void realtime() {
            try (var encoder = AudioEncoders.create(Codec.AAC, AudioEncoderOptions.forRealtime(48000,
                    ChannelLayout.STEREO))) {
                assertEquals(AacProfile.ELD, ((AacEncoder) encoder).options().profile());
                assertEquals(512, encoder.frameSize());
                assertEquals(384, encoder.delay());
            }
            try (var encoder = AudioEncoders.create(Codec.AAC, AudioEncoderOptions.forRealtime(48000,
                    ChannelLayout.SURROUND_7_1))) {
                assertEquals(AacProfile.LC, ((AacEncoder) encoder).options().profile());
            }
            try (var encoder = AudioEncoders.create(Codec.AAC, AudioEncoderOptions.defaults().toBuilder()
                    .format(48000, ChannelLayout.STEREO).build())) {
                assertEquals(AacProfile.LC, ((AacEncoder) encoder).options().profile());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AacProfile.class, names = {"LD", "ELD"})
        @DisplayName("ADTS cannot carry the error resilient object types")
        void adts(AacProfile profile) {
            var options = options(profile, 48000, ChannelLayout.STEREO, 96000, 1024);
            var config = new AacEncoder(options).codecConfig().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> new AdtsMuxer(MediaSink.memory()).addTrack(
                    AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                            .layout(ChannelLayout.STEREO).codecConfig(config).build()));
        }
    }
}
