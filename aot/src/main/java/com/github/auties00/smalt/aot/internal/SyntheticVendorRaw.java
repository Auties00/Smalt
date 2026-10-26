package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the Minolta MRW, Canon CRW and Sigma X3F files the camera raw training workloads decode,
 * deterministically, with the sensor data derived from a synthetic picture.
 *
 * <p>The files hold only the records the decoders read: the sensor geometry, levels, white balance
 * and shot records, a JPEG preview where the decoder estimates the white balance from it, and the
 * sensor data in the formats' own codings (packed or 16-bit MRW samples, the CRW low bits and
 * Huffman-coded high bits, the X3F TRUE and older Huffman codings with their metadata blocks).
 */
final class SyntheticVendorRaw {
    /**
     * The table set of the CRW stream.
     */
    private static final int CRW_SET = 2;

    /**
     * The marker of a CRW code whose value is not known.
     */
    private static final int UNKNOWN = -1;

    /**
     * The DC table of CRW table set 2, in the form of a JPEG {@code DHT} segment: sixteen code
     * counts by length, then the values in code order; a copy of the decoder's table.
     */
    private static final int[] CRW_DC = {0, 0, 6, 3, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            6, 5, 7, 4, 8, 3, 9, 2, 0, 10, 1, 11, UNKNOWN};

    /**
     * The AC table of CRW table set 2, in the form of {@link #CRW_DC}; a copy of the decoder's table.
     */
    private static final int[] CRW_AC = {0, 0, 6, 2, 1, 3, 3, 2, 5, 1, 2, 2, 8, 10, 0, 120,
            4, 5, 3, 6, 2, 7, 1, 8, 9, 0x12, 0x13, 0x14, 0x11, 0x15, 10, 0x16, 0x17, 0xF0, 0, 0x22, 0x21,
            0x18, 0x23, 0x19, 0x24, 0x32, 0x31, 0x25, 0x33, 0x38, 0x37, 0x34, 0x35, 0x36, 0x39, 0x79, 0x57,
            0x58, 0x59, 0x28, 0x56, 0x78, 0x27, 0x41, 0x29, 0x77, 0x26, 0x42, 0x76, 0x99, 0x1A, 0x55, 0x98,
            0x97, 0xF9, 0x48, 0x54, 0x96, 0x89, 0x47, 0xB7, 0x49, 0xFA, 0x75, 0x68, 0xB6, 0x67, 0x69, 0xB9,
            0xB8, 0xD8, 0x52, 0xD7, 0x88, 0xB5, 0x74, 0x51, 0x46, 0xD9, 0xF8, 0x3A, 0xD6, 0x87, 0x45, 0x7A,
            0x95, 0xD5, 0xF6, 0x86, 0xB4, 0xA9, 0x94, 0x53, 0x2A, 0xA8, 0x43, 0xF5, 0xF7, 0xD4, 0x66, 0xA7,
            0x5A, 0x44, 0x8A, 0xC9, 0xE8, 0xC8, 0xE7, 0x9A, 0x6A, 0x73, 0x4A, 0x61, 0xC7, 0xF4, 0xC6, 0x65,
            0xE9, 0x72, 0xE6, 0x71, 0x91, 0x93, 0xA6, 0xDA, 0x92, 0x85, 0x62, 0xF3, 0xC5, 0xB2, 0xA4, 0x84,
            0xBA, 0x64, 0xA5, 0xB3, 0xD2, 0x81, 0xE5, 0xD3, 0xAA, 0xC4, 0xCA, 0xF2, 0xB1, 0xE4, 0xD1, 0x83,
            0x63, 0xEA, 0xC3, 0xE2, 0x82, 0xF1, 0xA3, 0xC2, 0xA1, 0xC1, 0xE3, 0xA2, 0xE1, UNKNOWN,
            UNKNOWN, UNKNOWN};

    /**
     * The black level of the CRW sensor data.
     */
    private static final int CRW_BLACK = 128;

