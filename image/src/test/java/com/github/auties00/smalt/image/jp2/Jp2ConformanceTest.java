package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ISO/IEC 15444-4 conformance codestreams (class 1, profiles 0 and 1), the JP2
 * conformance files, and the Kakadu and Richter conformance files of the OpenJPEG test data, and
 * checks every component against the reference decodes within the peak and mean squared errors
 * of the conformance tables; and the files the 2024 edition (Rec. ITU-T T.803 (02/2024)) adds: the class 1HF
 * codestream, the revised JP2 files and the JPX files, while its HTJ2K and Part 2 files must be rejected as
 * unsupported. The bounds are those of Tables C.6, C.7 and C.8 of ISO/IEC 15444-4:2024 (where OpenJPEG's
 * conformance tests list tighter ones, the tighter ones). The class 0 references (Tables C.1 and C.4) are not
 * compared: they are what a decoder working within the class 0 limits of Table A.1 produces (the first tile-part,
 * precinct and progression of each tile, three layers, a cropped corner), which a decoder of the whole codestream
 * does not reproduce.
 */
@DisplayName("JPEG 2000 conformance (ISO/IEC 15444-4)")
class Jp2ConformanceTest {
    // Tables C.2 and C.5 of ISO/IEC 15444-4 as OpenJPEG's tests/conformance/CMakeLists.txt lists them
    private static final int[] P0_COMPONENTS = {1, 1, 1, 3, 4, 4, 3, 3, 1, 3, 1, 1, 4, 3, 1, 1};
    private static final int[] P0_REDUCTION = {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final String[] P0_PEAK = {"0", "0", "0", "5:4:6", "2:2:2:0", "635:403:378:0", "0:0:0", "0:0:0",
            "0", "0:0:0", "0", "0", "0:0:0:0", "0:0:0", "0", "0"};
    private static final String[] P0_MSE = {"0", "0", "0", "0.776:0.626:1.070", "0.302:0.307:0.269:0",
            "11287:6124:3968:0", "0:0:0", "0:0:0", "0", "0:0:0", "0", "0", "0:0:0:0", "0:0:0", "0", "0"};
    private static final int[] P1_COMPONENTS = {1, 3, 4, 1, 3, 3, 2};
    private static final String[] P1_PEAK = {"0", "5:4:6", "2:2:1:0", "624", "40:40:40", "2:2:2", "0:0"};
    // p1_05: Table C.7 of ISO/IEC 15444-4:2024 bounds component 1 at 9.716
    private static final String[] P1_MSE = {"0", "0.765:0.616:1.051", "0.3:0.210:0.200:0", "3080",
            "8.458:9.716:10.154", "0.6:0.6:0.6", "0:0"};

    static Stream<Arguments> profile0() {
        return IntStream.rangeClosed(1, 16).mapToObj(i -> Arguments.of(i));
    }

    static Stream<Arguments> profile1() {
        return IntStream.rangeClosed(1, 7).mapToObj(i -> Arguments.of(i));
    }

    @Nested
    @DisplayName("class 1 codestreams")
    class Class1 {
        @ParameterizedTest(name = "p0_{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2ConformanceTest#profile0")
        @DisplayName("profile 0 decodes within the conformance bounds")
        void profile0(int number) {
            var name = String.format("%02d", number);
            check("input/conformance/p0_" + name + ".j2k", "baseline/conformance/c1p0_" + name,
                    P0_COMPONENTS[number - 1], P0_REDUCTION[number - 1], P0_PEAK[number - 1], P0_MSE[number - 1]);
        }

        @ParameterizedTest(name = "p1_{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2ConformanceTest#profile1")
        @DisplayName("profile 1 decodes within the conformance bounds")
        void profile1(int number) {
            var name = String.format("%02d", number);
            check("input/conformance/p1_" + name + ".j2k", "baseline/conformance/c1p1_" + name,
                    P1_COMPONENTS[number - 1], 0, P1_PEAK[number - 1], P1_MSE[number - 1]);
        }

        private void check(String input, String reference, int components, int reduction, String peaks,
                           String errors) {
            var image = Jp2Decoder.decodeChannels(MediaSource.of(Jp2TestVectors.fetch(input)),
                    Jp2DecoderOptions.builder().reduction(reduction)
                            .limits(MediaLimits.builder().maxChannels(16384).build()).build());
            var peakLimits = peaks.split(":");
            var mseLimits = errors.split(":");
            for (var c = 0; c < components; c++) {
                var expected = Jp2Testing.pgx(Jp2TestVectors.fetch(reference + "_" + c + ".pgx"));
                var actual = image.channels().get(c);
                assertEquals(expected.width(), actual.width(), "width of component " + c);
                assertEquals(expected.height(), actual.height(), "height of component " + c);
                var peak = Jp2Testing.peak(expected.samples(), actual.samples());
                var mse = Jp2Testing.mse(expected.samples(), actual.samples());
                assertTrue(peak <= Long.parseLong(peakLimits[c]), "peak error " + peak + " of component " + c);
                assertTrue(mse <= Double.parseDouble(mseLimits[c]), "MSE " + mse + " of component " + c);
            }
        }
    }

    @Nested
    @DisplayName("ISO/IEC 15444-4:2024 electronic insert")
    class Edition4 {
        @Test
        @DisplayName("hifi_p1_02 decodes within the class 1HF bounds of Table C.8")
        void highFidelity() {
            long[] peaks = {43, 33, 38};
            double[] errors = {80, 62, 72};
            var image = Jp2Decoder.decodeChannels(MediaSource.of(Jp2TestVectors.edition4(
                    "files/codestreams_hifi/hifi_p1_02.j2k")));
            assertEquals(3, image.channels().size());
            for (var c = 0; c < 3; c++) {
                var expected = Jp2Testing.pgx(Jp2TestVectors.edition4("files/reference_class1HF_profile1/hifi_02-" + c
                        + ".pgx"));
                var actual = image.channels().get(c);
                assertEquals(expected.width(), actual.width(), "width of component " + c);
                assertEquals(expected.height(), actual.height(), "height of component " + c);
                var p = Jp2Testing.peak(expected.samples(), actual.samples());
                var m = Jp2Testing.mse(expected.samples(), actual.samples());
                assertTrue(p <= peaks[c], "peak error " + p + " of component " + c);
                assertTrue(m <= errors[c], "MSE " + m + " of component " + c);
            }
        }

        @ParameterizedTest(name = "file{0}.jp2")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("JP2 files of the 2024 edition decode to its reference RGB within peak 4 and MSE 1")
        void jp2(int number) {
            compare(Jp2TestVectors.edition4("files/testfiles_jp2/file" + number + ".jp2"),
                    Jp2TestVectors.edition4("files/reference_jp2/jp2_" + number + ".tif"));
        }

        @Test
        @DisplayName("multi_img.jpx decodes its first compositing layer within peak 4 and MSE 1")
        void firstLayer() {
            compare(Jp2TestVectors.edition4("files/testfiles_jpx/multi_img.jpx"),
                    Jp2TestVectors.edition4("files/reference_jpx/multi_img-0.tif"));
        }

        static Stream<Arguments> unsupported() {
            var files = new java.util.ArrayList<Arguments>();
            for (var path : Jp2TestVectors.edition4Files("files/htj2k_bsets_profile", ".j2k")) {
                files.add(Arguments.of(path, "HTJ2K"));
            }
            for (var path : Jp2TestVectors.edition4Files("files/testfiles_jph/", ".jph")) {
                files.add(Arguments.of(path, "HTJ2K"));
            }
            files.add(Arguments.of("files/codestreams_hifi/hifi_ht1_02.j2k", "HTJ2K"));
            for (var name : new String[]{"mct1", "mct2", "mct3o"}) {
                files.add(Arguments.of("files/codestreams_extended/" + name + ".j2k", "multiple component transforms"));
            }
            files.add(Arguments.of("files/codestreams_extended/nlt1.j2k", "non-linearity point transformations"));
            files.add(Arguments.of("files/testfiles_jpx/float.jpx", "non-linearity point transformations"));
            files.add(Arguments.of("files/testfiles_jpx/reboxing.jpx", "fragment tables"));
            return files.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unsupported")
        @DisplayName("HTJ2K and Part 2 files are rejected as unsupported, naming the feature")
        void unsupported(String path, String feature) {
            var data = Jp2TestVectors.edition4(path);
            var options = Jp2DecoderOptions.builder().limits(MediaLimits.builder().maxChannels(16384).build()).build();
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> Jp2Decoder.decodeChannels(MediaSource.of(data), options));
            assertTrue(failure.getMessage().contains(feature), failure.getMessage());
        }

        private void compare(byte[] file, byte[] tiff) {
            var decoded = Jp2Decoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(true).build());
            var reference = TiffDecoder.decode(MediaSource.of(tiff));
            var actual = (RgbaPicture) decoded.picture();
            var expected = (RgbaPicture) reference.picture();
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            for (var c = 0; c < 3; c++) {
                var e = Jp2Testing.channel(expected, c);
                var a = Jp2Testing.channel(actual, c);
                assertTrue(Jp2Testing.peak(e, a) <= 4, "peak " + Jp2Testing.peak(e, a) + " of channel " + c);
                assertTrue(Jp2Testing.mse(e, a) <= 1, "MSE " + Jp2Testing.mse(e, a) + " of channel " + c);
            }
        }
    }

