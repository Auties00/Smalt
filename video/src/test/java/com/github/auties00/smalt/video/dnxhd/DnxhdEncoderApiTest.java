package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
 * Covers the DNxHD builder setters and resets, the combinations {@code build()} rejects, and the
 * encoder's key frame request.
 */
@DisplayName("DNxHD encoder API")
class DnxhdEncoderApiTest {
    @Nested
    @DisplayName("DnxhdEncoderOptions.Builder")
    class BuilderSetters {
        @Test
        @DisplayName("compressionId(int) sets the ID and automaticCompressionId() clears it")
        void compressionId() {
            var base = DnxhdEncoderOptions.of(DnxhdProfile.DNXHD);
            var forced = base.toBuilder().compressionId(1238).build();
            assertEquals(OptionalInt.of(1238), forced.compressionId());
            assertEquals(base, forced.toBuilder().automaticCompressionId().build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().compressionId(1270).build());
        }

        @Test
        @DisplayName("bitDepth(int) sets the depth and automaticBitDepth() clears it")
        void bitDepth() {
            var base = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX);
            var deep = base.toBuilder().bitDepth(12).build();
            assertEquals(OptionalInt.of(12), deep.bitDepth());
            assertEquals(base, deep.toBuilder().automaticBitDepth().build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().bitDepth(9).build());
        }

        @Test
        @DisplayName("chromaFormat(ChromaFormat) sets the layout, automaticChromaFormat() selecting the ID's own")
        void chromaFormat() {
            var base = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ);
            var subsampled = base.toBuilder().chromaFormat(ChromaFormat.YUV420).build();
            assertEquals(Optional.of(ChromaFormat.YUV420), subsampled.chromaFormat());
            assertEquals(base, subsampled.toBuilder().automaticChromaFormat().build());
            assertThrows(IllegalArgumentException.class,
                    () -> base.toBuilder().chromaFormat(ChromaFormat.MONOCHROME).build());
        }

        @Test
        @DisplayName("bitrate(long) sets the bitrate and automaticBitrate() clears it")
        void bitrate() {
            var base = DnxhdEncoderOptions.of(DnxhdProfile.DNXHD);
            var rated = base.toBuilder().bitrate(120_000_000L).build();
            assertEquals(OptionalLong.of(120_000_000L), rated.bitrate());
            assertEquals(base, rated.toBuilder().automaticBitrate().build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().bitrate(0L).build());
        }

        @ParameterizedTest
        @EnumSource(value = DnxhdProfile.class, names = "DNXHD", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("build() rejects a compression ID or a bitrate with a DNxHR family")
        void dnxhrRejectsCompressionIdAndBitrate(DnxhdProfile profile) {
            var base = DnxhdEncoderOptions.of(profile);
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().compressionId(1238).build());
            assertThrows(IllegalArgumentException.class, () -> base.toBuilder().bitrate(120_000_000L).build());
            assertEquals(base, base.toBuilder().compressionId(1238).bitrate(1L).profile(DnxhdProfile.DNXHD)
                    .automaticCompressionId().automaticBitrate().profile(profile).build());
        }

        @Test
        @DisplayName("setters reject null where none is documented and build() checks the ranges")
        void setters() {
            var builder = DnxhdEncoderOptions.builder();
            assertThrows(NullPointerException.class, () -> builder.profile(null));
            assertThrows(NullPointerException.class, () -> builder.fieldOrder(null));
            builder.threads(-2);
            var failure = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("thread count -2 is negative", failure.getMessage());
        }
    }

    @Nested
    @DisplayName("DnxhdEncoder")
    class Encoder {
        @Test
        @DisplayName("requestKeyframe() changes nothing: every packet is a key frame either way")
        void requestKeyframe() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 3);
            var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB).toBuilder().threads(1).build();
            try (var plain = new DnxhdEncoder(64, 48, options); var requested = new DnxhdEncoder(64, 48, options)) {
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

        @ParameterizedTest
        @EnumSource(DnxhdProfile.class)
        @DisplayName("codecConfig() is the profile's sample entry type")
        void codecConfig(DnxhdProfile profile) {
            var size = profile == DnxhdProfile.DNXHD ? new int[]{1280, 720} : new int[]{64, 48};
            try (var encoder = new DnxhdEncoder(size[0], size[1], DnxhdEncoderOptions.of(profile))) {
                assertArrayEquals(profile.fourCc().getBytes(StandardCharsets.US_ASCII),
                        encoder.codecConfig().orElseThrow());
            }
        }
    }
}
