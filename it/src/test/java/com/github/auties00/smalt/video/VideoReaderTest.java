package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link VideoReader} pipeline: container recognition, decoding in presentation order,
 * early stop, and the failures for inputs without a decodable video track.
 */
class VideoReaderTest {
    @Test
    @DisplayName("decodes every frame of a VP8 WebM in presentation order")
    void vp8() {
        var file = Fixtures.testVideo("webm", 160, 120, 1, List.of("-c:v", "libvpx", "-b:v", "300k", "-an"));
        try (var reader = VideoReader.open(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(ContainerFormat.MATROSKA, reader.format());
            assertEquals(Codec.VP8, reader.track().codec());
            var timestamps = new ArrayList<Long>();
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                assertEquals(160, frame.picture().width());
                assertEquals(120, frame.picture().height());
                timestamps.add(frame.pts());
            }
            assertEquals(30, timestamps.size());
            for (var i = 1; i < timestamps.size(); i++) {
                assertTrue(timestamps.get(i) > timestamps.get(i - 1), "presentation order at frame " + i);
            }
        }
    }

    @Test
    @DisplayName("decodes every frame of VP9 in WebM and MP4 in presentation order")
    void vp9() {
        for (var extension : List.of("webm", "mp4")) {
            var file = Fixtures.testVideo(extension, 160, 120, 1, List.of("-c:v", "libvpx-vp9", "-b:v", "300k", "-an"));
            try (var reader = VideoReader.open(MediaSource.of(Fixtures.read(file)))) {
                assertEquals(Codec.VP9, reader.track().codec());
                var timestamps = new ArrayList<Long>();
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    assertEquals(160, frame.picture().width());
                    assertEquals(120, frame.picture().height());
                    timestamps.add(frame.pts());
                }
                assertEquals(30, timestamps.size(), extension);
                for (var i = 1; i < timestamps.size(); i++) {
                    assertTrue(timestamps.get(i) > timestamps.get(i - 1), extension + " presentation order at frame " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("decodes H.264 with B-frames in MP4 and Matroska in presentation order from time zero")
    void h264() {
        for (var extension : List.of("mp4", "mkv")) {
            var file = Fixtures.testVideo(extension, 176, 144, 1, List.of("-c:v", "libx264", "-bf", "3", "-an"));
            try (var reader = VideoReader.open(MediaSource.of(Fixtures.read(file)))) {
                assertEquals(Codec.H264, reader.track().codec());
                var timestamps = new ArrayList<Long>();
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    assertEquals(176, frame.picture().width());
                    timestamps.add(frame.pts());
                }
                assertEquals(30, timestamps.size(), extension);
                assertTrue(timestamps.getFirst() >= 0, extension + " starts at the presentation start");
                for (var i = 1; i < timestamps.size(); i++) {
                    assertTrue(timestamps.get(i) > timestamps.get(i - 1), extension + " presentation order at frame " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("pulls frames one at a time until the end, then seeks back into a WebM")
    void earlyStop() {
        var file = Fixtures.testVideo("webm", 64, 48, 1, List.of("-c:v", "libvpx", "-an"));
        try (var reader = VideoReader.open(MediaSource.of(Fixtures.read(file)))) {
            var first = reader.read();
            assertNotNull(first);
            var rest = 0;
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                assertTrue(frame.pts() > first.pts());
                rest++;
            }
            assertEquals(29, rest);
            assertNull(reader.read());
            assertTrue(reader.isSeekable());
            reader.seek(0);
            assertEquals(first.pts(), reader.read().pts());
        }
    }

    @Test
    @DisplayName("rejects inputs without a decodable video track")
    void rejects() {
        var audio = Fixtures.tone("ogg", 440, 48_000, 1, 0.5, List.of("-c:a", "libopus"));
        var noVideo = assertThrows(MediaException.Unsupported.class,
                () -> VideoReader.open(MediaSource.of(Fixtures.read(audio))));
        assertEquals("no video", noVideo.format());
        var text = MediaSource.of("not media".getBytes());
        assertThrows(MediaException.Unsupported.class, () -> VideoReader.open(text));
    }
}
