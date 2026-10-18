package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Covers the HEIF image sequence tracks of AVIF files written by ffmpeg: the {@code pict} color
 * track and the {@code auxv} alpha track demux as AV1 video tracks.
 */
@DisplayName("Mp4Demuxer on AVIF image sequences")
class Mp4ImageSequenceTest {
    @Test
    @DisplayName("presents the pict and auxv tracks as AV1 video tracks with every sample")
    void tracks() {
        var file = Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10,format=yuva420p"),
                List.of("-frames:v", "3", "-filter_complex", "[0]split[c][a];[a]alphaextract[al]", "-map", "[c]",
                        "-map", "[al]", "-c:v", "libaom-av1", "-cpu-used", "8"));
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(2, demuxer.tracks().size());
            for (var track : demuxer.tracks()) {
                var video = assertInstanceOf(VideoTrack.class, track);
                assertEquals(Codec.AV1, video.codec());
                assertEquals(64, video.width());
            }
            var packets = 0;
            while (demuxer.read() != null) {
                packets++;
            }
            assertEquals(6, packets);
        }
    }
}
