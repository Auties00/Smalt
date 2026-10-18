package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.vorbis.internal.Ilog;
import java.util.Arrays;

/**
 * A deliberately literal, slow Vorbis I audio packet decoder written from the specification's
 * pseudo-code, independent of the production classes: it reads the stream configuration from a
 * {@link VorbisStreamBuilder} (so it needs no header parser), walks an explicit Huffman tree bit by
 * bit, renders floors into integer or double curves, decodes residues with the specification's
 * loops, computes the inverse MDCT by its O(n^2) definition in double precision, and windows and
 * laps whole blocks aligned at their 3/4 and 1/4 points. Floor 0 follows libvorbis' single
 * precision arithmetic exactly as the production decoder documents, so both agree to float
 * rounding.
 */
final class ReferenceVorbisDecoder {
    private final VorbisStreamBuilder stream;
    private final Tree[] trees;
    private double[][] previous;
    private int previousSize;

    ReferenceVorbisDecoder(VorbisStreamBuilder stream) {
        this.stream = stream;
        this.trees = new Tree[stream.books.size()];
        for (var i = 0; i < trees.length; i++) {
            trees[i] = new Tree(stream.books.get(i).lengths);
        }
    }

    /** Bit reader with the specification's end-of-packet semantics. */
    static final class Bits {
        private final byte[] data;
        private long position;
        boolean endOfPacket;

        Bits(byte[] data) {
            this.data = data;
        }

        int read(int bits) {
            if (endOfPacket || position + bits > (long) data.length * 8) {
                endOfPacket = true;
                return 0;
            }
            var value = 0;
            for (var i = 0; i < bits; i++) {
                var bit = (data[(int) (position >>> 3)] >>> (position & 7)) & 1;
                value |= bit << i;
                position++;
            }
            return value;
        }
    }

    /** An explicit binary tree built by placing each codeword at the leftmost free node of its depth. */
    static final class Tree {
        private final boolean single;
        private final int singleEntry;

