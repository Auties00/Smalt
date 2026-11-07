package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Dolby Vision signaling of HLS master playlists: the {@code SUPPLEMENTAL-CODECS} format of a track whose
 * base layer is backward compatible (the Dolby Vision codec string with its two-digit profile and level, and the
 * base layer brand {@code db1p}, {@code db2g} or {@code db4h}), next to the base codec in {@code CODECS}, and the
 * Dolby Vision codec string alone in {@code CODECS} for a track without a compatible base layer, and the
 * {@code VIDEO-RANGE} of the base layer, or of the transfer of HDR video that is not Dolby Vision. The packaged
 * presentations come from the committed {@code make-dolby-vision-samples.py} streams (profiles 8.4 and 5), the 8.4
 * stream relabeled as profile 8.1 and 8.2, and a libaom AV1 encode labeled as profile 10.1 and 10.4; each master
 * playlist is matched against its expected lines and checked by the test kit's HLS validator.
 */
@DisplayName("Packager Dolby Vision signaling")
class PackagerDolbyVisionTest {
    /** The pattern of a decimal bandwidth, the only part of the expected lines that depends on the encode. */
    private static final String RATE = "\\d+";

    private static Path sample(String name) {
        try (var in = PackagerDolbyVisionTest.class.getResourceAsStream("/samples/" + name)) {
            return Scratch.share(Scratch.directory("packaging-inputs").resolve(name.replace('/', '-')),
                    in.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path av1Encode() {
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=128x96:rate=10:duration=1"),
                List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime", "-g", "5", "-pix_fmt",
                        "yuv420p10le"));
    }

    /**
     * Packages the video of a file relabeled with a Dolby Vision configuration, or as it is when the configuration
     * is {@code null}, and returns the master playlist.
     */
    private static String pack(Path directory, Path input, DolbyVision configuration, PackagingOptions options) {
        var video = Presentations.source(input, TrackKind.VIDEO);
        var track = configuration == null ? video.track()
                : ((VideoTrack) video.track()).toBuilder().dolbyVision(configuration).build();
        var rendition = Rendition.builder(List.of(track)).name("dv").build();
        try (var writer = Packager.open(List.of(rendition), PackageOutput.directory(directory), options)) {
            for (var packet : video.packets()) {
                writer.writePacket("dv", packet);
            }
            writer.finish();
        }
        var master = directory.resolve(options.masterPlaylistName());
        Validators.assertValidHls(master);
        return Presentations.text(master);
    }

    /** {@return the codec string of the base layer of the video of a file} */
    private static String base(Path input) {
        var track = (VideoTrack) Presentations.source(input, TrackKind.VIDEO).track();
        return CodecStrings.of(track.toBuilder().noDolbyVision().build(), null);
    }

    private static void assertLine(String playlist, String regex) {
        assertTrue(Pattern.compile("(?m)^" + regex + "$").matcher(playlist).find(),
                "no line matches " + regex + " in\n" + playlist);
    }

    @Nested
    @DisplayName("SUPPLEMENTAL-CODECS formats")
    class Formats {
        static Stream<Arguments> formats() {
            return Stream.of(
                    Arguments.of(Codec.HEVC, DolbyVision.of(8, 6, 1), "dvh1.08.06/db1p"),
                    Arguments.of(Codec.HEVC, DolbyVision.of(8, 4, 2), "dvh1.08.04/db2g"),
                    Arguments.of(Codec.HEVC, DolbyVision.of(8, 4, 4), "dvh1.08.04/db4h"),
                    Arguments.of(Codec.HEVC, DolbyVision.of(8, 13, 4), "dvh1.08.13/db4h"),
                    Arguments.of(Codec.HEVC, new DolbyVision(1, 0, 7, 6, true, true, true, 6, 0), "dvh1.07.06"),
                    Arguments.of(Codec.HEVC, DolbyVision.of(8, 6, 3), "dvh1.08.06"),
                    Arguments.of(Codec.AV1, DolbyVision.of(10, 9, 1), "dav1.10.09/db1p"),
                    Arguments.of(Codec.AV1, DolbyVision.of(10, 9, 2), "dav1.10.09/db2g"),
                    Arguments.of(Codec.AV1, DolbyVision.of(10, 1, 4), "dav1.10.01/db4h"),
                    Arguments.of(Codec.H264, DolbyVision.of(9, 5, 2), "dva1.09.05/db2g"));
        }

        @ParameterizedTest(name = "{1} -> {2}")
        @MethodSource("formats")
        @DisplayName("name the Dolby Vision codec and the base layer brand of a backward compatible track")
        void compatible(Codec codec, DolbyVision configuration, String expected) {
            var track = VideoTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(90_000))
                    .dolbyVision(configuration).build();
            assertEquals(expected, CodecStrings.supplemental(track));
            assertFalse(CodecStrings.of(track, null).startsWith("d"), "CODECS keeps the base codec");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(nullValues = "none", value = {"5:6:0, PQ", "10:6:0, PQ", "8:6:1, PQ", "8:6:2, SDR", "8:6:4, HLG",
                "7:6:6, PQ", "8:6:3, none", "none, none"})
        @DisplayName("give the VIDEO-RANGE of the base layer of Dolby Vision, and none for unmarked SDR video")
        void ranges(String configuration, String expected) {
            var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(Rational.timeBaseOf(90_000)).build();
            if (configuration != null) {
                var fields = configuration.split(":");
                track = track.toBuilder().dolbyVision(DolbyVision.of(Integer.parseInt(fields[0]),
                        Integer.parseInt(fields[1]), Integer.parseInt(fields[2]))).build();
            }
            assertEquals(expected, CodecStrings.videoRange(track));
            // a transfer only decides when Dolby Vision does not
            var hlg = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.HLG);
            assertEquals(expected == null ? "HLG" : expected,
                    CodecStrings.videoRange(track.toBuilder().color(hlg).build()));
        }

        @Test
        @DisplayName("leave out tracks without a compatible base layer, without Dolby Vision, or of other codecs")
        void none() {
            var hevc = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(Rational.timeBaseOf(90_000)).build();
            assertNull(CodecStrings.supplemental(hevc));
            assertNull(CodecStrings.supplemental(hevc.toBuilder().dolbyVision(DolbyVision.of(5, 6, 0)).build()));
            assertNull(CodecStrings.supplemental(hevc.toBuilder().dolbyVision(DolbyVision.of(10, 6, 0)).build()));
            // a stored enhancement layer has no base layer, so it supplements nothing
            assertNull(CodecStrings.supplemental(hevc.toBuilder()
                    .dolbyVision(new DolbyVision(1, 0, 7, 6, true, true, false, 6, 0)).build()));
            assertNull(CodecStrings.supplemental(hevc.toBuilder().codec(Codec.VP9)
                    .dolbyVision(DolbyVision.of(8, 6, 1)).build()));
            assertNull(CodecStrings.supplemental(AudioTrack.builder().id(2).codec(Codec.AAC)
                    .timeBase(Rational.timeBaseOf(48_000)).build()));
        }
    }

