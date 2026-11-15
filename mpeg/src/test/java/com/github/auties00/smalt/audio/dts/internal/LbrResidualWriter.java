package com.github.auties00.smalt.audio.dts.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Bits;
import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Book;
import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.chunk;

/**
 * Writes the residual chunks of an LBR stream (grid 1, high resolution grid, time samples 1 and 2) of a single channel
 * or a channel pair from the values a test chooses, in the layout measured on ffmpeg's decoder.
 */
final class LbrResidualWriter {
    static final Book RSD_AMP = new Book(LbrTables.RSD_AMP);
    static final Book FST_RSD_AMP = new Book(LbrTables.FST_RSD_AMP);
    static final Book RSD_APPX = new Book(LbrTables.RSD_APPX);
    static final Book AVG_G3 = new Book(LbrTables.AVG_G3);
    static final Book ST_GRID = new Book(LbrTables.ST_GRID);

    /**
     * The grid 2 codes by value, least significant bit first.
     */
    private static final String[] GRID2 = {"11", "01", "00", "100", "1010", "10110"};

    /**
     * The grid 3 codes by value from 14 to 18.
     */
    private static final String[] GRID3 = {"0010", "01", "1", "000", "00110"};

    /**
     * The grid 2 values from 6 to 18 after the escape code, as measured.
     */
    private static final String[] GRID2_ESCAPED = {"1", "01", "001", "0001", "000000", "000011", "0000010",
            "000010001", "00001010", "000010110", "0000011", "00001001", "000010000"};

    /**
     * The grid 3 values after the escape code, from 10 to 21, as measured.
     */
    private static final String[] GRID3_ESCAPED = {"1010100", "10100", "100", "0", null, null, null, null, null, "11",
            "1011", "101011"};

