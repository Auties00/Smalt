/* Ports dav1d (src/obu.c, src/decode.c, src/recon_tmpl.c, src/refmvs.c, src/lib.c, src/thread_task.c and the
   *_tmpl.c DSP templates). Copyright (c) 2018-2025, VideoLAN and dav1d authors; BSD 2-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.Av1Context;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes an AV1 video track (AOMedia AV1 Bitstream and Decoding Process Specification) into
 * pictures.
 *
 * <p>The decoder implements the Main, High and Professional profiles: 8-bit, 10-bit and 12-bit
 * 4:2:0, 4:2:2, 4:4:4 and monochrome video with every coding tool of the specification, including
 * intra block copy, palette, chroma-from-luma, every compound and motion mode, warped and global
 * motion, switchable and dual interpolation filters, every transform, the deblocking filter, CDEF,
 * loop restoration, super-resolution, scaled references, segmentation, quantizer and loop filter
 * deltas, skip mode, frame size from references, error resilient and switch frames, multiple tiles
 * and tile groups, and film grain synthesis, as well as the large scale tile decoding process of
 * tile list OBUs ({@link #setAnchorFrames(List)}). Output is bit-exact with the normative decoding
 * process.
 *
 * <p>Every packet holds one temporal unit: a sequence of OBUs (low-overhead bitstream format, as
 * stored in MP4, Matroska, WebM and IVF), with or without a temporal delimiter, or, with
 * {@link Av1DecoderOptions#annexB()}, one length-delimited temporal unit of the specification's
 * Annex B. When {@link VideoTrack#codecConfig()} holds an {@code av1C} record, its configuration
 * OBUs are decoded at construction. The decoder follows the operating point of
 * {@link Av1DecoderOptions#operatingPoint()}, 0 by default; for a scalable stream each temporal
 * unit yields only its highest spatial layer unless {@link Av1DecoderOptions#allLayers()} asks for
 * every layer. Frames are never reordered: with the default
 * {@link Av1DecoderOptions#maxFrameDelay()} of 1, a packet yields its shown frame (its shown frames
 * with every layer output), carrying the packet's timestamps, or none when it only holds hidden
 * frames; with a larger frame delay, a packet yields the frames of earlier packets whose decoding
 * completed, each carrying the timestamps of the packet that showed it, and
 * {@link #flush(Consumer)} yields the rest. Which call
 * yields a frame depends only on the frame delay, never on the threads. A frame is a keyframe when
 * it is a shown key frame or the display of an existing key frame.
 *
 * <p>Pictures are 8-bit {@link YuvPicture}s in the stream's chroma format
 * ({@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422}, {@link ChromaFormat#YUV444} or
 * {@link ChromaFormat#MONOCHROME}) at the frame size (the upscaled width when super-resolution is
 * used). High bit depth samples are reduced to 8 bits as
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Yuv16Picture) ImageOps.toEightBit}
 * defines. A decoder created with {@link Av1DecoderOptions#highBitDepth()} set hands out the frames
 * of 10 and 12-bit streams as {@link Yuv16Picture}s of the stream's bit depth instead, with the
 * samples of the normative decoding process; 8-bit streams always produce {@link YuvPicture}s. The
 * picture handed to the consumer is recycled and valid only until the consumer returns.
 *
 * <p>The color description of a picture comes from the sequence header: its matrix, primaries,
 * transfer and range, with unspecified components defaulting to BT.709 for frames wider than 1024
 * or taller than 576 samples and to BT.601 otherwise, its chroma siting
 * ({@code chroma_sample_position}), and the high-dynamic-range metadata of the last
 * {@code METADATA_TYPE_HDR_MDCV} and {@code METADATA_TYPE_HDR_CLL} metadata OBUs. When the track
 * declares a color description ({@link VideoTrack#color()}), its matrix, primaries, transfer and range
 * win; the chroma siting, mastering display and content light level it leaves unspecified come
 * from the stream.
 *
 * <p>Each call decodes on the calling thread, which the module's shared helper threads join for
 * large frames unless {@link Av1DecoderOptions#threads()} is {@code 1}; with a frame delay above 1
 * the decoding of consecutive frames overlaps as well. The pictures do not depend on the number of
 * threads; see {@link com.github.auties00.smalt.MediaRuntime} for the threading model.
 *
 * <p>A corrupt packet raises {@link MediaException.Malformed}, from the call that would yield its
 * frame; the reference slots the frame refreshed are emptied, so frames predicting from them fail
 * until a key frame restores them. An exception thrown by the consumer reaches the caller
 * unchanged. Instances are not thread-safe.
 *
 * @implNote This implementation keeps samples in 16-bit planes at every bit depth and reproduces
 * dav1d's 8-bit template for 8-bit streams and its high bit depth template otherwise. Large frames
 * are decoded in two passes, entropy decoding then reconstruction and filtering, with superblock
 * rows reconstructed in a wavefront and the tile rows and columns of a row entropy decoded in
 * parallel.
 */