    @Nested
    @DisplayName("master playlists")
    class Masters {
        @ParameterizedTest(name = "profile 8 compatibility {0} in {1}")
        @CsvSource({"4, db4h, HLG, hls", "4, db4h, HLG, hls-cmaf", "1, db1p, PQ, hls-cmaf", "2, db2g, SDR, hls"})
        @DisplayName("list the HEVC base layer in CODECS, the Dolby Vision layer in SUPPLEMENTAL-CODECS and the "
                + "base layer's VIDEO-RANGE")
        void hevc(int compatibility, String brand, String range, String layout) {
            var input = sample("mp4/dolby-vision-p84.mp4");
            var options = (layout.equals("hls") ? PackagingOptions.hls() : PackagingOptions.hlsCmaf()).toBuilder()
                    .trickPlay(true).build();
            var directory = Presentations.directory("dolby-vision-p8" + compatibility + "-" + layout);
            var master = pack(directory, input, DolbyVision.of(8, 1, compatibility), options);
            var codecs = Pattern.quote(base(input));
            assertTrue(base(input).startsWith("hvc1.2."), base(input));
            var playlist = layout.equals("hls") ? "dv.m3u8" : "dv_video.m3u8";
            var iFrames = layout.equals("hls") ? "dv_iframes.m3u8" : "dv_video_iframes.m3u8";
            assertLine(master, "#EXT-X-STREAM-INF:BANDWIDTH=" + RATE + ",AVERAGE-BANDWIDTH=" + RATE + ",CODECS=\""
                    + codecs + "\",SUPPLEMENTAL-CODECS=\"dvh1\\.08\\.01/" + brand + "\",VIDEO-RANGE=" + range
                    + ",RESOLUTION=\\d+x\\d+(,FRAME-RATE=[0-9.]+)?");
            assertLine(master, Pattern.quote(playlist));
            assertLine(master, "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=" + RATE + ",AVERAGE-BANDWIDTH=" + RATE
                    + ",CODECS=\"" + codecs + "\",SUPPLEMENTAL-CODECS=\"dvh1\\.08\\.01/" + brand + "\",VIDEO-RANGE="
                    + range + ",RESOLUTION=\\d+x\\d+,URI=\"" + Pattern.quote(iFrames) + "\"");
            assertEquals(2, master.split("SUPPLEMENTAL-CODECS=", -1).length - 1, master);
            assertEquals(2, master.split("VIDEO-RANGE=", -1).length - 1, master);
        }

