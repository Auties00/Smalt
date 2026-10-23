/* Ports OpenJPEG 2.5.3 (jp2.c, opj_jp2_apply_pclr and opj_jp2_apply_cdef). Copyright (c) 2002-2014, Universite
   catholique de Louvain (UCL), Belgium, and the OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Decodes the compositing layers of a JPEG 2000 file or codestream into channels: parses the boxes and the
 * codestreams, decodes every tile of each codestream of a layer at the requested resolution, assembles each
 * component's samples, applies each codestream's palette, registers the codestreams on the layer's grid, and applies
 * the layer's channel definitions or opacity box.
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_jp2_apply_pclr} (palette indices are
 * clamped to the palette) and {@code opj_jp2_apply_cdef} (color channels are swapped into the
 * positions of their associations) for the JP2 post-processing.
 */
public final class Jp2Reader {
    /**
     * Prevents instantiation.
     */
    private Jp2Reader() {
        throw new AssertionError();
    }

    /**
     * Parses the boxes and the main header of the first codestream of the first compositing layer, without decoding
     * packets.
     *
     * @param data the whole file
     * @param limits the limits bounding the tile, component, codestream and layer counts
     * @return the boxes and the codestream's main header
     * @throws MediaException.Malformed if the file or codestream headers are invalid
     * @throws MediaException.Unsupported if the file uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the component count exceeds the channel limit
     */
    public static Headers headers(byte[] data, MediaLimits limits) {
        return headers(data, data.length, limits);
    }

    /**
     * Parses the boxes and the main header of the first codestream of the first compositing layer of a file held
     * in the leading bytes of an array, without decoding packets.
     *
     * @param data the array holding the file from its first byte
     * @param length the length of the file
     * @param limits the limits bounding the tile, component, codestream and layer counts
     * @return the boxes and the codestream's main header
     * @throws MediaException.Malformed if the file or codestream headers are invalid
     * @throws MediaException.Unsupported if the file uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the component count exceeds the channel limit
     */
    public static Headers headers(byte[] data, int length, MediaLimits limits) {
        var boxes = Jp2Boxes.read(data, length, limits);
        var layer = boxes.layer(0);
        var bytes = boxes.codestream(layer.codestreams()[0]);
        var codestream = Codestream.parse(bytes.data(), bytes.start(), bytes.end(), limits);
        return new Headers(boxes, codestream, limits);
    }

    /**
     * Holds the boxes of a file and the main header of the first codestream of its first compositing layer.
     *
     * @param boxes the parsed JP2 or JPX boxes
     * @param codestream the parsed codestream
     * @param limits the limits other codestreams are parsed under
     */
    public record Headers(Jp2Boxes boxes, Codestream codestream, MediaLimits limits) {
        /**
         * Returns the number of channels the first compositing layer decodes to, before an opacity box adds a
         * chroma-key opacity channel.
         *
         * @return the sum over its codestreams of their component count or component mapping's channel count
         */
        public int channels() {
            return channels(0);
        }

        /**
         * Returns the number of channels a compositing layer decodes to, before an opacity box adds a chroma-key
         * opacity channel.
         *
         * @param index the layer index
         * @return the sum over its codestreams of their component count or component mapping's channel count
         */
        int channels(int index) {
            var total = 0;
            for (var stream : boxes.layer(index).codestreams()) {
                var mapping = boxes.mapping(stream);
                if (mapping != null) {
                    total += mapping.length;
                } else {
                    total += parse(stream).outputs().count();
                }
            }
            return total;
        }

        /**
         * Parses the main header of a codestream.
         *
         * @param index the codestream index
         * @return the codestream
         */
        Codestream parse(int index) {
            if (index == boxes.layer(0).codestreams()[0]) {
                return codestream;
            }
            var bytes = boxes.codestream(index);
            return Codestream.parse(bytes.data(), bytes.start(), bytes.end(), limits);
        }

        /**
         * Returns whether the first compositing layer has an opacity channel, from its opacity box or channel
         * definitions or, without them, from a channel count beyond its color space's.
         *
         * @return whether the image has alpha
         */
        public boolean hasAlpha() {
            return hasAlpha(0);
        }

