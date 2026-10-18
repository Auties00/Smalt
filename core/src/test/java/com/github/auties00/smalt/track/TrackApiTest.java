package com.github.auties00.smalt.track;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Codec#pcmBitsPerSample()}, {@link Track#durationMicros()} with
 * {@link Track#UNKNOWN_DURATION}, and {@link Rational#timeBaseOf(long)}.
 */
@DisplayName("Track model additions")
class TrackApiTest {
    @Nested
    @DisplayName("Codec.pcmBitsPerSample")
    class PcmBits {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "PCM_U8, 8", "PCM_S8, 8", "PCM_ALAW, 8", "PCM_MULAW, 8",
                "PCM_S16LE, 16", "PCM_S16BE, 16", "PCM_S24LE, 24", "PCM_S24BE, 24",
                "PCM_S32LE, 32", "PCM_S32BE, 32", "PCM_F32LE, 32", "PCM_F32BE, 32",
                "PCM_F64LE, 64", "PCM_F64BE, 64"})
        @DisplayName("gives the sample width of every PCM codec")
        void pcm(Codec codec, int bits) {
            assertEquals(OptionalInt.of(bits), codec.pcmBitsPerSample());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Codec.class)
        @DisplayName("is present exactly for the PCM codecs")
        void onlyPcm(Codec codec) {
            assertEquals(codec.isPcm(), codec.pcmBitsPerSample().isPresent());
        }
    }

    @Nested
    @DisplayName("Track.durationMicros")
    class DurationMicros {
        private static AudioTrack track(long duration) {
            return AudioTrack.builder()
                    .id(1)
                    .codec(Codec.PCM_S16LE)
                    .timeBase(Rational.timeBaseOf(48_000))
                    .duration(duration)
                    .sampleRate(48_000)
                    .layout(ChannelLayout.STEREO)
                    .build();
        }

        @Test
        @DisplayName("converts a known duration to microseconds")
        void known() {
            assertEquals(OptionalLong.of(1_500_000), track(72_000).durationMicros());
        }

        @Test
        @DisplayName("is empty for UNKNOWN_DURATION")
        void unknown() {
            var track = track(Track.UNKNOWN_DURATION);
            assertEquals(Track.UNKNOWN_DURATION, track.duration());
            assertEquals(OptionalLong.empty(), track.durationMicros());
        }

        @Test
        @DisplayName("is empty when the conversion overflows")
        void overflow() {
            var track = VideoTrack.builder()
                    .id(1)
                    .codec(Codec.H264)
                    .timeBase(new Rational(1_000_000, 1))
                    .duration(Long.MAX_VALUE / 2)
                    .width(16)
                    .height(16)
                    .build();
            assertEquals(OptionalLong.empty(), track.durationMicros());
        }
    }

    @Nested
    @DisplayName("Packet duration")
    class PacketDuration {
        @Test
        @DisplayName("shares one unknown value with Track")
        void shared() {
            assertEquals(-1, Packet.UNKNOWN_DURATION);
            assertEquals(Packet.UNKNOWN_DURATION, Track.UNKNOWN_DURATION);
        }

        @Test
        @DisplayName("accepts zero, positive and unknown durations and rejects other negative ones")
        void validates() {
            var data = new byte[1];
            assertEquals(0, new Packet(1, data, 0, 0, 0, true).duration());
            assertEquals(Packet.UNKNOWN_DURATION, new Packet(1, data, 0, 0, Packet.UNKNOWN_DURATION, true).duration());
            assertEquals(Packet.UNKNOWN_DURATION, new Packet(1, data, 0, 0, 5, true)
                    .withTiming(0, 0, Packet.UNKNOWN_DURATION).duration());
            assertThrows(IllegalArgumentException.class, () -> new Packet(1, data, 0, 0, -2, true));
        }
    }

    @Nested
    @DisplayName("Rational.timeBaseOf")
    class TimeBaseOf {
        @Test
        @DisplayName("returns one over the rate")
        void inverse() {
            assertEquals(new Rational(1, 44_100), Rational.timeBaseOf(44_100));
            assertEquals(new Rational(1, 90_000), Rational.timeBaseOf(90_000));
            assertTrue(Rational.timeBaseOf(1000).equals(Rational.MILLISECONDS));
        }

        @Test
        @DisplayName("rejects a rate that is not positive")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> Rational.timeBaseOf(0));
            assertThrows(IllegalArgumentException.class, () -> Rational.timeBaseOf(-48_000));
        }
    }
}
