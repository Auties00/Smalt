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
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Checks the Sigma X3F parser on synthetic files, written with the container layout of Sigma's
 * published specification and the codings and metadata block layout of the X3F tools library
 * (both TRUE variants, the older Huffman coding, metadata blocks of type 2 and 4, the levels, the
 * colors and the shot, damaged and unsupported files, random damage), and on the CC0 sample files:
 * their decoded layers must hash to the values the X3F tools library (commit e8b48ca, built from
 * source and run as a reference) decodes, and their metadata and previews must match what the files
 * hold.
 */
class X3fParserTest {
    /** The fuzz test class whose seeds the synthetic files feed. */
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // the TRUE code table of the DP1 sample: (length, code) per difference bit count
    private static final int[][] TRUE_TABLE = {{3, 0b100}, {3, 0b010}, {3, 0b001}, {4, 0b1100}, {4, 0b0110},
            {4, 0b1110}, {4, 0b0111}, {4, 0b1111}, {4, 0b0001}, {4, 0b1010}, {5, 0b11010}, {6, 0b110110},
            {6, 0b110111}};

    // a synthetic X3F file
    private static final class X3f {
        int width = 40;
        int height = 24;
        int version = 0x20002;
        int type = 3;
        int format = 30;
        long rowSize = -1;
        int camfType = 2;
        boolean camf = true;
        boolean raw = true;
        boolean preTrue = false;
        boolean preview = true;
        int planeShortfall = 0;
        String balance = "Auto";
        int[][] layers;
        final Map<String, String> properties = new LinkedHashMap<>(Map.of("CAMMANUF", "SIGMA", "CAMMODEL",
                "SIGMA TEST", "ISO", "200", "SHUTTER", "0.00800", "APERTURE", "5.60000", "FLENGTH", "16.6",
                "TIME", "1265012852"));
        final List<byte[]> entries = new ArrayList<>();

        boolean huffman() {
            return type == 3 && format == 6;
        }

        X3f layers(long seed, int low, int high) {
            var random = new SplittableRandom(seed);
            layers = new int[3][width * height];
            for (var layer = 0; layer < 3; layer++) {
                var value = (low + high) / 2;
                for (var index = 0; index < width * height; index++) {
                    value = Math.clamp(value + random.nextInt(-40, 41), low, high);
                    layers[layer][index] = index % 7 == 0 && !huffman() ? random.nextInt(low, high + 1) / 8 * 8 : value;
                }
            }
            for (var row = 0; row < 2; row++) {
                for (var column = 2; column < width - 2; column++) {
                    for (var layer = 0; layer < 3; layer++) {
                        layers[layer][row * width + column] = 7 + layer;
                    }
                }
            }
            return this;
        }

        void defaultEntries() {
            entries.add(matrix("KeepImageArea", 1, new int[]{4}, 0, 0, width - 1, height - 1));
            entries.add(matrix("ActiveImageArea", 1, new int[]{4}, 2, 3, width - 3, height - 2));
            entries.add(matrix("DarkShieldTop", 1, new int[]{4}, 2, 0, width - 3, 1));
            entries.add(matrix("DarkShieldColRange", 2, new int[]{2, 2}, 0, 0, width - 1, width - 1));
            if (preTrue) {
                entries.add(matrix("SaturationLevel", 0, new int[]{3}, 3000, 3100, 3200));
                entries.add(propertyList("WhiteBalanceIlluminants", balance, "CamToXYZ"));
                entries.add(propertyList("WhiteBalanceCorrections", balance, "Correction"));
                entries.add(floats("CamToXYZ", new int[]{3, 3}, 0.9, 0.4, 0.5, -2.1, 5.0, -1.6, 2.2, -8.1, 8.7));
                entries.add(floats("Correction", new int[]{3, 3}, 1.01, 0, 0, 0, 1, 0, 0, 0, 0.9));
            } else {
                entries.add(matrix("RawSaturationLevel", 0, new int[]{3}, 2990, 3077, 3074));
                entries.add(propertyList("WhiteBalanceGains", balance, "AutoWBGain"));
                entries.add(propertyList("WhiteBalanceColorCorrections", balance, "AutoCCMatrix"));
                entries.add(floats("AutoWBGain", new int[]{3}, 1.5, 1.0, 0.8));
                entries.add(floats("AutoCCMatrix", new int[]{3, 3}, 3.6, -4.4, 1.8, -1.3, 3.1, -0.8, 1.0, -3.6, 3.6));
                entries.add(floats("TempGainFact", new int[]{3}, 1.0, 1.02, 1.04));
            }
            entries.add(floats("SensorISO", new int[]{1}, 100));
            entries.add(floats("CaptureISO", new int[]{1}, 400));
            entries.add(text("CamsettingsInfo", "synthetic"));
        }

