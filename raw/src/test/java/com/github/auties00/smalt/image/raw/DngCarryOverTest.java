package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what the DNG encoder carries over from a source when converting it: the semantic masks (re-coded, moved
 * with the edge margin the encoder adds to a color filter array image) and the RGB tables that apply to them, the
 * extra camera profiles in their own byte order, and the private data and maker note. Each converted file is read
 * back through an independent TIFF reader and through the decoder, whose rendering must equal the source's.
 */
class DngCarryOverTest {
    static final int MASK = 65540;
    static final int SEMANTIC_NAME = 52526;
    static final int INSTANCE_ID = 52528;
    static final int SUB_AREA = 52536;
    static final int PRIVATE_DATA = 50740;
    static final int EXTRA_PROFILES = 50933;

    static byte[] convert(byte[] source) {
        return DngEncoder.convert(MediaSource.of(source));
    }

    static byte[] convert(byte[] source, RawDecoderOptions decoding, DngEncoderOptions options) {
        return DngEncoder.convert(MediaSource.of(source), decoding, options);
    }

    /** A mask as the converted file stores it: its entries and its inflated samples. */
    record Mask(DngStructure.Directory directory, ByteOrder order, byte[] samples) {
        String name() {
            return directory.get(SEMANTIC_NAME).text();
        }

        long width() {
            return directory.get(256).longs(order)[0];
        }

        long height() {
            return directory.get(257).longs(order)[0];
        }

        long bits() {
            return directory.get(258).longs(order)[0];
        }

        int value(int index) {
            assertEquals(8, bits(), "an 8-bit mask");
            return samples[index] & 0xFF;
        }

        long[] subArea() {
            return directory.has(SUB_AREA) ? directory.get(SUB_AREA).longs(order) : null;
        }

    }

    static List<Mask> masks(byte[] dng) {
        var structure = DngStructure.of(dng);
        var result = new ArrayList<Mask>();
        for (var directory : structure.directories) {
            if (!directory.has(254) || directory.get(254).longs(structure.order)[0] != MASK) {
                continue;
            }
            assertEquals(52527, directory.get(262).longs(structure.order)[0], "photometric interpretation");
            assertEquals(1, directory.get(277).longs(structure.order)[0], "samples per pixel");
            var compression = directory.get(259).longs(structure.order)[0];
            var offsets = directory.get(273).longs(structure.order);
            var counts = directory.get(279).longs(structure.order);
            var out = new ByteArrayOutputStream();
            for (var strip = 0; strip < offsets.length; strip++) {
                var data = Arrays.copyOfRange(dng, (int) offsets[strip], (int) (offsets[strip] + counts[strip]));
                out.writeBytes(compression == 8 ? inflate(data) : data);
            }
            result.add(new Mask(directory, structure.order, out.toByteArray()));
        }
        return result;
    }

    static byte[] inflate(byte[] data) {
        var inflater = new Inflater();
        inflater.setInput(data);
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                var count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new AssertionError("truncated deflate stream");
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException exception) {
            throw new AssertionError(exception);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** A mask of 8-bit values varying across it, so that a resampling or a shift shows. */
    static DngBuilder.Dir rampMask(DngBuilder builder, String name, int width, int height) {
        var data = new byte[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                data[y * width + x] = (byte) Math.min(255, (x * 255) / Math.max(1, width - 1) + y);
            }
        }
        return builder.child().longs(254, MASK).longs(256, width).longs(257, height).shorts(258, 8).shorts(259, 1)
                .shorts(262, 52527).shorts(277, 1).ascii(SEMANTIC_NAME, name).strips(height, data);
    }

    /** A Bayer file whose default crop is the whole image, so that the encoder adds a margin of 2 photosites. */
    static DngBuilder bayer(int width, int height) {
        var builder = DngSpecGapsTest.profile(new DngBuilder());
        DngSpecGapsTest.header(builder.first, width, height, 1);
        builder.first.shorts(33421, 2, 2).bytes(33422, 0, 1, 1, 2)
                .strips(height, DngSpecGapsTest.words(DngSpecGapsTest.gradient(width, height, 1)));
        return builder;
    }

