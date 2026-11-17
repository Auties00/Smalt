package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.SplittableRandom;

/**
 * Writes random but syntactically valid AAC raw data blocks (ISO/IEC 14496-3 subclause 4.4.2) for every element type
 * and coding tool: long and grouped short windows, every codebook with escaped section lengths, noise and intensity
 * scalefactors, pulses, TNS, gain control (Scalable Sample Rate), Main prediction and Long Term Prediction data, M/S
 * masks, coupling channels with every gain list form, data streams, program configurations and fill elements.
 */
public final class AacBlockGenerator {
    private static final int[] LARGEST = {0, 1, 1, 2, 2, 4, 4, 7, 7, 12, 12, 16};

    private final MsbBitWriter out = new MsbBitWriter(1 << 16);
    private final SplittableRandom random;
    private final int objectType;
    private final int[] longOffsets;
    private final int[] shortOffsets;
    private final int predictionBands;
    private final int[] bandTypes = new int[8 * 64];
    private final int[] groupLengths = new int[8];
    private int windowSequence;
    private int windowCount;
    private int groupCount;
    private int maxBands;

    public AacBlockGenerator(int objectType, int samplingIndex, int frameLength, long seed) {
        var blocks = new AacRawBlocks(objectType, samplingIndex, frameLength);
        this.objectType = objectType;
        this.longOffsets = blocks.longOffsets();
        this.shortOffsets = blocks.shortOffsets();
        this.predictionBands = blocks.predictionBands();
        this.random = new SplittableRandom(seed);
    }

    /** Returns a block of random elements, at most {@code elements} of them before its end element. */
    public byte[] block(int elements) {
        out.reset();
        var count = random.nextInt(elements + 1);
        for (var element = 0; element < count; element++) {
            switch (random.nextInt(9)) {
                case 0, 1 -> single(0);
                case 2, 3 -> pair();
                case 4 -> single(3);
                case 5 -> coupling();
                case 6 -> dataStream();
                case 7 -> programConfig();
                default -> fill();
            }
        }
        out.write(7, 3);
        out.alignWithZeros();
        return out.toByteArray();
    }

    /** Returns a block of one long-window channel whose every band uses {@code codebook}, every symbol in turn. */
    public byte[] sweep(int codebook) {
        out.reset();
        out.write(0, 3);
        out.write(0, 4);
        out.write(100, 8);
        out.write(0, 1);
        out.write(0, 2);
        out.write(0, 1);
        maxBands = longOffsets.length - 1;
        out.write(maxBands, 6);
        out.write(0, 1);
        windowCount = 1;
        groupCount = 1;
        groupLengths[0] = 1;
        out.write(codebook, 4);
        writeSectionLength(maxBands, 31);
        java.util.Arrays.fill(bandTypes, 0, maxBands, codebook);
        for (var band = 0; band < maxBands; band++) {
            writeScalefactor(60);
        }
        out.write(0, 3);
        var symbols = AacHuffmanTables.SPECTRUM_CODES[codebook].length;
        var symbol = 0;
        for (var band = 0; band < maxBands; band++) {
            var lines = longOffsets[band + 1] - longOffsets[band];
            for (var codeword = 0; codeword < lines / (codebook <= 4 ? 4 : 2); codeword++) {
                writeCodeword(codebook, symbol++ % symbols);
            }
        }
        out.write(7, 3);
        out.alignWithZeros();
        return out.toByteArray();
    }

    private void single(int id) {
        out.write(id, 3);
        out.write(random.nextInt(16), 4);
        channelStream(false);
    }

    private void pair() {
        out.write(1, 3);
        out.write(random.nextInt(16), 4);
        var common = random.nextBoolean();
        out.write(common ? 1 : 0, 1);
        if (common) {
            info(true);
            var mask = random.nextInt(3);
            out.write(mask, 2);
            if (mask == 1) {
                for (var bit = 0; bit < groupCount * maxBands; bit++) {
                    out.write(random.nextInt(2), 1);
                }
            }
        }
        channelStream(common);
        channelStream(common);
    }

