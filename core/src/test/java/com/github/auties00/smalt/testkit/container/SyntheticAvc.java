package com.github.auties00.smalt.testkit.container;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Writes small decodable H.264 Annex B streams whose slice headers are chosen picture by picture: frame or field
 * pictures of one macroblock, IDR pictures of {@code I_PCM} macroblocks and P pictures of skipped macroblocks, with
 * any {@code frame_num}, {@code nal_ref_idc}, picture order count, memory management control operations and
 * {@code no_output_of_prior_pics_flag}. They drive the reference marking and decoded picture buffer model of a
 * demuxer or a decoder through cases encoders do not produce, such as one field of a frame released while the
 * other stays a reference, or a gap in {@code frame_num}.
 *
 * <p>The streams are Main profile with CAVLC, {@code pic_order_cnt_type} 0 with 6-bit order count LSBs,
 * {@code MaxFrameNum} 16 and a VUI with timing information (25 frames per second) and the bitstream restriction
 * that sets the reorder depth and the decoded picture buffer size. A frame stream codes 16x16 frames; a field
 * stream codes 16x32 frames as field pictures.
 */
public final class SyntheticAvc {
    /**
     * The structure of a frame picture.
     */
    public static final int FRAME = 0;

    /**
     * The structure of a top field picture.
     */
    public static final int TOP = 1;

    /**
     * The structure of a bottom field picture.
     */
    public static final int BOTTOM = 2;

    private static final int LOG2_MAX_FRAME_NUM = 4;

    private static final int LOG2_MAX_POC_LSB = 6;

    private SyntheticAvc() {
        throw new AssertionError();
    }

    /**
     * One coded picture.
     *
     * @param idr whether the picture is an IDR picture, coded as {@code I_PCM}; otherwise a P picture of skipped
     * macroblocks
     * @param reference whether {@code nal_ref_idc} is not zero
     * @param structure {@link #FRAME}, {@link #TOP} or {@link #BOTTOM}
     * @param frameNum {@code frame_num}, from 0 to 15
     * @param pocLsb {@code pic_order_cnt_lsb}, from 0 to 63
     * @param idrPicId {@code idr_pic_id} of an IDR picture
     * @param noOutputOfPriorPics {@code no_output_of_prior_pics_flag} of an IDR picture
     * @param operations the memory management control operations of a non-IDR reference picture as triples of the
     * operation and its two values (the second used only by operation 3), or {@code null} for sliding window
     * marking
     */
    public record Picture(boolean idr, boolean reference, int structure, int frameNum, int pocLsb, int idrPicId,
                          boolean noOutputOfPriorPics, int[] operations) {
        /**
         * Validates the fields.
         */
        public Picture {
            if (structure < FRAME || structure > BOTTOM || frameNum < 0 || frameNum >= 1 << LOG2_MAX_FRAME_NUM
                    || pocLsb < 0 || pocLsb >= 1 << LOG2_MAX_POC_LSB || (idr && (!reference || frameNum != 0))
                    || (operations != null && (idr || !reference || operations.length % 3 != 0))) {
                throw new IllegalArgumentException("invalid picture");
            }
            operations = operations == null ? null : operations.clone();
        }

        /**
         * Returns an IDR picture.
         *
         * @param structure {@link #FRAME}, {@link #TOP} or {@link #BOTTOM}
         * @param pocLsb {@code pic_order_cnt_lsb}
         * @param idrPicId {@code idr_pic_id}
         * @param noOutputOfPriorPics {@code no_output_of_prior_pics_flag}
         * @return the picture
         */
        public static Picture idr(int structure, int pocLsb, int idrPicId, boolean noOutputOfPriorPics) {
            return new Picture(true, true, structure, 0, pocLsb, idrPicId, noOutputOfPriorPics, null);
        }

        /**
         * Returns a P picture marked by the sliding window when it is a reference.
         *
         * @param structure {@link #FRAME}, {@link #TOP} or {@link #BOTTOM}
         * @param frameNum {@code frame_num}
         * @param pocLsb {@code pic_order_cnt_lsb}
         * @param reference whether the picture is a reference picture
         * @return the picture
         */
        public static Picture p(int structure, int frameNum, int pocLsb, boolean reference) {
            return new Picture(false, reference, structure, frameNum, pocLsb, 0, false, null);
        }

        /**
         * Returns a reference P picture with adaptive marking.
         *
         * @param structure {@link #FRAME}, {@link #TOP} or {@link #BOTTOM}
         * @param frameNum {@code frame_num}
         * @param pocLsb {@code pic_order_cnt_lsb}
         * @param operations the operations, as triples of the operation and its two values
         * @return the picture
         */
        public static Picture adaptive(int structure, int frameNum, int pocLsb, int... operations) {
            return new Picture(false, true, structure, frameNum, pocLsb, 0, false, operations);
        }

        @Override
        public int[] operations() {
            return operations == null ? null : operations.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Picture picture && idr == picture.idr && reference == picture.reference
                    && structure == picture.structure && frameNum == picture.frameNum && pocLsb == picture.pocLsb
                    && idrPicId == picture.idrPicId && noOutputOfPriorPics == picture.noOutputOfPriorPics
                    && Arrays.equals(operations, picture.operations);
        }

        @Override
        public int hashCode() {
            return Objects.hash(idr, reference, structure, frameNum, pocLsb, idrPicId, noOutputOfPriorPics)
                    * 31 + Arrays.hashCode(operations);
        }

        @Override
        public String toString() {
            return (idr ? "IDR" : "P") + (reference ? "" : " non-reference") + " "
                    + (structure == FRAME ? "frame" : structure == TOP ? "top" : "bottom") + " frame_num "
                    + frameNum + " poc " + pocLsb + (operations == null ? "" : " mmco " + Arrays.toString(operations));
        }
    }

