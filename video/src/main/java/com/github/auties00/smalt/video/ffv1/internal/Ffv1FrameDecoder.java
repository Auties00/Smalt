package com.github.auties00.smalt.video.ffv1.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.Arrays;

/**
 * Decodes FFV1 frames (RFC 9043, section 4.4) into pictures: it locates the slices of a frame, keeps the context
 * states of every slice position across frames, decodes the slices in parallel and stores their samples in the
 * output picture, which it reuses from frame to frame.
 *
 * <p>Instances are not thread-safe.
 */
public final class Ffv1FrameDecoder {
    private final int width;

    private final int height;

    private final boolean highBitDepth;

    private final int threads;

    private final ColorInfo color;

    private final MediaLimits limits;

    /**
     * The parameters of the configuration record, or {@code null} for versions 0 and 1.
     */
    private final Ffv1Parameters configuration;

    /**
     * The parameters in effect: the configuration's, or those of the last version 0 or 1 key frame.
     */
    private Ffv1Parameters parameters;

    private Ffv1Contexts[] contexts = new Ffv1Contexts[0];

    private Ffv1SliceDecoder[] workers = {new Ffv1SliceDecoder()};

    private final byte[] keyState = new byte[1];

    private int[] sliceStarts = new int[16];

    private int[] sliceEnds = new int[16];

    private int[][] headers = new int[16][];

    private boolean[] covered = new boolean[0];

    private static final int[] NO_INDICES = new int[Ffv1Contexts.GROUPS];

    private Picture picture;

    private Output output;

    private Rational sampleAspect;

    private FieldOrder fieldOrder;

    private boolean keyframe;

