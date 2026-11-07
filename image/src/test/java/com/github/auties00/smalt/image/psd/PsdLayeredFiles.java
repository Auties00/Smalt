package com.github.auties00.smalt.image.psd;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds layered Photoshop documents for the tests: layer records with their channels, masks, groups and tagged
 * blocks, saved without a merged image unless one is given.
 */
final class PsdLayeredFiles {
    private final int version;
    private final int width;
    private final int height;
    private final int depth;
    private final int mode;
    private final int colors;
    private final List<Layer> layers = new ArrayList<>();
    private boolean transparentComposite = true;
    private boolean tagged;
    private int compression = 1;
    private int extraAlphaChannels;
    private byte[][] composite;

    PsdLayeredFiles(int version, int width, int height, int depth, int mode) {
        this.version = version;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mode = mode;
        this.colors = switch (mode) {
            case 3, 9 -> 3;
            case 4 -> 4;
            default -> 1;
        };
    }

    /** One layer record. */
    static final class Layer {
        String name = "Layer";
        int top;
        int left;
        int bottom;
        int right;
        String blend = "norm";
        int opacity = 255;
        int fill = 255;
        boolean clipping;
        boolean hidden;
        int divider;
        String dividerBlend;
        final Map<Integer, byte[]> channels = new LinkedHashMap<>();
        byte[] mask;
        final Map<String, byte[]> blocks = new LinkedHashMap<>();
        byte[] ranges;

        Layer rect(int top, int left, int bottom, int right) {
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            return this;
        }

        Layer blend(String key) {
            this.blend = key;
            return this;
        }

        Layer opacity(int opacity) {
            this.opacity = opacity;
            return this;
        }

        Layer fill(int fill) {
            this.fill = fill;
            blocks.put("iOpa", new byte[]{(byte) fill, 0, 0, 0});
            return this;
        }

        Layer clipping(boolean clipping) {
            this.clipping = clipping;
            return this;
        }

        Layer hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        Layer name(String name) {
            this.name = name;
            return this;
        }

        /** Sets a channel's stored plane, in the document's sample format, over the layer rectangle. */
        Layer channel(int id, byte[] plane) {
            channels.put(id, plane);
            return this;
        }

