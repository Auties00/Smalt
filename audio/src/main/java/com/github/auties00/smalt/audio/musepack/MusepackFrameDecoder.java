/* Ports libmpcdec r475 (mpc_decoder.c, mpc_bits_reader.h, mpc_bits_reader.c, requant.c, synth_filter.c).
   Copyright (c) 2005-2009, The Musepack Development Team; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.musepack;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.audio.musepack.MusepackTables.*;

/**
 * Decodes the frames of a Musepack stream, version 7 or 8, into 1152 float samples per channel: the bitstream
 * ({@code mpc_decoder_read_bitstream_sv7}, {@code _sv8}), the requantization and the polyphase synthesis.
 *
 * <p>The frame state (scale factors, resolutions, the synthesis memory) carries from frame to frame. Instances are
 * not thread-safe.
 */
final class MusepackFrameDecoder {
    static final int FRAME_LENGTH = 1152;

    private static final int V_MEM = 2304;

    private static final int[] IDX30 = {-1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0,
            1, -1, 0, 1};
    private static final int[] IDX31 = {-1, -1, -1, 0, 0, 0, 1, 1, 1, -1, -1, -1, 0, 0, 0, 1, 1, 1, -1, -1, -1, 0, 0,
            0, 1, 1, 1};
    private static final int[] IDX32 = {-1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1,
            1, 1, 1, 1};
    private static final int[] IDX50 = new int[125];
    private static final int[] IDX51 = new int[125];
    private static final int[] IDX52 = new int[125];
    private static final int[] THRES = {0, 0, 3, 0, 0, 1, 3, 4, 8};
    private static final int[] HUFFQ2_VAR = {6, 5, 4, 5, 6, 5, 4, 3, 4, 5, 4, 3, 2, 3, 4, 5, 4, 3, 4, 5, 6, 5, 4, 5,
            6, 5, 4, 3, 4, 5, 4, 3, 2, 3, 4, 3, 2, 1, 2, 3, 4, 3, 2, 3, 4, 5, 4, 3, 4, 5, 4, 3, 2, 3, 4, 3, 2, 1, 2, 3,
            2, 1, 0, 1, 2, 3, 2, 1, 2, 3, 4, 3, 2, 3, 4, 5, 4, 3, 4, 5, 4, 3, 2, 3, 4, 3, 2, 1, 2, 3, 4, 3, 2, 3, 4, 5,
            4, 3, 4, 5, 6, 5, 4, 5, 6, 5, 4, 3, 4, 5, 4, 3, 2, 3, 4, 5, 4, 3, 4, 5, 6, 5, 4, 5, 6};
    private static final int[] LOG2 = {1, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 6};
    private static final int[] LOG2_LOST = {0, 1, 0, 3, 2, 1, 0, 7, 6, 5, 4, 3, 2, 1, 0, 15, 14, 13, 12, 11, 10, 9,
            8, 7, 6, 5, 4, 3, 2, 1, 0, 31};
    private static final int[] PARITY = parity();
    private static final float[] DI = di();
    private static final int[][] HUFFQ = {HUFFQ1_0, HUFFQ1_1, HUFFQ2_0, HUFFQ2_1, HUFFQ3_0, HUFFQ3_1, HUFFQ4_0,
            HUFFQ4_1, HUFFQ5_0, HUFFQ5_1, HUFFQ6_0, HUFFQ6_1, HUFFQ7_0, HUFFQ7_1};
    private static final int[][] CAN_Q = {CAN_Q2_1, CAN_Q2_2, CAN_Q3, CAN_Q4, CAN_Q5_1, CAN_Q5_2, CAN_Q6_1, CAN_Q6_2,
            CAN_Q7_1, CAN_Q7_2, CAN_Q8_1, CAN_Q8_2};
    private static final byte[][] SYM_Q = {SYM_Q2_1, SYM_Q2_2, SYM_Q3, SYM_Q4, SYM_Q5_1, SYM_Q5_2, SYM_Q6_1, SYM_Q6_2,
            SYM_Q7_1, SYM_Q7_2, SYM_Q8_1, SYM_Q8_2};

    static {
        for (var i = 0; i < 125; i++) {
            IDX50[i] = i % 5 - 2;
            IDX51[i] = i / 5 % 5 - 2;
            IDX52[i] = i / 25 - 2;
        }
    }

    private final boolean sv8;

    private final int maxBand;

    private final boolean midSide;

    private final int channels;

    private int lastMaxBand;

    private int random1 = 1;

    private int random2 = 1;

    private final int[][] scfIndexL = new int[32][3];

    private final int[][] scfIndexR = new int[32][3];

    private final short[][] qL = new short[32][36];

    private final short[][] qR = new short[32][36];

    private final int[] resL = new int[32];

    private final int[] resR = new int[32];

