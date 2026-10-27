package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the HTJ2K test files of the ISO/IEC 15444-4:2024 electronic insert: every codestream of the HTJ2K
 * profile 0 and 1 sets, the JPH files and the class 1HF codestream. Each HTJ2K file is a Kakadu transcode of a
 * Part 1 conformance file, with its HT cleanup magnitudes bounded by the {@code b} value of its name; the insert's
 * statistics give, for each component, the peak and mean squared error between the decodes of the two. A transcode
 * the statistics call lossless must decode to exactly the samples of the Part 1 file, and a lossy one within its
 * statistics (with a margin for the reconstruction rules of truncated coefficients). The high-mixed ({@code hm})
 * codestreams mix HT and Part 1 code-blocks in each tile-component.
 */
@DisplayName("HTJ2K conformance (ISO/IEC 15444-4:2024)")
class Jp2HtConformanceTest {
    private static final String ARCHIVE = "jp2-15444-4-ed4-electronic_insert.zip";

    private static final Jp2DecoderOptions OPTIONS = Jp2DecoderOptions.builder()
            .limits(MediaLimits.builder().maxChannels(16384).build()).build();

    static Stream<Arguments> codestreams() {
        return Stream.of(
                Arguments.of("ds0_ht_01_b11", "0", "0"),
                Arguments.of("ds0_ht_02_b11", "1", "0.000992"),
                Arguments.of("ds0_ht_02_b12", "0", "0"),
                Arguments.of("ds0_ht_03_b11", "64", "27.726566"),
                Arguments.of("ds0_ht_03_b14", "0", "0"),
                Arguments.of("ds0_ht_04_b11", "2:2:2", "0.059733:0.053141:0.05429"),
                Arguments.of("ds0_ht_04_b12", "0:0:0", "0:0:0"),
                Arguments.of("ds0_ht_05_b11", "0:1:1:2", "0:0.000081:0.000011:0.000111"),
                Arguments.of("ds0_ht_05_b12", "0:0:0:0", "0:0:0:0"),
                Arguments.of("ds0_ht_06_b11", "2198:0:0:191", "49765.935365:0:0:1677.825416"),
                Arguments.of("ds0_hm_06_b11", "0:0:0:191", "0:0:0:1677.825416"),
                Arguments.of("ds0_ht_06_b15", "0:0:0:11", "0:0:0:6.659204"),
                Arguments.of("ds0_ht_06_b18", "0:0:0:0", "0:0:0:0"),
                Arguments.of("ds0_hm_06_b18", "0:0:0:0", "0:0:0:0"),
                Arguments.of("ds0_ht_07_b11", "39:35:47", "18.864944:17.38511:18.59579"),
                Arguments.of("ds0_ht_07_b15", "2:2:2", "0.047184:0.020661:0.045872"),
                Arguments.of("ds0_ht_07_b16", "0:0:0", "0:0:0"),
                Arguments.of("ds0_ht_08_b11", "38:34:38", "35.28131:20.025873:33.243848"),
                Arguments.of("ds0_ht_08_b15", "0:1:0", "0:0.000466:0"),
                Arguments.of("ds0_ht_08_b16", "0:0:0", "0:0:0"),
                Arguments.of("ds0_ht_09_b11", "0", "0"),
                Arguments.of("ds0_ht_10_b11", "0:0:0", "0:0:0"),
                Arguments.of("ds0_ht_11_b10", "0", "0"),
                Arguments.of("ds0_ht_12_b11", "0", "0"),
                Arguments.of("ds0_ht_13_b11", "0:0:0:0", "0:0:0:0"),
                Arguments.of("ds0_ht_14_b11", "0:0:0", "0:0:0"),
                Arguments.of("ds0_hm_15_b8", "0", "0"),
                Arguments.of("ds0_ht_15_b11", "64", "27.726566"),
                Arguments.of("ds0_ht_15_b14", "0", "0"),
                Arguments.of("ds0_ht_16_b11", "0", "0"),
                Arguments.of("ds1_ht_01_b11", "1", "0.000331"),
                Arguments.of("ds1_ht_01_b12", "0", "0"),
                Arguments.of("ds1_ht_02_b11", "2:2:2", "0.062126:0.054938:0.056699"),
                Arguments.of("ds1_ht_02_b12", "0:0:0", "0:0:0"),
                Arguments.of("ds1_ht_03_b11", "0:1:2:2", "0:0.016738:0.025322:0.000111"),
                Arguments.of("ds1_ht_03_b12", "0:0:0:0", "0:0:0:0"),
                Arguments.of("ds1_ht_04_b9", "0", "0"),
                Arguments.of("ds1_ht_05_b11", "0:0:0", "0:0:0"),
                Arguments.of("ds1_ht_06_b11", "0:0:0", "0:0:0"),
                Arguments.of("ds1_ht_07_b11", "0:0", "0:0")
        );
    }