    private void coupling() {
        out.write(2, 3);
        out.write(random.nextInt(16), 4);
        var independent = random.nextBoolean();
        out.write(independent ? 1 : 0, 1);
        var targets = random.nextInt(8);
        out.write(targets, 3);
        var lists = 0;
        for (var target = 0; target <= targets; target++) {
            lists++;
            var pair = random.nextBoolean();
            out.write(pair ? 1 : 0, 1);
            out.write(random.nextInt(16), 4);
            if (pair) {
                var select = random.nextInt(4);
                out.write(select, 2);
                if (select == 3) {
                    lists++;
                }
            }
        }
        out.write(random.nextInt(16), 4);
        channelStream(false);
        for (var list = 1; list < lists; list++) {
            var common = independent || random.nextBoolean();
            if (!independent) {
                out.write(common ? 1 : 0, 1);
            }
            if (common) {
                writeScalefactor(random.nextInt(121));
                continue;
            }
            for (var group = 0; group < groupCount; group++) {
                for (var band = 0; band < maxBands; band++) {
                    if (bandTypes[group * 64 + band] != 0) {
                        writeScalefactor(random.nextInt(121));
                    }
                }
            }
        }
    }

    private void dataStream() {
        out.write(4, 3);
        out.write(random.nextInt(16), 4);
        var align = random.nextBoolean();
        out.write(align ? 1 : 0, 1);
        var count = random.nextInt(4) == 0 ? 255 + random.nextInt(256) : random.nextInt(255);
        out.write(Math.min(count, 255), 8);
        if (count >= 255) {
            out.write(count - 255, 8);
        }
        if (align) {
            out.alignWithZeros();
        }
        bytes(count);
    }

    private void programConfig() {
        out.write(5, 3);
        out.write(random.nextInt(16), 4);
        out.write(random.nextInt(4), 2);
        out.write(random.nextInt(13), 4);
        var front = random.nextInt(16);
        var side = random.nextInt(16);
        var back = random.nextInt(16);
        var lfe = random.nextInt(4);
        var assoc = random.nextInt(8);
        var cc = random.nextInt(16);
        out.write(front, 4);
        out.write(side, 4);
        out.write(back, 4);
        out.write(lfe, 2);
        out.write(assoc, 3);
        out.write(cc, 4);
        for (var mixdown = 0; mixdown < 2; mixdown++) {
            var present = random.nextBoolean();
            out.write(present ? 1 : 0, 1);
            if (present) {
                out.write(random.nextInt(16), 4);
            }
        }
        var matrix = random.nextBoolean();
        out.write(matrix ? 1 : 0, 1);
        if (matrix) {
            out.write(random.nextInt(8), 3);
        }
        for (var element = 0; element < front + side + back; element++) {
            out.write(random.nextInt(32), 5);
        }
        for (var element = 0; element < lfe + assoc; element++) {
            out.write(random.nextInt(16), 4);
        }
        for (var element = 0; element < cc; element++) {
            out.write(random.nextInt(32), 5);
        }
        out.alignWithZeros();
        var comment = random.nextInt(40);
        out.write(comment, 8);
        bytes(comment);
    }

    private void fill() {
        out.write(6, 3);
        var count = random.nextInt(3) == 0 ? 15 + random.nextInt(255) : random.nextInt(15);
        if (count >= 15) {
            out.write(15, 4);
            out.write(count - 14, 8);
        } else {
            out.write(count, 4);
        }
        bytes(count);
    }

    private void bytes(int count) {
        for (var index = 0; index < count; index++) {
            out.write(random.nextInt(256), 8);
        }
    }

