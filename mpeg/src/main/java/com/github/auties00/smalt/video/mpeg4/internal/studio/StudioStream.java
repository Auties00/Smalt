package com.github.auties00.smalt.video.mpeg4.internal.studio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;

/**
 * Decodes the VOPs of a Studio profile video object layer (ISO/IEC 14496-2, Simple Studio and Core Studio object
 * types) and hands its frames out in decoding order, which is the display order of intra-only layers.
 *
 * <p>Frames come as {@link Yuv16Picture}s of the layer's sample precision when high bit depth output is asked for
 * and the precision exceeds 8 bits, as {@link YuvPicture}s otherwise (samples of more than 8 bits reduced as
 * {@link ImageOps#toEightBit(Picture)} reduces them), in the layer's chroma format. A layer of R, G and B components
 * gives pictures whose planes hold G, B and R under {@link MatrixCoefficients#IDENTITY}.
 *
 * <p>An instance is not thread-safe; the pictures it hands out are valid until the next call.
 */
public final class StudioStream {
    /**
     * Receives the frames.
     */
    public interface Sink {
        /**
         * Hands out one frame.
         *
         * @param picture the frame, valid until the call returns
         * @param time the frame time in ticks of {@link #timeResolution()}
         */
        void picture(Picture picture, long time);
    }

    /**
     * The frame periods of {@code frame_rate_code} 1 to 8 (ISO/IEC 13818-2 Table 6-4), as numerator and
     * denominator of the rate.
     */
    private static final int[][] FRAME_RATES = {
            {30000, 1001}, {24000, 1001}, {24, 1}, {25, 1}, {30000, 1001}, {30, 1}, {50, 1}, {60000, 1001},
            {60, 1}};

    private final StudioLayer layer;
    private final StudioDecoder decoder;
    private final boolean highBitDepth;
    private final ColorInfo color;

    /**
     * The color description of a sequence display extension, which overrides the visual object's, or
     * {@code null}.
     */
    private ColorInfo colorOverride;
    private final BitReader reader = new BitReader();
    private final int rate;
    private final int period;
    private Picture output;
    private long frames;

    /**
     * Creates a stream for a layer.
     *
     * @param data the bytes
     * @param offset the index after the video object layer start code
     * @param length the length of the layer header
     * @param signal the color description of the visual object's {@code video_signal_type()}, or {@code null}
     * @param highBitDepth whether frames of more than 8 bits keep their precision
     * @param limits the limits bounding the allocations
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the layer uses a tool the decoder lacks
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public StudioStream(byte[] data, int offset, int length, ColorInfo signal, boolean highBitDepth,
                        MediaLimits limits) {
        reader.load(data, offset, length);
        this.layer = StudioLayer.parse(reader, limits);
        this.highBitDepth = highBitDepth;
        var code = layer.frameRateCode >= 1 && layer.frameRateCode <= 8 ? layer.frameRateCode : 0;
        this.rate = FRAME_RATES[code][0];
        this.period = FRAME_RATES[code][1];
        this.decoder = new StudioDecoder(layer, limits);
        this.color = signal;
    }

    /**
     * {@return the width of the frames}
     */
    public int width() {
        return layer.width;
    }

    /**
     * {@return the height of the frames}
     */
    public int height() {
        return layer.height;
    }

    /**
     * {@return the tick rate of the frame times}
     */
    public int timeResolution() {
        return rate;
    }

    /**
     * {@return the duration of a frame in ticks of {@link #timeResolution()}}
     */
    public int framePeriod() {
        return period;
    }

    /**
     * Reads extension data that follows the layer header.
     *
     * @param data the bytes
     * @param offset the index after the extension start code
     * @param length the bytes up to the next start code
     * @throws MediaException.Malformed if the extension is corrupt
     * @throws MediaException.Unsupported for a VLC code extension
     */
    public void extension(byte[] data, int offset, int length) {
        layer.readExtension(data, offset, length, reader, true);
        decoder.reloadMatrices();
        if (layer.matrixCoefficients >= 0) {
            colorOverride = describe(layer.matrixCoefficients, layer.colorPrimaries, layer.transferCharacteristics);
        }
    }

