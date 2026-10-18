package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.List;

/**
 * Maps the color attachments of a {@code CVImageBuffer} to a {@link ColorInfo}.
 *
 * <p>CoreVideo describes the color of a buffer with three {@code CFString} attachments,
 * {@code kCVImageBufferYCbCrMatrixKey}, {@code kCVImageBufferColorPrimariesKey} and
 * {@code kCVImageBufferTransferFunctionKey}, whose values are exported constants such as
 * {@code kCVImageBufferYCbCrMatrix_ITU_R_709_2}. The native side compares an attachment with the
 * constants of {@link #MATRICES}, {@link #PRIMARIES} and {@link #TRANSFERS} and passes the index of
 * the one it equals (or {@code -1}) to {@link #resolve(int, int, int, ColorRange, int)}.
 */
final class ColorAttachments {
    /**
     * Pairs an exported CoreVideo constant with the value it stands for.
     *
     * @param symbol the name of the exported {@code CFStringRef} constant
     * @param value the value it describes
     * @param <T> the type of the value
     */
    record Constant<T>(String symbol, T value) {
    }

    /**
     * The {@code kCVImageBufferYCbCrMatrix_*} constants ({@code CVImageBuffer.h}) and their
     * H.273 matrix coefficients.
     */
    static final List<Constant<MatrixCoefficients>> MATRICES = List.of(
            new Constant<>("kCVImageBufferYCbCrMatrix_ITU_R_709_2", MatrixCoefficients.BT709),
            new Constant<>("kCVImageBufferYCbCrMatrix_ITU_R_601_4", MatrixCoefficients.BT601),
            new Constant<>("kCVImageBufferYCbCrMatrix_SMPTE_240M_1995", MatrixCoefficients.SMPTE240M),
            new Constant<>("kCVImageBufferYCbCrMatrix_ITU_R_2020", MatrixCoefficients.BT2020_NCL));

    /**
     * The {@code kCVImageBufferColorPrimaries_*} constants ({@code CVImageBuffer.h}) and their
     * H.273 color primaries; {@code P22} are the EBU Tech. 3213-E phosphors (H.273 value 22).
     */
    static final List<Constant<ColorPrimaries>> PRIMARIES = List.of(
            new Constant<>("kCVImageBufferColorPrimaries_ITU_R_709_2", ColorPrimaries.BT709),
            new Constant<>("kCVImageBufferColorPrimaries_EBU_3213", ColorPrimaries.BT470BG),
            new Constant<>("kCVImageBufferColorPrimaries_SMPTE_C", ColorPrimaries.SMPTE170M),
            new Constant<>("kCVImageBufferColorPrimaries_P22", ColorPrimaries.EBU3213),
            new Constant<>("kCVImageBufferColorPrimaries_DCI_P3", ColorPrimaries.SMPTE431),
            new Constant<>("kCVImageBufferColorPrimaries_P3_D65", ColorPrimaries.SMPTE432),
            new Constant<>("kCVImageBufferColorPrimaries_ITU_R_2020", ColorPrimaries.BT2020));

    /**
     * The {@code kCVImageBufferTransferFunction_*} constants ({@code CVImageBuffer.h}) and their
     * H.273 transfer characteristics.
     */
    static final List<Constant<TransferCharacteristics>> TRANSFERS = List.of(
            new Constant<>("kCVImageBufferTransferFunction_ITU_R_709_2", TransferCharacteristics.BT709),
            new Constant<>("kCVImageBufferTransferFunction_SMPTE_240M_1995", TransferCharacteristics.SMPTE240M),
            new Constant<>("kCVImageBufferTransferFunction_sRGB", TransferCharacteristics.SRGB),
            new Constant<>("kCVImageBufferTransferFunction_ITU_R_2020", TransferCharacteristics.BT2020_10),
            new Constant<>("kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ", TransferCharacteristics.PQ),
            new Constant<>("kCVImageBufferTransferFunction_ITU_R_2100_HLG", TransferCharacteristics.HLG),
            new Constant<>("kCVImageBufferTransferFunction_Linear", TransferCharacteristics.LINEAR),
            new Constant<>("kCVImageBufferTransferFunction_SMPTE_ST_428_1", TransferCharacteristics.SMPTE428));

    /**
     * The picture height from which a buffer without a matrix attachment is assumed to be
     * high-definition (BT.709) rather than standard-definition (BT.601).
     */
    private static final int HD_HEIGHT = 720;

    /**
     * Prevents instantiation.
     */
    private ColorAttachments() {
        throw new AssertionError();
    }

    /**
     * Resolves the color of a buffer from the indexes of its attachments.
     *
     * <p>A missing matrix defaults to BT.709 from {@link #HD_HEIGHT} lines and BT.601 below; a
     * missing primaries or transfer attachment defaults to the one the matrix's standard
     * specifies (BT.709, SMPTE 170M, SMPTE 240M or BT.2020).
     *
     * @param matrix the index in {@link #MATRICES}, or {@code -1} when absent or unknown
     * @param primaries the index in {@link #PRIMARIES}, or {@code -1} when absent or unknown
     * @param transfer the index in {@link #TRANSFERS}, or {@code -1} when absent or unknown
     * @param range the sample range the pixel format implies
     * @param height the picture height in pixels
     * @return the color description
     */
    static ColorInfo resolve(int matrix, int primaries, int transfer, ColorRange range, int height) {
        var coefficients = valid(matrix, MATRICES) ? MATRICES.get(matrix).value()
                : height >= HD_HEIGHT ? MatrixCoefficients.BT709 : MatrixCoefficients.BT601;
        var primaryValue = valid(primaries, PRIMARIES) ? PRIMARIES.get(primaries).value() : defaultPrimaries(coefficients);
        var transferValue = valid(transfer, TRANSFERS) ? TRANSFERS.get(transfer).value() : defaultTransfer(coefficients);
        return new ColorInfo(coefficients, range, primaryValue, transferValue);
    }

    /**
     * Returns whether an index designates an entry of a table.
     *
     * @return whether {@code 0 <= index < table.size()}
     */
    private static boolean valid(int index, List<?> table) {
        return index >= 0 && index < table.size();
    }

    /**
     * Returns the primaries of the standard a matrix belongs to.
     *
     * @return the primaries
     */
    private static ColorPrimaries defaultPrimaries(MatrixCoefficients matrix) {
        return switch (matrix) {
            case BT601 -> ColorPrimaries.SMPTE170M;
            case SMPTE240M -> ColorPrimaries.SMPTE240M;
            case BT2020_NCL, BT2020_CL -> ColorPrimaries.BT2020;
            default -> ColorPrimaries.BT709;
        };
    }

    /**
     * Returns the transfer characteristics of the standard a matrix belongs to.
     *
     * @return the transfer characteristics
     */
    private static TransferCharacteristics defaultTransfer(MatrixCoefficients matrix) {
        return switch (matrix) {
            case BT601 -> TransferCharacteristics.SMPTE170M;
            case SMPTE240M -> TransferCharacteristics.SMPTE240M;
            case BT2020_NCL, BT2020_CL -> TransferCharacteristics.BT2020_10;
            default -> TransferCharacteristics.BT709;
        };
    }
}
