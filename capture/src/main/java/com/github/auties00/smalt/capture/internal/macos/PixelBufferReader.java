package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.YuvPicture;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/**
 * Copies the image of a {@code CVPixelBuffer} into a planar 4:2:0 picture.
 *
 * <p>NV12 buffers ({@code '420v'} and {@code '420f'}) are copied plane by plane, respecting each
 * plane's bytes per row, and labeled with the color their attachments describe; BGRA buffers
 * ({@code 'BGRA'}) are converted to limited-range BT.709. A reader is used by one capturing thread
 * at a time.
 */
final class PixelBufferReader {
    /**
     * The converter doing the copy.
     */
    private final FrameConverter converter;

    /**
     * Creates a reader.
     */
    PixelBufferReader() {
        this.converter = new FrameConverter();
    }

    /**
     * Copies a pixel buffer into a picture.
     *
     * @param pixelBuffer the {@code CVPixelBufferRef}
     * @param target the picture to fill
     * @return the filled picture: {@code target}, or a picture wrapping its planes with the
     * buffer's color description; {@code null} when the buffer could not be locked
     * @throws MediaException.Io if the buffer has a pixel format or layout this reader cannot read
     */
    YuvPicture read(MemorySegment pixelBuffer, YuvPicture target) {
        if (!CoreVideo.lock(pixelBuffer)) {
            return null;
        }
        try {
            var format = CoreVideo.pixelFormat(pixelBuffer);
            ColorInfo color;
            if (format == FourCc.NV12_VIDEO_RANGE || format == FourCc.NV12_FULL_RANGE) {
                if (!CoreVideo.isPlanar(pixelBuffer) || CoreVideo.planeCount(pixelBuffer) < 2) {
                    throw unreadable("an NV12 buffer without two planes");
                }
                var luma = CoreVideo.plane(pixelBuffer, 0);
                var chroma = CoreVideo.plane(pixelBuffer, 1);
                if (!luma.readable() || !chroma.readable()) {
                    throw unreadable("an NV12 buffer without readable planes");
                }
                var range = format == FourCc.NV12_VIDEO_RANGE ? ColorRange.LIMITED : ColorRange.FULL;
                color = ColorAttachments.resolve(CoreVideo.matrix(pixelBuffer), CoreVideo.primaries(pixelBuffer),
                        CoreVideo.transfer(pixelBuffer), range, luma.height());
                converter.nv12(luma.memory(), luma.stride(), luma.width(), luma.height(),
                        chroma.memory(), chroma.stride(), chroma.width(), chroma.height(),
                        target, range == ColorRange.LIMITED ? 16 : 0);
            } else if (format == FourCc.BGRA) {
                var pixels = CoreVideo.packed(pixelBuffer);
                if (!pixels.readable()) {
                    throw unreadable("a BGRA buffer without readable memory");
                }
                color = YuvCoefficients.BT709_LIMITED.color();
                converter.bgra(pixels.memory(), pixels.stride(), pixels.width(), pixels.height(), target,
                        YuvCoefficients.BT709_LIMITED);
            } else {
                throw unreadable("pixel format " + FourCc.toString(format));
            }
            return target.color().equals(color) ? target : relabel(target, color);
        } catch (IllegalArgumentException inconsistent) {
            throw new MediaException.Io("the capture device delivered an inconsistent frame",
                    new IOException(inconsistent.getMessage(), inconsistent));
        } finally {
            CoreVideo.unlock(pixelBuffer);
        }
    }

    /**
     * Wraps a picture's planes with another color description.
     *
     * @param color the color description
     * @return the relabeled picture, sharing the planes
     */
    static YuvPicture relabel(YuvPicture picture, ColorInfo color) {
        return YuvPicture.builder().size(picture.width(), picture.height()).chromaFormat(ChromaFormat.YUV420).color(color)
                .luma(picture.y(), picture.yStride())
                .chroma(picture.u(), picture.v(), picture.uvStride())
                .build();
    }

    /**
     * Builds the failure raised for a buffer this reader cannot read.
     *
     * @param what the unreadable feature
     * @return the failure
     */
    private static MediaException.Io unreadable(String what) {
        return new MediaException.Io("the capture device delivered " + what,
                new IOException("unreadable CVPixelBuffer: " + what));
    }
}
