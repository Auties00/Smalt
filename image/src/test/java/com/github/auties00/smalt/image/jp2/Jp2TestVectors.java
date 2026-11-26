package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Pins the files of the OpenJPEG test data repository (uclouvain/openjpeg-data) the JPEG 2000
 * tests use: the ISO/IEC 15444-4 conformance codestreams and JP2 files with their reference
 * decodes, and the Kakadu and Richter conformance files; and the electronic insert of ISO/IEC 15444-4:2024, which
 * adds the high fidelity, HTJ2K, Part 2 and JPX test files.
 */
final class Jp2TestVectors {
    /** The pinned paths whose vector is {@code opj-} followed by their file name. */
    private static final Set<String> PATHS = Set.of(
            "input/nonregression/htj2k/Bretagne1_ht.j2k",
            "input/nonregression/htj2k/Bretagne1_ht_lossy.j2k",
            "input/nonregression/htj2k/byte.jph",
            "input/nonregression/htj2k/byte_causal.jhc",
            "baseline/conformance/a1_mono.ppm",
            "baseline/conformance/a2_colr.ppm",
            "baseline/conformance/a4_colr.ppm",
            "baseline/conformance/a6_mono_colr_0.pgm",
            "baseline/conformance/a6_mono_colr_1.pgm",
            "baseline/conformance/a6_mono_colr_2.pgm",
            "baseline/conformance/a6_mono_colr_3.pgm",
            "baseline/conformance/b2_mono.ppm",
            "baseline/conformance/c0p0_01.pgx",
            "baseline/conformance/c0p0_02.pgx",
            "baseline/conformance/c0p0_03r0.pgx",
            "baseline/conformance/c0p0_03r1.pgx",
            "baseline/conformance/c0p0_04.pgx",
            "baseline/conformance/c0p0_05.pgx",
            "baseline/conformance/c0p0_06.pgx",
            "baseline/conformance/c0p0_07.pgx",
            "baseline/conformance/c0p0_08.pgx",
            "baseline/conformance/c0p0_09.pgx",
            "baseline/conformance/c0p0_10.pgx",
            "baseline/conformance/c0p0_11.pgx",
            "baseline/conformance/c0p0_12.pgx",
            "baseline/conformance/c0p0_13.pgx",
            "baseline/conformance/c0p0_14.pgx",
            "baseline/conformance/c0p0_15r0.pgx",
            "baseline/conformance/c0p0_15r1.pgx",
            "baseline/conformance/c0p0_16.pgx",
            "baseline/conformance/c0p1_01.pgx",
            "baseline/conformance/c0p1_02.pgx",
            "baseline/conformance/c0p1_03.pgx",
            "baseline/conformance/c0p1_04r0.pgx",
            "baseline/conformance/c0p1_04r3.pgx",
            "baseline/conformance/c0p1_05.pgx",
            "baseline/conformance/c0p1_06.pgx",
            "baseline/conformance/c0p1_07.pgx",
            "baseline/conformance/c1p0_01_0.pgx",
            "baseline/conformance/c1p0_02_0.pgx",
            "baseline/conformance/c1p0_03_0.pgx",
            "baseline/conformance/c1p0_04_0.pgx",
            "baseline/conformance/c1p0_04_1.pgx",
            "baseline/conformance/c1p0_04_2.pgx",
            "baseline/conformance/c1p0_05_0.pgx",
            "baseline/conformance/c1p0_05_1.pgx",
            "baseline/conformance/c1p0_05_2.pgx",
            "baseline/conformance/c1p0_05_3.pgx",
            "baseline/conformance/c1p0_06_0.pgx",
            "baseline/conformance/c1p0_06_1.pgx",
            "baseline/conformance/c1p0_06_2.pgx",
            "baseline/conformance/c1p0_06_3.pgx",
            "baseline/conformance/c1p0_07_0.pgx",
            "baseline/conformance/c1p0_07_1.pgx",
            "baseline/conformance/c1p0_07_2.pgx",
            "baseline/conformance/c1p0_08_0.pgx",
            "baseline/conformance/c1p0_08_1.pgx",
            "baseline/conformance/c1p0_08_2.pgx",
            "baseline/conformance/c1p0_09_0.pgx",
            "baseline/conformance/c1p0_10_0.pgx",
            "baseline/conformance/c1p0_10_1.pgx",
            "baseline/conformance/c1p0_10_2.pgx",
            "baseline/conformance/c1p0_11_0.pgx",
            "baseline/conformance/c1p0_12_0.pgx",
            "baseline/conformance/c1p0_13_0.pgx",
            "baseline/conformance/c1p0_13_1.pgx",
            "baseline/conformance/c1p0_13_2.pgx",
            "baseline/conformance/c1p0_13_3.pgx",
            "baseline/conformance/c1p0_14_0.pgx",
            "baseline/conformance/c1p0_14_1.pgx",
            "baseline/conformance/c1p1_01_0.pgx",
            "baseline/conformance/c1p1_02_0.pgx",
            "baseline/conformance/c1p1_02_1.pgx",
            "baseline/conformance/c1p1_02_2.pgx",
            "baseline/conformance/c1p1_03_0.pgx",
            "baseline/conformance/c1p1_03_1.pgx",
            "baseline/conformance/c1p1_03_2.pgx",
            "baseline/conformance/c1p1_04_0.pgx",
            "baseline/conformance/c1p1_05_0.pgx",
            "baseline/conformance/c1p1_05_1.pgx",
            "baseline/conformance/c1p1_05_2.pgx",
            "baseline/conformance/c1p1_06_0.pgx",
            "baseline/conformance/c1p1_06_1.pgx",
            "baseline/conformance/c1p1_06_2.pgx",
            "baseline/conformance/c1p1_07_1.pgx",
            "baseline/conformance/e2_colr.ppm",
            "baseline/conformance/jp2_1.tif",
            "baseline/conformance/jp2_2.tif",
            "baseline/conformance/jp2_3.tif",
            "baseline/conformance/jp2_4.tif",
            "baseline/conformance/jp2_5.tif",
            "baseline/conformance/jp2_6.tif",
            "baseline/conformance/jp2_7.tif",
            "baseline/conformance/jp2_8.tif",
            "baseline/conformance/jp2_9.tif",
            "baseline/conformance/subsampling_1.ppm",
            "baseline/conformance/subsampling_2.ppm",
            "baseline/conformance/zoo1.ppm",
            "baseline/conformance/zoo2.ppm",
            "input/conformance/a1_mono.j2c",
            "input/conformance/a2_colr.j2c",
            "input/conformance/a3_mono.j2c",
            "input/conformance/a4_colr.j2c",
            "input/conformance/a5_mono.j2c",
            "input/conformance/a6_mono_colr.j2c",
            "input/conformance/b1_mono.j2c",
            "input/conformance/b2_mono.j2c",
            "input/conformance/b3_mono.j2c",
            "input/conformance/c1_mono.j2c",
            "input/conformance/c2_mono.j2c",
            "input/conformance/d1_colr.j2c",
            "input/conformance/d2_colr.j2c",
            "input/conformance/e1_colr.j2c",
            "input/conformance/e2_colr.j2c",
            "input/conformance/f1_mono.j2c",
            "input/conformance/f2_mono.j2c",
            "input/conformance/file1.jp2",
            "input/conformance/file2.jp2",
            "input/conformance/file3.jp2",
            "input/conformance/file4.jp2",
            "input/conformance/file5.jp2",
            "input/conformance/file6.jp2",
            "input/conformance/file7.jp2",
            "input/conformance/file8.jp2",
            "input/conformance/file9.jp2",
            "input/conformance/g1_colr.j2c",
            "input/conformance/g2_colr.j2c",
            "input/conformance/g3_colr.j2c",
            "input/conformance/g4_colr.j2c",
            "input/conformance/p0_01.j2k",
            "input/conformance/p0_02.j2k",
            "input/conformance/p0_03.j2k",
            "input/conformance/p0_04.j2k",
            "input/conformance/p0_05.j2k",
            "input/conformance/p0_06.j2k",
            "input/conformance/p0_07.j2k",
            "input/conformance/p0_08.j2k",
            "input/conformance/p0_09.j2k",
            "input/conformance/p0_10.j2k",
            "input/conformance/p0_11.j2k",
            "input/conformance/p0_12.j2k",
            "input/conformance/p0_13.j2k",
            "input/conformance/p0_14.j2k",
            "input/conformance/p0_16.j2k",
            "input/conformance/p1_01.j2k",
            "input/conformance/p1_02.j2k",
            "input/conformance/p1_03.j2k",
            "input/conformance/p1_04.j2k",
            "input/conformance/p1_05.j2k",
            "input/conformance/p1_06.j2k",
            "input/conformance/p1_07.j2k",
            "input/conformance/subsampling_1.jp2",
            "input/conformance/subsampling_2.jp2",
            "input/conformance/zoo1.jp2",
            "input/conformance/zoo2.jp2"
    );

