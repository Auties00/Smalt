package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Writes synthetic Sigma X3F files: the container layout of Sigma's published FOVb specification 1.2 and the
 * codings and metadata block layout of the X3F tools library (the TRUE codings, the Quattro layers, the older
 * Huffman coding and its uncompressed variant, metadata blocks of type 2, 4 and 5, the RGB thumbnails of formats 3
 * and 11 and the JPEG previews of formats 18 and 25).
 */
public final class X3fWriter {
    /** The TRUE code table of the DP1 sample: (length, code) per difference bit count. */
    public static final int[][] TRUE_TABLE = {{3, 0b100}, {3, 0b010}, {3, 0b001}, {4, 0b1100}, {4, 0b0110},
            {4, 0b1110}, {4, 0b0111}, {4, 0b1111}, {4, 0b0001}, {4, 0b1010}, {5, 0b11010}, {6, 0b110110},
            {6, 0b110111}};

    /** The width in the image header (of the top layer of a Quattro file). */
    public int width = 40;
    /** The height in the image header. */
    public int height = 24;
    /** The file format version. */
    public int version = 0x20002;
    /** The raw image type. */
    public int type = 3;
    /** The raw image format. */
    public int format = 30;
    /** The row size of the raw image header, or -1 for 0. */
    public long rowSize = -1;
    /** The metadata block type: 2, 4 or 5. */
    public int camfType = 2;
    /** Whether the file holds a metadata block. */
    public boolean camf = true;
    /** Whether the file holds a raw image. */
    public boolean raw = true;
    /** Whether the metadata block describes an older camera's colors. */
    public boolean preTrue = false;
    /** Whether the file holds a 48x32 JPEG preview of format 18. */
    public boolean preview = true;
    /** The bytes the top layer's stream length is declared short of its true length. */
    public int planeShortfall = 0;
    /** Whether the bottom and middle layers of a Quattro file have the top layer's rows (the binned layout). */
    public boolean binned = false;
    /** The columns a Quattro top layer codes past twice the lower layers' width. */
    public int topExtra = 6;
    /** The white balance setting's name in the header. */
    public String balance = "Auto";
    /** The layers, bottom, middle and top, each row by row at its own size. */
    public int[][] layers;
    /** The RGB thumbnails, each width, height, format (3 or 11) and the pixels. */
    public final List<Object[]> thumbnails = new ArrayList<>();
    /** Whether the file holds a 64x40 JPEG thumbnail of format 25. */
    public boolean quattroJpeg = false;
    /** The property list. */
    public final Map<String, String> properties = new LinkedHashMap<>(Map.of("CAMMANUF", "SIGMA", "CAMMODEL",
            "SIGMA TEST", "ISO", "200", "SHUTTER", "0.00800", "APERTURE", "5.60000", "FLENGTH", "16.6",
            "TIME", "1265012852"));
    /** The metadata block entries; filled with {@link #defaultEntries()} when empty at writing. */
    public final List<byte[]> entries = new ArrayList<>();

    /** {@return whether the raw image uses the older coding, Huffman coded or not} */
    public boolean older() {
        return type == 3 && (format == 6 || format == 5);
    }

    /** {@return whether the raw image uses the older coding stored without Huffman codes} */
    public boolean uncompressed() {
        return older() && rowSize > 0;
    }

    /** {@return whether the raw image is a Quattro coding} */
    public boolean quattro() {
        return type == 1 && (format == 35 || format == 37 || format == 39);
    }

    /** {@return the columns of each layer} */
    public int[] columns() {
        if (quattro()) {
            return binned ? new int[]{width, width, width + topExtra} : new int[]{width / 2, width / 2 + 2,
                    width + topExtra};
        }
        return new int[]{width, width, width};
    }

    /** {@return the columns of each layer the decoder keeps} */
    public int[] keptColumns() {
        if (quattro()) {
            return binned ? new int[]{width, width, width} : new int[]{width / 2, width / 2, width + topExtra};
        }
        return new int[]{width, width, width};
    }