    /**
     * Grid 2 values a test draws to exercise the escape codes.
     */
    static final int[] GRID2_LARGE = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 25, 40};

    /**
     * Grid 3 values a test draws to exercise the escape codes.
     */
    static final int[] GRID3_LARGE = {10, 11, 12, 13, 19, 20, 21, 1, 5, 25, 30};

    /**
     * {@return the code of a grid 2 value, least significant bit first}
     */
    static String grid2Code(int value) {
        if (value < GRID2.length) {
            return GRID2[value];
        }
        if (value <= 18) {
            return "10111" + GRID2_ESCAPED[value - 6];
        }
        return "10111" + "000010111" + rare(value);
    }

    /**
     * {@return the code of a grid 3 value, least significant bit first}
     */
    static String grid3Code(int value) {
        if (value >= 14 && value <= 18) {
            return GRID3[value - 14];
        }
        if (value >= 10 && value <= 21 && GRID3_ESCAPED[value - 10] != null) {
            return "00111" + GRID3_ESCAPED[value - 10];
        }
        return "00111" + "1010101" + rare(value);
    }

    /**
     * {@return the rare value form of the grid escapes: the bit count minus one in 3 bits, then the value}
     */
    private static String rare(int value) {
        var count = Math.max(1, 32 - Integer.numberOfLeadingZeros(value));
        var out = new StringBuilder();
        for (var i = 0; i < 3; i++) {
            out.append((count - 1) >> i & 1);
        }
        for (var i = 0; i < count; i++) {
            out.append(value >> i & 1);
        }
        return out.toString();
    }

    /**
     * The 8-level codes by symbol (level index, then 8 for the second code of level 4).
     */
    private static final String[] LEVEL_8 = {"011", "000", "10", "11", "010", "0010", "00110", "001110", "001111"};

    /**
     * The number of symbols of each quantizer level.
     */
    static final int[] SYMBOLS = {0, 2, 3, 5, 9, 16};

    /**
     * The subband whose formula level each of the lowest subbands takes, by subband of maximum energy, as measured on
     * ffmpeg's decoder with tools that probe one subband at a time.
     */
    private static final int[][] PEAK_ORDER = {{0}, {1, 0}, {3, 1, 0, 2}, {1, 2, 3, 0}, {1, 2, 5, 3, 0, 4},
            {1, 2, 2, 5, 3, 0, 4, 6}, {1, 2, 2, 6, 5, 3, 0, 4}, {1, 2, 2, 6, 5, 4, 0, 3}};

    /**
     * The values of one frame of a channel.
     *
     * @param profile the quantizer profile
     * @param lpc the 40 LPC reflection indices (subband 0 two sets, subband 1 two sets, subband 2)
     * @param grid1 the 8 time slots of the 11 grid 1 bands
     * @param averages the 29 grid 3 averages, 16 being none
     * @param grid2 the 64 slots of the 3 grid 2 bands
     * @param grid3 the 8 slots of the grid 3 of each subband, from 14 to 18
     * @param methods the coding method bit of each subband
     * @param symbols the 128 quantized samples of each subband
     */
    record Frame(int profile, int[] lpc, int[][] grid1, int[] averages, int[][] grid2, int[][] grid3, int[] methods,
                 int[][] symbols) {
    }

    private LbrResidualWriter() {
    }

    /**
     * The residual layout of a sample rate, bit rate and channel count, as the decoder derives it.
     *
     * @param subbands the subbands of the rate: 8, 16 or 32
     * @param grid1Bands the grid 1 bands of the subbands
     * @param grid2Bands the grid 2 bands of the subbands
     * @param targetBits the width of the subband field of the second time samples entries
     * @param averageOnly the first subband whose grid 3 is only its average
     * @param secondStart the first entry of the second channel of a pair
     * @param stereoStart the first entry that codes both channels of a pair
     */
    record Layout(int subbands, int grid1Bands, int grid2Bands, int targetBits, int averageOnly, int secondStart,
                  int stereoStart) {
        static Layout of(int rate, int bitRate, int channels) {
            var subbands = rate < 16000 ? 8 : rate < 32000 ? 16 : 32;
            var base = 500 + 250 * channels;
            var profile = bitRate >= 176 * base ? 2 : bitRate >= 100 * base ? 1 : 0;
            var half = rate / 2;
            var averageOnly = Math.min(subbands, subbands * 1000 * (profile == 0 ? 16 : profile == 1 ? 18 : 24) / half);
            return new Layout(subbands, subbands == 8 ? 6 : subbands == 16 ? 8 : 11, subbands == 8 ? 1 : subbands == 16
                    ? 2 : 3, Integer.numberOfTrailingZeros(subbands), averageOnly,
                    Math.min(subbands, subbands * 1000 * 2 / half), Math.min(subbands, subbands * 1000 * 14 / half));
        }

        /**
         * {@return the number of partial stereo grid groups}
         */
        int stereoGroups() {
            return (subbands - secondStart + 3) / 4;
        }
    }

    /**
     * {@return the quantizer level of each subband for a profile, as the decoder computes it}
     */
    static int[] levels(int profile, int rate) {
        var levels = new int[32];
        var subbands = rate < 16000 ? 8 : rate < 32000 ? 16 : 32;
        for (var sb = 0; sb < 32; sb++) {
            var frequency = sb * rate / subbands;
            var snr = 18000 / (12 * frequency / 1000 + 100 + 40 * ((profile & 0xC0) >> 6)) + 20 * ((profile & 0x38) >> 3);
            levels[sb] = snr <= 95 ? 1 : snr <= 140 ? 2 : snr <= 180 ? 3 : snr <= 230 ? 4 : 5;
        }
        var order = PEAK_ORDER[profile & 7];
        var base = levels.clone();
        for (var sb = 0; sb < order.length; sb++) {
            levels[sb] = base[order[sb]];
        }
        return levels;
    }

    /**
     * {@return a random frame}
     *
     * @param random the source
     * @param profile the quantizer profile
     * @param rate the sample rate, which with the profile selects the levels
     * @param features the features to randomize: {@code lpc}, {@code grid1}, {@code grid2}, {@code grid3},
     *        {@code averages}, {@code methods}
     */
    static Frame random(SplittableRandom random, int profile, int rate, List<String> features) {
        var lpc = new int[40];
        for (var i = 0; i < lpc.length; i++) {
            lpc[i] = features.contains("lpc") ? 5 + random.nextInt(7) : 8;
        }
        var base = 30 + random.nextInt(10);
        var grid1 = new int[11][8];
        for (var band = 0; band < 11; band++) {
            for (var slot = 0; slot < 8; slot++) {
                grid1[band][slot] = features.contains("grid1") ? base - 3 + random.nextInt(7) : 36;
            }
        }
        var averages = new int[29];
        for (var i = 0; i < averages.length; i++) {
            averages[i] = features.contains("averages") ? 14 + random.nextInt(5) : 16;
        }
        var grid2 = new int[3][64];
        if (features.contains("grid2")) {
            for (var band = 0; band < 3; band++) {
                for (var slot = 0; slot < 64; slot++) {
                    grid2[band][slot] = random.nextInt(10) < 3 ? random.nextInt(6) : 0;
                    if (features.contains("escapes") && random.nextInt(4) == 0) {
                        grid2[band][slot] = GRID2_LARGE[random.nextInt(GRID2_LARGE.length)];
                    }
                }
            }
        }
        var grid3 = new int[32][8];
        for (var sb = 0; sb < 32; sb++) {
            for (var slot = 0; slot < 8; slot++) {
                grid3[sb][slot] = features.contains("grid3") ? 14 + random.nextInt(5) : 16;
                if (features.contains("escapes") && random.nextInt(4) == 0) {
                    grid3[sb][slot] = GRID3_LARGE[random.nextInt(GRID3_LARGE.length)];
                }
            }
        }
        var levels = levels(profile, rate);
        var methods = new int[32];
        var symbols = new int[32][128];
        for (var sb = 0; sb < 32; sb++) {
            methods[sb] = features.contains("methods") ? random.nextInt(2) : 0;
            for (var n = 0; n < 128; n++) {
                symbols[sb][n] = random.nextInt(SYMBOLS[levels[sb]]);
            }
        }
        return new Frame(profile, lpc, grid1, averages, grid2, grid3, methods, symbols);
    }

    /**
     * Writes the points of a grid 1 band, every slot coded.
     */
    private static void scaleFactors(Bits w, int[] values) {
        FST_RSD_AMP.put(w, values[0]);
        var previous = values[0];
        for (var slot = 1; slot < 8; slot++) {
            RSD_APPX.put(w, 0);
            var difference = values[slot] - previous;
            RSD_AMP.put(w, difference > 0 ? 2 * difference - 1 : -2 * difference);
            previous = values[slot];
        }
    }

    /**
     * Writes the coding method bit and the samples of a subband in the code of a level.
     */
    private static void samples(Bits w, int level, int method, int[] symbols) {
        w.write(method, 1);
        switch (level) {
            case 1 -> {
                for (var s : symbols) {
                    w.write(s, 1);
                }
            }
            case 2 -> {
                if (method == 0) {
                    for (var i = 0; i < 130; i += 5) {
                        var packed = 0;
                        for (var d = 0; d < 5; d++) {
                            packed = packed * 3 + (i + d < 128 ? symbols[i + d] : 0);
                        }
                        w.write(packed, 8);
                    }
                } else {
                    for (var s : symbols) {
                        w.code(s == 1 ? "0" : s == 0 ? "10" : "11");
                    }
                }
            }
            case 3 -> {
                for (var i = 0; i < 129; i += 3) {
                    var packed = 0;
                    for (var d = 0; d < 3; d++) {
                        packed = packed * 5 + (i + d < 128 ? symbols[i + d] : 0);
                    }
                    w.write(packed, 7);
                }
            }
            case 4 -> {
                for (var s : symbols) {
                    w.code(LEVEL_8[s]);
                }
            }
            default -> {
                for (var s : symbols) {
                    w.write(s, 4);
                }
            }
        }
    }

    private static void grid2(Bits w, int[] band) {
        for (var group = 0; group < 8; group++) {
            var any = false;
            for (var j = 0; j < 8; j++) {
                any |= band[group * 8 + j] != 0;
            }
            w.write(any ? 1 : 0, 1);
            if (any) {
                for (var j = 0; j < 8; j++) {
                    w.code(grid2Code(band[group * 8 + j]));
                }
            }
        }
    }

    private static void grid3(Bits w, int[] slots) {
        for (var value : slots) {
            w.code(grid3Code(value));
        }
    }

    /**
     * {@return the four residual chunks of a frame of a single channel stream}
     *
     * @param frame the values
     * @param rate the sample rate
     * @param bitRate the original bit rate of the header
     * @param order the subband each entry of the second time samples chunk names, from entry 6
     */
    static List<byte[]> chunks(Frame frame, int rate, int bitRate, int[] order) {
        return chunks(frame, rate, bitRate, order, 26);
    }

    /**
     * {@return the four residual chunks of a frame of a single channel stream, the second time samples chunk ending
     * after some entries}
     *
     * @param frame the values
     * @param rate the sample rate
     * @param bitRate the original bit rate of the header
     * @param order the subband each entry of the second time samples chunk names, from entry 6
     * @param entries the number of entries of the second time samples chunk
     */
    static List<byte[]> chunks(Frame frame, int rate, int bitRate, int[] order, int entries) {
        return chunks(frame, rate, bitRate, order, entries, 1);
    }

    /**
     * {@return the four residual chunks of a frame of a channel coded alone in a stream of some channels}
     *
     * @param frame the values
     * @param rate the sample rate
     * @param bitRate the original bit rate of the header
     * @param order the subband each entry of the second time samples chunk names, from entry 6
     * @param entries the number of entries of the second time samples chunk
     * @param channels the number of full band channels of the stream, which with the bit rate selects the profile
     */
    static List<byte[]> chunks(Frame frame, int rate, int bitRate, int[] order, int entries, int channels) {
        var levels = levels(frame.profile(), rate);
        var layout = Layout.of(rate, bitRate, channels);
        var averageOnly = layout.averageOnly();
        entries = Math.min(entries, layout.subbands() - 6);
        var out = new ArrayList<byte[]>();
        var w = new Bits();
        for (var band = 2; band < layout.grid1Bands(); band++) {
            scaleFactors(w, frame.grid1()[band]);
        }
        for (var i = 0; i < layout.subbands() - 3; i++) {
            AVG_G3.put(w, frame.averages()[i]);
        }
        w.write(0, 32);
        out.add(chunk(0x30, w.toByteArray()));
        w = new Bits();
        w.write(frame.profile(), 8);
        for (var i = 0; i < 32; i++) {
            w.write(frame.lpc()[i], 4);
        }
        samples(w, levels[0], frame.methods()[0], frame.symbols()[0]);
        samples(w, levels[1], frame.methods()[1], frame.symbols()[1]);
        scaleFactors(w, frame.grid1()[0]);
        scaleFactors(w, frame.grid1()[1]);
        w.write(0, 32);
        out.add(chunk(0x40, w.toByteArray()));
        w = new Bits();
        for (var i = 32; i < 40; i++) {
            w.write(frame.lpc()[i], 4);
        }
        samples(w, levels[2], frame.methods()[2], frame.symbols()[2]);
        samples(w, levels[3], frame.methods()[3], frame.symbols()[3]);
        grid2(w, frame.grid2()[0]);
        for (var sb = 4; sb < 6; sb++) {
            grid3(w, frame.grid3()[sb]);
            samples(w, levels[sb], frame.methods()[sb], frame.symbols()[sb]);
        }
        w.write(0, 32);
        out.add(chunk(0x50, w.toByteArray()));
        w = new Bits();
        for (var band = 1; band < layout.grid2Bands(); band++) {
            grid2(w, frame.grid2()[band]);
        }
        for (var index = 6; index < 6 + entries; index++) {
            var sb = order[index - 6];
            w.write(sb, layout.targetBits());
            entryGrid3(w, frame, index, sb, order, averageOnly);
            var symbols = frame.symbols()[sb].clone();
            for (var n = 0; n < symbols.length; n++) {
                symbols[n] %= SYMBOLS[levels[index]];
            }
            samples(w, levels[index], frame.methods()[sb], symbols);
        }
        w.write(0, 32);
        out.add(chunk(0x60, w.toByteArray()));
        return out;
    }

    /**
     * {@return the identity order of the entries of the second time samples chunk, up to 32 subbands}
     */
    static int[] identity() {
        var order = new int[26];
        for (var i = 0; i < order.length; i++) {
            order[i] = 6 + i;
        }
        return order;
    }

    /**
     * Writes the grid 3 an entry of the second time samples chunk carries: the named subband's below entry 12, at
     * entry 12 every subband from 6 that no earlier entry named.
     */
    private static void entryGrid3(Bits w, Frame frame, int index, int sb, int[] order, int averageOnly) {
        if (index < 12) {
            if (sb >= 4) {
                grid3(w, frame.grid3()[sb]);
            }
        } else if (index == 12) {
            for (var g = 6; g < averageOnly; g++) {
                var named = false;
                for (var i = 0; i < 6; i++) {
                    named |= order[i] == g;
                }
                if (!named) {
                    grid3(w, frame.grid3()[g]);
                }
            }
        }
    }

    /**
     * The values of one frame of a channel pair.
     *
     * @param first the values of the first channel
     * @param second the values of the second channel
     * @param midSide the 8 mid/side flags of each subband
     * @param swap the 8 swap flags of each subband
     * @param minimum the minimum of the partial stereo grid of each channel
     * @param stereoGrid the partial stereo grid by channel, group and subsample, or {@code null} for none
     */
    record PairFrame(Frame first, Frame second, int[] midSide, int[] swap, int[] minimum, int[][][] stereoGrid) {
    }

    /**
     * {@return a random frame of a channel pair}
     *
     * @param random the source
     * @param profile the quantizer profile
     * @param rate the sample rate
     * @param features the features to randomize, as {@link #random}, plus {@code stereo} for a partial stereo grid
     *        and {@code flags} for mid/side and swap flags
     */
    static PairFrame randomPair(SplittableRandom random, int profile, int rate, List<String> features) {
        var first = random(random, profile, rate, features);
        var second = random(random, profile, rate, features);
        var midSide = new int[32];
        var swap = new int[32];
        if (features.contains("flags")) {
            for (var sb = 0; sb < 32; sb++) {
                midSide[sb] = random.nextInt(256);
                swap[sb] = random.nextInt(256);
            }
        }
        int[][][] grid = null;
        var minimum = new int[2];
        if (features.contains("stereo")) {
            grid = new int[2][8][4];
            for (var c = 0; c < 2; c++) {
                minimum[c] = random.nextInt(4);
                for (var g = 0; g < 8; g++) {
                    for (var k = 0; k < 4; k++) {
                        grid[c][g][k] = random.nextInt(8);
                    }
                }
            }
        }
        return new PairFrame(first, second, midSide, swap, minimum, grid);
    }

    /**
     * The subband each grid 1 band is centred on.
     */
    private static final int[] GRID1_CENTER = {0, 1, 2, 3, 4, 6, 9, 13, 18, 24, 31};

    /**
     * {@return the pair frame with negative scale factors from a grid 1 band up, so that those subbands are silent
     * whether coded or not}
     *
     * @param frame the frame, changed in place
     * @param band the first silent grid 1 band, from 5
     */
    static PairFrame silenceFrom(PairFrame frame, int band) {
        for (var channel : List.of(frame.first(), frame.second())) {
            for (var b = band; b < 11; b++) {
                java.util.Arrays.fill(channel.grid1()[b], 0);
            }
            for (var sb = GRID1_CENTER[band]; sb < 32; sb++) {
                channel.averages()[sb - 4] = 20;
            }
        }
        return frame;
    }

    /**
     * {@return the four residual chunks of a frame of a channel pair}
     *
     * @param frame the values
     * @param rate the sample rate
     * @param bitRate the original bit rate of the header
     * @param order the subband each entry of the second time samples chunk names, from entry 6
     * @param entries the number of entries of the second channel below the stereo entries, all of them when the
     *        stereo entries follow
     * @param stereoEntries whether the entries of both channels from the stereo entries on follow
     * @param padding the zero bits after the last entry
     */
    static List<byte[]> pairChunks(PairFrame frame, int rate, int bitRate, int[] order, int entries,
                                   boolean stereoEntries, int padding) {
        return pairChunks(frame, rate, bitRate, order, entries, stereoEntries, padding, 2);
    }

    /**
     * {@return the four residual chunks of a frame of a channel pair in a stream of some channels}
     *
     * @param frame the values
     * @param rate the sample rate
     * @param bitRate the original bit rate of the header
     * @param order the subband each entry of the second time samples chunk names, from entry 6
     * @param entries the number of entries of the second channel below the stereo entries, all of them when the
     *        stereo entries follow
     * @param stereoEntries whether the entries of both channels from the stereo entries on follow
     * @param padding the zero bits after the last entry
     * @param channels the number of full band channels of the stream, which with the bit rate selects the profile
     */
    static List<byte[]> pairChunks(PairFrame frame, int rate, int bitRate, int[] order, int entries,
                                   boolean stereoEntries, int padding, int channels) {
        var a = frame.first();
        var b = frame.second();
        var levels = levels(a.profile(), rate);
        var layout = Layout.of(rate, bitRate, channels);
        var averageOnly = layout.averageOnly();
        var subbands = layout.subbands();
        var out = new ArrayList<byte[]>();
        var w = new Bits();
        for (var band = 2; band < layout.grid1Bands(); band++) {
            scaleFactors(w, a.grid1()[band]);
            if (band < layout.secondStart()) {
                scaleFactors(w, b.grid1()[band]);
            }
        }
        for (var i = 0; i < subbands - 4; i++) {
            AVG_G3.put(w, a.averages()[i]);
        }
        if (frame.stereoGrid() != null) {
            w.write(frame.minimum()[0], 4);
            w.write(frame.minimum()[1], 4);
            for (var g = 0; g < layout.stereoGroups(); g++) {
                for (var c = 0; c < 2; c++) {
                    for (var k = 0; k < 4; k++) {
                        ST_GRID.put(w, frame.stereoGrid()[c][g][k]);
                    }
                }
            }
        }
        out.add(chunk(0x30, w.toByteArray()));
        w = new Bits();
        w.write(a.profile(), 8);
        for (var sb = 0; sb < 2; sb++) {
            for (var channel : List.of(a, b)) {
                for (var i = 0; i < 16; i++) {
                    w.write(channel.lpc()[sb * 16 + i], 4);
                }
            }
        }
        for (var sb = 0; sb < 2; sb++) {
            w.write(frame.midSide()[sb], 8);
            samples(w, levels[sb], a.methods()[sb], a.symbols()[sb]);
            samples(w, levels[sb], b.methods()[sb], b.symbols()[sb]);
        }
        for (var sb = 0; sb < 2; sb++) {
            scaleFactors(w, a.grid1()[sb]);
            scaleFactors(w, b.grid1()[sb]);
        }
        w.write(0, 32);
        out.add(chunk(0x40, w.toByteArray()));
        w = new Bits();
        for (var channel : List.of(a, b)) {
            for (var i = 32; i < 40; i++) {
                w.write(channel.lpc()[i], 4);
            }
        }
        for (var sb = 2; sb < 4; sb++) {
            w.write(frame.midSide()[sb], 8);
            samples(w, levels[sb], a.methods()[sb], a.symbols()[sb]);
            if (sb < layout.secondStart()) {
                samples(w, levels[sb], b.methods()[sb], b.symbols()[sb]);
            }
        }
        grid2(w, a.grid2()[0]);
        for (var sb = 4; sb < 6; sb++) {
            grid3(w, a.grid3()[sb]);
            w.write(frame.midSide()[sb], 8);
            samples(w, levels[sb], a.methods()[sb], a.symbols()[sb]);
        }
        w.write(0, 32);
        out.add(chunk(0x50, w.toByteArray()));
        w = new Bits();
        for (var band = 1; band < layout.grid2Bands(); band++) {
            grid2(w, a.grid2()[band]);
        }
        for (var index = 6; index < layout.stereoStart(); index++) {
            var sb = order[index - 6];
            w.write(sb, layout.targetBits());
            entryGrid3(w, a, index, sb, order, averageOnly);
            w.write(frame.midSide()[sb], 8);
            samples(w, levels[index], a.methods()[sb], entrySymbols(a, sb, levels[index]));
        }
        for (var band = layout.secondStart(); band < layout.grid1Bands(); band++) {
            scaleFactors(w, b.grid1()[band]);
        }
        for (var i = 0; i < subbands - 4; i++) {
            AVG_G3.put(w, b.averages()[i]);
        }
        for (var band = 0; band < layout.grid2Bands(); band++) {
            grid2(w, b.grid2()[band]);
        }
        var second = layout.secondStart();
        var end = stereoEntries ? layout.stereoStart() : Math.min(second + entries, layout.stereoStart());
        for (var index = second; index < end; index++) {
            var sb = index < 6 ? index : order[index - 6];
            if (index >= 4 && index < 6) {
                grid3(w, b.grid3()[sb]);
            } else if (index >= 6) {
                entryGrid3(w, b, index, sb, order, averageOnly);
            }
            w.write(frame.swap()[sb], 8);
            samples(w, levels[index], b.methods()[sb], entrySymbols(b, sb, levels[index]));
        }
        if (stereoEntries) {
            for (var sb = layout.stereoStart(); sb < subbands; sb++) {
                w.write(sb, layout.targetBits());
                w.write(frame.midSide()[sb], 8);
                w.write(frame.swap()[sb], 8);
                samples(w, levels[sb], a.methods()[sb], a.symbols()[sb]);
                samples(w, levels[sb], b.methods()[sb], b.symbols()[sb]);
            }
        }
        w.write(0, padding);
        out.add(chunk(0x60, w.toByteArray()));
        return out;
    }

    /**
     * {@return the symbols of a subband limited to the symbols of the level of the entry that carries them}
     */
    private static int[] entrySymbols(Frame frame, int sb, int level) {
        var symbols = frame.symbols()[sb].clone();
        for (var n = 0; n < symbols.length; n++) {
            symbols[n] %= SYMBOLS[level];
        }
        return symbols;
    }

    /**
     * {@return chunks moved to another channel pair, their ids raised by the pair index}
     */
    static List<byte[]> forPair(List<byte[]> chunks, int pair) {
        var out = new ArrayList<byte[]>();
        for (var chunk : chunks) {
            var copy = chunk.clone();
            copy[0] += (byte) pair;
            out.add(copy);
        }
        return out;
    }
}
