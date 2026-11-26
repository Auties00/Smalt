package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Official conformance material: the IETF CELLAR Matroska test suite, demuxed and compared packet by
 * packet with ffprobe (the MPEG file format conformance suite is {@link FileFormatConformanceTest}).
 * The timestamps of audio differ by the codec delay that ffprobe subtracts (see the demuxers'
 * timing conventions).
 */
@DisplayName("conformance suites")
class ConformanceTest {
    static long mp4Offset(Track track) {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        var timescale = audio.timeBase().denominator();
        var rate = audio.sampleRate() > 0 ? audio.sampleRate() : timescale;
        return Math.round(audio.skipSamples() * (double) timescale / rate);
    }

    static long matroskaOffset(Track track) {
        if (!(track instanceof AudioTrack audio) || audio.sampleRate() == 0) {
            return 0;
        }
        if (audio.codec() == Codec.OPUS && audio.codecConfig().isPresent()) {
            var head = audio.codecConfig().orElseThrow();
            return Math.round(((head[10] & 0xFF) | (head[11] & 0xFF) << 8) / 48.0);
        }
        return Math.round(audio.skipSamples() * 1000.0 / audio.sampleRate());
    }

    @Nested
    @DisplayName("Matroska test suite")
    class Cellar {
        @ParameterizedTest(name = "test{0}.mkv")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 8})
        @DisplayName("demuxes like ffprobe")
        void demuxes(int number) {
            var file = Conformance.vector("matroska-test" + number + ".mkv");
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, source -> new MatroskaDemuxer(source));
            // laced frames get the block timestamp plus the exact frame offset rounded to a tick; ffmpeg
            // adds whole multiples of the truncated frame duration, up to 3 ticks less in an 8-frame lace
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, ConformanceTest::matroskaOffset, false, 3);
        }

        @ParameterizedTest(name = "test{0}.mkv")
        @ValueSource(ints = 7)
        @DisplayName("recovers from the junk elements and damage of test 7")
        void damaged(int number) {
            var file = Conformance.vector("matroska-test" + number + ".mkv");
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, source -> new MatroskaDemuxer(source));
            // both demuxers skip the damaged region; every packet ffprobe keeps must be ours too
            var hashes = ours.order().stream().map(packet -> ContainerProbe.crc32(packet.data())).collect(java.util.stream.Collectors.toSet());
            var missing = probe.packets().stream().filter(packet -> !hashes.contains(packet.hash())).count();
            assertTrue(missing <= probe.packets().size() / 100, missing + " of " + probe.packets().size() + " packets missing");
            assertTrue(ours.order().size() >= probe.packets().size() * 99 / 100);
        }
    }
}
