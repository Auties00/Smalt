package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compares the decoded channels with {@code opj_decompress}'s output sample for sample, at full
 * and reduced resolution and with a layer limit, on every conformance file whose components
 * OpenJPEG writes without a color conversion.
 */
@DisplayName("JPEG 2000 decoding matches opj_decompress bit for bit")
class Jp2OracleTest {
    private static final String[] INPUTS = {"p0_01.j2k", "p0_02.j2k", "p0_03.j2k", "p0_04.j2k", "p0_05.j2k",
            "p0_06.j2k", "p0_07.j2k", "p0_08.j2k", "p0_09.j2k", "p0_10.j2k", "p0_11.j2k", "p0_12.j2k", "p0_13.j2k",
            "p0_14.j2k", "p0_15.j2k", "p0_16.j2k", "p1_01.j2k", "p1_02.j2k", "p1_03.j2k", "p1_04.j2k", "p1_05.j2k",
            "p1_06.j2k", "p1_07.j2k", "a1_mono.j2c", "a2_colr.j2c", "a3_mono.j2c", "a4_colr.j2c", "a5_mono.j2c",
            "a6_mono_colr.j2c", "b1_mono.j2c", "b2_mono.j2c", "b3_mono.j2c", "c1_mono.j2c", "c2_mono.j2c",
            "d1_colr.j2c", "d2_colr.j2c", "e1_colr.j2c", "e2_colr.j2c", "f1_mono.j2c", "f2_mono.j2c", "g1_colr.j2c",
            "g2_colr.j2c", "g3_colr.j2c", "g4_colr.j2c", "file1.jp2", "file4.jp2", "file6.jp2", "file9.jp2",
            "subsampling_2.jp2", "zoo2.jp2"};

    static Stream<Arguments> cases() {
        return Stream.of(INPUTS).flatMap(name -> Stream.of(Arguments.of(name, 0, 0), Arguments.of(name, 1, 0),
                Arguments.of(name, 0, 1)));
    }

    @ParameterizedTest(name = "{0} reduction {1} layers {2}")
    @MethodSource("cases")
    @DisplayName("decodes the same samples")
    void same(String name, int reduction, int layers) {
        OpenJpeg.assume("opj_decompress");
        var data = Jp2TestVectors.fetch("input/conformance/" + name);
        var options = Jp2DecoderOptions.builder().reduction(reduction)
                .limits(MediaLimits.builder().maxChannels(16384).build()).build();
        if (layers > 0) {
            options = options.toBuilder().qualityLayers(layers).build();
        }
        var image = Jp2Decoder.decodeChannels(MediaSource.of(data), options);
        var channels = image.channels();
        Assumptions.assumeFalse(channels.size() == 3 && channels.get(1).subsamplingX() != 1
                && channels.get(0).subsamplingX() == channels.get(0).subsamplingY(),
                "opj_decompress converts this image to RGB");
        var extension = name.endsWith(".jp2") ? "jp2" : "j2k";
        var expected = OpenJpeg.decode(data, extension, image.reduction(), layers);
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
