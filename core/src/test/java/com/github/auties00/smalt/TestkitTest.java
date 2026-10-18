package com.github.auties00.smalt;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Bytes;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Packets;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared test kit helpers that replace the per-module copies: the PSNR overloads, byte
 * concatenation, packet reading and the generic tool runner.
 */
class TestkitTest {
    @Test
    @DisplayName("computes PSNR of strided planes, deep samples, whole pictures and mean squared errors")
    void psnr() {
        assertEquals(Double.POSITIVE_INFINITY, Quality.psnr(0.0));
        assertEquals(10 * Math.log10(255.0 * 255.0 / 4), Quality.psnr(4.0), 1e-12);
        assertEquals(10 * Math.log10(1023.0 * 1023.0 / 4), Quality.psnr(4.0, 1023), 1e-12);
        var expected = new byte[]{10, 20, 0, 30, 40, 0};
        var actual = new byte[]{12, 20, 9, 9, 30, 40, 9, 9};
        assertEquals(Quality.psnr(1.0), Quality.psnr(expected, 3, actual, 4, 2, 2), 1e-12);
        assertEquals(Quality.psnr(2.0, 1023), Quality.psnr(new short[]{1000, 3}, new short[]{1002, 3}, 1023), 1e-12);
        var a = YuvPicture.allocate(4, 4, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var b = YuvPicture.allocate(4, 4, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        assertEquals(Double.POSITIVE_INFINITY, Quality.psnr(a, b));
        b.u()[0] = (byte) (a.u()[0] + 24);
        assertEquals(Quality.psnr(24.0 * 24 / 24), Quality.psnr(a, b), 1e-12);
        var deep = Yuv16Picture.allocate(2, 2, ChromaFormat.MONOCHROME, 10, false, ColorInfo.BT709_LIMITED);
        var deeper = Yuv16Picture.allocate(2, 2, ChromaFormat.MONOCHROME, 10, false, ColorInfo.BT709_LIMITED);
        deeper.y()[0] = 2;
        assertEquals(Quality.psnr(1.0, 1023), Quality.psnr(deep, deeper), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> Quality.psnr(a, RgbaPicture.allocate(4, 4, false)));
    }

    @Test
    @DisplayName("concatenates byte arrays")
    void concat() {
        assertArrayEquals(new byte[]{1, 2, 3}, Bytes.concat(new byte[]{1}, new byte[0], new byte[]{2, 3}));
        assertArrayEquals(new byte[0], Bytes.concat());
    }

    @Test
    @DisplayName("reads every packet, or the packets of one track")
    void packets() {
        var queue = new ArrayDeque<>(List.of(new Packet(1, new byte[1], 0, 0, 1, true),
                new Packet(2, new byte[1], 0, 0, 1, true), new Packet(1, new byte[1], 1, 1, 1, true)));
        assertEquals(3, Packets.readAll(demuxer(new ArrayDeque<>(queue))).size());
        assertEquals(2, Packets.readAll(demuxer(queue), 1).size());
    }

    @Test
    @DisplayName("finds tools by name and runs ffmpeg with its streaming and non-failing runners when installed")
    void oracle() {
        assertFalse(Oracle.find("smalt-no-such-tool").isPresent());
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        assertTrue(Oracle.find("ffmpeg").isPresent());
        var failed = Oracle.execute(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), null, List.of("-v", "error", "-i",
                "smalt-missing-input.wav"));
        assertTrue(failed.exitCode() != 0);
        assertFalse(failed.stderr().isBlank());
        var stream = new ByteArrayOutputStream();
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "lavfi", "-i",
                "anullsrc=r=8000:cl=mono", "-t", "0.01", "-f", "s16le", "-"), stream);
        assertEquals(160, stream.size());
        var merged = Oracle.runMerged(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-hide_banner", "-version"));
        assertTrue(merged.contains("ffmpeg"));
    }

    private static Demuxer demuxer(ArrayDeque<Packet> packets) {
        return new Demuxer() {
            @Override
            public ContainerFormat format() {
                return ContainerFormat.MP4;
            }

            @Override
            public List<Track> tracks() {
                return List.of();
            }

            @Override
            public Map<String, String> metadata() {
                return Map.of();
            }

            @Override
            public long durationMicros() {
                return -1;
            }

            @Override
            public Packet read() {
                return packets.poll();
            }
        };
    }
}
