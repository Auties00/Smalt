package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.MjpegReducer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Objects;

/**
 * Converts the frames of one negotiated Video4Linux2 format to planar 4:2:0 pictures of even
 * size.
 *
 * <p>A frame is the content of one driver buffer: {@code height} rows of {@code bytesPerLine}
 * bytes for the packed formats, a luma plane followed by the chroma plane(s) for the planar and
 * semi-planar ones (the planar chroma rows are {@code bytesPerLine / 2} bytes apart, as the V4L2
 * specification defines), or one JPEG image. An odd width or height is cropped by one pixel.
 * Packed 4:2:2 chroma is reduced to 4:2:0 by averaging vertically adjacent samples; semi-planar
 * chroma is de-interleaved; planar chroma is copied. Motion-JPEG frames are decoded and reduced
 * by a {@link MjpegReducer}.
 *
 * <p>A picture is valid until the next conversion. Instances are not thread-safe.
 */
final class FrameConverter {
    /**
     * The captured pixel format.
     */
    private final V4l2PixelFormat format;

    /**
     * The negotiated frame width.
     */
    private final int width;

    /**
     * The negotiated frame height.
     */
    private final int height;

    /**
     * The distance between rows of the first plane, in bytes.
     */
    private final int stride;

    /**
     * The width of the pictures, {@code width} rounded down to even.
     */
    private final int outputWidth;

    /**
     * The height of the pictures, {@code height} rounded down to even.
     */
    private final int outputHeight;

    /**
     * The smallest number of bytes a raw frame must hold, or {@code 1} for JPEG.
     */
    private final int requiredBytes;

    /**
     * The picture the raw formats are converted into.
     */
    private final YuvPicture target;

    private final ConversionKernel kernel;

    /**
     * The reducer of Motion-JPEG frames, or {@code null} for the raw formats.
     */
    private final MjpegReducer mjpeg;

    /**
     * Creates a converter.
     *
     * @param format the pixel format
     * @param width the frame width in pixels
     * @param height the frame height in pixels
     * @param bytesPerLine the driver's row stride of the first plane, or 0 for a tight stride
     * @param color the color of the raw formats' samples
     * @param kernel the row kernel
     * @throws MediaException.Unsupported if the size is smaller than 2x2 or the stride cannot hold a
     * row
     * @throws MediaException.LimitExceeded if the size exceeds the default limits
     */
    FrameConverter(V4l2PixelFormat format, int width, int height, int bytesPerLine, ColorInfo color, ConversionKernel kernel) {
        this.format = Objects.requireNonNull(format, "format");
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(color, "color");
        LimitChecks.checkPicture(MediaLimits.DEFAULT, width, height);
        if (width < 2 || height < 2) {
            throw new MediaException.Unsupported("v4l2 frame size", "frames of " + width + "x" + height + " are too small");
        }
        this.width = width;
        this.height = height;
        this.outputWidth = width & ~1;
        this.outputHeight = height & ~1;
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        var minimumStride = switch (format.kind()) {
            case PLANAR -> 2 * chromaWidth;
            case SEMI_PLANAR -> 2 * chromaWidth;
            case PACKED -> 4 * chromaWidth;
            case JPEG -> 0;
        };
        var rowStride = bytesPerLine == 0 ? minimumStride : bytesPerLine;
        if (rowStride < minimumStride || (format.kind() == V4l2PixelFormat.Kind.PLANAR && rowStride / 2 < chromaWidth)) {
            throw new MediaException.Unsupported("v4l2 stride", "a stride of " + bytesPerLine + " bytes cannot hold "
                    + width + " pixels of " + format);
        }
        this.stride = rowStride;
        var required = switch (format.kind()) {
            case PLANAR -> (long) rowStride * height + (long) (rowStride / 2) * (2 * chromaHeight - 1) + chromaWidth;
            case SEMI_PLANAR -> (long) rowStride * height + (long) rowStride * (chromaHeight - 1) + 2L * chromaWidth;
            case PACKED -> (long) rowStride * (height - 1) + 4L * chromaWidth;
            case JPEG -> 1L;
        };
        LimitChecks.checkAllocation(MediaLimits.DEFAULT, required);
        this.requiredBytes = (int) required;
        this.target = YuvPicture.allocate(outputWidth, outputHeight, ChromaFormat.YUV420, false, color);
        this.mjpeg = format.kind() == V4l2PixelFormat.Kind.JPEG ? new MjpegReducer(kernel) : null;
    }