    /**
     * A stream whose second IDR picture discards the pictures still waiting for output, and the frames it outputs
     * when the decoded picture buffer follows ITU-T H.264 clauses 8.2.5 and C.4.
     *
     * @param name the name
     * @param stream the Annex B stream
     * @param shown for each frame (a frame picture or a pair of fields) in decoding order, whether it is output
     */
    public record Scenario(String name, byte[] stream, List<Boolean> shown) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Returns the streams whose output depends on the reference marking of single fields and on the frames
     * inferred for gaps in {@code frame_num}, with the frames output as derived by hand from the standard: a model
     * that marks field pairs as frames, or ignores the gaps, outputs fewer.
     *
     * <ul>
     *   <li>{@code gap}: one reference frame and a buffer of two; a non-reference frame skips
     *       {@code frame_num} 2 to 4, the frame inferred for 4 fills the buffer and outputs a waiting frame, so the
     *       reference frame before the gap is output before the discarding IDR picture instead of discarded.</li>
     *   <li>{@code gap-wrap}: two reference frames and a buffer of three; a non-reference frame takes
     *       {@code frame_num} 0 after 1, a gap of 14 that wraps, whose last two inferred frames push both reference
     *       frames out of the sliding window, so the frame is output at once instead of waiting and being
     *       discarded.</li>
     *   <li>{@code field-mmco1}: three reference frames and a buffer of three, field pictures; the second field
     *       of the second frame releases the top field of the first frame with operation 1, so the first frame
     *       stays a reference by its bottom field, fills the buffer and makes the frame waiting before the
     *       discarding IDR picture leave first.</li>
     *   <li>{@code field-long-term}: the same through long-term marking: the first field of the second frame
     *       turns the top field of the first frame into a long-term field with operations 4 and 3, and its second
     *       field releases it with operation 2.</li>
     * </ul>
     *
     * @return the scenarios
     */
    public static List<Scenario> markingScenarios() {
        return List.of(
                new Scenario("gap", stream(false, 1, 2, 2, true, List.of(
                        Picture.idr(FRAME, 0, 0, false),
                        Picture.p(FRAME, 1, 8, true),
                        Picture.p(FRAME, 2, 4, false),
                        Picture.p(FRAME, 5, 12, false),
                        Picture.idr(FRAME, 0, 1, true),
                        Picture.p(FRAME, 1, 4, true))),
                        List.of(true, true, true, false, true, true)),
                new Scenario("gap-wrap", stream(false, 2, 3, 3, true, List.of(
                        Picture.idr(FRAME, 0, 0, false),
                        Picture.p(FRAME, 1, 20, true),
                        Picture.p(FRAME, 2, 4, false),
                        Picture.p(FRAME, 2, 8, false),
                        Picture.p(FRAME, 0, 12, false),
                        Picture.idr(FRAME, 0, 1, true),
                        Picture.p(FRAME, 1, 4, true))),
                        List.of(true, false, true, true, true, true, true)),
                new Scenario("field-mmco1", fieldStream(
                        Picture.p(TOP, 1, 16, true),
                        Picture.adaptive(BOTTOM, 1, 17, 1, 2, 0)),
                        List.of(true, false, true, false, true, true)),
                new Scenario("field-long-term", fieldStream(
                        Picture.adaptive(TOP, 1, 16, 4, 1, 0, 3, 1, 0),
                        Picture.adaptive(BOTTOM, 1, 17, 2, 0, 0)),
                        List.of(true, false, true, false, true, true)));
    }

