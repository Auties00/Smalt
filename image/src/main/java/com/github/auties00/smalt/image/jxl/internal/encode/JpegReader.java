/* Ports libjxl 0.11.2 (lib/jxl/jpeg/enc_jpeg_data_reader.cc: ReadJpeg and the marker, scan and block parsers that
   record what a bit-exact reconstruction needs). Copyright the JPEG XL Project Authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses a JPEG file into its quantized coefficients and everything a {@code jbrd} box records to rebuild the
 * file byte for byte.
 */
final class JpegReader {
    static final int[] NATURAL_ORDER = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7,
            14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39,
            46, 53, 60, 61, 54, 47, 55, 62, 63,
            63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63};

    private static final boolean[] VALID_MARKER = new boolean[64];

    static {
        int[] valid = {1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1, 0, 0,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0};
        for (var i = 0; i < 64; i++) {
            VALID_MARKER[i] = valid[i] != 0;
        }
    }

    /**
     * A quantization table.
     */
    static final class QuantTable {
        final int[] values = new int[64];

        int index;

        int precision;

        boolean last;
    }

    /**
     * A Huffman code as a {@code jbrd} box records it: counts with one extra code of the longest length, and
     * values ending with the sentinel 256.
     */
    static final class HuffmanCode {
        final int[] counts = new int[17];

        final int[] values = new int[257];

        int slot;

        boolean last;

        int total() {
            var t = 0;
            for (var c : counts) {
                t += c;
            }
            return t;
        }
    }

    /**
     * A frame component.
     */
    static final class Component {
        int id;

        int h;

        int v;

        int quantIndex;

        int widthInBlocks;

        int heightInBlocks;

        short[] coefficients;
    }

    /**
     * A scan.
     */
    static final class Scan {
        int count;

        final int[] component = new int[4];

        final int[] dcTable = new int[4];

        final int[] acTable = new int[4];

        int ss;

        int se;

        int ah;

        int al;

        final List<Integer> resetPoints = new ArrayList<>();

        final List<int[]> extraZeroRuns = new ArrayList<>();
    }

    int width;

    int height;

    int restartInterval;

    final List<byte[]> app = new ArrayList<>();

    final List<byte[]> com = new ArrayList<>();

    final List<QuantTable> quant = new ArrayList<>();

    final List<HuffmanCode> huffman = new ArrayList<>();

    final List<Component> components = new ArrayList<>();

    final List<Scan> scans = new ArrayList<>();

    final List<Integer> markers = new ArrayList<>();

    final List<byte[]> intermarker = new ArrayList<>();

    byte[] tail = new byte[0];

    boolean hasZeroPaddingBit;

    final List<Integer> paddingBits = new ArrayList<>();

    private final byte[] data;

    private final int length;

    private final int[][] dcLut = new int[4][];

    private final int[][] acLut = new int[4][];

    private final MediaLimits limits;

    private JpegReader(byte[] data, MediaLimits limits) {
        this.data = data;
        this.length = data.length;
        this.limits = limits;
    }

    static MediaException.Malformed malformed(String reason) {
        return new MediaException.Malformed("invalid JPEG file: " + reason);
    }

    /**
     * Parses a file.
     *
     * @param data the file
     * @param limits the bounds of the image size and of the coefficient memory
     * @return the parsed file
     * @throws MediaException.Malformed if the file is invalid or truncated
     * @throws MediaException.Unsupported if the file uses a process JPEG XL cannot hold
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    static JpegReader read(byte[] data, MediaLimits limits) {
        var r = new JpegReader(data, limits);
        r.parse();
        return r;
    }

    private int u8(int pos) {
        if (pos >= length) {
            throw malformed("truncated");
        }
        return data[pos] & 0xFF;
    }

    private int u16(int pos) {
        return (u8(pos) << 8) | u8(pos + 1);
    }

    private void parse() {
        var pos = 0;
        if (length < 2 || u8(0) != 0xFF || u8(1) != 0xD8) {
            throw malformed("no SOI marker");
        }
        pos = 2;
        var foundSof = false;
        var foundDri = false;
        var progressive = false;
        var progression = new int[4][64];
        int marker;
        do {
            var skipped = 0;
            var start = pos;
            while (pos + 1 < length && (u8(pos) != 0xFF || u8(pos + 1) < 0xC0 || !VALID_MARKER[u8(pos + 1) - 0xC0])) {
                pos++;
                skipped++;
            }
            if (skipped > 0) {
                markers.add(0xFF);
                intermarker.add(Arrays.copyOfRange(data, start, start + skipped));
            }
            if (pos + 1 >= length || u8(pos) != 0xFF) {
                throw malformed("marker expected at " + pos);
            }
            marker = u8(pos + 1);
            pos += 2;
            switch (marker) {
                case 0xC0, 0xC1, 0xC2 -> {
                    progressive = marker == 0xC2;
                    pos = processSof(pos);
                    foundSof = true;
                }
                case 0xC4 -> pos = processDht(pos);
                case 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD9 -> {
                }
                case 0xDA -> {
                    if (!foundSof) {
                        throw malformed("scan before SOF");
                    }
                    pos = processScan(pos, progression, progressive);
                }
                case 0xDB -> pos = processDqt(pos);
                case 0xDD -> {
                    if (foundDri) {
                        throw malformed("duplicate DRI marker");
                    }
                    foundDri = true;
                    var len = u16(pos);
                    if (len != 4) {
                        throw malformed("invalid DRI length");
                    }
                    restartInterval = u16(pos + 2);
                    pos += len;
                }
                case 0xFE -> pos = processSegment(pos, com);
                default -> {
                    if (marker >= 0xE0 && marker <= 0xEF) {
                        pos = processSegment(pos, app);
                    } else if (marker == 0xC3 || (marker >= 0xC5 && marker <= 0xCF && marker != 0xC8
                            && marker != 0xCC)) {
                        throw new MediaException.Unsupported("JPEG XL", "JPEG coding process SOF"
                                + Integer.toHexString(marker - 0xC0) + " cannot be recompressed");
                    } else {
                        throw malformed("unsupported marker 0x" + Integer.toHexString(marker));
                    }
                }
            }
            markers.add(marker);
        } while (marker != 0xD9);
        if (!foundSof) {
            throw malformed("missing SOF marker");
        }
        if (pos < length) {
            tail = Arrays.copyOfRange(data, pos, length);
        }
        for (var c : components) {
            var found = false;
            for (var j = 0; j < quant.size(); j++) {
                if (quant.get(j).index == c.quantIndex) {
                    c.quantIndex = j;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw malformed("missing quantization table");
            }
        }
        if (huffman.isEmpty()) {
            throw malformed("no Huffman table");
        }
        if (huffman.size() >= 512) {
            throw malformed("too many Huffman tables");
        }
    }

    private int processSegment(int pos, List<byte[]> into) {
        var len = u16(pos);
        if (len < 2 || pos + len > length) {
            throw malformed("invalid segment length");
        }
        into.add(Arrays.copyOfRange(data, pos - 1, pos + len));
        return pos + len;
    }

    private int processSof(int pos) {
        if (width != 0) {
            throw malformed("duplicate SOF marker");
        }
        var start = pos;
        var len = u16(pos);
        var precision = u8(pos + 2);
        height = u16(pos + 3);
        width = u16(pos + 5);
        var count = u8(pos + 7);
        if (precision != 8) {
            throw new MediaException.Unsupported("JPEG XL", precision + "-bit JPEG samples cannot be recompressed");
        }
        if (width < 1) {
            throw malformed("zero image width");
        }
        if (height < 1) {
            throw new MediaException.Unsupported("JPEG XL", "a JPEG file whose height a DNL marker defines cannot be "
                    + "recompressed");
        }
        if (count != 1 && count != 3) {
            throw new MediaException.Unsupported("JPEG XL", "JPEG files with " + count
                    + " components cannot be recompressed");
        }
        pos += 8;
        var seen = new boolean[256];
        var maxH = 1;
        var maxV = 1;
        for (var i = 0; i < count; i++) {
            var c = new Component();
            c.id = u8(pos);
            if (seen[c.id]) {
                throw malformed("duplicate component id");
            }
            seen[c.id] = true;
            var factor = u8(pos + 1);
            c.h = factor >> 4;
            c.v = factor & 15;
            if (c.h < 1 || c.h > 2 || c.v < 1 || c.v > 2) {
                throw new MediaException.Unsupported("JPEG XL", "JPEG sampling factor " + c.h + "x" + c.v
                        + " cannot be recompressed");
            }
            c.quantIndex = u8(pos + 2);
            maxH = Math.max(maxH, c.h);
            maxV = Math.max(maxV, c.v);
            components.add(c);
            pos += 3;
        }
        var mcuRows = (height + maxV * 8 - 1) / (maxV * 8);
        var mcuCols = (width + maxH * 8 - 1) / (maxH * 8);
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (long) mcuRows * mcuCols * maxH * maxV * 64 * components.size() * 24);
        for (var c : components) {
            if (maxH % c.h != 0 || maxV % c.v != 0) {
                throw malformed("non-integral subsampling");
            }
            c.widthInBlocks = mcuCols * c.h;
            c.heightInBlocks = mcuRows * c.v;
            c.coefficients = new short[c.widthInBlocks * c.heightInBlocks * 64];
        }
        if (pos != start + len) {
            throw malformed("SOF length mismatch");
        }
        return pos;
    }

    private int processDqt(int pos) {
        var start = pos;
        var len = u16(pos);
        if (len == 2) {
            throw malformed("empty DQT");
        }
        pos += 2;
        while (pos < start + len && quant.size() < 4) {
            var b = u8(pos++);
            var table = new QuantTable();
            table.precision = b >> 4;
            table.index = b & 15;
            if (table.precision > 1 || table.index > 3) {
                throw malformed("invalid DQT");
            }
            for (var i = 0; i < 64; i++) {
                int value;
                if (table.precision != 0) {
                    value = u16(pos);
                    pos += 2;
                } else {
                    value = u8(pos++);
                }
                if (value < 1) {
                    throw malformed("zero quantizer");
                }
                table.values[NATURAL_ORDER[i]] = value;
            }
            table.last = pos == start + len;
            quant.add(table);
        }
        if (pos != start + len) {
            throw malformed("DQT length mismatch");
        }
        return pos;
    }

    private int processDht(int pos) {
        var start = pos;
        var len = u16(pos);
        if (len == 2) {
            throw malformed("empty DHT");
        }
        pos += 2;
        while (pos < start + len) {
            var code = new HuffmanCode();
            code.slot = u8(pos++);
            var ac = (code.slot & 0x10) != 0;
            var index = code.slot & 0x0F;
            if (index > 3 || (code.slot & 0xE0) != 0) {
                throw malformed("invalid Huffman table index");
            }
            var total = 0;
            var space = 1 << 16;
            var maxDepth = 1;
            for (var i = 1; i <= 16; i++) {
                var count = u8(pos++);
                if (count != 0) {
                    maxDepth = i;
                }
                code.counts[i] = count;
                total += count;
                space -= count * (1 << (16 - i));
            }
            if (total > (ac ? 256 : 12)) {
                throw malformed("invalid Huffman code");
            }
            var seen = new boolean[256];
            for (var i = 0; i < total; i++) {
                var value = u8(pos++);
                if (!ac && value > 11) {
                    throw malformed("invalid DC Huffman value");
                }
                if (seen[value]) {
                    throw malformed("duplicate Huffman value");
                }
                seen[value] = true;
                code.values[i] = value;
            }
            code.counts[maxDepth]++;
            code.values[total] = 256;
            space -= 1 << (16 - maxDepth);
            if (space < 0) {
                throw malformed("invalid Huffman code lengths");
            }
            code.last = pos == start + len;
            var lut = buildLut(code.counts, code.values);
            if (ac) {
                acLut[index] = lut;
            } else {
                dcLut[index] = lut;
            }
            huffman.add(code);
        }
        if (pos != start + len) {
            throw malformed("DHT length mismatch");
        }
        return pos;
    }

    /**
     * Builds a decoding table indexed by the next 16 bits: value in the low 16 bits, length above.
     */
    private static int[] buildLut(int[] counts, int[] values) {
        var lut = new int[1 << 16];
        Arrays.fill(lut, -1);
        var code = 0;
        var k = 0;
        for (var len = 1; len <= 16; len++) {
            for (var i = 0; i < counts[len]; i++) {
                var value = values[k++];
                var first = code << (16 - len);
                var span = 1 << (16 - len);
                for (var j = 0; j < span && first + j < lut.length; j++) {
                    lut[first + j] = value | (len << 16);
                }
                code++;
            }
            code <<= 1;
        }
        return lut;
    }

    private final class BitReader {
        int pos;

        long val;

        int bitsLeft;

        int nextMarkerPos;

        BitReader(int pos) {
            reset(pos);
        }

        void reset(int p) {
            pos = p;
            val = 0;
            bitsLeft = 0;
            nextMarkerPos = length - 2;
            fill();
        }

        int nextByte() {
            if (pos >= nextMarkerPos) {
                pos++;
                return 0;
            }
            var c = data[pos++] & 0xFF;
            if (c == 0xFF) {
                var escape = pos < length ? data[pos] & 0xFF : 0;
                if (escape == 0) {
                    pos++;
                } else {
                    nextMarkerPos = pos - 1;
                }
            }
            return c;
        }

        void fill() {
            if (bitsLeft <= 16) {
                while (bitsLeft <= 56) {
                    val = (val << 8) | nextByte();
                    bitsLeft += 8;
                }
            }
        }

        int bits(int n) {
            if (n == 0) {
                return 0;
            }
            fill();
            var v = (int) ((val >>> (bitsLeft - n)) & ((1L << n) - 1));
            bitsLeft -= n;
            return v;
        }

        int symbol(int[] lut) {
            fill();
            var peek = (int) ((val >>> (bitsLeft - 16)) & 0xFFFF);
            var e = lut[peek];
            if (e < 0) {
                throw malformed("invalid Huffman code in a scan");
            }
            bitsLeft -= e >>> 16;
            return e & 0xFFFF;
        }

        int finish() {
            var pad = bitsLeft & 7;
            if (pad > 0) {
                var mask = (1L << pad) - 1;
                var padBits = (val >>> (bitsLeft - pad)) & mask;
                if (padBits != mask) {
                    hasZeroPaddingBit = true;
                }
                for (var i = pad - 1; i >= 0; i--) {
                    paddingBits.add((int) ((padBits >>> i) & 1));
                }
            }
            var unused = bitsLeft >> 3;
            while (unused-- > 0) {
                pos--;
                if (pos < nextMarkerPos && data[pos] == 0 && (data[pos - 1] & 0xFF) == 0xFF) {
                    pos--;
                }
            }
            if (pos > nextMarkerPos) {
                throw malformed("unexpected end of scan");
            }
            return pos;
        }
    }

    private static int extend(int x, int s) {
        var half = 1 << (s - 1);
        return x >= half ? x : x - (1 << s) + 1;
    }

    private int processScan(int pos, int[][] progression, boolean progressive) {
        var start = pos;
        var len = u16(pos);
        var count = u8(pos + 2);
        if (count < 1 || count > components.size()) {
            throw malformed("invalid scan component count");
        }
        var scan = new Scan();
        scan.count = count;
        pos += 3;
        var seen = new boolean[256];
        for (var i = 0; i < count; i++) {
            var id = u8(pos);
            if (seen[id]) {
                throw malformed("duplicate scan component");
            }
            seen[id] = true;
            var found = -1;
            for (var j = 0; j < components.size(); j++) {
                if (components.get(j).id == id) {
                    found = j;
                }
            }
            if (found < 0) {
                throw malformed("unknown scan component");
            }
            scan.component[i] = found;
            var t = u8(pos + 1);
            scan.dcTable[i] = t >> 4;
            scan.acTable[i] = t & 15;
            if (scan.dcTable[i] > 3 || scan.acTable[i] > 3) {
                throw malformed("invalid scan table index");
            }
            pos += 2;
        }
        scan.ss = u8(pos);
        scan.se = u8(pos + 1);
        var a = u8(pos + 2);
        scan.ah = a >> 4;
        scan.al = a & 15;
        pos += 3;
        if (scan.ss > 63 || scan.se < scan.ss || scan.se > 63) {
            throw malformed("invalid spectral selection");
        }
        if (pos != start + len) {
            throw malformed("SOS length mismatch");
        }
        for (var i = 0; i < count; i++) {
            if (scan.ss == 0 && dcLut[scan.dcTable[i]] == null) {
                throw malformed("missing DC Huffman table");
            }
            if (scan.se > 0 && acLut[scan.acTable[i]] == null) {
                throw malformed("missing AC Huffman table");
            }
        }
        scans.add(scan);
        var interleaved = count > 1;
        var maxH = 1;
        var maxV = 1;
        for (var c : components) {
            maxH = Math.max(maxH, c.h);
            maxV = Math.max(maxV, c.v);
        }
        var mcuRows = (height + maxV * 8 - 1) / (maxV * 8);
        var mcusPerRow = (width + maxH * 8 - 1) / (maxH * 8);
        if (!interleaved) {
            var c = components.get(scan.component[0]);
            mcusPerRow = (width * c.h + 8 * maxH - 1) / (8 * maxH);
            mcuRows = (height * c.v + 8 * maxV - 1) / (8 * maxV);
        }
        var lastDc = new int[4];
        var br = new BitReader(pos);
        var restartsToGo = restartInterval;
        var nextRestart = 0;
        var eobrun = new int[]{-1};
        var blockScanIndex = 0;
        var al = progressive ? scan.al : 0;
        var ah = progressive ? scan.ah : 0;
        var ss = progressive ? scan.ss : 0;
        var se = progressive ? scan.se : 63;
        var scanMask = ah == 0 ? (0xFFFF << al) & 0xFFFF : 1 << al;
        var refineMask = (1 << al) - 1;
        for (var i = 0; i < count; i++) {
            var ci = scan.component[i];
            for (var k = ss; k <= se; k++) {
                if ((progression[ci][k] & scanMask) != 0 || (progression[ci][k] & refineMask) != 0) {
                    throw malformed("overlapping or misordered progressive scans");
                }
                progression[ci][k] |= scanMask;
            }
        }
        if (al > 10) {
            throw malformed("successive approximation too fine");
        }
        var reset = new boolean[1];
        var zeroRuns = new int[1];
        for (var mcuY = 0; mcuY < mcuRows; mcuY++) {
            for (var mcuX = 0; mcuX < mcusPerRow; mcuX++) {
                if (restartInterval > 0) {
                    if (restartsToGo == 0) {
                        var p = br.finish();
                        if (p + 1 >= length || u8(p) != 0xFF || u8(p + 1) != 0xD0 + nextRestart) {
                            throw malformed("missing restart marker");
                        }
                        br.reset(p + 2);
                        nextRestart = (nextRestart + 1) & 7;
                        restartsToGo = restartInterval;
                        Arrays.fill(lastDc, 0);
                        if (eobrun[0] > 0) {
                            throw malformed("end-of-block run too long");
                        }
                        eobrun[0] = -1;
                    }
                    restartsToGo--;
                }
                for (var i = 0; i < count; i++) {
                    var c = components.get(scan.component[i]);
                    var by = interleaved ? c.v : 1;
                    var bx = interleaved ? c.h : 1;
                    for (var iy = 0; iy < by; iy++) {
                        for (var ix = 0; ix < bx; ix++) {
                            var blockY = mcuY * by + iy;
                            var blockX = mcuX * bx + ix;
                            var offset = (blockY * c.widthInBlocks + blockX) * 64;
                            reset[0] = false;
                            zeroRuns[0] = 0;
                            if (ah == 0) {
                                decodeBlock(dcLut[scan.dcTable[i]], acLut[scan.acTable[i]], ss, se, al, eobrun,
                                        reset, zeroRuns, br, lastDc, scan.component[i], c.coefficients, offset);
                            } else {
                                refineBlock(acLut[scan.acTable[i]], ss, se, al, eobrun, reset, br, c.coefficients,
                                        offset);
                            }
                            if (reset[0]) {
                                scan.resetPoints.add(blockScanIndex);
                            }
                            if (zeroRuns[0] > 0) {
                                scan.extraZeroRuns.add(new int[]{blockScanIndex, zeroRuns[0]});
                            }
                            blockScanIndex++;
                        }
                    }
                }
            }
        }
        if (eobrun[0] > 0) {
            throw malformed("end-of-block run too long");
        }
        pos = br.finish();
        if (pos > length) {
            throw malformed("truncated scan");
        }
        return pos;
    }

    private void decodeBlock(int[] dcTable, int[] acTable, int ss, int se, int al, int[] eobrun, boolean[] reset,
                             int[] zeroRuns, BitReader br, int[] lastDc, int comp, short[] coeffs, int offset) {
        var am = 1 << al;
        var eobAllowed = ss > 0;
        if (ss == 0) {
            var s = br.symbol(dcTable);
            if (s >= 12) {
                throw malformed("invalid DC symbol");
            }
            var diff = s > 0 ? extend(br.bits(s), s) : 0;
            var coeff = diff + lastDc[comp];
            var dc = coeff * am;
            if ((short) dc != dc) {
                throw malformed("DC coefficient out of range");
            }
            coeffs[offset] = (short) dc;
            lastDc[comp] = coeff;
            ss++;
        }
        if (ss > se) {
            return;
        }
        if (eobrun[0] > 0) {
            eobrun[0]--;
            return;
        }
        zeroRuns[0] = 0;
        for (var k = ss; k <= se; k++) {
            var sr = br.symbol(acTable);
            if (sr >= 256) {
                throw malformed("invalid AC symbol");
            }
            var r = sr >> 4;
            var s = sr & 15;
            if (s > 0) {
                k += r;
                if (k > se) {
                    throw malformed("coefficient outside the spectral band");
                }
                if (s + al >= 12) {
                    throw malformed("AC coefficient out of range");
                }
                coeffs[offset + NATURAL_ORDER[k]] = (short) (extend(br.bits(s), s) * am);
                zeroRuns[0] = 0;
            } else if (r == 15) {
                k += 15;
                zeroRuns[0]++;
            } else {
                if (eobAllowed && k == ss && eobrun[0] == 0) {
                    reset[0] = true;
                }
                eobrun[0] = 1 << r;
                if (r > 0) {
                    if (!eobAllowed) {
                        throw malformed("end-of-block run crossing the DC coefficient");
                    }
                    eobrun[0] += br.bits(r);
                }
                break;
            }
        }
        eobrun[0]--;
    }

    private void refineBlock(int[] acTable, int ss, int se, int al, int[] eobrun, boolean[] reset, BitReader br,
                             short[] coeffs, int offset) {
        var am = 1 << al;
        var eobAllowed = ss > 0;
        if (ss == 0) {
            var s = br.bits(1);
            coeffs[offset] = (short) (coeffs[offset] | (s * am));
            ss++;
        }
        if (ss > se) {
            return;
        }
        var p1 = am;
        var m1 = -am;
        var k = ss;
        var inZeroRun = false;
        if (eobrun[0] <= 0) {
            for (; k <= se; k++) {
                var s = br.symbol(acTable);
                if (s >= 256) {
                    throw malformed("invalid AC symbol");
                }
                var r = s >> 4;
                s &= 15;
                if (s != 0) {
                    if (s != 1) {
                        throw malformed("invalid refinement symbol");
                    }
                    s = br.bits(1) != 0 ? p1 : m1;
                    inZeroRun = false;
                } else {
                    if (r != 15) {
                        if (eobAllowed && k == ss && eobrun[0] == 0) {
                            reset[0] = true;
                        }
                        eobrun[0] = 1 << r;
                        if (r > 0) {
                            if (!eobAllowed) {
                                throw malformed("end-of-block run crossing the DC coefficient");
                            }
                            eobrun[0] += br.bits(r);
                        }
                        break;
                    }
                    inZeroRun = true;
                }
                do {
                    var index = offset + NATURAL_ORDER[k];
                    int coef = coeffs[index];
                    if (coef != 0) {
                        if (br.bits(1) != 0 && (coef & p1) == 0) {
                            coef += coef >= 0 ? p1 : m1;
                        }
                        coeffs[index] = (short) coef;
                    } else {
                        if (--r < 0) {
                            break;
                        }
                    }
                    k++;
                } while (k <= se);
                if (s != 0) {
                    if (k > se) {
                        throw malformed("coefficient outside the spectral band");
                    }
                    coeffs[offset + NATURAL_ORDER[k]] = (short) s;
                }
            }
        }
        if (inZeroRun) {
            throw malformed("extra zero run before end of block");
        }
        if (eobrun[0] > 0) {
            for (; k <= se; k++) {
                var index = offset + NATURAL_ORDER[k];
                int coef = coeffs[index];
                if (coef != 0) {
                    if (br.bits(1) != 0 && (coef & p1) == 0) {
                        coef += coef >= 0 ? p1 : m1;
                    }
                    coeffs[index] = (short) coef;
                }
            }
        }
        eobrun[0]--;
    }
}
