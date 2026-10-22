package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Builds the {@link ColorInfo} of AVIF images and sequences from their color properties and
 * AV1 sequence headers.
 */
final class Colors {
    /**
     * The H.273 code point of an unspecified color component.
     */
    private static final int UNSPECIFIED = 2;

    /**
     * Prevents instantiation.
     */
    private Colors() {
        throw new AssertionError();
    }

    /**
     * Builds a color description.
     *
     * <p>The code points and the range come from the {@code nclx} color box when there is one,
     * and from the sequence header otherwise (unspecified when the header has no color
     * description). The chroma siting comes from the sequence header's
     * {@code chroma_sample_position}.
     *
     * @param nclx the {@code nclx} color box, or {@code null}
     * @param sequence the AV1 sequence header, or {@code null}
     * @param mastering the mastering display, or {@code null}
     * @param lightLevel the content light level, or {@code null}
     * @return the description
     */
    static ColorInfo colorInfo(HeifProperty.ColorNclx nclx, SequenceHeader sequence, MasteringDisplay mastering,
                               ContentLightLevel lightLevel) {
        int primaries;
        int transfer;
        int matrix;
        boolean full;
        if (nclx != null) {
            primaries = nclx.primaries();
            transfer = nclx.transfer();
            matrix = nclx.matrix();
            full = nclx.fullRange();
        } else if (sequence != null && sequence.colorDescriptionPresent) {
            primaries = sequence.pri;
            transfer = sequence.trc;
            matrix = sequence.mtrx;
            full = sequence.colorRange;
        } else {
            primaries = UNSPECIFIED;
            transfer = UNSPECIFIED;
            matrix = UNSPECIFIED;
            full = sequence != null && sequence.colorRange;
        }
        var siting = sequence == null ? ChromaSiting.UNSPECIFIED : sequence.chromaSiting();
        return new ColorInfo(MatrixCoefficients.of(matrix), full ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of(primaries), TransferCharacteristics.of(transfer))
                .withChromaSiting(siting)
                .withMasteringDisplay(mastering)
                .withContentLightLevel(lightLevel);
    }

    /**
     * Returns whether a source decodes to 8-bit RGBA rather than to 8-bit Y'CbCr in the default 8-bit mode.
     *
     * <p>The YCgCo-Re and YCgCo-Ro matrices tie the R'G'B' depth to the sample depth, so 8-bit samples would keep
     * only 6 or 7 bits of R'G'B'. Such a source decodes at its own depth and {@link #toEightBit} converts it.
     *
     * @param matrix the matrix coefficients of the source
     * @param depth the bit depth of the source
     * @return {@code true} for a YCgCo-R matrix deeper than 8 bits
     */
    static boolean reducesToRgb(MatrixCoefficients matrix, int depth) {
        return depth > 8 && YcgcoR.applies(matrix);
    }

    /**
     * Reduces a picture decoded at its own depth to the default 8-bit output: a YCgCo-Re or YCgCo-Ro
     * {@link Yuv16Picture} to 8-bit RGBA, any other {@link Yuv16Picture} or {@link Rgba16Picture} with
     * {@link ImageOps#toEightBit(Picture)}.
     *
     * @param picture the picture decoded at its own depth, or already 8-bit
     * @return the 8-bit picture
     */
    static Picture toEightBit(Picture picture) {
        return switch (picture) {
            case Yuv16Picture yuv when YcgcoR.applies(yuv.color().matrix()) -> ImageOps.toRgba(yuv);
            default -> ImageOps.toEightBit(picture);
        };
    }
}
