package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeMap;

/**
 * Writes synthetic Canon CR3 files with CRX-coded sensor data, the encoding counterpart of the decoder's
 * description of the format: the lossless coding of the four color planes in one or more tiles, and the lossy
 * C-RAW coding of a single tile as the quantized subbands of a three-level 5/3 wavelet transform, with one
 * quantization parameter per subband (codec version 1) or a quantization map (codec version 2). After
 * {@link #write()}, {@link #expected} holds the photosites the decoder must reproduce: the samples for a lossless
 * file, the synthesis of the dequantized subbands (computed here by a plain whole-plane transform) for a lossy one.
 *
 * <p>The container holds the boxes the decoder reads: the file type, the Canon metadata box with the first
 * directory, the EXIF directory and a maker note, one track with the CRX image (its {@code CMP1} description and
 * {@code IAD1} image areas) and, optionally, the timed metadata track whose EXIF records carry the maker note with
 * the color data (version 18: white balance, black and white levels).
 */
public final class Cr3Writer {
    private static final int[] RUN_BITS = {0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8,
            9, 10, 11, 12, 13, 14, 15};
    private static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};
    private static final int[][] CELLS = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 0, 3, 1}, {3, 1, 2, 0}};
    private static final byte[] CANON = {(byte) 0x85, (byte) 0xC0, (byte) 0xB6, (byte) 0x87, (byte) 0x82, 0x0F, 0x11,
            (byte) 0xE0, (byte) 0x81, 0x11, (byte) 0xF4, (byte) 0xCE, 0x46, 0x2B, 0x6A, 0x48};

    /** The image width, even. */
    public int width = 96;
    /** The image height, even. */
    public int height = 64;
    /** The bits of each photosite. */
    public int bits = 14;
    /** The color filter layout: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    public int cfa = 0;
    /** The wavelet levels: 0 for lossless data, 3 for C-RAW. */
    public int levels = 0;
    /** The codec version, {@code 0x100} or {@code 0x200}. */
    public int version = 0x100;
    /** The tile columns (lossless data only). */
    public int tiles = 1;
    /** The CRX encoding type written in the {@code CMP1} box; the data is always coded as type 0. */
    public int encoding = 0;
    /** A second, smaller CRX image written as a track of its own after the main image's, or {@code null}. */
    public Cr3Writer thumbnail;
    /** The photosites, row by row; {@link #ramp} by default. */
    public int[] samples;
    /** The version 1 quantization parameter of each subband: LL3, HL3, LH3, HH3, HL2, LH2, HH2, HL1, LH1, HH1. */
    public int[] quantizers = {4, 4, 4, 4, 16, 16, 22, 26, 26, 32};
    /** The version 2 scale of each subband, 0 for the unquantized level-3 subbands. */
    public int[] scales = {0, 0, 0, 0, 4, 4, 8, 8, 8, 16};
    /** The values the version 2 quantization map draws from. */
    public int[] mapValues = {300, 302, 304, 307};
    /** The seed of the quantization map. */
    public long seed = 1;
    /** Whether the file has the timed metadata track with the color data. */
    public boolean colorData = true;
    /** The camera model. */
    public String model = "Canon EOS R5";
    /** The ISO speed. */
    public int iso = 200;
    /** The black level of the color data. */
    public int black = 512;
    /** The white level of the color data. */
    public int white = 15000;
    /** The as-shot white balance levels: red, green, green, blue. */
    public int[] balance = {2000, 1024, 1024, 1600};
    /** The crop inset from each edge. */
    public int inset = 4;
    /** After {@link #write()}: the photosites the decoder must reproduce. */
    public int[] expected;
    /** After {@link #write()}: the offset of the CRX sample in the file. */
    public int sampleOffset;
    /** After {@link #write()}: the length of the CRX sample. */
    public int sampleLength;

    /**
     * Fills {@link #samples} with a smooth ramp plus a texture and a flat area, so that both the run mode and the
     * regular mode are exercised.
     *
     * @return this writer
     */
    public Cr3Writer ramp() {
        samples = new int[width * height];
        var max = (1 << bits) - 1;
        var random = new Random(seed);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int value;
                if (y < height / 4) {
                    value = black;
                } else if (x > width * 3 / 4) {
                    value = black + 64 * ((x / 8 + y / 8) & 1) + random.nextInt(3);
                } else {
                    value = black + (x * 37 + y * 23) % 3000 + random.nextInt(40) + ((x + y) & 1) * 200;
                }
                samples[y * width + x] = Math.min(max, value);
            }
        }
        return this;
    }

    /**
     * Writes the file.
     *
     * @return the file
     */
    public byte[] write() {
        if (samples == null) {
            ramp();
        }
        if (levels > 0 && tiles != 1) {
            throw new IllegalArgumentException("lossy files are written with one tile");
        }
        expected = new int[width * height];
        var sample = sample();
        var small = new byte[0];
        if (thumbnail != null) {
            if (thumbnail.samples == null) {
                thumbnail.ramp();
            }
            thumbnail.expected = new int[thumbnail.width * thumbnail.height];
            small = thumbnail.sample();
        }
        var ctmd = colorData ? timedMetadata() : new byte[0];
        var moov = moov(sample.length, small.length, ctmd.length, 0);
        var ftyp = box("ftyp", concat("crx ".getBytes(StandardCharsets.ISO_8859_1), u32(1),
                "crx ".getBytes(StandardCharsets.ISO_8859_1), "isom".getBytes(StandardCharsets.ISO_8859_1)));
        var mdatStart = ftyp.length + moov.length;
        var dataStart = mdatStart + 8;
        // write the chunk offsets now that the layout is known
        var file = new ByteArrayOutputStream();
        file.writeBytes(ftyp);
        file.writeBytes(moov(sample.length, small.length, ctmd.length, dataStart));
        file.writeBytes(box("mdat", concat(sample, small, ctmd)));
        sampleOffset = dataStart;
        sampleLength = sample.length;
        return file.toByteArray();
    }

    // the media data holds the main image's sample, the thumbnail's and the timed metadata, in that order
    private byte[] moov(int sampleLength, int smallLength, int ctmdLength, long sampleOffset) {
        var canon = concat(CANON,
                box("CMT1", tiff(ifd(new Object[][]{{271, "Canon"}, {272, model}, {274, 1}}))),
                box("CMT2", tiff(ifd(new Object[][]{{0x8827, iso}, {0x829A, new int[]{1, 100}}, {0x829D, new int[]{4, 1}}}))),
                box("CMT3", tiff(ifd(new Object[][]{{0x0001, 0}}))));
        var children = new ArrayList<byte[]>();
        children.add(box("uuid", canon));
        children.add(track(rawEntry(), sampleLength, sampleOffset));
        if (smallLength > 0) {
            children.add(track(thumbnail.rawEntry(), smallLength, sampleOffset + sampleLength));
        }
        if (ctmdLength > 0) {
            children.add(track(box("CTMD", new byte[8]), ctmdLength, sampleOffset + sampleLength + smallLength));
        }
        return box("moov", concat(children.toArray(byte[][]::new)));
    }

    private static byte[] track(byte[] entry, int length, long offset) {
        var stsd = box("stsd", concat(u32(0), u32(1), entry));
        var stsz = box("stsz", concat(u32(0), u32(length), u32(1)));
        var co64 = box("co64", concat(u32(0), u32(1), u64(offset)));
        return box("trak", box("mdia", box("minf", box("stbl", concat(stsd, stsz, co64)))));
    }

    private byte[] rawEntry() {
        var head = new byte[0x5A - 8];
        ByteBuffer.wrap(head).putShort(24, (short) width).putShort(26, (short) height);
        var cmp1 = cmp1(headerLength());
        var areas = new short[]{0, 0, (short) width, (short) height, 1, 2, 1, 0,
                (short) inset, (short) inset, (short) (width - 1 - inset), (short) (height - 1 - inset),
                0, 0, 0, (short) (height - 1), 0, 0, (short) (width - 1), 0,
                0, 0, (short) (width - 1), (short) (height - 1)};
        var iad1 = ByteBuffer.allocate(areas.length * 2);
        for (var value : areas) {
            iad1.putShort(value);
        }
        var cdi1 = box("CDI1", concat(u32(0), box("IAD1", iad1.array())));
        return box("CRAW", concat(head, box("CMP1", cmp1), cdi1));
    }

    private byte[] cmp1(int headerLength) {
        var body = ByteBuffer.allocate(0x34);
        body.putShort((short) -1).putShort((short) 0x30).putShort((short) version).putShort((short) 0);
        body.putInt(width).putInt(height).putInt(width / tiles).putInt(height);
        body.put((byte) bits).put((byte) (4 << 4 | cfa)).put((byte) (encoding << 4 | levels)).put((byte) (tiles > 1 ? 0x80 : 0));
        body.putInt(headerLength);
        return body.array();
    }

    private int bandsPerPlane() {
        return levels == 0 ? 1 : 3 * levels + 1;
    }

    private int headerLength() {
        var tileHeader = version == 0x200 ? 20 : 12;
        var bandHeader = version == 0x200 ? 20 : 12;
        return tiles * (tileHeader + 4 * (12 + bandsPerPlane() * bandHeader));
    }

    private byte[] sample() {
        var headers = new ByteArrayOutputStream();
        var data = new ByteArrayOutputStream();
        var tileWidth = width / tiles;
        for (var t = 0; t < tiles; t++) {
            var map = version == 0x200 && levels > 0 ? map() : null;
            var mapBytes = map != null ? mapStream(map) : new byte[0];
            var planes = new byte[4][][];
            for (var p = 0; p < 4; p++) {
                var cell = CELLS[cfa][p];
                var pw = tileWidth / 2;
                var ph = height / 2;
                var plane = new int[pw * ph];
                for (var y = 0; y < ph; y++) {
                    for (var x = 0; x < pw; x++) {
                        plane[y * pw + x] = samples[(2 * y + (cell >> 1)) * width + t * tileWidth + 2 * x + (cell & 1)];
                    }
                }
                int[] restored;
                if (levels == 0) {
                    planes[p] = new byte[][]{encode(plane, pw, ph, Mode.LOW, 1 << (bits - 1))};
                    restored = plane;
                } else {
                    var coded = new int[10][];
                    planes[p] = wavelet(plane, pw, ph, map, coded);
                    restored = synthesize(coded, pw, ph);
                }
                var max = (1 << bits) - 1;
                for (var y = 0; y < ph; y++) {
                    for (var x = 0; x < pw; x++) {
                        expected[(2 * y + (cell >> 1)) * width + t * tileWidth + 2 * x + (cell & 1)]
                                = Math.clamp(restored[y * pw + x], 0, max);
                    }
                }
            }
            var tileLength = mapBytes.length;
            for (var plane : planes) {
                for (var band : plane) {
                    tileLength += band.length;
                }
            }
            var tileHeader = ByteBuffer.allocate(version == 0x200 ? 20 : 12);
            tileHeader.putShort((short) (version == 0x200 ? 0xFF11 : 0xFF01)).putShort((short) (version == 0x200 ? 16 : 8));
            tileHeader.putInt(tileLength).putInt(t << 28);
            if (version == 0x200) {
                tileHeader.putInt(mapBytes.length).putInt(0);
            }
            headers.writeBytes(tileHeader.array());
            data.writeBytes(mapBytes);
            for (var p = 0; p < 4; p++) {
                var planeLength = 0;
                for (var band : planes[p]) {
                    planeLength += band.length;
                }
                var planeHeader = ByteBuffer.allocate(12);
                planeHeader.putShort((short) (version == 0x200 ? 0xFF12 : 0xFF02)).putShort((short) 8).putInt(planeLength)
                        .putInt(p << 28);
                headers.writeBytes(planeHeader.array());
                for (var b = 0; b < planes[p].length; b++) {
                    var band = planes[p][b];
                    var bandHeader = ByteBuffer.allocate(version == 0x200 ? 20 : 12);
                    bandHeader.putShort((short) (version == 0x200 ? 0xFF13 : 0xFF03))
                            .putShort((short) (version == 0x200 ? 16 : 8)).putInt(band.length);
                    if (version == 0x200) {
                        bandHeader.putInt(b << 28 | (levels == 0 ? 0 : scales[b])).putInt(levels > 0 && scales[b] == 0 ? 1 : 0)
                                .putInt(0);
                    } else {
                        bandHeader.putInt(b << 28 | (levels == 0 ? 0 : quantizers[b]) << 19);
                    }
                    headers.writeBytes(bandHeader.array());
                    data.writeBytes(band);
                }
            }
        }
        return concat(headers.toByteArray(), data.toByteArray());
    }

    private int[] map() {
        var lows = (width / tiles / 2 + 1) / 2;
        var mapWidth = (lows + 3) / 4;
        var mapHeight = (height / 2 + 1) / 2;
        var random = new Random(seed * 31 + 7);
        var map = new int[mapWidth * mapHeight + 2];
        map[0] = mapWidth;
        map[1] = mapHeight;
        for (var i = 0; i < mapWidth * mapHeight; i++) {
            map[2 + i] = mapValues[random.nextInt(mapValues.length)];
        }
        return map;
    }

    private byte[] mapStream(int[] map) {
        var mapWidth = map[0];
        var mapHeight = map[1];
        var values = Arrays.copyOfRange(map, 2, map.length);
        var out = new Bits();
        out.write(values[0], 32);
        new Coder(out, mapWidth, Mode.MAP, values[0]).encode(values, mapHeight);
        return out.bytes();
    }

    private static int unit(int value) {
        var offset = value - 302;
        var octave = Math.floorDiv(offset, 6);
        var base = (long) LEVEL_SCALE[Math.floorMod(offset, 6)];
        base = octave >= 0 ? base << octave : base >> -octave;
        return (int) (2 * base / 5);
    }

    private int step(int band, int level, int x, int y, int[] map) {
        if (map == null) {
            var qp = quantizers[band];
            return (LEVEL_SCALE[qp % 6] << (qp / 6)) >> 6;
        }
        if (scales[band] == 0) {
            return 1;
        }
        var mapWidth = map[0];
        var mapHeight = map[1];
        int value;
        if (level == 1) {
            value = map[2 + Math.min(y, mapHeight - 1) * mapWidth + Math.min(x >> 2, mapWidth - 1)];
        } else {
            var column = Math.min(x >> 1, mapWidth - 1);
            value = (map[2 + Math.min(2 * y, mapHeight - 1) * mapWidth + column]
                    + map[2 + Math.min(2 * y + 1, mapHeight - 1) * mapWidth + column]) >> 1;
        }
        return (int) ((long) unit(value) * scales[band] >> 3);
    }

    /**
     * Transforms, quantizes and codes one plane; {@code coded} receives the dequantized subbands, each prefixed by
     * its width and height.
     */
    private byte[][] wavelet(int[] plane, int pw, int ph, int[] map, int[][] coded) {
        var bands = new int[10][];
        var dims = new int[10][];
        var low = plane;
        var w = pw;
        var h = ph;
        for (var level = 1; level <= 3; level++) {
            var lowRows = (h + 1) / 2;
            var highRows = h / 2;
            var lowCols = (w + 1) / 2;
            var highCols = w / 2;
            // vertical split, then the horizontal split of each half
            var vertical = new int[w * h];
            for (var x = 0; x < w; x++) {
                var column = new int[h];
                for (var y = 0; y < h; y++) {
                    column[y] = low[y * w + x];
                }
                var split = forward(column);
                for (var y = 0; y < h; y++) {
                    vertical[y * w + x] = split[y];
                }
            }
            var ll = new int[lowCols * lowRows];
            var hl = new int[highCols * lowRows];
            var lh = new int[lowCols * highRows];
            var hh = new int[highCols * highRows];
            for (var y = 0; y < h; y++) {
                var split = forward(Arrays.copyOfRange(vertical, y * w, y * w + w));
                for (var x = 0; x < w; x++) {
                    if (y < lowRows) {
                        if (x < lowCols) {
                            ll[y * lowCols + x] = split[x];
                        } else {
                            hl[y * highCols + x - lowCols] = split[x];
                        }
                    } else if (x < lowCols) {
                        lh[(y - lowRows) * lowCols + x] = split[x];
                    } else {
                        hh[(y - lowRows) * highCols + x - lowCols] = split[x];
                    }
                }
            }
            var first = 1 + 3 * (3 - level);
            bands[first] = hl;
            dims[first] = new int[]{highCols, lowRows};
            bands[first + 1] = lh;
            dims[first + 1] = new int[]{lowCols, highRows};
            bands[first + 2] = hh;
            dims[first + 2] = new int[]{highCols, highRows};
            low = ll;
            w = lowCols;
            h = lowRows;
        }
        bands[0] = low;
        dims[0] = new int[]{w, h};
        var out = new byte[10][];
        for (var b = 0; b < 10; b++) {
            var level = b == 0 ? 3 : 3 - (b - 1) / 3;
            var bw = dims[b][0];
            var bh = dims[b][1];
            var quantized = new int[bw * bh];
            var restored = new int[bw * bh + 2];
            restored[0] = bw;
            restored[1] = bh;
            for (var y = 0; y < bh; y++) {
                for (var x = 0; x < bw; x++) {
                    var step = step(b, level, x, y, map);
                    var value = bands[b][y * bw + x];
                    var q = step <= 1 ? value : (int) Math.round(value / (double) step);
                    quantized[y * bw + x] = q;
                    restored[2 + y * bw + x] = q * step;
                }
            }
            coded[b] = restored;
            out[b] = encode(quantized, bw, bh, b == 0 ? Mode.LOW : Mode.HIGH, 1 << (bits - 1));
        }
        return out;
    }

    /** The whole-plane synthesis of the dequantized subbands. */
    private static int[] synthesize(int[][] coded, int pw, int ph) {
        var widths = new int[4];
        var heights = new int[4];
        widths[0] = pw;
        heights[0] = ph;
        for (var level = 1; level <= 3; level++) {
            widths[level] = (widths[level - 1] + 1) / 2;
            heights[level] = (heights[level - 1] + 1) / 2;
        }
        var low = Arrays.copyOfRange(coded[0], 2, coded[0].length);
        for (var level = 3; level >= 1; level--) {
            var w = widths[level - 1];
            var h = heights[level - 1];
            var lowCols = widths[level];
            var lowRows = heights[level];
            var first = 1 + 3 * (3 - level);
            var hl = Arrays.copyOfRange(coded[first], 2, coded[first].length);
            var lh = Arrays.copyOfRange(coded[first + 1], 2, coded[first + 1].length);
            var hh = Arrays.copyOfRange(coded[first + 2], 2, coded[first + 2].length);
            var highCols = w / 2;
            var rows = new int[w * h];
            for (var y = 0; y < h; y++) {
                var line = new int[w];
                for (var x = 0; x < w; x++) {
                    if (y < lowRows) {
                        line[x] = x < lowCols ? low[y * lowCols + x] : hl[y * highCols + x - lowCols];
                    } else {
                        line[x] = x < lowCols ? lh[(y - lowRows) * lowCols + x] : hh[(y - lowRows) * highCols + x - lowCols];
                    }
                }
                System.arraycopy(inverse(line), 0, rows, y * w, w);
            }
            var out = new int[w * h];
            for (var x = 0; x < w; x++) {
                var column = new int[h];
                for (var y = 0; y < h; y++) {
                    column[y] = rows[y * w + x];
                }
                var restored = inverse(column);
                for (var y = 0; y < h; y++) {
                    out[y * w + x] = restored[y];
                }
            }
            low = out;
        }
        return low;
    }

    /** The reversible 5/3 analysis with symmetric extension: the lows, then the highs. */
    private static int[] forward(int[] x) {
        var n = x.length;
        var out = new int[n];
        if (n == 1) {
            out[0] = x[0];
            return out;
        }
        var highs = n / 2;
        var lows = (n + 1) / 2;
        var d = new int[highs];
        for (var i = 0; i < highs; i++) {
            var right = 2 * i + 2 < n ? x[2 * i + 2] : x[2 * i];
            d[i] = x[2 * i + 1] - ((x[2 * i] + right) >> 1);
        }
        for (var i = 0; i < lows; i++) {
            var left = i > 0 ? d[i - 1] : d[0];
            var right = i < highs ? d[i] : d[highs - 1];
            out[i] = x[2 * i] + ((left + right + 2) >> 2);
        }
        System.arraycopy(d, 0, out, lows, highs);
        return out;
    }

    /** The inverse of {@link #forward}. */
    private static int[] inverse(int[] coefficients) {
        var n = coefficients.length;
        var out = new int[n];
        if (n == 1) {
            out[0] = coefficients[0];
            return out;
        }
        var lows = (n + 1) / 2;
        var highs = n / 2;
        for (var i = 0; i < lows; i++) {
            var left = i > 0 ? coefficients[lows + i - 1] : coefficients[lows];
            var right = i < highs ? coefficients[lows + i] : coefficients[lows + highs - 1];
            out[2 * i] = coefficients[i] - ((left + right + 2) >> 2);
        }
        for (var i = 0; i < highs; i++) {
            var right = 2 * i + 2 < n ? out[2 * i + 2] : out[2 * i];
            out[2 * i + 1] = coefficients[lows + i] + ((out[2 * i] + right) >> 1);
        }
        return out;
    }

    private enum Mode {LOW, HIGH, MAP}

    private static byte[] encode(int[] values, int width, int height, Mode mode, int first) {
        var out = new Bits();
        new Coder(out, width, mode, first).encode(values, height);
        return out.bytes();
    }

    /** The encoder counterpart of the decoder's adaptive Golomb-Rice coder. */
    private static final class Coder {
        private final Bits out;
        private final int width;
        private final Mode mode;
        private final int first;
        private int k;
        private int run;
        private int[] parameters;
        private int[] current;

        Coder(Bits out, int width, Mode mode, int first) {
            this.out = out;
            this.width = width;
            this.mode = mode;
            this.first = first;
            this.k = mode == Mode.MAP ? 2 : 0;
            this.parameters = new int[width];
            this.current = new int[width];
        }

        void encode(int[] values, int height) {
            var top = new int[width];
            Arrays.fill(top, mode == Mode.HIGH ? 0 : first);
            var high = mode == Mode.HIGH;
            for (var y = 0; y < height; y++) {
                var prev = y == 0 ? top : Arrays.copyOfRange(values, (y - 1) * width, y * width);
                var row = y * width;
                Arrays.fill(current, 0);
                var x = 0;
                if (mode == Mode.MAP && y == 0) {
                    x = 1;
                }
                while (x < width) {
                    var b = prev[x];
                    var a = x > 0 ? values[row + x - 1] : b;
                    var c = x > 0 ? prev[x - 1] : b;
                    var hasD = x + 1 < width;
                    var d = hasD ? prev[x + 1] : 0;
                    if (mode != Mode.MAP && hasD && a == b && b == d && (!high || a == 0)) {
                        var length = 0;
                        while (x + length < width && values[row + x + length] == a) {
                            length++;
                        }
                        var ran = length > 0;
                        if (ran) {
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
                        b = prev[x];
                        hasD = x + 1 < width;
                        d = hasD ? prev[x + 1] : 0;
                        var value = values[row + x];
                        int s;
                        if (high) {
                            s = symbol(value) - 1;
                            golomb(s);
                            adapt(s, x, y, hasD);
                        } else {
                            s = symbol(value - b);
                            golomb(s);
                            k = update(k, !hasD || y == 0 ? s : (s + 2 * Math.abs(d - b)) >> 1);
                        }
                        if (ran) {
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

        private void adapt(int s, int x, int y, boolean hasD) {
            k = update(k, s);
            if (y > 0 && hasD && parameters[x + 1] - k > 1) {
                k++;
            }
            current[x] = k;
        }

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

        private static int symbol(int value) {
            return value >= 0 ? 2 * value : -2 * value - 1;
        }

        private static int update(int k, int estimate) {
            var q = estimate >> k;
            var next = k + (q >= 3 ? 1 : 0) + (q >= 6 ? 1 : 0);
            if (q == 0 && k > 0 && estimate < (1 << (k - 1))) {
                next--;
            }
            return Math.min(next, 15);
        }

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

    /** A most-significant-bit-first bit writer. */
    private static final class Bits {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int buffer;
        private int count;

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

        byte[] bytes() {
            if (count > 0) {
                bytes.write(buffer << (8 - count));
                buffer = 0;
                count = 0;
            }
            // the decoder reads ahead; a few bytes of padding end every bitstream as the camera's do
            bytes.writeBytes(new byte[4]);
            return bytes.toByteArray();
        }
    }

    private byte[] timedMetadata() {
        var color = new int[1816];
        color[0] = 18;
        System.arraycopy(balance, 0, color, 71, 4);
        Arrays.fill(color, 329, 333, black);
        color[797] = white;
        var note = tiff(ifd(new Object[][]{{0x4001, color}}));
        var record = concat(u32le(8 + note.length), u32le(0x927C), note);
        var exif = concat(u32le(12 + record.length), u16le(8), new byte[]{1, 1}, u16le(1), u16le(-1), record);
        var stamp = concat(u32le(24), u16le(1), new byte[]{0, 0}, u16le(1), u16le(0), new byte[12]);
        return concat(stamp, exif);
    }

    /** Builds a little-endian TIFF holding one directory; values are Strings, Integers (SHORT or LONG), int[] (SHORT) or rationals. */
    private static byte[] tiff(TreeMap<Integer, Object> entries) {
        var count = entries.size();
        var directory = ByteBuffer.allocate(2 + 12 * count + 4).order(ByteOrder.LITTLE_ENDIAN);
        var data = new ByteArrayOutputStream();
        var dataStart = 8 + directory.capacity();
        directory.putShort((short) count);
        for (var entry : entries.entrySet()) {
            directory.putShort((short) (int) entry.getKey());
            var value = entry.getValue();
            switch (value) {
                case String text -> {
                    var bytes = (text + "\0").getBytes(StandardCharsets.ISO_8859_1);
                    directory.putShort((short) 2).putInt(bytes.length);
                    if (bytes.length <= 4) {
                        directory.put(Arrays.copyOf(bytes, 4));
                    } else {
                        directory.putInt(dataStart + data.size());
                        data.writeBytes(bytes);
                        if ((data.size() & 1) != 0) {
                            data.write(0);
                        }
                    }
                }
                case Integer number -> directory.putShort((short) 3).putInt(1).putShort((short) (int) number).putShort((short) 0);
                case int[] values when entry.getKey() == 0x829A || entry.getKey() == 0x829D -> {
                    directory.putShort((short) 5).putInt(1).putInt(dataStart + data.size());
                    data.writeBytes(u32le(values[0]));
                    data.writeBytes(u32le(values[1]));
                }
                case int[] values -> {
                    directory.putShort((short) 3).putInt(values.length);
                    if (values.length <= 2) {
                        directory.putShort((short) values[0]).putShort((short) (values.length > 1 ? values[1] : 0));
                    } else {
                        directory.putInt(dataStart + data.size());
                        for (var v : values) {
                            data.writeBytes(u16le(v));
                        }
                    }
                }
                default -> throw new IllegalArgumentException(value.toString());
            }
        }
        directory.putInt(0);
        return concat(new byte[]{'I', 'I', 42, 0}, u32le(8), directory.array(), data.toByteArray());
    }

    private static TreeMap<Integer, Object> ifd(Object[][] entries) {
        var map = new TreeMap<Integer, Object>();
        for (var entry : entries) {
            map.put((Integer) entry[0], entry[1]);
        }
        return map;
    }

    private static byte[] box(String type, byte[] body) {
        return concat(u32(8 + body.length), type.getBytes(StandardCharsets.ISO_8859_1), body);
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] u32(long value) {
        return ByteBuffer.allocate(4).putInt((int) value).array();
    }

    private static byte[] u64(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    private static byte[] u32le(long value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array();
    }

    private static byte[] u16le(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }
}
