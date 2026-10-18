package com.github.auties00.smalt.image.webp.internal.anim;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaDecoder;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lDecoder;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder;

import java.util.List;

/**
 * Decodes the bitstream of one WebP image or animation frame.
 *
 * <p>Lossless frames decode to RGBA directly; lossy frames decode to Y'CbCr through the VP8 key
 * frame decoder, get the alpha plane of their {@code ALPH} chunk attached, and are converted to
 * RGBA with fancy upsampling when an RGBA result is needed.
 */
public final class FrameDecoder {
    /**
     * The fewest pixels for which the alpha plane is decoded concurrently with the color; below
     * it, handing the work over costs more than it saves.
     */
    private static final long PARALLEL_PIXELS = 128 * 128;

    /**
     * The fewest pixels for which a still picture decodes its alpha plane concurrently with the
     * color.
     */
    private static final long STILL_PARALLEL_PIXELS = 1024 * 1024;

    /**
     * Prevents instantiation.
     */
    private FrameDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads a chunk payload into a new array.
     *
     * @param in     the reader over the file
     * @param ref    the payload location
     * @param limits the resource bounds checked before allocating
     * @return the payload bytes
     * @throws MediaException.LimitExceeded if the payload exceeds {@code limits}
     * @throws MediaException.Malformed     if the input ends early
     */
    public static byte[] read(SourceReader in, WebpParser.ChunkRef ref, MediaLimits limits) {
        LimitChecks.checkAllocation(limits, ref.size());
        in.seek(ref.offset());
        return in.bytes(ref.size(), limits.maxBytes());
    }

    /**
     * Decodes a lossy bitstream and attaches the alpha plane of its {@code ALPH} payload.
     *
     * <p>A picture of at least {@link #STILL_PARALLEL_PIXELS} pixels with alpha decodes its alpha
     * plane concurrently with the color on the module's shared helpers.
     *
     * @param vp8    the {@code VP8 } payload
     * @param alph   the {@code ALPH} payload, or {@code null}
     * @param width  the width declared by the container
     * @param height the height declared by the container
     * @param limits the resource bounds
     * @return the 4:2:0 picture, with an alpha plane when {@code alph} is given
     * @throws MediaException.Malformed if the bitstream or the alpha data is corrupt, or the
     *                                  decoded size differs from the declared size
     *
     */
    public static YuvPicture decodeLossy(byte[] vp8, byte[] alph, int width, int height, MediaLimits limits) {
        var parallel = alph != null && ParallelBands.enabled() && (long) width * height >= STILL_PARALLEL_PIXELS;
        return decodeLossy(vp8, alph, width, height, limits, parallel);
    }

    /**
     * Decodes a lossy bitstream and attaches the alpha plane of its {@code ALPH} payload, decoding
     * the alpha plane concurrently with the color on the module's shared helpers when allowed.
     *
     * @implNote This implementation decodes the two concurrently when allowed, the picture has at
     * least {@link #PARALLEL_PIXELS} pixels and a helper is free; the failures are then reported in
     * the sequential order: a color failure, then a size mismatch, then an alpha failure.
     *
     * @param vp8    the {@code VP8 } payload
     * @param alph   the {@code ALPH} payload, or {@code null}
     * @param width  the width declared by the container
     * @param height the height declared by the container
     * @param limits   the resource bounds
     * @param parallel whether the color and alpha may be decoded concurrently, and the color as
     *                 a pipeline
     * @return the 4:2:0 picture, with an alpha plane when {@code alph} is given
     * @throws MediaException.Malformed if the bitstream or the alpha data is corrupt, or the
     *                                  decoded size differs from the declared size
     */
    public static YuvPicture decodeLossy(byte[] vp8, byte[] alph, int width, int height, MediaLimits limits,
                                         boolean parallel) {
        if (alph == null) {
            return checkSize(decodeColor(vp8, limits, parallel), width, height);
        }
        YuvPicture picture;
        byte[] alpha;
        if (parallel && (long) width * height >= PARALLEL_PIXELS && ParallelBands.available() > 0) {
            var parts = ParallelBands.<Object>invokeAll(List.of(
                    () -> decodeColor(vp8, limits, true),
                    () -> {
                        try {
                            return AlphaDecoder.decode(alph, 0, alph.length, width, height, limits);
                        } catch (RuntimeException | Error failure) {
                            return failure;
                        }
                    }));
            picture = checkSize((YuvPicture) parts.get(0), width, height);
            switch (parts.get(1)) {
                case RuntimeException failure -> throw failure;
                case Error failure -> throw failure;
                case Object plane -> alpha = (byte[]) plane;
            }
        } else {
            picture = checkSize(decodeColor(vp8, limits, parallel), width, height);
            alpha = AlphaDecoder.decode(alph, 0, alph.length, width, height, limits);
        }
        return YuvPicture.builder().size(width, height).chromaFormat(picture.chromaFormat()).color(picture.color())
                .luma(picture.y(), picture.yStride())
                .chroma(picture.u(), picture.v(), picture.uvStride())
                .alpha(alpha, width)
                .build();
    }

