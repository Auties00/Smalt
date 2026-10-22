package com.github.auties00.smalt.video.mpeg4;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Writes MPEG-4 Advanced Simple profile elementary streams that exercise interlaced direct mode
 * (ISO/IEC 14496-2 7.7.2.2) in isolation: an I-VOP taken from another encoder, then P-VOPs whose
 * only coded macroblocks are field predicted with chosen vectors and field references and no
 * residual, and B-VOPs whose macroblocks co-located with those are direct coded with chosen delta
 * vectors and no residual. Every other macroblock is skipped, so a B-VOP is the co-located
 * macroblocks of its forward reference plus the predictions of interlaced direct mode.
 */
final class InterlacedDirectStream {
    /**
     * A field-predicted macroblock of a P-VOP: its position, the reference field of each field (0
     * for the top field of the reference, 1 for the bottom) and the field vectors, horizontal in
     * half samples and vertical in half field lines.
     */
    record FieldBlock(int mx, int my, int topRef, int bottomRef, int topX, int topY, int bottomX, int bottomY) {
    }

    /**
     * The delta vector of a direct macroblock of a B-VOP, in half samples.
     */
    record Delta(int x, int y) {
    }

    /**
     * A VOP after the I-VOP: its type, time and field order, the field-predicted macroblocks of a
     * P-VOP or of the backward reference of a B-VOP, and the delta vectors of a B-VOP's direct
     * macroblocks in the order of those, with {@code null} elements for {@code modb == '1'}.
     */
    record Picture(boolean b, int time, boolean topFieldFirst, List<FieldBlock> blocks, List<Delta> deltas) {
    }

    // Table B-12 for motion_code 0 to 16, without the sign bit.
    private static final String[] MVD_CODES = {
            "1", "01", "001", "0001", "000011", "0000101", "0000100", "0000011", "000001011", "000001010",
            "000001001", "0000010001", "0000010000", "0000001111", "0000001110", "0000001101", "0000001100"
    };

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final List<Picture> pictures = new ArrayList<>();
    private final int mbWidth;
    private final int mbHeight;
    private final int timeBits;
    private int current;
    private int count;
    private List<FieldBlock> fieldBlocks = List.of();

