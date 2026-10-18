package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sound.sampled.AudioFormat;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SampledAudio")
class SampledAudioTest {
    static Stream<Arguments> formats() {
        var result = Stream.<Arguments>builder();
        for (var bigEndian : new boolean[]{false, true}) {
            for (var bits : new int[]{8, 16, 24, 32}) {
                result.add(Arguments.of(AudioFormat.Encoding.PCM_SIGNED, bits, bigEndian));
                result.add(Arguments.of(AudioFormat.Encoding.PCM_UNSIGNED, bits, bigEndian));
            }
            result.add(Arguments.of(AudioFormat.Encoding.PCM_FLOAT, 32, bigEndian));
            result.add(Arguments.of(AudioFormat.Encoding.PCM_FLOAT, 64, bigEndian));
        }
        return result.build();
    }

    @ParameterizedTest(name = "{0} {1}-bit big-endian={2}")
    @MethodSource("formats")
    @DisplayName("bytes -> samples -> bytes is lossless")
    void bytesRoundTrip(AudioFormat.Encoding encoding, int bits, boolean bigEndian) {
        var format = SampledAudio.format(encoding, 44_100, bits, ChannelLayout.STEREO, bigEndian);
        var frames = 257;
        var bytes = new byte[frames * SampledAudio.frameSize(format)];
        new Random(bits * 31L + (bigEndian ? 1 : 0)).nextBytes(bytes);
        if (AudioFormat.Encoding.PCM_FLOAT.equals(encoding) || bits == 32) {
            // random bit patterns include NaNs, and 32-bit integers carry more bits than a float
            var buffer = new AudioBuffer(ChannelLayout.STEREO, 44_100, frames);
            var random = new Random(7);
            for (var plane : buffer.planes()) {
                for (var i = 0; i < frames; i++) {
                    plane[i] = random.nextFloat() * 2 - 1;
                }
            }
            buffer.setFrames(frames);
            SampledAudio.write(buffer, format, bytes, 0);
        }
        var buffer = SampledAudio.read(bytes, 0, bytes.length, format);
        assertEquals(frames, buffer.frames());
        var back = new byte[bytes.length];
        assertEquals(bytes.length, SampledAudio.write(buffer, format, back, 0));
        assertArrayEquals(bytes, back);
    }

    @Test
    @DisplayName("16-bit samples scale by 2^15 and round, clamping out-of-range and NaN samples")
    void quantization() {
        var format = SampledAudio.pcm16(48_000, ChannelLayout.MONO);
        float[] samples = {0.5f, -1.0f, 1.0f, 2.0f, -3.0f, Float.NaN, 1.4f / 32768, -0.6f / 32768};
        var bytes = new byte[samples.length * 2];
        SampledAudio.write(new float[][]{samples}, 0, samples.length, format, bytes, 0);
        short[] expected = {16384, -32768, 32767, 32767, -32768, 0, 1, -1};
        for (var i = 0; i < expected.length; i++) {
            assertEquals(expected[i], (short) ((bytes[2 * i] & 0xFF) | bytes[2 * i + 1] << 8), "sample " + i);
        }
    }

    @Test
    @DisplayName("an 8-bit unsigned silence byte is 128")
    void unsignedSilence() {
        var format = SampledAudio.format(AudioFormat.Encoding.PCM_UNSIGNED, 8_000, 8, ChannelLayout.MONO, false);
        var bytes = new byte[1];
        SampledAudio.write(new float[][]{{0}}, 0, 1, format, bytes, 0);
        assertEquals((byte) 128, bytes[0]);
    }

    @Test
    @DisplayName("compressed and odd-sized formats are not supported")
    void unsupported() {
        assertFalse(SampledAudio.isSupported(new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false)));
        assertFalse(SampledAudio.isSupported(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 8000, 12, 1, 2, 8000, false)));
        assertFalse(SampledAudio.isSupported(new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 8000, 16, 1, 2, 8000, false)));
        assertTrue(SampledAudio.isSupported(new AudioFormat(44_100, 16, 2, true, false)));
        assertThrows(IllegalArgumentException.class,
                () -> SampledAudio.write(new float[1][4], 0, 4, SampledAudio.pcm16(8000, ChannelLayout.STEREO), new byte[16], 0));
    }
}
