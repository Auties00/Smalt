package com.github.auties00.smalt.image.exr;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Builds and edits OpenEXR files for the tests.
 */
final class ExrFiles {
    private ExrFiles() {
    }

    private static void attribute(ByteArrayOutputStream out, String name, String type, byte[] value) {
        out.writeBytes((name + "\0" + type + "\0").getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.length).array());
        out.writeBytes(value);
    }

    private static byte[] box(int xMin, int yMin, int xMax, int yMax) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(xMin).putInt(yMin).putInt(xMax)
                .putInt(yMax).array();
    }

    /**
     * Builds an uncompressed scanline file of half {@code Y} samples with zero {@code RY} and {@code BY} chroma
     * sampled every other pixel and row.
     */
    static byte[] luminanceChroma(int width, int height, float[] luminance) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x76, 0x2F, 0x31, 0x01, 2, 0, 0, 0});
        var channels = new ByteArrayOutputStream();
        for (var name : new String[]{"BY", "RY", "Y"}) {
            channels.writeBytes((name + "\0").getBytes(StandardCharsets.ISO_8859_1));
            var sampling = name.equals("Y") ? 1 : 2;
            channels.writeBytes(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(0)
                    .putInt(sampling).putInt(sampling).array());
        }
        channels.write(0);
        attribute(out, "channels", "chlist", channels.toByteArray());
        attribute(out, "compression", "compression", new byte[]{0});
        attribute(out, "dataWindow", "box2i", box(0, 0, width - 1, height - 1));
        attribute(out, "displayWindow", "box2i", box(0, 0, width - 1, height - 1));
        attribute(out, "lineOrder", "lineOrder", new byte[]{0});
        out.write(0);
        var tableStart = out.size();
        var chunks = new ByteArrayOutputStream();
        var offsets = new long[height];
        for (var y = 0; y < height; y++) {
            offsets[y] = tableStart + 8L * height + chunks.size();
            var chroma = y % 2 == 0 ? width / 2 : 0;
            var size = 2 * chroma * 2 + 2 * width;
            var line = ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN);
            line.putInt(y).putInt(size);
            for (var sample = 0; sample < 2 * chroma; sample++) {
                line.putShort((short) 0);
            }
            for (var x = 0; x < width; x++) {
                line.putShort(Float.floatToFloat16(luminance[y * width + x]));
            }
            chunks.writeBytes(line.array());
        }
        var table = ByteBuffer.allocate(8 * height).order(ByteOrder.LITTLE_ENDIAN);
        for (var offset : offsets) {
            table.putLong(offset);
        }
        out.writeBytes(table.array());
        out.writeBytes(chunks.toByteArray());
        return out.toByteArray();
    }

    /**
     * Returns a copy of a file whose compression attribute holds another method.
     */
    static byte[] withCompression(byte[] file, int method) {
        var marker = "compression\0compression\0".getBytes(StandardCharsets.ISO_8859_1);
        for (var at = 0; at + marker.length + 5 <= file.length; at++) {
            if (java.util.Arrays.equals(file, at, at + marker.length, marker, 0, marker.length)) {
                var copy = file.clone();
                copy[at + marker.length + 4] = (byte) method;
                return copy;
            }
        }
        throw new AssertionError("no compression attribute");
    }
}
