package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the OpusHead parser and writer: every mapping family, the Vorbis-to-WAVE channel order,
 * and rejection of malformed headers.
 */
class OpusHeadTest {
    private static byte[] head(int channels, int preSkip, int gain, int family, int streams, int coupled, int... mapping) {
        var table = family == 0 ? 0 : 2 + mapping.length;
        var out = new byte[19 + table];
        System.arraycopy("OpusHead".getBytes(), 0, out, 0, 8);
        out[8] = 1;
        out[9] = (byte) channels;
        out[10] = (byte) preSkip;
        out[11] = (byte) (preSkip >> 8);
        out[12] = (byte) 0x80;
        out[13] = (byte) 0xBB;
        out[16] = (byte) gain;
        out[17] = (byte) (gain >> 8);
        out[18] = (byte) family;
        if (family != 0) {
            out[19] = (byte) streams;
            out[20] = (byte) coupled;
            for (var i = 0; i < mapping.length; i++) {
                out[21 + i] = (byte) mapping[i];
            }
        }
        return out;
    }

    @Test
    @DisplayName("parses a stereo family 0 header and writes it back byte for byte")
    void family0() {
        var bytes = head(2, 312, -256, 0, 0, 0);
        var parsed = OpusHead.parse(bytes, MediaLimits.DEFAULT);
        assertEquals(2, parsed.channels());
        assertEquals(312, parsed.preSkip());
        assertEquals(-256, parsed.outputGain());
        assertEquals(48000, parsed.inputSampleRate());
        assertEquals(1, parsed.streamCount());
        assertEquals(1, parsed.coupledCount());
        assertEquals(ChannelLayout.STEREO, parsed.layout());
        assertArrayEquals(bytes, parsed.toBytes());
    }

    @Test
    @DisplayName("maps family 1 5.1 from Vorbis order to WAVE order")
    void family1() {
        var parsed = OpusHead.parse(head(6, 312, 0, 1, 4, 2, 0, 4, 1, 2, 3, 5), MediaLimits.DEFAULT);
        assertEquals(ChannelLayout.SURROUND_5_1, parsed.layout());
        var expected = new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT,
                Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.LOW_FREQUENCY};
        for (var i = 0; i < 6; i++) {
            assertEquals(expected[i], parsed.layout().speakers().get(parsed.wavePosition(i)), "Vorbis channel " + i);
        }
    }

    @ParameterizedTest(name = "{0} channels")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("maps every family 1 layout to distinct WAVE positions")
    void family1Permutations(int channels) {
        var mapping = new int[channels];
        for (var i = 0; i < channels; i++) {
            mapping[i] = i;
        }
        var parsed = OpusHead.parse(head(channels, 0, 0, 1, channels, 0, mapping), MediaLimits.DEFAULT);
        var seen = new boolean[channels];
        for (var i = 0; i < channels; i++) {
            var position = parsed.wavePosition(i);
            assertFalse(seen[position]);
            seen[position] = true;
        }
    }

    @Test
    @DisplayName("decodes family 255 as unknown speakers in coded order, with silent channels")
    void family255() {
        var parsed = OpusHead.parse(head(3, 0, 0, 255, 2, 0, 1, 255, 0), MediaLimits.DEFAULT);
        assertEquals(3, parsed.layout().channels());
        assertTrue(parsed.layout().speakers().stream().allMatch(speaker -> speaker == Speaker.UNKNOWN));
        assertEquals(2, parsed.wavePosition(2));
        assertEquals(255, parsed.mapping()[1]);
    }

    @Test
    @DisplayName("owns its mapping table: neither the constructor's array nor the accessor's copy aliases it")
    void mappingIsCopied() {
        var mapping = new int[]{0, 1};
        var head = new OpusHead(1, 2, 0, 48000, 0, 1, 1, 1, mapping);
        mapping[0] = 5;
        head.mapping()[1] = 7;
        assertArrayEquals(new int[]{0, 1}, head.mapping());
    }

    @Test
    @DisplayName("builds a family 0 header that keeps the output gain and round-trips through the parser")
    void family0WithGain() {
        var head = OpusHead.family0(1, 312, 44100, -256);
        var parsed = OpusHead.parse(head.toBytes(), MediaLimits.DEFAULT);
        assertEquals(-256, parsed.outputGain());
        assertEquals(312, parsed.preSkip());
        assertEquals(44100, parsed.inputSampleRate());
        assertArrayEquals(new int[]{0}, parsed.mapping());
    }

    @Test
    @DisplayName("rejects the projection family 3 as unsupported")
    void family3() {
        var error = assertThrows(MediaException.Unsupported.class, () -> OpusHead.parse(head(4, 0, 0, 3, 2, 2, 0, 1, 2, 3),
                MediaLimits.DEFAULT));
        assertTrue(error.format().contains("family 3"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"short", "magic", "version", "zero channels", "family 0 with 3 channels",
            "family 1 with 9 channels", "zero streams", "coupled above streams", "mapping out of range", "truncated table"})
    @DisplayName("rejects malformed headers")
    void malformed(String kind) {
        byte[] bytes = switch (kind) {
            case "short" -> new byte[18];
            case "magic" -> {
                var b = head(1, 0, 0, 0, 0, 0);
                b[0] = 'X';
                yield b;
            }
            case "version" -> {
                var b = head(1, 0, 0, 0, 0, 0);
                b[8] = 16;
                yield b;
            }
            case "zero channels" -> head(0, 0, 0, 0, 0, 0);
            case "family 0 with 3 channels" -> head(3, 0, 0, 0, 0, 0);
            case "family 1 with 9 channels" -> head(9, 0, 0, 1, 9, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8);
            case "zero streams" -> head(2, 0, 0, 1, 0, 0, 0, 1);
            case "coupled above streams" -> head(2, 0, 0, 1, 1, 2, 0, 1);
            case "mapping out of range" -> head(2, 0, 0, 1, 1, 0, 0, 1);
            default -> {
                var b = head(2, 0, 0, 1, 1, 1, 0, 1);
                yield java.util.Arrays.copyOf(b, b.length - 1);
            }
        };
        assertThrows(MediaException.class, () -> OpusHead.parse(bytes, MediaLimits.DEFAULT));
    }

    @Test
    @DisplayName("either parses or throws a MediaException for random mutations")
    void mutations() {
        var random = new Random(3);
        var base = head(6, 312, 0, 1, 4, 2, 0, 4, 1, 2, 3, 5);
        for (var n = 0; n < 5000; n++) {
            var bytes = java.util.Arrays.copyOf(base, random.nextInt(base.length + 4));
            for (var flips = random.nextInt(4); flips > 0 && bytes.length > 0; flips--) {
                bytes[random.nextInt(bytes.length)] ^= (byte) (1 << random.nextInt(8));
            }
            try {
                var parsed = OpusHead.parse(bytes, MediaLimits.DEFAULT);
                assertTrue(parsed.channels() > 0);
            } catch (MediaException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
