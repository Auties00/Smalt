package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Decodes one JPEG XS codestream into the samples of its components.
 *
 * <p>Slices are decoded in parallel, then each component is reconstructed, the inverse color transformation applied
 * and the values scaled to the component precision. Codestreams of temporal differential coding are rejected by
 * {@link JxsHeader#parse}. This class is thread-safe.
 */
public final class JxsFrameDecoder {
    private JxsFrameDecoder() {
        throw new AssertionError();
    }

    /**
     * The decoded components of a codestream.
     *
     * @param header the main header
     * @param layout the picture layout
     * @param samples the samples of each component, row by row, as unsigned 16-bit values
     * @param end the position after the {@code EOC} marker
     */
    public record Frame(JxsHeader header, JxsLayout layout, short[][] samples, int end) {
    }

    /**
     * The coefficient planes a sequence of codestreams decodes into, kept from one codestream to the next so that a
     * decoder of a video track does not allocate them for every frame. Instances are not thread-safe.
     */
    public static final class Workspace {
        /**
         * The coefficients of each component, or {@code null} before the first frame.
         */
        private int[][] planes;

        /**
         * Creates an empty workspace.
         */
        public Workspace() {
        }

        /**
         * {@return zeroed coefficient planes for a layout, reused when the layout matches the last one}
         *
         * @param layout the layout of the frame about to be decoded
         */
        private int[][] planes(JxsLayout layout) {
            var fits = planes != null && planes.length == layout.components;
            for (var c = 0; fits && c < layout.components; c++) {
                fits = planes[c].length == layout.componentWidth[c] * layout.componentHeight[c];
            }
            if (fits) {
                for (var plane : planes) {
                    java.util.Arrays.fill(plane, 0);
                }
                return planes;
            }
            var fresh = new int[layout.components][];
            for (var c = 0; c < layout.components; c++) {
                fresh[c] = new int[layout.componentWidth[c] * layout.componentHeight[c]];
            }
            planes = fresh;
            return fresh;
        }
    }

    /**
     * Decodes the codestream at {@code data[offset, end)}.
     *
     * @param data the bytes
     * @param offset the position of the {@code SOC} marker
     * @param end the end of the data (exclusive)
     * @param limits the limits on the picture size and the allocation
     * @return the components
     * @throws MediaException.Malformed if the codestream is invalid
     * @throws MediaException.Unsupported if it uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static Frame decode(byte[] data, int offset, int end, MediaLimits limits) {
        return decode(data, offset, end, limits, null);
    }

    /**
     * Decodes the codestream at {@code data[offset, end)} of a sequence into the planes of a workspace.
     *
     * @param data the bytes
     * @param offset the position of the {@code SOC} marker
     * @param end the end of the data (exclusive)
     * @param limits the limits on the picture size and the allocation
     * @param workspace the planes to decode into, or {@code null} to allocate them
     * @return the components
     * @throws MediaException.Malformed if the codestream is invalid
     * @throws MediaException.Unsupported if it uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static Frame decode(byte[] data, int offset, int end, MediaLimits limits, Workspace workspace) {
        var header = JxsHeader.parse(data, offset, end);
        var layout = layout(header, limits);
        var planes = (workspace != null ? workspace : new Workspace()).planes(layout);
        var positions = JxsSliceDecoder.locateSlices(header, layout, data, offset + header.length, end);
        var eoc = positions[layout.slices];
        if (eoc + 2 > end || JxsHeader.u16(data, eoc) != JxsHeader.EOC) {
            throw new MediaException.Malformed("the JPEG XS codestream does not end with EOC after its last slice");
        }
        var slices = layout.slices;
        var bands = ParallelBands.bands(slices, (long) header.width * header.height * header.components);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, slices);
            var to = ParallelBands.start(band + 1, bands, slices);
            var decoder = new JxsSliceDecoder(header, layout, planes, data, end);
            for (var s = from; s < to; s++) {
                decoder.decodeSlice(s, positions[s]);
            }
        });
        return new Frame(header, layout, reconstruct(header, layout, planes), eoc + 2);
    }

    /**
     * Computes the layout of a codestream and checks it against the limits.
     *
     * @param header the main header
     * @param limits the limits on the picture size
     * @return the layout
     * @throws MediaException.Malformed if the decomposition does not fit the picture
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static JxsLayout layout(JxsHeader header, MediaLimits limits) {
        var width = header.colorTransform == 3 ? header.width * 2 : header.width;
        var height = header.colorTransform == 3 ? header.height * 2 : header.height;
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkChannels(limits, header.components);
        var layout = new JxsLayout(header);
        var samples = 0L;
        for (var c = 0; c < layout.components; c++) {
            samples += (long) layout.componentWidth[c] * layout.componentHeight[c];
        }
        LimitChecks.checkAllocation(limits, samples * 6);
        return layout;
    }

    /**
     * Turns decoded wavelet coefficients into component samples.
     *
     * @param header the main header
     * @param layout the picture layout
     * @param planes the coefficient planes, overwritten
     * @return the samples of each component
     */
    public static short[][] reconstruct(JxsHeader header, JxsLayout layout, int[][] planes) {
        var shift = header.fractionalBits;
        var components = layout.components;
        for (var c = 0; c < components; c++) {
            var plane = planes[c];
            if (shift > 0) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] <<= shift;
                }
            }
            if (c < layout.decomposed) {
                JxsWavelet.inverse(plane, layout, c);
            }
        }
        if (header.colorTransform == 1) {
            JxsTransforms.inverseRct(planes, layout.componentWidth[0] * layout.componentHeight[0]);
        } else if (header.colorTransform == 3) {
            JxsTransforms.inverseStarTetrix(planes, layout.componentWidth[0], layout.componentHeight[0],
                    header.starTetrixExtent, JxsTransforms.cfaType(header), header.starTetrixE1, header.starTetrixE2);
        }
        var samples = new short[components][];
        ParallelBands.run(components, c -> {
            var count = layout.componentWidth[c] * layout.componentHeight[c];
            samples[c] = new short[count];
            JxsTransforms.output(planes[c], count, header, header.precision[c], samples[c]);
        });
        return samples;
    }
}
