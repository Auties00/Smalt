package com.github.auties00.smalt.image.tiff;

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
 * Decodes TIFF video ({@link Codec#TIFF}), as found in QuickTime files ({@code tiff}) and Matroska, where every frame
 * is an independent TIFF file.
 *
 * <p>Each frame is the first image of its file, decoded as {@link TiffDecoder#decode} decodes it, without conversion
 * to sRGB: to an {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture} (Y'CbCr images converted to RGB as
 * the file's coefficients and reference levels say, samples of more than 8 bits reduced to 8 bits), or with
 * {@link VideoDecoderOptions#highBitDepth()} a frame of more than 8 bits per sample to an
 * {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}.
 *
 * <p>Every packet yields one key frame carrying the packet's timestamps; {@link #flush(Consumer)} has nothing to drain.
 * A corrupt frame raises {@link MediaException.Malformed} and does not affect the next one, since frames share no
 * state.
 *
 * <p>Instances are not thread-safe.
 */
public final class TiffVideoDecoder implements VideoDecoder {
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
    public TiffVideoDecoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for frames that come with no track.
     *
     * @param options the limits checked against every frame before allocating, and whether 16-bit samples keep
     * their depth; the thread count is ignored
     */
    public TiffVideoDecoder(VideoDecoderOptions options) {
        this.track = null;
        this.imageOptions = imageOptions(options);
    }

    /**
     * Creates a decoder for a TIFF video track.
     *
     * @param track the track description; its codec must be {@link Codec#TIFF}
     * @param options the limits checked against every frame before allocating, and whether 16-bit samples keep
     * their depth; the thread count is ignored
     * @throws IllegalArgumentException if the track is not a TIFF video track
     */
    public TiffVideoDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        if (track.codec() != Codec.TIFF) {
            throw new IllegalArgumentException("not a tiff track: " + track.codec());
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
     * Decodes one packet holding one TIFF file.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps
     * @throws MediaException.Malformed if the packet is not a valid TIFF file
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
     * Decodes one TIFF file held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the decoded picture; it shares no state with the input or the decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is not a valid TIFF file
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation(operation)) {
            return Guards.malformed("TIFF frame",
                    () -> TiffDecoder.decode(MediaSource.of(data, offset, length), imageOptions).picture());
        }
    }

    /**
     * Does nothing: TIFF frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
