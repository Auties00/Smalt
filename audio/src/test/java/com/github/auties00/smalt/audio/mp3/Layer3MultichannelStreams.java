package com.github.auties00.smalt.audio.mp3;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds synthetic MPEG-1 Layer III streams at 48 kHz carrying the ISO/IEC 13818-3 multichannel extension, written
 * from the syntax of the standard, for the decoder tests and the fuzz seeds. Spectral lines are random small integers
 * coded in the coded order with Huffman table 15, and {@link #plain} writes the same lines to an ordinary stereo stream,
 * so a test compares an output channel with the plain decoding of the lines it should hold. Lines live in arrays of 576
 * in the coded order.
 */
final class Layer3MultichannelStreams {
    /** The transmission channel numbers of the left, right, center and surround channels. */
    static final int L = 2, R = 3, C = 4, LS = 5, RS = 6;

    /** The first band of each long block band group. */
    static final int[] GROUPS_LONG = {0, 3, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 22};

    /** The first band of each short block band group. */
    static final int[] GROUPS_SHORT = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13};

    /** The bytes of a frame after its header and side information, at 320 kbit/s and 48 kHz. */
    private static final int PAYLOAD = 960 - 4 - 32;

    /** The index of the band tables of MPEG-1 at 48 kHz. */
    private static final int TABLE_48K = 6;

    /** The Huffman table of every region. */
    private static final int TABLE = 15;

    private Layer3MultichannelStreams() {
    }

    /** One segment of a segment list. */
    record Segment(int length, int select, int... attenuations) {
    }

    /**
     * A segment list.
     *
     * @param range the {@code attenuation_range}
     * @param scale the {@code attenuation_scale}
     * @param segments the segments
     */
    record SegmentList(int range, int scale, List<Segment> segments) {
        /** The list with no transmitted data: every band group in the channel's own transmission channel. */
        static final SegmentList DEFAULT = new SegmentList(0, 0, List.of());
    }

    /** A stream's configuration; a channel with a {@code null} segment list is dematrixed. */
    static final class Configuration {
        int center = 1;
        int surround = 2;
        boolean lfe;
        int procedure;
        int dematrixLength;
        int[] dematrixSelect = new int[0];
        final SegmentList[][] lists = new SegmentList[2][7];
        final boolean[] tcPresent = new boolean[7];
        /** The scalefactor bits, {@code scalefac_compress}, 0 for none. */
        int compress;
        int preflag;
        int scalefacScale;
        /** The {@code scfsi} of every transmission channel, 4 bits. */
        int scfsi;
        /** Whether the second granule's segment lists are sent as a repeat of the first's. */
        boolean repeatLists;
        /** The prediction coefficient index per band group, channel and source, or -1 when none is sent; or null. */
        int[][][] predictors;
        /** The matrix attenuations per granule, channel, band group and equation, or null. */
        int[][][][] matrix;
        /** Carries the extension's main data past the base frame in an extension frame. */
        boolean extensionFrame;
        /** The bytes of each frame's end that hold the next frame's main data, from the second frame on. */
        int reservoir;
        /** Lets the extension's main data start in the free bytes of the frame before, through {@code mc_data_begin}. */
        boolean mcReservoir;
        /** The three {@code subblock_gain} values of short granules, 3 bits each. */
        int subblockGain;
        /** Sets {@code mixed_block_flag} on the short granules of the base channels. */
        boolean baseMixed;
        /** Writes a single channel base from channel 0 of the base lines. */
        boolean monoBase;
        /** The block type of a frame's granule. */
        BlockTypes blockTypes = (frame, granule) -> 0;
        int globalGain = 160;
        /** The number of multilingual channels. */
        int multilingual;
        /** Whether the multilingual channels are at half the sampling rate. */
        boolean multilingualHalfRate;
        /** The multilingual channels' lines by frame, granule and channel. */
        int[][][][] multilingualLines;
        /** The key of every scalefactor, or -1 for one that differs per channel. */
        int scalefactorKey = -1;

        Configuration() {
            Arrays.fill(tcPresent, L, RS + 1, true);
        }

        /** Sets a channel's segment list in both granules. */
        Configuration list(int ch, SegmentList list) {
            lists[0][ch] = list;
            lists[1][ch] = list;
            return this;
        }

        /** Dematrixes a channel: no segment list and no transmission channel. */
        Configuration dematrixed(int ch) {
            tcPresent[ch] = false;
            return list(ch, null);
        }

        /** Gives every channel the default segment list. */
        Configuration defaults() {
            for (var ch = L; ch <= RS; ch++) {
                list(ch, SegmentList.DEFAULT);
            }
            return this;
        }

        boolean channelPresent(int ch) {
            return switch (ch) {
                case L, R -> !monoBase;
                case C -> center != 0;
                case LS -> surround != 0;
                default -> surround >= 2;
            };
        }

        /** Whether a band group of a channel takes its data from a transmission channel, in granule order. */
        int source(int granule, int ch, int group) {
            if (group < dematrixLength) {
                return (dematrixSelect[group] & (1 << (ch - L))) != 0 ? 7 : ch;
            }
            var list = lists[granule][ch];
            if (list == null) {
                return 7;
            }
            if (list == SegmentList.DEFAULT) {
                return ch;
            }
            var at = dematrixLength;
            for (var segment : list.segments()) {
                if (segment.length() == 0) {
                    return -1;
                }
                if (group < at + segment.length()) {
                    return segment.select();
                }
                at += segment.length();
            }
            return -1;
        }

        /** Whether a transmission channel codes a band group. */
        boolean codes(int granule, int tc, int group, int blockType) {
            if (!tcPresent[tc]) {
                return false;
            }
            for (var ch = L; ch <= RS; ch++) {
                if (channelPresent(ch) && group < groupCount(blockType) && source(granule, ch, group) == tc) {
                    return true;
                }
            }
            return false;
        }

        boolean carries(int granule, int tc, int group, int blockType) {
            for (var ch = L; ch <= RS; ch++) {
                if (ch != tc && channelPresent(ch) && group < groupCount(blockType)
                        && source(granule, ch, group) == tc && tcPresent[tc]) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Gives the block type of a frame's granule. */
    interface BlockTypes {
        int of(int frame, int granule);
    }

    /** A bit buffer. */
    static final class Bits {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int current;
        private int count;
        int size;

        void write(int value, int width) {
            for (var i = width - 1; i >= 0; i--) {
                current = current << 1 | ((value >>> i) & 1);
                size++;
                if (++count == 8) {
                    bytes.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        void append(Bits other) {
            var data = other.toBytes();
            for (var i = 0; i < other.size; i++) {
                write((data[i >> 3] >> (7 - (i & 7))) & 1, 1);
            }
        }

        void align() {
            while (count != 0) {
                write(0, 1);
            }
        }

        byte[] toBytes() {
            var data = bytes.toByteArray();
            if (count == 0) {
                return data;
            }
            var out = Arrays.copyOf(data, data.length + 1);
            out[data.length] = (byte) (current << (8 - count));
            return out;
        }
    }

    static int groupCount(int blockType) {
        return blockType == 2 ? 12 : 15;
    }

    /** The line where each band group starts, ending with the end of the last. */
    static int[] groupLines(int blockType) {
        var groups = blockType == 2 ? GROUPS_SHORT : GROUPS_LONG;
        var starts = new int[groups.length];
        var line = 0;
        for (var group = 0; group + 1 < groups.length; group++) {
            starts[group] = line;
            for (var band = groups[group]; band < groups[group + 1]; band++) {
                line += blockType == 2 ? 3 * Mp3Tables.SFB_SHORT[TABLE_48K][3 * band]
                        : Mp3Tables.SFB_LONG[TABLE_48K][band];
            }
        }
        starts[groups.length - 1] = line;
        return starts;
    }

    /** Returns random lines, the first {@code count} of 576 nonzero at most. */
    static int[] randomLines(SplittableRandom random, int count) {
        var lines = new int[576];
        for (var i = 0; i < count; i++) {
            lines[i] = random.nextInt(-4, 5);
        }
        return lines;
    }

    /** Keeps only the lines of the band groups a filter accepts. */
    static int[] masked(int[] lines, int blockType, java.util.function.IntPredicate groups) {
        var starts = groupLines(blockType);
        var out = new int[576];
        for (var group = 0; group + 1 < starts.length; group++) {
            if (groups.test(group)) {
                System.arraycopy(lines, starts[group], out, starts[group], starts[group + 1] - starts[group]);
            }
        }
        return out;
    }

    /** Returns the number of values up to the last nonzero one, even. */
    private static int span(int[] values, int count) {
        var last = count;
        while (last > 0 && values[last - 1] == 0) {
            last--;
        }
        return Math.min(count, last + 1) & ~1;
    }

    /** Writes the Huffman data of an array of values, returning the pairs written. */
    private static int writeHuffman(Bits out, int[] values, int count) {
        var codes = Mp3EncoderTables.PAIR_CODES[TABLE];
        var lengths = Mp3EncoderTables.PAIR_LENGTHS[TABLE];
        for (var k = 0; k < count; k += 2) {
            var x = values[k];
            var y = values[k + 1];
            var ax = Math.abs(x);
            var ay = Math.abs(y);
            out.write(codes[ax << 4 | ay], lengths[ax << 4 | ay]);
            if (ax != 0) {
                out.write(x < 0 ? 1 : 0, 1);
            }
            if (ay != 0) {
                out.write(y < 0 ? 1 : 0, 1);
            }
        }
        return count / 2;
    }

    /** Writes the granule fields shared by the base and extension side information, after the gains. */
    private static void writeTables(Bits out, int blockType, boolean base, boolean mixed, int subblockGain, int preflag,
                                    int scale) {
        if (base) {
            out.write(blockType != 0 ? 1 : 0, 1);
        }
        if (blockType != 0) {
            if (base) {
                out.write(blockType, 2);
                out.write(mixed ? 1 : 0, 1);
            }
            out.write(TABLE, 5);
            out.write(TABLE, 5);
            if (base || blockType == 2) {
                out.write(blockType == 2 ? subblockGain : 0, 9);
            }
        } else {
            out.write(TABLE, 5);
            out.write(TABLE, 5);
            out.write(TABLE, 5);
            out.write(0, 4);
            out.write(0, 3);
        }
        out.write(preflag, 1);
        out.write(scale, 1);
        out.write(0, 1);
    }

    /** Writes an ordinary stereo frame with the given lines, frame after frame; its side information and Huffman. */
    private static byte[] frameHeader(int privateBit) {
        // MPEG-1 Layer III, no CRC, 320 kbit/s, 48 kHz, stereo
        return new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) (0xE4 | privateBit), 0x00};
    }

    /**
     * Writes plain stereo frames.
     *
     * @param lines the lines by frame, granule and channel
     * @param configuration gives the global gain and block types
     */
    static List<byte[]> plain(int[][][][] lines, Configuration configuration) {
        var frames = new ArrayList<byte[]>();
        for (var frame = 0; frame < lines.length; frame++) {
            var side = new Bits();
            var main = new Bits();
            writeBaseGranules(side, main, lines[frame], frame, configuration, 0);
            frames.add(assemble(side, main.toBytes()));
        }
        return frames;
    }

    /** The scalefactor of a band in a test with scalefactors. */
    private static int scalefactor(int tc, int granule, int band, Configuration configuration) {
        var width = band < 11 ? Mp3Tables.SCFC_DECODE[configuration.compress] >> 2
                : Mp3Tables.SCFC_DECODE[configuration.compress] & 3;
        var channel = configuration.scalefactorKey >= 0 ? configuration.scalefactorKey : tc;
        // three bits at most, which every length of the configurations here holds
        return width == 0 ? 0 : (band * 5 + channel * 3 + (configuration.scfsi != 0 ? 0 : granule))
                & Math.min(7, (1 << width) - 1);
    }

    /** Writes the scalefactors of a granule of a channel, of the bands a filter accepts. */
    private static void writeScalefactors(Bits out, int ch, int granule, int type, int scfsi, Configuration configuration,
                                          java.util.function.IntPredicate groupCoded) {
        if (configuration.compress == 0) {
            return;
        }
        var coded = 0;
        if (type == 2) {
            for (var band = 0; band < 12; band++) {
                if (!groupCoded.test(band)) {
                    continue;
                }
                var width = coded < 6 ? Mp3Tables.SCFC_DECODE[configuration.compress] >> 2
                        : Mp3Tables.SCFC_DECODE[configuration.compress] & 3;
                coded++;
                for (var window = 0; window < 3 && width > 0; window++) {
                    var channel = configuration.scalefactorKey >= 0 ? configuration.scalefactorKey : ch;
                    out.write((band * 5 + channel * 3 + granule + window) & Math.min(7, (1 << width) - 1), width);
                }
            }
            return;
        }
        for (var band = 0; band < 21; band++) {
            var scfsiGroup = band < 6 ? 0 : band < 11 ? 1 : band < 16 ? 2 : 3;
            var group = band < 9 ? band / 3 : Math.min(band - 6, 14);
            var transmitted = groupCoded.test(group);
            var width = coded < 11 ? Mp3Tables.SCFC_DECODE[configuration.compress] >> 2
                    : Mp3Tables.SCFC_DECODE[configuration.compress] & 3;
            if (transmitted) {
                coded++;
            }
            if (granule == 1 && (scfsi & (8 >> scfsiGroup)) != 0) {
                continue;
            }
            if (transmitted && width > 0) {
                out.write(scalefactor(ch, granule, band, configuration), width);
            }
        }
    }

    private static void writeBaseGranules(Bits side, Bits main, int[][][] lines, int frame, Configuration configuration,
                                          int mainDataBegin) {
        side.write(mainDataBegin, 9);
        var scfsi = configuration.compress != 0 ? configuration.scfsi : 0;
        var channels = configuration.monoBase ? 1 : 2;
        if (configuration.monoBase) {
            side.write(0, 5);
            side.write(scfsi, 4);
        } else {
            side.write(0, 3);
            side.write(scfsi << 4 | scfsi, 8);
        }
        for (var granule = 0; granule < 2; granule++) {
            var type = configuration.blockTypes.of(frame, granule);
            for (var ch = 0; ch < channels; ch++) {
                var data = new Bits();
                writeScalefactors(data, ch, granule, type, scfsi, configuration, group -> true);
                var pairs = writeHuffman(data, lines[granule][ch], span(lines[granule][ch], 576));
                side.write(data.size, 12);
                side.write(pairs, 9);
                side.write(configuration.globalGain, 8);
                side.write(configuration.compress, 4);
                writeTables(side, type, true, configuration.baseMixed && type == 2, configuration.subblockGain,
                        configuration.preflag, configuration.scalefacScale);
                main.append(data);
            }
        }
    }

    /** Joins a frame's header, side information and main data, filling the frame. */
    private static byte[] assemble(Bits side, byte[] main) {
        if (main.length > PAYLOAD) {
            throw new IllegalArgumentException("main data of " + main.length + " bytes does not fit");
        }
        var out = new byte[960];
        System.arraycopy(frameHeader(0), 0, out, 0, 4);
        System.arraycopy(side.toBytes(), 0, out, 4, 32);
        System.arraycopy(main, 0, out, 36, main.length);
        return out;
    }

    private static boolean predictable(Configuration configuration, int frame, int group, int ch, int from) {
        var both = ch == C || (ch == LS && configuration.surround == 1);
        var possible = false;
        for (var granule = 0; granule < 2; granule++) {
            var type = configuration.blockTypes.of(frame, granule);
            if (configuration.codes(granule, ch, group, type)) {
                possible = true;
                both |= configuration.carries(granule, ch, group, type);
            }
        }
        return possible && (both || from == (ch == L || ch == LS ? 0 : 1));
    }

    /** Writes everything of an extension after {@code mc_crc_check}, up to the end of its side information. */
    private static void writeStatusAndSide(Bits out, Configuration configuration, int frame, int mcDataBegin,
                                           int[][][][] coded, int[][] lfe, int[][] lengths) {
        out.write(mcDataBegin, 11);
        var types = new int[]{configuration.blockTypes.of(frame, 0), configuration.blockTypes.of(frame, 1)};
        for (var granule = 0; granule < 2; granule++) {
            for (var ch = L; ch <= R; ch++) {
                writeFlags(out, configuration, granule, ch, types[granule]);
            }
        }
        if (configuration.center != 0) {
            for (var granule = 0; granule < 2; granule++) {
                writeFlags(out, configuration, granule, C, types[granule]);
            }
        }
        if (configuration.surround == 1) {
            for (var granule = 0; granule < 2; granule++) {
                writeFlags(out, configuration, granule, LS, types[granule]);
            }
        } else if (configuration.surround >= 2) {
            for (var granule = 0; granule < 2; granule++) {
                writeFlags(out, configuration, granule, LS, types[granule]);
                writeFlags(out, configuration, granule, RS, types[granule]);
            }
        }
        if (configuration.procedure != 3) {
            out.write(configuration.dematrixLength, 4);
        }
        var selectBits = configuration.surround == 3
                || !(configuration.center != 0 && configuration.surround != 0 || configuration.surround == 2) ? 3 : 4;
        for (var group = 0; group < configuration.dematrixLength; group++) {
            out.write(selectValue(configuration.dematrixSelect[group], selectBits, configuration), selectBits);
        }
        var firstSent = new boolean[7];
        for (var granule = 0; granule < 2; granule++) {
            for (var ch = L; ch <= RS; ch++) {
                if (!configuration.channelPresent(ch) || configuration.lists[granule][ch] == null) {
                    continue;
                }
                var list = configuration.lists[granule][ch];
                if (list == SegmentList.DEFAULT) {
                    out.write(0, 1);
                    continue;
                }
                out.write(1, 1);
                if (granule == 1 && firstSent[ch]) {
                    if (configuration.repeatLists) {
                        out.write(1, 1);
                        continue;
                    }
                    out.write(0, 1);
                }
                writeList(out, list, ch, types[granule]);
                if (granule == 0) {
                    firstSent[ch] = true;
                }
            }
        }
        writePrediction(out, configuration, frame);
        if (configuration.procedure != 3) {
            out.write(configuration.matrix != null ? 1 : 0, 1);
        }
        if (configuration.matrix != null) {
            for (var granule = 0; granule < 2; granule++) {
                for (var ch = L; ch <= RS; ch++) {
                    for (var group = configuration.dematrixLength; group < groupCount(types[granule]); group++) {
                        if (configuration.carries(granule, ch, group, types[granule])) {
                            out.write(configuration.matrix[granule][ch][group][0], 3);
                            out.write(configuration.matrix[granule][ch][group][1], 3);
                        }
                    }
                }
            }
        }
        for (var tc = L; tc <= RS; tc++) {
            if (configuration.tcPresent[tc] && configuration.channelPresent(tc)) {
                out.write(configuration.scfsi, 4);
            }
        }
        for (var granule = 0; granule < 2; granule++) {
            for (var tc = L; tc <= RS; tc++) {
                if (configuration.tcPresent[tc] && configuration.channelPresent(tc)) {
                    out.write(lengths[granule][tc], 12);
                    out.write(coded[granule][tc][0].length / 2, 9);
                    out.write(configuration.globalGain, 8);
                    out.write(configuration.compress, 4);
                    writeTables(out, types[granule], false, false, configuration.subblockGain, configuration.preflag,
                            configuration.scalefacScale);
                }
            }
        }
        if (configuration.lfe) {
            out.write(lfe[0][0], 8);
            out.write(configuration.globalGain + 30, 8);
            out.write(TABLE, 5);
        }
        if (configuration.multilingual > 0) {
            var halfRate = configuration.multilingualHalfRate;
            if (!halfRate) {
                out.write(0, 4 * configuration.multilingual);
            }
            for (var granule = 0; granule < (halfRate ? 1 : 2); granule++) {
                for (var ch = 0; ch < configuration.multilingual; ch++) {
                    var values = configuration.multilingualLines[frame][granule][ch];
                    var data = new Bits();
                    var pairs = writeHuffman(data, values, span(values, 576));
                    writeMonoSide(out, data.size, pairs, configuration.globalGain, halfRate);
                }
            }
        }
    }

    /** Writes one granule of one channel of an MPEG-1 or lower sampling frequency side information. */
    private static void writeMonoSide(Bits out, int length, int pairs, int gain, boolean lowRate) {
        out.write(length, 12);
        out.write(pairs, 9);
        out.write(gain, 8);
        out.write(0, lowRate ? 9 : 4);
        out.write(0, 1);
        out.write(TABLE, 5);
        out.write(TABLE, 5);
        out.write(TABLE, 5);
        out.write(0, 4);
        out.write(0, 3);
        if (!lowRate) {
            out.write(0, 1);
        }
        out.write(0, 2);
    }

    /**
     * Writes plain stereo frames at half the sampling rate, the form multilingual channels at half the rate take: MPEG-2
     * Layer III, 24 kHz, one granule per frame.
     *
     * @param lines the lines by frame and channel
     * @param gain the global gain
     */
    static List<byte[]> plainHalfRate(int[][][] lines, int gain) {
        var frames = new ArrayList<byte[]>();
        for (var channels : lines) {
            var side = new Bits();
            var main = new Bits();
            side.write(0, 8);
            side.write(0, 2);
            for (var ch = 0; ch < 2; ch++) {
                var data = new Bits();
                var pairs = writeHuffman(data, channels[ch], span(channels[ch], 576));
                writeMonoSide(side, data.size, pairs, gain, true);
                main.append(data);
            }
            var out = new byte[480];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xF3;
            out[2] = (byte) 0xE4;
            System.arraycopy(side.toBytes(), 0, out, 4, 17);
            System.arraycopy(main.toBytes(), 0, out, 21, main.toBytes().length);
            frames.add(out);
        }
        return frames;
    }

    private static int selectValue(int mask, int bits, Configuration configuration) {
        if (bits == 4) {
            int[] table = {0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 17, 20, 24, 9};
            for (var value = 0; value < table.length; value++) {
                if (table[value] == mask) {
                    return value;
                }
            }
            throw new IllegalArgumentException("mask " + mask);
        }
        var third = mask & (configuration.center != 0 ? 4 : 8);
        var key = (mask & 3) | (third != 0 ? 4 : 0);
        return switch (key) {
            case 0 -> 0;
            case 1 -> 1;
            case 3 -> 2;
            case 4 -> 3;
            case 5 -> 4;
            case 6 -> 5;
            default -> throw new IllegalArgumentException("mask " + mask);
        };
    }

    private static void writeFlags(Bits out, Configuration configuration, int granule, int ch, int type) {
        out.write(configuration.lists[granule][ch] != null ? 1 : 0, 1);
        out.write(configuration.tcPresent[ch] ? 1 : 0, 1);
        out.write(type, 2);
    }

    private static void writeList(Bits out, SegmentList list, int ch, int type) {
        out.write(list.range(), 2);
        out.write(list.scale(), 1);
        for (var segment : list.segments()) {
            out.write(segment.length(), 4);
            if (segment.length() == 0) {
                return;
            }
            out.write(segment.select(), 3);
            for (var attenuation : segment.attenuations()) {
                out.write(attenuation, list.range() + 2);
            }
        }
    }

    private static void writePrediction(Bits out, Configuration configuration, int frame) {
        var predictors = configuration.predictors;
        out.write(predictors != null ? 1 : 0, 1);
        if (predictors == null) {
            return;
        }
        for (var group = 0; group < 15; group++) {
            out.write(predictors[group] != null ? 1 : 0, 1);
        }
        for (var group = 0; group < 15; group++) {
            if (predictors[group] == null) {
                continue;
            }
            for (var ch = L; ch <= RS; ch++) {
                for (var from = 0; from < 2; from++) {
                    if (predictable(configuration, frame, group, ch, from)) {
                        out.write(predictors[group][ch][from] >= 0 ? 1 : 0, 1);
                    }
                }
            }
        }
        for (var group = 0; group < 15; group++) {
            if (predictors[group] == null) {
                continue;
            }
            for (var ch = L; ch <= RS; ch++) {
                for (var from = 0; from < 2; from++) {
                    if (predictable(configuration, frame, group, ch, from) && predictors[group][ch][from] >= 0) {
                        out.write(predictors[group][ch][from], 3);
                    }
                }
            }
        }
    }

    /**
     * Builds a multichannel stream.
     *
     * @param configuration the configuration
     * @param base the lines of the base channels by frame, granule and channel
     * @param channels the lines of the transmission channels by frame, granule and channel number
     * @param lfe the low-frequency enhancement values by frame, granule and line, or null
     * @return the packets: each frame, followed by its extension frame when it has one
     */
    static List<byte[]> build(Configuration configuration, int[][][][] base, int[][][][] channels, int[][][] lfe) {
        var packets = new ArrayList<byte[]>();
        var mcDataBegin = 0;
        var nextEarly = 0;
        for (var frame = 0; frame < base.length; frame++) {
            var coded = new int[2][7][][];
            var main = new Bits();
            var lengths = new int[2][7];
            for (var granule = 0; granule < 2; granule++) {
                var type = configuration.blockTypes.of(frame, granule);
                for (var tc = L; tc <= RS; tc++) {
                    if (!configuration.tcPresent[tc] || !configuration.channelPresent(tc)) {
                        coded[granule][tc] = new int[][]{new int[0]};
                        continue;
                    }
                    var values = new int[576];
                    var count = 0;
                    var starts = groupLines(type);
                    for (var group = 0; group + 1 < starts.length; group++) {
                        if (configuration.codes(granule, tc, group, type)) {
                            System.arraycopy(channels[frame][granule][tc], starts[group], values, count,
                                    starts[group + 1] - starts[group]);
                            count += starts[group + 1] - starts[group];
                        }
                    }
                    count = span(values, count);
                    coded[granule][tc] = new int[][]{Arrays.copyOf(values, count)};
                    var data = new Bits();
                    final var granuleFinal = granule;
                    final var tcFinal = tc;
                    writeScalefactors(data, tc, granule, type, configuration.scfsi, configuration,
                            group -> configuration.codes(granuleFinal, tcFinal, group, type));
                    writeHuffman(data, values, count);
                    lengths[granule][tc] = data.size;
                    main.append(data);
                }
            }
            var lfeLengths = new int[1][1];
            if (configuration.lfe) {
                var data = new Bits();
                var pairs = new int[12];
                for (var line = 0; line < 6; line++) {
                    pairs[2 * line] = lfe[frame][0][line];
                    pairs[2 * line + 1] = lfe[frame][1][line];
                }
                writeHuffman(data, pairs, 12);
                lfeLengths[0][0] = data.size;
                main.append(data);
            }
            for (var granule = 0; granule < (configuration.multilingualHalfRate ? 1 : 2)
                    && configuration.multilingual > 0; granule++) {
                for (var ch = 0; ch < configuration.multilingual; ch++) {
                    var values = configuration.multilingualLines[frame][granule][ch];
                    writeHuffman(main, values, span(values, 576));
                }
            }
            main.align();
            var status = new Bits();
            writeStatusAndSide(status, configuration, frame, mcDataBegin, coded, lfeLengths, lengths);
            var header = new Bits();
            header.write(configuration.extensionFrame ? 1 : 0, 1);
            header.write(0, 8);
            header.write(configuration.center, 2);
            header.write(configuration.surround, 2);
            header.write(configuration.lfe ? 1 : 0, 1);
            header.write(0, 1);
            header.write(configuration.procedure, 2);
            header.write(configuration.multilingual, 3);
            header.write(configuration.multilingualHalfRate ? 1 : 0, 1);
            header.write(configuration.multilingual > 0 ? 1 : 0, 1);
            header.write(0, 2);
            var crc = crc(status, crc(header, 0xFFFF));

            var side = new Bits();
            var baseMain = new Bits();
            var begin = frame == 0 ? 0 : configuration.reservoir;
            writeBaseGranules(side, baseMain, base[frame], frame, configuration, begin);
            var extension = new Bits();
            extension.append(baseMain);
            extension.append(header);
            extension.write(crc, 16);
            extension.append(status);
            extension.align();
            var mainBytes = main.toBytes();

            var next = frame + 1 < base.length ? configuration.reservoir : 0;
            var sideBytes = configuration.monoBase ? 17 : 32;
            var frameHead = 4 + sideBytes;
            var payloadBytes = 960 - frameHead;
            var room = payloadBytes + begin - next - extension.size / 8;
            if (room < 0) {
                throw new IllegalStateException("the side information does not fit: " + room);
            }
            byte[] extensionFrame = null;
            // the first bytes of the main data go into what the frame before left free
            var early = mcDataBegin;
            if (early > 0) {
                var previous = packets.get(packets.size() - 1);
                System.arraycopy(mainBytes, 0, previous, 960 - configuration.reservoir - early, early);
                mainBytes = Arrays.copyOfRange(mainBytes, early, mainBytes.length);
            }
            var take = Math.min(room, mainBytes.length);
            if (take < mainBytes.length) {
                if (!configuration.extensionFrame) {
                    throw new IllegalStateException("the main data does not fit: " + mainBytes.length + " in " + room);
                }
            }
            var part1 = new byte[room];
            System.arraycopy(mainBytes, 0, part1, 0, take);
            var free = room - take;
            if (!configuration.mcReservoir && (configuration.extensionFrame || configuration.reservoir > 0)) {
                free = 0;
            }
            if (configuration.extensionFrame) {
                extensionFrame = extensionFrame(Arrays.copyOfRange(mainBytes, take, mainBytes.length));
            }
            var whole = new ByteArrayOutputStream();
            whole.writeBytes(extension.toBytes());
            whole.writeBytes(part1);
            var mainPart = whole.toByteArray();
            // this frame: the bytes left after what the previous frame carried, then the next frame's start
            var own = Arrays.copyOfRange(mainPart, Math.min(begin, mainPart.length), mainPart.length);
            var payload = new byte[payloadBytes];
            System.arraycopy(own, 0, payload, 0, Math.min(own.length, payloadBytes - next));
            if (frame > 0 && begin > 0) {
                var previous = packets.get(packets.size() - 1);
                System.arraycopy(mainPart, 0, previous, 960 - begin, begin);
            }
            if (frame + 1 < base.length) {
                nextEarly = nextFrameMainSize(configuration, frame + 1, channels, lfe, base);
                mcDataBegin = Math.min(free, nextEarly);
            }
            var out = new byte[960 + (extensionFrame != null ? extensionFrame.length : 0)];
            System.arraycopy(frameHeader(0), 0, out, 0, 4);
            if (configuration.monoBase) {
                out[3] = (byte) 0xC0;
            }
            System.arraycopy(side.toBytes(), 0, out, 4, sideBytes);
            System.arraycopy(payload, 0, out, frameHead, payloadBytes);
            if (extensionFrame != null) {
                System.arraycopy(extensionFrame, 0, out, 960, extensionFrame.length);
            }
            packets.add(out);
        }
        return packets;
    }

    /** Returns the size of a frame's main data: its transmission channels' data, in bytes. */
    private static int nextFrameMainSize(Configuration configuration, int frame, int[][][][] channels, int[][][] lfe,
                                         int[][][][] base) {
        var main = new Bits();
        for (var granule = 0; granule < 2; granule++) {
            var type = configuration.blockTypes.of(frame, granule);
            for (var tc = L; tc <= RS; tc++) {
                if (!configuration.tcPresent[tc] || !configuration.channelPresent(tc)) {
                    continue;
                }
                var values = new int[576];
                var count = 0;
                var starts = groupLines(type);
                for (var group = 0; group + 1 < starts.length; group++) {
                    if (configuration.codes(granule, tc, group, type)) {
                        System.arraycopy(channels[frame][granule][tc], starts[group], values, count,
                                starts[group + 1] - starts[group]);
                        count += starts[group + 1] - starts[group];
                    }
                }
                count = span(values, count);
                final var granuleFinal = granule;
                final var tcFinal = tc;
                writeScalefactors(main, tc, granule, type, configuration.scfsi, configuration,
                        group -> configuration.codes(granuleFinal, tcFinal, group, type));
                writeHuffman(main, values, count);
            }
        }
        if (configuration.lfe) {
            var pairs = new int[12];
            for (var line = 0; line < 6; line++) {
                pairs[2 * line] = lfe[frame][0][line];
                pairs[2 * line + 1] = lfe[frame][1][line];
            }
            writeHuffman(main, pairs, 12);
        }
        for (var granule = 0; granule < (configuration.multilingualHalfRate ? 1 : 2)
                && configuration.multilingual > 0; granule++) {
            for (var ch = 0; ch < configuration.multilingual; ch++) {
                var values = configuration.multilingualLines[frame][granule][ch];
                writeHuffman(main, values, span(values, 576));
            }
        }
        main.align();
        return main.toBytes().length;
    }

    /** Wraps data in an extension frame; its CRC field is left zero, which the decoder does not check. */
    private static byte[] extensionFrame(byte[] data) {
        var length = data.length + 5;
        var out = new byte[length];
        out[0] = 0x7F;
        out[1] = (byte) 0xF0;
        out[3] = (byte) (length >> 7);
        out[4] = (byte) (length << 1);
        System.arraycopy(data, 0, out, 5, data.length);
        return out;
    }

    private static int crc(Bits bits, int crc) {
        var data = bits.toBytes();
        for (var i = 0; i < bits.size; i++) {
            crc = MpegAudioCrc.update(crc, (data[i >> 3] >> (7 - (i & 7))) & 1, 1);
        }
        return crc;
    }
}