public final class Av1Decoder implements VideoDecoder {
    /**
     * The decoding state.
     */
    private final Av1Context context;

    /**
     * The track being decoded.
     */
    private final VideoTrack track;

    /**
     * The pictures shown by the current call, released once handed out.
     */
    private final List<Av1Picture> shown = new ArrayList<>();

    /**
     * The recycled 8-bit output picture.
     */
    private YuvPicture picture;

    /**
     * The recycled output picture of high bit depth frames, when {@link #highBitDepth} is set.
     */
    private Yuv16Picture widePicture;

    /**
     * The options.
     */
    private final Av1DecoderOptions options;

    /**
     * Whether frames of more than 8 bits are handed out at their own bit depth.
     */
    private final boolean highBitDepth;

    /**
     * The pool scope of this decoder's calls.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Whether the decoder was closed.
     */
    private boolean closed;

    /**
     * Constructs a decoder for an AV1 track with {@link Av1DecoderOptions#defaults()}.
     *
     * @param track the track description, whose codec must be {@link Codec#AV1}
     * @throws IllegalArgumentException if the track is not an AV1 track
     * @throws MediaException.Malformed if the track's {@code av1C} configuration OBUs are corrupt
     */
    public Av1Decoder(VideoTrack track) {
        this(track, Av1DecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an AV1 track.
     *
     * @param track the track description, whose codec must be {@link Codec#AV1}
     * @param options the options, whose limits are checked against every frame's size before
     * allocating
     * @throws IllegalArgumentException if the track is not an AV1 track
     * @throws MediaException.Malformed if the track's {@code av1C} configuration OBUs are corrupt
     */
    public Av1Decoder(VideoTrack track, Av1DecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.AV1) {
            throw new IllegalArgumentException("not an av1 track: " + track.codec());
        }
        this.track = track;
        this.highBitDepth = options.highBitDepth();
        this.context = new Av1Context(options.limits());
        if (options.threads() == 1) {
            context.threading(Av1Context.Threading.OFF);
        }
        context.frameDelay(options.maxFrameDelay());
        context.operatingPoint(options.operatingPoint());
        context.allLayers(options.allLayers());
        context.annexB(options.annexB());
        var config = track.codecConfig().orElse(null);
        if (config != null && config.length > 4) {
            try {
                context.decodeConfig(config);
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("corrupt av1C configuration", exception);
            }
        }
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the options this decoder was created with}
     */
    public Av1DecoderOptions options() {
        return options;
    }

    /**
     * Decodes one packet holding one temporal unit.
     *
     * @param packet the next packet in decoding order
     * @param output receives the frames the unit shows, if any, or with a frame delay above 1 the
     * frames of earlier units that became due
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if the unit is corrupt or references a missing frame, or a
     * frame of an earlier unit that became due is corrupt, or a tile list of the unit uses an anchor
     * frame whose size, chroma format or bit depth differs from the camera frame's
     * @throws MediaException.LimitExceeded if a frame exceeds the decoder's limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("decoder is closed");
            }
            var data = packet.data();
            shown.clear();
            ConsumerFailure.<VideoFrame>run("av1 packet", output, sink -> {
                context.decode(data, 0, data.length, packet.pts(), packet.duration(), shown);
                emit(sink);
            });
        }
    }

    /**
     * Hands the frames the decoder still holds to {@code output}: with a frame delay above 1, the
     * frames of the last units, which this call finishes decoding.
     *
     * @param output receives the frames
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if one of the frames is corrupt
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("decoder is closed");
            }
            shown.clear();
            ConsumerFailure.<VideoFrame>run("av1 packet", output, sink -> {
                context.flush(shown);
                emit(sink);
            });
        }
    }

    /**
     * Converts the pictures the context returned and hands them to {@code output}, releasing them
     * even when {@code output} fails.
     *
     * @param output receives the frames
     */
    private void emit(Consumer<VideoFrame> output) {
        try {
            for (var p : shown) {
                // TODO: the 8-bit mode rounds YCgCo-Re and YCgCo-Ro samples, keeping only 6 or 7 bits of R'G'B';
                // such frames could be output as 8-bit RGBA, as the AVIF decoder does
                Picture frame = highBitDepth && p.bpc > 8 ? convertWide(p) : convert(p);
                var keyframe = p.frameHdr != null && p.frameHdr.frameType == FrameHeader.KEY;
                output.accept(new VideoFrame(frame, p.pts, p.duration > 0 ? p.duration : Track.UNKNOWN_DURATION,
                        keyframe));
            }
        } finally {
            for (var p : shown) {
                p.release();
            }
            shown.clear();
        }
    }

    /**
     * Sets the anchor frames of large scale tile decoding, which the tiles of tile list OBUs predict
     * from, or returns to the general decoding process when {@code frames} is empty.
     *
     * <p>The frames are copied, so the caller may reuse them once this method returns; frame
     * {@code i} answers {@code anchor_frame_idx} {@code i}. Each must be a {@link YuvPicture} for an
     * 8-bit stream, or a {@link Yuv16Picture} of the stream's bit depth, in the stream's chroma
     * format and at the frame size of the camera frame header; a tile list that uses a frame that
     * does not match raises {@link MediaException.Malformed}. Frames the decoder output from the
     * same stream qualify when they are 8-bit or decoded with
     * {@link Av1DecoderOptions#highBitDepth()}.
     *
     * <p>While anchor frames are set, the decoder runs the large scale tile decoding process of the
     * AV1 specification (section 7.3) on the packets that follow: a frame header OBU or frame OBU
     * sets the camera frame header and yields no frame (the frame's tile data is not decoded), tile
     * group OBUs are skipped, the reference frames are not updated, and every tile list OBU yields
     * one frame. That frame is {@code output_frame_width_in_tiles} tiles wide and
     * {@code output_frame_height_in_tiles} tiles tall, holds the listed tiles in raster order, each
     * decoded from its anchor frame in place of the frame its camera frame header names first
     * ({@code ref_frame_idx[0]}), and holds 0 in every sample no listed tile covers; it carries the
     * timestamps of its packet, is not a keyframe and has no film grain. Without anchor frames, tile
     * list OBUs are ignored. The camera frame header must code an inter frame without
     * super-resolution or reference frame motion vectors, whose tiles all have one size; it stays in
     * effect until the next one, a new sequence, or a call with no frames. A camera frame header that
     * breaks these requirements, a tile list before any camera frame header, and a tile list entry
     * that names an anchor frame or a tile that does not exist raise
     * {@link MediaException.Malformed}.
     *
     * @apiNote This is the decoding side of the light field use case of the AV1 specification's
     * Annex D: an application decodes the anchor frames of a stream as ordinary frames, sets them
     * here, then decodes the camera frame header and the tile lists it built from the camera
     * frames' tiles.
     * @implNote This implementation decodes the tiles of tile lists without CDF adaptation whatever
     * {@code disable_cdf_update} says, as libaom does, so that the camera frames libaom's light field
     * encoder writes, which leave it 0, decode; on streams that set it to 1, as the specification
     * requires, the output is the normative one.
     * @param frames the anchor frames, at most 128
     * @throws NullPointerException if a frame is {@code null}
     * @throws IllegalArgumentException if there are more than 128 frames, or a frame is not a
     * {@link YuvPicture} or {@link Yuv16Picture}, is 4:4:0 or has a bit depth other than 8, 10 or 12
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.LimitExceeded if a frame exceeds the decoder's limits
     */
    public void setAnchorFrames(List<? extends Picture> frames) {
        Objects.requireNonNull(frames, "frames");
        if (closed) {
            throw new IllegalStateException("decoder is closed");
        }
        if (frames.size() > Av1Context.MAX_ANCHOR_FRAMES) {
            throw new IllegalArgumentException(frames.size() + " anchor frames exceed "
                    + Av1Context.MAX_ANCHOR_FRAMES);
        }
        var copies = new Av1Picture[frames.size()];
        for (var i = 0; i < copies.length; i++) {
            var frame = Objects.requireNonNull(frames.get(i), "frames");
            copies[i] = switch (frame) {
                case YuvPicture yuv -> anchor(yuv.width(), yuv.height(), yuv.chromaFormat(), 8, i);
                case Yuv16Picture yuv -> anchor(yuv.width(), yuv.height(), yuv.chromaFormat(), yuv.bitDepth(), i);
                default -> throw new IllegalArgumentException("anchor frame " + i + " is not a YUV picture");
            };
            var copy = copies[i];
            if (frame instanceof YuvPicture yuv) {
                widen(yuv.y(), yuv.yStride(), copy.data[0], copy.stride[0], yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    widen(yuv.u(), yuv.uvStride(), copy.data[1], copy.stride[1], yuv.chromaWidth(),
                            yuv.chromaHeight());
                    widen(yuv.v(), yuv.uvStride(), copy.data[2], copy.stride[1], yuv.chromaWidth(),
                            yuv.chromaHeight());
                }
            } else if (frame instanceof Yuv16Picture yuv) {
                Planes.copy(yuv.y(), 0, yuv.yStride(), copy.data[0], 0, copy.stride[0], yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    Planes.copy(yuv.u(), 0, yuv.uvStride(), copy.data[1], 0, copy.stride[1], yuv.chromaWidth(),
                            yuv.chromaHeight());
                    Planes.copy(yuv.v(), 0, yuv.uvStride(), copy.data[2], 0, copy.stride[1], yuv.chromaWidth(),
                            yuv.chromaHeight());
                }
            }
        }
        context.anchorFrames(copies);
    }

    /**
     * {@return the number of anchor frames set with {@link #setAnchorFrames}, 0 while the decoder
     * runs the general decoding process}
     */
    public int anchorFrameCount() {
        return context.anchorFrameCount();
    }

    /**
     * Copies a plane of 8-bit samples into a plane of 16-bit samples.
     *
     * @param src the source samples
     * @param srcStride the source stride
     * @param dst the destination samples
     * @param dstStride the destination stride
     * @param width the samples per row
     * @param height the rows
     */
    private static void widen(byte[] src, int srcStride, short[] dst, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            var in = y * srcStride;
            var out = y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[out + x] = (short) (src[in + x] & 0xFF);
            }
        }
    }

