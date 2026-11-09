package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Checks the uncompressed Panasonic RAW layouts of the oldest models on synthetic files: version 2 (ten 12-bit
 * photosites per 16-byte block, the first in the least significant bits) and the files without a version (12-bit
 * photosites in the high bits of 16-bit words), the repair of their zero photosites, the levels and image area of
 * the raw tags, short and damaged files.
 */
class Rw2UncompressedTest {
    static final class Raw {
        int width = 20;
        int height = 8;
        int version = 2;
        String model = "DMC-FZ8";
        short[] samples;
        int dropBytes;

        Raw samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) (1 + random.nextInt(4095));
            }
            return this;
        }

        byte[] strip() {
            if (version == 2) {
                var rowBytes = (width + 9) / 10 * 16;
                var data = new byte[rowBytes * height];
                for (var y = 0; y < height; y++) {
                    for (var block = 0; block * 10 < width; block++) {
                        var bits = java.math.BigInteger.ZERO;
                        for (var index = 0; index < 10 && block * 10 + index < width; index++) {
                            bits = bits.or(java.math.BigInteger.valueOf(samples[y * width + block * 10 + index] & 0xFFF)
                                    .shiftLeft(12 * index));
                        }
                        var bytes = bits.toByteArray();
                        for (var index = 0; index < 16 && index < bytes.length; index++) {
                            data[y * rowBytes + block * 16 + index] = bytes[bytes.length - 1 - index];
                        }
                    }
                }
                return data;
            }
            var buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var sample : samples) {
                buffer.putShort((short) (sample << 4));
            }
            return buffer.array();
        }

        byte[] write() {
            var writer = new VendorTiffWriter();
            var strip = strip();
            strip = Arrays.copyOf(strip, strip.length - dropBytes);
            var entries = new ArrayList<VendorTiffWriter.Entry>();
            entries.add(writer.shorts(0x02, width));
            entries.add(writer.shorts(0x03, height));
            entries.add(writer.shorts(0x04, 1));
            entries.add(writer.shorts(0x05, 2));
            entries.add(writer.shorts(0x06, height - 1));
            entries.add(writer.shorts(0x07, width - 2));
            entries.add(writer.shorts(0x09, 1));
            entries.add(writer.shorts(0x0A, 12));
            entries.add(writer.shorts(0x0E, 4095));
            entries.add(writer.shorts(0x1C, 3));
            entries.add(writer.shorts(0x1D, 4));
            entries.add(writer.shorts(0x1E, 5));
            entries.add(writer.shorts(0x24, 500));
            entries.add(writer.shorts(0x25, 250));
            entries.add(writer.shorts(0x26, 400));
            if (version != 0) {
                entries.add(writer.shorts(0x2D, version));
            }
            entries.add(VendorTiffWriter.ascii(271, "Panasonic"));
            entries.add(VendorTiffWriter.ascii(272, model));
            entries.add(writer.longs(279, strip.length));
            var directory = VendorTiffWriter.directory(entries, 8, writer.order());
            // the strip follows the directory: its offset entry is written once the directory's size is known
            var withOffset = new ArrayList<>(entries);
            withOffset.add(writer.longs(273, 8 + directory.length + 12));
            directory = VendorTiffWriter.directory(withOffset, 8, writer.order());
            var out = ByteBuffer.allocate(8 + directory.length + strip.length).order(ByteOrder.LITTLE_ENDIAN);
            out.put((byte) 'I').put((byte) 'I').putShort((short) 0x55).putInt(8).put(directory).put(strip);
            return out.array();
        }
    }

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] decode(RawFile file) {
        var plane = new SensorPlane(file.sensor.width, file.sensor.height, 1, false, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(ints = {2, 0})
    @DisplayName("decodes both layouts exactly, with the levels, image area and white balance of the raw tags")
    void layouts(int version) {
        var raw = new Raw();
        raw.version = version;
        var file = parse(raw.samples(version + 1).write());
        assertEquals(RawFormat.RW2, file.format);
        assertArrayEquals(raw.samples, decode(file));
        assertArrayEquals(new double[]{3, 4, 4, 5}, file.sensor.black);
        assertArrayEquals(new double[]{3986}, file.sensor.white);
        assertArrayEquals(new double[]{2, 1, 16, 6}, file.sensor.defaultCrop);
        assertArrayEquals(new double[]{0.5, 1, 250.0 / 400}, file.color.asShotNeutral, 1e-12);
    }

    @Test
    @DisplayName("replaces a zero photosite with the rounded mean of its same-color neighbors")
    void zeros() {
        var raw = new Raw();
        raw.samples(3);
        // a red photosite of an RGGB array, its red neighbors two columns and rows away
        var x = 4;
        var y = 4;
        raw.samples[y * raw.width + x] = 0;
        var expected = ((raw.samples[y * raw.width + x - 2] + raw.samples[y * raw.width + x + 2]
                + raw.samples[(y - 2) * raw.width + x] + raw.samples[(y + 2) * raw.width + x]) + 2) / 4;
        var decoded = decode(parse(raw.write()));
        assertEquals(expected, decoded[y * raw.width + x]);
    }

    @Test
    @DisplayName("keeps the zero photosites of the models whose zeros are dark photosites")
    void darkZeros() {
        var raw = new Raw();
        raw.version = 0;
        raw.model = "DMC-LX1";
        raw.samples(8);
        raw.samples[4 * raw.width + 4] = 0;
        assertArrayEquals(raw.samples, decode(parse(raw.write())));
    }

    @Test
    @DisplayName("takes the white level of the tag for a model not listed")
    void otherModel() {
        var raw = new Raw();
        raw.model = "DMC-TEST";
        assertArrayEquals(new double[]{4095}, parse(raw.samples(4).write()).sensor.white);
    }

    @Test
    @DisplayName("rejects data shorter than its photosites as unsupported")
    void shortData() {
        var raw = new Raw();
        raw.dropBytes = 20;
        var bytes = raw.samples(5).write();
        assertFalse(RawDecoder.info(MediaSource.of(bytes)).isDecodable());
        org.junit.jupiter.api.Assertions.assertThrows(MediaException.Unsupported.class,
                () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var words = new Raw();
        words.version = 0;
        var originals = new byte[][]{new Raw().samples(6).write(), words.samples(7).write()};
        var random = new SplittableRandom(24);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals[round % 2].clone();
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
                    RawDecoder.decodeRaw(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }
}
