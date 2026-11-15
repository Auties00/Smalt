package com.github.auties00.smalt.audio.dts.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes synthetic DTS core frames (ETSI TS 102 114 clause 5) from explicit coding parameters, for tests and for
 * deriving the vector code books through a reference decoder: every field is given by the caller, and the writer
 * applies the entropy coding the parameters select (Huffman, block or plain codes).
 */
public final class CoreStreamWriter {
    /**
     * The Huffman code books by name: level to {length, code}.
     */
    private static final Map<String, Map<Integer, int[]>> BOOKS = books();

    private static final String[] PREFIXES = {"A", "B", "C", "D", "E", "F", "G"};
    private static final int[] LEVELS = {0, 3, 5, 7, 9, 13, 17, 25, 33, 65, 129};
    private static final int[] BLOCK_BITS = {0, 7, 10, 12, 13, 15, 17, 19};

    private CoreStreamWriter() {
    }

    /**
     * The coding header of a set of channels coded with the core tools.
     */
    public static final class Channels {
        public final int count;
        public final int[] subbands;
        public final int[] vqStart;
        public final int[] joint;
        public final int[] transientBook;
        public final int[] scaleBook;
        public final int[] allocationBook;
        public final int[][] select;
        public final int[][] adjust;

        public Channels(int count) {
            this.count = count;
            subbands = filled(count, 32);
            vqStart = filled(count, 32);
            joint = new int[count];
            transientBook = new int[count];
            scaleBook = filled(count, 6);
            allocationBook = filled(count, 6);
            select = new int[count][10];
            adjust = new int[count][10];
            for (var ch = 0; ch < count; ch++) {
                select[ch][0] = 1;
                for (var n = 1; n < 5; n++) {
                    select[ch][n] = 3;
                }
                for (var n = 5; n < 10; n++) {
                    select[ch][n] = 7;
                }
            }
        }
    }

    /**
     * The side information and audio of one subframe of a set of channels.
     */
    public static final class Subframe {
        public final int subsubframes;
        public final int[][] predictionMode = new int[8][32];
        public final int[][] predictionVector = new int[8][32];
        public final int[][] allocation = new int[8][32];
        public final int[][] transientMode = new int[8][32];
        public final int[][][] scaleIndex = new int[8][32][2];
        public final int[] jointBook = new int[8];
        public final int[][] jointIndex = new int[8][32];
        public final int[][] vector = new int[8][32];
        public final int[][][] codes = new int[8][32][32];
        public final int[] lfe = new int[16];
        public int lfeScale;
        public int dynamicRange;

        public Subframe(int subsubframes) {
            this.subsubframes = subsubframes;
        }
    }

    /**
     * A core frame.
     */
    public static final class Frame {
        public int amode = 9;
        public int sfreq = 13;
        public int rate = 15;
        public int lfe;
        public boolean perfect = true;
        public boolean history;
        public boolean syncEverySubsubframe;
        public boolean dynamicRange;
        public boolean frontSumDifference;
        public boolean surroundSumDifference;
        public int pcmResolution = 6;
        public int version = 7;
        public int dialnorm;
        public int extensionType;
        public boolean extension;
        public Channels primary;
        public final List<Subframe> subframes = new ArrayList<>();
        public byte[] extensionData = new byte[0];
        public int minimumSize = 96;

        public Frame(Channels primary) {
            this.primary = primary;
        }

        public int blocks() {
            var blocks = 0;
            for (var subframe : subframes) {
                blocks += 8 * subframe.subsubframes;
            }
            return blocks;
        }
    }

