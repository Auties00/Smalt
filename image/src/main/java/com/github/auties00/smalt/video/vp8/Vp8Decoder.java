package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes a VP8 video stream (RFC 6386) into pictures.
 *
 * <p>Every packet holds exactly one compressed frame, as stored in WebM and IVF. Frames are never
 * reordered: a packet yields one frame, carrying the packet's timestamps, unless its
 * {@code show_frame} flag is clear (an invisible alternate reference frame) or it is empty (a
 * dropped frame), in which cases it yields none. {@link #flush(Consumer)} has nothing to drain.
 *
 * <p>Pictures are 8-bit 4:2:0 {@link YuvPicture}s cropped to the frame size, in limited-range
 * BT.601 unless the track declares a color description. Key frames may change the frame size;
 * the scale fields of the key frame header are reported by {@link #horizontalScaling()} and
 * {@link #verticalScaling()} but not applied. The picture handed to the consumer is recycled and
 * valid only until the consumer returns.
 *
 * <p>A corrupt packet raises {@link MediaException.Malformed}. The decoder then rejects inter
 * frames, each with {@link MediaException.Malformed}, until the next key frame restores valid
 * references; decoding continues normally from there.
 *
 * <p>Decoded pictures are bit-exact with those of libvpx, the reference VP8 decoder.
 *
 * <p>Instances are not thread-safe. Unless {@link VideoDecoderOptions#threads()} is {@code 1},
 * a frame may be decoded by a pipeline of parsing on the calling thread and reconstruction and
 * loop filtering on threads of the library's shared pool (see
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}), at most {@code threads} in all
 * when it is above one; a call returns only once every stage of its frame has finished. The
 * pictures do not depend on the threading.
 */
public final class Vp8Decoder implements VideoDecoder {
    /**
     * The frame decoder holding the reference frames and entropy state.
     */
    private final Vp8FrameDecoder decoder;

    /**
     * The track being decoded.
     */
    private final VideoTrack track;

    /**
     * The options.
     */
    private final VideoDecoderOptions options;

    /**
     * The recycled output picture.
     */
    private YuvPicture picture;

    /**
     * The operation the calls of this decoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Constructs a decoder for a VP8 track with {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#VP8}
     * @throws IllegalArgumentException if the track is not a VP8 track
     */
    public Vp8Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a VP8 track.
     *
     * <p>The thread count of {@link VideoDecoderOptions#threads()} works as the class description
     * says. {@link VideoDecoderOptions#highBitDepth()} has no effect, since VP8 pictures are always
     * 8-bit.
     *
     * @param track the track description; its codec must be {@link Codec#VP8}
     * @param options the limits, checked against every key frame's size before allocating, and the
     * thread count
     * @throws IllegalArgumentException if the track is not a VP8 track
     */
    public Vp8Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.VP8) {
            throw new IllegalArgumentException("not a vp8 track: " + track.codec());
        }
        this.track = track;
        this.decoder = new Vp8FrameDecoder(options.limits(), false);
        this.decoder.setThreads(options.threads());
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
     * {@return the horizontal upscaling the last key frame asks the display for, or
     * {@link Vp8Scale#NONE} before the first key frame}
     *
     * <p>The decoded frames are not scaled; the scaling is a display hint.
     */
    public Vp8Scale horizontalScaling() {
        return Vp8Scale.of(decoder.horizontalScale());
    }

    /**
     * {@return the vertical upscaling the last key frame asks the display for, or
     * {@link Vp8Scale#NONE} before the first key frame}
     *
     * <p>The decoded frames are not scaled; the scaling is a display hint.
     */
    public Vp8Scale verticalScaling() {
        return Vp8Scale.of(decoder.verticalScale());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A packet yields at most one frame.
     *
     * @throws MediaException.Malformed if the frame is corrupt, or is an inter frame without valid
     * references
     * @throws MediaException.LimitExceeded if a key frame exceeds the decoder's limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            var data = packet.data();
            decoder.setOutput(picture);
            decoder.decode(data, 0, data.length);
            if (!decoder.showFrame()) {
                return;
            }
            try {
                picture = decoder.copyPicture(picture);
            } catch (RuntimeException exception) {
                if (exception instanceof MediaException mediaException) {
                    throw mediaException;
                }
                throw new MediaException.Malformed("corrupt vp8 frame", exception);
            }
            var shown = picture;
            if (track.color().isPresent() && !track.color().get().equals(picture.color())) {
                shown = YuvPicture.builder().size(picture.width(), picture.height())
                        .chromaFormat(picture.chromaFormat())
                        .color(track.color().get())
                        .luma(picture.y(), picture.yStride())
                        .chroma(picture.u(), picture.v(), picture.uvStride())
                        .build();
            }
            output.accept(new VideoFrame(shown, packet.pts(),
                    packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, decoder.keyFrame()));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>VP8 frames are never held back, so this emits nothing.
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The decoder owns no threads, and no work of it runs once a call to {@link #decode} has
     * returned, so this does nothing.
     */
    @Override
    public void close() {
    }
}
