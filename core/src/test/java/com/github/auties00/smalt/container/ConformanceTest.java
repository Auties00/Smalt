package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Official conformance material: the MPEG File Format Conformance suite (ISO/IEC 14496-32, files
 * from the MPEGGroup/FileFormatConformance repository) and the IETF CELLAR Matroska test suite.
 * Every file is demuxed and compared packet by packet with ffprobe; the timestamps of audio
 * differ by the edit-list media time or codec delay that ffprobe subtracts (see the demuxers'
 * timing conventions).
 */
@DisplayName("conformance suites")
class ConformanceTest {
    static final String MPEG = "https://media.githubusercontent.com/media/MPEGGroup/FileFormatConformance/main/data/file_features/published/";
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
    @DisplayName("MPEG file format conformance")
    class Mpeg {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "isobmff/01_simple.mp4, c3d407a828ddd692c10c798ae4682b566bc977ff58a2f291f3c55f590633eedf",
                "isobmff/09_text.mp4, 78893928098d751dd3b6fcadfb9b6ed9f3c67d4f6f9b7301c85c637dc3ec4d90",
                "isobmff/10_fragments.mp4, a8a59ae73098ab6235f5aa982f68904b20a8c7d94213c306377e6d12227c697d",
                "isobmff/13_long.mp4, 2652e6d893ce4ba65940568a2c5c00456751803309d63d595d02bae9895af988",
                "isobmff/17_negative_ctso.mp4, 86f540fc711dd883fc880d7d0a3d7c8e024d084a5712ffb9430ed07b8e988468",
                "isobmff/21_segment.mp4, 704df221e8b93b10786f5dae8608670ba18b14ba29908af339e296ced3ee4421",
                "isobmff/22_tx3g.mp4, db9cf6c6e29e759b3538152a9b778fb2a4f18d6e8397e029d9eeea94d4a64a32",
                "isobmff/a1-foreman-QCIF.mp4, cad789a5b38cd4fd6dbe9b8a24a1b4df06d9a5104f4ee48c1a08e587654b41eb",
                "isobmff/a4-tone-fragmented.mp4, f8162b5b1889438c2d5a8ba32adee4e80c62fc70aa0965a72df389040e092cd0",
                "isobmff/a5-foreman-AVC.mp4, bbf5e49a91f9f1f8ebeb18e9fb21b64c18d60efcf164d35297d01a2d83f57673",
                "isobmff/a7-tone-oddities.mp4, f8f22d61b7211ed8bb8d291734bb6b98f58ec61ad56e586a8d10189d9d83d2f7",
                "isobmff/a8-foreman_QCIF_edit.mp4, 6776d9a8bfa9ae08a801fc4c470fceb159d937dc10ec0939321352d435dbcdc0",
                "isobmff/a9-aac-samplegroups-edit.mp4, 11b6fb91b70ddbbb37a59a99b30b485095524b6358f2bb3a1bc3d29bce04b022",
                "isobmff/f1.mp4, 73127ba85ea52aec757c29079fea0cd1491bfd91f874ae7a4fc118913fe486fe",
                "isobmff/fragment_random_access-2.mp4, 6a86eca2b78d120a3239c6e062828a350f66e7fb6046fea1949feb2dc2c050f0",
                "isobmff/sg-tl-st.mp4, 6052dd2f998a52da1ef47dd95ccc8bf8f0950ab0bcf3820d71bfe47ba92238b7",
                "isobmff/timed-metadata.mp4, 7c6170c8c3328e932f32a573e9dfad349648accd3cfc57549c285b1733b82342",
                "3gp/male_amr122.3gp, f1a747471437717e5d2c99677a50efb0136d28b9569c6eaf5d9a6b413ca2e7b5",
                "3gp/male_amr122DTX.3gp, 40294d2756955a2f774711a5c6a9bc856cb56317eae4c4bf26d65fbede5f5b5f",
                "3gp/pdin_example.3gp, 94531e9f061a57db1c0d906da9dff15f2ce63974cdae0cb5fc37eddf86770ce7",
                "3gp/rs_example_r1.3gp, f55f7fb0c24ac2dd5a7427b294d1e2255e4f90352fc221367f1a44aed1d02c2d",
                "nalu/hevc/hvc1_only.mp4, 20933b79db875c2c01d3d130c90e05e0d05f260778d8e2619e24c5be5f0b909b",
                "nalu/hevc/hev1_only.mp4, 7c8b241383765422d366edd495df223d10e3c28fc50e8ff45238e82af15c9b32",
        })
        @DisplayName("demuxes like ffprobe")
        void demuxes(String name, String sha256) {
            var file = fetch(name, sha256);
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, source -> new Mp4Demuxer(source));
            // ffmpeg ignores the lone zero-length edit of this fragmented segment, where we apply its media
            // time of 8; and it moves an edit start that falls inside a sample (a8: media time 15085, samples
            // every 100) to the next sample boundary, where we apply the exact media time
            ToLongFunction<Track> offset = name.endsWith("21_segment.mp4") ? track -> -8
                    : name.endsWith("a8-foreman_QCIF_edit.mp4") ? track -> 15 : ConformanceTest::mp4Offset;
            DemuxAssertions.assertPacketsMatch(ours, probe, offset, true);
            DemuxAssertions.assertMonotonicDts(ours);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource("isobmff/a10-foreman_QCIF-raw.mp4, b3ce8c6a1f7a3c851f70b33acaf23cbcc764e9e8d5ee3aeeee9b131bd6e6e035")
        @DisplayName("demuxes raw video that ffprobe cannot describe")
        void rawVideo(String name, String sha256) {
            var ours = ContainerProbe.demux(fetch(name, sha256), source -> new Mp4Demuxer(source));
            var track = ours.tracks().getFirst();
            assertEquals(Codec.UNKNOWN_VIDEO, track.codec());
            var packets = ours.packetsOf(track);
            assertTrue(packets.size() > 10);
            // raw 4:2:0 QCIF frames
            assertTrue(packets.stream().allMatch(packet -> packet.size() == 176 * 144 * 3 / 2), "every frame is one QCIF picture");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource("isobmff/02_dref_edts_img.mp4, cb25f781513fd0ae11fd27bdcaa144eccdd8f97da79d126e2f6ab6f696b5da20")
        @DisplayName("rejects samples stored in other files as unsupported")
        void externalData(String name, String sha256) {
            var file = fetch(name, sha256);
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> ContainerProbe.demux(file, source -> new Mp4Demuxer(source)));
            assertEquals("mp4 external data reference", failure.format());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "isobmff/a3-tone-protected.mp4, 4e50825d2c7b6e7e3aca447629184272b6e598e3522e14674e703d3f6864195c",
                "isobmff/f2.mp4, e676e6114f48d5a76d057f0225f3dfef21395b539c90e2223591e85b8940de7f",
        })
        @DisplayName("rejects encrypted tracks as unsupported")
        void encrypted(String name, String sha256) {
            var file = fetch(name, sha256);
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> ContainerProbe.demux(file, source -> new Mp4Demuxer(source)));
            assertEquals("mp4 encryption", failure.format());
        }

        Path fetch(String name, String sha256) {
            return Conformance.fetch("mpegff-" + name.substring(name.lastIndexOf('/') + 1), MPEG + name, sha256);
        }
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