    /** The pinned paths whose bytes equal another pinned file, by the vector that holds them. */
    private static final Map<String, String> DUPLICATES = Map.ofEntries(
            Map.entry("baseline/conformance/a3_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/a5_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/b1_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/b3_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/c1_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/c1p0_14_2.pgx", "opj-c1p0_14_0.pgx"),
            Map.entry("baseline/conformance/c1p0_15_0.pgx", "opj-c1p0_03_0.pgx"),
            Map.entry("baseline/conformance/c1p0_16_0.pgx", "opj-c0p0_16.pgx"),
            Map.entry("baseline/conformance/c1p1_03_3.pgx", "opj-c1p0_05_3.pgx"),
            Map.entry("baseline/conformance/c1p1_07_0.pgx", "opj-c0p1_07.pgx"),
            Map.entry("baseline/conformance/c2_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/d1_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("baseline/conformance/d2_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("baseline/conformance/e1_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("baseline/conformance/f1_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/f2_mono.ppm", "opj-a1_mono.ppm"),
            Map.entry("baseline/conformance/g1_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("baseline/conformance/g2_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("baseline/conformance/g3_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("baseline/conformance/g4_colr.ppm", "opj-a2_colr.ppm"),
            Map.entry("input/conformance/p0_15.j2k", "opj-p0_03.j2k")
    );

    private Jp2TestVectors() {
        throw new AssertionError();
    }

    /** Returns a pinned file, by its path in the repository ({@code input/conformance/p0_01.j2k}). */
    static byte[] vector(String path) {
        var name = DUPLICATES.get(path);
        if (name == null) {
            if (!PATHS.contains(path)) {
                throw new IllegalArgumentException("unpinned vector " + path);
            }
            name = "opj-" + path.substring(path.lastIndexOf('/') + 1);
        }
        var file = Conformance.vector(name);
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns whether a path is pinned. */
    static boolean pinned(String path) {
        return PATHS.contains(path) || DUPLICATES.containsKey(path);
    }

    /**
     * Returns a file of the ISO/IEC 15444-4:2024 (Rec. ITU-T T.803 (02/2024)) electronic insert, by its path in the
     * archive ({@code files/codestreams_profile1/hifi_p1_02.j2k}).
     */
    static byte[] edition4(String path) {
        var archive = Conformance.vector("jp2-15444-4-ed4-electronic_insert.zip");
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
}
