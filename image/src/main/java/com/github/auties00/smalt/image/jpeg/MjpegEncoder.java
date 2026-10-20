package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegWriter;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.FrameClock;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into Motion-JPEG video, every frame an independent baseline JPEG image.
 *
 * <p>A progressive frame is exactly the file {@link JpegEncoder#encode(Picture, JpegEncoderOptions)}
 * writes for the frame's picture at the frame's quality and the options' subsampling, Huffman
 * optimization and restart interval, without an ICC profile; with
 * {@link MjpegEncoderOptions#huffmanTables()} {@code false} its DHT segments are left out. An
 * interlaced frame ({@link MjpegEncoderOptions#fieldOrder()}) is two such images, one per field,
 * each starting with an {@code APP0 AVI1} segment in place of the JFIF one. Pictures of any
 * representation are accepted and converted as {@link JpegEncoder} converts them.
 *
 * <p>Every frame is independent: each {@link #encode(VideoFrame, Consumer)} emits one key-frame
 * packet carrying the frame's timestamp (or, without one, the timestamp following the previous
 * frame at the nominal frame rate) and duration, and {@link #flush(Consumer)} has nothing to
 * drain. The stream needs no codec configuration. The packets suit every container that carries
 * {@link Codec#MJPEG}: AVI, QuickTime and MP4, and Matroska.
 *
 * <p>Instances are not thread-safe. Large frames are encoded in parallel MCU rows on the module's
 * shared helper threads unless {@link MjpegEncoderOptions#threads()} is {@code 1}; the output does
 * not depend on the threading.
 *
 * @implNote This implementation's rate control keeps the quality scale (the percentage the
 * quantization tables are scaled by) as a real number, multiplies it after each frame by the ratio
 * of the frame's size to the size wanted for it, and codes the next frame at the nearest quality;
 * the size wanted is the per-frame budget less an eighth of the bits spent above budget so far,
 * kept within half and twice the budget.
 */
public final class MjpegEncoder implements VideoEncoder {
    /**
     * The largest frame dimension, the limit of the 16-bit size fields of the frame header.
     */
    private static final int MAX_DIMENSION = 65535;

    /**
     * The length of the {@code APP0 AVI1} payload: the identifier, the polarity, a reserved byte
     * and the two 32-bit field sizes.
     */
    private static final int AVI1_LENGTH = 14;

    /**
     * The index of the first field size in an image starting with an {@code APP0 AVI1} segment.
     */
    private static final int AVI1_SIZE_OFFSET = 12;

    private final int width;

    private final int height;

    private final MjpegEncoderOptions options;

    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * The operation the calls of this encoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The {@code APP0 AVI1} payloads of the first and second field.
     */
    private final byte[][] avi1 = new byte[2][];

    /**
     * The copy of the field that starts at the picture's second row, reused from frame to frame.
     */
    private Object[] oddRows;

    /**
     * The target bitrate in bits per second, or 0 for constant quality.
     */
    private long bitrate;

    /**
     * The quality scale the next frame is coded at, in percent of the T.81 Annex K tables.
     */
    private double scale;

    /**
     * The bits coded above the budget so far, negative when below.
     */
    private double debt;

    /**
     * Constructs an encoder for a picture size under the {@link MjpegEncoderOptions#defaults()}.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public MjpegEncoder(int width, int height) {
        this(width, height, MjpegEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width, from 1 to 65535
     * @param height the picture height, from 1 to 65535, and at least 2 for an interlaced
     * {@link MjpegEncoderOptions#fieldOrder()}
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public MjpegEncoder(int width, int height, MjpegEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        var minHeight = options.fieldOrder().isInterlaced() ? 2 : 1;
        if (width < 1 || height < minHeight || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        this.clock = new FrameClock(options.frameRate(), options.timeBase());
        this.bitrate = options.bitrate().orElse(0);
        this.scale = scaleOf(options.quality());
        for (var field = 0; field < 2; field++) {
            avi1[field] = new byte[]{'A', 'V', 'I', '1', (byte) (field + 1), 0, 0, 0, 0, 0, 0, 0, 0, 0};
        }
    }

    /**
     * {@return the encoding options}
     */
    public MjpegEncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#MJPEG}
     */
    @Override
    public Codec codec() {
        return Codec.MJPEG;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: Motion-JPEG frames carry their whole configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the key-frame packet of track 0, whose presentation and decoding times are the frame's
     * timestamp
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     */
    public Packet encodeFrame(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        try (var _ = ParallelBands.operation(operation)) {
            var picture = frame.picture();
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                        + ", encoder expects " + width + "x" + height);
            }
            var timing = clock.resolve(frame);
            var quality = bitrate > 0 ? qualityOf(scale) : options.quality();
            var data = options.fieldOrder().isInterlaced() ? encodeFields(picture, quality)
                    : encodeImage(picture, quality, null);
            clock.commit(timing.pts());
            if (bitrate > 0) {
                control(data.length, timing.duration());
            }
            return new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), true);
        }
    }

    /**
     * Does nothing: every Motion-JPEG frame is coded independently, so every packet is a key frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>The quality of the frames after the next one follows the new target; the bits coded above
     * or below the old target are forgotten.
     *
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException if the encoder codes at constant quality, without a
     * {@link MjpegEncoderOptions#bitrate()}
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("bitrate " + bitsPerSecond + " is not positive");
        }
        if (bitrate == 0) {
            throw new IllegalStateException("the encoder codes at constant quality");
        }
        bitrate = bitsPerSecond;
        debt = 0;
    }

    /**
     * Does nothing: Motion-JPEG frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Codes a picture or one field of it as one JPEG image.
     *
     * @param picture the picture or field to code
     * @param quality the quality, from 0 to 100
     * @param app0 the {@code APP0} payload, or {@code null} for the JFIF one
     * @return the image
     */
    private byte[] encodeImage(Picture picture, int quality, byte[] app0) {
        return JpegWriter.encodeMotion(picture, quality, options.chromaFormat(), options.optimizeHuffman(),
                options.restartInterval(), app0, options.huffmanTables(), options.threads());
    }

    /**
     * Codes the two fields of a picture as two JPEG images, the first field first, each with its
     * {@code APP0 AVI1} segment holding its size.
     *
     * @param picture the frame to split into fields
     * @param quality the quality, from 0 to 100
     * @return the two images, concatenated
     */
    private byte[] encodeFields(Picture picture, int quality) {
        var source = switch (picture) {
            case ChannelPicture channels -> ImageOps.toRgba(channels);
            default -> picture;
        };
        var topFirst = options.fieldOrder() == FieldOrder.TOP_FIRST;
        var first = encodeImage(field(source, !topFirst), quality, avi1[0]);
        var second = encodeImage(field(source, topFirst), quality, avi1[1]);
        writeSize(first);
        writeSize(second);
        var data = new byte[first.length + second.length];
        System.arraycopy(first, 0, data, 0, first.length);
        System.arraycopy(second, 0, data, first.length, second.length);
        return data;
    }

    /**
     * Writes an image's length into both field sizes of its {@code APP0 AVI1} segment, big-endian.
     *
     * @param image the image, starting with SOI and the segment
     */
    private static void writeSize(byte[] image) {
        for (var slot = 0; slot < 2; slot++) {
            var at = AVI1_SIZE_OFFSET + 4 * slot;
            image[at] = (byte) (image.length >>> 24);
            image[at + 1] = (byte) (image.length >>> 16);
            image[at + 2] = (byte) (image.length >>> 8);
            image[at + 3] = (byte) image.length;
        }
    }

    /**
     * Returns one field of a picture: every second row starting at row 0 for the top field and at
     * row 1 for the bottom field, and the chroma rows taken the same way. The top field shares the
     * picture's arrays; the bottom field is copied into arrays this encoder reuses.
     *
     * @param picture the picture, not a {@link ChannelPicture}
     * @param bottom whether to return the bottom field
     * @return the field
     */
    private Picture field(Picture picture, boolean bottom) {
        var rows = bottom ? height / 2 : (height + 1) / 2;
        return switch (picture) {
            case YuvPicture yuv -> {
                var chromaRows = fieldChromaRows(rows, yuv.chromaFormat().shiftY());
                var builder = YuvPicture.builder().size(width, rows).chromaFormat(yuv.chromaFormat())
                        .color(yuv.color());
                var planes = bottom ? bottomRows(yuv.y(), yuv.yStride(), rows, yuv.u(), yuv.v(), yuv.uvStride(),
                        chromaRows, yuv.chromaHeight(), yuv.y().length, yuv.u() == null ? 0 : yuv.u().length) : null;
                builder.luma(bottom ? (byte[]) planes[0] : yuv.y(), bottom ? yuv.yStride() : 2 * yuv.yStride());
                if (yuv.chromaFormat().hasChroma()) {
                    builder.chroma(bottom ? (byte[]) planes[1] : yuv.u(), bottom ? (byte[]) planes[2] : yuv.v(),
                            bottom ? yuv.uvStride() : 2 * yuv.uvStride());
                }
                yield builder.build();
            }
            case Yuv16Picture yuv -> {
                var chromaRows = fieldChromaRows(rows, yuv.chromaFormat().shiftY());
                var builder = Yuv16Picture.builder().size(width, rows).chromaFormat(yuv.chromaFormat())
                        .bitDepth(yuv.bitDepth()).color(yuv.color());
                var planes = bottom ? bottomRows(yuv.y(), yuv.yStride(), rows, yuv.u(), yuv.v(), yuv.uvStride(),
                        chromaRows, yuv.chromaHeight(), yuv.y().length, yuv.u() == null ? 0 : yuv.u().length) : null;
                builder.luma(bottom ? (short[]) planes[0] : yuv.y(), bottom ? yuv.yStride() : 2 * yuv.yStride());
                if (yuv.chromaFormat().hasChroma()) {
                    builder.chroma(bottom ? (short[]) planes[1] : yuv.u(), bottom ? (short[]) planes[2] : yuv.v(),
                            bottom ? yuv.uvStride() : 2 * yuv.uvStride());
                }
                yield builder.build();
            }
            case RgbaPicture rgba -> {
                var pixels = bottom ? (byte[]) bottomRows(rgba.pixels(), rgba.stride(), rows, null, null, 0, 0, 0,
                        rgba.pixels().length, 0)[0] : rgba.pixels();
                yield RgbaPicture.of(width, rows, bottom ? rgba.stride() : 2 * rgba.stride(), pixels, rgba.hasAlpha());
            }
            case Rgba16Picture rgba -> {
                var pixels = bottom ? (short[]) bottomRows(rgba.pixels(), rgba.stride(), rows, null, null, 0, 0, 0,
                        rgba.pixels().length, 0)[0] : rgba.pixels();
                yield Rgba16Picture.of(width, rows, bottom ? rgba.stride() : 2 * rgba.stride(), pixels,
                        rgba.hasAlpha());
            }
            case ChannelPicture _ -> throw new IllegalStateException("channel pictures are converted first");
        };
    }

    /**
     * Returns the chroma height of a field.
     *
     * @param rows the field height
     * @param shiftY the vertical chroma subsampling shift
     * @return the chroma rows of the field
     */
    private static int fieldChromaRows(int rows, int shiftY) {
        return (rows + (1 << shiftY) - 1) >> shiftY;
    }

    /**
     * Copies the odd rows of a plane, and of two chroma planes, into reused arrays of the same
     * element type, keeping their strides. A chroma plane with fewer odd rows than the field needs
     * repeats its last odd row, or its only row.
     *
     * @param luma the luma or pixel plane, an array of {@code byte} or {@code short}
     * @param stride its stride
     * @param rows the number of rows to copy
     * @param u the first chroma plane, or {@code null}
     * @param v the second chroma plane, or {@code null}
     * @param chromaStride the chroma stride
     * @param chromaRows the number of chroma rows of the field
     * @param chromaHeight the number of chroma rows of the picture
     * @param lumaLength the length of {@code luma}
     * @param chromaLength the length of each chroma plane
     * @return the copies of the three planes, the chroma ones {@code null} without chroma
     */
    private Object[] bottomRows(Object luma, int stride, int rows, Object u, Object v, int chromaStride,
                                int chromaRows, int chromaHeight, int lumaLength, int chromaLength) {
        if (oddRows == null || oddRows[0].getClass() != luma.getClass() || lengthOf(oddRows[0]) < lumaLength
                || (u != null && (oddRows[1] == null || lengthOf(oddRows[1]) < chromaLength))) {
            oddRows = new Object[]{newArray(luma, lumaLength), u == null ? null : newArray(u, chromaLength),
                    v == null ? null : newArray(v, chromaLength)};
        }
        for (var y = 0; y < rows; y++) {
            System.arraycopy(luma, (2 * y + 1) * stride, oddRows[0], y * stride, rowLength(luma, stride, 2 * y + 1));
        }
        if (u != null) {
            var lastOdd = chromaHeight >= 2 ? (chromaHeight / 2) * 2 - 1 : 0;
            for (var y = 0; y < chromaRows; y++) {
                var row = Math.min(2 * y + 1, lastOdd);
                var length = Math.min(rowLength(u, chromaStride, row), chromaLength - y * chromaStride);
                System.arraycopy(u, row * chromaStride, oddRows[1], y * chromaStride, length);
                System.arraycopy(v, row * chromaStride, oddRows[2], y * chromaStride, length);
            }
        }
        return oddRows;
    }

    /**
     * Returns how many elements of a row can be copied: the stride, or what is left of the plane
     * for its last row.
     *
     * @param plane the {@code byte} or {@code short} samples
     * @param stride its stride
     * @param row the index of the row to copy
     * @return the element count
     */
    private static int rowLength(Object plane, int stride, int row) {
        return Math.min(stride, lengthOf(plane) - row * stride);
    }

    /**
     * Returns the length of a {@code byte} or {@code short} array.
     *
     * @param array a {@code byte} or {@code short} array
     * @return its length
     */
    private static int lengthOf(Object array) {
        return array instanceof byte[] bytes ? bytes.length : ((short[]) array).length;
    }

    /**
     * Allocates an array of the element type of another.
     *
     * @param like the array whose element type is used
     * @param length the number of elements
     * @return the new array
     */
    private static Object newArray(Object like, int length) {
        return like instanceof byte[] ? new byte[length] : new short[length];
    }

    /**
     * Updates the rate control with the size of the frame just coded.
     *
     * @param bytes the frame size in bytes
     * @param duration the frame duration, in the time base
     */
    private void control(int bytes, long duration) {
        var budget = bitrate * duration * options.timeBase().toDouble();
        var bits = 8.0 * bytes;
        var wanted = Math.clamp(budget - debt / 8, budget / 2, budget * 2);
        debt += bits - budget;
        scale = Math.clamp(Math.max(scale, 1) * bits / wanted, 0, 5000);
    }

    /**
     * Returns the quality scale of a quality: {@code 5000 / quality} below 50 and
     * {@code 200 - 2 * quality} from 50 up, quality 0 coded as 1.
     *
     * @param quality the quality, from 0 to 100
     * @return the scale, from 0 to 5000
     */
    private static double scaleOf(int quality) {
        var q = Math.max(quality, 1);
        return q < 50 ? 5000.0 / q : 200 - 2.0 * q;
    }

    /**
     * Returns the quality whose scale is nearest a scale.
     *
     * @param scale the scale, from 0 to 5000
     * @return the quality, from 1 to 100
     */
    private static int qualityOf(double scale) {
        var quality = scale > 100 ? 5000 / scale : (200 - scale) / 2;
        return (int) Math.clamp(Math.round(quality), 1, 100);
    }
}
