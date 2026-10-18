package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers of the ProRes and DNxHD tests: demuxing fixtures, laying decoded pictures out
 * like ffmpeg's raw video, running ffmpeg as a decoding oracle and measuring the difference.
 */
public final class IntraTesting {
    private IntraTesting() {
    }

    public record Stream(VideoTrack track, List<Packet> packets) {
    }

    public static Stream demux(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst().orElseThrow();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            return new Stream(track, packets);
        }
    }

    /** Lays a picture out as ffmpeg's planar raw video: Y, U, V (and A), 16-bit little-endian above 8 bits. */
    public static byte[] raw(Picture picture, boolean withAlpha) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case Yuv16Picture yuv -> {
                plane16(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                plane16(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                plane16(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                if (withAlpha && yuv.alpha() != null) {
                    plane16(out, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
            }
            case YuvPicture yuv -> {
                plane8(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                plane8(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                plane8(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                if (withAlpha && yuv.alpha() != null) {
                    plane8(out, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
            }
            case Rgba16Picture rgba -> {
                // ffmpeg's gbrp order: G, B, R planes
                for (var channel : new int[]{1, 2, 0}) {
                    for (var y = 0; y < rgba.height(); y++) {
                        for (var x = 0; x < rgba.width(); x++) {
                            var v = rgba.pixels()[y * rgba.stride() + x * 4 + channel] & 0xFFFF;
                            out.write(v & 0xFF);
                            out.write(v >>> 8);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        return out.toByteArray();
    }

    /** Lays an RGBA picture out as ffmpeg's gbrp raw video of the given depth, little-endian. */
    public static byte[] rawGbr(Rgba16Picture rgba, int depth) {
        var out = new ByteArrayOutputStream();
        var max = (1 << depth) - 1;
        for (var channel : new int[]{1, 2, 0}) {
            for (var y = 0; y < rgba.height(); y++) {
                for (var x = 0; x < rgba.width(); x++) {
                    var v = (int) Math.round((rgba.pixels()[y * rgba.stride() + x * 4 + channel] & 0xFFFF) * (double) max / 65535);
                    out.write(v & 0xFF);
                    out.write(v >>> 8);
                }
            }
        }
        return out.toByteArray();
    }

    private static void plane16(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var v = plane[y * stride + x];
                out.write(v & 0xFF);
                out.write((v >>> 8) & 0xFF);
            }
        }
    }

    private static void plane8(ByteArrayOutputStream out, byte[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            out.write(plane, y * stride, width);
        }
    }

    public static byte[] ffmpegDecode(Path file, String pixelFormat, int frames) {
        var arguments = new ArrayList<>(List.of("-v", "error", "-i", file.toString(), "-map", "0:v:0"));
        if (frames > 0) {
            arguments.addAll(List.of("-frames:v", Integer.toString(frames)));
        }
        arguments.addAll(List.of("-f", "rawvideo", "-pix_fmt", pixelFormat, "-fps_mode", "passthrough", "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }

    /** Returns the PSNR of two raw buffers of samples of the given byte width and peak value. */
    public static double psnr(byte[] expected, byte[] actual, boolean wide, int peak) {
        if (expected.length != actual.length) {
            throw new AssertionError("sizes differ: " + expected.length + " vs " + actual.length);
        }
        double sum = 0;
        long count = 0;
        if (wide) {
            for (var i = 0; i < expected.length; i += 2) {
                var a = (expected[i] & 0xFF) | (expected[i + 1] & 0xFF) << 8;
                var b = (actual[i] & 0xFF) | (actual[i + 1] & 0xFF) << 8;
                sum += (double) (a - b) * (a - b);
                count++;
            }
        } else {
            for (var i = 0; i < expected.length; i++) {
                var d = (expected[i] & 0xFF) - (actual[i] & 0xFF);
                sum += d * d;
                count++;
            }
        }
        if (sum == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return 10 * Math.log10((double) peak * peak / (sum / count));
    }

    /** Returns the largest absolute sample difference of two raw buffers. */
    public static int maxDifference(byte[] expected, byte[] actual, boolean wide) {
        var max = 0;
        if (wide) {
            for (var i = 0; i + 1 < Math.min(expected.length, actual.length); i += 2) {
                var a = (expected[i] & 0xFF) | (expected[i + 1] & 0xFF) << 8;
                var b = (actual[i] & 0xFF) | (actual[i + 1] & 0xFF) << 8;
                max = Math.max(max, Math.abs(a - b));
            }
        } else {
            for (var i = 0; i < Math.min(expected.length, actual.length); i++) {
                max = Math.max(max, Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)));
            }
        }
        return max;
    }

    /** Returns the fraction of samples that differ. */
    public static double mismatchRate(byte[] expected, byte[] actual, boolean wide) {
        var step = wide ? 2 : 1;
        long differ = 0;
        long total = 0;
        for (var i = 0; i + step - 1 < Math.min(expected.length, actual.length); i += step) {
            total++;
            if (expected[i] != actual[i] || (wide && expected[i + 1] != actual[i + 1])) {
                differ++;
            }
        }
        return total == 0 ? 0 : (double) differ / total;
    }

    /**
     * Renders frames of a blend of ffmpeg's testsrc2 and mandelbrot patterns as pictures of a raw
     * pixel format: yuv422p, yuv422p10le, yuv444p10le, yuv444p12le, yuva444p12le or rgba.
     */
    public static List<Picture> sourceFrames(int width, int height, String pixelFormat, int frames) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25", "-f", "lavfi", "-i", "mandelbrot=size=320x240:rate=25",
                "-filter_complex", "[0][1]blend=all_mode=average,scale=" + width + ":" + height
                        + ":flags=bicubic,format=" + pixelFormat,
                "-frames:v", Integer.toString(frames), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        var pictures = new ArrayList<Picture>();
        var wide = pixelFormat.endsWith("le");
        var bytes = wide ? 2 : 1;
        var chroma = pixelFormat.contains("422") ? ChromaFormat.YUV422
                : pixelFormat.contains("420") ? ChromaFormat.YUV420 : ChromaFormat.YUV444;
        var alpha = pixelFormat.startsWith("yuva") || pixelFormat.equals("rgba");
        if (pixelFormat.equals("rgba")) {
            var size = width * height * 4;
            for (var f = 0; f < frames; f++) {
                var pixels = java.util.Arrays.copyOfRange(raw, f * size, (f + 1) * size);
                for (var i = 3; i < pixels.length; i += 4) {
                    pixels[i] = (byte) ((i / 4) % width * 255 / Math.max(1, width - 1));
                }
                pictures.add(com.github.auties00.smalt.image.RgbaPicture.of(width, height, width * 4, pixels, true));
            }
            return pictures;
        }
        var depth = pixelFormat.contains("12") ? 12 : pixelFormat.contains("10") ? 10 : 8;
        var chromaWidth = chroma == ChromaFormat.YUV444 ? width : (width + 1) / 2;
        var chromaHeight = chroma == ChromaFormat.YUV420 ? (height + 1) / 2 : height;
        var frameSize = (width * height * (alpha ? 2 : 1) + 2 * chromaWidth * chromaHeight) * bytes;
        for (var f = 0; f < frames; f++) {
            var at = f * frameSize;
            if (wide) {
                var y = new short[width * height];
                var u = new short[chromaWidth * chromaHeight];
                var v = new short[chromaWidth * chromaHeight];
                var a = alpha ? new short[width * height] : null;
                at = read16(raw, at, y);
                at = read16(raw, at, u);
                at = read16(raw, at, v);
                if (a != null) {
                    read16(raw, at, a);
                    // replace ffmpeg's opaque alpha with a gradient so the alpha channel carries data
                    for (var i = 0; i < a.length; i++) {
                        a[i] = (short) ((i % width) * ((1 << depth) - 1) / Math.max(1, width - 1));
                    }
                }
                pictures.add(Yuv16Picture.builder().size(width, height).chromaFormat(chroma).bitDepth(depth)
                        .color(com.github.auties00.smalt.image.ColorInfo.BT709_LIMITED)
                        .luma(y, width).chroma(u, v, chromaWidth).alpha(a, width).build());
            } else {
                var y = java.util.Arrays.copyOfRange(raw, at, at + width * height);
                at += width * height;
                var u = java.util.Arrays.copyOfRange(raw, at, at + chromaWidth * chromaHeight);
                at += chromaWidth * chromaHeight;
                var v = java.util.Arrays.copyOfRange(raw, at, at + chromaWidth * chromaHeight);
                pictures.add(YuvPicture.builder().size(width, height).chromaFormat(chroma)
                        .color(com.github.auties00.smalt.image.ColorInfo.BT709_LIMITED)
                        .luma(y, width).chroma(u, v, chromaWidth).build());
            }
        }
        return pictures;
    }

    private static int read16(byte[] raw, int at, short[] plane) {
        for (var i = 0; i < plane.length; i++, at += 2) {
            plane[i] = (short) ((raw[at] & 0xFF) | (raw[at + 1] & 0xFF) << 8);
        }
        return at;
    }

    public static String chromaName(ChromaFormat format) {
        return switch (format) {
            case YUV420 -> "420";
            case YUV422 -> "422";
            case YUV444 -> "444";
            default -> "gray";
        };
    }
}