    /**
     * Decodes the color of a lossy image, pipelining parsing, reconstruction, and the loop filter
     * when allowed, and otherwise as {@link Vp8#decodeKeyFrame} chooses.
     *
     * @param vp8      the {@code VP8 } payload
     * @param limits   the resource bounds
     * @param parallel whether the color may be decoded as a pipeline
     * @return the 4:2:0 picture
     */
    private static YuvPicture decodeColor(byte[] vp8, MediaLimits limits, boolean parallel) {
        return parallel ? Vp8FrameDecoder.decodeKeyFrame(vp8, 0, vp8.length, limits, true)
                : Vp8.decodeKeyFrame(vp8, 0, vp8.length, limits);
    }

    /**
     * Checks that a decoded picture has the size the container declares.
     *
     * @param picture the picture
     * @param width   the declared width
     * @param height  the declared height
     * @return {@code picture}
     * @throws MediaException.Malformed if the sizes differ
     */
    private static YuvPicture checkSize(YuvPicture picture, int width, int height) {
        if (picture.width() != width || picture.height() != height) {
            throw new MediaException.Malformed("VP8 frame is " + picture.width() + "x" + picture.height()
                    + ", its header declares " + width + "x" + height);
        }
        return picture;
    }

    /**
     * Decodes a frame into a region of an RGBA buffer, overwriting every pixel of the region.
     *
     * @param in       the reader over the file
     * @param image    the bitstream payload
     * @param lossless whether the bitstream is VP8L
     * @param alpha    the {@code ALPH} payload of a lossy frame, or {@code null}
     * @param width    the frame width
     * @param height   the frame height
     * @param limits   the resource bounds
     * @param dst      the RGBA destination
     * @param offset   the index of the first byte of the region
     * @param stride   the distance in bytes between destination rows
     * @throws MediaException.Malformed if the frame is corrupt
     */
    public static void decodeInto(SourceReader in, WebpParser.ChunkRef image, boolean lossless, WebpParser.ChunkRef alpha,
                                  int width, int height, MediaLimits limits, byte[] dst, int offset, int stride) {
        decodeInto(in, image, lossless, alpha, width, height, limits, dst, offset, stride, false);
    }

    /**
     * Decodes one frame's bitstream and alpha into an RGBA region, decoding a lossy frame's alpha
     * plane concurrently with its color on the module's shared helpers when allowed.
     *
     * @param in       the file reader
     * @param image    the {@code VP8 } or {@code VP8L} chunk
     * @param lossless whether the image is {@code VP8L}
     * @param alpha    the {@code ALPH} chunk of a lossy image, or {@code null}
     * @param width    the frame width
     * @param height   the frame height
     * @param limits   the resource bounds
     * @param dst      the RGBA destination
     * @param offset   the index of the region's first byte
     * @param stride   the distance in bytes between the region's rows
     * @param parallel whether a lossy frame may be decoded on several threads, and otherwise as a
     *                 still image is
     * @throws MediaException.Malformed if the frame is corrupt
     */
    public static void decodeInto(SourceReader in, WebpParser.ChunkRef image, boolean lossless, WebpParser.ChunkRef alpha,
                                  int width, int height, MediaLimits limits, byte[] dst, int offset, int stride,
                                  boolean parallel) {
        var payload = read(in, image, limits);
        if (lossless) {
            var header = Vp8lDecoder.decodeRgbaInto(payload, 0, payload.length, limits, dst, offset, stride);
            if (header[0] != width || header[1] != height) {
                throw new MediaException.Malformed("VP8L frame size changed while decoding");
            }
            return;
        }
        var alph = alpha == null ? null : read(in, alpha, limits);
        var picture = parallel ? decodeLossy(payload, alph, width, height, limits, true)
                : decodeLossy(payload, alph, width, height, limits);
        YuvConverter.toRgba(picture, dst, offset, stride);
    }
}