        /** Sets a user mask over {@code rect} (top, left, bottom, right) with its plane. */
        Layer mask(int[] rect, int background, int flags, byte[] plane, Integer density) {
            channels.put(-2, plane);
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            try {
                for (var value : rect) {
                    data.writeInt(value);
                }
                data.writeByte(background);
                data.writeByte(flags | (density != null ? 16 : 0));
                if (density != null) {
                    data.writeByte(1);
                    data.writeByte(density);
                }
                while (out.size() < 20) {
                    data.writeByte(0);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            this.mask = out.toByteArray();
            return this;
        }

        /** Sets the raw layer mask data block. */
        Layer maskData(byte[] data) {
            this.mask = data;
            return this;
        }

        Layer block(String key, byte[] body) {
            blocks.put(key, body);
            return this;
        }

        Layer ranges(byte[] ranges) {
            this.ranges = ranges;
            return this;
        }
    }

    /** Adds a layer above the previous ones. */
    PsdLayeredFiles add(Layer layer) {
        layers.add(layer);
        return this;
    }

    /** Opens a group: its layers follow, then {@link #close(String, String, int)}. */
    PsdLayeredFiles open() {
        var divider = new Layer().name("</Layer group>");
        divider.divider = 3;
        divider.blocks.put("lsct", new byte[]{0, 0, 0, 3});
        layers.add(divider);
        return this;
    }

    /** Closes the innermost group with its blend key ({@code pass} for pass-through) and opacity. */
    PsdLayeredFiles close(String name, String blend, int opacity) {
        return close(new Layer().name(name).opacity(opacity), blend);
    }

    /** Closes the innermost group with its own record (masks and blocks included). */
    PsdLayeredFiles close(Layer group, String blend) {
        group.divider = 1;
        var block = new ByteArrayOutputStream();
        block.writeBytes(new byte[]{0, 0, 0, 1});
        block.writeBytes("8BIM".getBytes(StandardCharsets.ISO_8859_1));
        block.writeBytes(blend.getBytes(StandardCharsets.ISO_8859_1));
        group.blocks.put("lsct", block.toByteArray());
        group.blend = blend.equals("pass") ? "norm" : blend;
        layers.add(group);
        return this;
    }

    /** Declares a positive layer count, as documents whose bottom layer is an opaque background do. */
    PsdLayeredFiles opaqueComposite() {
        this.transparentComposite = false;
        return this;
    }

    /** Stores the layer info in a {@code Lr16} block, as Photoshop does for 16 and 32-bit documents. */
    PsdLayeredFiles tagged() {
        this.tagged = true;
        return this;
    }

    PsdLayeredFiles compression(int compression) {
        this.compression = compression;
        return this;
    }

    /** Adds alpha channels (saved selections) to the image data, each filled with a constant. */
    PsdLayeredFiles extraAlphaChannels(int count) {
        this.extraAlphaChannels = count;
        return this;
    }

    /** Saves a merged image, marking the document as having one. */
    PsdLayeredFiles composite(byte[]... planes) {
        this.composite = planes;
        return this;
    }

    int bytesPerSample() {
        return depth / 8;
    }

    byte[] build() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            var imageChannels = colors + (transparentComposite ? 1 : 0) + extraAlphaChannels;
            out.writeBytes("8BPS");
            out.writeShort(version);
            out.write(new byte[6]);
            out.writeShort(imageChannels);
            out.writeInt(height);
            out.writeInt(width);
            out.writeShort(depth);
            out.writeShort(mode);
            out.writeInt(0);
            var resources = new ByteArrayOutputStream();
            var resourceOut = new DataOutputStream(resources);
            resourceOut.writeBytes("8BIM");
            resourceOut.writeShort(1057);
            resourceOut.writeShort(0);
            resourceOut.writeInt(18);
            resourceOut.writeInt(1);
            resourceOut.writeByte(composite != null ? 1 : 0);
            resourceOut.writeInt(0);
            resourceOut.writeInt(0);
            resourceOut.writeInt(1);
            resourceOut.writeByte(0);
            resourceOut.writeByte(0);
            out.writeInt(resources.size());
            resources.writeTo(out);
            var info = layerInfo();
            var lengthSize = version == 2 ? 8 : 4;
            var section = new ByteArrayOutputStream();
            var sectionOut = new DataOutputStream(section);
            if (tagged) {
                writeLength(sectionOut, 0);
                sectionOut.writeInt(0);
                sectionOut.writeBytes("8BIMLr16");
                var padded = (info.length + 3) & ~3;
                writeLength(sectionOut, padded);
                sectionOut.write(info);
                sectionOut.write(new byte[padded - info.length]);
            } else {
                var padded = (info.length + 1) & ~1;
                writeLength(sectionOut, padded);
                sectionOut.write(info);
                sectionOut.write(new byte[padded - info.length]);
                sectionOut.writeInt(0);
            }
            writeLength(out, section.size());
            section.writeTo(out);
            out.writeShort(0);
            var planeBytes = width * height * bytesPerSample();
            for (var channel = 0; channel < imageChannels; channel++) {
                if (composite != null && channel < composite.length) {
                    out.write(composite[channel]);
                } else {
                    var plane = new byte[planeBytes];
                    java.util.Arrays.fill(plane, (byte) (channel >= colors + (transparentComposite ? 1 : 0)
                            ? 0x40 + channel : 0));
                    out.write(plane);
                }
            }
            if (lengthSize == 0) {
                throw new AssertionError();
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void writeLength(DataOutputStream out, long length) throws IOException {
        if (version == 2) {
            out.writeLong(length);
        } else {
            out.writeInt((int) length);
        }
    }

    private byte[] layerInfo() throws IOException {
        var records = new ByteArrayOutputStream();
        var recordsOut = new DataOutputStream(records);
        var data = new ByteArrayOutputStream();
        for (var layer : layers) {
            var channelData = new ArrayList<byte[]>();
            for (var entry : layer.channels.entrySet()) {
                var id = entry.getKey();
                int rowWidth;
                int rows;
                if (id == -2 && layer.mask != null) {
                    var mask = java.nio.ByteBuffer.wrap(layer.mask);
                    var top = mask.getInt(0);
                    var left = mask.getInt(4);
                    var bottom = mask.getInt(8);
                    var right = mask.getInt(12);
                    rowWidth = right - left;
                    rows = bottom - top;
                } else {
                    rowWidth = layer.right - layer.left;
                    rows = layer.bottom - layer.top;
                }
                channelData.add(encode(entry.getValue(), rowWidth * bytesPerSample(), rows));
            }
            recordsOut.writeInt(layer.top);
            recordsOut.writeInt(layer.left);
            recordsOut.writeInt(layer.bottom);
            recordsOut.writeInt(layer.right);
            recordsOut.writeShort(layer.channels.size());
            var index = 0;
            for (var id : layer.channels.keySet()) {
                recordsOut.writeShort(id);
                if (version == 2) {
                    recordsOut.writeLong(channelData.get(index).length);
                } else {
                    recordsOut.writeInt(channelData.get(index).length);
                }
                index++;
            }
            recordsOut.writeBytes("8BIM");
            recordsOut.writeBytes(layer.blend);
            recordsOut.writeByte(layer.opacity);
            recordsOut.writeByte(layer.clipping ? 1 : 0);
            recordsOut.writeByte((layer.hidden ? 2 : 0) | 8);
            recordsOut.writeByte(0);
            var extra = new ByteArrayOutputStream();
            var extraOut = new DataOutputStream(extra);
            var mask = layer.mask == null ? new byte[0] : layer.mask;
            extraOut.writeInt(mask.length);
            extraOut.write(mask);
            var ranges = layer.ranges;
            if (ranges == null) {
                ranges = new byte[8 * (colors + 1)];
                for (var at = 0; at < ranges.length; at += 4) {
                    ranges[at + 2] = (byte) 255;
                    ranges[at + 3] = (byte) 255;
                }
            }
            extraOut.writeInt(ranges.length);
            extraOut.write(ranges);
            var name = layer.name.getBytes(StandardCharsets.ISO_8859_1);
            var nameLength = (1 + name.length + 3) & ~3;
            extraOut.writeByte(name.length);
            extraOut.write(name);
            extraOut.write(new byte[nameLength - 1 - name.length]);
            for (var block : layer.blocks.entrySet()) {
                extraOut.writeBytes("8BIM");
                extraOut.writeBytes(block.getKey());
                var body = block.getValue();
                var padded = (body.length + 1) & ~1;
                extraOut.writeInt(padded);
                extraOut.write(body);
                extraOut.write(new byte[padded - body.length]);
            }
            recordsOut.writeInt(extra.size());
            extra.writeTo(recordsOut);
            for (var channel : channelData) {
                data.write(channel);
            }
        }
        var info = new ByteArrayOutputStream();
        var infoOut = new DataOutputStream(info);
        infoOut.writeShort(transparentComposite ? -layers.size() : layers.size());
        records.writeTo(infoOut);
        data.writeTo(infoOut);
        return info.toByteArray();
    }

    private byte[] encode(byte[] plane, int rowBytes, int rows) throws IOException {
        var out = new ByteArrayOutputStream();
        var data = new DataOutputStream(out);
        data.writeShort(compression);
        switch (compression) {
            case 0 -> data.write(plane);
            case 1 -> {
                var packed = new byte[rows][];
                for (var y = 0; y < rows; y++) {
                    packed[y] = PsdFiles.packBits(plane, y * rowBytes, rowBytes);
                }
                for (var row : packed) {
                    if (version == 2) {
                        data.writeInt(row.length);
                    } else {
                        data.writeShort(row.length);
                    }
                }
                for (var row : packed) {
                    data.write(row);
                }
            }
            default -> {
                var deflater = new java.util.zip.Deflater();
                var source = compression == 3 ? predict(plane, rowBytes, rows) : plane;
                deflater.setInput(source);
                deflater.finish();
                var buffer = new byte[4096];
                while (!deflater.finished()) {
                    data.write(buffer, 0, deflater.deflate(buffer));
                }
                deflater.end();
            }
        }
        return out.toByteArray();
    }

    private byte[] predict(byte[] plane, int rowBytes, int rows) {
        var out = plane.clone();
        var columns = rowBytes / bytesPerSample();
        for (var y = 0; y < rows; y++) {
            var base = y * rowBytes;
            switch (depth) {
                case 16 -> {
                    for (var x = columns - 1; x > 0; x--) {
                        var at = base + 2 * x;
                        var value = ((plane[at] & 0xFF) << 8 | (plane[at + 1] & 0xFF))
                                - ((plane[at - 2] & 0xFF) << 8 | (plane[at - 1] & 0xFF));
                        out[at] = (byte) (value >>> 8);
                        out[at + 1] = (byte) value;
                    }
                }
                case 32 -> {
                    var shuffled = new byte[rowBytes];
                    for (var x = 0; x < columns; x++) {
                        for (var b = 0; b < 4; b++) {
                            shuffled[b * columns + x] = plane[base + 4 * x + b];
                        }
                    }
                    for (var x = rowBytes - 1; x > 0; x--) {
                        out[base + x] = (byte) (shuffled[x] - shuffled[x - 1]);
                    }
                    if (rowBytes > 0) {
                        out[base] = shuffled[0];
                    }
                }
                default -> {
                    for (var x = rowBytes - 1; x > 0; x--) {
                        out[base + x] = (byte) (plane[base + x] - plane[base + x - 1]);
                    }
                }
            }
        }
        return out;
    }
}
