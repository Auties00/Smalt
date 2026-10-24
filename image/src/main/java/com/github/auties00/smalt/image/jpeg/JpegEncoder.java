package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegEncoding;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Encodes pictures as JPEG files (ITU-T T.81): baseline and extended sequential, progressive and lossless,
 * Huffman or arithmetic coded, at 8 or 12 bits per sample (2 to 16 lossless), in YCbCr (JFIF), gray, RGB,
 * CMYK or YCCK.
 *
 * <p>The output is a strictly ordered, minimal file: SOI, a JFIF 1.01 {@code APP0} segment with the
 * {@link JpegEncoderOptions#density() density} and no thumbnail for YCbCr and gray, or an Adobe {@code APP14}
 * segment (transform 0 for RGB and CMYK, 2 for YCCK) for the other color spaces and transform 1 for a lossless
 * 9-bit YCbCr frame whose three components are all sampled 1x1 (a shape readers take for the RGB samples of a
 * reversible color transform when it has a JFIF header), the {@code APP2 ICC_PROFILE}
 * segments of {@link JpegEncoderOptions#colorProfile()}, the DQT segments, the SOF segment, and for each scan
 * its DHT segments (or a DAC segment with the default conditioning when arithmetic coded), a DRI segment when
 * the restart interval differs from the previous scan's, SOS and the entropy-coded data, then EOI. Other
 * metadata such as EXIF must be added by the caller if wanted. The SOF marker follows from the options:
 * {@code SOF0} (baseline) for 8-bit sequential Huffman coding with quantizers up to 255, {@code SOF1} for
 * other sequential Huffman coding, {@code SOF2} progressive, {@code SOF3} lossless, and {@code SOF9},
 * {@code SOF10} and {@code SOF11} for their arithmetic-coded forms.
 *
 * <p>The input is converted to the components of {@link JpegEncoderOptions#colorSpace()} at
 * {@link JpegEncoderOptions#precision()} bits:
 * <ul>
 *   <li>A {@link YuvPicture} or {@link com.github.auties00.smalt.image.Yuv16Picture Yuv16Picture} with
 *   {@link ColorInfo#JPEG}-compatible samples (BT.601 matrix, full range) whose layout equals the frame's
 *   sampling factors is coded as is, without any resampling or conversion other than a rescaling to the
 *   precision.</li>
 *   <li>An 8-bit {@link YuvPicture} in another layout has its chroma brought to full resolution with a
 *   triangle filter and then downsampled like RGB input; a limited-range or non-BT.601 picture
 *   (BT.709, BT.2020, FCC, SMPTE 240M, identity, YCgCo) is converted through R'G'B', and a picture in
 *   any other matrix is converted with
 *   {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(Picture) ImageOps.toRgba} first, at its full
 *   depth. A 16-bit planar picture that is not coded as is goes through R'G'B' with
 *   {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba16(Picture) ImageOps.toRgba16}.</li>
 *   <li>An {@link RgbaPicture} is converted to YCbCr or gray with fixed-point BT.601 arithmetic, coded as is
 *   in RGB, and converted to CMYK naively, with {@code K = 1 - max(R, G, B)}. Its alpha channel
 *   is ignored: callers that need transparency composited onto a background must flatten the
 *   picture first.</li>
 *   <li>A {@link com.github.auties00.smalt.image.ChannelPicture ChannelPicture} of the CMYK layout is coded
 *   as inverted inks in CMYK and YCCK; any other channel picture, and a CMYK one in another color space, is
 *   first converted with {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(Picture) ImageOps.toRgba}
 *   (or {@code toRgba16} for more than 8 bits).</li>
 *   <li>A 16-bit RGBA picture, or a 16-bit planar one with full-range BT.601 samples, coded at 8 bits is first
 *   reduced with
 *   {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Picture) ImageOps.toEightBit}; samples of
 *   any other depth are rescaled to the precision as {@link JpegEncoderOptions#precision()} describes.</li>
 * </ul>
 * Components are downsampled by averaging, and the forward DCT is the accurate integer transform. For
 * the same RGB or gray input (8-bit, or 16-bit coded at more than 8 bits) and settings the file is
 * byte-identical to libjpeg-turbo's
 * {@code cjpeg -dct int} with the matching switches ({@code -optimize}, {@code -progressive},
 * {@code -arithmetic}, {@code -lossless}, {@code -precision}, {@code -rgb}, {@code -grayscale},
 * {@code -sample}, {@code -quality}, {@code -qtables}, {@code -qslots}, {@code -scans}, {@code -restart} in
 * blocks, and {@code -baseline} unless {@link JpegEncoderOptions#baselineQuantization()} is off), and the
 * CMYK and YCCK files are byte-identical to those of libjpeg-turbo's TurboJPEG API from the same samples.
 * The output does not depend on the number of threads.
 *
 * <p>This class is thread-safe.
 */
public final class JpegEncoder {
    /**
     * Prevents instantiation.
     */
    private JpegEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link JpegEncoderOptions#defaults()}.
     *
     * @param picture the picture; its alpha channel, if any, is ignored
     * @return the JPEG file
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels
     * @see #encode(Picture, JpegEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, JpegEncoderOptions.defaults());
    }

    /**
     * Encodes a picture and writes the JPEG file to a sink, from its position.
     *
     * <p>The bytes written are those {@link #encode(Picture, JpegEncoderOptions)} returns.
     *
     * @param picture the picture; its alpha channel, if any, is ignored
     * @param options the settings
     * @param sink the output; not closed
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels, or the settings
     * do not fit it as {@link #encode(Picture, JpegEncoderOptions)} describes
     * @throws MediaException.LimitExceeded if the color profile needs more than 255
     * {@code APP2} segments
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, JpegEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture; its alpha channel, if any, is ignored, and its samples are converted as
     * the class documentation describes
     * @param options the settings
     * @return the JPEG file
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels, the
     * JPEG limit, or the settings do not fit the picture: a point transform not below the automatic
     * precision, sampling factors that do not divide the largest ones or put more than 10 data units in an
     * MCU, a progressive bit position beyond the limit of the precision, or a lossless restart interval
     * that is not a whole number of MCU rows
     * @throws MediaException.LimitExceeded if the color profile needs more than 255
     * {@code APP2} segments
     */
    public static byte[] encode(Picture picture, JpegEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return JpegEncoding.encode(picture, options);
        }
    }
}
