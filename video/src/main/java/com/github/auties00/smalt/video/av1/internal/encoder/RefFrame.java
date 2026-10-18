package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;

/**
 * Holds one reconstructed reference picture together with an edge-extended copy of its planes,
 * so the motion search can read blocks that reach past the picture edges without bounds checks.
 *
 * <p>The extension replicates the last visible row and column, which is the reference a decoder
 * sees beyond the picture edges, so predictions from the padded planes equal the decoder's.
 */
final class RefFrame {
    /**
     * The border of the luma plane, in samples.
     */
    static final int BORDER = 160;

    /**
     * The reconstruction, retained while the reference is held.
     */
    Av1Picture picture;

    /**
     * The edge-extended planes.
     */
    final short[][] padded = new short[3][];

    /**
     * The stride of each edge-extended plane.
     */
    final int[] stride = new int[3];

    /**
     * The index of the top-left visible sample in each edge-extended plane.
     */
    final int[] origin = new int[3];

    /**
     * The order hint of the reference.
     */
    int orderHint;

    /**
     * Creates an empty reference.
     */
    RefFrame() {
    }

    /**
     * The reduced levels of the luma plane, or {@code null} until requested.
     */
    private GlobalTranslation.Pyramid pyramid;

    /**
     * Returns the reduced levels of the luma plane, built on first use after each {@link #build}.
     *
     * @param width the picture width
     * @param height the picture height
     * @return the levels
     */
    GlobalTranslation.Pyramid pyramid(int width, int height) {
        if (pyramid == null) {
            pyramid = new GlobalTranslation.Pyramid(padded[0], origin[0], stride[0], width, height);
        }
        return pyramid;
    }

    /**
     * Builds the edge-extended planes of a reconstruction, in horizontal bands on up to
     * {@code bands} threads: each band copies and extends its rows sideways, the first band also
     * fills the top border and the last band the bottom border from the rows they extended.
     *
     * @param pic the reconstruction
     * @param width the visible width
     * @param height the visible height
     * @param bands the most threads of the module's pool to copy with
     */
    void build(Av1Picture pic, int width, int height, int bands) {
        pyramid = null;
        for (var pl = 0; pl < 3; pl++) {
            var ss = pl != 0 ? 1 : 0;
            var w = (width + ss) >> ss;
            var h = (height + ss) >> ss;
            var border = BORDER >> ss;
            var s = w + 2 * border;
            var size = s * (h + 2 * border);
            if (padded[pl] == null || padded[pl].length != size) {
                padded[pl] = new short[size];
            }
            stride[pl] = s;
            origin[pl] = border * s + border;
        }
        var count = Math.max(1, Math.min(bands, (height + 1) >> 1));
        if (count == 1) {
            for (var pl = 0; pl < 3; pl++) {
                copyRows(pic, pl, width, 0, planeHeight(pl, height));
                extendTop(pl);
                extendBottom(pl, height);
            }
            return;
        }
        ParallelBands.run(count, band -> {
            for (var pl = 0; pl < 3; pl++) {
                var h = planeHeight(pl, height);
                copyRows(pic, pl, width, ParallelBands.start(band, count, h), ParallelBands.start(band + 1, count, h));
                if (band == 0) {
                    extendTop(pl);
                }
                if (band == count - 1) {
                    extendBottom(pl, height);
                }
            }
        });
    }

    /**
     * Returns the visible height of a plane.
     *
     * @param pl the plane
     * @param height the visible luma height
     * @return the height
     */
    private static int planeHeight(int pl, int height) {
        return pl != 0 ? (height + 1) >> 1 : height;
    }

    /**
     * Copies a range of one plane's visible rows and repeats their edge samples into the left and
     * right borders.
     *
     * @param pic the reconstruction
     * @param pl the plane
     * @param width the visible luma width
     * @param first the first row
     * @param end the row after the last
     */
    private void copyRows(Av1Picture pic, int pl, int width, int first, int end) {
        var ss = pl != 0 ? 1 : 0;
        var w = (width + ss) >> ss;
        var border = BORDER >> ss;
        var s = stride[pl];
        var dst = padded[pl];
        var srcStride = pic.stride[pl != 0 ? 1 : 0];
        var d = (first + border) * s + border;
        Planes.copy(pic.data[pl], first * srcStride, srcStride, dst, d, s, w, end - first);
        Planes.extendSides(dst, d, s, w, end - first, border, s - border - w);
    }

    /**
     * Repeats one plane's first extended row into its top border.
     *
     * @param pl the plane
     */
    private void extendTop(int pl) {
        var border = BORDER >> (pl != 0 ? 1 : 0);
        var s = stride[pl];
        Planes.extendTop(padded[pl], border * s, s, s, border);
    }

    /**
     * Repeats one plane's last extended visible row into its bottom border.
     *
     * @param pl the plane
     * @param height the visible luma height
     */
    private void extendBottom(int pl, int height) {
        var h = planeHeight(pl, height);
        var border = BORDER >> (pl != 0 ? 1 : 0);
        var s = stride[pl];
        Planes.extendBottom(padded[pl], (border + h - 1) * s, s, s, border);
    }
}