    /**
     * Constructs a stream that starts with the given headers and I-VOP, at time zero.
     *
     * @param intro the visual object sequence up to the end of the first VOP
     * @param width the luminance width
     * @param height the luminance height
     * @param resolution the {@code vop_time_increment_resolution} of the video object layer
     */
    InterlacedDirectStream(byte[] intro, int width, int height, int resolution) {
        out.writeBytes(intro);
        mbWidth = (width + 15) / 16;
        mbHeight = (height + 15) / 16;
        timeBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(resolution - 1));
    }

    /**
     * Returns the headers and the first VOP of an elementary stream, which end where the second
     * {@code vop_start_code} begins.
     */
    static byte[] intro(byte[] stream) {
        var vops = 0;
        for (var i = 0; i + 3 < stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1 && (stream[i + 3] & 0xFF) == 0xB6
                    && ++vops == 2) {
                return Arrays.copyOf(stream, i);
            }
        }
        return stream;
    }

    /**
     * Returns six field-predicted macroblocks three macroblocks apart, so that none predicts the
     * vector of another, with random references and vectors.
     */
    static List<FieldBlock> randomBlocks(Random random) {
        var blocks = new ArrayList<FieldBlock>();
        for (var my : new int[]{2, 5}) {
            for (var mx : new int[]{2, 5, 8}) {
                blocks.add(new FieldBlock(mx, my, random.nextInt(2), random.nextInt(2), random.nextInt(25) - 12,
                        random.nextInt(29) - 14, random.nextInt(25) - 12, random.nextInt(29) - 14));
            }
        }
        return blocks;
    }

    /**
     * Returns six random delta vectors: absent, horizontal only, vertical only or both.
     */
    static List<Delta> randomDeltas(Random random) {
        var deltas = new ArrayList<Delta>();
        for (var i = 0; i < 6; i++) {
            deltas.add(switch (random.nextInt(4)) {
                case 0 -> null;
                case 1 -> new Delta(random.nextInt(7) - 3, 0);
                case 2 -> new Delta(0, random.nextInt(7) - 3);
                default -> new Delta(random.nextInt(7) - 3, random.nextInt(7) - 3);
            });
        }
        return deltas;
    }

    /**
     * Appends a P-VOP whose only coded macroblocks are the given field-predicted ones.
     *
     * @param time the VOP time in ticks, less than the resolution
     */
    InterlacedDirectStream p(int time, boolean topFieldFirst, List<FieldBlock> blocks) {
        header(1, time, topFieldFirst);
        for (var my = 0; my < mbHeight; my++) {
            for (var mx = 0; mx < mbWidth; mx++) {
                var block = find(blocks, mx, my);
                if (block == null) {
                    put(1, 1);
                    continue;
                }
                // not_coded, mcbpc (inter, cbpc 0), cbpy (none coded), field_prediction, references.
                put(0, 1);
                put(1, 1);
                put(0b11, 2);
                put(1, 1);
                put(block.topRef(), 1);
                put(block.bottomRef(), 1);
                vector(block.topX());
                vector(block.topY());
                vector(block.bottomX());
                vector(block.bottomY());
            }
        }
        align();
        fieldBlocks = List.copyOf(blocks);
        pictures.add(new Picture(false, time, topFieldFirst, fieldBlocks, List.of()));
        return this;
    }

    /**
     * Appends a B-VOP whose macroblocks co-located with the field-predicted macroblocks of the last
     * P-VOP are direct coded.
     *
     * @param time the VOP time in ticks, less than the resolution
     * @param deltas the delta vector of each field-predicted macroblock, in their order, with
     *        {@code null} elements for direct macroblocks without one
     */
    InterlacedDirectStream b(int time, boolean topFieldFirst, List<Delta> deltas) {
        header(2, time, topFieldFirst);
        for (var my = 0; my < mbHeight; my++) {
            for (var mx = 0; mx < mbWidth; mx++) {
                var block = find(fieldBlocks, mx, my);
                if (block == null) {
                    continue;
                }
                var delta = deltas.get(fieldBlocks.indexOf(block));
                if (delta == null) {
                    put(1, 1);
                    continue;
                }
                // modb '01' (mb_type without cbpb), mb_type direct, then the delta vector.
                put(0b01, 2);
                put(1, 1);
                vector(delta.x());
                vector(delta.y());
            }
        }
        align();
        pictures.add(new Picture(true, time, topFieldFirst, fieldBlocks, new ArrayList<>(deltas)));
        return this;
    }

    /**
     * Returns the VOPs after the I-VOP, in decoding order.
     */
    List<Picture> pictures() {
        return List.copyOf(pictures);
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    private static FieldBlock find(List<FieldBlock> blocks, int mx, int my) {
        for (var block : blocks) {
            if (block.mx() == mx && block.my() == my) {
                return block;
            }
        }
        return null;
    }

    private void header(int type, int time, boolean topFieldFirst) {
        out.writeBytes(new byte[]{0, 0, 1, (byte) 0xB6});
        // vop_coding_type, modulo_time_base '0', marker, vop_time_increment, marker, vop_coded.
        put(type, 2);
        put(0, 1);
        put(1, 1);
        put(time, timeBits);
        put(1, 1);
        put(1, 1);
        if (type == 1) {
            put(0, 1);
        }
        // intra_dc_vlc_thr, top_field_first, alternate_vertical_scan_flag, vop_quant, f_codes of 1.
        put(0, 3);
        put(topFieldFirst ? 1 : 0, 1);
        put(0, 1);
        put(4, 5);
        put(1, 3);
        if (type == 2) {
            put(1, 3);
        }
    }

    private void vector(int value) {
        if (value < -16 || value > 16) {
            throw new IllegalArgumentException("vector component out of range: " + value);
        }
        var code = MVD_CODES[Math.abs(value)];
        for (var i = 0; i < code.length(); i++) {
            put(code.charAt(i) - '0', 1);
        }
        if (value != 0) {
            put(value < 0 ? 1 : 0, 1);
        }
    }

    private void align() {
        put(0, 1);
        while (count != 0) {
            put(1, 1);
        }
    }

    private void put(int value, int width) {
        for (var bit = width - 1; bit >= 0; bit--) {
            current = current << 1 | (value >>> bit) & 1;
            if (++count == 8) {
                out.write(current);
                current = 0;
                count = 0;
            }
        }
    }
}
