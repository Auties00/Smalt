package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Decodes the frames of a hierarchical JPEG image (ITU-T T.81 annex J) into the components of the completed
 * image.
 *
 * <p>Each component starts with a non-differential frame, whose samples become its reference. Every later frame
 * coding the component is differential: the reference is first expanded by the EXP segment before the frame, if
 * any, with the bilinear interpolation of J.1.1.2 (horizontally, then vertically: the new samples between two
 * samples are their average truncated, toward negative infinity for the negative samples only a DCT reconstruction
 * holds, the last column and row repeated), brought to the frame component's size
 * (cropped, or extended by repeating its last column and row), and the frame's decoded differences are added to
 * it modulo {@code 2^16} (J.2.1): a DCT frame's differences are inverse transformed without the level shift, and
 * its reconstruction is kept as signed 16-bit values, unclamped, while only the non-differential DCT frame is
 * clamped to the range of the precision (A.3.1). The last reference of each component, brought to the component
 * size of the DHP segment and clamped to the range of the precision, is the decoded component; when the last
 * frame of every component is lossless the image decodes to exactly those samples.
 *
 * <p>The frames must follow the order of annex J: all non-differential frames DCT coded or all lossless, one
 * non-differential frame per component before its differential frames, only lossless differential frames over
 * lossless ones, and over DCT frames a lossless differential frame only as the last frame of a component.
 *
 * <p>Instances are immutable.
 */
final class HierarchicalImage {
    /**
     * The decoded samples of each component of the completed image, in DHP order.
     */
    private final int[][] planes;

    /**
     * The width of each decoded component.
     */
    private final int[] widths;

    /**
     * The height of each decoded component.
     */
    private final int[] heights;

    /**
     * Whether the last frame of every component is lossless.
     */
    private final boolean exact;

    /**
     * The image height: the DHP segment's, or the tallest frame's when the DHP segment declares none.
     */
    private final int height;

    /**
     * The precision of the samples.
     */
    private final int precision;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * Constructs the decoded image.
     *
     * @param planes the samples of each component
     * @param widths the width of each component
     * @param heights the height of each component
     * @param exact whether the last frame of every component is lossless
     * @param height the image height
     * @param precision the sample precision
     * @param limits the resource bounds
     */
    private HierarchicalImage(int[][] planes, int[] widths, int[] heights, boolean exact, int height, int precision,
                              MediaLimits limits) {
        this.planes = planes;
        this.widths = widths;
        this.heights = heights;
        this.exact = exact;
        this.height = height;
        this.precision = precision;
        this.limits = limits;
    }

