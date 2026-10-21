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
 * Decodes a JPEG 2000 file or codestream into channels: parses the boxes and the codestream,
 * decodes every tile at the requested resolution, assembles each component's samples, and applies
 * the JP2 palette and channel definitions.
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
     * Parses the boxes and the codestream headers of a file, without decoding packets.
     *
     * @param data the whole file
     * @param limits the limits bounding the tile and component counts
     * @return the boxes and the codestream's main header
     * @throws MediaException.Malformed if the file or codestream headers are invalid
     * @throws MediaException.Unsupported if the file uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the component count exceeds the channel limit
     */
    public static Headers headers(byte[] data, MediaLimits limits) {
        var boxes = Jp2Boxes.read(data, data.length);
        var codestream = Codestream.parse(data, boxes.codestreamStart(), boxes.codestreamEnd(), limits);
        return new Headers(boxes, codestream);
    }

    /**
     * Holds the boxes and the codestream of a file.
     *
     * @param boxes the parsed JP2 boxes
     * @param codestream the parsed codestream
     */
    public record Headers(Jp2Boxes boxes, Codestream codestream) {
        /**
         * Returns the number of channels the image decodes to.
         *
         * @return the component count, or the component mapping's channel count
         */
        public int channels() {
            var mapping = boxes.mapping();
            return mapping != null ? mapping.length : codestream.components();
        }

        /**
         * Returns whether the image has an opacity channel, from its channel definitions or, without
         * them, from a channel count beyond its color space's.
         *
         * @return whether the image has alpha
         */
        public boolean hasAlpha() {
            var definitions = boxes.definitions();
            if (definitions != null) {
                for (var definition : definitions) {
                    if (definition[1] == 1 || definition[1] == 2) {
                        return true;
                    }
                }
                return false;
            }
            var channels = channels();
            var colors = Jp2Pictures.colorChannels(colorSpace(), boxes.iccProfile(), channels);
            return channels > colors;
        }

        /**
         * Returns the declared color space.
         *
         * @return the color space
         */
        public Jp2ColorSpace colorSpace() {
            if (boxes.iccProfile() != null) {
                return Jp2ColorSpace.ICC;
            }
            return Jp2ColorSpace.of(boxes.enumeratedColorSpace());
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
     * Decodes the file whose headers were parsed into channels.
     *
     * @param headers the file's headers, from {@link #headers(byte[], MediaLimits)}
     * @param reduction the number of resolution levels to discard; lowered to what the codestream
     * allows
     * @param maxLayers the number of quality layers to decode, or 0 for all
     * @param limits the limits bounding every allocation
     * @return the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature this decoder does not implement
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static Jp2Image decode(Headers headers, int reduction, int maxLayers, MediaLimits limits) {
        var boxes = headers.boxes();
        var codestream = headers.codestream();
        // The declared size counts even when a reduction decodes less of it.
        LimitChecks.checkPicture(limits, codestream.width(), codestream.height());
        reduction = Math.min(reduction, maxReduction(codestream));
        var width = (int) (TileComponent.ceilDivPow2(codestream.x1(), reduction)
                - TileComponent.ceilDivPow2(codestream.x0(), reduction));
        var height = (int) (TileComponent.ceilDivPow2(codestream.y1(), reduction)
                - TileComponent.ceilDivPow2(codestream.y0(), reduction));
        LimitChecks.checkPicture(limits, width, height);
        var count = codestream.components();
        var planes = new int[count][];
        var bounds = new int[count][];
        var total = 0L;
        for (var c = 0; c < count; c++) {
            var cx0 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.x0(), codestream.subsamplingX(c)), reduction);
            var cy0 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.y0(), codestream.subsamplingY(c)), reduction);
            var cx1 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.x1(), codestream.subsamplingX(c)), reduction);
            var cy1 = TileComponent.ceilDivPow2(Math.ceilDiv(codestream.y1(), codestream.subsamplingY(c)), reduction);
            bounds[c] = new int[]{(int) cx0, (int) cy0, (int) (cx1 - cx0), (int) (cy1 - cy0)};
            total += (cx1 - cx0) * (cy1 - cy0);
            LimitChecks.checkAllocation(limits, total * 4);
        }
        for (var c = 0; c < count; c++) {
            planes[c] = new int[bounds[c][2] * bounds[c][3]];
        }
        var layers = maxLayers <= 0 ? Integer.MAX_VALUE : maxLayers;
        var tiles = codestream.tileCount();
        var work = (long) width * height * count * 64;
        var bands = ParallelBands.bands(tiles, work);
        var finalReduction = reduction;
        ParallelBands.run(bands, band -> {
            var end = ParallelBands.start(band + 1, bands, tiles);
            for (var tile = ParallelBands.start(band, bands, tiles); tile < end; tile++) {
                var samples = TileDecoder.decode(codestream, tile, finalReduction, layers, limits);
                for (var c = 0; c < count; c++) {
                    place(samples[c], planes[c], bounds[c]);
                }
            }
        });
        var channels = new ArrayList<Jp2Channel>();
        for (var c = 0; c < count; c++) {
            channels.add(new Jp2Channel(bounds[c][0], bounds[c][1], bounds[c][2], bounds[c][3],
                    codestream.subsamplingX(c), codestream.subsamplingY(c), codestream.precision(c),
                    codestream.signed(c), Jp2ChannelType.COLOR, -1, planes[c]));
        }
        var colorSpace = headers.colorSpace();
        var mapped = applyPalette(boxes, channels, limits);
        var defined = applyDefinitions(boxes, mapped, colorSpace);
        return new Jp2Image((int) TileComponent.ceilDivPow2(codestream.x0(), reduction),
                (int) TileComponent.ceilDivPow2(codestream.y0(), reduction), width, height, codestream.width(),
                codestream.height(), reduction, defined, colorSpace,
                Optional.ofNullable(boxes.iccProfile()), Optional.ofNullable(boxes.exif()),
                Optional.ofNullable(boxes.xmp()));
    }

    /**
     * Copies a tile-component's samples into its component plane.
     *
     * @param samples the tile-component's samples
     * @param plane the component plane
     * @param bounds the plane's left, top, width and height on the reduced component grid
     */
    private static void place(TileDecoder.Samples samples, int[] plane, int[] bounds) {
        var left = samples.x0() - bounds[0];
        var top = samples.y0() - bounds[1];
        var columns = Math.min(samples.width(), bounds[2] - left);
        for (var y = 0; y < samples.height(); y++) {
            var row = top + y;
            if (row < 0 || row >= bounds[3] || left < 0 || columns <= 0) {
                continue;
            }
            System.arraycopy(samples.samples(), y * samples.width(), plane, row * bounds[2] + left, columns);
        }
    }

    /**
     * Expands the palette of a JP2 file through its component mapping.
     *
     * @param boxes the parsed JP2 boxes
     * @param channels the decoded components
     * @param limits the resource bounds checked before every allocation
     * @return the channels the component mapping defines, or {@code channels} without a palette
     * @throws MediaException.Malformed if the mapping refers to a missing component
     */
    private static List<Jp2Channel> applyPalette(Jp2Boxes boxes, List<Jp2Channel> channels, MediaLimits limits) {
        var palette = boxes.palette();
        var mapping = boxes.mapping();
        if (palette == null) {
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
            var input = source.samples();
            var output = new int[input.length];
            for (var i = 0; i < input.length; i++) {
                var index = Math.clamp(input[i], 0, last);
                output[i] = values[index * columns + column];
            }
            result.add(new Jp2Channel(source.x0(), source.y0(), source.width(), source.height(),
                    source.subsamplingX(), source.subsamplingY(), palette.depths()[column], palette.signed()[column],
                    Jp2ChannelType.COLOR, -1, output));
        }
        return result;
    }

    /**
     * Applies the channel definitions: assigns each channel its role and association, and moves
     * each color channel to the position of its color.
     *
     * @param boxes the parsed JP2 boxes
     * @param channels the decoded channels
     * @param colorSpace the declared color space
     * @return the defined channels
     */
    private static List<Jp2Channel> applyDefinitions(Jp2Boxes boxes, List<Jp2Channel> channels,
                                                       Jp2ColorSpace colorSpace) {
        var count = channels.size();
        var types = new Jp2ChannelType[count];
        var associations = new int[count];
        var list = new ArrayList<>(channels);
        var definitions = boxes.definitions();
        if (definitions == null) {
            var colors = Jp2Pictures.colorChannels(colorSpace, boxes.iccProfile(), count);
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
                    associations[i], channel.samples()));
        }
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
