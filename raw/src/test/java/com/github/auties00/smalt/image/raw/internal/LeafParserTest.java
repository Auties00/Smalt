package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Leaf MOS parser on synthetic files built from the layout the CC0 samples show: one grayscale image in
 * the first directory, uncompressed or lossless JPEG of two components whose scan names each component by the
 * frame's identifier plus one, and the PKTS metadata packets (mosaic pattern, neutrals, rotation) with the XMP
 * camera name. The CC0 samples themselves are checked against Adobe DNG Converter in {@link VendorOracleTest}.
 */
class LeafParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    /**
     * A synthetic MOS file.
     */
    static final class Mos {
        int width = 24;
        int height = 10;
        boolean compressed;
        String mosaic = "1\n3\n2\n0";
        String neutrals = "3270\n2703\n3270\n2232";
        String rotation = "90";
        String model = "Leaf Aptus 22(LF10043    )/Mamiya 645 AFD";
        String fields;
        int[] photosites;

        Mos photosites(long seed) {
            var random = new SplittableRandom(seed);
            photosites = new int[width * height];
            for (var index = 0; index < photosites.length; index++) {
                photosites[index] = random.nextInt(8) == 0 ? random.nextInt(65536) : 9000 + random.nextInt(-200, 200);
            }
            return this;
        }

        static byte[] packet(String name, byte[] data) {
            var buffer = ByteBuffer.allocate(52 + data.length);
            buffer.put("PKTS".getBytes(StandardCharsets.US_ASCII)).putInt(1);
            var bytes = Arrays.copyOf(name.getBytes(StandardCharsets.US_ASCII), 40);
            buffer.put(bytes).putInt(data.length).put(data);
            return buffer.array();
        }

        static byte[] value(String text) {
            return (text + "\0").getBytes(StandardCharsets.US_ASCII);
        }

        static byte[] join(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        byte[] packets() {
            var capture = new ArrayList<byte[]>();
            capture.add(packet("CaptProf_version", value("80005")));
            if (mosaic != null) {
                capture.add(packet("CaptProf_mosaic_pattern", value(mosaic)));
            }
            if (fields != null) {
                capture.add(packet("CaptProf_image_fields", value(fields)));
            }
            var neutral = packet("NeutObj_neutrals", value(neutrals));
            var image = packet("ImgProf_rotation_angle", value(rotation));
            return join(packet("camera_profile", join(
                            packet("CamProf_capture_profile", join(capture.toArray(byte[][]::new))),
                            packet("CamProf_image_profile", join(packet("ImgProf_shoot_setup",
                                    packet("ShootObj_capture_setup", packet("CaptureObj_neutals", neutral))), image)))),
                    packet("JPEG_preview_data", new byte[5000]));
        }

        byte[] strip() {
            if (compressed) {
                return LosslessJpegWriter.encode(photosites, width / 2, height, 2);
            }
            var buffer = ByteBuffer.allocate(2 * photosites.length);
            for (var value : photosites) {
                buffer.putShort((short) value);
            }
            return buffer.array();
        }

        byte[] write() {
            var writer = new VendorTiffWriter();
            writer.little = false;
            var strip = strip();
            var xmp = ("<x:xmpmeta><rdf:RDF><rdf:Description><tiff:Make>Leaf</tiff:Make><tiff:Model>" + model
                    + "</tiff:Model></rdf:Description></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
            var packets = packets();
            List<VendorTiffWriter.Entry> entries = new ArrayList<>();
            entries.add(writer.longs(254, 0));
            entries.add(writer.longs(256, width));
            entries.add(writer.longs(257, height));
            entries.add(writer.shorts(258, 16));
            entries.add(writer.shorts(259, compressed ? 99 : 1));
            entries.add(writer.shorts(262, 1));
            entries.add(writer.shorts(277, 1));
            entries.add(writer.shorts(322, width));
            entries.add(writer.shorts(323, height));
            entries.add(writer.longs(324, 0));
            entries.add(writer.longs(325, strip.length));
            entries.add(new VendorTiffWriter.Entry(700, 1, xmp.length, xmp));
            entries.add(new VendorTiffWriter.Entry(34310, 7, packets.length, packets));
            var size = VendorTiffWriter.directory(entries, 8, writer.order()).length;
            var stripOffset = 8 + size + (size & 1);
            entries.set(9, writer.longs(324, stripOffset));
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8});
            out.writeBytes(VendorTiffWriter.directory(entries, 8, writer.order()));
            while (out.size() < stripOffset) {
                out.write(0);
            }
            out.writeBytes(strip);
            return out.toByteArray();
        }
    }

    /**
     * An encoder of lossless JPEG streams of predictor 1, with one fixed Huffman table of 5-bit codes and byte
     * stuffing, whose scan names each component by its frame identifier plus one, as Leaf's do.
     */
    static final class LosslessJpegWriter {
        private long buffer;
        private int count;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private void put(int value, int bits) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                buffer = buffer << 1 | (value >>> bit & 1);
                if (++count == 8) {
                    bytes.write((int) buffer & 0xFF);
                    if ((buffer & 0xFF) == 0xFF) {
                        bytes.write(0);
                    }
                    count = 0;
                    buffer = 0;
                }
            }
        }

        // encodes interleaved samples: width pixels of the given components per row
        static byte[] encode(int[] samples, int width, int height, int components) {
            var writer = new LosslessJpegWriter();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < components; c++) {
                        var index = (y * width + x) * components + c;
                        int predicted;
                        if (x == 0) {
                            predicted = y == 0 ? 32768 : samples[index - width * components];
                        } else {
                            predicted = samples[index - components];
                        }
                        var difference = (samples[index] - predicted) & 0xFFFF;
                        if (difference > 32768) {
                            difference -= 65536;
                        }
                        var category = difference == 32768 ? 16
                                : difference == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
                        writer.put(category, 5);
                        if (category > 0 && category < 16) {
                            writer.put(difference < 0 ? difference + (1 << category) - 1 : difference, category);
                        }
                    }
                }
            }
            writer.put(0x7F, (8 - writer.count) % 8);
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC3, 0, (byte) (8 + 3 * components), 16,
                    (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, (byte) components});
            for (var c = 0; c < components; c++) {
                out.writeBytes(new byte[]{(byte) c, 0x11, 0});
            }
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC4, 0, (byte) (2 + 17 + 17), 0});
            var counts = new byte[16];
            counts[4] = 17;
            out.writeBytes(counts);
            for (var category = 0; category <= 16; category++) {
                out.write(category);
            }
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, (byte) (6 + 2 * components), (byte) components});
            for (var c = 0; c < components; c++) {
                out.writeBytes(new byte[]{(byte) (c + 1), 0});
            }
            out.writeBytes(new byte[]{1, 0, 0});
            out.writeBytes(writer.bytes.toByteArray());
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
            return out.toByteArray();
        }
    }

    private static RawFile parse(byte[] data) {
        return RawParser.parse(MediaSource.of(data), MediaLimits.DEFAULT);
    }

    private static int[] read(RawFile file) {
        assertNotNull(file.sensor, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        var out = new int[plane.width * plane.height];
        for (var index = 0; index < out.length; index++) {
            out[index] = plane.integers[index] & 0xFFFF;
        }
        return out;
    }

    @ParameterizedTest(name = "compressed {0}")
    @CsvSource({"false", "true"})
    @DisplayName("an uncompressed or lossless JPEG file decodes to its photosites, with the metadata's filter array, "
            + "white balance, orientation and the model's white level")
    void decodes(boolean compressed) {
        var synthetic = new Mos().photosites(compressed ? 2 : 1);
        synthetic.compressed = compressed;
        var file = parse(synthetic.write());
        assertEquals(RawFormat.MOS, file.format);
        assertEquals("Leaf", file.make);
        assertEquals("Aptus 22", file.model);
        assertEquals(6, file.orientation);
        assertArrayEquals(synthetic.photosites, read(file));
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(new double[]{15000}, file.sensor.white);
        assertArrayEquals(new double[]{2703 / 3270.0, 1, 2232 / 3270.0}, file.color.asShotNeutral, 1e-12);
        assertEquals(compressed ? "lossless JPEG" : "uncompressed", file.sensor.compression);
    }

    @ParameterizedTest(name = "compressed {0}, {1} rows")
    @CsvSource({"false, 10", "true, 10", "false, 9", "true, 1"})
    @DisplayName("a sensor read as two fields, whose stored rows alternate between the top and the bottom field, "
            + "decodes to its rows in picture order")
    void twoFields(boolean compressed, int height) {
        var picture = new Mos();
        picture.height = height;
        picture.photosites(3);
        var synthetic = new Mos();
        synthetic.height = height;
        synthetic.compressed = compressed;
        synthetic.fields = String.join("\n", "1 0 0 1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0".split(" "));
        synthetic.photosites = new int[picture.photosites.length];
        var width = picture.width;
        for (int top = 0, stored = 0; stored < height; top++) {
            System.arraycopy(picture.photosites, top * width, synthetic.photosites, stored++ * width, width);
            if (stored < height) {
                System.arraycopy(picture.photosites, (height - 1 - top) * width, synthetic.photosites,
                        stored++ * width, width);
            }
        }
        assertArrayEquals(picture.photosites, read(parse(synthetic.write())));
        synthetic.fields = String.join("\n", "1", "0", "0", "0");
        assertArrayEquals(synthetic.photosites, read(parse(synthetic.write())));
    }

    @Test
    @DisplayName("a probe names the maker and the camera of the XMP packet, as the parser does")
    void probeTags() {
        var tags = Media.probe(MediaSource.of(new Mos().photosites(1).write())).tags();
        assertEquals("Leaf", tags.get("make"), tags.toString());
        assertEquals("Aptus 22", tags.get("model"), tags.toString());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"1 3 2 0, RGGB", "0 2 3 1, BGGR", "2 1 0 3, GRBG", "3 0 1 2, GBRG", "1 1 2 0, -", "1 3 2, -",
            "1 3 2 7, -", "a b c d, -"})
    @DisplayName("the mosaic pattern's codes give the filter array, and an invalid pattern gives none")
    void mosaic(String codes, String expected) {
        var cfa = LeafParser.cfa(codes.replace(' ', '\n'));
        if (expected.equals("-")) {
            assertNull(cfa);
        } else {
            assertEquals(expected, cfa.toString());
        }
    }

    @ParameterizedTest(name = "{0} degrees")
    @CsvSource({"0, 1", "90, 6", "180, 3", "270, 8", "45, 1", "x, 1"})
    @DisplayName("the rotation angle gives the orientation")
    void rotation(String angle, int orientation) {
        var synthetic = new Mos().photosites(3);
        synthetic.rotation = angle;
        assertEquals(orientation, parse(synthetic.write()).orientation);
    }

    @Test
    @DisplayName("another model takes the largest 16-bit value as its white level, and a file without neutrals "
            + "estimates its balance")
    void otherModel() {
        var synthetic = new Mos().photosites(4);
        synthetic.model = "Leaf Valeo 22";
        synthetic.neutrals = "0";
        var file = parse(synthetic.write());
        assertEquals("Valeo 22", file.model);
        assertArrayEquals(new double[]{65535}, file.sensor.white);
        assertTrue(file.sensor.estimateBalance);
    }

    @Test
    @DisplayName("a file without a mosaic pattern is described and not decoded")
    void noMosaic() {
        var synthetic = new Mos().photosites(5);
        synthetic.mosaic = null;
        var file = parse(synthetic.write());
        assertEquals(RawFormat.MOS, file.format);
        assertNull(file.sensor);
        assertNotNull(file.unsupported);
    }

    @Test
    @DisplayName("packets whose length runs past their parent are ignored, and the values read before them are kept")
    void damagedPackets() {
        var good = Mos.packet("CaptProf_mosaic_pattern", Mos.value("1\n3\n2\n0"));
        var bad = Mos.packet("NeutObj_neutrals", Mos.value("1\n2\n3\n4"));
        ByteBuffer.wrap(bad).order(ByteOrder.BIG_ENDIAN).putInt(48, 1 << 30);
        var data = Mos.join(good, bad);
        var writer = new VendorTiffWriter();
        writer.little = false;
        var entries = List.of(writer.longs(254, 0), new VendorTiffWriter.Entry(34310, 7, data.length, data));
        var tiff = Mos.join(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8}, VendorTiffWriter.directory(entries, 8,
                ByteOrder.BIG_ENDIAN));
        var reader = TiffReader.open(MediaSource.of(tiff), 0, MediaLimits.DEFAULT);
        var values = LeafParser.packets(reader.directory(reader.firstDirectory()));
        assertEquals("1\n3\n2\n0", values.get("CaptProf_mosaic_pattern"));
        assertNull(values.get("NeutObj_neutrals"));
    }

    @Test
    @DisplayName("the scan's component selectors are renumbered only when each names the frame's identifier plus one")
    void scanSelectors() {
        var stream = LosslessJpegWriter.encode(new int[]{1, 2, 3, 4}, 1, 2, 2);
        var fixed = stream.clone();
        TiffSamples.matchScanComponents(fixed);
        var sos = indexOf(fixed, (byte) 0xDA);
        assertEquals(0, fixed[sos + 3]);
        assertEquals(1, fixed[sos + 5]);
        var standard = stream.clone();
        standard[sos + 3] = 0;
        standard[sos + 5] = 1;
        var unchanged = standard.clone();
        TiffSamples.matchScanComponents(unchanged);
        assertArrayEquals(standard, unchanged);
        var other = stream.clone();
        other[sos + 5] = 5;
        var kept = other.clone();
        TiffSamples.matchScanComponents(kept);
        assertArrayEquals(other, kept);
    }

    private static int indexOf(byte[] data, byte marker) {
        for (var index = 0; index + 1 < data.length; index++) {
            if (data[index] == (byte) 0xFF && data[index + 1] == marker) {
                return index + 2;
            }
        }
        throw new AssertionError("no marker");
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var compressed = new Mos().photosites(7);
        compressed.compressed = true;
        var originals = List.of(new Mos().photosites(8).write(), compressed.write());
        var random = new SplittableRandom(24);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals.get(round & 1).clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[4 + random.nextInt(bytes.length - 4)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    RawDecoder.info(MediaSource.of(input), limits);
                    RawDecoder.decode(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the Leaf fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "mos", "valid-uncompressed.mos", new Mos().photosites(1).write());
        var compressed = new Mos().photosites(2);
        compressed.compressed = true;
        FuzzSeeds.write(FUZZ_TARGET, "mos", "valid-lossless-jpeg.mos", compressed.write());
    }
}