        Tree(int[] lengths) {
            var used = 0;
            var last = -1;
            for (var i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0) {
                    used++;
                    last = i;
                }
            }
            single = used == 1;
            singleEntry = last;
            nodes = new int[1 << 12][];
            full = new boolean[1 << 12];
            leafEntry = new int[1 << 12];
            Arrays.fill(leafEntry, -1);
            nodes[0] = new int[]{-1, -1};
            nodesCount = 1;
            if (single) {
                return;
            }
            for (var i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0 && !place(0, 0, lengths[i], i)) {
                    throw new IllegalStateException("overspecified");
                }
            }
        }

        private int[][] nodes;
        private boolean[] full;
        private int[] leafEntry;
        private int nodesCount;

        private int child(int node, int bit) {
            if (nodes[node][bit] < 0) {
                if (nodesCount == nodes.length) {
                    nodes = Arrays.copyOf(nodes, nodes.length * 2);
                    full = Arrays.copyOf(full, full.length * 2);
                    leafEntry = Arrays.copyOf(leafEntry, leafEntry.length * 2);
                    Arrays.fill(leafEntry, nodesCount, leafEntry.length, -1);
                }
                nodes[nodesCount] = new int[]{-1, -1};
                nodes[node][bit] = nodesCount++;
            }
            return nodes[node][bit];
        }

        /** Places a leaf at the leftmost free node of the given depth below {@code node}. */
        private boolean place(int node, int depth, int length, int entry) {
            if (full[node] || leafEntry[node] >= 0) {
                return false;
            }
            if (depth == length) {
                if (nodes[node][0] >= 0 || nodes[node][1] >= 0) {
                    return false;
                }
                leafEntry[node] = entry;
                full[node] = true;
                return true;
            }
            for (var bit = 0; bit < 2; bit++) {
                if (place(child(node, bit), depth + 1, length, entry)) {
                    var left = nodes[node][0];
                    var right = nodes[node][1];
                    full[node] = left >= 0 && right >= 0 && full[left] && full[right];
                    return true;
                }
            }
            return false;
        }

        int decode(Bits bits) {
            if (single) {
                bits.read(1);
                return bits.endOfPacket ? -1 : singleEntry;
            }
            var node = 0;
            while (leafEntry[node] < 0) {
                var bit = bits.read(1);
                if (bits.endOfPacket) {
                    return -1;
                }
                node = nodes[node][bit];
                if (node < 0) {
                    return -1;
                }
            }
            return leafEntry[node];
        }
    }

    /** Decodes one packet; returns per-channel samples in stream order, or null for no output. */
    double[][] decode(byte[] packet) {
        var bits = new Bits(packet);
        if (packet.length == 0 || bits.read(1) != 0) {
            return null;
        }
        var modeBits = Ilog.ilog(stream.modes.size() - 1);
        var modeNumber = bits.read(modeBits);
        if (bits.endOfPacket || modeNumber >= stream.modes.size()) {
            throw new IllegalArgumentException("undecodable packet");
        }
        var mode = stream.modes.get(modeNumber);
        var n = stream.blocksize(mode.longBlock());
        var shortSize = stream.blocksize(false);
        var previousFlag = true;
        var nextFlag = true;
        if (mode.longBlock()) {
            previousFlag = bits.read(1) == 1;
            nextFlag = bits.read(1) == 1;
            if (bits.endOfPacket) {
                throw new IllegalArgumentException("undecodable packet");
            }
        }
        var mapping = stream.mappings.get(mode.mapping());
        var channels = stream.channels;
        var half = n / 2;
        var floorValues = new Object[channels];
        var unused = new boolean[channels];
        for (var ch = 0; ch < channels; ch++) {
            var floor = stream.floors.get(mapping.submapFloors()[mapping.submaps() > 1 ? mapping.mux()[ch] : 0]);
            floorValues[ch] = floor instanceof VorbisStreamBuilder.Floor1Spec f1 ? decodeFloor1(bits, f1)
                    : decodeFloor0(bits, (VorbisStreamBuilder.Floor0Spec) floor);
            unused[ch] = floorValues[ch] == null;
        }
        var residues = new double[channels][half];
        if (bits.endOfPacket) {
            Arrays.fill(unused, true);
        } else {
            var noResidue = unused.clone();
            for (var i = 0; i < mapping.magnitude().length; i++) {
                if (!noResidue[mapping.magnitude()[i]] || !noResidue[mapping.angle()[i]]) {
                    noResidue[mapping.magnitude()[i]] = false;
                    noResidue[mapping.angle()[i]] = false;
                }
            }
            for (var submap = 0; submap < mapping.submaps(); submap++) {
                var members = new int[channels];
                var count = 0;
                for (var ch = 0; ch < channels; ch++) {
                    var mux = mapping.submaps() > 1 ? mapping.mux()[ch] : 0;
                    if (mux == submap) {
                        members[count++] = ch;
                    }
                }
                var skip = new boolean[count];
                for (var i = 0; i < count; i++) {
                    skip[i] = noResidue[members[i]];
                }
                var decoded = decodeResidue(bits, stream.residues.get(mapping.submapResidues()[submap]), count, skip, half);
                for (var i = 0; i < count; i++) {
                    residues[members[i]] = decoded[i];
                }
            }
            for (var i = mapping.magnitude().length - 1; i >= 0; i--) {
                var magnitude = residues[mapping.magnitude()[i]];
                var angle = residues[mapping.angle()[i]];
                for (var j = 0; j < half; j++) {
                    var m = (float) magnitude[j];
                    var a = (float) angle[j];
                    float newM;
                    float newA;
                    if (m > 0) {
                        if (a > 0) {
                            newM = m;
                            newA = m - a;
                        } else {
                            newA = m;
                            newM = m + a;
                        }
                    } else {
                        if (a > 0) {
                            newM = m;
                            newA = m + a;
                        } else {
                            newA = m;
                            newM = m - a;
                        }
                    }
                    magnitude[j] = newM;
                    angle[j] = newA;
                }
            }
        }
        var output = new double[channels][];
        var blocks = new double[channels][];
        for (var ch = 0; ch < channels; ch++) {
            var spectrum = new double[half];
            if (!unused[ch]) {
                var floor = stream.floors.get(mapping.submapFloors()[mapping.submaps() > 1 ? mapping.mux()[ch] : 0]);
                var curve = floor instanceof VorbisStreamBuilder.Floor1Spec f1 ? renderFloor1(f1, (int[]) floorValues[ch], half)
                        : renderFloor0((VorbisStreamBuilder.Floor0Spec) floor, (float[]) floorValues[ch], half, mode.longBlock());
                for (var i = 0; i < half; i++) {
                    spectrum[i] = (float) ((float) residues[ch][i] * (float) curve[i]);
                }
            }
            var block = new double[n];
            for (var i = 0; i < n; i++) {
                var sum = 0.0;
                for (var k = 0; k < half; k++) {
                    sum += spectrum[k] * Math.cos(2 * Math.PI / n * (i + 0.5 + n / 4.0) * (k + 0.5));
                }
                block[i] = sum * window(i, n, mode.longBlock(), previousFlag, nextFlag, shortSize);
            }
            blocks[ch] = block;
        }
        double[][] result = null;
        if (previous != null) {
            var length = previousSize / 4 + n / 4;
            result = new double[channels][length];
            for (var ch = 0; ch < channels; ch++) {
                // Output starts at the previous center; the current block's 1/4 point aligns with the previous 3/4 point.
                for (var o = 0; o < length; o++) {
                    var p = previousSize / 2 + o;
                    var c = o - (previousSize / 4 - n / 4);
                    var value = p < previousSize ? previous[ch][p] : 0.0;
                    if (c >= 0 && c < n) {
                        value += blocks[ch][c];
                    }
                    result[ch][o] = value;
                }
            }
        }
        previous = blocks;
        previousSize = n;
        return result == null ? new double[0][] : result;
    }

    static double window(int i, int n, boolean longBlock, boolean previousFlag, boolean nextFlag, int shortSize) {
        int leftStart;
        int leftEnd;
        int leftN;
        if (longBlock && !previousFlag) {
            leftStart = n / 4 - shortSize / 4;
            leftEnd = n / 4 + shortSize / 4;
            leftN = shortSize / 2;
        } else {
            leftStart = 0;
            leftEnd = n / 2;
            leftN = n / 2;
        }
        int rightStart;
        int rightEnd;
        int rightN;
        if (longBlock && !nextFlag) {
            rightStart = n * 3 / 4 - shortSize / 4;
            rightEnd = n * 3 / 4 + shortSize / 4;
            rightN = shortSize / 2;
        } else {
            rightStart = n / 2;
            rightEnd = n;
            rightN = n / 2;
        }
        if (i < leftStart) {
            return 0;
        }
        if (i < leftEnd) {
            var s = Math.sin((i - leftStart + 0.5) / leftN * Math.PI / 2);
            return Math.sin(Math.PI / 2 * s * s);
        }
        if (i < rightStart) {
            return 1;
        }
        if (i < rightEnd) {
            var s = Math.sin((i - rightStart + 0.5) / rightN * Math.PI / 2 + Math.PI / 2);
            return Math.sin(Math.PI / 2 * s * s);
        }
        return 0;
    }

    private int[] decodeFloor1(Bits bits, VorbisStreamBuilder.Floor1Spec floor) {
        if (bits.read(1) == 0) {
            return null;
        }
        var range = new int[]{256, 128, 86, 64}[floor.multiplier() - 1];
        var y = new int[floor.values()];
        y[0] = bits.read(Ilog.ilog(range - 1));
        y[1] = bits.read(Ilog.ilog(range - 1));
        var offset = 2;
        for (var cls : floor.partitionClasses()) {
            var cdim = floor.classDimensions()[cls];
            var cbits = floor.classSubclasses()[cls];
            var csub = (1 << cbits) - 1;
            var cval = 0;
            if (cbits > 0) {
                cval = trees[floor.classMasterbooks()[cls]].decode(bits);
            }
            for (var j = 0; j < cdim; j++) {
                var book = floor.subclassBooks()[cls][cval & csub];
                cval >>>= cbits;
                y[j + offset] = book >= 0 ? trees[book].decode(bits) : 0;
            }
            offset += cdim;
        }
        return bits.endOfPacket ? null : y;
    }

    private static int[] xList(VorbisStreamBuilder.Floor1Spec floor) {
        var xs = new int[floor.values()];
        xs[1] = 1 << floor.rangeBits();
        System.arraycopy(floor.xs(), 0, xs, 2, floor.xs().length);
        return xs;
    }

    private static double[] renderFloor1(VorbisStreamBuilder.Floor1Spec floor, int[] y, int n) {
        var range = new int[]{256, 128, 86, 64}[floor.multiplier() - 1];
        var xs = xList(floor);
        var values = xs.length;
        var step2 = new boolean[values];
        var finalY = new int[values];
        step2[0] = step2[1] = true;
        finalY[0] = y[0];
        finalY[1] = y[1];
        for (var i = 2; i < values; i++) {
            var low = -1;
            var high = -1;
            for (var j = 0; j < i; j++) {
                if (xs[j] < xs[i] && (low < 0 || xs[j] > xs[low])) {
                    low = j;
                }
                if (xs[j] > xs[i] && (high < 0 || xs[j] < xs[high])) {
                    high = j;
                }
            }
            var predicted = renderPoint(xs[low], finalY[low], xs[high], finalY[high], xs[i]);
            var val = y[i];
            var highroom = range - predicted;
            var lowroom = predicted;
            var room = highroom < lowroom ? highroom * 2 : lowroom * 2;
            if (val != 0) {
                step2[low] = step2[high] = step2[i] = true;
                if (val >= room) {
                    finalY[i] = highroom > lowroom ? val - lowroom + predicted : predicted - val + highroom - 1;
                } else {
                    finalY[i] = val % 2 == 1 ? predicted - (val + 1) / 2 : predicted + val / 2;
                }
            } else {
                step2[i] = false;
                finalY[i] = predicted;
            }
        }
        for (var i = 0; i < values; i++) {
            finalY[i] = Math.max(0, Math.min(range - 1, finalY[i]));
        }
        var order = new Integer[values];
        for (var i = 0; i < values; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(xs[a], xs[b]));
        var curve = new int[Math.max(n, xs[1]) + 1];
        var hx = 0;
        var lx = 0;
        var ly = finalY[order[0]] * floor.multiplier();
        var hy = 0;
        for (var k = 1; k < values; k++) {
            var i = order[k];
            if (step2[i]) {
                hy = finalY[i] * floor.multiplier();
                hx = xs[i];
                renderLine(lx, ly, hx, hy, curve);
                lx = hx;
                ly = hy;
            }
        }
        if (hx < n) {
            renderLine(hx, hy, n, hy, curve);
        }
        var result = new double[n];
        for (var i = 0; i < n; i++) {
            result[i] = Floor1.INVERSE_DB[curve[i]];
        }
        return result;
    }

    private static int renderPoint(int x0, int y0, int x1, int y1, int x) {
        var dy = y1 - y0;
        var adx = x1 - x0;
        var ady = Math.abs(dy);
        var err = ady * (x - x0);
        var off = err / adx;
        return dy < 0 ? y0 - off : y0 + off;
    }

    private static void renderLine(int x0, int y0, int x1, int y1, int[] v) {
        var dy = y1 - y0;
        var adx = x1 - x0;
        var ady = Math.abs(dy);
        var base = dy / adx;
        var x = x0;
        var y = y0;
        var err = 0;
        var sy = dy < 0 ? base - 1 : base + 1;
        ady = ady - Math.abs(base) * adx;
        v[x] = y;
        for (x = x0 + 1; x < x1; x++) {
            err = err + ady;
            if (err >= adx) {
                err = err - adx;
                y = y + sy;
            } else {
                y = y + base;
            }
            v[x] = y;
        }
    }

    /** Returns the LSP coefficients with the amplitude appended, or null when unused. */
    private float[] decodeFloor0(Bits bits, VorbisStreamBuilder.Floor0Spec floor) {
        var amplitude = bits.read(floor.amplitudeBits());
        if (amplitude <= 0 || bits.endOfPacket) {
            return null;
        }
        var bookNumber = bits.read(Ilog.ilog(floor.books().length));
        if (bits.endOfPacket || bookNumber >= floor.books().length) {
            return null;
        }
        var book = stream.books.get(floor.books()[bookNumber]);
        var tree = trees[floor.books()[bookNumber]];
        var coefficients = new float[floor.order() + 1];
        var count = 0;
        var last = 0.0f;
        while (count < floor.order()) {
            var entry = tree.decode(bits);
            if (entry < 0) {
                return null;
            }
            var vector = book.values(entry);
            for (var j = 0; j < vector.length; j++) {
                var value = vector[j] + last;
                if (count + j < floor.order()) {
                    coefficients[count + j] = value;
                }
                if (j == vector.length - 1) {
                    last = value;
                }
            }
            count += vector.length;
        }
        coefficients[floor.order()] = (float) amplitude / (float) ((1L << floor.amplitudeBits()) - 1) * floor.amplitudeOffset();
        return coefficients;
    }

    private static double bark(float x) {
        return 13.1f * Math.atan(0.00074f * x) + 2.24f * Math.atan(x * x * 1.85e-8f) + 1e-4f * x;
    }

    private static double[] renderFloor0(VorbisStreamBuilder.Floor0Spec floor, float[] coefficients, int n, boolean longBlock) {
        var map = new int[n + 1];
        var scale = (float) (floor.barkMapSize() / bark(floor.rate() / 2.0f));
        for (var i = 0; i < n; i++) {
            map[i] = Math.min(floor.barkMapSize() - 1, (int) Math.floor(bark((floor.rate() / 2.0f) / n * i) * scale));
        }
        map[n] = -1;
        var order = floor.order();
        var lsp = new float[order];
        for (var j = 0; j < order; j++) {
            lsp[j] = (float) (2.0f * Math.cos(coefficients[j]));
        }
        var amplitude = coefficients[order];
        var curve = new double[n];
        var i = 0;
        while (i < n) {
            var k = map[i];
            var w = (float) (2.0f * Math.cos((float) (Math.PI / floor.barkMapSize()) * k));
            var p = 0.5f;
            var q = 0.5f;
            var j = 1;
            for (; j < order; j += 2) {
                q *= w - lsp[j - 1];
                p *= w - lsp[j];
            }
            if (j == order) {
                q *= w - lsp[j - 1];
                p *= p * (4.0f - w * w);
                q *= q;
            } else {
                p *= p * (2.0f - w);
                q *= q * (2.0f + w);
            }
            var value = (float) Math.exp((amplitude / Math.sqrt(p + q) - floor.amplitudeOffset()) * 0.11512925f);
            do {
                curve[i++] = value;
            } while (map[i] == k);
        }
        return curve;
    }

    private double[][] decodeResidue(Bits bits, VorbisStreamBuilder.ResidueSpec residue, int ch, boolean[] skip, int n) {
        var result = new double[ch][n];
        if (residue.type() == 2) {
            var all = true;
            for (var flag : skip) {
                all &= flag;
            }
            if (all) {
                return result;
            }
            var interleaved = decodeResidueVectors(bits, residue, 1, new boolean[1], n * ch, 1)[0];
            for (var i = 0; i < n; i++) {
                for (var j = 0; j < ch; j++) {
                    result[j][i] = interleaved[i * ch + j];
                }
            }
            return result;
        }
        return decodeResidueVectors(bits, residue, ch, skip, n, residue.type());
    }

    private double[][] decodeResidueVectors(Bits bits, VorbisStreamBuilder.ResidueSpec residue, int ch, boolean[] skip,
                                            int actualSize, int format) {
        var vectors = new double[ch][actualSize];
        var limitBegin = Math.min(residue.begin(), actualSize);
        var limitEnd = Math.min(residue.end(), actualSize);
        var classbook = stream.books.get(residue.classbook());
        var classwords = classbook.dimensions;
        var nToRead = limitEnd - limitBegin;
        if (nToRead <= 0) {
            return vectors;
        }
        var partitions = nToRead / residue.partitionSize();
        var classifications = new int[ch][partitions + classwords];
        for (var pass = 0; pass < 8; pass++) {
            var partitionCount = 0;
            while (partitionCount < partitions) {
                if (pass == 0) {
                    for (var j = 0; j < ch; j++) {
                        if (!skip[j]) {
                            var temp = trees[residue.classbook()].decode(bits);
                            if (temp < 0) {
                                return vectors;
                            }
                            for (var i = classwords - 1; i >= 0; i--) {
                                classifications[j][i + partitionCount] = temp % residue.classifications();
                                temp /= residue.classifications();
                            }
                        }
                    }
                }
                for (var i = 0; i < classwords && partitionCount < partitions; i++) {
                    for (var j = 0; j < ch; j++) {
                        if (!skip[j]) {
                            var vqclass = classifications[j][partitionCount];
                            var vqbook = residue.books()[vqclass][pass];
                            if (vqbook >= 0) {
                                var offset = limitBegin + partitionCount * residue.partitionSize();
                                if (!decodePartition(bits, vqbook, vectors[j], offset, residue.partitionSize(), format)) {
                                    return vectors;
                                }
                            }
                        }
                    }
                    partitionCount++;
                }
            }
        }
        return vectors;
    }

    private boolean decodePartition(Bits bits, int bookNumber, double[] v, int offset, int n, int format) {
        var book = stream.books.get(bookNumber);
        var tree = trees[bookNumber];
        var dims = book.dimensions;
        if (format == 0) {
            var step = n / dims;
            for (var i = 0; i < step; i++) {
                var entry = tree.decode(bits);
                if (entry < 0) {
                    return false;
                }
                var temp = book.values(entry);
                for (var j = 0; j < dims; j++) {
                    v[offset + i + j * step] = (float) ((float) v[offset + i + j * step] + temp[j]);
                }
            }
            return true;
        }
        var i = 0;
        while (i < n) {
            var entry = tree.decode(bits);
            if (entry < 0) {
                return false;
            }
            var temp = book.values(entry);
            for (var j = 0; j < dims && i < n; j++) {
                v[offset + i] = (float) ((float) v[offset + i] + temp[j]);
                i++;
            }
        }
        return true;
    }
}