    /**
     * Decodes every frame of a hierarchical image.
     *
     * @param reader the reader, its headers read: positioned at the first scan of the first frame
     * @return the decoded image
     * @throws MediaException.Malformed if a frame or scan header is invalid, a differential frame codes a
     * component no earlier frame coded, a component is never coded, the frames break the order of annex J, or a
     * DCT frame holds no decodable data
     * @throws MediaException.Unsupported if a frame uses a coding process this decoder lacks
     * @throws MediaException.LimitExceeded if a frame exceeds the limits
     */
    static HierarchicalImage decode(JpegReader reader) {
        var image = reader.imageFrame();
        var count = image.components.length;
        var precision = image.precision;
        var limits = reader.limits();
        var references = new int[count][];
        var widths = new int[count];
        var heights = new int[count];
        var exact = new boolean[count];
        var losslessImage = reader.codedFrame().lossless();
        var tallest = 0;
        do {
            var frame = reader.codedFrame();
            var marker = frame.marker;
            var differential = marker == 0xC5 || marker == 0xC6 || marker == 0xC7 || marker == 0xCD
                    || marker == 0xCE || marker == 0xCF;
            var lossless = frame.lossless();
            var expansion = reader.takeExpansion();
            checkOrder(reader, frame, differential, lossless, losslessImage, references, exact);
            LimitChecks.checkPicture(limits, frame.width, frame.height);
            frame.computeLayout();
            tallest = Math.max(tallest, frame.height);
            int[][] samples;
            if (lossless) {
                samples = decodeLossless(reader, frame, differential);
                reader.skipFrame();
            } else {
                samples = reader.decodeDctFrame(differential);
            }
            for (var k = 0; k < frame.components.length; k++) {
                var component = frame.components[k];
                var index = reader.imageIndex(component.id);
                var width = component.width;
                var height = component.height;
                if (!differential) {
                    references[index] = samples[k];
                } else {
                    var reference = references[index];
                    if (reference == null) {
                        throw new MediaException.Malformed("hierarchical JPEG differential frame codes component "
                                + component.id + " before a non-differential frame does");
                    }
                    var referenceWidth = widths[index];
                    var referenceHeight = heights[index];
                    if (expansion > 0) {
                        var horizontal = (expansion & 2) != 0;
                        var vertical = (expansion & 1) != 0;
                        reference = expand(reference, referenceWidth, referenceHeight, horizontal, vertical, limits);
                        referenceWidth <<= horizontal ? 1 : 0;
                        referenceHeight <<= vertical ? 1 : 0;
                    }
                    reference = fit(reference, referenceWidth, referenceHeight, width, height, limits);
                    var difference = samples[k];
                    for (var i = 0; i < reference.length; i++) {
                        var value = reference[i] + difference[i];
                        reference[i] = lossless ? value & 0xFFFF : (short) value;
                    }
                    references[index] = reference;
                }
                widths[index] = width;
                heights[index] = height;
                exact[index] = lossless;
            }
        } while (reader.nextFrame());
        var all = true;
        for (var index = 0; index < count; index++) {
            if (references[index] == null) {
                throw new MediaException.Malformed("hierarchical JPEG image never codes component "
                        + image.components[index].id);
            }
            all &= exact[index];
        }
        var height = image.height != 0 ? image.height : tallest;
        return new HierarchicalImage(references, widths, heights, all, height, precision, limits);
    }

    /**
     * Checks a frame against the order of annex J before it is decoded.
     *
     * @param reader the reader, which maps component identifiers to the components of the image
     * @param frame the frame about to be decoded
     * @param differential whether the frame is differential
     * @param lossless whether the frame is lossless
     * @param losslessImage whether the first non-differential frame is lossless
     * @param references the reference of each component, {@code null} before its non-differential frame
     * @param exact whether the last frame of each component so far is lossless
     * @throws MediaException.Malformed if the frame breaks the order
     */
    private static void checkOrder(JpegReader reader, JpegFrame frame, boolean differential, boolean lossless,
                                   boolean losslessImage, int[][] references, boolean[] exact) {
        if (!differential && lossless != losslessImage) {
            throw new MediaException.Malformed("the non-differential frames of a hierarchical JPEG image mix DCT"
                    + " and lossless coding");
        }
        if (losslessImage && !lossless) {
            throw new MediaException.Malformed("a hierarchical JPEG image of lossless frames holds a DCT frame");
        }
        for (var component : frame.components) {
            var index = reader.imageIndex(component.id);
            if (!differential && references[index] != null) {
                throw new MediaException.Malformed("hierarchical JPEG image codes component " + component.id
                        + " in a second non-differential frame");
            }
            if (!losslessImage && exact[index]) {
                throw new MediaException.Malformed("hierarchical JPEG image codes component " + component.id
                        + " after the lossless differential frame that must be its last");
            }
        }
    }

    /**
     * Decodes the scans of the current lossless frame of a hierarchical image.
     *
     * @param reader the reader, positioned at the frame's first scan
     * @param frame the current frame of the image
     * @param differential whether the frame is differential
     * @return the samples, or the differences modulo {@code 2^16}, of each frame component
     */
    private static int[][] decodeLossless(JpegReader reader, JpegFrame frame, boolean differential) {
        var jpeg = new LosslessJpeg(reader.input(), reader.start(), reader.end() - reader.start(),
                reader.frameStart(), differential, frame.height, reader.limits());
        var components = frame.components;
        var samples = new int[components.length][];
        for (var k = 0; k < components.length; k++) {
            LimitChecks.checkAllocation(reader.limits(), 4L * components[k].width * components[k].height);
            samples[k] = new int[components[k].width * components[k].height];
        }
        jpeg.decodeComponents((component, row, values) -> {
            var width = components[component].width;
            if (row < components[component].height) {
                System.arraycopy(values, 0, samples[component], row * width, width);
            }
        });
        return samples;
    }

