/* Ports libjxl 0.11.2 (lib/jxl/enc_modular.cc: ModularFrameEncoder::Init, ComputeEncodingData and
   PrepareStreamParams for lossless frames). Copyright the JPEG XL Project Authors; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.image.jxl.internal.modular.ForwardTransforms;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a lossless modular frame: chooses the transforms, splits the channels into groups, learns the tree and
 * writes the sections in TOC order.
 */
final class ModularFrameEncoder {
    private static final int[] PROPERTY_ORDER = {0, 1, 15, 9, 10, 11, 12, 13, 14, 2, 3, 4, 5, 6, 7, 8};

    private ModularFrameEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes the channels of a frame.
     *
     * @param channels the color channels followed by the extra channels, at full frame size
     * @param colorChannels the number of color channels, 1 or 3
     * @param bitDepth the largest sample bit depth
     * @param effort the effort, from 1 to 10
     * @param groupSizeShift the group size shift, 0 to 3
     * @return the sections: LF global, one per LF group, HF global, one per group
     */
    static List<JxlBitWriter> encode(List<ModularChannel> channels, int colorChannels, int bitDepth, int effort,
                                     int groupSizeShift) {
        var width = channels.getFirst().width;
        var height = channels.getFirst().height;
        var groupDim = 128 << groupSizeShift;
        var numGroups = ceilDiv(width, groupDim) * ceilDiv(height, groupDim);
        var lfDim = groupDim * 8;
        var numLf = ceilDiv(width, lfDim) * ceilDiv(height, lfDim);
        var prepared = prepare(channels, colorChannels, bitDepth, effort, groupDim, numLf);
        var settings = treeSettings(effort, prepared.streams().size(), 0);
        var encoder = ModularEncoder.encode(prepared.streams(), List.of(settings),
                EntropyEncoder.Params.forEffort(effort, true));
        var sections = new ArrayList<JxlBitWriter>();
        var lfGlobal = new JxlBitWriter(1024);
        lfGlobal.writeBool(true);
        encoder.writeGlobal(lfGlobal);
        encoder.writeStream(prepared.global(), lfGlobal);
        sections.add(lfGlobal);
        for (var i = 0; i < numLf; i++) {
            sections.add(new JxlBitWriter(16));
        }
        sections.add(new JxlBitWriter(16));
        for (var g = 0; g < numGroups; g++) {
            var w = new JxlBitWriter(1024);
            prepared.writeGroup(encoder, g, w);
            sections.add(w);
        }
        return sections;
    }

    /**
     * The modular streams of a frame's channels: the global stream and one per group.
     *
     * @param global the global stream
     * @param groups the stream of each group, or {@code null} for a group without samples
     */
    record Prepared(ModularEncoder.Stream global, ModularEncoder.Stream[] groups) {
        /**
         * {@return every stream, in stream index order}
         */
        List<ModularEncoder.Stream> streams() {
            var list = new ArrayList<ModularEncoder.Stream>();
            list.add(global);
            for (var g : groups) {
                if (g != null) {
                    list.add(g);
                }
            }
            return list;
        }

        /**
         * Writes the stream of a group, if it has one.
         *
         * @param encoder the encoder built from these streams
         * @param group the index of the group in the frame
         * @param w the output
         */
        void writeGroup(ModularEncoder encoder, int group, JxlBitWriter w) {
            if (groups[group] != null) {
                encoder.writeStream(groups[group], w);
            }
        }
    }

