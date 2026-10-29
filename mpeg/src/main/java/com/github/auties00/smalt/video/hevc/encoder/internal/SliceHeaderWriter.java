package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcSliceHeader;

/**
 * Writes the slice segment header (ITU-T H.265 7.3.6.1) of the single slice segment of a picture, with the
 * parameter sets of {@link ParameterSets}.
 *
 * <p>The header carries the short-term reference picture set explicitly ({@code short_term_ref_pic_set_sps_flag}
 * 0), and the entry points of the wavefront substreams of the slice data.
 */
final class SliceHeaderWriter {
    /**
     * Prevents instantiation.
     */
    private SliceHeaderWriter() {
        throw new AssertionError();
    }

    /**
     * Writes a slice segment header, ending with its {@code byte_alignment()}.
     *
     * @param s the settings
     * @param p the picture's slice parameters
     * @param substreamSizes the escaped size of each wavefront substream of the slice data, in order, or
     * {@code null} to write no entry point
     * @return the header bytes
     */
    static byte[] write(EncoderSettings s, SliceParams p, int[] substreamSizes) {
        var w = new MsbBitWriter(64);
        w.writeBit(1);
        if (HevcNal.isIrap(p.nalType)) {
            w.writeBit(0);
        }
        w.writeUe(0);
        w.writeUe(p.sliceType);
        var idr = p.nalType == HevcNal.IDR_W_RADL || p.nalType == HevcNal.IDR_N_LP;
        if (!idr) {
            w.write(p.poc & ((1 << ParameterSets.LOG2_MAX_POC_LSB) - 1), ParameterSets.LOG2_MAX_POC_LSB);
            w.writeBit(0);
            w.writeUe(p.negativeCount);
            w.writeUe(p.positiveCount);
            var previous = 0;
            for (var i = 0; i < p.negativeCount; i++) {
                w.writeUe(p.negativeDelta[i] - previous - 1);
                w.writeBit(p.negativeUsed[i] ? 1 : 0);
                previous = p.negativeDelta[i];
            }
            previous = 0;
            for (var i = 0; i < p.positiveCount; i++) {
                w.writeUe(p.positiveDelta[i] - previous - 1);
                w.writeBit(p.positiveUsed[i] ? 1 : 0);
                previous = p.positiveDelta[i];
            }
            if (!s.stillPicture) {
                w.writeBit(p.temporalMvp ? 1 : 0);
            }
        }
        if (p.saoEnabled) {
            w.writeBit(p.saoLuma ? 1 : 0);
            w.writeBit(p.saoChroma ? 1 : 0);
        }
        if (p.sliceType != HevcSliceHeader.I) {
            var b = p.sliceType == HevcSliceHeader.B;
            var override = p.numRefIdx[0] != 1 || (b && p.numRefIdx[1] != 1);
            w.writeBit(override ? 1 : 0);
            if (override) {
                w.writeUe(p.numRefIdx[0] - 1);
                if (b) {
                    w.writeUe(p.numRefIdx[1] - 1);
                }
            }
            if (b) {
                w.writeBit(0);
            }
            if (p.temporalMvp) {
                if (b) {
                    w.writeBit(p.collocatedFromL0 ? 1 : 0);
                }
                var list = p.collocatedFromL0 ? 0 : 1;
                if (p.numRefIdx[list] > 1) {
                    w.writeUe(p.collocatedRefIdx);
                }
            }
            w.writeUe(5 - p.maxNumMergeCand);
        }
        w.writeSe(p.sliceQp - 26);
        if (substreamSizes == null) {
            w.writeUe(0);
        } else {
            var count = substreamSizes.length - 1;
            w.writeUe(count);
            if (count > 0) {
                var largest = 1;
                for (var i = 0; i < count; i++) {
                    largest = Math.max(largest, substreamSizes[i]);
                }
                var bits = 32 - Integer.numberOfLeadingZeros(largest - 1);
                bits = Math.max(bits, 1);
                w.writeUe(bits - 1);
                for (var i = 0; i < count; i++) {
                    w.write(substreamSizes[i] - 1, bits);
                }
            }
        }
        w.writeBit(1);
        w.alignWithZeros();
        return w.toByteArray();
    }

    /**
     * The values of the slice segment header of a picture.
     *
     * <p>Instances are mutable and not thread-safe.
     */
    static final class SliceParams {
        /**
         * The NAL unit type.
         */
        int nalType;

        /**
         * {@code slice_type}: {@link HevcSliceHeader#B}, {@link HevcSliceHeader#P} or {@link HevcSliceHeader#I}.
         */
        int sliceType;

        /**
         * {@code PicOrderCntVal}.
         */
        int poc;

        /**
         * {@code NumNegativePics}.
         */
        int negativeCount;

        /**
         * The picture order count differences of the earlier pictures, current minus reference, increasing.
         */
        final int[] negativeDelta = new int[16];

        /**
         * {@code UsedByCurrPicS0}.
         */
        final boolean[] negativeUsed = new boolean[16];

        /**
         * {@code NumPositivePics}.
         */
        int positiveCount;

        /**
         * The picture order count differences of the later pictures, reference minus current, increasing.
         */
        final int[] positiveDelta = new int[16];

        /**
         * {@code UsedByCurrPicS1}.
         */
        final boolean[] positiveUsed = new boolean[16];

        /**
         * {@code slice_temporal_mvp_enabled_flag}.
         */
        boolean temporalMvp;

        /**
         * {@code sample_adaptive_offset_enabled_flag} of the sequence parameter set.
         */
        boolean saoEnabled;

        /**
         * {@code slice_sao_luma_flag}.
         */
        boolean saoLuma;

        /**
         * {@code slice_sao_chroma_flag}.
         */
        boolean saoChroma;

        /**
         * {@code num_ref_idx_l0_active_minus1 + 1} and {@code num_ref_idx_l1_active_minus1 + 1}.
         */
        final int[] numRefIdx = new int[2];

        /**
         * {@code collocated_from_l0_flag}.
         */
        boolean collocatedFromL0 = true;

        /**
         * {@code collocated_ref_idx}.
         */
        int collocatedRefIdx;

        /**
         * {@code MaxNumMergeCand}.
         */
        int maxNumMergeCand = 5;

        /**
         * {@code SliceQpY}.
         */
        int sliceQp = 26;

        /**
         * Constructs empty parameters.
         */
        SliceParams() {
        }
    }
}
