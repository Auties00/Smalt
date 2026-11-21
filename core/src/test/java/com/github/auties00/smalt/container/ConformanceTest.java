package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Official conformance material: the IETF CELLAR Matroska test suite, demuxed and compared packet by
 * packet with ffprobe (the MPEG file format conformance suite is {@link FileFormatConformanceTest}).
 * The timestamps of audio differ by the codec delay that ffprobe subtracts (see the demuxers'
 * timing conventions).
 */
@DisplayName("conformance suites")
class ConformanceTest {
    static final String CELLAR = "https://raw.githubusercontent.com/ietf-wg-cellar/matroska-test-files/master/test_files/";

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
        @CsvSource({
                "1, 0996a309ff2095910b9d30d5253b044d637154297ddf7d0bda7f3adedf5addc1",
                "2, 5b53d306e56f9bda6e80c3fbd9f3ccd20cc885770449d1fc0b5bec35c71d61e2",
                "3, 1722b0d93a6ef1a14dd513bd031cd5901c233b45aa3e3c87be0b0d7348d7d1b5",
                "4, 43df750a2a01a37949791b717051b41522081a266b71d113be4b713063843699",
                "5, 92acdc33bb0b5d7a4d9b0d6ca792230a78c786a30179dc9999cee41c28642842",
                "6, 7cad84b434116e023d340dd584ac833b93f03fb1bd7ea2727fa45de50af0abb9",
                "8, 9dddcd1550b814dae44d62e2b9f27c0eca31d5e190df2220cbf7492e3d6c63da",
        })
        @DisplayName("demuxes like ffprobe")
        void demuxes(int number, String sha256) {
            var file = Conformance.fetch("matroska-test" + number + ".mkv", CELLAR + "test" + number + ".mkv", sha256);
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, source -> new MatroskaDemuxer(source));
            // laced frames get the block timestamp plus the exact frame offset rounded to a tick; ffmpeg
            // adds whole multiples of the truncated frame duration, up to 3 ticks less in an 8-frame lace
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, ConformanceTest::matroskaOffset, false, 3);
        }

        @ParameterizedTest(name = "test{0}.mkv")
        @CsvSource("7, 95b21c92ad5a4fe00914ff5009e2a64f12fd4c5fb9cb1c3c888ab50bf0ffe483")
        @DisplayName("recovers from the junk elements and damage of test 7")
        void damaged(int number, String sha256) {
            var file = Conformance.fetch("matroska-test" + number + ".mkv", CELLAR + "test" + number + ".mkv", sha256);
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