        @Test
        @DisplayName("name a profile 5 stream by its Dolby Vision codec string alone, in the PQ range")
        void profile5() throws IOException {
            var directory = Presentations.directory("dolby-vision-p5");
            var master = pack(directory, sample("mp4/dolby-vision-p5.mp4"), null, PackagingOptions.hlsCmaf());
            assertLine(master, "#EXT-X-STREAM-INF:BANDWIDTH=" + RATE + ",AVERAGE-BANDWIDTH=" + RATE
                    + ",CODECS=\"dvh1\\.05\\.01\",VIDEO-RANGE=PQ,RESOLUTION=\\d+x\\d+(,FRAME-RATE=[0-9.]+)?");
            assertFalse(master.contains("SUPPLEMENTAL-CODECS"), master);
            var file = directory.resolve("master.m3u8");
            Files.writeString(file, master.replace(",VIDEO-RANGE=PQ", ""), StandardCharsets.UTF_8);
            assertThrows(AssertionError.class, () -> Validators.assertValidHls(file));
        }

        @Test
        @DisplayName("declare the VIDEO-RANGE of HDR video that is not Dolby Vision, and none for SDR")
        void hdr() {
            var input = Presentations.video("160x120", "150k", 30, 2);
            for (var transfer : List.of(TransferCharacteristics.PQ, TransferCharacteristics.HLG,
                    TransferCharacteristics.BT709)) {
                var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                        transfer);
                var directory = Presentations.directory("hdr-" + transfer);
                var video = Presentations.source(input, TrackKind.VIDEO);
                var track = ((VideoTrack) video.track()).toBuilder().color(color).build();
                var rendition = Rendition.builder(List.of(track)).name("hdr").build();
                var options = PackagingOptions.hlsCmaf().toBuilder().trickPlay(true).build();
                try (var writer = Packager.open(List.of(rendition), PackageOutput.directory(directory), options)) {
                    for (var packet : video.packets()) {
                        writer.writePacket("hdr", packet);
                    }
                    writer.finish();
                }
                var master = Presentations.text(directory.resolve(options.masterPlaylistName()));
                Validators.assertValidHls(directory.resolve(options.masterPlaylistName()));
                var expected = transfer == TransferCharacteristics.BT709 ? 0 : 2;
                assertEquals(expected, master.split(",VIDEO-RANGE=" + transfer.name() + ",", -1).length - 1,
                        master);
                assertEquals(expected, master.split("VIDEO-RANGE=", -1).length - 1, master);
                assertFalse(master.contains("SUPPLEMENTAL-CODECS"), master);
            }
        }

        @Test
        @DisplayName("write the sample's own profile 8.4 configuration unchanged")
        void sampleConfiguration() {
            var directory = Presentations.directory("dolby-vision-p84-sample");
            var master = pack(directory, sample("mp4/dolby-vision-p84.mp4"), null, PackagingOptions.hls());
            assertTrue(master.contains(",SUPPLEMENTAL-CODECS=\"dvh1.08.01/db4h\","), master);
        }

