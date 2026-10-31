package com.github.auties00.smalt.container.asf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demuxes ASF files of other writers than Smalt against ffprobe: Windows Media files of the FFmpeg samples archive
 * and FATE suite (Windows Media Encoder output with compressed payloads, error correction data, sample duration
 * payload extensions, three streams, Windows Media Video 9 of the simple and main profiles), and the files ffmpeg's
 * ASF muxer writes from several codecs. {@link AsfProbe} compares every packet.
 */
@DisplayName("ASF files of other writers")
class AsfThirdPartyTest {
    @Nested
    @DisplayName("Windows Media files")
    class WindowsMedia {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"asf-samples-elephant.asf", "asf-samples-welcome3.wmv", "asf-samples-ecc-asf.asf",
                "asf-samples-multivideo.asf", "asf-samples-wma_with_WmPicture.wma", "asf-fate-wmv8_x8intra.wmv",
                "asf-fate-bug821-2.asf", "asf-fate-streaming_CBR-7K.wma", "asf-fate-Beethovens_9th-1_small.wma",
                "vc1-samples-wmv9-simple-sp-ml.wmv", "vc1-samples-wmv9-main-mp-ll.wmv", "vc1-samples-wmv9-niceday.wmv",
                "vc1-samples-wmv9-bloodyback.wmv", "vc1-samples-wmv9-psychonauts.wmv"})
        @DisplayName("every packet has ffprobe's timestamp, size, payload checksum and key flag")
        void packets(String name) {
            var file = Conformance.fetchListed(name);
            try (var source = MediaSource.of(file)) {
                assertEquals(ContainerFormat.ASF, Containers.detect(source).orElseThrow());
            }
            assertTrue(AsfProbe.compare(file) > 0, name + " has packets");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"asf-fate-wmv_drm.wmv", "asf-samples-wma_with_metadata_library_object_tag.wma"})
        @DisplayName("a file protected by digital rights management is refused")
        void protectedContent(String name) {
            var file = Conformance.fetchListed(name);
            try (var source = MediaSource.of(file)) {
                var failure = assertThrows(MediaException.Unsupported.class, () -> new AsfDemuxer(source));
                assertTrue(failure.getMessage().contains("digital rights"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("the pictures of WM/Picture attributes are the attached pictures")
        void picture() {
            var file = Conformance.fetchListed("asf-samples-wma_with_WmPicture.wma");
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                var pictures = demuxer.attachedPictures();
                assertEquals(1, pictures.size());
                var picture = pictures.getFirst();
                assertEquals("image/jpeg", picture.mediaType());
                assertEquals(0xFF, picture.data()[0] & 0xFF);
                assertEquals(0xD8, picture.data()[1] & 0xFF);
                assertEquals("Champagne for All the World", demuxer.metadata().get("album"));
                assertEquals("Jacques Higelin", demuxer.metadata().get("album_artist"));
                assertEquals("2", demuxer.metadata().get("track"));
            }
        }
    }

    @Nested
    @DisplayName("ffmpeg's ASF muxer")
    class Ffmpeg {
        @Test
        @DisplayName("H.264 with B-frames and MP3")
        void h264Mp3() {
            var file = Fixtures.ffmpeg("asf", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=160x120:rate=25:duration=2", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=44100:duration=2"), List.of("-c:v", "libx264", "-g", "12",
                    "-bf", "2", "-c:a", "libmp3lame", "-b:a", "64k", "-f", "asf"));
            AsfProbe.compare(file);
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                var video = demuxer.tracks().stream().filter(track -> track.codec() == Codec.H264).findFirst()
                        .orElseThrow();
                assertTrue(video.codecConfig().isPresent(), "an avcC record is built from the parameter sets");
            }
        }

        @Test
        @DisplayName("Windows Media Video 8 and Audio 2, which Smalt does not decode")
        void windowsMedia() {
            var file = Fixtures.ffmpeg("wmv", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=176x144:rate=25:duration=2", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=22050:duration=2"), List.of("-c:v", "wmv2", "-c:a", "wmav2"));
            AsfProbe.compare(file);
        }

        @Test
        @DisplayName("PCM audio")
        void pcm() {
            AsfProbe.compare(Fixtures.tone("wma", 440, 22050, 2, 1.5, List.of("-c:a", "pcm_s16le", "-f", "asf")));
        }

        @Test
        @DisplayName("MPEG-4 Part 2 and AC-3")
        void mpeg4Ac3() {
            AsfProbe.compare(Fixtures.ffmpeg("asf", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=160x120:rate=25:duration=2", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=2"), List.of("-c:v", "mpeg4", "-c:a", "ac3",
                    "-f", "asf")));
        }

        @Test
        @DisplayName("Motion JPEG and A-law audio")
        void mjpegAlaw() {
            AsfProbe.compare(Fixtures.ffmpeg("asf", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=96x64:rate=10:duration=1", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=8000:duration=1"), List.of("-c:v", "mjpeg", "-c:a", "pcm_alaw",
                    "-f", "asf")));
        }

        @Test
        @DisplayName("tags, chapters and the duration ffmpeg writes")
        void tags() {
            var file = Fixtures.ffmpeg("wmv", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=96x64:rate=25:duration=3", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=22050:duration=3"), List.of("-c:v", "wmv2", "-c:a", "wmav2",
                    "-metadata", "title=The Title", "-metadata", "artist=An Artist", "-metadata",
                    "album=An Album", "-metadata", "comment=A comment", "-metadata", "copyright=(c) Someone",
                    "-metadata", "genre=Rock", "-metadata", "track=7", "-metadata", "date=2024"));
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                var tags = demuxer.metadata();
                assertEquals("The Title", tags.get("title"));
                assertEquals("An Artist", tags.get("artist"));
                assertEquals("An Album", tags.get("album"));
                assertEquals("A comment", tags.get("comment"));
                assertEquals("(c) Someone", tags.get("copyright"));
                assertEquals("Rock", tags.get("genre"));
                assertEquals("7", tags.get("track"));
                assertEquals("2024", tags.get("date"));
                assertTrue(Math.abs(demuxer.durationMicros() - 3_000_000) < 100_000,
                        "duration " + demuxer.durationMicros());
            }
        }
    }
}
