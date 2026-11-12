package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes the Canon CR3 files the camera raw training workloads decode, deterministically, with the
 * RGGB mosaic of a synthetic picture coded in CRX: the lossless coding of the four color planes,
 * or the lossy C-RAW coding of their three-level 5/3 wavelet subbands, quantized with one step
 * per subband (codec version 1) or through a quantization map (codec version 2).
 *
 * <p>The files hold only the boxes the decoder reads: the file type, the Canon metadata box with
 * the camera's make and model, and one track whose {@code CRAW} sample entry describes the coded
 * image ({@code CMP1}) and its image areas ({@code IAD1}).
 */
final class SyntheticCr3 {
    /**
     * The block length exponents of the run mode, by run index.
     */
    private static final int[] RUN_BITS = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8,
            9, 10, 11, 12, 13, 14, 15};

    /**
     * The quantization scale of each sixth of an octave, in 64ths.
     */
    private static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};

    /**
     * The UUID of the Canon metadata box.
     */
    private static final byte[] CANON = {(byte) 0x85, (byte) 0xC0, (byte) 0xB6, (byte) 0x87, (byte) 0x82, 0x0F, 0x11,
            (byte) 0xE0, (byte) 0x81, 0x11, (byte) 0xF4, (byte) 0xCE, 0x46, 0x2B, 0x6A, 0x48};

    /**
     * The version 1 quantization parameter of each subband, in coding order.
     */
    private static final int[] QUANTIZERS = {4, 4, 4, 4, 16, 16, 22, 26, 26, 32};

    /**
     * The version 2 scale of each subband, in coding order; 0 leaves a subband unquantized.
     */
    private static final int[] SCALES = {0, 0, 0, 0, 4, 4, 8, 8, 8, 16};

    /**
     * The values the version 2 quantization map takes, in turn.
     */
    private static final int[] MAP_VALUES = {300, 302, 304, 307};

    /**
     * The bits of each photosite.
     */
    private static final int BITS = 14;

    /**
     * The black level of the photosites.
     */
    private static final int BLACK = 512;

    /**
     * Prevents instantiation.
     */
    private SyntheticCr3() {
        throw new AssertionError();
    }

    /**
     * Writes a CR3 file of a picture.
     *
     * @param picture the picture, whose width and height are multiples of 16
     * @param levels the wavelet levels: 0 for lossless data, 3 for C-RAW
     * @param version the codec version, {@code 0x100} or {@code 0x200}
     * @return the file
     */
    static byte[] cr3(RgbaPicture picture, int levels, int version) {
        var width = picture.width();
        var height = picture.height();
        var samples = SyntheticVendorRaw.mosaic(picture, BLACK, 12000);
        var sample = sample(samples, width, height, levels, version);
        var canon = concat(CANON, box("CMT1", tiff("Canon", "Canon EOS R5")));
        var entry = new byte[0x5A - 8];
        var cmp1 = ByteBuffer.allocate(0x34);
        cmp1.putShort((short) -1).putShort((short) 0x30).putShort((short) version).putShort((short) 0);
        cmp1.putInt(width).putInt(height).putInt(width).putInt(height);
        cmp1.put((byte) BITS).put((byte) 0x40).put((byte) levels).put((byte) 0);
        cmp1.putInt(headerLength(levels, version));
        var areas = ByteBuffer.allocate(48);
        areas.putShort(4, (short) width).putShort(6, (short) height).putShort(16, (short) 8).putShort(18, (short) 8)
                .putShort(20, (short) (width - 9)).putShort(22, (short) (height - 9)).putShort(44, (short) (width - 1))
                .putShort(46, (short) (height - 1));
        var craw = box("CRAW", concat(entry, box("CMP1", cmp1.array()),
                box("CDI1", concat(new byte[4], box("IAD1", areas.array())))));
        var ftyp = box("ftyp", concat(ascii("crx "), u32(1), ascii("crx "), ascii("isom")));
        // the moov box has the same length whatever the chunk offset, so its first build only measures it
        var moov = moov(canon, craw, sample.length, 0);
        moov = moov(canon, craw, sample.length, ftyp.length + moov.length + 8);
        return concat(ftyp, moov, box("mdat", sample));
    }

    /**
     * Builds the {@code moov} box.
     *
     * @param canon the body of the Canon metadata box
     * @param craw the sample entry
     * @param length the sample length
     * @param offset the sample offset
     * @return the box
     */
    private static byte[] moov(byte[] canon, byte[] craw, int length, long offset) {
        var stsd = box("stsd", concat(u32(0), u32(1), craw));
        var stsz = box("stsz", concat(u32(0), u32(length), u32(1)));
        var co64 = box("co64", concat(u32(0), u32(1), ByteBuffer.allocate(8).putLong(offset).array()));
        var trak = box("trak", box("mdia", box("minf", box("stbl", concat(stsd, stsz, co64)))));
        return box("moov", concat(box("uuid", canon), trak));
    }

    /**
     * Returns the length of the headers at the start of the sample.
     *
     * @param levels the wavelet levels
     * @param version the codec version
     * @return the length
     */
    private static int headerLength(int levels, int version) {
        var second = version == 0x200;
        var bands = levels == 0 ? 1 : 3 * levels + 1;
        return (second ? 20 : 12) + 4 * (12 + bands * (second ? 20 : 12));
    }

    /**
     * Codes the sample: the headers, the quantization map of version 2 and the bitstreams.
     *
     * @param samples the photosites
     * @param width the image width
     * @param height the image height
     * @param levels the wavelet levels
     * @param version the codec version
     * @return the sample
     */
    private static byte[] sample(int[] samples, int width, int height, int levels, int version) {
        var second = version == 0x200;
        var pw = width / 2;
        var ph = height / 2;
        int[] map = null;
        var mapBytes = new byte[0];
        if (second && levels > 0) {
            var mapWidth = ((pw + 1) / 2 + 3) / 4;
            var mapHeight = (ph + 1) / 2;
            map = new int[mapWidth * mapHeight];
            for (var i = 0; i < map.length; i++) {
                map[i] = MAP_VALUES[(i / 7 + i / mapWidth) % MAP_VALUES.length];
            }
            var bits = new Bits();
            bits.write(map[0], 32);
            new Coder(bits, mapWidth, Coder.MAP, map[0]).encode(map, mapHeight);
            mapBytes = bits.bytes();
        }
        var planes = new byte[4][][];
        for (var p = 0; p < 4; p++) {
            var plane = new int[pw * ph];
            for (var y = 0; y < ph; y++) {
                for (var x = 0; x < pw; x++) {
                    plane[y * pw + x] = samples[(2 * y + (p >> 1)) * width + 2 * x + (p & 1)];
                }
            }
            planes[p] = levels == 0 ? new byte[][]{code(plane, pw, ph, Coder.LOW)}
                    : wavelet(plane, pw, ph, map, ((pw + 1) / 2 + 3) / 4, version);
        }
        var headers = new ByteArrayOutputStream();
        var data = new ByteArrayOutputStream();
        var tileLength = mapBytes.length;
        for (var plane : planes) {
            for (var band : plane) {
                tileLength += band.length;
            }
        }
        var tile = ByteBuffer.allocate(second ? 20 : 12).putShort((short) (second ? 0xFF11 : 0xFF01))
                .putShort((short) (second ? 16 : 8)).putInt(tileLength).putInt(0);
        if (second) {
            tile.putInt(mapBytes.length).putInt(0);
        }
        headers.writeBytes(tile.array());
        data.writeBytes(mapBytes);
        for (var p = 0; p < 4; p++) {
            var planeLength = 0;
            for (var band : planes[p]) {
                planeLength += band.length;
            }
            headers.writeBytes(ByteBuffer.allocate(12).putShort((short) (second ? 0xFF12 : 0xFF02)).putShort((short) 8)
                    .putInt(planeLength).putInt(p << 28).array());
            for (var b = 0; b < planes[p].length; b++) {
                var band = planes[p][b];
                var header = ByteBuffer.allocate(second ? 20 : 12).putShort((short) (second ? 0xFF13 : 0xFF03))
                        .putShort((short) (second ? 16 : 8)).putInt(band.length);
                if (second) {
                    var scale = levels == 0 ? 0 : SCALES[b];
                    header.putInt(b << 28 | scale).putInt(levels > 0 && scale == 0 ? 1 : 0).putInt(0);
                } else {
                    header.putInt(b << 28 | (levels == 0 ? 0 : QUANTIZERS[b]) << 19);
                }
                headers.writeBytes(header.array());
                data.writeBytes(band);
            }
        }
        return concat(headers.toByteArray(), data.toByteArray());
    }

    /**
     * Transforms, quantizes and codes one color plane.
     *
     * @param plane the plane's photosites
     * @param pw the plane width
     * @param ph the plane height
     * @param map the version 2 quantization map, or {@code null}
     * @param mapWidth the map's values per row
     * @param version the codec version
     * @return the subbands' bitstreams, in coding order
     */
    private static byte[][] wavelet(int[] plane, int pw, int ph, int[] map, int mapWidth, int version) {
        var bands = new int[10][];
        var widths = new int[10];
        var heights = new int[10];
        var low = plane;
        var w = pw;
        var h = ph;
        for (var level = 1; level <= 3; level++) {
            var lowRows = (h + 1) / 2;
            var lowColumns = (w + 1) / 2;
            var split = new int[w * h];
            var column = new int[h];
            for (var x = 0; x < w; x++) {
                for (var y = 0; y < h; y++) {
                    column[y] = low[y * w + x];
                }
                var out = analysis(column);
                for (var y = 0; y < h; y++) {
                    split[y * w + x] = out[y];
                }
            }
            for (var y = 0; y < h; y++) {
                System.arraycopy(analysis(Arrays.copyOfRange(split, y * w, y * w + w)), 0, split, y * w, w);
            }
            var first = 1 + 3 * (3 - level);
            for (var band = 0; band < 3; band++) {
                var rows0 = band == 0 ? 0 : lowRows;
                var rows1 = band == 0 ? lowRows : h;
                var columns0 = band == 1 ? 0 : lowColumns;
                var columns1 = band == 1 ? lowColumns : w;
                var values = new int[(rows1 - rows0) * (columns1 - columns0)];
                for (var y = rows0; y < rows1; y++) {
                    System.arraycopy(split, y * w + columns0, values, (y - rows0) * (columns1 - columns0), columns1 - columns0);
                }
                bands[first + band] = values;
                widths[first + band] = columns1 - columns0;
                heights[first + band] = rows1 - rows0;
            }
            low = new int[lowColumns * lowRows];
            for (var y = 0; y < lowRows; y++) {
                System.arraycopy(split, y * w, low, y * lowColumns, lowColumns);
            }
            w = lowColumns;
            h = lowRows;
        }
        bands[0] = low;
        widths[0] = w;
        heights[0] = h;
        var mapHeight = map == null ? 0 : map.length / mapWidth;
        var out = new byte[10][];
        for (var b = 0; b < 10; b++) {
            var level = b == 0 ? 3 : 3 - (b - 1) / 3;
            for (var y = 0; y < heights[b]; y++) {
                for (var x = 0; x < widths[b]; x++) {
                    int step;
                    if (version != 0x200) {
                        step = (LEVEL_SCALE[QUANTIZERS[b] % 6] << (QUANTIZERS[b] / 6)) >> 6;
                    } else if (SCALES[b] == 0) {
                        step = 1;
                    } else if (level == 1) {
                        step = unit(map[Math.min(y, mapHeight - 1) * mapWidth + Math.min(x >> 2, mapWidth - 1)]) * SCALES[b] >> 3;
                    } else {
                        var column = Math.min(x >> 1, mapWidth - 1);
                        var value = (map[Math.min(2 * y, mapHeight - 1) * mapWidth + column]
                                + map[Math.min(2 * y + 1, mapHeight - 1) * mapWidth + column]) >> 1;
                        step = unit(value) * SCALES[b] >> 3;
                    }
                    var at = y * widths[b] + x;
                    bands[b][at] = step == 1 ? bands[b][at] : Math.round(bands[b][at] / (float) step);
                }
            }
            out[b] = code(bands[b], widths[b], heights[b], b == 0 ? Coder.LOW : Coder.HIGH);
        }
        return out;
    }

    /**
     * Returns the version 2 quantization unit of a map value.
     *
     * @param value the map value
     * @return the unit, in eighths of a step per unit of scale
     */
    private static int unit(int value) {
        var offset = value - 302;
        var octave = Math.floorDiv(offset, 6);
        var base = (long) LEVEL_SCALE[Math.floorMod(offset, 6)];
        base = octave >= 0 ? base << octave : base >> -octave;
        return (int) (2 * base / 5);
    }

    /**
     * Computes the reversible 5/3 analysis of a line with symmetric extension.
     *
     * @param x the line
     * @return the low coefficients followed by the high ones
     */
    private static int[] analysis(int[] x) {
        var n = x.length;
        var lows = (n + 1) / 2;
        var highs = n / 2;
        var out = new int[n];
        for (var i = 0; i < highs; i++) {
            var right = 2 * i + 2 < n ? x[2 * i + 2] : x[2 * i];
            out[lows + i] = x[2 * i + 1] - ((x[2 * i] + right) >> 1);
        }
        for (var i = 0; i < lows; i++) {
            var left = out[lows + Math.max(i - 1, 0)];
            var right = out[lows + Math.min(i, highs - 1)];
            out[i] = x[2 * i] + ((left + right + 2) >> 2);
        }
        return out;
    }

    /**
     * Codes one subband or plane.
     *
     * @param values the values, row by row
     * @param width the row length
     * @param height the rows
     * @param kind the coder kind
     * @return the bitstream
     */
    private static byte[] code(int[] values, int width, int height, int kind) {
        var bits = new Bits();
        new Coder(bits, width, kind, 1 << (BITS - 1)).encode(values, height);
        return bits.bytes();
    }

    /**
     * The adaptive Golomb-Rice coder of CRX, run mode included.
     */
    private static final class Coder {
        /**
         * The coder of the low band and the lossless planes.
         */
        static final int LOW = 0;

        /**
         * The coder of the high bands.
         */
        static final int HIGH = 1;

        /**
         * The coder of a quantization map.
         */
        static final int MAP = 2;

        /**
         * The bitstream written.
         */
        private final Bits out;

        /**
         * The row length.
         */
        private final int width;

        /**
         * The coder kind.
         */
        private final int kind;

        /**
         * The value of the row above the first.
         */
        private final int first;

        /**
         * The Golomb-Rice parameter.
         */
        private int k;

        /**
         * The run index.
         */
        private int run;

        /**
         * The parameter after each sample of the previous row.
         */
        private int[] parameters;

        /**
         * The parameter after each sample of the current row.
         */
        private int[] current;

        /**
         * Constructs the coder.
         *
         * @param out the bitstream
         * @param width the row length
         * @param kind the coder kind
         * @param first the value of the row above the first
         */
        Coder(Bits out, int width, int kind, int first) {
            this.out = out;
            this.width = width;
            this.kind = kind;
            this.first = first;
            this.k = kind == MAP ? 2 : 0;
            this.parameters = new int[width];
            this.current = new int[width];
        }

        /**
         * Codes the rows of a band.
         *
         * @param values the values, row by row
         * @param height the rows
         */
        void encode(int[] values, int height) {
            var top = new int[width];
            Arrays.fill(top, kind == HIGH ? 0 : first);
            var high = kind == HIGH;
            for (var y = 0; y < height; y++) {
                var prev = y == 0 ? top : values;
                var up = y == 0 ? 0 : (y - 1) * width;
                var row = y * width;
                Arrays.fill(current, 0);
                var x = kind == MAP && y == 0 ? 1 : 0;
                while (x < width) {
                    var b = prev[up + x];
                    var a = x > 0 ? values[row + x - 1] : b;
                    var c = x > 0 ? prev[up + x - 1] : b;
                    var hasD = x + 1 < width;
                    var d = hasD ? prev[up + x + 1] : 0;
                    if (kind != MAP && hasD && a == b && b == d && (!high || a == 0)) {
                        var length = 0;
                        while (x + length < width && values[row + x + length] == a) {
                            length++;
                        }
                        if (length > 0) {
                            out.write(1, 1);
                            x++;
                            var left = length - 1;
                            while (x < width) {
                                var n = 1 << RUN_BITS[run];
                                if (left >= n || x + left == width && left > 0) {
                                    out.write(1, 1);
                                    if (x + n <= width) {
                                        run = Math.min(run + 1, RUN_BITS.length - 1);
                                    }
                                    var taken = Math.min(n, width - x);
                                    x += taken;
                                    left -= taken;
                                } else {
                                    out.write(0, 1);
                                    out.write(left, RUN_BITS[run]);
                                    x += left;
                                    break;
                                }
                            }
                            if (x >= width) {
                                break;
                            }
                        } else {
                            out.write(0, 1);
                        }
                        b = prev[up + x];
                        hasD = x + 1 < width;
                        d = hasD ? prev[up + x + 1] : 0;
                        var value = values[row + x];
                        if (high) {
                            var s = symbol(value) - 1;
                            golomb(s);
                            adapt(s, x, y, hasD);
                        } else {
                            var s = symbol(value - b);
                            golomb(s);
                            k = update(k, !hasD || y == 0 ? s : (s + 2 * Math.abs(d - b)) >> 1);
                        }
                        if (length > 0) {
                            run = Math.max(run - 1, 0);
                        }
                        x++;
                        continue;
                    }
                    var value = values[row + x];
                    if (high) {
                        var s = symbol(value);
                        golomb(s);
                        adapt(s, x, y, hasD);
                    } else {
                        var s = symbol(value - median(a, b, c));
                        golomb(s);
                        k = update(k, !hasD || y == 0 ? s : (s + 2 * Math.abs(d - b)) >> 1);
                    }
                    x++;
                }
                var swap = parameters;
                parameters = current;
                current = swap;
            }
        }

        /**
         * Updates the high band parameter after a symbol.
         *
         * @param s the symbol
         * @param x the column
         * @param y the row
         * @param hasD whether the sample has a right neighbor
         */
        private void adapt(int s, int x, int y, boolean hasD) {
            k = update(k, s);
            if (y > 0 && hasD && parameters[x + 1] - k > 1) {
                k++;
            }
            current[x] = k;
        }

        /**
         * Writes a Golomb-Rice code, escaped when its quotient is too long.
         *
         * @param s the symbol
         */
        private void golomb(int s) {
            var q = s >> k;
            if (q >= 41) {
                out.write(0, 41);
                out.write(1, 1);
                out.write(s, 21);
                return;
            }
            out.write(0, q);
            out.write(1, 1);
            out.write(s & ((1 << k) - 1), k);
        }

        /**
         * Maps a signed value to a symbol.
         *
         * @param value the signed value
         * @return the symbol
         */
        private static int symbol(int value) {
            return value >= 0 ? 2 * value : -2 * value - 1;
        }

        /**
         * Returns the parameter after a symbol.
         *
         * @param k the parameter
         * @param estimate the estimate of the next symbol
         * @return the parameter
         */
        private static int update(int k, int estimate) {
            var q = estimate >> k;
            var next = k + (q >= 3 ? 1 : 0) + (q >= 6 ? 1 : 0);
            if (q == 0 && k > 0 && estimate < (1 << (k - 1))) {
                next--;
            }
            return Math.min(next, 15);
        }

        /**
         * Returns the median edge prediction.
         *
         * @param a the left neighbor
         * @param b the upper neighbor
         * @param c the upper-left neighbor
         * @return the prediction
         */
        private static int median(int a, int b, int c) {
            if (c >= Math.max(a, b)) {
                return Math.min(a, b);
            }
            if (c <= Math.min(a, b)) {
                return Math.max(a, b);
            }
            return a + b - c;
        }
    }

    /**
     * A most-significant-bit-first bit writer.
     */
    private static final class Bits {
        /**
         * The bytes written.
         */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /**
         * The bits not yet written as a byte.
         */
        private int buffer;

        /**
         * The number of those bits.
         */
        private int count;

        /**
         * Writes bits.
         *
         * @param value the bits, in the low bits
         * @param n the number of bits
         */
        void write(int value, int n) {
            for (var i = n - 1; i >= 0; i--) {
                buffer = (buffer << 1) | ((value >>> i) & 1);
                if (++count == 8) {
                    bytes.write(buffer);
                    buffer = 0;
                    count = 0;
                }
            }
        }

        /**
         * Returns the bitstream, padded to bytes and followed by four zero bytes.
         *
         * @return the bytes
         */
        byte[] bytes() {
            if (count > 0) {
                bytes.write(buffer << (8 - count));
                count = 0;
            }
            bytes.writeBytes(new byte[4]);
            return bytes.toByteArray();
        }
    }

    /**
     * Returns a little-endian TIFF structure whose only directory holds a make and a model.
     *
     * @param make the camera maker, ASCII
     * @param model the camera model, ASCII
     * @return the structure
     */
    private static byte[] tiff(String make, String model) {
        var makeBytes = (make + "\0").getBytes(StandardCharsets.US_ASCII);
        var modelBytes = (model + "\0").getBytes(StandardCharsets.US_ASCII);
        var data = 8 + 2 + 2 * 12 + 4;
        var buffer = ByteBuffer.allocate(data + makeBytes.length + modelBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) 2);
        buffer.putShort((short) 271).putShort((short) 2).putInt(makeBytes.length).putInt(data);
        buffer.putShort((short) 272).putShort((short) 2).putInt(modelBytes.length).putInt(data + makeBytes.length);
        buffer.putInt(0).put(makeBytes).put(modelBytes);
        return buffer.array();
    }

    /**
     * Returns an ISO base media box.
     *
     * @param type the box type
     * @param body the box body
     * @return the box
     */
    private static byte[] box(String type, byte[] body) {
        return concat(u32(8 + body.length), ascii(type), body);
    }

    /**
     * Concatenates byte arrays.
     *
     * @param parts the arrays
     * @return the concatenation
     */
    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Returns a big-endian 32-bit value.
     *
     * @param value the value, of which the low 32 bits are written
     * @return the bytes
     */
    private static byte[] u32(long value) {
        return ByteBuffer.allocate(4).putInt((int) value).array();
    }

    /**
     * Returns the bytes of an ASCII string.
     *
     * @param text the string
     * @return the bytes
     */
    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