    /**
     * The X3F TRUE code table: the length and the code of each difference bit count, from 0 to 12.
     */
    private static final int[][] TRUE_TABLE = {{3, 0b100}, {3, 0b010}, {3, 0b001}, {4, 0b1100}, {4, 0b0110},
            {4, 0b1110}, {4, 0b0111}, {4, 0b1111}, {4, 0b0001}, {4, 0b1010}, {5, 0b11010}, {6, 0b110110},
            {6, 0b110111}};

    /**
     * The seed of every X3F TRUE layer and of the type 4 metadata block.
     */
    private static final int TRUE_SEED = 512;

    /**
     * The offset of a difference in the X3F Huffman coding's tables.
     */
    private static final int HUFFMAN_OFFSET = 512;

    /**
     * Prevents instantiation.
     */
    private SyntheticVendorRaw() {
        throw new AssertionError();
    }

    /**
     * Writes an MRW file of an RGGB mosaic of a picture, with a centered image area 8 photosites
     * smaller than the sensor in each direction.
     *
     * @param picture the picture, whose width and height are even
     * @param packed {@code true} for 12-bit samples packed two in three bytes, {@code false} for one
     * 16-bit word per sample
     * @return the file
     */
    static byte[] mrw(RgbaPicture picture, boolean packed) {
        var width = picture.width();
        var height = picture.height();
        var samples = mosaic(picture, 0, 4000);
        var header = new ByteArrayOutputStream();
        var prd = ByteBuffer.allocate(24);
        prd.put("21810002".getBytes(StandardCharsets.US_ASCII)).putShort((short) height).putShort((short) width)
                .putShort((short) (height - 8)).putShort((short) (width - 8)).put((byte) (packed ? 12 : 16))
                .put((byte) 12).put((byte) (packed ? 0x59 : 0x52)).put(new byte[4]).put((byte) 1);
        mrwBlock(header, "PRD", prd.array());
        var wbg = ByteBuffer.allocate(12).put(new byte[]{2, 2, 2, 2}).putShort((short) 400).putShort((short) 256)
                .putShort((short) 256).putShort((short) 512);
        mrwBlock(header, "WBG", wbg.array());
        var rif = new byte[64];
        rif[6] = 56;
        mrwBlock(header, "RIF", rif);
        mrwBlock(header, "TTW", tiff("KONICA MINOLTA", "SYNTHETIC"));
        byte[] data;
        if (packed) {
            data = new byte[samples.length / 2 * 3];
            for (var pair = 0; pair < samples.length / 2; pair++) {
                var a = samples[2 * pair];
                var b = samples[2 * pair + 1];
                data[3 * pair] = (byte) (a >> 4);
                data[3 * pair + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                data[3 * pair + 2] = (byte) b;
            }
        } else {
            var words = ByteBuffer.allocate(samples.length * 2);
            for (var sample : samples) {
                words.putShort((short) sample);
            }
            data = words.array();
        }
        var head = header.toByteArray();
        return ByteBuffer.allocate(8 + head.length + data.length).put(new byte[]{0, 'M', 'R', 'M'})
                .putInt(head.length).put(head).put(data).array();
    }

    /**
     * Writes a CRW file of a 12-bit RGGB mosaic of a picture with masked columns on the left, a
     * JPEG preview of the picture and no white balance record, so that the decoder estimates the
     * white balance from the preview.
     *
     * @param picture the picture, whose width and height are even
     * @return the file
     */
    static byte[] crw(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var samples = mosaic(picture, CRW_BLACK, 3800);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < 4; x++) {
                samples[y * width + x] = CRW_BLACK + (x * 7 + y * 13) % 9;
            }
        }
        var little = ByteOrder.LITTLE_ENDIAN;
        var info = new short[17];
        info[0] = 34;
        info[1] = (short) width;
        info[2] = (short) height;
        info[5] = 4;
        info[6] = 2;
        info[7] = (short) (width - 5);
        info[8] = (short) (height - 3);
        info[9] = 0;
        info[10] = 2;
        info[11] = 3;
        info[12] = (short) (height - 3);
        var shot = new short[25];
        shot[0] = 50;
        shot[2] = 160;
        shot[4] = 139;
        shot[5] = 181;
        var settings = new short[32];
        settings[0] = 64;
        settings[23] = 85;
        settings[24] = 24;
        settings[25] = 1;
        var exif = new CiffHeap();
        exif.add(0x1031, words(info));
        exif.add(0x1835, ByteBuffer.allocate(16).order(little).putInt(CRW_SET).putInt(0).putInt(514).putInt(0).array());
        exif.add(0x102A, words(shot));
        exif.add(0x102D, words(settings));
        exif.add(0x1029, words(new short[]{0, 50, 914, 610}));
        var camera = new CiffHeap();
        camera.add(0x080A, "Canon\0Canon SYNTHETIC\0".getBytes(StandardCharsets.ISO_8859_1));
        var properties = new CiffHeap();
        properties.add(0x1810, ByteBuffer.allocate(28).order(little).putInt(width).putInt(height).putFloat(1)
                .putInt(0).putInt(8).putInt(24).putInt(257).array());
        properties.add(0x180E, ByteBuffer.allocate(12).order(little).putInt(1_000_000_000).putInt(0).putInt(0).array());
        properties.add(0x2807, camera.write());
        properties.add(0x300B, exif.write());
        var root = new CiffHeap();
        root.add(0x2005, crwSensorData(samples, width));
        root.add(0x2007, JpegEncoder.encode(picture));
        root.add(0x300A, properties.write());
        var heap = root.write();
        return ByteBuffer.allocate(26 + heap.length).order(little).put((byte) 'I').put((byte) 'I').putInt(26)
                .put("HEAPCCDR".getBytes(StandardCharsets.US_ASCII)).putInt(0x00010002).putLong(0).put(heap).array();
    }

    /**
     * Writes an X3F file of a picture's three channels as the three layers, with a metadata block
     * giving the image areas, the dark shield, the levels and the colors, and a JPEG preview.
     *
     * @param picture the picture whose red, green and blue channels become the first, second and third layers
     * @param huffman {@code true} for the older Huffman coding with a type 2 metadata block and the
     * older color entries, {@code false} for the TRUE coding with a type 4 metadata block
     * @return the file
     */
    static byte[] x3f(RgbaPicture picture, boolean huffman) {
        var width = picture.width();
        var height = picture.height();
        var layers = new int[3][width * height];
        var low = huffman ? 5 : 40;
        var range = huffman ? 440 : 3900;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var layer = 0; layer < 3; layer++) {
                    var value = (picture.pixels()[y * picture.stride() + x * 4 + layer] & 0xFF) / 255.0;
                    layers[layer][y * width + x] = low + (int) Math.round(value * value * range);
                }
            }
        }
        for (var y = 0; y < 2; y++) {
            for (var x = 2; x < width - 2; x++) {
                for (var layer = 0; layer < 3; layer++) {
                    layers[layer][y * width + x] = 7 + layer;
                }
            }
        }
        var out = new X3fOutput();
        var header = ByteBuffer.allocate(232).order(ByteOrder.LITTLE_ENDIAN);
        header.put("FOVb".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20002).put(new byte[16]).putInt(0)
                .putInt(width - 4).putInt(height - 5).putInt(0).put("Auto".getBytes(StandardCharsets.ISO_8859_1));
        out.bytes(header.array());
        var directory = new ArrayList<long[]>();
        directory.add(out.section("IMA2", x3fImage(2, 18, width, height, 0, JpegEncoder.encode(picture))));
        directory.add(out.section("PROP", x3fProperties()));
        directory.add(out.section("CAMF", camf(camfEntries(width, height, huffman), huffman ? 2 : 4)));
        var body = huffman ? huffmanBody(layers, width, height) : trueBody(layers, width, height);
        directory.add(out.section("IMA2", x3fImage(3, huffman ? 6 : 30, width, height, 0, body)));
        var position = out.size();
        var entries = ByteBuffer.allocate(12 + 12 * directory.size()).order(ByteOrder.LITTLE_ENDIAN);
        entries.put("SECd".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(directory.size());
        for (var entry : directory) {
            entries.putInt((int) entry[0]).putInt((int) entry[1]).putInt((int) entry[2]);
        }
        out.bytes(entries.array());
        out.bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(position).array());
        return out.toByteArray();
    }

    /**
     * Returns the RGGB mosaic of a picture, each photosite mapped to a linear value between a black
     * and a white level.
     *
     * @param picture the picture whose channels the photosites sample
     * @param black the value of a black pixel
     * @param range the distance from black to the value of a full channel
     * @return the photosites, row by row
     */
    static int[] mosaic(RgbaPicture picture, int black, int range) {
        var width = picture.width();
        var height = picture.height();
        var samples = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var channel = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
                var value = (picture.pixels()[y * picture.stride() + x * 4 + channel] & 0xFF) / 255.0;
                samples[y * width + x] = black + (int) Math.round(value * value * range);
            }
        }
        return samples;
    }

    /**
     * Appends an MRW header block.
     *
     * @param header the header blocks
     * @param name the three-letter block name
     * @param body the block's content
     */
    private static void mrwBlock(ByteArrayOutputStream header, String name, byte[] body) {
        header.writeBytes(ByteBuffer.allocate(8).put((byte) 0).put(name.getBytes(StandardCharsets.US_ASCII))
                .putInt(body.length).array());
        header.writeBytes(body);
    }

    /**
     * Returns a big-endian TIFF structure whose only directory holds a make and a model.
     *
     * @param make the camera maker, ASCII
     * @param model the camera model, ASCII
     * @return the structure
     */
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

    /**
     * Returns 16-bit values as little-endian bytes.
     *
     * @param values the 16-bit values, in order
     * @return the bytes
     */
    private static byte[] words(short[] values) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putShort(value);
        }
        return buffer.array();
    }

    /**
     * Returns the CRW sensor data of 12-bit photosites: the two low bits of each, four to a byte
     * from the least significant bits, a 514-byte gap and the coded ten high bits.
     *
     * @param samples the photosites, row by row
     * @param width the width of a row
     * @return the sensor data
     */
    private static byte[] crwSensorData(int[] samples, int width) {
        var out = new ByteArrayOutputStream();
        var low = new byte[(samples.length + 3) / 4];
        var high = new int[samples.length];
        for (var index = 0; index < samples.length; index++) {
            low[index >> 2] |= (byte) ((samples[index] & 3) << ((index & 3) * 2));
            high[index] = samples[index] >> 2;
        }
        out.writeBytes(low);
        out.writeBytes(new byte[514]);
        out.writeBytes(crwStream(high, width));
        return out.toByteArray();
    }

    /**
     * Codes the ten high bits of every photosite in blocks of 64 consecutive photosites, each value
     * the difference from the photosite two columns to its left (512 for the first two of a row),
     * the first of a block coded as its change from the previous block's first.
     *
     * @param high the high bits, row by row
     * @param width the width of a row
     * @return the stream, with {@code FF 00} byte stuffing and an end marker
     * @throws IllegalStateException if the picture needs a code whose value is unknown
     */
    private static byte[] crwStream(int[] high, int width) {
        var dc = codes(CRW_DC);
        var ac = codes(CRW_AC);
        var bits = new BitWriter(true);
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
        var stream = bits.bytes();
        var out = new ByteArrayOutputStream();
        out.writeBytes(stream);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /**
     * Returns the canonical codes of a table in the form of {@link #CRW_DC}, by value.
     *
     * @param spec the table
     * @return each known value's code and code length
     */
    private static Map<Integer, int[]> codes(int[] spec) {
        var codes = new HashMap<Integer, int[]>();
        var code = 0;
        var index = 16;
        for (var length = 1; length <= 16; length++) {
            for (var count = 0; count < spec[length - 1]; count++) {
                var value = spec[index++];
                if (value != UNKNOWN) {
                    codes.put(value, new int[]{code, length});
                }
                code++;
            }
            code <<= 1;
        }
        return codes;
    }

    /**
     * {@return the number of bits of a difference's magnitude, as in a JPEG scan}
     *
     * @param value the difference
     */
    private static int category(int value) {
        return 32 - Integer.numberOfLeadingZeros(Math.abs(value));
    }

    /**
     * {@return the bits that code a difference of a category, as in a JPEG scan}
     *
     * @param value the difference
     * @param category its category
     */
    private static int magnitude(int value, int category) {
        return value >= 0 ? value : value + (1 << category) - 1;
    }

    /**
     * Returns an X3F image section.
     *
     * @param type the image type
     * @param format the image format
     * @param columns the width
     * @param rows the height
     * @param rowSize the bytes of a row, 0 for coded data
     * @param body the data after the section header
     * @return the section
     */
    private static byte[] x3fImage(int type, int format, int columns, int rows, int rowSize, byte[] body) {
        var section = ByteBuffer.allocate(28 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        section.put("SECi".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(type).putInt(format)
                .putInt(columns).putInt(rows).putInt(rowSize).put(body);
        return section.array();
    }

    /**
     * Returns the X3F property list section: the camera, the shot and the capture time.
     *
     * @return the section
     */
    private static byte[] x3fProperties() {
        var properties = List.of("CAMMANUF", "SIGMA", "CAMMODEL", "SIGMA SYNTHETIC", "ISO", "200", "SHUTTER",
                "0.00800", "APERTURE", "5.60000", "FLENGTH", "16.6", "TIME", "1265012852");
        var characters = new StringBuilder();
        var offsets = new ArrayList<int[]>();
        for (var index = 0; index < properties.size(); index += 2) {
            var name = characters.length();
            characters.append(properties.get(index)).append('\0');
            var value = characters.length();
            characters.append(properties.get(index + 1)).append('\0');
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

    /**
     * Returns the X3F metadata block entries: the kept and active image areas, the dark shield, the
     * saturation levels and the color entries of the coding's camera generation, and the ISOs.
     *
     * @param width the width of the layers
     * @param height the height of the layers
     * @param older {@code true} for the illuminant matrices of the cameras with the older coding,
     * {@code false} for the white balance gains and color corrections of the TRUE cameras
     * @return the entries
     */
    private static List<byte[]> camfEntries(int width, int height, boolean older) {
        var entries = new ArrayList<byte[]>();
        entries.add(matrix("KeepImageArea", 1, new int[]{4}, 0, 0, width - 1, height - 1));
        entries.add(matrix("ActiveImageArea", 1, new int[]{4}, 2, 3, width - 3, height - 2));
        entries.add(matrix("DarkShieldTop", 1, new int[]{4}, 2, 0, width - 3, 1));
        entries.add(matrix("DarkShieldColRange", 2, new int[]{2, 2}, 0, 0, width - 1, width - 1));
        if (older) {
            entries.add(matrix("SaturationLevel", 0, new int[]{3}, 3000, 3100, 3200));
            entries.add(propertyList("WhiteBalanceIlluminants", "Auto", "CamToXYZ"));
            entries.add(propertyList("WhiteBalanceCorrections", "Auto", "Correction"));
            entries.add(matrix("CamToXYZ", 3, new int[]{3, 3}, 0.9, 0.4, 0.5, -2.1, 5.0, -1.6, 2.2, -8.1, 8.7));
            entries.add(matrix("Correction", 3, new int[]{3, 3}, 1.01, 0, 0, 0, 1, 0, 0, 0, 0.9));
        } else {
            entries.add(matrix("RawSaturationLevel", 0, new int[]{3}, 3990, 4077, 4074));
            entries.add(propertyList("WhiteBalanceGains", "Auto", "AutoWBGain"));
            entries.add(propertyList("WhiteBalanceColorCorrections", "Auto", "AutoCCMatrix"));
            entries.add(matrix("AutoWBGain", 3, new int[]{3}, 1.5, 1.0, 0.8));
            entries.add(matrix("AutoCCMatrix", 3, new int[]{3, 3}, 3.6, -4.4, 1.8, -1.3, 3.1, -0.8, 1.0, -3.6, 3.6));
            entries.add(matrix("TempGainFact", 3, new int[]{3}, 1.0, 1.02, 1.04));
        }
        entries.add(matrix("SensorISO", 3, new int[]{1}, 100));
        entries.add(matrix("CaptureISO", 3, new int[]{1}, 400));
        return entries;
    }

    /**
     * Returns an X3F metadata block section.
     *
     * @param entries the encoded entries, in order
     * @param type 2 for the XOR-scrambled block, 4 for the TRUE-coded block of 12-bit values
     * @return the section
     */
    private static byte[] camf(List<byte[]> entries, int type) {
        var body = new ByteArrayOutputStream();
        for (var entry : entries) {
            body.writeBytes(entry);
        }
        var plain = body.toByteArray();
        byte[] data;
        int[] values;
        if (type == 2) {
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
            var coded = encodeTrue(twelve, columns, rows);
            var table = trueTable();
            data = new byte[32 + coded.length];
            System.arraycopy(table, 0, data, 0, table.length);
            System.arraycopy(coded, 0, data, 32, coded.length);
            values = new int[]{plain.length, TRUE_SEED, columns, rows};
        }
        var section = ByteBuffer.allocate(28 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        section.put("SECc".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x20000).putInt(type);
        for (var value : values) {
            section.putInt(value);
        }
        return section.put(data).array();
    }

    /**
     * Scrambles a type 2 metadata block with its key stream.
     *
     * @param plain the entries
     * @param seed the key
     * @return the scrambled bytes
     */
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

    /**
     * Returns a metadata block entry: {@code "CMb"} and its kind, the version, the length, the
     * offsets of the name and of the value, the name and the value.
     *
     * @param kind {@code 'M'} for a matrix, {@code 'P'} for a property list
     * @param name the entry name
     * @param value the entry value
     * @return the entry
     */
    private static byte[] entry(char kind, String name, byte[] value) {
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
     * @param name the entry name
     * @param type the element type: 0 for 16-bit integers, 1 and 2 for 32-bit integers, 3 for floats
     * @param dimensions the size of each dimension
     * @param values the elements
     * @return the entry
     */
    private static byte[] matrix(String name, int type, int[] dimensions, double... values) {
        var size = type == 0 ? 2 : 4;
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var dataOffset = valueOffset + 12 + 12 * dimensions.length;
        var value = ByteBuffer.allocate(12 + 12 * dimensions.length + size * values.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        value.putInt(type).putInt(dimensions.length).putInt(dataOffset);
        for (var index = 0; index < dimensions.length; index++) {
            value.putInt(dimensions[index]).putInt(0).putInt(index);
        }
        for (var element : values) {
            switch (type) {
                case 0 -> value.putShort((short) element);
                case 3 -> value.putFloat((float) element);
                default -> value.putInt((int) (long) element);
            }
        }
        return entry('M', name, value.array());
    }

    /**
     * Returns a property list entry of one name and value.
     *
     * @param name the entry name
     * @param key the property name
     * @param value the property value
     * @return the entry
     */
    private static byte[] propertyList(String name, String key, String value) {
        var nameBytes = (name + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var valueOffset = (20 + nameBytes.length + 3) / 4 * 4;
        var strings = (key + "\0" + value + "\0").getBytes(StandardCharsets.ISO_8859_1);
        var base = valueOffset + 16;
        var buffer = ByteBuffer.allocate(16 + strings.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(1).putInt(base).putInt(0).putInt(key.length() + 1).put(strings);
        return entry('P', name, buffer.array());
    }

    /**
     * {@return the TRUE code table as stored: a length byte and a left-aligned code byte per entry,
     * then two zero bytes}
     */
    private static byte[] trueTable() {
        var table = new ByteArrayOutputStream();
        for (var entry : TRUE_TABLE) {
            table.write(entry[0]);
            table.write(entry[1] << (8 - entry[0]));
        }
        table.write(0);
        table.write(0);
        return table.toByteArray();
    }

    /**
     * Returns the body of a TRUE image section: the layer seeds, the code table, the length of each
     * layer's stream and the streams, each padded to 16 bytes.
     *
     * @param layers the layer values
     * @param width the width of the layers
     * @param height the height of the layers
     * @return the body
     */
    private static byte[] trueBody(int[][] layers, int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) TRUE_SEED)
                .putShort((short) TRUE_SEED).putShort((short) TRUE_SEED).putShort((short) 0).array());
        out.writeBytes(trueTable());
        var streams = new byte[3][];
        var sizes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        for (var layer = 0; layer < 3; layer++) {
            streams[layer] = encodeTrue(layers[layer], width, height);
            sizes.putInt(streams[layer].length);
        }
        out.writeBytes(sizes.array());
        for (var layer = 0; layer < 3; layer++) {
            var start = out.size();
            out.writeBytes(streams[layer]);
            while ((out.size() - start) % 16 != 0) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    /**
     * Codes values with the TRUE coding: each value is the difference from the value two columns to
     * its left, the first two of a row from the first two of the row two above (the seed for the
     * first rows), as a code of the difference's bit count and the difference's bits.
     *
     * @param values the values, row by row, from 0 to 4095
     * @param width the width of a row
     * @param height the number of rows
     * @return the stream
     */
    private static byte[] encodeTrue(int[] values, int width, int height) {
        var bits = new BitWriter(false);
        var rowStart = new int[]{TRUE_SEED, TRUE_SEED, TRUE_SEED, TRUE_SEED};
        var accumulator = new int[2];
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var parity = column & 1;
                var slot = (row & 1) * 2 + parity;
                var previous = column < 2 ? rowStart[slot] : accumulator[parity];
                var value = values[row * width + column];
                var difference = value - previous;
                var count = category(difference);
                bits.write(TRUE_TABLE[count][1], TRUE_TABLE[count][0]);
                if (count > 0) {
                    bits.write(magnitude(difference, count), count);
                }
                accumulator[parity] = value;
                if (column < 2) {
                    rowStart[slot] = value;
                }
            }
        }
        return bits.bytes();
    }

    /**
     * Returns the body of an image section in the older Huffman coding: the difference of each of
     * the 1024 codes, the length and the code of each, the rows, each coding every pixel's three
     * layer values as differences from the pixel to its left and padded to 4 bytes, and the offset
     * of each row.
     *
     * @param layers the layer values, from 0 to 511
     * @param width the width of the layers
     * @param height the height of the layers
     * @return the body
     */
    private static byte[] huffmanBody(int[][] layers, int width, int height) {
        var lengths = new int[1024];
        for (var index = 0; index < 1024; index++) {
            var magnitude = Math.abs(index - HUFFMAN_OFFSET);
            lengths[index] = magnitude == 0 ? 2 : magnitude < 4 ? 5 : magnitude < 16 ? 8 : magnitude < 64 ? 11
                    : magnitude < 256 ? 14 : 17;
        }
        var codes = new int[1024];
        var code = 0;
        var previousLength = 0;
        for (var length = 1; length <= 27; length++) {
            for (var index = 0; index < 1024; index++) {
                if (lengths[index] == length) {
                    code <<= length - previousLength;
                    previousLength = length;
                    codes[index] = code++;
                }
            }
        }
        var tables = ByteBuffer.allocate(2048 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 1024; index++) {
            tables.putShort((short) (index - HUFFMAN_OFFSET));
        }
        for (var index = 0; index < 1024; index++) {
            tables.putInt(lengths[index] << 27 | codes[index]);
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(tables.array());
        var offsets = ByteBuffer.allocate(4 * height).order(ByteOrder.LITTLE_ENDIAN);
        var data = new ByteArrayOutputStream();
        for (var row = 0; row < height; row++) {
            offsets.putInt(data.size());
            var bits = new BitWriter(false);
            var previous = new int[3];
            for (var column = 0; column < width; column++) {
                for (var layer = 0; layer < 3; layer++) {
                    var value = layers[layer][row * width + column];
                    var index = value - previous[layer] + HUFFMAN_OFFSET;
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
        out.writeBytes(offsets.array());
        return out.toByteArray();
    }

    /**
     * Collects the records of a CIFF heap: the values, then the directory and the size of the
     * values. Instances are not thread-safe.
     */
    private static final class CiffHeap {
        /**
         * The values.
         */
        private final ByteArrayOutputStream values = new ByteArrayOutputStream();

        /**
         * The directory entries.
         */
        private final ByteArrayOutputStream entries = new ByteArrayOutputStream();

        /**
         * The number of entries.
         */
        private int count;

        /**
         * Adds a record.
         *
         * @param kind the record kind
         * @param value the record value, or a heap
         */
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

        /**
         * {@return the heap}
         */
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

    /**
     * Collects the sections of an X3F file, each aligned to 4 bytes. Instances are not thread-safe.
     */
    private static final class X3fOutput {
        /**
         * The file so far.
         */
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * Appends bytes.
         *
         * @param bytes the bytes to append
         */
        void bytes(byte[] bytes) {
            out.writeBytes(bytes);
        }

        /**
         * {@return the length of the file so far}
         */
        int size() {
            return out.size();
        }

        /**
         * Appends a section.
         *
         * @param type the four-character directory type
         * @param body the section
         * @return its directory entry: the offset, the length and the type
         */
        long[] section(String type, byte[] body) {
            while (out.size() % 4 != 0) {
                out.write(0);
            }
            var offset = out.size();
            out.writeBytes(body);
            return new long[]{offset, body.length, ByteBuffer.wrap(type.getBytes(StandardCharsets.ISO_8859_1))
                    .order(ByteOrder.LITTLE_ENDIAN).getInt()};
        }

        /**
         * {@return the file}
         */
        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /**
     * Writes bits most significant first. Instances are not thread-safe.
     */
    private static final class BitWriter {
        /**
         * The bytes written.
         */
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * Whether a zero byte follows every {@code FF} byte.
         */
        private final boolean stuffing;

        /**
         * The pending bits, in the low {@link #count} bits.
         */
        private long buffer;

        /**
         * The number of pending bits, less than 8 between calls.
         */
        private int count;

        /**
         * Constructs a writer.
         *
         * @param stuffing whether a zero byte follows every {@code FF} byte
         */
        BitWriter(boolean stuffing) {
            this.stuffing = stuffing;
        }

        /**
         * Writes a code.
         *
         * @param code the code and its length, or {@code null} for a code whose value is unknown
         * @throws IllegalStateException if the code is {@code null}
         */
        void code(int[] code) {
            if (code == null) {
                throw new IllegalStateException("the synthetic CRW picture needs a code whose value is unknown");
            }
            write(code[0], code[1]);
        }

        /**
         * Writes the low bits of a value.
         *
         * @param value the bits, in its low {@code bits} bits
         * @param bits the number of bits, from 0 to 32
         */
        void write(int value, int bits) {
            buffer = (buffer << bits) | (value & ((1L << bits) - 1));
            count += bits;
            while (count >= 8) {
                count -= 8;
                put((int) (buffer >>> count) & 0xFF);
            }
        }

        /**
         * Writes a byte.
         *
         * @param value the byte
         */
        private void put(int value) {
            out.write(value);
            if (stuffing && value == 0xFF) {
                out.write(0);
            }
        }

        /**
         * Pads the last byte with one bits when stuffing, zero bits otherwise, and returns the bytes.
         *
         * @return the bytes written
         */
        byte[] bytes() {
            if (count > 0) {
                write(stuffing ? (1 << (8 - count)) - 1 : 0, 8 - count);
            }
            return out.toByteArray();
        }
    }
}