    /**
     * Expands a reference component by two horizontally, vertically or both (T.81 J.1.1.2).
     *
     * @param plane the samples
     * @param width the width in samples
     * @param height the height in rows
     * @param horizontal whether to double the width
     * @param vertical whether to double the height
     * @param limits the resource bounds
     * @return the expanded samples
     */
    static int[] expand(int[] plane, int width, int height, boolean horizontal, boolean vertical,
                        MediaLimits limits) {
        var result = plane;
        var columns = width;
        if (horizontal) {
            columns = 2 * width;
            LimitChecks.checkAllocation(limits, 4L * columns * height);
            result = new int[columns * height];
            for (var y = 0; y < height; y++) {
                var in = y * width;
                var out = y * columns;
                for (var x = 0; x < width; x++) {
                    var a = plane[in + x];
                    var b = plane[in + Math.min(x + 1, width - 1)];
                    result[out + 2 * x] = a;
                    result[out + 2 * x + 1] = (a + b) >> 1;
                }
            }
        }
        if (vertical) {
            var source = result;
            LimitChecks.checkAllocation(limits, 8L * columns * height);
            result = new int[columns * height * 2];
            for (var y = 0; y < height; y++) {
                var above = y * columns;
                var below = Math.min(y + 1, height - 1) * columns;
                var out = 2 * y * columns;
                for (var x = 0; x < columns; x++) {
                    var a = source[above + x];
                    result[out + x] = a;
                    result[out + columns + x] = (a + source[below + x]) >> 1;
                }
            }
        }
        return result;
    }

    /**
     * Brings a plane to a size, cropping it or extending it by repeating its last column and row.
     *
     * @param plane the samples
     * @param width the width in samples
     * @param height the height in rows
     * @param targetWidth the width to bring it to
     * @param targetHeight the height to bring it to
     * @param limits the resource bounds
     * @return the plane, the same array when the size already matches
     */
    static int[] fit(int[] plane, int width, int height, int targetWidth, int targetHeight, MediaLimits limits) {
        if (width == targetWidth && height == targetHeight) {
            return plane;
        }
        LimitChecks.checkAllocation(limits, 4L * targetWidth * targetHeight);
        var result = new int[targetWidth * targetHeight];
        for (var y = 0; y < targetHeight; y++) {
            var in = Math.min(y, height - 1) * width;
            var out = y * targetWidth;
            for (var x = 0; x < targetWidth; x++) {
                result[out + x] = plane[in + Math.min(x, width - 1)];
            }
        }
        return result;
    }

    /**
     * {@return whether the last frame of every component is lossless, so that the image decodes to exactly the
     * samples it codes}
     */
    boolean exact() {
        return exact;
    }

    /**
     * {@return the image height: the DHP segment's, or the tallest frame's when the DHP segment declares none}
     */
    int height() {
        return height;
    }