    /**
     * Allocates the internal copy of an anchor frame, after checking its format and size.
     *
     * @param width the frame width
     * @param height the frame height
     * @param format the chroma format
     * @param bitDepth the bits per sample
     * @param index the anchor frame index, for the messages
     * @return the copy, with unspecified samples
     */
    private Av1Picture anchor(int width, int height, ChromaFormat format, int bitDepth, int index) {
        if (format == ChromaFormat.YUV440) {
            throw new IllegalArgumentException("anchor frame " + index + " is 4:4:0, which AV1 cannot code");
        }
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("anchor frame " + index + " has " + bitDepth + " bits per sample");
        }
        var layout = SequenceHeader.layoutOf(format);
        LimitChecks.checkPicture(options.limits(), width, height);
        LimitChecks.checkAllocation(options.limits(), Av1Picture.samples(width, height, layout) * 2);
        return Av1Picture.allocate(width, height, layout, bitDepth);
    }

    /**
     * Stops the frames still being decoded and releases them, the reference frames and the anchor
     * frames; closing twice has no effect.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            context.reset();
        }
    }

    /**
     * Returns the color description of a frame.
     *
     * @param seq the sequence header of the frame
     * @param w the frame width
     * @param h the frame height
     * @return the description, as the class documentation describes
     */
    private ColorInfo colorInfo(SequenceHeader seq, int w, int h) {
        var color = track.color().orElseGet(() -> signaledColor(seq, w, h));
        if (color.chromaSiting() == ChromaSiting.UNSPECIFIED) {
            color = color.withChromaSiting(seq.chromaSiting());
        }
        if (color.masteringDisplay().isEmpty()) {
            color = color.withMasteringDisplay(context.masteringDisplay());
        }
        if (color.contentLightLevel().isEmpty()) {
            color = color.withContentLightLevel(context.contentLightLevel());
        }
        return color;
    }

    /**
     * Returns the matrix, range, primaries and transfer a sequence header signals, with the
     * defaults of the frame size for the unspecified ones.
     *
     * @param seq the sequence header
     * @param w the frame width
     * @param h the frame height
     * @return the description, without chroma siting or high-dynamic-range metadata
     */
    private static ColorInfo signaledColor(SequenceHeader seq, int w, int h) {
        var fallback = w > 1024 || h > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var matrix = fallback.matrix();
        var primaries = fallback.primaries();
        var transfer = fallback.transfer();
        if (seq.colorDescriptionPresent) {
            var signaledMatrix = MatrixCoefficients.of(seq.mtrx);
            if (signaledMatrix != MatrixCoefficients.UNSPECIFIED) {
                matrix = signaledMatrix;
            }
            var signaledPrimaries = ColorPrimaries.of(seq.pri);
            if (signaledPrimaries != ColorPrimaries.UNSPECIFIED) {
                primaries = signaledPrimaries;
            }
            var signaledTransfer = TransferCharacteristics.of(seq.trc);
            if (signaledTransfer != TransferCharacteristics.UNSPECIFIED) {
                transfer = signaledTransfer;
            }
        }
        return new ColorInfo(matrix, seq.colorRange ? ColorRange.FULL : ColorRange.LIMITED, primaries, transfer);
    }

    /**
     * Copies a high bit depth decoded picture to the recycled {@link Yuv16Picture}.
     *
     * @param p the decoded picture, of more than 8 bits per sample
     * @return the output picture
     */
    private Yuv16Picture convertWide(Av1Picture p) {
        var format = SequenceHeader.chromaFormat(p.layout);
        var color = colorInfo(p.seqHdr, p.w, p.h);
        var target = widePicture;
        if (target == null || target.width() != p.w || target.height() != p.h || target.chromaFormat() != format
                || target.bitDepth() != p.bpc || !target.color().equals(color)) {
            target = Yuv16Picture.allocate(p.w, p.h, format, p.bpc, false, color);
            widePicture = target;
        }
        Planes.copy(p.data[0], 0, p.stride[0], target.y(), 0, target.yStride(), p.w, p.h);
        if (format.hasChroma()) {
            var cw = target.chromaWidth();
            var ch = target.chromaHeight();
            Planes.copy(p.data[1], 0, p.stride[1], target.u(), 0, target.uvStride(), cw, ch);
            Planes.copy(p.data[2], 0, p.stride[1], target.v(), 0, target.uvStride(), cw, ch);
        }
        return target;
    }

    /**
     * Converts a decoded picture to the recycled 8-bit output picture.
     *
     * @param p the decoded picture
     * @return the output picture
     */
    private YuvPicture convert(Av1Picture p) {
        var format = SequenceHeader.chromaFormat(p.layout);
        var color = colorInfo(p.seqHdr, p.w, p.h);
        var target = picture;
        if (target == null || target.width() != p.w || target.height() != p.h || target.chromaFormat() != format
                || !target.color().equals(color)) {
            target = YuvPicture.allocate(p.w, p.h, format, false, color);
            picture = target;
        }
        var shift = p.bpc - 8;
        Narrow.plane(p.data[0], 0, p.stride[0], target.y(), 0, target.yStride(), p.w, p.h, shift, true);
        if (format.hasChroma()) {
            var cw = target.chromaWidth();
            var ch = target.chromaHeight();
            Narrow.plane(p.data[1], 0, p.stride[1], target.u(), 0, target.uvStride(), cw, ch, shift, true);
            Narrow.plane(p.data[2], 0, p.stride[1], target.v(), 0, target.uvStride(), cw, ch, shift, true);
        }
        return target;
    }
}
