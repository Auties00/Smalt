package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks Fujifilm RAF decoding against Adobe DNG Converter as a black-box oracle: for compressed files (lossless
 * and lossy, Bayer and X-Trans, 14 and 16 bits) and for the bare sensor words of older models (SuperCCD sensors in their
 * staggered layout, transposed or not, and Bayer sensors), every photosite the converter stores must equal the
 * decoded one, and the color filter array and its layout must agree at the place where the converter's image lies
 * within the decoded one. The oracle files are the uncompressed DNG files {@code tools/testdata/raw-oracle.py}
 * writes into {@code <test cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory), or, for
 * the GFX 50S, GFX100S and GFX100 II samples whose DNG files are too large to keep, the script's summaries of them
 * (each sample's SHA-256 over the stored image and its first row, which places the image); the tests are skipped
 * without them. Of the two samples per pixel the converter stores for the two images of SuperCCD SR and EXR sensors,
 * both are compared with the decoded images, the two samples of each photosite, and the converter's own DNG files of
 * them decode to the same images. The digest of each decoded image is pinned too, so that a change shows where the
 * converter's files are missing, and the SuperCCD renderings, turned upright, are compared with each file's own JPEG
 * preview. For those files the script also keeps crops of the converter's demosaiced linear DNG files
 * ({@code <stem>.linear}): the merged readings of each photosite must follow the converter's values band by band of
 * brightness, beyond the clipping of the primary sample, where the primary sample alone strays. The renderings'
 * colors are compared with the camera's own JPEG previews, which settles the order of the filter array records. The
 * black level of every photosite equals the converter's, and the white level measured where the older models'
 * clipped highlights pile up equals its too. One sample (an X10 file in SN mode of rawsamples.ch) is licensed
 * CC BY-NC-SA 4.0 and used for testing only.
 */
class RafOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.cache("raw-oracle").toString()));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_XT2_COMPRESSED, 0", "RAF_XT4_LOSSLESS, 0", "RAF_GFX50SII_LOSSLESS, 0", "RAF_GFX50SII_LOSSY, 0",
            "RAF_S5000, 0", "RAF_S6500FD, 0", "RAF_S100FS, 0", "RAF_S1, 0",
            // the two images of SuperCCD SR and EXR files: the converter's samples in the order of the decoded ones,
            // except that it stores the second image of EXR CMOS sensors first
            "RAF_S5PRO, 0 1", "RAF_X10_SN, 1 0"
    })
    @DisplayName("every photosite Adobe DNG Converter stores equals the decoded one, with the same filter array")
    void photosites(RawSamples sample, String components) {
        compare(Truth.of(sample), Decoded.of(sample), components);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_XT2_COMPRESSED, 0, 16383", "RAF_XT4_LOSSLESS, 0, 16383", "RAF_GFX50SII_LOSSY, 0, 16383",
            "RAF_S5000, 0, 15867", "RAF_S6500FD, 0, 16383", "RAF_S100FS, 0, 16382", "RAF_S1, 0, 4095",
            "RAF_S5PRO, 0 1, 16383", "RAF_X10_SN, 1 0, 4095",
            "RAF_GFX50SII_LOSSLESS, 0, 16383"
    })
    @DisplayName("the black level of every photosite equals the converter's, and the white level is the saturation of "
            + "the photosites, where the converter's lies too when the older models' photosites saturate")
    void levels(RawSamples sample, String components, int white) {
        var name = sample.fileName();
        var path = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle",
                "no oracle file " + path + "; run tools/testdata/raw-oracle.py");
        var dng = OracleDng.read(path);
        var oracle = Truth.of(dng);
        var decoded = Decoded.of(sample);
        var sensor = decoded.file().sensor;
        var mapping = Arrays.stream(components.split(" ")).mapToInt(Integer::parseInt).toArray();
        var offset = locate(decoded.view(mapping[0]), oracle, 0);
        assertNotNull(offset);
        var diagonal = decoded.diagonal();
        var spp = sensor.samplesPerPixel;
        for (var s = 0; s < mapping.length; s++) {
            for (var row = 0; row < 12; row++) {
                for (var column = 0; column < 12; column++) {
                    var y = offset[1] + row;
                    var x = diagonal ? 2 * (offset[0] + column) + mapping[s] : offset[0] + column;
                    var stored = diagonal ? 0 : spp == 2 ? mapping[s] : 0;
                    var ours = sensor.black == null ? 0 : sensor.black[((y % sensor.blackRows) * sensor.blackColumns
                            + x % sensor.blackColumns) * spp + stored];
                    assertEquals(dng.black(column, row, s), ours, "the black level of sample " + s + " at row " + row
                            + ", column " + column);
                }
            }
        }
        // the white level is measured when the photosites are read
        for (var level : sensor.white) {
            assertEquals(white, level, "the white level");
        }
        if (white < (1 << sensor.bitsPerSample) - 1) {
            // the photosites saturate below the bit depth: the converter clips them where they saturate too
            for (var level : dng.whiteLevels()) {
                assertEquals(white, level, 1, "the converter's white level");
            }
        }
    }

    // checks that every sample of the oracle equals a decoded image, the converter's samples taken in the given order
    // of the decoded images, and that the filter colors and layout agree where the oracle's image lies
    private static void compare(Truth oracle, Decoded decoded, String components) {
        var file = decoded.file();
        var mapping = Arrays.stream(components.split(" ")).mapToInt(Integer::parseInt).toArray();
        assertEquals(oracle.samplesPerPixel(), mapping.length, "the samples the oracle stores");
        int[] offset = null;
        for (var sample0 = 0; sample0 < mapping.length; sample0++) {
            var view = decoded.view(mapping[sample0]);
            var found = locate(view, oracle, sample0);
            assertNotNull(found, "the oracle's sample " + sample0 + " matches the decoded image " + mapping[sample0]
                    + " at no offset");
            if (offset != null) {
                assertArrayEquals(offset, found, "both samples lie at the same place");
            }
            offset = found;
        }
        var step = decoded.diagonal() ? 2 : 1;
        for (var row = 0; row < oracle.cfaRows(); row++) {
            for (var column = 0; column < oracle.cfaColumns(); column++) {
                for (var component : mapping) {
                    var color = file.sensor.cfa.color(offset[1] + row, (offset[0] + column) * step
                            + (decoded.diagonal() ? component : 0));
                    assertEquals(oracle.cfa()[row * oracle.cfaColumns() + column], color.code(),
                            "the filter color at row " + row + ", column " + column);
                }
            }
        }
        if (decoded.diagonal()) {
            // the converter keeps the pairs as two samples of a rectangular grid
            assertEquals(1, oracle.layout());
            assertEquals(CfaLayout.STAGGERED_A, file.sensor.layout.shifted(offset[1], 2 * offset[0]));
        } else {
            assertEquals(CfaLayout.of(oracle.layout()), file.sensor.layout.shifted(offset[1], offset[0]));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"RAF_X10_SN"})
    @DisplayName("an oracle summary places and checks the photosites as its DNG file does, and rejects a changed one")
    void summary(RawSamples sample) throws IOException {
        var name = sample.fileName();
        var stem = name.substring(0, name.lastIndexOf('.'));
        var dng = ORACLE.resolve(stem + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle",
                "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        var full = OracleDng.read(dng);
        // the summary the script would write, built from the DNG file by the same rules
        var lines = new ArrayList<String>();
        lines.add("width " + full.width());
        lines.add("height " + full.height());
        lines.add("samples " + full.samplesPerPixel());
        var cfa = new StringBuilder("cfa " + full.cfaColumns() + " " + full.cfaRows());
        for (var color : full.cfa()) {
            cfa.append(' ').append(color);
        }
        lines.add(cfa.toString());
        lines.add("layout " + full.layout());
        for (var s = 0; s < full.samplesPerPixel(); s++) {
            var words = new short[full.width() * full.height()];
            for (var y = 0; y < full.height(); y++) {
                for (var x = 0; x < full.width(); x++) {
                    words[y * full.width() + x] = (short) full.sample(x, y, s);
                }
            }
            lines.add("digest " + s + " " + digest(words, 0, full.width(), 1, full.width(), full.height()));
            var row = new byte[full.width() * 2];
            for (var x = 0; x < full.width(); x++) {
                row[2 * x] = (byte) words[x];
                row[2 * x + 1] = (byte) (words[x] >> 8);
            }
            lines.add("row " + s + " " + HexFormat.of().formatHex(row));
        }
        var summary = Truth.parse(lines);
        var decoded = Decoded.of(sample);
        // the first image, in the even columns of the decoded grid, is the converter's sample 1
        var first = decoded.view(0);
        var fromDng = locate(first, Truth.of(full), 1);
        assertNotNull(fromDng);
        assertArrayEquals(fromDng, locate(first, summary, 1));
        assertNull(locate(first, summary, 0), "the other image is not the first one");
        assertArrayEquals(fromDng, locate(decoded.view(1), summary, 0));
        // one photosite off, deep inside the image: the first row still places it, the digest rejects it
        first.words()[first.index(fromDng[0] + full.width() / 2, fromDng[1] + full.height() / 2)]++;
        assertNull(locate(first, summary, 1), "a changed photosite matches the summary");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_S6500FD, d3c8cccf2f896ae21888fe8d58eff6f91938ee1381bfe344f24b8dd95ab8a253",
            "RAF_XT2_COMPRESSED, a1c28e3a77d228bd2965dfb5473fdff3850707b47264a2bb877b747d9dc47690",
            "RAF_XT4_LOSSLESS, 663d30ff04025a84ef7a550b48d24097c8f29cfcecfad9def613c612e2b9c270",
            "RAF_GFX50SII_LOSSLESS, 463a7a5630a837883652c8f15cc425ea425b30d580da141c34c5d033f79ace4b",
            "RAF_GFX50SII_LOSSY, af7feaaaca02e52371d74913f75902568b72b37e008bb38550f0f2b70a086288",
            "RAF_S5PRO, a62998519aea29d88b447398dc39d56328afcd4147fc97169ecf51dd5c8de841",
            "RAF_S5000, 2f969db812ee7480fc6e87320be0c79930fd1bcf602efbb632babdcdee50819d",
            "RAF_S100FS, c22dce5be27fcb5658d4e994d1d9fa708c16588e15008b28e980c6a00a5798e2",
            "RAF_S1, 68af255b57bc6cf19eede92caba15b1bb88b05ad3a312f61ce8234209ed0f379",
            "RAF_DBP_GX680, 6e39ad0a71472eb69f73c3d3aa9bce20e6515bd2d59fbe6dfea31815e0024709",
            "RAF_X10_SN, c91b425718608cf42cce133347c98492f26c9518864e5f7fe2466a1ad403da4c"
    })
    @DisplayName("the decoded photosites keep the digest of the decoding checked against the converter")
    void digests(RawSamples sample, String sha256) {
        assertEquals(sha256, Decoded.of(sample).view(0).digest());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_S5PRO, e3999821283794795be5c0ce3d9ef7e3bdd9f36d21926efb7b657adedfa3827b",
            "RAF_X10_SN, 15152c2ecffc96ccc9fdb73a67436a8b373f9adcfc4a47c0598558c21cd043b0"
    })
    @DisplayName("the second image of SuperCCD SR and EXR files keeps the digest of the decoding checked against the "
            + "converter")
    void secondDigests(RawSamples sample, String sha256) {
        assertEquals(sha256, Decoded.of(sample).view(1).digest());
    }

    @ParameterizedTest(name = "{0}")
    // the DBP for GX680, which the converter does not convert, stores its sensor as the vertical stripes of eight
    // outputs: its rendering is checked against its preview only
    @EnumSource(value = RawSamples.class, names = {"RAF_S5PRO", "RAF_S5000", "RAF_S6500FD", "RAF_S100FS",
            "RAF_DBP_GX680"})
    @DisplayName("a SuperCCD file, turned upright and scaled to square pixels, renders like its own JPEG preview")
    void superCcdGeometry(RawSamples sample) {
        try (var source = MediaSource.of(sample.path())) {
            var info = RawDecoder.info(source);
            assertEquals(CfaLayout.STAGGERED_A, info.cfaLayout());
            var decoded = RawDecoder.decode(source);
            var rendered = ImageOps.orient(decoded.picture(), decoded.orientation());
            var image = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow()));
            var preview = ImageOps.orient(image.picture(), image.orientation());
            assertEquals((double) preview.width() / preview.height(), (double) rendered.width() / rendered.height(),
                    0.03, "the aspect ratio of " + rendered.width() + "x" + rendered.height());
            var ours = grid(rendered);
            var theirs = grid(preview);
            assertTrue(correlation(ours[1], theirs[1]) > 0.95, "structure " + correlation(ours[1], theirs[1]));
        }
    }

    @ParameterizedTest(name = "{0}")
    // the color filter array of the record 0x0130, read from the last photosite to the first: red and blue swapped,
    // the red-blue contrast turns against the camera's
    @EnumSource(value = RawSamples.class, names = {"RAF_S1"})
    @DisplayName("the rendering, turned upright, has the colors of the camera's own JPEG preview")
    void previewColors(RawSamples sample) {
        try (var source = MediaSource.of(sample.path())) {
            var decoded = RawDecoder.decode(source);
            var rendered = ImageOps.orient(decoded.picture(), decoded.orientation());
            var image = Images.decode(MediaSource.of(RawDecoder.extractPreview(source).orElseThrow()));
            var preview = ImageOps.orient(image.picture(), image.orientation());
            var ours = grid(rendered);
            var theirs = grid(preview);
            var a = new double[ours[0].length];
            var b = new double[a.length];
            for (var index = 0; index < a.length; index++) {
                a[index] = ours[0][index] - ours[2][index];
                b[index] = theirs[0][index] - theirs[2][index];
            }
            assertTrue(correlation(a, b) > 0.8, "red-blue contrast " + correlation(a, b));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"RAF_DBP_GX680"})
    @DisplayName("the eight output stripes of the DBP for GX680 join without seams: same-color photosites across each "
            + "join keep the level they keep inside the stripes")
    void outputStripes(RawSamples sample) throws IOException {
        var decoded = Decoded.of(sample);
        var view = decoded.view(0);
        var black = decoded.file().sensor.black[0];
        // the image area starts 8 rows and 32 stored columns into the stored grid of 3856 rows of 5504 words
        var file = Files.readAllBytes(sample.path());
        var offset = ByteBuffer.wrap(file).getInt(100);
        var unstriped = new short[3840 * 5440];
        for (var y = 0; y < 3840; y++) {
            for (var x = 0; x < 5440; x++) {
                var at = offset + ((y + 8) * 5504 + x + 32) * 2;
                unstriped[y * 5440 + x] = (short) ((file[at] & 0xFF) << 8 | file[at + 1] & 0xFF);
            }
        }
        var asStored = new View(unstriped, 5440, 3840, 5440, 1, 0);
        var largest = 0.0;
        for (var join = 1; join < 8; join++) {
            var column = 688 * join - 32;
            var across = level(view, column + 2, black) / level(view, column - 2, black);
            assertEquals(1, across, 0.02, "the levels across join " + join);
            largest = Math.max(largest, Math.abs(level(asStored, column + 2, black) / level(asStored, column - 2, black)
                    - 1));
        }
        // read as one grid, the words do not join there
        assertTrue(largest > 0.05, "the words read as one grid join within " + largest);
    }

    // the mean level above black of a column of an image's middle rows
    private static double level(View view, int column, double black) {
        var total = 0.0;
        for (var y = 0; y < view.height(); y++) {
            total += view.value(column, y) - black;
        }
        return total / view.height();
    }

    @ParameterizedTest(name = "{0}")
    // the converter's demosaiced image of EXR SN files keeps little of the photosites' detail, so the X10 SN is left
    // out
    @EnumSource(value = RawSamples.class, names = {"RAF_S5PRO"})
    @DisplayName("the merged readings of each photosite follow the converter's demosaiced values through the highlights, "
            + "where the primary sample alone strays")
    void merged(RawSamples sample) {
        var linear = Linear.of(sample);
        var oracle = Truth.of(sample);
        var decoded = Decoded.of(sample);
        var file = decoded.file();
        assertEquals(2, file.sensor.samplesPerPixel);
        var offset = locate(decoded.view(0), oracle, 0);
        if (offset == null) {
            offset = locate(decoded.view(0), oracle, 1);
        }
        assertNotNull(offset);
        var image = LinearImage.of(file.sensor, decoded.plane(), MediaLimits.DEFAULT);
        var merged = DualSamples.merge(image, MediaLimits.DEFAULT);
        // SuperCCD files: the converter's demosaiced grid has twice the rows, odd columns half a row down
        var staggered = oracle.layout() == 2;
        var counts = new long[21];
        var theirs = new double[21];
        var ours = new double[21];
        var primary = new double[21];
        var area = file.sensor.active();
        for (var y = 0; y < image.height; y++) {
            for (var x = 0; x < image.width; x++) {
                var xd = x + area[1] - offset[0];
                var yd = y + area[0] - offset[1];
                var lx = xd;
                var ly = staggered ? 2 * yd + Math.floorMod(xd, 2) : yd;
                if (xd < 0 || yd < 0 || xd >= oracle.width() || yd >= oracle.height() || !linear.contains(lx, ly, 4)) {
                    continue;
                }
                var p = image.data[(y * image.width + x) * 2 + merged.primary()];
                var band = Math.clamp((int) (p * 20), 0, 20);
                counts[band]++;
                theirs[band] += linear.value(lx, ly, image.plane(y, x));
                ours[band] += merged.image().data[y * image.width + x];
                primary[band] += p;
            }
        }
        var reference = 8;
        for (var band = 8; band <= 12; band++) {
            reference = counts[band] > counts[reference] ? band : reference;
        }
        assertTrue(counts[reference] >= 100, "too few mid-tone photosites in the oracle crop");
        var mergedReference = theirs[reference] / ours[reference];
        var primaryReference = theirs[reference] / primary[reference];
        // up to the blend the merged values are the primary sample's; the converter's values bend a little through the
        // highlights, so the bands of the blend and above get a wider tolerance
        var top = -1;
        for (var band = 9; band <= 20; band++) {
            if (counts[band] < 100) {
                continue;
            }
            top = band;
            var relative = theirs[band] / ours[band] / mergedReference;
            assertEquals(1, relative, band < 14 ? 0.08 : 0.12, "the converter's values against the merged ones at band "
                    + band + " of " + counts[band] + " photosites");
        }
        assertTrue(top >= 18, "the oracle crop reaches band " + top + " only");
        // in the brightest band the primary sample alone strays from the converter, the merged values do not
        var mergedError = Math.abs(theirs[top] / ours[top] / mergedReference - 1);
        var primaryError = Math.abs(theirs[top] / primary[top] / primaryReference - 1);
        assertTrue(primaryError > 0.05 && primaryError > 2 * mergedError, "at band " + top + " the primary sample "
                + "strays by " + primaryError + ", the merged values by " + mergedError);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"RAF_S5PRO", "RAF_X10_SN"})
    @DisplayName("the converter's two-sample DNG file of a SuperCCD SR or EXR file decodes to the same images as the RAF "
            + "file")
    void converterDng(RawSamples sample) {
        var name = sample.fileName();
        var path = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                + "; run tools/testdata/raw-oracle.py");
        var raf = Decoded.of(sample);
        RawFile dng;
        SensorPlane plane;
        try (var source = MediaSource.of(path)) {
            dng = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(dng.sensor, dng.unsupported);
            plane = SensorPlane.of(dng.sensor, MediaLimits.DEFAULT);
            dng.sensor.reader.read(plane, MediaLimits.DEFAULT);
        }
        var truth = Truth.of(OracleDng.read(path));
        if (raf.diagonal()) {
            assertEquals(1, dng.sensor.samplesPerPixel);
            assertEquals(CfaLayout.STAGGERED_A, dng.sensor.layout);
            assertEquals(2 * truth.width(), dng.sensor.width);
            var offset = locate(raf.view(0), truth, 1);
            assertNotNull(offset);
            for (var y = 0; y < truth.height(); y++) {
                for (var x = 0; x < 2 * truth.width(); x++) {
                    assertEquals(raf.plane().integers[(y + offset[1]) * raf.plane().width + 2 * offset[0] + x],
                            plane.integers[y * plane.width + x], "the interleaved photosite at " + x + ", " + y);
                }
            }
            for (var row = 0; row < 2; row++) {
                for (var column = 0; column < 4; column++) {
                    assertEquals(raf.file().sensor.cfa.color(offset[1] + row, 2 * offset[0] + column),
                            dng.sensor.cfa.color(row, column));
                }
            }
            return;
        }
        assertEquals(2, dng.sensor.samplesPerPixel);
        assertEquals(raf.file().sensor.layout == CfaLayout.STAGGERED_A, dng.sensor.layout == CfaLayout.STAGGERED_A);
        var dngView = new Decoded(dng, plane);
        int[] offset = null;
        var swapped = false;
        for (var order = 0; order < 2 && offset == null; order++) {
            offset = locate(raf.view(0), truth, order);
            swapped = order == 1;
        }
        assertNotNull(offset);
        var dngFirst = dngView.view(swapped ? 1 : 0);
        var dngSecond = dngView.view(swapped ? 0 : 1);
        for (var y = 0; y < truth.height(); y++) {
            for (var x = 0; x < truth.width(); x++) {
                assertEquals(raf.view(0).value(x + offset[0], y + offset[1]), dngFirst.value(x, y));
                assertEquals(raf.view(1).value(x + offset[0], y + offset[1]), dngSecond.value(x, y));
            }
        }
        var ours = DualSamples.merge(LinearImage.of(raf.file().sensor, raf.plane(), MediaLimits.DEFAULT),
                MediaLimits.DEFAULT);
        var theirs = DualSamples.merge(LinearImage.of(dng.sensor, plane, MediaLimits.DEFAULT), MediaLimits.DEFAULT);
        assertEquals(ours.primary(), swapped ? 1 - theirs.primary() : theirs.primary(), "the primary sample");
    }

    /**
     * A crop of the converter's demosaiced linear DNG file of a sample, written by {@code tools/testdata/raw-oracle.py}
     * as {@code <stem>.linear}.
     *
     * @param width the width of the linear image
     * @param height the height of the linear image
     * @param left the left of the crop
     * @param top the top of the crop
     * @param cropWidth the width of the crop
     * @param cropHeight the height of the crop
     * @param values the crop's red, green and blue values, pixel by pixel
     */
    private record Linear(int width, int height, int left, int top, int cropWidth, int cropHeight, short[] values) {
        static Linear of(RawSamples sample) {
            var name = sample.fileName();
            var path = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".linear");
            Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                    + "; run tools/testdata/raw-oracle.py");
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals("SMALTLIN", new String(bytes, 0, 8, StandardCharsets.US_ASCII));
            assertEquals(1, data.getInt(8));
            var cropWidth = data.getInt(28);
            var cropHeight = data.getInt(32);
            var values = new short[cropWidth * cropHeight * 3];
            data.position(36).slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(values);
            return new Linear(data.getInt(12), data.getInt(16), data.getInt(20), data.getInt(24), cropWidth, cropHeight,
                    values);
        }

        // whether a pixel of the linear image lies in the crop, a margin away from its edges
        boolean contains(int x, int y, int margin) {
            return x >= left + margin && y >= top + margin && x < left + cropWidth - margin
                    && y < top + cropHeight - margin;
        }

        int value(int x, int y, int channel) {
            return values[((y - top) * cropWidth + x - left) * 3 + channel] & 0xFFFF;
        }

        // a channel at a fractional place, interpolated between the four pixels around it
        double bilinear(double x, double y, int channel) {
            var x0 = (int) Math.floor(x);
            var y0 = (int) Math.floor(y);
            var fx = x - x0;
            var fy = y - y0;
            return (1 - fy) * ((1 - fx) * value(x0, y0, channel) + fx * value(x0 + 1, y0, channel))
                    + fy * ((1 - fx) * value(x0, y0 + 1, channel) + fx * value(x0 + 1, y0 + 1, channel));
        }
    }

    // the SHA-256 of a window of 16-bit words, as little-endian words row by row
    static String digest(short[] words, int from, int stride, int step, int width, int height) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            var row = new byte[width * 2];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var word = words[from + y * stride + x * step];
                    row[2 * x] = (byte) word;
                    row[2 * x + 1] = (byte) (word >> 8);
                }
                sha.update(row);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    // the red, green and blue values of a 48 by 32 reduction of a picture
    static double[][] grid(Picture picture) {
        var small = ImageOps.toRgba(ImageOps.resize(picture, 48, 32, ResizeFilter.AREA));
        var values = new double[3][48 * 32];
        var pixels = small.pixels();
        for (var y = 0; y < 32; y++) {
            for (var x = 0; x < 48; x++) {
                for (var channel = 0; channel < 3; channel++) {
                    values[channel][y * 48 + x] = pixels[y * small.stride() + x * 4 + channel] & 0xFF;
                }
            }
        }
        return values;
    }

    static double correlation(double[] a, double[] b) {
        var ma = java.util.Arrays.stream(a).average().orElse(0);
        var mb = java.util.Arrays.stream(b).average().orElse(0);
        var covariance = 0.0;
        var va = 0.0;
        var vb = 0.0;
        for (var index = 0; index < a.length; index++) {
            covariance += (a[index] - ma) * (b[index] - mb);
            va += (a[index] - ma) * (a[index] - ma);
            vb += (b[index] - mb) * (b[index] - mb);
        }
        return covariance / Math.sqrt(va * vb);
    }

    // the offset, of at most 64 photosites each way, at which every given sample of the oracle equals the decoded one
    private static int[] locate(View decoded, Truth oracle, int sample) {
        for (var y = 0; y <= Math.min(64, decoded.height() - oracle.height()); y++) {
            for (var x = 0; x <= Math.min(64, decoded.width() - oracle.width()); x++) {
                if (oracle.matches(decoded, x, y, sample)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    /**
     * One image of a decoded sensor plane: every photosite of a single-image sensor, one of the two samples of each
     * photosite of a two-sample sensor, or one of the two images interleaved in the columns of a staggered grid.
     *
     * @param words the plane's samples
     * @param width the image's width
     * @param height the image's height
     * @param stride the samples between rows
     * @param step the samples between columns
     * @param first the index of the first photosite
     */
    record View(short[] words, int width, int height, int stride, int step, int first) {
        int index(int x, int y) {
            return first + y * stride + x * step;
        }

        int value(int x, int y) {
            return words[index(x, y)] & 0xFFFF;
        }

        String digest() {
            return RafOracleTest.digest(words, first, stride, step, width, height);
        }
    }

    /**
     * A sample's parsed file and stored sensor plane.
     *
     * @param file the parsed file
     * @param plane the stored samples
     */
    record Decoded(RawFile file, SensorPlane plane) {
        static Decoded of(RawSamples sample) {
            try (var source = MediaSource.of(sample.path())) {
                return of(source);
            }
        }

        static Decoded of(byte[] data) {
            try (var source = MediaSource.of(data)) {
                return of(source);
            }
        }

        private static Decoded of(MediaSource source) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            return new Decoded(file, plane);
        }

        // whether the two images are interleaved in the columns of one staggered grid
        boolean diagonal() {
            var sensor = file.sensor;
            return sensor.samplesPerPixel == 1 && sensor.cfa.width() == 4 && sensor.cfa.height() == 2
                    && sensor.layout == CfaLayout.STAGGERED_A && sensor.cfa.color(0, 0) == sensor.cfa.color(0, 1);
        }

        View view(int component) {
            var width = plane.width;
            if (plane.samplesPerPixel == 2) {
                return new View(plane.integers, width, plane.height, 2 * width, 2, component);
            }
            if (diagonal()) {
                return new View(plane.integers, width / 2, plane.height, width, 2, component);
            }
            if (component != 0) {
                throw new AssertionError("a single-image sensor");
            }
            return new View(plane.integers, width, plane.height, width, 1, 0);
        }
    }

    /**
     * The stored image of the converter's DNG file of a sample: the file itself, or the summary
     * {@code tools/testdata/raw-oracle.py} keeps for the largest files (each sample's digest and first row).
     *
     * @param width the stored width
     * @param height the stored height
     * @param samplesPerPixel the samples of each pixel
     * @param cfaColumns the columns of the filter array repeat
     * @param cfaRows the rows of the filter array repeat
     * @param cfa the filter array colors, row by row
     * @param layout the {@code CFALayout} value
     * @param dng the DNG file's image, or {@code null} for a summary
     * @param digests the SHA-256 of each sample over the image, for a summary
     * @param firstRows the first row of each sample, for a summary
     */
    private record Truth(int width, int height, int samplesPerPixel, int cfaColumns, int cfaRows, int[] cfa,
                         int layout, OracleDng dng, String[] digests, short[][] firstRows) {
        static Truth of(RawSamples sample) {
            var name = sample.fileName();
            var stem = name.substring(0, name.lastIndexOf('.'));
            var dng = ORACLE.resolve(stem + ".dng");
            if (Files.isRegularFile(dng)) {
                return of(OracleDng.read(dng));
            }
            var summary = ORACLE.resolve(stem + ".photosites");
            Skip.unless(Files.isRegularFile(summary), "data:raw-oracle",
                    "no oracle file " + dng + " or " + summary + "; run tools/testdata/raw-oracle.py");
            try {
                return parse(Files.readAllLines(summary));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        static Truth of(OracleDng dng) {
            return new Truth(dng.width(), dng.height(), dng.samplesPerPixel(), dng.cfaColumns(), dng.cfaRows(),
                    dng.cfa(), dng.layout(), dng, null, null);
        }

        static Truth parse(List<String> lines) {
            var values = new HashMap<String, String>();
            for (var line : lines) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var space = line.indexOf(' ');
                    values.merge(line.substring(0, space), line.substring(space + 1), (a, b) -> a + "\n" + b);
                }
            }
            var width = Integer.parseInt(values.get("width"));
            var spp = Integer.parseInt(values.get("samples"));
            var cfa = Arrays.stream(values.get("cfa").split(" ")).mapToInt(Integer::parseInt).toArray();
            var digests = new String[spp];
            var rows = new short[spp][];
            for (var line : values.get("digest").split("\n")) {
                var parts = line.split(" ");
                digests[Integer.parseInt(parts[0])] = parts[1];
            }
            for (var line : values.get("row").split("\n")) {
                var parts = line.split(" ");
                var bytes = HexFormat.of().parseHex(parts[1]);
                var row = new short[width];
                for (var x = 0; x < width; x++) {
                    row[x] = (short) ((bytes[2 * x] & 0xFF) | (bytes[2 * x + 1] & 0xFF) << 8);
                }
                rows[Integer.parseInt(parts[0])] = row;
            }
            return new Truth(width, Integer.parseInt(values.get("height")), spp, cfa[0], cfa[1],
                    Arrays.copyOfRange(cfa, 2, cfa.length), Integer.parseInt(values.get("layout")), null, digests,
                    rows);
        }

        // whether the sample of every stored pixel equals the decoded photosite at an offset
        boolean matches(View decoded, int x, int y, int sample) {
            if (dng != null) {
                for (var row = 0; row < height; row++) {
                    for (var column = 0; column < width; column++) {
                        if (decoded.value(x + column, y + row) != dng.sample(column, row, sample)) {
                            return false;
                        }
                    }
                }
                return true;
            }
            var first = firstRows[sample];
            for (var column = 0; column < width; column++) {
                if (decoded.value(x + column, y) != (first[column] & 0xFFFF)) {
                    return false;
                }
            }
            return digests[sample].equals(digest(decoded.words(), decoded.index(x, y), decoded.stride(),
                    decoded.step(), width, height));
        }
    }
}
