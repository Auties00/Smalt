package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifUncompressedWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/**
 * Encodes pictures into the frames of a {@link Codec#UNCOMPRESSED_23001_17} track (ISO/IEC 23001-17 uncompressed
 * video) in one {@link UncompressedVideoFormat}.
 *
 * <p>Every frame becomes one key frame packet, of {@link UncompressedVideoFormat#frameSize(int, int)} bytes unless
 * the format compresses it, with the frame's timestamp as presentation and decoding time. Pictures are first
 * converted to the components of the format: a Y'CbCr format takes the planes of a {@link YuvPicture} or
 * {@link Yuv16Picture} of its chroma format and of a matrix other than the identity as they are, a monochrome format
 * the luma plane of any such picture, and both convert any other picture to the encoder's color description with
 * {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo)}; an RGB format takes
 * the planes of a 4:4:4 picture of the {@linkplain MatrixCoefficients#IDENTITY identity matrix} (green, blue and
 * red in its luma and chroma planes) as they are, and converts any other picture to one. Samples of 8 bits or more
 * are coded at the format's depth with {@link ImageOps#toBitDepth(Yuv16Picture, int)}; below 8 bits an 8-bit sample
 * {@code v} becomes {@code (v * m + 127) / 255} for the largest value {@code m}. A picture without alpha is coded
 * opaque in a format with alpha, and alpha is dropped in a format without it. A picture decoded by
 * {@link UncompressedVideoDecoder} from a frame of the same format encodes back to the same frame. Instances are not
 * thread-safe.
 */
public final class UncompressedVideoEncoder implements VideoEncoder {
    private final UncompressedVideoFormat format;

    private final int width;

    private final int height;

    private final Rational timeBase;

    /**
     * The color description of the pictures the components are taken from.
     */
    private final ColorInfo color;

    private final HeifUncompressedWriter writer;

    private final int frameSize;

    /**
     * The samples of each component type of a frame, by type, reused from frame to frame.
     */
    private final short[][] planes = new short[8][];

    /**
     * The compressor of compressed formats, or {@code null}.
     */
    private Deflater deflater;

    /**
     * The uncompressed frame of a compressed format, reused from frame to frame.
     */
    private byte[] uncompressed;

    /**
     * The buffer a compressed frame is assembled in, kept from frame to frame.
     */
    private byte[] compressed;

    /**
     * Constructs an encoder.
     *
     * @param format the frame layout
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @param frameRate the frame rate; the time base is its inverse
     * @param color the color description Y'CbCr and monochrome formats convert other pictures to, or {@code null} for
     * BT.601 limited range; an RGB format takes it with the identity matrix and full range
     * @throws IllegalArgumentException if a dimension is not positive, the tiles of the format do not divide the
     * picture, a frame would exceed the largest Java array, or the frame rate is not positive
     */
    public UncompressedVideoEncoder(UncompressedVideoFormat format, int width, int height, Rational frameRate,
                                    ColorInfo color) {
        this.format = Objects.requireNonNull(format, "format");
        Objects.requireNonNull(frameRate, "frameRate");
        if (frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
            throw new IllegalArgumentException("invalid frame rate " + frameRate);
        }
        this.writer = format.writer(width, height);
        if (writer.frameSize() > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("a " + width + "x" + height + " frame is too large");
        }
        this.frameSize = (int) writer.frameSize();
        this.width = width;
        this.height = height;
        this.timeBase = frameRate.invert();
        var base = color != null ? color : ColorInfo.BT601_LIMITED;
        this.color = format.colorModel() == UncompressedVideoFormat.ColorModel.RGB
                ? base.withMatrix(MatrixCoefficients.IDENTITY).withRange(ColorRange.FULL) : base;
        for (var type : format.definitionTypes()) {
            planes[type] = new short[writer.planeWidth(type) * writer.planeHeight(type)];
        }
        if (format.compression() != UncompressedVideoFormat.Compression.NONE) {
            deflater = new Deflater(Deflater.DEFAULT_COMPRESSION,
                    format.compression() == UncompressedVideoFormat.Compression.DEFLATE);
            uncompressed = new byte[frameSize];
            compressed = new byte[Math.max(64, frameSize / 2)];
        }
    }

    /**
     * {@return the frame layout}
     */
    public UncompressedVideoFormat format() {
        return format;
    }

    @Override
    public Codec codec() {
        return Codec.UNCOMPRESSED_23001_17;
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
        return timeBase;
    }

    /**
     * {@return the format's {@link UncompressedVideoFormat#toCodecConfig() codec configuration}}
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(format.toCodecConfig());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every frame is coded at once, so this method emits exactly one packet.
     *
     * @throws IllegalStateException if the encoder is closed
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        var picture = frame.picture();
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("a " + picture.width() + "x" + picture.height()
                    + " picture does not match the " + width + "x" + height + " encoder");
        }
        if (format.compression() != UncompressedVideoFormat.Compression.NONE && deflater == null) {
            throw new IllegalStateException("the encoder is closed");
        }
        components(picture);
        byte[] data;
        if (deflater == null) {
            data = new byte[frameSize];
            writer.write(planes, data);
        } else {
            Arrays.fill(uncompressed, (byte) 0);
            writer.write(planes, uncompressed);
            data = deflate();
        }
        var duration = frame.duration() >= 0 ? frame.duration() : Track.UNKNOWN_DURATION;
        output.accept(new Packet(0, data, frame.pts(), frame.pts(), duration, true));
    }

    /**
     * Holds no frames; does nothing.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Releases the compressor of a compressed format; the encoder cannot encode afterwards.
     */
    @Override
    public void close() {
        if (deflater != null) {
            deflater.end();
            deflater = null;
        }
    }

