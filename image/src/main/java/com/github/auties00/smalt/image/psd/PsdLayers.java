package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the layer records of a Photoshop document into a tree of layers and groups.
 *
 * <p>The records of the layer info are stored bottom to top; a group is the run of records between a bounding
 * section divider and the record that opens the folder. Channel image data is located but not decoded.
 */
final class PsdLayers {
    /** The deepest group nesting accepted. */
    static final int MAX_NESTING = 256;

    /** The most layer records accepted. */
    static final int MAX_LAYERS = 1 << 20;

    /** The longest side of a layer or mask rectangle accepted, far above the 300,000 pixels of a PSB. */
    static final int MAX_SIDE = 1 << 24;

    /** The transparency channel id. */
    static final int TRANSPARENCY = -1;

    /** The user (or rendered vector) mask channel id. */
    static final int USER_MASK = -2;

    /** The real user mask channel id, present with a user mask and a vector mask together. */
    static final int REAL_USER_MASK = -3;

    private PsdLayers() {
        throw new AssertionError();
    }

    /**
     * A rectangle of document coordinates, the right and bottom edges exclusive.
     *
     * @param top the top edge
     * @param left the left edge
     * @param bottom the bottom edge
     * @param right the right edge
     */
    record Rect(int top, int left, int bottom, int right) {
        int width() {
            return Math.max(0, right - left);
        }

        int height() {
            return Math.max(0, bottom - top);
        }

        boolean isEmpty() {
            return right <= left || bottom <= top;
        }
    }

    /**
     * A layer mask: a plane of coverage over a rectangle and the coverage outside it.
     *
     * @param channel the id of the channel holding the plane
     * @param rect the rectangle the plane covers
     * @param background the coverage outside the rectangle, 0 or 255
     * @param invert whether the coverage is inverted when blending
     * @param density the share of the mask applied, from 0 to 1
     */
    record Mask(int channel, Rect rect, int background, boolean invert, double density) {
    }

    /**
     * A layer record, or a group built from a folder record and the records between it and its divider.
     */
    static final class Layer {
        String name = "";
        Rect rect;
        int[] channelIds = new int[0];
        long[] channelOffsets = new long[0];
        long[] channelLengths = new long[0];
        String blend = "norm";
        int opacity = 255;
        int fill = 255;
        boolean clipping;
        boolean hidden;
        boolean group;
        int divider;
        List<Mask> masks = new ArrayList<>();
        List<Layer> children = new ArrayList<>();
        String unsupported;
        boolean blendClippedAsGroup = true;
        boolean filled;
        Rect artboard;
        float[] artboardColor;

        boolean hasChannel(int id) {
            return channel(id) >= 0;
        }

        int channel(int id) {
            for (var index = 0; index < channelIds.length; index++) {
                if (channelIds[index] == id) {
                    return index;
                }
            }
            return -1;
        }
    }

    /**
     * Parses the layer info that starts at {@code position} (its signed layer count) into the document's layer
     * tree, bottom first.
     *
     * @param data the document
     * @param position the offset of the layer count
     * @param end the end of the layer info
     * @param large whether the document is a PSB
     * @param colors the number of color channels of the document's mode
     * @return the top-level layers and groups, bottom first
     */
    static List<Layer> parse(byte[] data, long position, long end, boolean large, int colors) {
        if (end > data.length || position + 2 > end) {
            throw new MediaException.Malformed("the Photoshop layer info is truncated");
        }
        var count = Math.abs((short) ByteArrays.u16be(data, (int) position));
        if (count > MAX_LAYERS) {
            throw new MediaException.Malformed("too many Photoshop layers: " + count);
        }
        var reader = new Reader(data, position + 2, end, large);
        var records = new ArrayList<Layer>(count);
        for (var index = 0; index < count; index++) {
            records.add(reader.record(colors));
        }
        for (var layer : records) {
            for (var channel = 0; channel < layer.channelIds.length; channel++) {
                var length = layer.channelLengths[channel];
                if (length < 0 || reader.position + length > end) {
                    throw new MediaException.Malformed("Photoshop layer channel data runs past the layer info");
                }
                layer.channelOffsets[channel] = reader.position;
                reader.position += length;
            }
        }
        return tree(records);
    }

