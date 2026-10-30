package com.github.auties00.smalt.image.dds;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Builds DDS files for the tests: legacy and DX10 headers around given surface data.
 */
final class DdsFiles {
    static final int DDPF_ALPHAPIXELS = 0x1;
    static final int DDPF_ALPHA = 0x2;
    static final int DDPF_FOURCC = 0x4;
    static final int DDPF_RGB = 0x40;
    static final int DDPF_LUMINANCE = 0x20000;
    static final int DDPF_BUMPDUDV = 0x80000;

    private DdsFiles() {
    }

    static int fourCc(String code) {
        return code.charAt(0) | code.charAt(1) << 8 | code.charAt(2) << 16 | code.charAt(3) << 24;
    }

    /** A legacy header. */
    static byte[] legacy(int width, int height, int mipmaps, int pixelFlags, int fourCc, int bits, long[] masks,
                         int caps2, int depth, byte[] data) {
        var buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'D').put((byte) 'D').put((byte) 'S').put((byte) ' ');
        buffer.putInt(124);
        buffer.putInt(0x1007 | (mipmaps > 1 ? 0x20000 : 0) | (depth > 1 ? 0x800000 : 0));
        buffer.putInt(height);
        buffer.putInt(width);
        buffer.putInt(0);
        buffer.putInt(depth);
        buffer.putInt(mipmaps);
        buffer.position(76);
        buffer.putInt(32);
        buffer.putInt(pixelFlags);
        buffer.putInt(fourCc);
        buffer.putInt(bits);
        for (var mask : masks) {
            buffer.putInt((int) mask);
        }
        buffer.putInt(0x1000);
        buffer.putInt(caps2);
        var out = new ByteArrayOutputStream();
        out.writeBytes(buffer.array());
        out.writeBytes(data);
        return out.toByteArray();
    }

    /** A legacy header of a four-character code. */
    static byte[] fourCcFile(String code, int width, int height, byte[] data) {
        return legacy(width, height, 1, DDPF_FOURCC, fourCc(code), 0, new long[4], 0, 1, data);
    }

    /** A DX10 header. */
    static byte[] dx10(int width, int height, int mipmaps, int dxgi, int dimension, int misc, int arraySize,
                       int alphaMode, int depth, byte[] data) {
        var header = legacy(width, height, mipmaps, DDPF_FOURCC, fourCc("DX10"), 0, new long[4], 0, depth,
                new byte[0]);
        var extension = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        extension.putInt(dxgi).putInt(dimension).putInt(misc).putInt(arraySize).putInt(alphaMode);
        var out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(extension.array());
        out.writeBytes(data);
        return out.toByteArray();
    }

    /** A 2D DX10 file of one surface. */
    static byte[] dx10(int width, int height, int dxgi, byte[] data) {
        return dx10(width, height, 1, dxgi, 3, 0, 1, 0, 1, data);
    }

    /** Random bytes. */
    static byte[] random(int length, long seed) {
        var data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }
}
