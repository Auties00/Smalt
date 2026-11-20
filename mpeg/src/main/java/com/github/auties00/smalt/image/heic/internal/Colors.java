package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;

/**
 * Builds the {@link ColorInfo} of HEIC images and sequences from their color properties and the
 * video usability information of their HEVC or AVC streams.
 *
 * <p>The color of a coded picture starts from its stream: each H.273 code point the VUI
 * signals, and the full range flag when the VUI carries a video signal type; a code point the
 * stream leaves unspecified takes the MIAF default (ISO/IEC 23000-22 clause 7.3.6.4): sRGB
 * primaries, the sRGB transfer, the BT.601 matrix and full range. The chroma siting and the
 * mastering display and content light level SEI messages come from the stream too. A
 * {@code colr} property of type {@code nclx} then replaces the code points and the range, and the
 * {@code clli} and {@code mdcv} properties replace the light level and the mastering display
 * (ISO/IEC 23008-12 clause 6.5.5: the properties of an item take precedence over its bitstream).
 */
final class Colors {
    /**
     * The H.273 code point of an unspecified color component.
     */
    private static final int UNSPECIFIED = 2;

    /**
     * The MIAF default color primaries: BT.709, the primaries of sRGB.
     */
    private static final int DEFAULT_PRIMARIES = 1;

    /**
     * The MIAF default transfer characteristics: the sRGB curve.
     */
    private static final int DEFAULT_TRANSFER = 13;

    /**
     * The MIAF default matrix coefficients: BT.601.
     */
    private static final int DEFAULT_MATRIX = 6;

    /**
     * Prevents instantiation.
     */
    private Colors() {
        throw new AssertionError();
    }

    /**
     * The MIAF default color description: sRGB primaries and transfer, the BT.601 matrix and full
     * range.
     */
    static final ColorInfo MIAF_DEFAULT = new ColorInfo(MatrixCoefficients.of(DEFAULT_MATRIX), ColorRange.FULL,
            ColorPrimaries.of(DEFAULT_PRIMARIES), TransferCharacteristics.of(DEFAULT_TRANSFER));

    /**
     * Returns the color description a video stream gives a decoded picture.
     *
     * @param primaries the {@code colour_primaries} of the stream, 2 when unspecified
     * @param transfer the {@code transfer_characteristics} of the stream, 2 when unspecified
     * @param matrix the {@code matrix_coefficients} of the stream, 2 when unspecified
     * @param signalTypePresent whether the stream carries a video signal type
     * @param fullRange the full range flag of the stream
     * @param signaled the color description the decoder derived, whose transfer, chroma siting and
     * high-dynamic-range metadata are kept
     * @return the description, with the MIAF defaults for what the stream leaves unspecified
     */
    static ColorInfo ofStream(int primaries, int transfer, int matrix, boolean signalTypePresent, boolean fullRange,
                              ColorInfo signaled) {
        var full = !signalTypePresent || fullRange;
        return new ColorInfo(MatrixCoefficients.of(matrix != UNSPECIFIED ? matrix : DEFAULT_MATRIX),
                full ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of(primaries != UNSPECIFIED ? primaries : DEFAULT_PRIMARIES),
                transfer != UNSPECIFIED ? signaled.transfer() : TransferCharacteristics.of(DEFAULT_TRANSFER))
                .withChromaSiting(signaled.chromaSiting())
                .withMasteringDisplay(signaled.masteringDisplay().orElse(null))
                .withContentLightLevel(signaled.contentLightLevel().orElse(null));
    }

    /**
     * Returns the color of a decoded coded item with the color properties of the item applied.
     *
     * @param frame the decoded picture
     * @param item the coded item
     * @return the description: {@code frame}'s, overridden by the item's {@code nclx},
     * {@code clli} and {@code mdcv} properties, with the identity matrix and full range kept for
     * RGB samples
     */
    static ColorInfo of(CodedFrame frame, HeifItem item) {
        var color = override(frame.color(), item);
        return frame.rgb() ? color.withMatrix(MatrixCoefficients.IDENTITY).withRange(ColorRange.FULL) : color;
    }

    /**
     * Applies the color properties of an item to a color description.
     *
     * @param base the description of the item's pixels
     * @param item the image item
     * @return {@code base} with the code points and range of the item's {@code nclx} property and
     * its {@code clli} and {@code mdcv} properties, where present
     */
    static ColorInfo override(ColorInfo base, HeifItem item) {
        return override(base, item.property(HeifProperty.ColorNclx.class),
                item.property(HeifProperty.ContentLightLevel.class), item.property(HeifProperty.MasteringDisplay.class));
    }

    /**
     * Applies the color boxes of a sequence track's sample entry to a color description.
     *
     * @param base the description of the decoded frame
     * @param track the color track
     * @return {@code base} with the sample entry's {@code nclx}, {@code clli} and {@code mdcv}
     * boxes applied, where present
     */
    static ColorInfo override(ColorInfo base, HeifMovie.Track track) {
        return override(base, track.property(HeifProperty.ColorNclx.class),
                track.property(HeifProperty.ContentLightLevel.class), track.property(HeifProperty.MasteringDisplay.class));
    }

    /**
     * Applies color properties to a color description.
     *
     * @param base the description of the decoded pixels
     * @param nclx the {@code nclx} color property, or {@code null}
     * @param lightLevel the {@code clli} property, or {@code null}
     * @param mastering the {@code mdcv} property, or {@code null}
     * @return {@code base} with the properties that are present applied
     */
    private static ColorInfo override(ColorInfo base, HeifProperty.ColorNclx nclx,
                                      HeifProperty.ContentLightLevel lightLevel,
                                      HeifProperty.MasteringDisplay mastering) {
        var result = base;
        if (nclx != null) {
            result = withNclx(result, nclx);
        }
        if (lightLevel != null) {
            result = result.withContentLightLevel(HeifPictures.contentLightLevel(lightLevel));
        }
        if (mastering != null) {
            result = result.withMasteringDisplay(HeifPictures.masteringDisplay(mastering));
        }
        return result;
    }

    /**
     * Replaces the code points and the range of a color description.
     *
     * @param base the description
     * @param nclx the {@code nclx} color box
     * @return the description with the box's code points and range
     */
    private static ColorInfo withNclx(ColorInfo base, HeifProperty.ColorNclx nclx) {
        return base.withMatrix(MatrixCoefficients.of(nclx.matrix()))
                .withRange(nclx.fullRange() ? ColorRange.FULL : ColorRange.LIMITED)
                .withPrimaries(ColorPrimaries.of(nclx.primaries()))
                .withTransfer(TransferCharacteristics.of(nclx.transfer()));
    }
}
