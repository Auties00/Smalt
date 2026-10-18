package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.YuvPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Test helpers: an IVF reader, frame MD5 in libvpx's {@code --md5} layout, raw I420 extraction,
 * and a minimal RIFF/WEBP wrapper for feeding VP8 key frames to dwebp and ffmpeg.
 */
final class Ivf {
    /** One IVF frame. */
    record Frame(long pts, byte[] data) {
    }

    /** A parsed IVF file. */
    record File(String fourcc, int width, int height, int rate, int scale, List<Frame> frames) {
    }

    private Ivf() {
        throw new AssertionError();
    }

    static File parse(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 32 || bytes[0] != 'D' || bytes[1] != 'K' || bytes[2] != 'I' || bytes[3] != 'F') {
            throw new IllegalArgumentException("not an IVF file");
        }
        var headerSize = buffer.getShort(6) & 0xFFFF;
        var fourcc = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
        var width = buffer.getShort(12) & 0xFFFF;
        var height = buffer.getShort(14) & 0xFFFF;
        var rate = buffer.getInt(16);
        var scale = buffer.getInt(20);
        var frames = new ArrayList<Frame>();
        var pos = headerSize;
        while (pos + 12 <= bytes.length) {
            var size = buffer.getInt(pos);
            var pts = buffer.getLong(pos + 4);
            pos += 12;
            if (size < 0 || pos + size > bytes.length) {
                break;
            }
            var data = new byte[size];
            System.arraycopy(bytes, pos, data, 0, size);
            frames.add(new Frame(pts, data));
            pos += size;
        }
        return new File(fourcc, width, height, rate, scale, frames);
    }

    static byte[] write(int width, int height, List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'D', 'K', 'I', 'F'}).putShort((short) 0).putShort((short) 32)
                .put(new byte[]{'V', 'P', '8', '0'}).putShort((short) width).putShort((short) height)
                .putInt(30).putInt(1).putInt(frames.size()).putInt(0);
        out.writeBytes(header.array());
        var pts = 0L;
        for (var frame : frames) {
            var fh = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(frame.length).putLong(pts++);
            out.writeBytes(fh.array());
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /** Returns the visible Y, U, V planes of a picture concatenated, as ffmpeg's yuv420p rawvideo. */
    static byte[] i420(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var cw = (w + 1) / 2;
        var ch = (h + 1) / 2;
        var out = new byte[w * h + 2 * cw * ch];
        var pos = 0;
        for (var r = 0; r < h; r++, pos += w) {
            System.arraycopy(picture.y(), r * picture.yStride(), out, pos, w);
        }
        for (var r = 0; r < ch; r++, pos += cw) {
            System.arraycopy(picture.u(), r * picture.uvStride(), out, pos, cw);
        }
        for (var r = 0; r < ch; r++, pos += cw) {
            System.arraycopy(picture.v(), r * picture.uvStride(), out, pos, cw);
        }
        return out;
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Wraps a VP8 key frame in a minimal simple-format WebP file. */
    static byte[] webp(byte[] vp8) {
        var pad = vp8.length & 1;
        var buffer = ByteBuffer.allocate(20 + vp8.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(12 + vp8.length + pad)
                .put(new byte[]{'W', 'E', 'B', 'P', 'V', 'P', '8', ' '}).putInt(vp8.length).put(vp8);
        return buffer.array();
    }

    /** Extracts the VP8 chunk payload of a simple-format lossy WebP file. */
    static byte[] vp8Chunk(byte[] webp) {
        var buffer = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        var pos = 12;
        while (pos + 8 <= webp.length) {
            var tag = new String(webp, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            var size = buffer.getInt(pos + 4);
            if (tag.equals("VP8 ")) {
                var out = new byte[size];
                System.arraycopy(webp, pos + 8, out, 0, size);
                return out;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("no VP8 chunk");
    }
}
