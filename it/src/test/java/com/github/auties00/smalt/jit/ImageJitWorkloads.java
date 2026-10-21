package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gif.GifDecoder;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.BlendMode;
import com.github.auties00.smalt.image.ops.BlurMethod;
import com.github.auties00.smalt.image.ops.CompositeOptions;
import com.github.auties00.smalt.image.ops.ImageAdjustments;
import com.github.auties00.smalt.image.ops.ImageCompositing;
import com.github.auties00.smalt.image.ops.ImageFilters;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Interpolation;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.ops.RotationCanvas;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

/**
 * The still-image workloads of {@link JitWorkload}: the JPEG send path, PNG and GIF decoding and
 * the color conversions, each on synthetic natural-looking content encoded once at start-up by
 * the module's own encoders (and a small GIF writer here), so they run without ffmpeg. One frame
 * is one image operation, or one frame of the animated GIF.
 */
final class ImageJitWorkloads {
    private static long sink;

    private ImageJitWorkloads() {
    }

    /** Returns an RGBA picture derived from the synthetic video, opaque or with a round alpha mask. */
    static RgbaPicture rgba(YuvPicture source, boolean alpha) {
        var width = source.width();
        var height = source.height();
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var luma = source.y()[y * source.yStride() + x] & 0xFF;
                var u = source.u()[(y >> 1) * source.uvStride() + (x >> 1)] & 0xFF;
                var o = (y * width + x) * 4;
                pixels[o] = (byte) luma;
                pixels[o + 1] = (byte) ((luma + u) >> 1);
                pixels[o + 2] = (byte) (255 - u);
                var distance = Math.hypot(x - width / 2.0, y - height / 2.0);
                pixels[o + 3] = alpha ? (byte) Math.max(0, Math.min(255, (int) ((height / 2.0 - distance) * 3))) : (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    /**
     * Runs the send path of a camera photo: a 2560x1440 JPEG decoded at half size through the
     * size hint, fitted within 1200 pixels with Lanczos and re-encoded at quality 80.
     */
    static JitWorkload.Workload jpegSend(long bound, int count) {
        var photo = JpegEncoder.encode(JitWorkload.synthesize(2560, 1440, 1).getFirst(), JpegEncoderOptions.ofQuality(92));
        var hint = ImageDecoderOptions.builder().minSize(1200, 1200 * 1440 / 2560).build();
        var output = JpegEncoderOptions.ofQuality(80);
        return JitWorkload.named("jpegsend", bound, () -> {
            for (var i = 0; i < count; i++) {
                var decoded = JpegDecoder.decode(MediaSource.of(photo), hint).picture();
                var fitted = ImageOps.fitWithin(decoded, 1200, ResizeFilter.LANCZOS3);
                sink += JpegEncoder.encode(fitted, output).length + decoded.width();
            }
            return count;
        });
    }

    /**
     * Decodes an opaque 1024x768 photo and a 512x512 sticker with alpha, written with a cycle of
     * row filters that uses every filter type both alone and in runs of two rows, and the photo as
     * the module's encoder writes it (adaptive filtering, which chooses Paeth for nearly every row
     * of this smooth content).
     */
    static JitWorkload.Workload pngDecode(long bound, int count) {
        var photoPicture = rgba(JitWorkload.synthesize(1024, 768, 1).getFirst(), false);
        var photo = cycledFilterPng(photoPicture);
        var sticker = cycledFilterPng(rgba(JitWorkload.synthesize(512, 512, 1).getFirst(), true));
        var adaptive = PngEncoder.encode(photoPicture, PngEncoderOptions.defaults());
        return JitWorkload.named("pngdec", bound, () -> {
            for (var i = 0; i < count; i++) {
                sink += PngDecoder.decode(MediaSource.of(photo), ImageDecoderOptions.defaults()).picture().width();
                sink += PngDecoder.decode(MediaSource.of(sticker), ImageDecoderOptions.defaults()).picture().width();
                sink += PngDecoder.decode(MediaSource.of(adaptive), ImageDecoderOptions.defaults()).picture().width();
            }
            return 3 * count;
        });
    }

    /**
     * Writes an 8-bit RGB (or RGBA, when the picture has alpha) PNG whose rows cycle through the
     * filter types {@code 1 2 3 4 4 3 3 0 4 2 1 4}: Sub and Up rows, Average and Paeth rows both
     * alone and in pairs, and an unfiltered row.
     */
    static byte[] cycledFilterPng(RgbaPicture picture) {
        int[] cycle = {1, 2, 3, 4, 4, 3, 3, 0, 4, 2, 1, 4};
        var width = picture.width();
        var height = picture.height();
        var bpp = picture.hasAlpha() ? 4 : 3;
        var rowBytes = width * bpp;
        var raw = new byte[height * (rowBytes + 1)];
        var previous = new byte[rowBytes];
        var current = new byte[rowBytes];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                System.arraycopy(picture.pixels(), y * picture.stride() + 4 * x, current, bpp * x, bpp);
            }
            var type = cycle[y % cycle.length];
            var o = y * (rowBytes + 1);
            raw[o] = (byte) type;
            for (var i = 0; i < rowBytes; i++) {
                var value = current[i] & 0xFF;
                var a = i >= bpp ? current[i - bpp] & 0xFF : 0;
                var b = previous[i] & 0xFF;
                var c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                var predictor = switch (type) {
                    case 1 -> a;
                    case 2 -> b;
                    case 3 -> (a + b) >> 1;
                    case 4 -> {
                        var p = a + b - c;
                        var pa = Math.abs(p - a);
                        var pb = Math.abs(p - b);
                        var pc = Math.abs(p - c);
                        yield pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                    }
                    default -> 0;
                };
                raw[o + 1 + i] = (byte) (value - predictor);
            }
            var swap = previous;
            previous = current;
            current = swap;
        }
        var deflater = new java.util.zip.Deflater(6);
        deflater.setInput(raw);
        deflater.finish();
        var compressed = new ByteArrayOutputStream();
        var buffer = new byte[65536];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 0x1A, 10});
        var header = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) (bpp == 4 ? 6 : 2)).put((byte) 0).put((byte) 0).put((byte) 0).array();
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        var name = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /**
     * Decodes and composites every frame of a 480x270 animated GIF of twelve frames, {@code count}
     * times per round, so that a round lasts long enough for the allocation of the threads of the
     * workload before it to be negligible.
     */
    static JitWorkload.Workload gifDecode(long bound, int count) {
        var gif = animatedGif(480, 270, 12);
        return JitWorkload.named("gifdec", bound, () -> {
            var frames = 0;
            for (var i = 0; i < count; i++) {
                try (var reader = GifDecoder.openAnimation(MediaSource.of(gif), ImageDecoderOptions.defaults())) {
                    for (var current = reader.read(); current != null; current = reader.read()) {
                        frames++;
                        sink += ((RgbaPicture) current.picture()).pixels()[frames * 4099 % (480 * 270 * 4)];
                    }
                }
            }
            return frames;
        });
    }

    /**
     * Converts a 1280x720 4:2:0 picture to RGBA and back, and rotates the RGBA picture a quarter
     * turn, as the image and video preparation paths do.
     */
    static JitWorkload.Workload convert(long bound, int count) {
        var yuv = JitWorkload.synthesize(1280, 720, 1).getFirst();
        var rgba = rgba(yuv, false);
        return JitWorkload.named("convert", bound, () -> {
            for (var i = 0; i < count; i++) {
                sink += ImageOps.toRgba(yuv).pixels()[4097];
                sink += ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED).y()[4097];
                sink += ImageOps.orient(rgba, Orientation.ROTATE_90).width();
            }
            return 3 * count;
        });
    }

    /**
     * Runs the effects of the image operations on a 640x360 picture: exact and box Gaussian blurs,
     * an unsharp mask, a sepia tone, compositing with two blend modes and bilinear and bicubic
     * rotations, which together exercise every method of the effects and warp kernel families.
     */
    static JitWorkload.Workload effects(long bound, int count) {
        var yuv = JitWorkload.synthesize(640, 360, 1).getFirst();
        var opaque = rgba(yuv, false);
        var mask = rgba(yuv, true);
        return JitWorkload.named("effects", bound, () -> {
            for (var i = 0; i < count; i++) {
                sink += ImageFilters.gaussianBlur(opaque, 2, BlurMethod.EXACT).width();
                sink += ImageFilters.gaussianBlur(opaque, 6, BlurMethod.BOX).width();
                sink += ImageFilters.unsharpMask(opaque, 1.5, 1.2, 2, BlurMethod.BOX).width();
                sink += ImageAdjustments.sepia(opaque).width();
                sink += ImageCompositing.composite(opaque, mask, 10, 10, CompositeOptions.of(BlendMode.SOFT_LIGHT)).width();
                sink += ImageCompositing.composite(opaque, mask, -10, 0, CompositeOptions.of(BlendMode.NORMAL)).width();
                sink += ImageOps.rotate(mask, 7, Interpolation.BILINEAR, RotationCanvas.EXPAND, 0).width();
                sink += ImageOps.rotate(opaque, -12, Interpolation.BICUBIC, RotationCanvas.KEEP, 0xFFFFFFFF).width();
            }
            return 8 * count;
        });
    }

    /**
     * Writes an animated GIF: a full first frame, then frames updating a moving 200x150 window
     * with transparent unchanged pixels, all quantized to a 6x6x6 color cube.
     */
    static byte[] animatedGif(int width, int height, int count) {
        var frames = JitWorkload.synthesize(width, height, count);
        var out = new ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        u16(out, width);
        u16(out, height);
        out.write(0xF7);
        out.write(0);
        out.write(0);
        for (var i = 0; i < 256; i++) {
            var color = i < 216 ? i : 0;
            out.write(color / 36 * 51);
            out.write(color / 6 % 6 * 51);
            out.write(color % 6 * 51);
        }
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.writeBytes("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{3, 1, 0, 0, 0});
        var previous = new byte[width * height];
        for (var t = 0; t < count; t++) {
            var picture = frames.get(t);
            var indices = new byte[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var luma = picture.y()[y * picture.yStride() + x] & 0xFF;
                    var u = picture.u()[(y >> 1) * picture.uvStride() + (x >> 1)] & 0xFF;
                    var v = picture.v()[(y >> 1) * picture.uvStride() + (x >> 1)] & 0xFF;
                    indices[y * width + x] = (byte) ((luma * 6 >> 8) * 36 + (u * 6 >> 8) * 6 + (v * 6 >> 8));
                }
            }
            var left = t == 0 ? 0 : (t * 23) % (width - 200);
            var top = t == 0 ? 0 : (t * 11) % (height - 150);
            var w = t == 0 ? width : 200;
            var h = t == 0 ? height : 150;
            var rectangle = new byte[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var index = (top + y) * width + left + x;
                    rectangle[y * w + x] = t > 0 && indices[index] == previous[index] ? (byte) 255 : indices[index];
                    previous[index] = indices[index];
                }
            }
            out.writeBytes(new byte[]{0x21, (byte) 0xF9, 4, (byte) (1 << 2 | (t > 0 ? 1 : 0)), 4, 0, (byte) 255, 0});
            out.write(0x2C);
            u16(out, left);
            u16(out, top);
            u16(out, w);
            u16(out, h);
            out.write(0);
            out.write(8);
            var data = lzw(rectangle, 8);
            for (var offset = 0; offset < data.length; offset += 255) {
                var size = Math.min(255, data.length - offset);
                out.write(size);
                out.write(data, offset, size);
            }
            out.write(0);
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
    }

    /** LZW-encodes GIF indices, emitting a clear code whenever the code table fills. */
    private static byte[] lzw(byte[] indices, int minimumCodeSize) {
        var out = new ByteArrayOutputStream();
        long bits = 0;
        var count = 0;
        var clear = 1 << minimumCodeSize;
        var codeSize = minimumCodeSize + 1;
        var maxCode = clear + 1;
        var table = new HashMap<Integer, Integer>();
        var codes = new java.util.ArrayList<int[]>(List.of(new int[]{clear, codeSize}));
        var current = -1;
        for (var value : indices) {
            var index = value & 0xFF;
            if (current < 0) {
                current = index;
                continue;
            }
            var found = table.get(current << 8 | index);
            if (found != null) {
                current = found;
                continue;
            }
            codes.add(new int[]{current, codeSize});
            table.put(current << 8 | index, ++maxCode);
            if (maxCode >= (1 << codeSize) && codeSize < 12) {
                codeSize++;
            }
            if (maxCode == 4095) {
                codes.add(new int[]{clear, codeSize});
                table.clear();
                codeSize = minimumCodeSize + 1;
                maxCode = clear + 1;
            }
            current = index;
        }
        codes.add(new int[]{current, codeSize});
        codes.add(new int[]{clear + 1, codeSize});
        for (var code : codes) {
            bits |= (long) code[0] << count;
            count += code[1];
            while (count >= 8) {
                out.write((int) bits & 0xFF);
                bits >>>= 8;
                count -= 8;
            }
        }
        if (count > 0) {
            out.write((int) bits & 0xFF);
        }
        return out.toByteArray();
    }
}
