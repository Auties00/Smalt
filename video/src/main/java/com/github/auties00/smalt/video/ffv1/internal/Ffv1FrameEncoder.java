package com.github.auties00.smalt.video.ffv1.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Encodes pictures into FFV1 frames: it keeps the context states of every slice position, encodes the slices in
 * parallel and concatenates them into the frame.
 *
 * <p>The pictures must already have the coded layout: a {@link YuvPicture} or {@link Yuv16Picture} of the coded
 * chroma format and depth for Y'CbCr, an {@link RgbaPicture} or {@link Rgba16Picture} for RGB. Instances are not
 * thread-safe.
 */
public final class Ffv1FrameEncoder {
    /**
     * The run lengths of the first half of the quantization tables of the small context model: the context
     * inputs {@code l - tl}, {@code tl - t} and {@code t - tr} in six levels (0, 1, 2, 3 to 4, 5 to 10 and 11 or
     * more), the two inputs two samples away unused.
     */
    private static final int[][] SMALL_CONTEXT = {
            {1, 1, 1, 2, 6, 117}, {1, 1, 1, 2, 6, 117}, {1, 1, 1, 2, 6, 117}, {128}, {128}
    };

    /**
     * The run lengths of the quantization tables of the large context model: those of the small model, and the
     * inputs {@code L - l} and {@code T - t} in three levels (0, 1 to 2 and 3 or more).
     */
    private static final int[][] LARGE_CONTEXT = {
            {1, 1, 1, 2, 6, 117}, {1, 1, 1, 2, 6, 117}, {1, 1, 1, 2, 6, 117}, {1, 2, 125}, {1, 2, 125}
    };

    private final Ffv1Parameters parameters;

    private final int width;

    private final int height;

    private final int threads;

    private final Ffv1Contexts[] contexts;

    private final Ffv1SliceEncoder[] workers;

    private final int[] zeroIndices = new int[Ffv1Contexts.GROUPS];

    private final Source source = new Source();

    /**
     * Constructs a frame encoder.
     *
     * @param width the frame width
     * @param height the frame height
     * @param version the FFV1 version: 0, 1 or 3
     * @param coderType the coder type: 0, 1 or 2
     * @param rgb whether the pictures are coded as RGB
     * @param chromaPlanes whether Y'CbCr pictures have chroma planes
     * @param chromaShiftX the horizontal chroma subsampling of Y'CbCr pictures
     * @param chromaShiftY the vertical chroma subsampling of Y'CbCr pictures
     * @param alpha whether a transparency plane is coded
     * @param bits the coded depth
     * @param largeContext whether the large context model is used
     * @param horizontalSlices the slice raster width (version 3)
     * @param verticalSlices the slice raster height (version 3)
     * @param sliceCrc whether slices carry CRCs (version 3)
     * @param intra whether every frame is a key frame
     * @param threads the thread count convention of the encoder options
     */
    public Ffv1FrameEncoder(int width, int height, int version, int coderType, boolean rgb, boolean chromaPlanes,
                            int chromaShiftX, int chromaShiftY, boolean alpha, int bits, boolean largeContext,
                            int horizontalSlices, int verticalSlices, boolean sliceCrc, boolean intra, int threads) {
        this.width = width;
        this.height = height;
        this.threads = threads;
        var runs = new int[][][]{largeContext ? LARGE_CONTEXT : SMALL_CONTEXT};
        this.parameters = Ffv1Parameters.forEncoder(version, coderType, rgb ? 1 : 0, bits, rgb || chromaPlanes,
                rgb ? 0 : chromaShiftX, rgb ? 0 : chromaShiftY, alpha, version >= 3 ? horizontalSlices : 1,
                version >= 3 ? verticalSlices : 1, runs, version >= 3 && sliceCrc ? 1 : 0, intra);
        var cells = parameters.horizontalSlices * parameters.verticalSlices;
        this.contexts = new Ffv1Contexts[cells];
        this.workers = new Ffv1SliceEncoder[cells];
        for (var i = 0; i < cells; i++) {
            contexts[i] = new Ffv1Contexts();
            workers[i] = new Ffv1SliceEncoder();
        }
    }

    /**
     * {@return the configuration record of a version 3 stream, or {@code null} for versions 0 and 1}
     */
    public byte[] configurationRecord() {
        return parameters.version >= 3 ? parameters.configurationRecord() : null;
    }

