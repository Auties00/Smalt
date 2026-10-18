package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Carries everything the macroblock analyzers and the slice writer of one picture share: the
 * source, the reconstruction, the reference list, the macroblock tables, the picture type and
 * the quantizer of every macroblock.
 *
 * <p>Analyzers only read the shared inputs and write disjoint macroblocks of the outputs, so the
 * rows of a picture can be analyzed concurrently.
 */
final class PictureContext {
    /**
     * The source picture.
     */
    Frame source;

    /**
     * The reconstruction being built.
     */
    Frame reconstruction;

    /**
     * The reference pictures, most recent first.
     */
    final Frame[] refs;

    /**
     * The number of active references, {@code 0} for an I picture.
     */
    int refCount;

    /**
     * The macroblock tables of the picture.
     */
    final PictureInfo info;

    /**
     * Whether the picture is an IDR picture.
     */
    boolean idr;

    /**
     * Whether the picture's slices are P slices.
     */
    boolean pSlice;

    /**
     * Whether the picture's slices are B slices.
     */
    boolean bSlice;

    /**
     * The {@code pic_order_cnt_lsb} of the picture.
     */
    int pocLsb;

    /**
     * The {@code nal_ref_idc} of the picture's NAL units.
     */
    int nalRefIdc;

    /**
     * The POC distance from a B picture back to its list 0 reference.
     */
    int distPast;

    /**
     * The POC distance from a B picture forward to its list 1 reference.
     */
    int distFuture;

    /**
     * The {@code frame_num} of the picture.
     */
    int frameNum;

    /**
     * The packed motion vector, in quarter samples, that the look-ahead predicts for each
     * macroblock against the first reference, or {@code null} for none.
     */
    int[] seed;

    /**
     * Whether the B slices of the picture search lightly, see
     * {@link EncoderSettings.Analysis#lightB()}.
     */
    boolean lightB;

    /**
     * The number of {@code memory_management_control_operation} 1 commands of the picture's
     * {@code dec_ref_pic_marking}, or {@code -1} to use the sliding window.
     */
    int markCount = -1;

    /**
     * The {@code difference_of_pic_nums_minus1} of each marking command.
     */
    final int[] markDifferences = new int[32];

    /**
     * The number of reordering commands of a P slice's reference list, {@code 0} to keep the
     * default order.
     */
    int reorderCount;

    /**
     * The {@code modification_of_pic_nums_idc} of each reordering command.
     */
    final int[] reorderIdc = new int[32];

    /**
     * The {@code abs_diff_pic_num_minus1} of each reordering command.
     */
    final int[] reorderDifferences = new int[32];

    /**
     * The {@code idr_pic_id} of an IDR picture.
     */
    int idrPicId;

    /**
     * The {@code SliceQPY} every slice signals.
     */
    int sliceQp;

    /**
     * The half-resolution intra cost estimate of each macroblock, from {@link FrameAnalyzer}.
     */
    int[] lowresIntra;

    /**
     * The half-resolution inter cost estimate of each macroblock, from {@link FrameAnalyzer}.
     */
    int[] lowresInter;

    /**
     * The quantizer of each macroblock, including adaptive offsets.
     */
    final byte[] mbQp;

    /**
     * Whether each macroblock must be coded as {@code I_PCM}, set for the macroblocks that
     * exceeded the size limit on a previous pass over the picture.
     */
    final boolean[] forcePcm;

    /**
     * Constructs a context.
     *
     * @param settings the stream settings
     */
    PictureContext(EncoderSettings settings) {
        this.refs = new Frame[settings.refs()];
        this.info = new PictureInfo(settings.mbWidth(), settings.mbHeight());
        this.mbQp = new byte[settings.mbCount()];
        this.forcePcm = new boolean[settings.mbCount()];
    }
}
