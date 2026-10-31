package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, against Adobe DNG Converter as a black-box oracle, the vendor layouts, levels and curves worked out against
 * it: the photosites, filter array, levels, white balance and image area of the Samsung SRW and Epson ERF samples
 * and of the uncompressed Panasonic RAW files of the oldest models,
 * the VC-5 sensor data of the GoPro GPR samples, the photosites of the Hasselblad FFF samples, those of the CMOS
 * cameras' 3FR samples calibrated within one unit and, up to the converter's calibration, of the CCD cameras' 3FR
 * samples, those of an uncompressed Leaf MOS sample and of the Kodak KDC and
 * DCS samples (lossless JPEG and compression 65000, and the 8-bit EOS DCS 3), with their linearization tables
 * and, within one unit, the DCS520C's black levels,
 * the packed 12-bit photosites of the Sony DSLR-A900 sample, the photosites, levels, image area and orientation of
 * the Mamiya ZD sample and the white balance of copies of it with other maker note values,
 * the green-first Bayer pattern of a CC0 Minolta DiMAGE A2 file whose pattern byte is set to 4, the linearization
 * curves of the lossy Nikon NEF samples entry for entry, and the black and white levels and white balance of the Sony
 * ARW samples. The oracle files are the summaries {@code tools/testdata/raw-oracle.py} keeps of the converter's DNG
 * files: {@code <stem>.photosites} (the stored image's size, filter array, digest and first row) and
 * {@code <stem>.levels} (the camera, image area, crop, filter array, levels, as-shot neutral and linearization table),
 * in {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides it) and, for the ARW samples,
 * which {@code raw-oracle.py --coverage} converts, in {@code <conformance cache>/raw-oracle-coverage}
 * ({@code -Dsmalt.test.rawCoverage}); the tests are skipped without them.
 */
class VendorOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final Path COVERAGE = Path.of(System.getProperty("smalt.test.rawCoverage",
            Conformance.directory().resolve("raw-oracle-coverage").toString()));

    private static final String NONE = "no oracle files";

    /** The SRW samples of Samsung's compressions, which are not decoded. */
    private static final Set<RawSamples> COMPRESSED_SRW = Set.of(RawSamples.SRW_NX1, RawSamples.SRW_NX30,
            RawSamples.SRW_NX3000, RawSamples.SRW_NX300, RawSamples.SRW_NX500_12_NORMAL,
            RawSamples.SRW_NX500_12_LOSSLESS, RawSamples.SRW_NX500_14_NORMAL, RawSamples.SRW_NX500_14_LOSSLESS,
            RawSamples.SRW_NX_MINI, RawSamples.SRW_NX2000, RawSamples.SRW_NX3300, RawSamples.SRW_EK_GN120);

    /**
     * The Sony models whose files are not decoded: the variable-length coding of compression 32767 and the four-color
     * DSC-F828,
     * whose sensor data the parser does not read.
     */
    private static final Set<String> UNDECODED_ARW = Set.of("DSLR-A100", "DSLR-A200", "DSLR-A230", "DSLR-A290",
            "DSLR-A350", "DSLR-A380", "DSLR-A390", "DSC-F828");

    /**
     * The levels summary of a converter's DNG file.
     *
     * @param model the converter's camera model
     * @param width the stored width
     * @param height the stored height
     * @param active the active area: top, left, bottom and right
     * @param crop the default crop relative to the active area: x, y, width and height
     * @param cfa the filter array: columns, rows, then the colors row by row, or {@code null}
     * @param blackRows the rows of the black level repeat
     * @param blackColumns the columns of the black level repeat
     * @param black the black levels
     * @param white the white levels
     * @param neutral the as-shot neutral, or {@code null}
     * @param linearization the linearization table, or {@code null}
     */
    record Levels(String model, int width, int height, int[] active, double[] crop, int[] cfa, int blackRows,
                  int blackColumns, double[] black, double[] white, double[] neutral, int[] linearization) {
        static Levels read(Path path) {
            List<String> lines;
            try {
                lines = Files.readAllLines(path);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var values = new HashMap<String, String>();
            for (var line : lines) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var space = line.indexOf(' ');
                    values.put(space < 0 ? line : line.substring(0, space), space < 0 ? "" : line.substring(space + 1));
                }
            }
            var size = ints(values.get("size"));
            var black = doubles(values.get("black"));
            var linearization = values.containsKey("linearization") ? ints(values.get("linearization")) : null;
            return new Levels(values.get("model"), size[0], size[1], ints(values.get("active")),
                    doubles(values.get("crop")), values.containsKey("cfa") ? ints(values.get("cfa")) : null,
                    (int) black[0], (int) black[1], Arrays.copyOfRange(black, 2, black.length),
                    doubles(values.get("white")), values.containsKey("neutral") ? doubles(values.get("neutral")) : null,
                    linearization == null ? null : Arrays.copyOfRange(linearization, 1, linearization.length));
        }

        private static int[] ints(String text) {
            return Arrays.stream(text.trim().split(" ")).mapToInt(Integer::parseInt).toArray();
        }

        private static double[] doubles(String text) {
            return Arrays.stream(text.trim().split(" ")).mapToDouble(Double::parseDouble).toArray();
        }

        double black(int row, int column) {
            return black[(row % blackRows) * blackColumns + column % blackColumns];
        }
    }

    /**
     * The photosites summary of a converter's DNG file.
     *
     * @param width the stored width
     * @param height the stored height
     * @param digest the SHA-256 of the samples, little-endian words row by row
     * @param firstRow the first row
     */
    record Photosites(int width, int height, String digest, short[] firstRow) {
        static Photosites read(Path path) {
            List<String> lines;
            try {
                lines = Files.readAllLines(path);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var values = new HashMap<String, String>();
            for (var line : lines) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var space = line.indexOf(' ');
                    values.put(line.substring(0, space), line.substring(space + 1));
                }
            }
            if (!values.get("samples").equals("1")) {
                throw new AssertionError("a summary of one sample per pixel expected");
            }
            var width = Integer.parseInt(values.get("width"));
            var bytes = HexFormat.of().parseHex(values.get("row").split(" ")[1]);
            var row = new short[width];
            for (var x = 0; x < width; x++) {
                row[x] = (short) ((bytes[2 * x] & 0xFF) | (bytes[2 * x + 1] & 0xFF) << 8);
            }
            return new Photosites(width, Integer.parseInt(values.get("height")), values.get("digest").split(" ")[1], row);
        }

        // the offset, of at most 128 photosites each way, at which the summary matches the decoded plane
        int[] locate(SensorPlane plane) {
            for (var y = 0; y <= Math.min(128, plane.height - height); y++) {
                for (var x = 0; x <= Math.min(128, plane.width - width); x++) {
                    if (matches(plane, x, y)) {
                        return new int[]{x, y};
                    }
                }
            }
            return null;
        }

        private boolean matches(SensorPlane plane, int x, int y) {
            for (var column = 0; column < width; column++) {
                if (plane.integers[y * plane.width + x + column] != firstRow[column]) {
                    return false;
                }
            }
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
            var row = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var r = 0; r < height; r++) {
                row.clear();
                for (var column = 0; column < width; column++) {
                    row.putShort(plane.integers[(y + r) * plane.width + x + column]);
                }
                sha.update(row.array());
            }
            return digest.equals(HexFormat.of().formatHex(sha.digest()));
        }
    }

    private static Path oracle(Path directory, String stem, String extension) {
        var path = directory.resolve(stem + extension);
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                + "; run tools/testdata/raw-oracle.py");
        return path;
    }

    private static String stem(RawSamples sample) {
        var name = sample.fileName();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static byte[] bytes(RawSamples sample) {
        try {
            return Files.readAllBytes(sample.fetch());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RawFile parse(byte[] data) {
        try (var source = MediaSource.of(data)) {
            return RawParser.parse(source, MediaLimits.DEFAULT);
        }
    }

    private static RawFile parse(Path path) {
        try (var source = MediaSource.of(path)) {
            return RawParser.parse(source, MediaLimits.DEFAULT);
        }
    }

    private static SensorPlane read(RawFile file) {
        assertNotNull(file.sensor, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane;
    }

    // checks the photosites, the filter array, the levels and the white balance of a file against the summaries
    private static int[] check(RawFile file, Photosites photosites, Levels levels) {
        var plane = read(file);
        var offset = photosites.locate(plane);
        assertNotNull(offset, "the converter's photosites match the decoded ones at no offset");
        var sensor = file.sensor;
        var top = offset[1] + levels.active()[0];
        var left = offset[0] + levels.active()[1];
        var cfa = levels.cfa();
        for (var row = 0; row < cfa[1]; row++) {
            for (var column = 0; column < cfa[0]; column++) {
                assertEquals(cfa[2 + row * cfa[0] + column], sensor.cfa.color(top + row, left + column).ordinal(),
                        "the filter array color at " + row + ", " + column);
            }
        }
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 2; column++) {
                var ours = sensor.black == null ? 0 : sensor.black[((top + row) % sensor.blackRows) * sensor.blackColumns
                        + (left + column) % sensor.blackColumns];
                assertEquals(levels.black(row, column), ours, "the black level at " + row + ", " + column);
            }
        }
        assertArrayEquals(levels.white(), sensor.white, "the white level");
        if (levels.neutral() != null && !sensor.estimateBalance) {
            assertArrayEquals(normalized(levels.neutral()), normalized(file.color.asShotNeutral), 1e-4, "the white balance");
        }
        return new int[]{left, top};
    }

    private static double[] normalized(double[] neutral) {
        return new double[]{neutral[0] / neutral[1], 1, neutral[2] / neutral[1]};
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"SRW_EX1", "SRW_WB2000", "SRW_NX5", "SRW_NX10", "SRW_NX11",
            "SRW_NX20", "SRW_NX200", "SRW_NX210", "SRW_NX1000", "SRW_NX1100_4422", "SRW_NX1100_4423", "SRW_NX1100_4424",
            "ERF_R_D1", "ERF_R_D1S", "ERF_R_D1X", "RW2_DMC_FZ8_4_3", "RW2_DMC_FZ50_16_9", "RW2_DMC_LX1_16_9",
            "RW2_DIGILUX_3_4_3", "DCR_EASYSHARE_Z981", "DCR_EASYSHARE_Z990_NAN", "DCR_P880_NAN",
            "DCR_KODAK_P712_ZOOM_DIGITAL_CAMERA_NAN", "DCR_DCS520C_12BIT_3_2", "DCR_DCS760C_12BIT_3_2",
            "DCR_DCS_PRO_14N_12BIT_3_2", "DCR_DCS_PRO_14NX_12BIT_3_2", "DCR_DCS_PRO_SLR_C_12BIT_3_2",
            "DCR_DCS_PRO_SLR_N_12BIT_3_2", "ARW_A900_UNCOMPRESSED_12", "MEF_ZD"})
    @DisplayName("the photosites, filter array, levels and white balance equal the converter's, and the image area "
            + "is the converter's image size within a few photosites of its place")
    void decoding(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        var origin = check(file, photosites, levels);
        var crop = file.sensor.defaultCrop;
        assertNotNull(crop, "a default crop");
        assertEquals(levels.crop()[2], crop[2], "the crop width");
        assertEquals(levels.crop()[3], crop[3], "the crop height");
        assertTrue(Math.abs(origin[0] + levels.crop()[0] - crop[0]) <= 8, "the crop's left edge");
        assertTrue(Math.abs(origin[1] + levels.crop()[1] - crop[1]) <= 8, "the crop's top edge");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"SRW_NX1", "SRW_NX30", "SRW_NX3000", "SRW_NX300",
            "SRW_NX500_12_NORMAL", "SRW_NX500_12_LOSSLESS", "SRW_NX500_14_NORMAL", "SRW_NX500_14_LOSSLESS", "SRW_NX_MINI",
            "SRW_NX2000", "SRW_NX3300", "SRW_EK_GN120"})
    @DisplayName("Samsung's compressed files are described, with the converter's levels and white balance, and not "
            + "decoded")
    void compressed(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var file = parse(bytes(sample));
        assertTrue(COMPRESSED_SRW.contains(sample));
        assertTrue(file.unsupported != null, "a compressed file is not decoded");
        assertNotNull(file.color.asShotNeutral);
        assertArrayEquals(normalized(levels.neutral()), normalized(file.color.asShotNeutral), 1e-4, "the white balance");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"GPR_HERO5_BLACK_16BIT_4_3_936", "GPR_HERO5_BLACK_16BIT_4_3_1634",
            "GPR_HERO6_BLACK_16BIT_4_3_2556", "GPR_HERO6_BLACK_16BIT_4_3_2557", "GPR_HERO6_BLACK_16BIT_4_3_2558",
            "GPR_HERO7_BLACK_16BIT_4_3_2786", "GPR_HERO7_BLACK_16BIT_4_3_2846", "GPR_HERO7_BLACK_16BIT_4_3_2908",
            "GPR_HERO7_BLACK_16BIT_4_3_5766", "GPR_HERO8_BLACK_16BIT_4_3_3949", "GPR_HERO8_BLACK_16BIT_4_3_5767",
            "GPR_HERO9_BLACK_16BIT_4_3", "GPR_HERO10_BLACK_16BIT_4_3_4883", "GPR_HERO10_BLACK_16BIT_4_3_4894",
            "GPR_HERO11_BLACK_16BIT_5_4", "GPR_FUSION_16BIT_1_1_7370", "GPR_FUSION_16BIT_1_1_7371"})
    @DisplayName("a GoPro GPR file's VC-5 sensor data decodes to the converter's photosites, with its filter array "
            + "and levels")
    void gpr(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        assertEquals("VC-5", file.sensor.compression);
        var origin = check(file, photosites, levels);
        assertArrayEquals(new int[]{0, 0}, origin, "the converter's image is the whole stored image");
    }

    @Test
    @DisplayName("a Kodak DCS520C file's linearization table equals the converter's, and its black level of each "
            + "photosite is the converter's within one unit")
    void dcs520c() {
        var sample = RawSamples.DCR_DCS520C_12BIT_3_2;
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var file = parse(bytes(sample));
        assertArrayEquals(levels.linearization(), file.sensor.linearization, "the linearization table");
        // the converter's BlackLevelDeltaH lies between 176.996 and 177 in every column and its BlackLevelDeltaV
        // between 0 and 1 in every row (read from its DNG file, which the summaries do not keep); the file's
        // integer levels of each column, 176 or 177, and of each row, 0 or 1, are within one unit of their sum
        assertEquals(file.sensor.width, file.sensor.blackDeltaH.length);
        for (var delta : file.sensor.blackDeltaH) {
            assertTrue(delta == 176 || delta == 177, "the black level of a column is " + delta);
        }
        if (file.sensor.blackDeltaV != null) {
            for (var delta : file.sensor.blackDeltaV) {
                assertTrue(delta >= 0 && delta <= 1, "the black level of a row is " + delta);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"DCR_DCS760C_12BIT_3_2", "DCR_DCS_PRO_14N_12BIT_3_2",
            "DCR_DCS_PRO_14NX_12BIT_3_2", "DCR_DCS_PRO_SLR_C_12BIT_3_2", "DCR_DCS_PRO_SLR_N_12BIT_3_2"})
    @DisplayName("a Kodak DCS file of compression 65000 has the converter's linearization table, or none where the "
            + "converter's file has none")
    void kodakLinearization(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var file = parse(bytes(sample));
        assertEquals("Kodak 65000", file.sensor.compression);
        assertArrayEquals(levels.linearization(), file.sensor.linearization, "the linearization table");
    }

    @Test
    @DisplayName("a Kodak EOS DCS 3 file decodes to the converter's 8-bit photosites, with its filter array, image "
            + "area, linearization table, white level and black level")
    void eosDcs() throws NoSuchAlgorithmException {
        var sample = RawSamples.DCR_EOS_DCS_3_8BIT_4_3;
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var file = parse(bytes(sample));
        var plane = read(file);
        // the SHA-256 of the 8-bit image of the converter's DNG file, which raw-oracle.py keeps only as levels
        var stored = new byte[plane.width * plane.height];
        for (var index = 0; index < stored.length; index++) {
            stored[index] = (byte) plane.integers[index];
        }
        assertEquals("a2bb20b47f179ca31cc532ba3d48838e0631b28ff00cdf8f4fc5ba17a970785e",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored)), "the photosites");
        assertEquals(levels.width(), plane.width);
        assertEquals(levels.height(), plane.height);
        assertArrayEquals(levels.active(), file.sensor.active(), "the image area");
        var cfa = levels.cfa();
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 2; column++) {
                assertEquals(cfa[2 + row * 2 + column], file.sensor.cfa.color(levels.active()[0] + row,
                        levels.active()[1] + column).ordinal(), "the filter array color at " + row + ", " + column);
            }
        }
        assertArrayEquals(levels.linearization(), file.sensor.linearization, "the linearization table");
        assertArrayEquals(levels.white(), file.sensor.white, "the white level");
        assertArrayEquals(levels.black(), file.sensor.black, "the black level");
    }

    @Test
    @DisplayName("an uncompressed Leaf MOS file decodes to the converter's photosites, filter array, levels, white "
            + "balance and orientation")
    void mos() {
        var sample = RawSamples.MOS_APTUS_22_16BIT_4_3_3197;
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        assertEquals(levels.model(), file.make + " " + file.model);
        assertEquals(6, file.orientation, "the converter's orientation");
        assertArrayEquals(new int[]{0, 0}, check(file, photosites, levels), "the converter's image is the stored one");
    }

    @Test
    @DisplayName("the Mamiya ZD file has the converter's orientation and image area")
    void mef() {
        var sample = RawSamples.MEF_ZD;
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        assertEquals(RawFormat.MEF, file.format);
        assertEquals(6, file.orientation, "the converter's orientation");
        var origin = check(file, photosites, levels);
        assertArrayEquals(new double[]{origin[0] + levels.crop()[0], origin[1] + levels.crop()[1], levels.crop()[2],
                levels.crop()[3]}, file.sensor.defaultCrop, "the crop");
    }

    /**
     * The copies of the Mamiya ZD sample with maker note values changed, as {@code tools/testdata/raw-oracle.py}
     * converts them: the name, the absolute offset of the change and the bytes written there.
     */
    private static final List<Object[]> MEF_COPIES = List.of(
            new Object[]{"mode-5-fine-2", 7402, "0005000000a30003000000010002"},
            new Object[]{"mode-6-fine-minus-3", 7402, "0006000000a30003000000010007"},
            new Object[]{"mode-12", 7402, "000c"},
            new Object[]{"temperature-4000", 7402, "0028000000a30003000000010001"},
            new Object[]{"no-red-multiplier", 100392, "00000000"});

    static Stream<String> mefCopies() {
        return MEF_COPIES.stream().map(copy -> (String) copy[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mefCopies")
    @DisplayName("a copy of the Mamiya ZD file with another white balance mode, fine-tuning or camera multiplier has "
            + "the converter's white balance")
    void mefBalance(String name) {
        var levels = Levels.read(oracle(ORACLE, "patched-mamiya-zd-" + name, ".levels"));
        var copy = MEF_COPIES.stream().filter(entry -> entry[0].equals(name)).findFirst().orElseThrow();
        var data = bytes(RawSamples.MEF_ZD);
        var change = HexFormat.of().parseHex((String) copy[2]);
        System.arraycopy(change, 0, data, (int) copy[1], change.length);
        var file = parse(data);
        assertNotNull(file.color.asShotNeutral, "a white balance");
        assertArrayEquals(normalized(levels.neutral()), normalized(file.color.asShotNeutral), 1e-5, "the white balance");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"FFF_H4D_40_16BIT_4_3", "FFF_CFV_50C_16BIT_4_3_7199",
            "FFF_CFV_50C_16BIT_4_3_7218"})
    @DisplayName("a Hasselblad FFF file decodes to the converter's photosites, filter array, levels and white "
            + "balance, with the converter's crop")
    void fff(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        assertEquals(HasselbladParser.COMPRESSION, file.sensor.compression);
        var origin = check(file, photosites, levels);
        assertArrayEquals(new double[]{origin[0] + levels.crop()[0], origin[1] + levels.crop()[1], levels.crop()[2],
                levels.crop()[3]}, file.sensor.defaultCrop, "the crop");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"FFF_CFV_50C_16BIT_4_3", "FFF_X1D_16BIT_4_3",
            "FFF_X1D_II_50C_16BIT_4_3", "FFF_CFV_100C_16BIT_4_3"})
    @DisplayName("a CMOS camera's 3FR file decodes, calibrated, to the converter's photosites, with its levels, crop, "
            + "filter array and white balance")
    void calibratedThreeFr(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        var plane = read(file);
        var origin = threeFrOrigin(file, levels);
        var left = origin[0];
        var top = origin[1];
        assertEquals(levels.black(0, 0), file.sensor.black[0], "the black level");
        assertArrayEquals(levels.white(), file.sensor.white, "the white level");
        var row = photosites.firstRow();
        for (var column = 0; column < row.length; column++) {
            assertEquals(row[column] & 0xFFFF, plane.integers[top * plane.width + left + column] & 0xFFFF,
                    "the photosite at column " + column);
        }
        assertTrue(photosites.matches(plane, left, top), "every photosite equals the converter's");
        threeFrColors(file, levels, left, top);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"FFF_H3D_16BIT_4_3", "FFF_HASSELBLAD_H4D_16BIT_4_3"})
    @DisplayName("a CCD camera's 3FR file decodes, calibrated, to the converter's photosites, its defective columns "
            + "included (the H4D sample's first row, where its offset ramps are zero), with its levels, filter array "
            + "and white balance")
    void threeFr(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, stem(sample), ".photosites"));
        var file = parse(bytes(sample));
        var plane = read(file);
        var origin = threeFrOrigin(file, levels);
        assertEquals(levels.black(0, 0), file.sensor.black == null ? 0 : file.sensor.black[0], "the black level");
        assertArrayEquals(levels.white(), file.sensor.white, "the white level");
        // the first row crosses the H3D sample's four defective columns; the H4D sample differs elsewhere by the
        // offset ramps its first row does not have
        var row = photosites.firstRow();
        for (var column = 0; column < row.length; column++) {
            assertEquals(row[column] & 0xFFFF, plane.integers[origin[1] * plane.width + origin[0] + column] & 0xFFFF,
                    "the photosite at column " + column);
        }
        if (sample == RawSamples.FFF_H3D_16BIT_4_3) {
            assertTrue(photosites.matches(plane, origin[0], origin[1]), "every photosite equals the converter's");
        }
        threeFrColors(file, levels, origin[0], origin[1]);
    }

    // the position in the decoded plane of the converter's first photosite, from the crops, which must agree in size
    private static int[] threeFrOrigin(RawFile file, Levels levels) {
        var crop = file.sensor.defaultCrop;
        assertNotNull(crop, "a default crop");
        assertEquals(levels.crop()[2], crop[2], "the crop width");
        assertEquals(levels.crop()[3], crop[3], "the crop height");
        return new int[]{(int) (crop[0] - levels.crop()[0]), (int) (crop[1] - levels.crop()[1])};
    }

    // checks the filter array and the white balance of a 3FR file against the converter's
    private static void threeFrColors(RawFile file, Levels levels, int left, int top) {
        var cfa = levels.cfa();
        for (var row = 0; row < cfa[1]; row++) {
            for (var column = 0; column < cfa[0]; column++) {
                assertEquals(cfa[2 + row * cfa[0] + column], file.sensor.cfa.color(top + row, left + column).ordinal(),
                        "the filter array color at " + row + ", " + column);
            }
        }
        assertArrayEquals(normalized(levels.neutral()), normalized(file.color.asShotNeutral), 1e-4, "the white balance");
    }

    @Test
    @DisplayName("a DiMAGE A2 file whose Bayer pattern byte is 4 decodes to the converter's photosites, green-first "
            + "filter array, crop and white balance")
    void greenFirstMrw() throws IOException {
        var levels = Levels.read(oracle(ORACLE, "patched-minolta-dimage-a2-gbrg", ".levels"));
        var photosites = Photosites.read(oracle(ORACLE, "patched-minolta-dimage-a2-gbrg", ".photosites"));
        var data = MrwSamples.greenFirst(Files.readAllBytes(RawSamples.MRW_DIMAGE_A2.fetch()));
        var file = parse(data);
        assertEquals("GBRG", file.sensor.cfa.toString());
        var origin = check(file, photosites, levels);
        assertArrayEquals(new double[]{origin[0] + levels.crop()[0], origin[1] + levels.crop()[1], levels.crop()[2],
                levels.crop()[3]}, file.sensor.defaultCrop, "the crop");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"NEF_D70S_LOSSY_12", "NEF_D7000_LOSSY_12", "NEF_D7000_LOSSY_14_SPLIT",
            "NEF_D5600_LOSSY_12_SPLIT", "NEF_Z6II_LOSSY_12_SPLIT", "NEF_1_J1_LOSSY_12", "NEF_COOLPIX_A_LOSSY_14",
            "NEF_D3_LOSSY_12", "NEF_D3_LOSSY_14", "NEF_D5_LOSSY_14", "NEF_D500_LOSSY_12", "NEF_D750_LOSSY_14",
            "NEF_D810_LOSSY_12", "NEF_D850_LOSSY_14", "NEF_D90_LOSSY_12", "NEF_DF_LOSSY_14", "NEF_D6_LOSSY_12",
            "NEF_D6_LOSSY_14", "NEF_D780_LOSSY_12", "NEF_D780_LOSSY_14", "NEF_Z5_LOSSY_12", "NEF_Z5_LOSSY_14",
            "NEF_Z6II_LOSSY_14", "NEF_Z7II_LOSSY_12", "NEF_Z7II_LOSSY_14", "NEF_D300_LOSSY_12", "NEF_D700_LOSSY_12",
            "NEF_D3S_LOSSY_12", "NEF_D300S_LOSSY_12", "NEF_D4_LOSSY_12", "NEF_D3100_LOSSY_12", "NEF_D5000_LOSSY_12"})
    @DisplayName("the linearization curve equals the converter's linearization table entry for entry")
    void nefCurve(RawSamples sample) {
        var levels = Levels.read(oracle(ORACLE, stem(sample), ".levels"));
        var table = levels.linearization();
        assertNotNull(table, "the converter's file has a linearization table");
        try (var source = MediaSource.of(sample.fetch())) {
            var reader = TiffReader.open(source, 0, MediaLimits.DEFAULT);
            var tree = new TiffTree(reader);
            var model = tree.first.string(272);
            var curve = NefParser.compressed(reader, TiffEpParser.rawDirectory(tree), NefParser.makerNote(tree), model)
                    .curve();
            for (var index = 0; index < curve.length; index++) {
                var expected = table[Math.min(index, table.length - 1)];
                if (curve[index] != expected) {
                    throw new AssertionError("curve entry " + index + " is " + curve[index] + ", the converter's "
                            + expected);
                }
            }
        }
    }

    static Stream<String> arwLevels() throws IOException {
        if (!Files.isDirectory(COVERAGE)) {
            return Stream.of(NONE);
        }
        try (var files = Files.list(COVERAGE)) {
            var names = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("rawpixls-sony-") && name.endsWith(".levels"))
                    .sorted()
                    .toList();
            return names.isEmpty() ? Stream.of(NONE) : names.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arwLevels")
    @DisplayName("an ARW file's black and white levels equal the converter's, and so does its white balance where "
            + "the file records it readably")
    void arw(String levelsName) {
        Skip.unless(!levelsName.equals(NONE), "data:raw-oracle-coverage", "no ARW levels files in " + COVERAGE
                + "; run tools/testdata/raw-oracle.py --coverage");
        var stem = levelsName.substring(0, levelsName.length() - ".levels".length());
        var raw = Stream.of(".arw", ".sr2", ".srf")
                .map(extension -> Conformance.directory().resolve(stem + extension))
                .filter(Files::isRegularFile)
                .findFirst();
        Skip.unless(raw.isPresent(), "data:vector", "no sample of " + levelsName);
        var levels = Levels.read(COVERAGE.resolve(levelsName));
        var file = parse(raw.get());
        if (levels.cfa() == null) {
            // the converter stores the YCbCr images of the smaller lossless sizes as linear raw data, which this
            // parser does not read
            assertTrue(file.sensor == null, "a file the converter stores as linear raw data is not decoded");
            return;
        }
        if (file.sensor == null) {
            assertTrue(UNDECODED_ARW.contains(file.model), file.model + ": " + file.unsupported);
            return;
        }
        var sensor = file.sensor;
        var active = sensor.active();
        var top = active[0];
        var left = active[1];
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 2; column++) {
                var ours = sensor.black == null ? 0 : sensor.black[((top + row) % sensor.blackRows) * sensor.blackColumns
                        + (left + column) % sensor.blackColumns];
                assertEquals(levels.black(row, column), ours, "the black level at " + row + ", " + column);
            }
        }
        assertArrayEquals(levels.white(), sensor.white, "the white level");
        if (!sensor.estimateBalance) {
            assertArrayEquals(normalized(levels.neutral()), normalized(file.color.asShotNeutral), 1e-4,
                    "the white balance");
        }
    }

    /**
     * Copies of a CC0 Minolta MRW sample with another Bayer pattern byte.
     */
    static final class MrwSamples {
        private MrwSamples() {
        }

        // the file with the picture description's Bayer pattern byte set to 4 (green, blue, red, green)
        static byte[] greenFirst(byte[] data) {
            var copy = data.clone();
            var header = ByteBuffer.wrap(copy).getInt(4);
            var position = 8;
            while (position + 8 <= 8 + header) {
                var name = ByteBuffer.wrap(copy).getInt(position);
                var length = ByteBuffer.wrap(copy).getInt(position + 4);
                if (name == 0x00505244) {
                    copy[position + 8 + 23] = 4;
                    return copy;
                }
                position += 8 + length;
            }
            throw new AssertionError("no picture description block");
        }
    }
}
