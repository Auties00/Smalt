package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the names added next to the existing audio API: the combined format setters and the
 * automatic resets of the encoder options builders, and {@link AudioBuffer#copy()}.
 */
@DisplayName("Audio API additions")
class AudioApiAdditionsTest {
    @Nested
    @DisplayName("AudioEncoderOptions.Builder")
    class EncoderOptions {
        @Test
        @DisplayName("format sets the rate and layout together")
        void format() {
            var options = AudioEncoderOptions.builder().format(16_000, ChannelLayout.MONO).build();
            assertEquals(AudioEncoderOptions.of(16_000, ChannelLayout.MONO), options);
            assertThrows(NullPointerException.class, () -> AudioEncoderOptions.builder().format(16_000, null));
            assertThrows(IllegalArgumentException.class,
                    () -> AudioEncoderOptions.builder().format(0, ChannelLayout.MONO).build());
        }

        @Test
        @DisplayName("automaticBitrate and automaticBitsPerSample restore the codec defaults")
        void automatic() {
            var explicit = AudioEncoderOptions.builder().bitrate(96_000).bitsPerSample(24).build();
            var automatic = explicit.toBuilder().automaticBitrate().automaticBitsPerSample().build();
            assertEquals(OptionalInt.empty(), automatic.bitrate());
            assertEquals(OptionalInt.empty(), automatic.bitsPerSample());
            assertEquals(AudioEncoderOptions.defaults(), automatic);
        }
    }

    @Nested
    @DisplayName("PcmEncoderOptions.Builder")
    class PcmOptions {
        @Test
        @DisplayName("format sets the rate and layout together")
        void format() {
            var options = PcmEncoderOptions.builder().codec(Codec.PCM_S24LE).format(44_100, ChannelLayout.MONO).build();
            assertEquals(PcmEncoderOptions.of(Codec.PCM_S24LE, 44_100, ChannelLayout.MONO), options);
            assertThrows(NullPointerException.class, () -> PcmEncoderOptions.builder().format(44_100, null));
            assertThrows(IllegalArgumentException.class,
                    () -> PcmEncoderOptions.builder().format(-1, ChannelLayout.MONO).build());
        }
    }

    @Nested
    @DisplayName("AudioBuffer.copy")
    class Copy {
        private static AudioBuffer ramp() {
            var buffer = new AudioBuffer(ChannelLayout.STEREO, 48_000, 64);
            for (var channel = 0; channel < 2; channel++) {
                for (var i = 0; i < 64; i++) {
                    buffer.plane(channel)[i] = channel * 100 + i;
                }
            }
            buffer.setFrames(40);
            return buffer;
        }

        @Test
        @DisplayName("copies the valid samples into tight planes")
        void copy() {
            var buffer = ramp();
            var copy = buffer.copy();
            assertEquals(buffer.layout(), copy.layout());
            assertEquals(buffer.sampleRate(), copy.sampleRate());
            assertEquals(40, copy.frames());
            assertEquals(40, copy.capacity());
            for (var channel = 0; channel < 2; channel++) {
                assertNotSame(buffer.plane(channel), copy.plane(channel));
                assertArrayEquals(java.util.Arrays.copyOf(buffer.plane(channel), 40), copy.plane(channel));
            }
            buffer.plane(0)[0] = -1;
            assertEquals(0, copy.plane(0)[0]);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 39, 40})
        @DisplayName("copies a prefix of the valid samples")
        void prefix(int frames) {
            var copy = ramp().copy(frames);
            assertEquals(frames, copy.frames());
            assertEquals(frames, copy.capacity());
            if (frames > 0) {
                assertEquals(100 + frames - 1, copy.plane(1)[frames - 1]);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 41, 64})
        @DisplayName("rejects a prefix outside the valid samples")
        void outOfRange(int frames) {
            assertThrows(IllegalArgumentException.class, () -> ramp().copy(frames));
        }

        @Test
        @DisplayName("plane rejects a channel outside the layout")
        void plane() {
            assertThrows(IndexOutOfBoundsException.class, () -> ramp().plane(2));
        }
    }
}