    private final boolean[] dscfFlagL = new boolean[32];

    private final boolean[] dscfFlagR = new boolean[32];

    private final int[] scfiL = new int[32];

    private final int[] scfiR = new int[32];

    private final boolean[] msFlag = new boolean[32];

    private final float[] vL = new float[V_MEM + 960];

    private final float[] vR = new float[V_MEM + 960];

    private final float[] yL = new float[36 * 32];

    private final float[] yR = new float[36 * 32];

    private final float[] scf = new float[256];

    private byte[] data = new byte[0];

    private int length;

    private long bit;

    /**
     * Constructs a frame decoder.
     *
     * @param sv8 whether the stream is version 8
     * @param maxBand the highest band of the stream, from 0 to 31
     * @param midSide whether the stream may code bands as mid and side
     * @param channels the channel count, 1 or 2
     */
    MusepackFrameDecoder(boolean sv8, int maxBand, boolean midSide, int channels) {
        this.sv8 = sv8;
        this.maxBand = maxBand;
        this.midSide = midSide;
        this.channels = channels;
        initQuantization();
    }

    private static int[] parity() {
        var table = new int[256];
        for (var i = 0; i < 256; i++) {
            table[i] = Integer.bitCount(i) & 1;
        }
        return table;
    }

    private static float[] di() {
        var table = new float[32 * 16];
        for (var i = 0; i < 32; i++) {
            for (var j = 0; j < 16; j++) {
                table[i * 16 + j] = (float) ((double) DI_OPT[i][j] / (double) 0x10000);
            }
        }
        return table;
    }

    private void initQuantization() {
        var factor = 1.0 * (1.0 / (double) (1 << 15));
        var f1 = factor;
        var f2 = factor;
        scf[1] = (float) factor;
        f1 *= 0.83298066476582673961;
        f2 *= 1 / 0.83298066476582673961;
        for (var n = 1; n <= 128; n++) {
            scf[(1 + n) & 0xFF] = (float) f1;
            scf[(1 - n) & 0xFF] = (float) f2;
            f1 *= 0.83298066476582673961;
            f2 *= 1 / 0.83298066476582673961;
        }
    }

    /**
     * Loads the bitstream of the next frames, read from byte {@code offset} for {@code bytes} bytes and positioned at
     * bit {@code position}.
     */
    void load(byte[] source, int offset, int bytes, long position) {
        if (data.length < bytes + 8) {
            data = new byte[Math.max(bytes + 8, data.length * 2)];
        }
        System.arraycopy(source, offset, data, 0, bytes);
        java.util.Arrays.fill(data, bytes, bytes + 8, (byte) 0);
        length = bytes;
        bit = position;
    }

    /**
     * Loads a version 7 bitstream: little-endian 32-bit words read from the most significant bit.
     */
    void loadWords(byte[] source, int offset, int bytes, long position) {
        var words = bytes / 4;
        if (data.length < words * 4 + 8) {
            data = new byte[Math.max(words * 4 + 8, data.length * 2)];
        }
        for (var w = 0; w < words; w++) {
            var at = offset + 4 * w;
            data[4 * w] = source[at + 3];
            data[4 * w + 1] = source[at + 2];
            data[4 * w + 2] = source[at + 1];
            data[4 * w + 3] = source[at];
        }
        java.util.Arrays.fill(data, words * 4, words * 4 + 8, (byte) 0);
        length = words * 4;
        bit = position;
    }

    /**
     * {@return the bit position in the loaded data}
     */
    long position() {
        return bit;
    }

    /**
     * {@return the number of bits of loaded data}
     */
    long bits() {
        return (long) length * 8;
    }

    /**
     * Reads {@code count} bits, from 0 to 32, most significant first.
     */
    int read(int count) {
        if (count == 0) {
            return 0;
        }
        if (bit + count > (long) length * 8 + 32) {
            throw new MediaException.Malformed("Musepack frame overread");
        }
        var value = 0L;
        var remaining = count;
        while (remaining > 0) {
            var index = (int) (bit >>> 3);
            var offset = (int) (bit & 7);
            var take = Math.min(remaining, 8 - offset);
            var b = data[index] & 0xFF;
            value = value << take | ((b >>> (8 - offset - take)) & ((1 << take) - 1));
            remaining -= take;
            bit += take;
        }
        return (int) value;
    }

    private int peek16() {
        if (bit + 16 > (long) length * 8 + 32) {
            throw new MediaException.Malformed("Musepack frame overread");
        }
        var index = (int) (bit >>> 3);
        var window = (data[index] & 0xFF) << 16 | (data[index + 1] & 0xFF) << 8 | (data[index + 2] & 0xFF);
        return (window >>> (8 - (int) (bit & 7))) & 0xFFFF;
    }