        /**
         * Returns whether a compositing layer has an opacity channel.
         *
         * @param index the layer index
         * @return whether the layer has alpha
         */
        public boolean hasAlpha(int index) {
            var layer = boxes.layer(index);
            if (layer.opacity() != null) {
                return true;
            }
            var definitions = layer.definitions();
            if (definitions != null) {
                for (var definition : definitions) {
                    if (definition[1] == 1 || definition[1] == 2) {
                        return true;
                    }
                }
                return false;
            }
            var channels = channels(index);
            var colors = Jp2Pictures.colorChannels(colorSpace(index), layer.icc(), channels);
            return channels > colors;
        }

        /**
         * Returns whether a compositing layer has a channel wider than 8 bits.
         *
         * @param index the layer index
         * @return whether a palette column or a decoded image component of its codestreams is wider than 8 bits
         */
        public boolean wide(int index) {
            for (var stream : boxes.layer(index).codestreams()) {
                var palette = boxes.palette(stream);
                var depths = palette != null ? palette.depths() : parse(stream).outputs().precisions();
                for (var depth : depths) {
                    if (depth > 8) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Returns the declared color space of the first compositing layer.
         *
         * @return the color space
         */
        public Jp2ColorSpace colorSpace() {
            return colorSpace(0);
        }

        /**
         * Returns the declared color space of a compositing layer.
         *
         * @param index the layer index
         * @return the color space
         */
        Jp2ColorSpace colorSpace(int index) {
            var layer = boxes.layer(index);
            if (layer.icc() != null) {
                return Jp2ColorSpace.ICC;
            }
            return Jp2ColorSpace.of(layer.enumerated());
        }

        /**
         * Returns the size of a compositing layer at full resolution: its first codestream's image area, or the
         * registration grid area when the layer registers several codestreams.
         *
         * @param index the layer index
         * @return the width and height
         */
        public int[] size(int index) {
            var layer = boxes.layer(index);
            var streams = layer.codestreams();
            var parsed = new Codestream[streams.length];
            for (var k = 0; k < streams.length; k++) {
                parsed[k] = parse(streams[k]);
            }
            var area = area(layer.registration(), parsed, 0);
            if (area == null) {
                return new int[]{parsed[0].width(), parsed[0].height()};
            }
            return new int[]{(int) (area[2] - area[0]), (int) (area[3] - area[1])};
        }
    }

    /**
     * Returns the largest reduction every tile-component of a codestream allows.
     *
     * @param codestream the parsed codestream
     * @return the smallest number of decomposition levels of any tile-component
     */
    static int maxReduction(Codestream codestream) {
        var levels = Integer.MAX_VALUE;
        for (var tile = 0; tile < codestream.tileCount(); tile++) {
            for (var coding : codestream.parameters(tile).codings()) {
                levels = Math.min(levels, coding.resolutions() - 1);
            }
        }
        return levels;
    }

    /**
     * Decodes the first compositing layer of the file whose headers were parsed into channels.
     *
     * @param headers the file's headers, from {@link #headers(byte[], MediaLimits)}
     * @param reduction the number of resolution levels to discard; lowered to what the codestreams allow
     * @param maxLayers the number of quality layers to decode, or 0 for all
     * @param limits the limits bounding every allocation
     * @return the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static Jp2Image decode(Headers headers, int reduction, int maxLayers, MediaLimits limits) {
        return decode(headers, 0, reduction, maxLayers, limits);
    }

    /**
     * Decodes one compositing layer of the file whose headers were parsed into channels.
     *
     * @param headers the file's headers, from {@link #headers(byte[], MediaLimits)}
     * @param index the compositing layer, from 0 to the layer count minus one
     * @param reduction the number of resolution levels to discard; lowered to what the codestreams allow
     * @param maxLayers the number of quality layers to decode, or 0 for all
     * @param limits the limits bounding every allocation
     * @return the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static Jp2Image decode(Headers headers, int index, int reduction, int maxLayers, MediaLimits limits) {
        var boxes = headers.boxes();
        var layer = boxes.layer(index);
        var streams = layer.codestreams();
        var parsed = new Codestream[streams.length];
        for (var k = 0; k < streams.length; k++) {
            parsed[k] = headers.parse(streams[k]);
            // The declared size counts even when a reduction decodes less of it.
            LimitChecks.checkPicture(limits, parsed[k].width(), parsed[k].height());
            reduction = Math.min(reduction, maxReduction(parsed[k]));
        }
        var channels = new ArrayList<Jp2Channel>();
        var owners = new ArrayList<Integer>();
        Geometry geometry = null;
        for (var k = 0; k < streams.length; k++) {
            var decoded = decodeCodestream(parsed[k], reduction, maxLayers, limits);
            if (geometry == null) {
                geometry = decoded.geometry();
            }
            var mapped = applyPalette(boxes.palette(streams[k]), boxes.mapping(streams[k]), decoded.channels(),
                    limits);
            channels.addAll(mapped);
            for (var i = 0; i < mapped.size(); i++) {
                owners.add(k);
            }
            LimitChecks.checkChannels(limits, channels.size());
        }
        var registered = register(layer.registration(), parsed, owners, channels, geometry, reduction, limits);
        var colorSpace = headers.colorSpace(index);
        var defined = layer.opacity() != null ? applyOpacity(layer.opacity(), registered.channels(), limits)
                : applyDefinitions(layer.definitions(), registered.channels(), colorSpace, layer.icc());
        var area = registered.geometry();
        return new Jp2Image(area.x0(), area.y0(), area.width(), area.height(), area.fullWidth(), area.fullHeight(),
                reduction, defined, colorSpace, Optional.ofNullable(layer.parameters()),
                Optional.ofNullable(layer.icc()), Optional.ofNullable(boxes.exif()), Optional.ofNullable(boxes.xmp()));
    }

    /**
     * Holds the area of a decoded image.
     *
     * @param x0 the left edge at the decoded resolution
     * @param y0 the top edge at the decoded resolution
     * @param width the width at the decoded resolution
     * @param height the height at the decoded resolution
     * @param fullWidth the width at full resolution
     * @param fullHeight the height at full resolution
     */
    private record Geometry(long x0, long y0, int width, int height, int fullWidth, int fullHeight) {
    }

    /**
     * Holds the channels of a decoded codestream and its image area.
     *
     * @param channels the channels, one per decoded image component
     * @param geometry the image area
     */
    private record Decoded(List<Jp2Channel> channels, Geometry geometry) {
    }

    /**
     * Decodes a codestream into one channel per decoded image component.
     *
     * @param codestream the parsed codestream
     * @param reduction the number of resolution levels to discard, at most what the codestream allows
     * @param maxLayers the number of quality layers to decode, or 0 for all
     * @param limits the limits bounding every allocation
     * @return the channels and the image area
     */
    private static Decoded decodeCodestream(Codestream codestream, int reduction, int maxLayers,
                                            MediaLimits limits) {
        var depths = codestream.reductionDepths(0, reduction);
        var imageX0 = TileComponent.ceilDivPow2(codestream.x0(), depths[0]);
        var imageY0 = TileComponent.ceilDivPow2(codestream.y0(), depths[1]);
        var width = (int) (TileComponent.ceilDivPow2(codestream.x1(), depths[0]) - imageX0);
        var height = (int) (TileComponent.ceilDivPow2(codestream.y1(), depths[1]) - imageY0);
        LimitChecks.checkPicture(limits, width, height);
        var outputs = codestream.outputs();
        var count = outputs.count();
        var planes = new int[count][];
        var widePlanes = new long[count][];
        var bounds = new long[count][];
        var total = 0L;
        for (var c = 0; c < count; c++) {
            var dx = outputs.subsamplingX()[c];
            var dy = outputs.subsamplingY()[c];
            var componentDepths = codestream.reductionDepths(c, reduction);
            var cx0 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.x0(), dx), componentDepths[0]);
            var cy0 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.y0(), dy), componentDepths[1]);
            var cx1 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.x1(), dx), componentDepths[0]);
            var cy1 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.y1(), dy), componentDepths[1]);
            bounds[c] = new long[]{cx0, cy0, cx1 - cx0, cy1 - cy0};
            total += (cx1 - cx0) * (cy1 - cy0) * (outputs.precisions()[c] > 31 ? 2 : 1);
            LimitChecks.checkAllocation(limits, total * 4);
        }
        for (var c = 0; c < count; c++) {
            var size = (int) (bounds[c][2] * bounds[c][3]);
            if (outputs.precisions()[c] > 31) {
                widePlanes[c] = new long[size];
            } else {
                planes[c] = new int[size];
            }
        }
        var layers = maxLayers <= 0 ? Integer.MAX_VALUE : maxLayers;
        var tiles = codestream.tileCount();
        var work = (long) width * height * count * 64;
        var bands = ParallelBands.bands(tiles, work);
        ParallelBands.run(bands, band -> {
            var end = ParallelBands.start(band + 1, bands, tiles);
            for (var tile = ParallelBands.start(band, bands, tiles); tile < end; tile++) {
                var samples = TileDecoder.decode(codestream, tile, reduction, layers, limits);
                for (var c = 0; c < count; c++) {
                    place(samples[c], planes[c], widePlanes[c], bounds[c]);
                }
            }
        });
        var channels = new ArrayList<Jp2Channel>();
        for (var c = 0; c < count; c++) {
            channels.add(new Jp2Channel(bounds[c][0], bounds[c][1], (int) bounds[c][2], (int) bounds[c][3],
                    outputs.subsamplingX()[c], outputs.subsamplingY()[c], outputs.precisions()[c],
                    outputs.signed()[c], Jp2ChannelType.COLOR, -1, planes[c], widePlanes[c]));
        }
        return new Decoded(channels, new Geometry(imageX0, imageY0, width, height, codestream.width(),
                codestream.height()));
    }

    /**
     * Copies a tile-component's samples into its component plane.
     *
     * @param samples the tile-component's samples
     * @param plane the component plane of a component of up to 31 bits, or {@code null}
     * @param widePlane the component plane of a wider component, or {@code null}
     * @param bounds the plane's left, top, width and height on the reduced component grid
     */
    private static void place(TileDecoder.Samples samples, int[] plane, long[] widePlane, long[] bounds) {
        var left = (int) (samples.x0() - bounds[0]);
        var top = (int) (samples.y0() - bounds[1]);
        var width = (int) bounds[2];
        var columns = Math.min(samples.width(), width - left);
        for (var y = 0; y < samples.height(); y++) {
            var row = top + y;
            if (row < 0 || row >= bounds[3] || left < 0 || columns <= 0) {
                continue;
            }
            if (plane != null) {
                System.arraycopy(samples.samples(), y * samples.width(), plane, row * width + left, columns);
            } else if (samples.wide() != null) {
                System.arraycopy(samples.wide(), y * samples.width(), widePlane, row * width + left, columns);
            } else {
                for (var x = 0; x < columns; x++) {
                    widePlane[row * width + left + x] = samples.samples()[y * samples.width() + x];
                }
            }
        }
    }

    /**
     * Returns the area of a compositing layer on its registration grid (M.11.7.7): the intersection of the scaled
     * and offset areas of its codestreams, each grown out to whole registration grid points.
     *
     * @param registration the registration, or {@code null}
     * @param codestreams the parsed codestreams of the layer, in registration order
     * @param reduction the number of resolution levels discarded, which divides every distance by {@code 2^reduction}
     * @return the left, top, right and bottom edges, or {@code null} when every codestream's reference grid is the
     * layer's (no registration, or one that neither scales nor offsets)
     */
    private static long[] area(Jp2Boxes.Registration registration, Codestream[] codestreams, int reduction) {
        if (registration == null || identity(registration)) {
            return null;
        }
        long left = Long.MIN_VALUE;
        long top = Long.MIN_VALUE;
        long right = Long.MAX_VALUE;
        long bottom = Long.MAX_VALUE;
        var gridX = (long) registration.gridX() << reduction;
        var gridY = (long) registration.gridY() << reduction;
        for (var k = 0; k < codestreams.length; k++) {
            var codestream = codestreams[k];
            // a reduced reference grid point x lies at (XO + x * 2^r * XR) / (XS * 2^r) on the reduced layer grid
            var scaleX = (long) registration.resolutionX()[k] << reduction;
            var scaleY = (long) registration.resolutionY()[k] << reduction;
            var offsetX = registration.offsetX()[k];
            var offsetY = registration.offsetY()[k];
            left = Math.max(left, Math.floorDiv(offsetX + TileComponent.ceilDivPow2(codestream.x0(), reduction)
                    * scaleX, gridX));
            top = Math.max(top, Math.floorDiv(offsetY + TileComponent.ceilDivPow2(codestream.y0(), reduction)
                    * scaleY, gridY));
            right = Math.min(right, Math.ceilDiv(offsetX + TileComponent.ceilDivPow2(codestream.x1(), reduction)
                    * scaleX, gridX));
            bottom = Math.min(bottom, Math.ceilDiv(offsetY + TileComponent.ceilDivPow2(codestream.y1(), reduction)
                    * scaleY, gridY));
        }
        if (right <= left || bottom <= top) {
            throw new MediaException.Malformed("JPEG 2000 codestream registration without a common area");
        }
        return new long[]{left, top, right, bottom};
    }

    /**
     * Returns whether a registration maps every codestream's reference grid onto the layer grid unchanged.
     *
     * @param registration the codestream registration of the layer, or {@code null}
     * @return whether every resolution equals the grid size and every offset is 0
     */
    private static boolean identity(Jp2Boxes.Registration registration) {
        for (var k = 0; k < registration.codestreams().length; k++) {
            if (registration.resolutionX()[k] != registration.gridX()
                    || registration.resolutionY()[k] != registration.gridY()
                    || registration.offsetX()[k] != 0 || registration.offsetY()[k] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Holds the channels of a compositing layer on its registration grid.
     *
     * @param channels the channels of the layer, in channel order
     * @param geometry the layer's area
     */
    private record Registered(List<Jp2Channel> channels, Geometry geometry) {
    }

    /**
     * Places the channels of a compositing layer's codestreams on the layer's registration grid (M.11.7.7): a
     * codestream whose grid scales by a whole number without an offset keeps its samples with a larger subsampling;
     * any other registration resamples its channels onto the layer grid, each layer sample taking the codestream
     * sample whose reference grid area covers it.
     *
     * @param registration the registration, or {@code null}
     * @param codestreams the parsed codestreams
     * @param owners the position in the registration of each channel's codestream
     * @param channels the channels of every codestream, in order
     * @param first the area of the first codestream
     * @param reduction the number of resolution levels discarded
     * @param limits the limits bounding every allocation
     * @return the registered channels and the layer's area
     */
    private static Registered register(Jp2Boxes.Registration registration, Codestream[] codestreams,
                                       List<Integer> owners, List<Jp2Channel> channels, Geometry first, int reduction,
                                       MediaLimits limits) {
        var area = area(registration, codestreams, reduction);
        if (area == null) {
            return new Registered(channels, first);
        }
        var full = area(registration, codestreams, 0);
        var geometry = new Geometry(area[0], area[1], (int) (area[2] - area[0]), (int) (area[3] - area[1]),
                (int) (full[2] - full[0]), (int) (full[3] - full[1]));
        LimitChecks.checkPicture(limits, geometry.width(), geometry.height());
        var result = new ArrayList<Jp2Channel>(channels.size());
        for (var i = 0; i < channels.size(); i++) {
            var channel = channels.get(i);
            var k = owners.get(i);
            var gridX = registration.gridX();
            var gridY = registration.gridY();
            var rx = registration.resolutionX()[k];
            var ry = registration.resolutionY()[k];
            var ox = registration.offsetX()[k];
            var oy = registration.offsetY()[k];
            if (rx % gridX == 0 && ry % gridY == 0 && ox == 0 && oy == 0) {
                result.add(new Jp2Channel(channel.x0(), channel.y0(), channel.width(), channel.height(),
                        channel.subsamplingX() * (rx / gridX), channel.subsamplingY() * (ry / gridY),
                        channel.precision(), channel.signed(), channel.type(), channel.association(),
                        channel.samples(), channel.wideSamples()));
                continue;
            }
            result.add(resample(channel, geometry, gridX, gridY, rx, ry, ox, oy, reduction, limits));
        }
        return new Registered(result, geometry);
    }

    /**
     * Resamples a channel onto a layer's registration grid.
     *
     * @param channel the channel on its codestream's grid
     * @param area the layer's area at the decoded resolution
     * @param gridX the horizontal grid size {@code XS}
     * @param gridY the vertical grid size {@code YS}
     * @param rx the horizontal resolution {@code XR} of the channel's codestream
     * @param ry the vertical resolution {@code YR}
     * @param ox the horizontal offset {@code XO}
     * @param oy the vertical offset {@code YO}
     * @param reduction the number of resolution levels discarded
     * @param limits the limits bounding the allocation
     * @return the channel on the layer grid, with a subsampling of 1
     */
    private static Jp2Channel resample(Jp2Channel channel, Geometry area, int gridX, int gridY, int rx, int ry, int ox,
                                       int oy, int reduction, MediaLimits limits) {
        var width = area.width();
        var height = area.height();
        LimitChecks.checkAllocation(limits, (long) width * height * (channel.wide() ? 8 : 4));
        var columns = new int[width];
        for (var u = 0; u < width; u++) {
            // the reference grid point covering layer point u: (u * XS - XO / 2^r) / XR, in exact arithmetic
            var numerator = ((area.x0() + u) * gridX << reduction) - ox;
            var reference = Math.floorDiv(numerator, (long) rx << reduction);
            columns[u] = (int) Math.clamp(Math.floorDiv(reference, channel.subsamplingX()) - channel.x0(), 0,
                    channel.width() - 1);
        }
        var samples = channel.wide() ? null : new int[width * height];
        var wide = channel.wide() ? new long[width * height] : null;
        for (var v = 0; v < height; v++) {
            var numerator = ((area.y0() + v) * gridY << reduction) - oy;
            var reference = Math.floorDiv(numerator, (long) ry << reduction);
            var row = (int) Math.clamp(Math.floorDiv(reference, channel.subsamplingY()) - channel.y0(), 0,
                    channel.height() - 1);
            for (var u = 0; u < width; u++) {
                var index = row * channel.width() + columns[u];
                if (samples != null) {
                    samples[v * width + u] = channel.samples()[index];
                } else {
                    wide[v * width + u] = channel.wideSamples()[index];
                }
            }
        }
        return new Jp2Channel(area.x0(), area.y0(), width, height, 1, 1, channel.precision(), channel.signed(),
                channel.type(), channel.association(), samples, wide);
    }

    /**
     * Expands the palette of a codestream through its component mapping.
     *
     * @param palette the codestream's palette, or {@code null}
     * @param mapping the codestream's component mapping, or {@code null}
     * @param channels the decoded components
     * @param limits the resource bounds checked before every allocation
     * @return the channels the component mapping defines, or {@code channels} without a palette
     * @throws MediaException.Malformed if the mapping refers to a missing component
     */
    private static List<Jp2Channel> applyPalette(Jp2Boxes.Palette palette, int[][] mapping, List<Jp2Channel> channels,
                                                 MediaLimits limits) {
        if (palette == null || mapping == null) {
            return channels;
        }
        LimitChecks.checkChannels(limits, mapping.length);
        var result = new ArrayList<Jp2Channel>(mapping.length);
        for (var entry : mapping) {
            var component = entry[0];
            if (component >= channels.size()) {
                throw new MediaException.Malformed("JPEG 2000 component mapping refers to component " + component);
            }
            var source = channels.get(component);
            if (entry[1] == 0) {
                result.add(source);
                continue;
            }
            var column = entry[2];
            var columns = palette.columns();
            var last = palette.entries() - 1;
            var values = palette.values();
            var depth = palette.depths()[column];
            var count = source.width() * source.height();
            // a column of up to 32 bits fits int samples, a deeper one needs the wide samples
            var output = depth <= 32 ? new int[count] : null;
            var wideOutput = depth <= 32 ? null : new long[count];
            for (var i = 0; i < count; i++) {
                var sample = source.samples() != null ? source.samples()[i] : source.wideSamples()[i];
                var value = values[(int) Math.clamp(sample, 0, last) * columns + column];
                if (output != null) {
                    output[i] = (int) value;
                } else {
                    wideOutput[i] = value;
                }
            }
            result.add(new Jp2Channel(source.x0(), source.y0(), source.width(), source.height(),
                    source.subsamplingX(), source.subsamplingY(), depth, palette.signed()[column],
                    Jp2ChannelType.COLOR, -1, output, wideOutput));
        }
        return result;
    }

    /**
     * Applies the channel definitions: assigns each channel its role and association, and moves
     * each color channel to the position of its color.
     *
     * @param definitions the channel definitions, or {@code null}
     * @param channels the decoded channels
     * @param colorSpace the declared color space
     * @param icc the layer's ICC profile, or {@code null}
     * @return the defined channels
     */
    private static List<Jp2Channel> applyDefinitions(int[][] definitions, List<Jp2Channel> channels,
                                                       Jp2ColorSpace colorSpace, byte[] icc) {
        var count = channels.size();
        var types = new Jp2ChannelType[count];
        var associations = new int[count];
        var list = new ArrayList<>(channels);
        if (definitions == null) {
            var colors = Jp2Pictures.colorChannels(colorSpace, icc, count);
            for (var i = 0; i < count; i++) {
                types[i] = i < colors ? Jp2ChannelType.COLOR : Jp2ChannelType.UNSPECIFIED;
                associations[i] = i < colors ? i + 1 : -1;
            }
        } else {
            Arrays.fill(types, Jp2ChannelType.UNSPECIFIED);
            Arrays.fill(associations, -1);
            var pending = new int[definitions.length][];
            for (var i = 0; i < definitions.length; i++) {
                pending[i] = definitions[i].clone();
            }
            for (var i = 0; i < pending.length; i++) {
                var channel = pending[i][0];
                var type = pending[i][1];
                var association = pending[i][2];
                if (channel >= count) {
                    continue;
                }
                if (association == 0 || association == 65535) {
                    types[channel] = type(type);
                    associations[channel] = association == 0 ? 0 : -1;
                    continue;
                }
                var target = association - 1;
                if (target >= count) {
                    continue;
                }
                if (channel != target && type == 0) {
                    var moved = list.get(channel);
                    list.set(channel, list.get(target));
                    list.set(target, moved);
                    var movedType = types[channel];
                    types[channel] = types[target];
                    types[target] = movedType;
                    var movedAssociation = associations[channel];
                    associations[channel] = associations[target];
                    associations[target] = movedAssociation;
                    for (var j = i + 1; j < pending.length; j++) {
                        if (pending[j][0] == channel) {
                            pending[j][0] = target;
                        } else if (pending[j][0] == target) {
                            pending[j][0] = channel;
                        }
                    }
                    types[target] = type(type);
                    associations[target] = association;
                } else {
                    types[channel] = type(type);
                    associations[channel] = association;
                }
            }
        }
        var result = new ArrayList<Jp2Channel>(count);
        for (var i = 0; i < count; i++) {
            var channel = list.get(i);
            result.add(new Jp2Channel(channel.x0(), channel.y0(), channel.width(), channel.height(),
                    channel.subsamplingX(), channel.subsamplingY(), channel.precision(), channel.signed(), types[i],
                    associations[i], channel.samples(), channel.wideSamples()));
        }
        return result;
    }

    /**
     * Applies an opacity box (M.11.7.6): every channel but the last is the color of its position and the last is
     * opacity or premultiplied opacity; or, for a chroma key, every channel is a color and an opacity channel of one
     * bit is added, 0 where every channel holds the key's value and 1 elsewhere.
     *
     * @param opacity the opacity box
     * @param channels the decoded channels
     * @param limits the limits bounding the chroma-key channel
     * @return the defined channels
     * @throws MediaException.Malformed if the chroma key does not give one value per channel
     */
    private static List<Jp2Channel> applyOpacity(Jp2Boxes.Opacity opacity, List<Jp2Channel> channels,
                                                 MediaLimits limits) {
        var count = channels.size();
        var result = new ArrayList<Jp2Channel>(count + 1);
        var colors = opacity.type() == 2 ? count : count - 1;
        for (var i = 0; i < count; i++) {
            var channel = channels.get(i);
            var type = i < colors ? Jp2ChannelType.COLOR
                    : opacity.type() == 1 ? Jp2ChannelType.PREMULTIPLIED_OPACITY : Jp2ChannelType.OPACITY;
            result.add(new Jp2Channel(channel.x0(), channel.y0(), channel.width(), channel.height(),
                    channel.subsamplingX(), channel.subsamplingY(), channel.precision(), channel.signed(), type,
                    i < colors ? i + 1 : 0, channel.samples(), channel.wideSamples()));
        }
        if (opacity.type() != 2) {
            return result;
        }
        if (opacity.channels() != count) {
            throw new MediaException.Malformed("JPEG 2000 chroma key of " + opacity.channels() + " values for "
                    + count + " channels");
        }
        var keys = new long[count];
        var position = 0;
        var key = opacity.key();
        for (var i = 0; i < count; i++) {
            var channel = channels.get(i);
            var bytes = (channel.precision() + 7) / 8;
            if (key.length - position < bytes) {
                throw new MediaException.Malformed("truncated JPEG 2000 chroma key");
            }
            var value = 0L;
            for (var b = 0; b < bytes; b++) {
                value = (value << 8) | (key[position++] & 0xFF);
            }
            var bits = channel.precision();
            if (bits < 64) {
                value &= (1L << bits) - 1;
                if (channel.signed() && (value & (1L << (bits - 1))) != 0) {
                    value -= 1L << bits;
                }
            }
            keys[i] = value;
        }
        var base = channels.getFirst();
        var size = (long) base.width() * base.height();
        LimitChecks.checkAllocation(limits, size * 4);
        var mask = new int[(int) size];
        for (var y = 0; y < base.height(); y++) {
            var referenceY = (base.y0() + y) * base.subsamplingY();
            for (var x = 0; x < base.width(); x++) {
                var referenceX = (base.x0() + x) * base.subsamplingX();
                var keyed = true;
                for (var i = 0; i < count && keyed; i++) {
                    var channel = channels.get(i);
                    var column = (int) Math.clamp(referenceX / channel.subsamplingX() - channel.x0(), 0,
                            channel.width() - 1);
                    var row = (int) Math.clamp(referenceY / channel.subsamplingY() - channel.y0(), 0,
                            channel.height() - 1);
                    keyed = channel.sample(column, row) == keys[i];
                }
                mask[y * base.width() + x] = keyed ? 0 : 1;
            }
        }
        result.add(new Jp2Channel(base.x0(), base.y0(), base.width(), base.height(), base.subsamplingX(),
                base.subsamplingY(), 1, false, Jp2ChannelType.OPACITY, 0, mask));
        return result;
    }

    /**
     * Maps a channel definition type to a channel role.
     *
     * @param type the {@code Typ} value
     * @return the role
     */
    private static Jp2ChannelType type(int type) {
        return switch (type) {
            case 0 -> Jp2ChannelType.COLOR;
            case 1 -> Jp2ChannelType.OPACITY;
            case 2 -> Jp2ChannelType.PREMULTIPLIED_OPACITY;
            default -> Jp2ChannelType.UNSPECIFIED;
        };
    }
}
