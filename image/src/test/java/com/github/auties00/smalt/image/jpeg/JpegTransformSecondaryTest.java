package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.gainmap.GainMapComputeOptions;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the secondary images (gain maps and the other images of a Multi-Picture Format index) that
 * {@link JpegTransforms} carries through a lossless transform, on the gain map files of real tools (a Google Pixel 9
 * Ultra HDR photo, Apple's older and newer iPhone gain maps, the ISO 21496-1 files of the Web Platform Tests, Adobe
 * Camera Raw and Photoshop files). Each image of the output is held to libjpeg-turbo's {@code jpegtran} given the
 * same switches for that image alone, the primary image byte for byte except the index fields that locate the
 * transformed images, which CIPA DC-007 lays out and this test reads on its own. exiftool extracts the images through
 * the output's index, libultrahdr decodes the transformed Ultra HDR file with the source's gain map metadata, and
 * Smalt reads the gain map back. The transforms that leave the images out (crop, wipe, a marker copy without the
 * index, an image that does not transform) and damaged files are covered too.
 */
@DisplayName("JPEG lossless transforms of files with secondary images")
class JpegTransformSecondaryTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private static final byte[] MPF = {'M', 'P', 'F', 0};

    private static final byte[] XMP = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    private static final Pattern ITEM_LENGTH = Pattern.compile("(Item:Length=\")(\\d+)(\")");

    private static final String PIXEL = "jpeg-commons-pixel9-ultrahdr-heysong-1.jpg";

    private static final String APPLE_NEW = "libavif-apple_gainmap_new.jpg";

    private static final String WPT = "jpeg-wpt-iso21496-1-base_srgb-altr_none-use_base.jpg";

    private static final String WPT_REC2020 = "jpeg-wpt-iso21496-1-base_srgb-altr_rec2020-use_altr.jpg";

    private static final String CAMERA_RAW = "jpeg-commons-adobe-camera-raw-gainmap-sony-ilce6400.jpg";

    /**
     * The Multi-Picture Format index of a file.
     *
     * @param header the index of the TIFF header the image offsets are relative to
     * @param little whether the index is little-endian
     * @param images the start, size and entry position of each image
     */
    record Index(int header, boolean little, List<long[]> images) {
    }

    /** {@return the Multi-Picture Format index of the primary image of a file, or null} */
    static Index index(byte[] file) {
        var at = 2;
        while (at + 4 <= file.length && (file[at] & 0xFF) == 0xFF) {
            var marker = file[at + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            var length = u16(file, at + 2, false);
            if (marker == 0xE2 && length >= 12 && Arrays.equals(file, at + 4, at + 8, MPF, 0, 4)) {
                var header = at + 8;
                var little = file[header] == 'I';
                var ifd = header + (int) u32(file, header + 4, little);
                var count = u16(file, ifd, little);
                for (var i = 0; i < count; i++) {
                    var entry = ifd + 2 + 12 * i;
                    if (u16(file, entry, little) != 0xB002) {
                        continue;
                    }
                    var values = header + (int) u32(file, entry + 8, little);
                    var images = new ArrayList<long[]>();
                    for (var k = 0; k < u32(file, entry + 4, little) / 16; k++) {
                        var field = values + 16 * k;
                        var size = u32(file, field + 4, little);
                        var offset = u32(file, field + 8, little);
                        images.add(new long[]{offset == 0 ? 0 : header + offset, size, field});
                    }
                    return new Index(header, little, images);
                }
                return null;
            }
            at += 2 + length;
        }
        return null;
    }

    static int u16(byte[] data, int at, boolean little) {
        return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8
                : (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    static long u32(byte[] data, int at, boolean little) {
        return little ? (long) u16(data, at + 2, true) << 16 | u16(data, at, true)
                : (long) u16(data, at, false) << 16 | u16(data, at + 2, false);
    }

    /** {@return the bytes of an image of a file, as its index locates it} */
    static byte[] slice(byte[] file, long[] image) {
        return Arrays.copyOfRange(file, (int) image[0], (int) (image[0] + image[1]));
    }

    /** {@return whether a file holds a Multi-Picture Format index in its primary image's header} */
    static boolean hasIndex(byte[] file) {
        return index(file) != null;
    }

    /** {@return the file with the APP1 XMP segment of its primary image's header replaced} */
    static byte[] withXmp(byte[] file, UnaryOperator<String> edit) {
        var at = 2;
        while (at + 4 <= file.length && (file[at] & 0xFF) == 0xFF) {
            var marker = file[at + 1] & 0xFF;
            var length = u16(file, at + 2, false);
            if (marker == 0xE1 && Arrays.equals(file, at + 4, at + 4 + XMP.length, XMP, 0, XMP.length)) {
                var text = new String(file, at + 4, length - 2, StandardCharsets.ISO_8859_1);
                var payload = edit.apply(text).getBytes(StandardCharsets.ISO_8859_1);
                var out = new ByteArrayOutputStream();
                out.write(file, 0, at + 2);
                out.write((payload.length + 2) >> 8);
                out.write(payload.length + 2);
                out.write(payload, 0, payload.length);
                out.write(file, at + 2 + length, file.length - at - 2 - length);
                return out.toByteArray();
            }
            if (marker == 0xDA) {
                break;
            }
            at += 2 + length;
        }
        return file;
    }

    /** {@return the XMP text with its Item:Length values replaced, in order} */
    static String lengths(String xmp, List<Long> values) {
        var matcher = ITEM_LENGTH.matcher(xmp);
        var result = new StringBuilder();
        var item = 0;
        while (matcher.find()) {
            matcher.appendReplacement(result, "$1" + values.get(item++) + "$3");
        }
        matcher.appendTail(result);
        assertTrue(item == 0 || item == values.size(), "one Item:Length per secondary image");
        return result.toString();
    }

    /** {@return the jpegtran switches of the secondary images: their own segments, and no grayscale conversion} */
    static List<String> secondarySwitches(List<String> switches) {
        var result = new ArrayList<String>();
        for (var i = 0; i < switches.size(); i++) {
            switch (switches.get(i)) {
                case "-grayscale" -> {
                }
                case "-copy" -> {
                    result.add("-copy");
                    result.add("all");
                    i++;
                }
                default -> result.add(switches.get(i));
            }
        }
        return result;
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(PIXEL, "-rotate 90 -copy all"),
                Arguments.of(PIXEL, "-flip horizontal -trim -copy all"),
                Arguments.of(PIXEL, "-rotate 180 -perfect -copy all"),
                Arguments.of(PIXEL, "-progressive -copy all"),
                Arguments.of(PIXEL, "-grayscale -copy all"),
                Arguments.of(APPLE_NEW, "-rotate 270 -copy all"),
                Arguments.of(APPLE_NEW, "-transverse -perfect -copy all"),
                Arguments.of("libavif-apple_gainmap_old.jpg", "-transpose -arithmetic -copy all"),
                Arguments.of(WPT, "-rotate 90 -copy all"),
                Arguments.of(WPT, "-flip vertical -copy icc"),
                Arguments.of(WPT_REC2020, "-transpose -optimize -copy all"),
                Arguments.of(CAMERA_RAW, "-rotate 90 -trim -copy all"),
                Arguments.of(CAMERA_RAW, "-rotate 180 -perfect -copy all"),
                Arguments.of("libavif-seine_sdr_gainmap_srgb.jpg", "-flip horizontal -restart 1 -copy all"),
                Arguments.of("libavif-seine_sdr_different_gainmap_srgb.jpg", "-rotate 270 -grayscale -copy all"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    @DisplayName("writes each image as jpegtran writes it, and an index that locates them")
    void jpegtranPerImage(String vector, String line) {
        var switches = List.of(line.split(" "));
        var source = JpegGainMapTest.vector(vector);
        var sourceIndex = index(source);
        assertTrue(sourceIndex != null && sourceIndex.images().size() >= 2, "the vector has secondary images");
        var options = JpegtranSwitches.options(switches);
        var expected = new ArrayList<byte[]>();
        try {
            expected.add(JpegTransformsTest.run("jpegtran", switches, source));
            for (var image : sourceIndex.images().subList(1, sourceIndex.images().size())) {
                expected.add(JpegTransformsTest.run("jpegtran", secondarySwitches(switches), slice(source, image)));
            }
        } catch (JpegTransformsTest.ToolFailure failure) {
            // jpegtran -perfect refuses an image the transform is not perfect for, the primary or a secondary one
            var thrown = assertThrows(MediaException.Unsupported.class,
                    () -> JpegTransforms.transform(MediaSource.of(source), options));
            assertEquals("jpeg imperfect transform", thrown.format());
            return;
        }
        var actual = JpegTransforms.transform(MediaSource.of(source), options);
        var actualIndex = index(actual);
        assertTrue(actualIndex != null, "the output keeps the index");
        var images = actualIndex.images();
        assertEquals(expected.size(), images.size(), "images in the index");
        var position = 0L;
        for (var k = 0; k < images.size(); k++) {
            assertEquals(k == 0 ? 0 : position, images.get(k)[0], "start of image " + k);
            position += images.get(k)[1];
        }
        assertEquals(actual.length, position, "the images fill the file");
        for (var k = 1; k < images.size(); k++) {
            assertArrayEquals(expected.get(k), slice(actual, images.get(k)), "image " + k);
        }
        // the primary image is jpegtran's but for the index entries and the XMP item lengths that locate the images
        var secondaryLengths = images.subList(1, images.size()).stream().map(image -> image[1]).toList();
        var primary = withXmp(expected.getFirst(), xmp -> lengths(xmp, secondaryLengths));
        var primaryIndex = index(primary);
        assertTrue(primaryIndex != null, "jpegtran copies the index");
        for (var k = 0; k < images.size(); k++) {
            System.arraycopy(actual, (int) images.get(k)[2] + 4, primary, (int) primaryIndex.images().get(k)[2] + 4,
                    8);
        }
        assertArrayEquals(primary, slice(actual, images.getFirst()), "primary image");
    }

    @Test
    @DisplayName("writes an index that exiftool follows to each transformed image")
    void exiftoolExtracts() throws IOException {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        var directory = Scratch.directory("jpeg-secondary");
        for (var vector : List.of(PIXEL, APPLE_NEW, WPT)) {
            var rotated = JpegTransforms.transform(MediaSource.of(JpegGainMapTest.vector(vector)),
                    JpegTransformOptions.of(Orientation.ROTATE_90));
            var path = directory.resolve("rotated-" + vector);
            Files.write(path, rotated);
            var images = index(rotated).images();
            for (var k = 1; k < images.size(); k++) {
                var extracted = Oracle.run(Oracle.Tool.EXIFTOOL, TIMEOUT, List.of("-b", "-MPImage" + (k + 1),
                        path.toString()));
                assertArrayEquals(slice(rotated, images.get(k)), extracted, vector + " image " + (k + 1));
            }
        }
    }

    @Test
    @DisplayName("writes an Ultra HDR file libultrahdr decodes at the rotated size with the source's metadata")
    void libultrahdrDecodes() throws IOException {
        Oracle.assume(Oracle.Tool.ULTRAHDR_APP);
        var directory = Scratch.directory("jpeg-secondary");
        var source = JpegGainMapTest.vector(PIXEL);
        var sourcePath = directory.resolve("pixel9.jpg");
        Files.write(sourcePath, source);
        var rotatedPath = directory.resolve("pixel9-rotated.jpg");
        Files.write(rotatedPath, JpegTransforms.rotate(MediaSource.of(source), 90));
        var sourceMetadata = ultrahdrMetadata(sourcePath, 4080, 3072);
        var rotatedMetadata = ultrahdrMetadata(rotatedPath, 3072, 4080);
        assertEquals(sourceMetadata, rotatedMetadata, "gain map metadata");
    }

    /** Decodes an Ultra HDR file with ultrahdr_app to PQ, checks its size and returns the metadata it read. */
    static List<String> ultrahdrMetadata(Path file, int width, int height) throws IOException {
        var raw = file.resolveSibling(file.getFileName() + ".raw");
        var metadata = file.resolveSibling(file.getFileName() + ".cfg");
        Oracle.run(Oracle.Tool.ULTRAHDR_APP, TIMEOUT, List.of("-m", "1", "-j", file.toString(), "-o", "2", "-O", "5",
                "-z", raw.toString(), "-f", metadata.toString()));
        assertEquals((long) width * height * 4, Files.size(raw), "decoded size of " + file.getFileName());
        Files.delete(raw);
        return Files.readAllLines(metadata).stream().map(String::strip).filter(text -> !text.isEmpty()).toList();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PIXEL, APPLE_NEW, "libavif-apple_gainmap_old.jpg", WPT, CAMERA_RAW,
            "libavif-seine_sdr_different_gainmap_srgb.jpg"})
    @DisplayName("keeps a gain map Smalt reads with the source's metadata, transposed with the base image")
    void gainMapReadsBack(String vector) {
        var source = JpegGainMapTest.vector(vector);
        var rotated = JpegTransforms.rotate(MediaSource.of(source), 90);
        var before = JpegDecoder.decodeGainMap(MediaSource.of(source)).orElseThrow();
        var after = JpegDecoder.decodeGainMap(MediaSource.of(rotated)).orElseThrow();
        assertEquals(before.metadata(), after.metadata());
        assertEquals(before.alternateProfile().isPresent(), after.alternateProfile().isPresent());
        // the gain map is transposed; a partial edge MCU of it is trimmed, as jpegtran -trim trims it
        var width = after.image().width();
        var height = after.image().height();
        assertTrue(width <= before.image().height() && width > before.image().height() - 16, "width " + width);
        assertTrue(height <= before.image().width() && height > before.image().width() - 16, "height " + height);
        assertEquals(JpegDecoder.auxiliaryImages(MediaSource.of(source)).stream().map(AuxiliaryImage::type).toList(),
                JpegDecoder.auxiliaryImages(MediaSource.of(rotated)).stream().map(AuxiliaryImage::type).toList());
    }

    @Test
    @DisplayName("locates the gain map through the XMP container directory of a file without an index")
    void containerDirectory() {
        var pixel = JpegGainMapTest.vector(PIXEL);
        var withoutIndex = withoutIndex(pixel);
        assertFalse(hasIndex(withoutIndex));
        var gainMap = slice(pixel, index(pixel).images().get(1));
        var rotated = JpegTransforms.transform(MediaSource.of(withoutIndex), JpegTransformOptions.of(
                Orientation.ROTATE_90));
        assertFalse(hasIndex(rotated), "no index is added");
        var expected = JpegTransforms.transform(MediaSource.of(gainMap), JpegTransformOptions.of(
                Orientation.ROTATE_90));
        assertArrayEquals(expected, Arrays.copyOfRange(rotated, rotated.length - expected.length, rotated.length),
                "the gain map, transformed alone, ends the file");
        var text = new String(rotated, StandardCharsets.ISO_8859_1);
        var matcher = ITEM_LENGTH.matcher(text);
        assertTrue(matcher.find());
        assertEquals(expected.length, Integer.parseInt(matcher.group(2)), "Item:Length of the gain map");
        assertEquals(JpegDecoder.decodeGainMap(MediaSource.of(pixel)).orElseThrow().metadata(),
                JpegDecoder.decodeGainMap(MediaSource.of(rotated)).orElseThrow().metadata());
    }

    /** {@return the file without the APP2 MPF segment of its primary image} */
    static byte[] withoutIndex(byte[] file) {
        var at = 2;
        while (at + 4 <= file.length && (file[at] & 0xFF) == 0xFF) {
            var length = u16(file, at + 2, false);
            if ((file[at + 1] & 0xFF) == 0xE2 && Arrays.equals(file, at + 4, at + 8, MPF, 0, 4)) {
                var out = new ByteArrayOutputStream();
                out.write(file, 0, at);
                out.write(file, at + 2 + length, file.length - at - 2 - length);
                return out.toByteArray();
            }
            at += 2 + length;
        }
        throw new AssertionError("no index");
    }

    @Nested
    @DisplayName("leaves the secondary images and their index out")
    class LeftOut {
        static void assertPrimaryOnly(byte[] output) {
            assertFalse(hasIndex(output), "no index");
            assertFalse(new String(output, StandardCharsets.ISO_8859_1).contains("MPF\0"), "no index segment");
            assertTrue(JpegDecoder.auxiliaryImages(MediaSource.of(output)).isEmpty());
            assertTrue(JpegDecoder.decodeGainMap(MediaSource.of(output)).isEmpty());
        }

        @Test
        @DisplayName("from a crop, whose gain map would cover another area")
        void crop() {
            var output = JpegTransforms.crop(MediaSource.of(JpegGainMapTest.vector(PIXEL)), new Region(0, 0, 1024,
                    1024));
            assertPrimaryOnly(output);
            assertEquals(1024, JpegDecoder.decode(MediaSource.of(output)).picture().width());
        }

        @Test
        @DisplayName("from a wipe, whose gain map would reveal the wiped area")
        void wipe() {
            assertPrimaryOnly(JpegTransforms.wipe(MediaSource.of(JpegGainMapTest.vector(PIXEL)), new Region(0, 0, 512,
                    512)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"NONE", "COMMENTS"})
        @DisplayName("when the copied segments leave the index out")
        void markerCopy(String copy) {
            assertPrimaryOnly(JpegTransforms.transform(MediaSource.of(JpegGainMapTest.vector(WPT)),
                    JpegTransformOptions.builder().transform(Orientation.ROTATE_90)
                            .markerCopy(JpegMarkerCopy.valueOf(copy)).build()));
        }

        @Test
        @DisplayName("when an image of the index cannot be transformed")
        void untransformable() {
            var file = JpegGainMapTest.ultraHdr(JpegGainMapTest.sdr(32, 24), GainMapComputeOptions.defaults());
            var gainMap = index(file).images().get(1);
            // a lossless (SOF3) frame header, which the transforms lack
            var sof = (int) gainMap[0] + 2;
            while ((file[sof + 1] & 0xFF) != 0xC0) {
                assertTrue((file[sof + 1] & 0xFF) != 0xDA, "a baseline gain map");
                sof += 2 + u16(file, sof + 2, false);
            }
            sof++;
            var damaged = file.clone();
            damaged[sof] = (byte) 0xC3;
            var output = JpegTransforms.rotate(MediaSource.of(damaged), 90);
            assertFalse(hasIndex(output), "no index");
            assertEquals(24, JpegDecoder.decode(MediaSource.of(output)).picture().width());
        }
    }

    @Test
    @DisplayName("never writes an index that points outside the output, nor fails uncleanly, on damaged files")
    void damaged() {
        var file = JpegGainMapTest.ultraHdr(JpegGainMapTest.sdr(32, 24), GainMapComputeOptions.defaults());
        var random = new Random(21);
        for (var i = 0; i < 300; i++) {
            var damaged = file.clone();
            for (var k = 0; k < 4; k++) {
                damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
            }
            var length = random.nextBoolean() ? damaged.length : random.nextInt(damaged.length);
            var source = Arrays.copyOf(damaged, length);
            byte[] output;
            try {
                output = JpegTransforms.rotate(MediaSource.of(source), 90);
            } catch (MediaException expected) {
                var cause = expected.getCause();
                if (cause instanceof RuntimeException && !(cause instanceof MediaException)) {
                    throw new AssertionError("unexpected " + cause, cause);
                }
                continue;
            }
            var index = index(output);
            if (index == null) {
                continue;
            }
            for (var image : index.images()) {
                assertTrue(image[0] + image[1] <= output.length, "image inside the output");
                assertEquals(0xFF, output[(int) image[0]] & 0xFF, "image starting with SOI");
                assertEquals(0xD8, output[(int) image[0] + 1] & 0xFF, "image starting with SOI");
            }
        }
    }
}