    /**
     * Folds the bottom-to-top records into groups.
     */
    private static List<Layer> tree(List<Layer> records) {
        var stack = new ArrayList<List<Layer>>();
        stack.add(new ArrayList<>());
        for (var layer : records) {
            switch (layer.divider) {
                case 3 -> {
                    if (stack.size() > MAX_NESTING) {
                        throw new MediaException.Malformed("Photoshop groups nest too deeply");
                    }
                    stack.add(new ArrayList<>());
                }
                case 1, 2 -> {
                    layer.group = true;
                    if (stack.size() > 1) {
                        layer.children = stack.removeLast();
                    }
                    stack.getLast().add(layer);
                }
                default -> stack.getLast().add(layer);
            }
        }
        while (stack.size() > 1) {
            var orphans = stack.removeLast();
            stack.getLast().addAll(orphans);
        }
        return stack.getFirst();
    }

    /**
     * Reads layer records.
     */
    private static final class Reader {
        private final byte[] data;
        private final long end;
        private final boolean large;
        long position;

        Reader(byte[] data, long position, long end, boolean large) {
            this.data = data;
            this.position = position;
            this.end = end;
            this.large = large;
        }

        Layer record(int colors) {
            var layer = new Layer();
            need(18);
            layer.rect = rect(position);
            var channels = ByteArrays.u16be(data, (int) position + 16);
            position += 18;
            if (channels > 64) {
                throw new MediaException.Malformed("a Photoshop layer has " + channels + " channels");
            }
            layer.channelIds = new int[channels];
            layer.channelOffsets = new long[channels];
            layer.channelLengths = new long[channels];
            var entry = large ? 10 : 6;
            need((long) channels * entry);
            for (var index = 0; index < channels; index++) {
                layer.channelIds[index] = (short) ByteArrays.u16be(data, (int) position);
                layer.channelLengths[index] = large ? ByteArrays.i64be(data, (int) position + 2)
                        : ByteArrays.u32be(data, (int) position + 2);
                position += entry;
            }
            need(16);
            if (!signature(position, "8BIM")) {
                throw new MediaException.Malformed("a Photoshop layer record has no blend mode signature");
            }
            layer.blend = text(position + 4, 4);
            layer.opacity = data[(int) position + 8] & 0xFF;
            layer.clipping = data[(int) position + 9] != 0;
            layer.hidden = (data[(int) position + 10] & 2) != 0;
            var extra = ByteArrays.u32be(data, (int) position + 12);
            position += 16;
            var extraEnd = position + extra;
            if (extraEnd > end) {
                throw new MediaException.Malformed("a Photoshop layer record runs past the layer info");
            }
            var maskLength = u32(position);
            var maskStart = position + 4;
            if (maskStart + maskLength > extraEnd) {
                throw new MediaException.Malformed("Photoshop layer mask data runs past its record");
            }
            position = maskStart + maskLength;
            var rangesLength = u32(position);
            var rangesStart = position + 4;
            if (rangesStart + rangesLength > extraEnd) {
                throw new MediaException.Malformed("Photoshop blending ranges run past their record");
            }
            if (!defaultRanges(rangesStart, rangesLength)) {
                layer.unsupported = "Blend If ranges";
            }
            position = rangesStart + rangesLength;
            if (position + 1 > extraEnd) {
                throw new MediaException.Malformed("a Photoshop layer record has no name");
            }
            var nameLength = data[(int) position] & 0xFF;
            if (position + 1 + nameLength > extraEnd) {
                throw new MediaException.Malformed("a Photoshop layer name runs past its record");
            }
            layer.name = new String(data, (int) position + 1, nameLength, StandardCharsets.ISO_8859_1);
            position += (1 + nameLength + 3) & ~3;
            var vectorMask = false;
            while (position + 12 <= extraEnd) {
                if (!signature(position, "8BIM") && !signature(position, "8B64")) {
                    break;
                }
                var key = text(position + 4, 4);
                var wide = large && PsdDecoder.wideKey(key);
                var length = wide ? ByteArrays.i64be(data, (int) position + 8) : u32(position + 8);
                var body = position + 8 + (wide ? 8 : 4);
                if (length < 0 || body + length > extraEnd) {
                    throw new MediaException.Malformed("Photoshop layer block '" + key + "' runs past its record");
                }
                vectorMask |= block(layer, key, (int) body, (int) length);
                position = body + length;
            }
            position = extraEnd;
            masks(layer, maskStart, maskLength, vectorMask);
            if (vectorMask && !layer.filled && layer.masks.stream().noneMatch(mask -> mask.channel() == USER_MASK)) {
                unsupported(layer, "vector masks stored without their rendering");
            }
            if (layer.divider == 0 && (layer.filled || !layer.rect.isEmpty())) {
                for (var channel = 0; channel < colors; channel++) {
                    var index = layer.channel(channel);
                    if (index < 0 || layer.channelLengths[index] <= 2 || layer.rect.isEmpty()) {
                        // Photoshop leaves the pixels of fill and shape layers out of 16 and 32-bit documents.
                        unsupported(layer, layer.filled ? "fill or shape layers saved without their pixels"
                                : "layers saved without their pixels");
                        break;
                    }
                }
            }
            return layer;
        }