    @Nested
    @DisplayName("JP2 files")
    class Jp2Files {
        @ParameterizedTest(name = "file{0}.jp2")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("decode to the reference RGB within peak 4 and MSE 1")
        void file(int number) {
            var decoded = Jp2Decoder.decode(MediaSource.of(Jp2TestVectors.fetch("input/conformance/file" + number + ".jp2")),
                    ImageDecoderOptions.builder().convertToSrgb(true).build());
            var reference = TiffDecoder.decode(MediaSource.of(
                    Jp2TestVectors.fetch("baseline/conformance/jp2_" + number + ".tif")));
            var actual = (RgbaPicture) decoded.picture();
            var expected = (RgbaPicture) reference.picture();
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            for (var c = 0; c < 3; c++) {
                var e = Jp2Testing.channel(expected, c);
                var a = Jp2Testing.channel(actual, c);
                assertTrue(Jp2Testing.peak(e, a) <= 4, "peak " + Jp2Testing.peak(e, a) + " of channel " + c);
                assertTrue(Jp2Testing.mse(e, a) <= 1, "MSE " + Jp2Testing.mse(e, a) + " of channel " + c);
            }
        }
    }

    @Nested
    @DisplayName("Kakadu and Richter files")
    class Others {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"a1_mono", "a2_colr", "a3_mono", "a4_colr", "a5_mono", "b1_mono", "b2_mono", "b3_mono",
                "c1_mono", "c2_mono", "d1_colr", "d2_colr", "e1_colr", "e2_colr", "f1_mono", "f2_mono", "g1_colr",
                "g2_colr", "g3_colr", "g4_colr"})
        @DisplayName("Kakadu codestreams decode within peak 4 and MSE 1")
        void kakadu(String name) {
            compare("input/conformance/" + name + ".j2c", "baseline/conformance/" + name + ".ppm", 4, 1);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"subsampling_1", "subsampling_2", "zoo1", "zoo2"})
        @DisplayName("Richter files decode within peak 4 and MSE 2")
        void richter(String name) {
            compare("input/conformance/" + name + ".jp2", "baseline/conformance/" + name + ".ppm", 4, 2);
        }

        @Test
        @DisplayName("a6_mono_colr decodes each of its four components within peak 4 and MSE 1")
        void split() {
            for (var c = 0; c < 4; c++) {
                compareChannels("input/conformance/a6_mono_colr.j2c",
                        new Jp2Testing.Plane[]{Jp2Testing.pnm(Jp2TestVectors.fetch("baseline/conformance/a6_mono_colr_"
                                + c + ".pgm"))[0]}, c, 4, 1);
            }
        }

        private void compare(String input, String reference, long peak, double mse) {
            var expected = Jp2Testing.pnm(Jp2TestVectors.fetch(reference));
            var image = Jp2Decoder.decodeChannels(MediaSource.of(Jp2TestVectors.fetch(input)));
            if (image.colorSpace() != Jp2ColorSpace.SYCC) {
                for (var c = 0; c < expected.length; c++) {
                    compareChannels(input, new Jp2Testing.Plane[]{expected[c]}, c, peak, mse);
                }
                return;
            }
            var picture = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(Jp2TestVectors.fetch(input))).picture();
            for (var c = 0; c < expected.length; c++) {
                var actual = Jp2Testing.channel(picture, c);
                assertEquals(expected[c].width() * expected[c].height(), actual.length);
                assertTrue(Jp2Testing.peak(expected[c].samples(), actual) <= peak);
                assertTrue(Jp2Testing.mse(expected[c].samples(), actual) <= mse);
            }
        }

        private void compareChannels(String input, Jp2Testing.Plane[] expected, int channel, long peak, double mse) {
            var image = Jp2Decoder.decodeChannels(MediaSource.of(Jp2TestVectors.fetch(input)));
            var plane = expected[0];
            var actual = Jp2Testing.upsample(image, image.channels().get(channel), plane.width(), plane.height());
            assertEquals(plane.width() * plane.height(), actual.length);
            var p = Jp2Testing.peak(plane.samples(), actual);
            var m = Jp2Testing.mse(plane.samples(), actual);
            assertTrue(p <= peak, "peak " + p + " of channel " + channel);
            assertTrue(m <= mse, "MSE " + m + " of channel " + channel);
        }
    }
}