    /** {@return the rows of each layer} */
    public int[] rows() {
        if (quattro() && !binned) {
            return new int[]{height / 2, height / 2, height};
        }
        return new int[]{height, height, height};
    }

    /**
     * Fills the layers with a random walk between two values, the first two rows a dark shield of 7, 8 and 9.
     *
     * @param seed the random seed
     * @param low the smallest value
     * @param high the largest value
     * @return this writer
     */
    public X3fWriter layers(long seed, int low, int high) {
        var random = new SplittableRandom(seed);
        var columns = columns();
        var rows = rows();
        layers = new int[3][];
        for (var layer = 0; layer < 3; layer++) {
            var count = columns[layer] * rows[layer];
            layers[layer] = new int[count];
            var value = (low + high) / 2;
            for (var index = 0; index < count; index++) {
                value = Math.clamp(value + random.nextInt(-40, 41), low, high);
                layers[layer][index] = index % 7 == 0 && !older() ? random.nextInt(low, high + 1) / 8 * 8 : value;
            }
            var lower = quattro() && !binned && layer < 2;
            for (var row = 0; row < (lower ? 1 : 2); row++) {
                for (var column = lower ? 0 : 2; column < columns[layer] - (lower ? 0 : 2); column++) {
                    layers[layer][row * columns[layer] + column] = 7 + layer;
                }
            }
        }
        return this;
    }

