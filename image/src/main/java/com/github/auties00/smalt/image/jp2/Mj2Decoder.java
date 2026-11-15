package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jp2.internal.Jp2Pictures;
import com.github.auties00.smalt.image.jp2.internal.Jp2Reader;
import com.github.auties00.smalt.image.jpeg.internal.MotionJpegFields;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
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
 * Decodes Motion JPEG 2000 video (ISO/IEC 15444-3), as found in MJ2, MP4 and QuickTime files ({@code mjp2}), in
 * Matroska ({@code V_JPEG2000}) and in AVI ({@code MJ2C} and the other JPEG 2000 FourCCs), where every frame is an
 * independent JPEG 2000 image or a pair of field images.
 *
 * <p>A frame is a JP2 file, a raw codestream, or the Contiguous Codestream boxes of a Motion JPEG 2000 sample, as
 * {@link Codec#JPEG2000} describes; each image is decoded as {@link Jp2Decoder} decodes it, at full resolution and
 * with every quality layer. A raw codestream or a Contiguous Codestream box is decoded under a JP2 Header box, which
 * declares the color space and the channels: the one among the boxes of its frame, or else the track's
 * ({@link VideoTrack#codecConfig()}); without either, three components with subsampled chroma are Y'CbCr and other
 * layouts are RGB or gray, as for a raw codestream image. The picture is:
 * <ul>
 *   <li>for a gray image, or a Y'CbCr image (declared sYCC, or with subsampled chroma) whose chroma is subsampled
 *   1x1, 2x1 or 2x2, a {@link com.github.auties00.smalt.image.YuvPicture YuvPicture} of the stored samples in
 *   {@code MONOCHROME}, {@code YUV444}, {@code YUV422} or {@code YUV420}
 *   {@linkplain com.github.auties00.smalt.image.ChromaFormat chroma format}, without color
 *   conversion: samples of a precision {@code P} other than 8 are rescaled to 8 bits as
 *   {@code round(v * 255 / (2^P - 1))} or, when {@link VideoDecoderOptions#highBitDepth()} is set and {@code P}
 *   exceeds 8 (up to 16), kept in a {@link com.github.auties00.smalt.image.Yuv16Picture Yuv16Picture} of bit
 *   depth {@code P}. The picture's color description is
 *   the track's {@link VideoTrack#color()} or, without one, {@link ColorInfo#JPEG}, the full-range BT.601 matrix of
 *   sYCC;</li>
 *   <li>for any other image (RGB, an opacity channel, signed samples, a palette, the other color spaces), the
 *   {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture}, or with
 *   {@link VideoDecoderOptions#highBitDepth()} for channels wider than 8 bits the
 *   {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}, that {@link Jp2Decoder#decode}
 *   returns.</li>
 * </ul>
 *
 * <p>A frame of two Contiguous Codestream boxes holds two fields, the earlier one first; they are woven into one
 * picture whose rows 0, 2, 4 and so on come from the top field, which is the first field unless the track's
 * {@linkplain VideoTrack#interlacing() scan} is {@link Interlacing#BOTTOM_FIELD_FIRST} or
 * {@link Interlacing#BOTTOM_FIELD_FIRST_SEPARATED}. The picture is as high as the two fields together, or as the
 * track when the track is higher than the top field and lower than both. The second field is ignored, and the
 * first returned alone, when it cannot be decoded or does not pair with the first: both must be 8-bit or both
 * 16-bit Y'CbCr of one width, chroma format, depth and color, or both {@code RgbaPicture} or both
 * {@code Rgba16Picture} of one width and alpha, the top field as high as the bottom one or one row higher.
 *
 * <p>A decoder reads either the packets of a {@link Codec#JPEG2000} track through {@link #decode(Packet, Consumer)},
 * where every packet yields one key frame with the packet's timestamps and {@link #flush(Consumer)} has nothing to
 * drain, or frames that come with no track through {@link #decodeFrame(byte[], int, int)}, which treats a frame as
 * if its track declared no configuration, size or scan. A corrupt frame raises {@link MediaException.Malformed} and
 * does not affect the next one, since frames share no state.
 *
 * <p>Instances are not thread-safe. Frames are decoded partly on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes; the pictures do not depend on the threading. The
 * decoded samples are those {@link Jp2Decoder#decodeChannels} returns for the image.
 */
public final class Mj2Decoder implements VideoDecoder {
    /**
     * The JPEG 2000 signature box and a File Type box of the JP2 brand, which open the file a codestream is wrapped
     * in when the track has a JP2 Header box.
     */
    private static final byte[] FILE_START = {0, 0, 0, 0x0C, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A,
            0, 0, 0, 20, 'f', 't', 'y', 'p', 'j', 'p', '2', ' ', 0, 0, 0, 0, 'j', 'p', '2', ' '};

    /**
     * The input, named in failure messages.
     */
    private static final String WHAT = "JPEG 2000 frame";

    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    /**
     * The decoding options.
     */
    private final VideoDecoderOptions options;

    /**
     * The track's JP2 Header box, or {@code null} when it has none.
     */
    private final byte[] header;

    /**
     * The operation the calls of this decoder form, whose computing time decides how long a call keeps its carrier
     * thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Whether the track's scan puts the bottom field first.
     */
    private final boolean bottomFirst;

    /**
     * The bounds of the codestreams of the frame being decoded.
     */
    private final int[] bounds = new int[4];

    /**
     * The bounds of the JP2 Header box of the frame being decoded.
     */
    private final int[] headerBounds = new int[4];

    /**
     * The buffer a codestream is copied into when it does not start its array or is wrapped under the track's
     * header, reused from frame to frame.
     */
    private byte[] scratch = new byte[0];

    /**
     * Creates a decoder for frames that come with no track, under the {@link VideoDecoderOptions#defaults()}.
     */
    public Mj2Decoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for frames that come with no track.
     *
     * @param options the limits checked against every frame before allocating, and whether samples of more than
     * 8 bits keep their depth; the thread count is ignored
     */
    public Mj2Decoder(VideoDecoderOptions options) {
        this.track = null;
        this.options = Objects.requireNonNull(options, "options");
        this.header = null;
        this.bottomFirst = false;
    }

    /**
     * Creates a decoder for a Motion JPEG 2000 track under the {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#JPEG2000}
     * @throws IllegalArgumentException if the track is not a Motion JPEG 2000 track
     */
    public Mj2Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for a Motion JPEG 2000 track.
     *
     * <p>A configuration that is not a JP2 Header box is ignored.
     *
     * @param track the track description; its codec must be {@link Codec#JPEG2000}
     * @param options the limits checked against every frame before allocating, and whether samples of more than
     * 8 bits keep their depth; the thread count is ignored
     * @throws IllegalArgumentException if the track is not a Motion JPEG 2000 track
     */
    public Mj2Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.JPEG2000) {
            throw new IllegalArgumentException("not a jpeg2000 track: " + track.codec());
        }
        this.track = track;
        this.options = options;
        var config = track.codecConfig().orElse(null);
        this.header = Jp2Frames.isHeader(config) ? config.clone() : null;
        var scan = track.interlacing().orElse(null);
        this.bottomFirst = scan == Interlacing.BOTTOM_FIELD_FIRST || scan == Interlacing.BOTTOM_FIELD_FIRST_SEPARATED;
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
     * Decodes one packet holding one Motion JPEG 2000 frame.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps
     * @throws MediaException.Malformed if the frame is not in a form {@link Codec#JPEG2000} describes, holds no
     * complete codestream or has an invalid main header
     * @throws MediaException.Unsupported if the frame uses a feature {@link Jp2Decoder} lists as unsupported
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
     * Decodes one Motion JPEG 2000 frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the decoded picture, as the class description states; it shares no state with the input or the
     * decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is not in a form {@link Codec#JPEG2000} describes, holds no
     * complete codestream or has an invalid main header
     * @throws MediaException.Unsupported if the frame uses a feature {@link Jp2Decoder} lists as unsupported
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation(operation)) {
            return Guards.malformed(WHAT, () -> {
                var form = Jp2Frames.form(data, offset, length);
                if (form == Jp2Frames.UNKNOWN) {
                    throw new MediaException.Malformed("the frame is neither a JP2 file, a codestream nor a "
                            + "Motion JPEG 2000 sample");
                }
                if (form == Jp2Frames.FILE) {
                    return decodeFile(data, offset, length);
                }
                var count = Jp2Frames.codestreams(data, offset, length, bounds);
                if (count == 0) {
                    throw new MediaException.Malformed("the frame holds no complete codestream");
                }
                var secondStart = bounds[2];
                var secondEnd = bounds[3];
                var own = form == Jp2Frames.BOXES && Jp2Frames.findHeader(data, offset, length, headerBounds)
                        && Jp2Frames.isHeader(data, headerBounds[0], headerBounds[1]);
                var jp2h = own ? data : header;
                var jp2hStart = own ? headerBounds[0] : 0;
                var jp2hLength = own ? headerBounds[1] - headerBounds[0] : header != null ? header.length : 0;
                var first = decodeCodestream(data, bounds[0], bounds[1], jp2h, jp2hStart, jp2hLength);
                var second = count == 2
                        ? decodeSecondField(data, secondStart, secondEnd, jp2h, jp2hStart, jp2hLength) : null;
                if (second == null) {
                    return first;
                }
                var top = bottomFirst ? second : first;
                var bottom = bottomFirst ? first : second;
                if (!MotionJpegFields.pairs(top, bottom)) {
                    return first;
                }
                var height = top.height() + bottom.height();
                var frameHeight = track == null ? 0 : track.height();
                if (frameHeight > top.height() && frameHeight < height) {
                    height = frameHeight;
                }
                return MotionJpegFields.weave(top, bottom, height, options.limits());
            });
        }
    }

    /**
     * Decodes a frame that is a JP2 file.
     *
     * @param data the array holding the frame
     * @param offset the index of its signature box
     * @param length its length
     * @return the picture
     */
    private Picture decodeFile(byte[] data, int offset, int length) {
        if (offset == 0) {
            return toPicture(Jp2Reader.headers(data, length, options.limits()));
        }
        var buffer = scratch(length);
        System.arraycopy(data, offset, buffer, 0, length);
        return toPicture(Jp2Reader.headers(buffer, length, options.limits()));
    }

    /**
     * Decodes one codestream, under a JP2 Header box when there is one.
     *
     * @param data the array holding the codestream
     * @param start the index of its {@code SOC} marker
     * @param end the end of the codestream (exclusive)
     * @param jp2h the array holding the JP2 Header box of the frame or the track, or {@code null} for none
     * @param jp2hStart the index of the box, its header included
     * @param jp2hLength the length of the box
     * @return the picture
     */
    private Picture decodeCodestream(byte[] data, int start, int end, byte[] jp2h, int jp2hStart, int jp2hLength) {
        var length = end - start;
        if (jp2h == null) {
            if (start == 0) {
                return toPicture(Jp2Reader.headers(data, length, options.limits()));
            }
            var buffer = scratch(length);
            System.arraycopy(data, start, buffer, 0, length);
            return toPicture(Jp2Reader.headers(buffer, length, options.limits()));
        }
        var total = FILE_START.length + jp2hLength + 8 + length;
        var buffer = scratch(total);
        System.arraycopy(FILE_START, 0, buffer, 0, FILE_START.length);
        System.arraycopy(jp2h, jp2hStart, buffer, FILE_START.length, jp2hLength);
        var at = FILE_START.length + jp2hLength;
        var size = 8 + length;
        buffer[at] = (byte) (size >>> 24);
        buffer[at + 1] = (byte) (size >>> 16);
        buffer[at + 2] = (byte) (size >>> 8);
        buffer[at + 3] = (byte) size;
        buffer[at + 4] = 'j';
        buffer[at + 5] = 'p';
        buffer[at + 6] = '2';
        buffer[at + 7] = 'c';
        System.arraycopy(data, start, buffer, at + 8, length);
        return toPicture(Jp2Reader.headers(buffer, total, options.limits()));
    }

    /**
     * Decodes the second field of a frame, or gives {@code null} when it is damaged or uses a feature the decoder
     * lacks, so that the frame decodes from its first field alone.
     *
     * @param data the array holding the frame
     * @param start the index of the field's codestream
     * @param end the end of the codestream (exclusive)
     * @param jp2h the array holding the JP2 Header box of the frame or the track, or {@code null} for none
     * @param jp2hStart the index of the box, its header included
     * @param jp2hLength the length of the box
     * @return the picture, or {@code null}
     */
    private Picture decodeSecondField(byte[] data, int start, int end, byte[] jp2h, int jp2hStart, int jp2hLength) {
        try {
            return Guards.malformed(WHAT, () -> decodeCodestream(data, start, end, jp2h, jp2hStart, jp2hLength));
        } catch (MediaException.Malformed | MediaException.Unsupported ignored) {
            return null;
        }
    }

    /**
     * Decodes the first compositing layer of a parsed file into the picture the class description states.
     *
     * @param headers the file's boxes and main header
     * @return the picture
     */
    private Picture toPicture(Jp2Reader.Headers headers) {
        var limits = options.limits();
        var image = Jp2Reader.decode(headers, 0, 0, limits);
        var color = track == null ? ColorInfo.JPEG : track.color().orElse(ColorInfo.JPEG);
        var yuv = Jp2Pictures.toYuv(image, options.highBitDepth(), color, limits);
        return yuv != null ? yuv : Jp2Pictures.toPicture(image, options.highBitDepth(), limits);
    }

    /**
     * Returns the reusable buffer, grown to hold at least a number of bytes.
     *
     * @param length the number of bytes needed
     * @return the buffer
     */
    private byte[] scratch(int length) {
        if (scratch.length < length) {
            scratch = new byte[Math.max(length, scratch.length + (scratch.length >> 1))];
        }
        return scratch;
    }

    /**
     * Does nothing: Motion JPEG 2000 frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
