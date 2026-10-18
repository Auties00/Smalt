/* Ports libvpx 1.17.0 (vp9/vp9_dx_iface.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9Frame;
import com.github.auties00.smalt.video.vp9.internal.Vp9FrameDecoder;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes a VP9 video stream into pictures.
 *
 * <p>Every packet holds one frame or a superframe of several, as stored in WebM, MP4 and IVF;
 * hidden frames (alternate references) are decoded without output. A packet yields at most one
 * frame, carrying the packet's timestamps: the frame its last coded frame shows, either itself
 * or, with {@code show_existing_frame}, a reference. Frames are never reordered, so
 * {@link #flush(Consumer)} has nothing to drain.
 *
 * <p>All four profiles are decoded: profile 0 (8-bit 4:2:0), profile 1 (8-bit 4:2:2, 4:4:4 and
 * 4:4:0), profile 2 (10 and 12-bit 4:2:0) and profile 3 (10 and 12-bit 4:2:2, 4:4:4 and 4:4:0).
 * Pictures are 8-bit {@link YuvPicture}s of the coded frame size in {@link ChromaFormat#YUV420},
 * {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}; 4:4:0 frames, which no chroma
 * format represents, raise {@link MediaException.Unsupported} {@code "vp9 4:4:0"} when they are
 * shown. 10 and 12-bit frames are decoded at full precision and rounded to 8 bits on output:
 * sample {@code v} of bit depth {@code d} becomes {@code min(255, (v + 2^(d - 9)) >> (d - 8))}. A
 * decoder created with {@link VideoDecoderOptions#highBitDepth()} set hands them out as
 * {@link Yuv16Picture}s of the stream's bit depth instead, holding the full-precision samples;
 * reducing them with
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture)} gives exactly the
 * default 8-bit output. 8-bit frames are always {@link YuvPicture}s. The picture handed to the
 * consumer is recycled and valid only until the consumer returns.
 *
 * <p>The color description comes from the track when the container declares one, otherwise from
 * the frame header's color space and range:
 * <table class="striped">
 * <caption>Color description of each VP9 color space</caption>
 * <thead>
 * <tr><th scope="col">{@code color_space}</th><th scope="col">Matrix</th>
 * <th scope="col">Primaries and transfer</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>{@code CS_UNKNOWN}, {@code CS_RESERVED}</td><td>{@link MatrixCoefficients#BT601}</td>
 * <td>SMPTE 170M</td></tr>
 * <tr><td>{@code CS_BT_601}</td><td>{@link MatrixCoefficients#BT470BG}</td><td>SMPTE 170M</td></tr>
 * <tr><td>{@code CS_SMPTE_170}</td><td>{@link MatrixCoefficients#BT601}</td><td>SMPTE 170M</td></tr>
 * <tr><td>{@code CS_BT_709}</td><td>{@link MatrixCoefficients#BT709}</td><td>BT.709</td></tr>
 * <tr><td>{@code CS_SMPTE_240}</td><td>{@link MatrixCoefficients#SMPTE240M}</td><td>SMPTE 240M</td></tr>
 * <tr><td>{@code CS_BT_2020}</td><td>{@link MatrixCoefficients#BT2020_NCL}</td>
 * <td>BT.2020, with the transfer of the stream's bit depth</td></tr>
 * <tr><td>{@code CS_SRGB}</td><td>{@link MatrixCoefficients#IDENTITY}, full range</td>
 * <td>BT.709 primaries, sRGB transfer</td></tr>
 * </tbody>
 * </table>
 * The range is the header's except for {@code CS_SRGB}. The intended display size the header may
 * carry is reported by {@link #renderWidth()} and {@link #renderHeight()} but not applied.
 *
 * <p>A corrupt packet raises {@link MediaException.Malformed}. The decoder then rejects inter
 * frames, each with {@link MediaException.Malformed}, until a key frame or an intra-only frame
 * restores valid references; decoding continues normally from there. A stream must start with
 * such a frame.
 *
 * <p>Unless {@link VideoDecoderOptions#threads()} is {@code 1}, the tile columns of a frame, the
 * rows of its loop filter and the rounding of high bit depth frames run on the module's shared
 * pool, as {@link com.github.auties00.smalt.MediaRuntime} describes; a call returns only once its
 * frame is complete, and the pictures do not depend on the threading. Instances are not
 * thread-safe.
 *
 * <p>Decoded pictures are bit-identical to libvpx's.
 */
public final class Vp9Decoder implements VideoDecoder {
    /**
     * The frame decoder holding the reference frames and probabilities.
     */
    private final Vp9FrameDecoder decoder;

    private final VideoTrack track;

    /**
     * The recycled output picture of high bit depth frames rounded to 8 bits.
     */
    private YuvPicture converted;

    private final VideoDecoderOptions options;

    /**
     * Whether 10 and 12-bit frames are handed out at their own bit depth.
     */
    private final boolean highBitDepth;

    /**
     * Whether the work of a frame may run on the pool.
     */
    private final boolean parallel;

    /**
     * The pool scope of the decoder's calls.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Constructs a decoder for a VP9 track with {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#VP9}
     * @throws IllegalArgumentException if the track is not a VP9 track
     */
    public Vp9Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a VP9 track.
     *
     * @param track the track description; its codec must be {@link Codec#VP9}
     * @param options the options, whose limits are checked against every frame size before
     * allocating, whose thread count works as the class description says and whose
     * {@link VideoDecoderOptions#highBitDepth()} chooses the representation of 10 and 12-bit frames
     * @throws IllegalArgumentException if the track is not a VP9 track
     */
    public Vp9Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.VP9) {
            throw new IllegalArgumentException("not a vp9 track: " + track.codec());
        }
        this.track = track;
        this.highBitDepth = options.highBitDepth();
        this.parallel = options.threads() != 1 && ParallelBands.enabled();
        this.decoder = new Vp9FrameDecoder(options.limits(), parallel);
    }

