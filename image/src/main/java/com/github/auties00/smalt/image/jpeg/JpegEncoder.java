package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jpeg.internal.JpegWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Encodes pictures as baseline JFIF JPEG files.
 *
 * <p>The output is a strictly ordered, minimal file: SOI, a JFIF 1.01 {@code APP0} segment (aspect
 * ratio 1:1, no thumbnail) as the first segment, the DQT segments, SOF0, the DHT segments, a DRI
 * segment when restart markers are requested, SOS, the entropy-coded data, and EOI. The only
 * other segments written are the {@code APP2 ICC_PROFILE} segments of
 * {@link JpegEncoderOptions#colorProfile()}, right after the JFIF segment; other metadata such as
 * EXIF must be added by the caller if wanted. Only sequential Huffman coding with 8-bit samples is
 * produced.
 *
 * <p>The input is converted to full-range BT.601 Y'CbCr, JPEG's color space:
 * <ul>
 *   <li>A {@link YuvPicture} with {@link ColorInfo#JPEG}-compatible samples (BT.601 matrix, full
 *   range) whose layout equals the requested subsampling is coded as is, without any
 *   resampling.</li>
 *   <li>A {@link YuvPicture} in another layout has its chroma brought to full resolution with a
 *   triangle filter and then downsampled like RGB input; a limited-range or non-BT.601 picture
 *   (BT.709, BT.2020, FCC, SMPTE 240M, identity, YCgCo) is converted through R'G'B'.</li>
 *   <li>An {@link RgbaPicture} is converted with fixed-point BT.601 arithmetic. Its alpha channel
 *   is ignored: callers that need transparency composited onto a background must flatten the
 *   picture first.</li>
 * </ul>
 * Chroma is downsampled by averaging, and the forward DCT is the accurate integer transform. For
 * the same RGB input and settings the file is byte-identical to libjpeg-turbo's
 * {@code cjpeg -dct int} (with {@code -optimize} when Huffman optimization is enabled).
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
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels, or is
     * a {@link YuvPicture} with a color matrix that has no conversion to BT.601
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
     * @param options the quality, subsampling, Huffman, restart and color profile settings
     * @param sink the output; not closed
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels, or is
     * a {@link YuvPicture} with a color matrix that has no conversion to BT.601
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
     * @param picture the picture; its alpha channel, if any, is ignored, and a high-bit-depth
     * picture is first reduced to 8 bits with
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Picture)}
     * @param options the quality, subsampling, Huffman, restart and color profile settings
     * @return the JPEG file
     * @throws IllegalArgumentException if the picture is wider or taller than 65535 pixels, the
     * JPEG limit, or is a {@link YuvPicture} with a color matrix that has no conversion to BT.601
     * (BT.2020 constant luminance, SMPTE 2085, chromaticity-derived, ICtCp)
     * @throws MediaException.LimitExceeded if the color profile needs more than 255
     * {@code APP2} segments
     */
    public static byte[] encode(Picture picture, JpegEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return JpegWriter.encode(picture, options.quality(), options.chromaFormat(), options.optimizeHuffman(),
                    options.restartInterval(), options.colorProfile().map(ColorProfile::toByteArray).orElse(null));
        }
    }
}
