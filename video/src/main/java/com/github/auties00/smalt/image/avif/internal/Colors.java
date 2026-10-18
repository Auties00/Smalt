package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
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
}