    /**
     * {@return the options this decoder was created with}
     */
    public VideoDecoderOptions options() {
        return options;
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the intended display width of the last shown frame, which the frame header may
     * set apart from the coded width, or 0 before the first frame}
     */
    public int renderWidth() {
        var frame = decoder.shownFrame();
        return frame == null ? 0 : frame.renderWidth;
    }

    /**
     * {@return the intended display height of the last shown frame, or 0 before the first frame}
     */
    public int renderHeight() {
        var frame = decoder.shownFrame();
        return frame == null ? 0 : frame.renderHeight;
    }

    /**
     * Decodes one packet holding one VP9 frame or superframe.
     *
     * @param packet the next packet in decoding order
     * @param output receives the frame when the packet shows one
     * @throws MediaException.Malformed if a frame is corrupt, or is an inter frame without valid
     * references
     * @throws MediaException.Unsupported if a shown frame is 4:4:0
     * @throws MediaException.LimitExceeded if a frame exceeds the decoder's limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            var data = packet.data();
            decoder.decodePacket(data, 0, data.length);
            var frame = decoder.shownFrame();
            if (frame == null) {
                return;
            }
            output.accept(new VideoFrame(picture(frame), packet.pts(),
                    packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, decoder.shownKeyFrame()));
        }
    }

    /**
     * Returns the picture of a decoded frame: the frame's planes wrapped when they are 8-bit or
     * when high bit depth output is requested, and rounded to 8 bits into a recycled picture
     * otherwise.
     *
     * @param frame the decoded frame, with its header
     * @return the picture
     * @throws MediaException.Unsupported if the frame is 4:4:0
     */
    private Picture picture(Vp9Frame frame) {
        ChromaFormat format;
        if (frame.ssX == 1 && frame.ssY == 1) {
            format = ChromaFormat.YUV420;
        } else if (frame.ssX == 1) {
            format = ChromaFormat.YUV422;
        } else if (frame.ssY == 0) {
            format = ChromaFormat.YUV444;
        } else {
            throw new MediaException.Unsupported("vp9 4:4:0", "vp9 4:4:0 chroma subsampling has no picture format");
        }
        var color = track.color().orElseGet(() -> colorInfo(frame));
        var width = frame.width;
        var height = frame.height;
        if (!frame.highBitDepth()) {
            return YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                    .luma(frame.planes8[0], frame.strides[0])
                    .chroma(frame.planes8[1], frame.planes8[2], frame.strides[1])
                    .build();
        }
        if (highBitDepth) {
            return Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(frame.bitDepth).color(color)
                    .luma(frame.planes16[0], frame.strides[0])
                    .chroma(frame.planes16[1], frame.planes16[2], frame.strides[1])
                    .build();
        }
        var target = converted;
        if (target == null || target.width() != width || target.height() != height || target.chromaFormat() != format
                || !target.color().equals(color)) {
            target = YuvPicture.allocate(width, height, format, false, color);
            converted = target;
        }
        var shift = frame.bitDepth - 8;
        Narrow.plane(frame.planes16[0], 0, frame.strides[0], target.y(), 0, target.yStride(), width, height, shift,
                parallel);
        var chromaWidth = target.chromaWidth();
        var chromaHeight = target.chromaHeight();
        Narrow.plane(frame.planes16[1], 0, frame.strides[1], target.u(), 0, target.uvStride(), chromaWidth,
                chromaHeight, shift, parallel);
        Narrow.plane(frame.planes16[2], 0, frame.strides[2], target.v(), 0, target.uvStride(), chromaWidth,
                chromaHeight, shift, parallel);
        return target;
    }

    /**
     * Returns the color description of a frame's header, as the class description tabulates.
     *
     * @param frame the decoded frame, with its header
     * @return the description
     */
    private static ColorInfo colorInfo(Vp9Frame frame) {
        var range = frame.fullRange ? ColorRange.FULL : ColorRange.LIMITED;
        var matrix = MatrixCoefficients.of(VideoConfigs.vp9Matrix(frame.colorSpace));
        return switch (matrix) {
            case BT709 -> new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.BT709);
            case SMPTE240M -> new ColorInfo(matrix, range, ColorPrimaries.SMPTE240M, TransferCharacteristics.SMPTE240M);
            case BT2020_NCL -> new ColorInfo(matrix, range, ColorPrimaries.BT2020,
                    frame.bitDepth == 12 ? TransferCharacteristics.BT2020_12 : TransferCharacteristics.BT2020_10);
            case IDENTITY -> new ColorInfo(matrix, ColorRange.FULL, ColorPrimaries.BT709, TransferCharacteristics.SRGB);
            case BT470BG -> new ColorInfo(matrix, range, ColorPrimaries.SMPTE170M, TransferCharacteristics.SMPTE170M);
            default -> new ColorInfo(MatrixCoefficients.BT601, range, ColorPrimaries.SMPTE170M,
                    TransferCharacteristics.SMPTE170M);
        };
    }

    /**
     * {@inheritDoc}
     * <p>VP9 frames are never held back, so nothing is emitted.
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     * <p>The decoder owns no threads and no work of it runs once {@link #decode} has returned, so
     * this does nothing.
     */
    @Override
    public void close() {
    }
}