    /**
     * Compresses {@link #uncompressed}.
     *
     * @return the compressed frame, a new array
     */
    private byte[] deflate() {
        deflater.reset();
        deflater.setInput(uncompressed);
        deflater.finish();
        var size = 0;
        while (!deflater.finished()) {
            if (size == compressed.length) {
                compressed = Arrays.copyOf(compressed, compressed.length * 2);
            }
            size += deflater.deflate(compressed, size, compressed.length - size);
        }
        return Arrays.copyOf(compressed, size);
    }

    /**
     * Fills {@link #planes} with the components of a picture.
     *
     * @param picture the picture to encode
     */
    private void components(Picture picture) {
        var types = format.definitionTypes();
        var depth = format.bitDepth();
        var max = (1 << depth) - 1;
        var luma = types[0];
        var hasChroma = format.colorModel() != UncompressedVideoFormat.ColorModel.MONOCHROME;
        var alphaType = format.alpha() ? 7 : -1;
        var first = hasChroma ? types[1] : -1;
        var second = hasChroma ? types[2] : -1;
        if (format.colorModel() == UncompressedVideoFormat.ColorModel.RGB) {
            luma = 5;
            first = 6;
            second = 4;
        }
        var chromaWidth = hasChroma ? writer.planeWidth(first) : 0;
        var chromaHeight = hasChroma ? writer.planeHeight(first) : 0;
        if (depth > 8) {
            var yuv = yuv16(picture);
            copy(yuv.y(), yuv.yStride(), planes[luma], width, height);
            if (hasChroma) {
                copy(yuv.u(), yuv.uvStride(), planes[first], chromaWidth, chromaHeight);
                copy(yuv.v(), yuv.uvStride(), planes[second], chromaWidth, chromaHeight);
            }
            if (alphaType >= 0) {
                if (yuv.alpha() != null) {
                    copy(yuv.alpha(), yuv.alphaStride(), planes[alphaType], width, height);
                } else {
                    Arrays.fill(planes[alphaType], (short) max);
                }
            }
        } else {
            var yuv = yuv8(picture);
            copy(yuv.y(), yuv.yStride(), planes[luma], width, height, max);
            if (hasChroma) {
                copy(yuv.u(), yuv.uvStride(), planes[first], chromaWidth, chromaHeight, max);
                copy(yuv.v(), yuv.uvStride(), planes[second], chromaWidth, chromaHeight, max);
            }
            if (alphaType >= 0) {
                if (yuv.alpha() != null) {
                    copy(yuv.alpha(), yuv.alphaStride(), planes[alphaType], width, height, max);
                } else {
                    Arrays.fill(planes[alphaType], (short) max);
                }
            }
        }
    }

    /**
     * {@return whether a picture's samples are taken as they are}
     *
     * @param chroma the chroma format of the picture
     * @param pictureColor the color description of the picture
     */
    private boolean direct(ChromaFormat chroma, ColorInfo pictureColor) {
        var identity = pictureColor.matrix() == MatrixCoefficients.IDENTITY;
        if (format.colorModel() == UncompressedVideoFormat.ColorModel.MONOCHROME) {
            return !identity;
        }
        if (chroma != format.chromaFormat()) {
            return false;
        }
        return format.colorModel() == UncompressedVideoFormat.ColorModel.RGB ? identity : !identity;
    }

    /**
     * Returns a picture as 8-bit samples of the format's components.
     *
     * @param picture the picture to encode, of any type
     * @return the picture whose planes hold the components
     */
    private YuvPicture yuv8(Picture picture) {
        if (picture instanceof YuvPicture yuv && direct(yuv.chromaFormat(), yuv.color())) {
            return yuv;
        }
        if (picture instanceof Yuv16Picture deep && direct(deep.chromaFormat(), deep.color())) {
            return ImageOps.toEightBit(deep);
        }
        return ImageOps.toYuv(picture, format.chromaFormat(), color);
    }

    /**
     * Returns a picture as samples of the format's components at the format's depth, above 8 bits.
     *
     * @param picture the picture to encode, of any type
     * @return the picture whose planes hold the components
     */
    private Yuv16Picture yuv16(Picture picture) {
        var depth = format.bitDepth();
        if (picture instanceof Yuv16Picture deep && direct(deep.chromaFormat(), deep.color())) {
            return deep.bitDepth() == depth ? deep : ImageOps.toBitDepth(deep, depth);
        }
        if (picture instanceof YuvPicture yuv && direct(yuv.chromaFormat(), yuv.color())) {
            return ImageOps.toBitDepth(yuv, depth);
        }
        return ImageOps.toYuv16(picture, format.chromaFormat(), color, depth);
    }

    /**
     * Copies 16-bit samples into a component plane.
     *
     * @param source the picture plane
     * @param stride its row stride
     * @param target the component plane
     * @param width the plane width
     * @param height the plane height
     */
    private static void copy(short[] source, int stride, short[] target, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(source, y * stride, target, y * width, width);
        }
    }

    /**
     * Copies 8-bit samples into a component plane, rescaled to a depth of 8 bits or less.
     *
     * @param source the picture plane
     * @param stride its row stride
     * @param target the component plane
     * @param width the plane width
     * @param height the plane height
     * @param max the largest value of the component
     */
    private static void copy(byte[] source, int stride, short[] target, int width, int height, int max) {
        for (var y = 0; y < height; y++) {
            var in = y * stride;
            var out = y * width;
            for (var x = 0; x < width; x++) {
                var v = source[in + x] & 0xFF;
                target[out + x] = (short) (max == 255 ? v : (v * max + 127) / 255);
            }
        }
    }
}
