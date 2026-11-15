package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Phase One IIQ files laid out as the CC0 samples are: a TIFF header, Phase One's block from byte 8 (its sensor
 * data right after its head, then the tables, the calibration and the directory) and a TIFF directory naming the
 * camera. The row coder picks, for each group of eight photosites, the smallest width that holds their differences,
 * and writes the photosites after a row's last whole group as 16-bit values.
 */
public final class IiqWriter {
    /**
     * The compression the sensor data is written with: 0, 3, 5 or 8.
     */
    public int compression = 3;

    /**
     * The sensor's width and height.
     */
    public final int width, height;

    /**
     * The image inside the sensor.
     */
    public int left = 8, top = 4, imageWidth, imageHeight;

    /**
     * The black level, or -1 for no tag.
     */
    public int black = 1024;

    /**
     * Whether the file has the row numbers of the backs whose photosites keep their black level.
     */
    public boolean keepsBlack;

    /**
     * The black number of each row, or {@code null}.
     */
    public short[] rowBlack;

    /**
     * The value of a level grid's nodes over 32768, or 0 for no grid.
     */
    public int levelGrid;

    /**
     * The value of a color grid's red and blue nodes over 32768, or 0 for no grid.
     */
    public int redGrid, blueGrid;

    /**
     * The numbers each photosite is coded as, row by row, of {@code width * height}.
     */
    public final int[] numbers;

    /**
     * The defect records: column, row, kind.
     */
    public final List<int[]> defects = new ArrayList<>();

    /**
     * Further scalar entries of the block, by tag.
     */
    public final Map<Integer, Integer> scalars = new LinkedHashMap<>();

    /**
     * Further entries of the block that hold bytes, by tag.
     */
    public final Map<Integer, byte[]> tables = new LinkedHashMap<>();

    /**
     * Further entries of the calibration block: the tag, then the payload.
     */
    public final List<Map.Entry<Integer, byte[]>> calibrations = new ArrayList<>();

    /**
     * The camera model of the TIFF directory.
     */
    public String model = "P40+";

    /**
     * Creates a writer.
     *
     * @param width the sensor's width
     * @param height the sensor's height
     */
    public IiqWriter(int width, int height) {
        this.width = width;
        this.height = height;
        this.imageWidth = width - 16;
        this.imageHeight = height - 8;
        this.numbers = new int[width * height];
    }

    /**
     * Bits read by the decoder from the most significant one of little-endian words.
     */
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void put(int value, int bits) {
            buffer = buffer << bits | (value & ((1L << bits) - 1));
            count += bits;
            while (count >= 32) {
                count -= 32;
                var word = (int) (buffer >>> count);
                out.write(word);
                out.write(word >>> 8);
                out.write(word >>> 16);
                out.write(word >>> 24);
            }
        }

