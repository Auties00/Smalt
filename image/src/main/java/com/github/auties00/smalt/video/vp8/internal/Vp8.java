package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;

/**
 * Decodes and encodes single VP8 key frames, the payload of a WebP {@code VP8 } chunk.
 *
 * <p>A lossy WebP image is one VP8 key frame (RFC 6386) wrapped in a RIFF container. This class
 * works on the frame alone: the bytes from the frame tag through the last token partition,
 * without the chunk header or its padding byte. Pictures are 8-bit 4:2:0 in limited-range BT.601
 * ({@link com.github.auties00.smalt.image.ColorInfo#BT601_LIMITED}), the color space VP8
 * and WebP define.
 *
 * <p>This class is thread-safe. Arguments that are {@code null} cause a
 * {@link NullPointerException}.
 */
public final class Vp8 {
    /**
     * The fewest pixels for which {@link #decodeKeyFrame} pipelines parsing, reconstruction, and
     * the loop filter on several threads.
     */
    private static final long PARALLEL_PIXELS = 1024 * 1024;

    /**
     * Prevents instantiation.
     */
    private Vp8() {
        throw new AssertionError();
    }

    /**
     * Decodes one VP8 key frame.
     *
     * <p>The result is bit-exact with libvpx and with libwebp's {@code dwebp -yuv}. The frame must
     * be a displayable key frame of bitstream version 0 to 3.
     *
     * @implNote This implementation pipelines parsing, reconstruction, and the loop filter on
     * threads of the module's shared helpers when the frame header declares at least
     * {@link #PARALLEL_PIXELS} pixels, below which handing the stages over costs more than it
     * saves; the picture is identical either way.
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the frame size in bytes
     * @param limits the bounds checked before allocating
     * @return the picture, cropped to the frame size
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the frame is not a key frame, is truncated, or is corrupt
     * @throws MediaException.Unsupported if the frame is flagged as not displayable
     * @throws MediaException.LimitExceeded if the frame exceeds {@code limits}
     */
    public static YuvPicture decodeKeyFrame(byte[] data, int offset, int length, MediaLimits limits) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(limits, "limits");
            Objects.checkFromIndexSize(offset, length, data.length);
            var parallel = ParallelBands.enabled() && declaredPixels(data, offset, length) >= PARALLEL_PIXELS;
            return Vp8FrameDecoder.decodeKeyFrame(data, offset, length, limits, parallel);
        }
    }

    /**
     * Returns the pixel count a key frame header declares, without validating the frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the frame size in bytes
     * @return the declared width times height, or {@code 0} when the bytes do not start with a
     * key frame header
     */
    private static long declaredPixels(byte[] data, int offset, int length) {
        if (length < 10 || (data[offset] & 1) != 0) {
            return 0;
        }
        var width = ((data[offset + 6] & 0xFF) | (data[offset + 7] & 0xFF) << 8) & 0x3FFF;
        var height = ((data[offset + 8] & 0xFF) | (data[offset + 9] & 0xFF) << 8) & 0x3FFF;
        return (long) width * height;
    }

    /**
     * Encodes a picture as one VP8 key frame.
     *
     * <p>The returned bytes are exactly a WebP {@code VP8 } chunk payload: the frame tag, the key
     * frame start code and dimensions, the first partition, the partition sizes, and the token
     * partitions. No padding byte is appended; a RIFF writer pads odd sizes itself. The picture
     * must be 4:2:0 and at most 16383 pixels per side; its color description is not inspected,
     * so callers convert other color spaces to limited-range BT.601 first.
     *
     * @param picture the picture to encode
     * @param options the encoder settings
     * @return the encoded frame
     * @throws IllegalArgumentException if the picture is not 4:2:0 or exceeds 16383 pixels per side
     * @throws MediaException.Unsupported if the first partition exceeds the 512 KiB the format
     * allows, which only extremely large pictures reach
     */
    public static byte[] encodeKeyFrame(YuvPicture picture, Vp8KeyFrameSettings options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(options, "options");
            if (picture.chromaFormat() != ChromaFormat.YUV420) {
                throw new IllegalArgumentException("vp8 encodes 4:2:0 pictures, not " + picture.chromaFormat());
            }
            if (picture.width() > 16383 || picture.height() > 16383) {
                throw new IllegalArgumentException("picture size " + picture.width() + "x" + picture.height()
                        + " exceeds the vp8 maximum of 16383x16383");
            }
            return new Vp8KeyFrameEncoder(picture, options).encode();
        }
    }
}
