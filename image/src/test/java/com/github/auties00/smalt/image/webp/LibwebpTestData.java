package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Names the files of the official libwebp test corpus
 * (https://chromium.googlesource.com/webm/libwebp-test-data, mirrored on GitHub) at commit
 * 06ddd96e276c2c638a72d39d3c0f340afd61978c that the tests read, and reads them through
 * {@link Conformance}.
 */
final class LibwebpTestData {
    static final Set<String> NAMES = Set.of(
            "alpha_color_cache.webp",
            "alpha_filter_0_method_0.webp",
            "alpha_filter_0_method_1.webp",
            "alpha_filter_1.webp",
            "alpha_filter_1_method_0.webp",
            "alpha_filter_1_method_1.webp",
            "alpha_filter_2.webp",
            "alpha_filter_2_method_0.webp",
            "alpha_filter_2_method_1.webp",
            "alpha_filter_3.webp",
            "alpha_filter_3_method_0.webp",
            "alpha_filter_3_method_1.webp",
            "alpha_no_compression.webp",
            "bad_palette_index.webp",
            "big_endian_bug_393.webp",
            "bryce.webp",
            "bug3.webp",
            "color_cache_bits_11.webp",
            "dual_transform.webp",
            "lossless1.webp",
            "lossless2.webp",
            "lossless3.webp",
            "lossless4.webp",
            "lossless_big_random_alpha.webp",
            "lossless_color_transform.webp",
            "lossless_vec_1_0.webp",
            "lossless_vec_1_1.webp",
            "lossless_vec_1_10.webp",
            "lossless_vec_1_11.webp",
            "lossless_vec_1_12.webp",
            "lossless_vec_1_13.webp",
            "lossless_vec_1_14.webp",
            "lossless_vec_1_15.webp",
            "lossless_vec_1_2.webp",
            "lossless_vec_1_3.webp",
            "lossless_vec_1_4.webp",
            "lossless_vec_1_5.webp",
            "lossless_vec_1_6.webp",
            "lossless_vec_1_7.webp",
            "lossless_vec_1_8.webp",
            "lossless_vec_1_9.webp",
            "lossless_vec_2_0.webp",
            "lossless_vec_2_1.webp",
            "lossless_vec_2_10.webp",
            "lossless_vec_2_11.webp",
            "lossless_vec_2_12.webp",
            "lossless_vec_2_13.webp",
            "lossless_vec_2_14.webp",
            "lossless_vec_2_15.webp",
            "lossless_vec_2_2.webp",
            "lossless_vec_2_3.webp",
            "lossless_vec_2_4.webp",
            "lossless_vec_2_5.webp",
            "lossless_vec_2_6.webp",
            "lossless_vec_2_7.webp",
            "lossless_vec_2_8.webp",
            "lossless_vec_2_9.webp",
            "lossy_alpha1.webp",
            "lossy_alpha2.webp",
            "lossy_alpha3.webp",
            "lossy_alpha4.webp",
            "lossy_extreme_probabilities.webp",
            "lossy_q0_f100.webp",
            "near_lossless_75.webp",
            "one_color_no_palette.webp",
            "segment01.webp",
            "segment02.webp",
            "segment03.webp",
            "small_13x1.webp",
            "small_1x1.webp",
            "small_1x13.webp",
            "small_31x13.webp",
            "test-nostrong.webp",
            "test.webp",
            "very_short.webp",
            "vp80-00-comprehensive-001.webp",
            "vp80-00-comprehensive-002.webp",
            "vp80-00-comprehensive-003.webp",
            "vp80-00-comprehensive-004.webp",
            "vp80-00-comprehensive-005.webp",
            "vp80-00-comprehensive-006.webp",
            "vp80-00-comprehensive-007.webp",
            "vp80-00-comprehensive-008.webp",
            "vp80-00-comprehensive-009.webp",
            "vp80-00-comprehensive-010.webp",
            "vp80-00-comprehensive-011.webp",
            "vp80-00-comprehensive-012.webp",
            "vp80-00-comprehensive-013.webp",
            "vp80-00-comprehensive-014.webp",
            "vp80-00-comprehensive-015.webp",
            "vp80-00-comprehensive-016.webp",
            "vp80-00-comprehensive-017.webp",
            "vp80-01-intra-1400.webp",
            "vp80-01-intra-1411.webp",
            "vp80-01-intra-1416.webp",
            "vp80-01-intra-1417.webp",
            "vp80-02-inter-1402.webp",
            "vp80-02-inter-1412.webp",
            "vp80-02-inter-1418.webp",
            "vp80-02-inter-1424.webp",
            "vp80-03-segmentation-1401.webp",
            "vp80-03-segmentation-1403.webp",
            "vp80-03-segmentation-1407.webp",
            "vp80-03-segmentation-1408.webp",
            "vp80-03-segmentation-1409.webp",
            "vp80-03-segmentation-1410.webp",
            "vp80-03-segmentation-1413.webp",
            "vp80-03-segmentation-1414.webp",
            "vp80-03-segmentation-1415.webp",
            "vp80-03-segmentation-1425.webp",
            "vp80-03-segmentation-1426.webp",
            "vp80-03-segmentation-1427.webp",
            "vp80-03-segmentation-1432.webp",
            "vp80-03-segmentation-1435.webp",
            "vp80-03-segmentation-1436.webp",
            "vp80-03-segmentation-1437.webp",
            "vp80-03-segmentation-1441.webp",
            "vp80-03-segmentation-1442.webp",
            "vp80-04-partitions-1404.webp",
            "vp80-04-partitions-1405.webp",
            "vp80-04-partitions-1406.webp",
            "vp80-05-sharpness-1428.webp",
            "vp80-05-sharpness-1429.webp",
            "vp80-05-sharpness-1430.webp",
            "vp80-05-sharpness-1431.webp",
            "vp80-05-sharpness-1433.webp",
            "vp80-05-sharpness-1434.webp",
            "vp80-05-sharpness-1438.webp",
            "vp80-05-sharpness-1439.webp",
            "vp80-05-sharpness-1440.webp",
            "vp80-05-sharpness-1443.webp",
            "grid.pam",
            "peak.pam",
            "lossless_color_transform.pam",
            "libwebp_tests.md5"
    );

    private LibwebpTestData() {
        throw new AssertionError();
    }

    static Path vector(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("unpinned test vector " + name);
        }
        return Conformance.vector("libwebp-test-data-" + name);
    }

    static byte[] read(String name) {
        try {
            return Files.readAllBytes(vector(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the md5 of every dwebp output listed in libwebp_tests.md5, keyed by output file name. */
    static Map<String, String> md5List() {
        var map = new HashMap<String, String>();
        for (var line : new String(read("libwebp_tests.md5"), StandardCharsets.US_ASCII).lines().toList()) {
            var parts = line.trim().split("[ \t]+");
            if (parts.length == 2) {
                map.put(parts[1], parts[0]);
            }
        }
        return map;
    }

    static Stream<String> webpFiles() {
        return NAMES.stream().filter(name -> name.endsWith(".webp")).sorted();
    }
}
