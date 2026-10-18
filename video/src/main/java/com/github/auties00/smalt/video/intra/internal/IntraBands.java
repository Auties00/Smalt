package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Chooses how many parallel bands the DCT-based intra codecs (ProRes and VC-3) split a frame into.
 *
 * <p>The macroblock rows (VC-3) and slice rows (ProRes) of a frame are independent and cost
 * about the same, but entropy decoding makes a sample far more expensive than the picture
 * operations {@link ParallelBands#bands(int, long)} is tuned for, and a row of dense data costs
 * several times a row of flat data. The frame is therefore cut into several bands per thread,
 * which the participating threads claim in order as they finish, so that the last band of the
 * frame is short.
 */
public final class IntraBands {
    /**
     * The factor by which a sample of an intra frame outweighs a sample of a picture operation.
     */
    private static final int WORK_PER_SAMPLE = 16;

    /**
     * The number of bands per participating thread.
     */
    private static final int BANDS_PER_THREAD = 3;

    /**
     * Prevents instantiation.
     */
    private IntraBands() {
        throw new AssertionError();
    }

    /**
     * Returns the number of bands to decode a frame in.
     *
     * @param threads the thread count of the decoder: {@code 1} for the calling thread only,
     *                otherwise the cap on the participating threads ({@code 0} for no cap)
     * @param units   the number of independent rows of the frame
     * @param samples the number of luma samples of the frame
     * @return the band count, at least one
     */
    public static int count(int threads, int units, long samples) {
        if (threads == 1) {
            return 1;
        }
        var participants = ParallelBands.bands(units, samples * WORK_PER_SAMPLE);
        if (threads > 1) {
            participants = Math.min(participants, threads);
        }
        if (participants <= 1) {
            return 1;
        }
        return Math.min(units, participants * BANDS_PER_THREAD);
    }
}
