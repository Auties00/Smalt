package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
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
 * Decodes Motion-JPEG video, as found in AVI, MOV and Matroska files and in UVC camera streams,
 * where every frame is an independent JPEG image.
 *
 * <p>Each frame is decoded like {@link JpegDecoder#decode}, at full size, and must be gray or
 * YCbCr and coded with a DCT process (lossless frames are rejected). Frames that omit their
 * Huffman tables, as Motion-JPEG allows, use the tables of T.81 Annex K. Only the first field of
 * an interlaced frame is decoded. A frame decodes to a {@link YuvPicture} with
 * {@link ColorInfo#JPEG} in its stored subsampling ({@link ChromaFormat#MONOCHROME},
 * {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}); other
 * layouts are upsampled to {@link ChromaFormat#YUV444}. The planes are MCU-padded, so their strides
 * may exceed the width.
 *
 * <p>A decoder reads either the packets of a {@link Codec#MJPEG} track through
 * {@link #decode(Packet, Consumer)}, where every packet yields one key frame with the packet's
 * timestamps and {@link #flush(Consumer)} has nothing to drain, or frames that come with no
 * track (a camera's buffers) through {@link #decodeFrame(byte[], int, int)}. A corrupt frame
 * raises {@link MediaException.Malformed} and does not affect the next one, since frames share no
 * state.
 *
 * <p>Instances are not thread-safe; they hold no state between frames, so one decoder per thread
 * costs nothing. Large frames may be decoded partly on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes; the pictures do not depend on the
 * threading.
 *
 * @implNote This implementation decodes with the same reader as {@link JpegDecoder}, so every
 * plane is bit-exact with libjpeg-turbo's accurate integer IDCT.
 */
public final class MjpegDecoder implements VideoDecoder {
    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    /**
     * The decoding options.
     */
    private final VideoDecoderOptions options;

    /**
     * The operation the calls of this decoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Creates a decoder for frames that come with no track, under the
     * {@link VideoDecoderOptions#defaults()}.
     */
    public MjpegDecoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for frames that come with no track.
     *
     * @param options the limits checked against every frame's size before allocating; the thread
     * count is ignored
     */
    public MjpegDecoder(VideoDecoderOptions options) {
        this.track = null;
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Creates a decoder for a Motion-JPEG track under the
     * {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#MJPEG}
     * @throws IllegalArgumentException if the track is not a Motion-JPEG track
     */
    public MjpegDecoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for a Motion-JPEG track.
     *
     * @param track the track description; its codec must be {@link Codec#MJPEG}
     * @param options the limits checked against every frame's size before allocating; the thread
     * count is ignored
     * @throws IllegalArgumentException if the track is not a Motion-JPEG track
     */
    public MjpegDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.MJPEG) {
            throw new IllegalArgumentException("not a mjpeg track: " + track.codec());
        }
        this.track = track;
        this.options = options;
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
     * Decodes one packet holding one Motion-JPEG frame.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps
     * @throws MediaException.Malformed if the frame is invalid or holds no decodable data
     * @throws MediaException.Unsupported if the frame uses a coding feature {@link JpegDecoder}
     * lacks, is lossless, or is coded as RGB, CMYK or YCCK
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
     * Decodes one Motion-JPEG frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's SOI marker
     * @param length the number of bytes of the frame
     * @return the decoded picture, sharing no state with the input or the decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is invalid or holds no decodable data
     * @throws MediaException.Unsupported if the frame uses a coding feature {@link JpegDecoder}
     * lacks, is lossless, or is coded as RGB, CMYK or YCCK
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public YuvPicture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation(operation)) {
            var limits = options.limits();
            return Guards.malformed("JPEG frame", () -> {
                var reader = new JpegReader(data, offset, length, limits);
                reader.readHeaders();
                reader.checkSupported();
                var colorSpace = reader.colorSpace();
                var planes = reader.decodePlanes(0);
                return JpegOutput.toYuv(planes, colorSpace, limits);
            });
        }
    }

    /**
     * Does nothing: Motion-JPEG frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
