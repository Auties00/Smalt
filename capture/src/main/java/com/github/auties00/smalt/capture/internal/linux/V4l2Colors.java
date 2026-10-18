package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;

/**
 * Translates the color fields of a {@code struct v4l2_pix_format} into a {@link ColorInfo}.
 *
 * <p>A zero ({@code DEFAULT}) encoding, quantization or transfer function is resolved from the
 * colorspace with the {@code V4L2_MAP_YCBCR_ENC_DEFAULT}, {@code V4L2_MAP_QUANTIZATION_DEFAULT}
 * and {@code V4L2_MAP_XFER_FUNC_DEFAULT} rules of {@code linux/videodev2.h}, for a Y'CbCr format.
 * A zero colorspace is treated as sRGB, the colorspace USB Video Class cameras report, which
 * makes the common webcam output limited-range BT.601 with BT.709 primaries and the sRGB transfer.
 * The Rec. 709 transfer function of a standard-definition colorspace is reported as SMPTE 170M,
 * which ITU-T H.273 defines identically.
 */
final class V4l2Colors {
    /**
     * {@code V4L2_COLORSPACE_SMPTE170M}.
     */
    static final int COLORSPACE_SMPTE170M = 1;

    /**
     * {@code V4L2_COLORSPACE_SMPTE240M}.
     */
    static final int COLORSPACE_SMPTE240M = 2;

    /**
     * {@code V4L2_COLORSPACE_REC709}.
     */
    static final int COLORSPACE_REC709 = 3;

    /**
     * {@code V4L2_COLORSPACE_BT878}.
     */
    static final int COLORSPACE_BT878 = 4;

    /**
     * {@code V4L2_COLORSPACE_470_SYSTEM_M}.
     */
    static final int COLORSPACE_470_SYSTEM_M = 5;

    /**
     * {@code V4L2_COLORSPACE_470_SYSTEM_BG}.
     */
    static final int COLORSPACE_470_SYSTEM_BG = 6;

    /**
     * {@code V4L2_COLORSPACE_JPEG}.
     */
    static final int COLORSPACE_JPEG = 7;

    /**
     * {@code V4L2_COLORSPACE_SRGB}.
     */
    static final int COLORSPACE_SRGB = 8;

    /**
     * {@code V4L2_COLORSPACE_OPRGB}.
     */
    static final int COLORSPACE_OPRGB = 9;

    /**
     * {@code V4L2_COLORSPACE_BT2020}.
     */
    static final int COLORSPACE_BT2020 = 10;

    /**
     * {@code V4L2_COLORSPACE_RAW}.
     */
    static final int COLORSPACE_RAW = 11;

    /**
     * {@code V4L2_COLORSPACE_DCI_P3}.
     */
    static final int COLORSPACE_DCI_P3 = 12;

    /**
     * {@code V4L2_YCBCR_ENC_601}.
     */
    static final int ENC_601 = 1;

    /**
     * {@code V4L2_YCBCR_ENC_709}.
     */
    static final int ENC_709 = 2;

    /**
     * {@code V4L2_YCBCR_ENC_XV601}.
     */
    static final int ENC_XV601 = 3;

    /**
     * {@code V4L2_YCBCR_ENC_XV709}.
     */
    static final int ENC_XV709 = 4;

    /**
     * {@code V4L2_YCBCR_ENC_SYCC}.
     */
    static final int ENC_SYCC = 5;

    /**
     * {@code V4L2_YCBCR_ENC_BT2020}.
     */
    static final int ENC_BT2020 = 6;

    /**
     * {@code V4L2_YCBCR_ENC_BT2020_CONST_LUM}.
     */
    static final int ENC_BT2020_CONST_LUM = 7;

    /**
     * {@code V4L2_YCBCR_ENC_SMPTE240M}.
     */
    static final int ENC_SMPTE240M = 8;

    /**
     * {@code V4L2_QUANTIZATION_FULL_RANGE}.
     */
    static final int QUANTIZATION_FULL = 1;

    /**
     * {@code V4L2_QUANTIZATION_LIM_RANGE}.
     */
    static final int QUANTIZATION_LIMITED = 2;

    /**
     * {@code V4L2_XFER_FUNC_709}.
     */
    static final int XFER_709 = 1;

    /**
     * {@code V4L2_XFER_FUNC_SRGB}.
     */
    static final int XFER_SRGB = 2;

    /**
     * {@code V4L2_XFER_FUNC_OPRGB}.
     */
    static final int XFER_OPRGB = 3;

    /**
     * {@code V4L2_XFER_FUNC_SMPTE240M}.
     */
    static final int XFER_SMPTE240M = 4;