        byte[] write() {
            var out = new Output();
            var header = ByteBuffer.allocate(version >= 0x20001 ? 232 : 40).order(ByteOrder.LITTLE_ENDIAN);
            header.put("FOVb".getBytes(StandardCharsets.ISO_8859_1)).putInt(version).put(new byte[16]).putInt(0)
                    .putInt(width - 4).putInt(height - 5).putInt(0);
            if (version >= 0x20001) {
                var name = Arrays.copyOf(balance.getBytes(StandardCharsets.ISO_8859_1), 32);
                header.put(name);
            }
            out.bytes(header.array());
            var directory = new ArrayList<long[]>();
            if (preview) {
                var jpeg = JpegEncoder.encode(RgbaPicture.allocate(48, 32, false));
                directory.add(out.section("IMA2", image(2, 18, 48, 32, 0, jpeg)));
            }
            directory.add(out.section("PROP", properties()));
            if (camf) {
                if (entries.isEmpty()) {
                    defaultEntries();
                }
                directory.add(out.section("CAMF", camfSection()));
            }
            if (raw) {
                var body = huffman() ? huffmanBody() : trueBody();
                directory.add(out.section("IMA2", image(type, format, width, height,
                        rowSize >= 0 ? rowSize : 0, body)));
            }
            var position = out.size();
            var dir = ByteBuffer.allocate(12 + 12 * directory.size()).order(ByteOrder.LITTLE_ENDIAN);
            dir.put("SECd".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(directory.size());
            for (var entry : directory) {
                dir.putInt((int) entry[0]).putInt((int) entry[1]).putInt((int) entry[2]);
            }
            out.bytes(dir.array());
            out.bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(position).array());
            return out.toByteArray();
        }