    @Nested
    @DisplayName("semantic masks")
    class Masks {
        @Test
        @DisplayName("are written with their samples, names and instance, and the masked RGB table still applies")
        void maskedTable() {
            var builder = DngSpecGapsTest.linear(32, 16);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            DngSpecGapsTest.mask(builder, "subject", 8, 4, true).ascii(INSTANCE_ID, "person_a")
                    .raw(700, 1, 9, "<x:xmpmeta".getBytes(StandardCharsets.US_ASCII));
            var source = builder.write();
            var converted = convert(source);
            var masks = masks(converted);
            assertEquals(1, masks.size());
            var mask = masks.getFirst();
            assertEquals("subject", mask.name());
            assertEquals("person_a", mask.directory().get(INSTANCE_ID).text());
            assertArrayEquals("<x:xmpme".getBytes(StandardCharsets.US_ASCII),
                    Arrays.copyOf(mask.directory().get(700).bytes(), 8));
            assertEquals(8, mask.width());
            assertEquals(4, mask.height());
            assertNull(mask.subArea());
            for (var index = 0; index < 32; index++) {
                assertEquals(index % 8 < 4 ? 255 : 0, mask.value(index), "mask sample " + index);
            }
            var tables = DngStructure.of(converted).first().get(52543).bytes();
            assertEquals(1, ByteBuffer.wrap(tables).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
                    "the big-endian RGB tables are kept in the file's little-endian order");
            assertArrayEquals(Profiles.reorder(DngSpecGapsTest.constantTable("subject", 0f, 0)), tables);
            var rendering = DngSpecGapsTest.render(converted);
            assertArrayEquals(DngSpecGapsTest.render(source), rendering);
            assertFalse(Arrays.equals(DngSpecGapsTest.render(DngSpecGapsTest.linear(32, 16).write()), rendering));
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 16})
        @DisplayName("keep their MaskSubArea, and their samples at 8 bits, those of a 16-bit mask rounded")
        void subArea(int bits) {
            var builder = DngSpecGapsTest.linear(32, 16);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            var values = new long[3 * 4];
            for (var index = 0; index < values.length; index++) {
                values[index] = bits == 8 ? index * 21 : index * 5000 + 7;
            }
            builder.child().longs(254, MASK).longs(256, 3).longs(257, 4).shorts(258, bits).shorts(259, 1)
                    .shorts(262, 52527).shorts(277, 1).ascii(SEMANTIC_NAME, "subject").longs(SUB_AREA, 0, 2, 8, 4)
                    .strips(4, bits == 8 ? bytes(values) : DngBuilder.words(values, 16, ByteOrder.LITTLE_ENDIAN));
            var source = builder.write();
            var converted = convert(source);
            var mask = masks(converted).getFirst();
            assertArrayEquals(new long[]{0, 2, 8, 4}, mask.subArea());
            for (var index = 0; index < values.length; index++) {
                var expected = bits == 8 ? values[index] : Math.round(values[index] * 255 / 65535.0);
                assertEquals(expected, mask.value(index), "mask sample " + index);
            }
            DngSpecGapsTest.assertClose(DngSpecGapsTest.render(source), DngSpecGapsTest.render(converted), 1);
        }

        @Test
        @DisplayName("of floating-point samples are written at 8 bits")
        void floating() {
            var builder = DngSpecGapsTest.linear(32, 16);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            var values = new float[]{0, 0.25f, 0.5f, 1, 0.125f, 0.75f, 1, 1};
            var data = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (var value : values) {
                data.putFloat(value);
            }
            builder.child().longs(254, MASK).longs(256, 4).longs(257, 2).shorts(258, 32).shorts(259, 1)
                    .shorts(262, 52527).shorts(277, 1).shorts(339, 3).ascii(SEMANTIC_NAME, "subject")
                    .strips(2, data.array());
            var source = builder.write();
            var converted = convert(source);
            var mask = masks(converted).getFirst();
            assertFalse(mask.directory().has(339), "integer samples");
            for (var index = 0; index < values.length; index++) {
                assertEquals(Math.round(values[index] * 255), mask.value(index), "mask sample " + index);
            }
            DngSpecGapsTest.assertClose(DngSpecGapsTest.render(source), DngSpecGapsTest.render(converted), 1);
        }

