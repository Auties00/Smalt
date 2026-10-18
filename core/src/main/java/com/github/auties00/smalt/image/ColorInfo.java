package com.github.auties00.smalt.image;

import java.util.Objects;
import java.util.Optional;

/**
 * Describes how the samples of a {@link YuvPicture} map to color.
 *
 * <p>The {@link #matrix()} and {@link #range()} are needed to convert Y'CbCr to R'G'B'; the
 * {@link #primaries()} and {@link #transfer()} are needed to convert between color spaces, most
 * importantly to tone-map high-dynamic-range video to BT.709 standard dynamic range. The
 * {@link #chromaSiting()} tells where subsampled chroma samples sit relative to luma. The
 * {@link #masteringDisplay()} and {@link #contentLightLevel()} are the static metadata of
 * high-dynamic-range content (HDR10), present only when the source signals them.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param matrix            the Y'CbCr derivation matrix
 * @param range             the sample quantization range
 * @param primaries         the color primaries
 * @param transfer          the transfer characteristics
 * @param chromaSiting      the position of subsampled chroma samples
 * @param masteringDisplay  the color volume of the mastering display, or empty when not signaled
 * @param contentLightLevel the light level of the content, or empty when not signaled
 */
public record ColorInfo(MatrixCoefficients matrix, ColorRange range, ColorPrimaries primaries,
                        TransferCharacteristics transfer, ChromaSiting chromaSiting,
                        Optional<MasteringDisplay> masteringDisplay, Optional<ContentLightLevel> contentLightLevel) {
    /**
     * The color of a JFIF JPEG, a WebP lossy image, and every still image this module encodes:
     * BT.601 matrix, full range, BT.709 primaries, sRGB transfer.
     */
    public static final ColorInfo JPEG = new ColorInfo(MatrixCoefficients.BT601, ColorRange.FULL,
            ColorPrimaries.BT709, TransferCharacteristics.SRGB);

    /**
     * Limited-range BT.601, the default of standard-definition video.
     */
    public static final ColorInfo BT601_LIMITED = new ColorInfo(MatrixCoefficients.BT601, ColorRange.LIMITED,
            ColorPrimaries.SMPTE170M, TransferCharacteristics.SMPTE170M);

    /**
     * Limited-range BT.709, the default of high-definition video and of every video this module
     * encodes.
     */
    public static final ColorInfo BT709_LIMITED = new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED,
            ColorPrimaries.BT709, TransferCharacteristics.BT709);

    /**
     * Validates the components.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public ColorInfo {
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(primaries, "primaries");
        Objects.requireNonNull(transfer, "transfer");
        Objects.requireNonNull(chromaSiting, "chromaSiting");
        Objects.requireNonNull(masteringDisplay, "masteringDisplay");
        Objects.requireNonNull(contentLightLevel, "contentLightLevel");
    }

    /**
     * Creates a description with {@link ChromaSiting#UNSPECIFIED} siting and no high-dynamic-range
     * metadata.
     *
     * @param matrix    the Y'CbCr derivation matrix
     * @param range     the sample quantization range
     * @param primaries the color primaries
     * @param transfer  the transfer characteristics
     * @throws NullPointerException if any argument is {@code null}
     */
    public ColorInfo(MatrixCoefficients matrix, ColorRange range, ColorPrimaries primaries,
                     TransferCharacteristics transfer) {
        this(matrix, range, primaries, transfer, ChromaSiting.UNSPECIFIED, Optional.empty(), Optional.empty());
    }

    /**
     * Returns a copy of this description with the given range.
     *
     * @param range the replacement range
     * @return the modified description
     * @throws NullPointerException if {@code range} is {@code null}
     */
    public ColorInfo withRange(ColorRange range) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay, contentLightLevel);
    }

    /**
     * Returns a copy of this description with the given matrix.
     *
     * @param matrix the replacement matrix
     * @return the modified description
     * @throws NullPointerException if {@code matrix} is {@code null}
     */
    public ColorInfo withMatrix(MatrixCoefficients matrix) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay, contentLightLevel);
    }

    /**
     * Returns a copy of this description with the given primaries.
     *
     * @param primaries the replacement primaries
     * @return the modified description
     * @throws NullPointerException if {@code primaries} is {@code null}
     */
    public ColorInfo withPrimaries(ColorPrimaries primaries) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay, contentLightLevel);
    }

    /**
     * Returns a copy of this description with the given transfer characteristics.
     *
     * @param transfer the replacement transfer characteristics
     * @return the modified description
     * @throws NullPointerException if {@code transfer} is {@code null}
     */
    public ColorInfo withTransfer(TransferCharacteristics transfer) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay, contentLightLevel);
    }

    /**
     * Returns a copy of this description with the given chroma siting.
     *
     * @param chromaSiting the replacement siting
     * @return the modified description
     * @throws NullPointerException if {@code chromaSiting} is {@code null}
     */
    public ColorInfo withChromaSiting(ChromaSiting chromaSiting) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay, contentLightLevel);
    }

    /**
     * Returns a copy of this description with the given mastering display.
     *
     * @param masteringDisplay the replacement mastering display, or {@code null} to remove it
     * @return the modified description
     */
    public ColorInfo withMasteringDisplay(MasteringDisplay masteringDisplay) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, Optional.ofNullable(masteringDisplay),
                contentLightLevel);
    }

    /**
     * Returns a copy of this description with the given content light level.
     *
     * @param contentLightLevel the replacement light level, or {@code null} to remove it
     * @return the modified description
     */
    public ColorInfo withContentLightLevel(ContentLightLevel contentLightLevel) {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay,
                Optional.ofNullable(contentLightLevel));
    }

    /**
     * Returns a copy of this description without mastering-display metadata.
     *
     * @return the modified description, with an empty {@link #masteringDisplay()}
     */
    public ColorInfo withoutMasteringDisplay() {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, Optional.empty(), contentLightLevel);
    }

    /**
     * Returns a copy of this description without content light level metadata.
     *
     * @return the modified description, with an empty {@link #contentLightLevel()}
     */
    public ColorInfo withoutContentLightLevel() {
        return new ColorInfo(matrix, range, primaries, transfer, chromaSiting, masteringDisplay, Optional.empty());
    }
}
