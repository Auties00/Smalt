package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.RawSamples;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Canon CRW parser on synthetic files, built with the record layout and the stream
 * coding the CC0 samples show and encoded with the tables of {@link CrwTables} (every table set,
 * 10 and 12-bit samples, the geometry, levels, white balance and shot records, damaged and
 * unsupported files, random damage), and the metadata and previews of three sample files.
 */
class CrwParserTest {
    /** The fuzz test class whose seeds the synthetic files feed. */
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // a synthetic CRW file: the sensor data and a JPEG preview in the root heap, the records in sub-heaps
    private static final class Crw {
        int width = 80;
        int height = 12;
        int left = 4;
        int top = 2;
        int right = 75;
        int bottom = 11;
        int[] mask = {0, 2, 3, 11};
        int bits = 12;
        int set = 2;
        Integer whiteSample = null;
        boolean decoderTable = true;
        boolean sensorInfo = true;
        boolean rawData = true;
        String make = "Canon";
        String model = "Canon TEST";
        int rotation = 0;
        long time = 1_000_000_000L;
        short[] shot = shot(160, 139, 181, 0);
        short[] camera = settings(85, 24, 1);
        short[] focal = {0, 50, 914, 610};
        short[] balance = null;
        short[] samples;

        Crw samples(long seed, int spread) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            var maximum = (1 << bits) - 1;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var base = (bits == 12 ? 800 : 200) + 6 * x + 10 * y + ((x & 1) == 0 ? 0 : 40);
                    samples[y * width + x] = (short) Math.clamp(base + random.nextInt(-spread, spread + 1), 0, maximum);
                }
            }
            return this;
        }

        byte[] write() {
            var little = ByteOrder.LITTLE_ENDIAN;
            var exif = new Heap();
            if (sensorInfo) {
                var info = new short[17];
                info[0] = 34;
                info[1] = (short) width;
                info[2] = (short) height;
                info[5] = (short) left;
                info[6] = (short) top;
                info[7] = (short) right;
                info[8] = (short) bottom;
                for (var index = 0; index < 4; index++) {
                    info[9 + index] = (short) mask[index];
                }
                exif.add(0x1031, words(info));
            }
            if (decoderTable) {
                exif.add(0x1835, ByteBuffer.allocate(16).order(little).putInt(set).putInt(0).putInt(514).putInt(0).array());
            }
            var sampleBits = whiteSample != null ? whiteSample : bits;
            if (sampleBits != 12 || whiteSample != null) {
                exif.add(0x1030, words(new short[]{118, 8, 8, 0, 0, (short) sampleBits, 0}));
            }
            exif.add(0x102A, words(shot));
            exif.add(0x102D, words(camera));
            exif.add(0x1029, words(focal));
            if (balance != null) {
                exif.add(0x10A9, words(balance));
            }
            var cameraObject = new Heap();
            cameraObject.add(0x080A, (make + "\0" + model + "\0").getBytes(StandardCharsets.ISO_8859_1));
            var props = new Heap();
            props.add(0x1810, ByteBuffer.allocate(28).order(little).putInt(width).putInt(height).putFloat(1)
                    .putInt(rotation).putInt(8).putInt(24).putInt(257).array());
            props.add(0x180E, ByteBuffer.allocate(12).order(little).putInt((int) time).putInt(0).putInt(0).array());
            props.add(0x2807, cameraObject.write());
            props.add(0x300B, exif.write());
            var root = new Heap();
            if (rawData) {
                root.add(0x2005, sensorData());
            }
            root.add(0x2007, jpeg(48, 32));
            root.add(0x300A, props.write());
            var heap = root.write();
            return ByteBuffer.allocate(26 + heap.length).order(little).put((byte) 'I').put((byte) 'I').putInt(26)
                    .put("HEAPCCDR".getBytes(StandardCharsets.US_ASCII)).putInt(0x00010002).putLong(0).put(heap).array();
        }

        // the low bits of a 12-bit file, the 514-byte gap and the compressed high bits
        byte[] sensorData() {
            var out = new ByteArrayOutputStream();
            var high = new int[samples.length];
            for (var index = 0; index < samples.length; index++) {
                high[index] = bits == 12 ? samples[index] >> 2 : samples[index];
            }
            if (bits == 12) {
                var low = new byte[(samples.length + 3) / 4];
                for (var index = 0; index < samples.length; index++) {
                    low[index >> 2] |= (byte) ((samples[index] & 3) << ((index & 3) * 2));
                }
                out.writeBytes(low);
            }
            out.writeBytes(new byte[514]);
            // a table set the decoder does not know is coded with set 2, which it never reads
            out.writeBytes(encode(high, width, Math.min(set, 2)));
            return out.toByteArray();
        }
    }

    // a CIFF heap: the values, then the directory, then the size of the values
    private static final class Heap {
        private final ByteArrayOutputStream values = new ByteArrayOutputStream();
        private final ByteArrayOutputStream entries = new ByteArrayOutputStream();
        private int count;

        void add(int kind, byte[] value) {
            var offset = values.size();
            values.writeBytes(value);
            if ((value.length & 1) != 0) {
                values.write(0);
            }
            entries.writeBytes(ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putShort((short) kind)
                    .putInt(value.length).putInt(offset).array());
            count++;
        }

        byte[] write() {
            var size = values.size();
            var out = new ByteArrayOutputStream();
            out.writeBytes(values.toByteArray());
            out.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) count).array());
            out.writeBytes(entries.toByteArray());
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array());
            return out.toByteArray();
        }
    }

    private static byte[] words(short[] values) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putShort(value);
        }
        return buffer.array();
    }

    private static short[] shot(int iso, int aperture, int exposure, int balance) {
        var shot = new short[25];
        shot[0] = 50;
        shot[2] = (short) iso;
        shot[4] = (short) aperture;
        shot[5] = (short) exposure;
        shot[7] = (short) balance;
        return shot;
    }

    private static short[] settings(int longest, int shortest, int units) {
        var settings = new short[32];
        settings[0] = 64;
        settings[23] = (short) longest;
        settings[24] = (short) shortest;
        settings[25] = (short) units;
        return settings;
    }

    // a baseline JPEG image of one flat color
    private static byte[] jpeg(int width, int height) {
        return JpegEncoder.encode(RgbaPicture.allocate(width, height, false));
    }

    // the codes of a DHT-form table: value to its code and length
    private static Map<Integer, int[]> codes(int[] spec) {
        var codes = new HashMap<Integer, int[]>();
        var code = 0;
        var index = 16;
        for (var length = 1; length <= 16; length++) {
            for (var count = 0; count < spec[length - 1]; count++) {
                codes.put(spec[index++], new int[]{code, length});
                code++;
            }
            code <<= 1;
        }
        return codes;
    }

    // encodes the ten high bits of every photosite the way the samples' streams code them
    private static byte[] encode(int[] high, int width, int set) {
        var dc = codes(CrwTables.TABLES[2 * set]);
        var ac = codes(CrwTables.TABLES[2 * set + 1]);
        var bits = new BitWriter();
        var values = new int[64];
        var previous = 0;
        for (var start = 0; start < high.length; start += 64) {
            for (var index = 0; index < 64; index++) {
                var position = start + index;
                values[index] = position >= high.length ? 0
                        : high[position] - (position % width < 2 ? 512 : high[position - 2]);
            }
            var difference = values[0] - previous;
            previous = values[0];
            var category = category(difference);
            bits.code(dc.get(category));
            bits.write(magnitude(difference, category), category);
            var run = 0;
            for (var index = 1; index < 64; index++) {
                if (values[index] == 0) {
                    run++;
                    continue;
                }
                while (run > 15) {
                    bits.code(ac.get(0xF0));
                    run -= 16;
                }
                var size = category(values[index]);
                bits.code(ac.get((run << 4) | size));
                bits.write(magnitude(values[index], size), size);
                run = 0;
            }
            if (run > 0) {
                bits.code(ac.get(0));
            }
        }
        return bits.finish();
    }

    private static int category(int value) {
        return 32 - Integer.numberOfLeadingZeros(Math.abs(value));
    }

    private static int magnitude(int value, int category) {
        return value >= 0 ? value : value + (1 << category) - 1;
    }

    // writes bits most significant first, stuffing a zero byte after every FF byte
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void code(int[] code) {
            assertNotNull(code, "the synthetic image needs a code no sample has shown");
            write(code[0], code[1]);
        }

        void write(int value, int bits) {
            buffer = (buffer << bits) | (value & ((1L << bits) - 1));
            count += bits;
            while (count >= 8) {
                count -= 8;
                put((int) (buffer >>> count) & 0xFF);
            }
        }

        private void put(int value) {
            out.write(value);
            if (value == 0xFF) {
                out.write(0);
            }
        }

        byte[] finish() {
            if (count > 0) {
                write((1 << (8 - count)) - 1, 8 - count);
            }
            out.write(0xFF);
            out.write(0xD9);
            return out.toByteArray();
        }
    }

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] decode(RawFile file) {
        var sensor = file.sensor;
        var plane = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    @ParameterizedTest(name = "table set {0}, {1}-bit, spread {2}")
    @CsvSource({"0, 12, 3", "0, 10, 3", "1, 12, 1", "1, 10, 1", "2, 12, 60", "2, 10, 60", "2, 12, 0"})
    @DisplayName("decodes every table set and both sample depths exactly")
    void coding(int set, int bits, int spread) {
        var crw = new Crw();
        crw.set = set;
        crw.bits = bits;
        var file = parse(crw.samples(set * 10L + bits, spread).write());
        assertEquals(RawFormat.CRW, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertEquals(bits, file.sensor.bitsPerSample);
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(crw.samples, decode(file));
    }

    @Test
    @DisplayName("describes the image area, the levels, the shot and the camera")
    void description() {
        var crw = new Crw().samples(1, 3);
        var file = parse(crw.write());
        assertArrayEquals(new int[]{2, 4, 12, 76}, file.sensor.activeArea);
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertEquals("Canon", file.make);
        assertEquals("Canon TEST", file.model);
        assertEquals("2001:09:09 01:46:40", file.captureTime);
        assertEquals(1, file.orientation);
        assertEquals(100, file.iso);
        assertEquals(Math.pow(2, 139 / 64.0), file.fNumber, 1e-12);
        assertEquals(Math.pow(2, -181 / 32.0), file.exposureTime, 1e-12);
        assertEquals(50, file.focalLength, 1e-12);
        assertEquals("24.0-85.0 mm", file.lens);
        assertTrue(file.sensor.estimateBalance);
        assertNull(file.color.asShotNeutral);
        assertEquals(1, file.previews.size());
        assertEquals(48, file.previews.getFirst().width());
        decode(file);
        // the black level of each cell of the pattern is the mean of the masked rectangle
        var sums = new double[4];
        var counts = new int[4];
        for (var y = crw.mask[1]; y <= crw.mask[3]; y++) {
            for (var x = crw.mask[0]; x <= crw.mask[2]; x++) {
                sums[(y & 1) * 2 + (x & 1)] += crw.samples[y * crw.width + x];
                counts[(y & 1) * 2 + (x & 1)]++;
            }
        }
        for (var cell = 0; cell < 4; cell++) {
            assertEquals(sums[cell] / counts[cell], file.sensor.black[cell], 1e-9);
        }
        var info = RawDecoder.info(MediaSource.of(crw.write()));
        assertEquals(72, info.width());
        assertEquals(10, info.height());
        assertEquals(80, info.sensorWidth());
        assertTrue(info.isDecodable());
    }

    @ParameterizedTest(name = "rotation {0}")
    @CsvSource({"0, 1", "90, 6", "180, 3", "270, 8"})
    @DisplayName("reads the rotation as the EXIF orientation")
    void rotation(int rotation, int orientation) {
        var crw = new Crw();
        crw.rotation = rotation;
        assertEquals(orientation, parse(crw.samples(2, 3).write()).orientation);
    }

    @ParameterizedTest(name = "white balance setting {0}")
    @CsvSource({"0, 1", "1, 5", "8, 9", "2, 13", "9, 33"})
    @DisplayName("reads the white balance levels of the shot's setting")
    void balance(int setting, int slot) {
        var crw = new Crw();
        crw.balance = new short[41];
        for (var index = 1; index < 41; index++) {
            crw.balance[index] = (short) (500 + index);
        }
        crw.balance[slot] = 400;
        crw.balance[slot + 1] = 800;
        crw.balance[slot + 2] = 802;
        crw.balance[slot + 3] = 500;
        crw.shot = shot(160, 139, 181, setting);
        var file = parse(crw.samples(3, 3).write());
        assertArrayEquals(new double[]{801 / 400.0, 1, 801 / 500.0}, file.color.asShotNeutral, 1e-12);
        assertFalse(file.sensor.estimateBalance);
    }

    @Test
    @DisplayName("knows the white level of the EOS bodies that saturate at 4000 and the PowerShot G1's colors and matrix")
    void models() {
        var eos = new Crw();
        eos.model = "Canon EOS 10D";
        assertArrayEquals(new double[]{4000}, parse(eos.samples(4, 3).write()).sensor.white);
        var g1 = new Crw();
        g1.model = "Canon PowerShot G1";
        g1.bits = 10;
        var bytes = g1.samples(4, 3).write();
        var file = parse(bytes);
        assertEquals("GMYC", file.sensor.cfa.toString());
        assertArrayEquals(new double[]{1023}, file.sensor.white);
        assertArrayEquals(g1.samples, decode(file));
        assertArrayEquals(FourColorMatrices.find("Canon", "PowerShot G1").d65(), file.color.colorMatrix[0]);
        assertFalse(file.color.generic);
        assertNull(file.color.asShotNeutral);
        assertTrue(file.sensor.estimateBalance);
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
        assertTrue(info.isColorCalibrated());
        assertEquals(4, RawDecoder.decodeRaw(MediaSource.of(bytes)).cameraNeutral().length);
        assertTrue(RawDecoder.decode(MediaSource.of(bytes)).picture().width() > 0);
    }

    @Test
    @DisplayName("reads a file without a model name as an RGGB sensor")
    void noModel() {
        var crw = new Crw();
        crw.model = "";
        var file = parse(crw.samples(5, 3).write());
        assertNull(file.model);
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertArrayEquals(crw.samples, decode(file));
    }

    @Test
    @DisplayName("finds the end of a source whose size is unknown")
    void unknownSize() {
        var crw = new Crw().samples(5, 3);
        var bytes = crw.write();
        var source = new MediaSource() {
            @Override
            public long size() {
                return -1;
            }

            @Override
            public int read(long position, byte[] buffer, int offset, int length) {
                if (position >= bytes.length) {
                    return -1;
                }
                var count = (int) Math.min(length, bytes.length - position);
                System.arraycopy(bytes, (int) position, buffer, offset, count);
                return count;
            }

            @Override
            public void close() {
            }
        };
        assertArrayEquals(crw.samples, decode(RawParser.parse(source, MediaLimits.DEFAULT)));
    }

    @Test
    @DisplayName("decodes a truncated stream to its end")
    void truncated() {
        var crw = new Crw();
        crw.bits = 10;
        crw.samples(6, 3);
        var bytes = crw.write();
        var data = crw.sensorData();
        var cut = Arrays.copyOf(bytes, bytes.length);
        // the stream ends at the root heap's preview: overwrite its last bytes with a marker
        var streamEnd = 26 + data.length;
        cut[streamEnd - 40] = (byte) 0xFF;
        cut[streamEnd - 39] = (byte) 0xD9;
        var decoded = decode(parse(cut));
        var rows = crw.width * (crw.height / 2);
        assertArrayEquals(Arrays.copyOf(crw.samples, rows), Arrays.copyOf(decoded, rows));
    }

    @Test
    @DisplayName("rejects variants no sample has shown as unsupported")
    void unsupported() {
        var set = new Crw();
        set.set = 3;
        var noTable = new Crw();
        noTable.decoderTable = false;
        var depth = new Crw();
        depth.whiteSample = 14;
        for (var crw : new Crw[]{set, noTable, depth}) {
            var bytes = crw.samples(7, 3).write();
            var info = RawDecoder.info(MediaSource.of(bytes));
            assertEquals(RawFormat.CRW, info.format());
            assertFalse(info.isDecodable());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes)));
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
        }
    }

    @Test
    @DisplayName("rejects a damaged heap or a file without sensor data as malformed")
    void malformed() {
        var good = new Crw().samples(8, 3).write();
        var header = good.clone();
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(2, Integer.MAX_VALUE);
        assertThrows(MediaException.Malformed.class, () -> parse(header));
        var directory = good.clone();
        ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN).putInt(good.length - 4, good.length);
        assertThrows(MediaException.Malformed.class, () -> parse(directory));
        var noRaw = new Crw();
        noRaw.rawData = false;
        assertThrows(MediaException.Malformed.class, () -> parse(noRaw.samples(8, 3).write()));
        var noInfo = new Crw();
        noInfo.sensorInfo = false;
        assertThrows(MediaException.Malformed.class, () -> parse(noInfo.samples(8, 3).write()));
        var overrun = new Crw();
        overrun.samples(8, 3);
        overrun.height = 400;
        assertThrows(MediaException.Malformed.class, () -> parse(overrunFile(overrun)));
        assertThrows(MediaException.Malformed.class, () -> parse(Arrays.copyOf(good, 30)));
    }

    // a 12-bit file whose geometry promises more photosites than its low bits hold
    private static byte[] overrunFile(Crw crw) {
        var height = crw.height;
        crw.height = 12;
        var data = crw.write();
        crw.height = height;
        var file = data.clone();
        // patch the sensor geometry's height (word 2 of record 0x1031) in place
        for (var index = 0; index + 6 < file.length; index++) {
            if (file[index] == 34 && file[index + 1] == 0 && file[index + 2] == 80 && file[index + 3] == 0
                    && file[index + 4] == 12 && file[index + 5] == 0) {
                file[index + 4] = (byte) height;
                file[index + 5] = (byte) (height >> 8);
                return file;
            }
        }
        throw new AssertionError("no sensor geometry");
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var ten = new Crw();
        ten.bits = 10;
        ten.set = 0;
        var originals = new byte[][]{new Crw().samples(9, 30).write(), ten.samples(10, 3).write()};
        var random = new SplittableRandom(21);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals[round % 2].clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    RawDecoder.info(MediaSource.of(input), limits);
                    RawDecoder.extractPreview(MediaSource.of(input), limits);
                    RawDecoder.decodeRaw(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @DisplayName("describes an EOS D30 file: the shot settings and the large preview")
    void d30() {
        try (var source = MediaSource.of(RawSamples.CRW_D30.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals("Canon", info.make().orElseThrow());
            assertEquals("2016:12:23 17:05:30", info.captureTimeText().orElseThrow());
            assertEquals(Math.pow(2, -181 / 32.0), info.exposureTime().orElseThrow(), 1e-9);
            assertEquals(1.4, info.fNumber().orElseThrow(), 0.01);
            assertEquals(50, info.focalLength().orElseThrow(), 1e-9);
            assertEquals("50.0 mm", info.lens().orElseThrow());
            assertEquals(1, info.previews().size());
            assertEquals(1440, info.previews().getFirst().width());
            assertEquals(960, info.previews().getFirst().height());
            var preview = RawDecoder.extractPreview(source).orElseThrow();
            assertEquals(164136, preview.length);
            assertEquals(0xFF, preview[0] & 0xFF);
            assertEquals(0xD8, preview[1] & 0xFF);
        }
    }

    @Test
    @DisplayName("describes a PowerShot G5 file: focal units, the preview and the thumbnail")
    void g5() {
        try (var source = MediaSource.of(RawSamples.CRW_G5.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals(230 / 32.0, info.focalLength().orElseThrow(), 1e-9);
            assertEquals("7.2-28.8 mm", info.lens().orElseThrow());
            assertEquals(4.0, info.fNumber().orElseThrow(), 1e-9);
            assertEquals(2, info.previews().size());
            var sizes = info.previews().stream().map(preview -> preview.width() + "x" + preview.height()).sorted().toList();
            assertEquals(java.util.List.of("160x120", "640x480"), sizes);
            var hinted = RawDecoder.decode(source, RawDecoderOptions.builder().minSize(600, 400).build()).picture();
            assertEquals(640, hinted.width());
            assertEquals(480, hinted.height());
        }
    }

    @Test
    @DisplayName("describes an EOS 300D file shot in portrait orientation")
    void portrait() {
        try (var source = MediaSource.of(RawSamples.CRW_300D.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals(com.github.auties00.smalt.image.Orientation.ofExif(8), info.orientation());
            assertEquals("Canon EOS 300D DIGITAL", info.model().orElseThrow());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the CRW fuzz target")
    void fuzzSeeds() {
        var plain = new Crw().samples(1, 3);
        FuzzSeeds.write(FUZZ_TARGET, "crw", "valid-set2-12bit.crw", plain.write());
        var older = new Crw();
        older.set = 0;
        older.bits = 10;
        FuzzSeeds.write(FUZZ_TARGET, "crw", "valid-set0-10bit.crw", older.samples(10, 3).write());
    }
}