    /**
     * Writes the field stream of the field scenarios around the two fields of its second frame.
     */
    private static byte[] fieldStream(Picture secondTop, Picture secondBottom) {
        return stream(true, 3, 3, 3, false, List.of(
                Picture.idr(TOP, 0, 0, false),
                Picture.p(BOTTOM, 0, 1, true),
                secondTop,
                secondBottom,
                Picture.p(TOP, 2, 4, false),
                Picture.p(BOTTOM, 2, 5, false),
                Picture.p(TOP, 2, 8, false),
                Picture.p(BOTTOM, 2, 9, false),
                Picture.idr(TOP, 0, 1, true),
                Picture.p(BOTTOM, 0, 1, true),
                Picture.p(TOP, 1, 4, true),
                Picture.p(BOTTOM, 1, 5, true)));
    }

    /**
     * Writes a stream: a sequence and a picture parameter set, then one access unit per picture.
     *
     * @param fields whether the frames are coded as field pictures ({@code frame_mbs_only_flag} 0); every picture
     * of such a stream must be a field
     * @param refFrames {@code max_num_ref_frames}
     * @param reorderFrames {@code max_num_reorder_frames}
     * @param dpbFrames {@code max_dec_frame_buffering}, at least {@code refFrames}
     * @param gapsAllowed {@code gaps_in_frame_num_value_allowed_flag}
     * @param pictures the pictures in decoding order
     * @return the Annex B stream
     */
    public static byte[] stream(boolean fields, int refFrames, int reorderFrames, int dpbFrames, boolean gapsAllowed,
                                List<Picture> pictures) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(nal(0x67, sps(fields, refFrames, reorderFrames, dpbFrames, gapsAllowed)));
        out.writeBytes(nal(0x68, pps()));
        var sample = 0;
        for (var picture : pictures) {
            if (fields == (picture.structure() == FRAME)) {
                throw new IllegalArgumentException("a field stream codes fields, a frame stream frames");
            }
            var header = (picture.reference() ? 0x60 : 0) | (picture.idr() ? 5 : 1);
            out.writeBytes(nal(header, slice(fields, picture, 48 + 16 * (sample++ % 10))));
        }
        return out.toByteArray();
    }

    private static Bits sps(boolean fields, int refFrames, int reorderFrames, int dpbFrames, boolean gapsAllowed) {
        var bits = new Bits().bits(77, 8).bits(0, 8).bits(30, 8).ue(0).ue(LOG2_MAX_FRAME_NUM - 4).ue(0)
                .ue(LOG2_MAX_POC_LSB - 4).ue(refFrames).bits(gapsAllowed ? 1 : 0, 1).ue(0).ue(0)
                .bits(fields ? 0 : 1, 1);
        if (fields) {
            bits.bits(0, 1);
        }
        // direct_8x8_inference_flag, frame_cropping_flag, vui_parameters_present_flag
        bits.bits(1, 1).bits(0, 1).bits(1, 1);
        // no aspect ratio, overscan, video signal or chroma location information; 25 frames per second
        bits.bits(0, 4).bits(1, 1).bits(1, 32).bits(50, 32).bits(1, 1);
        // no HRD parameters nor pic_struct; the bitstream restriction
        bits.bits(0, 3).bits(1, 1).bits(1, 1).ue(0).ue(0).ue(16).ue(16).ue(reorderFrames).ue(dpbFrames);
        return bits;
    }

    private static Bits pps() {
        // CAVLC, one slice group, one default reference index per list, no weighted prediction, QP 26, the
        // deblocking filter on, no constrained intra prediction nor redundant pictures
        return new Bits().ue(0).ue(0).bits(0, 1).bits(0, 1).ue(0).ue(0).ue(0).bits(0, 1).bits(0, 2).se(0).se(0)
                .se(0).bits(0, 1).bits(0, 1).bits(0, 1);
    }

    private static Bits slice(boolean fields, Picture picture, int sample) {
        var bits = new Bits().ue(0).ue(picture.idr() ? 7 : 5).ue(0).bits(picture.frameNum(), LOG2_MAX_FRAME_NUM);
        if (fields) {
            bits.bits(1, 1).bits(picture.structure() == BOTTOM ? 1 : 0, 1);
        }
        if (picture.idr()) {
            bits.ue(picture.idrPicId());
        }
        bits.bits(picture.pocLsb(), LOG2_MAX_POC_LSB);
        if (!picture.idr()) {
            // num_ref_idx_active_override_flag, ref_pic_list_modification_flag_l0
            bits.bits(0, 1).bits(0, 1);
        }
        if (picture.reference()) {
            if (picture.idr()) {
                bits.bits(picture.noOutputOfPriorPics() ? 1 : 0, 1).bits(0, 1);
            } else if (picture.operations() == null) {
                bits.bits(0, 1);
            } else {
                bits.bits(1, 1);
                var operations = picture.operations();
                for (var i = 0; i < operations.length; i += 3) {
                    var operation = operations[i];
                    bits.ue(operation);
                    if (operation != 5) {
                        bits.ue(operations[i + 1]);
                    }
                    if (operation == 3) {
                        bits.ue(operations[i + 2]);
                    }
                }
                bits.ue(0);
            }
        }
        // slice_qp_delta
        bits.se(0);
        if (picture.idr()) {
            // mb_type I_PCM, the alignment, then 256 luma and 2 x 64 chroma samples
            bits.ue(25);
            while (!bits.aligned()) {
                bits.bits(0, 1);
            }
            for (var i = 0; i < 384; i++) {
                bits.bits(i < 256 ? sample : 128, 8);
            }
        } else {
            // mb_skip_run over the single macroblock
            bits.ue(1);
        }
        return bits;
    }

    private static byte[] nal(int header, Bits bits) {
        var rbsp = bits.trailing();
        var nal = new ByteArrayOutputStream();
        nal.writeBytes(new byte[]{0, 0, 0, 1, (byte) header});
        var zeros = 0;
        for (var b : rbsp) {
            if (zeros >= 2 && (b & 0xFF) <= 3) {
                nal.write(3);
                zeros = 0;
            }
            nal.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return nal.toByteArray();
    }

    /**
     * Writes the bits of an RBSP.
     */
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private int current;

        private int count;

        Bits bits(long value, int length) {
            for (var i = length - 1; i >= 0; i--) {
                current = current << 1 | (int) (value >>> i & 1);
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
            return this;
        }

        Bits ue(int value) {
            var length = 32 - Integer.numberOfLeadingZeros(value + 1);
            return bits(0, length - 1).bits(value + 1, length);
        }

        Bits se(int value) {
            return ue(value > 0 ? 2 * value - 1 : -2 * value);
        }

        boolean aligned() {
            return count == 0;
        }

        byte[] trailing() {
            bits(1, 1);
            while (count != 0) {
                bits(0, 1);
            }
            return out.toByteArray();
        }
    }
}
