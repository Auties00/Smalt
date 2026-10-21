package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegColorSpace;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.jpeg.internal.LosslessOutput;
import com.github.auties00.smalt.image.jpeg.internal.MotionJpegFields;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
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
 * where every frame is an independent JPEG image or a pair of field images.
 *
 * <p>Each image is decoded like {@link JpegDecoder#decode}, at full size, and must be gray or
 * YCbCr, coded with a DCT process or with the lossless process (SOF3). Frames that omit their
 * Huffman tables, as Motion-JPEG allows, use the tables of T.81 Annex K. A frame decodes to a
 * {@link YuvPicture} with {@link ColorInfo#JPEG} in its stored subsampling
 * ({@link ChromaFormat#MONOCHROME}, {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422} or
 * {@link ChromaFormat#YUV444}); other layouts are upsampled to {@link ChromaFormat#YUV444}. The
 * planes of a frame that is not woven from fields may be padded, so their strides may exceed the
 * width. A frame of a precision {@code P} other than 8 (12-bit DCT, or a lossless precision from 2
 * to 16) decodes to the same layout with every sample {@code v} rescaled to 8 bits as
 * {@code round(v * 255 / (2^P - 1))}, or, when {@link VideoDecoderOptions#highBitDepth()} is set
 * and {@code P} exceeds 8, to a {@link Yuv16Picture} of bit depth {@code P} holding the samples as
 * decoded. A lossless frame of 8 bits decodes to exactly the samples it codes, and a lossless
 * frame holding RGB samples through the reversible transform {@link JpegDecoder} describes (the
 * frames of ffmpeg's lossless JPEG encoder from RGB input) to an
 * {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture}.
 *
 * <p>Interlaced frames are woven into one picture whose top field holds rows 0, 2, 4 and so on:
 * <ul>
 *   <li>A frame holding two field images one after the other, as the OpenDML convention (fields
 *   marked by an {@code APP0 AVI1} segment) and QuickTime Motion-JPEG format A (fields marked by an
 *   {@code APP1 mjpg} segment) store them, decodes both fields. The field an {@code AVI1} segment
 *   marks as the first in time is taken as the first field even when stored second. The first
 *   field is the top field unless the track's {@linkplain VideoTrack#interlacing() scan} is
 *   {@link Interlacing#BOTTOM_FIELD_FIRST} or {@link Interlacing#BOTTOM_FIELD_FIRST_SEPARATED}. The
 *   picture is as high as the two fields together, or as the track when the track is lower and
 *   higher than one field. The second image is ignored, and the frame decoded as one holding a
 *   single field image, when the first image is already as high as the track, when the second
 *   image cannot be decoded, or when the fields differ in kind (Y'CbCr or RGB), width, chroma
 *   format, depth or alpha, or in height other than by the top field having one more row.</li>
 *   <li>A frame holding a single field image, marked by an {@code AVI1} segment as one field or
 *   belonging to an interlaced track, whose track is higher than the image but at most twice as
 *   high, decodes to the track height with the field supplying both fields.</li>
 *   <li>A QuickTime Motion-JPEG format B frame (sample entry {@code mjpb}), recognized by its
 *   marker-less {@code mjpg} header in place of SOI, has each field rebuilt into an interchange
 *   JPEG stream from the header's offsets and is then decoded the same way; a field without a
 *   Huffman table offset uses the tables of Annex K.</li>
 * </ul>
 *
 * <p>A decoder reads either the packets of a {@link Codec#MJPEG} track through
 * {@link #decode(Packet, Consumer)}, where every packet yields one key frame with the packet's
 * timestamps and {@link #flush(Consumer)} has nothing to drain, or frames that come with no
 * track (a camera's buffers) through {@link #decodeFrame(byte[], int, int)}, which treats a frame
 * as if its track declared no size and no scan. A corrupt frame raises
 * {@link MediaException.Malformed} and does not affect the next one, since frames share no state.
 *
 * <p>Instances are not thread-safe; they hold no state between frames, so one decoder per thread
 * costs nothing. Large frames may be decoded partly on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes; the pictures do not depend on the
 * threading.
 *
 * @implNote This implementation decodes with the same reader as {@link JpegDecoder}, so every
 * plane of a DCT frame is bit-exact with libjpeg-turbo's accurate integer IDCT.
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
     * The bounds of the fields of the frame being decoded.
     */
    private final int[] bounds = new int[4];

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
     * @param options the limits checked against every frame's size before allocating, and whether
     * frames of more than 8 bits keep their depth; the thread count is ignored
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
     * @param options the limits checked against every frame's size before allocating, and whether
     * frames of more than 8 bits keep their depth; the thread count is ignored
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
     * lacks, is coded as RGB (other than through the reversible transform), CMYK or YCCK, or is a
     * format B frame that takes its quantization tables from the sample description
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
     * @param offset the index of the frame's SOI marker, or of its header for a format B frame
     * @param length the number of bytes of the frame
     * @return the decoded picture, a {@link YuvPicture}, a {@link Yuv16Picture} for a frame of more
     * than 8 bits under {@link VideoDecoderOptions#highBitDepth()}, or an
     * {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture} for a reversible RGB frame; it
     * shares no state with the input or the decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is invalid or holds no decodable data
     * @throws MediaException.Unsupported if the frame uses a coding feature {@link JpegDecoder}
     * lacks, is coded as RGB (other than through the reversible transform), CMYK or YCCK, or is a
     * format B frame that takes its quantization tables from the sample description
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation(operation)) {
            var limits = options.limits();
            return Guards.malformed("JPEG frame", () -> {
                var bytes = data;
                var start = offset;
                var size = length;
                if (MotionJpegFields.isFormatB(data, offset, length)) {
                    bytes = MotionJpegFields.toInterchange(data, offset, length, limits);
                    start = 0;
                    size = bytes.length;
                }
                var fields = MotionJpegFields.split(bytes, start, size, bounds);
                var first = decodeField(bytes, bounds[0], bounds[1]);
                var frameHeight = track == null ? 0 : track.height();
                var second = fields == 2 && (frameHeight == 0 || frameHeight > first.height())
                        ? decodeSecondField(bytes, bounds[2], bounds[3]) : null;
                Picture top = null;
                Picture bottom = null;
                if (second != null) {
                    var earlier = first;
                    var later = second;
                    if (MotionJpegFields.polarity(bytes, bounds[0], bounds[1]) == 2
                            && MotionJpegFields.polarity(bytes, bounds[2], bounds[3]) == 1) {
                        earlier = second;
                        later = first;
                    }
                    var bottomFirst = track != null && track.interlacing()
                            .filter(scan -> scan == Interlacing.BOTTOM_FIELD_FIRST
                                    || scan == Interlacing.BOTTOM_FIELD_FIRST_SEPARATED)
                            .isPresent();
                    top = bottomFirst ? later : earlier;
                    bottom = bottomFirst ? earlier : later;
                }
                if (top == null || !MotionJpegFields.pairs(top, bottom)) {
                    var polarity = MotionJpegFields.polarity(bytes, bounds[0], bounds[1]);
                    var interlaced = polarity == 1 || polarity == 2
                            || (track != null && track.interlacing().filter(Interlacing::isInterlaced).isPresent());
                    if (interlaced && frameHeight > first.height() && frameHeight <= 2 * first.height()
                            && MotionJpegFields.pairs(first, first)) {
                        return MotionJpegFields.weave(first, first, frameHeight, limits);
                    }
                    return first;
                }
                var height = top.height() + bottom.height();
                if (frameHeight > top.height() && frameHeight < height) {
                    height = frameHeight;
                }
                return MotionJpegFields.weave(top, bottom, height, limits);
            });
        }
    }

    /**
     * Decodes the second image of a frame, or gives {@code null} when it is damaged or uses a
     * feature the decoder lacks, so that the frame decodes from its first field alone.
     *
     * @param data the array holding the frame
     * @param start the index of the image's SOI marker
     * @param end the index after the image
     * @return the picture, or {@code null}
     */
    private Picture decodeSecondField(byte[] data, int start, int end) {
        try {
            return Guards.malformed("JPEG field", () -> decodeField(data, start, end));
        } catch (MediaException.Malformed | MediaException.Unsupported ignored) {
            return null;
        }
    }

    /**
     * Decodes one field or progressive image of a frame.
     *
     * @param data the array holding the frame
     * @param start the index of the image's SOI marker
     * @param end the index after the image
     * @return the picture
     */
    private Picture decodeField(byte[] data, int start, int end) {
        var limits = options.limits();
        var reader = new JpegReader(data, start, end - start, limits);
        var frame = reader.readHeaders();
        reader.checkSupported();
        if (LosslessOutput.reversibleRgb(reader)) {
            return LosslessOutput.decodeReversibleRgb(reader, limits);
        }
        var colorSpace = reader.colorSpace();
        if (colorSpace != JpegColorSpace.GRAY && colorSpace != JpegColorSpace.YCBCR) {
            throw new MediaException.Unsupported("jpeg " + colorSpace.name().toLowerCase() + " color space",
                    "a Motion-JPEG frame coded as " + colorSpace + " has no planar Y'CbCr form");
        }
        if (reader.lossless()) {
            if (options.highBitDepth() && frame.precision() > 8) {
                return LosslessOutput.decodePicture(reader, colorSpace, limits);
            }
            return JpegOutput.toYuv(LosslessOutput.decodePlanes(reader, limits), colorSpace, limits);
        }
        if (options.highBitDepth() && frame.precision() == 12) {
            return JpegOutput.toWidePicture(reader.decodeWidePlanes(0), colorSpace, limits);
        }
        return JpegOutput.toYuv(reader.decodePlanes(0), colorSpace, limits);
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