    /**
     * Applies the transforms of a lossless encoder and splits the channels into the global stream and the group
     * streams.
     *
     * @param channels the channels at full frame size, color channels first
     * @param colorChannels the number of color channels among them, 0 when only extra channels are coded
     * @param bitDepth the largest sample bit depth
     * @param effort the effort, from 1 to 10
     * @param groupDim the group size in samples
     * @param numLf the number of LF groups
     * @return the streams
     */
    static Prepared prepare(List<ModularChannel> channels, int colorChannels, int bitDepth, int effort,
                            int groupDim, int numLf) {
        var width = channels.getFirst().width;
        var height = channels.getFirst().height;
        var groupsX = ceilDiv(width, groupDim);
        var groupsY = ceilDiv(height, groupDim);
        var numGroups = groupsX * groupsY;
        var costCheck = effort >= 8;
        var global = new ModularEncoder.Stream(0, channels);
        global.bitDepth(bitDepth);
        ForwardTransforms.tryPalettes(global, bitDepth, 1 << 10, effort >= 2 ? 95 : 0, costCheck);
        var nonMeta = global.channels().size() - global.nbMeta();
        if (colorChannels == 3 && nonMeta >= 3 && effort <= 4 && sameSize(global, global.nbMeta(), 3)) {
            ForwardTransforms.rct(global, global.nbMeta(), 6);
        }
        var rctTries = switch (effort) {
            case 5 -> 4;
            case 6 -> 5;
            case 7 -> 7;
            case 8 -> 9;
            case 9, 10 -> 19;
            default -> 0;
        };
        var groupStreams = new ModularEncoder.Stream[numGroups];
        if (numGroups == 1) {
            if (colorChannels == 3 && global.channels().size() - global.nbMeta() >= 3
                    && sameSize(global, global.nbMeta(), 3)) {
                ForwardTransforms.searchRct(global, global.nbMeta(), rctTries);
            }
            return new Prepared(global, groupStreams);
        }
        var all = global.channels();
        var start = 0;
        while (start < all.size()) {
            var c = all.get(start);
            if (start >= global.nbMeta() && (c.width > groupDim || c.height > groupDim)) {
                break;
            }
            start++;
        }
        global.codeOnly(start);
        for (var g = 0; g < numGroups; g++) {
            var gx = g % groupsX;
            var gy = g / groupsX;
            var sub = new ArrayList<ModularChannel>();
            for (var i = start; i < all.size(); i++) {
                var c = all.get(i);
                var gw = groupDim >> c.hshift;
                var gh = groupDim >> c.vshift;
                var x0 = gx * gw;
                var y0 = gy * gh;
                if (x0 >= c.width || y0 >= c.height) {
                    continue;
                }
                var w = Math.min(gw, c.width - x0);
                var h = Math.min(gh, c.height - y0);
                var crop = new ModularChannel(w, h, c.hshift, c.vshift);
                crop.data = new int[w * h];
                for (var y = 0; y < h; y++) {
                    System.arraycopy(c.data, (y0 + y) * c.width + x0, crop.data, y * w, w);
                }
                sub.add(crop);
            }
            if (sub.isEmpty()) {
                continue;
            }
            var stream = new ModularEncoder.Stream(1 + 3 * numLf + 17 + g, sub);
            stream.bitDepth(bitDepth);
            if (effort >= 5) {
                ForwardTransforms.tryPalettes(stream, bitDepth, 1 << 10, 80, costCheck);
            }
            if (colorChannels == 3 && start == global.nbMeta() && stream.channels().size() - stream.nbMeta() >= 3
                    && sameSize(stream, stream.nbMeta(), 3)) {
                ForwardTransforms.searchRct(stream, stream.nbMeta(), rctTries);
            }
            groupStreams[g] = stream;
        }
        return new Prepared(global, groupStreams);
    }

    private static boolean sameSize(ModularEncoder.Stream stream, int begin, int count) {
        var channels = stream.channels();
        if (begin + count > channels.size()) {
            return false;
        }
        var first = channels.get(begin);
        for (var i = begin + 1; i < begin + count; i++) {
            var c = channels.get(i);
            if (c.width != first.width || c.height != first.height || c.hshift != first.hshift
                    || c.vshift != first.vshift) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the tree settings libjxl uses for lossless modular coding at an effort.
     *
     * @param effort the effort, from 1 to 10
     * @param numStreams the number of streams of the frame
     * @param firstStream the first stream index of the range
     * @return the settings
     */
    static ModularEncoder.TreeSettings treeSettings(int effort, int numStreams, int firstStream) {
        if (effort <= 2) {
            return ModularEncoder.TreeSettings.fixed(firstStream, ModularEncoder.TREE_GRADIENT_FIXED);
        }
        if (effort == 3) {
            return ModularEncoder.TreeSettings.fixed(firstStream, ModularEncoder.TREE_WP_FIXED);
        }
        var tier = 10 - effort;
        var order = new ArrayList<Integer>();
        for (var p : PROPERTY_ORDER) {
            order.add(p);
        }
        if (numStreams < 30 && effort < 9) {
            order.remove(1);
        }
        int count;
        int values;
        switch (effort) {
            case 5 -> {
                count = 4;
                values = 24;
            }
            case 6 -> {
                count = 5;
                values = 32;
            }
            case 7 -> {
                count = 7;
                values = 48;
            }
            case 8 -> {
                count = 10;
                values = 96;
            }
            case 9, 10 -> {
                count = order.size();
                values = 256;
            }
            default -> {
                count = 3;
                values = 16;
            }
        }
        var props = new int[Math.min(count, order.size())];
        for (var i = 0; i < props.length; i++) {
            props[i] = order.get(i);
        }
        int[] predictors;
        if (effort >= 10) {
            predictors = new int[14];
            for (var i = 0; i < 14; i++) {
                predictors[i] = i;
            }
            predictors[0] = 6;
            predictors[6] = 0;
            predictors[1] = 5;
            predictors[5] = 1;
        } else {
            predictors = new int[]{6, 5};
        }
        return new ModularEncoder.TreeSettings(firstStream, ModularEncoder.TREE_LEARN, predictors, props, values,
                82 + 14 * tier, 0.5f, 1.01f);
    }

    static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