        byte[] finish() {
            if (count > 0) {
                put(0, 32 - count);
            }
            return out.toByteArray();
        }
    }

    /**
     * Writes a width code.
     *
     * @param bits the stream
     * @param width the width
     * @param current the width of the group before
     */
    private static void code(Bits bits, int width, int current) {
        if (width == current) {
            bits.put(1, 1);
            return;
        }
        switch (width) {
            case 7 -> bits.put(0b011, 3);
            case 8 -> bits.put(0b010, 3);
            case 9 -> bits.put(0b0011, 4);
            case 6 -> bits.put(0b0010, 4);
            case 10 -> bits.put(0b00011, 5);
            case 11 -> bits.put(0b00010, 5);
            case 12 -> bits.put(0b000011, 6);
            case 5 -> bits.put(0b000010, 6);
            case 13 -> bits.put(0b000001, 6);
            default -> bits.put(0, 6);
        }
    }

    /**
     * {@return the smallest width that holds a lane's differences}
     *
     * @param row the row's numbers
     * @param x the group's first column
     * @param lane the lane
     * @param last the lane's value before the group
     */
    private static int widthOf(int[] row, int x, int lane, int last) {
        var low = 0;
        var high = 0;
        for (var index = lane; index < 8; index += 2) {
            var at = Math.min(x + index, row.length - 1);
            var difference = row[at] - last;
            low = Math.min(low, difference);
            high = Math.max(high, difference);
            last = row[at];
        }
        for (var width : new int[]{5, 6, 7, 8, 9, 10, 11, 12, 13}) {
            var excess = (1 << (width - 1)) - 1;
            if (low >= -excess && high <= (1 << width) - 1 - excess) {
                return width;
            }
        }
        return 16;
    }

    /**
     * {@return a row's bits}
     *
     * @param y the row
     */
    private byte[] row(int y) {
        var row = new int[width];
        System.arraycopy(numbers, y * width, row, 0, width);
        var bits = new Bits();
        int even = 16, odd = 16, lastEven = 0, lastOdd = 0;
        for (var x = 0; x < width; x += 8) {
            if (width - x < 8) {
                for (var at = x; at < width; at++) {
                    bits.put(row[at], 16);
                }
                break;
            }
            var newEven = x == 0 ? 16 : widthOf(row, x, 0, lastEven);
            var newOdd = x == 0 ? 16 : widthOf(row, x, 1, lastOdd);
            code(bits, newEven, x == 0 ? -1 : even);
            code(bits, newOdd, x == 0 ? -1 : odd);
            even = newEven;
            odd = newOdd;
            for (var index = 0; index < 8; index++) {
                var at = Math.min(x + index, width - 1);
                var bitsOf = (index & 1) == 0 ? even : odd;
                var last = (index & 1) == 0 ? lastEven : lastOdd;
                bits.put(bitsOf == 16 ? row[at] : row[at] - last + (1 << (bitsOf - 1)) - 1, bitsOf);
                if ((index & 1) == 0) {
                    lastEven = row[at];
                } else {
                    lastOdd = row[at];
                }
            }
        }
        return bits.finish();
    }

    /**
     * {@return the grid entry of a whole sensor, of two nodes per side}
     *
     * @param values the values of each node
     */
    private byte[] grid(int... values) {
        var buffer = ByteBuffer.allocate(16 + 4 * values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0).putShort((short) 0).putShort((short) (2 * width)).putShort((short) (2 * height));
        buffer.putShort((short) width).putShort((short) height).putInt(0);
        for (var node = 0; node < 4; node++) {
            for (var value : values) {
                buffer.putShort((short) value);
            }
        }
        return buffer.array();
    }

    /**
     * {@return the calibration block}
     */
    private byte[] calibration() {
        var entries = new ArrayList<int[]>();
        var payloads = new ArrayList<byte[]>();
        if (levelGrid != 0) {
            entries.add(new int[]{0x410});
            payloads.add(grid(levelGrid));
        }
        if (redGrid != 0) {
            entries.add(new int[]{0x40B});
            payloads.add(grid(redGrid, blueGrid));
        }
        if (!defects.isEmpty()) {
            var buffer = ByteBuffer.allocate(defects.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (var defect : defects) {
                buffer.putShort((short) defect[0]).putShort((short) defect[1]).putShort((short) defect[2]).putShort((short) 0);
            }
            entries.add(new int[]{0x400});
            payloads.add(buffer.array());
        }
        for (var entry : calibrations) {
            entries.add(new int[]{entry.getKey()});
            payloads.add(entry.getValue());
        }
        var head = 12;
        var size = head + payloads.stream().mapToInt(payload -> payload.length).sum();
        var out = ByteBuffer.allocate(size + 8 + entries.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
        out.put("IIII".getBytes(StandardCharsets.ISO_8859_1)).putInt(1).putInt(size);
        for (var payload : payloads) {
            out.put(payload);
        }
        out.putInt(entries.size()).putInt(0);
        var at = head;
        for (var index = 0; index < entries.size(); index++) {
            out.putInt(entries.get(index)[0]).putInt(payloads.get(index).length).putInt(at);
            at += payloads.get(index).length;
        }
        return out.array();
    }

    /**
     * {@return the file}
     */
    public byte[] write() {
        var data = new ByteArrayOutputStream();
        var offsets = ByteBuffer.allocate(height * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (compression == 0) {
            var buffer = ByteBuffer.allocate(numbers.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var number : numbers) {
                buffer.putShort((short) number);
            }
            data.writeBytes(buffer.array());
        } else {
            for (var y = 0; y < height; y++) {
                offsets.putInt(data.size());
                data.writeBytes(row(y));
            }
        }
        var sensor = data.toByteArray();
        var body = new ByteArrayOutputStream();
        var tags = new ArrayList<int[]>();
        var dataAt = 12;
        var at = dataAt + sensor.length;
        var scalars = new int[][]{{0x108, width}, {0x109, height}, {0x10A, left}, {0x10B, top}, {0x10C, imageWidth},
                {0x10D, imageHeight}, {0x10E, compression}, {0x100, 0}};
        var extras = new ArrayList<Object[]>();
        var balance = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(2f).putFloat(1f).putFloat(1.5f).array();
        extras.add(new Object[]{0x107, balance});
        if (compression != 0) {
            extras.add(new Object[]{0x21C, offsets.array()});
        }
        if (rowBlack != null) {
            var buffer = ByteBuffer.allocate(rowBlack.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var number : rowBlack) {
                buffer.putShort(number);
            }
            extras.add(new Object[]{0x21F, buffer.array()});
        }
        if (keepsBlack) {
            extras.add(new Object[]{0x25A, new byte[height * 4]});
        }
        for (var table : tables.entrySet()) {
            extras.add(new Object[]{table.getKey(), table.getValue()});
        }
        extras.add(new Object[]{0x110, calibration()});
        for (var extra : extras) {
            var bytes = (byte[]) extra[1];
            tags.add(new int[]{(Integer) extra[0], bytes.length, at});
            body.writeBytes(bytes);
            at += bytes.length;
        }
        var count = scalars.length + this.scalars.size() + tags.size() + 1 + (black >= 0 ? 1 : 0);
        var directoryAt = at;
        var directory = ByteBuffer.allocate(8 + count * 16).order(ByteOrder.LITTLE_ENDIAN);
        directory.putInt(count).putInt(0);
        directory.putInt(0x10F).putInt(2).putInt(sensor.length).putInt(dataAt);
        for (var scalar : scalars) {
            directory.putInt(scalar[0]).putInt(4).putInt(4).putInt(scalar[1]);
        }
        if (black >= 0) {
            directory.putInt(0x21D).putInt(4).putInt(4).putInt(black);
        }
        for (var scalar : this.scalars.entrySet()) {
            directory.putInt(scalar.getKey()).putInt(4).putInt(4).putInt(scalar.getValue());
        }
        for (var tag : tags) {
            directory.putInt(tag[0]).putInt(1).putInt(tag[1]).putInt(tag[2]);
        }
        var ifdAt = 8 + directoryAt + directory.capacity();
        var make = "Phase One\0".getBytes(StandardCharsets.ISO_8859_1);
        var model = (this.model + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var ifd = ByteBuffer.allocate(2 + 24 + 4 + make.length + model.length).order(ByteOrder.LITTLE_ENDIAN);
        ifd.putShort((short) 2);
        ifd.putShort((short) 271).putShort((short) 2).putInt(make.length).putInt(ifdAt + 30);
        ifd.putShort((short) 272).putShort((short) 2).putInt(model.length).putInt(ifdAt + 30 + make.length);
        ifd.putInt(0).put(make).put(model);
        var file = new ByteArrayOutputStream();
        var head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        head.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdAt);
        head.put("IIIICwaR".getBytes(StandardCharsets.ISO_8859_1)).putInt(directoryAt);
        file.writeBytes(head.array());
        file.writeBytes(sensor);
        file.writeBytes(body.toByteArray());
        file.writeBytes(directory.array());
        file.writeBytes(ifd.array());
        return file.toByteArray();
    }
}