    private void channelStream(boolean common) {
        out.write(random.nextInt(256), 8);
        if (!common) {
            info(false);
        }
        sections();
        scalefactors();
        var pulses = windowCount == 1 && random.nextInt(4) == 0;
        out.write(pulses ? 1 : 0, 1);
        if (pulses) {
            var count = random.nextInt(4);
            out.write(count, 2);
            out.write(random.nextInt(64), 6);
            for (var pulse = 0; pulse <= count; pulse++) {
                out.write(random.nextInt(32), 5);
                out.write(random.nextInt(16), 4);
            }
        }
        var tns = random.nextBoolean();
        out.write(tns ? 1 : 0, 1);
        if (tns) {
            tns();
        }
        var gain = objectType == AacRawBlocks.AOT_SSR && random.nextBoolean();
        out.write(gain ? 1 : 0, 1);
        if (gain) {
            gainControl();
        }
        spectralData();
    }

    private void info(boolean common) {
        out.write(0, 1);
        windowSequence = random.nextInt(4);
        out.write(windowSequence, 2);
        out.write(random.nextInt(2), 1);
        if (windowSequence == 2) {
            maxBands = random.nextInt(shortOffsets.length);
            out.write(maxBands, 4);
            var grouping = random.nextInt(128);
            out.write(grouping, 7);
            windowCount = 8;
            groupCount = 1;
            groupLengths[0] = 1;
            for (var window = 6; window >= 0; window--) {
                if ((grouping >>> window & 1) != 0) {
                    groupLengths[groupCount - 1]++;
                } else {
                    groupLengths[groupCount++] = 1;
                }
            }
            return;
        }
        maxBands = random.nextInt(longOffsets.length);
        out.write(maxBands, 6);
        windowCount = 1;
        groupCount = 1;
        groupLengths[0] = 1;
        var predictor = (objectType == AacRawBlocks.AOT_MAIN || objectType == AacRawBlocks.AOT_LTP) && random.nextBoolean();
        out.write(predictor ? 1 : 0, 1);
        if (!predictor) {
            return;
        }
        if (objectType == AacRawBlocks.AOT_MAIN) {
            var reset = random.nextBoolean();
            out.write(reset ? 1 : 0, 1);
            if (reset) {
                out.write(random.nextInt(32), 5);
            }
            for (var band = 0; band < Math.min(maxBands, predictionBands); band++) {
                out.write(random.nextInt(2), 1);
            }
            return;
        }
        for (var channel = 0; channel < (common ? 2 : 1); channel++) {
            var present = random.nextBoolean();
            out.write(present ? 1 : 0, 1);
            if (present) {
                out.write(random.nextInt(2048), 11);
                out.write(random.nextInt(8), 3);
                for (var band = 0; band < Math.min(maxBands, 40); band++) {
                    out.write(random.nextInt(2), 1);
                }
            }
        }
    }

    private void sections() {
        var bits = windowCount == 8 ? 3 : 5;
        var escape = (1 << bits) - 1;
        for (var group = 0; group < groupCount; group++) {
            var band = 0;
            while (band < maxBands) {
                var codebook = random.nextInt(15);
                if (codebook >= 12) {
                    codebook++;
                }
                var length = 1 + random.nextInt(maxBands - band);
                out.write(codebook, 4);
                writeSectionLength(length, escape);
                java.util.Arrays.fill(bandTypes, group * 64 + band, group * 64 + band + length, codebook);
                band += length;
            }
        }
    }

    private void writeSectionLength(int length, int escape) {
        var bits = Integer.bitCount(escape);
        var rest = length;
        while (rest >= escape) {
            out.write(escape, bits);
            rest -= escape;
        }
        out.write(rest, bits);
    }

