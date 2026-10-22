package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
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
 * Checks the Minolta MRW parser on synthetic files built from the block layout the CC0 samples
 * show (both sample storage methods, the crop, the white balance and ISO blocks, damaged and
 * unsupported headers, random damage), and the metadata of two sample files: a DiMAGE 5, whose EXIF block gives
 * the shot in APEX units and whose preview is a maker note value, and a Dynax 7D, whose preview
 * the maker note points to and letterboxes the image.
 */
class MrwParserTest {
    /** The fuzz test class whose seeds the synthetic files feed. */
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // a synthetic MRW file: the header blocks, then the sensor data
    private static final class Mrw {
        int sensorWidth = 16;
        int sensorHeight = 6;
        int imageWidth = 8;
        int imageHeight = 4;
        int stored = 12;
        int bits = 12;
        int storage = 0x59;
        int pattern = 1;
        int[] balance = {2, 2, 2, 2, 400, 256, 256, 512};
        int isoCode = 56;
        String make = "KONICA MINOLTA";
        String model = "TEST";
        boolean prd = true;
        short[] samples;

        Mrw samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[sensorWidth * sensorHeight];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(1 << bits);
            }
            return this;
        }

        byte[] write() {
            var header = new ByteArrayOutputStream();
            if (prd) {
                var body = ByteBuffer.allocate(24);
                body.put("21810002".getBytes(StandardCharsets.US_ASCII));
                body.putShort((short) sensorHeight).putShort((short) sensorWidth);
                body.putShort((short) imageHeight).putShort((short) imageWidth);
                body.put((byte) stored).put((byte) bits).put((byte) storage).put(new byte[4]).put((byte) pattern);
                block(header, "PRD", body.array());
            }
            if (balance != null) {
                var body = ByteBuffer.allocate(12);
                for (var index = 0; index < 4; index++) {
                    body.put((byte) balance[index]);
                }
                for (var index = 4; index < 8; index++) {
                    body.putShort((short) balance[index]);
                }
                block(header, "WBG", body.array());
            }
            var rif = new byte[64];
            rif[6] = (byte) isoCode;
            block(header, "RIF", rif);
            if (make != null) {
                block(header, "TTW", tiff(make, model));
            }
            var head = header.toByteArray();
            var data = sensorData();
            return ByteBuffer.allocate(8 + head.length + data.length)
                    .put(new byte[]{0, 'M', 'R', 'M'}).putInt(head.length).put(head).put(data).array();
        }

        private byte[] sensorData() {
            if (storage == 0x59) {
                var data = new byte[samples.length / 2 * 3];
                for (var pair = 0; pair < samples.length / 2; pair++) {
                    var a = samples[2 * pair];
                    var b = samples[2 * pair + 1];
                    data[3 * pair] = (byte) (a >> 4);
                    data[3 * pair + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                    data[3 * pair + 2] = (byte) b;
                }
                return data;
            }
            var data = ByteBuffer.allocate(samples.length * 2);
            for (var sample : samples) {
                data.putShort(sample);
            }
            return data.array();
        }

        private static void block(ByteArrayOutputStream header, String name, byte[] body) {
            header.writeBytes(ByteBuffer.allocate(8).put((byte) 0).put(name.getBytes(StandardCharsets.US_ASCII))
                    .putInt(body.length).array());
            header.writeBytes(body);
        }

        // a big-endian TIFF structure with the make and the model in its first directory
        private static byte[] tiff(String make, String model) {
            var makeBytes = (make + "\0").getBytes(StandardCharsets.US_ASCII);
            var modelBytes = (model + "\0").getBytes(StandardCharsets.US_ASCII);
            var values = 8 + 2 + 2 * 12 + 4;
            var buffer = ByteBuffer.allocate(values + makeBytes.length + modelBytes.length);
            buffer.put(new byte[]{'M', 'M', 0, 42}).putInt(8).putShort((short) 2);
            buffer.putShort((short) 271).putShort((short) 2).putInt(makeBytes.length).putInt(values);
            buffer.putShort((short) 272).putShort((short) 2).putInt(modelBytes.length).putInt(values + makeBytes.length);
            buffer.putInt(0).put(makeBytes).put(modelBytes);
            return buffer.array();
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

    @ParameterizedTest(name = "storage method {0}")
    @ValueSource(ints = {0x52, 0x59})
    @DisplayName("decodes both storage methods exactly")
    void storage(int storage) {
        var mrw = storage == 0x52 ? words() : new Mrw();
        var file = parse(mrw.samples(storage).write());
        assertEquals(RawFormat.MRW, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertEquals(12, file.sensor.bitsPerSample);
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(mrw.samples, decode(file));
    }

    @Test
    @DisplayName("describes the crop, the levels, the white balance, the ISO speed and the camera")
    void description() {
        var file = parse(new Mrw().samples(1).write());
        assertArrayEquals(new double[]{4, 1, 8, 4}, file.sensor.defaultCrop);
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertArrayEquals(new double[]{256.0 / 400, 1, 256.0 / 512}, file.color.asShotNeutral, 1e-12);
        assertEquals(200, file.iso);
        assertEquals("KONICA MINOLTA", file.make);
        assertEquals("TEST", file.model);
        var info = RawDecoder.info(MediaSource.of(new Mrw().samples(1).write()));
        assertEquals(8, info.width());
        assertEquals(4, info.height());
        assertEquals(16, info.sensorWidth());
        assertTrue(info.isDecodable());
    }

    @Test
    @DisplayName("ignores a white balance whose scale codes differ")
    void balanceScales() {
        var mrw = new Mrw();
        mrw.balance = new int[]{2, 3, 2, 2, 400, 256, 256, 512};
        assertNull(parse(mrw.samples(2).write()).color.asShotNeutral);
    }

    @Test
    @DisplayName("decodes a truncated file to its end")
    void truncated() {
        var mrw = new Mrw().samples(3);
        var bytes = mrw.write();
        var file = parse(Arrays.copyOf(bytes, bytes.length - 12));
        var decoded = decode(file);
        var full = mrw.sensorWidth * (mrw.sensorHeight - 1);
        assertArrayEquals(Arrays.copyOf(mrw.samples, full), Arrays.copyOf(decoded, full));
    }

    @Test
    @DisplayName("rejects variants no sample has shown as unsupported")
    void unsupported() {
        var gbrg = new Mrw();
        gbrg.pattern = 4;
        var words16 = new Mrw();
        words16.storage = 0x52;
        words16.stored = 16;
        words16.bits = 16;
        var sony = new Mrw();
        sony.make = "SONY";
        for (var mrw : new Mrw[]{gbrg, words16, sony}) {
            var bytes = mrw.samples(4).write();
            var info = RawDecoder.info(MediaSource.of(bytes));
            assertEquals(RawFormat.MRW, info.format());
            assertFalse(info.isDecodable());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes)));
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
        }
    }

    @Test
    @DisplayName("rejects a damaged header as malformed")
    void malformed() {
        var good = new Mrw().samples(5).write();
        var overrun = good.clone();
        ByteBuffer.wrap(overrun).putInt(4, Integer.MAX_VALUE);
        assertThrows(MediaException.Malformed.class, () -> parse(overrun));
        var shortHeader = good.clone();
        ByteBuffer.wrap(shortHeader).putInt(4, 4);
        assertThrows(MediaException.Malformed.class, () -> parse(shortHeader));
        var block = good.clone();
        ByteBuffer.wrap(block).putInt(12, 1 << 20);
        assertThrows(MediaException.Malformed.class, () -> parse(block));
        var noPrd = new Mrw();
        noPrd.prd = false;
        assertThrows(MediaException.Malformed.class, () -> parse(noPrd.samples(5).write()));
        var empty = new Mrw();
        empty.sensorWidth = 0;
        empty.imageWidth = 0;
        assertThrows(MediaException.Malformed.class, () -> parse(empty.samples(5).write()));
        var crop = new Mrw();
        crop.imageWidth = 18;
        assertThrows(MediaException.Malformed.class, () -> parse(crop.samples(5).write()));
        assertThrows(MediaException.Malformed.class, () -> parse(Arrays.copyOf(good, 20)));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var originals = new byte[][]{new Mrw().samples(6).write(), words().samples(7).write()};
        var random = new SplittableRandom(20);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals[round % 2].clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[4 + random.nextInt(Math.min(bytes.length - 4, 200))] = (byte) random.nextInt(256);
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

    private static Mrw words() {
        var mrw = new Mrw();
        mrw.storage = 0x52;
        mrw.stored = 16;
        return mrw;
    }

    @Test
    @DisplayName("describes a DiMAGE 5 file: APEX shot values and the maker note preview")
    void dimage5() {
        try (var source = MediaSource.of(RawSamples.MRW_DIMAGE_5.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals("Minolta Co., Ltd.", info.make().orElseThrow());
            assertEquals("2025:01:04 10:23:44", info.captureTimeText().orElseThrow());
            assertEquals(1.0 / 256, info.exposureTime().orElseThrow(), 1e-9);
            assertEquals(Math.pow(2, 2.25), info.fNumber().orElseThrow(), 1e-9);
            assertEquals(1, info.previews().size());
            assertEquals(640, info.previews().getFirst().width());
            assertEquals(480, info.previews().getFirst().height());
            var preview = RawDecoder.extractPreview(source).orElseThrow();
            assertEquals(0xFF, preview[0] & 0xFF);
            assertEquals(0xD8, preview[1] & 0xFF);
        }
    }

    @Test
    @DisplayName("describes a Dynax 7D file: the EXIF shot and the letterboxed preview the maker note points to")
    void dynax7d() {
        try (var source = MediaSource.of(RawSamples.MRW_DYNAX_7D.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals("KONICA MINOLTA", info.make().orElseThrow());
            assertEquals("2010:07:29 11:43:16", info.captureTimeText().orElseThrow());
            assertEquals(0.0015625, info.exposureTime().orElseThrow(), 1e-9);
            assertEquals(5.6, info.fNumber().orElseThrow(), 1e-9);
            assertEquals(30, info.focalLength().orElseThrow(), 1e-9);
            assertEquals(1, info.previews().size());
            assertEquals(640, info.previews().getFirst().width());
            assertEquals(30133, info.previews().getFirst().length());
            var preview = RawDecoder.extractPreview(source).orElseThrow();
            assertEquals(30133, preview.length);
            assertEquals(0xFF, preview[0] & 0xFF);
            // the 4:3 preview letterboxes the 3:2 image: a rendering from it keeps only the image rows
            var hinted = RawDecoder.decode(source, RawDecoderOptions.builder().minSize(600, 400).build()).picture();
            assertEquals(640, hinted.width());
            assertEquals(425, hinted.height());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the MRW fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "mrw", "valid-synthetic.mrw", new Mrw().samples(1).write());
    }
}
