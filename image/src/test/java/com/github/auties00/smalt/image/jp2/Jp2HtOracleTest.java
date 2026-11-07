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
import java.util.List;
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
 * the test checks that OpenJPEG still rejects them, so the list stays exact. Three components with subsampled chroma,
 * which {@code opj_decompress} writes converted to RGB, are compared after the same conversion.
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
        // opj_decompress takes three channels whose luma is not subsampled and whose second channel is for sYCC
        // and writes them converted to RGB
        var converted = channels.size() == 3 && channels.get(1).subsamplingX() != 1
                && channels.get(0).subsamplingX() == channels.get(0).subsamplingY();
        var expected = OpenJpeg.decode(data, "j2k", image.reduction(), layers);
        // OpenJPEG decodes the first layer of ds0_ht_16_b11, a single HT set per code-block
        if (REJECTED.contains(name) && !(name.equals("ds0_ht_16_b11") && layers == 1)) {
            assertTrue(expected == null, "opj_decompress now decodes " + name);
            return;
        }
        if (converted) {
            Skip.unless(syccConvertible(image), "input:opj-rgb",
                    "opj_decompress leaves this sYCC layout or origin unconverted or converts it with edge rules");
            channels = syccToRgb(image);
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

    /**
     * Returns whether {@code opj_decompress} converts an image of three channels with subsampled chroma by
     * replicating each chroma sample over the luma samples it covers: unsigned channels of one precision, 4:2:0 or
     * 4:2:2 chroma, and an image and channels that start on even reference grid coordinates (its
     * {@code sycc420_to_rgb} and {@code sycc422_to_rgb} treat an odd origin with edge rules of their own, and it
     * leaves other layouts unconverted).
     */
    private static boolean syccConvertible(Jp2Image image) {
        var channels = image.channels();
        var luma = channels.getFirst();
        if (luma.subsamplingX() != 1 || luma.subsamplingY() != 1 || (image.x0() & 1) != 0 || (image.y0() & 1) != 0) {
            return false;
        }
        var dy = channels.get(1).subsamplingY();
        for (var channel : channels) {
            if (channel.signed() || channel.precision() != luma.precision() || channel.precision() > 30
                    || (channel != luma && (channel.subsamplingX() != 2 || channel.subsamplingY() != dy
                    || (dy != 1 && dy != 2) || (channel.x0() & 1) != 0 || (channel.y0() & 1) != 0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts the channels of an sYCC image to the RGB channels {@code opj_decompress} writes: chroma replicated to
     * the luma grid and the per-sample formula of OpenJPEG's {@code sycc_to_rgb} ({@code color.c}), which truncates
     * the products of the float differences and clips to the channel precision.
     */
    private static List<Jp2Channel> syccToRgb(Jp2Image image) {
        var luma = image.channels().getFirst();
        var width = luma.width();
        var height = luma.height();
        var y = luma.samples();
        var cb = Jp2Testing.upsample(image, image.channels().get(1), width, height);
        var cr = Jp2Testing.upsample(image, image.channels().get(2), width, height);
        var offset = 1 << (luma.precision() - 1);
        var upb = (1 << luma.precision()) - 1;
        var red = new int[y.length];
        var green = new int[y.length];
        var blue = new int[y.length];
        for (var i = 0; i < y.length; i++) {
            var blueDifference = cb[i] - offset;
            var redDifference = cr[i] - offset;
            red[i] = Math.clamp(y[i] + (int) (1.402 * (float) redDifference), 0, upb);
            green[i] = Math.clamp(y[i] - (int) (0.344 * (float) blueDifference + 0.714 * (float) redDifference), 0,
                    upb);
            blue[i] = Math.clamp(y[i] + (int) (1.772 * (float) blueDifference), 0, upb);
        }
        var result = new ArrayList<Jp2Channel>();
        var planes = List.of(red, green, blue);
        for (var c = 0; c < planes.size(); c++) {
            result.add(new Jp2Channel(luma.x0(), luma.y0(), width, height, 1, 1, luma.precision(), false, luma.type(),
                    c + 1, planes.get(c)));
        }
        return result;
    }
}
