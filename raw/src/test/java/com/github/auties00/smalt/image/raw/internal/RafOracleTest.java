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
 * writes into {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory), or, for
 * the GFX 50S, GFX100S and GFX100 II samples whose DNG files are too large to keep, the script's summaries of them
 * (each sample's SHA-256 over the stored image and its first row, which places the image); the tests are skipped
 * without them. Of the two samples per pixel the converter stores for the two images of SuperCCD SR and EXR sensors,
 * both are compared with the decoded images: the two samples of each photosite, or, for EXR HR files, the two images
 * interleaved in the columns of one staggered grid, and the converter's own DNG files of them decode to the same
 * images. The digest of each decoded image is pinned too, so that a change shows where the converter's files are
 * missing, and the SuperCCD renderings, turned upright, are compared with each file's own JPEG preview. For those
 * files the script also keeps crops of the converter's demosaiced linear DNG files ({@code <stem>.linear}): the
 * merged readings of each photosite must follow the converter's values band by band of brightness, beyond the
 * clipping of the primary sample, where the primary sample alone strays, and the two images of EXR HR files must
 * correlate with the converter's values at the places the interleaving gives them better than at the other
 * photosite of their pair, each place carried through the lens correction (WarpRectilinear) the converter applies to
 * some of them. The two equal readings of EXR SN files, whose demosaiced linear files the converter processes beyond
 * use, are checked on the file itself: the merged values keep each reading's level and about half its noise. The
 * renderings' colors are compared with the camera's own JPEG previews, which settles the order of the filter array
 * records. The black level of every photosite equals the converter's, and the white level measured where the older
 * models' clipped highlights pile up equals its too. Of the F710 and F810 no file is published: the converter's entries of them must read copies of the F700
 * and E550 samples named after them (and refuse the swapped copies), to the photosites decoded from those copies.
 * Some samples (F700, E550, E900, S5600, HS10, F600EXR, F900EXR, HS20EXR of raw.pixls.us, an S200EXR
 * file in HR mode and an X10 file in SN mode of rawsamples.ch) are licensed CC BY-NC-SA 4.0 and only downloaded for
 * testing.
 */
class RafOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_XT2_COMPRESSED, 0", "RAF_XT4_LOSSLESS, 0", "RAF_XE5_LOSSLESS, 0", "RAF_GFX50SII_LOSSLESS, 0",
            "RAF_GFX50S_COMPRESSED, 0", "RAF_GFX100S_16BIT_LOSSLESS, 0", "RAF_XE5_LOSSY, 0", "RAF_XT4_LOSSY, 0",
            "RAF_GFX50SII_LOSSY, 0", "RAF_GFX100II_16BIT_LOSSY, 0", "RAF_S2PRO, 0", "RAF_S5000, 0", "RAF_S5200, 0",
            "RAF_S5500, 0", "RAF_S6000FD, 0", "RAF_S6500FD, 0", "RAF_S7000, 0", "RAF_S9500, 0", "RAF_S9600, 0",
            "RAF_S100FS, 0", "RAF_S1, 0", "RAF_SL1000, 0", "RAF_E550, 0", "RAF_E900, 0", "RAF_S5600, 0",
            "RAF_HS10, 0",
            // the two images of SuperCCD SR and EXR files: the converter's samples in the order of the decoded ones,
            // except that it stores the second image of EXR CMOS sensors first
            "RAF_S3PRO, 0 1", "RAF_S5PRO, 0 1", "RAF_F700, 0 1", "RAF_S200EXR, 0 1", "RAF_F550EXR, 1 0",
            "RAF_F770EXR, 1 0", "RAF_X10, 1 0", "RAF_F600EXR, 1 0", "RAF_F900EXR, 1 0", "RAF_HS20EXR, 1 0",
            // the S200EXR in HR mode: its second image, which takes the even columns, is the converter's sample 1
            "RAF_S200EXR_HR, 1 0", "RAF_X10_SN, 1 0"
    })
    @DisplayName("every photosite Adobe DNG Converter stores equals the decoded one, with the same filter array")
    void photosites(RawSamples sample, String components) {
        compare(Truth.of(sample), Decoded.of(sample), components);
    }

    @ParameterizedTest(name = "{2}")
    @CsvSource({
            "RAF_F700, FinePix F700, FinePix F710, renamed-fujifilm-finepix-f710, renamed-fujifilm-finepix-e550-as-f710, "
                    + "0 1",
            "RAF_E550, FinePix E550, FinePix F810, renamed-fujifilm-finepix-f810, renamed-fujifilm-finepix-f700-as-f810, 0"
    })
    @DisplayName("the converter's entry of a model of which no file is published reads its sibling's file, renamed, to "
            + "the decoded photosites, with the sibling's color matrices, and refuses the other sibling's")
    void siblings(RawSamples sample, String model, String sibling, String stem, String refused, String components)
            throws IOException {
        var bytes = Files.readAllBytes(sample.fetch());
        var path = ORACLE.resolve(stem + ".dng");
        var name = sample.fileName();
        var original = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(path) && Files.isRegularFile(original), "data:raw-oracle",
                "no oracle file " + path + " or " + original + "; run tools/testdata/raw-oracle.py");
        var dng = OracleDng.read(path);
        assertEquals("Fujifilm " + sibling, dng.uniqueCameraModel(), "the camera the converter takes the file for");
        var matrices = OracleDng.read(original).colorMatrices();
        assertEquals(18, matrices.length, "the sibling's two color matrices");
        assertArrayEquals(matrices, dng.colorMatrices(), "the converter's color matrices of the two models");
        assertTrue(Files.isRegularFile(ORACLE.resolve(refused + ".refused")), "the converter refuses " + refused);
        assertTrue(Files.notExists(ORACLE.resolve(refused + ".dng")), "the converter writes no file of " + refused);
        var from = model.getBytes(StandardCharsets.US_ASCII);
        var to = sibling.getBytes(StandardCharsets.US_ASCII);
        var replaced = 0;
        for (var at = 0; at + from.length <= bytes.length; at++) {
            if (Arrays.equals(bytes, at, at + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, bytes, at, to.length);
                replaced++;
            }
        }
        assertTrue(replaced >= 2, "the model name in the header and in the Exif data");
        var decoded = Decoded.of(bytes);
        assertEquals(sibling, decoded.file().model);
        compare(Truth.of(dng), decoded, components);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_XT2_COMPRESSED, 0, 16383", "RAF_XT4_LOSSLESS, 0, 16383", "RAF_XE5_LOSSLESS, 0, 16383",
            "RAF_XE5_LOSSY, 0, 16383", "RAF_XT4_LOSSY, 0, 16383", "RAF_GFX50SII_LOSSY, 0, 16383", "RAF_S2PRO, 0, 4095",
            "RAF_S5000, 0, 15867", "RAF_S5200, 0, 16383", "RAF_S5500, 0, 15880", "RAF_S6000FD, 0, 16383",
            "RAF_S6500FD, 0, 16383", "RAF_S7000, 0, 15869", "RAF_S9500, 0, 16383", "RAF_S9600, 0, 16383",
            "RAF_S100FS, 0, 16382", "RAF_S1, 0, 4095", "RAF_SL1000, 0, 4095", "RAF_E550, 0, 15875", "RAF_E900, 0, 16383",
            "RAF_S5600, 0, 16383", "RAF_HS10, 0, 4095", "RAF_S3PRO, 0 1, 16383", "RAF_S5PRO, 0 1, 16383",
            "RAF_F700, 0 1, 16383", "RAF_S200EXR, 0 1, 16383", "RAF_F550EXR, 1 0, 4095", "RAF_F770EXR, 1 0, 4095",
            "RAF_X10, 1 0, 4095", "RAF_F600EXR, 1 0, 4095", "RAF_F900EXR, 1 0, 4095", "RAF_HS20EXR, 1 0, 4095",
            "RAF_S200EXR_HR, 1 0, 16383", "RAF_X10_SN, 1 0, 4095",
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
    @EnumSource(value = RawSamples.class, names = {"RAF_X10"})
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
            "RAF_S2PRO, 216d469bc44987fda2ad1e8988232bb36d8f3f75685949339a4c00ec6e42b5b5",
            "RAF_S6500FD, d3c8cccf2f896ae21888fe8d58eff6f91938ee1381bfe344f24b8dd95ab8a253",
            "RAF_XT2_COMPRESSED, a1c28e3a77d228bd2965dfb5473fdff3850707b47264a2bb877b747d9dc47690",
            "RAF_XT4_LOSSLESS, 663d30ff04025a84ef7a550b48d24097c8f29cfcecfad9def613c612e2b9c270",
            "RAF_XE5_LOSSLESS, 7ba267c1f6507753e5ce37fc3dc282aab2ae2b429183e4af7415ee9ce306db2d",
            "RAF_GFX50SII_LOSSLESS, 463a7a5630a837883652c8f15cc425ea425b30d580da141c34c5d033f79ace4b",
            "RAF_GFX50S_COMPRESSED, 41ecdff6d760afde67a9195b68a6970275e6f2f0f8e4482781676e9ed5cd76af",
            "RAF_GFX100S_16BIT_LOSSLESS, 901ad4417bd29286a3c698737c8b17789c89ec34944bf16d4f4c4a5e2dc11b02",
            "RAF_XE5_LOSSY, 4bf0e7e6bd20c554602e3aadcce30a6a902cca36d408fca722f4189c03d5fe37",
            "RAF_XT4_LOSSY, 775608237691fe9c00619558978bcb7d208eea483b98aa08971255a2b88633ee",
            "RAF_GFX50SII_LOSSY, af7feaaaca02e52371d74913f75902568b72b37e008bb38550f0f2b70a086288",
            "RAF_GFX100II_16BIT_LOSSY, 9f26800c3cc1319815446698b4f71068761e6db431f259a6d931b07f0f2dff83",
            "RAF_S9500, eac9b8ec60421bc903d545a2285eea29e1179ee114d14de788548144227cf789",
            "RAF_S9600, a140d0be482514014b4c7c2f2534abd1017ebeb49291a50a7480e53b1ec26856",
            "RAF_S5500, c3a251334345c2282dfcbaac292ed48adfb092837c1d365d81d992bfafdbbc0f",
            "RAF_S3PRO, 567b0ece6da30fa25edfc498685aeeccb4698584254314cec620335e77434b69",
            "RAF_S5200, 58c21d817275ace651b9a06062c4cc843dcf818c63d5ffac7b63f91e5a54fef8",
            "RAF_S5PRO, a62998519aea29d88b447398dc39d56328afcd4147fc97169ecf51dd5c8de841",
            "RAF_S5000, 2f969db812ee7480fc6e87320be0c79930fd1bcf602efbb632babdcdee50819d",
            "RAF_S200EXR, a22ac3dd93c890caca8fbf809074612c58230c9348f5d7f55646125cae714163",
            "RAF_S7000, c64240c6326dd61aa5d6925758772ba5308c5fb89336903809b0d04d5eb1634c",
            "RAF_S100FS, c22dce5be27fcb5658d4e994d1d9fa708c16588e15008b28e980c6a00a5798e2",
            "RAF_S6000FD, f7619987d5cf482f66b5d1a19ae73b464582449ad35a40eb6fcbb69f681a2044",
            "RAF_F550EXR, cf65811523ffbb5862a90538f311466ce22aa65aab1240e963703de59b174ccf",
            "RAF_F770EXR, 534193ba8317673104120216fb1ec3bacb19b64b7ba09f7704271e4e33bb6c32",
            "RAF_X10, 278c317a25bfa56a5176b0bedde26bec2176e54fec6028ab690e23a0ed155299",
            "RAF_S1, 68af255b57bc6cf19eede92caba15b1bb88b05ad3a312f61ce8234209ed0f379",
            "RAF_SL1000, d6633fdf2947a53410da2a36db6437faaebc5e814ccb7d8132c8da4a226720c5",
            "RAF_DBP_GX680, 6e39ad0a71472eb69f73c3d3aa9bce20e6515bd2d59fbe6dfea31815e0024709",
            "RAF_F700, 30e56efb1720e1fe1d761f6c8b5e96475fbf58e54e9d2794079c788882f4ba8e",
            "RAF_E550, 5aae99cdf48da13cb73647308e68bdef09736118c9ee1201d6104ab5690a4ddc",
            "RAF_E900, 1e276f86ee0ca097637cf2ce9c1323275ab2e2d2df2d6bfdaedb43df03a8d78e",
            "RAF_S5600, eacf9063771b8ff77efee012df706ed904393229290cd2dab0a567bb07356a8f",
            "RAF_HS10, 6a37badfa16812d21bb5ff5f5e96543545354e5a0e4430459622ad09bd8790f3",
            "RAF_F600EXR, 3cf5b76a89fe4a4af3e5561012e8ad981a8b1cb4feb7806f5e2ff3a600dfc299",
            "RAF_F900EXR, dcdfb54d5ce057cc6f6d51d6cbf79ecded3f39aba64b10040964e6c6bf7d2925",
            "RAF_HS20EXR, 9434c0070636e7c155315e08a61a03e2f92bb695fee7c59db80c61d19ed63245",
            "RAF_S200EXR_HR, f822299345acb8cbfbd6a1d7084073fb2bd496bb4292c65e56030b2e420f3b1c",
            "RAF_X10_SN, c91b425718608cf42cce133347c98492f26c9518864e5f7fe2466a1ad403da4c"
    })
    @DisplayName("the decoded photosites keep the digest of the decoding checked against the converter")
    void digests(RawSamples sample, String sha256) {
        assertEquals(sha256, Decoded.of(sample).view(0).digest());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RAF_S3PRO, 2677fa14bf33131af28135466629fab80fc58648133031114bf3bba3ffce0d3a",
            "RAF_S5PRO, e3999821283794795be5c0ce3d9ef7e3bdd9f36d21926efb7b657adedfa3827b",
            "RAF_S200EXR, 81b971d3fcbad6def7966541b740ba9e1b80cc3d1f5f3a1b1e6b50909ea2f198",
            "RAF_F550EXR, 75571291e34072cb4c0f02d6e122322c4577670154aa2d3f82a3fb13ca22ae75",
            "RAF_F770EXR, 787e3ff7f25c47d6399cbe189dd2c959d2ecadb10a687259e3d2bc90ee020589",
            "RAF_X10, 415e0475e34a505949fee6153aa70ec646c5f482b07fcd5af4779eae897b35f5",
            "RAF_F700, 8cd5589781f5e8a7f320e0e270069887b65eb82f8f4c73b1ca9c9927a92cb844",
            "RAF_F600EXR, b9b2638ad2a54239575d059eea5b5bf6abc590986fc3edec1fdc22f69feb0685",
            "RAF_F900EXR, e64a1d4004183c0059e1577381440ccad98df961e58bce4301526c4365069147",
            "RAF_HS20EXR, a7c2192227ea7c679efea9b5a03f9c5914247baf173a9274fa8ab5ce82a192ff",
            "RAF_S200EXR_HR, c763d6b6a7cb36137ae36860615224bbe3d498b3741549a3914ccdcb4f0a32f9",
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
    @EnumSource(value = RawSamples.class, names = {"RAF_S2PRO", "RAF_S3PRO", "RAF_S5PRO", "RAF_S5000", "RAF_S5200",
            "RAF_S6000FD", "RAF_S6500FD", "RAF_S7000", "RAF_S9500", "RAF_S9600", "RAF_S100FS", "RAF_DBP_GX680",
            "RAF_F700", "RAF_E550", "RAF_E900", "RAF_S5600"})
    @DisplayName("a SuperCCD file, turned upright and scaled to square pixels, renders like its own JPEG preview")
    void superCcdGeometry(RawSamples sample) {
        try (var source = MediaSource.of(sample.fetch())) {
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
    // the color filter arrays of the record 0x0130, read from the last photosite to the first (from the first to the
    // last for the HS10), and of the SuperCCD and two-image layouts: red and blue swapped, the red-blue contrast
    // turns against the camera's
    @EnumSource(value = RawSamples.class, names = {"RAF_HS10", "RAF_S1", "RAF_SL1000", "RAF_F600EXR", "RAF_F770EXR",
            "RAF_F700", "RAF_E550", "RAF_E900", "RAF_S5600"})
    @DisplayName("the rendering, turned upright, has the colors of the camera's own JPEG preview")
    void previewColors(RawSamples sample) {
        try (var source = MediaSource.of(sample.fetch())) {
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
        var file = Files.readAllBytes(sample.fetch());
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
    // the converter's demosaiced image of EXR SN files keeps little of the photosites' detail (see sameExposure), so
    // the F770EXR is left out
    @EnumSource(value = RawSamples.class, names = {"RAF_S3PRO", "RAF_S5PRO", "RAF_F700", "RAF_S200EXR", "RAF_F600EXR"})
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
        // highlights of the EXR DR files, so the bands of the blend and above get a wider tolerance
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
    // the X10 SN sample, of a detailed scene at ISO 100, is left out: its pairs' detail is mostly the scene's, which
    // both readings share, not noise
    @EnumSource(value = RawSamples.class, names = {"RAF_F770EXR"})
    @DisplayName("the two equal readings of each photosite of an EXR SN file are averaged: the merged values keep each "
            + "reading's level and about half its noise")
    void sameExposure(RawSamples sample) {
        var decoded = Decoded.of(sample);
        var file = decoded.file();
        assertEquals(2, file.sensor.samplesPerPixel);
        var image = LinearImage.of(file.sensor, decoded.plane(), MediaLimits.DEFAULT);
        var merged = DualSamples.merge(image, MediaLimits.DEFAULT);
        for (var factor : merged.factors()) {
            assertEquals(1, factor, 0.1, "the two readings' ratio");
        }
        var width = image.width;
        var primary = merged.primary();
        // the detail of a photosite, itself less the mean of its same-color neighbors two photosites away, is mostly
        // noise at this scale: averaging two independent readings halves its variance, one reading alone keeps it
        var levels = new double[3];
        var variances = new double[3];
        var count = 0L;
        for (var y = 2; y < image.height - 2; y++) {
            for (var x = 2; x < width - 2; x++) {
                var usable = true;
                for (var site : new int[]{0, -2, 2, -2 * width, 2 * width}) {
                    var index = y * width + x + site;
                    for (var s = 0; s < 2; s++) {
                        var value = image.data[index * 2 + s];
                        usable &= value > 0.05 && value < 0.8;
                    }
                }
                if (!usable) {
                    continue;
                }
                count++;
                for (var kind = 0; kind < 3; kind++) {
                    var center = reading(image, merged, kind, y * width + x, primary);
                    var around = reading(image, merged, kind, y * width + x - 2, primary)
                            + reading(image, merged, kind, y * width + x + 2, primary)
                            + reading(image, merged, kind, (y - 2) * width + x, primary)
                            + reading(image, merged, kind, (y + 2) * width + x, primary);
                    var detail = center - around / 4;
                    levels[kind] += center;
                    variances[kind] += detail * detail;
                }
            }
        }
        assertTrue(count > 1_000_000, "too few usable photosites: " + count);
        for (var kind = 0; kind < 2; kind++) {
            // the merged values, brought to the reading's scale
            var scale = levels[kind] / levels[2];
            var ratio = variances[2] * scale * scale / variances[kind];
            assertEquals(1, levels[kind] / levels[1 - kind], 0.1, "the levels of the two readings");
            assertTrue(ratio > 0.45 && ratio < 0.65, "the merged detail variance is " + ratio + " of reading " + kind
                    + "'s");
        }
    }

    // the primary reading (kind 0), the secondary one (kind 1) or the merged value (kind 2) of a photosite
    private static double reading(LinearImage image, DualSamples.Merged merged, int kind, int index, int primary) {
        return kind == 2 ? merged.image().data[index] : image.data[index * 2 + (kind == 0 ? primary : 1 - primary)];
    }

    @ParameterizedTest(name = "{0}")
    // the converter corrects the lens distortion of some of these files (the X10's) in its demosaiced image with the
    // WarpRectilinear of its DNG file's OpcodeList3: each photosite's place is carried through that warp
    @EnumSource(value = RawSamples.class, names = {"RAF_F550EXR", "RAF_X10", "RAF_F900EXR", "RAF_HS20EXR",
            "RAF_S200EXR_HR"})
    @DisplayName("the two images of an EXR HR file lie where the converter's demosaiced image places them, the image of "
            + "the odd columns half a pixel right of and below the image of the even ones")
    void diagonalPairs(RawSamples sample) {
        var linear = Linear.of(sample);
        var oracle = Truth.of(sample);
        assertNotNull(oracle.dng(), "the oracle DNG file");
        var warp = Warp.of(oracle.dng().opcodeList3(), linear.width(), linear.height());
        var decoded = Decoded.of(sample);
        assertTrue(decoded.diagonal());
        var offset = locate(decoded.view(0), oracle, 1);
        assertNotNull(offset);
        var cfa = decoded.file().sensor.cfa;
        // photosites near saturation are left out: the converter's values there are reconstructed
        var clip = 0.88 * decoded.file().sensor.white[0];
        // for each image, the correlation of its photosites with the converter's values at the linear places
        // (2x + dx, 2y + dy) of the photosites at x, y, for dx and dy from -2 to 2
        var correlations = new double[2][25];
        var best = new int[2];
        for (var component = 0; component < 2; component++) {
            var view = decoded.view(component);
            var sums = new double[25][6];
            for (var y = 0; y < view.height(); y++) {
                for (var x = 0; x < view.width(); x++) {
                    var xd = x - offset[0];
                    var yd = y - offset[1];
                    var value = view.value(x, y);
                    if (xd < 0 || yd < 0 || xd >= oracle.width() || yd >= oracle.height() || value >= clip
                            || !linear.contains(2 * xd, 2 * yd, -64)) {
                        continue;
                    }
                    var color = cfa.color(y, 2 * x + component).code();
                    for (var shift = 0; shift < 25; shift++) {
                        var at = warp.target(2 * xd + shift % 5 - 2, 2 * yd + shift / 5 - 2, color);
                        if (linear.contains((int) Math.floor(at[0]), (int) Math.floor(at[1]), 2)) {
                            accumulate(sums[shift], value, linear.bilinear(at[0], at[1], color));
                        }
                    }
                }
            }
            for (var shift = 0; shift < 25; shift++) {
                assertTrue(sums[shift][0] > 10000, "too few photosites in the oracle crop");
                correlations[component][shift] = correlation(sums[shift]);
                best[component] = correlations[component][shift] > correlations[component][best[component]] ? shift
                        : best[component];
            }
            assertTrue(correlations[component][best[component]] > 0.99, "image " + component + " correlates "
                    + correlations[component][best[component]] + " with the converter at best");
        }
        // the converter's image may sit a pixel off the doubled grid, both images alike; the odd columns' image lies
        // one linear pixel right of and below the even columns', not at the other photosite of the pair
        var first = best[0];
        assertTrue(first % 5 >= 1 && first % 5 <= 3 && first / 5 >= 1 && first / 5 <= 3, "the first image's place "
                + (first % 5 - 2) + ", " + (first / 5 - 2));
        assertEquals(first + 6, best[1], "the odd columns' image's place relative to the even columns'");
        var right = correlations[1][first + 6];
        var wrong = correlations[1][first - 6];
        assertTrue(right > wrong + 0.002, "the odd columns' image correlates " + right + " at its place, " + wrong
                + " at the other photosite of the pair");
    }

    /**
     * The {@code WarpRectilinear} of a DNG file's {@code OpcodeList3}, written from the DNG specification: the place
     * in the demosaiced image at which the converter shows the light of a place of the unwarped image.
     *
     * @param sets the coefficients of each plane: kr0 to kr3, kt0 and kt1
     * @param xc the column of the optical center
     * @param yc the row of the optical center
     * @param m the distance from the optical center to the farthest corner
     */
    private record Warp(double[][] sets, double xc, double yc, double m) {
        static Warp of(byte[] list, int width, int height) {
            var data = ByteBuffer.wrap(list);
            var count = list.length >= 4 ? data.getInt(0) : 0;
            var position = 4;
            for (var index = 0; index < count; index++) {
                var id = data.getInt(position);
                var size = data.getInt(position + 12);
                if (id == 1) {
                    var sets = new double[data.getInt(position + 16)][6];
                    var at = position + 20;
                    for (var set : sets) {
                        for (var k = 0; k < 6; k++, at += 8) {
                            set[k] = data.getDouble(at);
                        }
                    }
                    var xc = data.getDouble(at) * (width - 1);
                    var yc = data.getDouble(at + 8) * (height - 1);
                    var mx = Math.max(xc, width - 1 - xc);
                    var my = Math.max(yc, height - 1 - yc);
                    return new Warp(sets, xc, yc, Math.sqrt(mx * mx + my * my));
                }
                assertTrue(id != 2 && id != 14, "a warp other than WarpRectilinear");
                position += 16 + size;
            }
            return new Warp(new double[][]{{1, 0, 0, 0, 0, 0}}, 0, 0, 1);
        }

        // the output place whose source is (x, y), found by fixed-point iteration of the source mapping
        double[] target(double x, double y, int plane) {
            var c = sets[Math.min(plane, sets.length - 1)];
            var tx = x;
            var ty = y;
            for (var iteration = 0; iteration < 8; iteration++) {
                var dx = (tx - xc) / m;
                var dy = (ty - yc) / m;
                var r2 = dx * dx + dy * dy;
                var f = c[0] + r2 * (c[1] + r2 * (c[2] + r2 * c[3]));
                var sx = xc + m * (f * dx + c[4] * 2 * dx * dy + c[5] * (r2 + 2 * dx * dx));
                var sy = yc + m * (f * dy + c[5] * 2 * dx * dy + c[4] * (r2 + 2 * dy * dy));
                tx += x - sx;
                ty += y - sy;
            }
            return new double[]{tx, ty};
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"RAF_S3PRO", "RAF_S5PRO", "RAF_F700", "RAF_S200EXR", "RAF_F550EXR",
            "RAF_F770EXR", "RAF_X10", "RAF_F600EXR", "RAF_F900EXR", "RAF_HS20EXR", "RAF_S200EXR_HR",
            "RAF_X10_SN"})
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

    // adds a pair of values to the sums of a correlation: count, x, y, x squared, y squared and x times y
    private static void accumulate(double[] sums, double x, double y) {
        sums[0]++;
        sums[1] += x;
        sums[2] += y;
        sums[3] += x * x;
        sums[4] += y * y;
        sums[5] += x * y;
    }

    // the correlation of the pairs a sums array holds
    private static double correlation(double[] sums) {
        var n = sums[0];
        var covariance = sums[5] / n - sums[1] / n * (sums[2] / n);
        var vx = sums[3] / n - (sums[1] / n) * (sums[1] / n);
        var vy = sums[4] / n - (sums[2] / n) * (sums[2] / n);
        return covariance / Math.sqrt(vx * vy);
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
            try (var source = MediaSource.of(sample.fetch())) {
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