    private void scalefactors() {
        var firstNoise = true;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                var codebook = bandTypes[group * 64 + band];
                if (codebook == 0) {
                    continue;
                }
                if (codebook == 13 && firstNoise) {
                    firstNoise = false;
                    out.write(random.nextInt(512), 9);
                } else {
                    writeScalefactor(random.nextInt(121));
                }
            }
        }
    }

    private void writeScalefactor(int symbol) {
        out.write(AacHuffmanTables.SCALEFACTOR_CODES[symbol], AacHuffmanTables.SCALEFACTOR_LENGTHS[symbol]);
    }

    private void tns() {
        var shortWindow = windowCount == 8;
        for (var window = 0; window < windowCount; window++) {
            var filters = random.nextInt(shortWindow ? 2 : 4);
            out.write(filters, shortWindow ? 1 : 2);
            if (filters == 0) {
                continue;
            }
            var resolution = random.nextInt(2);
            out.write(resolution, 1);
            for (var filter = 0; filter < filters; filter++) {
                out.write(random.nextInt(shortWindow ? 16 : 64), shortWindow ? 4 : 6);
                var order = random.nextInt(shortWindow ? 8 : 32);
                out.write(order, shortWindow ? 3 : 5);
                if (order != 0) {
                    out.write(random.nextInt(2), 1);
                    var compress = random.nextInt(2);
                    out.write(compress, 1);
                    var bits = resolution + 3 - compress;
                    for (var coefficient = 0; coefficient < order; coefficient++) {
                        out.write(random.nextInt(1 << bits), bits);
                    }
                }
            }
        }
    }

    private void gainControl() {
        var bands = random.nextInt(4);
        out.write(bands, 2);
        int windows;
        int first;
        int other;
        switch (windowSequence) {
            case 0 -> {
                windows = 1;
                first = 5;
                other = 5;
            }
            case 1 -> {
                windows = 2;
                first = 4;
                other = 2;
            }
            case 2 -> {
                windows = 8;
                first = 2;
                other = 2;
            }
            default -> {
                windows = 2;
                first = 4;
                other = 5;
            }
        }
        for (var band = 1; band <= bands; band++) {
            for (var window = 0; window < windows; window++) {
                var adjustments = random.nextInt(8);
                out.write(adjustments, 3);
                for (var adjustment = 0; adjustment < adjustments; adjustment++) {
                    out.write(random.nextInt(16), 4);
                    var bits = window == 0 ? first : other;
                    out.write(random.nextInt(1 << bits), bits);
                }
            }
        }
    }

    private void spectralData() {
        var offsets = windowCount == 8 ? shortOffsets : longOffsets;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                var codebook = bandTypes[group * 64 + band];
                if (codebook == 0 || codebook > 11) {
                    continue;
                }
                var lines = (offsets[band + 1] - offsets[band]) * groupLengths[group];
                var symbols = AacHuffmanTables.SPECTRUM_CODES[codebook].length;
                for (var codeword = 0; codeword < lines / (codebook <= 4 ? 4 : 2); codeword++) {
                    writeCodeword(codebook, random.nextInt(symbols));
                }
            }
        }
    }

    private void writeCodeword(int codebook, int symbol) {
        out.write(AacHuffmanTables.SPECTRUM_CODES[codebook][symbol], AacHuffmanTables.SPECTRUM_LENGTHS[codebook][symbol]);
        var unsigned = codebook >= 3 && codebook != 5 && codebook != 6;
        if (!unsigned) {
            return;
        }
        var modulus = LARGEST[codebook] + 1;
        int[] values = codebook <= 4
                ? new int[]{symbol / 27, symbol / 9 % 3, symbol / 3 % 3, symbol % 3}
                : new int[]{symbol / modulus, symbol % modulus};
        for (var value : values) {
            if (value != 0) {
                out.write(random.nextInt(2), 1);
            }
        }
        if (codebook == 11) {
            for (var value : values) {
                if (value == 16) {
                    var prefix = random.nextInt(9);
                    for (var one = 0; one < prefix; one++) {
                        out.write(1, 1);
                    }
                    out.write(0, 1);
                    out.write(random.nextInt(1 << (prefix + 4)), prefix + 4);
                }
            }
        }
    }
}
