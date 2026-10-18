package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the AC-3 frame header reader and the {@code dac3}/{@code dec3} builders against the
 * boxes ffmpeg writes when it remuxes its own encoder's output into MP4.
 */
class Ac3ConfigsTest {
    @TempDir
    Path directory;

    private byte[] box(Path stream, String type) {
        var mp4 = directory.resolve("remux.mp4");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", stream.toString(), "-c", "copy",
                mp4.toString()));
        var data = Fixtures.read(mp4);
        var name = type.getBytes(StandardCharsets.ISO_8859_1);
        for (var i = 4; i + 4 <= data.length; i++) {
            if (Arrays.equals(data, i, i + 4, name, 0, 4)) {
                var size = ((data[i - 4] & 0xFF) << 24) | ((data[i - 3] & 0xFF) << 16) | ((data[i - 2] & 0xFF) << 8)
                        | (data[i - 1] & 0xFF);
                return Arrays.copyOfRange(data, i + 4, i - 4 + size);
            }
        }
        throw new AssertionError("no " + type + " box");
    }

    @ParameterizedTest(name = "{0} Hz, {1} channels, {2}")
    @CsvSource({"48000, 6, 448k", "44100, 2, 192k", "32000, 1, 64k"})
    @DisplayName("builds the dac3 box ffmpeg writes and reads the layout back from it")
    void dac3(int sampleRate, int channels, String bitrate) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = Fixtures.tone("ac3", 440, sampleRate, channels, 0.2, List.of("-c:a", "ac3", "-b:a", bitrate));
        var data = Fixtures.read(stream);
        var frame = Ac3Configs.parse(data, 0, data.length);
        assertNotNull(frame);
        assertFalse(frame.enhanced());
        assertEquals(sampleRate, frame.sampleRate());
        var expected = box(stream, "dac3");
        assertArrayEquals(expected, Ac3Configs.dac3(frame), HexFormat.of().formatHex(expected));
        var parsed = Ac3Configs.parseDac3(expected);
        assertEquals(Codec.AC3, parsed.codec());
        assertEquals(sampleRate, parsed.sampleRate());
        assertEquals(channels, parsed.layout().channels());
        assertEquals(Integer.parseInt(bitrate.replace("k", "")) * 1000L, parsed.bitrate());
    }

    @ParameterizedTest(name = "{0} Hz, {1} channels, {2}")
    @CsvSource({"48000, 6, 384k", "44100, 2, 96k"})
    @DisplayName("builds the dec3 box ffmpeg writes and reads the layout back from it")
    void dec3(int sampleRate, int channels, String bitrate) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = Fixtures.tone("eac3", 440, sampleRate, channels, 0.2, List.of("-c:a", "eac3", "-b:a", bitrate));
        var data = Fixtures.read(stream);
        var frame = Ac3Configs.parse(data, 0, data.length);
        var ours = Ac3Configs.dec3(data, 0, frame.length());
        var expected = box(stream, "dec3");
        // ffmpeg pads the box with a zero byte after the substream description, and derives the
        // 13-bit data_rate from its configured bit rate where Smalt measures the first frame, which
        // differs at 44.1 kHz; the substream description must match bit for bit
        var masked = Arrays.copyOf(expected, ours.length);
        masked[0] = ours[0];
        masked[1] = (byte) ((ours[1] & 0xF8) | (masked[1] & 0x07));
        assertArrayEquals(masked, ours, HexFormat.of().formatHex(expected));
        var parsed = Ac3Configs.parseDec3(expected);
        assertEquals(Codec.EAC3, parsed.codec());
        assertEquals(sampleRate, parsed.sampleRate());
        assertEquals(channels, parsed.layout().channels());
    }

    @Test
    @DisplayName("describes the dependent-substream locations of a dec3 box")
    void locations() {
        var layout = Ac3Configs.layout(7, true, 1 << 1);
        assertEquals(ChannelLayout.SURROUND_7_1, layout);
        assertEquals(Ac3Configs.layout(7, true, 0).channels(), 6);
    }

    @Test
    @DisplayName("rejects truncated and invalid input")
    void invalid() {
        assertNull(Ac3Configs.parse(new byte[]{0x0B, 0x77, 0, 0, (byte) 0xC0, 0x40, 0, 0}, 0, 8));
        assertEquals(-1, Ac3Configs.frameLength(new byte[]{0x0B, 0x78, 0, 0, 0, 0}, 0, 6));
        assertEquals(0, Ac3Configs.frameLength(new byte[]{0x0B, 0x77, 0}, 0, 3));
        assertNull(Ac3Configs.parseDac3(new byte[2]));
        assertNull(Ac3Configs.parseDec3(new byte[3]));
        assertNull(Ac3Configs.describe(new byte[16], 0, 16));
    }
}
