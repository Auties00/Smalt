package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ProRes builder setters and resets, the combinations {@code build()} rejects, the
 * profile's lookups, rates and chroma format, and the encoder's key frame request.
 */
@DisplayName("ProRes encoder API")
class ProResEncoderApiTest {
    @Nested
    @DisplayName("ProResEncoderOptions.Builder")
    class BuilderSetters {
        @Test
        @DisplayName("bitrate(long) sets the bitrate and automaticBitrate() clears it")
        void bitrate() {
            var base = ProResEncoderOptions.defaults();
            var rated = base.toBuilder().bitrate(90_000_000L).build();
            assertEquals(OptionalLong.of(90_000_000L), rated.bitrate());
            assertEquals(base, rated.toBuilder().automaticBitrate().build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().bitrate(-1L).build());
        }

        @Test
        @DisplayName("quantizationIndex(int) sets the index and automaticQuantizationIndex() clears it")
        void quantizationIndex() {
            var base = ProResEncoderOptions.defaults();
            var fixed = base.toBuilder().quantizationIndex(8).build();
            assertEquals(OptionalInt.of(8), fixed.quantizationIndex());
            assertEquals(base, fixed.toBuilder().automaticQuantizationIndex().build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().quantizationIndex(225).build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().quantizationIndex(0).build());
        }

        @Test
        @DisplayName("build() rejects a bitrate together with a quantization index")
        void bitrateWithQuantizationIndex() {
            var both = ProResEncoderOptions.defaults().toBuilder().bitrate(90_000_000L).quantizationIndex(8);
            assertThrows(IllegalArgumentException.class, both::build);
            assertEquals(OptionalInt.of(8), both.automaticBitrate().build().quantizationIndex());
        }

        @Test
        @DisplayName("setters reject null and build() checks the ranges")
        void setters() {
            var builder = ProResEncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.profile(null));
            assertThrows(NullPointerException.class, () -> builder.color(null));
            builder.sliceSize(3);
            var failure = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("slice size 3 is not 1, 2, 4 or 8", failure.getMessage());
        }
    }

    @Nested
    @DisplayName("ProResProfile")
    class Profile {
        @ParameterizedTest
        @EnumSource(ProResProfile.class)
        @DisplayName("chromaFormat() is 4:4:4 exactly for the flavors that carry alpha")
        void chromaFormat(ProResProfile profile) {
            assertEquals(profile.supportsAlpha() ? ChromaFormat.YUV444 : ChromaFormat.YUV422, profile.chromaFormat());
        }

        @ParameterizedTest
        @CsvSource({"PROXY, 45", "LT, 102", "STANDARD, 147", "HQ, 220", "PRORES_4444, 330", "PRORES_4444_XQ, 500"})
        @DisplayName("referenceBitrate() is Apple's published rate in bits per second")
        void referenceBitrate(ProResProfile profile, long megabits) {
            assertEquals(megabits * 1_000_000L, profile.referenceBitrate());
            assertEquals(profile.referenceBitrate() / (120.0 * 68 * 30000 / 1001), profile.bitsPerMacroblock(), 1e-9);
        }

        @ParameterizedTest
        @EnumSource(ProResProfile.class)
        @DisplayName("ofFourCc and ofConfig find every flavor by its sample entry type")
        void lookup(ProResProfile profile) {
            assertEquals(Optional.of(profile), ProResProfile.ofFourCc(profile.fourCc()));
            assertEquals(Optional.of(profile),
                    ProResProfile.ofConfig(profile.fourCc().getBytes(StandardCharsets.US_ASCII)));
            assertArrayEquals(profile.fourCc().getBytes(StandardCharsets.US_ASCII),
                    new ProResEncoder(16, 16, ProResEncoderOptions.of(profile)).codecConfig().orElseThrow());
        }

        @Test
        @DisplayName("ofFourCc and ofConfig return empty for anything else")
        void unknown() {
            assertEquals(Optional.empty(), ProResProfile.ofFourCc("APCH"));
            assertEquals(Optional.empty(), ProResProfile.ofFourCc("avc1"));
            assertEquals(Optional.empty(), ProResProfile.ofConfig(null));
            assertEquals(Optional.empty(), ProResProfile.ofConfig("apc".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(Optional.empty(), ProResProfile.ofConfig("apchx".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(Optional.empty(), ProResProfile.ofConfig("xxxx".getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Nested
    @DisplayName("ProResEncoder")
    class Encoder {
        @Test
        @DisplayName("requestKeyframe() changes nothing: every packet is a key frame either way")
        void requestKeyframe() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 3);
            var options = ProResEncoderOptions.of(ProResProfile.LT).toBuilder().threads(1).build();
            try (var plain = new ProResEncoder(64, 48, options); var requested = new ProResEncoder(64, 48, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    var frame = new VideoFrame(pictures.get(i), i * 3000L, 3000, false);
                    requested.requestKeyframe();
                    Packet expected = plain.encodeFrame(frame);
                    Packet actual = requested.encodeFrame(frame);
                    assertArrayEquals(expected.data(), actual.data());
                    assertTrue(actual.keyframe());
                }
            }
        }

        @Test
        @DisplayName("frames without timestamps follow the previous one by the nominal duration")
        void timestamps() {
            var picture = Vp9TestVideo.synthesize(64, 48, 1).getFirst();
            var options = ProResEncoderOptions.of(ProResProfile.LT).toBuilder().threads(1).build();
            try (var encoder = new ProResEncoder(64, 48, options)) {
                var first = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, false));
                assertEquals(0, first.pts());
                assertEquals(3000, first.duration());
                assertEquals(3000, encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, false)).pts());
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.encodeFrame(new VideoFrame(picture, 3000, 0, false)));
                assertEquals(7000, encoder.encodeFrame(new VideoFrame(picture, 7000, 10, false)).pts());
            }
        }

        @Test
        @DisplayName("rejects RGB input for an unsupported matrix and keeps its clock")
        void unsupportedMatrix() {
            var yuv = Vp9TestVideo.synthesize(64, 48, 1).getFirst();
            var options = ProResEncoderOptions.of(ProResProfile.LT).toBuilder().threads(1)
                    .color(ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.BT2020_CL)).build();
            try (var encoder = new ProResEncoder(64, 48, options)) {
                var rgb = new VideoFrame(ImageOps.toRgba(yuv), Packet.NO_TIMESTAMP, 0, false);
                assertThrows(MediaException.Unsupported.class, () -> encoder.encodeFrame(rgb));
                assertEquals(0, encoder.encodeFrame(new VideoFrame(yuv, Packet.NO_TIMESTAMP, 0, false)).pts());
            }
        }
    }
}