        @Test
        @DisplayName("are left out when they cannot be decoded, and the table naming one stays without a mask")
        void undecodable() {
            var builder = DngSpecGapsTest.linear(16, 16);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            builder.child().longs(254, MASK).longs(256, 8).longs(257, 8).shorts(258, 8).shorts(259, 99)
                    .shorts(262, 52527).shorts(277, 1).ascii(SEMANTIC_NAME, "subject").strips(8, new byte[64]);
            DngSpecGapsTest.mask(builder, "subject", 8, 8, true);
            DngSpecGapsTest.mask(builder, "other", 4, 4, false);
            var source = builder.write();
            var converted = convert(source);
            assertEquals(List.of("other"), masks(converted).stream().map(Mask::name).toList());
            assertArrayEquals(DngSpecGapsTest.render(source), DngSpecGapsTest.render(converted));
            assertArrayEquals(DngSpecGapsTest.render(DngSpecGapsTest.linear(16, 16).write()),
                    DngSpecGapsTest.render(converted));
        }

        @Test
        @DisplayName("keep their order, and a table matches the first mask of its name as in the source")
        void order() {
            var builder = DngSpecGapsTest.linear(32, 16);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            DngSpecGapsTest.mask(builder, "subject", 8, 4, true);
            DngSpecGapsTest.mask(builder, "subject", 8, 4, false);
            DngSpecGapsTest.mask(builder, "sky", 2, 2, true);
            var source = builder.write();
            var converted = convert(source);
            var masks = masks(converted);
            assertEquals(List.of("subject", "subject", "sky"), masks.stream().map(Mask::name).toList());
            assertEquals(255, masks.get(0).value(0));
            assertEquals(0, masks.get(1).value(0));
            assertArrayEquals(DngSpecGapsTest.render(source), DngSpecGapsTest.render(converted));
        }

        @Test
        @DisplayName("grow with the edge margin by whole mask samples when the margin allows it")
        void wholeSampleMargin() {
            var builder = bayer(32, 16);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            rampMask(builder, "subject", 16, 8);
            var source = builder.write();
            var converted = convert(source, RawDecoderOptions.defaults(),
                    DngEncoderOptions.builder().preview(false).build());
            var raw = DngStructure.of(converted).raw();
            assertEquals(36, raw.get(256).longs(ByteOrder.LITTLE_ENDIAN)[0], "the width with its margin");
            var mask = masks(converted).getFirst();
            assertEquals(16, mask.width());
            assertEquals(8, mask.height());
            // a margin of 2 photosites is 1 sample of a mask of half the image's resolution
            assertArrayEquals(new long[]{1, 1, 18, 10}, mask.subArea());
            var original = masks(source).getFirst();
            for (var index = 0; index < 16 * 8; index++) {
                assertEquals(original.value(index), mask.value(index), "mask sample " + index);
            }
            assertRendersAsSource(source, converted, bayer(32, 16).write());
        }