    /** Adds the default metadata block entries: areas, levels, colors and ISO speeds. */
    public void defaultEntries() {
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

    /**
     * Adds a 3x8-bit RGB thumbnail of random smooth pixels.
     *
     * @param format 3 (uncompressed) or 11 (Huffman coded)
     * @param columns the width
     * @param rows the height
     * @param seed the random seed
     * @return the pixels, red, green and blue of each pixel row by row
     */
    public byte[] thumbnail(int format, int columns, int rows, long seed) {
        var random = new SplittableRandom(seed);
        var pixels = new byte[3 * columns * rows];
        var value = new int[]{128, 128, 128};
        for (var index = 0; index < pixels.length; index++) {
            var channel = index % 3;
            value[channel] = (value[channel] + random.nextInt(-12, 13) + (index % 53 == 0 ? 200 : 0)) & 0xFF;
            pixels[index] = (byte) value[channel];
        }
        thumbnails.add(new Object[]{columns, rows, format, pixels});
        return pixels;
    }

    /** {@return the file} */
    public byte[] write() {
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
        for (var thumbnail : thumbnails) {
            var columns = (int) thumbnail[0];
            var rows = (int) thumbnail[1];
            var format = (int) thumbnail[2];
            var pixels = (byte[]) thumbnail[3];
            if (format == 3) {
                var stride = (3 * columns + 3) / 4 * 4;
                var body = new byte[stride * rows];
                for (var row = 0; row < rows; row++) {
                    System.arraycopy(pixels, 3 * columns * row, body, stride * row, 3 * columns);
                }
                directory.add(out.section("IMAG", image(2, 3, columns, rows, stride, body)));
            } else {
                directory.add(out.section("IMA2", image(2, 11, columns, rows, 0, huffmanThumbnail(columns, rows,
                        pixels))));
            }
        }
        if (quattroJpeg) {
            var jpeg = JpegEncoder.encode(RgbaPicture.allocate(64, 40, false));
            directory.add(out.section("IMA2", image(2, 25, 64, 40, 0, jpeg)));
        }
        directory.add(out.section("PROP", properties()));
        if (camf) {
            if (entries.isEmpty()) {
                defaultEntries();
            }
            directory.add(out.section("CAMF", camfSection()));
        }
        if (raw) {
            var body = uncompressed() ? uncompressedBody() : older() ? huffmanBody() : trueBody();
            directory.add(out.section("IMA2", image(type, format, width, height, rowSize >= 0 ? rowSize : 0, body)));
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

    private static byte[] image(int type, int format, int columns, int rows, long rowSize, byte[] body) {
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
        var table = new ByteArrayOutputStream();
        for (var entry : TRUE_TABLE) {
            table.write(entry[0]);
            table.write(entry[1] << (8 - entry[0]));
        }
        table.write(0);
        table.write(0);
        if (camfType == 2) {
            var key = 0x1234567L;
            data = scramble(plain, key);
            values = new int[]{0, 0, 0, (int) key};
        } else if (camfType == 4) {
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
            data = new byte[32 + coded.length];
            System.arraycopy(table.toByteArray(), 0, data, 0, table.size());
            System.arraycopy(coded, 0, data, 32, coded.length);
            values = new int[]{plain.length, 512, columns, rows};
        } else {
            var bias = 300;
            var bits = new Bits();
            var sum = bias;
            for (var value : plain) {
                var difference = (byte) ((value & 0xFF) - (sum & 0xFF));
                var count = difference == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
                bits.write(TRUE_TABLE[count][1], TRUE_TABLE[count][0]);
                if (count > 0) {
                    bits.write(difference > 0 ? difference : difference + (1 << count) - 1, count);
                }
                sum += difference;
            }
            var coded = bits.bytes();
            data = new byte[32 + coded.length];
            System.arraycopy(table.toByteArray(), 0, data, 0, table.size());
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(28, coded.length);
            System.arraycopy(coded, 0, data, 32, coded.length);
            values = new int[]{plain.length, bias, 0, 0};
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
        var columns = columns();
        var rows = rows();
        if (quattro()) {
            var sizes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            for (var layer = 0; layer < 3; layer++) {
                sizes.putShort((short) columns[layer]).putShort((short) rows[layer]);
            }
            out.writeBytes(sizes.array());
        }
        var head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 512).putShort((short) 512)
                .putShort((short) 512).putShort((short) 0);
        out.writeBytes(head.array());
        for (var entry : TRUE_TABLE) {
            out.write(entry[0]);
            out.write(entry[1] << (8 - entry[0]));
        }
        out.write(0);
        out.write(0);
        if (quattro()) {
            out.writeBytes(new byte[4]);
        }
        var streams = new byte[3][];
        for (var layer = 0; layer < 3; layer++) {
            streams[layer] = encodeTrue(layers[layer], columns[layer], rows[layer], 512);
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

    private byte[] uncompressedBody() {
        var buffer = ByteBuffer.allocate(2048 + (int) rowSize * height).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 1024; index++) {
            buffer.putShort((short) (index - 512));
        }
        for (var row = 0; row < height; row++) {
            buffer.position(2048 + (int) rowSize * row);
            var previous = new int[3];
            for (var column = 0; column < width; column++) {
                var word = 0;
                for (var layer = 0; layer < 3; layer++) {
                    var value = layers[layer][row * width + column];
                    word |= (value - previous[layer] + 512) << (10 * layer);
                    previous[layer] = value;
                }
                buffer.putInt(word);
            }
        }
        return buffer.array();
    }

    private static byte[] huffmanThumbnail(int columns, int rows, byte[] pixels) {
        var lengths = new int[256];
        for (var index = 0; index < 256; index++) {
            var magnitude = Math.min(index, 256 - index);
            lengths[index] = magnitude == 0 ? 2 : magnitude < 4 ? 5 : magnitude < 16 ? 8 : magnitude < 64 ? 11 : 13;
        }
        var codes = canonical(lengths);
        var out = new ByteArrayOutputStream();
        var table = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 256; index++) {
            table.putInt(lengths[index] << 27 | codes[index]);
        }
        out.writeBytes(table.array());
        var offsets = new int[rows];
        var data = new ByteArrayOutputStream();
        for (var row = 0; row < rows; row++) {
            offsets[row] = data.size();
            var bits = new Bits();
            var previous = new int[3];
            for (var index = 0; index < 3 * columns; index++) {
                var channel = index % 3;
                var value = pixels[3 * columns * row + index] & 0xFF;
                var code = (value - previous[channel]) & 0xFF;
                bits.write(codes[code], lengths[code]);
                previous[channel] = value;
            }
            data.writeBytes(bits.bytes());
            while (data.size() % 4 != 0) {
                data.write(0);
            }
        }
        out.writeBytes(data.toByteArray());
        var table2 = ByteBuffer.allocate(4 * rows).order(ByteOrder.LITTLE_ENDIAN);
        for (var offset : offsets) {
            table2.putInt(offset);
        }
        out.writeBytes(table2.array());
        return out.toByteArray();
    }

    /**
     * Returns canonical codes of the given lengths, in index order within each length.
     *
     * @param lengths the code length of each index
     * @return the codes, right-aligned
     */
    public static int[] canonical(int[] lengths) {
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

    /**
     * Codes one layer with the TRUE coding.
     *
     * @param values the layer, row by row
     * @param width the columns
     * @param height the rows
     * @param seed the starting value
     * @return the stream
     */
    public static byte[] encodeTrue(int[] values, int width, int height, int seed) {
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

    /**
     * Returns a metadata block entry: {@code "CMb"} and the kind, version, length, name offset, value offset,
     * the name and the value.
     *
     * @param kind {@code 'M'}, {@code 'T'} or {@code 'P'}
     * @param name the name
     * @param value the value
     * @return the entry
     */
    public static byte[] entry(char kind, String name, byte[] value) {
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var buffer = ByteBuffer.allocate(valueOffset + value.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'C', 'M', 'b', (byte) kind}).putInt(0x20000).putInt(valueOffset + value.length)
                .putInt(20).putInt(valueOffset).put(nameBytes);
        buffer.position(valueOffset);
        return buffer.put(value).array();
    }

    /**
     * Returns a matrix entry.
     *
     * @param name the name
     * @param type the element type: 0 signed 16-bit, 1 and 2 unsigned 32-bit, 3 float, 5 unsigned 8-bit, 6 unsigned
     * 16-bit
     * @param dimensions the size of each dimension
     * @param values the elements
     * @return the entry
     */
    public static byte[] matrix(String name, int type, int[] dimensions, double... values) {
        var width = switch (type) {
            case 0, 6 -> 2;
            case 5 -> 1;
            default -> 4;
        };
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var dataOffset = valueOffset + 12 + 12 * dimensions.length;
        var value = ByteBuffer.allocate(12 + 12 * dimensions.length + width * values.length)
                .order(ByteOrder.LITTLE_ENDIAN);
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

    /**
     * Returns a floating-point matrix entry.
     *
     * @param name the name
     * @param dimensions the size of each dimension
     * @param values the elements
     * @return the entry
     */
    public static byte[] floats(String name, int[] dimensions, double... values) {
        return matrix(name, 3, dimensions, values);
    }

    /**
     * Returns a property list entry.
     *
     * @param name the name
     * @param pairs the property names and values, alternating
     * @return the entry
     */
    public static byte[] propertyList(String name, String... pairs) {
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var count = pairs.length / 2;
        var strings = new ByteArrayOutputStream();
        var offsets = new int[pairs.length];
        for (var index = 0; index < pairs.length; index++) {
            offsets[index] = strings.size();
            strings.writeBytes((pairs[index] + "\0").getBytes(StandardCharsets.ISO_8859_1));
        }
        var base = valueOffset + 8 + 8 * count;
        var buffer = ByteBuffer.allocate(8 + 8 * count + strings.size()).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(count).putInt(base);
        for (var offset : offsets) {
            buffer.putInt(offset);
        }
        return entry('P', name, buffer.put(strings.toByteArray()).array());
    }

    /**
     * Returns a text entry.
     *
     * @param name the name
     * @param text the text
     * @return the entry
     */
    public static byte[] text(String name, String text) {
        var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        var buffer = ByteBuffer.allocate(4 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        return entry('T', name, buffer.putInt(bytes.length).put(bytes).array());
    }
}