    /**
     * Writes a frame.
     *
     * @param frame the frame
     * @return the frame bytes
     */
    public static byte[] write(Frame frame) {
        var body = new DtsBitWriter();
        writeAudioHeader(body, frame.primary, frame.subframes.size());
        for (var subframe : frame.subframes) {
            body.write(subframe.subsubframes - 1, 2);
            body.write(0, 3);
            writeSideInformation(body, frame.primary, subframe);
            if (frame.dynamicRange) {
                body.write(subframe.dynamicRange, 8);
            }
            writeVectors(body, frame.primary, subframe);
            if (frame.lfe > 0) {
                for (var n = 0; n < 2 * frame.lfe * subframe.subsubframes; n++) {
                    body.writeSigned(subframe.lfe[n], 8);
                }
                body.write(subframe.lfeScale, 8);
            }
            for (var s = 0; s < subframe.subsubframes; s++) {
                writeSubsubframe(body, frame.primary, subframe, s);
                if (s == subframe.subsubframes - 1 || frame.syncEverySubsubframe) {
                    body.write(0xFFFF, 16);
                }
            }
        }
        var header = new DtsBitWriter();
        var headerBits = 32 + 1 + 5 + 1 + 7 + 14 + 6 + 4 + 5 + 1 + 1 + 1 + 1 + 1 + 3 + 1 + 1 + 2 + 1 + 1 + 4 + 2 + 3
                + 1 + 1 + 4;
        var coreBits = headerBits + body.position();
        var coreBytes = (int) ((coreBits + 31) / 32 * 4);
        var size = Math.max(frame.minimumSize, coreBytes + frame.extensionData.length);
        header.write(0x7FFE8001L, 32);
        header.write(1, 1);
        header.write(31, 5);
        header.write(0, 1);
        header.write(frame.blocks() - 1, 7);
        header.write(size - 1, 14);
        header.write(frame.amode, 6);
        header.write(frame.sfreq, 4);
        header.write(frame.rate, 5);
        header.write(0, 1);
        header.write(frame.dynamicRange ? 1 : 0, 1);
        header.write(0, 1);
        header.write(0, 1);
        header.write(0, 1);
        header.write(frame.extensionType, 3);
        header.write(frame.extension ? 1 : 0, 1);
        header.write(frame.syncEverySubsubframe ? 1 : 0, 1);
        header.write(frame.lfe, 2);
        header.write(frame.history ? 1 : 0, 1);
        header.write(frame.perfect ? 1 : 0, 1);
        header.write(frame.version, 4);
        header.write(0, 2);
        header.write(frame.pcmResolution, 3);
        header.write(frame.frontSumDifference ? 1 : 0, 1);
        header.write(frame.surroundSumDifference ? 1 : 0, 1);
        header.write(frame.dialnorm, 4);
        var bodyBytes = body.toByteArray();
        var bodyBits = body.position();
        var reader = new com.github.auties00.smalt.internal.bits.MsbBitReader(bodyBytes);
        for (long i = 0; i < bodyBits; i++) {
            header.write(reader.read(1), 1);
        }
        header.alignTo(32);
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.toByteArray());
        out.writeBytes(frame.extensionData);
        while (out.size() < size) {
            out.write(0);
        }
        return out.toByteArray();
    }

    /**
     * Writes the audio coding header from {@code SUBFS} (core) on.
     */
    private static void writeAudioHeader(DtsBitWriter out, Channels set, int subframes) {
        out.write(subframes - 1, 4);
        out.write(set.count - 1, 3);
        writeChannelHeader(out, set);
    }

    /**
     * Writes the per-channel fields of an audio coding header from {@code SUBS} on.
     */
    public static void writeChannelHeader(DtsBitWriter out, Channels set) {
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.subbands[ch] - 2, 5);
        }
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.vqStart[ch] - 1, 5);
        }
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.joint[ch], 3);
        }
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.transientBook[ch], 2);
        }
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.scaleBook[ch], 3);
        }
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.allocationBook[ch], 3);
        }
        for (var ch = 0; ch < set.count; ch++) {
            out.write(set.select[ch][0], 1);
        }
        for (var n = 1; n < 5; n++) {
            for (var ch = 0; ch < set.count; ch++) {
                out.write(set.select[ch][n], 2);
            }
        }
        for (var n = 5; n < 10; n++) {
            for (var ch = 0; ch < set.count; ch++) {
                out.write(set.select[ch][n], 3);
            }
        }
        for (var ch = 0; ch < set.count; ch++) {
            if (set.select[ch][0] == 0) {
                out.write(set.adjust[ch][0], 2);
            }
        }
        for (var n = 1; n < 5; n++) {
            for (var ch = 0; ch < set.count; ch++) {
                if (set.select[ch][n] < 3) {
                    out.write(set.adjust[ch][n], 2);
                }
            }
        }
        for (var n = 5; n < 10; n++) {
            for (var ch = 0; ch < set.count; ch++) {
                if (set.select[ch][n] < 7) {
                    out.write(set.adjust[ch][n], 2);
                }
            }
        }
    }

    /**
     * Writes the side information of one subframe.
     */
    public static void writeSideInformation(DtsBitWriter out, Channels set, Subframe subframe) {
        for (var ch = 0; ch < set.count; ch++) {
            for (var n = 0; n < set.subbands[ch]; n++) {
                out.write(subframe.predictionMode[ch][n], 1);
            }
        }
        for (var ch = 0; ch < set.count; ch++) {
            for (var n = 0; n < set.subbands[ch]; n++) {
                if (subframe.predictionMode[ch][n] != 0) {
                    out.write(subframe.predictionVector[ch][n], 12);
                }
            }
        }
        for (var ch = 0; ch < set.count; ch++) {
            for (var n = 0; n < vqStart(set, ch); n++) {
                var abits = subframe.allocation[ch][n];
                switch (set.allocationBook[ch]) {
                    case 5 -> out.write(abits, 4);
                    case 6 -> out.write(abits, 5);
                    default -> huffman(out, PREFIXES[set.allocationBook[ch]] + "12", abits);
                }
            }
        }
        if (subframe.subsubframes > 1) {
            for (var ch = 0; ch < set.count; ch++) {
                for (var n = 0; n < vqStart(set, ch); n++) {
                    if (subframe.allocation[ch][n] > 0) {
                        huffman(out, PREFIXES[set.transientBook[ch]] + "4", subframe.transientMode[ch][n]);
                    }
                }
            }
        }
        for (var ch = 0; ch < set.count; ch++) {
            var previous = 0;
            for (var n = 0; n < vqStart(set, ch); n++) {
                if (subframe.allocation[ch][n] > 0) {
                    previous = scale(out, set.scaleBook[ch], subframe.scaleIndex[ch][n][0], previous);
                    if (subframe.transientMode[ch][n] > 0) {
                        previous = scale(out, set.scaleBook[ch], subframe.scaleIndex[ch][n][1], previous);
                    }
                }
            }
            for (var n = vqStart(set, ch); n < set.subbands[ch]; n++) {
                previous = scale(out, set.scaleBook[ch], subframe.scaleIndex[ch][n][0], previous);
            }
        }
        for (var ch = 0; ch < set.count; ch++) {
            if (set.joint[ch] > 0) {
                out.write(subframe.jointBook[ch], 3);
            }
        }
        for (var ch = 0; ch < set.count; ch++) {
            if (set.joint[ch] > 0) {
                var source = set.joint[ch] - 1;
                for (var n = set.subbands[ch]; n < set.subbands[source]; n++) {
                    var value = subframe.jointIndex[ch][n] - 64;
                    switch (subframe.jointBook[ch]) {
                        case 5 -> out.write(value, 6);
                        case 6 -> out.write(value, 7);
                        default -> huffman(out, "S" + PREFIXES[subframe.jointBook[ch]] + "129", value);
                    }
                }
            }
        }
    }

    /**
     * Writes the high frequency VQ indexes of one subframe.
     */
    public static void writeVectors(DtsBitWriter out, Channels set, Subframe subframe) {
        for (var ch = 0; ch < set.count; ch++) {
            for (var n = vqStart(set, ch); n < set.subbands[ch]; n++) {
                out.write(subframe.vector[ch][n], 10);
            }
        }
    }

    /**
     * Writes one subsubframe of audio codes.
     */
    public static void writeSubsubframe(DtsBitWriter out, Channels set, Subframe subframe, int subsubframe) {
        for (var ch = 0; ch < set.count; ch++) {
            for (var n = 0; n < vqStart(set, ch); n++) {
                var abits = subframe.allocation[ch][n];
                if (abits == 0) {
                    continue;
                }
                var codes = subframe.codes[ch][n];
                var base = 8 * subsubframe;
                var sel = abits <= 10 ? set.select[ch][abits - 1] : 0;
                var huffmanBooks = abits == 1 ? 1 : abits <= 5 ? 3 : abits <= 10 ? 7 : 0;
                if (sel < huffmanBooks) {
                    for (var m = 0; m < 8; m++) {
                        huffman(out, PREFIXES[sel] + LEVELS[abits], codes[base + m]);
                    }
                } else if (abits <= 7) {
                    var levels = LEVELS[abits];
                    var offset = (levels - 1) / 2;
                    for (var block = 0; block < 2; block++) {
                        var code = 0;
                        for (var m = 3; m >= 0; m--) {
                            code = code * levels + codes[base + block * 4 + m] + offset;
                        }
                        out.write(code, BLOCK_BITS[abits]);
                    }
                } else {
                    for (var m = 0; m < 8; m++) {
                        out.writeSigned(codes[base + m], abits - 3);
                    }
                }
            }
        }
    }

    private static int vqStart(Channels set, int ch) {
        return set.vqStart[ch];
    }

    private static int scale(DtsBitWriter out, int book, int index, int previous) {
        switch (book) {
            case 5 -> out.write(index, 6);
            case 6 -> out.write(index, 7);
            default -> huffman(out, "S" + PREFIXES[book] + "129", index - previous);
        }
        return index;
    }

    /**
     * Writes one level with a Huffman code book.
     */
    public static void huffman(DtsBitWriter out, String book, int level) {
        var entry = BOOKS.get(book).get(level);
        if (entry == null) {
            throw new IllegalArgumentException("level " + level + " outside code book " + book);
        }
        out.write(entry[1], entry[0]);
    }

    private static int[] filled(int count, int value) {
        var values = new int[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static Map<String, Map<Integer, int[]>> books() {
        var books = new HashMap<String, Map<Integer, int[]>>();
        for (var line : DtsCoreTables.HUFFMAN.lines().toList()) {
            var text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            var colon = text.indexOf(':');
            var values = text.substring(colon + 1).split(",");
            var book = new HashMap<Integer, int[]>();
            for (var i = 0; i + 2 < values.length; i += 3) {
                book.put(Integer.parseInt(values[i]), new int[]{Integer.parseInt(values[i + 1]),
                        Integer.parseInt(values[i + 2])});
            }
            books.put(text.substring(0, colon), book);
        }
        return books;
    }
}