    /**
     * {@code V4L2_XFER_FUNC_NONE}.
     */
    static final int XFER_NONE = 5;

    /**
     * {@code V4L2_XFER_FUNC_DCI_P3}.
     */
    static final int XFER_DCI_P3 = 6;

    /**
     * {@code V4L2_XFER_FUNC_SMPTE2084}.
     */
    static final int XFER_SMPTE2084 = 7;

    /**
     * Prevents instantiation.
     */
    private V4l2Colors() {
        throw new AssertionError();
    }

    /**
     * Describes the color of a Y'CbCr format.
     *
     * @param colorspace the {@code colorspace} field
     * @param encoding the {@code ycbcr_enc} field
     * @param quantization the {@code quantization} field
     * @param transfer the {@code xfer_func} field
     * @return the color description
     */
    static ColorInfo colorInfo(int colorspace, int encoding, int quantization, int transfer) {
        var space = colorspace == 0 ? COLORSPACE_SRGB : colorspace;
        var matrix = switch (encoding == 0 ? defaultEncoding(space) : encoding) {
            case ENC_709, ENC_XV709 -> MatrixCoefficients.BT709;
            case ENC_BT2020 -> MatrixCoefficients.BT2020_NCL;
            case ENC_BT2020_CONST_LUM -> MatrixCoefficients.BT2020_CL;
            case ENC_SMPTE240M -> MatrixCoefficients.SMPTE240M;
            case ENC_601, ENC_XV601, ENC_SYCC -> MatrixCoefficients.BT601;
            default -> MatrixCoefficients.BT601;
        };
        var range = switch (quantization) {
            case QUANTIZATION_FULL -> ColorRange.FULL;
            case QUANTIZATION_LIMITED -> ColorRange.LIMITED;
            default -> space == COLORSPACE_JPEG ? ColorRange.FULL : ColorRange.LIMITED;
        };
        var primaries = switch (space) {
            case COLORSPACE_SMPTE170M, COLORSPACE_BT878 -> ColorPrimaries.SMPTE170M;
            case COLORSPACE_SMPTE240M -> ColorPrimaries.SMPTE240M;
            case COLORSPACE_470_SYSTEM_M -> ColorPrimaries.BT470M;
            case COLORSPACE_470_SYSTEM_BG -> ColorPrimaries.BT470BG;
            case COLORSPACE_BT2020 -> ColorPrimaries.BT2020;
            case COLORSPACE_DCI_P3 -> ColorPrimaries.SMPTE431;
            case COLORSPACE_OPRGB, COLORSPACE_RAW -> ColorPrimaries.UNSPECIFIED;
            default -> ColorPrimaries.BT709;
        };
        var transferCharacteristics = switch (transfer == 0 ? defaultTransfer(space) : transfer) {
            case XFER_709 -> space == COLORSPACE_SMPTE170M || space == COLORSPACE_BT878
                    ? TransferCharacteristics.SMPTE170M : TransferCharacteristics.BT709;
            case XFER_SRGB -> TransferCharacteristics.SRGB;
            case XFER_SMPTE240M -> TransferCharacteristics.SMPTE240M;
            case XFER_NONE -> TransferCharacteristics.LINEAR;
            case XFER_SMPTE2084 -> TransferCharacteristics.PQ;
            default -> TransferCharacteristics.UNSPECIFIED;
        };
        return new ColorInfo(matrix, range, primaries, transferCharacteristics);
    }

    /**
     * Returns the Y'CbCr encoding a colorspace implies, {@code V4L2_MAP_YCBCR_ENC_DEFAULT}.
     *
     * @param colorspace the colorspace, not zero
     * @return the encoding
     */
    private static int defaultEncoding(int colorspace) {
        return switch (colorspace) {
            case COLORSPACE_REC709, COLORSPACE_DCI_P3 -> ENC_709;
            case COLORSPACE_BT2020 -> ENC_BT2020;
            case COLORSPACE_SMPTE240M -> ENC_SMPTE240M;
            default -> ENC_601;
        };
    }

    /**
     * Returns the transfer function a colorspace implies, {@code V4L2_MAP_XFER_FUNC_DEFAULT}.
     *
     * @param colorspace the colorspace, not zero
     * @return the transfer function
     */
    private static int defaultTransfer(int colorspace) {
        return switch (colorspace) {
            case COLORSPACE_OPRGB -> XFER_OPRGB;
            case COLORSPACE_SMPTE240M -> XFER_SMPTE240M;
            case COLORSPACE_DCI_P3 -> XFER_DCI_P3;
            case COLORSPACE_RAW -> XFER_NONE;
            case COLORSPACE_SRGB, COLORSPACE_JPEG -> XFER_SRGB;
            default -> XFER_709;
        };
    }
}