    static Stream<Arguments> jphFiles() {
        return Stream.of(
                Arguments.of("file1_b11", "1:1:1", "0.001373:0.001048:0.001055"),
                Arguments.of("file1_b12", "0:0:0", "0:0:0"),
                Arguments.of("file2_b11", "1:1:1", "0.004886:0.000739:0.001143"),
                Arguments.of("file2_b12", "0:0:0", "0:0:0"),
                Arguments.of("file3_b11", "1:1:1", "0.004886:0.000026:0.000404"),
                Arguments.of("file3_b12", "0:0:0", "0:0:0"),
                Arguments.of("file4_b11", "1", "0.000415"),
                Arguments.of("file4_b12", "0", "0"),
                Arguments.of("file5_b11", "2:1:1", "0.003746:0.002581:0.002602"),
                Arguments.of("file5_b12", "0:0:0", "0:0:0"),
                Arguments.of("file6_b11", "25", "24.301909"),
                Arguments.of("file6_b15", "0", "0"),
                Arguments.of("file7_b11", "653:569:603", "12309.820302:8565.125329:12184.264617"),
                Arguments.of("file7_b15", "28:23:28", "31.932053:21.380571:32.407776"),
                Arguments.of("file7_b19", "0:0:0", "0:0:0"),
                Arguments.of("file8_b11", "0", "0"),
                Arguments.of("file9_b11", "20:32:54", "0.019368:0.014331:0.029856"),
                Arguments.of("file9_b12", "0:0:0", "0:0:0"),
                Arguments.of("file10_b11", "2:2:2:1:0", "0.062927:0.047226:0.049042:0.03833:0"),
                Arguments.of("file10_b12", "0:0:0:0:0", "0:0:0:0:0")
        );
    }

    static byte[] insert(String path) {
        var archive = Conformance.fetchListed(ARCHIVE);
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry(path);
            if (entry == null) {
                throw new IllegalArgumentException("no " + path + " in " + archive);
            }
            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Jp2Image decode(byte[] data) {
        return Jp2Decoder.decodeChannels(MediaSource.of(data), OPTIONS);
    }

    static void compare(String name, int[][] expected, Jp2Image actual, String peaks, String errors) {
        var peakLimits = peaks.split(":");
        var mseLimits = errors.split(":");
        assertEquals(expected.length, actual.channels().size(), "channel count of " + name);
        var lossless = true;
        for (var c = 0; c < peakLimits.length; c++) {
            lossless &= Long.parseLong(peakLimits[c]) == 0 && Double.parseDouble(mseLimits[c]) == 0;
        }
        // the statistics cover the first components only (4 of the 257 of p0_13); a lossless transcode covers all
        var compared = lossless ? expected.length : peakLimits.length;
        for (var c = 0; c < compared; c++) {
            var samples = actual.channels().get(c).samples();
            var peakLimit = c < peakLimits.length ? Long.parseLong(peakLimits[c]) : 0;
            var mseLimit = c < mseLimits.length ? Double.parseDouble(mseLimits[c]) : 0;
            if (peakLimit == 0 && mseLimit == 0) {
                assertArrayEquals(expected[c], samples, "samples of channel " + c + " of " + name);
                continue;
            }
            assertEquals(expected[c].length, samples.length, "size of channel " + c + " of " + name);
            var peak = Jp2Testing.peak(expected[c], samples);
            var mse = Jp2Testing.mse(expected[c], samples);
            // the statistics come from Kakadu, which reconstructs truncated coefficients its own way
            assertTrue(peak <= peakLimit + peakLimit / 10 + 1, "peak error " + peak + " above " + peakLimit
                    + " in channel " + c + " of " + name);
            assertTrue(mse <= mseLimit * 1.1 + 0.01, "MSE " + mse + " above " + mseLimit + " in channel " + c + " of "
                    + name);
        }
    }

    static int[][] samples(Jp2Image image) {
        return image.channels().stream().map(Jp2Channel::samples).toArray(int[][]::new);
    }

    @Nested
    @DisplayName("HTJ2K profile 0 and 1 codestreams")
    class Codestreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2HtConformanceTest#codestreams")
        @DisplayName("decode as their Part 1 originals do, within the insert's statistics")
        void decodes(String name, String peaks, String errors) {
            var profile = name.charAt(2);
            var number = name.substring(7, 9);
            var set = "files/htj2k_bsets_profile" + profile + "/p" + profile + "_" + number + "_bset/";
            var stream = set + name + ".j2k";
            var original = "files/codestreams_profile" + profile + "/p" + profile + "_" + number + ".j2k";
            var expected = samples(decode(insert(original)));
            compare(name, expected, decode(insert(stream)), peaks, errors);
        }
    }