        private byte[] image(int type, int format, int columns, int rows, long rowSize, byte[] body) {
            var header = ByteBuffer.allocate(28 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            header.put("SECi".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(type).putInt(format)
                    .putInt(columns).putInt(rows).putInt((int) rowSize).put(body);
            return header.array();
        }

        private byte[] properties() {
            var characters = new StringBuilder();
            var offsets = new ArrayList<int[]>();
            for (var entry : properties.entrySet()) {
                var name = characters.length();
                characters.append(entry.getKey()).append('\0');
                var value = characters.length();
                characters.append(entry.getValue()).append('\0');
                offsets.add(new int[]{name, value});
            }
            var text = characters.toString().getBytes(StandardCharsets.UTF_16LE);
            var buffer = ByteBuffer.allocate(24 + 8 * offsets.size() + text.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put("SECp".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(offsets.size()).putInt(0)
                    .putInt(0).putInt(characters.length());
            for (var offset : offsets) {
                buffer.putInt(offset[0]).putInt(offset[1]);
            }
            return buffer.put(text).array();
        }

        private byte[] camfSection() {
            var body = new ByteArrayOutputStream();
            for (var entry : entries) {
                body.writeBytes(entry);
            }
            var plain = body.toByteArray();
            byte[] data;
            int[] values;
            if (camfType == 2) {
                var key = 0x1234567L;
                data = scramble(plain, key);
                values = new int[]{0, 0, 0, (int) key};
            } else {
                var columns = 64;
                var count = (plain.length * 2 + 2) / 3;
                var rows = (count + columns - 1) / columns;
                var twelve = new int[rows * columns];
                for (var index = 0; index < count; index++) {
                    var at = index / 2 * 3;
                    var b0 = at < plain.length ? plain[at] & 0xFF : 0;
                    var b1 = at + 1 < plain.length ? plain[at + 1] & 0xFF : 0;
                    var b2 = at + 2 < plain.length ? plain[at + 2] & 0xFF : 0;
                    twelve[index] = index % 2 == 0 ? b0 << 4 | b1 >> 4 : (b1 & 0x0F) << 8 | b2;
                }
                var coded = encodeTrue(twelve, columns, rows, 512);
                var table = new ByteArrayOutputStream();
                for (var entry : TRUE_TABLE) {
                    table.write(entry[0]);
                    table.write(entry[1] << (8 - entry[0]));
                }
                table.write(0);
                table.write(0);
                data = new byte[32 + coded.length];
                System.arraycopy(table.toByteArray(), 0, data, 0, table.size());
                System.arraycopy(coded, 0, data, 32, coded.length);
                values = new int[]{plain.length, 512, columns, rows};
            }
            var buffer = ByteBuffer.allocate(28 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put("SECc".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(camfType);
            for (var value : values) {
                buffer.putInt(value);
            }
            return buffer.put(data).array();
        }

        private byte[] trueBody() {
            var out = new ByteArrayOutputStream();
            var head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 512).putShort((short) 512)
                    .putShort((short) 512).putShort((short) 0);
            out.writeBytes(head.array());
            for (var entry : TRUE_TABLE) {
                out.write(entry[0]);
                out.write(entry[1] << (8 - entry[0]));
            }
            out.write(0);
            out.write(0);
            var streams = new byte[3][];
            for (var layer = 0; layer < 3; layer++) {
                streams[layer] = encodeTrue(layers[layer], width, height, 512);
            }
            var sizes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            for (var layer = 0; layer < 3; layer++) {
                sizes.putInt(streams[layer].length - (layer == 2 ? planeShortfall : 0));
            }
            out.writeBytes(sizes.array());
            var start = 28 + out.size();
            for (var layer = 0; layer < 3; layer++) {
                out.writeBytes(streams[layer]);
                while ((28 + out.size() - start) % 16 != 0) {
                    out.write(0);
                }
                start = 28 + out.size();
            }
            return out.toByteArray();
        }

        private byte[] huffmanBody() {
            var lengths = new int[1024];
            for (var index = 0; index < 1024; index++) {
                var magnitude = Math.abs(index - 512);
                lengths[index] = magnitude == 0 ? 2 : magnitude < 4 ? 5 : magnitude < 16 ? 8 : magnitude < 64 ? 11
                        : magnitude < 256 ? 14 : 17;
            }
            var codes = canonical(lengths);
            var out = new ByteArrayOutputStream();
            var tables = ByteBuffer.allocate(2048 + 4096).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < 1024; index++) {
                tables.putShort((short) (index - 512));
            }
            for (var index = 0; index < 1024; index++) {
                tables.putInt(lengths[index] << 27 | codes[index]);
            }
            out.writeBytes(tables.array());
            var offsets = new int[height];
            var data = new ByteArrayOutputStream();
            for (var row = 0; row < height; row++) {
                offsets[row] = data.size();
                var bits = new Bits();
                var previous = new int[3];
                for (var column = 0; column < width; column++) {
                    for (var layer = 0; layer < 3; layer++) {
                        var value = layers[layer][row * width + column];
                        var index = value - previous[layer] + 512;
                        bits.write(codes[index], lengths[index]);
                        previous[layer] = value;
                    }
                }
                data.writeBytes(bits.bytes());
                while (data.size() % 4 != 0) {
                    data.write(0);
                }
            }
            out.writeBytes(data.toByteArray());
            var rows = ByteBuffer.allocate(4 * height).order(ByteOrder.LITTLE_ENDIAN);
            for (var offset : offsets) {
                rows.putInt(offset);
            }
            out.writeBytes(rows.array());
            return out.toByteArray();
        }
    }

    // canonical codes of the given lengths, in index order within each length
    private static int[] canonical(int[] lengths) {
        var codes = new int[lengths.length];
        var code = 0;
        var previous = 0;
        for (var length = 1; length <= 27; length++) {
            for (var index = 0; index < lengths.length; index++) {
                if (lengths[index] == length) {
                    code <<= length - previous;
                    previous = length;
                    codes[index] = code++;
                }
            }
        }
        return codes;
    }

    // the TRUE coding of one layer
    private static byte[] encodeTrue(int[] values, int width, int height, int seed) {
        var bits = new Bits();
        var rowStart = new int[]{seed, seed, seed, seed};
        var accumulator = new int[2];
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var parity = column & 1;
                var slot = (row & 1) * 2 + parity;
                var previous = column < 2 ? rowStart[slot] : accumulator[parity];
                var value = values[row * width + column];
                var difference = value - previous;
                var count = difference == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
                bits.write(TRUE_TABLE[count][1], TRUE_TABLE[count][0]);
                if (count > 0) {
                    bits.write(difference > 0 ? difference : difference + (1 << count) - 1, count);
                }
                accumulator[parity] = value;
                if (column < 2) {
                    rowStart[slot] = value;
                }
            }
        }
        return bits.bytes();
    }

    private static byte[] scramble(byte[] plain, long seed) {
        var data = new byte[plain.length];
        var key = seed;
        for (var index = 0; index < plain.length; index++) {
            key = ((key * 1597 + 51749) & 0xFFFFFFFFL) % 244944;
            var scaled = ((key * 301593171L) >> 24) & 0xFFFFFFFFL;
            var mask = (int) (((((key << 8) - scaled) & 0xFFFFFFFFL) >>> 1) + scaled >>> 17);
            data[index] = (byte) (plain[index] ^ mask);
        }
        return data;
    }

    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void write(int value, int bits) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                buffer = buffer << 1 | ((value >>> bit) & 1);
                if (++count == 8) {
                    out.write((int) buffer);
                    buffer = 0;
                    count = 0;
                }
            }
        }

