package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ambisonic layout of Opus mapping family 2 in every container that carries Opus, on streams libopus
 * coded through ffmpeg: the layout each demuxer reports, and remuxing between Ogg, Matroska and MP4, which keeps
 * the identification header byte for byte and the layout, as ffprobe confirms.
 */
@DisplayName("Opus ambisonic layouts across containers")
class OpusAmbisonicRemuxTest {
    private static final ChannelLayout HEAD_LOCKED = ChannelLayout.ofAmbisonics(Ambisonics.of(1),
            List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));

    private static final Map<String, Function<MediaSink, Muxer>> MUXERS = Map.of(
            "ogg", OggMuxer::new, "mkv", MatroskaMuxer::new, "mp4", Mp4Muxer::new);

    private static Path family2(int channels) {
        var pan = new StringBuilder("pan=" + channels + "C");
        for (var c = 0; c < channels; c++) {
            pan.append("|c").append(c).append('=').append(1.0 / (c + 1)).append("*c0");
        }
        return Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.5"),
                List.of("-af", pan.toString(), "-c:a", "libopus", "-mapping_family", "2", "-b:a", "128k"));
    }

    private static AudioTrack audio(ContainerProbe.Demuxed demuxed) {
        return (AudioTrack) demuxed.tracks().getFirst();
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6, 9})
    @DisplayName("reports and keeps the ambisonic layout of family 2 through Ogg, Matroska and MP4")
    void remux(int channels) {
        Oracle.assumeEncoder("libopus");
        var expected = channels == 6 ? HEAD_LOCKED : ChannelLayout.ofAmbisonics(channels == 4 ? 1 : 2);
        var source = MuxSupport.demux(Fixtures.read(family2(channels)));
        var track = audio(source);
        assertEquals(expected, track.layout().orElseThrow(), "Ogg demuxer");
        var head = track.codecConfig().orElseThrow();
        assertEquals(2, head[18], "mapping family");
        for (var entry : MUXERS.entrySet()) {
            var file = MuxSupport.toMemory(List.<Track>of(track), source.order(), entry.getValue());
            var remuxed = MuxSupport.demux(file);
            var read = audio(remuxed);
            assertEquals(expected, read.layout().orElseThrow(), entry.getKey());
            assertArrayEquals(head, read.codecConfig().orElseThrow(), entry.getKey() + " OpusHead");
            MuxSupport.assertSamePayloads(source, remuxed, Map.of(track.id(), read.id()));
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                var probe = Oracle.probe(MuxSupport.save("opus-ambisonic-" + channels + "." + entry.getKey(), file));
                var name = channels == 6 ? "ambisonic 1+stereo" : "ambisonic " + (channels == 4 ? 1 : 2);
                assertTrue(probe.contains("\"channel_layout\": \"" + name + "\""), entry.getKey() + ": " + probe);
            }
        }
    }

    @Test
    @DisplayName("derives the ambisonic layout from the channel count of families 2 and 3 only")
    void layouts() {
        for (var order = 0; order <= Ambisonics.MAX_ORDER; order++) {
            var count = (order + 1) * (order + 1);
            for (var family : new int[]{2, 3}) {
                assertEquals(ChannelLayout.ofAmbisonics(order), OpusHeader.ambisonicLayout(count, family));
                assertEquals(ChannelLayout.ofAmbisonics(Ambisonics.of(order), List.of(Speaker.FRONT_LEFT,
                        Speaker.FRONT_RIGHT)), OpusHeader.ambisonicLayout(count + 2, family));
            }
            assertNull(OpusHeader.ambisonicLayout(count, 1));
            assertNull(OpusHeader.ambisonicLayout(count, 255));
        }
        for (var count : new int[]{2, 5, 7, 8, 10, 12, 226, 228, 255}) {
            assertNull(OpusHeader.ambisonicLayout(count, 2), Integer.toString(count));
        }
    }
}