        /**
         * Reads one tagged block of a layer record, returning whether it is an enabled vector mask.
         */
        private boolean block(Layer layer, String key, int body, int length) {
            switch (key) {
                case "lsct", "lsdk" -> {
                    if (length >= 4) {
                        layer.divider = (int) ByteArrays.u32be(data, body);
                    }
                    if (length >= 12 && signature(body + 4, "8BIM")) {
                        layer.blend = text(body + 8, 4);
                    }
                }
                case "iOpa" -> {
                    if (length >= 1) {
                        layer.fill = data[body] & 0xFF;
                    }
                }
                case "knko" -> {
                    if (length >= 1 && data[body] != 0) {
                        unsupported(layer, "knockout");
                    }
                }
                case "clbl" -> {
                    if (length >= 1) {
                        layer.blendClippedAsGroup = data[body] != 0;
                    }
                }
                case "brst" -> {
                    if (length >= 4) {
                        unsupported(layer, "channel restrictions");
                    }
                }
                case "vmsk", "vsms" -> {
                    return length >= 8 && (ByteArrays.u32be(data, body + 4) & 4) == 0;
                }
                case "levl", "curv", "brit", "CgEd", "expA", "vibA", "hue ", "hue2", "blnc", "blwh", "phfl", "mixr",
                     "clrL", "nvrt", "post", "thrs", "grdm", "selc" -> unsupported(layer, "adjustment layers");
                case "lrFX" -> {
                    if (PsdEffects.legacyEnabled(data, body, body + length)) {
                        unsupported(layer, "layer effects");
                    }
                }
                case "lfx2", "lmfx" -> {
                    try {
                        if (PsdEffects.enabled(data, body, body + length)) {
                            unsupported(layer, "layer effects");
                        }
                    } catch (MediaException.Malformed exception) {
                        unsupported(layer, "layer effects whose description it cannot read");
                    }
                }
                case "artb", "artd", "abdd" -> {
                    try {
                        artboard(layer, body, length);
                    } catch (MediaException.Malformed exception) {
                        unsupported(layer, "artboards whose description it cannot read");
                    }
                }
                case "SoCo", "GdFl", "PtFl", "vscg" -> layer.filled = true;
                default -> {
                }
            }
            return false;
        }

        private void artboard(Layer layer, int body, int length) {
            Map<String, Object> items = PsdDescriptor.versioned(data, body, body + length);
            var rect = PsdDescriptor.object(items, "artboardRect");
            if (rect == null) {
                return;
            }
            layer.artboard = new Rect((int) Math.round(PsdDescriptor.number(rect, "Top ", 0)),
                    (int) Math.round(PsdDescriptor.number(rect, "Left", 0)),
                    (int) Math.round(PsdDescriptor.number(rect, "Btom", 0)),
                    (int) Math.round(PsdDescriptor.number(rect, "Rght", 0)));
            var type = (int) PsdDescriptor.number(items, "artboardBackgroundType", 1);
            layer.artboardColor = switch (type) {
                case 1 -> new float[]{1, 1, 1};
                case 2 -> new float[]{0, 0, 0};
                case 3 -> null;
                default -> {
                    var color = PsdDescriptor.object(items, "Clr ");
                    if (color == null) {
                        yield new float[]{1, 1, 1};
                    }
                    yield new float[]{(float) (PsdDescriptor.number(color, "Rd  ", 255) / 255),
                            (float) (PsdDescriptor.number(color, "Grn ", 255) / 255),
                            (float) (PsdDescriptor.number(color, "Bl  ", 255) / 255)};
                }
            };
        }