    private int huffman(int[] table) {
        var code = peek16();
        var entry = 0;
        while (code < table[entry]) {
            entry += 3;
        }
        bit += table[entry + 1];
        return table[entry + 2];
    }

    private int canonical(int[] table, byte[] symbols) {
        var code = peek16();
        var entry = 0;
        while (code < table[entry]) {
            entry += 3;
        }
        var bits = table[entry + 1];
        bit += bits;
        return symbols[(table[entry + 2] - (code >>> (16 - bits))) & 0xFF];
    }

    private int enumerated(int k, int n) {
        var bits = 0;
        var row = k - 1;
        long code = read((int) CNK_LEN[row][n - 1] - 1) & 0xFFFFFFFFL;
        if (code >= CNK_LOST[row][n - 1]) {
            code = ((code << 1) | read(1)) - CNK_LOST[row][n - 1];
        }
        do {
            n--;
            if (n < 0) {
                throw new MediaException.Malformed("invalid Musepack enumeration");
            }
            if (code >= CNK[row][n]) {
                bits |= 1 << n;
                code -= CNK[row][n];
                row--;
                k--;
            }
        } while (k > 0);
        return bits;
    }

    private int logarithmic(int max) {
        if (max == 0) {
            return 0;
        }
        var value = 0;
        if (LOG2[max - 1] > 1) {
            value = read(LOG2[max - 1] - 1);
        }
        if (value >= LOG2_LOST[max - 1]) {
            value = ((value << 1) | read(1)) - LOG2_LOST[max - 1];
        }
        return value;
    }

    private int random() {
        var t1 = random1;
        var t3 = t1;
        var t2 = random2;
        var t4 = t2;
        t1 &= 0xF5;
        t2 >>>= 25;
        t1 = PARITY[t1];
        t2 &= 0x63;
        t1 <<= 31;
        t2 = PARITY[t2];
        random1 = (t3 >>> 1) | t1;
        random2 = (t4 + t4) | t2;
        return random1 ^ random2;
    }

    private static short noise(int value) {
        return (short) (((value >>> 24) & 0xFF) + ((value >>> 16) & 0xFF) + ((value >>> 8) & 0xFF) + (value & 0xFF)
                - 510);
    }

    /**
     * Reads the bitstream of one frame ({@code mpc_decoder_read_bitstream_sv7} or {@code _sv8}).
     *
     * @param keyFrame whether a version 8 frame starts an audio packet
     */
    void readFrame(boolean keyFrame) {
        if (sv8) {
            readSv8(keyFrame);
        } else {
            readSv7();
        }
    }

    private void readSv7() {
        var maxUsed = 0;
        resL[0] = read(4);
        resR[0] = read(4);
        if (!(resL[0] == 0 && resR[0] == 0)) {
            if (midSide) {
                msFlag[0] = read(1) != 0;
            }
            maxUsed = 1;
        }
        for (var n = 1; n <= maxBand; n++) {
            var idx = huffman(HUFFHDR);
            resL[n] = idx != 4 ? resL[n - 1] + idx : read(4);
            idx = huffman(HUFFHDR);
            resR[n] = idx != 4 ? resR[n - 1] + idx : read(4);
            if (!(resL[n] == 0 && resR[n] == 0)) {
                if (midSide) {
                    msFlag[n] = read(1) != 0;
                }
                maxUsed = n + 1;
            }
        }
        for (var n = 0; n < maxUsed; n++) {
            if (resL[n] != 0) {
                scfiL[n] = huffman(HUFFSCFI);
            }
            if (resR[n] != 0) {
                scfiR[n] = huffman(HUFFSCFI);
            }
        }
        for (var n = 0; n < maxUsed; n++) {
            scfSv7(scfIndexL[n], resL[n], scfiL[n]);
            scfSv7(scfIndexR[n], resR[n], scfiR[n]);
        }
        for (var n = 0; n < maxUsed; n++) {
            samplesSv7(qL[n], resL[n]);
            samplesSv7(qR[n], resR[n]);
        }
    }

    private int dscf(int previous) {
        var idx = huffman(HUFFDSCF);
        return idx != 8 ? previous + idx : read(6);
    }

    private void scfSv7(int[] scf, int res, int scfi) {
        if (res == 0) {
            return;
        }
        switch (scfi) {
            case 1 -> {
                scf[0] = dscf(scf[2]);
                scf[1] = dscf(scf[0]);
                scf[2] = scf[1];
            }
            case 3 -> {
                scf[0] = dscf(scf[2]);
                scf[1] = scf[0];
                scf[2] = scf[1];
            }
            case 2 -> {
                scf[0] = dscf(scf[2]);
                scf[1] = scf[0];
                scf[2] = dscf(scf[1]);
            }
            case 0 -> {
                scf[0] = dscf(scf[2]);
                scf[1] = dscf(scf[0]);
                scf[2] = dscf(scf[1]);
            }
            default -> throw new MediaException.Malformed("invalid Musepack scale factor selection");
        }
        for (var i = 0; i < 3; i++) {
            if (scf[i] > 1024) {
                scf[i] = 0x8080;
            }
        }
    }