    /**
     * Decodes one VOP and hands out the frame it completes.
     *
     * @param data the bytes
     * @param offset the index after the VOP start code
     * @param end the index after the VOP's last slice
     * @param sink the receiver of the frame
     * @return {@code true} when a frame was handed out
     * @throws MediaException.Malformed if the VOP is corrupt
     * @throws MediaException.Unsupported for a P-, B- or S-VOP
     */
    public boolean decode(byte[] data, int offset, int end, Sink sink) {
        if (!decoder.decode(data, offset, end)) {
            return false;
        }
        var picture = render();
        var time = frames * period;
        frames++;
        sink.picture(picture, time);
        return true;
    }

    /**
     * Copies the decoded planes into the output picture.
     *
     * @return the picture
     */
    private Picture render() {
        var info = colorOverride != null ? colorOverride : color != null ? color : defaultColor();
        if (layer.rgb) {
            info = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, info.primaries(), info.transfer());
        }
        var width = layer.width;
        var height = layer.height;
        var chromaWidth = layer.chroma.chromaWidth(width);
        var chromaHeight = layer.chroma.chromaHeight(height);
        if (layer.bitsPerPixel == 8) {
            var target = output instanceof YuvPicture picture && picture.color().equals(info) ? picture
                    : YuvPicture.allocate(width, height, layer.chroma, false, info);
            copy8(decoder.y, decoder.codedWidth(), target.y(), target.yStride(), width, height);
            copy8(decoder.cb, decoder.chromaWidth(), target.u(), target.uvStride(), chromaWidth, chromaHeight);
            copy8(decoder.cr, decoder.chromaWidth(), target.v(), target.uvStride(), chromaWidth, chromaHeight);
            output = target;
            return target;
        }
        var deep = output instanceof Yuv16Picture picture && picture.color().equals(info) ? picture
                : Yuv16Picture.allocate(width, height, layer.chroma, layer.bitsPerPixel, false, info);
        copy16(decoder.y, decoder.codedWidth(), deep.y(), deep.yStride(), width, height);
        copy16(decoder.cb, decoder.chromaWidth(), deep.u(), deep.uvStride(), chromaWidth, chromaHeight);
        copy16(decoder.cr, decoder.chromaWidth(), deep.v(), deep.uvStride(), chromaWidth, chromaHeight);
        output = deep;
        return highBitDepth ? deep : ImageOps.toEightBit(deep);
    }

    /**
     * {@return BT.709 for pictures larger than standard definition, BT.601 otherwise, in limited range}
     */
    private ColorInfo defaultColor() {
        return layer.width > 1024 || layer.height > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
    }

    /**
     * Builds the color description of a sequence display extension.
     *
     * @param matrix the {@code matrix_coefficients}
     * @param primaries the {@code colour_primaries}
     * @param transfer the {@code transfer_characteristics}
     * @return the description, with the size defaults for unspecified components
     */
    private ColorInfo describe(int matrix, int primaries, int transfer) {
        var fallback = defaultColor();
        var m = MatrixCoefficients.of(matrix);
        var p = ColorPrimaries.of(primaries);
        var t = TransferCharacteristics.of(transfer);
        return new ColorInfo(m == MatrixCoefficients.UNSPECIFIED ? fallback.matrix() : m, ColorRange.LIMITED,
                p == ColorPrimaries.UNSPECIFIED ? fallback.primaries() : p,
                t == TransferCharacteristics.UNSPECIFIED ? fallback.transfer() : t);
    }

    private static void copy8(short[] source, int sourceStride, byte[] target, int targetStride, int width,
                              int height) {
        for (var row = 0; row < height; row++) {
            var from = row * sourceStride;
            var to = row * targetStride;
            for (var column = 0; column < width; column++) {
                target[to + column] = (byte) source[from + column];
            }
        }
    }

    private static void copy16(short[] source, int sourceStride, short[] target, int targetStride, int width,
                               int height) {
        for (var row = 0; row < height; row++) {
            System.arraycopy(source, row * sourceStride, target, row * targetStride, width);
        }
    }
}