        /**
         * Reads the layer mask data: the user mask, and the real user mask that accompanies a vector mask.
         */
        private void masks(Layer layer, long start, long length, boolean vectorMask) {
            if (length < 18) {
                return;
            }
            var rect = rect(start);
            var background = data[(int) start + 16] & 0xFF;
            var flags = data[(int) start + 17] & 0xFF;
            var at = start + 18;
            var real = layer.hasChannel(REAL_USER_MASK) && length >= 36;
            Rect realRect = null;
            var realFlags = 0;
            var realBackground = 0;
            if (real) {
                realFlags = data[(int) at] & 0xFF;
                realBackground = data[(int) at + 1] & 0xFF;
                realRect = rect(at + 2);
                at += 18;
            }
            var userDensity = 1.0;
            var vectorDensity = 1.0;
            if ((flags & 16) != 0 && at < start + length) {
                var limit = start + length;
                var parameters = data[(int) at++] & 0xFF;
                if ((parameters & 1) != 0) {
                    userDensity = maskByte(at++, limit) / 255.0;
                }
                if ((parameters & 2) != 0) {
                    if (maskDouble(at, limit) != 0) {
                        unsupported(layer, "feathered masks");
                    }
                    at += 8;
                }
                if ((parameters & 4) != 0) {
                    vectorDensity = maskByte(at++, limit) / 255.0;
                }
                if ((parameters & 8) != 0) {
                    if (maskDouble(at, limit) != 0) {
                        unsupported(layer, "feathered masks");
                    }
                }
            }
            if (real) {
                // With a vector mask beside the user mask, -2 holds both rendered together and -3 the user mask.
                if (!vectorMask) {
                    if ((realFlags & 2) == 0) {
                        layer.masks.add(new Mask(REAL_USER_MASK, realRect, realBackground, (realFlags & 4) != 0,
                                userDensity));
                    }
                } else if ((realFlags & 2) != 0) {
                    unsupported(layer, "a disabled user mask beside a vector mask");
                } else if (userDensity < 1 || vectorDensity < 1) {
                    unsupported(layer, "mask densities beside both a user and a vector mask");
                } else if (layer.hasChannel(USER_MASK)) {
                    layer.masks.add(new Mask(USER_MASK, rect, background, false, 1));
                }
            } else if (layer.hasChannel(USER_MASK)) {
                if ((flags & 8) != 0) {
                    if (vectorMask) {
                        layer.masks.add(new Mask(USER_MASK, rect, background, false, vectorDensity));
                    }
                } else if ((flags & 2) == 0) {
                    layer.masks.add(new Mask(USER_MASK, rect, background, (flags & 4) != 0, userDensity));
                }
            }
        }

        private int maskByte(long at, long limit) {
            if (at + 1 > limit) {
                throw new MediaException.Malformed("Photoshop mask parameters run past their block");
            }
            return data[(int) at] & 0xFF;
        }

        private double maskDouble(long at, long limit) {
            if (at + 8 > limit) {
                throw new MediaException.Malformed("Photoshop mask parameters run past their block");
            }
            return Double.longBitsToDouble(ByteArrays.i64be(data, (int) at));
        }

        private boolean defaultRanges(long start, long length) {
            for (var at = start; at + 8 <= start + length; at += 8) {
                for (var range = 0; range < 2; range++) {
                    var base = (int) at + 4 * range;
                    if (data[base] != 0 || data[base + 1] != 0 || (data[base + 2] & 0xFF) != 255
                            || (data[base + 3] & 0xFF) != 255) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Reads a rectangle, rejecting one whose sides are longer than {@link PsdLayers#MAX_SIDE}.
         */
        private Rect rect(long at) {
            var rect = new Rect(i32(at), i32(at + 4), i32(at + 8), i32(at + 12));
            if ((long) rect.bottom() - rect.top() > MAX_SIDE || (long) rect.right() - rect.left() > MAX_SIDE) {
                throw new MediaException.Malformed("a Photoshop layer rectangle is too large: " + rect);
            }
            return rect;
        }

        private boolean signature(long at, String signature) {
            return at + 4 <= end && data[(int) at] == signature.charAt(0) && data[(int) at + 1] == signature.charAt(1)
                    && data[(int) at + 2] == signature.charAt(2) && data[(int) at + 3] == signature.charAt(3);
        }

        private String text(long at, int length) {
            return new String(data, (int) at, length, StandardCharsets.ISO_8859_1);
        }

        private int i32(long at) {
            return ByteArrays.i32be(data, (int) at);
        }

        private long u32(long at) {
            if (at + 4 > end) {
                throw new MediaException.Malformed("a Photoshop layer record is truncated");
            }
            return ByteArrays.u32be(data, (int) at);
        }

        private void need(long count) {
            if (count < 0 || position + count > end) {
                throw new MediaException.Malformed("a Photoshop layer record is truncated");
            }
        }
    }

    private static void unsupported(Layer layer, String feature) {
        if (layer.unsupported == null) {
            layer.unsupported = feature;
        }
    }
}