    private void samplesSv7(short[] q, int res) {
        switch (res) {
            case -2, -3, -4, -5, -6, -7, -8, -9, -10, -11, -12, -13, -14, -15, -16, -17, 0 -> {
            }
            case -1 -> {
                for (var k = 0; k < 36; k++) {
                    q[k] = noise(random());
                }
            }
            case 1 -> {
                var table = HUFFQ[read(1)];
                for (var k = 0; k < 36; k += 3) {
                    var idx = huffman(table);
                    checkIndex(idx, 27);
                    q[k] = (short) IDX30[idx];
                    q[k + 1] = (short) IDX31[idx];
                    q[k + 2] = (short) IDX32[idx];
                }
            }
            case 2 -> {
                var table = HUFFQ[2 + read(1)];
                for (var k = 0; k < 36; k += 2) {
                    var idx = huffman(table);
                    checkIndex(idx, 25);
                    q[k] = (short) IDX50[idx];
                    q[k + 1] = (short) IDX51[idx];
                }
            }
            case 3, 4, 5, 6, 7 -> {
                var table = HUFFQ[2 * (res - 1) + read(1)];
                for (var k = 0; k < 36; k++) {
                    q[k] = (short) huffman(table);
                }
            }
            case 8, 9, 10, 11, 12, 13, 14, 15, 16, 17 -> {
                for (var k = 0; k < 36; k++) {
                    q[k] = (short) (read(RES_BIT[res]) - DC[res + 1]);
                }
            }
            default -> throw new MediaException.Malformed("invalid Musepack resolution " + res);
        }
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new MediaException.Malformed("invalid Musepack quantizer index");
        }
    }

    private void readSv8(boolean keyFrame) {
        int maxUsed;
        if (keyFrame) {
            maxUsed = logarithmic(maxBand + 1);
        } else {
            maxUsed = lastMaxBand + canonical(CAN_BANDS, SYM_BANDS);
            if (maxUsed > 32) {
                maxUsed -= 33;
            }
        }
        if (maxUsed < 0 || maxUsed > 32) {
            throw new MediaException.Malformed("invalid Musepack band count " + maxUsed);
        }
        lastMaxBand = maxUsed;
        if (maxUsed != 0) {
            resL[maxUsed - 1] = canonical(CAN_RES_1, SYM_RES_1);
            resR[maxUsed - 1] = canonical(CAN_RES_1, SYM_RES_1);
            if (resL[maxUsed - 1] > 15) {
                resL[maxUsed - 1] -= 17;
            }
            if (resR[maxUsed - 1] > 15) {
                resR[maxUsed - 1] -= 17;
            }
            for (var n = maxUsed - 2; n >= 0; n--) {
                resL[n] = (resL[n + 1] > 2 ? canonical(CAN_RES_2, SYM_RES_2) : canonical(CAN_RES_1, SYM_RES_1))
                        + resL[n + 1];
                if (resL[n] > 15) {
                    resL[n] -= 17;
                }
                resR[n] = (resR[n + 1] > 2 ? canonical(CAN_RES_2, SYM_RES_2) : canonical(CAN_RES_1, SYM_RES_1))
                        + resR[n + 1];
                if (resR[n] > 15) {
                    resR[n] -= 17;
                }
            }
            if (midSide) {
                var total = 0;
                for (var n = 0; n < maxUsed; n++) {
                    if (resL[n] != 0 || resR[n] != 0) {
                        total++;
                    }
                }
                var count = logarithmic(total);
                var flags = 0;
                if (count != 0 && count != total) {
                    flags = enumerated(Math.min(count, total - count), total);
                }
                if (count * 2 > total) {
                    flags = ~flags;
                }
                for (var n = maxUsed - 1; n >= 0; n--) {
                    if (resL[n] != 0 || resR[n] != 0) {
                        msFlag[n] = (flags & 1) != 0;
                        flags >>>= 1;
                    }
                }
            }
        }
        for (var n = maxUsed; n <= maxBand; n++) {
            resL[n] = 0;
            resR[n] = 0;
        }
        if (keyFrame) {
            java.util.Arrays.fill(dscfFlagL, true);
            java.util.Arrays.fill(dscfFlagR, true);
        }
        for (var n = 0; n < maxUsed; n++) {
            var count = -1;
            if (resL[n] != 0) {
                count++;
            }
            if (resR[n] != 0) {
                count++;
            }
            if (count >= 0) {
                var value = count == 0 ? canonical(CAN_SCFI_1, SYM_SCFI_1) : canonical(CAN_SCFI_2, SYM_SCFI_2);
                if (resL[n] != 0) {
                    scfiL[n] = value >> (2 * count);
                }
                if (resR[n] != 0) {
                    scfiR[n] = value & 3;
                }
            }
        }
        for (var n = 0; n < maxUsed; n++) {
            if (resL[n] != 0) {
                dscfFlagL[n] = scfSv8(scfIndexL[n], scfiL[n], dscfFlagL[n]);
            }
            if (resR[n] != 0) {
                dscfFlagR[n] = scfSv8(scfIndexR[n], scfiR[n], dscfFlagR[n]);
            }
        }
        for (var n = 0; n < maxUsed; n++) {
            samplesSv8(qL[n], resL[n]);
            samplesSv8(qR[n], resR[n]);
        }
    }

    /**
     * Reads the scale factors of one band and channel of a version 8 frame.
     *
     * @return the new flag forcing the next scale factor to be coded absolutely
     */
    private boolean scfSv8(int[] scf, int scfi, boolean absolute) {
        if (absolute) {
            scf[0] = read(7) - 6;
        } else {
            var value = canonical(CAN_DSCF_2, SYM_DSCF_2);
            if (value == 64) {
                value += read(6);
            }
            scf[0] = ((scf[2] - 25 + value) & 127) - 6;
        }
        for (var m = 0; m < 2; m++) {
            if (((scfi << m) & 2) == 0) {
                var value = canonical(CAN_DSCF_1, SYM_DSCF_1);
                if (value == 31) {
                    value = 64 + read(6);
                }
                scf[m + 1] = ((scf[m] - 25 + value) & 127) - 6;
            } else {
                scf[m + 1] = scf[m];
            }
        }
        return false;
    }

    private void samplesSv8(short[] q, int res) {
        if (res == 0) {
            return;
        }
        if (res == 2) {
            var idx = 2 * THRES[res];
            for (var k = 0; k < 36; k += 3) {
                var table = idx > THRES[res] ? 1 : 0;
                var value = canonical(CAN_Q[table], SYM_Q[table]);
                checkIndex(value, 125);
                q[k] = (short) IDX50[value];
                q[k + 1] = (short) IDX51[value];
                q[k + 2] = (short) IDX52[value];
                idx = (idx >>> 1) + HUFFQ2_VAR[value];
            }
        } else if (res == 1) {
            for (var k = 0; k < 36; ) {
                var kmax = k + 18;
                var count = canonical(CAN_Q1, SYM_Q1);
                var idx = 0;
                if (count > 0 && count < 18) {
                    idx = enumerated(count <= 9 ? count : 18 - count, 18);
                }
                if (count > 9) {
                    idx = ~idx;
                }
                for (; k < kmax; k++) {
                    q[k] = 0;
                    if ((idx & (1 << 17)) != 0) {
                        q[k] = (short) ((read(1) << 1) - 1);
                    }
                    idx <<= 1;
                }
            }
        } else if (res == -1) {
            for (var k = 0; k < 36; k++) {
                q[k] = noise(random());
            }
        } else if (res <= 4) {
            if (res < 0) {
                throw new MediaException.Malformed("invalid Musepack resolution " + res);
            }
            var table = 2 + res - 3;
            for (var k = 0; k < 36; k += 2) {
                var symbol = canonical(CAN_Q[table], SYM_Q[table]);
                q[k] = (short) (symbol << 28 >> 28);
                q[k + 1] = (short) ((byte) symbol >> 4);
            }
        } else if (res <= 8) {
            var first = 2 * (res - 3);
            var idx = 2 * THRES[res];
            for (var k = 0; k < 36; k++) {
                var table = first + (idx > THRES[res] ? 1 : 0);
                q[k] = (short) canonical(CAN_Q[table], SYM_Q[table]);
                idx = (idx >>> 1) + Math.abs(q[k]);
            }
        } else {
            if (res > 17) {
                throw new MediaException.Malformed("invalid Musepack resolution " + res);
            }
            for (var k = 0; k < 36; k++) {
                var value = canonical(CAN_Q9UP, SYM_Q9UP) & 0xFF;
                if (res != 9) {
                    value = (short) ((short) value << (res - 9) | read(res - 9));
                }
                q[k] = (short) ((short) value - DC[res + 1]);
            }
        }
    }

    /**
     * Requantizes the subband samples of the frame read last ({@code mpc_decoder_requantisierung}).
     */
    void requantize() {
        for (var band = 0; band <= maxBand; band++) {
            var l = qL[band];
            var r = qR[band];
            var resLeft = resL[band];
            var resRight = resR[band];
            if (resLeft < -1 || resLeft > 17 || resRight < -1 || resRight > 17) {
                throw new MediaException.Malformed("invalid Musepack resolution");
            }
            if (msFlag[band]) {
                if (resLeft != 0) {
                    if (resRight != 0) {
                        for (var part = 0; part < 3; part++) {
                            var facL = CC[resLeft + 1] * scf[scfIndexL[band][part] & 0xFF];
                            var facR = CC[resRight + 1] * scf[scfIndexR[band][part] & 0xFF];
                            for (var n = part * 12; n < part * 12 + 12; n++) {
                                var templ = facL * l[n];
                                var tempr = facR * r[n];
                                yL[n * 32 + band] = templ + tempr;
                                yR[n * 32 + band] = templ - tempr;
                            }
                        }
                    } else {
                        for (var part = 0; part < 3; part++) {
                            var facL = CC[resLeft + 1] * scf[scfIndexL[band][part] & 0xFF];
                            for (var n = part * 12; n < part * 12 + 12; n++) {
                                var value = facL * l[n];
                                yL[n * 32 + band] = value;
                                yR[n * 32 + band] = value;
                            }
                        }
                    }
                } else if (resRight != 0) {
                    for (var part = 0; part < 3; part++) {
                        var facR = CC[resRight + 1] * scf[scfIndexR[band][part] & 0xFF];
                        for (var n = part * 12; n < part * 12 + 12; n++) {
                            var value = facR * r[n];
                            yL[n * 32 + band] = value;
                            yR[n * 32 + band] = -value;
                        }
                    }
                } else {
                    for (var n = 0; n < 36; n++) {
                        yL[n * 32 + band] = 0;
                        yR[n * 32 + band] = 0;
                    }
                }
            } else {
                for (var part = 0; part < 3; part++) {
                    var facL = resLeft != 0 ? CC[resLeft + 1] * scf[scfIndexL[band][part] & 0xFF] : 0f;
                    var facR = resRight != 0 ? CC[resRight + 1] * scf[scfIndexR[band][part] & 0xFF] : 0f;
                    for (var n = part * 12; n < part * 12 + 12; n++) {
                        yL[n * 32 + band] = resLeft != 0 ? facL * l[n] : 0;
                        yR[n * 32 + band] = resRight != 0 ? facR * r[n] : 0;
                    }
                }
            }
        }
    }

    /**
     * Runs the synthesis filter bank over the requantized frame ({@code mpc_decoder_synthese_filter_float}).
     *
     * @param output one plane per channel, receiving 1152 samples from {@code offset}
     * @param offset the index of the first sample
     */
    void synthesize(float[][] output, int offset) {
        System.arraycopy(vL, 0, vL, V_MEM, 960);
        synthesize(output[0], offset, vL, yL);
        if (channels > 1) {
            System.arraycopy(vR, 0, vR, V_MEM, 960);
            synthesize(output[1], offset, vR, yR);
        }
    }

    private static void synthesize(float[] out, int offset, float[] v, float[] y) {
        var pv = V_MEM;
        var at = offset;
        for (var n = 0; n < 36; n++) {
            pv -= 64;
            computeNewV(y, n * 32, v, pv);
            for (var k = 0; k < 32; k++, pv++) {
                var d = k * 16;
                out[at + k] = v[pv] * DI[d] + v[pv + 96] * DI[d + 1] + v[pv + 128] * DI[d + 2]
                        + v[pv + 224] * DI[d + 3] + v[pv + 256] * DI[d + 4] + v[pv + 352] * DI[d + 5]
                        + v[pv + 384] * DI[d + 6] + v[pv + 480] * DI[d + 7] + v[pv + 512] * DI[d + 8]
                        + v[pv + 608] * DI[d + 9] + v[pv + 640] * DI[d + 10] + v[pv + 736] * DI[d + 11]
                        + v[pv + 768] * DI[d + 12] + v[pv + 864] * DI[d + 13] + v[pv + 896] * DI[d + 14]
                        + v[pv + 992] * DI[d + 15];
            }
            pv -= 32;
            at += 32;
        }
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    private static void computeNewV(float[] s, int o, float[] v, int p) {
        float a00, a01, a02, a03, a04, a05, a06, a07, a08, a09, a10, a11, a12, a13, a14, a15;
        float b00, b01, b02, b03, b04, b05, b06, b07, b08, b09, b10, b11, b12, b13, b14, b15;
        float tmp;
        a00 = s[o] + s[o + 31];
        a01 = s[o + 1] + s[o + 30];
        a02 = s[o + 2] + s[o + 29];
        a03 = s[o + 3] + s[o + 28];
        a04 = s[o + 4] + s[o + 27];
        a05 = s[o + 5] + s[o + 26];
        a06 = s[o + 6] + s[o + 25];
        a07 = s[o + 7] + s[o + 24];
        a08 = s[o + 8] + s[o + 23];
        a09 = s[o + 9] + s[o + 22];
        a10 = s[o + 10] + s[o + 21];
        a11 = s[o + 11] + s[o + 20];
        a12 = s[o + 12] + s[o + 19];
        a13 = s[o + 13] + s[o + 18];
        a14 = s[o + 14] + s[o + 17];
        a15 = s[o + 15] + s[o + 16];
        b00 = a00 + a15;
        b01 = a01 + a14;
        b02 = a02 + a13;
        b03 = a03 + a12;
        b04 = a04 + a11;
        b05 = a05 + a10;
        b06 = a06 + a09;
        b07 = a07 + a08;
        b08 = (a00 - a15) * 0.5024192929f;
        b09 = (a01 - a14) * 0.5224986076f;
        b10 = (a02 - a13) * 0.5669440627f;
        b11 = (a03 - a12) * 0.6468217969f;
        b12 = (a04 - a11) * 0.7881546021f;
        b13 = (a05 - a10) * 1.0606776476f;
        b14 = (a06 - a09) * 1.7224471569f;
        b15 = (a07 - a08) * 5.1011486053f;
        a00 = b00 + b07;
        a01 = b01 + b06;
        a02 = b02 + b05;
        a03 = b03 + b04;
        a04 = (b00 - b07) * 0.5097956061f;
        a05 = (b01 - b06) * 0.6013448834f;
        a06 = (b02 - b05) * 0.8999761939f;
        a07 = (b03 - b04) * 2.5629155636f;
        a08 = b08 + b15;
        a09 = b09 + b14;
        a10 = b10 + b13;
        a11 = b11 + b12;
        a12 = (b08 - b15) * 0.5097956061f;
        a13 = (b09 - b14) * 0.6013448834f;
        a14 = (b10 - b13) * 0.8999761939f;
        a15 = (b11 - b12) * 2.5629155636f;
        b00 = a00 + a03;
        b01 = a01 + a02;
        b02 = (a00 - a03) * 0.5411961079f;
        b03 = (a01 - a02) * 1.3065630198f;
        b04 = a04 + a07;
        b05 = a05 + a06;
        b06 = (a04 - a07) * 0.5411961079f;
        b07 = (a05 - a06) * 1.3065630198f;
        b08 = a08 + a11;
        b09 = a09 + a10;
        b10 = (a08 - a11) * 0.5411961079f;
        b11 = (a09 - a10) * 1.3065630198f;
        b12 = a12 + a15;
        b13 = a13 + a14;
        b14 = (a12 - a15) * 0.5411961079f;
        b15 = (a13 - a14) * 1.3065630198f;
        a00 = b00 + b01;
        a01 = (b00 - b01) * 0.7071067691f;
        a02 = b02 + b03;
        a03 = (b02 - b03) * 0.7071067691f;
        a04 = b04 + b05;
        a05 = (b04 - b05) * 0.7071067691f;
        a06 = b06 + b07;
        a07 = (b06 - b07) * 0.7071067691f;
        a08 = b08 + b09;
        a09 = (b08 - b09) * 0.7071067691f;
        a10 = b10 + b11;
        a11 = (b10 - b11) * 0.7071067691f;
        a12 = b12 + b13;
        a13 = (b12 - b13) * 0.7071067691f;
        a14 = b14 + b15;
        a15 = (b14 - b15) * 0.7071067691f;
        v[p + 48] = -a00;
        v[p] = a01;
        v[p + 8] = a03;
        v[p + 40] = -a02 - v[p + 8];
        v[p + 12] = a07;
        v[p + 4] = a05 + v[p + 12];
        v[p + 36] = -(v[p + 4] + a06);
        v[p + 44] = -a04 - a06 - a07;
        v[p + 14] = a15;
        v[p + 10] = a11 + v[p + 14];
        v[p + 6] = v[p + 10] + a13;
        v[p + 2] = a09 + a13 + a15;
        v[p + 34] = -v[p + 2] - a14;
        v[p + 38] = v[p + 34] + a09 - a10 - a11;
        tmp = -(a12 + a14 + a15);
        v[p + 46] = tmp - a08;
        v[p + 42] = tmp - a10 - a11;
        a00 = (s[o] - s[o + 31]) * 0.5006030202f;
        a01 = (s[o + 1] - s[o + 30]) * 0.5054709315f;
        a02 = (s[o + 2] - s[o + 29]) * 0.5154473186f;
        a03 = (s[o + 3] - s[o + 28]) * 0.5310425758f;
        a04 = (s[o + 4] - s[o + 27]) * 0.5531039238f;
        a05 = (s[o + 5] - s[o + 26]) * 0.5829349756f;
        a06 = (s[o + 6] - s[o + 25]) * 0.6225041151f;
        a07 = (s[o + 7] - s[o + 24]) * 0.6748083234f;
        a08 = (s[o + 8] - s[o + 23]) * 0.7445362806f;
        a09 = (s[o + 9] - s[o + 22]) * 0.8393496275f;
        a10 = (s[o + 10] - s[o + 21]) * 0.9725682139f;
        a11 = (s[o + 11] - s[o + 20]) * 1.1694399118f;
        a12 = (s[o + 12] - s[o + 19]) * 1.4841645956f;
        a13 = (s[o + 13] - s[o + 18]) * 2.0577809811f;
        a14 = (s[o + 14] - s[o + 17]) * 3.4076085091f;
        a15 = (s[o + 15] - s[o + 16]) * 10.1900081635f;
        b00 = a00 + a15;
        b01 = a01 + a14;
        b02 = a02 + a13;
        b03 = a03 + a12;
        b04 = a04 + a11;
        b05 = a05 + a10;
        b06 = a06 + a09;
        b07 = a07 + a08;
        b08 = (a00 - a15) * 0.5024192929f;
        b09 = (a01 - a14) * 0.5224986076f;
        b10 = (a02 - a13) * 0.5669440627f;
        b11 = (a03 - a12) * 0.6468217969f;
        b12 = (a04 - a11) * 0.7881546021f;
        b13 = (a05 - a10) * 1.0606776476f;
        b14 = (a06 - a09) * 1.7224471569f;
        b15 = (a07 - a08) * 5.1011486053f;
        a00 = b00 + b07;
        a01 = b01 + b06;
        a02 = b02 + b05;
        a03 = b03 + b04;
        a04 = (b00 - b07) * 0.5097956061f;
        a05 = (b01 - b06) * 0.6013448834f;
        a06 = (b02 - b05) * 0.8999761939f;
        a07 = (b03 - b04) * 2.5629155636f;
        a08 = b08 + b15;
        a09 = b09 + b14;
        a10 = b10 + b13;
        a11 = b11 + b12;
        a12 = (b08 - b15) * 0.5097956061f;
        a13 = (b09 - b14) * 0.6013448834f;
        a14 = (b10 - b13) * 0.8999761939f;
        a15 = (b11 - b12) * 2.5629155636f;
        b00 = a00 + a03;
        b01 = a01 + a02;
        b02 = (a00 - a03) * 0.5411961079f;
        b03 = (a01 - a02) * 1.3065630198f;
        b04 = a04 + a07;
        b05 = a05 + a06;
        b06 = (a04 - a07) * 0.5411961079f;
        b07 = (a05 - a06) * 1.3065630198f;
        b08 = a08 + a11;
        b09 = a09 + a10;
        b10 = (a08 - a11) * 0.5411961079f;
        b11 = (a09 - a10) * 1.3065630198f;
        b12 = a12 + a15;
        b13 = a13 + a14;
        b14 = (a12 - a15) * 0.5411961079f;
        b15 = (a13 - a14) * 1.3065630198f;
        a00 = b00 + b01;
        a01 = (b00 - b01) * 0.7071067691f;
        a02 = b02 + b03;
        a03 = (b02 - b03) * 0.7071067691f;
        a04 = b04 + b05;
        a05 = (b04 - b05) * 0.7071067691f;
        a06 = b06 + b07;
        a07 = (b06 - b07) * 0.7071067691f;
        a08 = b08 + b09;
        a09 = (b08 - b09) * 0.7071067691f;
        a10 = b10 + b11;
        a11 = (b10 - b11) * 0.7071067691f;
        a12 = b12 + b13;
        a13 = (b12 - b13) * 0.7071067691f;
        a14 = b14 + b15;
        a15 = (b14 - b15) * 0.7071067691f;
        v[p + 15] = a15;
        v[p + 13] = a07 + v[p + 15];
        v[p + 11] = v[p + 13] + a11;
        v[p + 5] = v[p + 11] + a05 + a13;
        v[p + 9] = a03 + a11 + a15;
        v[p + 7] = v[p + 9] + a13;
        v[p + 1] = a01 + a09 + a13 + a15;
        v[p + 33] = -v[p + 1] - a14;
        v[p + 3] = a05 + a07 + a09 + a13 + a15;
        v[p + 35] = -v[p + 3] - a06 - a14;
        tmp = -(a10 + a11 + a13 + a14 + a15);
        v[p + 37] = tmp - a05 - a06 - a07;
        v[p + 39] = tmp - a02 - a03;
        tmp += a13 - a12;
        v[p + 41] = tmp - a02 - a03;
        v[p + 43] = tmp - a04 - a06 - a07;
        tmp = -(a08 + a12 + a14 + a15);
        v[p + 47] = tmp - a00;
        v[p + 45] = tmp - a04 - a06 - a07;
        for (var i = 0; i < 16; i++) {
            v[p + 32 - i] = -v[p + i];
        }
        for (var i = 33; i <= 47; i++) {
            v[p + 96 - i] = v[p + i];
        }
    }
}
