package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes PNG video ({@link Codec#PNG}), as found in QuickTime files ({@code png }), AVI and Matroska ({@code MPNG}),
 * where every frame is an independent PNG file.
 *
 * <p>Each frame is decoded as {@link PngDecoder#decode} decodes a file, without color conversion: to an
 * {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture} of the stored samples (gray and palette images
 * expanded, 16-bit samples reduced to 8 bits), or with {@link VideoDecoderOptions#highBitDepth()} a 16-bit frame to
 * an {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}. An animated PNG frame decodes to its
 * default image.
 *
 * <p>Every packet yields one key frame carrying the packet's timestamps; {@link #flush(Consumer)} has nothing to drain.
 * A corrupt frame raises {@link MediaException.Malformed} and does not affect the next one, since frames share no
 * state.
 *
 * <p>Instances are not thread-safe.
 */
public final class PngVideoDecoder implements VideoDecoder {
    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    /**
     * The options every frame is decoded under.
     */
    private final ImageDecoderOptions imageOptions;

    /**
     * The operation the calls of this decoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Creates a decoder for frames that come with no track, under the {@link VideoDecoderOptions#defaults()}.
     */
    public PngVideoDecoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for frames that come with no track.
     *
     * @param options the limits checked against every frame before allocating, and whether 16-bit samples keep
     * their depth; the thread count is ignored
     */
    public PngVideoDecoder(VideoDecoderOptions options) {
        this.track = null;
        this.imageOptions = imageOptions(options);
    }

    /**
     * Creates a decoder for a PNG video track.
     *
     * @param track the track description; its codec must be {@link Codec#PNG}
     * @param options the limits checked against every frame before allocating, and whether 16-bit samples keep
     * their depth; the thread count is ignored
     * @throws IllegalArgumentException if the track is not a PNG video track
     */
    public PngVideoDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        if (track.codec() != Codec.PNG) {
            throw new IllegalArgumentException("not a png track: " + track.codec());
        }
        this.track = track;
        this.imageOptions = imageOptions(options);
    }

    /**
     * {@return the options a frame is decoded under}
     *
     * @param options the video decoding options
     */
    static ImageDecoderOptions imageOptions(VideoDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        return ImageDecoderOptions.builder().limits(options.limits()).highBitDepth(options.highBitDepth()).build();
    }

    /**
     * Returns the track being decoded.
     *
     * @return the track description, or {@code null} for a decoder of frames without a track
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Decodes one packet holding one PNG file.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps
     * @throws MediaException.Malformed if the packet is not a valid PNG file
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var picture = decodeFrame(data, 0, data.length);
        output.accept(new VideoFrame(picture, packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, true));
    }

    /**
     * Decodes one PNG file held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the decoded picture; it shares no state with the input or the decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is not a valid PNG file
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation(operation)) {
            return Guards.malformed("PNG frame",
                    () -> PngDecoder.decode(MediaSource.of(data, offset, length), imageOptions).picture());
        }
    }

    /**
     * Does nothing: PNG frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
