package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.FrameDecoder;
import com.github.auties00.smalt.audio.ac3.internal.FrameHeader;
import com.github.auties00.smalt.audio.ac3.internal.Ac3Kernels;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the metadata the AC-3 and E-AC-3 encoders write: every field of {@link Ac3Metadata} read
 * back from the frames with Smalt's header parser, the default frames, the validation of the
 * builders, and the dynamic range words through Smalt's decoder in line and RF mode; ffmpeg must
 * decode every stream cleanly. {@code Eac3MediaFoundationTest} checks the downmix levels and the
 * line mode words against Dolby's decoder.
 */
class Ac3MetadataTest {
    private static final ChannelLayout SURROUND = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));

    @TempDir
    Path directory;

    private static Ac3Metadata full() {
        return Ac3Metadata.builder()
                .serviceType(Ac3ServiceType.COMMENTARY)
                .centerMixLevel(Ac3MixLevel.MINUS_6_DB)
                .surroundMixLevel(Ac3MixLevel.SILENT)
                .surroundMode(Ac3Indication.ENCODED)
                .preferredDownmix(Ac3PreferredDownmix.LO_RO)
                .ltrtCenterMixLevel(Ac3MixLevel.MINUS_3_DB)
                .ltrtSurroundMixLevel(Ac3MixLevel.MINUS_4_5_DB)
                .loroCenterMixLevel(Ac3MixLevel.ZERO_DB)
                .loroSurroundMixLevel(Ac3MixLevel.MINUS_1_5_DB)
                .surroundExMode(Ac3SurroundExMode.SURROUND_EX)
                .headphoneMode(Ac3Indication.NOT_ENCODED)
                .converterType(Ac3ConverterType.HDCD)
                .mixingLevel(105)
                .roomType(Ac3RoomType.SMALL)
                .copyright(true)
                .original(false)
                .lfeMixLevel(-3)
                .programScale(-6)
                .externalProgramScale(4)
                .doubleRateSource(true)
                .build();
    }

    private static List<FrameHeader> headers(byte[] stream) {
        var headers = new ArrayList<FrameHeader>();
        var position = 0;
        while (position + 8 <= stream.length) {
            var header = new FrameHeader();
            assertTrue(header.parse(new MsbBitReader(stream, position, stream.length - position), stream.length - position),
                    "frame at byte " + position);
            headers.add(header);
            position += header.frameBytes;
        }
        return headers;
    }

    private static byte[] ac3(Ac3EncoderOptions options, AudioBuffer input) {
        return Ac3EncoderTest.concatenate(Ac3EncoderTest.encode(options, input));
    }

    private static byte[] eac3(Eac3EncoderOptions options, AudioBuffer input) {
        return Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, input));
    }

    private Path write(String name, byte[] data) {
        try {
            return Files.write(directory.resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("AC-3 frames")
    class Ac3Frames {
        @Test
        @DisplayName("keep the A/52 syntax with the default levels and flags under the default metadata")
        void defaults() {
            var input = Ac3EncoderTest.signal(SURROUND, 48000, 0.5);
            var stream = ac3(Ac3EncoderOptions.of(48000, SURROUND), input);
            for (var header : headers(stream)) {
                assertEquals(8, header.bsid);
                assertEquals(0, header.bsmod);
                assertEquals(1, header.centerMixLevel);
                assertEquals(1, header.surroundMixLevel);
                assertEquals(0, header.copyright);
                assertEquals(1, header.original);
                assertEquals(-1, header.mixingLevel);
                assertEquals(-1, header.compression[0]);
                assertEquals(31, header.dialogueNormalization[0]);
            }
            var explicit = Ac3EncoderOptions.of(48000, SURROUND).toBuilder().metadata(Ac3Metadata.builder().build()).build();
            assertEquals(Ac3EncoderOptions.of(48000, SURROUND), explicit);
        }

        @Test
        @DisplayName("write the alternate syntax of Annex D with every field of the metadata")
        void alternateSyntax() {
            var input = Ac3EncoderTest.signal(SURROUND, 48000, 0.5);
            var options = Ac3EncoderOptions.of(48000, SURROUND).toBuilder().dialogueLevel(-24).metadata(full()).build();
            var stream = ac3(options, input);
            for (var header : headers(stream)) {
                assertEquals(6, header.bsid);
                assertEquals(5, header.bsmod);
                assertEquals(2, header.centerMixLevel);
                assertEquals(2, header.surroundMixLevel);
                assertEquals(24, header.dialogueNormalization[0]);
                assertEquals(2, header.downmixMode);
                assertEquals(4, header.ltrtCenterMixLevel);
                assertEquals(5, header.ltrtSurroundMixLevel);
                assertEquals(2, header.loroCenterMixLevel);
                assertEquals(3, header.loroSurroundMixLevel);
                assertEquals(2, header.surroundExMode);
                assertEquals(1, header.headphoneMode);
                assertEquals(1, header.converterType);
                assertEquals(25, header.mixingLevel);
                assertEquals(2, header.roomType);
                assertEquals(1, header.copyright);
                assertEquals(0, header.original);
            }
            assertTrue(Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).frames() > 0);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("annex-d.ac3", stream));
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"MINUS_3_DB, MINUS_3_DB, 0, 0", "MINUS_4_5_DB, MINUS_6_DB, 1, 1", "MINUS_6_DB, SILENT, 2, 2"})
        @DisplayName("code the center and surround mix levels")
        void mixLevels(Ac3MixLevel center, Ac3MixLevel surround, int centerCode, int surroundCode) {
            var input = Ac3EncoderTest.signal(SURROUND, 48000, 0.2);
            var metadata = Ac3Metadata.builder().centerMixLevel(center).surroundMixLevel(surround).build();
            var stream = ac3(Ac3EncoderOptions.of(48000, SURROUND).toBuilder().metadata(metadata).build(), input);
            var header = headers(stream).getFirst();
            assertEquals(8, header.bsid);
            assertEquals(centerCode, header.centerMixLevel);
            assertEquals(surroundCode, header.surroundMixLevel);
        }

        @Test
        @DisplayName("write the Dolby Surround mode of a 2/0 program")
        void surroundMode() {
            var input = Ac3EncoderTest.signal(ChannelLayout.STEREO, 48000, 0.2);
            var metadata = Ac3Metadata.builder().surroundMode(Ac3Indication.ENCODED).build();
            var stream = ac3(Ac3EncoderOptions.defaults().toBuilder().metadata(metadata).build(), input);
            assertEquals(2, headers(stream).getFirst().surroundMode);
        }
    }

    @Nested
    @DisplayName("E-AC-3 frames")
    class Eac3Frames {
        @Test
        @DisplayName("carry no mixing or informational metadata under the default metadata")
        void defaults() {
            var input = Ac3EncoderTest.signal(SURROUND, 48000, 0.5);
            for (var header : headers(eac3(Eac3EncoderOptions.of(48000, SURROUND), input))) {
                assertFalse(header.mixingMetadata);
                assertFalse(header.informationalMetadata);
                assertEquals(-1, header.compression[0]);
            }
        }

        @Test
        @DisplayName("write every field of the mixing and informational metadata of a 3/2 program")
        void surround() {
            var input = Ac3EncoderTest.signal(SURROUND, 48000, 0.5);
            var options = Eac3EncoderOptions.of(48000, SURROUND).toBuilder().metadata(full()).build();
            var stream = eac3(options, input);
            for (var header : headers(stream)) {
                assertTrue(header.mixingMetadata);
                assertTrue(header.informationalMetadata);
                assertEquals(5, header.bsmod);
                assertEquals(2, header.downmixMode);
                assertEquals(4, header.ltrtCenterMixLevel);
                assertEquals(5, header.ltrtSurroundMixLevel);
                assertEquals(2, header.loroCenterMixLevel);
                assertEquals(3, header.loroSurroundMixLevel);
                assertEquals(13, header.lfeMixLevel);
                assertEquals(45, header.programScale);
                assertEquals(55, header.externalProgramScale);
                assertEquals(1, header.copyright);
                assertEquals(0, header.original);
                assertEquals(2, header.surroundExMode);
                assertEquals(25, header.mixingLevel);
                assertEquals(2, header.roomType);
                assertEquals(1, header.converterType);
                assertEquals(1, header.sourceRate);
            }
            assertTrue(Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).frames() > 0);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("metadata.ec3", stream));
            }
        }

        @Test
        @DisplayName("write the Dolby Surround and Dolby Headphone modes of a 2/0 program, and take the legacy levels")
        void stereo() {
            var input = Ac3EncoderTest.signal(ChannelLayout.STEREO, 48000, 0.3);
            var metadata = Ac3Metadata.builder().surroundMode(Ac3Indication.NOT_ENCODED)
                    .headphoneMode(Ac3Indication.ENCODED).build();
            var header = headers(eac3(Eac3EncoderOptions.defaults().toBuilder().metadata(metadata).build(), input))
                    .getFirst();
            assertFalse(header.mixingMetadata);
            assertTrue(header.informationalMetadata);
            assertEquals(1, header.surroundMode);
            assertEquals(2, header.headphoneMode);
            var levels = Ac3Metadata.builder().centerMixLevel(Ac3MixLevel.MINUS_3_DB)
                    .surroundMixLevel(Ac3MixLevel.SILENT).build();
            var surround = headers(eac3(Eac3EncoderOptions.of(48000, SURROUND).toBuilder().metadata(levels).build(),
                    Ac3EncoderTest.signal(SURROUND, 48000, 0.2))).getFirst();
            assertTrue(surround.mixingMetadata);
            assertEquals(4, surround.ltrtCenterMixLevel);
            assertEquals(4, surround.loroCenterMixLevel);
            assertEquals(7, surround.ltrtSurroundMixLevel);
            assertEquals(7, surround.loroSurroundMixLevel);
        }

        @Test
        @DisplayName("leave the metadata out of the dependent substream of a 7.1 program")
        void dependent() {
            var input = Ac3EncoderTest.signal(ChannelLayout.SURROUND_7_1, 48000, 0.3);
            var options = Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_7_1).toBuilder().metadata(full()).build();
            var headers = headers(eac3(options, input));
            for (var header : headers) {
                assertEquals(header.streamType == 0, header.mixingMetadata);
                assertEquals(header.streamType == 0, header.informationalMetadata);
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("default to a complete main service without dynamic range control and round-trip through toBuilder")
        void defaults() {
            var metadata = Ac3Metadata.defaults();
            assertEquals(Ac3ServiceType.COMPLETE_MAIN, metadata.serviceType());
            assertEquals(Ac3MixLevel.MINUS_4_5_DB, metadata.centerMixLevel());
            assertEquals(Ac3MixLevel.MINUS_6_DB, metadata.surroundMixLevel());
            assertTrue(metadata.ltrtCenterMixLevel().isEmpty());
            assertTrue(metadata.mixingLevel().isEmpty());
            assertTrue(metadata.lfeMixLevel().isEmpty());
            assertTrue(metadata.programScale().isEmpty());
            assertTrue(metadata.original());
            assertFalse(metadata.copyright());
            assertEquals(Ac3DynamicRangeProfile.NONE, metadata.lineModeProfile());
            assertEquals(Ac3DynamicRangeProfile.NONE, metadata.rfModeProfile());
            assertEquals(full(), full().toBuilder().build());
            assertEquals(full().hashCode(), full().toBuilder().build().hashCode());
            assertNotEquals(metadata, full());
            var cleared = full().toBuilder().noLtrtCenterMixLevel().noLtrtSurroundMixLevel().noLoroCenterMixLevel()
                    .noLoroSurroundMixLevel().noMixingLevel().roomType(Ac3RoomType.NOT_INDICATED).noLfeMixLevel()
                    .noProgramScale().noExternalProgramScale().build();
            assertTrue(cleared.ltrtCenterMixLevel().isEmpty());
            assertTrue(cleared.loroSurroundMixLevel().isEmpty());
            assertTrue(cleared.mixingLevel().isEmpty());
            assertTrue(cleared.programScale().isEmpty());
            assertEquals(full(), Eac3EncoderOptions.defaults().toBuilder().metadata(full()).build().metadata());
            assertEquals(Ac3Metadata.defaults(), Eac3EncoderOptions.defaults().metadata());
        }

        @Test
        @DisplayName("reject the levels and values the frames cannot carry")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().centerMixLevel(Ac3MixLevel.ZERO_DB).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().surroundMixLevel(Ac3MixLevel.MINUS_4_5_DB).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().loroSurroundMixLevel(Ac3MixLevel.ZERO_DB).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().ltrtSurroundMixLevel(Ac3MixLevel.PLUS_3_DB).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().mixingLevel(79).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().mixingLevel(112).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().roomType(Ac3RoomType.LARGE).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().lfeMixLevel(11).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().lfeMixLevel(-22).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().programScale(-51).build());
            assertThrows(IllegalArgumentException.class, () -> Ac3Metadata.builder().externalProgramScale(13).build());
            assertThrows(NullPointerException.class, () -> Ac3Metadata.builder().serviceType(null));
            var voiceOver = Ac3Metadata.builder().serviceType(Ac3ServiceType.VOICE_OVER).build();
            assertThrows(IllegalArgumentException.class, () -> Ac3EncoderOptions.defaults().toBuilder().metadata(voiceOver).build());
            assertEquals(voiceOver, Ac3EncoderOptions.of(48000, ChannelLayout.MONO).toBuilder().metadata(voiceOver).build()
                    .metadata());
            var karaoke = Ac3Metadata.builder().serviceType(Ac3ServiceType.KARAOKE).build();
            assertThrows(IllegalArgumentException.class, () -> Eac3EncoderOptions.of(48000, ChannelLayout.MONO).toBuilder()
                    .metadata(karaoke).build());
            assertEquals(7, Ac3ServiceType.KARAOKE.code());
            assertEquals(7, Ac3ServiceType.VOICE_OVER.code());
            assertEquals(0, Ac3MixLevel.SILENT.gain());
            assertEquals(Math.sqrt(0.5), Ac3MixLevel.MINUS_3_DB.gain(), 1e-3);
        }
    }

    @Nested
    @DisplayName("dynamic range control")
    class DynamicRange {
        /**
         * Returns a stereo sine of 1 kHz at an amplitude, identical in both channels.
         */
        private static AudioBuffer sine(double amplitude, double seconds) {
            var frames = (int) (48000 * seconds);
            var planes = new float[2][frames];
            for (var i = 0; i < frames; i++) {
                planes[0][i] = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
                planes[1][i] = planes[0][i];
            }
            return new AudioBuffer(ChannelLayout.STEREO, 48000, planes, frames);
        }

        private static double rms(float[] samples, int from, int to) {
            var sum = 0.0;
            for (var i = from; i < to; i++) {
                sum += (double) samples[i] * samples[i];
            }
            return Math.sqrt(sum / (to - from));
        }

        @ParameterizedTest(name = "{0}, amplitude {1}, dialogue level {2}")
        @CsvSource({"FILM_STANDARD, 0.9, -31", "FILM_STANDARD, 0.001, -31", "FILM_LIGHT, 0.1, -31",
                "MUSIC_STANDARD, 0.0005, -31", "MUSIC_LIGHT, 0.5, -31", "SPEECH, 0.003, -31", "FILM_STANDARD, 0.2, -20"})
        @DisplayName("write line mode words that bring a steady sine to the gain of the profile")
        void lineMode(Ac3DynamicRangeProfile profile, double amplitude, int dialogueLevel) {
            var metadata = Ac3Metadata.builder().lineModeProfile(profile).build();
            var input = sine(amplitude, 4);
            for (var enhanced : new boolean[]{false, true}) {
                var stream = enhanced
                        ? eac3(Eac3EncoderOptions.defaults().toBuilder().dialogueLevel(dialogueLevel).metadata(metadata).build(), input)
                        : ac3(Ac3EncoderOptions.defaults().toBuilder().dialogueLevel(dialogueLevel).metadata(metadata).build(), input);
                var light = Ac3TestSupport.decode(stream, Ac3DecoderOptions.defaults()).planes().getFirst();
                var full = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).planes().getFirst();
                // the last second, where the release has settled
                var gain = 20 * Math.log10(rms(light, 2 * 48000, 3 * 48000) / rms(full, 2 * 48000, 3 * 48000));
                // two identical channels, power summed, relative to a full-scale sine
                var level = 20 * Math.log10(amplitude) + 10 * Math.log10(2) - 31 - dialogueLevel;
                var expected = Math.max(-24.08, profile.gain(level));
                System.out.printf("%s %s amplitude %s: gain %.2f dB, profile %.2f dB%n", enhanced ? "E-AC-3" : "AC-3",
                        profile, amplitude, gain, expected);
                assertEquals(expected, gain, 0.35);
            }
        }

        @Test
        @DisplayName("write RF mode words that keep a decoder adding 11 dB below full scale")
        void rfMode() {
            var metadata = Ac3Metadata.builder().rfModeProfile(Ac3DynamicRangeProfile.FILM_STANDARD).build();
            var input = Ac3EncoderTest.stereoMusic(48000, 4);
            var stream = eac3(Eac3EncoderOptions.defaults().toBuilder().metadata(metadata).build(), input);
            for (var header : headers(stream)) {
                assertTrue(header.compression[0] >= 0, "compr in every frame");
            }
            var heavy = Ac3TestSupport.decode(stream, Ac3DecoderOptions.defaults().toBuilder()
                    .compression(Ac3Compression.HEAVY).build()).interleaved();
            var peak = 0.0;
            for (var sample : heavy) {
                peak = Math.max(peak, Math.abs(sample));
            }
            var rfPeak = peak * Math.pow(10, 11 / 20.0);
            System.out.printf("RF mode peak %.3f%n", rfPeak);
            assertTrue(rfPeak <= 1.0 && rfPeak > 0.3, "peak in RF mode " + rfPeak);
            var quiet = eac3(Eac3EncoderOptions.defaults().toBuilder().metadata(metadata).build(), sine(0.001, 2));
            var boosted = Ac3TestSupport.decode(quiet, Ac3DecoderOptions.defaults().toBuilder()
                    .compression(Ac3Compression.HEAVY).build()).planes().getFirst();
            var plain = Ac3TestSupport.decode(quiet, Ac3DecoderOptions.fullRange()).planes().getFirst();
            var gain = 20 * Math.log10(rms(boosted, 48000, 2 * 48000 - 4000) / rms(plain, 48000, 2 * 48000 - 4000));
            assertEquals(6, gain, 0.4, "the boost of a quiet sine");
        }

        @Test
        @DisplayName("write the same words into every substream of a 7.1 program")
        void substreams() {
            var metadata = Ac3Metadata.builder().lineModeProfile(Ac3DynamicRangeProfile.MUSIC_STANDARD)
                    .rfModeProfile(Ac3DynamicRangeProfile.SPEECH).build();
            var input = Ac3EncoderTest.signal(ChannelLayout.SURROUND_7_1, 48000, 0.5);
            var stream = eac3(Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_7_1).toBuilder().metadata(metadata).build(),
                    input);
            var position = 0;
            int[] previous = null;
            int previousCompr = -2;
            while (position + 8 <= stream.length) {
                var header = new FrameHeader();
                header.parse(new MsbBitReader(stream, position, stream.length - position), stream.length - position);
                var decoder = new FrameDecoder(Ac3Kernels.scalar());
                assertTrue(decoder.parse(stream, position, header.frameBytes, false, true));
                var words = new int[header.blocks];
                for (var b = 0; b < header.blocks; b++) {
                    words[b] = decoder.dynamicRange(b, 0);
                }
                if (header.streamType == 1) {
                    assertEquals(java.util.Arrays.toString(previous), java.util.Arrays.toString(words));
                    assertEquals(previousCompr, header.compression[0]);
                }
                previous = words;
                previousCompr = header.compression[0];
                position += header.frameBytes;
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ac3DynamicRangeProfile.class)
    @DisplayName("profiles have a null band at the dialogue level and never cut quiet or boost loud passages")
    void profiles(Ac3DynamicRangeProfile profile) {
        assertEquals(0, profile.gain(-31), 1e-9);
        for (var level = -100.0; level < 20; level += 0.5) {
            var gain = profile.gain(level);
            if (level < -31) {
                assertTrue(gain >= 0 && gain <= profile.maximumBoost(), level + ": " + gain);
            } else {
                assertTrue(gain <= 0, level + ": " + gain);
            }
            assertTrue(profile.gain(level + 0.5) <= gain + 1e-9, "monotonic at " + level);
        }
        if (profile != Ac3DynamicRangeProfile.NONE) {
            assertEquals(profile.maximumBoost(), profile.gain(profile.boostStart() - 5), 1e-9);
        }
    }
}
