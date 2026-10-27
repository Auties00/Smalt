package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the decoded components of HTJ2K codestreams with {@code opj_decompress}'s output sample for sample, at
 * full and reduced resolution and with a layer limit: the codestreams of the ISO/IEC 15444-4:2024 HTJ2K sets, those
 * of its JPH files, and OpenJPEG's own HTJ2K test files. OpenJPEG 2.5.3 rejects HT code-blocks with placeholder
 * passes, more than one HT set, regions of interest or mixed block coders; for the codestreams that need one of them
 * the test checks that OpenJPEG still rejects them, so the list stays exact.
 */
@DisplayName("HTJ2K decoding matches opj_decompress bit for bit")
class Jp2HtOracleTest {
    // the codestreams opj_decompress 2.5.3 refuses: several quality layers (placeholder passes or more than one HT
    // set), regions of interest, mixed block coders
    private static final Set<String> REJECTED = Set.of("ds0_ht_02_b11", "ds0_ht_02_b12", "ds0_ht_03_b11",
            "ds0_ht_03_b14", "ds0_ht_04_b11", "ds0_ht_04_b12", "ds0_ht_05_b11", "ds0_ht_05_b12", "ds0_ht_06_b11",
            "ds0_hm_06_b11", "ds0_ht_06_b15", "ds0_ht_06_b18", "ds0_hm_06_b18", "ds0_ht_07_b11", "ds0_ht_07_b15",
            "ds0_ht_07_b16", "ds0_ht_08_b11", "ds0_ht_08_b15", "ds0_ht_08_b16", "ds0_ht_10_b11", "ds0_ht_13_b11",
            "ds0_hm_15_b8", "ds0_ht_15_b11", "ds0_ht_15_b14", "ds0_ht_16_b11", "ds1_ht_01_b11", "ds1_ht_01_b12",
            "ds1_ht_02_b11", "ds1_ht_02_b12", "ds1_ht_03_b11", "ds1_ht_03_b12");

    static Stream<Arguments> cases() {
        var names = new ArrayList<String>();
        Jp2HtConformanceTest.codestreams().forEach(arguments -> names.add((String) arguments.get()[0]));
        Jp2HtConformanceTest.jphFiles().forEach(arguments -> names.add((String) arguments.get()[0]));
        names.add("hifi_ht1_02");
        names.add("Bretagne1_ht.j2k");
        names.add("Bretagne1_ht_lossy.j2k");
        names.add("byte.jph");
        names.add("byte_causal.jhc");
        return names.stream().flatMap(name -> Stream.of(Arguments.of(name, 0, 0), Arguments.of(name, 1, 0),
                Arguments.of(name, 0, 1)));
    }

    static byte[] codestream(String name) {
        byte[] data;
        if (name.startsWith("ds")) {
            var profile = name.charAt(2);
            var number = name.substring(7, 9);
            data = Jp2HtConformanceTest.insert("files/htj2k_bsets_profile" + profile + "/p" + profile + "_" + number
                    + "_bset/" + name + ".j2k");
        } else if (name.startsWith("file")) {
            data = Jp2HtConformanceTest.insert("files/testfiles_jph/" + name + ".jph");
        } else if (name.startsWith("hifi")) {
            data = Jp2HtConformanceTest.insert("files/codestreams_hifi/" + name + ".j2k");
        } else {
            data = Jp2TestVectors.fetch("input/nonregression/htj2k/" + name);
        }
        return contiguousCodestream(data);
    }

    /** Returns the codestream of a JP2-family file, or the data itself when it is a codestream. */
    static byte[] contiguousCodestream(byte[] data) {
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F) {
            return data;
        }
        var position = 0;
        while (position + 8 <= data.length) {
            var length = ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            var type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            var header = 8;
            if (length == 1) {
                length = 0;
                for (var i = 0; i < 8; i++) {
                    length = (length << 8) | (data[position + 8 + i] & 0xFF);
                }
                header = 16;
            } else if (length == 0) {
                length = data.length - position;
            }
            if (type.equals("jp2c")) {
                return Arrays.copyOfRange(data, position + header, (int) (position + length));
            }
            position += (int) length;
        }
        throw new IllegalArgumentException("no contiguous codestream box");
    }

    @ParameterizedTest(name = "{0} reduction {1} layers {2}")
    @MethodSource("cases")
    @DisplayName("decodes the same samples")
    void same(String name, int reduction, int layers) {
        OpenJpeg.assume("opj_decompress");
        var data = codestream(name);
        var options = Jp2DecoderOptions.builder().reduction(reduction)
                .limits(MediaLimits.builder().maxChannels(16384).build()).build();
        if (layers > 0) {
            options = options.toBuilder().qualityLayers(layers).build();
        }
        var image = Jp2Decoder.decodeChannels(MediaSource.of(data), options);
        var channels = image.channels();
        Skip.when(channels.size() == 3 && channels.get(1).subsamplingX() != 1
                && channels.get(0).subsamplingX() == channels.get(0).subsamplingY(),
                "input:opj-rgb", "opj_decompress converts this image to RGB");
        var expected = OpenJpeg.decode(data, "j2k", image.reduction(), layers);
        // OpenJPEG decodes the first layer of ds0_ht_16_b11, a single HT set per code-block
        if (REJECTED.contains(name) && !(name.equals("ds0_ht_16_b11") && layers == 1)) {
            assertTrue(expected == null, "opj_decompress now decodes " + name);
            return;
        }
        assertNotNull(expected, "opj_decompress rejected " + name);
        assertEquals(expected.length, channels.size(), "channel count");
        for (var c = 0; c < expected.length; c++) {
            var actual = channels.get(c);
            assertEquals(expected[c].width(), actual.width(), "width of channel " + c);
            assertEquals(expected[c].height(), actual.height(), "height of channel " + c);
            assertArrayEquals(expected[c].samples(), actual.samples(), "samples of channel " + c);
        }
    }
}