    /**
     * Constructs a frame decoder.
     *
     * @param width the frame width
     * @param height the frame height
     * @param configuration the configuration record, or {@code null} for versions 0 and 1
     * @param highBitDepth whether pictures deeper than 8 bits keep their depth
     * @param threads the thread count convention of the decoder options
     * @param color the color description of Y'CbCr pictures
     * @param limits the resource limits
     * @throws MediaException.Malformed if the configuration record is invalid
     * @throws MediaException.Unsupported if it describes an unsupported stream
     * @throws MediaException.LimitExceeded if the frame size exceeds the limits
     */
    public Ffv1FrameDecoder(int width, int height, byte[] configuration, boolean highBitDepth, int threads,
                            ColorInfo color, MediaLimits limits) {
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("FFV1 track without a frame size");
        }
        LimitChecks.checkPicture(limits, width, height);
        this.width = width;
        this.height = height;
        this.highBitDepth = highBitDepth;
        this.threads = threads;
        this.color = color;
        this.limits = limits;
        this.configuration = configuration == null ? null : Ffv1Parameters.readConfigurationRecord(configuration);
        if (this.configuration != null) {
            setUp(this.configuration);
        }
    }

    /**
     * {@return whether the last frame was a key frame}
     */
    public boolean keyframe() {
        return keyframe;
    }

    /**
     * {@return the sample aspect ratio the last frame's slices declare, or {@code null} when unknown}
     */
    public Rational sampleAspect() {
        return sampleAspect;
    }

    /**
     * {@return the field order the last frame's slices declare, or {@code null} when unknown}
     */
    public FieldOrder fieldOrder() {
        return fieldOrder;
    }

    /**
     * Decodes one frame.
     *
     * @param data the array holding the frame
     * @param offset the first byte of the frame
     * @param length the number of bytes of the frame
     * @return the picture, valid until the next call
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if a version 0 or 1 frame header describes an unsupported stream
     */
    public Picture decode(byte[] data, int offset, int length) {
        if (length <= 0) {
            throw new MediaException.Malformed("empty FFV1 frame");
        }
        var end = offset + length;
        var first = workers[0];
        var range = first.range();
        range.reset(data, offset, end);
        keyState[0] = (byte) 128;
        keyframe = range.bit(keyState, 0) != 0;
        if (configuration == null) {
            if (keyframe) {
                var read = Ffv1Parameters.read(range);
                if (read.version > 1) {
                    throw new MediaException.Malformed("FFV1 version " + read.version + " without configuration record");
                }
                if (!sameFormat(read)) {
                    setUp(read);
                } else {
                    parameters = read;
                }
            } else if (parameters == null) {
                throw new MediaException.Malformed("FFV1 stream starts with a frame that is not a key frame");
            }
        }
        var p = parameters;
        if (p.version <= 1) {
            var context = contexts[0];
            if (keyframe || !context.valid) {
                if (!keyframe) {
                    throw new MediaException.Malformed("FFV1 frame continues the states of a frame that failed");
                }
                context.reset(p, NO_INDICES, 0);
            }
            try {
                first.decodeContent(p, context, data, end, 0, 0, width, height, width, height, output);
            } catch (RuntimeException exception) {
                context.invalidate();
                throw malformed(exception);
            }
            sampleAspect = null;
            fieldOrder = null;
            return picture;
        }
        var count = locateSlices(data, offset, end, p);
        if (workers.length < count) {
            var grown = Arrays.copyOf(workers, count);
            for (var i = workers.length; i < count; i++) {
                grown[i] = new Ffv1SliceDecoder();
            }
            workers = grown;
        }
        for (var i = 0; i < count; i++) {
            if (headers[i] == null) {
                headers[i] = new int[7 + Ffv1Contexts.GROUPS];
            }
        }
        var bands = threads == 1 ? 1 : Math.min(count, ParallelBands.bands(count, (long) width * height));
        try {
            ParallelBands.run(bands, band -> {
                var from = ParallelBands.start(band, bands, count);
                var to = ParallelBands.start(band + 1, bands, count);
                for (var slice = from; slice < to; slice++) {
                    decodeSlice(p, data, offset, slice);
                }
            });
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw malformed(exception);
        }
        checkCoverage(p, count);
        var header = headers[0];
        if (header[5] <= 0 || header[6] <= 0) {
            sampleAspect = null;
        } else if (sampleAspect == null || sampleAspect.numerator() != header[5]
                || sampleAspect.denominator() != header[6]) {
            sampleAspect = new Rational(header[5], header[6]);
        }
        fieldOrder = switch (header[4]) {
            case 1 -> FieldOrder.TOP_FIRST;
            case 2 -> FieldOrder.BOTTOM_FIRST;
            case 3 -> FieldOrder.PROGRESSIVE;
            default -> null;
        };
        return picture;
    }

    private static MediaException malformed(RuntimeException exception) {
        if (exception instanceof MediaException media) {
            return media;
        }
        return new MediaException.Malformed("corrupt FFV1 frame", exception);
    }

    private void decodeSlice(Ffv1Parameters p, byte[] data, int offset, int slice) {
        var worker = workers[slice];
        var range = worker.range();
        if (slice != 0) {
            range.reset(data, sliceStarts[slice], sliceEnds[slice]);
        } else {
            range.limit(sliceEnds[0]);
        }
        var header = headers[slice];
        worker.readHeader(p, header);
        var position = header[1] * p.horizontalSlices + header[0];
        var context = contexts[position];
        if (keyframe) {
            context.reset(p, header, 7);
        } else if (!context.continuable(header, 7)) {
            throw new MediaException.Malformed("FFV1 slice " + header[0] + "," + header[1]
                    + " continues states that no previous frame left");
        }
        var x0 = (int) ((long) header[0] * width / p.horizontalSlices);
        var y0 = (int) ((long) header[1] * height / p.verticalSlices);
        var x1 = (int) ((long) (header[0] + header[2]) * width / p.horizontalSlices);
        var y1 = (int) ((long) (header[1] + header[3]) * height / p.verticalSlices);
        if (x1 <= x0 || y1 <= y0) {
            throw new MediaException.Malformed("FFV1 slice of zero size");
        }
        try {
            worker.decodeContent(p, context, data, sliceEnds[slice], x0, y0, x1 - x0, y1 - y0, width, height, output);
        } catch (RuntimeException exception) {
            context.invalidate();
            throw exception;
        }
    }

    /**
     * Finds the slices of a version 3 frame from its end, checking their CRCs.
     *
     * @return the number of slices
     */
    private int locateSlices(byte[] data, int offset, int end, Ffv1Parameters p) {
        var footer = p.ec != 0 ? 8 : 3;
        var position = end;
        var count = 0;
        var max = p.horizontalSlices * p.verticalSlices;
        while (position > offset) {
            if (position - offset < footer) {
                throw new MediaException.Malformed("FFV1 slice footer truncated");
            }
            var size = (data[position - footer] & 0xFF) << 16 | (data[position - footer + 1] & 0xFF) << 8
                    | data[position - footer + 2] & 0xFF;
            var start = position - footer - size;
            if (start < offset) {
                throw new MediaException.Malformed("FFV1 slice size " + size + " runs past the frame");
            }
            if (p.ec != 0) {
                if (Ffv1Tables.crc(data, start, position - start) != 0) {
                    throw new MediaException.Malformed("FFV1 slice fails its CRC");
                }
                if (data[position - footer + 3] != 0) {
                    throw new MediaException.Malformed("FFV1 slice reports error status "
                            + (data[position - footer + 3] & 0xFF));
                }
            }
            if (count == max) {
                throw new MediaException.Malformed("FFV1 frame holds more slices than its slice raster");
            }
            if (count == sliceStarts.length) {
                sliceStarts = Arrays.copyOf(sliceStarts, count * 2);
                sliceEnds = Arrays.copyOf(sliceEnds, count * 2);
                headers = Arrays.copyOf(headers, count * 2);
            }
            sliceStarts[count] = start;
            sliceEnds[count] = position - footer;
            count++;
            position = start;
        }
        for (int i = 0, j = count - 1; i < j; i++, j--) {
            var start = sliceStarts[i];
            sliceStarts[i] = sliceStarts[j];
            sliceStarts[j] = start;
            var stop = sliceEnds[i];
            sliceEnds[i] = sliceEnds[j];
            sliceEnds[j] = stop;
        }
        if (sliceStarts[0] != offset) {
            throw new MediaException.Malformed("FFV1 frame does not start with a slice");
        }
        return count;
    }

    private void checkCoverage(Ffv1Parameters p, int count) {
        var cells = p.horizontalSlices * p.verticalSlices;
        if (covered.length < cells) {
            covered = new boolean[cells];
        }
        Arrays.fill(covered, 0, cells, false);
        var total = 0;
        for (var i = 0; i < count; i++) {
            var header = headers[i];
            for (var sy = header[1]; sy < header[1] + header[3]; sy++) {
                for (var sx = header[0]; sx < header[0] + header[2]; sx++) {
                    var cell = sy * p.horizontalSlices + sx;
                    if (covered[cell]) {
                        throw new MediaException.Malformed("FFV1 slices overlap");
                    }
                    covered[cell] = true;
                    total++;
                }
            }
        }
        if (total != cells) {
            throw new MediaException.Malformed("FFV1 slices leave part of the frame uncoded");
        }
    }

    private boolean sameFormat(Ffv1Parameters other) {
        var p = parameters;
        return p != null && p.colorspace == other.colorspace && p.bits == other.bits
                && p.chromaPlanes == other.chromaPlanes && p.chromaShiftX == other.chromaShiftX
                && p.chromaShiftY == other.chromaShiftY && p.extraPlane == other.extraPlane;
    }

    /**
     * Allocates the output picture and the context states of a set of parameters.
     */
    private void setUp(Ffv1Parameters p) {
        parameters = p;
        var cells = p.horizontalSlices * p.verticalSlices;
        contexts = new Ffv1Contexts[cells];
        for (var i = 0; i < cells; i++) {
            contexts[i] = new Ffv1Contexts();
        }
        var planes = p.planeCount();
        LimitChecks.checkAllocation(limits, (long) width * height * planes * (p.bits > 8 && highBitDepth ? 2 : 1));
        output = new Output(p);
        picture = output.picture;
    }

    /**
     * The output picture and the conversion of decoded lines into it.
     */
    private final class Output implements Ffv1SampleSink {
        final Picture picture;

        private final int bits;

        private final boolean wide;

        /**
         * The binary logarithm of the replication of chroma samples beyond 4:2:0, horizontally and vertically.
         */
        private final int repeatX;

        private final int repeatY;

        private final int chromaShiftX;

        private final int chromaShiftY;

        private final byte[][] bytePlanes;

        private final short[][] shortPlanes;

        private final int[] strides;

        private final int[] planeWidths;

        private final int[] planeHeights;

        private final boolean rgb;

        private final boolean alpha;

        Output(Ffv1Parameters p) {
            this.bits = p.bits;
            this.rgb = p.colorspace == 1;
            this.alpha = p.extraPlane;
            this.wide = bits > 8 && highBitDepth;
            this.chromaShiftX = p.chromaShiftX;
            this.chromaShiftY = p.chromaShiftY;
            var shiftX = Math.min(chromaShiftX, 1);
            var shiftY = Math.min(chromaShiftY, 1);
            this.repeatX = chromaShiftX - shiftX;
            this.repeatY = chromaShiftY - shiftY;
            this.bytePlanes = new byte[4][];
            this.shortPlanes = new short[4][];
            this.strides = new int[4];
            this.planeWidths = new int[4];
            this.planeHeights = new int[4];
            if (rgb) {
                if (wide) {
                    var rgba = Rgba16Picture.allocate(width, height, alpha);
                    shortPlanes[0] = rgba.pixels();
                    strides[0] = rgba.stride();
                    picture = rgba;
                } else {
                    var rgba = RgbaPicture.allocate(width, height, alpha);
                    bytePlanes[0] = rgba.pixels();
                    strides[0] = rgba.stride();
                    picture = rgba;
                }
                return;
            }
            ChromaFormat format;
            if (!p.chromaPlanes) {
                format = ChromaFormat.MONOCHROME;
            } else if (shiftX == 1) {
                format = shiftY == 1 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
            } else {
                format = shiftY == 1 ? ChromaFormat.YUV440 : ChromaFormat.YUV444;
            }
            if (wide) {
                var yuv = Yuv16Picture.allocate(width, height, format, bits, alpha, color);
                shortPlanes[0] = yuv.y();
                shortPlanes[1] = yuv.u();
                shortPlanes[2] = yuv.v();
                shortPlanes[3] = yuv.alpha();
                strides[0] = yuv.yStride();
                strides[1] = yuv.uvStride();
                strides[2] = yuv.uvStride();
                strides[3] = yuv.alphaStride();
                picture = yuv;
            } else {
                var yuv = YuvPicture.allocate(width, height, format, alpha, color);
                bytePlanes[0] = yuv.y();
                bytePlanes[1] = yuv.u();
                bytePlanes[2] = yuv.v();
                bytePlanes[3] = yuv.alpha();
                strides[0] = yuv.yStride();
                strides[1] = yuv.uvStride();
                strides[2] = yuv.uvStride();
                strides[3] = yuv.alphaStride();
                picture = yuv;
            }
            planeWidths[0] = planeWidths[3] = width;
            planeHeights[0] = planeHeights[3] = height;
            planeWidths[1] = planeWidths[2] = format.chromaWidth(width);
            planeHeights[1] = planeHeights[2] = format.chromaHeight(height);
        }

        @Override
        public void line(int plane, int x, int y, int[] samples, int offset, int count) {
            var chroma = plane == 1 || plane == 2;
            var copiesX = chroma ? 1 << repeatX : 1;
            var copiesY = chroma ? 1 << repeatY : 1;
            var stride = strides[plane];
            var planeWidth = planeWidths[plane];
            var planeHeight = planeHeights[plane];
            var alphaPlane = plane == 3;
            for (var copy = 0; copy < copiesY; copy++) {
                var row = y * copiesY + copy;
                if (row >= planeHeight) {
                    break;
                }
                var base = row * stride;
                var column = x * copiesX;
                if (wide) {
                    var target = shortPlanes[plane];
                    for (var i = 0; i < count; i++) {
                        var value = (short) samples[offset + i];
                        for (var c = 0; c < copiesX && column < planeWidth; c++) {
                            target[base + column++] = value;
                        }
                    }
                } else {
                    var target = bytePlanes[plane];
                    for (var i = 0; i < count; i++) {
                        var value = (byte) narrow(samples[offset + i], alphaPlane);
                        for (var c = 0; c < copiesX && column < planeWidth; c++) {
                            target[base + column++] = value;
                        }
                    }
                }
            }
        }

        /**
         * Reduces a Y'CbCr or transparency sample to 8 bits as {@code ImageOps.toEightBit} does, scaling
         * shallower samples up to the full 8-bit range.
         */
        private int narrow(int value, boolean alphaSample) {
            if (bits == 8) {
                return value;
            }
            if (bits < 8 || alphaSample) {
                var max = (1 << bits) - 1;
                return (int) (((long) value * 255 + (max >> 1)) / max);
            }
            return Math.min(value + (1 << bits - 9) >> bits - 8, 255);
        }

        @Override
        public void rgbLine(int x, int y, int[] red, int[] green, int[] blue, int[] alphaLine, int count) {
            var max = (1 << bits) - 1;
            var base = y * strides[0] + x * 4;
            if (wide) {
                var target = shortPlanes[0];
                for (var i = 0; i < count; i++) {
                    var index = base + i * 4;
                    target[index] = (short) widen(red[i], max);
                    target[index + 1] = (short) widen(green[i], max);
                    target[index + 2] = (short) widen(blue[i], max);
                    target[index + 3] = (short) (alphaLine == null ? 0xFFFF : widen(alphaLine[i + 2], max));
                }
            } else {
                var target = bytePlanes[0];
                for (var i = 0; i < count; i++) {
                    var index = base + i * 4;
                    target[index] = (byte) scale(red[i], max);
                    target[index + 1] = (byte) scale(green[i], max);
                    target[index + 2] = (byte) scale(blue[i], max);
                    target[index + 3] = (byte) (alphaLine == null ? 0xFF : scale(alphaLine[i + 2], max));
                }
            }
        }

        private int scale(int value, int max) {
            var v = value & max;
            return max == 255 ? v : (int) (((long) v * 255 + (max >> 1)) / max);
        }

        private int widen(int value, int max) {
            var v = value & max;
            return max == 65535 ? v : (int) (((long) v * 65535 + (max >> 1)) / max);
        }
    }
}