        @Test
        @DisplayName("are resampled at the image's resolution when the margin is not a whole number of mask samples")
        void resampled() {
            var builder = bayer(30, 18);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable("subject", 0f, 0));
            rampMask(builder, "subject", 10, 6);
            var source = builder.write();
            var converted = convert(source, RawDecoderOptions.defaults(),
                    DngEncoderOptions.builder().preview(false).build());
            var mask = masks(converted).getFirst();
            var area = mask.subArea();
            assertEquals(34, area[2], "the full mask spans the widened image");
            assertEquals(22, area[3]);
            assertTrue(area[0] <= 2 && area[1] <= 2 && area[0] + mask.height() >= 20 && area[1] + mask.width() >= 32,
                    Arrays.toString(area));
            // the value at the center of the image's pixel (x, y) is the source mask's there: 3 pixels per sample
            for (var y = 0; y < 18; y++) {
                for (var x = 0; x < 30; x++) {
                    var u = (x + 0.5) / 30 * 10 - 0.5;
                    var v = (y + 0.5) / 18 * 6 - 0.5;
                    var expected = bilinear(original(10, 6), 10, 6, u, v);
                    var at = (int) ((y + 2 - area[0]) * mask.width() + (x + 2 - area[1]));
                    assertEquals(expected, mask.value(at), 1, "pixel " + x + ", " + y);
                }
            }
            assertRendersAsSource(source, converted, bayer(30, 18).write());
        }

        static double[] original(int width, int height) {
            var values = new double[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    values[y * width + x] = Math.min(255, (x * 255) / Math.max(1, width - 1) + y);
                }
            }
            return values;
        }

        static double bilinear(double[] values, int width, int height, double x, double y) {
            x = Math.clamp(x, 0, width - 1);
            y = Math.clamp(y, 0, height - 1);
            var x0 = (int) x;
            var y0 = (int) y;
            var x1 = Math.min(x0 + 1, width - 1);
            var y1 = Math.min(y0 + 1, height - 1);
            var tx = x - x0;
            var ty = y - y0;
            var upper = values[y0 * width + x0] + (values[y0 * width + x1] - values[y0 * width + x0]) * tx;
            var lower = values[y1 * width + x0] + (values[y1 * width + x1] - values[y1 * width + x0]) * tx;
            return upper + (lower - upper) * ty;
        }

        /**
         * The masked table darkens the rendering where the mask is, so both files must agree on where that is; the
         * pixels near the image's edges are left out, which the converted file demosaics from its margin.
         */
        static void assertRendersAsSource(byte[] source, byte[] converted, byte[] withoutTable) {
            var picture = RawDecoder.decode(MediaSource.of(source), RawDecoderOptions.defaults()).picture();
            var width = picture.width();
            var height = picture.height();
            var expected = DngSpecGapsTest.render(source);
            var actual = DngSpecGapsTest.render(converted);
            var plain = DngSpecGapsTest.render(withoutTable);
            assertEquals(expected.length, actual.length);
            var differing = 0;
            for (var y = 5; y < height - 5; y++) {
                for (var x = 5; x < width - 5; x++) {
                    for (var c = 0; c < 3; c++) {
                        var at = (y * width + x) * 3 + c;
                        assertEquals(expected[at], actual[at], 2, "pixel " + x + ", " + y);
                        differing += Math.abs(expected[at] - plain[at]) > 8 ? 1 : 0;
                    }
                }
            }
            assertTrue(differing > 30, "the masked table changes the rendering: " + differing);
        }
    }

    static byte[] bytes(long[] values) {
        var out = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = (byte) values[index];
        }
        return out;
    }

    @Nested
    @DisplayName("extra camera profiles")
    class Profiles {
        static final double[] ALTERNATE = DngSpecGapsTest.Profiles.ALTERNATE;
        static final double[] THIRD = {0.9, 0.05, 0, 0, 1.1, -0.1, 0.1, 0, 0.8};

        /** A profile whose undefined data depends on its byte order: an RGB table and a dynamic range. */
        static byte[] orderedProfile(String name, boolean little) {
            var profile = new DngBuilder(little, 0x4352);
            var order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            var tables = DngSpecGapsTest.constantTable(null, 0.4f, 0);
            if (little) {
                tables = reorder(tables);
            }
            var range = ByteBuffer.allocate(8).order(order).putShort((short) 1).putShort((short) 0).putFloat(0)
                    .array();
            profile.first.ascii(50936, name).signedRationals(50721, ALTERNATE).shorts(50778, 21)
                    .undefined(52543, tables).undefined(52551, range).ascii(50942, "Copyright " + name)
                    .longs(50941, 1);
            return profile.write();
        }

        /** Rewrites the big-endian table of {@link DngSpecGapsTest#constantTable} little-endian. */
        static byte[] reorder(byte[] big) {
            var in = ByteBuffer.wrap(big).order(ByteOrder.BIG_ENDIAN);
            var out = ByteBuffer.allocate(big.length).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(in.getInt()).putInt(in.getInt());
            var nameLength = in.getShort();
            out.putShort(nameLength);
            for (var index = 0; index < nameLength + 5; index++) {
                out.put(in.get());
            }
            while (in.hasRemaining()) {
                out.putFloat(in.getFloat());
            }
            return out.array();
        }

        static DngBuilder file(String asShot, boolean littleProfile) {
            var builder = DngSpecGapsTest.linear(16, 16);
            builder.first.ascii(50936, "Primary").ascii(50942, "Copyright Primary")
                    .blobs(EXTRA_PROFILES, orderedProfile("Alternate", littleProfile),
                            DngSpecGapsTest.Profiles.cameraProfile("Third", THIRD));
            if (asShot != null) {
                builder.first.ascii(50934, asShot);
            }
            return builder;
        }

        static RawDecoderOptions profile(String name) {
            return RawDecoderOptions.builder().cameraProfile(name).build();
        }

        static void assertSameProfiles(byte[] source, byte[] converted) {
            assertEquals(List.of("Primary", "Alternate", "Third"),
                    RawDecoder.info(MediaSource.of(converted)).cameraProfiles());
            for (var name : List.of("Primary", "Alternate", "Third")) {
                assertArrayEquals(DngSpecGapsTest.render(source, profile(name)),
                        DngSpecGapsTest.render(converted, profile(name)), name);
            }
        }

        static String asShot(byte[] dng) {
            var first = DngStructure.of(dng).first();
            return first.has(50934) ? first.get(50934).text() : null;
        }

        @ParameterizedTest(name = "little-endian profile {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("are kept in their own byte order and render as in the source")
        void kept(boolean little) {
            var source = file(null, little).write();
            var converted = convert(source);
            assertSameProfiles(source, converted);
            var structure = DngStructure.of(converted);
            assertEquals(2, structure.first().get(EXTRA_PROFILES).count());
            var offset = (int) structure.first().get(EXTRA_PROFILES).longs(ByteOrder.LITTLE_ENDIAN)[0];
            assertEquals(little ? 'I' : 'M', converted[offset], "the profile keeps its byte order");
            assertEquals("Copyright Primary", structure.first().get(50942).text());
            assertNull(asShot(converted));
            var renderings = List.of("Primary", "Alternate", "Third").stream()
                    .map(name -> DngSpecGapsTest.render(source, profile(name))).toList();
            assertFalse(Arrays.equals(renderings.get(0), renderings.get(1)));
            assertFalse(Arrays.equals(renderings.get(0), renderings.get(2)));
        }

        @Test
        @DisplayName("name the profile the decoding options render with as the as-shot profile")
        void chosen() {
            var source = file("Alternate", false).write();
            var converted = convert(source);
            assertSameProfiles(source, converted);
            assertEquals("Alternate", asShot(converted));
            assertArrayEquals(DngSpecGapsTest.render(source), DngSpecGapsTest.render(converted));
            for (var name : List.of("Third", "Primary")) {
                var chosen = convert(source, profile(name), DngEncoderOptions.defaults());
                assertSameProfiles(source, chosen);
                assertEquals(name, asShot(chosen));
                assertArrayEquals(DngSpecGapsTest.render(source, profile(name)), DngSpecGapsTest.render(chosen),
                        name);
            }
        }

        @Test
        @DisplayName("without tables of their own inherit the first directory's tables, as in the source")
        void inherited() {
            var builder = file("Third", true);
            builder.first.undefined(52543, DngSpecGapsTest.constantTable(null, 0.3f, 0));
            var source = builder.write();
            var converted = convert(source, profile("Alternate"), DngEncoderOptions.defaults());
            assertSameProfiles(source, converted);
            assertFalse(Arrays.equals(DngSpecGapsTest.render(source, profile("Third")),
                    DngSpecGapsTest.render(source, profile("Alternate"))));
        }

        @Test
        @DisplayName("are left out when the decoding options replace the color matrix")
        void replaced() {
            var source = file(null, true).write();
            var converted = convert(source, RawDecoderOptions.builder().colorMatrix(new double[]{
                    0.5, 0.3, 0.15, 0.25, 0.65, 0.1, 0.02, 0.1, 0.7}).build(), DngEncoderOptions.defaults());
            assertFalse(DngStructure.of(converted).first().has(EXTRA_PROFILES));
        }
    }

    @Nested
    @DisplayName("private data and maker notes")
    class PrivateData {
        @Test
        @DisplayName("a DNG source's private data is kept byte for byte")
        void dng() {
            var block = new byte[37];
            var name = "PENTAX \0MM".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, block, 0, name.length);
            for (var index = name.length; index < block.length; index++) {
                block[index] = (byte) (index * 7);
            }
            var builder = DngSpecGapsTest.linear(16, 16);
            builder.first.raw(PRIVATE_DATA, 1, block.length, block);
            var converted = convert(builder.write());
            var entry = DngStructure.of(converted).first().get(PRIVATE_DATA);
            assertEquals(1, entry.type());
            assertArrayEquals(block, entry.bytes());
            var replaced = convert(builder.write(), RawDecoderOptions.defaults(), DngEncoderOptions.builder()
                    .exif(ExifData.EMPTY).build());
            assertFalse(DngStructure.of(replaced).first().has(PRIVATE_DATA), "replaced EXIF drops the private data");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(MakerNoteSample.class)
        @DisplayName("a camera raw file's maker note is held in Adobe's form with its original position, a DNG "
                + "file's private data as it is")
        void makerNote(MakerNoteSample sample) {
            var path = sample.sample.fetch();
            var source = DngConvertTest.read(path);
            var converted = convert(source);
            var block = DngStructure.of(converted).first().get(PRIVATE_DATA).bytes();
            if (sample.dng) {
                assertArrayEquals(MakerNoteSample.privateData(source), block);
                return;
            }
            var buffer = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
            assertArrayEquals("Adobe\0MakN".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(block, 10));
            var note = Arrays.copyOfRange(block, 20, block.length);
            assertTrue(note.length >= 64, "a maker note of " + note.length + " bytes");
            assertEquals(6 + note.length, buffer.getInt(10), "the section length");
            assertTrue(block[14] == 'I' || block[14] == 'M');
            assertEquals(block[14], block[15]);
            // the note is a copy of the source's, whose TIFF header lies the recorded offset before it
            var position = MakerNoteSample.indexOf(source, note);
            assertTrue(position >= 0, "the maker note is a copy of the source's");
            var header = position - (buffer.getInt(16) & 0xFFFFFFFFL);
            assertTrue(header >= 0, "the original offset");
            var order = source[(int) header] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            assertEquals(block[14], source[(int) header], "the byte order of the TIFF header the offsets refer to");
            assertEquals(block[14], source[(int) header + 1]);
            var magic = ByteBuffer.wrap(source, (int) header + 2, 2).order(order).getShort();
            assertTrue(magic == 42 || magic == 0x55 || magic == 0x4F52, "a TIFF header, magic " + magic);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(MakerNoteSample.class)
        @DisplayName("exiftool reads the same maker note fields from the DNG file as from its source")
        void exiftool(MakerNoteSample sample) {
            Oracle.assume("exiftool");
            var source = sample.sample.fetch();
            var converted = DngValidateTest.save("makernote-" + sample.name() + ".dng",
                    convert(DngConvertTest.read(source)));
            var expected = makerNoteFields(source);
            var actual = makerNoteFields(converted);
            assertTrue(expected.size() >= 20, "the source's maker note fields: " + expected);
            var missing = new ArrayList<String>();
            for (var field : expected.entrySet()) {
                if (!field.getValue().equals(actual.get(field.getKey()))) {
                    missing.add(field.getKey() + " = " + field.getValue() + " but " + actual.get(field.getKey()));
                }
            }
            assertEquals(List.of(), missing, "fields that differ");
        }

        /**
         * Returns the maker note fields exiftool reads from a file, by group and name, other than the positions of
         * data inside the file (offsets, starts, lengths), which depend on where the maker note is.
         */
        static Map<String, String> makerNoteFields(Path file) {
            var text = new String(Oracle.run("exiftool", Duration.ofMinutes(2), new byte[0],
                    List.of("-a", "-G1", "-s", "-n", "-MakerNotes:all", file.toString())), StandardCharsets.UTF_8);
            var fields = new TreeMap<String, String>();
            var pattern = Pattern.compile("^\\[([^\\]]+)]\\s+(\\S+)\\s*: ?(.*)$");
            for (var line : text.lines().toList()) {
                var matcher = pattern.matcher(line);
                if (matcher.matches() && !matcher.group(2).matches(".*(Offset|Start|Length|Address).*")) {
                    fields.put(matcher.group(1) + ":" + matcher.group(2), matcher.group(3).strip());
                }
            }
            return fields;
        }
    }

    /**
     * Camera raw files with maker notes whose offsets refer to the file (Canon) or to a header of their own, a CR3
     * file's {@code CMT3} maker note, and DNG files with private data: a Pentax maker note and Adobe's form of a Canon one.
     */
    enum MakerNoteSample {
        CANON_SRAW_SMOKE(RawSamples.CR2_40D_SRAW, false),
        PANASONIC_RW2_SMOKE(RawSamples.RW2_S5M2, false),
        ADOBE_DNG_SMOKE(RawSamples.ADC_5D3_LINEAR_LOSSY, true),
        CANON_CR2(RawSamples.CR2_5D3, false),
        NIKON_NEF(RawSamples.NEF_D7000_LOSSLESS_14, false),
        PENTAX_PEF(RawSamples.PEF_K5, false),
        CANON_CR3(RawSamples.CR3_EOS_R6, false),
        PENTAX_DNG(RawSamples.DNG_PENTAX_K3, true);

        final RawSamples sample;
        final boolean dng;

        MakerNoteSample(RawSamples sample, boolean dng) {
            this.sample = sample;
            this.dng = dng;
        }

        /** Returns the {@code DNGPrivateData} of a DNG file's first directory. */
        static byte[] privateData(byte[] file) {
            var buffer = ByteBuffer.wrap(file).order(file[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            var at = (int) entry(buffer, buffer.getInt(4) & 0xFFFFFFFFL, PRIVATE_DATA);
            var count = buffer.getInt(at + 4);
            var offset = buffer.getInt(at + 8);
            return Arrays.copyOfRange(file, offset, offset + count);
        }

        /** Returns the first position of a byte sequence in a file, or -1. */
        static int indexOf(byte[] file, byte[] sequence) {
            outer:
            for (var at = 0; at + sequence.length <= file.length; at++) {
                for (var k = 0; k < sequence.length; k++) {
                    if (file[at + k] != sequence[k]) {
                        continue outer;
                    }
                }
                return at;
            }
            return -1;
        }

        /** Returns the position of the entry of a tag in a directory. */
        static long entry(ByteBuffer buffer, long directory, int tag) {
            var count = buffer.getShort((int) directory) & 0xFFFF;
            for (var index = 0; index < count; index++) {
                var at = (int) directory + 2 + index * 12;
                if ((buffer.getShort(at) & 0xFFFF) == tag) {
                    return at;
                }
            }
            throw new AssertionError("no tag " + tag);
        }
    }
}