    /**
     * Returns the decoded components at the sizes of the completed image.
     *
     * <p>Below 16 bits a sample outside the range of the precision, which only an encoder whose references differ
     * from those of J.1.1.2 makes the last lossless frame produce, is read as a signed 16-bit value and clamped to
     * the range: a difference that went below zero gives zero rather than wrapping around to the top of the range.
     *
     * @param image the frame of the completed image, its layout computed
     * @return the samples
     */
    LosslessSamples samples(JpegFrame image) {
        var components = image.components;
        var fitted = new int[components.length][];
        var max = (1 << precision) - 1;
        for (var c = 0; c < components.length; c++) {
            var plane = fit(planes[c], widths[c], heights[c], components[c].width, components[c].height, limits);
            if (precision < 16) {
                if (plane == planes[c]) {
                    LimitChecks.checkAllocation(limits, 4L * plane.length);
                    plane = plane.clone();
                }
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = Math.clamp((short) plane[i], 0, max);
                }
            }
            fitted[c] = plane;
        }
        return new PlaneSamples(image, fitted, limits);
    }

    /**
     * Returns the decoded components of a DCT-coded image as planes of the completed image's layout, padded to
     * whole MCUs by repeating the last column and row of each component, each sample read as a signed 16-bit value
     * and clamped to the range of the precision.
     *
     * @param image the frame of the completed image, its layout computed
     * @param wide whether to keep 12-bit samples rather than reduce them to 8 bits
     * @return the planes
     */
    JpegPlanes planes(JpegFrame image, boolean wide) {
        var components = image.components;
        var count = components.length;
        var strides = new int[count];
        var bytes = wide ? null : new byte[count][];
        var shorts = wide ? new short[count][] : null;
        for (var c = 0; c < count; c++) {
            var component = components[c];
            var stride = component.paddedBlocksWide * 8;
            var rows = component.paddedBlocksHigh * 8;
            strides[c] = stride;
            var fitted = fit(fit(planes[c], widths[c], heights[c], component.width, component.height, limits),
                    component.width, component.height, stride, rows, limits);
            var max = (1 << precision) - 1;
            LimitChecks.checkAllocation(limits, (wide ? 2L : 1L) * stride * rows);
            if (wide) {
                var plane = new short[stride * rows];
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (short) Math.clamp((short) fitted[i], 0, max);
                }
                shorts[c] = plane;
            } else {
                var plane = new byte[stride * rows];
                for (var i = 0; i < plane.length; i++) {
                    var value = Math.clamp((short) fitted[i], 0, max);
                    plane[i] = precision == 12 ? Idct12.EIGHT_BIT[value] : (byte) value;
                }
                bytes[c] = plane;
            }
        }
        return wide ? new JpegPlanes(image, shorts, strides, 0) : new JpegPlanes(image, bytes, strides, 0);
    }

    /**
     * Supplies decoded component planes as the samples of a frame.
     */
    private static final class PlaneSamples implements LosslessSamples {
        /**
         * The frame the planes belong to.
         */
        private final JpegFrame frame;

        /**
         * The samples of each component, at its size.
         */
        private final int[][] planes;

        /**
         * The resource bounds.
         */
        private final MediaLimits limits;

        /**
         * Constructs the samples.
         *
         * @param frame the frame, its layout computed
         * @param planes the samples of each component
         * @param limits the resource bounds
         */
        PlaneSamples(JpegFrame frame, int[][] planes, MediaLimits limits) {
            this.frame = frame;
            this.planes = planes;
            this.limits = limits;
        }

        @Override
        public int componentWidth(int component) {
            return frame.components[component].width;
        }

        @Override
        public boolean fullResolution() {
            for (var component : frame.components) {
                if (component.h != frame.hMax || component.v != frame.vMax) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean streamsRows() {
            return false;
        }

        @Override
        public void decode(LosslessJpeg.RowSink sink) {
            if (!fullResolution()) {
                throw new IllegalStateException("the frame has subsampled components");
            }
            var count = planes.length;
            var width = frame.width;
            LimitChecks.checkAllocation(limits, 4L * width * count);
            var row = new int[width * count];
            for (var y = 0; y < frame.height; y++) {
                for (var c = 0; c < count; c++) {
                    var plane = planes[c];
                    for (var x = 0; x < width; x++) {
                        row[x * count + c] = plane[y * width + x];
                    }
                }
                sink.row(y, row);
            }
        }

        @Override
        public void decodeComponents(LosslessJpeg.ComponentSink sink) {
            for (var c = 0; c < planes.length; c++) {
                var width = frame.components[c].width;
                var rows = frame.components[c].height;
                var row = new int[width];
                for (var y = 0; y < rows; y++) {
                    System.arraycopy(planes[c], y * width, row, 0, width);
                    sink.row(c, y, row);
                }
            }
        }
    }
}
