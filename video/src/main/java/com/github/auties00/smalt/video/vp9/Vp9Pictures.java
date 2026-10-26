package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.YuvConverter;

/**
 * Converts pictures to the representation a VP9 stream of given options codes: its chroma
 * format, matrix, range and bit depth. Instances are not thread-safe.
 */
final class Vp9Pictures {
    /**
     * The chroma format pictures are converted to: the coded one, or 4:4:4 for 4:4:0, whose
     * chroma the encoder halves vertically.
     */
    private final ChromaFormat format;

    /**
     * The color description of the stream.
     */
    private final ColorInfo color;

    /**
     * The bit depth of the stream.
     */
    private final int bitDepth;

    /**
     * The converter of the pictures in another format or color, owning their converted copies.
     */
    private final YuvConverter converter;

    /**
     * Creates the conversions of a stream's options.
     *
     * @param options the stream's options
     */
    Vp9Pictures(Vp9EncoderOptions options) {
        var subsampling = options.subsampling();
        this.format = subsampling == Vp9Subsampling.YUV422 ? ChromaFormat.YUV422
                : subsampling == Vp9Subsampling.YUV420 ? ChromaFormat.YUV420 : ChromaFormat.YUV444;
        this.color = options.color();
        this.bitDepth = options.bitDepth();
        this.converter = YuvConverter.create(format, color);
    }

    /**
     * Returns whether the stream codes 10 or 12-bit samples.
     *
     * @return whether it does
     */
    boolean high() {
        return bitDepth > 8;
    }

    /**
     * Returns a picture in the chroma format, matrix and range the stream codes, at 8 bits.
     *
     * @param picture the picture to code
     * @return {@code picture} itself when it is in that representation already, otherwise a
     * converted copy, valid until the next call
     */
    YuvPicture toCoded(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == format && sameSamples(yuv.color(), color)) {
            return yuv;
        }
        return converter.convert(picture);
    }

    /**
     * Returns a picture as a 10 or 12-bit stream codes it.
     *
     * @param picture the picture to code
     * @return {@code picture} itself when it is an 8-bit picture or a picture of the stream's bit
     * depth in the chroma format, matrix and range the stream codes, whose 8-bit samples the
     * encoder scales up, otherwise a converted copy at the stream's bit depth
     */
    Picture toCodedHigh(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == format && sameSamples(yuv.color(), color)) {
            return yuv;
        }
        if (picture instanceof Yuv16Picture yuv && yuv.chromaFormat() == format && sameSamples(yuv.color(), color)) {
            return ImageOps.toBitDepth(yuv, bitDepth);
        }
        return ImageOps.toYuv16(picture, format, color, bitDepth);
    }

    /**
     * Returns whether two color descriptions give the same Y'CbCr samples for the same image: the
     * same range and the same matrix, with an unspecified matrix taken as BT.601 and the two
     * code points of the BT.601 matrix taken as one.
     *
     * @param first one description
     * @param second the other description
     * @return whether the samples are the same
     */
    private static boolean sameSamples(ColorInfo first, ColorInfo second) {
        return first.range() == second.range() && canonical(first.matrix()) == canonical(second.matrix());
    }

    /**
     * Returns the matrix whose arithmetic a matrix applies.
     *
     * @param matrix the matrix coefficients
     * @return {@link MatrixCoefficients#BT601} for BT.601, BT.470 System B and G and unspecified,
     * otherwise {@code matrix}
     */
    private static MatrixCoefficients canonical(MatrixCoefficients matrix) {
        var resolved = matrix.effective();
        return resolved == MatrixCoefficients.BT470BG ? MatrixCoefficients.BT601 : resolved;
    }
}