        @ParameterizedTest(name = "compatibility {0}")
        @CsvSource({"1, db1p", "4, db4h"})
        @DisplayName("list the AV1 base layer in CODECS and the profile 10 layer in SUPPLEMENTAL-CODECS")
        void av1(int compatibility, String brand) {
            var input = av1Encode();
            var directory = Presentations.directory("dolby-vision-p10" + compatibility);
            var master = pack(directory, input, DolbyVision.of(10, 2, compatibility), PackagingOptions.hlsCmaf());
            assertTrue(base(input).startsWith("av01.0."), base(input));
            assertLine(master, "#EXT-X-STREAM-INF:BANDWIDTH=" + RATE + ",AVERAGE-BANDWIDTH=" + RATE + ",CODECS=\""
                    + Pattern.quote(base(input)) + "\",SUPPLEMENTAL-CODECS=\"dav1\\.10\\.02/" + brand
                    + "\",VIDEO-RANGE=" + (compatibility == 1 ? "PQ" : "HLG")
                    + ",RESOLUTION=128x96(,FRAME-RATE=[0-9.]+)?");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"hls", "hls-cmaf"})
        @DisplayName("list the H.264 base layer in CODECS and the profile 9 layer in SUPPLEMENTAL-CODECS")
        void h264(String layout) {
            var input = Presentations.video("160x120", "150k", 30, 2);
            var options = layout.equals("hls") ? PackagingOptions.hls() : PackagingOptions.hlsCmaf();
            var directory = Presentations.directory("dolby-vision-p9-" + layout);
            var master = pack(directory, input, DolbyVision.of(9, 3, 2), options);
            assertTrue(base(input).startsWith("avc1."), base(input));
            assertLine(master, "#EXT-X-STREAM-INF:BANDWIDTH=" + RATE + ",AVERAGE-BANDWIDTH=" + RATE + ",CODECS=\""
                    + Pattern.quote(base(input)) + "\",SUPPLEMENTAL-CODECS=\"dva1\\.09\\.03/db2g\""
                    + ",VIDEO-RANGE=SDR,RESOLUTION=160x120(,FRAME-RATE=[0-9.]+)?");
        }

        @Test
        @DisplayName("are rejected by the validator when SUPPLEMENTAL-CODECS is malformed or supplements nothing")
        void validator() throws IOException {
            var directory = Presentations.directory("dolby-vision-invalid");
            var master = pack(directory, sample("mp4/dolby-vision-p84.mp4"), null, PackagingOptions.hls());
            var file = directory.resolve("master.m3u8");
            for (var broken : List.of(master.replace("dvh1.08.01/db4h", "dvh1.8.1/db4h"),
                    master.replace("dvh1.08.01/db4h", "dvh1.08.01/db9x"),
                    master.replace("dvh1.08.01/db4h", "dvh1.08.01/dbx"),
                    master.replace("dvh1.08.01/db4h", "dav1.10.01/db4h"),
                    // well formed, but not what the segments' DOVI descriptor holds
                    master.replace("dvh1.08.01/db4h", "dvh1.08.01/db1p"),
                    master.replace("dvh1.08.01/db4h", "dvh1.08.02/db4h"),
                    master.replace("dvh1.08.01/db4h", "dvh1.08.01"),
                    master.replace(",SUPPLEMENTAL-CODECS=\"dvh1.08.01/db4h\"", ""),
                    master.replace("SUPPLEMENTAL-CODECS=\"dvh1.08.01/db4h\"", "SUPPLEMENTAL-CODECS=dvh1"),
                    // the HLG base layer signaled as another range, or as SDR by the attribute's absence
                    master.replace("VIDEO-RANGE=HLG", "VIDEO-RANGE=PQ"),
                    master.replace("VIDEO-RANGE=HLG", "VIDEO-RANGE=hlg"),
                    master.replace(",VIDEO-RANGE=HLG", ""))) {
                assertFalse(broken.equals(master));
                Files.writeString(file, broken, StandardCharsets.UTF_8);
                assertThrows(AssertionError.class, () -> Validators.assertValidHls(file), broken);
            }
            Files.writeString(file, master, StandardCharsets.UTF_8);
            Validators.assertValidHls(file);
        }
    }
}