    /**
     * Encodes one picture.
     *
     * @param picture the picture, of the coded layout and the frame size
     * @param keyframe whether the frame resets the context states
     * @param pictureStructure the picture structure a version 3 frame declares
     * @param aspectNumerator the sample aspect ratio numerator, 0 when unknown
     * @param aspectDenominator the sample aspect ratio denominator, 0 when unknown
     * @return the frame
     */
    public byte[] encode(Picture picture, boolean keyframe, int pictureStructure, int aspectNumerator,
                         int aspectDenominator) {
        source.picture(picture, parameters);
        var p = parameters;
        if (keyframe) {
            for (var context : contexts) {
                context.reset(p, zeroIndices, 0);
            }
        }
        if (p.version <= 1) {
            var worker = workers[0];
            worker.encodeFrame(p, contexts[0], keyframe, width, height, source);
            return java.util.Arrays.copyOf(worker.slice(), worker.sliceSize());
        }
        var count = contexts.length;
        var bands = threads == 1 ? 1 : Math.min(count, ParallelBands.bands(count, (long) width * height));
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, count);
            var to = ParallelBands.start(band + 1, bands, count);
            for (var slice = from; slice < to; slice++) {
                workers[slice].encodeSlice(p, contexts[slice], slice == 0, keyframe, slice % p.horizontalSlices,
                        slice / p.horizontalSlices, pictureStructure, aspectNumerator, aspectDenominator, width,
                        height, source);
            }
        });
        var total = 0;
        for (var worker : workers) {
            total += worker.sliceSize();
        }
        var frame = new byte[total];
        var position = 0;
        for (var worker : workers) {
            System.arraycopy(worker.slice(), 0, frame, position, worker.sliceSize());
            position += worker.sliceSize();
        }
        return frame;
    }

    /**
     * Reads the samples of the picture being encoded.
     */
    private static final class Source implements Ffv1SampleSource {
        private Picture picture;

        private int bits;

        private int max;

        private void picture(Picture picture, Ffv1Parameters parameters) {
            this.picture = picture;
            this.bits = parameters.bits;
            this.max = (1 << bits) - 1;
        }

        @Override
        public void line(int plane, int x, int y, int[] samples, int offset, int count) {
            switch (picture) {
                case YuvPicture yuv -> {
                    byte[] data;
                    int stride;
                    switch (plane) {
                        case 0 -> {
                            data = yuv.y();
                            stride = yuv.yStride();
                        }
                        case 1 -> {
                            data = yuv.u();
                            stride = yuv.uvStride();
                        }
                        case 2 -> {
                            data = yuv.v();
                            stride = yuv.uvStride();
                        }
                        default -> {
                            data = yuv.hasAlpha() ? yuv.alpha() : null;
                            stride = yuv.alphaStride();
                        }
                    }
                    if (data == null) {
                        java.util.Arrays.fill(samples, offset, offset + count, max);
                        return;
                    }
                    var base = y * stride + x;
                    for (var i = 0; i < count; i++) {
                        samples[offset + i] = data[base + i] & 0xFF;
                    }
                }
                case Yuv16Picture yuv -> {
                    short[] data;
                    int stride;
                    switch (plane) {
                        case 0 -> {
                            data = yuv.y();
                            stride = yuv.yStride();
                        }
                        case 1 -> {
                            data = yuv.u();
                            stride = yuv.uvStride();
                        }
                        case 2 -> {
                            data = yuv.v();
                            stride = yuv.uvStride();
                        }
                        default -> {
                            data = yuv.hasAlpha() ? yuv.alpha() : null;
                            stride = yuv.alphaStride();
                        }
                    }
                    if (data == null) {
                        java.util.Arrays.fill(samples, offset, offset + count, max);
                        return;
                    }
                    var base = y * stride + x;
                    for (var i = 0; i < count; i++) {
                        samples[offset + i] = data[base + i] & max;
                    }
                }
                default -> throw new IllegalStateException("not a Y'CbCr picture: " + picture);
            }
        }

        @Override
        public void rgbLine(int x, int y, int[] red, int[] green, int[] blue, int[] alpha, int count) {
            switch (picture) {
                case RgbaPicture rgba -> {
                    var data = rgba.pixels();
                    var base = y * rgba.stride() + x * 4;
                    for (var i = 0; i < count; i++) {
                        var index = base + i * 4;
                        red[i] = data[index] & 0xFF;
                        green[i] = data[index + 1] & 0xFF;
                        blue[i] = data[index + 2] & 0xFF;
                        if (alpha != null) {
                            alpha[i] = rgba.hasAlpha() ? data[index + 3] & 0xFF : 255;
                        }
                    }
                }
                case Rgba16Picture rgba -> {
                    var data = rgba.pixels();
                    var base = y * rgba.stride() + x * 4;
                    for (var i = 0; i < count; i++) {
                        var index = base + i * 4;
                        red[i] = narrow(data[index]);
                        green[i] = narrow(data[index + 1]);
                        blue[i] = narrow(data[index + 2]);
                        if (alpha != null) {
                            alpha[i] = rgba.hasAlpha() ? narrow(data[index + 3]) : max;
                        }
                    }
                }
                default -> throw new IllegalStateException("not an RGB picture: " + picture);
            }
        }

        /**
         * Scales a 16-bit sample to the coded depth, the inverse of the decoder's widening.
         */
        private int narrow(short sample) {
            var value = sample & 0xFFFF;
            return max == 65535 ? value : (int) (((long) value * max + 32767) / 65535);
        }
    }
}