    @Nested
    @DisplayName("JPH files")
    class JphFiles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2HtConformanceTest#jphFiles")
        @DisplayName("decode as their JP2 originals do, within the insert's statistics")
        void decodes(String name, String peaks, String errors) {
            var actual = decode(insert("files/testfiles_jph/" + name + ".jph"));
            var number = name.substring(4, name.indexOf('_'));
            int[][] expected;
            if (number.equals("10")) {
                // file10 was coded from these sources rather than transcoded from a JP2 file
                expected = new int[actual.channels().size()][];
                for (var c = 0; c < expected.length; c++) {
                    expected[c] = Jp2Testing.pnm(insert("files/reference_jph/jph_10_" + c + ".pgm"))[0].samples();
                }
            } else {
                expected = samples(decode(insert("files/testfiles_jp2/file" + number + ".jp2")));
            }
            compare(name, expected, actual, peaks, errors);
        }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ds0_hm_06_b11", "ds0_hm_15_b8", "ds0_ht_02_b11",
            "ds0_ht_03_b11", "ds0_ht_16_b11"})
    @DisplayName("mutated and truncated mixed, placeholder, region-of-interest and multi-set codestreams fail only with "
            + "media exceptions not caused by a bug")
    void robustness(String name) {
        var profile = name.charAt(2);
        var number = name.substring(7, 9);
        var data = insert("files/htj2k_bsets_profile" + profile + "/p" + profile + "_" + number + "_bset/" + name
                + ".j2k");
        var random = new java.util.Random(name.hashCode());
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(240), () -> {
            for (var round = 0; round < 40; round++) {
                Jp2RobustnessTest.decodeStrictly(java.util.Arrays.copyOf(data, random.nextInt(data.length)));
            }
            for (var round = 0; round < 150; round++) {
                var mutated = data.clone();
                // past the main header, so the packet headers and code-block data take the damage
                var start = Math.min(data.length - 1, 200);
                for (var i = 1 + random.nextInt(8); i > 0; i--) {
                    mutated[start + random.nextInt(data.length - start)] = (byte) random.nextInt(256);
                }
                Jp2RobustnessTest.decodeStrictly(mutated);
            }
        });
    }

    @Test
    @DisplayName("hifi_ht1_02 decodes within the class 1HF bounds of Table C.8")
    void highFidelity() {
        long[] peaks = {43, 33, 38};
        double[] errors = {80, 62, 72};
        var image = decode(insert("files/codestreams_hifi/hifi_ht1_02.j2k"));
        assertEquals(3, image.channels().size());
        for (var c = 0; c < 3; c++) {
            var expected = Jp2Testing.pgx(insert("files/reference_class1HF_profile1/hifi_02-" + c + ".pgx"));
            var actual = image.channels().get(c);
            assertEquals(expected.width(), actual.width(), "width of component " + c);
            assertEquals(expected.height(), actual.height(), "height of component " + c);
            var p = Jp2Testing.peak(expected.samples(), actual.samples());
            var m = Jp2Testing.mse(expected.samples(), actual.samples());
            assertTrue(p <= peaks[c], "peak error " + p + " of component " + c);
            assertTrue(m <= errors[c], "MSE " + m + " of component " + c);
        }
    }
}