        byte[] bytes() {
            if (count > 0) {
                out.write((int) (buffer << (8 - count)));
                buffer = 0;
                count = 0;
            }
            return out.toByteArray();
        }
    }

    private static final class Output {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void bytes(byte[] bytes) {
            out.writeBytes(bytes);
        }

        int size() {
            return out.size();
        }

        long[] section(String type, byte[] body) {
            while (out.size() % 4 != 0) {
                out.write(0);
            }
            var offset = out.size();
            out.writeBytes(body);
            return new long[]{offset, body.length, ByteBuffer.wrap(type.getBytes(StandardCharsets.ISO_8859_1))
                    .order(ByteOrder.LITTLE_ENDIAN).getInt()};
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    // a CAMF entry: "CMb" + kind, version, length, name offset, value offset, the name, the value
    private static byte[] entry(char kind, String name, byte[] value) {
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var buffer = ByteBuffer.allocate(valueOffset + value.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'C', 'M', 'b', (byte) kind}).putInt(0x20000).putInt(valueOffset + value.length)
                .putInt(20).putInt(valueOffset).put(nameBytes);
        buffer.position(valueOffset);
        return buffer.put(value).array();
    }

    private static byte[] matrix(String name, int type, int[] dimensions, double... values) {
        var width = switch (type) {
            case 0, 6 -> 2;
            case 5 -> 1;
            default -> 4;
        };
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var dataOffset = valueOffset + 12 + 12 * dimensions.length;
        var value = ByteBuffer.allocate(12 + 12 * dimensions.length + width * values.length).order(ByteOrder.LITTLE_ENDIAN);
        value.putInt(type).putInt(dimensions.length).putInt(dataOffset);
        for (var index = 0; index < dimensions.length; index++) {
            value.putInt(dimensions[index]).putInt(0).putInt(index);
        }
        for (var element : values) {
            switch (type) {
                case 0, 6 -> value.putShort((short) element);
                case 5 -> value.put((byte) element);
                case 3 -> value.putFloat((float) element);
                default -> value.putInt((int) (long) element);
            }
        }
        return entry('M', name, value.array());
    }

    private static byte[] floats(String name, int[] dimensions, double... values) {
        return matrix(name, 3, dimensions, values);
    }

    private static byte[] propertyList(String name, String key, String value) {
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var strings = (key + "\0" + value + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var base = valueOffset + 16;
        var buffer = ByteBuffer.allocate(16 + strings.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(1).putInt(base).putInt(0).putInt(key.length() + 1).put(strings);
        return entry('P', name, buffer.array());
    }

    private static byte[] text(String name, String text) {
        var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        var buffer = ByteBuffer.allocate(4 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        return entry('T', name, buffer.putInt(bytes.length).put(bytes).array());
    }

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] decode(RawFile file) {
        assertNotNull(file.sensor, file.unsupported);
        var sensor = file.sensor;
        var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    private static short[] interleaved(int[][] layers, int bias) {
        var samples = new short[layers[0].length * 3];
        for (var index = 0; index < layers[0].length; index++) {
            for (var layer = 0; layer < 3; layer++) {
                samples[index * 3 + layer] = (short) (layers[layer][index] + bias);
            }
        }
        return samples;
    }

    @ParameterizedTest(name = "type {0} format {1}")
    @CsvSource({"3, 30, 0, 4000", "1, 30, 0, 4095", "3, 6, -60, 450"})
    @DisplayName("decodes the TRUE codings and the older Huffman coding exactly")
    void coding(int type, int format, int low, int high) {
        var x3f = new X3f();
        x3f.type = type;
        x3f.format = format;
        x3f.layers(type * 100 + format, low, high);
        var file = parse(x3f.write());
        assertEquals(RawFormat.X3F, file.format);
        var bias = x3f.huffman() ? X3fDecoder.HUFFMAN_BIAS : 0;
        assertArrayEquals(interleaved(x3f.layers, bias), decode(file));
        assertEquals(x3f.huffman() ? "Sigma Huffman" : "Sigma TRUE", file.sensor.compression);
    }

    @ParameterizedTest(name = "CAMF type {0}")
    @CsvSource({"2", "4"})
    @DisplayName("describes the image area, the levels, the colors and the shot")
    void description(int camfType) {
        var x3f = new X3f().layers(5, 0, 4000);
        x3f.camfType = camfType;
        var file = parse(x3f.write());
        assertEquals("SIGMA", file.make);
        assertEquals("SIGMA TEST", file.model);
        assertEquals(200, file.iso);
        assertEquals(0.008, file.exposureTime, 1e-9);
        assertEquals(5.6, file.fNumber, 1e-9);
        assertEquals(16.6, file.focalLength, 1e-9);
        assertEquals("2010:02:01 08:27:32", file.captureTime);
        var sensor = file.sensor;
        assertEquals(3, sensor.samplesPerPixel);
        assertArrayEquals(new int[]{3, 2, x3f.height - 1, x3f.width - 2}, sensor.activeArea);
        assertArrayEquals(new double[]{2990, 3077, 3074}, sensor.white);
        decode(file);
        // the shield rows hold 7, 8 and 9: the column ranges are left out
        assertArrayEquals(new double[]{7, 8, 9}, sensor.black, 1e-9);
        var gain = new double[]{1.5, 1.02, 0.8 * 1.04};
        assertArrayEquals(new double[]{1 / gain[0], 1 / gain[1], 1 / gain[2]}, file.color.asShotNeutral, 1e-6);
        assertArrayEquals(new double[]{3.6, -4.4, 1.8, -1.3, 3.1, -0.8, 1.0, -3.6, 3.6}, file.color.cameraToSrgb, 1e-6);
        assertEquals(Math.log(4 * 0.8 * 1.04) / Math.log(2), file.color.baselineExposure, 1e-6);
        assertEquals(1, file.previews.size());
        assertEquals(48, file.previews.getFirst().width());
        var info = RawDecoder.info(MediaSource.of(x3f.write()));
        assertEquals(x3f.width - 4, info.width());
        assertEquals(x3f.height - 4, info.height());
        assertTrue(info.isDecodable());
        var raw = RawDecoder.decodeRaw(MediaSource.of(x3f.write()));
        assertEquals(3, raw.channels());
        assertEquals(x3f.width - 4, raw.width());
    }

    @Test
    @DisplayName("derives the neutral and the matrix of an older camera from its illuminant matrices")
    void olderColors() {
        var x3f = new X3f();
        x3f.type = 3;
        x3f.format = 6;
        x3f.preTrue = true;
        x3f.layers(6, -20, 450);
        var file = parse(x3f.write());
        assertArrayEquals(new double[]{3000 + 1024, 3100 + 1024, 3200 + 1024}, file.sensor.white);
        var cameraToXyz = ColorMath.multiply(new double[]{1.01, 0, 0, 0, 1, 0, 0, 0, 0.9},
                new double[]{0.9, 0.4, 0.5, -2.1, 5.0, -1.6, 2.2, -8.1, 8.7});
        var neutral = ColorMath.transform(ColorMath.invert(cameraToXyz), new double[]{0.95047, 1.0, 1.08883});
        assertArrayEquals(neutral, file.color.asShotNeutral, 1e-6);
        // a neutral, balanced, maps to sRGB white
        var white = ColorMath.transform(file.color.cameraToSrgb, new double[]{1, 1, 1});
        assertArrayEquals(new double[]{1, 1, 1}, white, 1e-3);
        decode(file);
        assertArrayEquals(new double[]{7 + 1024, 8 + 1024, 9 + 1024}, file.sensor.black, 1e-9);
    }

    @Test
    @DisplayName("falls back to the Sunlight entries for a Daylight setting and estimates a missing balance")
    void balanceNames() {
        var daylight = new X3f().layers(7, 0, 3000);
        daylight.balance = "Daylight";
        daylight.defaultEntries();
        daylight.entries.replaceAll(entry -> new String(entry, StandardCharsets.ISO_8859_1).contains("WhiteBalance")
                ? propertyList(new String(entry, 20, entry.length - 20, StandardCharsets.ISO_8859_1).split("\0")[0],
                "Sunlight", new String(entry, StandardCharsets.ISO_8859_1).contains("Gains") ? "AutoWBGain" : "AutoCCMatrix")
                : entry);
        assertNotNull(parse(daylight.write()).color.asShotNeutral);
        var unknown = new X3f().layers(7, 0, 3000);
        unknown.defaultEntries();
        unknown.balance = "Underwater";
        var file = parse(unknown.write());
        assertNull(file.color.asShotNeutral);
        assertTrue(file.sensor.estimateBalance);
        var none = new X3f().layers(7, 0, 3000);
        none.camf = false;
        var bare = parse(none.write());
        assertNull(bare.sensor.activeArea);
        assertArrayEquals(new double[]{4095, 4095, 4095}, bare.sensor.white);
        decode(bare);
        assertArrayEquals(new double[]{0, 0, 0}, bare.sensor.black);
    }

    @Test
    @DisplayName("reads a Merrill file's image depth as its white level")
    void imageDepth() {
        var x3f = new X3f().layers(8, 0, 4095);
        x3f.type = 1;
        x3f.defaultEntries();
        x3f.entries.add(matrix("ImageDepth", 6, new int[]{1}, 12));
        var file = parse(x3f.write());
        assertArrayEquals(new double[]{4095, 4095, 4095}, file.sensor.white);
        assertEquals(12, file.sensor.bitsPerSample);
    }

    @Test
    @DisplayName("finds the end of a source whose size is unknown")
    void unknownSize() {
        var x3f = new X3f().layers(9, 0, 3000);
        var bytes = x3f.write();
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
        assertArrayEquals(interleaved(x3f.layers, 0), decode(RawParser.parse(source, MediaLimits.DEFAULT)));
    }

    @ParameterizedTest(name = "type {0} format {1} row size {2}")
    @CsvSource({"1, 35, 0", "1, 37, 0", "1, 39, 0", "3, 5, 0", "3, 6, 120", "3, 99, 0"})
    @DisplayName("rejects the Quattro layouts and the codings no sample shows as unsupported, keeping the previews")
    void unsupported(int type, int format, int rowSize) {
        var x3f = new X3f();
        x3f.type = type;
        x3f.format = format;
        x3f.layers(10, 0, 450);
        x3f.rowSize = rowSize;
        var bytes = x3f.write();
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(RawFormat.X3F, info.format());
        assertFalse(info.isDecodable());
        assertEquals("SIGMA TEST", info.model().orElseThrow());
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(MediaSource.of(bytes)));
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
        assertTrue(RawDecoder.extractPreview(MediaSource.of(bytes)).isPresent());
        var hinted = RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(40, 20).build());
        assertEquals(48, hinted.picture().width());
    }

    @Test
    @DisplayName("rejects a damaged directory, code table or stream as malformed")
    void malformed() {
        var x3f = new X3f().layers(11, 0, 3000);
        var good = x3f.write();
        var pointer = good.clone();
        ByteBuffer.wrap(pointer).order(ByteOrder.LITTLE_ENDIAN).putInt(good.length - 4, good.length);
        assertThrows(MediaException.Malformed.class, () -> parse(pointer));
        var directory = ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).getInt(good.length - 4);
        var identifier = good.clone();
        identifier[directory] = 'X';
        assertThrows(MediaException.Malformed.class, () -> parse(identifier));
        var count = good.clone();
        ByteBuffer.wrap(count).order(ByteOrder.LITTLE_ENDIAN).putInt(directory + 8, 100000);
        assertThrows(MediaException.Malformed.class, () -> parse(count));
        assertThrows(MediaException.Malformed.class, () -> parse(Arrays.copyOf(good, 30)));
        // the stream of the top layer declared shorter than it is runs past its end
        var shortfall = new X3f().layers(11, 0, 3000);
        shortfall.planeShortfall = 40;
        assertThrows(MediaException.Malformed.class, () -> decode(parse(shortfall.write())));
        // a code table that assigns the same code twice
        var table = good.clone();
        var raw = indexOf(good, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 3, 0, 0, 0, 30});
        table[raw + 28 + 8 + 2] = 3;
        table[raw + 28 + 8 + 3] = (byte) 0x80;
        assertThrows(MediaException.Malformed.class, () -> decode(parse(table)));
        // a raw section without its streams
        var cut = good.clone();
        var rawSection = indexOf(cut, new byte[]{'S', 'E', 'C', 'i', 0, 0, 2, 0, 3, 0, 0, 0, 30});
        ByteBuffer.wrap(cut).order(ByteOrder.LITTLE_ENDIAN).putInt(rawSection + 16, 4000);
        assertThrows(MediaException.class, () -> decode(parse(cut)));
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var index = 0; index + pattern.length <= data.length; index++) {
            for (var k = 0; k < pattern.length; k++) {
                if (data[index + k] != pattern[k]) {
                    continue outer;
                }
            }
            return index;
        }
        throw new AssertionError("pattern not found");
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var huffman = new X3f();
        huffman.format = 6;
        huffman.preTrue = true;
        var four = new X3f();
        four.camfType = 4;
        var originals = new byte[][]{new X3f().layers(12, 0, 3000).write(), huffman.layers(13, -30, 450).write(),
                four.layers(14, 0, 4000).write()};
        var random = new SplittableRandom(23);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals[round % 3].clone();
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

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // SHA-256 of the decoded layers (interleaved bottom, middle, top, little-endian 16-bit; the Huffman
            // values plus 1024), as the X3F tools library decodes them
            "X3F_DP1, 8cfbb29b663d0342c30e2fa56f578d223bbae175a050d3c8cd75f03ffa4c8c5a",
            "X3F_SD14, 21dcb08975397ae5b6c3a2dd1dac7e275dba2ca666aa9afe85b301aaefbe47d1",
            "X3F_SD15, 086e8cd5377a6bd95888cea49585b4724541c98ab38522b8795e8121b4b1e14e",
            "X3F_SD1_MERRILL, c6547387c357f478f12d1a28034d089884b57a4f24eddee32b3dea13f6079869",
            "X3F_DP2_MERRILL, f4164d231603fe19dc13d41ea81659e34151649d7906938fc769c2f358381e3b"
    })
    @DisplayName("decodes the layers of the sample files exactly as the X3F tools library does")
    void samples(RawSamples sample, String sha256) throws Exception {
        try (var source = MediaSource.of(sample.fetch())) {
            var samples = decode(RawParser.parse(source, MediaLimits.DEFAULT));
            var bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asShortBuffer().put(samples);
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes.array());
            assertEquals(sha256, HexFormat.of().formatHex(digest));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "X3F_DP1, SIGMA DP1, 2640, 1760, 2688, 1792, 400, 2010:02:01 08:27:32, 2",
            "X3F_SD14, SIGMA SD14, 2640, 1760, 2688, 1792, 100, -, 1",
            "X3F_SD15, SIGMA SD15, 2640, 1760, 2688, 1792, 50, -, 2",
            "X3F_SD1_MERRILL, SIGMA SD1 Merrill, 4704, 3136, 4928, 3264, 100, -, 2",
            "X3F_DP2_MERRILL, SIGMA DP2 Merrill, 4704, 3136, 4928, 3264, 100, -, 2"
    })
    @DisplayName("describes the sample files: the camera, the image area, the levels and the previews")
    void sampleInfo(RawSamples sample, String model, int width, int height, int sensorWidth, int sensorHeight,
                    long iso, String time, int previews) {
        try (var source = MediaSource.of(sample.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals(RawFormat.X3F, info.format());
            assertEquals("SIGMA", info.make().orElseThrow());
            assertEquals(model, info.model().orElseThrow());
            assertEquals(width, info.width());
            assertEquals(height, info.height());
            assertEquals(sensorWidth, info.sensorWidth());
            assertEquals(sensorHeight, info.sensorHeight());
            assertEquals(iso, info.iso().orElse(0));
            assertEquals(12, info.bitsPerSample());
            assertTrue(info.isColorCalibrated());
            if (!time.equals("-")) {
                assertEquals(time, info.captureTimeText().orElseThrow());
            }
            assertEquals(previews, info.previews().size());
            assertEquals(width, info.previews().getFirst().width());
            assertEquals(height, info.previews().getFirst().height());
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            decode(file);
            for (var layer = 0; layer < 3; layer++) {
                var black = file.sensor.black[layer] - (sample == RawSamples.X3F_SD14 ? X3fDecoder.HUFFMAN_BIAS : 0);
                assertTrue(black > -2 && black < 40, "black level " + black);
                assertTrue(file.sensor.white[layer] > 2000, "white level " + file.sensor.white[layer]);
            }
        }
    }

    @Test
    @DisplayName("describes a dp2 Quattro file and its preview, and rejects its layers as unsupported")
    void quattro() {
        try (var source = MediaSource.of(RawSamples.X3F_DP2_QUATTRO.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals(RawFormat.X3F, info.format());
            assertEquals("SIGMA dp2 Quattro", info.model().orElseThrow());
            assertFalse(info.isDecodable());
            assertTrue(info.unsupportedFeature().orElseThrow().contains("Quattro"));
            assertEquals(5424, info.previews().getFirst().width());
            assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(source));
            var hinted = RawDecoder.decode(source, RawDecoderOptions.builder().minSize(1000, 600).build());
            assertTrue(hinted.picture().width() >= 1000 && hinted.picture().width() <= 5424);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true")
    @DisplayName("writes the seeds of the X3F fuzz target")
    void fuzzSeeds() {
        var truePlanes = new X3f();
        truePlanes.layers(330, 0, 4000);
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-true.x3f", truePlanes.write());
        var huffman = new X3f();
        huffman.format = 6;
        huffman.layers(306, -60, 450);
        FuzzSeeds.write(FUZZ_TARGET, "x3f", "valid-huffman.x3f", huffman.write());
    }
}