    /**
     * Returns the width of the pictures.
     *
     * @return the width, even
     */
    int outputWidth() {
        return outputWidth;
    }

    /**
     * Returns the height of the pictures.
     *
     * @return the height, even
     */
    int outputHeight() {
        return outputHeight;
    }

    /**
     * Returns the smallest number of bytes a frame must hold to be converted.
     *
     * @return the size of a raw frame, or {@code 1} for JPEG
     */
    int requiredBytes() {
        return requiredBytes;
    }

    /**
     * Converts a frame.
     *
     * @param frame the array holding the frame from index {@code 0}
     * @param length the number of bytes of the frame
     * @return the picture, valid until the next conversion, or {@code null} when the frame is
     * unusable: shorter than {@link #requiredBytes()}, or a JPEG image that does not
     * decode or has another size
     */
    YuvPicture convert(byte[] frame, int length) {
        if (length < requiredBytes || length > frame.length) {
            return null;
        }
        return switch (format.kind()) {
            case PLANAR -> planar(frame);
            case SEMI_PLANAR -> semiPlanar(frame);
            case PACKED -> packed(frame);
            case JPEG -> jpeg(frame, length);
        };
    }

    /**
     * Converts a planar 4:2:0 frame.
     *
     * @return the target picture
     */
    private YuvPicture planar(byte[] frame) {
        copyLuma(frame);
        var chromaStride = stride / 2;
        var chromaHeight = (height + 1) / 2;
        var first = stride * height;
        var second = first + chromaStride * chromaHeight;
        var u = format.cbIndex() == 0 ? first : second;
        var v = format.cbIndex() == 0 ? second : first;
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight / 2; row++) {
            System.arraycopy(frame, u + row * chromaStride, target.u(), row * outputChroma, outputChroma);
            System.arraycopy(frame, v + row * chromaStride, target.v(), row * outputChroma, outputChroma);
        }
        return target;
    }

    /**
     * Converts a semi-planar 4:2:0 frame.
     *
     * @return the target picture
     */
    private YuvPicture semiPlanar(byte[] frame) {
        copyLuma(frame);
        var chroma = stride * height;
        var first = format.cbIndex() == 0 ? target.u() : target.v();
        var second = format.cbIndex() == 0 ? target.v() : target.u();
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight / 2; row++) {
            kernel.deinterleave(frame, chroma + row * stride, outputChroma, first, second, row * outputChroma);
        }
        return target;
    }

    /**
     * Converts a packed 4:2:2 frame.
     *
     * @return the target picture
     */
    private YuvPicture packed(byte[] frame) {
        var y = target.y();
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight; row += 2) {
            var upper = row * stride;
            var lower = upper + stride;
            kernel.packedLuma(frame, upper, outputWidth, format.lumaIndex(), y, row * outputWidth);
            kernel.packedLuma(frame, lower, outputWidth, format.lumaIndex(), y, (row + 1) * outputWidth);
            kernel.packedChroma(frame, upper, lower, outputChroma, format.cbIndex(), format.crIndex(),
                    target.u(), target.v(), (row / 2) * outputChroma);
        }
        return target;
    }

    /**
     * Copies the luma plane of a planar or semi-planar frame.
     */
    private void copyLuma(byte[] frame) {
        var y = target.y();
        for (var row = 0; row < outputHeight; row++) {
            System.arraycopy(frame, row * stride, y, row * outputWidth, outputWidth);
        }
    }

    /**
     * Decodes a JPEG frame and reduces it to 4:2:0.
     *
     * @param length the number of bytes of the frame
     * @return the picture, or {@code null} when the frame does not decode or has another size
     */
    private YuvPicture jpeg(byte[] frame, int length) {
        YuvPicture reduced;
        try {
            reduced = mjpeg.decode(frame, 0, length);
        } catch (MediaException exception) {
            return null;
        }
        return reduced.width() == outputWidth && reduced.height() == outputHeight ? reduced : null;
    }
}
